// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import com.weartak.plugin.nearbydrop.protocol.ContentPolicy
import com.weartak.plugin.nearbydrop.protocol.DropSession
import com.weartak.plugin.nearbydrop.protocol.DropWire

internal class NearbyTransport(
    private val context: Context,
    private val update: (ViewState) -> Unit,
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context),
    private val policy: (DropSession.Role) -> ContentPolicy = { ContentPolicy.PKCS12_ONLY },
    private val available: () -> Boolean = {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    },
) {
    data class ViewState(
        @StringRes val message: Int,
        val code: String? = null,
        val offeredBytes: Int = 0,
        val peers: List<String> = emptyList(),
        val sessionState: DropSession.State? = null,
        val offeredKind: DropWire.ContentKind? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var pendingTasks = 0
    private var active = false
    private var peer: String? = null
    private var session: DropSession? = null
    private var loopbackSender: DropSession? = null
    private var verified = false
    private var discovery = false
    private var startedAt = 0L
    private val peers = linkedSetOf<String>()
    private val outbound = mutableMapOf<Long, ByteArray>()
    private val timer = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.elapsedRealtime() - startedAt >= DropSession.TIMEOUT_MS) {
                session?.fail() ?: fail()
                return
            }
            session?.tick()
            if (active) main.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION")
    fun startSending(content: ByteArray, kind: DropWire.ContentKind = DropWire.ContentKind.PKCS12) {
        if (!begin()) return
        val token = generation
        try {
            session = newSession(DropSession.Role.SENDER, content, token, kind)
        } catch (_: IllegalArgumentException) {
            close()
            update(ViewState(R.string.invalid_bundle))
            return
        }
        update(ViewState(R.string.offering))
        api(token) {
            client.startAdvertising(
                DropWire.ENDPOINT_NAME, DropWire.SERVICE_ID, lifecycle(token),
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT)
                    .setDisruptiveUpgrade(false).setLowPower(true).build(),
            )
        }
    }

    fun startReceiving() {
        if (!begin()) return
        discovery = true
        val token = generation
        update(ViewState(R.string.searching))
        api(token) {
            client.startDiscovery(
                DropWire.SERVICE_ID,
                object : EndpointDiscoveryCallback() {
                    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) = dispatch(token) {
                        if (!discovery || peer != null || info.serviceId != DropWire.SERVICE_ID ||
                            info.endpointName != DropWire.ENDPOINT_NAME || endpointId.length > 64) return@dispatch
                        if (peers.size < MAX_PEERS) peers.add(endpointId)
                        update(ViewState(R.string.searching, peers = peers.toList()))
                    }
                    override fun onEndpointLost(endpointId: String) = dispatch(token) {
                        peers.remove(endpointId)
                        if (peer == null) update(ViewState(R.string.searching, peers = peers.toList()))
                    }
                },
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).setLowPower(true).build(),
            )
        }
    }

    @Suppress("DEPRECATION")
    fun connect(endpointId: String) {
        if (!guardActive() || !discovery || peer != null || endpointId !in peers) return
        peer = endpointId
        discovery = false
        client.stopDiscovery()
        peers.clear()
        val token = generation
        session = newSession(DropSession.Role.RECEIVER, null, token)
        update(ViewState(R.string.connecting))
        api(token) {
            client.requestConnection(
                DropWire.ENDPOINT_NAME, endpointId, lifecycle(token),
                ConnectionOptions.Builder().setDisruptiveUpgrade(false).setLowPower(true).build(),
            )
        }
    }

    fun confirmPeer() {
        val current = session ?: return
        if (!guardActive() || current.state != DropSession.State.VERIFYING || verified) return
        current.confirmPeer()
        verified = true
        update(ViewState(R.string.waiting_peer))
        val token = generation
        api(token) { client.acceptConnection(checkNotNull(peer), payloads(token)) }
    }

    fun acceptOffer() {
        val current = session ?: return
        if (guardActive() && current.state == DropSession.State.REVIEW) current.acceptOffer()
    }

    fun takeReceivedContent(): ByteArray {
        check(guardActive() && session?.state == DropSession.State.RECEIVED) {
            "No received bundle available for explicit host handoff"
        }
        return checkNotNull(session).takeReceivedContent()
    }

    fun cancel() {
        session?.cancel() ?: close()
        session = null
        update(ViewState(R.string.cancelled))
    }

    private fun begin(): Boolean {
        if (!available()) {
            update(ViewState(R.string.play_services_unavailable))
            return false
        }
        if (pendingTasks != 0) {
            update(ViewState(R.string.nearby_failed))
            return false
        }
        cancel()
        generation++
        active = true
        startedAt = SystemClock.elapsedRealtime()
        main.postDelayed(timer, 1000)
        return true
    }

    private fun lifecycle(token: Int) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            main.post {
                if (token != generation || !guardActive()) {
                    try {
                        client.rejectConnection(endpointId).addOnFailureListener {
                            Log.w(TAG, "Stale connection rejection failed")
                        }
                    } catch (_: SecurityException) {
                        Log.w(TAG, "Stale connection rejection permissions unavailable")
                    }
                } else {
                    initiated(endpointId, info, token)
                }
            }
        }

        private fun initiated(endpointId: String, info: ConnectionInfo, token: Int) {
            if ((peer != null && peer != endpointId) || info.endpointName != DropWire.ENDPOINT_NAME ||
                info.rawAuthenticationToken?.isNotEmpty() != true ||
                !info.authenticationDigits.matches(Regex("[0-9]{4}")) ||
                session?.state != DropSession.State.CONNECTING) {
                api(token) { client.rejectConnection(endpointId) }
                return
            }
            peer = endpointId
            client.stopAdvertising()
            client.stopDiscovery()
            session?.verifying()
            update(ViewState(R.string.verify_code, code = info.authenticationDigits))
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) = dispatch(token) {
            if (endpointId != peer) return@dispatch
            if (!result.status.isSuccess || !verified || session?.state != DropSession.State.VERIFYING) {
                fail()
            } else {
                session?.connected()
            }
        }

        override fun onDisconnected(endpointId: String) = dispatch(token) {
            if (endpointId == peer) session?.disconnected()
        }
    }

    private fun payloads(token: Int) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes()
            main.post {
                if (payload.type != Payload.Type.BYTES || bytes == null) {
                    cancelPayload(payload.id)
                    cleanupUnexpectedPayload(payload)
                    if (token == generation && active) fail()
                } else {
                    try {
                        if (token == generation && active && endpointId == peer) {
                            session?.receive(bytes)
                        }
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, transfer: PayloadTransferUpdate) = dispatch(token) {
            if (endpointId != peer) return@dispatch
            when (transfer.status) {
                PayloadTransferUpdate.Status.SUCCESS -> outbound.remove(transfer.payloadId)?.fill(0)
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    outbound.remove(transfer.payloadId)?.fill(0)
                    fail()
                }
            }
        }
    }

    private fun newSession(
        role: DropSession.Role,
        content: ByteArray?,
        token: Int,
        kind: DropWire.ContentKind = DropWire.ContentKind.PKCS12,
    ) = DropSession(
        role = role, now = SystemClock::elapsedRealtime, content = content,
        contentKind = kind, policy = policy(role),
        send = { bytes ->
            if (token != generation || !active || peer == null || outbound.size >= 4) {
                bytes.fill(0)
                fail()
            } else {
                val payload = Payload.fromBytes(bytes)
                outbound[payload.id] = bytes
                api(token) { client.sendPayload(checkNotNull(peer), payload) }
            }
        },
        closeTransport = ::close,
        changed = ::publish,
    )

    /**
     * Debug-fixture path only: runs the real OFFER/ACCEPT/DATA/RECEIPT exchange between two in-memory
     * sessions. No Nearby advertising, discovery or connection is started; the receiver still must
     * explicitly accept the offer before data is delivered.
     */
    internal fun startLoopbackReceive(content: ByteArray, kind: DropWire.ContentKind): Boolean {
        cancel()
        generation++
        active = true
        startedAt = SystemClock.elapsedRealtime()
        main.postDelayed(timer, 1000)
        val token = generation
        var sender: DropSession? = null
        val relay = { target: () -> DropSession?, bytes: ByteArray ->
            main.post {
                try {
                    if (token == generation && active) target()?.receive(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            Unit
        }
        val receiver = DropSession(
            role = DropSession.Role.RECEIVER, now = SystemClock::elapsedRealtime,
            policy = policy(DropSession.Role.RECEIVER),
            send = { relay({ sender }, it) },
            closeTransport = ::close,
            changed = ::publish,
        )
        session = receiver
        sender = try {
            DropSession(
                role = DropSession.Role.SENDER, now = SystemClock::elapsedRealtime, content = content,
                contentKind = kind, policy = policy(DropSession.Role.RECEIVER),
                send = { relay({ session }, it) },
                closeTransport = {},
                changed = {},
            )
        } catch (_: IllegalArgumentException) {
            close()
            session = null
            update(ViewState(R.string.invalid_bundle))
            return false
        }
        loopbackSender = sender
        receiver.verifying()
        sender.verifying()
        receiver.confirmPeer()
        sender.confirmPeer()
        verified = true
        receiver.connected()
        sender.connected()
        return true
    }

    private fun publish(state: DropSession.State) {
        val message = when (state) {
            DropSession.State.CONNECTING -> R.string.connecting
            DropSession.State.VERIFYING -> R.string.waiting_peer
            DropSession.State.CONNECTED -> R.string.connected
            DropSession.State.WAITING_ACCEPTANCE -> R.string.waiting_acceptance
            DropSession.State.REVIEW -> R.string.connected
            DropSession.State.WAITING_DATA -> R.string.waiting_data
            DropSession.State.WAITING_RECEIPT -> R.string.waiting_receipt
            DropSession.State.RECEIVED -> R.string.received
            DropSession.State.DELIVERED -> R.string.delivered
            DropSession.State.CANCELLED -> R.string.cancelled
            DropSession.State.FAILED -> R.string.failed
        }
        update(ViewState(message, offeredBytes = session?.offeredBytes ?: 0, sessionState = state,
            offeredKind = session?.offeredKind))
    }

    private fun dispatch(token: Int, block: () -> Unit) {
        main.post { if (token == generation && guardActive()) block() }
    }

    private fun guardActive(): Boolean {
        if (!active) return false
        if (SystemClock.elapsedRealtime() - startedAt >= DropSession.TIMEOUT_MS) {
            session?.fail() ?: fail()
            return false
        }
        return true
    }

    private fun api(token: Int, operation: () -> Task<*>) {
        try {
            val task = operation()
            pendingTasks++
            task.addOnCompleteListener { result ->
                pendingTasks--
                if (token != generation || !active) {
                    stopRadios()
                } else if (!result.isSuccessful) {
                    Log.w(TAG, "Nearby operation failed")
                    fail()
                }
            }
        } catch (_: SecurityException) {
            Log.w(TAG, "Nearby permissions unavailable")
            fail()
        }
    }

    private fun fail() {
        session?.fail() ?: close()
        update(ViewState(R.string.nearby_failed))
    }

    private fun close() {
        active = false
        generation++
        loopbackSender?.let { sender ->
            loopbackSender = null
            sender.cancel()
        }
        main.removeCallbacks(timer)
        stopRadios()
        outbound.keys.forEach(::cancelPayload)
        outbound.values.forEach { it.fill(0) }
        outbound.clear()
        peers.clear()
        peer = null
        discovery = false
        verified = false
    }

    private fun stopRadios() {
        for (stop in listOf(client::stopAdvertising, client::stopDiscovery, client::stopAllEndpoints)) {
            try {
                stop()
            } catch (_: SecurityException) {
                Log.w(TAG, "Nearby cleanup permissions unavailable")
            }
        }
    }

    private fun cancelPayload(id: Long) {
        try {
            client.cancelPayload(id).addOnFailureListener {
                Log.w(TAG, "Nearby payload cancellation failed")
            }
        } catch (_: SecurityException) {
            Log.w(TAG, "Nearby payload cancellation permissions unavailable")
        }
    }

    @Suppress("DEPRECATION")
    private fun cleanupUnexpectedPayload(payload: Payload) {
        payload.asStream()?.close()
        if (payload.type != Payload.Type.FILE) return
        val file = payload.asFile() ?: return
        val uri = file.asUri()
        file.close()
        if (uri == null || uri.scheme != "content") {
            Log.w(TAG, "Unsupported incoming file has no deletable scoped URI")
            return
        }
        try {
            // This is the exact incoming object supplied by Nearby, never a peer-provided path.
            if (context.contentResolver.delete(uri, null, null) == 0) {
                Log.w(TAG, "Could not clear unsupported incoming file")
            }
        } catch (_: SecurityException) {
            Log.w(TAG, "Could not clear unsupported incoming file")
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Unsupported incoming file provider")
        }
    }

    companion object {
        private const val MAX_PEERS = 20
        private const val TAG = "NearbyDrop"
    }
}
