// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "HomeDashKit",
    defaultLocalization: "en",
    platforms: [.iOS("27.0"), .macOS("27.0")],
    products: [
        .library(name: "HomeDashKit", targets: ["HomeDashKit"]),
        .executable(name: "HomeDashSnapshots", targets: ["HomeDashSnapshots"]),
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
        .testTarget(
            name: "HomeDashKitTests",
            dependencies: ["HomeDashKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
