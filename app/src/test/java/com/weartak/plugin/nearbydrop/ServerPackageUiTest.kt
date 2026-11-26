// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.activity.result.contract.ActivityResultContracts
import com.weartak.plugin.nearbydrop.protocol.DropSession
import com.weartak.plugin.nearbydrop.protocol.DropWire
import com.weartak.plugin.sdk.serverpackage.ServerPackageDiscovery
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round")
class ServerPackageUiTest {
    @After fun reset() = ServerPackageBridge.setDiscovery(ServerPackageDiscovery.Unsupported)

    @Test fun `unavailable watch document stub is not launched without host export`() {
        ServerPackageBridge.setDiscovery(ServerPackageDiscovery.Unsupported)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            registerDocumentStub(activity)
            buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(texts(activity).any { it.contains("Reopen Nearby Drop from WearTAK") })
        } finally { controller.stop().destroy() }
    }

    @Test fun `unavailable watch document stub leaves supported server export accessible`() {
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(ServerPackageFixtures.FakeControl()))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            registerDocumentStub(activity)
            buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
            assertEquals(
                listOf(activity.getString(R.string.send_server_package), activity.getString(R.string.cancel)),
                buttons(activity).map { it.text.toString() },
            )
            assertTrue(texts(activity).any { it.contains("Bundle files unavailable") })
        } finally { controller.stop().destroy() }
    }

    @Test fun `genuine document picker remains available without host export`() {
        ServerPackageBridge.setDiscovery(ServerPackageDiscovery.Unsupported)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            registerDocumentPicker(activity, "example.documents", "example.documents.Picker")
            buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, intent.action)
        } finally { controller.stop().destroy() }
    }

    @Test fun `send offers server package then bundle file while entry stays Receive then Send`() {
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(ServerPackageFixtures.FakeControl()))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(R.string.receive_bundle, R.string.choose_bundle, R.string.licenses).map(activity::getString),
                buttons(activity).map { it.text.toString() })
            buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
            assertEquals(
                listOf(R.string.send_server_package, R.string.send_bundle_file, R.string.cancel).map(activity::getString),
                buttons(activity).map { it.text.toString() },
            )
        } finally { controller.stop().destroy() }
    }

    @Test fun `package summary names sensitive items without rendering secret values`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val summary = checkNotNull(ServerPackageSummary.describe(ServerPackageFixtures.sharedIdentityPackage()))
            val text = activity.packageDetails(summary).joinToString("\n")
            assertTrue(text.contains(activity.getString(R.string.package_sensitive_identity)))
            assertTrue(text.contains(
                "Sensitive fields have no independent app-layer encryption and rely on the Nearby UKEY2-encrypted connection plus mutual code confirmation.",
            ))
            assertTrue(text.contains(activity.getString(R.string.package_no_device_identity)))
            assertFalse(text.contains(ServerPackageFixtures.SECRET_PASSWORD))

            val credentials = checkNotNull(ServerPackageSummary.describe(ServerPackageFixtures.sharedCredentialsPackage()))
            val credentialText = activity.packageDetails(credentials).joinToString("\n")
            assertTrue(credentialText.contains(activity.getString(R.string.package_identity_enroll_shared)))
            assertFalse(credentialText.contains(ServerPackageFixtures.SECRET_USER))
            assertFalse(credentialText.contains(ServerPackageFixtures.SECRET_PASSWORD))
        } finally { controller.destroy() }
    }

    @Test fun `receiver review of package offer warns about credentials before acceptance`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.render(NearbyTransport.ViewState(R.string.connected, offeredBytes = 512,
                sessionState = DropSession.State.REVIEW, offeredKind = DropWire.ContentKind.SERVER_PACKAGE))
            val status = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                .first { it.text.contains("server package") }
            assertTrue(status.text.contains("Nothing will be applied"))
            assertEquals(activity.getString(R.string.accept_offer), buttons(activity).first().text.toString())
        } finally { controller.destroy() }
    }

    @Test fun `host picker covering the activity keeps export pending until host stop`() {
        val control = ServerPackageFixtures.FakeControl()
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(control))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
            buttons(activity).first().performClick()
            awaitMain { control.requestExportCalls == 1 && buttons(activity).map { it.text.toString() } ==
                listOf(activity.getString(R.string.cancel)) }

            // Host picker covers the activity: real onStop -> ActiveTransfer.detach must not cancel the export.
            controller.pause().stop()
            settle()
            assertEquals(0, control.cancelExportCalls)
            controller.start().resume()
            settle()
            assertEquals(activity.getString(R.string.export_waiting), statusText(activity))

            // Host stop while covered must still cancel it.
            controller.pause().stop()
            ActiveTransfer.stopFromHost()
            awaitMain { control.cancelExportCalls == 1 }
        } finally { controller.destroy() }
        settle()
        assertEquals(1, control.cancelExportCalls)
    }

    @Test fun `destroying the activity while picker covers it cancels the export`() {
        val control = ServerPackageFixtures.FakeControl()
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(control))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
        buttons(activity).first().performClick()
        awaitMain { control.requestExportCalls == 1 && buttons(activity).size == 1 }
        controller.pause().stop()
        settle()
        assertEquals(0, control.cancelExportCalls)
        controller.destroy()
        awaitMain { control.cancelExportCalls == 1 }
        ActiveTransfer.stopFromHost()
        settle()
        assertEquals(1, control.cancelExportCalls)
    }

    @Test fun `send after backing out of host export shows chooser and survives capability replay`() {
        val control = ServerPackageFixtures.FakeControl()
        val supported = ServerPackageFixtures.supported(control)
        ServerPackageBridge.setDiscovery(supported)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val chooser = listOf(R.string.send_server_package, R.string.send_bundle_file, R.string.cancel)
                .map(activity::getString)
            buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
            buttons(activity).first().performClick()
            awaitMain { control.requestExportCalls == 1 && buttons(activity).size == 1 }

            // Host picker covers the plugin; wearer backs out so the host reports CANCELLED.
            controller.pause().stop()
            settle()
            control.exportState = com.weartak.plugin.sdk.serverpackage.ServerPackageExportState.CANCELLED
            controller.start().resume()
            awaitMain {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_100))
                texts(activity).any { it == activity.getString(R.string.export_cancelled) }
            }
            assertEquals(listOf(R.string.receive_bundle, R.string.choose_bundle, R.string.licenses).map(activity::getString),
                buttons(activity).map { it.text.toString() })

            buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
            settle()
            assertEquals(chooser, buttons(activity).map { it.text.toString() })
            // Host re-initialization replays the stale cancelled transport state; chooser must stay.
            ServerPackageBridge.setDiscovery(supported)
            settle()
            assertEquals(chooser, buttons(activity).map { it.text.toString() })
            assertTrue(texts(activity).contains(activity.getString(R.string.send_choice)))

            // Repeat export works.
            control.exportState = com.weartak.plugin.sdk.serverpackage.ServerPackageExportState.PENDING
            buttons(activity).first().performClick()
            awaitMain { control.requestExportCalls == 2 && buttons(activity).size == 1 }
            ServerPackageBridge.setDiscovery(supported)
            settle()
            assertTrue(texts(activity).contains(activity.getString(R.string.export_waiting)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `unauthorized export start tells wearer to reopen from WearTAK`() {
        val control = ServerPackageFixtures.FakeControl().apply {
            exportCode = com.weartak.plugin.sdk.serverpackage.ServerPackageExportCode.UNAUTHORIZED
        }
        ServerPackageBridge.setDiscovery(ServerPackageFixtures.supported(control))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            buttons(activity).first { it.text == activity.getString(R.string.choose_bundle) }.performClick()
            buttons(activity).first().performClick()
            awaitMain { texts(activity).contains(activity.getString(R.string.export_unauthorized)) }
            assertTrue(activity.getString(R.string.export_unauthorized).contains("Reopen Nearby Drop from WearTAK"))
            assertFalse(texts(activity).contains(activity.getString(R.string.export_unavailable)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `title is centred on round screens so the mask does not clip it`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val title = descendants(controller.get().findViewById(android.R.id.content))
                .first { it.tag == "title" } as TextView
            assertEquals(android.view.Gravity.CENTER_HORIZONTAL, title.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, title.layoutParams.width)
        } finally { controller.destroy() }
    }

    private fun statusText(activity: MainActivity) =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
            .first { it.text.toString() == activity.getString(R.string.export_waiting) }.text.toString()

    private fun texts(activity: MainActivity) =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
            .filter { it !is Button }.map { it.text.toString() }

    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not reached" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun buttons(activity: MainActivity) =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()

    private fun registerDocumentStub(activity: MainActivity) {
        registerDocumentPicker(
            activity, "com.google.android.wearable.frameworkpackagestubs",
            "com.google.android.wearable.frameworkpackagestubs.Stubs\$DocumentsStub",
        )
    }

    @Suppress("DEPRECATION")
    private fun registerDocumentPicker(activity: MainActivity, packageName: String, className: String) {
        val intent = ActivityResultContracts.OpenDocument().createIntent(activity, arrayOf("application/x-pkcs12"))
        shadowOf(activity.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = className
            }
        })
    }

    private fun descendants(view: android.view.View): List<android.view.View> =
        listOf(view) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) } } ?: emptyList())
}
