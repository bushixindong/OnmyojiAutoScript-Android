#!/usr/bin/env python3
"""OASA 本地 Web 打包控制台（零第三方依赖，纯 Python 标准库）。

功能：
- 展示 App 仓 / 引擎仓 Git 状态、runtime 校验和、已连接 ADB 设备（支持无线连接）
- 一键触发：导出 oas-runtime.zip / 编译 Release 或 Debug APK（支持单架构 arm64-v8a 提速）/ 跑单测 / 归档 dist / 自动 adb install -r
- 网页实时显示构建步骤、耗时、进度与流式日志，支持中途停止与历史日志查看
- 内置局域网 APK 下载，手机同一 WiFi 下打开页面即可直接下载安装包

启动：
    E:\\toolchains\\python310\\python.exe tools\\build_console.py
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import webbrowser
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOG_ROOT = ROOT / ".workbuddy" / "build-console"
PHONE_LOG_ROOT = LOG_ROOT / "phone-logs"
LOG_ROOT.mkdir(parents=True, exist_ok=True)
PHONE_LOG_ROOT.mkdir(parents=True, exist_ok=True)

DEFAULT_JAVA_HOME = os.environ.get("JAVA_HOME") or r"E:\toolchains\jdk21"
DEFAULT_ANDROID_HOME = os.environ.get("ANDROID_HOME") or r"E:\toolchains\android-sdk"
DEFAULT_PYTHON = r"E:\toolchains\python310\python.exe" if Path(r"E:\toolchains\python310\python.exe").is_file() else sys.executable
_SIBLING_ENGINE_DIR = ROOT.parent / "OnmyojiAutoScript"
DEFAULT_ENGINE_DIR = str(_SIBLING_ENGINE_DIR) if _SIBLING_ENGINE_DIR.is_dir() else r"E:\OnmyojiAutoScript"


def detect_lan_ip() -> str:
    # 优先挑真实的局域网 IPv4（避开 VPN/代理虚拟网卡如 28.0.0.1 / 198.18.x.x）
    try:
        candidates = []
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith(("192.168.", "10.")):
                candidates.append(ip)
            elif ip.startswith("172."):
                second = int(ip.split(".")[1])
                if 16 <= second <= 31:
                    candidates.append(ip)
        if candidates:
            for ip in candidates:
                if ip.startswith("192.168."):
                    return ip
            return candidates[0]
    except Exception:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def adb_path() -> str:
    candidate = Path(DEFAULT_ANDROID_HOME) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    return str(candidate) if candidate.is_file() else "adb"


def run_capture(cmd: list[str], cwd: Path | None = None, timeout: int = 12) -> tuple[int, str]:
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd) if cwd else None,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode, out.strip()
    except Exception as exc:
        return 1, str(exc)


def git_info(repo: Path) -> dict:
    if not (repo / ".git").exists():
        return {"exists": False, "path": str(repo)}
    _, branch = run_capture(["git", "-C", str(repo), "rev-parse", "--abbrev-ref", "HEAD"])
    _, commit = run_capture(["git", "-C", str(repo), "log", "-1", "--format=%h %s (%cr)"])
    _, st = run_capture(["git", "-C", str(repo), "status", "--short"])
    dirty = len([ln for ln in st.splitlines() if ln.strip()])
    return {
        "exists": True,
        "path": str(repo),
        "branch": branch or "?",
        "commit": commit or "?",
        "dirty": dirty,
    }


def runtime_info() -> dict:
    man_path = ROOT / "app" / "src" / "main" / "assets" / "runtime" / "manifest.json"
    zip_path = ROOT / "app" / "src" / "main" / "assets" / "runtime" / "oas-runtime.zip"
    if not man_path.is_file():
        return {"exists": False}
    try:
        data = json.loads(man_path.read_text(encoding="utf-8"))
        return {
            "exists": True,
            "taskCount": data.get("taskCount", 0),
            "fileCount": data.get("fileCount", 0),
            "archiveSha256": data.get("archiveSha256", ""),
            "zipSizeMb": round(zip_path.stat().st_size / 1048576, 1) if zip_path.is_file() else 0,
            "zipMtime": datetime.fromtimestamp(zip_path.stat().st_mtime).strftime("%Y-%m-%d %H:%M:%S") if zip_path.is_file() else "",
        }
    except Exception as exc:
        return {"exists": False, "error": str(exc)}


def list_adb_devices() -> list[dict]:
    code, out = run_capture([adb_path(), "devices", "-l"], timeout=8)
    if code != 0:
        return []
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        model = ""
        for token in parts[2:]:
            if token.startswith("model:"):
                model = token.split(":", 1)[1]
                break
        devices.append({"serial": serial, "state": state, "model": model or serial})
    return devices


def list_artifacts() -> list[dict]:
    items = []
    candidates = []
    dist_dir = ROOT / "dist"
    if dist_dir.is_dir():
        candidates.extend(sorted(dist_dir.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True))
    apk_out = ROOT / "app" / "build" / "outputs" / "apk"
    if apk_out.is_dir():
        candidates.extend(sorted(apk_out.rglob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True))
    seen = set()
    for p in candidates:
        rp = p.resolve()
        if rp in seen:
            continue
        seen.add(rp)
        rel = rp.relative_to(ROOT).as_posix()
        st = rp.stat()
        sha_file = rp.with_name(rp.name + ".sha256")
        sha = ""
        if sha_file.is_file():
            try:
                sha = sha_file.read_text(encoding="utf-8", errors="ignore").split()[0]
            except OSError:
                pass
        items.append({
            "name": rp.name,
            "rel": rel,
            "sizeMb": round(st.st_size / 1048576, 1),
            "mtime": datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M:%S"),
            "sha256": sha,
        })
    return items[:20]


def list_phone_logs() -> list[dict]:
    items = []
    if not PHONE_LOG_ROOT.is_dir():
        return items
    for p in sorted(PHONE_LOG_ROOT.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
        if not p.is_file() or p.suffix.lower() not in (".md", ".txt", ".log"):
            continue
        st = p.stat()
        title = p.name
        config_name = ""
        occurred = datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M:%S")
        summary = ""
        if p.suffix.lower() == ".md":
            try:
                head = p.read_text(encoding="utf-8", errors="ignore")[:1500]
                m_task = re.search(r"\*\*报错任务\*\*[：:][ \t]*([^\r\n]+)", head)
                m_cfg = re.search(r"\*\*配置名称\*\*[：:][ \t]*`?([^`\r\n]+)`?", head)
                m_time = re.search(r"\*\*发生时间\*\*[：:][ \t]*([^\r\n]+)", head)
                m_sum = re.search(r"\*\*异常摘要\*\*[：:][ \t]*([^\r\n]+)", head)
                if m_task:
                    title = m_task.group(1).strip().replace("`", "")
                if m_cfg:
                    config_name = m_cfg.group(1).strip()
                if m_time:
                    occurred = m_time.group(1).strip()
                if m_sum:
                    summary = m_sum.group(1).strip()
            except OSError:
                pass
        elif p.name.startswith("logcat-"):
            title = "实时 logcat 快照"
        items.append({
            "name": p.name,
            "title": f"{title} · {config_name}" if config_name else title,
            "sizeKb": round(st.st_size / 1024, 1),
            "mtime": occurred,
            "summary": summary,
            "isMd": p.suffix.lower() == ".md",
        })
    items.sort(key=lambda x: x["mtime"], reverse=True)
    return items[:30]


def parse_phone_log(path: Path) -> dict:
    raw = path.read_text(encoding="utf-8", errors="replace")
    if path.suffix.lower() != ".md":
        return {"name": path.name, "meta": [], "images": [], "text": raw}
    images = []
    def repl(m):
        b64 = re.sub(r"\s+", "", m.group(2))
        images.append(f"data:image/{m.group(1)};base64,{b64}")
        return ""
    cleaned = re.sub(r"!\[[^\]]*\]\(data:image/([^;]+);base64,([A-Za-z0-9+/=\s]+)\)", repl, raw)
    meta = [ln.lstrip("- ").strip() for ln in cleaned.splitlines()[:20] if ln.startswith("- **")]
    m_code = re.search(r"```[^\n]*\n(.*?)```", cleaned, re.S)
    text = m_code.group(1).strip() if m_code else cleaned
    return {"name": path.name, "meta": meta, "images": images, "text": text}


class BuildManager:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._running = False
        self._proc: subprocess.Popen | None = None
        self._cancel_requested = False
        self._build_id = ""
        self._status = "idle"  # idle | running | success | failed | cancelled
        self._started_at = 0.0
        self._finished_at = 0.0
        self._steps: list[dict] = []
        self._logs: list[str] = []
        self._history: collections.deque[dict] = collections.deque(maxlen=20)
        self._last_apk = ""
        self._load_history()

    def _load_history(self) -> None:
        for meta_file in sorted(LOG_ROOT.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)[:15]:
            try:
                self._history.append(json.loads(meta_file.read_text(encoding="utf-8")))
            except Exception:
                pass

    def snapshot(self, log_offset: int = 0) -> dict:
        with self._lock:
            now = time.time()
            elapsed = (
                (now - self._started_at)
                if self._running and self._started_at
                else ((self._finished_at - self._started_at) if self._finished_at and self._started_at else 0.0)
            )
            total_steps = len(self._steps)
            done_steps = sum(1 for s in self._steps if s["state"] == "done")
            running_bonus = 0.45 if any(s["state"] == "running" for s in self._steps) else 0.0
            progress = int(min(100, ((done_steps + running_bonus) / total_steps) * 100)) if total_steps else 0
            if self._status == "success":
                progress = 100
            return {
                "running": self._running,
                "buildId": self._build_id,
                "status": self._status,
                "elapsedSec": round(elapsed, 1),
                "progress": progress,
                "steps": list(self._steps),
                "logs": self._logs[log_offset:],
                "logTotal": len(self._logs),
                "lastApk": self._last_apk,
                "history": list(self._history),
            }

    def _append_log(self, line: str) -> None:
        ts = datetime.now().strftime("%H:%M:%S")
        with self._lock:
            self._logs.append(f"[{ts}] {line}")

    def _set_step(self, idx: int, state: str) -> None:
        with self._lock:
            if 0 <= idx < len(self._steps):
                self._steps[idx]["state"] = state

    def cancel(self) -> bool:
        with self._lock:
            if not self._running:
                return False
            self._cancel_requested = True
            proc = self._proc
        self._append_log("!!! 收到取消请求，正在终止当前进程...")
        if proc and proc.poll() is None:
            try:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
                else:
                    proc.terminate()
            except Exception:
                pass
        return True

    def start(self, opts: dict) -> tuple[bool, str]:
        with self._lock:
            if self._running:
                return False, "已有构建任务正在运行，请等待完成或先取消"
            self._running = True
            self._cancel_requested = False
            self._build_id = datetime.now().strftime("%Y%m%d-%H%M%S")
            self._status = "running"
            self._started_at = time.time()
            self._finished_at = 0.0
            self._logs = []
            self._last_apk = ""
            self._steps = self._plan_steps(opts)

        t = threading.Thread(target=self._run_worker, args=(opts,), daemon=True)
        t.start()
        return True, self._build_id

    def _plan_steps(self, opts: dict) -> list[dict]:
        steps = []
        if opts.get("exportRuntime"):
            steps.append({"id": "export", "title": "导出 oas-runtime.zip", "state": "pending"})
        if not opts.get("runtimeOnly"):
            variant = "Release" if opts.get("variant", "release") == "release" else "Debug"
            abi_label = "arm64-v8a" if opts.get("abi", "arm64") == "arm64" else "arm64+x86_64"
            steps.append({"id": "build", "title": f"编译 {variant} APK ({abi_label})", "state": "pending"})
            if opts.get("runTests"):
                steps.append({"id": "test", "title": "运行单元测试", "state": "pending"})
            if opts.get("copyDist"):
                steps.append({"id": "dist", "title": "归档到 dist/ 并计算 SHA-256", "state": "pending"})
            if opts.get("installDevice"):
                steps.append({"id": "install", "title": f"ADB 安装到 {opts.get('serial')}", "state": "pending"})
        return steps

    def _run_cmd(self, cmd: list[str], env: dict[str, str], cwd: Path = ROOT) -> int:
        self._append_log("$ " + " ".join(cmd))
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        with self._lock:
            self._proc = proc
        assert proc.stdout is not None
        for raw_line in proc.stdout:
            self._append_log(raw_line.rstrip("\r\n"))
        code = proc.wait()
        with self._lock:
            self._proc = None
        return code

    def _run_worker(self, opts: dict) -> None:
        env = os.environ.copy()
        env["JAVA_HOME"] = DEFAULT_JAVA_HOME
        env["ANDROID_HOME"] = DEFAULT_ANDROID_HOME
        env["ANDROID_SDK_ROOT"] = DEFAULT_ANDROID_HOME
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        env["PYTHONUTF8"] = "1"

        variant = "release" if opts.get("variant", "release") == "release" else "debug"
        abi_mode = opts.get("abi", "arm64")
        abi_prop = "arm64-v8a" if abi_mode == "arm64" else "arm64-v8a,x86_64"
        gradle_bat = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))

        ok = True
        try:
            for idx, step in enumerate(list(self._steps)):
                if self._cancel_requested:
                    ok = False
                    break
                self._set_step(idx, "running")
                sid = step["id"]
                self._append_log(f"========== [{idx + 1}/{len(self._steps)}] {step['title']} ==========")

                if sid == "export":
                    engine_dir = opts.get("engineDir") or DEFAULT_ENGINE_DIR
                    code = self._run_cmd([DEFAULT_PYTHON, str(ROOT / "tools" / "export-runtime.py"), "--source", engine_dir], env)
                    if code != 0:
                        self._set_step(idx, "failed")
                        ok = False
                        break
                    self._set_step(idx, "done")

                elif sid == "build":
                    task = ":app:assembleRelease" if variant == "release" else ":app:assembleDebug"
                    cmd = [
                        gradle_bat,
                        task,
                        f"-PpythonExecutable={DEFAULT_PYTHON}",
                        f"-PabiFilters={abi_prop}",
                        "--console=plain",
                    ]
                    code = self._run_cmd(cmd, env)
                    if code != 0:
                        self._set_step(idx, "failed")
                        ok = False
                        break
                    apk_path = ROOT / "app" / "build" / "outputs" / "apk" / variant / f"app-{variant}.apk"
                    if apk_path.is_file():
                        with self._lock:
                            self._last_apk = apk_path.relative_to(ROOT).as_posix()
                    self._set_step(idx, "done")

                elif sid == "test":
                    cmd = [
                        gradle_bat,
                        ":app:testDebugUnitTest",
                        f"-PpythonExecutable={DEFAULT_PYTHON}",
                        "--console=plain",
                    ]
                    code = self._run_cmd(cmd, env)
                    if code != 0:
                        self._set_step(idx, "failed")
                        ok = False
                        break
                    self._set_step(idx, "done")

                elif sid == "dist":
                    apk_path = ROOT / "app" / "build" / "outputs" / "apk" / variant / f"app-{variant}.apk"
                    if not apk_path.is_file():
                        self._append_log(f"ERROR: 找不到 APK: {apk_path}")
                        self._set_step(idx, "failed")
                        ok = False
                        break
                    gradle_txt = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
                    m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_txt)
                    ver = m.group(1) if m else "dev"
                    suffix = "" if variant == "release" else "-debug"
                    abi_tag = "-arm64" if abi_mode == "arm64" else ""
                    dist_dir = ROOT / "dist"
                    dist_dir.mkdir(parents=True, exist_ok=True)
                    dst = dist_dir / f"OASA-{ver}{abi_tag}{suffix}.apk"
                    shutil.copy2(apk_path, dst)
                    h = hashlib.sha256()
                    with dst.open("rb") as f:
                        for chunk in iter(lambda: f.read(1024 * 1024), b""):
                            h.update(chunk)
                    digest = h.hexdigest()
                    dst.with_name(dst.name + ".sha256").write_text(f"{digest}  {dst.name}\n", encoding="ascii")
                    self._append_log(f"已归档: {dst.name} ({round(dst.stat().st_size / 1048576, 1)} MB)")
                    self._append_log(f"SHA-256: {digest}")
                    with self._lock:
                        self._last_apk = dst.relative_to(ROOT).as_posix()
                    self._set_step(idx, "done")

                elif sid == "install":
                    serial = (opts.get("serial") or "").strip()
                    apk_path = ROOT / "app" / "build" / "outputs" / "apk" / variant / f"app-{variant}.apk"
                    if not serial:
                        self._append_log("ERROR: 未选择 ADB 设备")
                        self._set_step(idx, "failed")
                        ok = False
                        break
                    code = self._run_cmd([adb_path(), "-s", serial, "install", "-r", str(apk_path)], env)
                    if code != 0:
                        self._set_step(idx, "failed")
                        ok = False
                        break
                    self._set_step(idx, "done")

        except Exception as exc:
            self._append_log(f"FATAL: {exc}")
            ok = False
        finally:
            finished = time.time()
            with self._lock:
                self._finished_at = finished
                if self._cancel_requested:
                    self._status = "cancelled"
                elif ok:
                    self._status = "success"
                else:
                    self._status = "failed"
                self._running = False
                record = {
                    "buildId": self._build_id,
                    "status": self._status,
                    "elapsedSec": round(self._finished_at - self._started_at, 1),
                    "time": datetime.fromtimestamp(self._started_at).strftime("%Y-%m-%d %H:%M:%S"),
                    "summary": " + ".join(s["title"] for s in self._steps),
                    "apk": self._last_apk,
                }
                self._history.appendleft(record)
                log_text = "\n".join(self._logs)
            try:
                (LOG_ROOT / f"{record['buildId']}.log").write_text(log_text, encoding="utf-8")
                (LOG_ROOT / f"{record['buildId']}.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
            except OSError:
                pass


MANAGER = BuildManager()


HTML_PAGE = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>OASA 本地构建控制台</title>
<style>
  :root{
    --bg:#0c0e12; --card:#141820; --surface:#1c222d; --surface-2:#0f131a;
    --border:rgba(255,255,255,.08); --border-2:rgba(255,255,255,.15);
    --text:#eef2f6; --muted:#9aa5b5; --dim:#6b7688;
    --accent:#38bdf8; --accent-2:#0ea5e9; --ok:#22c55e; --warn:#f59e0b; --err:#ef4444;
    --topbar-bg:rgba(12,14,18,.88); --log-bg:#0b0f17; --log-text:#e2e8f0;
    --radius:12px; --shadow:0 8px 28px rgba(0,0,0,.28);
  }
  html[data-theme="light"]{
    --bg:#f3f5f8; --card:#ffffff; --surface:#f1f5f9; --surface-2:#f8fafc;
    --border:rgba(15,23,42,.09); --border-2:rgba(15,23,42,.16);
    --text:#0f172a; --muted:#475569; --dim:#64748b;
    --accent:#0284c7; --accent-2:#0369a1; --ok:#16a34a; --warn:#d97706; --err:#dc2626;
    --topbar-bg:rgba(255,255,255,.88); --log-bg:#0f172a; --log-text:#e2e8f0;
    --shadow:0 4px 20px rgba(15,23,42,.05);
  }
  *{box-sizing:border-box}
  html{scroll-behavior:smooth}
  body{margin:0;min-height:100vh;display:flex;flex-direction:column;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;background:var(--bg);color:var(--text);font-size:14px;line-height:1.55;font-variant-numeric:tabular-nums;transition:background .18s,color .18s}
  .mono{font-family:Consolas,'Cascadia Mono',Menlo,monospace;font-variant-numeric:tabular-nums}
  .num{font-variant-numeric:tabular-nums}
  .ico{width:16px;height:16px;flex:none;fill:none;stroke:currentColor;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round;vertical-align:-3px}
  .spin{animation:spin .8s linear infinite}
  @keyframes spin{to{transform:rotate(360deg)}}

  /* ---------- 顶部全局导航栏 ---------- */
  .topbar{position:sticky;top:0;z-index:60;background:var(--topbar-bg);backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);border-bottom:1px solid var(--border)}
  .topbar-inner{width:100%;max-width:1920px;margin:0 auto;padding:10px 24px;display:flex;align-items:center;gap:14px;flex-wrap:wrap}
  .brand{display:flex;align-items:center;gap:10px;min-width:0}
  .brand-logo{width:32px;height:32px;flex:none;display:grid;place-items:center;border-radius:8px;background:linear-gradient(135deg,var(--accent),#0369a1);color:#fff;box-shadow:0 3px 12px rgba(14,165,233,.28)}
  .brand-logo .ico{width:18px;height:18px}
  .brand-name{font-size:15.5px;font-weight:750;letter-spacing:.01em;white-space:nowrap}
  .brand-sub{font-size:11px;color:var(--dim);letter-spacing:.04em;margin-top:-2px}
  .pill{display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:600;padding:4px 11px;border-radius:999px;background:rgba(56,189,248,.10);border:1px solid rgba(56,189,248,.26);color:var(--accent);white-space:nowrap;font-variant-numeric:tabular-nums}
  .pill .dot{width:7px;height:7px;flex:none;border-radius:50%;background:var(--dim)}
  .pill.running{color:var(--accent);border-color:rgba(56,189,248,.45)}
  .pill.running .dot{background:var(--accent);animation:dotPulse 1.4s infinite}
  .pill.success{color:var(--ok);background:rgba(34,197,94,.11);border-color:rgba(34,197,94,.30)}
  .pill.success .dot{background:var(--ok)}
  .pill.failed{color:var(--err);background:rgba(239,68,68,.11);border-color:rgba(239,68,68,.32)}
  .pill.failed .dot{background:var(--err)}
  .pill.cancelled{color:var(--warn);background:rgba(245,158,11,.11);border-color:rgba(245,158,11,.32)}
  .pill.cancelled .dot{background:var(--warn)}
  @keyframes dotPulse{0%{box-shadow:0 0 0 0 rgba(56,189,248,.55)}70%{box-shadow:0 0 0 7px rgba(56,189,248,0)}100%{box-shadow:0 0 0 0 rgba(56,189,248,0)}}
  .tabs{display:flex;gap:3px;padding:3px;background:var(--surface);border:1px solid var(--border);border-radius:10px;max-width:100%;overflow-x:auto}
  .tab-btn{display:inline-flex;align-items:center;gap:7px;white-space:nowrap;border:1px solid transparent;background:transparent;color:var(--muted);padding:7px 14px;border-radius:7px;font-size:13.5px;font-weight:600;cursor:pointer;transition:.16s}
  .tab-btn:hover{color:var(--text);background:rgba(148,163,184,.10)}
  .tab-btn.active{color:var(--text);background:var(--card);border-color:var(--border-2);box-shadow:0 1px 4px rgba(0,0,0,.14)}
  .tab-badge{font-size:11px;font-weight:700;min-width:19px;text-align:center;padding:1px 6px;border-radius:6px;background:rgba(148,163,184,.16);color:var(--muted);font-variant-numeric:tabular-nums}
  .tab-btn.active .tab-badge{background:rgba(14,165,233,.16);color:var(--accent)}
  .top-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-left:auto}
  .dev-chip{display:inline-flex;align-items:center;gap:5px;font-size:12.5px;color:var(--dim);white-space:nowrap}
  .dev-chip.ok{color:var(--ok)}
  .dev-chip .ico{width:15px;height:15px}
  .top-actions select{width:auto;min-width:165px;max-width:260px;padding:7px 10px;font-size:12.5px}
  .conn-box{display:flex;align-items:center;gap:6px}
  .conn-box input{width:190px;padding:7px 10px;font-size:12.5px}
  select,input[type=text]{border-radius:8px;border:1px solid var(--border-2);background:var(--surface-2);color:var(--text);font-size:13.5px;padding:9px 12px;outline:none;transition:.15s}
  select:focus,input[type=text]:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(14,165,233,.14)}
  .lan-btn{display:inline-flex;align-items:center;gap:6px;max-width:235px;padding:7px 11px;border-radius:8px;border:1px solid var(--border);background:var(--surface);color:var(--accent);font-size:12.5px;cursor:pointer;white-space:nowrap}
  .lan-btn:hover{border-color:var(--border-2)}
  .lan-btn span{overflow:hidden;text-overflow:ellipsis}

  /* ---------- 页面骨架 ---------- */
  .page{flex:1;width:100%;max-width:1920px;margin:0 auto;padding:16px 24px 24px;display:flex;flex-direction:column}
  .tab-panel{display:none;animation:fade .18s ease;flex:1}
  .tab-panel.active{display:flex;flex-direction:column}
  @keyframes fade{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}
  .card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:18px 20px;box-shadow:var(--shadow);min-width:0}
  .card-hd{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;margin-bottom:14px}
  .card-title{display:flex;align-items:center;gap:8px;font-size:14.5px;font-weight:700;letter-spacing:.01em;margin:0}
  .card-title .ico{color:var(--accent)}
  .card-sub{font-size:12.5px;color:var(--dim);font-variant-numeric:tabular-nums}
  .hint{font-size:12.5px;color:var(--dim)}

  /* ---------- 紧凑环境状态条 ---------- */
  .status-strip{display:flex;align-items:center;gap:16px;flex-wrap:wrap;padding:11px 18px;margin-bottom:16px;background:var(--card);border:1px solid var(--border);border-radius:var(--radius);box-shadow:var(--shadow)}
  .strip-seg{display:flex;align-items:center;gap:9px;min-width:0;flex:1 1 280px}
  .strip-seg.rt-seg{flex:1.25 1 360px}
  .strip-div{width:1px;height:22px;background:var(--border-2);flex:none}
  .strip-lbl{display:inline-flex;align-items:center;gap:5px;font-size:12.5px;font-weight:700;color:var(--dim);text-transform:uppercase;letter-spacing:.03em;white-space:nowrap;flex:none}
  .strip-lbl .ico{color:var(--accent);width:15px;height:15px}
  .strip-branch{font-size:12.5px;font-weight:700;color:var(--text);background:var(--surface);border:1px solid var(--border);border-radius:6px;padding:2px 8px;white-space:nowrap;flex:none}
  .strip-dirty{font-size:12.5px;font-weight:600;color:var(--muted);white-space:nowrap;flex:none}
  .strip-dirty.has-dirty{color:var(--warn)}
  .strip-commit{font-size:12.5px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0;flex:1}
  .strip-kv{font-size:13px;color:var(--text);font-weight:600;white-space:nowrap;flex:none}
  .strip-actions{display:flex;align-items:center;gap:6px;margin-left:auto;flex:none}

  /* ---------- 构建台 ---------- */
  .console-grid{flex:1;display:grid;grid-template-columns:400px minmax(0,1fr);gap:16px;align-items:stretch}
  .config-card{display:flex;flex-direction:column}
  .log-card{display:flex;flex-direction:column;min-height:calc(100vh - 160px)}
  .field{margin-bottom:13px}
  .field>label{display:block;font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:var(--dim);font-weight:700;margin-bottom:6px}
  .field select{width:100%}
  .presets{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-bottom:14px}
  .preset{display:flex;flex-direction:column;align-items:flex-start;gap:5px;width:100%;text-align:left;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);color:var(--text);padding:10px 11px;cursor:pointer;transition:.15s;min-width:0}
  .preset:hover{background:var(--surface);border-color:var(--border-2);transform:translateY(-1px)}
  .preset.active{border-color:var(--accent);background:rgba(14,165,233,.11)}
  .preset .p-top{display:flex;align-items:center;gap:6px;width:100%}
  .preset .p-ico{width:24px;height:24px;flex:none;display:grid;place-items:center;border-radius:6px;background:rgba(56,189,248,.14);color:var(--accent)}
  .preset .p-ico .ico{width:14px;height:14px}
  .preset[data-preset=hot] .p-ico{background:rgba(245,158,11,.15);color:var(--warn)}
  .preset b{display:block;font-size:13px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .preset small{display:block;font-size:11.5px;color:var(--dim);line-height:1.35}
  .divider{height:1px;background:var(--border);margin:14px 0}
  .chk{display:flex;align-items:flex-start;gap:10px;font-size:13.5px;line-height:1.5;margin:9px 0;cursor:pointer;color:var(--text)}
  .chk input{appearance:none;-webkit-appearance:none;width:17px;height:17px;flex:none;border:1px solid var(--border-2);border-radius:5px;background:var(--surface-2);position:relative;cursor:pointer;margin:2px 0 0;transition:.15s}
  .chk input:checked{background:var(--accent-2);border-color:var(--accent-2)}
  .chk input:checked::after{content:'';position:absolute;left:5px;top:2px;width:4px;height:8px;border:solid #fff;border-width:0 2px 2px 0;transform:rotate(45deg)}
  .target-line{display:flex;align-items:center;justify-content:space-between;gap:10px;font-size:13px;color:var(--dim);background:var(--surface-2);border:1px solid var(--border);border-radius:8px;padding:9px 12px;margin-top:auto}
  .target-line>span{white-space:nowrap;flex:none}
  .target-line b{color:var(--accent);font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0;text-align:right}
  .btns{display:flex;gap:8px;flex-wrap:wrap;margin-top:13px}
  .btn{display:inline-flex;align-items:center;justify-content:center;gap:7px;border:1px solid transparent;border-radius:8px;padding:9px 14px;font-size:13.5px;font-weight:650;color:var(--text);background:var(--surface);cursor:pointer;transition:.15s;font-family:inherit;white-space:nowrap}
  .btn:hover:not(:disabled){border-color:var(--border-2);filter:brightness(1.06)}
  .btn:active:not(:disabled){transform:translateY(1px)}
  .btn:disabled{opacity:.45;cursor:not-allowed}
  .btn-primary{background:linear-gradient(135deg,var(--accent-2),#0284c7);color:#fff;box-shadow:0 3px 12px rgba(14,165,233,.24)}
  .btn-primary:hover:not(:disabled){filter:brightness(1.08);border-color:transparent}
  .btn-danger{background:rgba(239,68,68,.13);border-color:rgba(239,68,68,.34);color:var(--err)}
  .btn-danger:hover:not(:disabled){background:rgba(239,68,68,.20)}
  .btn-ghost{background:var(--surface);border-color:var(--border)}
  .btn-sm{padding:6px 11px;font-size:12.5px;border-radius:7px}
  .btn-icon{padding:0;width:33px;height:33px}
  .btn.hl{animation:hi 1s ease-in-out 3;border-color:var(--warn)!important}
  @keyframes hi{0%,100%{box-shadow:0 0 0 0 rgba(245,158,11,.0)}50%{box-shadow:0 0 0 4px rgba(245,158,11,.28)}}
  .progress-shell{display:flex;align-items:center;gap:10px;margin-bottom:10px}
  .progress-wrap{flex:1;background:var(--surface-2);border-radius:999px;height:9px;overflow:hidden;border:1px solid var(--border)}
  .progress-bar{height:100%;width:0%;background:linear-gradient(90deg,#0284c7,var(--accent));transition:width .3s;border-radius:999px}
  .progress-bar.ok{background:linear-gradient(90deg,#16a34a,var(--ok))}
  .progress-bar.err{background:linear-gradient(90deg,#b91c1c,var(--err))}
  .progress-bar.warn{background:linear-gradient(90deg,#b45309,var(--warn))}
  .steps{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px;min-height:26px}
  .step{font-size:12.5px;padding:4px 10px;border-radius:7px;background:var(--surface-2);border:1px solid var(--border);color:var(--muted);font-variant-numeric:tabular-nums}
  .step.running{border-color:rgba(56,189,248,.5);color:var(--accent);background:rgba(56,189,248,.08)}
  .step.done{border-color:rgba(34,197,94,.35);color:var(--ok);background:rgba(34,197,94,.08)}
  .step.failed{border-color:rgba(239,68,68,.42);color:var(--err);background:rgba(239,68,68,.08)}
  .step-hint{font-size:12.5px;color:var(--dim)}
  .hist-banner{display:none;align-items:center;justify-content:space-between;gap:10px;padding:9px 12px;border:1px solid rgba(245,158,11,.34);background:rgba(245,158,11,.10);color:var(--warn);border-radius:9px;margin-bottom:10px;font-size:12.5px;flex-wrap:wrap}
  .hist-banner.show{display:flex}
  .log-shell{flex:1;display:flex;flex-direction:column;min-height:420px;border:1px solid var(--border);border-radius:10px;overflow:hidden;background:var(--log-bg)}
  .log-tools{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:9px 12px;background:var(--surface);border-bottom:1px solid var(--border)}
  .log-tools .input{width:210px;padding:6px 10px;font-size:12.5px}
  .log-tools .chk{font-size:12.5px;margin:0;color:var(--muted)}
  .log-tools .chk input{width:15px;height:15px;margin:0}
  .log-tools .chk input:checked::after{left:4.5px;top:1.5px;width:4px;height:7px}
  .log-sep{width:1px;height:18px;background:var(--border-2)}
  .log-count{margin-left:auto;font-size:12px;color:var(--dim);font-variant-numeric:tabular-nums}
  pre.log{margin:0;flex:1;min-height:360px;overflow:auto;padding:14px 16px;background:var(--log-bg);color:var(--log-text);font-family:Consolas,'Cascadia Mono',Menlo,monospace;font-size:13px;line-height:1.65;white-space:pre-wrap;word-break:break-word;scrollbar-color:#334155 transparent}
  .phone-log-pre{height:calc(100vh - 340px);min-height:360px;flex:none}
  .ln-err{color:#f87171}
  .ln-ok{color:#4ade80;font-weight:600}
  .ln-task{color:#38bdf8}
  .ln-sec{color:#fbbf24;font-weight:600}
  .log-empty{color:#64748b}

  /* ---------- 产物与历史 ---------- */
  .stack{display:flex;flex-direction:column;gap:16px}
  .table-scroll{overflow-x:auto}
  .data-table{width:100%;border-collapse:collapse;min-width:600px;font-size:13.5px}
  .data-table th{text-align:left;color:var(--dim);font-weight:700;font-size:12px;text-transform:uppercase;letter-spacing:.04em;padding:9px 10px;border-bottom:1px solid var(--border-2);white-space:nowrap}
  .data-table td{padding:11px 10px;border-bottom:1px solid var(--border);vertical-align:middle}
  .data-table tr:last-child td{border-bottom:0}
  .data-table tbody tr:hover{background:rgba(148,163,184,.04)}
  .file-name{display:flex;align-items:center;gap:6px;font-weight:650;color:var(--text);white-space:nowrap}
  .file-name .ico{color:var(--accent)}
  .file-rel{font-size:12px;color:var(--dim);margin-top:3px;white-space:nowrap}
  .sha-chip{display:inline-block;font-size:12px;color:var(--muted);background:var(--surface-2);border:1px solid var(--border);border-radius:6px;padding:2px 7px;cursor:pointer;white-space:nowrap}
  .sha-chip:hover{color:var(--accent);border-color:rgba(56,189,248,.4)}
  .ops{display:flex;align-items:center;gap:7px;white-space:nowrap}
  .ops a{text-decoration:none;color:inherit}
  .badge{display:inline-block;padding:3px 9px;border-radius:6px;font-size:12px;font-weight:700;white-space:nowrap}
  .b-success{background:rgba(34,197,94,.14);color:#4ade80}
  .b-failed{background:rgba(239,68,68,.14);color:#f87171}
  .b-cancelled{background:rgba(245,158,11,.14);color:#fbbf24}
  .b-running{background:rgba(56,189,248,.14);color:#38bdf8}
  .b-idle{background:rgba(148,163,184,.14);color:var(--muted)}
  .empty-row{color:var(--dim);text-align:center;padding:22px!important}
  .sum-link{color:var(--muted);cursor:pointer;text-decoration:none;border-bottom:1px dashed var(--border-2)}
  .sum-link:hover{color:var(--accent)}

  /* ---------- 手机日志 ---------- */
  .phone-grid{display:grid;grid-template-columns:minmax(300px,380px) minmax(0,1fr);gap:16px;align-items:start}
  .phone-list{max-height:calc(100vh - 260px);min-height:360px;overflow:auto;display:flex;flex-direction:column;gap:6px;padding-right:2px}
  .log-item{display:block;width:100%;text-align:left;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);color:var(--text);padding:9px 11px;cursor:pointer;transition:.15s}
  .log-item:hover{background:var(--surface);border-color:var(--border-2)}
  .log-item.active{border-color:var(--accent);background:rgba(14,165,233,.10)}
  .li-title{display:block;font-size:13.5px;font-weight:650;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .li-sum{font-size:12px;color:var(--err);margin-top:3px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .li-foot{display:flex;justify-content:space-between;gap:8px;font-size:11.5px;color:var(--dim);margin-top:5px;font-variant-numeric:tabular-nums}
  .sync-status{font-size:12px;color:var(--dim);font-variant-numeric:tabular-nums;max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .sync-status.ok{color:var(--ok)}
  .sync-status.err{color:var(--err)}
  .phone-title{font-size:14px;color:var(--text)}
  .meta-tags{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}
  .tag{font-size:12px;color:var(--muted);background:var(--surface);border:1px solid var(--border);border-radius:6px;padding:3px 8px}
  .img-grid{display:flex;gap:9px;flex-wrap:wrap;margin:10px 0}
  .thumb{position:relative;width:112px;height:78px;padding:0;border:1px solid var(--border-2);border-radius:9px;overflow:hidden;background:var(--surface-2);cursor:zoom-in}
  .thumb:hover{border-color:var(--accent)}
  .thumb img{width:100%;height:100%;object-fit:cover;display:block}
  .thumb .ico{position:absolute;right:4px;bottom:4px;width:13px;height:13px;color:#fff;filter:drop-shadow(0 1px 2px #000)}
  .thumb-empty{font-size:11px;color:var(--dim);padding:4px 0}

  /* ---------- Toast / Modal / Lightbox ---------- */
  .toast-wrap{position:fixed;top:78px;right:18px;z-index:200;display:flex;flex-direction:column;gap:9px;width:min(350px,calc(100vw - 32px));pointer-events:none}
  .toast{pointer-events:auto;display:flex;align-items:flex-start;gap:9px;padding:11px 13px;border-radius:10px;background:var(--surface);border:1px solid var(--border-2);border-left:3px solid var(--accent);box-shadow:0 14px 34px rgba(0,0,0,.45);font-size:13px;line-height:1.5;animation:toastIn .22s ease}
  .toast.success{border-left-color:var(--ok)}
  .toast.error{border-left-color:var(--err)}
  .toast.info{border-left-color:var(--accent)}
  .toast .t-ico{margin-top:2px;color:var(--accent)}
  .toast.success .t-ico{color:var(--ok)}
  .toast.error .t-ico{color:var(--err)}
  .toast .t-body{flex:1;min-width:0;word-break:break-word}
  .toast .t-close{border:0;background:transparent;color:var(--dim);cursor:pointer;padding:0;line-height:1}
  .toast .t-close:hover{color:var(--text)}
  .toast.out{opacity:0;transform:translateX(18px);transition:.22s}
  @keyframes toastIn{from{opacity:0;transform:translateX(18px)}to{opacity:1;transform:none}}
  .mask{position:fixed;inset:0;z-index:300;display:none;align-items:center;justify-content:center;padding:20px;background:rgba(3,7,18,.74);backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px)}
  .mask.show{display:flex}
  .modal{width:min(430px,100%);background:var(--card);border:1px solid var(--border-2);border-radius:16px;box-shadow:0 24px 70px rgba(0,0,0,.6);padding:20px;animation:modalIn .2s ease}
  @keyframes modalIn{from{opacity:0;transform:translateY(10px) scale(.98)}to{opacity:1;transform:none}}
  .modal h3{margin:0 0 10px;font-size:16px}
  .modal p{margin:0;color:var(--muted);font-size:13.5px;line-height:1.65;word-break:break-word}
  .modal-foot{display:flex;justify-content:flex-end;gap:8px;margin-top:19px}
  .lightbox{position:fixed;inset:0;z-index:400;display:none;align-items:center;justify-content:center;padding:28px;background:rgba(2,6,16,.93)}
  .lightbox.show{display:flex}
  .lightbox img{max-width:100%;max-height:100%;object-fit:contain;border-radius:10px;box-shadow:0 20px 70px rgba(0,0,0,.8)}
  .lb-close{position:absolute;top:16px;right:18px;width:36px;height:36px;border-radius:50%;display:grid;place-items:center;background:var(--surface);border:1px solid var(--border-2);color:var(--text);cursor:pointer}

  /* ---------- 窄屏适配 ---------- */
  @media(max-width:1180px){
    .strip-div{display:none}
    .console-grid{grid-template-columns:1fr}
    .log-card{min-height:520px}
    .phone-grid{grid-template-columns:1fr}
    .phone-list{max-height:280px;min-height:140px}
    .phone-log-pre{height:380px}
    .tabs{order:3;width:100%;justify-content:flex-start}
    .top-actions{margin-left:0}
  }
  @media(max-width:720px){
    .topbar-inner{padding:9px 12px;gap:9px}
    .brand-sub{display:none}
    .top-actions{width:100%}
    .conn-box{flex:1}
    .conn-box input{width:100%;min-width:0}
    .top-actions select{max-width:160px}
    .page{padding:12px 12px 24px}
    .presets{grid-template-columns:1fr}
    .card{padding:13px}
    .log-tools .input{width:100%}
    .log-count{margin-left:0}
    pre.log{min-height:320px}
    .toast-wrap{top:auto;bottom:14px;right:12px}
  }
</style>
</head>
<body>
<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">
  <symbol id="i-brand" viewBox="0 0 24 24"><path d="M12 2.6 20 7v10l-8 4.4L4 17V7z"/><path d="M12 7.5v9M7.8 10l8.4 4M16.2 10l-8.4 4"/></symbol>
  <symbol id="i-refresh" viewBox="0 0 24 24"><path d="M20 11a8 8 0 1 0-2.3 6.7"/><path d="M20 4v7h-7"/></symbol>
  <symbol id="i-copy" viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></symbol>
  <symbol id="i-wifi" viewBox="0 0 24 24"><path d="M2.5 8.5a15 15 0 0 1 19 0"/><path d="M5.5 12a10 10 0 0 1 13 0"/><path d="M8.5 15.5a5 5 0 0 1 7 0"/><circle cx="12" cy="19" r=".9" fill="currentColor" stroke="none"/></symbol>
  <symbol id="i-phone" viewBox="0 0 24 24"><rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18.5h2"/></symbol>
  <symbol id="i-check" viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5"/></symbol>
  <symbol id="i-x" viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></symbol>
  <symbol id="i-alert" viewBox="0 0 24 24"><path d="M12 3 2.5 20h19z"/><path d="M12 9v5M12 17.2v.1"/></symbol>
  <symbol id="i-info" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.1"/></symbol>
  <symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></symbol>
  <symbol id="i-download" viewBox="0 0 24 24"><path d="M12 3v12m0 0 4.5-4.5M12 15l-4.5-4.5"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></symbol>
  <symbol id="i-install" viewBox="0 0 24 24"><path d="M12 3v11m0 0 4-4m-4 4-4-4"/><path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/></symbol>
  <symbol id="i-play" viewBox="0 0 24 24"><path d="M8 5.5v13l11-6.5z"/></symbol>
  <symbol id="i-stop" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/></symbol>
  <symbol id="i-package" viewBox="0 0 24 24"><path d="m12 2.8 8 4.3v9.8l-8 4.3-8-4.3V7.1z"/><path d="M4 7.1 12 11.5l8-4.4M12 11.5V21"/></symbol>
  <symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></symbol>
  <symbol id="i-terminal" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="m7 9 3 3-3 3M13 15h4"/></symbol>
  <symbol id="i-image" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9.5" r="1.5"/><path d="m4 17 5-5 4 4 3-3 4 4"/></symbol>
  <symbol id="i-zap" viewBox="0 0 24 24"><path d="M13 2 4.5 13.5H11L10 22l8.5-11.5H12z"/></symbol>
  <symbol id="i-git" viewBox="0 0 24 24"><circle cx="6" cy="5" r="2.5"/><circle cx="6" cy="19" r="2.5"/><circle cx="18" cy="7" r="2.5"/><path d="M6 7.5v9M8.5 6.5h4A4 4 0 0 1 16.5 10.5v2"/></symbol>
  <symbol id="i-file-text" viewBox="0 0 24 24"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h6"/></symbol>
  <symbol id="i-spinner" viewBox="0 0 24 24"><path d="M12 3a9 9 0 1 0 9 9"/></symbol>
  <symbol id="i-sun" viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><path d="M12 2v2.5M12 19.5V22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M2 12h2.5M19.5 12H22M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"/></symbol>
  <symbol id="i-moon" viewBox="0 0 24 24"><path d="M20.5 14.5A8.5 8.5 0 1 1 9.5 3.5a6.8 6.8 0 0 0 11 11z"/></symbol>
</svg>

<header class="topbar">
 <div class="topbar-inner">
  <div class="brand">
    <div class="brand-logo"><svg class="ico"><use href="#i-brand"/></svg></div>
    <div>
      <div class="brand-name">OASA 构建控制台</div>
      <div class="brand-sub">ONMYOJI AUTO SCRIPT · LOCAL</div>
    </div>
    <span class="pill idle" id="statusPill"><i class="dot"></i><span id="statusText">空闲</span></span>
  </div>

  <nav class="tabs">
    <button class="tab-btn active" data-tab="build"><svg class="ico"><use href="#i-terminal"/></svg>构建台</button>
    <button class="tab-btn" data-tab="artifacts"><svg class="ico"><use href="#i-package"/></svg>产物与历史<span class="tab-badge" id="badgeArtifacts">0</span></button>
    <button class="tab-btn" data-tab="phone"><svg class="ico"><use href="#i-phone"/></svg>手机日志<span class="tab-badge" id="badgePhone">0</span></button>
  </nav>

  <div class="top-actions">
    <span class="dev-chip" id="devCount"><svg class="ico"><use href="#i-phone"/></svg>检测中</span>
    <select id="serial" title="全局 ADB 设备（所有 Tab 共用）"><option value="">（未检测到设备）</option></select>
    <div class="conn-box">
      <input type="text" id="adbAddr" placeholder="无线 ADB 地址，回车连接" title="上次地址会自动记住" />
      <button class="btn btn-sm btn-ghost" id="btnAdbConnect" title="连接无线 ADB"><svg class="ico"><use href="#i-wifi"/></svg>连接</button>
    </div>
    <button class="lan-btn" id="lanBtn" title="点击复制局域网地址"><svg class="ico"><use href="#i-copy"/></svg><span id="lanUrl">局域网地址加载中…</span></button>
    <button class="btn btn-sm btn-ghost btn-icon" id="btnRefresh" title="刷新状态"><svg class="ico"><use href="#i-refresh"/></svg></button>
    <button class="btn btn-sm btn-ghost btn-icon" id="btnTheme" title="切换深色 / 浅色主题"><svg class="ico"><use href="#i-sun"/></svg></button>
  </div>
 </div>
</header>

<div class="page">

  <!-- ============ 构建台 ============ -->
  <section class="tab-panel active" data-tab="build">
    <div class="status-strip">
      <div class="strip-seg" id="appStrip"><span class="strip-lbl"><svg class="ico"><use href="#i-git"/></svg>App</span><span class="hint">加载中…</span></div>
      <div class="strip-div"></div>
      <div class="strip-seg" id="engineStrip"><span class="strip-lbl"><svg class="ico"><use href="#i-git"/></svg>引擎</span><span class="hint">加载中…</span></div>
      <div class="strip-div"></div>
      <div class="strip-seg rt-seg" id="rtStrip"><span class="strip-lbl"><svg class="ico"><use href="#i-package"/></svg>Runtime</span><span class="hint">加载中…</span></div>
    </div>

    <div class="console-grid">
      <div class="card config-card">
        <div class="card-hd"><h2 class="card-title"><svg class="ico"><use href="#i-zap"/></svg>一键预设</h2><span class="card-sub">点击即填</span></div>
        <div class="presets">
          <button class="preset" data-preset="fast" data-name="真机快编装机"><div class="p-top"><span class="p-ico"><svg class="ico"><use href="#i-phone"/></svg></span><b>真机快编</b></div><small>arm64 · 装机</small></button>
          <button class="preset" data-preset="hot" data-name="热更脚本包"><div class="p-top"><span class="p-ico"><svg class="ico"><use href="#i-zap"/></svg></span><b>热更脚本</b></div><small>仅导 runtime</small></button>
          <button class="preset" data-preset="full" data-name="全架构正式包"><div class="p-top"><span class="p-ico"><svg class="ico"><use href="#i-package"/></svg></span><b>全架构包</b></div><small>arm64+x86_64</small></button>
        </div>
        <div class="divider"></div>
        <div class="card-hd"><h2 class="card-title"><svg class="ico"><use href="#i-terminal"/></svg>构建配置</h2></div>
        <div class="field"><label>构建类型</label><select id="variant"><option value="release">Release 正式签名（可无损覆盖手机）</option><option value="debug">Debug 调试包</option></select></div>
        <div class="field"><label>目标架构（ABI）</label><select id="abi"><option value="arm64">仅 arm64-v8a（真机推荐，快约 2 倍）</option><option value="all">全架构 arm64-v8a + x86_64（含模拟器）</option></select></div>
        <label class="chk"><input type="checkbox" id="exportRuntime" /> 构建前先从引擎仓重导 oas-runtime.zip</label>
        <label class="chk"><input type="checkbox" id="runTests" /> 运行单元测试 (:app:testDebugUnitTest)</label>
        <label class="chk"><input type="checkbox" id="copyDist" checked /> 归档到 dist/ 并生成 .sha256</label>
        <label class="chk"><input type="checkbox" id="installDevice" /> 编完自动 adb install -r 到选中设备</label>
        <div class="divider"></div>
        <div class="target-line"><span>当前目标设备</span><b id="targetDevice">未选择</b></div>
        <div class="btns">
          <button class="btn btn-primary" id="btnStart" style="flex:1"><svg class="ico"><use href="#i-play"/></svg>开始构建</button>
          <button class="btn btn-ghost" id="btnExportOnly"><svg class="ico"><use href="#i-zap"/></svg>仅导脚本包</button>
          <button class="btn btn-danger" id="btnCancel" style="display:none"><svg class="ico"><use href="#i-stop"/></svg>停止</button>
        </div>
      </div>

      <div class="card log-card">
        <div class="card-hd" style="margin-bottom:12px"><h2 class="card-title"><svg class="ico"><use href="#i-terminal"/></svg>实时进度与日志</h2><span class="card-sub" id="buildMeta">空闲</span></div>
        <div class="hist-banner" id="histBanner"><span id="histBannerText">正在查看历史日志</span><button class="btn btn-sm btn-ghost" id="btnBackLive2"><svg class="ico"><use href="#i-refresh"/></svg>切回实时日志</button></div>
        <div class="progress-shell"><div class="progress-wrap"><div class="progress-bar" id="pbar"></div></div><span class="mono card-sub" id="pctText">0%</span></div>
        <div class="steps" id="steps"><span class="step-hint">暂无步骤</span></div>
        <div class="log-shell">
          <div class="log-tools">
            <input type="text" class="input" id="logSearch" placeholder="搜索日志…" />
            <label class="chk"><input type="checkbox" id="onlyErrors" />仅看错误</label>
            <label class="chk"><input type="checkbox" id="autoScroll" checked />自动滚底</label>
            <div class="log-sep"></div>
            <button class="btn btn-sm btn-ghost" id="btnCopyLog"><svg class="ico"><use href="#i-copy"/></svg>复制日志</button>
            <button class="btn btn-sm btn-ghost" id="btnBackLive" style="display:none;color:#fbbf24;border-color:rgba(245,158,11,.4)"><svg class="ico"><use href="#i-refresh"/></svg>返回实时日志</button>
            <span class="log-count" id="logCount">0/0 行</span>
          </div>
          <pre class="log" id="logBox"><span class="log-empty">等待日志输出…</span></pre>
        </div>
      </div>
    </div>
  </section>

  <!-- ============ 产物与历史 ============ -->
  <section class="tab-panel" data-tab="artifacts">
    <div class="stack">
      <div class="card">
        <div class="card-hd"><h2 class="card-title"><svg class="ico"><use href="#i-package"/></svg>APK 产物</h2><span class="card-sub">局域网下载 / 一键装到当前设备 · <b id="artBadge">0</b> 个</span></div>
        <div class="table-scroll"><table class="data-table">
          <thead><tr><th>文件</th><th>大小</th><th>时间</th><th>SHA-256</th><th>操作</th></tr></thead>
          <tbody id="artBody"><tr><td colspan="5" class="empty-row">加载中…</td></tr></tbody>
        </table></div>
      </div>
      <div class="card">
        <div class="card-hd"><h2 class="card-title"><svg class="ico"><use href="#i-clock"/></svg>最近构建历史</h2><span class="card-sub">点击「查看日志」切到构建台锁定历史日志</span></div>
        <div class="table-scroll"><table class="data-table">
          <thead><tr><th>时间</th><th>状态</th><th>耗时</th><th>步骤摘要</th><th>操作</th></tr></thead>
          <tbody id="histBody"><tr><td colspan="5" class="empty-row">加载中…</td></tr></tbody>
        </table></div>
      </div>
    </div>
  </section>

  <!-- ============ 手机日志 ============ -->
  <section class="tab-panel" data-tab="phone">
    <div class="card">
      <div class="card-hd">
        <h2 class="card-title"><svg class="ico"><use href="#i-file-text"/></svg>手机报错日志</h2>
        <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
          <label class="chk" style="margin:0"><input type="checkbox" id="autoSyncLogs" checked />自动同步(10s)</label>
          <button class="btn btn-sm btn-primary" id="btnSyncNow"><svg class="ico"><use href="#i-refresh"/></svg>立即同步</button>
          <button class="btn btn-sm btn-ghost" id="btnLogcat"><svg class="ico"><use href="#i-terminal"/></svg>抓实时 logcat</button>
          <span class="sync-status" id="phoneSyncStatus">尚未同步</span>
        </div>
      </div>
      <div class="phone-grid">
        <div>
          <input type="text" id="phoneSearch" placeholder="搜索报错标题 / 摘要…" style="width:100%;margin-bottom:9px" />
          <div class="hint" style="margin-bottom:7px"><span id="phoneListCount">0/0</span> 份日志 · 点击查看详情</div>
          <div class="phone-list" id="phoneList"><div class="empty-row">加载中…</div></div>
        </div>
        <div style="min-width:0">
          <div id="phoneLogMeta" class="hint" style="margin-bottom:4px">点击左侧日志查看详情与现场截图</div>
          <div id="phoneLogImgs" class="img-grid"></div>
          <div style="display:flex;justify-content:flex-end;gap:8px;margin:6px 0">
            <a class="btn btn-sm btn-ghost" id="btnDownloadPhone" href="#" style="display:none"><svg class="ico"><use href="#i-download"/></svg>下载原文件</a>
            <button class="btn btn-sm btn-ghost" id="btnCopyPhone" disabled><svg class="ico"><use href="#i-copy"/></svg>复制报错文本</button>
          </div>
          <pre class="log phone-log-pre" id="phoneLogText"><span class="log-empty">暂未选择手机日志…</span></pre>
        </div>
      </div>
    </div>
  </section>
</div>

<div class="toast-wrap" id="toastWrap"></div>

<div class="mask" id="modalMask">
  <div class="modal" role="dialog" aria-modal="true">
    <h3 id="mTitle">确认操作</h3>
    <p id="mBody"></p>
    <div class="modal-foot"><button class="btn btn-sm btn-ghost" id="mCancel">取消</button><button class="btn btn-sm btn-primary" id="mOk">确认</button></div>
  </div>
</div>

<div class="lightbox" id="lightbox"><button class="lb-close" id="lbClose" title="关闭（Esc）"><svg class="ico"><use href="#i-x"/></svg></button><img id="lightboxImg" alt="截图预览" /></div>

<script>
'use strict';
var $ = function(id){ return document.getElementById(id); };
var LS_KEY = 'oasa.console.v1';
var prefs = (function(){ try{ return JSON.parse(localStorage.getItem(LS_KEY) || '{}') || {}; }catch(e){ return {}; } })();
var CHECKS = ['exportRuntime','runTests','copyDist','installDevice'];
var STATUS = {idle:'空闲',running:'构建中',success:'构建成功',failed:'构建失败',cancelled:'已取消'};

var state = {
  logMode:'live', historyId:'', logLines:[],
  phoneLogs:[], phoneFilter:'', currentPhone:null, currentImages:[],
  meta:null
};
var logOffset = 0, lastBuildId = '', pollBusy = false, confirmCb = null;

function icon(n, cls){ return '<svg class="ico '+(cls||'')+'"><use href="#i-'+n+'"/></svg>'; }
function esc(s){ return String(s==null?'':s).replace(/[&<>"']/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
function fmtLine(line){
  var e = esc(line);
  if (/FATAL|ERROR|FAILURE|Exception|FAILED/i.test(line)) return '<span class="ln-err">'+e+'</span>';
  if (/BUILD SUCCESSFUL|Success|已归档/i.test(line)) return '<span class="ln-ok">'+e+'</span>';
  if (/^\[.*?\]\s*> Task /.test(line)) return '<span class="ln-task">'+e+'</span>';
  if (/==========/.test(line)) return '<span class="ln-sec">'+e+'</span>';
  return e;
}
function fmtDur(s){ s = Number(s)||0; if(s < 60) return s.toFixed(1)+'s'; return Math.floor(s/60)+'m'+Math.round(s%60)+'s'; }

/* ---------- Toast 通知队列 ---------- */
function toast(type, msg, ms){
  var el = document.createElement('div');
  el.className = 'toast '+(type||'info');
  var ic = type==='success' ? 'check' : type==='error' ? 'alert' : 'info';
  el.innerHTML = icon(ic,'t-ico')+'<div class="t-body">'+esc(msg)+'</div><button class="t-close" title="关闭">'+icon('x')+'</button>';
  el.querySelector('.t-close').onclick = function(){ el.remove(); };
  $('toastWrap').appendChild(el);
  while($('toastWrap').children.length > 5) $('toastWrap').firstChild.remove();
  setTimeout(function(){ el.classList.add('out'); setTimeout(function(){ el.remove(); }, 230); }, ms||4000);
}

/* ---------- 自定义确认弹窗 ---------- */
function showConfirm(opt){
  $('mTitle').textContent = opt.title || '确认操作';
  $('mBody').textContent = opt.message || '';
  $('mOk').className = 'btn btn-sm '+(opt.danger ? 'btn-danger' : 'btn-primary');
  $('mOk').innerHTML = icon(opt.danger?'alert':'check')+'<span>'+esc(opt.okText||'确认')+'</span>';
  confirmCb = opt.onOk || null;
  $('modalMask').classList.add('show');
}
function closeConfirm(){ $('modalMask').classList.remove('show'); confirmCb = null; }

/* ---------- 复制（兼容非 HTTPS 局域网） ---------- */
function copyText(text, label){
  if(!text){ toast('error','没有可复制的内容'); return Promise.resolve(false); }
  return new Promise(function(resolve){
    var ok = false;
    function done(){
      toast(ok?'success':'error', ok ? ((label||'内容')+'已复制到剪贴板') : '复制失败，请手动选择文本');
      resolve(ok);
    }
    if(navigator.clipboard && window.isSecureContext){
      navigator.clipboard.writeText(text).then(function(){ ok=true; done(); }, function(){ fallback(); });
    } else fallback();
    function fallback(){
      try{
        var ta = document.createElement('textarea');
        ta.value = text; ta.setAttribute('readonly','');
        ta.style.cssText = 'position:fixed;top:-1000px;left:-1000px;opacity:0';
        document.body.appendChild(ta); ta.select();
        ok = document.execCommand('copy'); ta.remove();
      }catch(e){ ok = false; }
      done();
    }
  });
}

/* ---------- 按钮 Loading ---------- */
function setLoading(btn, on, label){
  if(!btn) return;
  if(on){
    if(btn.dataset.html === undefined) btn.dataset.html = btn.innerHTML;
    btn.disabled = true; btn.classList.add('loading');
    btn.innerHTML = btn.classList.contains('btn-icon')
      ? icon('spinner','spin')
      : icon('spinner','spin')+'<span>'+esc(label||'处理中…')+'</span>';
  }else{
    btn.disabled = false; btn.classList.remove('loading');
    if(btn.dataset.html !== undefined){ btn.innerHTML = btn.dataset.html; delete btn.dataset.html; }
  }
}
function setDisabled(id, d){ var el = $(id); if(el && !el.classList.contains('loading')) el.disabled = !!d; }

/* ---------- 偏好持久化 ---------- */
function savePrefs(){
  try{
    prefs.variant = $('variant').value;
    prefs.abi = $('abi').value;
    CHECKS.forEach(function(k){ prefs[k] = $(k).checked; });
    prefs.adbAddr = $('adbAddr').value.trim();
    prefs.serial = $('serial').value;
    prefs.autoSync = $('autoSyncLogs').checked;
    localStorage.setItem(LS_KEY, JSON.stringify(prefs));
  }catch(e){}
  refreshPresetHighlight();
}
function applyPrefs(){
  if(prefs.variant) $('variant').value = prefs.variant;
  if(prefs.abi) $('abi').value = prefs.abi;
  CHECKS.forEach(function(k){ if(typeof prefs[k] === 'boolean') $(k).checked = prefs[k]; });
  if(prefs.adbAddr) $('adbAddr').value = prefs.adbAddr;
  if(typeof prefs.autoSync === 'boolean') $('autoSyncLogs').checked = prefs.autoSync;
}

/* ---------- Tab ---------- */
function switchTab(name){
  document.querySelectorAll('.tab-panel').forEach(function(p){ p.classList.toggle('active', p.dataset.tab===name); });
  document.querySelectorAll('.tab-btn').forEach(function(b){ b.classList.toggle('active', b.dataset.tab===name); });
  prefs.tab = name;
  try{ localStorage.setItem(LS_KEY, JSON.stringify(prefs)); }catch(e){}
  window.scrollTo({top:0,behavior:'smooth'});
}

/* ---------- 主题切换 ---------- */
function applyTheme(t){
  var mode = t === 'light' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', mode);
  var btn = $('btnTheme');
  if(btn){
    btn.innerHTML = icon(mode === 'light' ? 'moon' : 'sun');
    btn.title = mode === 'light' ? '切换到暗色主题' : '切换到浅色主题';
  }
}
function toggleTheme(){
  var next = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
  prefs.theme = next;
  applyTheme(next);
  try{ localStorage.setItem(LS_KEY, JSON.stringify(prefs)); }catch(e){}
}

/* ---------- 元信息 ---------- */
function stripRepoHtml(label, x){
  if(!x || !x.exists) return '<span class="strip-lbl">'+icon('git')+esc(label)+'</span><span class="hint">未找到</span>';
  var dirtyCls = x.dirty > 0 ? 'strip-dirty has-dirty' : 'strip-dirty';
  return '<span class="strip-lbl">'+icon('git')+esc(label)+'</span>'
    + '<span class="strip-branch" title="分支">'+esc(x.branch)+'</span>'
    + '<span class="'+dirtyCls+'">改动 '+x.dirty+'</span>'
    + '<span class="strip-commit mono" title="'+esc(x.commit)+'">'+esc(x.commit)+'</span>';
}
function renderOverview(d){
  $('appStrip').innerHTML = stripRepoHtml('App', d.app);
  $('engineStrip').innerHTML = stripRepoHtml('引擎', d.engine);
  var rt = d.runtime || {};
  if(rt.exists){
    var hotUrl = (d.lanUrl||'')+'/update/update.json';
    var sha = rt.archiveSha256||'';
    $('rtStrip').innerHTML = '<span class="strip-lbl">'+icon('package')+'Runtime</span>'
      + '<span class="strip-kv num">'+rt.taskCount+' 任务 · '+rt.zipSizeMb+' MB</span>'
      + (sha ? '<span class="sha-chip mono" data-copy="'+esc(sha)+'" data-copy-label="Runtime SHA-256" title="点击复制完整 SHA-256">'+esc(sha.slice(0,10))+'…</span>' : '')
      + '<span class="strip-actions"><button class="btn btn-sm btn-ghost" data-copy="'+esc(hotUrl)+'" data-copy-label="局域网热更地址" title="'+esc(hotUrl)+'">'+icon('copy')+'热更地址</button></span>';
  }else{
    $('rtStrip').innerHTML = '<span class="strip-lbl">'+icon('package')+'Runtime</span><span class="hint">未找到 manifest.json</span>';
  }
}
function renderArtifacts(arts){
  $('artBadge').textContent = arts.length;
  $('artBody').innerHTML = arts.map(function(a){
    var sha = a.sha256||'';
    return '<tr>'
      + '<td><div class="file-name">'+icon('package')+esc(a.name)+'</div><div class="file-rel" title="'+esc(a.rel)+'">'+esc(a.rel)+'</div></td>'
      + '<td class="num">'+a.sizeMb+' MB</td>'
      + '<td class="num" style="color:var(--muted)">'+esc(a.mtime)+'</td>'
      + '<td>'+(sha ? '<span class="sha-chip mono" data-copy="'+esc(sha)+'" data-copy-label="SHA-256" title="点击复制完整 SHA-256">'+esc(sha.slice(0,12))+'…</span>' : '<span class="hint">—</span>')+'</td>'
      + '<td><div class="ops"><a class="btn btn-sm btn-ghost" href="/download?path='+encodeURIComponent(a.rel)+'">'+icon('download')+'下载</a>'
      + '<button class="btn btn-sm btn-primary" data-act="install" data-rel="'+esc(a.rel)+'">'+icon('install')+'装机</button></div></td></tr>';
  }).join('') || '<tr><td colspan="5" class="empty-row">暂无产物，构建并归档后会出现在这里</td></tr>';
}
var lastHistorySig = null;
function renderHistory(hist){
  var sig = hist.map(function(h){ return (h.buildId||'')+':'+(h.status||'')+':'+(h.elapsedSec||''); }).join('|');
  if(sig === lastHistorySig) return;
  lastHistorySig = sig;
  $('histBody').innerHTML = hist.map(function(h){
    return '<tr><td class="num">'+esc(h.time)+'</td>'
      + '<td><span class="badge b-'+esc(h.status)+'">'+esc(STATUS[h.status]||h.status)+'</span></td>'
      + '<td class="num">'+fmtDur(h.elapsedSec)+'</td>'
      + '<td class="hint" style="max-width:340px">'+esc(h.summary)+'</td>'
      + '<td><button class="btn btn-sm btn-ghost" data-act="viewlog" data-id="'+esc(h.buildId)+'">'+icon('file-text')+'查看日志</button></td></tr>';
  }).join('') || '<tr><td colspan="5" class="empty-row">暂无构建记录</td></tr>';
}
function renderPhoneList(){
  var q = state.phoneFilter.trim().toLowerCase();
  var list = state.phoneLogs.filter(function(l){
    return !q || ((l.title||'')+' '+(l.name||'')+' '+(l.summary||'')).toLowerCase().indexOf(q) >= 0;
  });
  $('phoneListCount').textContent = list.length+'/'+state.phoneLogs.length;
  $('phoneList').innerHTML = list.map(function(l){
    var active = state.currentPhone && state.currentPhone.name===l.name;
    return '<button class="log-item'+(active?' active':'')+'" data-name="'+esc(l.name)+'">'
      + '<span class="li-title">'+esc(l.title||l.name)+'</span>'
      + (l.summary?'<span class="li-sum">'+esc(l.summary)+'</span>':'')
      + '<span class="li-foot"><span>'+esc(l.mtime)+'</span><span>'+l.sizeKb+' KB</span></span></button>';
  }).join('') || '<div class="empty-row">暂无日志，点击「立即同步」拉取</div>';
}
function setSyncStatus(text, ok){
  $('phoneSyncStatus').textContent = text;
  $('phoneSyncStatus').className = 'sync-status '+(ok===undefined?'':ok?'ok':'err');
}

async function refreshMeta(btn){
  setLoading(btn, true, '刷新中');
  try{
    var r = await fetch('/api/meta');
    var d = await r.json();
    state.meta = d;
    $('lanUrl').textContent = d.lanUrl || '';
    $('lanBtn').title = '点击复制：'+(d.lanUrl||'');
    renderOverview(d);

    var sel = $('serial');
    var prev = sel.value || prefs.serial || '';
    sel.innerHTML = d.devices.length
      ? d.devices.map(function(x){ return '<option value="'+esc(x.serial)+'">'+esc(x.model)+' · '+esc(x.serial)+' ['+esc(x.state)+']</option>'; }).join('')
      : '<option value="">（未检测到设备）</option>';
    if(prev && d.devices.some(function(x){ return x.serial===prev; })) sel.value = prev;
    else if(d.devices.length) sel.value = d.devices[0].serial;
    else sel.value = '';
    $('targetDevice').textContent = sel.value || '未选择';
    $('devCount').className = 'dev-chip '+(d.devices.length?'ok':'');
    $('devCount').innerHTML = icon('phone')+(d.devices.length ? d.devices.length+' 台在线' : '未连接');

    renderArtifacts(d.artifacts||[]);
    renderHistory((state.meta && state.meta.history) || lastHistory);
    state.phoneLogs = d.phoneLogs||[];
    $('badgeArtifacts').textContent = (d.artifacts||[]).length;
    $('badgePhone').textContent = state.phoneLogs.length;
    renderPhoneList();
    savePrefs();
  }catch(e){ toast('error','刷新状态失败：'+e.message); }
  finally{ setLoading(btn, false); }
}

/* ---------- 构建轮询（历史日志模式绝不被覆盖） ---------- */
var lastHistory = [], wasRunning = false;
async function pollBuild(){
  if(pollBusy) return;
  pollBusy = true;
  try{
    var r = await fetch('/api/build?offset='+logOffset);
    var b = await r.json();
    if(wasRunning && !b.running) refreshMeta();
    wasRunning = !!b.running;
    lastHistory = b.history||[];

    var pill = $('statusPill');
    pill.className = 'pill '+(b.status||'idle');
    $('statusText').textContent = STATUS[b.status]||b.status;
    var pbar = $('pbar');
    pbar.style.width = (b.progress||0)+'%';
    pbar.className = 'progress-bar '+(b.status==='success'?'ok':b.status==='failed'?'err':b.status==='cancelled'?'warn':'');
    $('pctText').textContent = (b.progress||0)+'%';
    $('buildMeta').textContent = b.status==='idle' ? '空闲' : (STATUS[b.status]+' · 耗时 '+fmtDur(b.elapsedSec));
    $('steps').innerHTML = (b.steps||[]).length
      ? b.steps.map(function(s){ var mk = s.state==='done'?'✓ ':s.state==='running'?'⟳ ':s.state==='failed'?'✗ ':'· '; return '<span class="step '+s.state+'">'+mk+esc(s.title)+'</span>'; }).join('')
      : '<span class="step-hint">暂无步骤</span>';
    setDisabled('btnStart', b.running);
    setDisabled('btnExportOnly', b.running);
    $('btnCancel').style.display = b.running ? '' : 'none';
    renderHistory(lastHistory);

    // 历史日志查看模式：只同步偏移量，绝不写入日志框
    if(state.logMode === 'history'){ lastBuildId = b.buildId; logOffset = b.logTotal; return; }

    if(b.buildId !== lastBuildId){
      lastBuildId = b.buildId; logOffset = 0; state.logLines = [];
      if(b.logTotal > 0){
        var r2 = await fetch('/api/build?offset=0');
        var b2 = await r2.json();
        state.logLines = b2.logs||[]; logOffset = b2.logTotal;
      }
      renderLog();
    }else if(b.logs.length){
      state.logLines = state.logLines.concat(b.logs);
      logOffset = b.logTotal;
      renderLog();
    }
  }catch(e){}
  finally{ pollBusy = false; }
}
function renderLog(){
  var q = $('logSearch').value.trim().toLowerCase();
  var onlyErr = $('onlyErrors').checked;
  var lines = state.logLines.filter(function(l){ return !q || l.toLowerCase().indexOf(q) >= 0; });
  if(onlyErr) lines = lines.filter(function(l){ return /FATAL|ERROR|FAILURE|Exception|FAILED|错误|失败/i.test(l); });
  var box = $('logBox');
  box.innerHTML = lines.length ? lines.map(fmtLine).join('\n') : '<span class="log-empty">'+(state.logLines.length?'没有匹配的日志行':'等待日志输出…')+'</span>';
  if($('autoScroll').checked) box.scrollTop = box.scrollHeight;
  $('logCount').textContent = lines.length+'/'+state.logLines.length+' 行';
}
function backToLive(silent){
  state.logMode = 'live'; state.historyId = '';
  state.logLines = []; logOffset = 0; lastBuildId = '';
  $('histBanner').classList.remove('show');
  $('btnBackLive').style.display = 'none';
  renderLog();
  if(!silent){ toast('info','已切回实时日志'); pollBuild(); }
}
async function viewLog(buildId, btn){
  setLoading(btn, true, '读取中');
  try{
    var r = await fetch('/api/history/log?id='+encodeURIComponent(buildId));
    var text = await r.text();
    state.logMode = 'history'; state.historyId = buildId;
    state.logLines = text.split('\n');
    $('histBannerText').textContent = '正在查看历史日志 #'+buildId;
    $('histBanner').classList.add('show');
    $('btnBackLive').style.display = '';
    switchTab('build');
    renderLog();
    $('logBox').scrollTop = 0;
    toast('info','已锁定历史日志 #'+buildId+'，实时轮询不会覆盖');
  }catch(e){ toast('error','读取历史日志失败：'+e.message); }
  finally{ setLoading(btn, false); }
}

/* ---------- 操作 ---------- */
async function adbConnect(btn){
  var addr = $('adbAddr').value.trim();
  if(!addr){ toast('error','请输入无线 ADB 地址，如 192.168.3.8:44929'); return; }
  prefs.adbAddr = addr; savePrefs();
  setLoading(btn, true, '连接中');
  try{
    var r = await fetch('/api/adb/connect',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({addr:addr})});
    var d = await r.json();
    var out = (d.output||'').trim();
    toast(d.ok?'success':'error', out || (d.ok?'已连接':'连接失败'));
    await refreshMeta();
  }catch(e){ toast('error','连接请求失败：'+e.message); }
  finally{ setLoading(btn, false); }
}
async function installApk(rel, btn){
  var serial = $('serial').value;
  if(!serial){ toast('error','请先在顶部选择一台 ADB 设备'); return; }
  showConfirm({
    title:'安装 APK 到手机',
    message:'将 '+rel+' 安装到设备 '+serial+'，会覆盖手机上的同版本应用（配置数据保留）。确认继续？',
    okText:'确认安装', danger:true,
    onOk: async function(){
      setLoading(btn, true, '安装中');
      try{
        var r = await fetch('/api/adb/install',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({rel:rel,serial:serial})});
        var d = await r.json();
        toast(d.ok?'success':'error', d.ok?('安装成功 → '+serial):('安装失败：'+((d.output||'').trim()||'未知错误')), d.ok?4200:7000);
      }catch(e){ toast('error','安装请求失败：'+e.message); }
      finally{ setLoading(btn, false); }
    }
  });
}
async function startBuild(runtimeOnly, btn){
  var body = {
    runtimeOnly: runtimeOnly,
    exportRuntime: runtimeOnly ? true : $('exportRuntime').checked,
    variant: $('variant').value,
    abi: $('abi').value,
    runTests: $('runTests').checked,
    copyDist: $('copyDist').checked,
    installDevice: $('installDevice').checked,
    serial: $('serial').value
  };
  savePrefs();
  if(!runtimeOnly && body.installDevice && !body.serial){ toast('error','已勾选自动安装，但尚未选择 ADB 设备'); return; }
  setLoading(btn, true, runtimeOnly?'导出中…':'启动中…');
  try{
    var r = await fetch('/api/build/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    var d = await r.json();
    if(d.ok){ toast('success','构建已启动 #'+d.buildId); backToLive(true); }
    else toast('error', d.error||'启动失败');
  }catch(e){ toast('error','启动请求失败：'+e.message); }
  finally{ setLoading(btn, false); }
}
async function cancelBuild(btn){
  showConfirm({
    title:'停止当前构建',
    message:'将终止正在运行的构建进程，已产生的日志会保留。确认停止？',
    okText:'停止构建', danger:true,
    onOk: async function(){
      setLoading(btn, true, '停止中');
      try{
        var r = await fetch('/api/build/cancel',{method:'POST'});
        var d = await r.json();
        toast(d.ok?'info':'error', d.ok?'已发送停止请求':'当前没有运行中的构建');
      }catch(e){ toast('error','停止请求失败：'+e.message); }
      finally{ setLoading(btn, false); }
    }
  });
}
async function syncPhoneLogs(silent, btn){
  var serial = $('serial').value;
  if(!serial){ if(!silent) toast('error','请先选择一台 ADB 设备'); return; }
  setLoading(btn, true, '同步中');
  try{
    var r = await fetch('/api/phone-logs/sync',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({serial:serial})});
    var d = await r.json();
    setSyncStatus((d.message||(d.ok?'同步完成':'同步失败'))+' · '+new Date().toLocaleTimeString('zh-CN',{hour12:false}), d.ok);
    if(!silent || d.newCount>0) toast(d.ok?'success':'error', d.message||'同步完成');
    await refreshMeta();
    if(d.latest && (!silent || d.newCount>0)) await openPhoneLog(d.latest);
  }catch(e){ setSyncStatus('同步失败：'+e.message, false); if(!silent) toast('error','同步失败：'+e.message); }
  finally{ setLoading(btn, false); }
}
async function grabLogcat(btn){
  var serial = $('serial').value;
  if(!serial){ toast('error','请先选择一台 ADB 设备'); return; }
  setLoading(btn, true, '抓取中');
  try{
    var r = await fetch('/api/phone-logs/logcat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({serial:serial})});
    var d = await r.json();
    setSyncStatus(d.message||(d.ok?'抓取完成':'抓取失败'), d.ok);
    toast(d.ok?'success':'error', d.message||'抓取完成');
    await refreshMeta();
    if(d.name) await openPhoneLog(d.name);
  }catch(e){ toast('error','抓取失败：'+e.message); }
  finally{ setLoading(btn, false); }
}
async function openPhoneLog(name){
  try{
    var r = await fetch('/api/phone-logs/view?name='+encodeURIComponent(name));
    if(!r.ok){ toast('error','日志不存在'); return; }
    var d = await r.json();
    state.currentPhone = d; state.currentImages = d.images||[];
    document.querySelectorAll('.log-item').forEach(function(el){ el.classList.toggle('active', el.dataset.name===name); });
    $('phoneLogMeta').innerHTML = icon('file-text')+' <b class="phone-title">'+esc(d.name)+'</b>'
      + (d.meta.length ? '<div class="meta-tags">'+d.meta.map(function(m){ return '<span class="tag">'+esc(m)+'</span>'; }).join('')+'</div>' : '');
    $('phoneLogImgs').innerHTML = state.currentImages.length
      ? state.currentImages.map(function(src,i){ return '<button class="thumb" data-idx="'+i+'" title="点击放大"><img src="'+esc(src)+'" alt="现场截图" />'+icon('image')+'</button>'; }).join('')
      : '<div class="thumb-empty">该日志暂无现场截图</div>';
    $('phoneLogText').innerHTML = d.text.split('\n').map(fmtLine).join('\n');
    $('btnCopyPhone').disabled = false;
    var dl = $('btnDownloadPhone');
    dl.href = '/api/phone-logs/raw?name='+encodeURIComponent(d.name);
    dl.style.display = '';
  }catch(e){ toast('error','读取日志失败：'+e.message); }
}
function openLightbox(idx){
  var src = state.currentImages[idx]; if(!src) return;
  $('lightboxImg').src = src;
  $('lightbox').classList.add('show');
}
function closeLightbox(){ $('lightbox').classList.remove('show'); $('lightboxImg').src=''; }

/* ---------- 预设 ---------- */
var PRESETS = {
  fast:{variant:'release',abi:'arm64',exportRuntime:false,runTests:false,copyDist:true,installDevice:true},
  hot:{variant:'release',abi:'arm64',exportRuntime:true,runTests:false,copyDist:false,installDevice:false},
  full:{variant:'release',abi:'all',exportRuntime:false,runTests:false,copyDist:true,installDevice:false}
};
function refreshPresetHighlight(){
  document.querySelectorAll('.preset').forEach(function(b){
    var p = PRESETS[b.dataset.preset];
    var match = p && $('variant').value===p.variant && $('abi').value===p.abi
      && CHECKS.every(function(k){ return $(k).checked===p[k]; });
    b.classList.toggle('active', !!match);
  });
}
function applyPreset(key){
  var p = PRESETS[key]; if(!p) return;
  $('variant').value = p.variant; $('abi').value = p.abi;
  CHECKS.forEach(function(k){ $(k).checked = p[k]; });
  savePrefs();
  if(key==='hot'){
    var eb = $('btnExportOnly'); eb.classList.add('hl'); setTimeout(function(){ eb.classList.remove('hl'); }, 3200);
    toast('info','已配置为「热更脚本包」，点击高亮的「仅导出脚本包」即可');
  }else{
    var name = document.querySelector('.preset[data-preset="'+key+'"]').dataset.name;
    toast('success','已应用预设：'+name);
  }
}

/* ---------- 事件绑定 ---------- */
function bindEvents(){
  document.querySelectorAll('.tab-btn').forEach(function(b){ b.onclick = function(){ switchTab(b.dataset.tab); }; });
  document.querySelectorAll('.preset').forEach(function(b){ b.onclick = function(){ applyPreset(b.dataset.preset); }; });
  $('btnRefresh').onclick = function(){ refreshMeta($('btnRefresh')); };
  $('btnTheme').onclick = toggleTheme;
  $('lanBtn').onclick = function(){ copyText((state.meta&&state.meta.lanUrl)||'', '局域网地址'); };
  $('btnAdbConnect').onclick = function(){ adbConnect($('btnAdbConnect')); };
  $('adbAddr').onkeydown = function(e){ if(e.key==='Enter'){ e.preventDefault(); adbConnect($('btnAdbConnect')); } };
  $('adbAddr').oninput = function(){ prefs.adbAddr = $('adbAddr').value.trim(); savePrefs(); };
  $('serial').onchange = function(){ prefs.serial = $('serial').value; $('targetDevice').textContent = $('serial').value||'未选择'; savePrefs(); };
  $('variant').onchange = savePrefs; $('abi').onchange = savePrefs;
  CHECKS.forEach(function(k){ $(k).onchange = savePrefs; });

  $('btnStart').onclick = function(){ startBuild(false, $('btnStart')); };
  $('btnExportOnly').onclick = function(){ startBuild(true, $('btnExportOnly')); };
  $('btnCancel').onclick = function(){ cancelBuild($('btnCancel')); };
  $('logSearch').oninput = renderLog;
  $('onlyErrors').onchange = renderLog;
  $('autoScroll').onchange = function(){ if($('autoScroll').checked){ var b=$('logBox'); b.scrollTop=b.scrollHeight; } };
  $('btnCopyLog').onclick = function(){ copyText(state.logLines.join('\n'), '全部日志'); };
  $('btnBackLive').onclick = function(){ backToLive(false); };
  $('btnBackLive2').onclick = function(){ backToLive(false); };

  $('btnSyncNow').onclick = function(){ syncPhoneLogs(false, $('btnSyncNow')); };
  $('btnLogcat').onclick = function(){ grabLogcat($('btnLogcat')); };
  $('autoSyncLogs').onchange = savePrefs;
  $('phoneSearch').oninput = function(){ state.phoneFilter = $('phoneSearch').value; renderPhoneList(); };
  $('btnCopyPhone').onclick = function(){ copyText(state.currentPhone?state.currentPhone.text:'', '报错文本'); };

  $('mCancel').onclick = closeConfirm;
  $('mOk').onclick = async function(){ var cb = confirmCb; closeConfirm(); if(cb) await cb(); };
  $('modalMask').onclick = function(e){ if(e.target===$('modalMask')) closeConfirm(); };
  $('lbClose').onclick = closeLightbox;
  $('lightbox').onclick = function(e){ if(e.target===$('lightbox')) closeLightbox(); };
  document.addEventListener('keydown', function(e){
    if(e.key==='Escape'){
      if($('lightbox').classList.contains('show')) closeLightbox();
      else if($('modalMask').classList.contains('show')) closeConfirm();
    }
  });

  document.addEventListener('click', function(e){
    var cp = e.target.closest('[data-copy]');
    if(cp){ copyText(cp.getAttribute('data-copy'), cp.dataset.copyLabel||'内容'); return; }
    var ins = e.target.closest('[data-act="install"]');
    if(ins){ installApk(ins.dataset.rel, ins); return; }
    var vh = e.target.closest('[data-act="viewlog"]');
    if(vh){ viewLog(vh.dataset.id, vh); return; }
    var li = e.target.closest('.log-item');
    if(li){ openPhoneLog(li.dataset.name); return; }
    var th = e.target.closest('.thumb');
    if(th){ openLightbox(Number(th.dataset.idx)); return; }
  });
}

applyTheme(prefs.theme||'dark');
applyPrefs();
bindEvents();
refreshPresetHighlight();
switchTab(prefs.tab||'build');
refreshMeta();
pollBuild();
setInterval(pollBuild, 1000);
setInterval(function(){ refreshMeta(); }, 12000);
setInterval(function(){
  var cb = $('autoSyncLogs');
  if(cb && cb.checked && $('serial').value) syncPhoneLogs(true);
}, 10000);
</script>
</body>
</html>
"""


