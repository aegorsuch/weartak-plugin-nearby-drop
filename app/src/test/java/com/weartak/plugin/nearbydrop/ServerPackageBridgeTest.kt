// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import com.weartak.plugin.nearbydrop.ServerPackageFixtures.FakeControl
import com.weartak.plugin.nearbydrop.ServerPackageFixtures.HANDLE
import com.weartak.plugin.nearbydrop.ServerPackageFixtures.supported
import com.weartak.plugin.nearbydrop.protocol.DropSession
import com.weartak.plugin.nearbydrop.protocol.DropWire
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.serverpackage.ServerPackageDiscovery
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportCode
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportReason
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerPackageBridgeTest {
    @After fun reset() = ServerPackageBridge.setDiscovery(ServerPackageDiscovery.Unsupported)

    @Test fun `export waits for host picker then takes ready package once without exposing secrets`() {
        val control = FakeControl()
        ServerPackageBridge.setDiscovery(supported(control))
        assertEquals(ExportStartResult.Pending(HANDLE), ServerPackageBridge.requestExport())
        assertEquals(ExportPollResult.Pending, ServerPackageBridge.pollExport(HANDLE))
        assertEquals(0, control.takeCalls)

        control.exportPayload = ServerPackageFixtures.sharedCredentialsPackage()
        control.exportState = ServerPackageExportState.READY
        val ready = ServerPackageBridge.pollExport(HANDLE) as ExportPollResult.Ready
        assertEquals(1, control.takeCalls)
        assertTrue(ready.summary.sharedCredentials)
        assertFalse(ready.summary.sharedIdentity)
        assertEquals("tak.example", ready.summary.host)
        assertFalse(ready.summary.toString().contains(ServerPackageFixtures.SECRET_PASSWORD))
        assertFalse(ready.summary.toString().contains(ServerPackageFixtures.SECRET_USER))
    }

    @Test fun `export refusals surface host reason and never take bytes`() {
        val control = FakeControl()
        ServerPackageBridge.setDiscovery(supported(control))
        control.exportState = ServerPackageExportState.REJECTED
        for (reason in listOf(ServerPackageExportReason.TOO_LARGE, ServerPackageExportReason.NONEXPORTABLE_KEY,
                ServerPackageExportReason.MANAGED_RESTRICTED)) {
            control.exportReason = reason
            assertEquals(ExportPollResult.Refused(reason), ServerPackageBridge.pollExport(HANDLE))
        }
        control.exportState = ServerPackageExportState.EXPIRED
        assertEquals(ExportPollResult.Expired, ServerPackageBridge.pollExport(HANDLE))
        assertEquals(0, control.takeCalls)
    }

    @Test fun `export is unavailable without host export capability or when host declines start`() {
        val control = FakeControl()
        ServerPackageBridge.setDiscovery(supported(control, export = false))
        assertFalse(ServerPackageBridge.canExport())
        assertEquals(ExportStartResult.Unsupported, ServerPackageBridge.requestExport())
        ServerPackageBridge.setDiscovery(supported(control))
        control.exportCode = ServerPackageExportCode.HOST_BUSY
        assertEquals(ExportStartResult.Rejected, ServerPackageBridge.requestExport())
    }

    @Test fun `receiver stages then requests review and wipes only after staged`() {
        val control = FakeControl()
        ServerPackageBridge.setDiscovery(supported(control))
        val bytes = ServerPackageFixtures.sharedIdentityPackage()
        val original = bytes.copyOf()
        assertEquals(PackageHandoffResult.ReviewRequested(HANDLE), ServerPackageBridge.stageAndRequestReview(bytes))
        assertArrayEquals(original, control.stagedBytes)
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(1, control.reviewCalls)
    }

    @Test fun `stage rejection keeps bytes and never opens review`() {
        val control = FakeControl().apply { stageCode = 3 }
        ServerPackageBridge.setDiscovery(supported(control))
        val bytes = ServerPackageFixtures.sharedCredentialsPackage()
        val original = bytes.copyOf()
        assertEquals(PackageHandoffResult.Rejected, ServerPackageBridge.stageAndRequestReview(bytes))
        assertArrayEquals(original, bytes)
        assertEquals(0, control.reviewCalls)
    }

    @Test fun `host one-launch grant refusal is reported as unauthorized on every path`() {
        val control = FakeControl()
        ServerPackageBridge.setDiscovery(supported(control))
        control.exportCode = ServerPackageExportCode.UNAUTHORIZED
        assertEquals(ExportStartResult.Unauthorized, ServerPackageBridge.requestExport())

        control.exportState = ServerPackageExportState.REJECTED
        control.exportReason = ServerPackageExportReason.UNAUTHORIZED
        assertEquals(ExportPollResult.Refused(ServerPackageExportReason.UNAUTHORIZED), ServerPackageBridge.pollExport(HANDLE))

        control.exportState = ServerPackageExportState.READY
        control.takeCode = com.weartak.plugin.sdk.serverpackage.ServerPackageTakeCode.UNAUTHORIZED
        assertEquals(ExportPollResult.Refused(ServerPackageExportReason.UNAUTHORIZED), ServerPackageBridge.pollExport(HANDLE))

        control.stageCode = com.weartak.plugin.sdk.nearbyimport.Pkcs12StageCode.UNAUTHORIZED
        val bytes = ServerPackageFixtures.sharedCredentialsPackage()
        val original = bytes.copyOf()
        assertEquals(PackageHandoffResult.Unauthorized, ServerPackageBridge.stageAndRequestReview(bytes))
        assertArrayEquals(original, bytes)
        assertEquals(0, control.reviewCalls)
    }

    @Test fun `host status keeps saved enrolled connected and failed outcomes distinct`() {
        val control = FakeControl()
        ServerPackageBridge.setDiscovery(supported(control))
        val expected = mapOf(
            Pkcs12ImportState.IN_REVIEW to PackageOutcome.PENDING,
            Pkcs12ImportState.APPLIED to PackageOutcome.APPLIED,
            Pkcs12ImportState.ENROLLED to PackageOutcome.ENROLLED,
            Pkcs12ImportState.CONNECTED to PackageOutcome.CONNECTED,
            Pkcs12ImportState.NETWORK_FAILED to PackageOutcome.NETWORK_FAILED,
            Pkcs12ImportState.REJECTED to PackageOutcome.REJECTED,
            99 to PackageOutcome.UNKNOWN,
        )
        expected.forEach { (state, outcome) ->
            control.statusState = state
            assertEquals(outcome, ServerPackageBridge.status(HANDLE))
        }
    }

    @Test fun `content policy accepts packages only when host can stage them`() {
        val control = FakeControl()
        ServerPackageBridge.setDiscovery(supported(control, receive = false))
        assertFalse(ServerPackageBridge.policyFor(DropSession.Role.RECEIVER).supports(DropWire.ContentKind.SERVER_PACKAGE))
        assertTrue(ServerPackageBridge.policyFor(DropSession.Role.SENDER).supports(DropWire.ContentKind.SERVER_PACKAGE))
        ServerPackageBridge.setDiscovery(supported(control))
        val policy = ServerPackageBridge.policyFor(DropSession.Role.RECEIVER)
        policy.validate(DropWire.ContentKind.SERVER_PACKAGE, ServerPackageFixtures.sharedCredentialsPackage())
        assertThrows(IllegalArgumentException::class.java) {
            policy.validate(DropWire.ContentKind.SERVER_PACKAGE, byteArrayOf(1, 2, 3))
        }
    }
}
