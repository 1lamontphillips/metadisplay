# Display Transport Test: five-minute camera 8

The user confirmed version 0.7 stays smooth and readable throughout a one-minute live camera run, with about two seconds of acceptable delay. This update adds a five-minute camera option using the same picture, pacing and framing. The five-minute run still needs physical phone/glasses validation.

## Update and run on the phone

1. Download and extract `DisplayTransport-five-minute-8.zip` in Files.
2. Open `DisplayTransport-five-minute-8.apk` and choose **Update** for **Display Transport Test**. The package ID and signing certificate are unchanged. No new permissions are requested.
3. Wear the glasses, keep the phone unlocked with this app visible, and tap **Start five-minute camera**. Grant Camera access first if requested, then tap Start explicitly.
4. Point the rear camera at a moving scene. Check whether the picture stays upright, readable and smooth for the whole five minutes. Saved rotation and Fill frame / Show whole view choices still apply; the view controls work during the run.
5. Let the run and cleanup finish before switching to chat. Tap **Copy report**, paste the fresh **0.8 report**, and say whether motion stayed smooth throughout and whether the delay changed.

The report should show **Mode: LIVE_CAMERA_300**, a **300-fragment** budget, and eventually **fragments=300/300** if capture completes. Stop is always available. Leaving this app stops active capture. The event list keeps its most recent 240 entries; the first-PLAYING summary and bounded SDK state counts are retained separately. Those counts do not identify buffering or visible pauses.

## Session limits

The five-minute option uses one continuous camera producer, encoder, MP4 stream, SDK session and HTTP response. Three hundred one-second GOP fragments give about five minutes of capture. This remains a finite run.

The exact response length is actual initialization bytes plus `300 × 65,536` bytes. A typical 703-byte initialization gives 19,661,503 bytes, approximately 18.8 MiB. Valid MP4 padding fills unused slot space. Response length is never estimated from bitrate. The existing memory cap is unchanged.

The camera has a 360-second capture guard and a 660-second overall test/HTTP limit, allowing bounded startup or buffered playback. These guards are not the intended capture duration: the source normally completes near five minutes. Visible motion and delay must be confirmed in the glasses; SDK states and byte counters do not measure them.

## Shorter tests remain available

**Start one-minute camera** retains its 60-fragment budget, 90-second capture guard and 150-second test/HTTP limits. **Start live camera test** under **Quick live check** retains its 20-fragment budget and original short limits. **Show earlier transport tests → Play bounded control** still uses the original 20-fragment public fixture.

Capture remains explicit, foreground-only and silent. The camera response stays in bounded temporary memory and is cleared on cleanup; no recording file or microphone is used. The camera endpoint binds only to phone loopback under a temporary token. Diagnostic reports contain counters and numeric view settings, never camera images, URLs, tokens, names/IDs or credentials.
