import ExpoModulesCore
import OnsideKit

public class OnsideAppDelegateSubscriber: ExpoAppDelegateSubscriber {

    public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil
    ) -> Bool {

        // Автоматически ставим callbackScheme как bundleId + ".onside-auth"
        let bundleId = Bundle.main.bundleIdentifier ?? ""
        Onside.callbackScheme = bundleId + ".onside-auth"

        return true
    }

    public func application(
    _ app: UIApplication,
    open url: URL,
    options: [UIApplication.OpenURLOptionsKey : Any] = [:]
    ) -> Bool {

        // Сначала пытаемся отдать URL в Onside
        let handledByOnside = Onside.handle(url: url)
        if handledByOnside {
            return true
        }

        return false
    }
}
