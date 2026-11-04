// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import com.weartak.plugin.nearbydrop.protocol.ContentPolicy
import com.weartak.plugin.nearbydrop.protocol.DropSession
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.nearbyimport.Pkcs12StageCode
import com.weartak.plugin.sdk.serverpackage.ServerPackage
import com.weartak.plugin.sdk.serverpackage.ServerPackageClient
import com.weartak.plugin.sdk.serverpackage.ServerPackageCodec
import com.weartak.plugin.sdk.serverpackage.ServerPackageDecodeResult
import com.weartak.plugin.sdk.serverpackage.ServerPackageDiscovery
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportCode
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportReason
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportState
import com.weartak.plugin.sdk.serverpackage.ServerPackageSensitiveFlags
import com.weartak.plugin.sdk.serverpackage.ServerPackageTakeCode
import com.weartak.plugin.sdk.serverpackage.ServerPackageTakeResult
import com.weartak.plugin.sdk.serverpackage.ServerPackageTrustMode

/** Non-secret description of a package, safe to show on the secure plugin window. */
internal data class ServerPackageSummary(
    val name: String,
    val host: String,
    val port: Int,
    val privateCaCount: Int,
    val identityMode: Int,
    val sharedIdentity: Boolean,
    val sharedCredentials: Boolean,
) {
    companion object {
        fun of(pkg: ServerPackage) = ServerPackageSummary(
            name = pkg.name,
            host = pkg.host,
            port = pkg.port,
            privateCaCount = if (pkg.trustMode == ServerPackageTrustMode.PRIVATE_CA) pkg.caCertificates.size else 0,
            identityMode = pkg.identityMode,
            sharedIdentity = pkg.sensitiveFlags and ServerPackageSensitiveFlags.CLIENT_IDENTITY != 0,
            sharedCredentials = pkg.sensitiveFlags and ServerPackageSensitiveFlags.SHARED_CREDENTIALS != 0,
        )

        /** Decodes only to describe the package; every decoded secret copy is wiped before returning. */
        fun describe(bytes: ByteArray): ServerPackageSummary? =
            when (val decoded = ServerPackageCodec.decode(bytes)) {
                is ServerPackageDecodeResult.Valid -> try {
                    of(decoded.serverPackage)
                } finally {
                    decoded.serverPackage.wipe()
                }
                is ServerPackageDecodeResult.Invalid -> null
            }
    }
}

internal sealed interface ExportStartResult {
    data object Unsupported : ExportStartResult
    data object Rejected : ExportStartResult
    /** Host requires a fresh wearer launch from WearTAK for each export. */
    data object Unauthorized : ExportStartResult
    data object Unconfirmed : ExportStartResult
    data class Pending(val handle: String) : ExportStartResult
}

internal sealed interface ExportPollResult {
    data object Pending : ExportPollResult
    class Ready(val encoded: ByteArray, val summary: ServerPackageSummary) : ExportPollResult
    data class Refused(val reason: Int) : ExportPollResult
    data object Cancelled : ExportPollResult
    data object Expired : ExportPollResult
    data object Unconfirmed : ExportPollResult
}

internal sealed interface PackageHandoffResult {
    data object Unsupported : PackageHandoffResult
    data object Rejected : PackageHandoffResult
    data object Unauthorized : PackageHandoffResult
    data object Unconfirmed : PackageHandoffResult
    data class ReviewRequested(val handle: String) : PackageHandoffResult
    data class StagedReviewUnavailable(val handle: String) : PackageHandoffResult
}

/** Host-reported outcome; only APPLIED or later means the host saved anything. */
internal enum class PackageOutcome { PENDING, REJECTED, CANCELLED, EXPIRED, APPLIED, ENROLLED, CONNECTED, NETWORK_FAILED, UNKNOWN }

internal object ServerPackageBridge {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var capability: ServerPackageDiscovery.Supported? = null
    private var observerOwner: Any? = null
    private var observer: (() -> Unit)? = null

