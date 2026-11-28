// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.weartak.plugin.nearbydrop.FixtureReceiveService.Companion.MARKER
import com.weartak.plugin.nearbydrop.FixtureReceiveService.Companion.RESULT_ACCEPTED
import com.weartak.plugin.nearbydrop.FixtureReceiveService.Companion.RESULT_BUSY
import com.weartak.plugin.nearbydrop.FixtureReceiveService.Companion.RESULT_INVALID
import com.weartak.plugin.nearbydrop.FixtureReceiveService.Companion.RESULT_NOT_FIXTURE_HOST
import com.weartak.plugin.nearbydrop.FixtureReceiveService.Companion.RESULT_RECEIVE_UNSUPPORTED
import com.weartak.plugin.nearbydrop.FixtureReceiveService.Companion.RESULT_TOO_LARGE
import com.weartak.plugin.nearbydrop.protocol.DropSession
import com.weartak.plugin.nearbydrop.protocol.DropWire
import com.weartak.plugin.sdk.serverpackage.ServerPackageDiscovery
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round")
class FixtureReceiveTest {
    private val uid = Process.myUid()
    private val fixturePackage = "com.example.weartak.nearbyfixture"
    private lateinit var service: FixtureReceiveService

    @Before fun setUp() {
        service = Robolectric.buildService(FixtureReceiveService::class.java).create().get()
        shadowOf(service.packageManager).setPackagesForUid(uid, fixturePackage)
        NearbyDropService.InitializedHost.set(uid, fixturePackage)
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(ServerPackageFixtures.FakeControl()))
    }

    @After fun tearDown() {
        FixtureHook.clear()
        NearbyDropService.InitializedHost.clear()
        ServerPackageBridge.setDiscovery(ServerPackageDiscovery.Unsupported)
    }

    @Test fun `only the initialized nearbyfixture host can deliver`() {
        NearbyDropService.InitializedHost.clear()
        assertEquals(RESULT_NOT_FIXTURE_HOST, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
        NearbyDropService.InitializedHost.set(uid, "com.atakmap.android.weartak")
        shadowOf(service.packageManager).setPackagesForUid(uid, "com.atakmap.android.weartak")
        assertEquals(RESULT_NOT_FIXTURE_HOST, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
        NearbyDropService.InitializedHost.set(uid, fixturePackage)
        shadowOf(service.packageManager).setPackagesForUid(uid, fixturePackage)
        assertEquals(RESULT_NOT_FIXTURE_HOST, deliver(ServerPackageFixtures.sharedCredentialsPackage(), caller = uid + 1))
    }

    @Test fun `marker size validity and capability are enforced`() {
        assertEquals(RESULT_INVALID, deliver(ServerPackageFixtures.sharedCredentialsPackage(), marker = "other"))
        assertEquals(RESULT_TOO_LARGE, deliver(ByteArray(DropWire.MAX_CONTENT_BYTES + 1)))
        assertEquals(RESULT_INVALID, deliver(byteArrayOf(1, 2, 3)))
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(ServerPackageFixtures.FakeControl(), receive = false))
        assertEquals(RESULT_RECEIVE_UNSUPPORTED, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
    }

    @Test fun `one pending package bound to the delivering host`() {
        assertEquals(RESULT_ACCEPTED, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
        assertEquals(RESULT_BUSY, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
        assertNull(FixtureHook.take(NearbyDropService.InitializedHost.Identity(uid, "other.nearbyfixture")))
        assertEquals(RESULT_ACCEPTED, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
        assertNotNull(FixtureHook.take(NearbyDropService.InitializedHost.current))
        assertNull(FixtureHook.take(NearbyDropService.InitializedHost.current))
    }

    @Test fun `foreground activity shows synthetic offer that still needs explicit acceptance`() {
        assertEquals(RESULT_ACCEPTED, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val texts = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
            assertTrue(texts.any { it.text.toString() == activity.getString(R.string.fixture_notice) })
            assertEquals(activity.getString(R.string.accept_offer), buttons(activity).first().text.toString())
            buttons(activity).first().performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(activity.getString(R.string.review_in_weartak), buttons(activity).first().text.toString())
            val all = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                .joinToString("\n") { it.text }
            assertFalse(all.contains(ServerPackageFixtures.SECRET_USER))
            assertFalse(all.contains(ServerPackageFixtures.SECRET_PASSWORD))
        } finally { controller.stop().destroy() }
        assertNull(FixtureHook.take(NearbyDropService.InitializedHost.current))
    }

    @Test fun `screen capture remains allowed across host changes foreground and render`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            fun secure() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            assertFalse(secure())
            val hosts = listOf(fixturePackage, "com.atakmap.android.weartak", "spoof.nearbyfixture", null)
            for (host in hosts) {
                if (host == null) {
                    NearbyDropService.InitializedHost.clear()
                } else {
                    NearbyDropService.InitializedHost.set(uid, host)
                    shadowOf(service.packageManager).setPackagesForUid(
                        uid, if (host == "spoof.nearbyfixture") fixturePackage else host,
                    )
                }
                controller.start().resume()
                assertFalse(secure())
                activity.render(NearbyTransport.ViewState(R.string.verify_code, code = "1234"))
                assertTrue(texts(activity).contains("1234"))
                assertFalse(secure())
                activity.render(NearbyTransport.ViewState(
                    R.string.connected, offeredBytes = 512, sessionState = DropSession.State.REVIEW,
                    offeredKind = DropWire.ContentKind.SERVER_PACKAGE,
                ))
                assertTrue(buttons(activity).any { it.text == activity.getString(R.string.accept_offer) })
                assertFalse(secure())
                controller.pause().stop()
            }
        } finally { controller.destroy() }
    }

    @Test fun `host review handle survives host review UI covering the plugin`() {
        val control = ServerPackageFixtures.FakeControl()
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(control))
        assertEquals(RESULT_ACCEPTED, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            await { buttons(activity).firstOrNull()?.text == activity.getString(R.string.accept_offer) }
            buttons(activity).first().performClick()
            await { buttons(activity).firstOrNull()?.text == activity.getString(R.string.review_in_weartak) }
            buttons(activity).first().performClick()
            await { control.reviewCalls == 1 && buttons(activity).firstOrNull()?.text == activity.getString(R.string.check_status) }

            // Host review UI covers the plugin: real onStop -> ActiveTransfer.detach -> transport.cancel.
            controller.pause().stop()
            await { true }
            assertEquals(0, control.cancelCalls)
            assertThrows(IllegalStateException::class.java) { activity.transportForTest().takeReceivedContent() }

            // Wearer applies in the host, then returns.
            control.statusState = com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState.APPLIED
            controller.start().resume()
            await { true }
            assertEquals(
                listOf(R.string.check_status, R.string.receive_bundle, R.string.choose_bundle, R.string.licenses).map(activity::getString),
                buttons(activity).map { it.text.toString() },
            )
            buttons(activity).first().performClick()
            await { texts(activity).contains(activity.getString(R.string.outcome_applied)) }
            assertTrue(texts(activity).contains(activity.getString(R.string.package_handoff_title)))
            assertFalse(texts(activity).contains("in memory"))
            assertFalse(texts(activity).contains("Not applied"))
            assertFalse(texts(activity).contains(activity.getString(R.string.outcome_connected)))
            assertFalse(texts(activity).contains(ServerPackageFixtures.SECRET_PASSWORD))
            assertEquals(0, control.cancelCalls)

            // Explicit new workflow ends and cancels the handle.
            buttons(activity)[1].performClick()
            await { control.cancelCalls == 1 }
            assertFalse(buttons(activity).any { it.text == activity.getString(R.string.check_status) })
        } finally { controller.pause().stop().destroy() }
        await { true }
        assertEquals(1, control.cancelCalls)
    }

    @Test fun `destroy after host review handoff cancels the handle`() {
        val control = ServerPackageFixtures.FakeControl()
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(control))
        assertEquals(RESULT_ACCEPTED, deliver(ServerPackageFixtures.sharedCredentialsPackage()))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        await { buttons(activity).firstOrNull()?.text == activity.getString(R.string.accept_offer) }
        buttons(activity).first().performClick()
        await { buttons(activity).firstOrNull()?.text == activity.getString(R.string.review_in_weartak) }
        buttons(activity).first().performClick()
        await { control.reviewCalls == 1 && buttons(activity).firstOrNull()?.text == activity.getString(R.string.check_status) }
        controller.pause().stop()
        await { true }
        assertEquals(0, control.cancelCalls)
        controller.destroy()
        await { control.cancelCalls == 1 }
    }

    private fun texts(activity: MainActivity) =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
            .joinToString("\n") { it.text }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not reached" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun deliver(bytes: ByteArray, marker: String = MARKER, caller: Int = uid): Int {
        val parcel = Parcel.obtain()
        try {
            parcel.writeString(marker)
            parcel.writeByteArray(bytes)
            parcel.setDataPosition(0)
            return service.deliver(parcel, caller)
        } finally {
            parcel.recycle()
        }
    }

    private fun buttons(activity: MainActivity) =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()

    private fun descendants(view: android.view.View): List<android.view.View> =
        listOf(view) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) } } ?: emptyList())
}
