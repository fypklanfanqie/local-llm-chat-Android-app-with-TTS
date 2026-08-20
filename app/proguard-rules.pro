# 基础属性
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeVisibleTypeAnnotations
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Application / Activity / Worker（框架反射实例化）
-keep public class com.chatbyyourside.ChatApp { public <init>(...); }
-keep public class com.chatbyyourside.MainActivity { public <init>(...); }
-keep class com.chatbyyourside.work.GreetingWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# JNI 桥接：类名、native 方法名、被 C 代码访问的静态成员都不能混淆
-keep class com.chatbyyourside.llm.backend.MnnBridge { *; }
-keep class com.chatbyyourside.llm.CpuSysBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }

# LLM / Provider / Repository / Config / Model / Manager
-keep class com.chatbyyourside.llm.** { *; }
-keep class com.chatbyyourside.provider.** { *; }
-keep class com.chatbyyourside.data.model.** { *; }
-keep class com.chatbyyourside.data.local.** { *; }
-keep class com.chatbyyourside.data.repository.** { *; }
-keep class com.chatbyyourside.data.remote.** { *; }
-keep class com.chatbyyourside.config.** { *; }
-keep class com.chatbyyourside.notification.** { *; }
-keep class com.chatbyyourside.manager.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Retrofit
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Kotlin Serialization
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class * implements kotlinx.serialization.KSerializer { *; }

# Compose
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# 枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 常见库警告压制
-dontwarn java.lang.invoke.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.**
-dontwarn androidx.compose.material3.**

# ===== OPPO/鸿蒙闪退排查加固：release 防 R8 反射剥离 =====
# WorkManager 按 WorkData 里的类名字符串反射实例化自定义 Worker（不引用 manifest），
# 漏 keep 会被 R8 改名/裁剪。GroupChatWorker 曾漏 keep，release 群聊实际已受影响。
-keep class com.chatbyyourside.work.** { *; }
# 清单组件 / 前台服务 / 隔离探测进程（框架按清单类名反射实例化 + JNI）
-keep class com.chatbyyourside.llm.backend.OpenClProbeService { *; }
-keep class com.chatbyyourside.service.** { *; }
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
# 玻璃 UI / 三方液态玻璃库（Compose 反射 + 图形栈）
-keep class com.chatbyyourside.ui.glass.** { *; }
-keep class com.qmdeve.liquidglass.** { *; }
