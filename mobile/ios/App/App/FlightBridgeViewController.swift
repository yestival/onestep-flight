import Capacitor
import UIKit

final class FlightBridgeViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(FlightNativePlugin())
        bridge?.registerPluginInstance(FlightPushPlugin())
        bridge?.registerPluginInstance(FlightLiveActivityPlugin())
        webView?.scrollView.contentInsetAdjustmentBehavior = .never
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
    override var preferredStatusBarStyle: UIStatusBarStyle { .darkContent }
}
