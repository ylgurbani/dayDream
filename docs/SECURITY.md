# Yattu Bhaa: security and design notes

This is a remote-help tool for one vulnerable person to get help from one trusted family member.
Its safety properties matter more than its feature list. This records what the app does, what it
deliberately does not do, and what is not yet verified.

## How a session is protected

1. **Pairing.** The helper's phone makes a random 256-bit secret and a link
   (`https://<relay>/join#k=<secret>&n=<name>&r=<relay>`). The person being helped taps it; the
   secret sits after the `#`, which browsers never send to a server. The app shows who is asking
   and requires a Yes before storing anything. Pairing secrets are stored encrypted with a key
   held in the Android Keystore.
2. **Every session starts from the person being helped.** They tap Get Help and get a one-time
   six digit number. The helper types it in. Both sides derive their keys from the pairing
   secret **and** that number, so a stolen link is not enough to join. Five wrong tries end the
   session; the number expires after ten minutes.
3. **Encrypted end to end.** A fresh X25519 key exchange per session (so recorded traffic cannot
   be decrypted later), AES-256-GCM per direction, strictly increasing counters (replays and
   reordering are rejected). The relay only ever carries ciphertext. See `SecureChannel.kt`,
   covered by unit tests including the RFC 5869 vector, wrong code, wrong secret, tampering
   and replay.
4. **Screen sharing needs a second, separate yes.** Even when connected, nothing is visible until
   they tap the share button and accept Android's own screen-capture dialog.
5. **Always-visible Stop.** While sharing, a large red STOP SHARING button stays on top of every
   app, and Android shows its own screen-sharing indicator. Either side ending it tears down the
   capture, overlay and notification.
6. **No unattended access.** A connected session that never starts sharing closes itself after
   three minutes. There is no way for the helper to start anything.

## Staying connected through a real blip

Real networks drop for a moment — wifi switching, a brief DNS hiccup, the relay restarting. Three
things were found by deliberately breaking the connection mid-session and watching what happened,
not by reasoning about it in the abstract:

- **This phone's own connection to the relay drops.** It is retried automatically (four attempts,
  growing delay: 1.5s, 3s, 6s, 6s) before the session is actually ended. Verified by killing the
  relay mid-session and restarting it five seconds later: the session picked back up on its own —
  no message shown, no action needed, and a screen-share request made right after still worked,
  proving the channel was genuinely re-established and not just showing a stale "connected" label.
- **The other phone is briefly not in the room.** Found a real bug here: each phone retries its own
  connection independently, so after a shared blip one of them typically rejoins a few seconds
  before the other. The first one back was treating the other's momentary absence as "they left"
  and ending a session that was actually about to recover by itself. Fixed with a grace period
  (8 seconds — long enough for one or two retry attempts to reconnect, short enough that a phone
  whose app genuinely closed is not reported 15+ seconds late, which real-device testing found the
  original 15 second grace period was doing), but only *after* a session has been secured at least
  once — the ordinary "have not shown up yet" case (nobody connected yet) still says so
  immediately, with no invented delay. The relay itself notices a closed connection and tells the
  other phone almost instantly; this grace period is the dominant part of the remaining delay.
- **A connection attempt never completes at all** (their phone is off, its app closed mid-connect,
  or the number was never entered because they never got that far). Previously this left the
  helper's screen saying "Waiting for Grandad" forever with no way to know it was not going to
  change on its own — confirmed by force-stopping the app on the other phone right as the helper
  connected, and confirmed it does not recover on its own (18+ seconds of watching, nothing
  changes; nothing in the code would ever have changed it). Fixed: after 25 seconds without
  connecting, it now says so and offers to try again.
