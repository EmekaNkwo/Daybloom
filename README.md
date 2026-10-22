# DayBloom

An offline-first daily planner for Android and iOS, built with Kotlin Multiplatform and Compose Multiplatform.
Tasks, appointments, habits, reminders, focus sessions and a small gamified "bloom garden" share one Kotlin
codebase; only notifications, storage paths and a few platform services are written per platform.

All data stays on the device. There is no account, server or network access.

## Features

- **Tasks** with natural-language quick capture ("Pay rent tomorrow at 9 AM !!", "#hashtags"), priorities,
  recurrence and a done list
- **Calendar** with agenda, day, week and month views across tasks and appointments
- **Planner** that fits the day's tasks to your available capacity and handles unfinished tasks
- **Habits** with weekly targets and streaks
- **Reminders** with recurrence, time-zone pinning and visible delivery status
- **Focus** timer linked to tasks, with daily totals
- **Insights** and a shareable monthly recap (exported as a text file); a configurable productivity score
- **Bloom garden**: points, achievements and unlockable themes for completed work
- Daily reflection, onboarding, settings and a full local data reset

## Project layout

```
composeApp/            Shared Kotlin module (UI, domain, data) → Android library + iOS framework
  src/commonMain/      domain/ (models, use cases, services), data/ (Room), features/ (screens + ViewModels),
                       notifications/, gamification/, design/, navigation/, di/ (Koin)
  src/androidMain/     AlarmManager/WorkManager notifications, receivers, Android platform services
  src/iosMain/         UNUserNotificationCenter adapter, iOS platform services, MainViewController
  src/commonTest/      Domain and use-case tests with hand-written fakes (testing/Fakes.kt, TestGraph.kt)
  src/androidUnitTest/ Robolectric tests: Room persistence, migrations, Android notifications
  src/androidInstrumentedTest/  On-device tests: database, alarms, channels, permissions
  src/iosTest/         Kotlin/Native persistence tests
  schemas/             Exported Room schemas (used by migration tests)
androidApp/            Android application shell (Application, Activity, manifest, resources)
iosApp/                Xcode project: SwiftUI host app + iosAppTests (XCTest)
```

## Requirements

| Tool | Version |
|---|---|
| JDK | 17+ |
| Android SDK | compileSdk/targetSdk 36, minSdk 26 |
| Xcode | 16.x (iOS deployment target 16.0) |
| Kotlin | 2.2.21 (see `gradle/libs.versions.toml`) |

Kotlin 2.2 is pinned on purpose: it still supports the x86_64 iOS simulator and Xcode 16.

## Building and running

### Android

```bash
./gradlew :androidApp:assembleDebug      # APK in androidApp/build/outputs/apk/debug/
./gradlew :androidApp:installDebug       # install on a running emulator or device
```

Or open the project in Android Studio and run the `androidApp` configuration.

### iOS

Open `iosApp/iosApp.xcodeproj` in Xcode, choose a simulator, and run the `iosApp` scheme. The build phase calls
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, so the shared framework is rebuilt automatically.

To run on a physical device, set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig`.

`gradle.properties` sets `kotlin.native.cacheKind=none` because Kotlin 2.2's prebuilt caches reference symbols that
Xcode 16's SDK lacks. Remove it when building with Xcode 26+ for faster debug builds.

## Testing

```bash
./gradlew :composeApp:testDebugUnitTest          # commonTest + Robolectric tests on the JVM
./gradlew :composeApp:iosX64Test                 # commonTest + iosTest on the iOS simulator (Intel Macs)
./gradlew :composeApp:iosSimulatorArm64Test      # same, Apple silicon
./gradlew :composeApp:connectedDebugAndroidTest  # on-device tests (needs a running emulator/device)
```

Swift-side notification tests live in `iosApp/iosAppTests` and run from Xcode (Product → Test) or:

```bash
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

`commonTest` is not included in the instrumented (on-device) test APK: its backtick test names contain spaces,
which the Android dexer rejects below API 30.

## Notifications

- **Android** posts through `AlarmManager`. Exact alarms are used when exact-alarm access is granted; otherwise
  inexact allow-while-idle alarms are used and the app says that delivery may be a few minutes late. Alarms are
  rescheduled after reboot, time changes and permission changes.
- **iOS** uses `UNUserNotificationCenter`. iOS allows 64 pending notifications per app, so DayBloom schedules the
  nearest occurrences and tops the queue up when the app becomes active.
- The Reminders screen can send a test notification to check delivery on the device.
