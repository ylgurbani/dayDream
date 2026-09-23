#!/usr/bin/env node
// A TCP proxy that makes a connection slow and laggy on purpose, for testing the app against a
// connection like a real long-distance one without leaving the desk.
//
// Why this exists: an emulator's own network throttling (`adb emu network speed/delay`) does not
// apply to 10.0.2.2, the address an emulator uses to reach the computer it runs on - which is
// exactly where a local relay lives. An earlier "throttled" test was quietly not throttled at all
// because of that. Putting this proxy between one emulator and the relay shapes the traffic that
// actually matters, measurably.
//
// Each direction gets a bandwidth cap with a queue behind it (like a real slow link: data waits
// in a buffer, and once that is full the sender is held back through TCP itself), plus a one-way
// delay with optional jitter. Order is always preserved - this is one TCP stream, so bytes are
// never dropped or reordered, only delayed, the same as a real congested connection looks to TCP.
//
//   node tools/netem-proxy.js --listen 8788 --target 127.0.0.1:8787 \
//       --up-kbps 300 --down-kbps 2000 --delay-ms 150 --jitter-ms 30 --queue-kb 64
//
// "up" is from whatever connects to this proxy towards the target (for the phone sharing its
// screen, that is its video). 0 kbps means no cap. The settings can be changed while it runs:
//
//   curl localhost:8790                                   # show the current settings
//   curl -X POST localhost:8790 -d '{"upKbps": 120}'      # change some of them
//
// Point one emulator at it by giving that phone a relay address of ws://10.0.2.2:8788 (the
// pairing link's r= parameter) while the other keeps ws://10.0.2.2:8787.

import http from 'node:http';
import net from 'node:net';

const cfg = {
  listen: 8788,
  target: '127.0.0.1:8787',
  control: 8790,
  upKbps: 0,
  downKbps: 0,
  delayMs: 0,
  jitterMs: 0,
  queueKb: 64,
};

const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i += 2) {
  const key = argv[i].replace(/^--/, '').replace(/-([a-z])/g, (_, c) => c.toUpperCase());
  if (!(key in cfg)) throw new Error(`unknown option ${argv[i]}`);
  cfg[key] = typeof cfg[key] === 'number' ? Number(argv[i + 1]) : argv[i + 1];
}
const [targetHost, targetPort] = cfg.target.split(':');

const TICK_MS = 10;
const totals = { up: 0, down: 0 };

/** One direction of one connection: a rate-limited queue in front of a delay line. */
class Shaper {
  constructor(src, dst, dir) {
    this.src = src;
    this.dst = dst;
    this.dir = dir;
    this.queue = [];
    this.queued = 0;
    this.allowance = 0;
    this.lastDeliverAt = 0;
    this.delayLine = []; // [{at, piece}], always in order; drained by tick()
    this.paused = false;
    src.on('data', (chunk) => this.push(chunk));
  }

  rate() {
    const kbps = this.dir === 'up' ? cfg.upKbps : cfg.downKbps;
    return kbps > 0 ? (kbps * 1000) / 8 : Infinity; // bytes per second
  }

  push(chunk) {
    this.queue.push(chunk);
    this.queued += chunk.length;
    if (!this.paused && this.queued > cfg.queueKb * 1024) {
      this.paused = true;
      this.src.pause(); // the queue is full: hold the sender back, as a real link's buffer would
    }
    if (this.rate() === Infinity) this.tick(TICK_MS);
  }

  tick(dtMs) {
    const rate = this.rate();
    // Allowance can build up briefly (a packet or two), not into a big burst after a quiet spell.
    this.allowance = rate === Infinity ? Infinity : Math.min(this.allowance + (rate * dtMs) / 1000, Math.max(1500, rate * 0.02));
    while (this.queue.length && this.allowance > 0) {
      const head = this.queue[0];
      const take = Math.min(head.length, this.allowance === Infinity ? head.length : Math.floor(this.allowance) || 1);
      const piece = take === head.length ? this.queue.shift() : head.subarray(0, take);
      if (take < head.length) this.queue[0] = head.subarray(take);
      this.queued -= take;
      if (this.allowance !== Infinity) this.allowance -= take;
      totals[this.dir] += take;
      this.deliver(piece);
    }
    if (this.paused && this.queued < (cfg.queueKb * 1024) / 2) {
      this.paused = false;
      this.src.resume();
    }
    this.flush();
  }

  // One ordered line per direction, never a timer per piece: Node does not promise to fire two
  // timers due in the same millisecond in the order they were set, and an earlier version of
  // this proxy reordered bytes that way - which the app rightly rejected as tampering.
  flush() {
    const now = Date.now();
    while (this.delayLine.length && this.delayLine[0].at <= now && !this.dst.destroyed) {
      this.dst.write(this.delayLine.shift().piece);
    }
  }

  deliver(piece) {
    const now = Date.now();
    const jitter = cfg.jitterMs > 0 ? Math.random() * cfg.jitterMs : 0;
    // Never earlier than the piece before it: a TCP stream stays in order however it is delayed.
    const at = Math.max(this.lastDeliverAt, now + cfg.delayMs + jitter);
    this.lastDeliverAt = at;
    this.delayLine.push({ at, piece });
    if (at <= now) this.flush();
  }
}

const shapers = new Set();

net.createServer((client) => {
  const upstream = net.connect(Number(targetPort), targetHost);
  client.setNoDelay(true);
  upstream.setNoDelay(true);
  const up = new Shaper(client, upstream, 'up');
  const down = new Shaper(upstream, client, 'down');
  shapers.add(up); shapers.add(down);
  const close = () => {
    shapers.delete(up); shapers.delete(down);
    client.destroy(); upstream.destroy();
  };
  client.on('close', close); upstream.on('close', close);
  client.on('error', close); upstream.on('error', close);
}).listen(cfg.listen, () => console.log(`netem-proxy :${cfg.listen} -> ${cfg.target}`, JSON.stringify(cfg)));

let lastTick = Date.now();
setInterval(() => {
  const now = Date.now();
  for (const s of shapers) s.tick(now - lastTick); // timers run late; use the real elapsed time
  lastTick = now;
}, TICK_MS).unref();

let last = { up: 0, down: 0 };
setInterval(() => {
  const queued = [...shapers].reduce((acc, s) => { acc[s.dir] += s.queued; return acc; }, { up: 0, down: 0 });
  console.log(
    `up ${(((totals.up - last.up) * 8) / 5000).toFixed(0)} kbps (queued ${(queued.up / 1024).toFixed(0)}KB)` +
    ` | down ${(((totals.down - last.down) * 8) / 5000).toFixed(0)} kbps (queued ${(queued.down / 1024).toFixed(0)}KB)` +
    ` | ${shapers.size / 2} connection(s)`,
  );
  last = { ...totals };
}, 5000).unref();

http.createServer((req, res) => {
  let body = '';
  req.on('data', (d) => { body += d; });
  req.on('end', () => {
    if (req.method === 'POST') {
      try {
        const patch = JSON.parse(body || '{}');
        for (const k of Object.keys(patch)) if (typeof cfg[k] === 'number') cfg[k] = Number(patch[k]);
        console.log('settings now', JSON.stringify(cfg));
      } catch (e) {
        res.writeHead(400); res.end(String(e)); return;
      }
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(cfg) + '\n');
  });
}).listen(cfg.control, '127.0.0.1');
