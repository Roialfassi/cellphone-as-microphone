# Wireless Mic

An Android application that transforms your smartphone into a wireless microphone by capturing real-time audio and streaming it to:
- **Bluetooth speakers and headphones**
- **Wired audio devices (3.5mm, USB)**
- **📺 Smart TVs via WiFi (DLNA/Chromecast)**

## ✨ Features

| Feature | Description |
|---------|-------------|
| 🎤 **Real-time Audio Streaming** | Low-latency capture and playback |
| 🔊 **Bluetooth Support** | Works with Bluetooth A2DP speakers |
| 📺 **TV Streaming** | Stream to Smart TVs via DLNA or Chromecast |
| 🎧 **Wired Support** | Lower latency with wired connections |
| 🔇 **Volume & Mute** | In-app volume control and mute toggle |
| 🔊 **Audio Enhancements** | Noise suppression and echo cancellation |
| ⏱️ **Latency Monitoring** | Real-time buffer latency display |
| 📱 **Device Auto-Detection** | Automatic detection of connected audio devices |

## 📺 TV Streaming (New!)

Stream your microphone audio directly to your Smart TV via WiFi:

### Supported Devices
- **Chromecast** (dongle or built-in)
- **Android TV** with Chromecast built-in
- **DLNA/UPnP compatible TVs** (Samsung, LG, Sony, etc.)
- **Chromecast Audio**
- **Smart speakers** with Chromecast

### How It Works
1. Tap **"📺 Stream to TV (WiFi)"**
2. The app scans your network for compatible devices
3. Select your TV from the list
4. Audio from your phone's microphone streams to your TV's speakers!

```
┌─────────────────┐         ┌─────────────────┐
│                 │  WiFi   │                 │
│  📱 Phone       │ ──────► │  📺 Smart TV    │
│  (Microphone)   │         │   (Speakers)    │
│                 │         │                 │
└─────────────────┘         └─────────────────┘
```

## 📋 Prerequisites

- **Android Studio**: Iguana or newer
- **Android SDK**: API 34 (UpsideDownCake)
- **Java**: JDK 17 or higher
- **Physical Android Device**: Android 9.0 Pie (API 28) or higher
- **For TV streaming**: Phone and TV on the same WiFi network

## 🔧 Build Instructions

1. Clone this repository:
   ```bash
   git clone https://github.com/yourusername/cellphone-as-microphone.git
   cd cellphone-as-microphone
   ```

2. Open the project in Android Studio

3. Sync Gradle files (File > Sync Project with Gradle Files)

4. Set Gradle JDK to **Java 17** (File > Settings > Build Tools > Gradle)

5. Connect your physical Android device via USB

6. Click **Run > Run 'app'**

## 🎯 Usage Guide

### Local Streaming (Bluetooth/Wired)

| Step | Action |
|------|--------|
| 1 | Tap **"🔊 Connect Bluetooth/Wired"** to pair a speaker |
| 2 | Tap **"🎤 Start Streaming"** |
| 3 | Speak into your phone's microphone |
| 4 | Audio plays through the connected speaker |

### TV Streaming (WiFi)

| Step | Action |
|------|--------|
| 1 | Ensure phone and TV are on the same WiFi network |
| 2 | Tap **"📺 Stream to TV (WiFi)"** |
| 3 | Wait for device discovery (10 seconds) |
| 4 | Select your TV from the list |
| 5 | Tap **"🎤 Start Streaming"** if not already streaming |
| 6 | Audio plays through your TV's speakers! |

## 🔊 Supported Audio Devices

| Device Type | Local | TV Streaming |
|-------------|-------|--------------|
| Bluetooth A2DP | ✅ | N/A |
| Bluetooth SCO | ✅ | N/A |
| Wired Headset | ✅ | N/A |
| Wired Headphones | ✅ | N/A |
| USB Audio | ✅ | N/A |
| HDMI | ✅ | N/A |
| Chromecast | N/A | ✅ |
| Android TV | N/A | ✅ |
| DLNA Smart TV | N/A | ✅ |
| Chromecast Audio | N/A | ✅ |
| BLE Audio | ✅ | N/A |
| Hearing Aids | ✅ | N/A |

