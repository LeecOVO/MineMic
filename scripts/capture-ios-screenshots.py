#!/usr/bin/env python3
"""Capture the real iOS app on an installed 13-inch iPad simulator runtime."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import struct
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
SIZES = {"ipad13": {(2048, 2732), (2064, 2752)}, "iphone": {(1179, 2556), (1206, 2622)}}
SCENES = ("01-home-dark", "02-processing-dark", "03-home-light", "04-processing-light", "05-theme-selection")


def run(*args, timeout=120, check=True):
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{args[0]} failed ({result.returncode}): {result.stderr.strip()}")
    return result.stdout.strip()


def simctl(*args, **kwargs):
    return run("xcrun", "simctl", *args, **kwargs)


def run_logged(args, log_path, timeout):
    """Keep the complete diagnostic log while streaming test progress to Actions."""
    with log_path.open("w") as log, log_path.open() as progress:
        process = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT)
        started = time.monotonic()
        try:
            while process.poll() is None:
                chunk = progress.read()
                if chunk:
                    print(chunk, end="", flush=True)
                if time.monotonic() - started > timeout:
                    raise subprocess.TimeoutExpired(args, timeout)
                time.sleep(5)
            print(progress.read(), end="", flush=True)
            return process.returncode
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def version(value):
    return tuple(int(part) for part in re.findall(r"\d+", value))


def matches_device(name, device_class):
    if device_class == "ipad13":
        return "iPad Pro 13-inch" in name
    return re.fullmatch(r"iPhone \d+( Pro)?", name) is not None


def select_device(device_class):
    inventory = json.loads(simctl("list", "--json"))
    runtimes = sorted(
        (runtime for runtime in inventory["runtimes"]
         if runtime.get("isAvailable") and runtime["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")
         and version(runtime["version"]) >= (26,)),
        key=lambda runtime: version(runtime["version"]), reverse=True,
    )
    for runtime in runtimes:
        devices = [device for device in inventory["devices"].get(runtime["identifier"], [])
                   if device.get("isAvailable") and matches_device(device["name"], device_class)]
        if devices:
            device = max(devices, key=lambda device: (version(device["name"]), "Pro" in device["name"]))
            return device["udid"], device["name"], runtime, False
    # Create only the requested screen class; never substitute an 11-inch iPad or an iPhone Max.
    for runtime in runtimes:
        device_types = [device_type for device_type in inventory["devicetypes"]
                        if matches_device(device_type["name"], device_class)]
        for device_type in sorted(device_types, key=lambda item: version(item["name"]), reverse=True):
            name = "MineMic Screenshots " + device_type["name"]
            try:
                udid = simctl("create", name, device_type["identifier"], runtime["identifier"])
                return udid, device_type["name"], runtime, True
            except RuntimeError:
                continue
    raise RuntimeError(f"No available iOS 26+ runtime for {device_class}. Install one on the runner.")


def export_images(result_bundle, export_path, output, device_class, scenes):
    from PIL import Image, ImageStat

    run("xcrun", "xcresulttool", "export", "attachments", "--path", str(result_bundle), "--output-path", str(export_path))
    attachments = json.loads((export_path / "manifest.json").read_text())
    images = {}
    for test in attachments:
        for attachment in test["attachments"]:
            name = attachment["suggestedHumanReadableName"]
            scene = next((scene for scene in scenes if name.startswith(scene)), None)
            if scene is None:
                continue
            source = export_path / attachment["exportedFileName"]
            raw = source.read_bytes()
            if raw[:8] != b"\x89PNG\r\n\x1a\n":
                raise RuntimeError(f"Expected a PNG attachment for {scene}")
            size = struct.unpack(">II", raw[16:24])
            if size not in SIZES[device_class]:
                raise RuntimeError(f"Unexpected native resolution {size}; refusing to resize the screenshot")
            path = output / f"MineMic-{device_class}-{scene}.png"
            with Image.open(source) as image:
                image = image.convert("RGB")
                if max(ImageStat.Stat(image).stddev) < 5:
                    raise RuntimeError(f"Blank screenshot: {path.name}")
                # Remove alpha without moving, cropping, padding, or resizing any pixel.
                image.save(path)
            images[scene] = {"file": path.name, "scene": scene, "width": size[0], "height": size[1],
                             "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    if set(images) != set(scenes):
        raise RuntimeError(f"Missing screenshot scenes: {set(scenes) - set(images)}")
    return [images[scene] for scene in scenes]


def capture(app, output, derived_data, device_classes, scenes):
    app = app.resolve()
    with (app / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    if "iPhoneSimulator" not in info.get("CFBundleSupportedPlatforms", []):
        raise RuntimeError("Expected a simulator build, not an IPA or device build")
    manifest = {
        "appVersion": info.get("MineMicVersion", info["CFBundleShortVersionString"]),
        "build": info["CFBundleVersion"],
        "commit": os.environ.get("GITHUB_SHA", run("git", "rev-parse", "HEAD")),
        "xcode": run("xcodebuild", "-version"),
        "captureMethod": "XCUITest on the real app; native pixels, no resizing or padding",
        "audioNote": "Processing options are selected in the app UI; no PC connection or microphone capture is started.",
        "scenes": list(scenes),
        "devices": [],
    }
    output.mkdir(parents=True, exist_ok=True)
    diagnostics = ROOT / "build/screenshot-tests"
    diagnostics.mkdir(parents=True, exist_ok=True)
    for device_class in device_classes:
        udid, device_name, runtime, created = select_device(device_class)
        print(f"Running screenshot UI tests: {device_name}, iOS {runtime['version']}", flush=True)
        try:
            simctl("boot", udid, check=False)
            simctl("bootstatus", udid, "-b", timeout=300)
            simctl("status_bar", udid, "override", "--time", "9:41", "--dataNetwork", "wifi",
                   "--wifiMode", "active", "--wifiBars", "3", "--batteryState", "charged", "--batteryLevel", "100")
            result_bundle = diagnostics / f"{device_class}.xcresult"
            log_path = diagnostics / f"{device_class}.log"
            tests = [] if scenes == SCENES else [
                f"-only-testing:MineMicScreenshots/MineMicScreenshots/{name}" for name in
                ("test02ProcessingDark", "test04ProcessingLight")]
            returncode = run_logged([
                    "xcodebuild", "test-without-building", "-project", str(ROOT / "iosApp/iosApp.xcodeproj"),
                    "-scheme", "MineMicScreenshots", "-configuration", "Debug",
                    "-destination", f"id={udid}", "-derivedDataPath", str(derived_data.resolve()),
                    "-resultBundlePath", str(result_bundle), "-parallel-testing-enabled", "NO",
                    "-maximum-concurrent-test-simulator-destinations", "1", "CODE_SIGNING_ALLOWED=NO",
                ] + tests, log_path, timeout=1200)
            export_path = diagnostics / f"{device_class}-attachments"
            if returncode:
                if result_bundle.exists():
                    run("xcrun", "xcresulttool", "export", "attachments", "--path", str(result_bundle),
                        "--output-path", str(export_path), check=False)
                print(log_path.read_text()[-12000:], flush=True)
                raise RuntimeError(f"Screenshot UI tests failed for {device_name}; see the diagnostic artifact")
            device_output = output / device_class
            device_output.mkdir(parents=True, exist_ok=True)
            images = export_images(result_bundle, export_path, device_output, device_class, scenes)
            manifest["devices"].append({"deviceClass": device_class, "device": device_name,
                                        "runtime": runtime["version"], "runtimeIdentifier": runtime["identifier"],
                                        "images": images})
            (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
            print(f"Captured and validated {len(scenes)} scenes for {device_name}", flush=True)
        finally:
            simctl("status_bar", udid, "clear", check=False)
            simctl("shutdown", udid, check=False)
            if created:
                simctl("delete", udid, check=False)
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True, help="Built .app for arm64 iOS Simulator")
    parser.add_argument("--output", type=Path, default=ROOT / "build/app-store-screenshots")
    parser.add_argument("--derived-data", type=Path, default=ROOT / "build/ios-screenshots")
    parser.add_argument("--device", choices=("both", "ipad13", "iphone"), default="both")
    parser.add_argument("--scenes", choices=("all", "processing"), default="all")
    arguments = parser.parse_args()
    capture(arguments.app, arguments.output, arguments.derived_data,
            ("ipad13", "iphone") if arguments.device == "both" else (arguments.device,),
            SCENES if arguments.scenes == "all" else (SCENES[1], SCENES[3]))
