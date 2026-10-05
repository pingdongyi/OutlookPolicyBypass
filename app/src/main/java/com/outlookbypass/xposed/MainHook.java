package com.outlookbypass.xposed;

import android.content.Context;
import android.webkit.WebView;

import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Outlook 设备策略 / Intune MAM 绕过模块。
 *
 * <p>Outlook 有两套设备管理链路，登录企业账号时任意一套判定为“需要注册/不合规”都会阻止访问：
 * <ol>
 *     <li>OLM 传统 MDM：{@code DevicePolicy}（本模块一直 Hook 的旧链路）</li>
 *     <li>Intune MAM SDK：{@code MAMWEAccountManager} / {@code MAMEnrollmentManager}
 *         （公司门户 / Company Portal 注册链路，报“使用管理应用重新注册设备”）</li>
 * </ol>
 *
 * <p>本机报错“如果你已经注册，则设备设置可能已过期…请使用管理应用重新注册设备”来自第 2 条链路：
 * 设备此前注册过 Intune，但注册状态已过期，Outlook 拿到
 * {@code MAMEnrollmentManager$Result.COMPANY_PORTAL_REQUIRED} 后阻断登录。
 *
 * <p>适配目标（本机）：Outlook 5.2638.1 (versionCode 72638120)。相关类与方法在该版本中均存在。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "OutlookPolicyBypass";
    private static final String TARGET_PACKAGE = "com.microsoft.office.outlook";

    /** 伪装成桌面 Chrome，绕过按平台（Android/iOS）作用域的条件访问策略。 */
    private static final String DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** OLM 传统 MDM 策略类。 */
    private static final String OLM_DEVICE_POLICY =
            "com.microsoft.office.outlook.olmcore.managers.mdm.DevicePolicy";

    /** Intune MAM SDK：注册状态管理。 */
    private static final String SDK_WE_ACCOUNT_MANAGER =
            "com.microsoft.intune.mam.policy.MAMWEAccountManager";
    private static final String SDK_OFFLINE_ENROLLMENT_MANAGER =
            "com.microsoft.intune.mam.client.app.offline.OfflineMAMEnrollmentManager";
    private static final String SDK_MAM_IDENTITY =
            "com.microsoft.intune.mam.client.identity.MAMIdentity";
    private static final String SDK_LOG_PII_FACTORY =
            "com.microsoft.intune.mam.log.MAMLogPIIFactory";
    private static final String SDK_RESULT =
            "com.microsoft.intune.mam.policy.MAMEnrollmentManager$Result";

    /** Outlook 对 Intune SDK 的封装。 */
    private static final String OUTLOOK_ENROLLMENT_IMPL =
            "com.microsoft.office.outlook.intune.impl.policy.MAMEnrollmentManagerImpl";
    private static final String OUTLOOK_RESULT =
            "com.microsoft.office.outlook.intune.api.policy.MAMEnrollmentManager$Result";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);
        ClassLoader cl = lpparam.classLoader;

        hookOlmDevicePolicy(cl);
        hookIntuneSdkEnrollment(cl);
        hookIntuneSdkCompliance(cl);
        hookOutlookEnrollment(cl);
        hookWebViewUserAgent(cl);
    }

    // ------------------------------------------------------------------
    // 1) OLM 传统 MDM
    // ------------------------------------------------------------------

    private void hookOlmDevicePolicy(ClassLoader cl) {
        Class<?> devicePolicy;
        try {
            devicePolicy = XposedHelpers.findClass(OLM_DEVICE_POLICY, cl);
        } catch (Throwable t) {
            log("OLM DevicePolicy class not found: " + t);
            return;
        }

        // 不需要设备管理 -> 绕过旧 MDM 注册要求
        hookBooleanReturn(devicePolicy, "requiresDeviceManagement", false);
        // 策略已应用 -> 账号标记为合规
        hookBooleanReturn(devicePolicy, "isPolicyApplied", true);
    }

    // ------------------------------------------------------------------
    // 2) Intune MAM SDK（Company Portal 注册链路）
    // ------------------------------------------------------------------

    private void hookIntuneSdkEnrollment(ClassLoader cl) {
        Class<?> we = XposedHelpers.findClass(SDK_WE_ACCOUNT_MANAGER, cl);

        // getAccountStatus(MAMIdentity) -> ENROLLMENT_SUCCEEDED
        // 让 Outlook / SDK 认为账号已完成 MAM 注册，避免 COMPANY_PORTAL_REQUIRED。
        try {
            Class<?> identity = XposedHelpers.findClass(SDK_MAM_IDENTITY, cl);
            Class<?> result = XposedHelpers.findClass(SDK_RESULT, cl);
            final Object enrolled = enumValue(result, "ENROLLMENT_SUCCEEDED");

            XposedHelpers.findAndHookMethod(we, "getAccountStatus", identity, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(enrolled);
                }
            });
            log("hooked MAMWEAccountManager.getAccountStatus -> ENROLLMENT_SUCCEEDED");
        } catch (Throwable t) {
            log("MAMWEAccountManager.getAccountStatus hook failed: " + t);
        }

        // isCompanyPortalRequired() -> false（实例方法）
        try {
            XposedHelpers.findAndHookMethod(we, "isCompanyPortalRequired", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            log("hooked MAMWEAccountManager.isCompanyPortalRequired() -> false");
        } catch (Throwable t) {
            log("MAMWEAccountManager.isCompanyPortalRequired() hook failed: " + t);
        }

        // isCompanyPortalRequired(Context, MAMLogPIIFactory) -> false（静态方法）
        try {
            Class<?> logPiiFactory = XposedHelpers.findClass(SDK_LOG_PII_FACTORY, cl);
            XposedHelpers.findAndHookMethod(we, "isCompanyPortalRequired",
                    Context.class, logPiiFactory, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            log("hooked MAMWEAccountManager.isCompanyPortalRequired(Context,..) -> false");
        } catch (Throwable t) {
            log("MAMWEAccountManager.isCompanyPortalRequired(Context,..) hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 2b) Intune MAM SDK：拦截“安装公司门户 / 重新注册”UI 链路
    //     remediateCompliance -> handleCompanyPortalRequirement
    //     -> showNonBlockingInstallSSPUI -> OfflineInstallCompanyPortalDialogActivity
    // ------------------------------------------------------------------

    private void hookIntuneSdkCompliance(ClassLoader cl) {
        Class<?> em;
        try {
            em = XposedHelpers.findClass(SDK_OFFLINE_ENROLLMENT_MANAGER, cl);
        } catch (Throwable t) {
            log("OfflineMAMEnrollmentManager class not found: " + t);
            return;
        }

        // remediateCompliance(String,String,String,String,boolean) -> 直接跳过
        // （该方法会起线程去弹“安装公司门户”对话框）
        try {
            XposedHelpers.findAndHookMethod(em, "remediateCompliance",
                    String.class, String.class, String.class, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
            log("hooked OfflineMAMEnrollmentManager.remediateCompliance -> no-op");
        } catch (Throwable t) {
            log("remediateCompliance hook failed: " + t);
        }

        // showNonBlockingInstallSSPUI(MAMIdentity, Context) -> 直接跳过
        // （该方法 startActivity 拉起 OfflineInstallCompanyPortalDialogActivity）
        try {
            Class<?> identity = XposedHelpers.findClass(SDK_MAM_IDENTITY, cl);
            XposedHelpers.findAndHookMethod(em, "showNonBlockingInstallSSPUI",
                    identity, Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
            log("hooked OfflineMAMEnrollmentManager.showNonBlockingInstallSSPUI -> no-op");
        } catch (Throwable t) {
            log("showNonBlockingInstallSSPUI hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 3) Outlook 对 Intune 的封装（登录流程直接读取该结果）
    // ------------------------------------------------------------------

    private void hookOutlookEnrollment(ClassLoader cl) {
        try {
            Class<?> impl = XposedHelpers.findClass(OUTLOOK_ENROLLMENT_IMPL, cl);
            Class<?> result = XposedHelpers.findClass(OUTLOOK_RESULT, cl);
            final Object enrolled = enumValue(result, "ENROLLMENT_SUCCEEDED");

            XposedHelpers.findAndHookMethod(impl, "getRegisteredAccountStatus",
                    String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(enrolled);
                }
            });
            log("hooked MAMEnrollmentManagerImpl.getRegisteredAccountStatus -> ENROLLMENT_SUCCEEDED");
        } catch (Throwable t) {
            log("MAMEnrollmentManagerImpl.getRegisteredAccountStatus hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 4) 登录 WebView UA 伪装（服务端条件访问按 UA 判定平台时有效）
    // ------------------------------------------------------------------

    private void hookWebViewUserAgent(ClassLoader cl) {
        try {
            Class<?> webView = XposedHelpers.findClass("android.webkit.WebView", cl);
            XC_MethodHook setDesktopUa = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        WebView wv = (WebView) param.thisObject;
                        wv.getSettings().setUserAgentString(DESKTOP_CHROME_UA);
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedHelpers.findAndHookMethod(webView, "loadUrl", String.class, setDesktopUa);
            XposedHelpers.findAndHookMethod(webView, "loadUrl", String.class, Map.class, setDesktopUa);
            XposedHelpers.findAndHookMethod(webView, "postUrl", String.class, byte[].class, setDesktopUa);
            log("hooked WebView loadUrl/postUrl -> desktop Chrome UA");
        } catch (Throwable t) {
            log("WebView UA hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** Hook 一个无参 boolean 方法，固定返回 {@code value}。 */
    private void hookBooleanReturn(Class<?> clazz, String methodName, final boolean value) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            });
            log("hooked " + clazz.getSimpleName() + "." + methodName + " -> " + value);
        } catch (Throwable t) {
            log(clazz.getName() + "." + methodName + "() not found: " + t);
        }
    }

    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class) enumClass, name);
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
