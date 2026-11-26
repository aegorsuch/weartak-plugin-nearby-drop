// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition
import com.weartak.plugin.sdk.model.*
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportClient
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportDiscovery
import com.weartak.plugin.sdk.serverpackage.ServerPackageClient
import com.weartak.plugin.sdk.serverpackage.ServerPackageDiscovery
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.PluginMetadataValidator
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.WearTakPluginV3Contract
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3

class NearbyDropService : Service() {
    private val lifecycle = PluginLifecycleMachine()
    private var callback: IWearTakHostCallback? = null
    private var hostUid: Int? = null
    private var hostControlV3: IWearTakPluginHostControlV3? = null

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != WearTakPluginContract.ACTION_PLUGIN_SERVICE) return null
        transition(PluginLifecycleEvent.BIND_ATTACHED)
        return plugin
    }

    override fun onDestroy() {
        ActiveTransfer.stopFromHost()
        resetImportBridges()
        InitializedHost.clear()
        callback = null
        hostUid = null
        hostControlV3 = null
        lifecycle.dispatch(PluginLifecycleEvent.RESET)
        super.onDestroy()
    }

    internal val plugin = object : IWearTakPlugin.Stub() {
        override fun getPluginInfo() = PluginInfoParcel(
            PLUGIN_ID, getString(R.string.app_name), BuildConfig.VERSION_NAME,
            BuildConfig.APPLICATION_ID, getString(R.string.plugin_description),
        )
        override fun getCapabilities() = PluginCapabilitiesParcel(externalMainActivity = true)
        override fun getApiVersion() = WearTakPluginV3Contract.CONTRACT_API_VERSION
        override fun getStatus() = PluginStatusParcel(lifecycle.snapshot(), getString(R.string.host_ready))

        override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) =
            initializePluginV2(init, control, null)

        @Synchronized
        override fun initializePluginV3(
            init: PluginInitParcel?,
            control: IWearTakPluginControl?,
            controlV2: IWearTakPluginHostControlV2?,
            controlV3: IWearTakPluginHostControlV3?,
        ) {
            initializePluginV2(init, control, controlV2)
            hostControlV3 = controlV3
            discoverImportBridges(controlV3)
        }

        @Synchronized
        override fun initializePluginV2(init: PluginInitParcel?, control: IWearTakPluginControl?, controlV2: IWearTakPluginHostControlV2?) {
            requireNotNull(init) { "Missing host identity" }
            requireNotNull(control) { "Missing host control" }
            require(PluginMetadataValidator.validateInitAgainstPlugin(init, PLUGIN_ID) is ContractValidationResult.Ok) {
                "Invalid plugin identity"
            }
            val caller = Binder.getCallingUid()
            require(init.hostUid == caller &&
                packageManager.getPackagesForUid(caller)?.contains(init.hostPackageName) == true) { "Invalid host caller" }
            check(!lifecycle.isShutdown()) { "Plugin shut down" }
            hostUid = caller
            InitializedHost.set(caller, checkNotNull(init.hostPackageName))
            hostControlV3 = null
            transition(PluginLifecycleEvent.INITIALIZED)
            resetImportBridges()
        }

        override fun startPlugin() {
            verifyHost()
            discoverImportBridges(hostControlV3)
            transition(PluginLifecycleEvent.HOST_STARTED)
        }
        override fun stopPlugin() {
            verifyHost()
            resetImportBridges()
            ActiveTransfer.stopFromHost()
            transition(PluginLifecycleEvent.HOST_STOPPED)
        }
        override fun shutdownPlugin() {
            verifyHost()
            resetImportBridges()
            ActiveTransfer.stopFromHost()
            transition(PluginLifecycleEvent.HOST_SHUTDOWN)
            InitializedHost.clear()
            hostControlV3 = null
            callback = null
        }
        override fun registerHostCallback(value: IWearTakHostCallback?) {
            // Host registers its callback before initialization in the standard binding flow.
            check(!lifecycle.isShutdown()) { "Plugin shut down" }
            callback = value
            transition(PluginLifecycleEvent.HOST_CALLBACK_REGISTERED)
        }
        override fun unregisterHostCallback(value: IWearTakHostCallback?) {
            verifyHost()
            if (value?.asBinder() == callback?.asBinder()) callback = null
        }
        override fun getSettingsSchema() = SettingsSchemaParcel(title = getString(R.string.app_name))
        override fun getSettingsValues() = Bundle()
        override fun applySettings(values: Bundle?) {
            val result = applySettingsWithResult(values, SettingsApplyMode.PARTIAL_PATCH)
            require(result.success) { "No settings supported" }
        }
        override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
            verifyHost()
            val valid = values?.isEmpty != false && applyMode in setOf(
                SettingsApplyMode.PARTIAL_PATCH, SettingsApplyMode.FULL_REPLACEMENT, SettingsApplyMode.HOST_DRIVEN_RECOMPUTE,
            )
            return SettingsApplyResultParcel(
                success = valid, applyMode = applyMode,
                errors = if (valid) emptyList() else listOf(
                    SettingsValidationErrorParcel("", "UNSUPPORTED", getString(R.string.no_settings)),
                ),
            )
        }
        override fun onHostSettingsChanged(settings: HostSettingsParcel?) { verifyHost() }
        override fun getMainUiSchema() = PluginMainUiSchemaParcel()
        override fun getMainUiState() = Bundle()
        override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
            verifyHost()
            throw IllegalArgumentException("No hosted actions supported")
        }
    }

    private fun verifyHost() {
        check(hostUid == Binder.getCallingUid() && !lifecycle.isShutdown()) { "Host not initialized" }
    }

    private fun transition(event: PluginLifecycleEvent) {
        check(lifecycle.dispatch(event) !is PluginLifecycleTransition.Rejected) { "Invalid plugin lifecycle" }
        try {
            callback?.onPluginStatusChanged(plugin.getStatus())
        } catch (_: RemoteException) {
            Log.w(TAG, "Host callback unavailable")
            callback = null
            ActiveTransfer.stopFromHost()
        }
    }

    companion object {
        const val PLUGIN_ID = "com.weartak.plugin.nearbydrop"
        private const val TAG = "NearbyDropService"
    }

    /** Caller identity verified during the most recent successful host initialization. */
    internal object InitializedHost {
        data class Identity(val uid: Int, val packageName: String)

        @Volatile var current: Identity? = null
            private set

        fun set(uid: Int, packageName: String) {
            current = Identity(uid, packageName)
        }

        fun clear() {
            current = null
        }
    }

    private fun discoverImportBridges(control: IWearTakPluginHostControlV3?) {
        Pkcs12ReviewBridge.setDiscovery(Pkcs12ImportClient.discover(control))
        ServerPackageBridge.setDiscovery(ServerPackageClient.discover(control))
    }

    private fun resetImportBridges() {
        Pkcs12ReviewBridge.setDiscovery(Pkcs12ImportDiscovery.Unsupported)
        ServerPackageBridge.setDiscovery(ServerPackageDiscovery.Unsupported)
    }
}