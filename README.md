<div align="center">

# Outlook Device Policy Bypass

**LSPosed / Xposed 模块：让 Microsoft Outlook 登录企业 / Microsoft 365 账号时跳过设备管理（MDM / Intune）策略要求**

</div>

---

## ✨ 功能

微软 Outlook（`com.microsoft.office.outlook`）在登录企业账号时，会根据 `DevicePolicy` 以及 Intune MAM SDK
判断设备是否满足 MDM / Intune 要求。不满足时，Outlook 会强制要求注册设备管理、应用设备策略，或提示
“使用管理应用重新注册设备”，否则无法继续使用。

本模块在 Outlook 进程内 Hook 两套设备管理链路的判定方法：

| 链路 | 方法 | Hook 后 |
| ---- | ---- | ------- |
| OLM 传统 MDM | `DevicePolicy.requiresDeviceManagement()` | 固定返回 `false` |
| OLM 传统 MDM | `DevicePolicy.isPolicyApplied()` | 固定返回 `true` |
| Intune MAM | `MAMWEAccountManager.getAccountStatus(...)` | 固定返回 `ENROLLMENT_SUCCEEDED` |
| Intune MAM | `MAMWEAccountManager.isCompanyPortalRequired(...)` | 固定返回 `false` |
| Intune MAM | `MAMEnrollmentManagerImpl.getRegisteredAccountStatus(...)` | 固定返回 `ENROLLMENT_SUCCEEDED` |
| Intune MAM | `OfflineMAMEnrollmentManager.remediateCompliance(...)` | 跳过（不弹“安装公司门户”） |
| Intune MAM | `OfflineMAMEnrollmentManager.showNonBlockingInstallSSPUI(...)` | 跳过（不弹“安装公司门户”） |

> 无界面、无额外功耗，只在 Outlook 进程内生效。

> 模块包名：`com.outlookbypass.xposed`

## 🔧 实现原理

```
com.microsoft.office.outlook
      │
      ├──▶ OLM 传统 MDM（旧链路）
      │      com.microsoft.office.outlook.olmcore.managers.mdm.DevicePolicy
      │        ├── requiresDeviceManagement()  -> false  （不需要设备管理）
      │        └── isPolicyApplied()           -> true   （策略已应用，账号合规）
      │
      └──▶ Intune MAM（公司门户 / Company Portal 链路）
             com.microsoft.intune.mam.policy.MAMWEAccountManager
               ├── getAccountStatus(...)             -> ENROLLMENT_SUCCEEDED
               └── isCompanyPortalRequired(...)      -> false
             com.microsoft.office.outlook.intune.impl.policy.MAMEnrollmentManagerImpl
               └── getRegisteredAccountStatus(...)   -> ENROLLMENT_SUCCEEDED
```

“使用管理应用重新注册设备” 来自第二条链路：设备曾注册过 Intune，但注册状态过期后
Outlook 拿到 `MAMEnrollmentManager$Result.COMPANY_PORTAL_REQUIRED` 并阻断登录。
Hook 后固定返回 `ENROLLMENT_SUCCEEDED` / `false`，即可绕过。

## 📦 适配说明（本机）

已针对本机 Outlook **5.2638.1**（`versionCode 72638120`）验证：

| 类 | 方法 | 状态 |
| -- | -- | -- |
| `...olmcore.managers.mdm.DevicePolicy` | `requiresDeviceManagement()Z` / `isPolicyApplied()Z` | ✅ 存在 |
| `com.microsoft.intune.mam.policy.MAMWEAccountManager` | `getAccountStatus(MAMIdentity)` | ✅ 存在 |
| `com.microsoft.intune.mam.policy.MAMWEAccountManager` | `isCompanyPortalRequired()` / `isCompanyPortalRequired(Context, MAMLogPIIFactory)` | ✅ 存在 |
| `...intune.impl.policy.MAMEnrollmentManagerImpl` | `getRegisteredAccountStatus(String, String)` | ✅ 存在 |

## 🚀 使用

1. 设备已安装 LSPosed / Xposed 环境；
2. 将本模块作用域勾选为 **Outlook（`com.microsoft.office.outlook`）**；
3. 激活后重启一次 Outlook（或手机）；
4. 登录企业账号时不再要求设备管理 / 设备策略。

## 🏗️ 构建

本地构建（Android Studio 或命令行）：

```bash
gradle assembleRelease
```

或使用 Android Studio 打开后直接 Build（会自动生成 Gradle Wrapper）。

产物：`app/build/outputs/apk/release/app-release.apk`。

仓库自带 GitHub Actions：push 到 `main` 会编译并上传 Artifact；push `v*` 标签会自动创建
Release 并附带 APK。

## 🛡️ 免责声明

本模块仅供学习与技术研究使用，请勿用于任何违反法律法规或公司安全策略的用途。作者不对使用本模块造成的任何后果承担责任。
