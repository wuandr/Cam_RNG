# Cam RNG

Minimal Android app that uses the device's camera app to capture a full-resolution photo and seed a `SecureRandom` coin flip.

## Setup

1. Install Android Studio (or the Android SDK + platform tools).
2. Open this folder as a project in Android Studio.
3. When prompted, let Android Studio install the required SDKs (compileSdk 34).
4. Ensure `local.properties` is created with your SDK path (Android Studio does this automatically).

Optional: If building from the command line, make sure the `ANDROID_HOME` or `ANDROID_SDK_ROOT` environment variable points to your SDK.

## Build APK

From Android Studio:
1. `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`
2. APK output appears under `app/build/outputs/apk/`.

From the command line (requires Gradle installed):

```bash
gradle assembleDebug
```

APK output appears under:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Run on device

- Connect a device with USB debugging enabled, then click Run in Android Studio.
- The app launches the default camera app when you tap "Flip coin".
