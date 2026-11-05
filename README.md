# MetaDisplay

Android prototypes for a smooth, continuous phone-camera view in Meta Ray-Ban Display glasses.

The official sample's video playback and smooth progressive playback of a phone-hosted, known-length fragmented MP4 have been confirmed on physical glasses. A live phone-camera stream has not yet been implemented or validated. Meta SDK 1.0.1 exposes MP4 playback by URL; the current experiment compares unknown-length delivery methods through that route.

## Current transport experiment

[Download delivery check 3](artifacts/DisplayTransport-delivery-check-3.zip) · [Phone instructions](artifacts/DisplayTransport-delivery-check-3-INSTALL.md) · [Source and build](transport-test/README.md)

This updates the separate Display Transport Test app. The gradual chunked response failed before playback. This update compares close-delimited HTTP/1.1 and HTTP/1.0 responses with an immediate chunked control, recording actual protocol/framing and transfer state at first SDK PLAYING. It does not request camera access. Successful finite-clip playback is a prerequisite experiment, not proof of indefinite continuous-media support.

[Transport Test 1](artifacts/DisplayTransport-test-1.zip) remains available as the validated finite-file baseline.

[Streaming check 2](artifacts/DisplayTransport-stream-check-2.zip) remains available for the earlier fragmented/gradual comparison.

Reports stay in memory until copied. No actual phone diagnostic reports or camera recordings are stored in this repository. The temporary endpoint serves only the bundled public sample clip.

## Earlier sample builds

[Display Access sample and diagnostic builds](artifacts/README.md) remain available as baselines.
