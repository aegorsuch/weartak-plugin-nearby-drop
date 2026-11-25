// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportDiscovery
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.nearbyimport.Pkcs12StageCode
import com.weartak.plugin.sdk.nearbyimport.aidl.IWearTakPkcs12ImportControlV1
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12CancelResultParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12ImportStatusParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12ReviewResultParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12StageRequestParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12StageResultParcel
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round")
class MainActivityTest {
    @After fun resetBridge() {
        Pkcs12ReviewBridge.setDiscovery(Pkcs12ImportDiscovery.Unsupported)
    }

    @Test fun `verification digits remain distinct accessible and before confirmation on round watch`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.render(NearbyTransport.ViewState(R.string.verify_code, code = "1234"))
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val nodes = descendants(root)
            val code = nodes.filterIsInstance<TextView>().single { it.tag == "authentication_code" }
            val confirm = nodes.filterIsInstance<Button>().single { it.text == activity.getString(R.string.confirm_code) }
            assertEquals("1234", code.text.toString())
            assertEquals(View.VISIBLE, code.visibility)
            assertTrue(code.contentDescription.toString().contains("1 2 3 4"))
            assertTrue(nodes.indexOf(code) < nodes.indexOf(confirm))
            val pixels = (192 * activity.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, pixels, pixels)
            assertTrue(confirm.measuredHeight >= 48 * activity.resources.displayMetrics.density)
        } finally { controller.destroy() }
    }

    @Test fun `round watch scroll keeps receive action reachable and touch sized`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val pixels = (192 * activity.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, pixels, pixels)

            val scroll = descendants(root).filterIsInstance<ScrollView>().single()
            val receive = descendants(root).filterIsInstance<Button>().single {
                it.text == activity.getString(R.string.receive_bundle)
            }
            assertTrue(receive.minimumHeight >= 48 * activity.resources.displayMetrics.density)
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
            )
            scroll.layout(0, 0, pixels, pixels)
            val receiveBounds = Rect(0, 0, receive.width, receive.height)
            scroll.offsetDescendantRectToMyCoords(receive, receiveBounds)
            val targetScrollY = (receiveBounds.bottom - scroll.height).coerceAtLeast(0)
            assertTrue(targetScrollY <= scroll.getChildAt(0).height - scroll.height)
            scroll.scrollTo(0, targetScrollY)
            assertTrue(receiveBounds.top >= scroll.scrollY)
            assertTrue(receiveBounds.bottom <= scroll.scrollY + scroll.height)
            assertTrue(receive.height >= 48 * activity.resources.displayMetrics.density)

        } finally { controller.destroy() }
    }

    @Test fun `entry screen includes notices after transfer actions`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val buttons = descendants(root).filterIsInstance<Button>().map { it.text.toString() }
            assertEquals(listOf("Receive", "Send", activity.getString(R.string.licenses)), buttons)
            descendants(root).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.licenses) }.performClick()
            assertEquals(LicensesActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        } finally { controller.destroy() }
    }

    @Test fun `offer review exposes acceptance and cancellation but no enrollment action`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.render(NearbyTransport.ViewState(
                R.string.connected,
                offeredBytes = 640,
                sessionState = com.weartak.plugin.nearbydrop.protocol.DropSession.State.REVIEW,
            ))
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val buttons = descendants(root).filterIsInstance<Button>().map { it.text.toString() }
            assertEquals(
                listOf(activity.getString(R.string.accept_offer), activity.getString(R.string.cancel)),
                buttons,
            )
            val status = descendants(root).filterIsInstance<TextView>().single {
                it.text.toString().contains("640")
            }
            assertTrue(status.text.toString().contains("Nothing will be imported"))
        } finally { controller.destroy() }
    }

    @Test fun `received screen omits review action when host capability is unsupported`() {
        Pkcs12ReviewBridge.setDiscovery(Pkcs12ImportDiscovery.Unsupported)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.render(NearbyTransport.ViewState(
                R.string.received, sessionState = com.weartak.plugin.nearbydrop.protocol.DropSession.State.RECEIVED,
            ))
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val buttons = descendants(root).filterIsInstance<Button>().map { it.text.toString() }
            assertEquals(listOf(activity.getString(R.string.cancel)), buttons)
            assertTrue(descendants(root).filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.received)
            })
        } finally { controller.destroy() }
    }

    @Test fun `received screen offers explicit wearer review only with supported capability`() {
        var stageCalls = 0
        Pkcs12ReviewBridge.setDiscovery(Pkcs12ImportDiscovery.Supported(
            object : IWearTakPkcs12ImportControlV1.Stub() {
                override fun stagePkcs12(request: Pkcs12StageRequestParcel): Pkcs12StageResultParcel {
                    stageCalls++
                    return Pkcs12StageResultParcel(Pkcs12StageCode.HOST_BUSY)
                }
                override fun requestReview(handle: String) =
                    Pkcs12ReviewResultParcel(Pkcs12ImportState.REVIEW_PENDING)
                override fun getStatus(handle: String) =
                    Pkcs12ImportStatusParcel(Pkcs12ImportState.STAGED)
                override fun cancel(handle: String) =
                    Pkcs12CancelResultParcel(Pkcs12ImportState.CANCELLED)
            },
            32_700,
            300_000,
        ))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.render(NearbyTransport.ViewState(
                R.string.received, sessionState = com.weartak.plugin.nearbydrop.protocol.DropSession.State.RECEIVED,
            ))
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val buttons = descendants(root).filterIsInstance<Button>()
            assertEquals(
                listOf(activity.getString(R.string.review_in_weartak), activity.getString(R.string.cancel)),
                buttons.map { it.text.toString() },
            )
            assertTrue(buttons.first().minimumHeight >= 48 * activity.resources.displayMetrics.density)
            assertTrue(descendants(root).filterIsInstance<TextView>().any {
                it.text.toString().contains("Not imported or enrolled")
            })
            val pixels = (192 * activity.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, pixels, pixels)
            val scroll = descendants(root).filterIsInstance<ScrollView>().single()
            val actionBounds = Rect(0, 0, buttons.first().width, buttons.first().height)
            scroll.offsetDescendantRectToMyCoords(buttons.first(), actionBounds)
            val targetScrollY = (actionBounds.bottom - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, targetScrollY)
            assertTrue(actionBounds.top >= scroll.scrollY)
            assertTrue(actionBounds.bottom <= scroll.scrollY + scroll.height)
            assertEquals(0, stageCalls)
        } finally { controller.destroy() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

}
