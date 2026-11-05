# Display Transport Test: delivery check 3

Known-length gradual delivery has produced smooth visible video in the glasses. The gradual chunked test failed before PLAYING. This update compares HTTP response framing using the same silent, finite 20-second fragmented MP4. It does not access the camera.

## Update on the phone

1. Download and extract `DisplayTransport-delivery-check-3.zip` in Files.
2. Open `DisplayTransport-delivery-check-3.apk` and choose **Update** for **Display Transport Test**. Its application ID and signing certificate are unchanged. Keep the separate Display Access app installed.
3. Keep the glasses worn and unfolded and the app open on the unlocked phone throughout each test.

## Run these buttons in order

1. **Play direct stream**: gradual raw media with an HTTP/1.1 response, no Content-Length and no chunk encoding. The finite response ends when the connection closes.
2. **Play compatibility stream**: the same delivery with an HTTP/1.0 response.
3. **Play chunked control**: immediate delivery of the same media with chunk encoding, to compare framing independently of gradual delivery. An HTTP/1.0 requester receives a standards-compliant close-delimited fallback instead; the report records actual request/response protocols and framing.

HTTP/1.0 requests receive HTTP/1.0 responses in every mode. If the requester uses that version, the two direct-stream buttons may produce the same actual response protocol; the report makes that explicit.

Allow each test and cleanup to finish before starting the next. Each has a 55-second limit. The fixtures are silent, with a 20-second media timeline. Keep this app visible until it finishes; switching to chat stops an active test.

Afterward tap **Copy report**, paste it into the troubleshooting chat, and say which buttons showed moving video, whether it stayed smooth, and approximately how long it played. SDK PLAYING/ENDED events alone do not establish visible playback or its duration.

## Optional controls

Under **Show earlier transport tests**, **Play direct control** sends the close-delimited body immediately. The known-length **Play gradual delivery**, earlier gradual **Play stream check**, and original reference/local/Wi-Fi controls remain available. Further tests are blocked if resource cleanup cannot be confirmed; follow the app's force-stop/reopen instruction in that case.

## Scope of the evidence

The report captures actual response framing, request/response HTTP versions, and an immutable transfer snapshot at first SDK PLAYING. It retains the distinction between unsent media bytes and an open response after the payload is complete. Multiple or partial GETs make timing conclusions conservative. Socket writes do not measure client receipt, decoder progress or visible latency.

Close-delimited success would distinguish that route from the failed chunked route. Failure of these finite variants would not establish that all continuous transport is impossible. Indefinite camera streaming remains unimplemented and unverified. No new permissions are requested. Reports stay in memory until copied; actual phone reports and camera recordings are not included in the repository or APK.
