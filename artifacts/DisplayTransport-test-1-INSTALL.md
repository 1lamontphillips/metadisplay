# Display Transport Test 1

This is a separate app that tests whether the glasses can play an MP4 hosted on the phone. It serves the same short public clip that worked in Display Access. It does not access the camera, and finite-clip playback does not yet prove smooth continuous camera streaming.

## Install and connect

1. Download `DisplayTransport-test-1.zip` on your Android phone and extract it in Files.
2. Open `DisplayTransport-test-1.apk` and install **Display Transport Test**. Its package is different, so the working **Display Access** app stays installed.
3. Before testing, force-stop **Display Access** in Android's app settings to release its glasses session. Keep Meta Developer Mode enabled and the glasses paired in Meta's companion app.
4. Open **Display Transport Test**, grant **Nearby devices** access, then tap **Connect my glasses** and complete registration in Meta's app.
5. Wear the glasses fully unfolded and wait until this test app shows **Registered**, **Connected**, and **Worn**. Keep the test app visible on the unlocked phone.

## Two tests first

1. Tap **Play reference clip**. Look through the glasses and note whether the original short video plays. Wait for the buttons to become available again after cleanup.
2. Tap **Play phone-local clip**. Look through the glasses again. This source is served by the phone, rather than the public sample URL.
3. Tap **Copy report** and paste the report into the troubleshooting chat. Say separately whether each clip was visible in the glasses.

The report includes whether requests reached the temporary phone endpoint, how many media bytes it sent, and SDK playback state/errors. It omits endpoint tokens, addresses, device names/identifiers, and credentials. SDK PLAYING or ENDED alone is not a physical viewing confirmation.

## Optional Wi-Fi route

If the phone-local clip cannot be reached, **Play phone Wi-Fi clip** provides another route when the phone has an available private Wi-Fi IPv4 address. Wear the glasses, keep the app open, run that test once, then copy the report. Wi-Fi route availability can be affected by network configuration; the app does not change router, VPN, or companion-app settings.

## Stop and cleanup

Use **Stop test** to end a run. The temporary phone endpoint serves only the bundled public clip, has a random path, and closes when the test ends or the app leaves the foreground. Each run has a 30-second limit. If cleanup cannot be confirmed, the app blocks the next test and asks for force-stop/reopen so overlapping sessions do not invalidate the experiment.

This is a Developer Mode, debug-signed test build for Android 12 or newer. It uses Meta SDK 1.0.1 and the documented development ID/token values of `0`. No production credentials or phone-camera frames are included.
