# Wireless Mic MVP

This Android application transforms your smartphone into a wireless microphone by capturing real-time audio and streaming it to the system's active output device (Bluetooth Speaker, Wired Headphones, etc.).

## Features

*   **Real-time Audio Streaming**: Low-latency capture and playback.
*   **Bluetooth Support**: Seamlessly works with Bluetooth A2DP speakers via Android System settings.
*   **Volume Control**: In-app volume adjustment.
*   **Mute Toggle**: Quickly mute the microphone stream.
*   **Latency Monitoring**: Estimated buffer latency display.
*   **Permission Handling**: proper support for Audio and Bluetooth permissions (Android 9.0+).

## Prerequisites

*   Android Studio Iguana or newer.
*   Android SDK 34 (UpsideDownCake).
*   Physical Android Device (Android 9.0 Pie or higher).
    *   *Note: Emulators generally do not support Bluetooth connections or realistic audio loopback testing.*

## Build Instructions

1.  Clone this repository.
2.  Open the project in Android Studio.
3.  Sync Gradle files.
4.  Connect your physical Android device via USB (ensure USB Debugging is enabled).
5.  Click **Run > Run 'app'**.

## End-to-End Testing Instructions

Since this app relies on external hardware (speakers) and system-level audio routing, manual end-to-end testing is required.

### 1. Setup
1.  Install the app on your Android Phone.
2.  Have a **Bluetooth Speaker** or **Wired Aux Cable** ready.

### 2. Test Flow: Bluetooth Connection
1.  **Launch the App**: Open "Wireless Mic MVP".
    *   *Verify*: Status says "Disconnected". Output Device likely says "Internal Speaker".
2.  **Connect Speaker**:
    *   Tap the **"Connect Device (Settings)"** button. This opens the Android Bluetooth Settings.
    *   Pair and connect to your Bluetooth Speaker.
    *   Return to the App.
    *   *Verify*: The "Output Device" label should update to "Bluetooth A2DP (Address...)" or similar within 3 seconds.
3.  **Start Streaming**:
    *   Tap **"Start Streaming"**.
    *   Grant the requested permissions (Microphone, Nearby Devices).
    *   *Verify*: Status changes to "Streaming".
4.  **Audio Loopback**:
    *   Speak into the phone's microphone.
    *   *Verify*: You hear your voice coming out of the Bluetooth Speaker.
    *   *Note*: There will be a slight delay (latency). This is normal for Bluetooth.
5.  **Volume & Mute**:
    *   Adjust the "Volume" slider. *Verify* the output volume on the speaker changes.
    *   Check the "Mute" box. Speak again. *Verify* no sound comes out of the speaker. Uncheck it.
6.  **Stop**:
    *   Tap **"Stop Streaming"**.
    *   *Verify*: Sound stops immediately.

### 3. Test Flow: Wired Connection (Lower Latency)
1.  Plug in a generic 3.5mm Aux cable connected to a speaker (or wired headphones).
2.  Launch the app.
3.  *Verify*: Output Device updates to "Wired Headset".
4.  Start Streaming.
5.  *Verify*: The latency should be noticeably lower than Bluetooth.

## Troubleshooting

*   **Feedback Loop (Squealing Noise)**: This happens if the microphone is too close to the speaker. Move away from the speaker or lower the volume.
*   **No Sound**: Ensure the system media volume is up (using the physical buttons on the phone).
*   **Crash on Permission**: If the app crashes on start, ensure you are on Android 9+ and have accepted permissions.

## Architecture

*   **Audio Engine**: Uses `AudioRecord` (Input) directly feeding `AudioTrack` (Output) in a dedicated high-priority thread.
*   **Latency**: Configured for `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY` implicitly by using minimal buffer sizes.
