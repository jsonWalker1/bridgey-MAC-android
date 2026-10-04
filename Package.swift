// swift-tools-version: 5.10
import Foundation
import PackageDescription

// DOMAIN SOURCE TREE (see ARCHITECTURE.md). SwiftPM only accepts target paths inside the package
// root, so the package lives at the repository root while macos/ stays the app shell (resources,
// build and release scripts). Sources are the legacy macos/Sources/BridgeyMac plus every
// features/<feature>/macos; tests are macos/Tests/BridgeyMacTests plus every
// features/<feature>/tests/macos. Everything else in the repository (docs, android/, *.md next to
// the sources, ...) is excluded so the build stays warning-free.

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let fileManager = FileManager.default

func children(_ relative: String) -> [String] {
    let path = relative.isEmpty ? root : "\(root)/\(relative)"
    return ((try? fileManager.contentsOfDirectory(atPath: path)) ?? []).sorted()
        .map { relative.isEmpty ? $0 : "\(relative)/\($0)" }
}

func isDirectory(_ relative: String) -> Bool {
    var directory: ObjCBool = false
    return fileManager.fileExists(atPath: "\(root)/\(relative)", isDirectory: &directory) && directory.boolValue
}

func featureDirectories(_ subdirectory: String) -> [String] {
    children("features").map { "\($0)/\(subdirectory)" }.filter(isDirectory)
}

/// Every path under `relative` that is not a Swift file inside one of `sources`.
func everythingExcept(_ sources: [String], under relative: String = "") -> [String] {
    children(relative).flatMap { entry -> [String] in
        if sources.contains(where: { entry == $0 || entry.hasPrefix($0 + "/") }) {
            if isDirectory(entry) { return everythingExcept(sources, under: entry) }
            return entry.hasSuffix(".swift") ? [] : [entry]
        }
        if sources.contains(where: { $0.hasPrefix(entry + "/") }) {
            return everythingExcept(sources, under: entry)
        }
        return [entry]
    }
}

let appSources = ["macos/Sources/BridgeyMac"] + featureDirectories("macos")
let testSources = ["macos/Tests/BridgeyMacTests"] + featureDirectories("tests/macos")

let package = Package(
    name: "Bridgey",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "BridgeyMac", targets: ["BridgeyMac"])],
    targets: [
        .executableTarget(
            name: "BridgeyMac",
            path: ".",
            exclude: everythingExcept(appSources),
            sources: appSources
        ),
        .testTarget(
            name: "BridgeyMacTests",
            dependencies: ["BridgeyMac"],
            path: ".",
            exclude: everythingExcept(testSources),
            sources: testSources
        ),
    ],
    swiftLanguageVersions: [.v5]
)