- **His app being swiped away from Recents while actively sharing** used to have no handling at
  all — a real, meaningful gap, not a hypothetical one. Android does not stop a foreground service
  just because its task was removed from Recents (deliberately: the same mechanism lets a music
  player keep playing after being swiped away), so `ScreenShareService` kept running, invisibly,
  with no signal to the helper that anything had happened — the helper would only find out once
  the relay's own heartbeat eventually noticed the dead connection, or Android eventually reclaimed
  the process, whichever came first, neither bounded or fast. Fixed two ways: `onTaskRemoved()`
  now ends the session properly the moment the task is removed, sending the helper a clean
  "stopped" message over the still-open connection — the same fast path as tapping Stop, not the
  slow one — and, found only by reading real logcat timestamps rather than trusting a UI-polling
  loop that turned out to have its own, misleading overhead under load: the helper's own video
  decoder was being torn down *on the main thread*, and a slow decoder teardown (confirmed on an
  emulator's software codec: several real seconds) blocked Compose from showing the "session
  ended" screen it was itself in the middle of triggering, even though the underlying state had
  already updated instantly. Moved the decoder onto its own thread, the same way the encoder
  already worked. Verified with a screenshot taken a fixed short time after swiping the app away
  from Recents (not a polling loop, which this exercise found could not be trusted for measuring
  anything on this timescale): the helper's screen showed "Finished" within about two seconds.
- **Either app leaving the screen for more than a few seconds** dropped the session, on newer
  Android versions. Reported from a real test: the moment he came back from Android's "Display
  over other apps" screen (which comes before sharing starts), the connection was gone.
  Reproduced on an emulator, where Android logged "Destroyed live tcp sockets" for this app five
  seconds after it left the screen — not a crash, and not the app being killed (its process was
  untouched): newer Android cuts the network off from an app that is in the background without a
  foreground service, and closes its open connections. His phone only had a foreground service
  while sharing; the helper's never did, so switching to a WhatsApp call mid-session for more than
  a few seconds would have ended it too. Fixed with `SessionService`, a foreground service that
  runs for exactly as long as a session is open on either phone, with a notification saying so
  ("Help session with ... is open.") and a Stop button; on his phone it steps aside once sharing
  starts, since the sharing service's own notification then takes over. Verified: twelve
  seconds lingering on the overlay setting, and the helper's app away on the home screen for
  twenty, both with the session intact and no connection destroyed.

## What each capability needs from Android

| Piece | Standing state on his phone | Ends when |
|-------|-----------------------------|-----------|
| Idle app | Installed; a paired-helper record; "display over other apps" allowed once | Never (a setting) |
| Get Help / connected | A relay connection, and a foreground service with a "Help session ... is open" notification so the connection survives the app leaving the screen | Session ends |
| Screen sharing | Foreground service + capture, granted per session by Android's dialog | Session ends or Stop |
| Pointer ring | Overlay window during a session only | Session ends |
| Remote tap/swipe (built, opt-in) | An Accessibility Service, offered when he says Yes and switched on by him in Settings, every session. | Session ends |

**Remote tap and swipe is switched off again at the end of every session**, and also hidden from
Android's Accessibility list, so between sessions nothing on his phone has an accessibility service
switched on. An earlier version left it on between sessions to spare him the Settings trip each
time (Android forgets he turned it on the moment it is switched off). A real test then found that
some banking apps refuse to open at all while *any* app has an accessibility service on, until that
app is uninstalled or the switch is turned off: left on, it would have locked him out of his bank.
The cost is that every session he says Allow, Android's Accessibility list opens, and he switches
Yattu Bhaa on again and accepts Android's "full control" warning (the helper can see his screen
throughout and talk him through it). While it is on, during a session, those same banking apps
will still refuse to open; ending the session is what lets them open again.

How it is switched off (`ControlCapability.switchOff`): the service switches itself off through
Android's own call for that (`disableSelf`), and the app hides the service from the list. Either
alone clears Android's switch (tested on an emulator: hiding it alone, after a crash, cleared the
switch within 2 seconds of the app restarting). It runs when a session ends however it ends — his
Stop, the helper's Stop, a dropped connection, the app swiped away — and again whenever the app
starts, so a session cut off without ending properly (the app crashing or being killed, the phone
restarting) cannot leave it on either: Android restarts an app whose accessibility service is on,
and that restart switches it off. Measured on an emulator: the switch was off within 0.1 seconds of
the helper tapping Stop, and within 2 seconds of the app being crashed deliberately mid-session.
Force-stopping the app also switches it off (Android does that itself).

