// swift-tools-version: 5.10
// EXPERIMENT ONLY - Apple Books automation POC. Not part of the Bridgey app.
import PackageDescription

let package = Package(
    name: "BooksAutomationPOC",
    platforms: [.macOS(.v13)],
    targets: [.executableTarget(name: "BooksAutomationPOC")]
)
