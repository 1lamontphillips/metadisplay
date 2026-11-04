# Display Access: diagnostic update 1

This update adds a **Connection details** card to the Settings screen so a session-start failure can be investigated from the phone. It does not claim to fix `Session ended by device`, and it does not add live phone-camera streaming.

## Install the update

1. Download `DisplayAccess-diagnostics-1.zip` on the phone and extract it using Files.
2. Open `DisplayAccess-diagnostics-1.apk` and follow Android's app-update prompts. Use the existing Display Access installation; this build uses the same application ID and Android debug signing certificate.
3. Open **Display Access → Settings** and scroll to **Connection details**. Check that it says **Diagnostic update 1**.

## Collect one attempt

1. Keep your glasses worn and fully unfolded, and keep Display Access visible on the unlocked phone.
2. Tap **Samples → Car maintenance guide → Try it** once.
3. After the error, return to **Settings** without closing the app.
4. Scroll to **Connection details** and tap **Copy connection details**.
5. Paste the copied report into the chat where the problem is being investigated.

The report records observed registration/session/display states, named SDK errors, Android API level, and allowlisted device state (model type, compatibility, connection, display support, battery, wearing/hinge/thermal state). It excludes device names, identifiers, addresses, and credentials. Events are held only in app memory, and nothing is uploaded automatically. The app's developer-configuration flag does not prove that Developer Mode is enabled in Meta's companion app.

The published original APK remains available separately. The original official sample source was preserved; this update was built from an isolated copy with only diagnostic instrumentation and the Settings card added. The accompanying patch shows those changes. No retry loop or session-control change was added.

## Verification limits

Build and signature validation can be completed in the cloud. Real registration, session startup, glasses rendering, the Settings card, and clipboard behavior require the phone-side test above; no phone is attached to the cloud workspace.
