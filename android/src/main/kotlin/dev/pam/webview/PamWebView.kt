package dev.pam.webview

import dev.pam.nativeapp.views.NativePropertyView
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.protocol.WireMap

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Page ↔ host channel rules shared by the Android and iOS web views, kept
 * pure for JVM tests.
 *
 * Pages post with `window.PamNative.postMessage(text)` or, for pages written
 * for react-native-webview, `window.ReactNativeWebView.postMessage(text)`;
 * the second exists from document start. The host answers by changing
 * `injectedJavaScript`: a loaded page runs each new script once. Messages
 * carry the main-frame URL; the host still checks its own protocol (nonce),
 * since any frame of the page can reach the interface.
 */
internal object PamWebBridge {
    const val INTERFACE_NAME = "ReactNativeWebView"
    const val MAX_MESSAGE_CHARS = 1_048_576

    /** `PamNative.postMessage` as an alias of the document-start interface. */
    const val ALIAS_SCRIPT =
        "window.PamNative=window.PamNative||{};" +
            "window.PamNative.postMessage=function(v){window.ReactNativeWebView.postMessage(String(v));};true;"

    fun accepts(message: String?): Boolean =
        message != null && message.length <= MAX_MESSAGE_CHARS

    /** A changed, non-empty script runs at once on a page that already finished loading. */
    fun runsOnChange(previous: String, next: String, pageLoaded: Boolean): Boolean =
        pageLoaded && next.isNotEmpty() && next != previous

    /** `ScrollOverScrollMode` (1 auto, 2 always, 3 never); a web view that does not scroll never over-scrolls. */
    fun overScrollMode(mode: Int, scrollEnabled: Boolean): Int = when {
        !scrollEnabled || mode == 3 -> View.OVER_SCROLL_NEVER
        mode == 2 -> View.OVER_SCROLL_ALWAYS
        else -> View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    /** Paused while the host app is in the background or the view is not shown (covered route, hidden tab). */
    fun runs(hostActive: Boolean, shown: Boolean): Boolean = hostActive && shown

    /** `WebViewErrorCode` values (PHP); the `code` field of `WEB_VIEW_ERROR`. */
    const val ERROR_LOAD = "load"
    const val ERROR_TIMEOUT = "timeout"
    const val ERROR_BLOCKED = "blocked"
    const val ERROR_PROCESS_GONE = "process-gone"

    /**
     * A main-frame navigation outside `allowedHosts` is only cancelled once a
     * page is shown (a link, `target=_blank`, a script redirect): the page
     * stays. Before the first page finishes it fails the load, since nothing
     * would ever show.
     */
    fun blockedNavigationFails(pageLoaded: Boolean): Boolean = !pageLoaded
}

/** Existing reserved core protocol IDs; never renumbered by the plugin. */
private object WebViewWire {
    const val WEB_VIEW_SOURCE = 325
    const val WEB_VIEW_JAVA_SCRIPT_ENABLED = 326
    const val WEB_VIEW_DOM_STORAGE_ENABLED = 327
    const val WEB_VIEW_USER_AGENT = 328
    const val WEB_VIEW_INJECTED_JAVA_SCRIPT = 329
    const val WEB_VIEW_ALLOWS_INLINE_MEDIA = 330
    const val WEB_VIEW_ALLOWED_HOSTS = 366
    const val SCROLL_ENABLED = 64
    const val SCROLL_OVER_SCROLL_MODE = 181
    const val WEB_VIEW_LOAD = 48
    const val WEB_VIEW_ERROR = 49
    const val WEB_VIEW_MESSAGE = 50
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
internal class PamWebView(context: Context) : WebView(context), NativePropertyView {
    override var onProtocolEvent: ((Int, ByteArray) -> Unit)? = null
    override fun pauseHost() = onHostPause()
    override fun resumeHost() = onHostResume()
    override fun releaseHost() {
        if (processGone) destroyRetired() else destroy()
    }
    override fun applyProtocolProperty(key: Int, value: WireValue?) {
        if (processGone) return
        when (key) {
            WebViewWire.WEB_VIEW_SOURCE -> setSource((value as? WireValue.Text)?.value ?: "")
            WebViewWire.WEB_VIEW_JAVA_SCRIPT_ENABLED -> setJavaScriptEnabled((value as? WireValue.Flag)?.value ?: true)
            WebViewWire.WEB_VIEW_DOM_STORAGE_ENABLED -> setDomStorageEnabled((value as? WireValue.Flag)?.value ?: true)
            WebViewWire.WEB_VIEW_USER_AGENT -> setUserAgent((value as? WireValue.Text)?.value ?: "")
            WebViewWire.WEB_VIEW_INJECTED_JAVA_SCRIPT -> setInjectedJavaScript((value as? WireValue.Text)?.value ?: "")
            WebViewWire.WEB_VIEW_ALLOWS_INLINE_MEDIA -> setAllowsInlineMedia((value as? WireValue.Flag)?.value ?: true)
            WebViewWire.WEB_VIEW_ALLOWED_HOSTS -> setAllowedHosts((value as? WireValue.Text)?.value ?: "")
            WebViewWire.SCROLL_ENABLED -> setScrollEnabled((value as? WireValue.Flag)?.value ?: true)
            WebViewWire.SCROLL_OVER_SCROLL_MODE -> setOverScrollModeValue((value as? WireValue.Integer)?.value?.toInt() ?: 1)
        }
    }
    private val defaultUserAgent = settings.userAgentString
    private var source = ""
    private var injectedJavaScript = ""
    private var allowedHosts: Set<String> = emptySet()
    private val main = Handler(Looper.getMainLooper())
    private var loadTimeout: Runnable? = null
    private var pageLoaded = false
    private var scrollEnabled = true
    private var overScrollModeValue = 1
    private var hostActive = true
    private var running = true
    /** The renderer died: this WebView can never be used again (Android 8.0+ contract). */
    private var processGone = false
    var onLoad: (() -> Unit)? = null
    var onError: ((String, String) -> Unit)? = null

