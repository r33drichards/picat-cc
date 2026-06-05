# picat-cc: Picat FFI for ComputerCraft

**Date:** 2026-06-04
**Status:** Design validated, not yet implemented
**Revised 2026-06-04** after toolchain research (see "Research corrections" at bottom): wasi-sdk replaces Emscripten; Chicory has no timeout mechanism so the timeout design changed; the request channel uses generated Picat literals instead of JSON.

## Goal

Embed the Picat logic-programming engine in a standalone Fabric mod so that
ComputerCraft (CC:Tweaked) computers and turtles can call Picat from Lua and
get **structured data** back — e.g. a turtle executing a quarry plan produced
by Picat's `planner` module (`~/Downloads/turtle_quarry.pi` is the motivating
program). The FFI is generic: any Picat program works (planner, CP/SAT,
tabling), not just turtle planning. Inventory sort-by-minimal-swaps is the
second acceptance use case.

Picat source: `/Users/robertwendt/Picat` (C engine in `emu/`, stdlib in
`lib/`). The Minecraft server is user-controlled; mods can be installed.

## Decisions made (with reasoning)

| Decision | Choice | Why |
|---|---|---|
| Engine path | **Picat compiled to WASM (wasi-sdk), run with Chicory** (pure-Java WASM runtime) | One jar for every OS; a Picat crash/OOM is a catchable Java exception, not a dead server; per-call isolation; memory caps. Cost: ~2–5x slower than native, acceptable for solver workloads. Rejected: JNI (segfaults kill the server, per-platform natives, global state = one instance per JVM), subprocess daemon (not embedded, process management). |
| Term marshalling | **Generic term mapping** (see contract below) | True FFI — works for any Picat program. Rejected: fixed turtle-action vocabulary (turtle-only), raw JSON string pass-through (extra hop, lossy). |
| Exposure | **Global `picat` API** via CC:Tweaked `ILuaAPIFactory` | Every computer and turtle gets `picat` with nothing to place or equip. Turtles in the field can't sit next to a peripheral block. |
| Execution model | **Blocking call + timeout** | `picat.query` yields the Lua coroutine; solve runs on the mod's own worker pool; result returns when done. Other coroutines (`parallel` API) keep running. Job-handle/event API deferred (YAGNI; addable later). |
| Instance lifecycle | **Fresh WASM instance per call** | Instantiation is milliseconds, solves are seconds. Zero state leakage, full crash isolation, no global-state reset logic. |

## Architecture

```
┌─ CC computer/turtle (Lua) ──────────────────────────┐
│  picat.query(prog, goal, vars, opts) → solutions    │
│  picat.eval(prog, goal)              → ok, stdout   │
└──────────────────────┬──────────────────────────────┘
                       │ ILuaAPIFactory (global `picat` on every machine)
┌──────────────────────▼──────────────────────────────┐
│  Mod core (Java): job queue + worker pool,          │
│  timeout/memory caps, JSON→Lua-table conversion     │
└──────────────────────┬──────────────────────────────┘
┌──────────────────────▼──────────────────────────────┐
│  PicatInstance: Chicory WASM runtime + WASI         │
│  virtual FS: lib/*.pi + user program + shim.pi      │
│  picat.wasm (built from emu/ via wasi-sdk, Nix)     │
└─────────────────────────────────────────────────────┘
```

## Lua API

### `picat.query(prog, goal, vars, opts?) → ok, solutions | err`

- `prog` — Picat source string (caller reads it from CC fs)
- `goal` — goal string, e.g. `"do_plan(Start, Plan, Cost)"`
- `vars` — array of variable names to capture: `{"Plan", "Cost"}`
- `opts`:
  - `timeout` — seconds, default 60; Lua can lower, not raise past server max
  - `max` — max solutions to collect via backtracking, default 1
  - `bind` — table of varname → Lua value, marshalled into the goal before it runs
