# Repository Guidelines

## Project Structure & Module Organization

HDUHelper is a single-module Android app using Kotlin, Jetpack Compose, and MIUIX. Application code lives in `app/src/main/java/moe/nepnep/hduhelper/`:

- `data/auth/`: CAS/SSO protocol, session storage, and authentication recovery.
- `data/settings/` and `data/network/`: preferences and connectivity monitoring.
- `ui/screens/`, `ui/components/`, and `ui/theme/`: screens, shared composables, and appearance.
- `ui/HDUHelperApp.kt` handles navigation; `ui/AppViewModel.kt` owns UI state.

Android resources are in `app/src/main/res/`. JVM tests live in `app/src/test/`; device tests in `app/src/androidTest/`. Dependency versions are centralized in `gradle/libs.versions.toml`; development utilities live in `tools/`.

## Build, Test, and Development Commands

Open the root project in Android Studio and configure the SDK through local `local.properties`. The project compiles against SDK 37, supports API 33+, and configures a JDK 25 Gradle daemon with Java 11 source compatibility.

Run from the repository root:

- `./gradlew :app:assembleDebug`: build `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:testDebugUnitTest`: run JVM unit tests.
- `./gradlew :app:lintDebug`: run Android Lint.
- `./gradlew :app:assembleDebugAndroidTest`: build the instrumentation APK.
- `./gradlew :app:connectedDebugAndroidTest`: run device tests on a connected emulator/device.
- `./gradlew :app:installDebug`: install the app; launch it from the device launcher.

## Coding Style & Naming Conventions

Follow Kotlin official style with four-space indentation. Use PascalCase for types, filenames, and screen composables (`ProfileScreen`); use camelCase for functions and properties. Keep protocol/storage logic in `data/` and UI state in the ViewModel. Follow existing MIUIX components and navigation patterns. Use Android Studio formatting and Android Lint; no dedicated ktlint or Detekt configuration exists.

## Testing Guidelines

Use JUnit 4, MockWebServer, and coroutine test utilities for unit tests; AndroidX JUnit, Compose testing, and Espresso for device tests. Name classes `*Test` and methods after the behavior tested. No coverage threshold is configured; add regression tests for authentication, storage, and UI behavior changes. Simulate credential failures rather than risking real-account lockouts. Live authentication is opt-in via `python3 tools/live-auth-smoke.py --serial DEVICE_SERIAL`; consult `docs/development.md` for WebView checks and signing/release commands.

## Commit & Pull Request Guidelines

History uses concise imperative subjects with `feat:` and `fix:` prefixes. Keep commits focused. PRs should describe behavior changes, link relevant issues, report validation commands/results, and include screenshots for UI changes.

## Security & Configuration

Never commit credentials, cookies, signing keys, or local SDK configuration. Preserve Keystore encryption and backup exclusions. Keep passwords out of logs, saved UI state, command arguments, and environment variables.
