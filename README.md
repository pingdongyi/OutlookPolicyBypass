<div align="center">

# Outlook Device Policy Bypass

**LSPosed / Xposed 模块：让 Microsoft Outlook 登录企业 / Microsoft 365 账号时跳过设备管理（MDM / Intune）策略要求**

</div>

---

## ✨ 功能

微软 Outlook（`com.microsoft.office.outlook`）在登录企业账号时，会根据 `DevicePolicy` 判断设备是否满足
MDM / Intune 要求。不满足时，Outlook 会强制要求注册设备管理、应用设备策略，否则无法继续使用。

本模块在 Outlook 进程内 Hook `DevicePolicy` 的两个判定方法，绕过设备策略限制：

| 方法 | 原行为 | Hook 后 |
| ---- | ------ | ------- |
| `DevicePolicy.requiresDeviceManagement()` | 密码策略要求时为 `true` | 固定返回 `false` |
| `DevicePolicy.isPolicyApplied()` | 策略是否已应用 | 固定返回 `true` |

> 无界面、无额外功耗，只在 Outlook 进程内生效。

> 模块包名：`com.outlookbypass.xposed`

## 🔧 实现原理

```
com.microsoft.office.outlook
      │
      ▼
com.microsoft.office.outlook.olmcore.managers.mdm.DevicePolicy
      ├── requiresDeviceManagement()  -> false  （不需要设备管理）
      └── isPolicyApplied()           -> true   （策略已应用，账号合规）
```

这两个方法在 Outlook 中被以下流程调用：

- `OlmDeviceEnrollmentManager.isDeviceManagementRequired()`
- `OlmDeviceEnrollmentManager.getRestrictedAccounts()`
- `OlmDeviceEnrollmentManager.updateAccountPolicyAndCheckIfStillCompliant()`
- `OlmDeviceEnrollmentManager.markAllAccountsAsInCompliance()`
- `AuthFragment.finishLoginWithResult()`

## 📦 适配说明（本机）

已针对本机 Outlook **5.2638.1**（`versionCode 72638120`）验证：

- 类 `com.microsoft.office.outlook.olmcore.managers.mdm.DevicePolicy` ✅ 存在
- `requiresDeviceManagement()Z` ✅ 存在，返回 `mIsPasswordRequired` 字段
- `isPolicyApplied()Z` ✅ 存在，返回 `mIsPolicyApplied` 字段

因此原模块的 Hook 目标在 5.2638.1 中仍然有效，无需改动类名 / 方法名。

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
