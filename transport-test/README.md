# Display Transport Test

A side-by-side Android test app for checking phone-hosted MP4 playback in Meta Ray-Ban Display glasses. Smooth progressive playback of a known-length fragmented MP4 has been confirmed on the user's glasses. Version 0.3 compares unknown-length delivery methods as the next prerequisite for smooth phone-camera video; it does not access the camera.

This test has a distinct application ID and installs alongside Display Access. It uses Meta's documented Developer Mode values of `0` and needs its own app registration.

Version 0.3 compares paced close-delimited HTTP/1.1 and HTTP/1.0 responses, plus an immediately delivered chunked control. All use the same finite 20-second silent fragmented MP4. The report records actual request/response protocols and framing, plus an immutable transfer snapshot at first SDK PLAYING. It retains uncertainty for incomplete/ranged/concurrent measurements. Chunked delivery falls back to connection-close framing for HTTP/1.0 requesters. See [the device test guide](../artifacts/DisplayTransport-delivery-check-3-INSTALL.md).

Version 0.2's ordinary MP4, fragmented MP4, paced known-length and paced chunked modes remain under **Show earlier transport tests**, along with an immediate close-delimited control. The known-length gradual test worked; the paced chunked test failed before PLAYING. Request HTTP versions were not captured by 0.2, so that report cannot determine whether HTTP/1.0 chunked incompatibility was involved.

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
