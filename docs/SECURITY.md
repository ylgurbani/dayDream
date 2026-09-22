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
  and ending a session that was actually about to recover by itself. Fixed with a 15 second grace
  period, but only *after* a session has been secured at least once — the ordinary "have not shown
  up yet" case (nobody connected yet) still says so immediately, with no invented delay.
- **A connection attempt never completes at all** (their phone is off, its app closed mid-connect,
  or the number was never entered because they never got that far). Previously this left the
  helper's screen saying "Waiting for Grandad" forever with no way to know it was not going to
  change on its own — confirmed by force-stopping the app on the other phone right as the helper
  connected, and confirmed it does not recover on its own (18+ seconds of watching, nothing
  changes; nothing in the code would ever have changed it). Fixed: after 25 seconds without
  connecting, it now says so and offers to try again.

## What each capability needs from Android

| Piece | Standing state on his phone | Ends when |
|-------|-----------------------------|-----------|
| Idle app | Installed; a paired-helper record; "display over other apps" allowed once | Never (a setting) |
| Get Help / connected | A relay connection while the screen is open | Session ends |
| Screen sharing | Foreground service + capture, granted per session by Android's dialog | Session ends or Stop |
| Pointer ring | Overlay window during a session only | Session ends |
| Remote tap/swipe (built, opt-in) | An Accessibility Service, offered after his first Yes and switched on by him in Settings once. Stays on until he taps **Turn off remote control** on his home screen. | He taps Turn off |

Remote tap and swipe is the one piece with a **standing state**, and I tried to avoid that: Android
will not let an app switch its own accessibility service on, and if the app switches it off between
sessions, Android forgets that he ever turned it on (tested), so every session would need the whole
Settings trip again. So it stays on once he has turned it on, and he gets a one-tap
**Turn off remote control** button on his home screen (shown only while it is on). That button also
removes it from Android's Accessibility list, with no visit to Settings. Any app that can check
whether an accessibility service is enabled can see this state, whatever the app is called, and
nothing here hides it.

**Android, not just this app's own button, can take the accessibility switch away** — confirmed by
clearing it directly rather than through "Turn off remote control", the same as if the system had
revoked it on its own. When he next says Yes to a request, this app used to wait a few seconds and
then just report "needs a setting" with no way to actually get to that setting, because it only
offered the Settings trip the first time ever, never on a return visit. Fixed: tapping Allow now
sends him to Settings whenever the switch is not actually on, first time or not — sending him there
carries no cost any more (see below), so there is no reason left to ever leave him stuck.

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
- The service does not read what is on the screen. It declares the ability to see windows only so it
  can read those package names. Android's own "full control" warning uses generic wording that
  sounds broader than that.
- It is off in the manifest, and is only offered once he has said yes to a request.
- Recovery from Blocked back to On happens the next time a Back/Home/Recents/Notifications message
  is applied (those always go through), or the next tap once he is genuinely out. If he leaves the
  secure app himself and the helper sends nothing further, the paused state does not clear on its
  own — there is no proactive recheck purely from the app changing on his screen, only from the
  next message actually being applied. Pressing one of the nav buttons is the reliable way out.

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

Screen sharing is a live H.264 video stream, encoded and decoded by the phone's own video
hardware (`MediaCodec`, surface-in/surface-out — the same technique scrcpy and other screen
mirroring tools use), not a series of still pictures. His screen draws straight into the
encoder's input surface, and the compressed picture draws straight onto the helper's screen, with
no bitmap ever copied through app code on either side. This replaced an earlier JPEG-per-frame
pipeline that had to read every frame back to a `Bitmap` and JPEG-encode it in software; the CPU
cost of that path capped both how large a picture could be sent and how often. See
`ScreenEncoder.kt` and `VideoDecoder.kt`.

Two things were found the hard way while building this, both now handled without depending on
whichever turns out to be true on a given phone:

