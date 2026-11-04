// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.Handler
import android.os.Looper

/**
 * Tracks the activity that owns the current transfer. Backgrounding (picker covers the screen) runs only the
 * background cleanup; the host stop stays registered until the owner is destroyed so a host
 * shutdown/stop while covered still cancels everything, including a pending host export.
 */
internal object ActiveTransfer {
    private val main = Handler(Looper.getMainLooper())
    private var owner: Any? = null
    private var background: (() -> Unit)? = null
    private var hostStop: (() -> Unit)? = null

    fun attach(owner: Any, background: () -> Unit, hostStop: () -> Unit = background) {
        if (this.owner !== owner) this.hostStop?.invoke()
        this.owner = owner
        this.background = background
        this.hostStop = hostStop
    }

    /** Owner left the foreground: background cleanup only; host stop stays registered. */
    fun detach(owner: Any) {
        if (this.owner === owner) {
            background?.invoke()
            background = null
        }
    }

    /** Owner destroyed: forget it without invoking callbacks (the owner cleans up itself). */
    fun release(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            background = null
            hostStop = null
        }
    }

    fun stopFromHost() = main.post { hostStop?.invoke() }
}
