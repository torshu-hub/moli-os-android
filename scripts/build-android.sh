#!/bin/sh
# Compile one JNI ABI without running a server or contacting any devices.
set -eu
cd "$(dirname "$0")/.."
rust_version=${MOLI_RUST_VERSION:-1.99.1}
ndk_version=${MOLI_NDK_VERSION:-28.2.13676358}
android_api=${MOLI_ANDROID_API:-28}
abi=${1:-arm64-v8a}
ndk=${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/$ndk_version}
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
case "$abi" in
  arm64-v8a)
    target=aarch64-linux-android
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/aarch64-linux-android${android_api}-clang"
    export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
    export AR_aarch64_linux_android="$bin/llvm-ar"
    ;;
  x86_64)
    target=x86_64-linux-android
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$bin/x86_64-linux-android${android_api}-clang"
    export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
    export AR_x86_64_linux_android="$bin/llvm-ar"
    ;;
  *) echo 'Supported ABIs: arm64-v8a, x86_64' >&2; exit 2 ;;
esac
test -s web/dist/index.html
test -x "$bin/llvm-ar"
cargo +"$rust_version" build --release --locked -p moli-android --target "$target"
mkdir -p "android/app/src/main/jniLibs/$abi"
cp "target/$target/release/libmoli_android.so" "android/app/src/main/jniLibs/$abi/"
