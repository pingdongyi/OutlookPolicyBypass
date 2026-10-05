# Add project specific ProGuard rules here.

# Xposed 入口类（xposed_init 通过反射加载）
-keep class com.outlookbypass.xposed.MainHook { *; }

# 保留本模块全部类，避免 R8 重命名/裁剪 Hook 回调
-keep class com.outlookbypass.xposed.** { *; }

# 无论如何保留 XC_MethodHook 的 before/after 回调实现
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(...);
    protected void afterHookedMethod(...);
}
