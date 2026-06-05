# picat-cc

A standalone Fabric mod that embeds the [Picat](http://picat-lang.org/)
logic-programming engine inside Minecraft and exposes it to
[CC:Tweaked](https://tweaked.cc/) Lua. Every ComputerCraft computer and turtle
gets a `picat` global, so a Lua program can hand Picat a source string and a
goal and get **structured solutions** back as Lua tables — plans from the
`planner` module, CP/SAT results, tabled relations, neural-net outputs (FANN
`nn` module), and so on. The motivating example is a mining turtle that asks
Picat for a safe, fuel-aware quarry plan and then carries it out, but the FFI
is generic: any Picat program works, not just turtle planning.

Picat runs as WebAssembly (compiled from its C engine via wasi-sdk) inside the
JVM through the [Chicory](https://chicory.dev/) pure-Java WASM runtime — one jar
for every OS, and a Picat crash or out-of-memory is a catchable Java exception,
not a dead server.

## Status — v0.1.0

**Engine: complete and tested.** The Java engine (`engine/`) has a JUnit suite
of ~67 tests across six classes:

- `SpikeTest` — Chicory loads `picat.wasm` and runs a goal end-to-end.
- `PicatLiteralsTest` — inbound marshalling (Lua/Java values → Picat literals).
- `ShimTest` — the WASM↔Java contract matrix (response shapes, marshalling
  round-trips, char-list/tuple/atom edge cases, compile-error relay).
- `PicatServiceTest` — timeout-by-abandonment, the saturation guard, the error
  taxonomy.
- `QuarryE2eTest` — the quarry planner solved through the full stack.
- `DataMountTest` — the `/data` mount plus an `nn` (FANN) train/save/load
  round-trip.

The suite runs in roughly **8–9 minutes** because each test spins up a real
WASM Picat instance — there is no mocking of the engine.

**Mod: builds, not yet played.** The Fabric mod (`mod/`) compiles against
CC:Tweaked 1.116.1 / Minecraft 1.21.8 and produces a jar with the engine and
all its dependencies bundled jar-in-jar. **In-game acceptance has NOT been
run.** The `picat` global, the yield/event plumbing, and the turtle actually
mining are gated by the manual checklist in
[`docs/acceptance.md`](docs/acceptance.md) — the boxes there are unchecked on
purpose until a human plays through them.

## Lua API

Three functions on the `picat` global:

```
picat.query(prog, goal [, vars] [, opts])  -> ok, solutions | err
picat.eval(prog [, goal] [, opts])         -> ok, stdout    | err
picat.version()                            -> "0.1.0"
```

`query` runs a goal and returns captured variable bindings. `eval` runs a
`main`-style driver (or a named goal) for its stdout — handy for print-style
programs and debugging. Both yield the calling Lua coroutine while the solve
runs on a server-side worker pool, so other coroutines (`parallel` API) keep
running.

### `picat.query(prog, goal, vars, opts)`

- `prog` — Picat source string (read it off the CC filesystem with `fs.open`).
- `goal` — the goal text, **without** a trailing `.`, e.g.
  `"do_plan(Plan, Cost)"`.
- `vars` — optional array of goal-variable names to capture, e.g.
  `{"Plan", "Cost"}` (default `{}`).
- `opts` — optional table (see below).

On success returns `true, solutions` where `solutions` is an array of tables,
one per solution, each mapping a captured var name to its value
(`solutions[n].Plan`). On failure returns `false, err` (a taxonomy string).

### `picat.eval(prog, goal, opts)`

- `goal` — optional; defaults to `main`.
- `opts` — only `timeout` and `fs` apply (`max`/`bind` are ignored: eval is
  single-solution and var-free by design).

On success returns `true, stdout` (the program's captured standard output).

### Options (`opts`)

| key | type | meaning |
|---|---|---|
| `timeout` | number (seconds) | per-call wall-clock limit. Default 60; clamped to the server max (`maxTimeoutSeconds`, default 300). Lua can lower it, not raise it past the server cap. |
| `max` | number | maximum solutions to collect by backtracking (`query` only). Default 1. |
| `bind` | table | var-name → Lua value, marshalled into the goal before it runs (`query` only). |
| `fs` | string | a sub-directory of the computer's own storage to mount read-write at `/data` in Picat (see below). |

### Marshalling

Outbound (Picat → Lua), per solution variable:

| Picat | Lua |
|---|---|
| int / float | `number` |
| atom | `string` |
| string / char list | `string` |
| list, tuple `{...}` | array table |
| struct `mine(A, B)` | `{f = "mine", args = {A, B}}` |
| unbound variable | `{var = "_"}` (gensym names would be non-deterministic) |

Inbound (`bind`, the reverse): number → int (whole, `|n| ≤ 2^53`) or float;
string → quoted atom; boolean → `true`/`false`; array table → **list** (not a
tuple — a goal that wants a tuple array should convert with `to_array/1`);
`{f=..., args=...}` → struct.

Lossiness is deliberate and documented: char lists come back as strings, tuples
and lists both flatten to arrays, and integers outside ±2^53 lose precision
(everything crosses as a JSON number → Lua double). Round-tripping is not
bit-exact.

### Error taxonomy

Every expected failure is `false, err` where `err` is a string with a
dispatchable prefix. Lua exceptions are raised only for genuine misuse (wrong
argument types).

| prefix | meaning |
|---|---|
| `compile: <picat stderr>` | the program failed to compile. |
| `goal failed` | the goal ran but had no solution. |
| `timeout` | exceeded the per-call timeout (job abandoned, see below). |
| `memory limit` | hit the 256 MB WASM cap. |
| `bind: <detail>` | a `bind` value could not be marshalled. |
| `error: <picat msg>` | the goal threw at runtime. |
| `busy: <detail>` | a job is already running on this computer, or the solver is saturated by timed-out jobs. |
| `internal: <detail>` | an engine fault (malformed response, unexpected trap). |
| `fs: <detail>` | the `fs` mount path was rejected or unavailable. |

### Examples

Arithmetic — capture one variable:

```lua
local ok, sols = picat.query("", "X = 6 * 7", {"X"})
-- ok == true, sols[1].X == 42
```

The quarry plan (the planner program is in `examples/quarry_small.pi`):

```lua
local f = fs.open("quarry_small.pi", "r"); local prog = f.readAll(); f.close()
local ok, sols = picat.query(prog, "do_plan(Plan, Cost)", {"Plan", "Cost"},
                             {timeout = 120})
if not ok then error(sols) end
for _, a in ipairs(sols[1].Plan) do
  if a == "dump" then goDump()
  elseif a == "refuel" then goRefuel()
  elseif type(a) == "table" and a.f == "mine" then
    local t = a.args[1]; goTo(t[1], t[2], t[3]); turtle.dig()
  end
end
```

Inventory sort with inbound binding — pass a Lua array in, get a plan back:

```lua
local inv = {}
for i = 1, 16 do inv[i] = turtle.getItemDetail(i) and i or 0 end
local ok, sols = picat.query(prog, "sort_plan(Inv, Plan)", {"Plan"},
                             {bind = {Inv = inv}})
-- sols[1].Plan = { {f="swap", args={3,7}}, ... }
```

Persistence with `fs` and the `nn` (FANN) module — train once, reload later:

```lua
-- train and save; the model lands on this computer's own disk under "models/"
picat.query(prog, "train_and_save('/data/xor.net')", {}, {fs = "models"})
-- a later call loads it back from the same mount
local ok, sols = picat.query(prog2, "predict('/data/xor.net', In, Out)",
                             {"Out"}, {fs = "models"})
```

### The `fs` mount

`opts.fs = "<dir>"` names a directory **inside the calling computer's own CC
filesystem** (created if absent) and mounts it read-write at **`/data`** in the
Picat guest, so models, tables, and other files persist on the computer's disk
where both Lua (`fs.*`) and later Picat calls see them.

- The Lua value is a CC-sandbox path, never a host path. Resolution rejects
  absolute paths, `..` segments, and anything that escapes the computer's own
  save directory.
- Only the computer's own root storage — no `/rom`, no floppy `/disk` mounts.
- **Not supported on command computers.** The mod cannot derive their on-disk
  save folder from the CC api surface, so rather than mis-mount it rejects the
  request with `fs: not supported on command computers`.
- Caveat: WASI writes bypass CC's disk-quota accounting (see Limits).

## How it works

Three layers:

1. **`picat.wasm`** — Picat's C engine built with **wasi-sdk 25** to a pure
   wasm32 (32-bit) WASI module, no JavaScript glue. As vendored, Picat uses no
   `setjmp`/`longjmp` (the only references in `toam.c` are commented out), so
   the module contains no exception-handling instructions and **needs no
   Wasm-EH support in the runtime** — `wasm-tools validate --features=-exceptions`
   passes. The build gates out sockets/signals/pthread/`system()` dead code
   with `-DPICAT_WASM` and caps memory at link time
   (`-Wl,--max-memory=268435456`).
2. **Engine (`engine/`)** — Chicory loads the module per call into a fresh
   instance (instantiation is milliseconds; solves are seconds) with a jimfs
   in-memory guest filesystem holding the stdlib (`/picat/lib`), the user
   program, and a generated `request.pi`. `shim.pi` compiles the goal,
   fail-drivenly collects up to `max` solutions, and writes `/work/response.json`,
   which Java parses (Gson) into maps/lists. The inbound channel is generated
   Picat term literals, not JSON — there is no JSON *parser* in the shim, only a
   writer.
3. **Mod (`mod/`)** — registers the `picat` global via CC:Tweaked's
   `ILuaAPIFactory`. A `query`/`eval` submits to the engine's worker pool and
   returns `MethodResult.pullEvent("picat_done", …)` so the Lua coroutine yields
   instead of blocking CC's thread; completion queues the event back. Because
   Chicory cannot interrupt running WASM, timeouts work **by abandonment**: on
   timeout the caller gets `timeout` immediately and the worker thread is left to
   burn until the goal finishes or hits the memory cap.

For the full design and the toolchain research behind these choices, see
[`docs/plans/2026-06-04-picat-cc-ffi-design.md`](docs/plans/2026-06-04-picat-cc-ffi-design.md).

## Build

Prerequisites: [Nix](https://nixos.org/) with flakes. The devShell provides
wasi-sdk 25 and JDK 21 (`flake.nix`).

```sh
# (Re)build picat.wasm from third_party and bundle it + the stdlib into the
# engine's resources. picat.wasm is committed, so this is only needed after a
# change to the C engine.
nix develop -c make resources

# Run the engine test suite (~8-9 min; a real WASM Picat per test).
nix develop -c ./gradlew :engine:test

# Build the mod jar -> mod/build/libs/mod.jar
nix develop -c ./gradlew :mod:build
```

**Use the Gradle wrapper (`./gradlew`, 9.4.1), not the devShell's bare
`gradle`.** The devShell ships Gradle 8.x, but Fabric Loom 1.16 requires Gradle
9.4+; invoking the bare `gradle` will fail. This is a real gotcha.

## Install / run

1. Install Fabric loader for Minecraft 1.21.8.
2. Drop **CC:Tweaked 1.116.1** (Fabric build) and the mod jar into `mods/`:

   ```sh
   cp mod/build/libs/mod.jar <server-or-client>/mods/picat-cc.jar
   ```

The mod writes a config file at `config/picat-cc.json` on first run:

| key | default | meaning |
|---|---|---|
| `workerThreads` | 2 | engine worker pool size (concurrent solves, process-wide). |
| `maxAbandonedJobs` | 4 | reject new work with `busy:` once this many timed-out jobs are still outstanding. |
| `maxTimeoutSeconds` | 300 | hard cap on any single call's `timeout`. |

For the in-game smoke test (`picat_test.lua`) and the turtle quarry demo, follow
[`docs/acceptance.md`](docs/acceptance.md).

## Limits and caveats

Honest constraints, most of them inherent to the chosen design:

- **Speed.** Picat under Chicory runs roughly 2–5x slower than native — fine for
  solver workloads, not for hot loops.
- **Number precision.** All numbers cross the FFI as doubles; integers outside
  ±2^53 lose precision.
- **ASCII only.** `parse_term` under WASI rejects non-ASCII atoms (probed:
  `'café'`, `δ` → syntax error). Goals and atoms must be ASCII.
- **One job per computer.** A second `query`/`eval` while one is in flight gets
  `busy:`.
- **No force-kill.** Chicory has no interrupt/fuel mechanism, so a truly
  infinite goal cannot be stopped — its worker thread is *abandoned*, burning a
  core until the 256 MB memory cap traps it. Saturating `maxAbandonedJobs` with
  such jobs wedges the engine on `busy:` until the server restarts (the rest of
  the server is unaffected).
- **fs quota bypass.** Writes through the `/data` mount go straight to disk and
  bypass CC's disk-quota accounting; a concurrent Lua `fs.write` to the same file
  during a solve is last-writer-wins.
- **Goal strings must not contain a `.` terminator** — pass the goal text bare.

Out of scope for v1 (all addable later without breaking the API): persistent
Picat sessions across calls, streaming solutions, a peripheral block, a
job-handle/event API.

## Layout

```
third_party/picat/   vendored Picat engine source (C) + stdlib + Makefile.wasm
engine/              Java library: Chicory runner, shim.pi, marshalling, tests
mod/                 Fabric mod: the `picat` global, config, in-game scripts
examples/            quarry_small.pi (planner) + quarry_turtle.lua (executor)
docs/plans/          design and implementation plans
docs/acceptance.md   manual in-game acceptance procedure
```

## License and credits

The mod's own code is **MPL-2.0** (declared in `fabric.mod.json`). The vendored
Picat source under `third_party/picat/` is governed by Picat's own license — the
C source is MPL-2.0, owned by picat-lang.org; see
[`third_party/picat/LICENSE`](third_party/picat/LICENSE).

Built on:

- **Picat** — the upstream logic-programming engine (picat-lang.org).
- **Chicory** — the pure-Java WebAssembly runtime that executes `picat.wasm`.
- **CC:Tweaked** — the ComputerCraft fork that hosts the `picat` global.
