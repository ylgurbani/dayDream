# Setting up the relay, in plain terms

## What the relay is

Your phone and your grandad's phone can't call each other directly (home wifi hides them). So they
both connect **out** to a small program on the internet, and that program passes messages between
them. That program is the relay.

Think of a post office box that both of you can reach. The relay carries the parcels, but the
parcels are locked with a key only your two phones have, so the relay can't open them. It sees
that a parcel went through, not what was inside.

"Secured" means two things, and you already have one of them:

1. **The lock on the parcel** (end-to-end encryption). Built into the app already.
2. **A safe road to the post office** (`https` / `wss`). This comes from where you host the relay:
   good hosts give it to you automatically. The release app refuses to use an unsafe road.

So all you need is **somewhere to run the relay that gives you an `https://` address**.

## Option A: try it on your real phones today (no account, about 10 minutes)

This runs the relay on your Mac and lends it a temporary safe `https://` address using Cloudflare's
free "quick tunnel". It only works while your Mac is on and these two commands are running, and the
address changes every time. So this is for **testing**, not for your grandad.

```bash
# Terminal 1: start the relay
cd "Yattu Bhaa/relay-server" && npm install && npm start

# Terminal 2: give it a temporary https address (installs the free cloudflared tool first)
brew install cloudflared
cloudflared tunnel --url http://localhost:8787
```

The second command prints an address like `https://random-words.trycloudflare.com`. Open
`https://random-words.trycloudflare.com/healthz` in a browser: it should say `ok`.

In the app (Helper mode > Set up a new phone), enter the relay address as
`wss://random-words.trycloudflare.com` (the same address, but starting `wss://`).

I haven't run Cloudflare's tunnel from here, so treat these steps as untested.

## Option B: a permanent home (for your grandad)

You need a small always-on host. The easiest I know of is **Fly.io**: it runs the relay from the
folder you already have, gives you an `https://something.fly.dev` address, and handles the safe
road for you. It costs a few dollars a month at most, and needs an account with a card. Please check
their current pricing, and I haven't created any account for you.

1. Make an account at fly.io.
2. Install their tool and log in:
   ```bash
   brew install flyctl
   fly auth login
   ```
3. Open `relay-server/fly.toml` and change the `app =` line to a name nobody else has, for example
   `yattu-relay-yourname`.
4. From the `relay-server` folder:
   ```bash
   fly apps create yattu-relay-yourname      # the same name as in fly.toml
   fly deploy --ha=false                     # exactly ONE copy, on purpose (see below)
   ```
5. Open `https://yattu-relay-yourname.fly.dev/healthz`. It should say `ok`.
6. In the app, use the relay address `wss://yattu-relay-yourname.fly.dev`.

**Why exactly one copy:** the relay keeps who is connected in memory. Two copies would each know
half of it, and two phones could end up on different ones and never meet.

The `fly.toml` I wrote is untested against the real Fly.io.

## Other places that work

Any host that gives you an `https://` address and allows WebSockets: Railway, Render (its free
level goes to sleep when idle, so the first connection can take a minute), or a small rented server
with Caddy in front. The relay is one Node program with a Dockerfile.

## Checking it worked

1. `https://your-address/healthz` shows `ok`.
2. Helper mode > Set up a new phone > enter `wss://your-address` > Make the link.
3. Open the link on the other phone; it should ask "Connect to (your name)?".
4. Get Help on that phone, type the number on yours. Both should say connected.

If step 4 hangs on "Connecting", the address is wrong or the relay isn't running.
