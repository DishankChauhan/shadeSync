# Default ProGuard rules
-keepattributes *Annotation*

# ── MediaPipe ──
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ── CameraX ──
-keep class androidx.camera.** { *; }

# ── Keep data classes used in reflection/serialization ──
-keep class com.shadesync.app.LipShade { *; }
-keep class com.shadesync.app.LookPreset { *; }
-keep class com.shadesync.app.SkinAnalyzer$* { *; }
