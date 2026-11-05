# Display Transport Test

A side-by-side Android test app for checking phone-hosted MP4 playback in Meta Ray-Ban Display glasses. Finite phone-local playback has been confirmed on the user's glasses. Version 0.2 tests progressive delivery as the next prerequisite for smooth phone-camera video; it does not access the camera.

This test has a distinct application ID and installs alongside Display Access. It uses Meta's documented Developer Mode values of `0` and needs its own app registration.

Version 0.2 compares a 20-second silent MP4 control, the same encoded pictures remuxed into fragmented MP4, paced known-length delivery, and paced chunked unknown-length delivery. The report records an immutable transfer snapshot at first SDK PLAYING and retains uncertainty for incomplete/ranged/concurrent measurements. See [the device test guide](../artifacts/DisplayTransport-stream-check-2-INSTALL.md).

The earlier transport tests remain available under **Show earlier transport tests** and use the same public sample clip:

1. Internet reference: the original sample URL checks this app's registration and player path.
2. Phone loopback: a temporary phone HTTP endpoint tests whether the URL-fetching path can reach the phone locally.
3. Phone Wi-Fi: a temporary endpoint on the phone's current Wi-Fi address tests reachability through the local network.

The fixture generation script writes into a temporary or supplied output directory without replacing checked-in assets. Exact file hashes can depend on FFmpeg/x264 versions. Validate that the generated matching pair carries identical packet hashes before replacing either asset.

The phone endpoint serves only the bundled clip under a temporary path. It does not serve files, diagnostics, or camera frames, and closes when the test ends or the app goes into the background. The test's plain HTTP permission is for this non-sensitive local fixture; HTTPS requests retain system certificate verification. Future camera transport requires its own verified design.

Build with JDK 21 and Android SDK 36:

```sh
source /workspace/android-setup/env.sh
./gradlew --no-daemon --max-workers=2 :app:assembleDebug
```

Use the included protocol test script for the fixed-asset HTTP server. Physical glasses playback and URL routing require device testing; a cloud build or protocol test cannot establish them.

Meta SDK 1.0.1 exposes URL-based MP4 playback but no public raw-camera video source. It forwards the source URL beyond the Android SDK boundary. Finite-file reachability does not prove continuous or fragmented-MP4 support.

The bundled clip and Gradle scaffolding derive from Meta's official Android sample; see `Meta-Sample-LICENSE`.
