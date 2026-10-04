// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "BridgeyMac",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "BridgeyMac", targets: ["BridgeyMac"])],
    targets: [
        .executableTarget(
            name: "BridgeyMac",
            // Component documentation lives next to the source (see ARCHITECTURE.md).
            exclude: [
                "BooksAutomation.md",
                "ChannelSecurity.md",
                "KvmGestureRecognizer.md",
                "KvmPointerCalibration.md",
                "NotificationActionRouting.md",
                "NotificationClearAllDetector.md",
                "NotificationIdentity.md",
                "ScreenStreamDecoder.md",
            ]
        ),
        .testTarget(name: "BridgeyMacTests", dependencies: ["BridgeyMac"]),
    ],
    swiftLanguageVersions: [.v5]
)

