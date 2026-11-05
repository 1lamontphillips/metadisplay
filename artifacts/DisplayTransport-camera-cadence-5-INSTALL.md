# Display Transport Test: camera cadence check 5

Version 0.4 showed a live rear-camera scene in physical glasses, with visible pauses and an approximately two-second user-observed delay. The padded public-video control was smooth. This update tests a camera-only pacing correction and records numeric timing evidence. A phone/glasses test must determine whether it improves motion.

## Update and run on the phone

1. Download and extract `DisplayTransport-camera-cadence-5.zip` in Files.
2. Open `DisplayTransport-camera-cadence-5.apk` and choose **Update** for **Display Transport Test**. The package ID and signing certificate are unchanged.
3. Wear the glasses and keep the app visible on the unlocked phone. Tap **Start live camera test**. If Camera permission is requested, grant it, then tap Start explicitly.
4. Point the rear camera at a clock or moving object. Watch whether motion stays continuous through the whole run. Keep the phone in the same orientation.
5. Let the test and cleanup finish before leaving the app. It still has a 20-fragment limit, about 20 seconds, and a 75-second overall limit. Tap **Copy report**, paste it into chat, and describe pauses, approximate delay, and any rotation/cropping issue.

The existing **Play bounded control** remains available for comparison. Its footage and delivery are unchanged. If cleanup is blocked, follow the app's force-stop/reopen instruction before another test.

## What changed

The earlier camera path submitted a frame only when a fresh texture was pending and scheduled its next attempt relative to the current time. Missing a callback at a deadline could skip an encoder submission and change the frame cadence.

The camera now uses anchored 24-Hz deadlines after its first real image. It updates the texture when fresh camera data is available and otherwise submits the latest live texture at the next tick. Presentation timestamps follow scheduled monotonic ticks. Overdue ticks are skipped while preserving elapsed gaps; they are not compressed into a faster timeline or queued for unlimited catch-up. A two-second fresh-camera watchdog remains, and a large missed pacing deadline fails explicitly instead of hiding a long stall.

The report adds counts of submitted, fresh, repeated and skipped frames, plus maximum fresh-frame, rendering, codec-PTS, keyframe and fragment-release gaps. It captures current source/HTTP/producer context when SDK playback changes to IDLE or PLAYING. IDLE is a playback-state event, not a documented buffering diagnosis.

The camera format, target 24fps, one-second keyframe interval, muxer threshold, 20 fixed slots, byte budget and HTTP route remain as before. The update does not add startup buffering or extend the session. It tests a timing hypothesis; it does not establish that the glasses reject all variable-rate video.

Capture remains explicit, foreground-only and silent. Camera bytes stay in a bounded temporary memory response, with no recording file, microphone or Wi-Fi camera endpoint. No new permissions are requested. The public SDK still receives a valid, finite MP4 URL; indefinite streaming remains unimplemented.