- Returns `true, solutions` (`solutions[n][varName]` = mapped term) or
  `false, err` (string, see error taxonomy).

### `picat.eval(prog, goal?) → ok, stdout`

Runs `main` (or `goal`), captures stdout. For print-style programs and debugging.

### The `fs` option — mounting CC storage into Picat (added 2026-06-05)

`opts.fs = "<dir>"` names a directory **inside the calling computer's own CC
filesystem** (created if absent). The mod resolves it to the computer's real
save directory (`<world>/computercraft/computer/<id>/<dir>`) and mounts it
read-write at **`/data`** in the WASM guest. Motivating case: the `nn` module
(FANN) — train a network, `nn_save('/data/xor.net')`, and the model persists
on the computer's disk where both Lua (`fs.*`) and later Picat calls see it.

```lua
-- train once; model lands on the turtle's own disk
picat.query(prog, "train_and_save('/data/xor.net')", {}, {fs = "models"})
-- later call loads it back
picat.query(prog2, "predict('/data/xor.net', In, Out)", {"Out"}, {fs = "models"})
```

Security invariants:
- The Lua value is a CC-sandbox path, never a host path. Resolution rejects
  absolute paths, `..` segments, and anything that escapes the computer's own
  save dir after normalization.
- Only the computer's own root storage for v1 — no `/rom`, no floppy `/disk`
  mounts (YAGNI; revisit if turtles need to carry models between computers).
- Engine layer (`PicatRunner`/`PicatService`) takes a real `java.nio.Path` for
  the mount — path *resolution and validation* is mod-layer responsibility,
  keeping the engine testable with plain temp dirs.

Caveats (accepted): WASI writes bypass CC's disk-quota accounting, and a
concurrent Lua `fs.write` to the same file during a solve is last-writer-wins
— same as two Lua programs racing today.

### Example: quarry turtle

Add one predicate to `turtle_quarry.pi` (planner code unchanged):

```picat
do_plan(Plan, Cost) =>
    Start = {start_pos(), fuel_cap(), 0, 0},
    plan(Start, 100000000, Plan, Cost).
```

```lua
local f = fs.open("quarry.pi", "r"); local prog = f.readAll(); f.close()
local ok, sols = picat.query(prog, "do_plan(Plan, Cost)", {"Plan","Cost"},
                             {timeout = 120})
if not ok then error(sols) end
for _, a in ipairs(sols[1].Plan) do
  if a == "dump" then goDump()
  elseif a == "refuel" then goRefuel()
  elseif a.f == "mine" then goTo(a.args[1]); turtle.dig() end
end
```

### Example: inventory sort with inbound binding

```lua
local inv = {}
for i = 1, 16 do inv[i] = turtle.getItemDetail(i) end
local ok, sols = picat.query(prog, "sort_plan(Inv, Plan)", {"Plan"},
                             {bind = {Inv = inv}})
-- sols[1].Plan = { {f="swap", args={3,7}}, ... }  (minimal swaps via best_plan/2)
```

## Term mapping contract

Outbound (Picat → JSON → Lua):

| Picat | JSON | Lua |
|---|---|---|
| int / float | number | number |
| atom | string | string |
| string / char list | string | string |
| list, tuple `{...}` | array | array table |
| struct `mine(A,B)` | `{"f":"mine","args":[A,B]}` | `{f="mine", args={...}}` |
| unbound var | `{"var":"_G123"}` | `{var="_G123"}` |

Inbound (`bind`, the reverse): number→int/float, string→atom, array
table→**list** (not tuple — goals expecting arrays use `to_array/1`; documented
rule), `{f=..., args=...}`→struct.

Deliberate lossiness, documented: char lists serialize as strings; tuples and
lists both flatten to arrays. Round-tripping is not bit-exact.

## Shim and WASM↔Java contract

