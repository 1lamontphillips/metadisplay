# Display Transport Test: camera framing check 6

The live camera reaches the glasses. Version 0.5 reports steady 24-fps encoding, but the user holding the phone upright saw sideways video in a tall narrow strip. This update corrects orientation composition and defaults to a centered crop that fills the video area. Physical readability and orientation still need testing.

## Update and try the view

1. Download and extract `DisplayTransport-camera-framing-6.zip` in Files on the phone.
2. Open `DisplayTransport-camera-framing-6.apk` and choose **Update** for **Display Transport Test**. Its package ID and signing certificate are unchanged.
3. Wear the glasses, hold the phone upright as before, keep this app open, and tap **Start live camera test**. If Camera permission is requested, grant it and tap Start explicitly.
4. The default is **Fill frame**. If the picture needs rotation, tap **Rotate 90°** once and wait a couple of seconds to see the result in the glasses. Repeat until upright. You can change the view before or during a run; the choices are remembered.
5. Let the test and cleanup finish, then tap **Copy report**. Paste the fresh **0.6 report** into chat and describe whether the picture is upright, fills a useful width, and looks undistorted. Also mention any pauses and the approximate delay.

Each run still ends after 20 fragments, about 20 seconds, with a 75-second overall limit. If it ends while you adjust, start another run with the saved settings. Keep the phone in the same orientation during a run; switching to chat stops active capture.

## Fill frame and Show whole view

**Fill frame** makes the image larger through a centered crop. With an upright phone, some top and bottom content is cropped. It preserves proportions.

**Show whole view** keeps the complete image. An upright portrait image will then occupy a narrower part of the wide video area. Tap **Fill frame** to return to the larger view.

**Rotate 90°** adjusts the image clockwise in quarter turns. Its displayed adjustment is relative to the automatic orientation. Wait for the existing feed delay after each tap instead of rapidly cycling through all settings.

## What changed

Camera `SurfaceTexture` already supplies a stream transform, including rotation, cropping and flipping. The old renderer additionally applied sensor rotation to its vertices. The new renderer retains the supplied transform once, applies display/manual adjustment in texture coordinates, and derives image aspect from its pixel-space axes. Fill uses centered sampling crop with a full output quad; whole-view mode uses undistorted letterboxing. Rotation is no longer applied again to the vertices.

View options update atomically on render ticks without restarting the player, encoder, or frame clock. The report separates requested settings from the geometry actually applied after a successful draw. It contains only finite numeric view metadata and the existing safe counters; no camera pixels, IDs, URLs or tokens.

The 0.5 frame pacing, camera format, GOP, MP4 budget, HTTP route, permissions and cleanup remain unchanged. Capture is still explicit, foreground-only and silent, with bounded temporary memory and no recording file. This is a finite experiment, not indefinite streaming.
