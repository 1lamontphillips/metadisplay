# Display Transport Test

A side-by-side Android experiment for phone-hosted MP4 playback in Meta Ray-Ban Display glasses. Smooth progressive playback of a known-length fragmented MP4 has been confirmed on the user's glasses. Version 0.4 adds a bounded rear-camera producer through that route; physical camera playback and latency remain unverified.

This test has a distinct application ID and installs alongside Display Access. It uses Meta's documented Developer Mode values of `0` and needs its own app registration.

Version 0.4 first checks a progressively produced padded fixture, then offers Camera2 → EGL scaling/rotation → MediaCodec AVC → public Media3 fragmented MP4 muxer → bounded phone-loopback HTTP delivery. Twenty 65,536-byte fragment slots give the response an exact length, independent of variable encoded sample sizes. Valid MP4 `free` boxes fill unused slot space; absolute fragment offsets must be rebased. Overflow or invalid container structure fails explicitly. See [the device test guide](../artifacts/DisplayTransport-live-camera-4-INSTALL.md).

Camera use is explicit, foreground-only and silent. The response cache has a fixed memory bound and is cleared on cleanup; no camera file is saved. The source stops at the fragment limit or on Stop/background/failure. This is a finite live experiment, not seamless indefinite streaming. The optional known-length/no-range control checks the remaining seekability difference from the failed variants.

Version 0.3 compared paced close-delimited HTTP/1.1 and HTTP/1.0 responses plus an immediate chunked control. All failed before PLAYING on the tested device path. The observed requests were HTTP/1.1, so the HTTP/1.0 chunked fallback was not relevant to those failures. These results suggest a length/seekability restriction; public SDK documentation does not state a universal Content-Length requirement.

Version 0.2's ordinary MP4, fragmented MP4, paced known-length and paced chunked modes remain under **Show earlier transport tests**, along with an immediate close-delimited control. The known-length gradual test worked; the paced chunked test failed before PLAYING. Request HTTP versions were not captured by 0.2, so that report cannot determine whether HTTP/1.0 chunked incompatibility was involved.

The earlier transport tests remain available under **Show earlier transport tests** and use the same public sample clip:

1. Internet reference: the original sample URL checks this app's registration and player path.
2. Phone loopback: a temporary phone HTTP endpoint tests whether the URL-fetching path can reach the phone locally.
3. Phone Wi-Fi: a temporary endpoint on the phone's current Wi-Fi address tests reachability through the local network.

The fixture generation script writes into a temporary or supplied output directory without replacing checked-in assets. Exact file hashes can depend on FFmpeg/x264 versions. Validate that the generated matching pair carries identical packet hashes before replacing either asset.

The live endpoint binds only to phone loopback under a temporary token, serves a bounded in-memory media response, and closes on cleanup. Earlier Wi-Fi controls serve only bundled footage. No endpoint serves arbitrary filesystem paths or diagnostics. HTTPS requests retain system certificate verification.

Build with JDK 21 and Android SDK 36:

```sh
source /workspace/android-setup/env.sh
./gradlew --no-daemon --max-workers=2 :app:assembleDebug
```

Use the included protocol test script for the fixed-asset HTTP server. Physical glasses playback and URL routing require device testing; a cloud build or protocol test cannot establish them.

Meta SDK 1.0.1 exposes URL-based MP4 playback but no public raw-camera video source. It forwards the source URL beyond the Android SDK boundary. This prototype uses public Camera2/MediaCodec and pinned Media3 muxer 1.11.1 to produce its bounded MP4; no private SDK APIs or arbitrary oversized Content-Length values are used.

The bundled clip and Gradle scaffolding derive from Meta's official Android sample; see `Meta-Sample-LICENSE`.

The public Media3 muxer is provided by the Android Open Source Project under Apache 2.0; see `Media3-LICENSE`.
