# kotlinx-serialization
-keepattributes Signature, InnerClasses, *Annotation*
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pcmonitor.app.data.**$$serializer { *; }
-keepclassmembers class com.pcmonitor.app.data.** { *** Companion; }
-keepclasseswithmembers class com.pcmonitor.app.data.** { kotlinx.serialization.KSerializer serializer(...); }
