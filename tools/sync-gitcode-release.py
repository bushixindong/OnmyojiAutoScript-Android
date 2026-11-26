#!/usr/bin/env python3
"""Mirror OASA release and hot-update assets to GitCode's fixed `latest` release."""
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def api_request(token: str, url: str, method: str = "GET", payload: dict | None = None) -> dict:
    headers = {"PRIVATE-TOKEN": token}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
        return json.loads(raw) if raw.strip() else {}


def ensure_release(token: str, owner: str, repo: str, tag: str) -> None:
    base = f"https://api.gitcode.com/api/v5/repos/{owner}/{repo}/releases"
    try:
        api_request(token, f"{base}/{urllib.parse.quote(tag, safe='')}")
        return
    except urllib.error.HTTPError as err:
        if err.code != 404:
            raise
    api_request(
        token,
        base,
        method="POST",
        payload={
            "tag_name": tag,
            "name": "OASA Latest Mirror",
            "body": "OASA 国内镜像与热更新源（由 GitHub Actions 自动同步）",
            "target_commitish": "main",
        },
    )
    print(f"[gitcode] created release tag '{tag}'")


def upload_asset(token: str, owner: str, repo: str, tag: str, local_path: Path, remote_name: str) -> None:
    query = urllib.parse.urlencode({"file_name": remote_name})
    info = api_request(
        token,
        f"https://api.gitcode.com/api/v5/repos/{owner}/{repo}/releases/{urllib.parse.quote(tag, safe='')}/upload_url?{query}",
    )
    put_url = info["url"]
    headers = dict(info.get("headers") or {})
    size = local_path.stat().st_size
    headers["Content-Length"] = str(size)
    print(f"[gitcode] uploading {remote_name} ({size} bytes)...")
    with local_path.open("rb") as f:
        req = urllib.request.Request(put_url, data=f, headers=headers, method="PUT")
        with urllib.request.urlopen(req, timeout=600) as resp:
            body = resp.read().decode("utf-8", errors="replace").strip()
            if not (200 <= resp.status < 300):
                raise RuntimeError(f"Upload failed for {remote_name}: HTTP {resp.status} {body[:200]}")
    print(f"[gitcode] uploaded {remote_name} (HTTP {resp.status})")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", default=os.environ.get("GITCODE_OWNER", "kongdr418"))
    parser.add_argument("--repo", default=os.environ.get("GITCODE_REPO", "oasa-releases"))
    parser.add_argument("--tag", default="latest")
    parser.add_argument("--update-json", required=True)
    parser.add_argument("--runtime-zip", required=True)
    parser.add_argument("--manifest-json", required=True)
    parser.add_argument("--app-download-url", default="https://kongdr.bbs0.cc/")
    parser.add_argument("--apk", default="")
    parser.add_argument("--apk-sha", default="")
    args = parser.parse_args()

    token = os.environ.get("GITCODE_TOKEN", "").strip()
    if not token:
        print("[gitcode] GITCODE_TOKEN not set; skipping GitCode mirror sync.")
        return 0

    ensure_release(token, args.owner, args.repo, args.tag)

    download_base = f"https://gitcode.com/{args.owner}/{args.repo}/releases/download/{args.tag}"
    runtime_zip = Path(args.runtime_zip)
    manifest_json = Path(args.manifest_json)
    update_json = Path(args.update_json)

    # Upload binary/manifest payloads first so update.json never points to missing files.
    upload_asset(token, args.owner, args.repo, args.tag, runtime_zip, "oas-runtime.zip")
    upload_asset(token, args.owner, args.repo, args.tag, manifest_json, "manifest.json")

    apk_download_url = args.app_download_url.strip() or f"https://gitcode.com/{args.owner}/{args.repo}/releases/tag/{args.tag}"
    if args.apk:
        apk_path = Path(args.apk)
        if apk_path.is_file():
            upload_asset(token, args.owner, args.repo, args.tag, apk_path, apk_path.name)
            apk_download_url = f"{download_base}/{apk_path.name}"
    if args.apk_sha:
        sha_path = Path(args.apk_sha)
        if sha_path.is_file():
            upload_asset(token, args.owner, args.repo, args.tag, sha_path, sha_path.name)

    payload = json.loads(update_json.read_text(encoding="utf-8"))
    payload["runtimeArchiveUrl"] = f"{download_base}/oas-runtime.zip"
    payload["runtimeManifestUrl"] = f"{download_base}/manifest.json"
    payload["appDownloadUrl"] = apk_download_url

    with tempfile.TemporaryDirectory() as tmpdir:
        mirrored = Path(tmpdir) / "update.json"
        mirrored.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        upload_asset(token, args.owner, args.repo, args.tag, mirrored, "update.json")

    print(f"[gitcode] mirror sync completed -> {download_base}/update.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
