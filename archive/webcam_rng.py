#!/usr/bin/env python3
import argparse
import hashlib
import random
import sys

try:
    import cv2
except ImportError:  # pragma: no cover - user environment dependent
    print("Error: Missing dependency 'opencv-python'. Install with: pip install -r requirements.txt")
    sys.exit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate random numbers seeded from a webcam image."
    )
    parser.add_argument("--device", type=int, default=0, help="Webcam device index.")
    parser.add_argument("--warmup", type=int, default=5, help="Frames to discard.")
    parser.add_argument("--width", type=int, default=0, help="Capture width (optional).")
    parser.add_argument("--height", type=int, default=0, help="Capture height (optional).")
    parser.add_argument("--save", default="", help="Optional path to save the captured frame.")
    parser.add_argument(
        "--save-frame",
        action="store_true",
        help="Save the captured frame to a default file (seed.png).",
    )
    parser.add_argument("--count", type=int, default=5, help="How many random numbers to output.")
    parser.add_argument("--lower", type=int, default=0, help="Lower bound (inclusive).")
    parser.add_argument(
        "--upper",
        type=int,
        default=2**32,
        help="Upper bound (exclusive).",
    )
    parser.add_argument(
        "--coin-flip",
        action="store_true",
        help="Also output a heads/tails result for each number.",
    )
    return parser.parse_args()


def capture_frame(device: int, warmup: int, width: int, height: int):
    cap = cv2.VideoCapture(device)
    if not cap.isOpened():
        raise RuntimeError(f"Unable to open webcam device {device}.")

    if width > 0:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    if height > 0:
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)

    frame = None
    for _ in range(max(warmup, 0)):
        cap.read()

    ok, frame = cap.read()
    cap.release()
    if not ok or frame is None:
        raise RuntimeError("Failed to capture a frame from the webcam.")
    return frame


def seed_from_frame(frame) -> int:
    ok, buffer = cv2.imencode(".png", frame)
    if not ok:
        raise RuntimeError("Failed to encode frame for seeding.")
    digest = hashlib.sha256(buffer.tobytes()).digest()
    return int.from_bytes(digest, "big")


def main() -> int:
    args = parse_args()
    if args.upper <= args.lower:
        print("Error: --upper must be greater than --lower.")
        return 2

    try:
        frame = capture_frame(args.device, args.warmup, args.width, args.height)
        seed = seed_from_frame(frame)
    except RuntimeError as exc:
        print(f"Error: {exc}")
        return 1

    if args.save or args.save_frame:
        save_path = args.save if args.save else "seed.png"
        cv2.imwrite(save_path, frame)

    rng = random.Random(seed)
    print(f"Seed (sha256 int): {seed}")
    for _ in range(args.count):
        value = rng.randrange(args.lower, args.upper)
        if args.coin_flip:
            result = "heads" if value % 2 == 0 else "tails"
            print(f"{value} -> {result}")
        else:
            print(value)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
