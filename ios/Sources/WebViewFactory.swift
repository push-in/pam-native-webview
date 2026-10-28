import UIKit
import PamNative

public final class WebViewFactory: NativeViewFactory {
    public init() {}
    public func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView { PamWebView() }
    public func update(view: UIView, properties: [String: WireValue]) {}
    public func release(view: UIView) { (view as? PamWebView)?.releaseHost() }
}
