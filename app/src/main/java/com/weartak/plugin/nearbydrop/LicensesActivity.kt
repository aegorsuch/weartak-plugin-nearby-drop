// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException

class LicensesActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var selected: String? = null
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = if (resources.configuration.isScreenRound) 24 else 20
            setPadding(dp(horizontal), dp(16), dp(horizontal), dp(16))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(content)
        }
        setContentView(scroll)
        selected = savedInstanceState?.getString("selected")
        page = savedInstanceState?.getInt("page") ?: 0
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected)
        outState.putInt("page", page)
        super.onSaveInstanceState(outState)
    }

    private fun show() {
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        val name = selected
        content.addView(TextView(this).apply {
            text = name ?: getString(R.string.licenses)
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            isAccessibilityHeading = true
            if (resources.configuration.isScreenRound) gravity = Gravity.CENTER_HORIZONTAL
        })
        try {
            if (name == null) {
                val files = listOf(
                    "LICENSE.txt", "LICENSE-ADDITIONAL-PERMISSION.txt", "THIRD_PARTY_NOTICES.txt",
                    "THIRD_PARTY_COMPONENTS.txt",
                    "WEARTAK-SDK-LICENSE.txt", "WEARTAK-SDK-LICENSE-ADDITIONAL-PERMISSION.txt",
                    "APACHE-2.0.txt",
                ) + assets.list("third_party").orEmpty().sorted().map { "third_party/$it" }
                files.forEach { path ->
                    button(path.substringAfterLast('/')) { selected = path; page = 0; show() }
                }
            } else {
                val text = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val total = ((text.length + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
                page = page.coerceIn(0, total - 1)
                content.addView(TextView(this).apply {
                    this.text = getString(R.string.license_page, page + 1, total)
                })
                content.addView(TextView(this).apply {
                    this.text = text.substring(page * PAGE_SIZE, minOf((page + 1) * PAGE_SIZE, text.length))
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                })
                if (page > 0) button(getString(R.string.license_previous)) { page--; show() }
                if (page + 1 < total) button(getString(R.string.license_next)) { page++; show() }
                button(getString(R.string.license_list)) { selected = null; show() }
            }
        } catch (_: IOException) {
            content.addView(TextView(this).apply { setText(R.string.license_error) })
            if (name != null) button(getString(R.string.license_list)) { selected = null; show() }
        }
    }

    private fun button(label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { action() }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object { const val PAGE_SIZE = 4000 }
}
