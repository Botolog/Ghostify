# Bundled static ffmpeg binaries

Place a **statically linked** ffmpeg executable in each ABI directory of the
real project (`app/src/main/jniLibs/<abi>/`):

```
app/src/main/jniLibs/
├── arm64-v8a/libffmpeg.so
├── armeabi-v7a/libffmpeg.so
└── x86_64/libffmpeg.so
```

## Rules (all are load-bearing)

1. **Name must be `libffmpeg.so`.** Files under `lib/<abi>/` in the APK that do
   not match `lib*.so` are dropped by the package manager at install time. The
   file is a static *executable*, not a shared library — the `.so` name is only
   so the system installs it.
2. **Must be statically linked.** The runtime `exec()`s it directly; it must not
   depend on shared libraries (no `libavcodec.so` etc. on device). Build with
   `--disable-shared --enable-static` and any needed codecs/libx264/lame
   statically.
3. **Must include `libmp3lame`** (or whatever codec spotdl is configured to
   emit). The boot test `ffmpeg_probe` encodes MP3; spotdl's MP3 output needs
   the same codec.
4. **Architecture must match `ndk.abiFilters`** in `build-config/app-build.gradle.kts`
   (arm64-v8a, armeabi-v7a, x86_64). A mismatched binary fails at exec time
   with an "Exec format error" — this is caught at the boot test, not silently.
5. **Don't strip it yourself** with a dynamic-target tool; `keepDebugSymbols`
   already stops AGP from touching it.

## Getting a binary

- Any upstream "static ffmpeg for Android" build that targets the three ABIs
  (there are several; pick one whose toolchain you trust), or
- build it yourself for each ABI, e.g.:

```sh
git clone https://github.com/FFmpeg/FFmpeg && cd FFmpeg
./configure \
  --target-os=android --arch=aarch64 --enable-cross-compile \
  --cc=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
  --disable-shared --enable-static --enable-pic --enable-libmp3lame \
  --extra-cflags=-I.../lame/include --extra-ldflags=-L.../lame/lib ...
make -j$(nproc)
# rename the produced `ffmpeg` executable to libffmpeg.so
```

(repeat with `armv7a` / `x86_64` toolchains for the other ABIs)

## Runtime behavior (see `FfmpegLocator.kt`)

At host boot the binary is copied once from `applicationInfo.nativeLibraryDir`
to `filesDir/ffmpeg/ffmpeg`, marked executable, and its directory is prepended
to `os.environ["PATH"]` in the interpreter — so spotdl's `subprocess` calls
resolve `ffmpeg` by name. The copy exists because the exec bit on
`nativeLibraryDir` files is not guaranteed across devices.
