# Yattu Bhaa

An Android app that lets a family member help someone who struggles with their phone, built for a
grandfather who has very poor eyesight and keeps his phone on the largest font.

- **Help Needed mode** (what he sees): one giant **Get Help** button. Every screen scrolls and
  keeps its buttons pinned, so no font size can push them off the screen.
- **Helper mode** (for you): hidden behind a long-press on the small "Yattu Bhaa" label at the
  bottom of the home screen, then a PIN.
- Encrypted end to end, no root, no adb. See [docs/SECURITY.md](docs/SECURITY.md).

## What works today

Pair by link, connect with a one-time number, see his screen, point at things with a ring on his
screen, and (once he says yes) tap, swipe and press Back/Home for him, never inside a banking app.
Either side can stop at any time. Tested on two Android 16 emulators through the real relay, with
the grandad phone at 200% font.

Screen sharing is a live video stream (H.264, or H.265 when both phones have it in hardware),
encoded and decoded by the phones' own video hardware. Every frame is numbered, the helper reports
four times a second on what arrived and how late, and the sharing phone uses that to pause capture
when the link backs up, pick the bitrate, and step down to a smaller picture at fewer frames a
second on a slow link. The picture freezes briefly on a correct image rather than ever showing a
corrupted one. After a real long-distance test showed tearing and a picture that went stale, the
worst causes turned out to be two of this app's own earlier mechanisms (dropping frames after
encoding, and resending an old keyframe); both are gone. Measured since under a genuinely
throttled link, on emulators only: see "How the picture gets to the helper" in
[docs/SECURITY.md](docs/SECURITY.md).

The helper sees a small Good/Fair/Poor indicator (long-press it for detailed stats); he sees
nothing of this, deliberately, to keep his own screen simple. In control mode, the helper can tap,
swipe, press and hold (a long-press), and press, hold and drag to move something, such as
reordering a list or moving an icon. Turning his phone sideways while sharing works.

While a session is open, both phones show a "Help session ... is open" notification with a Stop
button: newer Android versions cut the network off from apps that leave the screen without one,
which used to drop the session when he visited a settings screen or you switched apps.

**Both phones must run the same version**: the messages between them changed, and an older phone
will connect but not work properly.

**Not built yet:** the floating HOME button (Back/Home from the helper cover most of it), Hindi/Gujarati
text, and push notifications when he taps Get Help.

## Layout

```
app/           the Android app (Kotlin, Jetpack Compose)
relay-server/  the small relay the two phones meet at (Node.js)
docs/          security and design notes
```

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@23/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug testDebugUnitTest      # app + 85 unit tests
cd relay-server && npm install && npm test     # 12 relay tests
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Try it on two emulators

```bash
cd relay-server && npm start                   # relay on :8787
```

The debug build defaults to `ws://10.0.2.2:8787`, the emulator's name for your computer. Install on
both, then: on the helper phone long-press "Yattu Bhaa", set a PIN, **Set up a new phone**; deliver
the link to the other phone; tap **Yes, connect**; tap **Get Help**; type the number on the helper.

To try it over a slow, laggy connection, see "Test on a slow, laggy connection" in
[relay-server/README.md](relay-server/README.md).

## Using it for real

1. **Deploy the relay** somewhere with TLS: see [relay-server/README.md](relay-server/README.md) and
   [docs/SETUP-RELAY.md](docs/SETUP-RELAY.md). Where it runs matters: for India and the UK,
   Render's Frankfurt region rather than its default, Oregon (a region cannot be changed later).
2. **Get the app onto his phone.** This is the open question: a sideloaded APK needs him (or you,
   guiding him) to allow "install unknown apps", possibly get past a Play Protect scan prompt, and,
   once per install, allow "restricted settings" (see docs/SECURITY.md, "Not verified"). Play Store
   testing tracks are easier for him but need a developer account. The debug APK works over
   `wss://` but is debug-signed.
3. **First call, once:** on your phone make the link with your `wss://` address and send it over
   WhatsApp. He taps it and says Yes. Then have him tap **Get Help > Let you see my screen**; it
   sends him to Android's "Display over other apps" list, where he switches on Yattu Bhaa, so
   this is easiest done together, once. If the APK was installed from a file, that switch may say
   "App was denied access": then go to Settings > Apps > Yattu Bhaa > ⋮ > **Allow restricted
   settings**, and switch it on again. That one step also covers remote tap and swipe later.
4. **After that:** he taps Get Help and reads you the number; you type it in.
5. **Remote tap and swipe (optional):** in the session, tap **Ask to tap for them**. He gets a big
   Allow / Not now question. Each time, Android then sends him to its Accessibility list to switch
   on Yattu Bhaa (Android shows a "full control" warning; talk him through it). Afterwards you can tap
   and swipe on the mirrored screen, and use Back / Home / Recent (Back twice brings him out of
   Settings). It is switched off again automatically when the session ends, because some banking
   apps refuse to open while any app has it switched on. See docs/SECURITY.md before using it.

See also [docs/SETUP-RELAY.md](docs/SETUP-RELAY.md) for setting up the relay in plain terms.
