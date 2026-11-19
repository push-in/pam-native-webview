package dev.pam.webview

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamWebViewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val loads = CountDownLatch(1)
    private val errors = CopyOnWriteArrayList<Map<String, WireValue>>()
    private val errorLatch = CountDownLatch(1)

    private fun webView(): PamWebView {
        lateinit var view: PamWebView
        instrumentation.runOnMainSync {
            view = PamWebView(instrumentation.targetContext)
            view.onProtocolEvent = { kind, payload ->
                when (kind) {
                    WEB_VIEW_LOAD -> loads.countDown()
                    WEB_VIEW_ERROR -> {
                        errors += WireMap.decode(payload)
                        errorLatch.countDown()
                    }
                }
            }
        }
        return view
    }

    private fun code(error: Map<String, WireValue>) = (error["code"] as WireValue.Text).value

    /** Android 8.0+: a renderer crash used to kill the whole app process with it. */
    @Test
    fun aRendererCrashKeepsTheAppAndRetiresTheView() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val view = webView()
        instrumentation.runOnMainSync { view.loadUrl("chrome://crash") }
        assertTrue("onRenderProcessGone must report process-gone", errorLatch.await(30, TimeUnit.SECONDS))
        assertEquals(PamWebBridge.ERROR_PROCESS_GONE, code(errors.single()))
        // This process is still alive, and a retired view ignores every later call.
        instrumentation.runOnMainSync {
            view.setSource("https://example.com/")
            view.applyProtocolProperty(326, WireValue.Flag(true))
            view.pauseHost()
            view.resumeHost()
            view.releaseHost()
        }
        assertEquals(1, errors.size)
    }

    /** A blocked link on a page that already loaded is only cancelled (no WEB_VIEW_ERROR). */
    @Test
    fun aBlockedNavigationAfterLoadIsOnlyCancelled() {
        val view = webView()
        instrumentation.runOnMainSync {
            view.setJavaScriptEnabled(true)
            view.setAllowedHosts("example.com")
            view.setSource(
                "<html><body><script>" +
                    "window.addEventListener('load',function(){setTimeout(function(){location.href='https://blocked.example.net/x';},200);});" +
                    "</script></body></html>",
            )
        }
        assertTrue(loads.await(15, TimeUnit.SECONDS))
        Thread.sleep(1_500)
        assertEquals(emptyList<Map<String, WireValue>>(), errors.toList())
        instrumentation.runOnMainSync { view.releaseHost() }
    }

    /** Before any page shows, a navigation outside allowedHosts fails the load with code blocked. */
    @Test
    fun aBlockedNavigationBeforeTheFirstPageFailsTheLoad() {
        val view = webView()
        instrumentation.runOnMainSync {
            view.setJavaScriptEnabled(true)
            view.setAllowedHosts("example.com")
            view.setSource("<html><body><script>location.replace('https://blocked.example.net/x');</script></body></html>")
        }
        assertTrue(errorLatch.await(15, TimeUnit.SECONDS))
        assertEquals(PamWebBridge.ERROR_BLOCKED, code(errors.first()))
        instrumentation.runOnMainSync { view.releaseHost() }
    }

    private companion object {
        const val WEB_VIEW_LOAD = 48
        const val WEB_VIEW_ERROR = 49
    }
}