## 🛠 Troubleshooting

| Issue | Solution |
|-------|----------|
| **Feedback Loop** | Move away from the speaker or lower volume |
| **No Sound** | Check system media volume (physical buttons) |
| **Crash on Permission** | Ensure Android 9+ and accept all permissions |
| **High Latency** | Use wired connection instead of Bluetooth |
| **TV Not Found** | Ensure same WiFi network, TV is on, DLNA enabled |
| **Chromecast Not Found** | Make sure Google Cast is enabled on TV |
| **Audio Cuts Out** | Keep app in foreground |

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
├─────────────────────────────────────────────────────────────────┤
│  UI Layer                                                        │
│  ├── Status Display                                              │
│  ├── Device Controls (Bluetooth, TV)                            │
│  ├── Streaming Controls                                          │
│  └── Volume/Mute                                                 │
├─────────────────────────────────────────────────────────────────┤
│  Audio Engine (High-Priority Thread)                             │
│  ├── AudioRecord (Microphone Input)                             │
│  ├── AudioTrack (Local Output)                                  │
│  ├── NoiseSuppressor                                            │
│  └── AcousticEchoCanceler                                       │
├─────────────────────────────────────────────────────────────────┤
│  Network Streaming (WiFi)                                        │
│  ├── NetworkStreamingManager (Unified Interface)                │
│  ├── DlnaStreamingManager (DLNA/UPnP Discovery + HTTP Server)   │
│  └── CastStreamingManager (Google Cast via MediaRouter)         │
└─────────────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Purpose |
|-----------|---------|
| `MainActivity` | UI and audio capture/playback |
| `NetworkStreamingManager` | Coordinates all WiFi streaming |
| `DlnaStreamingManager` | Discovers UPnP devices, runs HTTP audio server |
| `CastStreamingManager` | Google Cast device discovery and control |
| `CastOptionsProvider` | Configures Google Cast SDK |

### Technical Details

- **Sample Rate**: 44.1 kHz (CD quality)
- **Audio Format**: 16-bit PCM Mono
- **Thread Priority**: `THREAD_PRIORITY_URGENT_AUDIO`
- **Network Streaming**: HTTP WAV stream on port 8080
- **Cast Protocol**: Google Cast SDK with MediaRouter

## 📁 Project Structure

```
app/
├── src/main/
│   ├── java/com/example/wirelessmic/
│   │   ├── MainActivity.kt           # Main UI and audio engine
│   │   ├── CastOptionsProvider.kt    # Google Cast configuration
│   │   └── streaming/
│   │       ├── NetworkStreamingManager.kt  # Unified streaming manager
│   │       ├── DlnaStreamingManager.kt     # DLNA/UPnP streaming
│   │       └── CastStreamingManager.kt     # Google Cast streaming
│   ├── res/
│   │   ├── layout/activity_main.xml  # UI layout
│   │   ├── drawable/                 # Background shapes
│   │   └── values/                   # Strings, colors, themes
│   └── AndroidManifest.xml           # Permissions & Cast provider
├── build.gradle.kts                  # Dependencies
└── proguard-rules.pro                # Release minification rules
```

## 🚀 Future Enhancements

- [ ] **Foreground Service**: Keep streaming when app is backgrounded
- [ ] **Audio Visualization**: Real-time waveform display
- [ ] **Multiple Mic Sources**: Front/back microphone selection
- [ ] **Audio Effects**: Reverb, EQ, pitch shifting
- [ ] **Recording**: Save streamed audio to file
- [ ] **Notification Controls**: Mute/unmute from notification
- [ ] **AirPlay Support**: Stream to Apple devices

## 📄 License

This project is open source and available under the [MIT License](LICENSE).

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

**Made with ❤️ for wireless audio enthusiasts**
