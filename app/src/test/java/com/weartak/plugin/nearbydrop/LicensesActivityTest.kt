// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round")
class LicensesActivityTest {
    @Test fun `bundled notice is readable and paginated`() {
        val controller = Robolectric.buildActivity(LicensesActivity::class.java).create()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            fun nodes() = descendants(root)
            nodes().filterIsInstance<Button>().single { it.text == "APACHE-2.0.txt" }.performClick()
            assertTrue(nodes().filterIsInstance<TextView>().any { it.text.contains("Apache License") })
            assertTrue(nodes().filterIsInstance<TextView>().any { it.text.contains("Page 1 of") })
            nodes().filterIsInstance<Button>().single { it.text == activity.getString(R.string.license_next) }.performClick()
            assertTrue(nodes().filterIsInstance<TextView>().any { it.text.contains("Page 2 of") })
            nodes().filterIsInstance<Button>().single { it.text == activity.getString(R.string.license_list) }.performClick()
            assertTrue(nodes().filterIsInstance<Button>().any { it.text == "APACHE-2.0.txt" })
        } finally { controller.destroy() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
