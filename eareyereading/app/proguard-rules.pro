# EareyeReading R8 规则
#
# 注：Room / Hilt / Compose / ML Kit / Coil / coroutines 等库均自带
# consumer proguard 规则（AAR 内置），全量 keep 只会阻止 R8 裁剪。
# 旧规则里 keep 整个 Room 实体包、dagger.hilt、javax.inject、整个 Gson
# 库都属冗余，已删除。

# sherpa-onnx JNI：native 层按类名/方法名绑定（System.loadLibrary 后
# external fun 按名解析），混淆即 UnsatisfiedLinkError 崩溃
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Gson 反射模型（DictionaryManager.fromJson 解析 manifest.json）：
# 字段名被混淆后 JSON key 对不上，解析会静默拿到 null
-keep class com.eareyereading.util.DictionaryManifest { *; }
-keep class com.eareyereading.util.DictionaryInfo { *; }
-keepattributes Signature
-keepattributes *Annotation*

# 崩溃堆栈可读性（保留行号，R8 会用映射表还原类名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
