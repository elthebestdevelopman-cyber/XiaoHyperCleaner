# 🤖 XiaoHyperCleaner

**No root. No bloat. No data.**

<div align="center">

[![Version](https://img.shields.io/badge/version-1.0--beta2-blue)](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases)
[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen)](https://www.android.com/)
[![License](https://img.shields.io/badge/license-CC%20BY--NC%204.0-orange)](https://creativecommons.org/licenses/by-nc/4.0/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple)](https://kotlinlang.org/)

**🇷🇺 [Русский](README.md)** | 🇬🇧 English

</div>

A tool for disabling system analytics and ads on **Xiaomi**, **Redmi**, and **Poco** devices via local ADB. Works without root access, all changes are fully reversible.

---

## 📋 Table of Contents

- [About](#-about)
- [Features](#-features)
- [Requirements](#-requirements)
- [Installation](#-installation)
- [Usage](#-usage)
- [Project Structure](#-project-structure)
- [Development](#-development)
- [Testing](#-testing)
- [FAQ](#-faq)
- [Roadmap](#-roadmap)
- [License](#-license)
- [Author](#-author)
- [Support the Project](#-support-the-project)

---

## 🎯 About

**XiaoHyperCleaner** is an app for Xiaomi, Redmi, and Poco device owners who are tired of system analytics and ads built into MIUI / HyperOS.

The app connects to the device via **local ADB** (`127.0.0.1`) and applies settings that previously required manual console commands:

- ✅ **No root required** — works via standard ADB
- ✅ **No system modification** — doesn't touch system partitions
- ✅ **No data collection** — everything runs locally on the device
- ✅ **Fully reversible** — one click to undo
- ✅ **Transactional rollback** — any failure automatically rolls back all changes

---

## ✨ Features

### Main Functions

| Function | Description |
|----------|-------------|
| 🚫 **Disable analytics** | `com.miui.analytics`, `com.xiaomi.ab`, `com.miui.bugreport`, etc. |
| 🛑 **Disable ads** | `com.xiaomi.ad`, `com.miui.ad`, `com.miui.systemAdSolution` |
| 🤖 **Disable recommendations** | `com.miui.msa.core`, `com.miui.personalassistant`, `com.miui.smartassistant` |
| ⚙️ **Optimize parameters** | Animations, background process limits, battery saving |
| 🌐 **DNS filter (optional)** | Block ad domains via AdGuard DNS |
| 🔄 **Full rollback** | All changes can be undone with one button |

### Highlights

- 📚 **Onboarding** on first launch (3 short screens)
- 🎛️ **Options dialog** before optimization
- ⚠️ **DNS warning** — explains potential side effects
- 📊 **Detailed report** after optimization:

```
✅ Disabled services: 7
✅ Applied parameters: 12
⚠️ Failed: 2 (protected by system)
```

- 🤖 **Cute robot cat** on the splash screen rolling a yarn ball
- 📝 **Log sharing** — "Share log" button in the menu
- 🌍 **Localization** — RU and EN, always in sync
- 🌙 **Dark theme** — follows system settings

---

## 📱 Requirements

| Parameter | Value |
|-----------|-------|
| **Devices** | Xiaomi / Redmi / Poco |
| **Firmware** | MIUI 12+ or HyperOS |
| **Android** | 10+ (API 29+) |
| **Root** | ❌ Not required |
| **Wireless debugging** | Enabled automatically |

---

## 📥 Installation

### Option 1: APK from Releases

1. Download `XiaoHyperCleaner-v1.0-beta2.apk` from [Releases](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases)
2. Allow installation from unknown sources
3. Install and launch
4. Go through onboarding (3 screens)

### Option 2: Build from Source

```bash
git clone https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner.git
cd XiaoHyperCleaner
./gradlew assembleDebug
```

APK will appear in `app/build/outputs/apk/debug/`

### ⚠️ Important for Android 13+ (MIUI 14 / HyperOS)

On Android 13+, the system blocks sideload apps from accessing "restricted settings" (called "forbidden settings" in some firmware). This is a **system Android limitation**, not an app bug.

**How to bypass:**

1. Open **Settings → Apps → XiaoHyperCleaner**
2. Tap **⋮** in the top-right corner
3. Select **"Allow restricted settings"** (or "Forbidden settings" in HyperOS)
4. Confirm with fingerprint/password
5. Return to the app

This is done **only once**. The app will prompt you to do this when needed.

---

## 🚀 Usage

### First Launch

1. Launch the app
2. Go through **onboarding** (3 screens)
3. Tap **"Optimize"** on the main screen
4. Choose options (enable/disable DNS filter)
5. Enable the **accessibility service** (required only once)
6. The app **automatically**:
   - Enables wireless debugging
   - Connects to ADB
   - Applies all settings
   - Shows a **detailed report**

### Rolling Back Changes

1. Main screen → tap **"Undo optimization"**
2. Confirm the action
3. All packages are re-enabled
4. All system parameters return to original values
5. DNS returns to system default (if it was enabled)

### Sharing Logs

If something went wrong:

1. Open the **⋮ menu** in the top-right corner
2. Tap **"Share log"**
3. Send the `xhc.log` file to [Issues](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/issues)

All sensitive data in the log is masked: IP addresses (except 127.0.0.1), long tokens, paths to user data, system setting values.

---

## 🏗 Project Structure

```
app/src/main/java/com/xiaohypercleaner/
├── data/
│   ├── AdbClient.kt              # ADB over TCP (%04x header, shell until EOF)
│   ├── AdbExecutor.kt            # Interface for DI/tests
│   ├── AdbPortResolver.kt        # mDNS _adb-tls._tcp
│   ├── OptimizationEngine.kt     # 4 methods + DNS + transactional rollback
│   ├── PreferencesManager.kt     # DataStore
│   └── ServiceRegistry.kt        # Package lists for disabling
├── service/
│   ├── AdbEnablerService.kt      # Accessibility chain
│   ├── OverlayController.kt      # onCancel via WeakReference
│   └── OverlayService.kt         # Progress overlay
├── ui/
│   ├── MainActivity.kt           # Main screen
│   ├── MainViewModel.kt          # UI logic
│   ├── SplashActivity.kt         # Splash with robot and yarn ball
│   ├── OnboardingScreen.kt       # Onboarding (3 screens)
│   ├── WebViewActivity.kt        # WebView for donations
│   └── components/
│       └── Dialogs.kt            # All dialogs
├── util/
│   ├── AppLog.kt                 # Beta logging with masking
│   ├── LogMasker.kt              # Data masking in logs
│   ├── OptimizationNotifier.kt   # StateFlow for passing results
│   └── Wait.kt                   # waitFor helper
├── AppConstants.kt               # Constants (timeouts, progress)
├── AppDependencies.kt            # Manual DI
└── XiaoHyperApp.kt               # Application
```

---

## 💻 Development

### Tech Stack

| Component | Version |
|-----------|---------|
| **Gradle** | 9.5 |
| **AGP** | 9.3.1 (built-in Kotlin) |
| **Kotlin** | 2.4.10 |
| **Compose BOM** | 2026.06.01 |
| **compileSdk** | 37 |
| **targetSdk** | 36 |
| **minSdk** | 28 |
| **JDK** | 21 |

### Build

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

### Architectural Decisions

- **Manual DI** via `AppDependencies` — no Dagger/Hilt
- **AccessibilityService** for UI action automation
- **OverlayService** for progress display
- **StateFlow** for reactive UI
- **DataStore** for persistence
- **Coroutines** for asynchronous operations
- **Transactional rollback** in `OptimizationEngine`

---

## 🧪 Testing

### Unit Tests (29 tests)

```bash
./gradlew testDebugUnitTest
```

| File | Tests | What is tested |
|------|-------|----------------|
| `OptimizationEngineTest` | 12 | Optimization, rollback, DNS, transactions |
| `AdbPortResolverTest` | 3 | mDNS discovery, mergePorts |
| `LogMaskerTest` | 6 | IP, token, path masking |
| `MainViewModelTest` | 8 | UI logic (Robolectric) |

### How to Submit a Bug Report

Open an [Issue](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/issues) and provide:

```
Model: Xiaomi Redmi Note 10 Pro
Firmware: MIUI 14.0.4
Android: 13
Problem: item "Wireless debugging toggle" — doesn't enable automatically
Log: attached
Screenshot: attached
```

The **log** can be obtained via the ⋮ menu → "Share log".

---

## ❓ FAQ

### Why doesn't the app work on Samsung / Realme / others?

Technically, ADB works everywhere, but the **package lists for disabling are specific to MIUI / HyperOS**. On other firmware, the app will disable the wrong packages or nothing at all. Currently, only Xiaomi / Redmi / Poco are supported.

### Will this break firmware updates?

No. When MIUI / HyperOS is updated, all disabled packages **will be restored automatically** (they're system packages). After the update, you'll need to tap "Optimize" again.

### What if something doesn't work after rollback?

All app commands are standard ADB commands. If problems occur:

1. Reboot the device
2. If that doesn't help — factory reset (as a last resort)

No such cases have occurred during testing, but good to know.

### Why isn't the app on Google Play?

Google Play policy **prohibits** the use of Accessibility Services for UI action automation (tapping buttons, toggling switches). This is the primary use of our service. Therefore, the app is distributed via **RuStore**, **GetApps**, and **GitHub**.

### Can I see what the app does?

Yes, the project is fully open. All ADB commands are logged to `xhc.log` (in beta builds). Anyone can verify exactly what is executed.

---

## 🗺 Roadmap

### v1.1 (next version)

- [ ] Optimization profiles: "Light" / "Medium" / "Maximum"
- [ ] Optimization history in DataStore
- [ ] Settings export/import
- [ ] More optimization methods (appops, suspend)
- [ ] Improved onboarding with video

### v2.0 (future)

- [ ] Support for other firmware (OneUI, ColorOS)
- [ ] Plugins for extended optimization
- [ ] Cloud settings sync
- [ ] Quick-launch widget

---

## 📄 License

**CC BY-NC 4.0** (Creative Commons Attribution-NonCommercial 4.0)

- ✅ Usage allowed
- ✅ Modification allowed
- ✅ Distribution allowed
- ❌ Commercial use prohibited

Full text: [creativecommons.org/licenses/by-nc/4.0](https://creativecommons.org/licenses/by-nc/4.0/)

---

## 👨‍💻 Author

**ElthebestDevelopman**

- GitHub: [@elthebestdevelopman-cyber](https://github.com/elthebestdevelopman-cyber)
- Project: [XiaoHyperCleaner](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner)

---

## 💖 Support the Project

If the app turned out to be useful and you'd like to say thanks:

| Service | Link |
|---------|------|
| **YooMoney** | [yoomoney.ru/to/410011379195150](https://yoomoney.ru/to/410011379195150) |
| **CloudTips** | [pay.cloudtips.ru/p/90614cff](https://pay.cloudtips.ru/p/90614cff) |

Or just **give a ⭐ star** on GitHub — it helps others find the project.

---

## ⚖️ Disclaimer

The application is provided "as is". The author is not responsible for any consequences of use. Please read the FAQ and Usage sections before using.

All trademarks (Xiaomi, Redmi, Poco, MIUI, HyperOS) belong to their respective owners. The application is not affiliated with Xiaomi Corporation.

---

<div align="center">

**Made with ❤️ for Xiaomi device owners**

[Releases](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases) • [Issues](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/issues) • [Discussions](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/discussions)

</div>
