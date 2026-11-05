# Display Transport Test: one-minute camera 7

The user confirmed version 0.6 gives a good, smooth live picture, with about two seconds of observed delay. This update adds a one-minute camera option using the same format, pacing and framing. The longer run still needs physical phone/glasses validation.

## Update and run on the phone

1. Download and extract `DisplayTransport-one-minute-7.zip` in Files.
2. Open `DisplayTransport-one-minute-7.apk` and choose **Update** for **Display Transport Test**. The package ID and signing certificate are unchanged. No new permissions are requested.
3. Wear the glasses, keep the phone unlocked with this app visible, and tap **Start one-minute camera**. Grant Camera access first if requested, then tap Start explicitly.
4. Point the rear camera at a clock or moving scene. Check whether the image remains upright, readable and smooth throughout the minute. Your saved rotation and Fill frame / Show whole view choices still apply; the view controls work during the run.
5. Let the run and cleanup finish before switching to chat. Tap **Copy report**, paste the fresh **0.7 report**, and say whether motion stayed smooth for the whole minute and whether the delay changed.

The new report should show **Mode: LIVE_CAMERA_60**, a **60-fragment** budget, and eventually **fragments=60/60** if capture completes. Stop is always available. Leaving this app stops active capture.

## Session limits

The one-minute option uses one continuous camera producer, encoder, MP4 stream, SDK session and HTTP response. It does not join separately recorded clips. Sixty one-second GOP fragments give about one minute of capture; this remains a real finite limit, not indefinite streaming.

The exact response length is actual initialization bytes plus `60 × 65,536` bytes. A typical 703-byte initialization gives 3,932,863 bytes. Valid MP4 padding fills unused slot space. The response length is never estimated from bitrate.

The one-minute camera has a 90-second capture guard and a 150-second overall test/HTTP limit, allowing bounded startup or buffered playback. These guards are not the intended capture duration. The source normally completes near one minute. Initial playback can begin before all fragments exist, but visible freshness and delay must be confirmed in the glasses.

## Short baseline remains available

**Start live camera test** under **Quick live check** retains the original 20-fragment, roughly 20-second camera run, with its original short limits. **Show earlier transport tests → Play bounded control** still uses the original 20-fragment public fixture. The new session plan does not expand that finite fixture.

Capture remains explicit, foreground-only and silent. The camera response stays in bounded temporary memory and is cleared on cleanup; no recording file or microphone is used. The camera endpoint binds only to phone loopback under a temporary token. Diagnostic reports contain counters and numeric view settings, never camera images, URLs, tokens, names/IDs or credentials.
