#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$repo_dir"
python3 scripts/build-apm.py --target macos
mkdir -p native/build/tests
java_dir=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
library="$repo_dir/native/build/tests/libminemic-apm-test.dylib"
xcrun clang++ -std=c++17 -O2 -dynamiclib -I native/include \
    -I "$java_dir/include" -I "$java_dir/include/darwin" \
    native/src/apm_jni.cpp native/build/macos/libminemic_apm.a \
    -framework Foundation -o "$library"
MINEMIC_APM_TEST_LIBRARY="$library" ./gradlew :shared:jvmTest \
    --tests 'com.aiaixyz.minemic.audio.NativeEchoPipelineTest' --rerun-tasks
