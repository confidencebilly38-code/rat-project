# GitHub Codespaces Setup Guide for RAT APK Project

This guide explains how to use this project in GitHub Codespaces for building the RAT APK and running the C2 server.

## 🚀 Quick Start

### 1. Create a Codespace

1. Go to your GitHub repository
2. Click **Code** → **Codespaces** → **Create codespace on main**
3. Wait for the container to build (2-5 minutes on first run)

### 2. Build the APK

Once the Codespace is ready, you'll be in a terminal. Run:

```bash
# Quick build (debug)
build-apk

# Or manually:
cd android-client
./gradlew assembleDevelopmentDebug
```

The APK will be at:
- Debug: `android-client/app/build/outputs/apk/development/debug/app-development-debug.apk`
- Release: `android-client/app/build/outputs/apk/development/release/app-development-release.apk`

### 3. Start Android Emulator (Optional)

In a **new terminal** (Terminal → New Terminal):

```bash
start-emulator.sh
```

Wait for "Emulator booted successfully" message.

### 4. Install APK on Emulator

```bash
# Debug version
install-debug

# Or with specific path
adb install -r android-client/app/build/outputs/apk/development/debug/app-development-debug.apk
```

### 5. Start C2 Server

In a **new terminal**:

```bash
start-c2.sh
```

### 6. Access Dashboard

Open the **Ports** tab in VS Code, find port 8080, click the globe icon, or visit:
- `http://localhost:8080/dashboard` (if port forwarding works)
- Or use the forwarded URL from the Ports tab

**Login:**
- Username: `admin`
- Password: `ratadmin123`

## 🔧 Available Commands

### Build Commands
| Command | Description |
|---------|-------------|
| `build-apk` | Build all variants (debug + release) |
| `gw` | Run gradle wrapper |
| `gwc` | Clean build |
| `gwd` | Assemble debug |
| `gwr` | Assemble release |
| `gwcd` | Clean + debug |
| `gwcr` | Clean + release |

### Device Commands
| Command | Description |
|---------|-------------|
| `devices` | List connected devices |
| `logcat` | View RAT service logs |
| `install-debug` | Install debug APK |
| `install-release` | Install release APK |
| `uninstall-rat` | Uninstall RAT app |

### Server Commands
| Command | Description |
|---------|-------------|
| `start-c2` | Start C2 server |
| `rat-status` | Show project status |

### Navigation
| Command | Description |
|---------|-------------|
| `rat` | Go to project root |
| `rat-client` | Go to android-client |
| `rat-c2` | Go to c2-server |

## 🏗️ Build Variants

The project supports three product flavors:

| Flavor | C2 Host | Port | Protocol | Use Case |
|--------|---------|------|----------|----------|
| `development` | 10.0.2.2 | 8080 | http | Local emulator |
| `staging` | staging.example.com | 8443 | https | Staging |
| `production` | production.example.com | 443 | https | Production |

Build specific variant:
```bash
./gradlew assembleDevelopmentDebug
./gradlew assembleProductionRelease
```

## ⚙️ Configuration

### Environment Variables

Set these in **GitHub Codespaces Secrets** (Repository Settings → Secrets → Codespaces):

| Secret | Description | Default |
|--------|-------------|---------|
| `C2_HOST` | C2 server hostname | `0.0.0.0` |
| `C2_PORT` | C2 server port | `8080` |
| `C2_PROTOCOL` | http/https | `http` |
| `KEYSTORE_PASSWORD` | Release keystore password | - |
| `KEY_ALIAS` | Release key alias | - |
| `KEY_PASSWORD` | Release key password | - |
| `ADMIN_PASSWORD_HASH` | C2 admin password hash | - |
| `SECRET_KEY` | Flask secret key | Auto-generated |
| `ENCRYPTION_KEY` | Data encryption key | Auto-generated |

### Custom C2 Domain

For production deployment, update `.devcontainer/devcontainer.json`:
```json
"containerEnv": {
  "C2_HOST": "your-c2-domain.com",
  "C2_PORT": "443",
  "C2_PROTOCOL": "https"
}
```

## 📱 Testing on Real Device

1. Enable **USB Debugging** on your Android device
2. Connect via USB to your computer
3. In Codespace terminal: `adb devices` (should show your device)
4. Install: `install-debug` or `install-release`

**Note:** USB passthrough in Codespaces may require the GitHub CLI or local port forwarding.

## 🐛 Troubleshooting

### Build Fails

```bash
# Clean and rebuild
cd android-client
./gradlew clean
./gradlew assembleDevelopmentDebug --stacktrace
```

### Gradle Daemon Issues

```bash
# Stop all daemons
./gradlew --stop
# Then rebuild
./gradlew assembleDevelopmentDebug
```

### Emulator Won't Start

```bash
# Check KVM
ls -la /dev/kvm
# If missing, KVM not available - use software rendering
```

### C2 Server Connection Issues

1. Check if server is running: `pgrep -f "python.*app.py"`
2. Check ports: `netstat -tlnp | grep 8080`
3. Check logs: `tail -f c2-server/logs/app.log`

### ADB Not Finding Device

```bash
# Restart ADB
adb kill-server
adb start-server
adb devices
```

## 📦 CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/android-build.yml`) runs on:
- Push to main/develop
- Pull requests
- Manual dispatch

It builds all 6 variant/type combinations, runs lint, tests, and security scans.

### Trigger Manual Build

Go to **Actions** → **Android RAT APK Build** → **Run workflow** → Select variant/type.

### Download Artifacts

After workflow completes, download APKs from the workflow run artifacts.

## 🔐 Security Notes

- **Never commit real secrets** - Use GitHub Codespaces Secrets
- **Release keystore** - Store in Codespaces secrets, not in repo
- **C2 server** - Use HTTPS in production with valid certificates
- **Network security** - Configure `network_security_config.xml` for production domains

## 📚 Useful Links

- [GitHub Codespaces Documentation](https://docs.github.com/en/codespaces)
- [Android Gradle Plugin](https://developer.android.com/build)
- [Flask Documentation](https://flask.palletsprojects.com/)
- [Socket.IO Documentation](https://socket.io/docs/)

## 🆘 Getting Help

1. Check the **Terminal** output for errors
2. View **Gradle** logs: `~/.gradle/daemon/*/gradle.log`
3. View **C2 Server** logs: `c2-server/logs/`
4. Check **ADB** logs: `adb logcat -s RatService:D`

---

**Happy Hacking! 🕷️**