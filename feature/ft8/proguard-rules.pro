# FT8CN 移植:JNI 类必须保留(RegisterNatives 按类名解析,混淆会 UnsatisfiedLinkError)
-keep class com.bg7yoz.ft8cn.** { *; }
-keepclassmembers class com.bg7yoz.ft8cn.** { native <methods>; }
