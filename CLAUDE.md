# Yattu Bhaa — notes for Claude Code

An Android app so a family member (the "helper") can remotely help their elderly grandfather
(the "needy" side, in India: very poor eyesight, largest font, confused by technical words).
Read `README.md` and `docs/SECURITY.md` first: SECURITY.md is the full, honest record of what was
built, every real bug found and fixed, and what is verified versus not.

## Rules that do not change

- **What this app must not become** (end of `docs/SECURITY.md`): no hidden or silent start, no
  disguising the app, no defeating other apps' security checks (his banking apps refuse
  TeamViewer; never try to evade that detection), no capture of secure (`FLAG_SECURE`) screens,
  no unattended access. Every session needs his consent; remote control needs a second yes.
- Keep his screen free of technical detail. Diagnostics go to the helper only (the stats overlay
  behind a long-press on the Good/Fair/Poor dot).
- Every screen must survive 200% font scale: content scrolls, the main button is pinned below it.
- Say plainly when an earlier fix caused a problem, and caveat anything not verified on real
  phones and real networks.

## Working with the owner

- The owner tests on real phones (often genuinely long-distance) and reports back in plain
  language. Work "reproduce broken → fix → re-verify", with measured numbers.
- When asked to suggest, suggest and wait; don't implement until asked.
- This folder is not a git repository. The owner keeps a separate git clone of
  github.com/ylgurbani/dayDream, copies changes into it by hand and commits from Terminal. Never
  commit or push. After changes, list every file changed so none is missed, and remind them that
  copying loses the executable bit on `gradlew` (`chmod +x gradlew` before committing).
- The relay is deployed on Render from that repo. Say so whenever `relay-server/` changes, since
  it needs redeploying there.
- Both phones must run the same build: the messages between them change often.

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@23/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug testDebugUnitTest      # app + unit tests (Gradle 8.11.1, Java 23)
cd relay-server && npm test                    # relay tests
```

The debug APK is copied to `Yattu-Bhaa-debug.apk` in this folder for the owner. It is signed with
the Mac's debug key (backed up by the owner); updates only install over it with that same key.
The release build type has never been built or tested (R8 rules exist only for Tink).

## Where things are

- `app/.../service/` — screen capture and video: `ScreenShareService` (ties it together),
  `ScreenEncoder` / `VideoDecoder` (MediaCodec H.264/H.265), `SendGate` (pauses capture when the
  link backs up, never drops encoded frames), `SendTracker` / `ReceiveTracker` (frame numbers,
  receiver reports), `CongestionController` (bitrate and 720/544/432 quality tiers),
  `RemoteInput` + `RemoteInputAccessibilityService` (taps, swipes, press-hold-drag, banking-app
  guard in `SecureAppPolicy`), `SessionOverlay` (Stop button, pointer ring, banner).
- `app/.../session/` — `NeedySession`, `HelperSession`, `BaseSession` (reconnects),
  `SessionHub`, `SessionService` (foreground service so a session survives the app leaving the
  screen: newer Android cuts background apps' network after ~5 s).
- `app/.../net/` — `Protocol` (message formats), `SecureChannel` (end-to-end encryption),
  `RelayClient`.
- `relay-server/` — the blind WebSocket relay; `tools/netem-proxy.js` slows a connection for tests.

## Testing on emulators

- Two emulators (e.g. ports 5554 = needy, 5556 = helper) with a local relay on `:8787`. The debug
  build uses `ws://10.0.2.2:8787`.
- `10.0.2.2` bypasses the emulator's own network throttling. To test a slow link, run
  `relay-server/tools/netem-proxy.js` and give only one phone the proxy's port (edit the `r=` in
  the pairing link delivered to it).
- The two emulators' clocks differ by about 2 s: never subtract log timestamps across devices
  without measuring the offset first.
- Restarting an emulator from its saved snapshot can roll the installed app back to an old build
  (Get Help then silently does nothing): reinstall the current APK after booting, and re-pair.
- Overlay windows don't appear in uiautomator dumps; screenshot and tap by position.
- `adb shell input draganddrop` makes a real press-hold-drag (the helper's hold threshold is
  450 ms, so raise that emulator's `long_press_timeout`). Switching on magnification mid-drag
  makes Android cut a held drag short on purpose.
- Pre-granting the overlay permission with `appops` hides the real settings trip; reset it to
  `default` to test that flow.

## Open items

Moving the Render relay to the Frankfurt region (Oregon is the default; a region can't be
changed later, so it means a new service and re-pairing both phones), getting the app onto his
phone (sideload or a Play testing track), release signing, the floating HOME button,
Hindi/Gujarati text, and testing alongside his real banking apps.
