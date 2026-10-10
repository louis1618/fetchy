package com.luverse.fetchy

import android.accessibilityservice.AccessibilityService
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalAccessDeviceTest {
    @Test fun actualFileAndFolderOpenAndShare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = Store(context)
        val id = "fetchy-process-recovery-fixture"
        assumeTrue(store.get("files", id) != null)
        val storage = Storage(context, store)
        fun outsideFetchy() {
            val end = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < end) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null && root.packageName?.toString() != "com.luverse.fetchy" && !root.packageName.toString().contains("launcher")) return
                Thread.sleep(200)
            }
            fail("External file application did not open")
        }
        storage.openFile(id); outsideFetchy()
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        storage.openFile(id, true); outsideFetchy()
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        val folder = store.list("folders").first()
        storage.openFolder(folder.getString("id")); outsideFetchy()
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
