# MetaDisplay

Android prototypes for a smooth, continuous phone-camera view in Meta Ray-Ban Display glasses.

The user confirmed version 0.7 gives a good, smooth live phone-camera view throughout a one-minute run in physical glasses, with about two seconds of acceptable observed delay. Version 0.8 adds a five-minute option while retaining the working shorter tests. The five-minute run still needs device validation.

## Current transport experiment

[Download five-minute camera 8](artifacts/DisplayTransport-five-minute-8.zip) · [Phone instructions](artifacts/DisplayTransport-five-minute-8-INSTALL.md) · [Source and build](transport-test/README.md)

This updates the separate Display Transport Test app. **Start five-minute camera** uses one continuous encoder/session and an exact 300-fragment byte budget. The one-minute option, 20-fragment quick camera test and public-fixture controls retain their original budgets. Anchored 24-Hz pacing and the saved live rotation/framing controls are unchanged. Capture is explicit and foreground-only, saves no recording file, and ends at its selected finite limit.

[One-minute camera 7](artifacts/DisplayTransport-one-minute-7.zip) remains the user-confirmed one-minute baseline.

[Transport Test 1](artifacts/DisplayTransport-test-1.zip) remains available as the validated finite-file baseline.

[Streaming check 2](artifacts/DisplayTransport-stream-check-2.zip) remains available for the earlier fragmented/gradual comparison.

[Delivery check 3](artifacts/DisplayTransport-delivery-check-3.zip) remains available for the unknown-length HTTP comparison. Those results suggest a length/seekability restriction in this software path; they do not establish a documented universal SDK requirement.

[Bounded camera experiment 4](artifacts/DisplayTransport-live-camera-4.zip) remains the first device-tested live-camera baseline.

[Camera cadence check 5](artifacts/DisplayTransport-camera-cadence-5.zip) remains available for the previous pacing comparison.

[Camera framing check 6](artifacts/DisplayTransport-camera-framing-6.zip) remains the user-confirmed smooth, readable short-session baseline.

Reports stay in memory until copied. No actual phone diagnostic reports or camera recordings are stored in this repository. Camera-response bytes use a bounded temporary memory buffer and a token-protected phone-loopback endpoint; earlier Wi-Fi controls serve only bundled test footage.

## Earlier sample builds

[Display Access sample and diagnostic builds](artifacts/README.md) remain available as baselines.
