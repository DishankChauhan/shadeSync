# Default ProGuard rules
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# ── MediaPipe ──
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ── MediaPipe native libs + JNI ──
-keep class com.google.mediapipe.framework.** { *; }
-keepclassmembers class * {
    @com.google.mediapipe.framework.* *;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── TFLite (used internally by MediaPipe) ──
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ── CameraX ──
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Keep all app classes (prevents R8 stripping) ──
-keep class com.shadesync.app.** { *; }
-keepclassmembers class com.shadesync.app.** { *; }

# ── View Binding ──
-keep class com.shadesync.app.databinding.** { *; }

# ── Kotlin Metadata ──
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# ── Material Components ──
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── AndroidX ──
-dontwarn androidx.**
-keep class androidx.** { *; }
-keepclassmembers class androidx.** { *; }

# ── Annotation processors (not needed at runtime) ──
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
