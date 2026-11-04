// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.RemoteException
import android.os.SystemClock
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportDiscovery
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.nearbyimport.Pkcs12StageCode
import com.weartak.plugin.sdk.nearbyimport.aidl.IWearTakPkcs12ImportControlV1
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12CancelResultParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12ImportStatusParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12ReviewResultParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12StageRequestParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12StageResultParcel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pkcs12ReviewBridgeTest {
    @After fun resetBridge() {
        Pkcs12ReviewBridge.setDiscovery(Pkcs12ImportDiscovery.Unsupported)
    }

    @Test fun `explicit stage opens wearer review and clears local bytes only after staged`() {
        val payload = byteArrayOf(1, 2, 3)
        var receivedRequest: ByteArray? = null
        var requestId: String? = null
        var reviewHandle: String? = null
        var stageCalls = 0
        val control = control(
            stage = { request ->
                stageCalls++
                requestId = request.requestId
                receivedRequest = request.pkcs12Bytes.copyOf()
                Pkcs12StageResultParcel(
                    Pkcs12StageCode.STAGED,
                    HANDLE,
                    SystemClock.elapsedRealtime() + 60_000,
                )
            },
            review = { handle ->
                reviewHandle = handle
                Pkcs12ReviewResultParcel(Pkcs12ImportState.REVIEW_PENDING)
            },
        )
        Pkcs12ReviewBridge.setDiscovery(supported(control))

        assertTrue(Pkcs12ReviewBridge.isAvailable())
        val outcome = Pkcs12ReviewBridge.stageAndRequestReview(payload)

        assertEquals(Pkcs12HandoffResult.ReviewRequested, outcome)
        assertEquals(1, stageCalls)
        assertNotNull(requestId)
        assertArrayEquals(byteArrayOf(1, 2, 3), receivedRequest)
        assertTrue(payload.all { it == 0.toByte() })
        assertEquals(HANDLE, reviewHandle)
    }

    @Test fun `stage rejection preserves bytes and never opens review`() {
        val payload = byteArrayOf(4, 5, 6)
        var reviewCalls = 0
        val control = control(
            stage = { Pkcs12StageResultParcel(Pkcs12StageCode.HOST_BUSY) },
            review = {
                reviewCalls++
                Pkcs12ReviewResultParcel(Pkcs12ImportState.REVIEW_PENDING)
            },
        )
        Pkcs12ReviewBridge.setDiscovery(supported(control))

        assertEquals(Pkcs12HandoffResult.Rejected, Pkcs12ReviewBridge.stageAndRequestReview(payload))
        assertArrayEquals(byteArrayOf(4, 5, 6), payload)
        assertEquals(0, reviewCalls)
    }

    @Test fun `unknown stage outcome preserves bytes and does not retry`() {
        val payload = byteArrayOf(7, 8, 9)
        var stageCalls = 0
        val control = control(
            stage = {
                stageCalls++
                throw RemoteException("offline")
            },
            review = { Pkcs12ReviewResultParcel(Pkcs12ImportState.REVIEW_PENDING) },
        )
        Pkcs12ReviewBridge.setDiscovery(supported(control))

        assertEquals(Pkcs12HandoffResult.Unconfirmed, Pkcs12ReviewBridge.stageAndRequestReview(payload))
        assertArrayEquals(byteArrayOf(7, 8, 9), payload)
        assertEquals(1, stageCalls)
    }

    @Test fun `review failure after confirmed staging keeps host copy and clears plugin copy`() {
        val payload = byteArrayOf(10, 11)
        val control = control(
            stage = {
                Pkcs12StageResultParcel(
                    Pkcs12StageCode.STAGED,
                    HANDLE,
                    SystemClock.elapsedRealtime() + 60_000,
                )
            },
            review = { throw RemoteException("review unavailable") },
        )
        Pkcs12ReviewBridge.setDiscovery(supported(control))

        assertEquals(
            Pkcs12HandoffResult.StagedReviewUnavailable,
            Pkcs12ReviewBridge.stageAndRequestReview(payload),
        )
        assertTrue(payload.all { it == 0.toByte() })
    }

    private fun supported(control: IWearTakPkcs12ImportControlV1) =
        Pkcs12ImportDiscovery.Supported(control, 32_700, 300_000)

    private fun control(
        stage: (Pkcs12StageRequestParcel) -> Pkcs12StageResultParcel,
        review: (String) -> Pkcs12ReviewResultParcel,
    ) = object : IWearTakPkcs12ImportControlV1.Stub() {
        override fun stagePkcs12(request: Pkcs12StageRequestParcel) = stage(request)
        override fun requestReview(handle: String) = review(handle)
        override fun getStatus(handle: String) = Pkcs12ImportStatusParcel(Pkcs12ImportState.STAGED)
        override fun cancel(handle: String) = Pkcs12CancelResultParcel(Pkcs12ImportState.CANCELLED)
    }

    companion object {
        private const val HANDLE = "abcdefghijklmnopqrstuv"
    }
}
