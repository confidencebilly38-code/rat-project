#!/bin/bash
# DevContainer Post-Start Script
# Runs every time the container starts

set -e

echo "========================================="
echo "  Container Started"
echo "========================================="

# Start ADB server
/opt/android-sdk/platform-tools/adb start-server

# Check for connected devices
devices=$(/opt/android-sdk/platform-tools/adb devices | grep -v "List of devices" | grep "device$" | wc -l)
if [ "$devices" -gt 0 ]; then
    echo "Connected devices: $devices"
    /opt/android-sdk/platform-tools/adb devices -l
else
    echo "No devices connected. Start emulator with: start-emulator.sh"
fi

# Check C2 server status
if pgrep -f "python.*app.py" > /dev/null; then
    echo "C2 Server: Running"
else
    echo "C2 Server: Stopped (run start-c2.sh to start)"
fi

# Show quick status
rat-status

echo ""
echo "Ready to build! Run 'build-apk' to compile the RAT APK."
echo "========================================="