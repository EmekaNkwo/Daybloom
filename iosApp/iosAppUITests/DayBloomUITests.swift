import XCTest

/// End-to-end flows driven through the real UI, the way a person would use the app. Each test creates items with a
/// unique name so it doesn't depend on what earlier runs left behind.
final class DayBloomUITests: XCTestCase {
    private var app: XCUIApplication!
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
        completeOnboardingIfNeeded()
        // A level-up earned in an earlier run is celebrated on the next launch; acknowledge it like a person would.
        let celebration = element("Keep growing")
        if celebration.waitForExistence(timeout: 3) { celebration.tap() }
        XCTAssertTrue(element("Today").waitForExistence(timeout: 15), "main tabs are shown")
    }

    // MARK: - Flows

    func testQuickAddThenCompleteAndUndo() throws {
        let title = uniqueName("Milk")
        quickAdd("\(title) today at 11pm")
        search(title)
        tap("Complete \(title)")
        tap("Done")
        XCTAssertTrue(element("Mark \(title) as not done").waitForExistence(timeout: 5), "completed task is in Done")
        tap("Mark \(title) as not done")
        tap("Open")
        XCTAssertTrue(element("Complete \(title)").waitForExistence(timeout: 5), "completion can be undone")
    }

    func testEditingWarnsBeforeDiscardingChanges() throws {
        let title = uniqueName("Edit")
        quickAdd("\(title) today at 11pm")
        search(title)
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
        if !row.waitForExistence(timeout: 5) { print("=== TREE (search) ===\n\(app.debugDescription)\n=== END ===") }
        XCTAssertTrue(row.exists, "search finds the new task")
        row.tap()

        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap() // cursor at the end of the title
        app.typeText(" renamed")
        tap("Back")
        XCTAssertTrue(element("Discard changes?").waitForExistence(timeout: 3), "leaving with edits asks first")
        tap("Keep editing")
        tap("Save")
        XCTAssertTrue(element(containing: "\(title) renamed").waitForExistence(timeout: 5), "saved title shows in the search results")
    }

    func testCreateHabitAndTickItOff() throws {
        let name = uniqueName("Habit")
        tap("Habits")
        tap("New habit")
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        app.typeText(name)
        tap("Save")
        XCTAssertTrue(reveal(element("Complete \(name) today")), "habit is listed")
        tap("Complete \(name) today")
        XCTAssertTrue(reveal(element("Undo \(name) today")), "habit is ticked for today")
    }

    func testCreateAppointmentShowsInCalendar() throws {
        let name = uniqueName("Appt")
        tap("Calendar")
        tap("New appointment")
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        app.typeText(name)
        tap("Save")
        XCTAssertTrue(reveal(element(containing: name)), "appointment shows in the agenda")
    }

    func testFocusSessionStartPauseStop() throws {
        tap("Focus")
        tap("Start focusing")
        XCTAssertTrue(element("Pause").waitForExistence(timeout: 5), "timer is running")
        tap("Pause")
        XCTAssertTrue(element("Resume").waitForExistence(timeout: 3))
        tap("Resume")
        tap("Stop")
        XCTAssertTrue(element("Start focusing").waitForExistence(timeout: 5), "session ended")
    }

    func testEveryTabOpens() throws {
        for (tab, title) in [("Tasks", "Tasks"), ("Calendar", "Calendar"), ("Habits", "Habits"), ("Bloom", "Bloom Garden"), ("Today", "TODAY'S PRODUCTIVITY")] {
            tap(tab)
            XCTAssertTrue(element(title, excludingButtons: true).waitForExistence(timeout: 5), "\(tab) shows \(title)")
        }
    }

    func testCreateReminderThenDeleteAndUndo() throws {
        let title = uniqueName("Call")
        tap("Settings")
        tap("Reminder status & permissions")
        tap("New")
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        app.typeText(title)
        tap("Save")
        XCTAssertTrue(reveal(element("More for \(title)")), "new reminder is listed (it defaults to the next full hour, so it's accepted)")

        tap("More for \(title)")
        tap("Delete")
        XCTAssertTrue(element("Undo").waitForExistence(timeout: 5), "deleting offers Undo")
        tap("Undo")
        XCTAssertTrue(reveal(element("More for \(title)")), "undo brings the reminder back")
    }

    func testTurningOffGamificationHidesTheGardenAndBack() throws {
        tap("Settings")
        let toggle = element(beginningWith: "XP, quests & Bloom Garden")
        XCTAssertTrue(reveal(toggle), "gamification setting is shown")
        toggle.tap()
        tap("Back")
        tap("Bloom")
        XCTAssertTrue(element("Gamification is off", excludingButtons: true).waitForExistence(timeout: 5), "Bloom explains it's off")

        // Turn it back on from the Bloom tab so later runs start from the default.
        tap("Open settings")
        let again = element(beginningWith: "XP, quests & Bloom Garden")
        XCTAssertTrue(reveal(again))
        again.tap()
        tap("Back")
        tap("Bloom")
        XCTAssertTrue(element("Bloom Garden", excludingButtons: true).waitForExistence(timeout: 5), "garden is back")
    }

    func testPlanMyDayStarsATopPriority() throws {
        let title = uniqueName("Plan")
        quickAdd("\(title) today")
        tap("Today")
        openPlanner()
        // Up to three top priorities are allowed and earlier runs may have used them; clear them first.
        let existingTop = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Remove ' AND label ENDSWITH ' from top priorities'")).firstMatch
        for _ in 0..<3 where reveal(existingTop) { existingTop.tap() }
        tap("Make \(title) a top priority")
        XCTAssertTrue(element("Remove \(title) from top priorities").waitForExistence(timeout: 3), "star toggles on")
        tap("Save plan")
        let keep = element("Keep my plan")
        if keep.waitForExistence(timeout: 2) { keep.tap() } // the sample day may already be over capacity
        XCTAssertTrue(element("TODAY'S PRODUCTIVITY", excludingButtons: true).waitForExistence(timeout: 5), "saving returns to Today")
        XCTAssertTrue(reveal(element("Edit plan")), "once saved, Today offers to edit the plan")

        tap("Edit plan")
        XCTAssertTrue(reveal(element("Remove \(title) from top priorities")), "the saved plan keeps the priority")
    }

    // MARK: - Helpers

    /// Adds a task through quick capture and waits until the sheet has closed (i.e. it was saved).
    private func quickAdd(_ text: String) {
        tap("Add")
        app.typeText(text)
        sleep(1) // the sheet slides up with the keyboard; tapping mid-animation can land outside it
        tap("Add task")
        XCTAssertTrue(element("Add task").waitForNonExistence(timeout: 5), "the sheet closes once the task is saved")
    }

    /// Searches the Tasks tab for [text] and puts the keyboard away so results can be tapped.
    private func search(_ text: String) {
        tap("Tasks")
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "keyboard is up")
        app.typeText(text)
        // Fast typing must land exactly: the field once lagged behind the filtered list and lost or reordered keys.
        let typed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", text), object: field)
        XCTAssertEqual(XCTWaiter().wait(for: [typed], timeout: 3), .completed, "search field shows exactly what was typed, got '\(field.label)'")
        app.keyboards.buttons["search"].tap()
        sleep(1)
    }

    private func uniqueName(_ prefix: String) -> String {
        "\(prefix)\(Int(Date().timeIntervalSince1970) % 100_000)"
    }

    private func element(_ label: String, excludingButtons: Bool = false) -> XCUIElement {
        let predicate = excludingButtons
            ? NSPredicate(format: "elementType != %d AND label == %@", XCUIElement.ElementType.button.rawValue, label)
            : NSPredicate(format: "label == %@", label)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    /// Today's planner button reads "Plan my day" until a plan is saved, then "Edit plan" (an earlier run may have saved one).
    private func openPlanner() {
        let button = app.buttons.matching(NSPredicate(format: "label == 'Plan my day' OR label == 'Edit plan'")).firstMatch
        XCTAssertTrue(reveal(button), "planner button is on Today")
        button.tap()
    }

    private func element(beginningWith prefix: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
    }

    /// A button or text whose label contains [text] — never a text field, whose typed value would match trivially.
    private func element(containing text: String) -> XCUIElement {
        let predicate = NSPredicate(format: "(elementType == %d OR elementType == %d) AND label CONTAINS %@",
                                    XCUIElement.ElementType.button.rawValue, XCUIElement.ElementType.staticText.rawValue, text)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    /// Waits for [target], scrolling down if needed: lazy lists only build rows near the screen.
    private func reveal(_ target: XCUIElement) -> Bool {
        let found = scan(for: target)
        // A list still gliding from a swipe treats the next tap as "stop scrolling", not as a tap on the row.
        sleep(1)
        return found
    }

    private func scan(for target: XCUIElement) -> Bool {
        if target.waitForExistence(timeout: 3) && target.isHittable { return true }
        // Rows can sit above or below the viewport (e.g. a completed task re-sorts), so start from the top.
        for _ in 0..<6 { app.swipeDown() }
        if target.exists && target.isHittable { return true }
        for _ in 0..<10 {
            app.swipeUp()
            if target.exists && target.isHittable { return true }
        }
        if !target.exists { print("=== TREE (not revealed) ===\n\(app.debugDescription)\n=== END ===") }
        return target.exists
    }

    private func tap(_ label: String, file: StaticString = #filePath, line: UInt = #line) {
        let target = element(label)
        if !reveal(target) {
            print("=== TREE (missing \(label)) ===\n\(app.debugDescription)\n=== END ===")
        }
        XCTAssertTrue(target.exists, "\(label) exists", file: file, line: line)
        target.tap()
    }

    private func completeOnboardingIfNeeded() {
        let begin = element("Let's begin")
        guard begin.waitForExistence(timeout: 4) else { return }
        begin.tap()
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 4) { allow.tap() }
    }
}
