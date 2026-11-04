// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveTransferTest {
    @Test fun `host stop targets current activity and stale activity cannot detach it`() {
        val firstOwner = Any()
        val currentOwner = Any()
        var firstStops = 0
        var currentStops = 0
        try {
            ActiveTransfer.attach(firstOwner, background = { firstStops++ })
            ActiveTransfer.attach(currentOwner, background = { currentStops++ })
            assertEquals(1, firstStops)

            ActiveTransfer.detach(firstOwner)
            assertEquals(0, currentStops)

            ActiveTransfer.stopFromHost()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, currentStops)
        } finally {
            ActiveTransfer.detach(currentOwner)
            ActiveTransfer.release(currentOwner)
        }
        assertEquals(2, currentStops)
    }

    @Test fun `background detach keeps host stop registered until release`() {
        val owner = Any()
        var backgrounds = 0
        var hostStops = 0
        try {
            ActiveTransfer.attach(owner, background = { backgrounds++ }, hostStop = { hostStops++ })
            ActiveTransfer.detach(owner)
            assertEquals(1, backgrounds)
            assertEquals(0, hostStops)

            ActiveTransfer.stopFromHost()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, hostStops)
            assertEquals(1, backgrounds)
        } finally {
            ActiveTransfer.release(owner)
        }
        ActiveTransfer.stopFromHost()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, hostStops)
    }
}
