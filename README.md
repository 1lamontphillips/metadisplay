# MetaDisplay

Android prototypes for a smooth, continuous phone-camera view in Meta Ray-Ban Display glasses.

The user has confirmed a live phone-camera scene in physical glasses through a bounded MP4 URL response. Motion pauses remain, with about two seconds of user-observed delay. The matching padded public-video control was smooth. The current update tests a camera frame-pacing correction; its effect on visible smoothness still requires device validation.

## Current transport experiment

[Download camera cadence check 5](artifacts/DisplayTransport-camera-cadence-5.zip) · [Phone instructions](artifacts/DisplayTransport-camera-cadence-5-INSTALL.md) · [Source and build](transport-test/README.md)

This updates the separate Display Transport Test app. The rear camera uses an anchored 24-Hz schedule and the latest available texture, with bounded timing counters for diagnosis. Twenty fixed fragment slots still provide an exact response length; valid MP4 padding fills unused space. Capture is explicit and foreground-only, saves no recording file, and ends at a real session limit of about 20 seconds.

[Transport Test 1](artifacts/DisplayTransport-test-1.zip) remains available as the validated finite-file baseline.

[Streaming check 2](artifacts/DisplayTransport-stream-check-2.zip) remains available for the earlier fragmented/gradual comparison.

[Delivery check 3](artifacts/DisplayTransport-delivery-check-3.zip) remains available for the unknown-length HTTP comparison. Those results suggest a length/seekability restriction in this software path; they do not establish a documented universal SDK requirement.

[Bounded camera experiment 4](artifacts/DisplayTransport-live-camera-4.zip) remains the first device-tested live-camera baseline.

Reports stay in memory until copied. No actual phone diagnostic reports or camera recordings are stored in this repository. Camera-response bytes use a bounded temporary memory buffer and a token-protected phone-loopback endpoint; earlier Wi-Fi controls serve only bundled test footage.

## Earlier sample builds

[Display Access sample and diagnostic builds](artifacts/README.md) remain available as baselines.