The home screen's **Turn off remote control** button is now only a safety net and should never
show: it appears only while Android's own record says the switch is on (it is re-read whenever
that record changes). An earlier version showed it from his first Allow on, whether or not he had
actually switched it on in Settings, or after Android had switched it off.

**Found while making it switch off every session:** a service that has just been put back in
Android's list takes a moment to appear there (1.1 to 1.4 seconds, measured on an emulator), and
an Accessibility list opened before then leaves Yattu Bhaa out, for good, until it is closed and
opened again. The first-ever Allow always had this race too; it just happened once and was never
caught. Now, after he taps Allow, the app waits until Android lists the service (up to 5 seconds)
before opening the list. It cannot open Yattu Bhaa's own switch page directly, which would save
him a step: Android reserves that for system apps (`OPEN_ACCESSIBILITY_DETAILS_SETTINGS`).

**Android, not just this app, can take the accessibility switch away** — confirmed by clearing it
directly, the same as if the system had revoked it on its own. When he next said Yes, an earlier
version waited a few seconds and then just reported "needs a setting" with no way to get to that
setting, because it only offered the Settings trip the first time ever. Tapping Allow now sends him
to Settings whenever the switch is not actually on — which, since it is switched off after every
session, is every time.

Whether the switch is on is now read from Android's own record of enabled accessibility services
(`ControlCapability.isSwitchedOn`), not guessed from how long the service takes to connect. The
guess got both cases wrong: the first time, he waited three seconds for a trip to Settings that
was always going to be needed; and on an older phone slow to reconnect a service that *was*
switched on, he could be sent to Settings for nothing. Now: switched off, he goes to Settings as
soon as Android lists the service (see above; about 1.1 seconds on an emulator); switched on but
still connecting, it waits for the connection and never sends him to Settings.

## Remote tap and swipe: how it is contained

- The helper can only **ask**. A full-screen "Allow / Not now" question appears on his phone, on top
  of whatever app he is in. His phone ignores every tap, swipe and back/home message unless he said
  yes for this session, and only while his screen is being shared.
- A red banner ("Yashwant can tap on your screen") stays on his screen while it is on, next to the
  Stop button. Stop ends the whole session.
- **Never inside a banking or payment app.** Before every tap or swipe the service asks Android which
  apps have a window on screen (a package name, nothing else) and refuses if any is on the list in
  `SecureAppPolicy.kt` (known apps plus words like "bank" and "upi"), **or if it cannot tell**.
  Back, Home and Recents are always allowed, because that is how someone gets out of such an app.
  The helper is told when taps are paused. I found and fixed a real hole in this while testing: an
  earlier version remembered the last window event, which is wiped when Android rebinds the service
  (for example when any app is installed), and treated "unknown" as "allowed".
- **Press, hold and drag** (moving an icon, reordering a list) is streamed live, one step at a
  time, rather than sent whole once the finger lifts, so the same rules are applied to *every*
  step, not just the first: a drag that reaches a banking app is let go on the spot. The held
  finger is also lifted the moment control ends for any reason (Give back, Stop, the session
  ending, a secure app), and on its own if the helper's connection goes quiet for five seconds
  mid-drag — a finger is never left pressed on his screen. Lifting is always allowed, like Back
  and Home: it can only let go. See `RemoteInputAccessibilityService.touch`.
  The banking-app check itself asks every app on screen for its window, which means waiting on
  each app in turn, and for a drag it used to run on every step, many times a second, on the
  connection's own thread — where a slow answer also held up every video frame going to the
  helper. A real test saw drags freeze part-way on both phones until the next tap; that could not
  be reproduced on an emulator (whose launcher answers in a few milliseconds), but this is the
  likeliest cause, so: taps and drags now run on their own thread; a drag is checked when it is
  pressed, again whenever Android reports a different window coming to the front, and at least
  every 0.75 seconds; and if drag steps pile up behind anything, the finger goes straight to the
  newest position rather than replaying the backlog. The helper's stats overlay now shows how many
  steps were carried out, how many failed or were cut short by Android, and the slowest step.
  The next real test's overlay then showed what was actually happening: "cut short 2, failed 158"
  — Android had twice cancelled a held drag part-way, and every later step of each of those
  drags was silently ignored, leaving his launcher with an icon floating mid-drag until the next
  tap. Android cancels an injected drag on any real touch on his screen, and also whenever it
  rebuilds the machinery that injects these touches, which it does when accessibility settings
  change — reproduced on an emulator by switching magnification on mid-drag — and plausibly a few
  times while settling just after the service is switched on (the real test saw it only in the
  first couple of drags). Now, if the helper's finger is still moving 0.3 seconds after such a
  cut (time for a brief touch of his own to finish undisturbed), the finger is pressed again
  where the helper's is and carries on, which also clears anything left frozen. Verified with the
  magnification switch: one drag cut short, resumed, no steps lost, both screens normal after.
