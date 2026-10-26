import XCTest
import UserNotifications
import ComposeApp

/// Exercises the Kotlin iOS notification adapter against the real UNUserNotificationCenter.
/// Hosted in the DayBloom app because the notification center requires an app bundle.
final class NotificationAdapterTests: XCTestCase {
    private let probe = IosNotificationProbe.shared

    /// iOS keeps no pending requests for an app that isn't authorized. Provisional authorization is granted
    /// without a prompt, so a fresh simulator can run these tests unattended.
    private func requireSchedulingPermission() async throws {
        let center = UNUserNotificationCenter.current()
        if await center.notificationSettings().authorizationStatus == .notDetermined {
            _ = try await center.requestAuthorization(options: [.alert, .sound, .provisional])
        }
        let status = await center.notificationSettings().authorizationStatus
        try XCTSkipUnless(
            status == .authorized || status == .provisional,
            "Notifications are denied for DayBloom on this device; allow them in Settings to run scheduling tests."
        )
    }

    func testScheduleListAndCancel() async throws {
        try await requireSchedulingPermission()
        let id = "probe-\(UUID().uuidString)"
        try await probe.schedule(id: id, title: "Probe", secondsFromNow: 3600)
        let pending = try await probe.pendingIds()
        XCTAssertTrue(pending.contains(id), "scheduled request is pending with the OS")

        try await probe.cancel(id: id)
        let afterCancel = try await probe.pendingIds()
        XCTAssertFalse(afterCancel.contains(id), "cancelled request is removed")
    }

    func testSchedulingTheSameIdReplacesInsteadOfDuplicating() async throws {
        try await requireSchedulingPermission()
        let id = "probe-\(UUID().uuidString)"
        try await probe.schedule(id: id, title: "First", secondsFromNow: 3600)
        try await probe.schedule(id: id, title: "Second", secondsFromNow: 7200)
        let requests = await UNUserNotificationCenter.current().pendingNotificationRequests().filter { $0.identifier == id }
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests.first?.content.title, "Second")
        XCTAssertNotNil(requests.first?.trigger as? UNCalendarNotificationTrigger, "uses a calendar (date-based) trigger")
        try await probe.cancel(id: id)
    }

    /// End to end with the real notification center: a reminder fires, then the app's sync step cancels its record.
    /// The delivered notification must stay in Notification Center; only an explicit cancel (Done, delete) removes it.
    func testDeliveredNotificationSurvivesSyncAndIsRemovedOnlyWhenAsked() async throws {
        try await requireSchedulingPermission()
        let center = UNUserNotificationCenter.current()
        let id = "probe-\(UUID().uuidString)"
        try await probe.schedule(id: id, title: "Delivery probe", secondsFromNow: 2)

        var delivered = false
        for _ in 0..<30 where !delivered {
            try await _Concurrency.Task.sleep(nanoseconds: 500_000_000)
            delivered = await center.deliveredNotifications().contains { $0.request.identifier == id }
        }
        XCTAssertTrue(delivered, "the scheduled notification is delivered by iOS")

        try await probe.cancelKeepingDelivered(id: id)
        try await _Concurrency.Task.sleep(nanoseconds: 500_000_000)
        let afterSync = await center.deliveredNotifications().map { $0.request.identifier }
        XCTAssertTrue(afterSync.contains(id), "syncing after it fired leaves the notification on screen")

        try await probe.cancel(id: id)
        try await _Concurrency.Task.sleep(nanoseconds: 500_000_000)
        let afterCancel = await center.deliveredNotifications().map { $0.request.identifier }
        XCTAssertFalse(afterCancel.contains(id), "an explicit cancel clears it")
    }

    func testRespectsTheSixtyFourNotificationLimit() async throws {
        let max = try await probe.maxPending()
        XCTAssertLessThanOrEqual(max.intValue, 64)
    }

    func testBootstrapRegistersActionsAndDelegate() async throws {
        let categories = await UNUserNotificationCenter.current().notificationCategories()
        let reminder = categories.first { $0.identifier == "DAYBLOOM_REMINDER" }
        XCTAssertNotNil(reminder, "reminder category with actions is registered at launch")
        XCTAssertEqual(reminder?.actions.map { $0.identifier }, ["DAYBLOOM_DONE", "DAYBLOOM_SNOOZE"])
        XCTAssertTrue(reminder?.options.contains(.customDismissAction) ?? false)
        XCTAssertNotNil(UNUserNotificationCenter.current().delegate, "Kotlin delegate is installed")
    }

    func testPermissionStatusIsReadable() async throws {
        let status = try await IosNotificationPermissionManager().status()
        XCTAssertNotNil(status.permission)
    }
}
