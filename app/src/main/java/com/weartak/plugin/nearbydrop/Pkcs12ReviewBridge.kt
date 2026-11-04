// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportClient
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportDiscovery
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.nearbyimport.Pkcs12StageCode

internal sealed interface Pkcs12HandoffResult {
    data object Unsupported : Pkcs12HandoffResult
    data object Rejected : Pkcs12HandoffResult
    data object Unauthorized : Pkcs12HandoffResult
    data object Unconfirmed : Pkcs12HandoffResult
    data object ReviewRequested : Pkcs12HandoffResult
    data object StagedReviewUnavailable : Pkcs12HandoffResult
}

internal object Pkcs12ReviewBridge {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var capability: Pkcs12ImportDiscovery.Supported? = null
    private var observerOwner: Any? = null
    private var observer: (() -> Unit)? = null

    fun setDiscovery(discovery: Pkcs12ImportDiscovery) {
        capability = (discovery as? Pkcs12ImportDiscovery.Supported)
        main.post { observer?.invoke() }
    }

    fun observe(owner: Any, onChanged: () -> Unit) {
        observerOwner = owner
        observer = onChanged
        onChanged()
    }

    fun detach(owner: Any) {
        if (observerOwner === owner) {
            observerOwner = null
            observer = null
        }
    }

    fun isAvailable(): Boolean = capability != null

    fun stageAndRequestReview(bytes: ByteArray): Pkcs12HandoffResult {
        val supported = capability ?: return Pkcs12HandoffResult.Unsupported
        var staged = false
        return try {
            val result = Pkcs12ImportClient.stage(
                supported,
                Pkcs12ImportClient.newRequestId(),
                bytes,
            )
            if (result.code == Pkcs12StageCode.UNAUTHORIZED) {
                Pkcs12HandoffResult.Unauthorized
            } else if (result.code != Pkcs12StageCode.STAGED) {
                Pkcs12HandoffResult.Rejected
            } else {
                staged = true
                val handle = checkNotNull(result.handle)
                val review = supported.control.requestReview(handle)
                if (review.state in setOf(Pkcs12ImportState.REVIEW_PENDING, Pkcs12ImportState.IN_REVIEW)) {
                    Pkcs12HandoffResult.ReviewRequested
                } else {
                    Pkcs12HandoffResult.StagedReviewUnavailable
                }
            }
        } catch (_: RemoteException) {
            if (staged) Pkcs12HandoffResult.StagedReviewUnavailable else Pkcs12HandoffResult.Unconfirmed
        } catch (_: SecurityException) {
            if (staged) Pkcs12HandoffResult.StagedReviewUnavailable else Pkcs12HandoffResult.Unconfirmed
        } catch (_: IllegalArgumentException) {
            if (staged) Pkcs12HandoffResult.StagedReviewUnavailable else Pkcs12HandoffResult.Unconfirmed
        } catch (_: IllegalStateException) {
            if (staged) Pkcs12HandoffResult.StagedReviewUnavailable else Pkcs12HandoffResult.Unconfirmed
        }
    }
}
