# MetaDisplay

Android prototypes for a smooth, continuous phone-camera view in Meta Ray-Ban Display glasses.

The official sample's video playback and smooth progressive playback of a phone-hosted, known-length fragmented MP4 have been confirmed on physical glasses. The tested unknown-length responses failed before playback. A bounded live-camera experiment is now implemented through the known-length MP4 URL route; physical camera playback and latency still require validation.

## Current transport experiment

[Download bounded camera experiment 4](artifacts/DisplayTransport-live-camera-4.zip) · [Phone instructions](artifacts/DisplayTransport-live-camera-4-INSTALL.md) · [Source and build](transport-test/README.md)

This updates the separate Display Transport Test app. It first checks a padded finite fixture, then offers an explicit, foreground-only rear-camera test. Twenty fixed fragment slots provide an exact response length; valid MP4 padding fills unused space. The camera experiment needs Android Camera permission and stops at a real session limit, about 20 seconds. It saves no recording file and is not an indefinite stream.

[Transport Test 1](artifacts/DisplayTransport-test-1.zip) remains available as the validated finite-file baseline.

[Streaming check 2](artifacts/DisplayTransport-stream-check-2.zip) remains available for the earlier fragmented/gradual comparison.

[Delivery check 3](artifacts/DisplayTransport-delivery-check-3.zip) remains available for the unknown-length HTTP comparison. Those results suggest a length/seekability restriction in this software path; they do not establish a documented universal SDK requirement.

Reports stay in memory until copied. No actual phone diagnostic reports or camera recordings are stored in this repository. Camera-response bytes use a bounded temporary memory buffer and a token-protected phone-loopback endpoint; earlier Wi-Fi controls serve only bundled test footage.

## Earlier sample builds

[Display Access sample and diagnostic builds](artifacts/README.md) remain available as baselines.
