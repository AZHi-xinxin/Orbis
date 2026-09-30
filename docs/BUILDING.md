# 从源码构建 Orbis

这份说明面向本地编译和开发，不代表已有可分发的正式安装包，也不代表签名、素材许可或全部真机功能已经验收。普通使用方法见 [上手指南](GETTING_STARTED.md)。

下面的命令都从源码根目录执行。版本号以 `app/build.gradle.kts` 为准，不要用文档里的名称判断两个安装包是否相同。

## 准备环境

当前工程使用以下配置：

| 项目 | 配置与说明 |
| --- | --- |
| Java | JDK 17；Java 和 Kotlin 的编译目标均为 17 |
| Gradle | 使用仓库自带的 Wrapper，当前为 9.5.0 |
| Android | `compileSdk` / `targetSdk` 为 37，应用最低支持 Android 8.0（API 26） |
| 原生构建 | `workspace` 模块使用 CMake 3.22.1；还需要 Android NDK |
| Web 前端 | 需要 Node.js 和可在终端调用的 pnpm；已有本地构建记录使用 Node 24、pnpm 11 |
| CPU 架构 | `arm64-v8a` 和 `x86_64`；APK 构建还配置了通用包 |

NDK 版本没有在当前模块配置中显式固定；既有本地构建记录使用 28.2.13676358。前端 `package.json` 也没有用 `packageManager` 字段固定 pnpm 版本。换环境时应记录实际工具版本，不能把这些本地记录当作完整兼容性保证。

安装 Android SDK 后，让 Android Studio 配置 SDK 位置，或在本机的 `local.properties` 中设置 `sdk.dir`。该文件只留在本机，不提交到仓库。

源码应包含 `material3/material-color-utilities/kotlin` 及其许可证。`material3` 直接编译这里的源码；若使用带 Git 子模块的检出方式，应补齐 `.gitmodules` 中声明的模块。若使用源码压缩包，也要确认这个目录不为空。不要只保留子模块路径而遗漏文件。

## 准备前端依赖

Android 构建会调用 `web` 模块的 `buildWebUi`，它在 `web-ui` 中运行 `pnpm run build`，再将产物复制到 `web/src/main/resources/static`。因此 pnpm 必须在构建进程的 PATH 中；仅安装 Android SDK 不够。

先在源码根目录执行：

```text
cd web-ui
pnpm install --frozen-lockfile --ignore-scripts
cd ..
```

保留 `web-ui/pnpm-lock.yaml`，不要为解决环境问题随意更新整份依赖。上面的安装方式不运行依赖的安装脚本；如果某个环境确实需要额外步骤，应先核对具体依赖，再决定是否执行，而不是对所有依赖直接放开脚本权限。

首次准备 Gradle 和前端依赖通常需要网络。只有相关依赖已经缓存齐全时，才适合使用 Gradle 的 `--offline`。构建本身不需要模型 API 密钥、Supabase 账号、咨询室凭据或他人的 `google-services.json`；不要把运行时配置写进源码。

## 构建开发包

Windows PowerShell：

```text
.\gradlew.bat :app:assembleDebug --console=plain
```

macOS / Linux：

```text
./gradlew :app:assembleDebug --console=plain
```

APK 产物位于 `app/build/outputs/apk/debug/`。根据设备架构选择安装包，或使用通用包。构建不会自动安装到手机。

Debug 的应用 ID 为 `org.orbis.agent.dev`；release 的应用 ID 为 `org.orbis.agent`。它们使用不同的应用数据空间，不会自动迁移对方的数据。对已有同包名应用做覆盖安装前，应先备份，并核对包名、签名和版本；不要把卸载当作普通升级步骤。

公开候选不需要早期私有页面的准备脚本，也不应复制私有调试资源、聊天、书库或个人配置来补齐构建。若导出的源码缺少所需公共资源，应修正源码导出，而不是补入私人文件。

## 咨询室保持关闭

默认构建中的 `ORBIS_CONSULTATION_ENABLED` 为 `false`，release 构建也明确固定为 `false`。公开版本的咨询室正在开发，暂不开放：不能配置、待命、加入或发起调用，旧的保存设置也不能绕过这个限制。咨询相关源码保留供开发与审阅。

源码另有仅供内部开发的 Gradle 属性 `orbisInternalConsultation`。只有 debug 构建显式将它设为 `true` 才会开启开发路径；它不是应用内设置，也不是公开用户的启用方法。准备公开候选时不要在命令、本机或用户级 `gradle.properties`、CI 参数中携带这个属性。release 不因这个 debug 属性而开放咨询。

`orbisIsolatedTests` 是另一项测试 runner 选择，与咨询室开关无关，也不应加入普通安装包的构建参数。

## 本地检查

可以先运行不连接手机的 JVM 检查与静态检查：

```text
.\gradlew.bat :app:testDebugUnitTest :ai:testDebugUnitTest --console=plain
.\gradlew.bat lint --console=plain
```

在 macOS / Linux 将 `.\gradlew.bat` 换为 `./gradlew`。全模块 JVM 测试任务为 `test`，但部分工作区测试依赖宿主机的 shell 或文件系统能力；保留真实失败结果，不要把平台相关失败直接改成通过。

这些命令不是已有验收回执。编译、单元测试或 Lint 通过，不等于手机权限、后台运行、WebView、模型接口、备份恢复和全部 UI 都可用。Android 仪器测试需要专用、无私人数据的测试环境；不要在日常手机上直接运行整套连接设备测试。

## Release 与签名

release APK 与 App Bundle 的任务分别是 `:app:assembleRelease`、`:app:bundleRelease`。未配置发布签名时，默认生成未签名产物；未签名 APK 不能直接安装。当前脚本从根目录的本机 `local.properties` 读取 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；四项齐全才会使用该 release 签名配置，不会退回调试签名。

仓库不提供签名密钥，也不保证你的 release 签名已经配置完成。需要发布或长期升级时，请使用并妥善备份自己的签名材料；不要提交密钥、密码或包含它们的配置文件。不要把调试签名产物说成正式签名产物。

在交付任何安装包之前，仍需核对最终包的签名、包名、版本、咨询关闭状态、实际包含的资源与第三方声明，并完成对应设备范围的测试。字体和素材的许可核对状态见 [来源与许可说明](NOTICE.md)。
