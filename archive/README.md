# Webcam RNG

This repo includes:
- A Python CLI RNG that seeds from a webcam frame.
- A native Android app that captures a frame with CameraX and uses SecureRandom.

## Python CLI

### Setup

```
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### Usage

```
python webcam_rng.py
```

Options:

```
python webcam_rng.py --device 0 --count 10 --lower 0 --upper 1000 --save seed.png
python webcam_rng.py --save-frame
python webcam_rng.py --count 5 --coin-flip
```

Notes:
- The Python script uses `random.Random` and is not cryptographically secure.
- The first few frames are discarded to let the camera settle.

## Android (SecureRandom + CameraX)

Open the project in Android Studio, let Gradle sync, then run on a device.

Behavior:
- Captures a frame, hashes it with SHA-256, and seeds SecureRandom.
- Displays a 32-bit number and a heads/tails result.

Permissions:
- Camera permission is requested on first launch.
