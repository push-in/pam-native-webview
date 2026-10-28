package dev.pam.webview

import android.content.Context
import android.view.View
import dev.pam.nativeapp.views.NativeViewFactory
import dev.pam.nativeapp.protocol.WireValue

class WebViewFactory : NativeViewFactory {
    override fun create(context: Context, emit: (ByteArray) -> Unit): View = PamWebView(context)
    override fun update(view: View, properties: Map<String, WireValue>) = Unit
    override fun release(view: View) { (view as PamWebView).onProtocolEvent = null; view.destroy() }
}
