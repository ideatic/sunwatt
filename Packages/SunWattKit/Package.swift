// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "SunWattKit",
    defaultLocalization: "en",
    platforms: [.iOS("27.0"), .macOS("27.0"), .watchOS("27.0")],
    products: [
        .library(name: "SunWattKit", targets: ["SunWattKit"]),
        .executable(name: "SunWattSnapshots", targets: ["SunWattSnapshots"]),
        .executable(name: "SunWattWatchSnapshots", targets: ["SunWattWatchSnapshots"]),
    ],
    targets: [
        .target(
            name: "SunWattKit",
            resources: [.process("Resources")]
        ),
        .executableTarget(
            name: "SunWattSnapshots",
            dependencies: ["SunWattKit"]
        ),
        .executableTarget(
            name: "SunWattWatchSnapshots",
            dependencies: ["SunWattKit"]
        ),
        .testTarget(
            name: "SunWattKitTests",
            dependencies: ["SunWattKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