Per job, Java builds an in-memory filesystem (jimfs, mounted via Chicory's
WASI `withDirectory`) and runs `picat /work/shim.pi` in a fresh instance, with
`PICATPATH=/picat/lib` in the WASI environment (Picat resolves its stdlib via
the `PICATPATH` env var, `emu/init.c:155`):

```
/picat/lib/*.pi    Picat stdlib (bundled in jar, read-only)
/work/user.pi      the program string
/work/request.pi   generated BY JAVA: goal_str() = "...". var_names() = [...].
                   max_sols() = 1. bind_list() = [(Name, <term literal>), ...].
/work/response.json ← shim writes this
```

**Inbound channel is generated Picat source, not JSON.** Java serializes the
goal/vars/opts/bind values directly as Picat term literals (a ~50-line
recursive printer with atom quoting). This deletes the entire would-be Picat
JSON *parser* from the shim — only the much simpler JSON *writer* remains.

Shim (~70 lines of Picat, bundled in jar):

1. `cl("/work/user.pi")` — compile errors abort; Java relays Picat's stderr.
2. `parse_term(GoalStr, Goal, Bindings)` (`lib/basic.pi:202`) — name→variable
   map; this is how `bind` unifies in and `vars` read out by name.
3. Unify `bind_list()` entries with their named variables.
4. `catch`-wrapped fail-driven collection of up to `max` solutions.
5. `term_to_json` each solution → `/work/response.json`:
   `{"status":"ok","solutions":[...]}` or `{"status":"failed"|"error","message":...}`.

Java parses the JSON (Gson) into `Map`/`List`; CC:Tweaked converts those to
Lua tables natively. JSON never reaches Lua.

## Build pipeline

Nix flake (devShell provides wasi-sdk 25, JDK 21, Gradle), two outputs:

