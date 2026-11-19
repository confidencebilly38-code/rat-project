# RAT Client ProGuard Rules
# Aggressive obfuscation for anti-analysis

# Keep application class
-keep class com.rat.client.RatApplication { *; }

# Keep manifest components
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Activity
-keep public class * extends androidx.appcompat.app.AppCompatActivity
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.app.admin.DeviceAdminReceiver

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep generics
-keepattributes Signature

# Obfuscate everything else aggressively
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Optimize
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively

# Keep line numbers for debugging (remove in production)
# -keeplinenumbers
# -keepattributes SourceFile,LineNumberTable

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# Keep Retrofit interfaces
-keep interface retrofit2.** { *; }
-keep class retrofit2.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }

# Keep Moshi
-keep class com.squareup.moshi.** { *; }

# Keep Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keep @androidx.room.Database class *

# Keep WorkManager
-keep class androidx.work.** { *; }

# Keep Coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep Java WebSocket
-keep class org.java_websocket.** { *; }

# Keep Socket.IO
-keep class io.socket.** { *; }
-keep class com.github.nkzawa.** { *; }

# Keep Glide
-keep class com.bumptech.glide.** { *; }

# Keep Crypto
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Keep AndroidX Security
-keep class androidx.security.crypto.** { *; }

# Keep Biometric
-keep class androidx.biometric.** { *; }

# Keep PermissionsDispatcher
-keep class com.github.gabriellima.permissionsdispatcher.** { *; }

# Keep specific RAT classes that need reflection
-keep class com.rat.client.model.** { *; }
-keep class com.rat.client.command.** { *; }
-keep class com.rat.client.crypto.** { *; }

# Remove unused resources
-shrinkResources