-keepclassmembers class * { @kotlinx.serialization.SerialName <fields>; }
# Reflection on framework classes (status bar, system properties) is resolved at runtime.
-dontwarn android.os.SystemProperties
# kotlinx.serialization: keep generated serializers of our models.
-keepclassmembers @kotlinx.serialization.Serializable class app.quiet.launcher.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.quiet.launcher.**$$serializer { *; }