    /** Message text and the URL of the page that posted it. */
    var onMessage: ((String, String) -> Unit)? = null

    init {
        onLoad = { onProtocolEvent?.invoke(WebViewWire.WEB_VIEW_LOAD, ByteArray(0)) }
        onError = { message, code ->
            onProtocolEvent?.invoke(
                WebViewWire.WEB_VIEW_ERROR,
                WireMap.encode(mapOf("message" to WireValue.Text(message), "code" to WireValue.Text(code))),
            )
        }
        onMessage = { message, url -> onProtocolEvent?.invoke(WebViewWire.WEB_VIEW_MESSAGE, WireMap.encode(mapOf("message" to WireValue.Text(message), "url" to WireValue.Text(url)))) }

        // Hardened defaults: no file/content access, no popups or new
        // windows, no mixed content, no geolocation, no third-party cookies
        // and no page zoom. Navigation stays inside `allowedHosts`.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setGeolocationEnabled(false)
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        addJavascriptInterface(MessageInterface(), PamWebBridge.INTERFACE_NAME)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handlePamMessage(request.url) || request.isForMainFrame && !allowsNavigation(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                Uri.parse(url).let { handlePamMessage(it) || !allowsNavigation(it) }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoaded = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                cancelLoadTimeout()
                pageLoaded = true
                if (settings.javaScriptEnabled) {
                    evaluateJavascript(PamWebBridge.ALIAS_SCRIPT, null)
                }
                if (settings.javaScriptEnabled && injectedJavaScript.isNotEmpty()) {
                    evaluateJavascript(injectedJavaScript, null)
                }
                onLoad?.invoke()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    cancelLoadTimeout()
                    onError?.invoke(error.description.toString(), PamWebBridge.ERROR_LOAD)
                }
            }

