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
DEFAULT_ENGINE_DIR = r"E:\OnmyojiAutoScript"


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
        summary = ""
        if p.suffix.lower() == ".md":
            try:
                head = p.read_text(encoding="utf-8", errors="ignore")[:1200]
                m = re.search(r"\*\*异常摘要\*\*[：:]\s*(.+)", head)
                if m:
                    summary = m.group(1).strip()
            except OSError:
                pass
        items.append({
            "name": p.name,
            "sizeKb": round(st.st_size / 1024, 1),
            "mtime": datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M:%S"),
            "summary": summary,
            "isMd": p.suffix.lower() == ".md",
        })
    return items[:30]


def parse_phone_log(path: Path) -> dict:
    raw = path.read_text(encoding="utf-8", errors="replace")
    if path.suffix.lower() != ".md":
        return {"name": path.name, "meta": [], "images": [], "text": raw}
    images = []
    def repl(m):
        images.append(f"data:image/{m.group(1)};base64,{re.sub(r'\\s+', '', m.group(2))}")
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
  :root {
    --bg:#0f172a; --card:#1e293b; --border:#334155; --text:#e2e8f0; --muted:#94a3b8;
    --accent:#38bdf8; --ok:#22c55e; --err:#ef4444; --warn:#f59e0b;
  }
  * { box-sizing: border-box; }
  body { margin:0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; background:var(--bg); color:var(--text); }
  .wrap { max-width:1280px; margin:0 auto; padding:20px; }
  header { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:12px; margin-bottom:18px; }
  h1 { margin:0; font-size:20px; display:flex; align-items:center; gap:10px; }
  .pill { font-size:12px; padding:3px 10px; border-radius:999px; background:#0ea5e922; color:var(--accent); border:1px solid #0ea5e955; }
  .grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap:12px; margin-bottom:16px; }
  .card { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:14px 16px; }
  .card h2 { margin:0 0 10px; font-size:14px; color:var(--muted); font-weight:600; text-transform:uppercase; letter-spacing:.03em; }
  .kv { font-size:13px; line-height:1.7; word-break:break-all; }
  .kv b { color:#fff; }
  .main { display:grid; grid-template-columns: 360px 1fr; gap:16px; align-items:start; }
  @media (max-width: 960px) { .main { grid-template-columns: 1fr; } }
  .row { margin-bottom:12px; }
  label.lbl { display:block; font-size:12px; color:var(--muted); margin-bottom:6px; }
  select, input[type=text] { width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:#0b1220; color:var(--text); font-size:13px; }
  .chk { display:flex; align-items:center; gap:8px; font-size:13px; margin:7px 0; cursor:pointer; }
  .chk input { width:16px; height:16px; accent-color:var(--accent); }
  .btns { display:flex; gap:8px; flex-wrap:wrap; margin-top:14px; }
  button { cursor:pointer; border:none; border-radius:8px; padding:9px 14px; font-size:13px; font-weight:600; transition:.15s; }
  .btn-primary { background:#0284c7; color:#fff; }
  .btn-primary:hover { background:#0369a1; }
  .btn-primary:disabled { opacity:.45; cursor:not-allowed; }
  .btn-danger { background:#dc2626; color:#fff; }
  .btn-ghost { background:#334155; color:var(--text); }
  .btn-ghost:hover { background:#475569; }
  .btn-sm { padding:5px 10px; font-size:12px; }
  .progress-wrap { background:#0b1220; border-radius:999px; height:10px; overflow:hidden; margin:10px 0 12px; border:1px solid var(--border); }
  .progress-bar { height:100%; width:0%; background:linear-gradient(90deg, #0284c7, #38bdf8); transition:width .3s; }
  .progress-bar.ok { background:var(--ok); }
  .progress-bar.err { background:var(--err); }
  .steps { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:10px; }
  .step { font-size:12px; padding:4px 10px; border-radius:8px; background:#0b1220; border:1px solid var(--border); color:var(--muted); }
  .step.running { border-color:var(--accent); color:var(--accent); }
  .step.done { border-color:#16a34a; color:#4ade80; }
  .step.failed { border-color:var(--err); color:#f87171; }
  pre.log { margin:0; height:420px; overflow:auto; background:#020617; border:1px solid var(--border); border-radius:10px; padding:12px; font-family:Consolas, "Cascadia Mono", monospace; font-size:12px; line-height:1.5; white-space:pre-wrap; word-break:break-word; }
  .ln-err { color:#f87171; }
  .ln-ok { color:#4ade80; font-weight:600; }
  .ln-task { color:#38bdf8; }
  .ln-sec { color:#fbbf24; font-weight:600; }
  table { width:100%; border-collapse:collapse; font-size:12px; }
  th, td { text-align:left; padding:7px 8px; border-bottom:1px solid var(--border); }
  th { color:var(--muted); font-weight:600; }
  a { color:var(--accent); text-decoration:none; }
  a:hover { text-decoration:underline; }
  .badge { padding:2px 7px; border-radius:6px; font-size:11px; font-weight:600; }
  .b-success { background:#16a34a33; color:#4ade80; }
  .b-failed { background:#dc262633; color:#f87171; }
  .b-cancelled { background:#f59e0b33; color:#fbbf24; }
  .b-running { background:#0284c733; color:#38bdf8; }
  .inline { display:flex; gap:6px; align-items:center; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1>OASA 本地构建控制台 <span class="pill" id="lanUrl">加载中...</span></h1>
    <button class="btn-ghost btn-sm" onclick="refreshMeta()">刷新状态</button>
  </header>

  <div class="grid">
    <div class="card">
      <h2>App 仓库</h2>
      <div class="kv" id="appRepo">加载中...</div>
    </div>
    <div class="card">
      <h2>引擎仓库</h2>
      <div class="kv" id="engineRepo">加载中...</div>
    </div>
    <div class="card">
      <h2>内置 Runtime</h2>
      <div class="kv" id="rtInfo">加载中...</div>
    </div>
    <div class="card">
      <h2>ADB 设备</h2>
      <div class="kv" id="adbBox">加载中...</div>
      <div class="inline" style="margin-top:8px">
        <input type="text" id="adbAddr" placeholder="无线连接如 192.168.3.8:44929" />
        <button class="btn-ghost btn-sm" onclick="adbConnect()">连接</button>
      </div>
    </div>
  </div>

  <div class="main">
    <div class="card">
      <h2>构建配置</h2>
      <div class="row">
        <label class="lbl">构建类型</label>
        <select id="variant">
          <option value="release" selected>Release 正式签名（可无损覆盖手机）</option>
          <option value="debug">Debug 调试包</option>
        </select>
      </div>
      <div class="row">
        <label class="lbl">目标架构（ABI）</label>
        <select id="abi">
          <option value="arm64" selected>仅 arm64-v8a（真机推荐，快约 2 倍）</option>
          <option value="all">全架构 arm64-v8a + x86_64（含模拟器）</option>
        </select>
      </div>
      <div class="row">
        <label class="chk"><input type="checkbox" id="exportRuntime" /> 构建前先从引擎仓重导 oas-runtime.zip</label>
        <label class="chk"><input type="checkbox" id="runTests" /> 运行单元测试 (:app:testDebugUnitTest)</label>
        <label class="chk"><input type="checkbox" id="copyDist" checked /> 归档到 dist/ 并生成 .sha256</label>
        <label class="chk"><input type="checkbox" id="installDevice" /> 编完自动 adb install -r 到选中设备</label>
      </div>
      <div class="row">
        <label class="lbl">目标安装设备</label>
        <select id="serial"><option value="">（未检测到设备）</option></select>
      </div>
      <div class="btns">
        <button class="btn-primary" id="btnStart" onclick="startBuild(false)">开始构建</button>
        <button class="btn-ghost" id="btnExportOnly" onclick="startBuild(true)">仅导出脚本包</button>
        <button class="btn-danger" id="btnCancel" onclick="cancelBuild()" style="display:none">停止构建</button>
      </div>
    </div>

    <div class="card">
      <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
        <h2 style="margin:0">实时进度与日志</h2>
        <div style="font-size:12px;color:var(--muted)" id="buildMeta">空闲</div>
      </div>
      <div class="progress-wrap"><div class="progress-bar" id="pbar"></div></div>
      <div class="steps" id="steps"></div>
      <pre class="log" id="logBox">等待构建任务...</pre>
    </div>
  </div>

  <div class="grid" style="margin-top:16px; grid-template-columns: 1fr 1fr;">
    <div class="card">
      <h2>APK 产物（局域网可直接下载 / 一键装机）</h2>
      <table>
        <thead><tr><th>文件</th><th>大小</th><th>时间</th><th>操作</th></tr></thead>
        <tbody id="artBody"></tbody>
      </table>
    </div>
    <div class="card">
      <h2>最近构建历史</h2>
      <table>
        <thead><tr><th>时间</th><th>状态</th><th>耗时</th><th>内容</th></tr></thead>
        <tbody id="histBody"></tbody>
      </table>
    </div>
  </div>

  <div class="card" style="margin-top:16px">
    <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:10px">
      <h2 style="margin:0">手机报错日志（ADB 拉取与图文查看）</h2>
      <div class="inline">
        <button class="btn-primary btn-sm" onclick="syncPhoneLogs()">从手机同步报错 MD</button>
        <button class="btn-ghost btn-sm" onclick="grabLogcat()">抓实时 logcat</button>
      </div>
    </div>
    <div class="main" style="grid-template-columns: 380px 1fr;">
      <div style="max-height:460px;overflow:auto">
        <table>
          <thead><tr><th>日志文件</th><th>时间</th><th>操作</th></tr></thead>
          <tbody id="phoneLogBody"></tbody>
        </table>
      </div>
      <div>
        <div id="phoneLogMeta" style="font-size:12px;color:var(--muted);margin-bottom:8px">点击左侧日志查看详情与现场截图</div>
        <div id="phoneLogImgs" style="display:flex;gap:10px;flex-wrap:wrap;margin-bottom:10px"></div>
        <pre class="log" id="phoneLogText" style="height:360px">暂未选择手机日志...</pre>
      </div>
    </div>
  </div>
</div>

<script>
let logOffset = 0;
let lastBuildId = "";

function esc(s){ return String(s).replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])); }
function fmtLine(line){
  const e = esc(line);
  if (/FATAL|ERROR|FAILURE|Exception|FAILED/i.test(line)) return `<span class="ln-err">${e}</span>`;
  if (/BUILD SUCCESSFUL|Success|已归档/i.test(line)) return `<span class="ln-ok">${e}</span>`;
  if (/^\[.*?\]\s*> Task /.test(line)) return `<span class="ln-task">${e}</span>`;
  if (/==========/.test(line)) return `<span class="ln-sec">${e}</span>`;
  return e;
}

async function refreshMeta(){
  const r = await fetch('/api/meta');
  const d = await r.json();
  document.getElementById('lanUrl').textContent = `局域网访问: ${d.lanUrl}`;
  document.getElementById('appRepo').innerHTML = `分支: <b>${esc(d.app.branch)}</b> (改动 ${d.app.dirty})<br/>提交: ${esc(d.app.commit)}`;
  document.getElementById('engineRepo').innerHTML = d.engine.exists
    ? `分支: <b>${esc(d.engine.branch)}</b> (改动 ${d.engine.dirty})<br/>提交: ${esc(d.engine.commit)}`
    : `未找到: ${esc(d.engine.path)}`;
  document.getElementById('rtInfo').innerHTML = d.runtime.exists
    ? `任务: <b>${d.runtime.taskCount}</b> / 文件: <b>${d.runtime.fileCount}</b> / ${d.runtime.zipSizeMb} MB<br/>SHA: ${esc(d.runtime.archiveSha256.slice(0,16))}...<br/>局域网热更地址: <a href="/update/update.json" target="_blank">${esc(d.lanUrl)}/update/update.json</a>`
    : `未找到 manifest.json`;

  const sel = document.getElementById('serial');
  const prev = sel.value;
  sel.innerHTML = d.devices.length
    ? d.devices.map(x => `<option value="${esc(x.serial)}">${esc(x.model)} (${esc(x.serial)}) [${esc(x.state)}]</option>`).join('')
    : '<option value="">（未检测到设备）</option>';
  if (prev && d.devices.some(x => x.serial === prev)) sel.value = prev;
  document.getElementById('adbBox').innerHTML = d.devices.length
    ? d.devices.map(x => `<b>${esc(x.model)}</b> · ${esc(x.serial)} (${esc(x.state)})`).join('<br/>')
    : '未连接设备';

  document.getElementById('artBody').innerHTML = d.artifacts.map(a => `
    <tr>
      <td><b>${esc(a.name)}</b><div style="color:var(--muted);font-size:11px">${esc(a.rel)}</div></td>
      <td>${a.sizeMb} MB</td>
      <td>${esc(a.mtime)}</td>
      <td style="white-space:nowrap">
        <a href="/download?path=${encodeURIComponent(a.rel)}">下载</a>
        · <a href="javascript:void(0)" onclick="installApk('${esc(a.rel)}')">装到手机</a>
      </td>
    </tr>`).join('') || '<tr><td colspan="4" style="color:var(--muted)">暂无产物</td></tr>';

  document.getElementById('phoneLogBody').innerHTML = (d.phoneLogs || []).map(l => `
    <tr>
      <td><b>${esc(l.name)}</b>${l.summary ? `<div style="color:#f87171;font-size:11px">${esc(l.summary)}</div>` : ''}</td>
      <td style="white-space:nowrap">${esc(l.mtime.slice(5))}</td>
      <td style="white-space:nowrap">
        <a href="javascript:void(0)" onclick="openPhoneLog('${esc(l.name)}')">查看</a>
        · <a href="/api/phone-logs/raw?name=${encodeURIComponent(l.name)}">下载</a>
      </td>
    </tr>`).join('') || '<tr><td colspan="3" style="color:var(--muted)">暂无日志，请点击上方「从手机同步报错 MD」</td></tr>';
}

async function pollBuild(){
  try {
    const r = await fetch(`/api/build?offset=${logOffset}`);
    const b = await r.json();
    if (b.buildId !== lastBuildId) {
      lastBuildId = b.buildId;
      logOffset = 0;
      document.getElementById('logBox').innerHTML = '';
      if (b.logTotal > 0) {
        const r2 = await fetch('/api/build?offset=0');
        const b2 = await r2.json();
        appendLogs(b2.logs);
        logOffset = b2.logTotal;
      }
    } else if (b.logs.length > 0) {
      appendLogs(b.logs);
      logOffset = b.logTotal;
    }

    const pbar = document.getElementById('pbar');
    pbar.style.width = `${b.progress}%`;
    pbar.className = 'progress-bar' + (b.status === 'success' ? ' ok' : b.status === 'failed' ? ' err' : '');

    document.getElementById('steps').innerHTML = b.steps.map(s =>
      `<span class="step ${s.state}">${s.state==='done'?'✓ ':s.state==='running'?'⟳ ':s.state==='failed'?'✗ ':'· '}${esc(s.title)}</span>`
    ).join('');

    const statusMap = {idle:'空闲', running:'构建中...', success:'构建成功', failed:'构建失败', cancelled:'已取消'};
    document.getElementById('buildMeta').textContent = `${statusMap[b.status]||b.status} · ${b.progress}% · 耗时 ${b.elapsedSec}s`;

    document.getElementById('btnStart').disabled = b.running;
    document.getElementById('btnExportOnly').disabled = b.running;
    document.getElementById('btnCancel').style.display = b.running ? '' : 'none';

    document.getElementById('histBody').innerHTML = b.history.map(h => `
      <tr>
        <td>${esc(h.time)}</td>
        <td><span class="badge b-${h.status}">${esc(statusMap[h.status]||h.status)}</span></td>
        <td>${h.elapsedSec}s</td>
        <td><a href="javascript:void(0)" onclick="viewLog('${esc(h.buildId)}')">${esc(h.summary)}</a></td>
      </tr>`).join('') || '<tr><td colspan="4" style="color:var(--muted)">暂无记录</td></tr>';
  } catch(e) {}
}

function appendLogs(lines){
  const box = document.getElementById('logBox');
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  box.innerHTML += lines.map(fmtLine).join('\n') + '\n';
  if (nearBottom) box.scrollTop = box.scrollHeight;
}

async function startBuild(runtimeOnly){
  const body = {
    runtimeOnly,
    exportRuntime: runtimeOnly ? true : document.getElementById('exportRuntime').checked,
    variant: document.getElementById('variant').value,
    abi: document.getElementById('abi').value,
    runTests: document.getElementById('runTests').checked,
    copyDist: document.getElementById('copyDist').checked,
    installDevice: document.getElementById('installDevice').checked,
    serial: document.getElementById('serial').value,
  };
  const r = await fetch('/api/build/start', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
  const d = await r.json();
  if (!d.ok) alert(d.error || '启动失败');
  pollBuild();
}

async function cancelBuild(){
  await fetch('/api/build/cancel', {method:'POST'});
  pollBuild();
}

async function adbConnect(){
  const addr = document.getElementById('adbAddr').value.trim();
  if (!addr) return;
  const r = await fetch('/api/adb/connect', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({addr})});
  const d = await r.json();
  alert(d.output || (d.ok ? '已连接' : '连接失败'));
  refreshMeta();
}

async function installApk(rel){
  const serial = document.getElementById('serial').value;
  if (!serial) { alert('请先连接并选择一台 ADB 设备'); return; }
  if (!confirm(`确认安装 ${rel} 到 ${serial}？`)) return;
  const r = await fetch('/api/adb/install', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({rel, serial})});
  const d = await r.json();
  alert(d.output || (d.ok ? '安装成功' : '安装失败'));
}

async function viewLog(buildId){
  const r = await fetch(`/api/history/log?id=${encodeURIComponent(buildId)}`);
  const text = await r.text();
  const box = document.getElementById('logBox');
  box.innerHTML = text.split('\n').map(fmtLine).join('\n');
  document.getElementById('buildMeta').textContent = `历史日志: ${buildId}`;
}

async function syncPhoneLogs(){
  const serial = document.getElementById('serial').value;
  if (!serial) { alert('请先连接并选择一台 ADB 设备'); return; }
  document.getElementById('phoneLogMeta').textContent = '正在从手机同步报错日志...';
  const r = await fetch('/api/phone-logs/sync', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({serial})});
  const d = await r.json();
  document.getElementById('phoneLogMeta').textContent = d.message || (d.ok ? '同步完成' : '同步失败');
  await refreshMeta();
  if (d.latest) openPhoneLog(d.latest);
}

async function grabLogcat(){
  const serial = document.getElementById('serial').value;
  if (!serial) { alert('请先连接并选择一台 ADB 设备'); return; }
  document.getElementById('phoneLogMeta').textContent = '正在抓取手机实时 logcat...';
  const r = await fetch('/api/phone-logs/logcat', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({serial})});
  const d = await r.json();
  document.getElementById('phoneLogMeta').textContent = d.message || (d.ok ? '抓取完成' : '抓取失败');
  await refreshMeta();
  if (d.name) openPhoneLog(d.name);
}

async function openPhoneLog(name){
  const r = await fetch(`/api/phone-logs/view?name=${encodeURIComponent(name)}`);
  if (!r.ok) return;
  const d = await r.json();
  document.getElementById('phoneLogMeta').innerHTML = `<b>${esc(d.name)}</b>` + (d.meta.length ? '<br/>' + d.meta.map(esc).join(' · ') : '');
  document.getElementById('phoneLogImgs').innerHTML = d.images.map(src =>
    `<a href="${src}" target="_blank"><img src="${src}" style="max-height:200px;border-radius:8px;border:1px solid var(--border)" /></a>`
  ).join('');
  document.getElementById('phoneLogText').innerHTML = d.text.split('\n').map(fmtLine).join('\n');
}

refreshMeta();
pollBuild();
setInterval(pollBuild, 1000);
setInterval(refreshMeta, 12000);
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
            mtime = datetime.fromtimestamp(zip_path.stat().st_mtime)
            self._send_json({
                "runtimeVersion": mtime.strftime("%Y.%m.%d.%H%M"),
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
            code, out = run_capture(
                [adb_path(), "-s", serial, "shell", "find /storage/emulated/0 -maxdepth 4 -iname 'OASA-error-*.md' 2>/dev/null"],
                timeout=20,
            )
            remote_files = [ln.strip() for ln in out.splitlines() if ln.strip().endswith(".md")]
            pulled = []
            for rf in remote_files:
                fname = Path(rf).name
                dst = PHONE_LOG_ROOT / fname
                c2, _ = run_capture([adb_path(), "-s", serial, "pull", rf, str(dst)], timeout=30)
                if c2 == 0 and dst.is_file():
                    pulled.append(fname)
            latest = sorted(pulled)[-1] if pulled else ""
            msg = f"已同步 {len(pulled)} 份报错日志到电脑" if pulled else "手机里未找到已导出的 OASA-error-*.md（请先在 App 报错日志页点导出 MD）"
            self._send_json({"ok": True, "count": len(pulled), "latest": latest, "message": msg})
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
