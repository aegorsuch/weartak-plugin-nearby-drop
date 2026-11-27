// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

/** Release builds contain no synthetic fixture entry. */
internal object FixtureHook {
    @Suppress("UNUSED_PARAMETER")
    fun onForeground(activity: MainActivity) = Unit
}
