# Yattu Bhaa relay

A small blind relay: it pipes encrypted bytes between the two paired phones and cannot read
them. It also serves the `/join` page that a pairing link opens.

## Run it locally

```bash
npm install
npm start          # listens on :8787
npm test
```

The debug build of the Android app talks to `ws://10.0.2.2:8787` from the emulator
(10.0.2.2 is the emulator's name for your computer).

## Test on a slow, laggy connection

An emulator's own network throttling does not apply to 10.0.2.2, so it cannot slow down the
traffic to a local relay. `tools/netem-proxy.js` does: a small proxy with a bandwidth cap (and a
queue behind it, like a real slow link), delay and jitter, no dependencies.

```bash
npm start                                           # the relay, on :8787
node tools/netem-proxy.js --listen 8788 --target 127.0.0.1:8787 \
    --up-kbps 350 --down-kbps 1500 --delay-ms 180 --jitter-ms 40
curl -X POST localhost:8790 -d '{"upKbps": 120}'    # change it while a session runs
```

Give only the phone you want to slow down the relay address `ws://10.0.2.2:8788` (edit the `r=`
in the pairing link you deliver to it), and leave the other on `:8787`. "up" is traffic from
that phone. It prints the real throughput every five seconds.

## Deploy it

It must run behind TLS, so the phones use `wss://` and the pairing link is `https://`.
Any host that terminates TLS for you works. Set `TRUST_PROXY=1` if the host puts a proxy in
front, so the per-IP connection limit sees real client addresses.

Nothing here is deployed yet. You would need to create an account with a host, which I have
not done for you. Rough options:

- **Fly.io**: `fly launch` from this folder (it picks up the Dockerfile), then `fly deploy`.
- **Render / Railway**: create a Web Service from this folder using the Dockerfile. On Render,
  choose the Frankfurt region (see docs/SETUP-RELAY.md): it is fixed once the service exists.
- **Your own VPS**: run `node src/server.js` under systemd, with Caddy in front for TLS.

The relay keeps everything in memory. Restarting it drops live sessions, and nothing is
stored on disk.

## What the relay can and cannot see

It can see: that a room exists, when the two phones connect, roughly how much data flows, and
the IP addresses that connect. It cannot see: the pairing secret, the session code, any screen
content, or any input. Those are encrypted between the phones before they reach it.

Its log only ever names a room by a short hash of the room id, never the id itself: the id stays
the same for as long as two phones are paired, so logging it would link all their sessions.

## Limits it enforces

Per connection: 300 messages a second, and 1MB a second of data with a 6MB burst allowance
(the app's highest video bitrate is about 0.3MB a second); going over either closes the
connection and logs which limit was hit. A phone that cannot keep up with what is being sent to
it gets frames dropped once 1MB is waiting for it, rather than an ever-growing queue; the app
notices the missing frame and asks for a fresh keyframe.
