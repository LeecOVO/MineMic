#!/usr/bin/env swift
// macOS: swift scripts/export-app-icons.swift design/branding/minemic-icon.svg
// Platform size exports only: preserve the artwork and downsample for launchers.
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

guard CommandLine.arguments.count == 2 else {
    fatalError("Usage: swift scripts/export-app-icons.swift <square-source.svg|png>")
}
let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
var input = URL(fileURLWithPath: CommandLine.arguments[1])
if input.pathExtension.lowercased() == "svg" {
    // Keep the editable reference's rounded outer tile and shadow intact. Platform
    // exports have an opaque full-bleed background; the OS supplies the outer mask.
    let directory = root.appendingPathComponent("build/icon-export", isDirectory: true)
    try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    var svg = try! String(contentsOf: input, encoding: .utf8)
    svg = svg.replacingOccurrences(of: "</defs>", with: "</defs>\n<rect width=\"512\" height=\"512\" fill=\"#121215\" />")
    svg = svg.replacingOccurrences(of: "fill=\"#121215\" filter=\"url(#dropShadow)\"", with: "fill=\"#121215\"")
    let rendered = directory.appendingPathComponent("minemic-icon-platform.svg")
    try! svg.write(to: rendered, atomically: true, encoding: .utf8)
    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/qlmanage")
    process.arguments = ["-t", "-s", "1024", "-o", directory.path, rendered.path]
    process.standardOutput = FileHandle.nullDevice
    process.standardError = FileHandle.nullDevice
    try! process.run()
    process.waitUntilExit()
    precondition(process.terminationStatus == 0, "SVG rendering failed")
    input = URL(fileURLWithPath: rendered.path + ".png")
}
guard let source = CGImageSourceCreateWithURL(input as CFURL, nil),
      let artwork = CGImageSourceCreateImageAtIndex(source, 0, nil),
      artwork.width == artwork.height else {
    fatalError("Expected square artwork")
}
let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
func export(_ path: String, size: Int, scale: CGFloat = 1) {
    let destination = root.appendingPathComponent(path)
    try! FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
    let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
        bytesPerRow: size * 4, space: colorSpace,
        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    context.setFillColor(CGColor(red: 18 / 255, green: 18 / 255, blue: 21 / 255, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: size, height: size))
    context.interpolationQuality = .high
    let side = CGFloat(size) * scale
    let margin = (CGFloat(size) - side) / 2
    context.draw(artwork, in: CGRect(x: margin, y: margin, width: side, height: side))
    let png = CGImageDestinationCreateWithURL(destination as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(png, context.makeImage()!, nil)
    precondition(CGImageDestinationFinalize(png), "Could not write \(path)")
    print("\(path): \(size)x\(size), opaque sRGB")
}
export("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png", size: 1024)
// Adaptive layers are 108dp, with a guaranteed central 66dp circular safe area.
// Give the complete tile a margin so its waveform survives circular cropping.
export("androidApp/src/main/res/drawable-nodpi/ic_launcher_artwork.png", size: 1024, scale: 0.70)
for (density, size) in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)] {
    export("androidApp/src/main/res/mipmap-\(density)/ic_launcher.png", size: size)
    export("androidApp/src/main/res/mipmap-\(density)/ic_launcher_round.png", size: size)
}