- The service does not read what is on the screen. It declares the ability to see windows only so it
  can read those package names. Android's own "full control" warning uses generic wording that
  sounds broader than that.
- It is off in the manifest, and is only offered once he has said yes to a request.
- Recovery from Blocked back to On happens the next time a Back/Home/Recents/Notifications message
  is applied (those always go through), or the next tap once he is genuinely out. If he leaves the
  secure app himself and the helper sends nothing further, the paused state does not clear on its
  own — there is no proactive recheck purely from the app changing on his screen, only from the
  next message actually being applied. Pressing one of the nav buttons is the reliable way out.
- **Recovery from Unavailable is proactive, unlike Blocked, and this was a real bug on a real
  device, not a hypothetical.** Android can rebind or restart the accessibility service entirely
  on its own mid-session, well after he already said yes — confirmed by clearing the service
  directly rather than through anything in this app, while a session already had control On.
  Every gesture correctly reported Unavailable once that happened, exactly as designed, but
  nothing ever un-reported it: an earlier version of the code that decides whether to even attempt
  a gesture excluded Unavailable, on the reasoning that only On and Blocked were worth acting on —
  which meant that once Android reconnected the service on its own, the very next gesture never
  reached the check that would have noticed, because the exclusion stopped it first. The session
  was stuck reporting Unavailable indefinitely, with no gesture, however many were sent, able to
  fix it. Fixed two ways: that exclusion now only covers Off and Asked (genuine consent gates,
  which must never be bypassed by a message alone) so a gesture arriving after the service is
  back gets a fair, live re-check instead of being turned away on stale state; and separately, the
  session now watches for the service reconnecting on its own and recovers immediately, with no
  gesture needed at all. Verified on two emulators: granted control, revoked the service directly
  (not through this app), confirmed every gesture including Back and Home correctly stopped
  working, then re-granted it and confirmed the session recovered entirely on its own — no
  message sent, no re-ask — and a Recent-apps gesture sent afterwards genuinely worked.

## What Android itself hides, not this app

While Android's own Settings app is in the foreground, Android hides every overlay window from
every app, ours included — the red Stop button, banner and pointer ring vanish, and come back the
instant he leaves Settings. Confirmed to be Android's own doing, not this app's, by opening Settings
with a command that never touches this app's code at all and watching the same thing happen.
**Taps and swipes keep working the whole time** — only the visible overlay is hidden, confirmed by
watching a real slider move while it was invisible.

This app used to remove its own overlay by hand before sending him to turn the accessibility switch
on, from an assumption that Android would otherwise ignore the tap on "Allow" (it does, if another
app's overlay is actually visible and drawn — that assumption was correct in general, just
unnecessary here). Tested since: Android's own Settings-foreground hiding already covers that exact
dialog, so removing our overlay on top of it only cost him the Stop button and the pointer ring for
no benefit. The app no longer touches the overlay for this at all — it always stays "on"; Android
decides moment to moment whether to actually draw it, correctly and more precisely than any timer
this app could set.

## How the picture gets to the helper

Screen sharing is a live video stream, encoded and decoded by the phones' own video hardware
(`MediaCodec`, surface-in/surface-out, the same technique scrcpy uses): his screen draws straight
into the encoder, the compressed picture draws straight onto the helper's screen, and no bitmap is
ever copied through app code. It replaced a JPEG-per-frame pipeline that did all of that in software.

What happens to each frame, as of the third round of real-world fixes (see below for why):

