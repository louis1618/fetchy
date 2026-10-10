package com.luverse.fetchy

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in device setup: create Download/Fetchy before invoking this class. */
@RunWith(AndroidJUnit4::class)
class StorageGrantDeviceTest {
    @Test fun approveDedicatedTestFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Store(context).settings().optString("rootUri").isNotEmpty()) return
        val activity = instrumentation.startActivitySync(testActivityIntent(context)) as MainActivity
        val done = CountDownLatch(1)
        var failure: String? = null
        instrumentation.runOnMainSync {
            val method = MainActivity::class.java.getDeclaredMethod("handle", MethodCall::class.java, MethodChannel.Result::class.java).apply { isAccessible = true }
            method.invoke(activity, MethodCall("chooseRoot", null), object : MethodChannel.Result {
                override fun success(value: Any?) { done.countDown() }
                override fun error(code: String, message: String?, details: Any?) { failure = message; done.countDown() }
                override fun notImplemented() { failure = "not implemented"; done.countDown() }
            })
        }
        fun find(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (test(node)) return node
            for (i in 0 until node.childCount) { find(node.getChild(i), test)?.let { return it } }
            return null
        }
        fun click(test: (AccessibilityNodeInfo) -> Boolean) {
            val deadline = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < deadline) {
                val node = find(instrumentation.uiAutomation.rootInActiveWindow, test)
                if (node != null) {
                    var parent: AccessibilityNodeInfo? = node
                    while (parent != null) { if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; parent = parent.parent }
                }
                Thread.sleep(250)
            }
            fail("System folder picker action unavailable")
        }
        click { it.text?.toString() == "Fetchy" }
        click { val t = it.text?.toString().orEmpty(); t == "USE THIS FOLDER" || t == "이 폴더 사용" }
        click { val t = it.text?.toString().orEmpty(); t == "ALLOW" || t == "허용" }
        assertTrue("Folder callback timed out", done.await(15, TimeUnit.SECONDS))
        assertNull(failure)
        assertTrue(Store(context).settings().optString("rootUri").contains("Fetchy"))
        instrumentation.runOnMainSync { activity.finish() }
    }
}
