<div align="center">

# OnmyojiAutoScript-Android (OASA)

<br>

<div>
    <img alt="platform" src="https://img.shields.io/badge/platform-Android%209%2B-3DDC84?logo=android&logoColor=white">
    <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white">
    <img alt="python" src="https://img.shields.io/badge/Python-3.10%20(Chaquopy)-3776AB?logo=python&logoColor=white">
</div>
<div>
    <img alt="license" src="https://img.shields.io/github/license/kongdr418/OnmyojiAutoScript-Android">
    <img alt="GitHub all releases" src="https://img.shields.io/github/downloads/kongdr418/OnmyojiAutoScript-Android/total">
    <img alt="stars" src="https://img.shields.io/github/stars/kongdr418/OnmyojiAutoScript-Android?style=social">
</div>

<br>

阴阳师自动化脚本 · 纯安卓本地独立运行版

无需电脑 · 免 Root · Shizuku 后台虚拟屏一键托管

#### 上游 OAS 仓库: [kongdr418/OnmyojiAutoScript](https://github.com/kongdr418/OnmyojiAutoScript) · [runhey/OnmyojiAutoScript](https://github.com/runhey/OnmyojiAutoScript)

</div>

---

## 功能 Features

直接内嵌原版 OAS Python 引擎与 ONNX OCR，覆盖原项目绝大多数自动化任务：

- **日常任务**：悬赏封印、小猫咪、日常琐事（含收益采集）、金币/经验妖怪、年兽、花合战、地域鬼王、逢魔之时、御魂整理、自动重启登录
- **每周相关**：真蛇、秘闻竞速、神秘商店、斗技、每周琐事、对弈竞猜等
- **阴阳寮**：结界上卡、结界蹭卡、结界突破、寮突破、狩猎战、集体任务、道馆、狭间暗域、宴会
- **御魂副本**：八岐大蛇、业原火、日轮之城、永生之海、六道之门
- **肝帝专属**：探索、契灵、御灵、觉醒副本、石距、百鬼夜行
- **限时活动**：爬塔、超鬼王、花车巡游、智力答题等（随 OAS 引擎同步支持）

### 安卓端显著特点

- **纯手机本地运行**：APK 内嵌 Python 3.10 与 ONNX Runtime，无需连接电脑、无需模拟器，手机随时随地挂机
- **Shizuku 后台独立虚拟屏（免 Root）**：创建独立 1280×720 横屏虚拟显示，后台运行不干扰正常刷手机，**不修改手机主屏分辨率、DPI 或息屏时间**；支持 Android 14+ `VirtualDevice` 与传统 `DisplayManager` 自动回退
- **原始帧加速与低功耗**：后台虚拟屏优先使用 NDK `AImageReader`，任务请求时才转换并通过共享内存传递 RGB。实时预览独立更新。支持后台保活、自动熄屏和游戏静音恢复，取图与预览可分别设置帧率上限。
- **计划等待休眠**：预测等待至少 60 秒时，可用系统精确闹钟释放 CPU 唤醒锁并在到点恢复。
- **实时画面与随时接管**：独立「画面」导航页，`SurfaceView + EGL` 实时预览后台虚拟屏，支持脚本运行中随时开启手动触控介入、一键停止脚本与释放虚拟屏，底部同步展示实时彩色日志
- **OASX 风格原生界面**：多配置管理、配置秒开缓存、直接导入/导出桌面端 OAS `.json` 配置文件；工作台完整提供 `总览 | 任务 | 日志 | 统计 | 收益` 五大视图，包含任务耗时/战斗柱状图、8 项资源每日收益与趋势折线图、以及报错日志与现场截图中心

---

## 快速使用 Quick Start

### 环境要求
- Android 9 及以上（推荐 Android 11+，支持无线调试免电脑启动 Shizuku）
- 已安装并启动 [Shizuku](https://shizuku.rikka.app/)（无需 Root）

### 使用步骤
1. 前往 [Releases](https://github.com/kongdr418/OnmyojiAutoScript-Android/releases) 下载并安装最新版 APK；
2. 打开 **Shizuku** 并确保服务正常运行，打开 OASA 并授予 Shizuku 权限；
3. 进入 **设备与游戏**，选择你的阴阳师客户端包名与 **后台虚拟屏** 模式，点击 **保存并打开虚拟屏幕**；
4. 在主页选择一个配置（或点右上角 `+` 新增 / **直接导入桌面端 OAS 导出的 `.json` 配置**），按需调整任务；
5. 点击配置右侧的 **电源按钮** 启动调度（游戏未运行时会自动触发 `Restart` 拉起并登录）；
6. 切到下方 **画面** 页即可实时预览虚拟屏、查看彩色日志、手动介入或停止与释放虚拟屏。

> **桌面配置互通说明**：从电脑版 OAS 导出的 `.json` 可直接导入 OASA。模拟器路径、ADB 序列号、桌面截图/控制方案等电脑专属项在手机端会自动适配或忽略，互不影响导出回电脑端。

---

## 声明 Announcement

本软件开源、免费，仅供学习交流使用。使用本软件产生的所有问题与本项目及开发者无关。  
OASA is a free open source software, if you paid for OASA from any channel, please refund.  
OASA 是一款免费开源软件，如果你在任何渠道付费购买了 OASA，请立刻退款。

---

## 从源码构建 Build

- 环境要求：JDK 17+（推荐 Android Studio JBR）、Android SDK（`compileSdk 37` / `targetSdk 35` / `minSdk 28`）、Android NDK `r27d`、Python 3.10

```powershell
# 编译 Debug APK 并运行单元测试与 Lint
.\build.ps1 -JavaHome <JDK_PATH>

# 编译并安装到指定 ADB 设备
.\build.ps1 -JavaHome <JDK_PATH> -Install -Serial <DEVICE_SERIAL>
```

---

## 相关项目与鸣谢 Relative Repositories

- [OnmyojiAutoScript](https://github.com/runhey/OnmyojiAutoScript) / [kongdr418/OnmyojiAutoScript](https://github.com/kongdr418/OnmyojiAutoScript)：阴阳师自动化脚本核心引擎
- [OASX](https://github.com/runhey/OASX)：跨平台 OAS 客户端，本项目界面风格、中文文案、统计与收益看板参考自此项目
- [Shizuku](https://github.com/RikkaApps/Shizuku)：免 Root 特权 API 桥接
- [MaaMeow](https://github.com/MaaAssistantArknights)：安卓端独立虚拟屏、共享内存原始帧、熄屏保活与单应用静音思路参考

---

## 许可证 LICENSE

This project is licensed under the GNU General Public License v3.0. 第三方组件说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。
