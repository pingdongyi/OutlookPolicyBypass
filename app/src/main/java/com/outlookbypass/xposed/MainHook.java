package com.outlookbypass.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Outlook 设备策略绕过模块。
 *
 * <p>微软 Outlook（com.microsoft.office.outlook）在登录企业 / Microsoft 365 账号时，会根据
 * {@code DevicePolicy} 判断设备是否满足 MDM / Intune 要求，不满足时要求注册设备管理或应用设备策略。
 * 本模块在 Outlook 进程内 Hook {@code DevicePolicy} 的两个判定方法：
 * <ul>
 *     <li>{@code requiresDeviceManagement()}  -> 返回 {@code false}：不需要注册设备管理</li>
 *     <li>{@code isPolicyApplied()}            -> 返回 {@code true}：策略已应用，账号视为合规</li>
 * </ul>
 *
 * <p>适配目标（本机）：Outlook 5.2638.1 (versionCode 72638120)，
 * 类 {@code com.microsoft.office.outlook.olmcore.managers.mdm.DevicePolicy} 与上述两个方法在该版本中均存在。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "OutlookPolicyBypass";

    private static final String TARGET_PACKAGE = "com.microsoft.office.outlook";

    private static final String DEVICE_POLICY_CLASS =
            "com.microsoft.office.outlook.olmcore.managers.mdm.DevicePolicy";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);

        Class<?> devicePolicy;
        try {
            devicePolicy = XposedHelpers.findClass(DEVICE_POLICY_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DevicePolicy class not found; "
                    + "Outlook version may have moved/renamed it: " + t);
            return;
        }

        // 不需要设备管理 -> 绕过 MDM 注册要求
        hookBoolean(devicePolicy, "requiresDeviceManagement", false);

        // 策略已应用 -> 账号标记为合规，不再反复提示
        hookBoolean(devicePolicy, "isPolicyApplied", true);
    }

    /**
     * Hook 一个无参的 boolean 方法，让其固定返回 {@code value}（跳过原方法实现）。
     */
    private void hookBoolean(Class<?> clazz, String methodName, final boolean value) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            });
            XposedBridge.log(TAG + ": hooked " + clazz.getSimpleName() + "."
                    + methodName + " -> " + value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + clazz.getName() + "."
                    + methodName + "() not found: " + t);
        }
    }
}