    fun setDiscovery(discovery: ServerPackageDiscovery) {
        capability = discovery as? ServerPackageDiscovery.Supported
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

    fun canExport() = capability?.supportsExport == true
    fun canReceive() = capability?.supportsReceive == true

    /** Server packages are offered or accepted only when this host can export or stage them. */
    fun policyFor(role: DropSession.Role): ContentPolicy {
        val enabled = if (role == DropSession.Role.SENDER) canExport() else canReceive()
        return if (enabled) PACKAGE_POLICY else ContentPolicy.PKCS12_ONLY
    }

    private val PACKAGE_POLICY = ContentPolicy { bytes ->
        ServerPackageCodec.validate(bytes)?.let { throw IllegalArgumentException("Invalid server package") }
    }

    /** Explicit wearer tap only: opens the host-owned server picker and sensitive-item confirmation. */
    fun requestExport(): ExportStartResult {
        val supported = capability?.takeIf { it.supportsExport } ?: return ExportStartResult.Unsupported
        return try {
            val result = ServerPackageClient.requestExport(supported, ServerPackageClient.newRequestId())
            if (result.code == ServerPackageExportCode.PENDING) {
                ExportStartResult.Pending(checkNotNull(result.handle))
            } else if (result.code == ServerPackageExportCode.UNAUTHORIZED) {
                ExportStartResult.Unauthorized
            } else {
                ExportStartResult.Rejected
            }
        } catch (_: RemoteException) {
            ExportStartResult.Unconfirmed
        } catch (_: SecurityException) {
            ExportStartResult.Unconfirmed
        } catch (_: IllegalArgumentException) {
            ExportStartResult.Unconfirmed
        } catch (_: IllegalStateException) {
            ExportStartResult.Unconfirmed
        }
    }

    fun pollExport(handle: String): ExportPollResult {
        val supported = capability?.takeIf { it.supportsExport } ?: return ExportPollResult.Unconfirmed
        return try {
            val status = supported.control.getExportStatus(handle)
            when (status.state) {
                ServerPackageExportState.PENDING -> ExportPollResult.Pending
                ServerPackageExportState.READY -> take(supported, handle)
                ServerPackageExportState.CANCELLED -> ExportPollResult.Cancelled
                ServerPackageExportState.EXPIRED -> ExportPollResult.Expired
                ServerPackageExportState.REJECTED -> ExportPollResult.Refused(status.errorCode)
                else -> ExportPollResult.Unconfirmed
            }
        } catch (_: RemoteException) {
            ExportPollResult.Unconfirmed
        } catch (_: SecurityException) {
            ExportPollResult.Unconfirmed
        } catch (_: IllegalArgumentException) {
            ExportPollResult.Unconfirmed
        }
    }

    private fun take(supported: ServerPackageDiscovery.Supported, handle: String): ExportPollResult =
        when (val taken = ServerPackageClient.takeExport(supported, handle)) {
            is ServerPackageTakeResult.Ready -> try {
                ExportPollResult.Ready(taken.encodedPackage, ServerPackageSummary.of(taken.serverPackage))
            } finally {
                taken.serverPackage.wipe()
            }
            is ServerPackageTakeResult.HostCode -> if (taken.code == ServerPackageTakeCode.UNAUTHORIZED) {
                ExportPollResult.Refused(ServerPackageExportReason.UNAUTHORIZED)
            } else ExportPollResult.Unconfirmed
            is ServerPackageTakeResult.Invalid -> ExportPollResult.Refused(ServerPackageExportReason.FAILED)
        }

    fun cancelExport(handle: String) {
        val supported = capability ?: return
        try {
            supported.control.cancelExport(handle)
        } catch (_: RemoteException) {
        } catch (_: SecurityException) {
        }
    }

    /** Ends a staged review handle; the host ignores it once the review reached a terminal state. */
    fun cancelReview(handle: String) {
        val supported = capability ?: return
        try {
            supported.control.cancel(handle)
        } catch (_: RemoteException) {
        } catch (_: SecurityException) {
        }
    }

    /** Explicit wearer tap only, after transfer receipt. STAGED is host admission, not review or apply. */
    fun stageAndRequestReview(bytes: ByteArray): PackageHandoffResult {
        val supported = capability?.takeIf { it.supportsReceive } ?: return PackageHandoffResult.Unsupported
        var handle: String? = null
        return try {
            val result = ServerPackageClient.stage(supported, ServerPackageClient.newRequestId(), bytes)
            if (result.code == Pkcs12StageCode.UNAUTHORIZED) {
                PackageHandoffResult.Unauthorized
            } else if (result.code != Pkcs12StageCode.STAGED) {
                PackageHandoffResult.Rejected
            } else {
                val staged = checkNotNull(result.handle)
                handle = staged
                val review = supported.control.requestReview(staged)
                if (review.state in setOf(Pkcs12ImportState.REVIEW_PENDING, Pkcs12ImportState.IN_REVIEW)) {
                    PackageHandoffResult.ReviewRequested(staged)
                } else {
                    PackageHandoffResult.StagedReviewUnavailable(staged)
                }
            }
        } catch (_: RemoteException) {
            afterFailure(handle)
        } catch (_: SecurityException) {
            afterFailure(handle)
        } catch (_: IllegalArgumentException) {
            afterFailure(handle)
        } catch (_: IllegalStateException) {
            afterFailure(handle)
        }
    }

    private fun afterFailure(handle: String?) =
        handle?.let { PackageHandoffResult.StagedReviewUnavailable(it) } ?: PackageHandoffResult.Unconfirmed

    fun status(handle: String): PackageOutcome {
        val supported = capability ?: return PackageOutcome.UNKNOWN
        return try {
            when (supported.control.getStatus(handle).state) {
                Pkcs12ImportState.STAGED, Pkcs12ImportState.REVIEW_PENDING, Pkcs12ImportState.IN_REVIEW -> PackageOutcome.PENDING
                Pkcs12ImportState.REJECTED -> PackageOutcome.REJECTED
                Pkcs12ImportState.CANCELLED -> PackageOutcome.CANCELLED
                Pkcs12ImportState.EXPIRED -> PackageOutcome.EXPIRED
                Pkcs12ImportState.APPLIED -> PackageOutcome.APPLIED
                Pkcs12ImportState.ENROLLED -> PackageOutcome.ENROLLED
                Pkcs12ImportState.CONNECTED -> PackageOutcome.CONNECTED
                Pkcs12ImportState.NETWORK_FAILED -> PackageOutcome.NETWORK_FAILED
                else -> PackageOutcome.UNKNOWN
            }
        } catch (_: RemoteException) {
            PackageOutcome.UNKNOWN
        } catch (_: SecurityException) {
            PackageOutcome.UNKNOWN
        }
    }
}
