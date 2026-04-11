# NetworkShare

> **Wireless file sharing powered by WebDAV — no internet, no USB, no limits.**

NetworkShare turns your Android phone into a WebDAV file server on your local network. Browse, download, upload, and stream files from any device on the same network — Windows, Mac, Linux, Android, or iOS. No cloud. No cables. No middleman.

---

## What It Does

Most file transfer apps either need the internet, require a USB cable, or lock you into their own ecosystem. NetworkShare does none of that. It runs a full WebDAV server directly on your phone, making your storage accessible to any device that supports WebDAV — which is basically everything.

Connect your PC to your phone's hotspot, open File Explorer, type in the address NetworkShare shows you, and your phone's folders appear like a network drive. It's that simple.

---

## Features

### 📡 WebDAV Server
- Runs a fully compliant WebDAV server on your local network
- Digest authentication to protect your files with a username and password
- Session caching for seamless compatibility with Windows WebDAV clients (which make multiple rapid requests per operation)
- Supports multiple storage roots simultaneously — internal storage, SD card, and USB OTG at the same time
- Binds to the correct network interface automatically (hotspot, Wi-Fi, USB tethering)

### 🗂️ File Management
- Choose exactly which folders to share — not your entire storage
- Nested folder selection with visual inheritance indicators
- Real-time file browsing directly from the app
- Upload files from any WebDAV client directly to your phone

### 🌐 Network Intelligence
- **Network Trust System** — mark Wi-Fi networks as Allowed, Blocked, or Allow Once
- Hotspot and USB tethering are always trusted automatically
- Unknown network detection with instant notification and one-tap trust actions
- Blocked network notification so you always know why sharing isn't active

### 🎬 Media Streaming
- Stream video and audio files directly in compatible media players (VLC, Infuse, Kodi, etc.)
- No transcoding — files stream at their original quality
- Works over hotspot for truly cable-free media playback

### 🔒 Security
- Biometric and PIN authentication gate — the app locks after 25 seconds of inactivity
- Digest authentication on the WebDAV server itself
- Per-network trust control so you never accidentally share on an untrusted network
- File safety lock — protects original files if an upload fails mid-transfer

### 🔔 Notifications
- Persistent foreground notification while sharing is active with a one-tap turn off action
- Transfer progress notifications for uploads and downloads with cancel support
- Unknown network alerts with Allow / Allow Once / Block actions directly from the notification
- Blocked network notification
- No-network notification when the server is on but no valid network is detected

### ⚙️ Reliability
- Survives app kills — after OEM battery optimization kills
- CPU wake lock and high-performance Wi-Fi lock to keep transfers stable
- Automatic server restart when network interfaces change
- Pull-to-refresh to force re-broadcast server addresses

### 🎨 UI & UX
- Clean, modern Material 3 design
- Dark, Light, and System theme support
- Supports Android 8.0 through Android 15
- Edge-to-edge display with proper insets handling
- Draggable scrollbar for long folder lists

---

## Connecting to NetworkShare

Once sharing is turned on, the app shows you the server addresses. Use them like this:

| Device | How to Connect |
|---|---|
| **Windows** | Open File Explorer → type the address in the address bar → enter your username and password |
| **Mac** | Finder → Go → Connect to Server (`⌘K`) → paste the address |
| **Linux** | File manager → Connect to Server → WebDAV |
| **Android** | Use a WebDAV client like Cx File Explorer, Solid Explorer, or ES File Manager |
| **iOS** | Files app → `...` → Connect to Server, or use a WebDAV app |
| **Media Players** | VLC, Infuse, Kodi — open network stream with the address |

---

## Requirements

- Android 8.0 (API 26) or higher
- `MANAGE_EXTERNAL_STORAGE` permission (Android 11+) or `READ/WRITE_EXTERNAL_STORAGE` (Android 10 and below)
- Local network (Wi-Fi, hotspot, or USB tethering)

---

## Building from Source

1. Clone the repository
   ```
   git clone https://github.com/danielezeobinna/NetworkShare.git
   ```

2.  The app uses AdMob for ads. If you're building for personal/development use, you can replace the AdMob IDs in local.properties with the [AdMob test IDs](https://developers.google.com/admob/android/test-ads) or remove the ad integration entirely.

3. Open the project in Android Studio (Hedgehog or newer recommended)

4. Build and run on a device running Android 8.0+

> **Note:** The app requires a real device for full functionality. The WebDAV server, biometric authentication, and network detection do not work properly on emulators.

---

## Permissions

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Access files across all storage locations |
| `FOREGROUND_SERVICE` | Keep the WebDAV server running while the app is in the background |
| `ACCESS_WIFI_STATE` / `ACCESS_FINE_LOCATION` | Read the current Wi-Fi SSID for network trust evaluation |
| `POST_NOTIFICATIONS` | Show server status and transfer progress notifications |
| `WAKE_LOCK` | Prevent the CPU from sleeping during active file transfers |
| `INTERNET` | Run the local WebDAV server (no external internet connection is made) |

---

## Tech Stack

- **Kotlin** + **Jetpack Compose** — UI
- **NanoHTTPD** — embedded HTTP/WebDAV server
- **Material 3** — design system
- **AndroidX Biometric** — authentication
- **Google Mobile Ads** — AdMob integration

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

## Developer

Built by **Daniel Eze**

---

*NetworkShare — your files, your network, your rules.*
