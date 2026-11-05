# Display Transport Test: bounded live camera experiment 4

The known-length progressive MP4 test produced smooth visible video. All tested unknown-length variants failed before PLAYING on the tested device path. This update tries a deliberately finite live-camera response with an exact planned byte budget. It does not establish indefinite streaming support.

## Update on the Android phone

1. Download and extract `DisplayTransport-live-camera-4.zip` in Files.
2. Open `DisplayTransport-live-camera-4.apk` and choose **Update** for **Display Transport Test**. Its application ID and signing certificate are unchanged. Keep Display Access installed as a separate baseline.
3. Wear the glasses, keep this app open on the unlocked phone, and keep the phone in the same orientation throughout a run.

## First check the bounded control

Tap **Play bounded control**. It uses the same public silent video as earlier, with valid MP4 padding and an exact response length. Twenty fragments are produced progressively, about one per second. Watch the glasses until the run finishes and cleanup completes.

If it fails or does not show moving video, tap **Copy report**, paste the report into the troubleshooting chat, and describe what appeared. Resolve that control before trying the camera. A successful cloud build or standard-decoder check does not prove compatibility with the glasses.

## Then try the live rear camera

1. Tap **Allow camera for live test**, and grant Android Camera permission.
2. Tap **Start live camera test**. Point the rear camera at a clock or a moving object. Check the glasses for that live scene.
3. Keep this app visible until the run ends. It captures 20 video fragments, approximately 20 seconds depending on encoder keyframes. The whole test has a 75-second limit. Capture stops when its fragment budget is complete, on **Stop test**, when the app leaves the foreground, or on failure.
4. After cleanup finishes, tap **Copy report** and paste it into chat. Describe whether the scene was live, whether motion stayed smooth, and the approximate delay between movement in front of the phone and movement in the glasses. Also mention rotation or cropping problems.

This first camera experiment is silent. It buffers only a bounded response in temporary memory and saves no camera recording file. Its endpoint binds only to phone loopback under a temporary token; it does not expose a camera feed on Wi-Fi. Reports contain counters and static status/error categories, never camera frames, URLs, tokens, device names/IDs or credentials. Copying a report does not upload it automatically.

## What the fixed budget means

Each fragment gets a 65,536-byte slot. Unused space becomes a valid top-level MP4 `free` box. The exact HTTP Content-Length is initialization bytes plus 20 slots; it is not estimated from bitrate. Absolute fragment offsets are rebased when padding changes positions. Samples and timestamps are preserved. An oversized fragment or encoder failure aborts the test and reports failure; it does not truncate samples or claim a complete response.

The public SDK still receives a normal MP4 URL. The experiment has a real session limit and does not join separate recordings or promise seamless rollover. Smooth finite-file playback does not establish low live-camera latency.

The optional **Show earlier transport tests → Play length control** compares known-length delivery with byte ranges disabled. Earlier transport modes and versioned APKs remain available for reproduction.
