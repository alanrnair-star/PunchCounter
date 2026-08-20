# Real-Time Punch Counter (Android prototype)

A native Android prototype that uses the phone camera and ML Kit Pose Detection to count alternating arm extensions in real time.

## What it does
- Live front-camera preview (falls back to rear camera)
- On-device pose detection in stream mode
- Top-right live punch counter
- Live punches/minute estimate
- Reset button
- Start/stop video recording with audio (when permission is granted)
- Saves recordings to `Movies/PunchCounter`

## Important prototype limitation
The live counter is drawn by the app UI **over the camera preview**, but CameraX records the camera stream itself. In this v0.1 prototype the number is therefore **not burned into the saved video pixels**. The live count remains visible while recording. A production version can add post-processing or a CameraX effect/Media3 pipeline to embed the counter into the exported video.

## Counting logic
The app tracks shoulder → elbow → wrist landmarks for both arms. An arm is "armed" when its elbow bends below 128°. A punch is registered when that elbow subsequently extends above 154°. A 115 ms global debounce prevents immediate double-counting. These thresholds are intentionally exposed as constants in `MainActivity.java` for calibration.

## Build
Recommended: Android Studio Quail 3 (2026.1.3) or newer.

1. Open this folder in Android Studio.
2. Allow Gradle sync to download dependencies.
3. Connect an Android phone with USB debugging enabled.
4. Run the `app` configuration.
5. Grant Camera and Microphone permission.

Minimum Android version: Android 6.0 (API 23).

## Accuracy guidance
For best results place the phone 2–4 m away, keep both shoulders/elbows/wrists visible, use good lighting, and avoid another person in the frame. This prototype should be calibrated against a manually verified sample before competition/record use.
