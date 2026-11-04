// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.SystemClock
import android.view.WindowManager

/**
 * Debug-only synthetic receive path for the offline `.nearbyfixture` simulator demo. Not a product API.
 * One synthetic package is held in memory for a short time, then handed to the real receiver session
 * through an in-memory loopback; no Nearby radio is used.
 */
internal object FixtureHook {
    const val LIFETIME_MS = 60_000L

    private var pending: ByteArray? = null
    private var owner: NearbyDropService.InitializedHost.Identity? = null
    private var expiresAt = 0L

    @Synchronized
    fun offer(bytes: ByteArray, host: NearbyDropService.InitializedHost.Identity): Boolean {
        if (pending != null && SystemClock.elapsedRealtime() < expiresAt) {
            bytes.fill(0)
            return false
        }
        clear()
        pending = bytes
        owner = host
        expiresAt = SystemClock.elapsedRealtime() + LIFETIME_MS
        return true
    }

    @Synchronized
    fun take(current: NearbyDropService.InitializedHost.Identity?): ByteArray? {
        val valid = pending != null && current != null && current == owner &&
            SystemClock.elapsedRealtime() < expiresAt
        if (!valid) {
            clear()
            return null
        }
        return pending.also {
            pending = null
            owner = null
        }
    }

    @Synchronized
    fun clear() {
        pending?.fill(0)
        pending = null
        owner = null
        expiresAt = 0L
    }

    /**
     * Allows screen capture only while the initialized, signature-bound host is the verified
     * `.nearbyfixture` simulator package; every other state keeps FLAG_SECURE.
     */
    fun applyWindowSecurity(activity: MainActivity) {
        val host = NearbyDropService.InitializedHost.current
        val fixture = host != null &&
            host.packageName.endsWith(FixtureReceiveService.FIXTURE_PACKAGE_SUFFIX) &&
            activity.packageManager.getPackagesForUid(host.uid)?.contains(host.packageName) == true
        if (fixture) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun onForeground(activity: MainActivity) {
        applyWindowSecurity(activity)
        val bytes = take(NearbyDropService.InitializedHost.current) ?: return
        if (!ServerPackageBridge.canReceive()) {
            bytes.fill(0)
            return
        }
        activity.startFixtureReceive(bytes, activity.getString(R.string.fixture_notice))
    }
}
