# Display Access: diagnostic update 2

This update isolates failures when opening a tutorial's detail screen. It labels menu, detail, and step rendering separately. The detail screen starts without its large photo; the initial menu icons and the rest of the detail layout remain unchanged. This is a controlled comparison, not a confirmed image defect or fix.

It also records video-player states and named errors before playback for a later video test. The original sample video URL and MP4 codec are unchanged. No automatic retry was added.

## Install

1. Download `DisplayAccess-diagnostics-2.zip` on the phone and extract it in Files.
2. Open `DisplayAccess-diagnostics-2.apk` and follow Android's update prompts for the existing Display Access app.
3. In **Settings → Connection details**, check that **Diagnostic update 2** appears. Leave **Include large tutorial image** off for the first test. This option resets to off when the app process restarts.

## Test the detail screen first

1. Keep the glasses worn and fully unfolded throughout the attempt.
2. Tap **Samples → Car maintenance guide → Try it**.
3. In the glasses, select **Oil change**. Check whether its title and **Start** button appear.
4. If that succeeds, select **Start** and check that **Step 1** appears.
5. If any step ends the session, return to phone **Settings → Connection details → Copy connection details** without closing the app or clearing its events. Paste the report into the troubleshooting chat.

Only after the detail screen and Step 1 work, test **Watch video**. If it fails, copy the same report so its player state/error events are included.

The image toggle can be enabled later for comparison after the image-free test succeeds. Its state is included in the copied report. Turning it on does not modify any source image.

## Build and evidence

The package uses the same application ID and Android debug signing certificate as the earlier builds. SDK remains 1.0.1. An accompanying patch shows changes from the original official Meta DisplayAccess sample. No production credentials are included. Connection details stay in app memory and are shared only when the user copies them; no actual phone diagnostic reports are included in this package.

Cloud build/signature checks do not verify the phone UI, physical glasses rendering, or clipboard behavior. Those require the device-side test above. A rendering error after device session termination can be secondary to the missing rendering acknowledgement, so this update does not treat that error as proof of an invalid image.
