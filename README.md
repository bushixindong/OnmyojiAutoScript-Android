# OASA 0.5.0

OASA 全称 Onmyoji AutoScript Android（阴阳师自动脚本安卓版）。独立 Android 工程，目录 `D:\OAS-Android`，应用 ID `com.oas.android`。原 OAS 工作区只读参考，不改其配置或源码。

## 0.5.0 底层性能与系统优化

参考 MaaMeow 同源实现，在保留原 OAS 任务决策与 OASX 风格界面的基础上补齐安卓底层性能与长期挂机优化：

- **原始帧共享内存识别通道**：[raw_frames.cpp](file:///d:/OAS-Android/app/src/main/native/raw_frames.cpp) 三缓冲 + 只读 `SharedMemory` 映射 + ARM NEON / x86_64 RGB 转换（[RawFrameStore.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/bridge/RawFrameStore.java)、[RawFrameReader.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/bridge/RawFrameReader.java)），Python 识别经 `PythonHost.screenshotRgb()` 直接读取原始 RGB 帧，跳过 PNG 编解码；保留 PNG 截图作前台兼容兜底。
- **Android 14+ `VirtualDevice` 虚拟屏分支**：[VirtualDeviceDisplay.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/bridge/VirtualDeviceDisplay.java) 通过 `CompanionDeviceManager` + `VirtualDeviceManager` 4 档策略创建独立虚拟屏，受 ROM 限制时自动回退传统 `DisplayManager`。
- **实体屏熄屏、后台保活与定时唤醒**：[ScreenPower.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/bridge/ScreenPower.java) 与 [RuntimePowerGuard.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/RuntimePowerGuard.java) 在后台运行期间持有部分唤醒锁并定时维护虚拟屏活跃度，可选单独关闭实体屏电源并在退出或异常时恢复；不改手机系统亮度、分辨率或息屏时间。
- **游戏单独静音与恢复**：[GameAudio.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/bridge/GameAudio.java) 与 [DeviceSession.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/DeviceSession.java) 优先通过 `OP_PLAY_AUDIO`（失败时回退单应用播放器音量）仅静音目标游戏，并持久化原始权限状态以便结束、异常或重连时自动恢复。
- **游戏帧率监测与低帧提示**：[GameFpsMonitor.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/bridge/GameFpsMonitor.java) 在 API 33+ 优先注册 `ITaskFpsCallback`（不可用时回退虚拟屏合成帧率），持续低帧时给出提示。
- **多点触控实时预览与运行设置**：[ManualTouchController.java](file:///d:/OAS-Android/app/src/main/java/com/oas/android/bridge/ManualTouchController.java) 与 [LivePreview.kt](file:///d:/OAS-Android/app/src/main/java/com/oas/android/ui/LivePreview.kt) 支持多指操作并在切页/断连/脚本运行时自动取消按住触点；设置页新增 [SystemSettings.kt](file:///d:/OAS-Android/app/src/main/java/com/oas/android/ui/SystemSettings.kt) 管理保活、自动熄屏、自动静音、帧率监测及取图/预览帧率档位（15/30/60 FPS）。

## OASX 风格的新界面

0.4.3 将应用显示名改为 OASA。画面页使用 SurfaceView + EGL/OpenGL ES 连续预览后台虚拟屏，不编码视频、不轮询 PNG、不读取旧截图。仅在画面页可见且 Activity 处于 RESUMED 时连接；切页或退到后台断开。前台兼容模式明确提示不支持本页直播，避免镜像 OASA 自身。手动控制支持单指点击和滑动，脚本运行时禁用。

0.4.2 应用主界面、配置页和高级工具默认竖屏；游戏虚拟屏仍为独立的 1280×720 横屏。

0.4.1 直接读取原 OAS 的 ConfigMenu（与 OASX /script_menu 同源），保留脚本、御魂副本、日常任务、肝帝专属、阴阳寮、每周任务、限时活动、游戏辅助八组，以及原组内顺序。筛选为全部任务/已启用/未启用。

0.4.0 按用户指定的 OASX 0.3.15 与本机对应前端源码适配：紫色 Material 3 主题、原 OASX 图标、主页/设置导航、脚本工作台。横屏显示脚本列表与工作台分栏，竖屏为单栏。支持浅色、深色、跟随系统。具体源码对应见 [UI_SOURCE.md](UI_SOURCE.md)。

工作台包含任务总览、全部任务、日志、画面。全部任务支持中文搜索、分类、展开分组、启用状态和完整参数编辑；直接复用 1171 条 OASX 中文文案。设备与游戏通过独立面板配置，高级工具折叠收纳。

## 任务迁移范围

APK 内嵌 Python 3.10，直接运行原 OAS 的任务、共享组件、配置模型和资源；将桌面设备、图像 RPC 和 OCR RPC 换成 Android 实现。不是仅添加任务名称，也不要求手机连接电脑运行 Python。

- 原项目共 62 个任务入口；按用户要求暂缓百鬼夜行，其余 **61 个入口已接入**。
- MuMu Android 12 / x86_64：61 个入口导入和实例化、原 Pydantic 2 配置校验通过；64 组原生配置表单回读和 8 项集成审计通过。
- 原 OCR 模型通过 Android ONNX Runtime 实际推理；合成单行、检测框与原桌面结果对照通过。
- 原 GotoMain 通过完整执行器、图像运行时和假设备，从合成探索页返回庭院。取消、失败与空计划回放通过。
- **真实游戏逐任务测试留给用户。上述离线通过不表示 61 项已经实战成功。0.4.0 尚未在 ARM64 真机运行审计。**

完整逐项状态见 [MIGRATION_STATUS.json](MIGRATION_STATUS.json)，验证范围与测试顺序见 [VALIDATION.md](VALIDATION.md)。

## 使用

1. 安装 `dist/OASA-0.5.0-debug.apk`，打开 Shizuku 并给 OASA 授权；正常使用不需要 Root。
2. 在首页选择已登录的阴阳师包名，连接设备服务，选择显示模式并打开游戏。真机优先使用独立虚拟屏；MuMu 游戏使用前台兼容模式。
3. 进入“全部任务”页，按分类或搜索选择任务，点按进入配置，保存后运行。任务默认值来自原 OAS，首次请按需调整次数、时间、组队和账号选项。
4. “自定义任务”的列表支持添加条目、选择类型、编辑各自配置和删除；按列表顺序执行。
5. 各任务的 scheduler.enable、next_run、优先级等共同决定计划。在工作台点“启动脚本”执行，单次服务最长两小时；可随时从界面或通知栏停止。
6. 最近任务结果包含引擎状态与触控计数；失败时查看结果和截图，可从首页导出诊断记录。

应用自己的配置在私有目录 `files/oas-engine/config/android.json`，不会导入电脑个人配置。升级保留这些设置；停止任务后编辑。

## 手机设备层

- Shizuku shell UserService、独立 1280×720 虚拟屏、PNG 文件描述符截图和指定显示器触控；不改变手机主屏分辨率、密度或息屏时间。
- 大神纯 UI 领取任务可将**自建虚拟屏**切到 720×1280，结束后恢复横屏；前台模式不能完成这个竖屏切换。
- 连续按住拖动、画符的连续 DOWN/MOVE/UP、多点路径边界检查、文本输入、只读任务查询和游戏战报读取已接入。
- 计划复用原 TaskScheduler、AntiBanGuard 和任务囤积窗口；保留失败退避，连续三次失败停止。空闲可回庭院，按设置延时关闭游戏；手机不执行桌面模拟器开关操作。
- 仍保留 0.2.0 原生的日常、单人副本、识别和检查工具。完整引擎的任务入口在“全部任务”页中，高级原生工具归入“设置 → 识别与高级工具”。

## 平台限制

- Android 9+；APK 含 arm64-v8a、x86_64。Shizuku 需要已启动；Android 11+ 可用无线调试启动，旧系统通常需要电脑 ADB。
- 不同系统对受信任虚拟屏、锁屏后保活和输入的限制不同。旧版曾在小米 2201122G / Android 13 验证虚拟屏与短时锁屏工作；不能替代新版完整任务测试。
- MuMu 不支持可靠的真实游戏虚拟屏后台运行，请使用前台兼容模式；该模式要求实际游戏画面 1280×720，不会自动修改模拟器/手机分辨率。
- 大神签到固定使用原项目的纯 UI 领取模式，不运行 Frida 或 Root 提取。请先手动登录大神。
- 游戏内组队和账号切换流程随原任务一起接入，但未实际执行。原本地跨脚本协同数据库仍保留；本版没有手机与电脑之间的远程协同服务，也没有同时控制多台设备的界面。
- 百鬼夜行入口标为暂缓，不可运行，不加载其跟踪库或模型。

## 构建与离线验证

当前机器：JDK 25（Android Studio JBR）、SDK 37、AGP 9.5.1、Gradle 9.6.0、Chaquopy 17.2.0。

```powershell
.\build.ps1 -JavaHome D:\AndroidStudio\jbr
# 可显式指定 CPython 3.10；本机默认只读复用原 OAS toolkit 的解释器
.\build.ps1 -PythonExecutable C:\Python310\python.exe
# 显式选择安装设备
.\build.ps1 -JavaHome D:\AndroidStudio\jbr -Install -Serial 127.0.0.1:16384
# 仅自带离线画面，不执行真实游戏任务
.\tools\test-emulator.ps1 -Serial 127.0.0.1:16384
.\tools\test-engine.ps1 -Serial 127.0.0.1:16384
```

构建脚本运行 assembleDebug、15 项 JVM 测试和 Android Lint，生成 dist APK 与 SHA-256；使用 --no-daemon，完成后退出构建 JVM，释放内存。首次依赖准备可能耗时数分钟，完整 APK 约 355 MiB。

Pydantic Core 2.27.0 使用真实 Android 原生扩展，ARM64/x86_64 wheel 在本机 `.toolchains/wheels`。在其他机器重建这些被 Git 忽略的依赖时，使用 `tools/prepare-cross-build.py`、`tools/build-pydantic-android.sh` 和 `tools/package-pydantic-wheel.py`；需要 Linux/WSL、Python 3.10、Rust 1.85.1 和 Android NDK r27d。APK 使用不需要这些构建工具。

`tools/export-runtime.py --source <OAS目录>` 只读打包源码和资源，记录逐文件 SHA-256，不复制个人 config JSON、日志、截图或账号资料。两个上游无用的桌面 import 在导出包中移除，修改记录在 runtime manifest 内；原文件不改动。

第三方来源与协议见 [THIRD_PARTY.md](THIRD_PARTY.md)。
