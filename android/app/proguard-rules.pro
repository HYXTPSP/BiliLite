# ============================================================================
# BiliLite — ProGuard/R8 规则
# v0.4.17 发布加固。
# 说明:本项目无反射、无 JNI、无自定义序列化,业务全走 Room/Compose/Coil/ZXing/Media3,
#      上述库均自带 consumer rules 自动合并,故无需额外 keep。
#      仅保留 AndroidX 通用兜底规则。
# ============================================================================

# AndroidX 通用:保留 keep 注解标注的类(如 @Keep)
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}

# 应用崩溃处理器依赖日志堆栈,保留行号信息便于定位问题
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