class ConsoleHandler(BaseHTTPRequestHandler):
    server_port: int = 8766

    def log_message(self, fmt: str, *args) -> None:
        return

    def _send_json(self, obj: dict, status: int = 200) -> None:
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length > 0 else b"{}"
        return json.loads(raw.decode("utf-8"))

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)

        if parsed.path in ("/", "/index.html"):
            raw = HTML_PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return

        if parsed.path == "/api/meta":
            ip = detect_lan_ip()
            self._send_json({
                "lanUrl": f"http://{ip}:{self.server_port}",
                "app": git_info(ROOT),
                "engine": git_info(Path(DEFAULT_ENGINE_DIR)),
                "runtime": runtime_info(),
                "devices": list_adb_devices(),
                "artifacts": list_artifacts(),
                "phoneLogs": list_phone_logs(),
            })
            return

        if parsed.path == "/api/phone-logs/view":
            name = Path((qs.get("name") or [""])[0]).name
            target = (PHONE_LOG_ROOT / name).resolve()
            if not name or target.parent != PHONE_LOG_ROOT.resolve() or not target.is_file():
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            self._send_json(parse_phone_log(target))
            return

        if parsed.path == "/api/phone-logs/raw":
            name = Path((qs.get("name") or [""])[0]).name
            target = (PHONE_LOG_ROOT / name).resolve()
            if not name or target.parent != PHONE_LOG_ROOT.resolve() or not target.is_file():
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            raw = target.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Disposition", f'attachment; filename="{target.name}"')
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return

        if parsed.path == "/api/build":
            offset = int((qs.get("offset") or ["0"])[0] or "0")
            self._send_json(MANAGER.snapshot(log_offset=offset))
            return

        if parsed.path == "/api/history/log":
            bid = (qs.get("id") or [""])[0]
            if not re.fullmatch(r"[0-9\-]+", bid):
                self.send_error(HTTPStatus.BAD_REQUEST)
                return
            log_file = LOG_ROOT / f"{bid}.log"
            raw = log_file.read_bytes() if log_file.is_file() else b"Log not found"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return

        if parsed.path == "/update/update.json":
            zip_path = ROOT / "app" / "src" / "main" / "assets" / "runtime" / "oas-runtime.zip"
            if not zip_path.is_file():
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            host_hdr = self.headers.get("Host") or f"{detect_lan_ip()}:{self.server_port}"
            base = f"http://{host_hdr}/update"
            h = hashlib.sha256()
            with zip_path.open("rb") as f:
                for chunk in iter(lambda: f.read(1024 * 1024), b""):
                    h.update(chunk)
            gradle_txt = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
            m_ver = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_txt)
            m_code = re.search(r'versionCode\s*=\s*(\d+)', gradle_txt)
            manifest_path = ROOT / "app" / "src" / "main" / "assets" / "runtime" / "manifest.json"
            rt_ver = ""
            if manifest_path.is_file():
                try:
                    rt_ver = str(json.loads(manifest_path.read_text(encoding="utf-8")).get("runtimeVersion", "") or "").strip()
                except Exception:
                    rt_ver = ""
            if not rt_ver:
                rt_ver = m_ver.group(1) if m_ver else datetime.fromtimestamp(zip_path.stat().st_mtime).strftime("%Y.%m.%d.%H%M")
            self._send_json({
                "runtimeVersion": rt_ver,
                "runtimeArchiveUrl": f"{base}/oas-runtime.zip",
                "runtimeSha256": h.hexdigest(),
                "runtimeManifestUrl": f"{base}/manifest.json",
                "runtimeNotes": "局域网本地实时热更新包",
                "appVersion": m_ver.group(1) if m_ver else "1.0.4",
                "appVersionCode": int(m_code.group(1)) if m_code else 14,
                "appDownloadUrl": f"http://{host_hdr}/",
                "appNotes": "",
            })
            return

        if parsed.path in ("/update/oas-runtime.zip", "/update/manifest.json"):
            fname = parsed.path.rsplit("/", 1)[-1]
            target = ROOT / "app" / "src" / "main" / "assets" / "runtime" / fname
            if not target.is_file():
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            st = target.stat()
            ctype = "application/zip" if fname.endswith(".zip") else "application/json; charset=utf-8"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(st.st_size))
            self.end_headers()
            with target.open("rb") as f:
                shutil.copyfileobj(f, self.wfile, length=256 * 1024)
            return

        if parsed.path == "/download":
            rel = (qs.get("path") or [""])[0]
            target = (ROOT / rel).resolve()
            allowed_roots = [(ROOT / "dist").resolve(), (ROOT / "app" / "build" / "outputs" / "apk").resolve()]
            if not target.is_file() or not any(r in target.parents for r in allowed_roots):
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            st = target.stat()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", f'attachment; filename="{target.name}"')
            self.send_header("Content-Length", str(st.st_size))
            self.end_headers()
            with target.open("rb") as f:
                shutil.copyfileobj(f, self.wfile, length=256 * 1024)
            return

        self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/build/start":
            opts = self._read_json()
            ok, msg = MANAGER.start(opts)
            self._send_json({"ok": ok, "buildId": msg if ok else "", "error": "" if ok else msg})
            return

        if parsed.path == "/api/build/cancel":
            ok = MANAGER.cancel()
            self._send_json({"ok": ok})
            return

        if parsed.path == "/api/adb/connect":
            body = self._read_json()
            addr = (body.get("addr") or "").strip()
            if not addr:
                self._send_json({"ok": False, "output": "地址为空"}, 400)
                return
            code, out = run_capture([adb_path(), "connect", addr], timeout=12)
            self._send_json({"ok": code == 0, "output": out})
            return

        if parsed.path == "/api/adb/install":
            body = self._read_json()
            rel = (body.get("rel") or "").strip()
            serial = (body.get("serial") or "").strip()
            target = (ROOT / rel).resolve()
            allowed_roots = [(ROOT / "dist").resolve(), (ROOT / "app" / "build" / "outputs" / "apk").resolve()]
            if not serial or not target.is_file() or not any(r in target.parents for r in allowed_roots):
                self._send_json({"ok": False, "output": "无效的设备或 APK 路径"}, 400)
                return
            code, out = run_capture([adb_path(), "-s", serial, "install", "-r", str(target)], timeout=180)
            self._send_json({"ok": code == 0, "output": out})
            return

        if parsed.path == "/api/phone-logs/sync":
            body = self._read_json()
            serial = (body.get("serial") or "").strip()
            if not serial:
                self._send_json({"ok": False, "message": "未选择 ADB 设备"}, 400)
                return
            shell_cmd = (
                "find /storage/emulated/0/Android/data/com.oas.android/files/error-logs "
                "/storage/emulated/0 -maxdepth 3 -iname 'OASA-error-*.md' 2>/dev/null"
            )
            code, out = run_capture([adb_path(), "-s", serial, "shell", shell_cmd], timeout=20)
            remote_files = []
            seen_names = set()
            for ln in out.splitlines():
                rf = ln.strip()
                if not rf.endswith(".md"):
                    continue
                fname = Path(rf).name
                if fname not in seen_names:
                    seen_names.add(fname)
                    remote_files.append((rf, fname))
            pulled_new = []
            for rf, fname in remote_files:
                dst = PHONE_LOG_ROOT / fname
                if dst.is_file() and dst.stat().st_size > 0:
                    continue
                c2, _ = run_capture([adb_path(), "-s", serial, "pull", rf, str(dst)], timeout=30)
                if c2 == 0 and dst.is_file():
                    pulled_new.append(fname)
            all_names = [fname for _, fname in remote_files]
            latest = sorted(pulled_new)[-1] if pulled_new else (sorted(all_names)[-1] if all_names else "")
            if pulled_new:
                msg = f"自动同步新增 {len(pulled_new)} 份报错日志（共 {len(all_names)} 份）"
            elif all_names:
                msg = f"已是最新（共 {len(all_names)} 份报错日志）"
            else:
                msg = "手机里暂无报错日志"
            self._send_json({"ok": True, "count": len(all_names), "newCount": len(pulled_new), "latest": latest, "message": msg})
            return

        if parsed.path == "/api/phone-logs/logcat":
            body = self._read_json()
            serial = (body.get("serial") or "").strip()
            if not serial:
                self._send_json({"ok": False, "message": "未选择 ADB 设备"}, 400)
                return
            code, out = run_capture(
                [adb_path(), "-s", serial, "logcat", "-d", "-s", "python.stdout", "python.stderr", "AndroidRuntime"],
                timeout=15,
            )
            fname = f"logcat-{datetime.now().strftime('%Y%m%d-%H%M%S')}.txt"
            (PHONE_LOG_ROOT / fname).write_text(out or "(logcat 为空)", encoding="utf-8")
            self._send_json({"ok": code == 0, "name": fname, "message": f"已抓取实时 logcat -> {fname}"})
            return

        self.send_error(HTTPStatus.NOT_FOUND)


def main() -> None:
    parser = argparse.ArgumentParser(description="OASA 本地 Web 打包控制台")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--open", action="store_true", help="启动后自动打开浏览器")
    args = parser.parse_args()

    ConsoleHandler.server_port = args.port
    server = ThreadingHTTPServer((args.host, args.port), ConsoleHandler)
    lan_ip = detect_lan_ip()
    print(f"OASA Web 构建控制台已启动:")
    print(f"  本机访问:   http://127.0.0.1:{args.port}")
    print(f"  局域网访问: http://{lan_ip}:{args.port}")
    print("按 Ctrl+C 停止服务。")
    if args.open:
        threading.Timer(0.6, lambda: webbrowser.open(f"http://127.0.0.1:{args.port}")).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")


if __name__ == "__main__":
    main()
