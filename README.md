# MetaDisplay

Android prototypes for a smooth, continuous phone-camera view in Meta Ray-Ban Display glasses.

The user has confirmed a live phone-camera scene in physical glasses through a bounded MP4 URL response. Version 0.5 reports steady 24-fps encoding, but the upright-phone view was sideways and narrow. The current update corrects camera orientation/framing and adds live view controls. Physical readability and motion still require validation; the earlier user-observed delay was about two seconds.

## Current transport experiment

[Download camera framing check 6](artifacts/DisplayTransport-camera-framing-6.zip) · [Phone instructions](artifacts/DisplayTransport-camera-framing-6-INSTALL.md) · [Source and build](transport-test/README.md)

This updates the separate Display Transport Test app. The camera respects its supplied texture transform once and fills the video area with an undistorted centered crop. **Rotate 90°** and **Show whole view / Fill frame** work during capture and retain the user's choice. Anchored 24-Hz scheduling and the exact 20-fragment byte budget remain. Capture is explicit and foreground-only, saves no recording file, and ends at a real session limit of about 20 seconds.

[Transport Test 1](artifacts/DisplayTransport-test-1.zip) remains available as the validated finite-file baseline.

[Streaming check 2](artifacts/DisplayTransport-stream-check-2.zip) remains available for the earlier fragmented/gradual comparison.

[Delivery check 3](artifacts/DisplayTransport-delivery-check-3.zip) remains available for the unknown-length HTTP comparison. Those results suggest a length/seekability restriction in this software path; they do not establish a documented universal SDK requirement.

[Bounded camera experiment 4](artifacts/DisplayTransport-live-camera-4.zip) remains the first device-tested live-camera baseline.

[Camera cadence check 5](artifacts/DisplayTransport-camera-cadence-5.zip) remains available for the previous pacing comparison.

Reports stay in memory until copied. No actual phone diagnostic reports or camera recordings are stored in this repository. Camera-response bytes use a bounded temporary memory buffer and a token-protected phone-loopback endpoint; earlier Wi-Fi controls serve only bundled test footage.

## Earlier sample builds

[Display Access sample and diagnostic builds](artifacts/README.md) remain available as baselines.