            // The default (false) kills the whole app process with the
            // renderer (OOM kill while a game is open or in the background,
            // or a renderer crash). Keep the app: retire this view and tell
            // the page owner, who remounts it (new key) or shows a failure.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== this@PamWebView) return false
                // Only called on Android 8.0+, where the renderer runs out of process.
                val crashed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail.didCrash()
                retireAfterProcessGone()
                onError?.invoke(
                    if (crashed) "WebView renderer crashed" else "WebView renderer was killed by the system",
                    PamWebBridge.ERROR_PROCESS_GONE,
                )
                return true
            }
        }
    }

    /**
     * The renderer is gone: no further call may reach this WebView. It stays
     * in the hierarchy the renderer manages (invisible, so it never draws) and
     * is destroyed once detached.
     */
    private fun retireAfterProcessGone() {
        if (processGone) return
        processGone = true
        cancelLoadTimeout()
        pageLoaded = false
        visibility = View.INVISIBLE
        if (!isAttachedToWindow) destroyRetired()
    }

    private var retiredDestroyed = false

    private fun destroyRetired() {
        if (retiredDestroyed) return
        retiredDestroyed = true
        destroy()
    }

    /** Called on a binder thread; delivered on the main thread with the page URL of that moment. */
    private inner class MessageInterface {
        @JavascriptInterface
        fun postMessage(value: String?) {
            if (!PamWebBridge.accepts(value)) return
            val text = value ?: return
            main.post { if (!processGone) onMessage?.invoke(text, url.orEmpty()) }
        }
    }

    fun setSource(value: String) {
        if (processGone || source == value) return
        source = value
        pageLoaded = false
        if (value.isEmpty()) {
            cancelLoadTimeout()
            stopLoading()
            return
        }
        scheduleLoadTimeout()
        if (value.trimStart().startsWith("<")) {
            loadDataWithBaseURL(null, value, "text/html", "UTF-8", null)
        } else {
            loadUrl(value)
        }
    }

    fun setJavaScriptEnabled(value: Boolean) {
        if (processGone) return
        settings.javaScriptEnabled = value
    }

    fun setDomStorageEnabled(value: Boolean) {
        if (processGone) return
        settings.domStorageEnabled = value
    }

    fun setUserAgent(value: String) {
        if (processGone) return
        settings.userAgentString = value.ifEmpty { defaultUserAgent }
    }

    fun setInjectedJavaScript(value: String) {
        if (processGone) return
        val runNow = settings.javaScriptEnabled &&
            PamWebBridge.runsOnChange(injectedJavaScript, value, pageLoaded)
        injectedJavaScript = value
        if (runNow) evaluateJavascript(value, null)
    }

    fun setAllowsInlineMedia(value: Boolean) {
        if (processGone) return
        settings.mediaPlaybackRequiresUserGesture = !value
    }

    fun setScrollEnabled(value: Boolean) {
        if (processGone) return
        scrollEnabled = value
        isVerticalScrollBarEnabled = value
        isHorizontalScrollBarEnabled = value
        overScrollMode = PamWebBridge.overScrollMode(overScrollModeValue, scrollEnabled)
    }

    fun setOverScrollModeValue(value: Int) {
        if (processGone) return
        overScrollModeValue = value
        overScrollMode = PamWebBridge.overScrollMode(overScrollModeValue, scrollEnabled)
    }

    fun setAllowedHosts(value: String) {
        if (processGone) return
        allowedHosts = value.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::lowercase)
            .toSet()
        val current = url?.let(Uri::parse)
        if (current != null && !allows(current)) {
            cancelLoadTimeout()
            stopLoading()
            onError?.invoke("WebView navigation was blocked by the allowed-host policy", PamWebBridge.ERROR_BLOCKED)
        }
    }

    fun onHostPause() {
        hostActive = false
        syncRunning()
    }

    fun onHostResume() {
        hostActive = true
        syncRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncRunning()
    }

    /** A covered route or a background app pauses the page (no frames, timers or animations of this view). */
    private fun syncRunning() {
        if (processGone) return
        val next = PamWebBridge.runs(hostActive, isShown && windowVisibility == View.VISIBLE)
        if (next == running) return
        running = next
        if (next) onResume() else onPause()
    }

    private fun handlePamMessage(uri: Uri): Boolean {
        if (uri.scheme != "pam" || uri.host != "message") return false
        val text = uri.getQueryParameter("data").orEmpty()
        if (PamWebBridge.accepts(text)) onMessage?.invoke(text, url.orEmpty())
        return true
    }

    private fun allows(uri: Uri): Boolean {
        if (allowedHosts.isEmpty() || uri.scheme == "about" || uri.scheme == "data") return true
        val host = uri.host?.lowercase() ?: return false
        return host in allowedHosts
    }

    /** Main-frame navigation check: a blocked one is cancelled, and fails only a load that never showed a page. */
    private fun allowsNavigation(uri: Uri): Boolean {
        if (allows(uri)) return true
        if (PamWebBridge.blockedNavigationFails(pageLoaded)) {
            cancelLoadTimeout()
            onError?.invoke("WebView blocked navigation to ${uri.host.orEmpty()}", PamWebBridge.ERROR_BLOCKED)
        }
        return false
    }

    private fun scheduleLoadTimeout() {
        cancelLoadTimeout()
        val timeout = Runnable {
            stopLoading()
            onError?.invoke("WebView navigation timed out after 30 seconds", PamWebBridge.ERROR_TIMEOUT)
        }
        loadTimeout = timeout
        main.postDelayed(timeout, 30_000)
    }

    private fun cancelLoadTimeout() {
        loadTimeout?.let(main::removeCallbacks)
        loadTimeout = null
    }

    override fun onDetachedFromWindow() {
        cancelLoadTimeout()
        super.onDetachedFromWindow()
        if (processGone) destroyRetired()
    }
}
