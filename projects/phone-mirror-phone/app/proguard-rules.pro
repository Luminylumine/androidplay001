# Keep Compose runtime (required by Jetpack Compose)
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Room (kapt 会处理注解)
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# Keep Okio (Kotlin Multiplatform, reflection-free)
-dontwarn okio.**

# Keep Serialization generated code
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep our adb packet model (value classes and data classes)
-keepclassmembers class com.phone.mirror.transport.adb.core.** { *; }
-keepclassmembers class com.phone.mirror.core.** { *; }
