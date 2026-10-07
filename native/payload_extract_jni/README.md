# payload_extract_jni

Independent JNI extraction bridge for local Android OTA ZIP / `payload.bin` inputs.

The Rust module consumes the Apache-2.0 `payload_dumper` crate and schedules independent supported manifest operations concurrently within one selected partition. It validates destination extent bounds and non-overlap before concurrent writes. Differential or unsupported operations return an error so the Kotlin caller can fall back to the existing sequential root CLI path. Progress is based on bytes actually decoded and written (plus sparse zero/discard extents), not on a preallocated output file's reported size.

Build the ARM64 library from the repository root:

```bash
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.1.10909125"
export PATH="$HOME/.cargo/bin:$PATH"
CC_aarch64_linux_android="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang" \
AR_aarch64_linux_android="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" \
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang" \
cargo build --manifest-path native/payload_extract_jni/Cargo.toml --target aarch64-linux-android --release
```

The generated `.so` is packaged by Gradle under `app/src/main/jniLibs/arm64-v8a/`. See `THIRD_PARTY_NOTICES.md` for upstream attribution. The implementation does not include Dsu-Manager's JNI binary or source.
