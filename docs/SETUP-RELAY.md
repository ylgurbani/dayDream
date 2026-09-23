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

## Option B: a permanent home on Render (for your grandad)

Render builds the relay from its Dockerfile and gives you an `https://something.onrender.com`
address with the safe road already handled. The free plan is enough for this (0.1 CPU, 512 MB),
but it goes to sleep after 15 minutes of no traffic and takes maybe a minute to wake up on the
next connection — fine for occasional help calls, less fine if he needs you urgently and the
relay is asleep. Paid plans stay awake; check Render's current pricing. **Choose the Frankfurt
region when you create it** — see "Where the relay runs matters" below; it cannot be changed later.

Render deploys from a GitHub repository, so this project needs to be on GitHub first:

1. Turn this project into a git repository and push it to GitHub (a new **private** repo is
   fine — Render only needs read access to it):
   ```bash
   cd "Yattu Bhaa"
   git init && git add -A && git commit -m "Yattu Bhaa"
   gh repo create yattu-bhaa --private --source=. --push   # needs the gh CLI and a GitHub login
   ```
   Without the `gh` CLI: create an empty repo at github.com, then
   `git remote add origin <the URL> && git push -u origin main`.
2. Make an account at render.com and connect your GitHub account to it.
3. In the Render dashboard: **New +** → **Blueprint** → pick the `yattu-bhaa` repo. Render reads
   `relay-server/render.yaml` (already in this project) and proposes one web service built from
   `relay-server/Dockerfile`. Click **Apply**.

   **A path gotcha if you edit that file, or use the other method below:** Render always resolves
   `dockerfilePath` and `dockerContext` **inside a `render.yaml`** relative to the repo root, no
   matter where the `render.yaml` file itself lives. That's why the file says
   `relay-server/Dockerfile`, not `./Dockerfile` — the shorter form builds with the whole repo
   root as context, which has no top-level `Dockerfile` or `src/`, and fails with something like
   "cannot find /src".

   - If you'd rather not use a Blueprint: **New +** → **Web Service** → pick the repo → set
     **Root Directory** to `relay-server` → **Runtime** to **Docker**. Here the convention is the
     *opposite* of the Blueprint file: once Root Directory is set, **Dockerfile Path** is relative
     to that root directory, so it should just be `Dockerfile` (not `relay-server/Dockerfile`).
     Then add the environment variable `TRUST_PROXY=1` yourself (the Blueprint already sets this).
4. Once it deploys, open `https://<the name Render gave it>.onrender.com/healthz`. It should say
   `ok`.
5. In the app, use the relay address `wss://<the same name>.onrender.com`.
6. In the service's Settings, set **Health Check Path** to `/healthz` — Render then restarts it
   automatically if it ever stops responding.

### Where the relay runs matters: pick Frankfurt

Every frame of his screen goes from his phone to the relay and from the relay to yours, so the
relay should sit between the two of you, not off to one side. Render's `render.yaml` in this
project does not name a region, which means **Oregon (US West Coast)** by default. For India and
the UK that sends every frame across two oceans and back: very roughly 250ms from India to Oregon
plus 140ms from Oregon to the UK, against roughly 140ms plus 20ms through **Frankfurt** — the
closest of Render's regions to both of you. (Singapore is closer to him but much further from you.)
That difference is added to every tap you make and every frame you see.

**Check which region yours is in:** the Render dashboard shows it on the service's page.

**Render cannot move an existing service to another region** — its docs are explicit that the
region cannot be changed after creation. To move it:

1. In the Render dashboard, **New +** → **Web Service** (the manual method above), and choose
   **Frankfurt** as the region when creating it, with a new name (e.g. `yattu-bhaa-relay-eu`).
2. Check `https://<new name>.onrender.com/healthz` says `ok`.
3. Pair again on both phones with the new `wss://` address: the relay address is part of the
   pairing link, so the old pairing keeps pointing at the old relay. (Helper mode > Set up a new
   phone, then send him the new link.)
4. Once the new pairing works, delete the old service.

Do not simply add `region: frankfurt` to `render.yaml` while the old service exists: Render will
not apply it to a service that already exists, and the Blueprint sync may fail over it.

**Leave the instance count at 1.** The relay keeps who is connected in memory; a second instance
would only know half of it, and two phones could land on different ones and never meet. The free
plan only ever runs one instance, so this is automatic there — just don't turn on scaling later.

I have not run this against the real Render, so treat it as untested; I confirmed the `render.yaml`
field names and the free-plan behaviour against Render's current docs, but not an actual deploy.

## Other places that work

Any host that gives you an `https://` address, allows WebSockets, and (as above) runs exactly one
instance: Railway, **Fly.io** (a `relay-server/fly.toml` is included, untested, if you'd rather use
that), or a small rented server with Caddy in front.

## Checking it worked

1. `https://your-address/healthz` shows `ok`.
2. Helper mode > Set up a new phone > enter `wss://your-address` > Make the link.
3. Open the link on the other phone; it should ask "Connect to (your name)?".
4. Get Help on that phone, type the number on yours. Both should say connected.

If step 4 hangs on "Connecting", the address is wrong or the relay isn't running.
