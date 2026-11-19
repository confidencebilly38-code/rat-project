# GitHub Codespaces DevContainer for Android RAT Development
# Based on Ubuntu 22.04 with Android SDK, NDK, Gradle, and all build tools

FROM ubuntu:22.04

# Prevent interactive prompts
ENV DEBIAN_FRONTEND=noninteractive \
    TZ=UTC

# Install base packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    # Basic tools
    curl wget git unzip zip tar gzip bzip2 xz-utils \
    # Build tools
    build-essential cmake ninja-build pkg-config \
    # Java
    openjdk-17-jdk-headless \
    # Python
    python3 python3-pip python3-venv \
    # Android dependencies
    libc6-dev libstdc++6 lib32stdc++6 lib32z1 lib32ncurses6 \
    libgl1-mesa-glx libpulse0 libxi6 libxrender1 libxrandr2 libxfixes3 libxcursor1 \
    libxcomposite1 libxdamage1 libxext6 libx11-6 libxcb1 \
    # Network
    net-tools iproute2 iptables dnsutils \
    # Utilities
    vim nano htop tree jq rsync openssh-client \
    # KVM/QEMU for emulator
    qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils virt-manager \
    && rm -rf /var/lib/apt/lists/*

# Set up environment variables
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_HOME=/opt/android-ndk
ENV GRADLE_USER_HOME=/home/vscode/.gradle
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${ANDROID_NDK_HOME}

# Create vscode user (UID 1000 matches Codespaces default)
RUN useradd -m -s /bin/bash -u 1000 vscode && \
    usermod -aG kvm,libvirt vscode && \
    mkdir -p /opt/android-sdk /opt/android-ndk /home/vscode/.gradle && \
    chown -R vscode:vscode /opt/android-sdk /opt/android-ndk /home/vscode/.gradle

# Install Android SDK Command Line Tools
USER vscode
WORKDIR /home/vscode

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    cd ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip && \
    unzip -q cmdline-tools.zip && \
    mv cmdline-tools latest && \
    rm cmdline-tools.zip

# Accept licenses and install SDK packages
RUN yes | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses && \
    ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager \
        "platform-tools" \
        "platforms;android-34" \
        "platforms;android-33" \
        "platforms;android-32" \
        "platforms;android-31" \
        "platforms;android-30" \
        "platforms;android-29" \
        "platforms;android-28" \
        "build-tools;34.0.0" \
        "build-tools;33.0.2" \
        "build-tools;32.0.0" \
        "build-tools;31.0.0" \
        "build-tools;30.0.3" \
        "emulator" \
        "system-images;android-34;google_apis;x86_64" \
        "system-images;android-33;google_apis;x86_64" \
        "system-images;android-30;google_apis;x86_64" \
        "ndk;26.1.10909125" \
        "cmake;3.22.1" \
        "patcher;v4" \
        "tools" && \
    ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --update

# Install Android NDK
ENV ANDROID_NDK_VERSION=26.1.10909125
RUN ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager "ndk;${ANDROID_NDK_VERSION}" && \
    ln -sf ${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION} ${ANDROID_NDK_HOME}

# Install Gradle
ENV GRADLE_VERSION=8.7
RUN cd /tmp && \
    wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip && \
    unzip -q gradle-${GRADLE_VERSION}-bin.zip && \
    sudo mv gradle-${GRADLE_VERSION} /opt/gradle && \
    sudo ln -sf /opt/gradle/bin/gradle /usr/local/bin/gradle && \
    rm gradle-${GRADLE_VERSION}-bin.zip

# Install Kotlin
ENV KOTLIN_VERSION=2.0.0
RUN cd /tmp && \
    wget -q https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip && \
    unzip -q kotlin-compiler-${KOTLIN_VERSION}.zip && \
    sudo mv kotlinc /opt/kotlin && \
    sudo ln -sf /opt/kotlin/bin/kotlinc /usr/local/bin/kotlinc && \
    sudo ln -sf /opt/kotlin/bin/kotlin /usr/local/bin/kotlin && \
    rm kotlin-compiler-${KOTLIN_VERSION}.zip

# Install Node.js for any JS tooling
ENV NODE_VERSION=20
RUN curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | sudo bash - && \
    sudo apt-get install -y nodejs && \
    sudo npm install -g yarn pnpm

# Install Python packages for C2 server
RUN pip3 install --no-cache-dir --upgrade pip && \
    pip3 install --no-cache-dir \
    flask flask-socketio flask-cors \
    werkzeug python-socketio python-engineio \
    eventlet gunicorn requests \
    cryptography pyjwt bcrypt

# Set up Gradle properties for performance
RUN mkdir -p /home/vscode/.gradle && cat > /home/vscode/.gradle/gradle.properties << 'EOF'
# Gradle performance settings
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true
org.gradle.daemon=true
org.gradle.workers.max=4

# Android settings
android.useAndroidX=true
android.enableJetifier=false
android.nonTransitiveRClass=true
android.nonFinalResIds=false
kotlin.code.style=official
kapt.use.worker.api=true
EOF

# Create AVD for testing
RUN echo "no" | ${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager create avd \
    -n test_device \
    -k "system-images;android-34;google_apis;x86_64" \
    --device "pixel_6" \
    --force

# Create emulator startup script
RUN cat > /home/vscode/start-emulator.sh << 'EOF'
#!/bin/bash
# Start Android emulator in background
export ANDROID_HOME=/opt/android-sdk
export PATH=${PATH}:${ANDROID_HOME}/emulator:${ANDROID_HOME}/platform-tools

# Enable KVM acceleration
if [ -e /dev/kvm ]; then
    echo "KVM available, using hardware acceleration"
    ACCEL="-accel kvm -gpu swiftshader_indirect"
else
    echo "KVM not available, using software rendering"
    ACCEL="-gpu swiftshader_indirect"
fi

# Start emulator
${ANDROID_HOME}/emulator/emulator \
    -avd test_device \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -camera-back none \
    -camera-front none \
    ${ACCEL} \
    -memory 2048 \
    -cores 2 \
    -wipe-data &

EMULATOR_PID=$!
echo "Emulator started with PID: $EMULATOR_PID"

# Wait for boot completion
${ANDROID_HOME}/platform-tools/adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
echo "Emulator booted successfully"

# Keep script running
wait $EMULATOR_PID
EOF
chmod +x /home/vscode/start-emulator.sh

# Create build script
RUN cat > /home/vscode/build-apk.sh << 'EOF'
#!/bin/bash
# Build RAT APK script
set -e

PROJECT_DIR="/workspaces/rat-project/android-client"
cd "$PROJECT_DIR"

echo "=== Building RAT APK ==="
echo "Project: $PROJECT_DIR"
echo "Gradle: $(gradle --version | head -1)"
echo "Java: $(java -version 2>&1 | head -1)"
echo "Android SDK: $ANDROID_HOME"

# Clean previous builds
./gradlew clean

# Build all variants
echo "Building debug APK..."
./gradlew assembleDebug

echo "Building release APK..."
./gradlew assembleRelease

# List outputs
echo ""
echo "=== Build Outputs ==="
find . -name "*.apk" -type f | while read apk; do
    size=$(du -h "$apk" | cut -f1)
    echo "$apk ($size)"
done

echo ""
echo "=== Build Complete ==="
EOF
chmod +x /home/vscode/build-apk.sh

# Create C2 server startup script
RUN cat > /home/vscode/start-c2.sh << 'EOF'
#!/bin/bash
# Start C2 server
cd /workspaces/rat-project/c2-server
export PYTHONPATH=/workspaces/rat-project/c2-server/src
python3 src/app.py
EOF
chmod +x /home/vscode/start-c2.sh

# Switch back to root for final setup
USER root

# Add vscode to sudoers for convenience
RUN echo "vscode ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# Create entrypoint script
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["bash"]