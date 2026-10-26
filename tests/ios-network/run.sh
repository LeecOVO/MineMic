#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/minemic-network.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
xcrun swiftc -O "$repo_dir/iosApp/iosApp/IOSNetworkAddress.swift" \
    "$repo_dir/tests/ios-network/main.swift" -o "$test_dir/network-tests"
"$test_dir/network-tests"
