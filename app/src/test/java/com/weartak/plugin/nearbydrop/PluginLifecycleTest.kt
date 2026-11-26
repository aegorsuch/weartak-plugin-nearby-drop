// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.SettingsApplyMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginLifecycleTest {
    @Test fun `wifi state permission remains declared on modern Android`() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(android.Manifest.permission.ACCESS_WIFI_STATE))
    }

    @Test fun `service is discoverable by host default-only query`() {
        val context = RuntimeEnvironment.getApplication()
        val services = context.packageManager.queryIntentServices(
            Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(services.any {
            it.serviceInfo.packageName == context.packageName &&
                it.serviceInfo.name == NearbyDropService::class.java.name &&
                it.serviceInfo.permission == WearTakPluginContract.PERMISSION_BIND_PLUGIN
        })
    }

    @Test fun `service requires correct bind action and declares only external main UI`() {
        val controller = Robolectric.buildService(NearbyDropService::class.java).create()
        try {
            val service = controller.get()
            assertNull(service.onBind(Intent("wrong.action")))
            assertNotNull(service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE)))
            val capabilities = service.plugin.getCapabilities()
            assertTrue(capabilities.externalMainActivity)
            assertFalse(capabilities.hostedMainUi)
            assertFalse(capabilities.hostedSettingsUi)
            assertFalse(capabilities.deviceIntegration)
            assertFalse(capabilities.cotReadyEvents)
        } finally { controller.destroy() }
    }

    @Test fun `host lifecycle validated and cannot restart after shutdown`() {
        val controller = Robolectric.buildService(NearbyDropService::class.java).create()
        try {
            val service = controller.get()
            service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE))
            val plugin = service.plugin
            assertThrows(IllegalStateException::class.java) { plugin.startPlugin() }
            plugin.initializePluginV2(
                PluginInitParcel(service.packageName, Binder.getCallingUid(), NearbyDropService.PLUGIN_ID),
                IWearTakPluginControl.Default(), null,
            )
            assertEquals(PluginLifecycleState.READY, plugin.getStatus().state)
            plugin.startPlugin()
            assertEquals(PluginLifecycleState.RUNNING, plugin.getStatus().state)
            plugin.stopPlugin()
            assertEquals(PluginLifecycleState.STOPPED, plugin.getStatus().state)
            val result = plugin.applySettingsWithResult(Bundle().apply { putString("password", "forbidden") }, SettingsApplyMode.PARTIAL_PATCH)
            assertFalse(result.success)
            assertFalse(result.errors.isEmpty())
            plugin.shutdownPlugin()
            assertEquals(PluginLifecycleState.SHUTDOWN, plugin.getStatus().state)
            assertThrows(IllegalStateException::class.java) { plugin.startPlugin() }
        } finally { controller.destroy() }
    }

    @Test fun `initialization rejects mismatched caller and plugin id`() {
        val controller = Robolectric.buildService(NearbyDropService::class.java).create()
        try {
            val service = controller.get()
            service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE))
            assertThrows(IllegalArgumentException::class.java) {
                service.plugin.initializePluginV2(
                    PluginInitParcel(service.packageName, Binder.getCallingUid() + 1, NearbyDropService.PLUGIN_ID),
                    IWearTakPluginControl.Default(), null,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.plugin.initializePluginV2(
                    PluginInitParcel(service.packageName, Binder.getCallingUid(), "wrong.plugin"),
                    IWearTakPluginControl.Default(), null,
                )
            }
            assertEquals(PluginLifecycleState.BOUND, service.plugin.getStatus().state)
        } finally { controller.destroy() }
    }

    @Test fun `v3 initialization keeps lifecycle checks and does not imply PCM capability`() {
        val controller = Robolectric.buildService(NearbyDropService::class.java).create()
        try {
            val service = controller.get()
            service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE))
            val plugin = service.plugin
            plugin.initializePluginV3(
                PluginInitParcel(service.packageName, Binder.getCallingUid(), NearbyDropService.PLUGIN_ID),
                IWearTakPluginControl.Default(),
                null,
                null,
            )
            assertEquals(PluginLifecycleState.READY, plugin.getStatus().state)
            assertEquals(WearTakPluginContract.CONTRACT_API_VERSION, plugin.getApiVersion())
            assertFalse(plugin.getCapabilities().deviceIntegration)
            plugin.startPlugin()
            assertEquals(PluginLifecycleState.RUNNING, plugin.getStatus().state)
        } finally { controller.destroy() }
    }
}
