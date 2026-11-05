# MetaDisplay

Android prototypes for a smooth, continuous phone-camera view in Meta Ray-Ban Display glasses.

The official sample's video playback has been reported working repeatedly on physical glasses. A live phone-camera stream has not yet been implemented or validated. Meta SDK 1.0.1 exposes MP4 playback by URL, so the next experiment checks whether that route can reach a video hosted on the phone.

## Current transport experiment

[Download Display Transport Test 1](artifacts/DisplayTransport-test-1.zip) · [Phone instructions](artifacts/DisplayTransport-test-1-INSTALL.md) · [Source and build](transport-test/README.md)

This installs alongside the working Display Access sample. It compares the same finite clip from the internet, phone loopback, and the phone's Wi-Fi address. It does not request camera access. Successful finite-clip playback is a prerequisite experiment, not proof of continuous-media support.

Reports stay in memory until copied. No actual phone diagnostic reports or camera recordings are stored in this repository. The temporary endpoint serves only the bundled public sample clip.

## Earlier sample builds

[Display Access sample and diagnostic builds](artifacts/README.md) remain available as baselines.
