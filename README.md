# WebCam RNG (Android)

Native Android app that captures a camera frame, hashes it, and seeds
`SecureRandom` to generate a number and a heads/tails result.

## Requirements

- Android Studio (Giraffe/Koala or newer)
- Android SDK 34
- A device or emulator with a camera

## Run

1. Open this repo in Android Studio.
2. Let Gradle sync.
3. Run the app on a device/emulator.
4. Grant camera permission when prompted.

## Behavior

- Tap "Capture & Generate" to capture a frame and seed `SecureRandom`.
- The app displays a 32-bit number and a coin flip result.

## Archived Python CLI

The original Python script and its README are in `archive/`.