- **Capture.** The encoder is told to skip frames beyond a set rate *before* encoding them
  (`KEY_MAX_FPS_TO_ENCODER`), and to repeat the current picture when the screen is still
  (`KEY_REPEAT_PREVIOUS_FRAME_AFTER`), so the stream never goes quiet and a still screen keeps
  sharpening. Optional settings are tried in layers, so an encoder that rejects one still works
  (`ScreenEncoder`).
- **Numbering.** Every frame sent carries a sequence number and its send time.
- **Receiver reports.** Four times a second the helper says which frame it has got up to, and how
  much queueing delay frames are picking up on the way — measured against the quietest recent
  moment, so the two phones' clocks never need to agree (`ReceiveTracker`, `SendTracker`).
- **Flow control.** When frames already sent are not getting through in reasonable time — too many
  bytes, or too old, still unconfirmed — capture is *paused* at the source, and resumed with an
  ordinary frame when the link clears (`SendGate`). Nothing already encoded is thrown away in the
  normal course of things, so the chain of frames the helper decodes is never broken.
- **Keyframes on demand.** After any gap in the numbers, the helper's decoder keeps showing the
  last correct picture and asks for a keyframe; periodic keyframes are only a 10-second safety net
  (`VideoDecoder`). Frames that arrive before the helper's screen is laid out are held and shown the
  moment it is, rather than dropped.
- **Adapting.** From the reports, `CongestionController` halves the bitrate when the picture is
  queueing anywhere on the way (or capture is being held back most of the time), climbs back
  slowly when it is clear, and below a point steps down to a smaller picture at a lower frame rate
  — 720, then 544, then 432 pixels across at 20, 15, 10 frames a second — because for reading his
  screen, fewer crisp frames beat many blurry ones. `OvershootCorrector` measures what the encoder
  actually produces and scales what it is asked for, since encoders do not always hit their target.
- **H.265** is used instead of H.264 when both phones have it in hardware and it supports the size
  (about a third fewer bits for the same picture); anything that fails falls back to H.264.
- **Rotation** restarts the encoder at the new shape; the helper's view follows.

The helper can long-press the Good/Fair/Poor indicator for a small stats overlay (codec, size,
frame rate, bitrate, queueing delay, round trip, frames lost, keyframes asked for), meant for
reporting what happened on a real test.

### Round three: what was really causing the tearing and the stale picture

The long-distance test before this one (a VPN in Bhutan to UK cellular) showed tearing, heavy
pixelation, and a picture that did not update when his screen was still. Re-reading the whole
pipeline found that the two worst causes were mechanisms added in earlier rounds, not the network:

