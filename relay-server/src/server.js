// Yattu Bhaa relay: a blind pipe between the two paired phones in a "room".
//
// It never sees plaintext. Everything the phones send each other is encrypted end to end
// before it reaches this server; the relay only decides who can talk to whom and forwards
// the bytes. A room is named by an unguessable id that only the two paired phones can
// compute, and holds at most one "needy" socket (the person being helped) and one "helper".
//
// Production must run behind TLS (wss://). Fly.io, Render, Railway and Caddy/nginx all
// terminate TLS for you; do not expose this process on plain ws:// to the internet.

import http from 'node:http';
import { pathToFileURL } from 'node:url';
import { WebSocketServer } from 'ws';

const ROOM_ID_RE = /^[A-Za-z0-9_-]{22,128}$/;
const ROLES = new Set(['needy', 'helper']);

// The page a pairing link opens. WhatsApp only makes http(s) links tappable, so the helper
// sends https://<relay>/join#k=...; this page hands that over to the app. The pairing secret
// lives in the URL fragment, which browsers never send to a server, so the relay cannot see it.
const JOIN_PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Yattu Bhaa</title>
<style>
  body{font:28px/1.45 system-ui,sans-serif;margin:0;padding:24px;background:#fff;color:#000}
  h1{font-size:40px}
  a.btn{display:block;background:#0a3d91;color:#fff;text-align:center;padding:32px 16px;
        border-radius:20px;font-weight:bold;font-size:34px;text-decoration:none;margin-top:32px}
</style></head><body>
<h1>Yattu Bhaa</h1>
<p id="msg">Opening the app…</p>
<a class="btn" id="open" href="#">Open Yattu Bhaa</a>
<p id="hint" style="display:none">If nothing happens, the app is not installed on this phone yet.</p>
<script>
(function () {
  var frag = location.hash.slice(1);
  var msg = document.getElementById('msg');
  var btn = document.getElementById('open');
  if (!frag) {
    msg.textContent = 'This link is incomplete. Please ask your family member to send it again.';
    btn.style.display = 'none';
    return;
  }
  var intent = 'intent://pair?' + frag + '#Intent;scheme=yattubhaa;package=com.yattubhaa.app;end';
  btn.href = intent;
  setTimeout(function () { location.replace(intent); }, 300);
  setTimeout(function () {
    msg.textContent = 'Tap the big blue button.';
    document.getElementById('hint').style.display = 'block';
  }, 2000);
})();
</script></body></html>`;

export function createRelay(overrides = {}) {
  const cfg = {
    port: Number(process.env.PORT || 8787),
    host: process.env.HOST || '0.0.0.0',
    trustProxy: process.env.TRUST_PROXY === '1',
    maxMessageBytes: 2 * 1024 * 1024, // one JPEG screen frame, generously
    joinTimeoutMs: 5000,
    heartbeatMs: 30000,
    maxRooms: 1000,
    maxConnectionsPerIp: 10,
    maxMessagesPerSecond: 100,
    maxBufferedBytes: 4 * 1024 * 1024, // drop frames for a slow peer instead of buffering forever
    ...overrides,
  };

  /** @type {Map<string, {needy: import('ws').WebSocket|null, helper: import('ws').WebSocket|null}>} */
  const rooms = new Map();
  /** @type {Map<string, number>} */
  const connectionsByIp = new Map();

  const server = http.createServer((req, res) => {
    const path = (req.url || '').split(/[?#]/)[0];
    if (path === '/healthz') {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('ok');
      return;
    }
    if (path === '/join') {
      res.writeHead(200, {
        'content-type': 'text/html; charset=utf-8',
        'cache-control': 'no-store',
        'referrer-policy': 'no-referrer',
        'content-security-policy': "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'",
      });
      res.end(JOIN_PAGE);
      return;
    }
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: cfg.maxMessageBytes });

  const clientIp = (req) =>
    (cfg.trustProxy && String(req.headers['x-forwarded-for'] || '').split(',')[0].trim()) ||
    req.socket.remoteAddress ||
    'unknown';

  server.on('upgrade', (req, socket, head) => {
    const match = /^\/room\/([^/?#]+)$/.exec((req.url || '').split('?')[0]);
    if (!match || !ROOM_ID_RE.test(match[1])) {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }
    const ip = clientIp(req);
    if ((connectionsByIp.get(ip) || 0) >= cfg.maxConnectionsPerIp) {
      socket.write('HTTP/1.1 429 Too Many Requests\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => onConnection(ws, match[1], ip));
  });

  function onConnection(ws, roomId, ip) {
    connectionsByIp.set(ip, (connectionsByIp.get(ip) || 0) + 1);
    let role = null;
    let windowStart = Date.now();
    let windowCount = 0;
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    const joinTimer = setTimeout(() => ws.close(4001, 'join timeout'), cfg.joinTimeoutMs);

    ws.on('message', (data, isBinary) => {
      const now = Date.now();
      if (now - windowStart >= 1000) { windowStart = now; windowCount = 0; }
      if (++windowCount > cfg.maxMessagesPerSecond) { ws.close(1008, 'rate limit'); return; }

      if (!role) {
        // The first message must be a text join. Anything else is a protocol error.
        if (isBinary) { ws.close(4002, 'expected join'); return; }
        let msg;
        try { msg = JSON.parse(data.toString()); } catch { ws.close(4002, 'bad join'); return; }
        if (msg?.type !== 'join' || !ROLES.has(msg.role)) { ws.close(4002, 'bad join'); return; }
        if (!rooms.has(roomId) && rooms.size >= cfg.maxRooms) { ws.close(1013, 'busy'); return; }
        clearTimeout(joinTimer);
        role = msg.role;
        joinRoom(roomId, role, ws);
        return;
      }

      if (!isBinary) return; // after joining only opaque binary frames are relayed
      const peer = peerOf(roomId, role);
      if (!peer || peer.readyState !== peer.OPEN) return;
      if (peer.bufferedAmount > cfg.maxBufferedBytes) return; // slow peer: drop, don't buffer
      peer.send(data, { binary: true });
    });

    ws.on('close', () => {
      clearTimeout(joinTimer);
      const n = (connectionsByIp.get(ip) || 1) - 1;
      if (n <= 0) connectionsByIp.delete(ip); else connectionsByIp.set(ip, n);
      if (role) leaveRoom(roomId, role, ws);
    });
    ws.on('error', () => ws.terminate());
  }

  function peerOf(roomId, role) {
    const room = rooms.get(roomId);
    if (!room) return null;
    return role === 'needy' ? room.helper : room.needy;
  }

  function sendPresence(ws, present) {
    if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify({ type: 'peer', present }));
  }

  function joinRoom(roomId, role, ws) {
    let room = rooms.get(roomId);
    if (!room) { room = { needy: null, helper: null }; rooms.set(roomId, room); }
    const previous = room[role];
    room[role] = ws;
    // Last one in wins: a phone reconnecting after a network blip replaces its stale socket.
    if (previous && previous !== ws) previous.close(4000, 'replaced');
    const peer = role === 'needy' ? room.helper : room.needy;
    sendPresence(ws, !!peer && peer.readyState === peer.OPEN);
    sendPresence(peer, true);
  }

  function leaveRoom(roomId, role, ws) {
    const room = rooms.get(roomId);
    if (!room || room[role] !== ws) return; // already replaced by a newer socket
    room[role] = null;
    sendPresence(role === 'needy' ? room.helper : room.needy, false);
    if (!room.needy && !room.helper) rooms.delete(roomId);
  }

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) { ws.terminate(); continue; }
      ws.isAlive = false;
      ws.ping();
    }
  }, cfg.heartbeatMs);
  heartbeat.unref();

  return {
    server,
    rooms,
    listen: () => new Promise((resolve) => server.listen(cfg.port, cfg.host, () => resolve(server.address()))),
    close: () => new Promise((resolve) => {
      clearInterval(heartbeat);
      for (const ws of wss.clients) ws.terminate();
      server.close(() => resolve());
    }),
  };
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  const relay = createRelay();
  const addr = await relay.listen();
  console.log(`Yattu Bhaa relay listening on ${addr.address}:${addr.port}`);
}
