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

## Deploy it

It must run behind TLS, so the phones use `wss://` and the pairing link is `https://`.
Any host that terminates TLS for you works. Set `TRUST_PROXY=1` if the host puts a proxy in
front, so the per-IP connection limit sees real client addresses.

Nothing here is deployed yet. You would need to create an account with a host, which I have
not done for you. Rough options:

- **Fly.io**: `fly launch` from this folder (it picks up the Dockerfile), then `fly deploy`.
- **Render / Railway**: create a Web Service from this folder using the Dockerfile.
- **Your own VPS**: run `node src/server.js` under systemd, with Caddy in front for TLS.

The relay keeps everything in memory. Restarting it drops live sessions, and nothing is
stored on disk.

## What the relay can and cannot see

It can see: that a room exists, when the two phones connect, roughly how much data flows, and
the IP addresses that connect. It cannot see: the pairing secret, the session code, any screen
content, or any input. Those are encrypted between the phones before they reach it.