- **A key that should have worked did not, on every device.** `MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES`
  is the documented way to ask the encoder to make every keyframe self-contained. On one real
  encoder (an emulator's software AVC encoder) asking for it made `configure()` fail outright, not
  just get ignored. Fixed by not asking for it at all: `ScreenEncoder` instead caches the SPS/PPS
  bytes the encoder emits once at the start and prepends them, by hand, to every keyframe itself —
  the same end result, achieved a way that does not depend on that key being supported anywhere.
- **A decoder that never decoded anything, and never said why.** Because the picture is a
  continuous stream, not one request per frame, the helper's decoder can come into existence
  (its `SurfaceView` ready, the picture's size known) independently of which particular chunk
  happens to be arriving at that moment. Built and tested wrong the first time: a decoder created
  right as an ordinary (non-keyframe) chunk arrived would happily accept it as its first input,
  even though a delta frame only means anything relative to a keyframe the decoder has already
  seen — it accepted chunk after chunk, forever, and decoded nothing, ever, with no error from
  Android at any point. Caught by checking, not assuming: logging exactly what bytes the encoder
  sent and what bytes the decoder fed the codec, and finding the two did not start at the same
  place. Fixed with an explicit "still waiting for a keyframe" flag on the decoder, cleared only
  once one has actually been fed to the current codec instance — see `VideoDecoder.submit()`.

A screen that never changes produces no new video frames at all (this is normal for any
compositor-driven capture, not a bug), so the last keyframe is resent every few seconds regardless
— both so a freshly connected or reconnected helper is never left looking at nothing, and as a
safety net against a chunk lost to a slow connection (`ScreenShareService`'s keepalive timer).

## Known limitation: the ring can look briefly stale in the picture itself

The Stop button, banner and pointer ring are real content drawn on his screen, so they are part of
what the mirrored picture captures — including the ring, which means the picture can keep showing
it for a moment after "Clear ring" until the next captured frame reaches the helper. Tried excluding
these overlay windows from capture with `FLAG_SECURE`: on this Android version that blanks the
*entire* captured frame to black, not just that window's own pixels, so it was reverted (see
`SessionOverlay.kt`). What is fixed: the specific case where switching from Point mode into "tap for
them" left a ring stuck on his screen with no way to clear it at all — that ring is now cleared
automatically the moment you switch. What remains is only the brief lag between clearing a ring and
the next picture confirming it. The move to a live video pipeline (above) should make this lag
considerably shorter in practice — a captured frame no longer waits on a software JPEG encode
before it can be sent — but this has not been directly measured, only reasoned about from the
architecture, so it is recorded here as expected rather than confirmed.

## Not verified

- **The banking-app guard is best effort.** It recognises apps by package name, tested here only
  against a dummy app called "Test Bank". If he installs a finance app whose package name is not on
  the list and has no obvious word in it, taps would not be held back. Add its package name to
  `SecureAppPolicy.kt`. Screens that block screen capture already show as black to the helper.
- **Sideloaded installs and Android's "restricted settings".** On Android 13 and up, an app installed
  from outside a store may have its accessibility switch greyed out until you open Settings > Apps >
  Yattu Bhaa > the three dots > **Allow restricted settings**. I could not test this (adb installs are
  not treated that way). A Play Store testing track avoids it.
- **Nobody has tested this against HDFC, Paytm, GPay or Kotak.** Whether any of them object to
  this app installed and idle, to the overlay permission being granted, or to an overlay showing,
  can only be found out on his actual phone. Some banking apps ignore touches or warn while any
  overlay is drawn over them.
- Tested only on Android 16 emulators (two instances). Not yet on a real phone, an older
  Motorola, or Android 14/15.
- **The video pipeline's actual latency has not been measured, only reasoned about.** Two
  emulators on the same machine say nothing reliable about real network conditions, real encode
  time on real (not emulated) video hardware, or how a real phone's thermal/power state affects a
  sustained encode. What was verified is correctness — the picture, pointer ring, and remote tap
  and swipe all work through the new pipeline, checked by watching the actual bytes the encoder
  produced and the decoder consumed, not just by eye — not that it is fast on a real phone over a
  real connection.
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
- **Screen sharing shows whatever is on his screen.** Screens that block capture (`FLAG_SECURE`,
  which most banking apps use) appear black, and nothing here tries to change that. But
  notifications, messages and one-time codes are visible. Ask him to choose "Share one app" in
  Android's dialog when the problem is in a single app, and to stop sharing when done.
- Rotating the phone while sharing is not handled yet.
- While he is sent to Android's accessibility screens, our overlay (Stop button, pointer) is lifted,
  because Android disables its own "Allow" button while anything is drawn over it. It comes back once
  the switch is on, or after two minutes. Android's own red screen-sharing timer stays throughout.
- Force-stopping the app (Settings > Apps > Force stop) makes Android switch its accessibility
  service off, so he would have to turn it on again. I did not test what swiping the app away from
  recent apps does.
- Long swipes and fast flicks are approximated: the path is a straight line over the time you took.

## What this app must not become

A remote-control tool aimed at an elderly user is exactly what scammers use in the "remote
access" frauds that banks' checks exist to stop. So: no hidden or silent start, no disguising
what the app is, no defeating other apps' security checks, no capture of secure screens, and no
unattended access.
