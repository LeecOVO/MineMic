#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/minemic-audio.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
xcrun swiftc -O "$repo_dir/iosApp/iosApp/IOSAudioCapture.swift" \
    "$repo_dir/iosApp/iosApp/CaptureIntent.swift" \
    "$repo_dir/iosApp/iosApp/LiveActivitySessionPolicy.swift" "$repo_dir/tests/ios-audio/main.swift" -o "$test_dir/audio-tests"
"$test_dir/audio-tests"
