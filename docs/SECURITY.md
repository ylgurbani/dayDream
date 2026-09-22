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
