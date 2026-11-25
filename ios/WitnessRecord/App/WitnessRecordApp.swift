import SwiftUI
import GoogleSignIn
import BackgroundTasks

@main
struct WitnessRecordApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var appState = AppState.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .environmentObject(appState.capture)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    if GIDSignIn.sharedInstance.handle(url) { return }
                    appState.handleDeepLink(url)
                }
                .task {
                    for id in StorageProviderID.allCases {
                        StorageProviders.shared.provider(id)?.restore()
                    }
                    SessionStitcher.shared.cleanupOrphanedStaging()
                    CrewCloud.shared.start()
                }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Must match the entry in Info.plist's BGTaskSchedulerPermittedIdentifiers.
    static let uploadFlushTaskID = "com.witnessrecord.uploadflush"
    static let sessionMergeTaskID = "com.witnessrecord.sessionmerge"

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions:
                        [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Once per process launch — exactly what the Pro upsell's
        // "second time the app is opened" needs to count against.
        // Synchronous, not a Task: this method always runs on the main
        // thread already, and a Task here raced against Home's own — the
        // increment could still be sitting unrun when Home checked the
        // count, since neither Task's scheduling order is actually
        // guaranteed relative to the other.
        MainActor.assumeIsolated { ProUpsellPrompt.shared.recordAppOpened() }

        // Registration has to happen before this method returns. Register it
        // later and BGTaskScheduler rejects the identifier for the lifetime of
        // the process, silently, which is how this went unnoticed: the
        // identifier was declared in Info.plist and never claimed in code.
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.uploadFlushTaskID,
            using: nil
        ) { [weak self] task in
            self?.handleUploadFlush(task)
        }
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.sessionMergeTaskID,
            using: nil
        ) { [weak self] task in
            self?.handleSessionMerge(task)
        }
        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        scheduleUploadFlushIfNeeded()
        scheduleSessionMergeIfNeeded()
    }

    /// Asks for time to finish assembling a recording the foreground ran out
    /// of time for.
    ///
    /// Backgrounding the app now ends the recording and starts the merge, but
    /// the merge holds an ordinary `beginBackgroundTask` assertion — about
    /// thirty seconds. That is plenty for a few minutes of footage and nowhere
    /// near enough for half an hour, and the unfinished session then waits for
    /// somebody to open the app again. Which is exactly how a thirty-minute
    /// recording ended up with nothing to show for it.
    ///
    /// Not a substitute for the foreground merge: iOS grants these when it
    /// chooses. It is the difference between "finished later, by itself" and
    /// "finished only if you happen to come back".
    func scheduleSessionMergeIfNeeded() {
        guard SessionStitcher.shared.hasUnfinishedSessions else { return }

        let request = BGProcessingTaskRequest(identifier: Self.sessionMergeTaskID)
        request.requiresNetworkConnectivity = false   // merging is local work
        // Same reasoning as the upload flush: footage stranded on a phone at
        // 20% is the footage most worth assembling.
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30)

        try? BGTaskScheduler.shared.submit(request)
    }

    private func handleSessionMerge(_ task: BGTask) {
        // Chained first, as above: one grant may not be enough for a long
        // session, and nothing else would ask for another.
        scheduleSessionMergeIfNeeded()
        task.expirationHandler = { task.setTaskCompleted(success: false) }
        SessionStitcher.shared.cleanupOrphanedStaging()
        // The merge runs on the stitcher's own queue and outlives this call;
        // the assertion it holds carries it as far as iOS allows, and anything
        // still unfinished is picked up by the next grant or the next launch.
        task.setTaskCompleted(success: true)
    }

    /// iOS relaunches the app when background uploads finish; hand the
    /// completion handler to the UploadManager so it can signal when the
    /// session's events are drained.
    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        if identifier == UploadManager.backgroundSessionIdentifier {
            UploadManager.shared.backgroundCompletionHandler = completionHandler
        }
    }

    /// The background URLSession already carries transfers across suspension
    /// and across termination by the system, so this is not the main mechanism
    /// and is not a substitute for it.
    ///
    /// It covers the case the session cannot: chunks still queued when there
    /// was no network to hand them to. Those never became URLSession tasks, so
    /// nothing outside the app knows they exist, and without this they wait for
    /// the user to open the app again. Which, if the phone has been taken, is
    /// not going to happen.
    func scheduleUploadFlushIfNeeded() {
        guard UploadManager.shared.pendingCount > 0 else { return }

        let request = BGProcessingTaskRequest(identifier: Self.uploadFlushTaskID)
        request.requiresNetworkConnectivity = true
        // Waiting for a charger would defeat the point; footage sitting on a
        // phone at 20% is exactly the footage worth getting off it.
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Submission is refused on Simulator and when too many requests are
            // already pending. Neither is worth surfacing: the queue is still
            // drained on next launch, this only makes it happen sooner.
        }
    }

    private func handleUploadFlush(_ task: BGTask) {
        // Chain the next one first. One grant may not be enough, and if this
        // method exits early nothing else will ask for another.
        scheduleUploadFlushIfNeeded()

        task.expirationHandler = {
            task.setTaskCompleted(success: false)
        }

        UploadManager.shared.pump()

        // pump() hands work to the background URLSession, which transfers out
        // of process and outlives this task. So the useful work here is the
        // enqueueing, not the transfer, and holding the task open waiting for
        // bytes to move would burn the budget for nothing. Give the queue a
        // moment to settle, then let go.
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
            task.setTaskCompleted(success: true)
        }
    }
}
