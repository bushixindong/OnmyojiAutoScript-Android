#!/bin/sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export CARGO_HOME="$project_root/.toolchains/cargo"
export RUSTUP_HOME="$project_root/.toolchains/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
export PYTHONPATH="$project_root/.toolchains/host-python"
export PYTHONDONTWRITEBYTECODE=1
ndk_bin="$project_root/.toolchains/android-ndk-r27d/toolchains/llvm/prebuilt/linux-x86_64/bin"
source_dir="$project_root/.toolchains/pydantic_core-2.27.0"
build_dir="$project_root/.toolchains/pydantic-build"
mkdir -p "$build_dir" "$project_root/.toolchains/wheels"
for pair in 'arm64-v8a aarch64-linux-android' 'x86_64 x86_64-linux-android'; do
    set -- $pair
    abi=$1
    target=$2
    printf '#!/bin/sh\nexec "%s/clang" --target=%s24 "$@"\n' "$ndk_bin" "$target" > "$build_dir/link-$abi.sh"
    chmod +x "$build_dir/link-$abi.sh"
    printf 'implementation=CPython\nversion=3.10\nshared=true\nabi3=false\nlib_name=python3.10\nlib_dir=%s/app/build/python/jniLibs/debug/%s\npointer_width=64\nbuild_flags=\nsuppress_build_script_link_lines=false\n' "$project_root" "$abi" > "$build_dir/python-$abi.txt"
    export PYO3_CONFIG_FILE="$build_dir/python-$abi.txt"
    export PYO3_CROSS=1
    export CARGO_TARGET_DIR="$build_dir/target"
    export RUSTFLAGS="-C linker=$build_dir/link-$abi.sh -C link-arg=-Wl,-z,max-page-size=16384"
    (cd "$source_dir" && cargo build --release --target "$target" --features extension-module -j 4)
    "$ndk_bin/llvm-strip" "$build_dir/target/$target/release/lib_pydantic_core.so"
    python3 "$project_root/tools/package-pydantic-wheel.py" "$source_dir" "$build_dir/target/$target/release/lib_pydantic_core.so" "$abi"
done
