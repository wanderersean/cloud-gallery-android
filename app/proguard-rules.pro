-keep class org.cloud.** { *; }
-dontwarn android.graphics.Canvas
-dontwarn org.cloud.**
-dontwarn org.apache.**

# 阿里云 OSS SDK - 保留泛型信息防止 ClassCastException
-keep class com.alibaba.sdk.android.oss.** { *; }
-keep class com.alibaba.sdk.android.oss.model.** { *; }
-keep class com.alibaba.sdk.android.oss.network.** { *; }
-keep class com.alibaba.sdk.android.oss.internal.** { *; }
-keepattributes Signature
-keepattributes EnclosingMethod
-dontwarn com.alibaba.sdk.android.oss.**

# Picasso
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.ConscryptPlatform

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# RenderScript
-keepclasseswithmembernames class * {
native <methods>;
}
-keep class androidx.renderscript.** { *; }

# Reprint
-keep class com.github.ajalt.reprint.module.** { *; }
