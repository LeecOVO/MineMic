#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
python3 scripts/build-apm.py --target macos
mkdir -p native/build/tests
xcrun clang++ -std=c++17 -O2 -I native/include tests/native-audio/main.cpp native/build/macos/libminemic_apm.a -framework Foundation -o native/build/tests/apm-tests
native/build/tests/apm-tests
if [ -n "${MINEMIC_SPEECH_PCM:-}" ]; then
    xcrun clang++ -std=c++17 -O2 -I native/include tests/native-audio/speech-probe.cpp native/build/macos/libminemic_apm.a -framework Foundation -o native/build/tests/speech-probe
    native/build/tests/speech-probe "$MINEMIC_SPEECH_PCM"
fi
