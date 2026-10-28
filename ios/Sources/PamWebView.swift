import AVFoundation
import AVKit
import Foundation
import Photos
import UIKit
import WebKit
import PamNative

/// Page ↔ host channel rules shared with Android (`PamWebBridge.kt`).
///
/// Pages post with `window.PamNative.postMessage(text)` or, for pages written
/// for react-native-webview, `window.ReactNativeWebView.postMessage(text)`;
/// both exist from document start. The host answers by changing
/// `injectedJavaScript`: a loaded page runs each new script once. Only the
/// main frame may post, and each message carries the page URL.
enum PamWebBridge {
    static let handlerName = "pam"
    static let maxMessageCharacters = 1_048_576
    static let documentStartScript = """
    window.PamNative = window.PamNative || {};
    window.PamNative.postMessage = function(value) {
        window.webkit.messageHandlers.pam.postMessage(String(value));
    };
    window.ReactNativeWebView = window.ReactNativeWebView || {
        postMessage: function(value) { window.PamNative.postMessage(value); }
    };
    """

    static func accepts(_ message: String, isMainFrame: Bool) -> Bool {
        isMainFrame && message.count <= maxMessageCharacters
    }

    static func runsOnChange(previous: String, next: String, pageLoaded: Bool) -> Bool {
        pageLoaded && !next.isEmpty && next != previous
    }
}

final class PamWebView: WKWebView, WKNavigationDelegate, WKScriptMessageHandler, NativePropertyView {
    func releaseHost() {
        cancelLoadTimeout()
        stopLoading()
        navigationDelegate = nil
        configuration.userContentController.removeScriptMessageHandler(forName: PamWebBridge.handlerName)
        onProtocolEvent = nil
    }
    var onProtocolEvent: ((Int, Data) -> Void)?
    func applyProtocolProperty(key: Int, value: WireValue?) {
        switch key {
        case 325: setSource(value?.webText ?? "")
        case 326: setJavaScriptEnabled(value?.webFlag ?? true)
        case 327: setDomStorageEnabled(value?.webFlag ?? true)
        case 328: setUserAgent(value?.webText ?? "")
        case 329: setInjectedJavaScript(value?.webText ?? "")
        case 330: setAllowsInlineMedia(value?.webFlag ?? true)
        case 366: setAllowedHosts(value?.webText ?? "")
        case 64: setScrollEnabled(value?.webFlag ?? true)
        default: break
        }
    }
    var onLoad: (() -> Void)?
    var onError: ((String) -> Void)?
    /// Message text and the URL of the page that posted it.
    var onMessage: ((String, String) -> Void)?
    private var source = ""
    private var injectedJavaScript = ""
    private var javaScriptEnabled = true
    private var allowedHosts: Set<String> = []
    private var loadTimeout: DispatchWorkItem?
    private var pageLoaded = false

    init() {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.addUserScript(
            WKUserScript(
                source: PamWebBridge.documentStartScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            )
        )
        // Hardened defaults: no data detectors, no popups.
        configuration.dataDetectorTypes = []
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        super.init(frame: .zero, configuration: configuration)
        onLoad = { [weak self] in self?.onProtocolEvent?(48, Data()) }
        onError = { [weak self] message in self?.onProtocolEvent?(49, (try? WireMap.encode(["message": .text(message)])) ?? Data()) }
        onMessage = { [weak self] message, url in self?.onProtocolEvent?(50, (try? WireMap.encode(["message": .text(message), "url": .text(url)])) ?? Data()) }
        navigationDelegate = self
        // No link previews (long press) and no back/forward swipes inside the page.
        allowsLinkPreview = false
        allowsBackForwardNavigationGestures = false
        scrollView.contentInsetAdjustmentBehavior = .never
        configuration.userContentController.add(WeakWebMessageHandler(self), name: PamWebBridge.handlerName)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setSource(_ value: String) {
        guard value != source else { return }
        source = value
        pageLoaded = false
        guard !value.isEmpty else {
            cancelLoadTimeout()
            stopLoading()
            return
        }
        scheduleLoadTimeout()
        if value.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("<") {
            loadHTMLString(value, baseURL: nil)
        } else if let url = URL(string: value) {
            load(URLRequest(url: url))
        }
    }

    func setJavaScriptEnabled(_ value: Bool) {
        javaScriptEnabled = value
        configuration.defaultWebpagePreferences.allowsContentJavaScript = value
    }

    func setDomStorageEnabled(_: Bool) {}
    func setUserAgent(_ value: String) { customUserAgent = value.isEmpty ? nil : value }

    func setInjectedJavaScript(_ value: String) {
        let runNow = javaScriptEnabled
            && PamWebBridge.runsOnChange(previous: injectedJavaScript, next: value, pageLoaded: pageLoaded)
        injectedJavaScript = value
        if runNow { evaluateJavaScript(value) }
    }

    func setAllowsInlineMedia(_ value: Bool) { configuration.allowsInlineMediaPlayback = value }

    /// false: the page never scrolls, bounces or zooms with the scroll view (a fixed page such as a game).
    func setScrollEnabled(_ value: Bool) {
        scrollView.isScrollEnabled = value
        scrollView.bounces = value
        scrollView.alwaysBounceVertical = false
        scrollView.alwaysBounceHorizontal = false
        scrollView.pinchGestureRecognizer?.isEnabled = value
    }

    func setAllowedHosts(_ value: String) {
        allowedHosts = Set(
            value.split(separator: "\n")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
                .filter { !$0.isEmpty }
        )
        if let current = url, !allows(current) {
            stopLoading()
            onError?("WebView navigation was blocked by the allowed-host policy")
        }
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard navigationAction.targetFrame?.isMainFrame != false,
              let target = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        if allows(target) {
            decisionHandler(.allow)
        } else {
            cancelLoadTimeout()
            onError?("WebView blocked navigation to \(target.host ?? "unknown host")")
            decisionHandler(.cancel)
        }
    }

    private func allows(_ url: URL) -> Bool {
        if allowedHosts.isEmpty || url.scheme == "about" || url.scheme == "data" {
            return true
        }
        guard let host = url.host?.lowercased() else { return false }
        return allowedHosts.contains(host)
    }

    private func scheduleLoadTimeout() {
        cancelLoadTimeout()
        let timeout = DispatchWorkItem { [weak self] in
            self?.stopLoading()
            self?.onError?("WebView navigation timed out after 30 seconds")
        }
        loadTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timeout)
    }

    private func cancelLoadTimeout() {
        loadTimeout?.cancel()
        loadTimeout = nil
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        pageLoaded = false
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        cancelLoadTimeout()
        pageLoaded = true
        if javaScriptEnabled && !injectedJavaScript.isEmpty {
            evaluateJavaScript(injectedJavaScript)
        }
        onLoad?()
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        cancelLoadTimeout()
        onError?(error.localizedDescription)
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        let text = String(describing: message.body)
        guard PamWebBridge.accepts(text, isMainFrame: message.frameInfo.isMainFrame) else { return }
        onMessage?(text, url?.absoluteString ?? "")
    }
}

private final class WeakWebMessageHandler: NSObject, WKScriptMessageHandler {
    private weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

/// Video surface without player chrome: a bare `AVPlayerLayer`. The
/// heavier `AVPlayerViewController` (and its gesture recognizers) exists only
/// while `controls=true`, like Android's MediaController.

private extension WireValue {
    var webText: String? { if case let .text(value) = self { return value }; return nil }
    var webFlag: Bool? { if case let .flag(value) = self { return value }; return nil }
}
