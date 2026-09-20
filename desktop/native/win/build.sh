#!/usr/bin/env bash
# Cross-compile tb-app-audio.exe for Windows x64 from Linux/WSL with Zig's
# bundled clang + MinGW-w64. No Windows SDK or Visual Studio needed.
#   ZIG=/path/to/zig ./build.sh
# (On Windows with Visual Studio: cl /EHsc /O2 tb-app-audio.cpp ole32.lib mmdevapi.lib)
set -euo pipefail
cd "$(dirname "$0")"
ZIG="${ZIG:-zig}"
mkdir -p bin
"$ZIG" c++ -target x86_64-windows-gnu -O2 -std=c++17 -municode -static \
  -o bin/tb-app-audio.exe tb-app-audio.cpp \
  -lole32 -lmmdevapi -luuid
ls -la bin/tb-app-audio.exe
