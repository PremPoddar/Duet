# kotlinx.serialization: keep generated serializers for the session protocol.
-keepclassmembers class com.prempoddar.duet.** { *** Companion; }
-keepclasseswithmembers class com.prempoddar.duet.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.prempoddar.duet.**$$serializer { *; }
# WebRTC is called from native code.
-keep class org.webrtc.** { *; }
# Paho looks up its network modules reflectively.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.**
-dontwarn javax.naming.**
# Web pages call these bridge methods by name.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
