# 🎧 AudioBridge - PC to Android Audio Streamer

A low-latency, open-source solution to stream system audio from a Windows PC to an Android device over Wi-Fi.

## 📸 Screenshots
| Windows Server | Android App |
| :---: | :---: |
| <img src="<img width="591" height="1212" alt="123" src="https://github.com/user-attachments/assets/688f7249-c882-4298-acac-b00e63eb1747" />" width="380" /> | <img src="<img width="463" height="660" alt="3131" src="https://github.com/user-attachments/assets/6fe46467-7adc-4ba9-826d-0a831376613d" />" width="220" /> |

## ⬇️ Download (For Users)
You do not need to build the code to use this app! Go to the **[Releases](../../releases)** tab on the right side of this page and download the latest version:
- **`app-release.apk`**: Install this on your Android device.
- **`AudioBridge-Server.exe`**: Run this on your Windows PC (No .NET installation required, just double-click to run).

## 🚀 How to Use
1. Run `AudioBridge-Server.exe` on your Windows PC.
2. Install and open the Android app.
3. Make sure **both devices are connected to the same Wi-Fi network**.
4. In the Android app, tap **"Auto-Discover PC"** (or enter the PC's IP manually).
5. Click **"Connect & Play"**. Your PC audio is now streaming to your phone!
*(Note: If Windows Firewall prompts you, make sure to click "Allow Access" for both UDP ports).*

## ✨ Features
- **System Audio Capture:** Captures all PC audio (Spotify, YouTube, Games) using WASAPI Loopback.
- **Low Latency:** Uses UDP with an Adaptive Jitter Buffer to achieve sub-50ms latency.
- **Background Service:** Android app runs as a Foreground Service, keeping audio alive when the screen is off.
- **Auto-Discovery:** Automatically finds the PC on the local network using UDP Broadcast.
- **App Exclusion:** Mute specific applications on the PC so their audio isn't streamed (e.g., mute a game while streaming music).
- **Modern UI:** Beautiful Material Design for Android and a sleek WPF UI for Windows.

## 🛠️ Build from Source (For Developers)
### Windows Server (C# / WPF)
1. Navigate to `windows-server`.
2. Ensure you have .NET 9 SDK installed.
3. Run `dotnet run`.

### Android App (Kotlin)
1. Navigate to `android-app`.
2. Ensure you have Android SDK and JDK 17 installed.
3. Run `./gradlew installDebug` (or `gradlew.bat installDebug` on Windows).

## 📄 License
This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

