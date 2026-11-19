#!/bin/bash
# DevContainer Post-Create Setup Script
# Runs once after container creation

set -e

echo "========================================="
echo "  Post-Create Setup"
echo "========================================="

# Navigate to project root
cd /workspaces/rat-project

# Initialize Git repository if not already
if [ ! -d .git ]; then
    echo "Initializing Git repository..."
    git init
    git add .
    git commit -m "Initial commit: RAT APK project structure"
fi

# Set up Git hooks
mkdir -p .git/hooks
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/bash
# Pre-commit hook for code quality
echo "Running pre-commit checks..."

# Check for large files
large_files=$(find . -type f -size +10M ! -path "./.git/*" ! -path "./android-client/.gradle/*" ! -path "./android-client/build/*" 2>/dev/null)
if [ -n "$large_files" ]; then
    echo "Warning: Large files detected:"
    echo "$large_files"
fi

# Check for secrets
secrets=$(grep -r -i "password\|secret\|key\|token" --include="*.kt" --include="*.java" --include="*.xml" --include="*.gradle" --include="*.properties" android-client/app/src/main/ 2>/dev/null | grep -v "// " | head -5)
if [ -n "$secrets" ]; then
    echo "Warning: Potential secrets in code:"
    echo "$secrets"
fi

exit 0
EOF
chmod +x .git/hooks/pre-commit

# Create Gradle wrapper if not exists
cd android-client
if [ ! -f gradlew ]; then
    echo "Generating Gradle wrapper..."
    gradle wrapper --gradle-version 8.7 --distribution-type bin
    chmod +x gradlew
fi

# Download Gradle dependencies for offline building
echo "Pre-downloading Gradle dependencies..."
./gradlew --offline dependencies 2>/dev/null || ./gradlew dependencies --configuration compileClasspath > /dev/null 2>&1 || true

# Create local.properties for SDK location
cat > local.properties << EOF
sdk.dir=/opt/android-sdk
ndk.dir=/opt/android-ndk
EOF

# Build once to cache dependencies
echo "Running initial build to cache dependencies..."
./gradlew assembleDebug --no-daemon -q 2>&1 | tail -20 || true

# Set up C2 server
cd /workspaces/rat-project/c2-server
pip3 install --no-cache-dir -r requirements.txt 2>&1 | tail -5

# Initialize C2 database
cd /workspaces/rat-project/c2-server/src
python3 -c "
import sys
sys.path.insert(0, '.')
from app import init_db
init_db()
print('C2 database initialized')
"

# Create useful aliases
cat >> /home/vscode/.bashrc << 'EOF'

# RAT Project Aliases
alias build-apk='cd /workspaces/rat-project/android-client && ./build-apk.sh'
alias start-emu='cd /workspaces/rat-project && ./start-emulator.sh'
alias start-c2='cd /workspaces/rat-project && ./start-c2.sh'
alias adb='/opt/android-sdk/platform-tools/adb'
alias logcat='adb logcat -s RatService:D RatApplication:D CommandExecutor:D *:S'
alias devices='adb devices -l'
alias install-debug='adb install -r /workspaces/rat-project/android-client/app/build/outputs/apk/debug/app-debug.apk'
alias install-release='adb install -r /workspaces/rat-project/android-client/app/build/outputs/apk/release/app-release.apk'
alias uninstall-rat='adb uninstall com.rat.client'

# Gradle aliases
alias gw='cd /workspaces/rat-project/android-client && ./gradlew'
alias gwc='gw clean'
alias gwd='gw assembleDebug'
alias gwr='gw assembleRelease'
alias gwcd='gw clean assembleDebug'
alias gwcr='gw clean assembleRelease'

# Navigation
alias rat='cd /workspaces/rat-project'
alias rat-client='cd /workspaces/rat-project/android-client'
alias rat-c2='cd /workspaces/rat-project/c2-server'

# Show project status
rat-status() {
    echo "=== RAT Project Status ==="
    echo ""
    echo "Android Client:"
    cd /workspaces/rat-project/android-client
    echo "  Gradle: $(./gradlew --version 2>/dev/null | head -1 || echo 'Not built')"
    if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then
        size=$(du -h app/build/outputs/apk/debug/app-debug.apk | cut -f1)
        echo "  Debug APK: ✓ ($size)"
    else
        echo "  Debug APK: ✗"
    fi
    if [ -f app/build/outputs/apk/release/app-release.apk ]; then
        size=$(du -h app/build/outputs/apk/release/app-release.apk | cut -f1)
        echo "  Release APK: ✓ ($size)"
    else
        echo "  Release APK: ✗"
    fi
    echo ""
    echo "C2 Server:"
    cd /workspaces/rat-project/c2-server
    if [ -f data/c2_server.db ]; then
        echo "  Database: ✓"
    else
        echo "  Database: ✗ (run start-c2 to initialize)"
    fi
    echo ""
    echo "Devices:"
    adb devices -l 2>/dev/null | grep -v "List of devices" | while read line; do
        if [ -n "$line" ]; then
            echo "  $line"
        fi
    done
}

echo "Post-create setup complete!"
EOF

# Source the new aliases
source /home/vscode/.bashrc

echo "========================================="
echo "  Setup Complete!"
echo "========================================="