// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "PixelBridge",
    platforms: [.macOS(.v13)],
    targets: [
        .target(name: "BridgeCore", path: "Sources/BridgeCore"),
        .executableTarget(name: "PixelBridge", dependencies: ["BridgeCore"], path: "Sources/PixelBridge"),
        // A fake "phone" used to test the Mac app and the wire protocol without an Android device.
        .executableTarget(name: "bridge-test-client", dependencies: ["BridgeCore"], path: "Sources/TestClient"),
    ]
)
