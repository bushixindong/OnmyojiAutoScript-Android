"""Project-local Linux NDK extraction and verified pydantic-core source download."""
import hashlib
import json
import os
from pathlib import Path
import stat
import tarfile
import urllib.request
import zipfile
import argparse
import posixpath

parser=argparse.ArgumentParser()
parser.add_argument('--minimal',action='store_true')
options=parser.parse_args()

root = Path(__file__).resolve().parents[1]
downloads = root / ".downloads"
toolchains = root / ".toolchains"
ndk_zip = downloads / "android-ndk-r27d-linux.zip"
assert hashlib.sha1(ndk_zip.read_bytes()).hexdigest() == "22105e410cf29afcf163760cc95522b9fb981121", "NDK hash mismatch"
with zipfile.ZipFile(ndk_zip) as archive:
    selected = [entry for entry in archive.infolist() if "/toolchains/llvm/prebuilt/linux-x86_64/" in entry.filename]
    if options.minimal:
        selected=[entry for entry in selected if '/bin/' in entry.filename or '/sysroot/usr/lib/' in entry.filename and (
            '/aarch64-linux-android/' in entry.filename or '/x86_64-linux-android/' in entry.filename) and (
            '/24/' in entry.filename or entry.filename.count('/')<10)]
    for entry in selected:
        target = toolchains / entry.filename
        if not target.resolve().is_relative_to(toolchains.resolve()):
            raise ValueError("Archive path escapes toolchains")
        mode = entry.external_attr >> 16
        if entry.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        elif stat.S_ISLNK(mode):
            if not target.exists() and not target.is_symlink():
                target.parent.mkdir(parents=True, exist_ok=True)
                if os.name=='nt':
                    member=entry
                    for _ in range(16):
                        if not stat.S_ISLNK(member.external_attr>>16):break
                        member=archive.getinfo(posixpath.normpath(posixpath.join(posixpath.dirname(member.filename),archive.read(member).decode())))
                    target.write_bytes(archive.read(member))
                else:target.symlink_to(archive.read(entry).decode())
        elif not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
            target.chmod(mode or 0o644)
print("NDK toolchain extracted", flush=True)
metadata_file=downloads/'pydantic-core-metadata.json'
metadata=json.loads(metadata_file.read_text()) if metadata_file.exists() else json.load(urllib.request.urlopen("https://pypi.org/pypi/pydantic-core/2.27.0/json"))
source = next(item for item in metadata["urls"] if item["packagetype"] == "sdist")
source_file = downloads / source["filename"]
if not source_file.exists():
    urllib.request.urlretrieve(source["url"], source_file)
assert hashlib.sha256(source_file.read_bytes()).hexdigest() == source["digests"]["sha256"]
with tarfile.open(source_file) as archive:
    for item in archive.getmembers():
        if not (toolchains / item.name).resolve().is_relative_to(toolchains.resolve()):
            raise ValueError("Source archive escapes toolchains")
    archive.extractall(toolchains)
print("Verified pydantic-core source prepared", flush=True)
