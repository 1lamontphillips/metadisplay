# MetaDisplay

Android prototypes for a smooth, continuous phone-camera view in Meta Ray-Ban Display glasses.

The official sample's video playback and phone-local finite MP4 playback have been confirmed on physical glasses. A live phone-camera stream has not yet been implemented or validated. Meta SDK 1.0.1 exposes MP4 playback by URL; the next experiment checks progressive fragmented-MP4 delivery through that route.

## Current transport experiment

[Download streaming check 2](artifacts/DisplayTransport-stream-check-2.zip) · [Phone instructions](artifacts/DisplayTransport-stream-check-2-INSTALL.md) · [Source and build](transport-test/README.md)

This updates the separate Display Transport Test app. It compares matching normal/fragmented MP4 files and paced known/unknown-length delivery, recording transfer state at first SDK PLAYING. It does not request camera access. Successful finite-clip playback is a prerequisite experiment, not proof of continuous-media support.

[Transport Test 1](artifacts/DisplayTransport-test-1.zip) remains available as the validated finite-file baseline.

Reports stay in memory until copied. No actual phone diagnostic reports or camera recordings are stored in this repository. The temporary endpoint serves only the bundled public sample clip.

## Earlier sample builds

[Display Access sample and diagnostic builds](artifacts/README.md) remain available as baselines.
