import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { createRelay } from '../src/server.js';

const ROOM = 'A'.repeat(43); // shaped like a base64url SHA-256
const OTHER_ROOM = 'B'.repeat(43);

let relay;
let base;

before(async () => {
  relay = createRelay({ port: 0, host: '127.0.0.1', joinTimeoutMs: 300, maxMessagesPerSecond: 50 });
  const addr = await relay.listen();
  base = `ws://127.0.0.1:${addr.port}`;
});

after(() => relay.close());

/** Opens a socket, joins as `role`, and collects everything it receives. */
function connect(room, role, { join = true } = {}) {
  const ws = new WebSocket(`${base}/room/${room}`);
  const inbox = [];
  const waiters = [];
  ws.on('message', (data, isBinary) => {
    const item = isBinary ? { binary: Buffer.from(data) } : { json: JSON.parse(data.toString()) };
    const w = waiters.shift();
    if (w) w(item); else inbox.push(item);
  });
  ws.next = () => new Promise((resolve, reject) => {
    if (inbox.length) return resolve(inbox.shift());
    const t = setTimeout(() => reject(new Error('timed out waiting for a message')), 1500);
    waiters.push((m) => { clearTimeout(t); resolve(m); });
  });
  ws.closed = new Promise((resolve) => ws.on('close', (code) => resolve(code)));
  const opened = new Promise((resolve, reject) => { ws.on('open', resolve); ws.on('error', reject); });
  return opened.then(() => {
    if (join) ws.send(JSON.stringify({ type: 'join', role }));
    return ws;
  });
}

test('forwards opaque bytes in both directions between the two phones', async () => {
  const needy = await connect(ROOM, 'needy');
  assert.deepEqual((await needy.next()).json, { type: 'peer', present: false });
  const helper = await connect(ROOM, 'helper');
  assert.deepEqual((await helper.next()).json, { type: 'peer', present: true });
  assert.deepEqual((await needy.next()).json, { type: 'peer', present: true });

  const frame = Buffer.from([1, 2, 3, 4, 250, 251]);
  needy.send(frame);
  assert.deepEqual((await helper.next()).binary, frame);
  helper.send(Buffer.from([9, 9]));
  assert.deepEqual((await needy.next()).binary, Buffer.from([9, 9]));

  needy.close(); helper.close();
  await Promise.all([needy.closed, helper.closed]);
});

test('rooms are isolated from each other', async () => {
  const a1 = await connect(ROOM, 'needy'); await a1.next();
  const a2 = await connect(ROOM, 'helper'); await a2.next(); await a1.next();
  const b1 = await connect(OTHER_ROOM, 'needy'); await b1.next();
  const b2 = await connect(OTHER_ROOM, 'helper'); await b2.next(); await b1.next();

  a1.send(Buffer.from('for room A only'));
  assert.equal((await a2.next()).binary.toString(), 'for room A only');
  await assert.rejects(b2.next(), /timed out/); // nothing leaks across

  for (const ws of [a1, a2, b1, b2]) ws.close();
  await Promise.all([a1.closed, a2.closed, b1.closed, b2.closed]);
});

test('tells the remaining phone when its peer leaves', async () => {
  const needy = await connect(ROOM, 'needy'); await needy.next();
  const helper = await connect(ROOM, 'helper'); await helper.next(); await needy.next();
  helper.close();
  assert.deepEqual((await needy.next()).json, { type: 'peer', present: false });
  needy.close();
  await needy.closed;
});

test('a reconnecting phone replaces its own stale socket', async () => {
  const first = await connect(ROOM, 'needy'); await first.next();
  const second = await connect(ROOM, 'needy'); await second.next();
  assert.equal(await first.closed, 4000);
  second.close();
  await second.closed;
});

test('drops frames when the peer is not there instead of queueing them', async () => {
  const needy = await connect(ROOM, 'needy'); await needy.next();
  needy.send(Buffer.from('nobody home'));
  const helper = await connect(ROOM, 'helper');
  assert.deepEqual((await helper.next()).json, { type: 'peer', present: true });
  await assert.rejects(helper.next(), /timed out/); // the earlier frame was not replayed
  needy.close(); helper.close();
  await Promise.all([needy.closed, helper.closed]);
});

test('rejects room ids that are not shaped like an unguessable id', async () => {
  const ws = new WebSocket(`${base}/room/short`);
  const result = await new Promise((resolve) => {
    ws.on('unexpected-response', (_req, res) => resolve(res.statusCode));
    ws.on('error', () => {});
  });
  assert.equal(result, 404);
});

test('closes a socket that never joins, or joins badly', async () => {
  const silent = await connect(ROOM, 'needy', { join: false });
  assert.equal(await silent.closed, 4001);

  const wrongRole = await connect(ROOM, 'needy', { join: false });
  wrongRole.send(JSON.stringify({ type: 'join', role: 'admin' }));
  assert.equal(await wrongRole.closed, 4002);

  const binaryFirst = await connect(ROOM, 'needy', { join: false });
  binaryFirst.send(Buffer.from([1, 2, 3]));
  assert.equal(await binaryFirst.closed, 4002);
});

test('disconnects a socket that floods messages', async () => {
  const needy = await connect(ROOM, 'needy'); await needy.next();
  const helper = await connect(ROOM, 'helper'); await helper.next(); await needy.next();
  for (let i = 0; i < 80; i++) needy.send(Buffer.from([i]));
  assert.equal(await needy.closed, 1008);
  helper.close();
  await helper.closed;
});

test('serves the pairing landing page without caching or referrer leaks', async () => {
  const addr = relay.server.address();
  const res = await fetch(`http://127.0.0.1:${addr.port}/join`);
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type'), /text\/html/);
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal(res.headers.get('referrer-policy'), 'no-referrer');
  assert.match(await res.text(), /intent:\/\/pair\?/);
});

test('health check answers', async () => {
  const addr = relay.server.address();
  const res = await fetch(`http://127.0.0.1:${addr.port}/healthz`);
  assert.equal(res.status, 200);
  assert.equal(await res.text(), 'ok');
});
