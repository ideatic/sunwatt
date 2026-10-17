// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "HomeDashKit",
    defaultLocalization: "en",
    platforms: [.iOS("27.0"), .macOS("27.0"), .watchOS("27.0")],
    products: [
        .library(name: "HomeDashKit", targets: ["HomeDashKit"]),
        .executable(name: "HomeDashSnapshots", targets: ["HomeDashSnapshots"]),
        .executable(name: "HomeDashWatchSnapshots", targets: ["HomeDashWatchSnapshots"]),
    ],
    targets: [
        .target(
            name: "HomeDashKit",
            resources: [.process("Resources")]
        ),
        .executableTarget(
            name: "HomeDashSnapshots",
            dependencies: ["HomeDashKit"]
        ),
        .executableTarget(
            name: "HomeDashWatchSnapshots",
            dependencies: ["HomeDashKit"]
        ),
        .testTarget(
            name: "HomeDashKitTests",
            dependencies: ["HomeDashKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
