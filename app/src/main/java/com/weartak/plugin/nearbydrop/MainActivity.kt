// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.weartak.plugin.nearbydrop.protocol.DropSession
import com.weartak.plugin.nearbydrop.protocol.DropWire
import com.weartak.plugin.nearbydrop.protocol.Pkcs12Envelope
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportReason
import com.weartak.plugin.sdk.serverpackage.ServerPackageIdentityMode
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {
    private lateinit var transport: NearbyTransport
    @androidx.annotation.VisibleForTesting internal fun transportForTest() = transport
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private lateinit var code: TextView
    private lateinit var fixtureNotice: TextView
    private var selected: ByteArray? = null
    private var selectedKind = DropWire.ContentKind.PKCS12
    private var selectedSummary: ServerPackageSummary? = null
    private var sendingKind = DropWire.ContentKind.PKCS12
    private var exportHandle: String? = null
    private var exportVersion = 0
    private var exportDeadline = 0L
    /** Opaque host review handle; independent of transport bytes, kept across the host review UI. */
    private var packageReviewHandle: String? = null
    private var packageWorkflow = 0
    private var receivedSummary: ServerPackageSummary? = null
    private var exportPoll: Runnable? = null
    private var receivedBundle: ByteArray? = null
    private var currentView: NearbyTransport.ViewState? = null
    /** True while a plugin-drawn screen (entry, Send chooser, export) is shown rather than live transport state. */
    private var localScreen = false
    @Volatile private var receivedHandoffAttempted = false
    private var receivedHandoffMessage: Int? = null
    @Volatile private var handoffVersion = 0
    private var permissionAction: (() -> Unit)? = null
    @Volatile private var foreground = false
    private val reader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val openInput = AtomicReference<InputStream?>()
    private val readInFlight = AtomicBoolean()
    private var readVersion = 0
    private var readCancellation: CancellationSignal? = null
    private val readTimeout = Runnable {
        cancelRead()
        status.setText(R.string.read_failed)
        idleActions()
    }

    private val chooseFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readBundle(uri)
    }

    private fun readBundle(uri: Uri) {
        cancelRead()
        clearSelection()
        if (!readInFlight.compareAndSet(false, true)) {
            status.setText(R.string.read_failed)
            idleActions()
            return
        }
        val version = readVersion
        val cancellation = CancellationSignal()
        readCancellation = cancellation
        status.setText(R.string.reading_bundle)
        actions.removeAllViews()
        addButton(R.string.cancel) {
            cancelRead()
            status.setText(R.string.cancelled)
            idleActions()
        }
        main.postDelayed(readTimeout, 30_000)
        reader.execute {
            var bytes: ByteArray? = null
            var result = R.string.read_failed
            try {
                val descriptor = contentResolver.openAssetFileDescriptor(uri, "r", cancellation)
                    ?: throw IOException("File unavailable")
                bytes = descriptor.use { asset ->
                    asset.createInputStream().use { input ->
                        openInput.set(input)
                        val buffer = ByteArray(DropWire.MAX_CONTENT_BYTES + 1)
                        try {
                            var total = 0
                            while (total < buffer.size) {
                                cancellation.throwIfCanceled()
                                val read = input.read(buffer, total, buffer.size - total)
                                if (read == -1) break
                                if (read == 0) throw IOException("File read stalled")
                                total += read
                            }
                            buffer.copyOf(total)
                        } finally {
                            buffer.fill(0)
                            openInput.compareAndSet(input, null)
                        }
                    }
                }
                Pkcs12Envelope.validate(bytes)
                result = R.string.selected_bundle_hint
            } catch (_: IllegalArgumentException) {
                result = R.string.invalid_bundle
            } catch (_: IOException) {
                result = R.string.read_failed
            } catch (_: SecurityException) {
                result = R.string.read_failed
            } catch (_: OperationCanceledException) {
                result = R.string.cancelled
            } finally {
                readInFlight.set(false)
            }
            val content = bytes
            val message = result
            main.post {
                try {
                    if (version == readVersion && !cancellation.isCanceled && !isDestroyed) {
                        main.removeCallbacks(readTimeout)
                        readCancellation = null
                        if (message == R.string.selected_bundle_hint && content != null) {
                            selected = content.copyOf()
                            status.text = resources.getQuantityString(R.plurals.selected_bundle, content.size, content.size)
                        } else {
                            status.setText(message)
                        }
                        idleActions()
                    }
                } finally {
                    content?.fill(0)
                }
            }
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = permissionAction
        permissionAction = null
        if (requiredPermissions().all(::granted)) {
            if (foreground && action != null) action() else {
                status.setText(R.string.permissions_ready)
                idleActions()
            }
        } else {
            status.setText(R.string.permission_denied)
            idleActions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        transport = NearbyTransport(this, ::render, policy = ServerPackageBridge::policyFor)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = if (resources.configuration.isScreenRound) 24 else 20
            setPadding(dp(horizontal), dp(16), dp(horizontal), dp(16))
        }
        val round = resources.configuration.isScreenRound
        content.addView(TextView(this).apply {
            tag = "title"
            setText(R.string.app_name)
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            isAccessibilityHeading = true
            // The top of a round display is narrowest; centre the title so the circular mask does not clip it.
            if (round) gravity = Gravity.CENTER_HORIZONTAL
        })
        fixtureNotice = TextView(this).apply {
            tag = "fixture_notice"
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, 0, 0, dp(8))
            visibility = View.GONE
        }
        content.addView(fixtureNotice)
        status = TextView(this).apply {
            setText(R.string.idle)
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(status)
        code = TextView(this).apply {
            tag = "authentication_code"
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
            visibility = View.GONE
        }
        content.addView(code)
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(actions)
        content.addView(TextView(this).apply {
            setText(R.string.foundation_notice)
            setPadding(0, dp(12), 0, 0)
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(content)
        })
        idleActions()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        Pkcs12ReviewBridge.observe(this) {
            currentView?.let(::render)
        }
        ServerPackageBridge.observe(this) {
            currentView?.let(::render)
        }
        ActiveTransfer.attach(this, background = ::stopBackgroundWork, hostStop = {
            val exporting = exportHandle != null
            endPackageHandoff()
            stopBackgroundWork()
            cancelExport()
            if (exporting && !isDestroyed) exportEnded(R.string.export_cancelled)
        })
        // The host picker covers this screen; resume polling only once the wearer is back.
        exportHandle?.let {
            exportWaiting()
            pollExport(it, exportVersion)
        }
        FixtureHook.onForeground(this)
    }

    /**
     * Synthetic receive entry used only by the debug-only fixture hook. The release build ships a no-op
     * [FixtureHook], so nothing reaches this method there.
     */
    internal fun startFixtureReceive(content: ByteArray, notice: CharSequence) {
        try {
            if (!foreground) return
            endPackageHandoff()
            cancelRead()
            cancelExport()
            clearSelection()
            clearReceivedBundle()
            fixtureNotice.text = notice
            fixtureNotice.visibility = View.VISIBLE
            localScreen = false
            transport.startLoopbackReceive(content, DropWire.ContentKind.SERVER_PACKAGE)
        } finally {
            content.fill(0)
        }
    }

    override fun onStop() {
        foreground = false
        permissionAction = null
        Pkcs12ReviewBridge.detach(this)
        ServerPackageBridge.detach(this)
        handoffVersion++
        clearReceivedBundle()
        ActiveTransfer.detach(this)
        clearSelection()
        fixtureNotice.visibility = View.GONE
        super.onStop()
    }

    /** Leaving the foreground wipes reads/selection/transfer; a pending host export survives the host picker. */
    private fun stopBackgroundWork() {
        cancelRead()
        clearSelection()
        clearReceivedBundle()
        transport.cancel()
    }

    override fun onDestroy() {
        ActiveTransfer.release(this)
        endPackageHandoff()
        cancelRead()
        cancelExport(destroying = true)
        Pkcs12ReviewBridge.detach(this)
        ServerPackageBridge.detach(this)
        clearReceivedBundle()
        reader.shutdownNow()
        super.onDestroy()
    }

    internal fun render(view: NearbyTransport.ViewState) {
        currentView = view
        FixtureHook.applyWindowSecurity(this)
        val isPackage = view.offeredKind == DropWire.ContentKind.SERVER_PACKAGE
        if (view.sessionState != DropSession.State.RECEIVED && packageReviewHandle != null &&
            view.code == null && view.sessionState != DropSession.State.REVIEW && view.peers.isEmpty()
        ) {
            renderPackageHandoff()
            return
        }
        // A finished/cancelled transport state (e.g. replayed by a capability refresh) must not replace a
        // screen the plugin drew itself, such as the Send chooser after an export was backed out.
        val terminal = view.message in TERMINAL_MESSAGES && view.code == null && view.peers.isEmpty() &&
            view.sessionState != DropSession.State.REVIEW && view.sessionState != DropSession.State.RECEIVED
        if (localScreen && terminal) return
        localScreen = false
        if (view.sessionState != DropSession.State.RECEIVED) {
            receivedHandoffAttempted = false
            receivedHandoffMessage = null
            receivedSummary = null
        }
        status.text = when {
            view.code != null -> getString(view.message)
            view.sessionState == DropSession.State.REVIEW && isPackage ->
                resources.getQuantityString(R.plurals.review_offer_package, view.offeredBytes, view.offeredBytes)
            view.sessionState == DropSession.State.REVIEW ->
                resources.getQuantityString(R.plurals.review_offer, view.offeredBytes, view.offeredBytes)
            view.sessionState == DropSession.State.RECEIVED && isPackage -> receivedPackageText()
            view.sessionState == DropSession.State.RECEIVED ->
                getString(receivedHandoffMessage ?: if (Pkcs12ReviewBridge.isAvailable()) {
                    if (receivedHandoffAttempted) R.string.received else R.string.received_review_available
                } else R.string.received)
            view.message == R.string.offering && sendingKind == DropWire.ContentKind.SERVER_PACKAGE ->
                getString(R.string.offering_package)
            else -> getString(view.message)
        }
        code.text = view.code.orEmpty()
        code.contentDescription = view.code?.let { getString(R.string.verification_code, it.toCharArray().joinToString(" ")) }
        code.visibility = if (view.code == null) View.GONE else View.VISIBLE
        actions.removeAllViews()
        when {
            view.code != null -> addButton(R.string.confirm_code) { transport.confirmPeer() }
            view.sessionState == DropSession.State.REVIEW -> addButton(R.string.accept_offer) { transport.acceptOffer() }
            view.sessionState == DropSession.State.RECEIVED && isPackage &&
                ServerPackageBridge.canReceive() && !receivedHandoffAttempted ->
                addButton(R.string.review_in_weartak) { stageReceivedPackage() }
            view.sessionState == DropSession.State.RECEIVED && isPackage && packageReviewHandle != null ->
                addButton(R.string.check_status) { checkPackageStatus() }
            view.sessionState == DropSession.State.RECEIVED && !isPackage &&
                Pkcs12ReviewBridge.isAvailable() && !receivedHandoffAttempted ->
                addButton(R.string.review_in_weartak) { stageReceivedBundle() }
        }
        view.peers.forEach { endpoint ->
            addButtonText(getString(R.string.connect_peer, endpoint)) { transport.connect(endpoint) }
        }
        if (view.message in TERMINAL_MESSAGES) {
            idleActions()
        } else {
            addButton(R.string.cancel) { endPackageHandoff(); transport.cancel(); clearSelection(); clearReceivedBundle() }
        }
    }

    /**
     * Host owns the staged package; transport bytes are already gone. Shows the last known host outcome with
     * Check status, then the normal entry actions (which end this workflow explicitly).
     */
    private fun renderPackageHandoff() {
        status.text = listOf(
            getString(R.string.package_handoff_title),
            getString(receivedHandoffMessage ?: R.string.package_review_requested),
        ).joinToString("\n")
        code.text = ""
        code.contentDescription = null
        code.visibility = View.GONE
        idleActions()
        if (selected == null) {
            val before = actions.childCount
            addButton(R.string.check_status) { checkPackageStatus() }
            actions.getChildAt(before)?.let {
                actions.removeView(it)
                actions.addView(it, 0)
            }
        }
    }

    /** Explicit end of the package workflow: forget and cancel the opaque host handle. */
    private fun endPackageHandoff(): Boolean {
        packageWorkflow++
        val handle = packageReviewHandle ?: return false
        packageReviewHandle = null
        receivedHandoffAttempted = false
        receivedHandoffMessage = null
        receivedSummary = null
        Thread({ ServerPackageBridge.cancelReview(handle) }, "nearbydrop-review-cancel").start()
        return true
    }

    private fun stageReceivedBundle() {
        if (!foreground || receivedHandoffAttempted || !Pkcs12ReviewBridge.isAvailable()) return
        val content = receivedBundle ?: try {
            transport.takeReceivedContent().also { receivedBundle = it }
        } catch (_: IllegalStateException) {
            status.setText(R.string.review_stage_unconfirmed)
            return
        }
        receivedHandoffAttempted = true
        receivedHandoffMessage = R.string.review_staging
        status.setText(R.string.review_staging)
        currentView?.let(::render)
        val version = ++handoffVersion
        reader.execute {
            if (!foreground || version != handoffVersion) return@execute
            val result = Pkcs12ReviewBridge.stageAndRequestReview(content)
            main.post {
                if (version != handoffVersion || isDestroyed) return@post
                receivedHandoffMessage = when (result) {
                    Pkcs12HandoffResult.Unsupported -> R.string.received
                    Pkcs12HandoffResult.Rejected -> R.string.review_stage_rejected
                    Pkcs12HandoffResult.Unauthorized -> R.string.review_stage_unauthorized
                    Pkcs12HandoffResult.Unconfirmed -> R.string.review_stage_unconfirmed
                    Pkcs12HandoffResult.ReviewRequested -> R.string.review_requested
                    Pkcs12HandoffResult.StagedReviewUnavailable -> R.string.review_open_failed
                }
                if (result == Pkcs12HandoffResult.ReviewRequested ||
                    result == Pkcs12HandoffResult.StagedReviewUnavailable
                ) clearReceivedBundle()
                status.setText(checkNotNull(receivedHandoffMessage))
                currentView?.let(::render)
            }
        }
    }

    private fun stageReceivedPackage() {
        if (!foreground || receivedHandoffAttempted || !ServerPackageBridge.canReceive()) return
        val content = receivedBundle ?: run {
            status.setText(R.string.package_stage_unconfirmed)
            return
        }
        receivedHandoffAttempted = true
        receivedHandoffMessage = R.string.review_staging
        currentView?.let(::render)
        val version = ++handoffVersion
        val workflow = packageWorkflow
        reader.execute {
            if (!foreground || version != handoffVersion) return@execute
            val result = ServerPackageBridge.stageAndRequestReview(content)
            main.post {
                // The host review UI may already cover (stop) this activity; only an explicit workflow end or
                // destroy discards the result, and then any staged handle is cancelled.
                val handle = (result as? PackageHandoffResult.ReviewRequested)?.handle
                    ?: (result as? PackageHandoffResult.StagedReviewUnavailable)?.handle
                if (workflow != packageWorkflow || isDestroyed) {
                    handle?.let { Thread({ ServerPackageBridge.cancelReview(it) }, "nearbydrop-review-cancel").start() }
                    return@post
                }
                receivedHandoffMessage = when (result) {
                    PackageHandoffResult.Unsupported -> R.string.package_received_no_review
                    PackageHandoffResult.Rejected -> R.string.package_stage_rejected
                    PackageHandoffResult.Unauthorized -> R.string.package_stage_unauthorized
                    PackageHandoffResult.Unconfirmed -> R.string.package_stage_unconfirmed
                    is PackageHandoffResult.ReviewRequested -> R.string.package_review_requested
                    is PackageHandoffResult.StagedReviewUnavailable -> R.string.package_review_open_failed
                }
                if (handle != null) packageReviewHandle = handle
                // The host owns the bytes once STAGED was confirmed.
                if (packageReviewHandle != null) clearReceivedBundle()
                currentView?.let(::render)
            }
        }
    }

    private fun checkPackageStatus() {
        val handle = packageReviewHandle ?: return
        if (!foreground) return
        val workflow = packageWorkflow
        reader.execute {
            val outcome = ServerPackageBridge.status(handle)
            main.post {
                if (workflow != packageWorkflow || isDestroyed || packageReviewHandle != handle) return@post
                receivedHandoffMessage = when (outcome) {
                    PackageOutcome.PENDING -> R.string.outcome_pending
                    PackageOutcome.REJECTED -> R.string.outcome_rejected
                    PackageOutcome.CANCELLED -> R.string.outcome_cancelled
                    PackageOutcome.EXPIRED -> R.string.outcome_expired
                    PackageOutcome.APPLIED -> R.string.outcome_applied
                    PackageOutcome.ENROLLED -> R.string.outcome_enrolled
                    PackageOutcome.CONNECTED -> R.string.outcome_connected
                    PackageOutcome.NETWORK_FAILED -> R.string.outcome_network_failed
                    PackageOutcome.UNKNOWN -> R.string.outcome_unknown
                }
                currentView?.let(::render)
            }
        }
    }

    private fun receivedPackageText(): String {
        if (receivedBundle == null && !receivedHandoffAttempted) {
            try {
                receivedBundle = transport.takeReceivedContent()
                receivedSummary = receivedBundle?.let(ServerPackageSummary::describe)
            } catch (_: IllegalStateException) {
                receivedSummary = null
            }
        }
        val lines = mutableListOf(getString(
            if (receivedBundle == null || receivedHandoffMessage != null) R.string.package_handoff_title
            else R.string.package_received))
        receivedSummary?.let { lines += packageDetails(it) }
        when {
            receivedHandoffMessage != null -> getString(receivedHandoffMessage!!)
            receivedBundle == null -> getString(R.string.package_stage_unconfirmed)
            ServerPackageBridge.canReceive() -> null
            else -> getString(R.string.package_received_no_review)
        }?.let { lines += it }
        return lines.joinToString("\n")
    }

    /** Names only which item types are present; secret values are never rendered. */
    internal fun packageDetails(summary: ServerPackageSummary): List<String> = buildList {
        add(getString(R.string.package_summary, summary.name, summary.host, summary.port))
        add(if (summary.privateCaCount > 0) {
            resources.getQuantityString(R.plurals.package_trust_private, summary.privateCaCount, summary.privateCaCount)
        } else getString(R.string.package_trust_public))
        add(getString(when (summary.identityMode) {
            ServerPackageIdentityMode.SHARED_IDENTITY -> R.string.package_identity_shared
            ServerPackageIdentityMode.ADMIN_PKCS12_SEPARATE -> R.string.package_identity_admin
            else -> if (summary.sharedCredentials) R.string.package_identity_enroll_shared else R.string.package_identity_enroll
        }))
        if (summary.sharedIdentity) add(getString(R.string.package_sensitive_identity))
        if (summary.sharedCredentials) add(getString(R.string.package_sensitive_credentials))
        if (summary.sharedIdentity || summary.sharedCredentials) add(getString(R.string.package_sensitive_transport))
        add(getString(R.string.package_no_device_identity))
    }

    private fun sendChoices() {
        localScreen = true
        cancelExport()
        clearSelection()
        transport.cancel()
        status.setText(R.string.send_choice)
        actions.removeAllViews()
        addButton(R.string.send_server_package) { startExport() }
        addButton(R.string.send_bundle_file) { openBundlePicker() }
        addButton(R.string.cancel) {
            status.setText(R.string.idle)
            idleActions()
        }
    }

    private fun startExport() {
        if (!foreground || exportHandle != null) return
        val version = ++exportVersion
        localScreen = true
        status.setText(R.string.export_starting)
        actions.removeAllViews()
        reader.execute {
            val result = ServerPackageBridge.requestExport()
            main.post {
                if (version != exportVersion || isDestroyed) {
                    (result as? ExportStartResult.Pending)?.let { ServerPackageBridge.cancelExport(it.handle) }
                    return@post
                }
                when (result) {
                    is ExportStartResult.Pending -> {
                        exportHandle = result.handle
                        exportDeadline = SystemClock.elapsedRealtime() + EXPORT_WAIT_MS
                        exportWaiting()
                        if (foreground) pollExport(result.handle, version)
                    }
                    ExportStartResult.Unsupported, ExportStartResult.Rejected -> exportEnded(R.string.export_unavailable)
                    ExportStartResult.Unauthorized -> exportEnded(R.string.export_unauthorized)
                    ExportStartResult.Unconfirmed -> exportEnded(R.string.export_unconfirmed)
                }
            }
        }
    }

    private fun exportWaiting() {
        localScreen = true
        status.setText(R.string.export_waiting)
        actions.removeAllViews()
        addButton(R.string.cancel) {
            cancelExport()
            status.setText(R.string.cancelled)
            idleActions()
        }
    }

    private fun pollExport(handle: String, version: Int) {
        exportPoll?.let(main::removeCallbacks)
        exportPoll = Runnable {
            if (version != exportVersion || exportHandle != handle || !foreground || isDestroyed) return@Runnable
            if (SystemClock.elapsedRealtime() >= exportDeadline) {
                cancelExport()
                exportEnded(R.string.export_expired)
                return@Runnable
            }
            reader.execute {
                val result = ServerPackageBridge.pollExport(handle)
                main.post { onExportPolled(handle, version, result) }
            }
        }.also { main.postDelayed(it, EXPORT_POLL_MS) }
    }

    private fun onExportPolled(handle: String, version: Int, result: ExportPollResult) {
        if (version != exportVersion || exportHandle != handle || isDestroyed || !foreground) {
            (result as? ExportPollResult.Ready)?.encoded?.fill(0)
            return
        }
        when (result) {
            ExportPollResult.Pending -> {
                exportWaiting()
                pollExport(handle, version)
            }
            is ExportPollResult.Ready -> {
                exportHandle = null
                clearSelection()
                selected = result.encoded
                selectedKind = DropWire.ContentKind.SERVER_PACKAGE
                selectedSummary = result.summary
                status.text = packageDetails(result.summary).joinToString("\n")
                idleActions()
            }
            is ExportPollResult.Refused -> exportEnded(when (result.reason) {
                ServerPackageExportReason.USER_DECLINED -> R.string.export_declined
                ServerPackageExportReason.NONEXPORTABLE_KEY -> R.string.export_nonexportable
                ServerPackageExportReason.MANAGED_RESTRICTED -> R.string.export_managed
                ServerPackageExportReason.NO_ELIGIBLE_SERVER -> R.string.export_no_server
                ServerPackageExportReason.TOO_LARGE -> R.string.export_too_large
                ServerPackageExportReason.UNAUTHORIZED -> R.string.export_unauthorized
                else -> R.string.export_failed
            })
            ExportPollResult.Cancelled -> exportEnded(R.string.export_cancelled)
            ExportPollResult.Expired -> exportEnded(R.string.export_expired)
            ExportPollResult.Unconfirmed -> {
                cancelExport()
                exportEnded(R.string.export_unconfirmed)
            }
        }
    }

    private fun exportEnded(message: Int) {
        exportHandle = null
        exportVersion++
        status.setText(message)
        idleActions()
    }

    private fun cancelExport(destroying: Boolean = false) {
        exportPoll?.let(main::removeCallbacks)
        val handle = exportHandle ?: return
        exportHandle = null
        exportVersion++
        // reader is shut down with shutdownNow() on destroy, which would drop a queued cancel.
        if (destroying) {
            Thread({ ServerPackageBridge.cancelExport(handle) }, "nearbydrop-export-cancel").start()
        } else {
            reader.execute { ServerPackageBridge.cancelExport(handle) }
        }
    }

    private fun openBundlePicker() {
        try {
            chooseFile.launch(arrayOf("application/x-pkcs12"))
        } catch (_: ActivityNotFoundException) {
            status.setText(R.string.no_document_picker)
            idleActions()
        }
    }

    private fun idleActions() {
        localScreen = true
        actions.removeAllViews()
        if (selected != null) {
            val kind = selectedKind
            addButton(if (kind == DropWire.ContentKind.SERVER_PACKAGE) R.string.send_package else R.string.send_bundle) {
                withPermissions {
                    selected?.let {
                        sendingKind = kind
                        localScreen = false
                        transport.startSending(it, kind)
                    }
                    clearSelection()
                }
            }
            addButton(R.string.cancel) {
                clearSelection()
                transport.cancel()
                status.setText(R.string.idle)
                idleActions()
            }
            return
        }
        addButton(R.string.receive_bundle) {
            if (endPackageHandoff()) {
                status.setText(R.string.idle)
                idleActions()
            }
            cancelExport()
            clearSelection()
            withPermissions {
                localScreen = false
                transport.startReceiving()
            }
        }
        addButton(R.string.choose_bundle) {
            if (endPackageHandoff()) {
                status.setText(R.string.idle)
                idleActions()
            }
            if (ServerPackageBridge.canExport()) {
                sendChoices()
            } else {
                transport.cancel()
                clearSelection()
                openBundlePicker()
            }
        }
        addButton(R.string.licenses) {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
    }

    private fun withPermissions(action: () -> Unit) {
        if (requiredPermissions().all(::granted)) action() else {
            permissionAction = action
            permissions.launch(requiredPermissions())
        }
    }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun addButton(label: Int, action: () -> Unit) = addButtonText(getString(label), action)
    private fun addButtonText(label: String, action: () -> Unit) {
        actions.addView(Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
            setOnClickListener { action() }
        })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun clearSelection() {
        selected?.fill(0)
        selected = null
        selectedKind = DropWire.ContentKind.PKCS12
        selectedSummary = null
    }
    private fun clearReceivedBundle() {
        receivedBundle?.fill(0)
        receivedBundle = null
    }
    private fun cancelRead() {
        readVersion++
        readCancellation?.cancel()
        readCancellation = null
        try {
            openInput.getAndSet(null)?.close()
        } catch (_: IOException) {
            Log.w("NearbyDrop", "Could not close selected document")
        }
        main.removeCallbacks(readTimeout)
    }

    private companion object {
        const val EXPORT_POLL_MS = 1_000L
        const val EXPORT_WAIT_MS = 5 * 60_000L
        val TERMINAL_MESSAGES = setOf(R.string.cancelled, R.string.failed, R.string.nearby_failed,
            R.string.delivered, R.string.invalid_bundle, R.string.play_services_unavailable)
    }
}
