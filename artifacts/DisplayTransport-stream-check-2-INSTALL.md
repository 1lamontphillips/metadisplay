# Display Transport Test: streaming check 2

The phone-local finite MP4 path is working. This update checks the remaining transport prerequisites for smooth continuous camera video: fragmented MP4 decoding, playback before all media bytes arrive, and HTTP delivery without a declared final file length. It still uses public test footage and does not access the phone camera.

## Update

1. Download `DisplayTransport-stream-check-2.zip` on the phone and extract it in Files.
2. Open `DisplayTransport-stream-check-2.apk` and update the existing **Display Transport Test** app. The application ID/signing certificate are unchanged. Keep the separate working Display Access app installed.
3. Keep the glasses worn and fully unfolded, and this app visible on the unlocked phone.

## Run these checks in order

Each new check uses a silent, 20-second video and has a 55-second overall limit. Allow each run and cleanup to finish before the next button. Some tests can take roughly 40 seconds if the player buffers the whole file first.

1. **Play silent control**: normal MP4 served from the phone. Confirm the picture is visible in the glasses; silence is intentional.
2. **Play fragmented clip**: the exact same encoded pictures in fragmented MP4 form, delivered immediately. Confirm the picture again.
3. **Play gradual delivery**: the fragmented file arrives gradually over about 20 seconds, with a known final length. The report records what the server had sent at the first SDK PLAYING event.
4. **Play stream check**: the same gradual delivery uses chunked HTTP without a Content-Length, and holds the final chunk terminator briefly after the payload. This models an unknown-length source's delivery behavior; it is still a finite fixture.

After the checks, tap **Copy report**, paste it into the troubleshooting chat, and say which buttons produced visible video. If a check fails, copy its report before proceeding. If cleanup is blocked, follow the app's force-stop/reopen instruction.

## What the result means

The two files contain identical 480 encoded H.264 video packets. Both are 266×150 at 24fps; only their MP4 structure differs. The fragmented file contains an initialization header followed by 20 movie/media fragments.

The report retains a first-PLAYING snapshot with response identity, bytes written/planned, completion flags, range status, concurrent GET count, and a write-in-progress guard. It treats multiple or partial requests conservatively. A single full response that still has unsent payload at first SDK PLAYING is useful progressive-playback evidence, provided the user also saw video. Playback only after the full payload was written does not establish that capability. Keeping a response open after all media bytes were sent is not enough.

Socket writes do not directly measure client receipt, decoder progress, or glass-to-glass latency. These tests do not prove indefinite streaming or a live camera implementation. They determine which continuous-media experiment is justified next. Actual phone reports and camera footage are not bundled or published.

Earlier reference/phone/Wi-Fi tests remain under **Show earlier transport tests**. No new permissions are requested by this update.
