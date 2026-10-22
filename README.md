# 🎧 AudioBridge - PC to Android Audio Streamer

A low-latency, open-source solution to stream system audio from a Windows PC to an Android device over Wi-Fi.

## ⬇️ Download (For Users)
You do not need to build the code to use this app! Go to the **[Releases](../../releases)** tab on the right side of this page and download:
- `app-release.apk` (For your Android phone).
- `AudioStreamerPC.exe` (For your Windows PC - no setup required).

## ✨ Features
- **System Audio Capture:** Captures all PC audio (Spotify, YouTube, Games) using WASAPI Loopback.
- **Low Latency:** Uses UDP with an Adaptive Jitter Buffer to achieve sub-50ms latency.
- **Background Service:** Android app runs as a Foreground Service.
- **Auto-Discovery:** Automatically finds the PC on the local network using UDP Broadcast.
- **App Exclusion:** Mute specific applications on the PC so their audio isn't streamed.
- **Modern UI:** Beautiful Material Design for Android and WPF UI for Windows.

## 🛠️ Build from Source (For Developers)
### Windows Server
1. Navigate to `windows-server`.
2. Run `dotnet run`.

### Android App
1. Navigate to `android-app`.
2. Run `./gradlew installDebug`.

## 📄 License
This project is licensed under the MIT License.