#!/usr/bin/env bash
# 重新构建 Media3 的 FFmpeg 解码扩展，产出 arm64-v8a + armeabi-v7a 的 libffmpegJNI.so。
#
# 背景：仓库里 `app/src/main/jniLibs/` 只有 armeabi-v7a 一份，64 位设备（只接受 arm64 的盒子）
# 上 System.loadLibrary("ffmpegJNI") 会失败 —— Media3 不会崩，但 FLAC/APE/WavPack 等
# 平台解码器覆盖不到的格式会直接播不了。应用启动日志里的
# `[DEC] ... ffmpeg=missing abi=arm64-v8a` 就是这种情况。
#
# 前置（本机没有，需要在一台装好 Android SDK 的机器上跑）：
#   - Android NDK r23+，NDK_PATH 指向它
#   - git / make / curl
#   - ffmpeg 源码（脚本自己拉）
#
# 用法：
#   NDK_PATH=~/Android/Sdk/ndk/25.2.9519653 ./tools/build-ffmpeg.sh
#
# 产物会直接写进 app/app/src/main/jniLibs/<abi>/libffmpegJNI.so。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEDIA3_TAG="${MEDIA3_TAG:-1.4.1}"
FFMPEG_TAG="${FFMPEG_TAG:-n6.0}"
NDK_PATH="${NDK_PATH:?请设置 NDK_PATH}"
WORK="${WORK:-$REPO_ROOT/.ffmpeg-build}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"

# 与现有 32 位产物保持同一集合：平台 MediaCodec 通常不提供的无损/开源编码都在这里。
DECODERS="${DECODERS:-aac ac3 alac ape dca eac3 flac mp3 opus vorbis wavpack pcm_mulaw pcm_alaw}"

HOST=""
case "$(uname -s)" in
  Darwin) HOST="darwin-x86_64" ;;
  Linux)  HOST="linux-x86_64" ;;
  *) echo "不支持在 $(uname -s) 上构建；请在 macOS/Linux 或 WSL 里跑" >&2; exit 1 ;;
esac

mkdir -p "$WORK"
cd "$WORK"

if [ ! -d media ]; then
  git clone --depth 1 --branch "$MEDIA3_TAG" https://github.com/androidx/media.git
fi
if [ ! -d ffmpeg ]; then
  git clone --depth 1 --branch "$FFMPEG_TAG" https://git.ffmpeg.org/ffmpeg.git
fi

# media3 的扩展构建脚本需要的目录结构： <root>/ffmpeg + <root>/media/...
# build_ffmpeg.sh <ffmpegModulePath> <ndkPath> <hostPlatform> <androidAbi> <decoders>
FFMPEG_MODULE="$WORK/media/libraries/decoder_ffmpeg/src/main"
JNI_DIR="$FFMPEG_MODULE/jni"

for ABI in $ABIS; do
  echo "=== 构建 $ABI ==="
  pushd "$JNI_DIR" >/dev/null
    # 幂等：重复跑时先清掉上一次的中间产物
    rm -f "$WORK/ffmpeg/ffmpeg-${ABI}" 2>/dev/null || true
    ./build_ffmpeg.sh "$WORK/ffmpeg" "$NDK_PATH" "$HOST" "$ABI" "$DECODERS"
    ./build.sh "$WORK/ffmpeg" "$NDK_PATH" "$HOST" "$ABI"
  popd >/dev/null

  SRC="$FFMPEG_MODULE/libs/$ABI/libffmpegJNI.so"
  DST="$REPO_ROOT/app/app/src/main/jniLibs/$ABI"
  mkdir -p "$DST"
  cp "$SRC" "$DST/libffmpegJNI.so"
  echo "已写入 $DST/libffmpegJNI.so ($(du -h "$DST/libffmpegJNI.so" | cut -f1))"
done

# Java 侧类不需要重建：app/libs/media3-ffmpeg.jar 只依赖 ABI 无关的 JNI 符号名。
echo
echo "完成。验证方式："
echo "  ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo "  启动后看 http://<设备>:8081/ 的 [DEC] 行，应变成 ffmpeg=ok abi=arm64-v8a"
