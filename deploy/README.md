# picat-cc artifact server

A tiny Caddy static-file server (deployed on Railway) that hosts the picat-cc
mod jar(s) over plain HTTPS, so a Minecraft server using
[itzg/docker-minecraft-server](https://github.com/itzg/docker-minecraft-server)
can pull the mod by direct URL in its `MODS=` list.

## What's here

- `picat-cc-<version>.jar` — prebuilt mod artifacts (committed; see "Releasing").
- `Dockerfile` — `caddy:2-alpine` serving `/srv` (the jars).
- `Caddyfile` — binds Railway's `$PORT`, serves `*.jar` with a directory browse index.
- `railway.json` — tells Railway to build from this Dockerfile.

## Using it from a Minecraft server

The deployed service (Railway project `picat-cc-cdn`) is live at:

```
https://picat-cc-cdn-production.up.railway.app/picat-cc-0.1.3.jar
```

(Browse index of all hosted jars: <https://picat-cc-cdn-production.up.railway.app/>.)

> **0.1.0 was withdrawn** — it bundled `org.objectweb.asm` unrelocated, which
> caused a `LinkageError` (loader-constraint violation) crashing any server that
> also runs Mixin/MixinExtras-based mods (e.g. Axiom). 0.1.1 relocates ASM into
> `cc.picat.shadow.asm`.
>
> **0.1.3 is the current release.** It raises the engine's concurrency defaults
> and adds a configurable `maxJobsPerComputer` with robustness floors, so a
> handful of timed-out solves can no longer wedge the engine into
> `busy: saturated`. Use the 0.1.3 URL; 0.1.1/0.1.2 remain hosted for servers
> pinned to those filenames.

Add that URL to `MODS` (newline- or comma-separated). It must sit alongside its
dependencies, which are already in the example server config:

- `cc-tweaked-1.21.8-fabric-1.116.1.jar`
- `fabric-api-0.136.1+1.21.8.jar`

itzg accepts any direct `.jar` URL — it does not need to be Modrinth/CurseForge.

### Important: itzg caches mods by filename

itzg downloads each `MODS` URL into `/data/mods/` using the URL's filename and
**skips files that already exist** on restart. So to ship a new build either:

- bump the version in the filename (`picat-cc-0.1.1.jar`) and update the URL, **or**
- set `REMOVE_OLD_MODS="TRUE"` on the Minecraft server (re-downloads every boot).

The versioned-filename approach is preferred (deterministic, no surprise
re-downloads).

## Releasing a new jar

From the repo root, with the nix devShell (provides wasi-sdk + JDK 21):

```sh
nix develop -c make resources          # only if the C/wasm changed
nix develop -c ./gradlew :mod:build    # -> mod/build/libs/mod.jar
cp mod/build/libs/mod.jar deploy/picat-cc-<version>.jar
git add deploy/picat-cc-<version>.jar && git commit -m "deploy: picat-cc <version> artifact"
```

Then redeploy (from this `deploy/` dir):

```sh
railway up
```

Keep old versioned jars in place so existing servers pinned to an old URL keep working.

## First-time Railway setup

```sh
cd deploy
railway link            # select/create the project + service
railway up              # build & deploy this Dockerfile
railway domain          # generate the public *.up.railway.app domain
```
