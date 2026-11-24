# 0.5.0 底层性能与系统优化验证

`versionCode 9` / `versionName 0.5.0`。在最新一轮代码改动后完成以下验证：

- **构建与静态检查**：`build.ps1` 构建 [OASA-0.5.0-debug.apk](file:///d:/OAS-Android/dist/OASA-0.5.0-debug.apk)、15 项 JVM 单元测试与 Android Lint 无 error 通过（`system-optimization-build.log`）。同时修复了 Windows PowerShell 5.1 下 `.ps1` 编码及 `local.properties` 写 BOM 导致找不到 SDK 的问题，以及 `tools/test-engine.ps1` 轮询时的误报错。
- **MuMu Android 12 / x86_64（`127.0.0.1:16384`）**：
  - `OptimizationSmokeMain` 通过（`system-optimization-smoke.log`）：共享内存原始 RGB 取图中位数 **0.81 ms**（对比 PNG **32.59 ms**）、`SharedMemory` 只读保护、多点触控、detach 取消按下触点、720×1280 横竖屏 resize 恢复、38 FPS 合成帧率监测、游戏静音与原始音频模式恢复、释放后拒绝旧帧并重建均通过。
  - `PreviewSmokeMain` 通过（`system-preview-smoke.log`）：87 缓冲、75 变化帧及全套预览生命周期回归通过。
  - 真实 Root 模式 Shizuku 降权链路通过（MuMu `moe.shizuku.privileged.api` v13.6.0）：`DeviceSession` 检测到 Root 服务（`uid=0`）后经 `NativeFrames.spawnShellBridge` 派生并降权 `ShellBridgeMain`（`uid=2000`），通过 `BridgeAttachProvider` 单次随机 token 接管后成功创建虚拟屏并启动 `ProbeActivity`，`force-stop` 后父子进程均通过管道 EOF 干净退出无残留。
  - 离线引擎审计 `tools/test-engine.ps1` 通过（`system-engine-test.log`、`engine-audit-mumu.json`）：61 个原任务入口与构造器、64 组配置表单、**10 项**集成探针（含新增 `raw-rgb-frame-path` 直接验证 Python `screenshotRgb` 路径）零失败。
  - `tools/test-live-preview.py` 通过（`system-live-preview-ui.log`）：SurfaceView 263 帧连续变化、映射点击滑动、切页/后台/释放重建及前台兼容提示通过。
  - `tools/test-ui.py`（OASX 手机版完整布局复验）通过（截图保存在 `captures/ui-oasx/`）：验证 OASX 竖屏两页流（`脚本列表` 计数/状态条/预览/`+` 新增配置与导入导出弹窗 → 工作台）、头部批量立即运行/等待与状态圆点、总览三色调度卡、任务页可折叠分组卡与三键操作、任务参数详页（默认展开的分组卡片、`secondaryContainer` 底色、M3 下拉/日期选择器与单任务 JSON 导入/导出/复制按钮）、日志信息/结果分段切换、画面实时预览、深浅色与横屏双栏切换。
- **Android 37 / API 14+ 测试模拟器（`emulator-5580`，已关闭只读实例）**：
  - `OptimizationSmokeMain panel` 通过：`VirtualDevice tier=0, unlocked=true, trusted=true`（`displayGroup=4`）、共享内存原始 RGB 取图（中位数 4.80 ms vs PNG 105.69 ms）、`ITaskFpsCallback`“游戏任务帧率” 29.8 FPS、多点触控、detach 取消、游戏静音/恢复、实体屏 `panelOff=true` 切换与恢复、resize/释放重建全部通过。（注：此前首次冷启动后立即运行曾出现过一次 `Raw frame is stale`，后续两次包含 `panel` 在内的运行均一次通过。）
  - `EngineAuditActivity` 通过：61 入口、64 表单、9 项审计零失败。
- **尚未验证范围**：新版 0.5.0 尚未安装到真机 `32a1fe51`（ARM64 / Android 13），真实游戏逐任务实战仍由用户测试，不能将离线审计和 Probe 冒烟等效为实战通过。

以下为之前版本的验证记录，不代表所有真实任务已经实测。

# 0.4.3 OASA 实时预览

应用名称、主页/设置标题改为 OASA（Onmyoji AutoScript Android）；沿用 OASX 风格。versionCode 8。构建、15 项 JVM 测试、Lint 通过（live-preview-build.log）。本版没有进行真机或真实游戏任务测试。原引擎离线审计 61 个任务入口、64 组配置表单、9 项集成检查通过（live-preview-engine-test.log）。

MuMu Android 12 / x86_64 的动态 Probe 验证：

- 原生 GPU 预览连续输出 93 个缓冲、80 个变化帧；检查 RGBA 颜色、上下方向；预览打开时原 PNG 识别截图仍正常。
- 单指点击/连续滑动、720×1280 虚拟屏 resize/恢复、过期 owner 的 detach 不影响新预览、关闭预览取消按下手指、detach 后停止输出、重新 attach、释放/重建均通过。
- 实际 Compose SurfaceView 显示连续变化画面，UI 检查期间预览计数增加 305 帧；通过主屏显示区域的缩放坐标点击和滑动，Probe 的 DOWN/MOVE/UP 计数及背景变化均符合预期。
- 切换日志页停止渲染、回到画面页恢复；按 Home 暂停、回到应用恢复；释放时覆盖旧帧，重建后自动重连。
- 前台兼容模式只显示不支持实时预览的提示；没有采集 OASA 自身、其他主屏应用或展示缓存游戏截图。
- 只操作 OASA 自带 ProbeActivity，不运行游戏。手动控制仅支持单指，UI 在脚本运行时禁用；不是多点触控或 H.264 视频流。

复验：`tools/test-live-preview.py --serial 127.0.0.1:16384`；Shell `PreviewSmokeMain` 使用已安装 APK 的 CLASSPATH。日志 live-preview-smoke.log、live-preview-ui-test.log；截图和状态报告 captures/live-preview-0.4.3/。ADB 输入显式指定主屏 `input -d 0`，避免虚拟屏触控改变系统焦点后测试点错显示器。

以下为之前版本的验证记录，不代表所有真实任务已经实测。

# 0.4.2 默认竖屏

主界面、EngineActivity 和 ToolsActivity 的 manifest 方向设为 portrait。构建、15 项 JVM 测试、Lint 通过。MuMu 安装后 force-stop 本应用，再按普通 MAIN/LAUNCHER 入口启动，没有传测试方向参数，实际截图为 720×1280 并显示 OASX 主界面。记录见 captures/ui-portrait-0.4.2/default-launch.png 和 report.json，构建日志 portrait-build.log。没有启动游戏或修改系统分辨率/密度。游戏与 Probe 虚拟屏保留原方向。

# 0.4.1 原任务菜单修正

- 菜单直接来自原 ConfigMenu，与 OASX /script_menu 同源。内嵌 config_menu.py 与本机原文件 SHA-256 一致。
- 全部 8 组和全部 63 个菜单入口的分组、顺序逐项与原菜单比较通过；每周琐事、斗技、协同对弈、百鬼棋局等按原版归每周任务，突破按原版归阴阳寮，游戏辅助单列。没有改原项目菜单或用户调度配置。
- 0.4.1 构建、15 JVM 测试、Lint、61 入口/构造器、64 配置回读及 9 项集成审计通过。
- MuMu UI 实际检查通过：八组中文标题与顺序，展开每周任务，周常/游戏辅助任务搜索，原版筛选菜单，横屏工作台。未运行游戏任务。截图 captures/ui-menu-0.4.1，日志 task-menu-ui.log/menu-engine-test.log/menu-build.log。
- 本次修正只改变菜单数据与呈现，原版工作台结构继续保留。以下为前版验证记录。

# 0.4.0 OASX 风格界面验证

参考用户指定的 OASX 0.3.15 打包资源和本机 OASX 源码，实现原图标、紫色 M3 主题、主页/设置主导航、脚本工作台。源码对应见 UI_SOURCE.md。

2026-11-24，MuMu Android 12 / x86_64 的最终 APK 验证通过：

- Debug 构建、15 项 JVM 测试、Android Lint 通过，无 error；既有非 SDK 接口和文案等 warning 仍保留，没有禁用检查或建立 baseline。
- 同一 APK 的 shell 设备 smoke：截图、指定显示器触控、hold-drag、连续绘制、越界拒绝、竖屏切换/恢复、释放/重建通过。只操作 OAS 自带 ProbeActivity。
- 61 个任务导入/实例化、64 组配置表单回读、8 项集成审计通过。新增任务启用状态持久化回读、中文标签和百鬼启用拒绝检查使用 android-audit 私有测试配置，未改用户任务选择。
- Android UIAutomator 实际操作：主页/设置导航，任务搜索与清除，八岐大蛇参数页和调度组展开，任务总览、日志、画面页，深浅色切换，设备面板，竖屏/横屏切换。横屏验证脚本列表、工作台和实际任务行均可见。
- UI 检查只运行本应用，未连接游戏控制服务、未启动游戏或执行真实游戏任务；结束后恢复原主题偏好和普通启动方式。

记录：ui-build.log、ui-device-smoke.log、engine-test-0.4.log、ui-test.log、engine-audit-mumu.json。截图与 UI 报告在 captures/ui-0.4.0/。重跑：`python tools/test-ui.py --serial 127.0.0.1:16384`。

仍未安装 0.4.0 到真机，ARM64 与真实游戏逐任务测试留给用户。百鬼夜行继续暂缓。以下 0.3.0 记录保留作引擎迁移历史范围。

# 0.3.0 验证与用户测试

## 已验证的范围

本次使用 MuMu Android 12 / API 32 / x86_64，设备 `127.0.0.1:16384`。没有启动或运行真实游戏任务；没有领取奖励、消耗体力、切换账号或发送游戏消息。真机 `32a1fe51` 保留此前版本，0.3.0 未安装实测。

- 构建：Debug APK、15 项 JVM 测试、Android Lint 通过。Lint 仍有非 SDK 设备接口、文案等既有 warning，无 error，未用 baseline 隐藏错误。
- 完整引擎：61 个原 ScriptTask 导入、真实构造器初始化、原 Pydantic 2 默认配置校验通过。百鬼夜行按用户要求排除。
- 原生配置界面：61 个任务及 GlobalGame、Script 的默认配置，共 63 组；另加自定义任务的 6 种非空条目为第 64 组。表单读回再用原模型校验，与规范化原值一致。
- OCR：4 条合成文字（12345、1.2万、633/180、准备）通过原 ppocronnx + Android ORT 推理，文字一致、置信度差异小于 0.015；检测页/竖排列共 7 处文本一致，框坐标与桌面参考差异不超过 4 像素。检测对照使用相同 resize；GEOS 多边形偏移只验证这组样本，不能视为与 pyclipper 对所有形状完全相等。
- 原执行器回放：真实 GotoMain 流程从合成探索页返回庭院，经本地 ImageRuntime 匹配、截图帧缓存与假触控；8 帧、一次返回点击。另验证按住拖动/画符参数、取消不触控、异常写 failed、空计划结束。
- 原生设备 smoke：独立虚拟屏截图、tap/swipe、hold-drag、连续路径恰好一次 DOWN/UP 与多个 MOVE、越界拒绝、720×1280 竖屏切换和恢复、释放/重建。只操作 OAS 自带 ProbeActivity。
- 旧原生能力：76 条图像规则及黑帧拒绝，4 条单行 OCR、7 处检测文字和空白拒绝；日常和四类单人副本通过假 AIDL 设备回放。完整回放报告在 offline-replay.log。

可复现报告：`engine-audit-mumu.json`；脚本 `tools/test-engine.ps1` 和 `tools/test-emulator.ps1`。离线检查并未穷举全部任务分支，也未证明真实活动的界面/模板没有版本变化。

## 用户真实测试顺序

1. 安装新版、重新连接 Shizuku、按需要重建虚拟屏并打开已登录的账号。先截图检查尺寸与默认庭院，再从“全部任务与完整配置”单独运行 GotoMain。
2. 每项先设最小次数/时间。分别测日常、邮件、资源读取、御魂/觉醒等单人副本；核对页面、识别、实际领取、票数、场次、结果与返回庭院。
3. 测组队、御魂切换、加成、委派、突破、寮任务等分支；邀请目标与账号配置由你确认后再运行。手机和电脑间的跨脚本远程联动本版没有实现。
4. 测活动任务；活动未开放时可能无法进入。测试协斗的选人、上阵、结算和战报文件读取，特别留意拖拽是否成功。
5. 自定义任务先配一项，再配多项和账号切换；先核对类型、顺序、服务器和角色，异常立即停止。
6. 大神需先手动登录；仅在虚拟屏模式测试纯 UI 领取，检查竖屏坐标，任务结束应恢复 1280×720 并回到阴阳师。
7. 单项稳定后再开启计划，核对优先级、下次时间、失败退避、任务囤积、作息休息、空闲关闭游戏和两小时上限。再测手动停止、短时锁屏与长时间保活。
8. 百鬼夜行暂不测试；入口不会启动。异常时保留“最近任务结果”、失败截图、配置项和当时游戏页面。

桌面任务同样存在版本、活动与皮肤差异。本版真实效果以用户测试为准，不能把导入成功、配置通过或假设备回放写成全量实战完成。

## 保留的历史证据

此前 0.1.x 在小米 2201122G / Android 13 验证了真实 Shizuku、虚拟屏截图触控、默认庭院往返和短时物理屏 OFF/虚拟屏 ON。0.2.0 在 MuMu 验证了原生日常与单人副本离线回放。这些不是 0.3.0 ARM64 完整引擎或长时间息屏运行的证据。
