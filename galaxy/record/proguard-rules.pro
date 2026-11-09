# AutoRecord 现场验证包（field）的 R8 规则
#
# 目的：把 APK 压到能走 4KB 分块通道推送的体积（远程 artifact 通道今天对 APK 返回 429）。
# 约束：本模块不用反射、不用序列化框架、不动态加载类，唯一动态入口是 Manifest 里的 Activity，
# 它由 AAPT 生成的 keep 规则保护，所以这里只保留可读栈与真正需要的元数据。
#
# 注意：不要 -keep class **.core.** { *; }——那会把全部 core 类与成员钉住，
# 实测会让包从 ~700KB 涨到 1.43MB，直接把推送时间翻倍。

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 入口 Activity（Manifest 声明，双保险）
-keep class com.galaxy.autorecord.ui.RecordActivity { *; }

# 只压缩日志级别，保留 Log.i/w：现场靠它们判断出帧、帧率与失败原因
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# WebRTC AAR 无 consumer rules；native JNI 通过固定类/方法名回调 Java。
# 保留第三方边界，不扩大为本项目 core 类的保留规则。
-keep class org.webrtc.** { *; }
-keepattributes *Annotation*
# M150的JNI注册同时依赖jni_zero固定Java名称。
-keep class org.jni_zero.** { *; }
# 上游AAR未附带可选setJniClassLoader的生成桥；本App不调用该方法。
# JNI_ZERO仍需保留JniZero类与其native回调，不能整包忽略missing警告。
-dontwarn org.jni_zero.JniZeroJni
