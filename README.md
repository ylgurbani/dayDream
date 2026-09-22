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

Screen sharing is a live H.264 video stream (hardware encode and decode via `MediaCodec`), not a
series of still pictures — see "How the picture gets to the helper" in
[docs/SECURITY.md](docs/SECURITY.md) for how that works and the real bugs found building it,
including one found only once two real phones tried it genuinely far apart: the encoder had no
cap on its own output rate, and the pointer ring's own animation could push it past the relay's
message-rate limit, closing the connection almost every time the helper pointed at something —
fixed with `FrameRateLimiter`, verified by deliberately hammering the pointer for half a minute.
Its actual latency on a real phone over a real connection has otherwise not been measured; what
has been verified on two emulators is that the picture, pointer ring, and remote tap and swipe are
all correct through it.

The bitrate adapts live to how the connection is actually coping (lower under real congestion,
higher once it clears), and the helper sees a small Good/Fair/Poor indicator built from the same
signal — not shown to him, deliberately, to keep his own screen simple. A drag now carries the
whole path a finger took, not just a straight line between where it started and ended, which is
what real swipes and scrolls actually need (dragging to reorder still needs a continuous
press-then-move gesture the protocol does not support yet — see docs/SECURITY.md).

**Not built yet:** the floating HOME button (Back/Home from the helper cover most of it), Hindi/Gujarati
text, push notifications when he taps Get Help, and rotation while sharing.

## Layout

```
app/           the Android app (Kotlin, Jetpack Compose)
relay-server/  the small relay the two phones meet at (Node.js)
docs/          security and design notes
```

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@23/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug testDebugUnitTest      # app + 58 unit tests
cd relay-server && npm install && npm test     # 10 relay tests
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Try it on two emulators

```bash
cd relay-server && npm start                   # relay on :8787
```

The debug build defaults to `ws://10.0.2.2:8787`, the emulator's name for your computer. Install on
both, then: on the helper phone long-press "Yattu Bhaa", set a PIN, **Set up a new phone**; deliver
the link to the other phone; tap **Yes, connect**; tap **Get Help**; type the number on the helper.

## Using it for real

1. **Deploy the relay** somewhere with TLS: see [relay-server/README.md](relay-server/README.md).
   Nothing is deployed yet and no hosting account has been created.
2. **Get the app onto his phone.** This is the open question: a sideloaded APK needs him (or you,
   guiding him) to allow "install unknown apps". Play Store testing tracks are easier for him but
   need a developer account. The debug APK works over `wss://` but is debug-signed.
3. **First call, once:** on your phone make the link with your `wss://` address and send it over
   WhatsApp. He taps it and says Yes. Then have him tap **Get Help > Let you see my screen**; it
   sends him to Android's "Display over other apps" list, where he switches on Yattu Bhaa, so
   this is easiest done together, once.
4. **After that:** he taps Get Help and reads you the number; you type it in.
5. **Remote tap and swipe (optional):** in the session, tap **Ask to tap for them**. He gets a big
   Allow / Not now question. The first time only, Android sends him to its Accessibility list to switch
   on Yattu Bhaa (Android shows a "full control" warning; talk him through it). Afterwards you can tap
   and swipe on the mirrored screen, and use Back / Home / Recent. A **Turn off remote control**
   button on his home screen switches it off again. See docs/SECURITY.md before using it.

See also [docs/SETUP-RELAY.md](docs/SETUP-RELAY.md) for setting up the relay in plain terms.