- **Frames were thrown away after encoding.** A frame-rate cap (`FrameRateLimiter`, added to stop
  the relay's rate limit closing sessions) dropped encoded frames over the cap, and the backlog
  protection dropped them too. But every frame except a keyframe only describes what changed since
  the frame before it; the helper decoded each frame after a dropped one against the wrong picture
  — smearing and blockiness, during any movement, until the next keyframe up to two seconds later.
- **A "keepalive" rewound the picture.** When nothing had been sent for a second, the last
  *keyframe* was resent — but on a screen that changed and then went still, that keyframe was up to
  two seconds out of date, so the helper's picture snapped back to how the screen used to look,
  every second.

Both are gone (see above). Testing the replacement on a deliberately slowed connection then found
more, each fixed and re-measured:

- **25 seconds behind.** After a sudden drop from 2.5 Mbps to 400 kbps, the phone's own send queue
  stayed small while over a megabyte sat in the operating system's network buffers beneath it —
  the "bufferbloat" real mobile networks have too. Fixed by capping what may be sent but
  unconfirmed by the helper, in both bytes and age: 25 seconds became about 3 at worst, and around
  half a second once settled.
- **A keyframe storm.** Holding back frames and then asking for a keyframe after every hold-up
  meant a large keyframe every few seconds on the slowest links, congesting them again. Fixed by
  pausing capture instead: 13 to 20 keyframes a minute and a half became about 5.
- **Encoder overshoot.** While scrolling, the test encoder produced two to four times its target.
  Fixed by measuring and correcting for it, and by the byte cap above.
- **A deadlock after a lost connection.** Restarting the relay mid-session lost the frames in
  flight; the helper could never confirm them, and capture waited for them forever — the
  connection recovered but the picture never did. Fixed: a reconnect settles everything sent
  before it, and frames with no progress for three seconds are given up on. Verified by restarting
  the relay mid-session while scrolling: 13 frames lost, one keyframe asked for, picture back and
  correct within seconds.
- **Too slow to step down.** At the middle size, capture could be held back almost continuously
  for 17 seconds while the controller waited for delay evidence that paused capture was barely
  producing. Fixed by treating "held back most of the time" as congestion itself: 17 seconds
  became about 5.

**How this was tested**, since the last round's "throttled" test turned out not to be throttled
at all (an emulator's own throttling does not apply to the address a local relay lives at):
`relay-server/tools/netem-proxy.js` sits between one emulator and the relay and genuinely limits
bandwidth, with a queue behind it, delay and jitter. (Its first version reordered bytes under
jitter; the app correctly rejected the corrupted stream as tampering and ended the session.)
On a 350 kbps link with 180ms delay and 40ms jitter, scrolling Settings continuously for 40
seconds, after settling: 432 pixels across, 8 to 11 frames a second, a 400 to 700ms round trip
(of which 360ms is the link's own), queueing mostly under 250ms, no frames lost, no keyframes
needed, and every screenshot free of corruption; the picture stepped back up to the middle size
once scrolling stopped. On an unthrottled link, a still screen costs about 20 kbps.

**Still not verified on real phones**: every number here comes from two emulators, whose software
encoder overshoots far more than a phone's hardware one — real phones should do better, but that
is a prediction, not a measurement. H.265 was exercised end to end only by forcing the emulators'
software codecs at a small size (they are capped at 512x512); on real hardware it is untested.

### Earlier rounds, briefly

- A key that should have made every keyframe self-contained (`KEY_PREPEND_HEADER_TO_SYNC_FRAMES`)
  made `configure()` fail on a real encoder; the codec config is prepended by hand instead.
- A decoder created while a non-keyframe was arriving accepted input forever and decoded nothing;
  it now waits for a keyframe, and (this round) asks for one.
- The pointer ring's endless pulse, captured like anything on his screen, pushed the encoder's
  output past the relay's message limit and closed sessions the moment the helper pointed. The
  frame-rate cap added for that is the one that caused the tearing above; the ring now pulses
  twice and holds still, and the relay now limits bytes as well as messages, with room to spare.
- The first bitrate adapter only watched his phone's own send queue, so it could show "Good" while
  the helper's picture was seconds behind. It is replaced by `CongestionController` above.

## Known limitation: the ring can look briefly stale in the picture itself

The Stop button, banner and pointer ring are real content drawn on his screen, so they are part of
what the mirrored picture captures — the picture can keep showing a ring for a moment after "Clear
ring", until the next captured frame arrives. Excluding these windows from capture with
`FLAG_SECURE` blanks the *entire* captured frame to black on this Android version, so it is not
used (see `SessionOverlay.kt`). Switching from Point mode into "tap for them" clears the ring
automatically. The ring pulses twice to catch his eye and then holds still: an endlessly pulsing
ring made the encoder send a stream of frames that told the helper nothing new, using bandwidth
that mattered on a slow link.

## Not verified

- **The banking-app guard is best effort.** It recognises apps by package name, tested here only
  against a dummy app called "Test Bank". If he installs a finance app whose package name is not on
  the list and has no obvious word in it, taps would not be held back. Add its package name to
  `SecureAppPolicy.kt`. Screens that block screen capture already show as black to the helper.
- **Sideloaded installs and Android's "restricted settings"** — tested since on an Android 16
  emulator by installing the APK from the Files app (adb installs are exempt, which is why earlier
  tests never saw it). His Accessibility list shows Yattu Bhaa as "Controlled by Restricted
  Setting", and tapping it only says "App was denied access". The way through: Settings > Apps >
  Yattu Bhaa > the three dots (top right) > **Allow restricted settings** (the menu item only
  appears after that "denied" message has been seen once), then switch it on as normal. Measured:
  this is needed **once per install**. It stayed allowed through the service being switched off at
  session end, through a second session (which showed the normal switch), and through an update
  installed from an APK file; only uninstalling would bring it back. The emulator has no screen
  lock; a real phone may ask for his PIN at that step. Installing from the Files app also got a
  Google Play Protect "App scan recommended" prompt, on the first install and again on the update.
  A Play Store testing track avoids both.
- **Not yet tested against HDFC, Paytm, GPay or Kotak on his phone.** A real test did find banking
  apps that refuse to open while any accessibility service is switched on, which is why it is now
  switched off after every session (see above) — but whether his apps object to this app installed
  and idle, to the overlay permission being granted, or to an overlay showing, can only be found out
  on his actual phone. Some banking apps ignore touches or warn while any overlay is drawn over them.
- Tested on two Android 16 emulators, and since on two real phones over a real long-distance
  connection (one on a VPN in Bhutan, one on UK cellular data) — not yet on an older Motorola, or
  Android 14/15.
- **The video pipeline has been measured under genuinely constrained bandwidth, but only on
  emulators** (see "How this was tested" above). Real phones, a real mobile network, and hardware
  H.265 are all still to be tried. The helper's stats overlay is there so that test can say what
  actually happened.
- **Resuming a cut-short drag is not guaranteed on every launcher.** On the emulator's launcher a
  cut drag drops the icon back where it started, and the resumed press carries on as an ordinary
  swipe. On a real phone, the next test after this was added saw drags resume smoothly, with one
  exception in a long session. The stats overlay's "drags cut short, resumed" counts show what
  happened in any future case.
- **A still screen and H.265 on a real phone:** one real test's overlay showed 0 frames a second
  at the moment a drag froze, which looked like the phone's H.265 encoder ignoring the request to
  repeat the last frame on a still screen. The next test showed about 7 frames a second in the
  same situation, so it was specific to that moment, not the encoder.
- **Press, hold and drag, rotation, and the new Allow/Settings logic were tested on emulators
  only**: an app icon was picked up and moved on his home screen from the helper's picture, a hold
  without moving opened the icon's menu (a long-press), taps landed correctly in landscape, and
  rotating mid-session showed the new shape on the helper's screen in well under a second.
- The relay's `/join` page (which hands a tapped link to the app) is tested only as a served page,
  not through a real browser. The app's side of that hand-off was tested by sending the same
  `yattubhaa://pair?...` link directly.
- No release signing is set up.

## Known limits

- The relay can see that a room exists, when phones connect, how much data flows, and IP
  addresses. It cannot read content or the pairing secret.
- The pairing link passes through whatever chat app carries it. The six digit number is what stops
  a leaked link from being enough on its own.
- The Helper PIN only keeps Helper mode out of the way. It is not a security boundary.
- **Both phones need the same version of the app.** The video messages changed in the third round
  (every frame now carries a number and a send time, and the helper reports back); an older phone
  and a newer one will connect, but the helper will see no picture. There is no version check yet
  to say so on screen.
- **Screen sharing shows whatever is on his screen.** Screens that block capture (`FLAG_SECURE`,
  which most banking apps use) appear black, and nothing here tries to change that. But
  notifications, messages and one-time codes are visible. Ask him to choose "Share one app" in
  Android's dialog when the problem is in a single app, and to stop sharing when done.
- While Android's own Settings app is in front (including when he is sent there to switch the
  accessibility service on), Android hides every overlay, ours included; it comes back the moment
  he leaves Settings. Android's own red screen-sharing indicator stays throughout.
- A held drag on his phone plays back the helper's movement in steps as they arrive, so on a slow
  connection it can move in small hops rather than smoothly; it never lets go early, since his
  phone keeps the finger down between steps however long the next one takes. Quick swipes and
  scrolls are still sent whole once the finger lifts, so their speed survives any lag intact.

## What this app must not become

A remote-control tool aimed at an elderly user is exactly what scammers use in the "remote
access" frauds that banks' checks exist to stop. So: no hidden or silent start, no disguising
what the app is, no defeating other apps' security checks, no capture of secure screens, and no
unattended access.
