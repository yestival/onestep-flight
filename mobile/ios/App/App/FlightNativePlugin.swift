import Capacitor
import UIKit
import WebKit

@objc(FlightNativePlugin)
public class FlightNativePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FlightNativePlugin"
    public let jsName = "FlightNative"
    public let pluginMethods: [CAPPluginMethod] = [CAPPluginMethod(name: "openExternal", returnType: CAPPluginReturnPromise)]

    private func allowed(_ url: URL) -> Bool {
        guard url.user == nil, url.password == nil else { return false }
        if url.scheme == "https", url.host != nil { return true }
        return url.absoluteString.range(of: "^tel:\\+?[0-9 ()-]{5,25}$", options: .regularExpression) != nil
    }

    @objc func openExternal(_ call: CAPPluginCall) {
        guard let raw = call.getString("url"), let url = URL(string: raw), allowed(url) else {
            call.reject("Unsupported URL"); return
        }
        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:]) { success in
                if success { call.resolve() } else { call.reject("Unable to open URL") }
            }
        }
    }

    @objc public override func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> NSNumber? {
        guard let url = navigationAction.request.url else { return true }
        if url.scheme == "capacitor" && url.host == "localhost" { return nil }
        if allowed(url), navigationAction.targetFrame?.isMainFrame != false {
            DispatchQueue.main.async { UIApplication.shared.open(url, options: [:]) }
        }
        return true // No external document receives the main WebView's native bridge.
    }
}
