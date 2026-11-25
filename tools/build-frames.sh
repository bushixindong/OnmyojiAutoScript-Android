#!/bin/sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_ndk_root="${ANDROID_NDK_HOME:-$task_root/.toolchains/android-ndk-r27d}"
task_ndk="$task_ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
for task_pair in 'arm64-v8a aarch64-linux-android28' 'x86_64 x86_64-linux-android28'; do
    set -- $task_pair
    task_output="$task_root/app/src/main/jniLibs/$1/liboas_frames.so"
    mkdir -p "$(dirname "$task_output")"
    "$task_ndk/clang++" --target="$2" -std=c++17 -O3 -fPIC -shared -static-libstdc++ \
        -Wl,-z,max-page-size=16384 -Wl,-soname,liboas_frames.so \
        "$task_root/app/src/main/native/raw_frames.cpp" "$task_root/app/src/main/native/hardware_capture.cpp" \
        -lmediandk -landroid -lEGL -lGLESv2 -o "$task_output"
    "$task_ndk/llvm-strip" "$task_output"
done