1. **`picat.wasm`** — `emu/` via **wasi-sdk 25** (not Emscripten — its SjLj
   lowering can't produce a JS-free module; wasi-sdk's can), new
   `Makefile.wasm` cloned from `Makefile.linux64`. Key flags:
   - SjLj → Wasm exception-handling proposal:
     `-mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false`, link
     `-lsetjmp`. Chicory ≥1.5.0 executes the EH proposal in both interpreter
     and compiler backends.
   - WASI emulation shims: `-D_WASI_EMULATED_SIGNAL -lwasi-emulated-signal`
     (signal use in `toam.c`/`getline.c`),
     `-D_WASI_EMULATED_PROCESS_CLOCKS -lwasi-emulated-process-clocks`
     (espresso's `cpu_time.c`).
   - **Memory cap enforced at link time**: `-Wl,--max-memory=268435456`
     (any runtime honors the module's declared max), stack
     `-Wl,-z,stack-size=8388608`.
   - wasm32 = 32-bit pointers → build **without** `-DM64BITS`. Risk: Picat's
     32-bit support may have bit-rotted; spike verifies.
   - Source audit: almost all plain C; kissat/espresso/fann vendored pure C;
     only system dep `-lm`; no GMP (own `bigint.c`); `setjmp` only in
     `toam.c`; `pthread` only in `event.c` — stub under `#ifdef PICAT_WASM`,
     drop `-lpthread`.
2. **Mod jar** — Gradle/Loom Fabric project, **MC 1.21.8** (user's server),
   CC:Tweaked current release for 1.21.8, Java 21. Resources: `picat.wasm`,
   `lib/*.pi`, `shim.pi`. Bundled (jar-in-jar) deps: Chicory 1.7.5
   runtime/wasi/compiler, jimfs, Gson.

### Spike (do FIRST, before any mod code, ~half a day)

```
nix develop → wasi-sdk build → plain JUnit test:
  Chicory loads picat.wasm, mounts lib/, runs the quarry
  program end-to-end, asserts on response.json
```

Precedent: Trealla Prolog ships as pure-WASI via wasi-sdk and runs under
non-JS runtimes — the closest existing proof of this path. SWI's WASM build is
JS-bound; no public Picat WASM port exists, so this is first-of-kind for
Picat. The JUnit harness then remains the permanent fast iteration loop — shim
and marshalling development never requires launching Minecraft.

## Limits and error handling

Server config `picat-cc.toml`:

| Limit | Default | Enforced by |
|---|---|---|
| Per-call timeout | 60s (max 300s) | `Future.get(timeout)` + thread abandonment (see below) |
| WASM memory | 256 MB per instance | link-time `--max-memory` in the module |
| Worker pool | 2 threads | mod executor; never the server tick thread |
| Queue per computer | 1 pending job | stops one turtle hogging the pool |

**Timeout caveat (research finding):** Chicory has **no** epoch interruption,
fuel metering, or interrupt-flag checks — a runaway solve cannot be forcibly
stopped. Mitigation: worker threads are daemons; on timeout the job's thread
is **abandoned** (Lua immediately gets `false, "timeout"`), the thread keeps
burning until the solve finishes or hits the 256 MB memory cap and traps.
Abandoned threads are counted; while ≥ N are outstanding, new jobs are
rejected with `busy: solver saturated by timed-out jobs` so a hostile/buggy
program degrades Picat service only, never the server. Revisit if Chicory
ships its planned gas-metering API.

A solve hitting a limit dies cleanly: instance discarded, `false, <err>` to
Lua, server unaffected.

Error taxonomy — every expected failure is `false, err` with a dispatchable
prefix: `compile: <picat stderr>`, `goal failed`, `timeout`, `memory limit`,
`bind: <bad value path>`, `internal: <trap>`. Lua exceptions only for misuse
(wrong argument types).

## Testing

1. **JUnit** (grown from the spike harness): marshalling round-trips both
   directions, quarry end-to-end, timeout/OOM, compile-error relay,
   char-list/tuple edge cases.
2. **`picat_test.lua`** shipped in repo — in-game smoke suite (query, eval,
   bind, multi-solution, errors).
3. **In-game acceptance**: real turtle executes a small (4×4×3) quarry plan
   from `do_plan`; inventory-sort example.

## Out of scope for v1 (YAGNI)

Persistent Picat sessions across calls, streaming solutions, Picat→Lua
callbacks mid-solve, peripheral block, job-handle/event API. The
fresh-instance model makes all addable later without breaking the API.

## Research corrections (2026-06-04, post-validation)

Verified against current releases before planning:

- **Chicory 1.7.5** (`com.dylibso.chicory:runtime`/`wasi`/`compiler`); EH
  proposal supported since 1.5.0 in interpreter AND compiler backend; WASI
  `withDirectory` accepts any `java.nio.file.Path` → **jimfs** gives a fully
  in-memory guest FS. **No timeout/fuel mechanism exists** → abandon-thread
  design above.
- **Emscripten dropped**: `-sSTANDALONE_WASM` + setjmp still requires JS
  imports. **wasi-sdk** `-mllvm -wasm-enable-sjlj -mllvm
  -wasm-use-legacy-eh=false -lsetjmp` emits a pure-WASI module needing only
  the EH proposal. (Trealla Prolog is the working precedent.)
- **CC:Tweaked**: register the global via
  `ComputerCraftAPI.registerAPIFactory(ILuaAPIFactory)`; long work must NOT
  block the `@LuaFunction` — submit to own executor, return
  `MethodResult.pullEvent("picat_done", cb)`, deliver via
  `IComputerAccess.queueEvent`. Maven: `https://maven.squiddev.cc`,
  `cc.tweaked:cc-tweaked-<mc>-fabric-api`.
- **Picat facts** (verified in source): `parse_term/3` exists
  (`lib/basic.pi:202`); stdlib path via `PICATPATH` env (`emu/init.c:155`);
  no JSON module in `lib/` → inbound channel switched to generated Picat
  literals, shim only needs a JSON writer.
