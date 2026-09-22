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
compositor-driven capture, not a bug), so the last keyframe is resent whenever nothing new has
gone out for a second (`ScreenShareService`'s keepalive timer) — both so a freshly connected or
reconnected helper is never left looking at nothing for long, and as a safety net against a chunk
lost to a slow connection. Tightened from an initial 3 seconds after real-device testing (two
phones on different networks, one deliberately left running for two minutes first to rule out a
cold relay) found the wait before the first picture appeared noticeably long, and still does on a
real network even with that ruled out. Since the exact remaining cause (real hardware codec
warm-up, real network conditions, or something else) is not yet pinned down, a `SharingStarted`
message is now sent the instant sharing begins, well before any picture could possibly have
arrived, so the helper can show "connecting" rather than a screen that gives no sign anything is
happening — improving what the wait *feels* like even where the wait itself has not been
shortened further.

### The bitrate adapts to the connection, and the helper can see how it is doing

The target bitrate is not fixed. `BitrateAdapter` samples how many bytes of picture are queued on
his phone but not yet actually sent (`RelayClient.backlogBytes`, via
`NeedySession.outgoingBacklogBytes`) roughly every two seconds, and adjusts the encoder's live
target the same way TCP congestion control does: a queue backing up drops the bitrate
immediately, by a set fraction; a queue that has stayed empty for a few samples in a row raises it
again, one small step at a time. `MediaCodec` accepts a bitrate change on a running encoder
directly (`ScreenEncoder.setBitrate`), so this never needs to reconfigure or recreate the encoder,
the virtual display, or the helper's decoder — only the target number changes, live. Deliberately
does not touch resolution or frame rate: those would need exactly that heavier reconstruction, for
a smaller and less certain gain than simply asking for fewer (or more) bits per frame from the
picture already flowing. Bounded between 400 kbps and 2.5 Mbps; verified on an emulator (imperfect
but real evidence, since it involves genuine encode, transmit and decode, not a simulated number):
a real burst of frames at the start of a session backed the queue up, dropped the bitrate, and
reported it — visibly, live, on the helper's own screen (see below) — before climbing back to the
ceiling on its own once the queue had stayed empty for a few seconds.

The same signal drives a small, quiet **connection-quality indicator** next to the status line on
the helper's screen only — a colored dot and a word (Good, Fair, Poor), not a number or a graph.
Deliberately not shown to him: he already cannot see technical detail comfortably, and a raw
quality readout would be one more confusing thing on a screen kept as simple as possible on
purpose. The helper is the technical user here, and the one who can actually act on knowing the
link is struggling (wait, or suggest moving closer to the router) rather than assume the app itself
is broken.

### A real, disruptive bug: the encoder's own output rate had no ceiling

Real-world testing with two phones genuinely far apart found the session disconnecting roughly
every ten seconds, and doing so almost every time the helper pointed at something. Root cause,
found by re-reading the two places that actually decide this rather than guessing: a surface-input
encoder has no frame-rate limit of its own — `MediaFormat.KEY_FRAME_RATE` is only a hint used for
bitrate math, not an enforced cap, so the encoder processes every frame the compositor draws to its
input surface, however often that happens to be. Most of the time a phone screen is close enough
to still that this does not matter. But the pointer ring's own pulsing animation is drawn on his
screen too, so it is captured like anything else — while it is on, the compositor can be redrawing
at the display's full refresh rate, and the encoder followed it, producing far more video chunks
per second than usual, each one its own message to the relay. The relay caps how many messages one
connection may send per second and closes it over the limit — a real, useful defence in general,
but tuned for the old JPEG pipeline's own explicit ~4fps throttle, never revisited when that
pipeline was replaced with one that has no throttle of its own. The two together meant: point at
something, the ring starts pulsing, the encoder's real output rate spikes, the relay's limit is hit,
the connection is closed — which is exactly "almost always crashes the connection immediately."

Fixed at the source, not by papering over it at the limit: `ScreenEncoder` now runs every delta
(non-keyframe) chunk past a `FrameRateLimiter` before forwarding it, capping the real output rate
to about 25fps — smoother than the ~4fps the old pipeline ran at, comfortably under what the relay
allows, keyframes never held back. Verified live, not just reasoned about: with the fix in place,
tapping to point roughly forty times over half a minute — deliberately harder and faster than
normal use, to make sure the old failure would have shown up if the fix had not actually worked —
produced zero rate-limit closes (the relay now logs this event by name specifically because it
was hard to diagnose without that), and the session, the picture and the ring all stayed correct
throughout. The relay's own limit was also raised a little as a margin, and its heartbeat (how
long it waits without a reply before deciding a connection is dead) was eased back from an
earlier, over-tightened value — 10 seconds turned out to be too little slack for a real
connection's own real latency, especially while it is also carrying active video, and it does
not need to be that tight now that `ScreenShareService.onTaskRemoved` handles the common "app
closed" case directly, without depending on this heartbeat at all.

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
  service off, so he would have to turn it on again.
- A drag still cannot pick something up and hold it before moving — real drag-and-drop reordering
  (long-press an item, then move it without lifting) needs a single continuous gesture from press
  to release, and the protocol only has a discrete long-press and a discrete drag, not a way to
  chain the two into one unbroken touch. What the drag *does* carry now is the whole path a finger
  actually took, not just where it started and ended (see `GESTURE_PATH` in `Protocol.kt`), which
  is what ordinary swipes, scrolls and slider drags rely on — that part is fixed, verified by
  dragging on the mirrored picture and watching a long settings list actually scroll through it,
  not just jump.

## What this app must not become

A remote-control tool aimed at an elderly user is exactly what scammers use in the "remote
access" frauds that banks' checks exist to stop. So: no hidden or silent start, no disguising
what the app is, no defeating other apps' security checks, no capture of secure screens, and no
unattended access.
