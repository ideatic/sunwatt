// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "HomeDashKit",
    defaultLocalization: "es",
    platforms: [.iOS("27.0"), .macOS("27.0")],
    products: [
        .library(name: "HomeDashKit", targets: ["HomeDashKit"]),
    ],
    targets: [
        .target(name: "HomeDashKit"),
        .testTarget(
            name: "HomeDashKitTests",
            dependencies: ["HomeDashKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
