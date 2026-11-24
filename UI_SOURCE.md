# OASX 风格的 Android 前端

用户指定的风格参考为 `D:\OnmyojiAutoScript-easy-install\oasx_v0.3.15_windows`。这是 Flutter 打包目录。本机对应源代码在 `D:\OnmyojiAutoScript-easy-install\OASX`，参考 Git 提交 `3dd8cdaa2d25303adc27976294400b30939188f2`，只读使用；没有修改该仓库或打包目录。

## 源码对应

| OASX 源码 | Android 文件 | 适配内容 |
| --- | --- | --- |
| lib/config/theme.dart | ui/Theme.kt、NativeFormStyle.java、values/styles.xml | 相同 M3 Baseline 种子色 #6750A4；浅色底 #FFFBFF、深色底 #313033；相应表面色、表单和浅/深色主题 |
| lib/modules/common/widgets/primary_navigation_shell.dart | ui/AppScreen.kt、BottomNavigation.kt | 主页/设置两项主导航；宽屏 80dp 侧栏，窄屏底栏 |
| lib/modules/common/widgets/title.dart | ui/AppScreen.kt | OASX 图标 + OASX / 主页、OASX / 设置标题 |
| lib/modules/home/models/home_workbench_layout.dart | ui/HomeScreen.kt | 横屏脚本列表与工作台分栏（>=626dp）；手机竖屏单栏两页流（脚本列表 ↔ 工作台） |
| lib/modules/home/widgets/config_collection_panel.dart、config_collection_tile.dart、common/widgets/add_config_dialog_body.dart | ui/HomeScreen.kt、android_oas/bootstrap.py | 多脚本预设列表（新增/复制/重命名/删除/JSON 导入导出、状态计数、搜索/筛选、左侧 6dp 状态色条与首任务预览） |
| lib/modules/home/widgets/active_config_panel.dart | ui/HomeScreen.kt | 返回脚本列表按钮、批量立即运行/等待、状态指示圆点、电源启停、自动换行工作台页签 |
| lib/modules/home/widgets/task_catalog_panel.dart | ui/TaskScreen.kt | 搜索/筛选、可折叠分组边框卡片、左侧勾选图标、右侧立即运行/置为等待/参数三键 |
| lib/modules/home/widgets/task_status_panel.dart、task_status_row.dart | ui/TaskScreen.kt | 运行中/待运行/等待中三色任务卡片、点按时间修改 next_run、右侧快捷调度三键 |
| lib/modules/home/widgets/log_center_panel.dart、log_center_toolbar.dart | ui/HomeScreen.kt | 信息/结果分段切换、换行/自动滚动/复制/清空工具栏与等宽日志区 |
| lib/modules/settings/settings_view.dart、widgets/setting_card.dart | ui/SettingsScreen.kt、ui/SystemSettings.kt | 分组设置卡片（设备与环境、OAS 设置、外观与系统、运行与省电） |
| lib/modules/home/widgets/task_parameter_panel.dart、task_json_transfer_actions.dart、modules/args/index.dart | ui/OasxArgsForm.kt、ui/TaskEditor.kt | 原生 Compose OASX 任务参数页与展开分组卡（`secondaryContainer` 底色、默认展开、M3 下拉/日期选择器、单任务 JSON 导入/导出/复制、草稿栏） |
| lib/translation/cn_parts/*.dart | android_oas/oasx_labels.py | 只读提取 1171 条中文文案，补全桌面生成缓存缺失的标签 |

`tools/import-oasx-ui.py` 可重新提取文案与图标；逐文件来源 SHA-256 记录在 `app/src/main/assets/oasx-ui-source.json`。启动图标直接复用指定 0.3.15 打包目录中的 Icon-app.png。

## 手机适配

界面在原生 Android Kotlin / Compose 中实现，绑定现有 Python 引擎和 Shizuku，采用 OASX 的视觉规范与工作台结构；不是直接在手机中启动 Flutter Windows 打包程序。

本机只有 android 配置，因此脚本列表显示该真实配置，不提供无实现的新增服务器/远程脚本操作。设备权限入口对应本机 Shizuku；“日志”显示实际会话消息和最近任务结果；“画面”通过原生 Surface 显示后台虚拟屏实时画面，断开时遮盖旧帧并显示真实状态。任务总览只是原 OAS 调度计划，开始脚本后由服务执行，不创建系统闹钟。

OASX 源码与提取文案遵循 GPL-3.0，协议原文随包 OASX-LICENSE.txt。MaaMeow 仍是既有 Android 设备层的参考来源，见 THIRD_PARTY.md。

## 0.4.1 原菜单同步

任务分类直接读取内嵌原源码的 module/config/config_menu.py::ConfigMenu.menu；该文件与本机原 OAS 文件 SHA-256 相同。OASX 的 ApiClient.getScriptMenu() 读取 /script_menu 后按原映射迭代，手机版采用相同的数据来源和两级顺序，不另设任务归类表。

脚本/御魂副本/日常任务/肝帝专属/阴阳寮/每周任务/限时活动/游戏辅助，共 8 组、63 个原菜单入口（含 Script 与 GlobalGame 设置）。GotoMain 不在原菜单中，仍可通过高级工具返回庭院；百鬼夜行留在原分组但保持暂缓。顶部筛选与 OASX 相同，为全部任务/已启用/未启用。

## 0.4.3 实时画面与名称

应用名称为 OASA（Onmyoji AutoScript Android），OASX 仅指参考的界面风格及源码来源。

实时预览参考 MaaMeow 固定提交 df6dd42e1107f400b314b72244848c3b1dcd7635 的 `presentation/view/background/BackgroundTaskView.kt`、`presentation/viewmodel/BackgroundTaskViewModel.kt` 与 `native/bridge_preview.cpp`。上游用 AImage HardwareBuffer → EGLImage → OpenGL → SurfaceView；本工程使用 Java SurfaceTexture OES → EGL/OpenGL 双输出：SurfaceView 预览约上限 30fps，ImageReader 识别约 5fps。复用已有 PNG 截图识别 API；预览不经过 PNG、不落盘。不是 H.264/网络视频流。只下载相关源文件到 .downloads/maa-ui-reference，没有整仓复制。

LivePreview.kt 负责 Surface、Activity 生命周期、状态元数据查询和单指触控坐标映射；DisplayRenderer.java 在独立画面线程渲染两个输出；DeviceBridge 用预览 owner 防止旧页 detach 关闭新页。切页、暂停、窗口销毁均取消手指并断开预览，不停止后台任务。
