# Display Access Android sample

The next experiment is a separate [Display Transport Test](./DisplayTransport-test-1.zip), which checks phone-hosted finite MP4 reachability without replacing this working sample. See its [phone instructions](./DisplayTransport-test-1-INSTALL.md).

## Diagnostic update

For a session that starts but ends when opening a tutorial, use [diagnostic update 2](./DisplayAccess-diagnostics-2.zip). It starts the detail screen without its large image for comparison and labels menu/detail/step and video events. See the [test instructions](./DisplayAccess-diagnostics-2-INSTALL.md) and [source changes](./DisplayAccess-diagnostics-2.patch). This is a controlled isolation test, not a confirmed fix.

[Diagnostic update 1](./DisplayAccess-diagnostics-1.zip) remains available for the earlier startup trace. Both updates add **Settings → Connection details → Copy connection details**. The original sample package remains below.

## Original sample

[Download the APK package (ZIP, 26.4 MiB)](./DisplayAccess-download.zip)

This package contains the debug-signed APK built from [Meta's official DisplayAccess sample](https://github.com/facebook/meta-wearables-dat-android/tree/main/samples/DisplayAccess), an APK checksum, installation instructions, and Meta's sample license. The official sample source and dependency declarations were left unchanged. The sample displays a car-maintenance tutorial on compatible glasses; it does not stream the phone's camera.

## Install and test

1. On your Android phone, download the ZIP using the link above. If GitHub opens the file page, choose **Download raw file**.
2. Open the ZIP in your phone's Files app and extract it.
3. Tap **DisplayAccess-debug.apk** and follow Android's installation prompts.
4. Have your Meta Ray-Ban Display glasses paired in Meta's companion app and enable Meta Developer Mode for that linked device. The sample requires Android 12 or newer.
5. Open **Display Access**, grant Nearby devices/Bluetooth permission, tap **Connect my glasses**, and complete registration until the sample reports **Registered**.
6. Open **Samples → Car maintenance guide → Try it**. In the glasses, select **Oil change → Start** and confirm the instructions appear. Test **Next** and **Previous**.

The APK was built successfully and its Android debug signature verified. Installation, physical glasses connectivity, and visible content still require testing on the paired phone and glasses. Simulated previews do not establish physical device support.

Developer Mode uses Meta's documented development application ID and client token values of `0`. No production credentials are included.

ZIP SHA-256:

```text
fbbbbfe262d6cb5f3710537c1472cfd8eccb7be1b73c9484564fd7516ed72279
```
