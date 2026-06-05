# picat-cc: Picat FFI for ComputerCraft

**Date:** 2026-06-04
**Status:** Design validated, not yet implemented

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
| Engine path | **Picat compiled to WASM, run with Chicory** (pure-Java WASM runtime) | One jar for every OS; a Picat crash/OOM is a catchable Java exception, not a dead server; per-call isolation; memory caps. Cost: ~2–5x slower than native, acceptable for solver workloads. Rejected: JNI (segfaults kill the server, per-platform natives, global state = one instance per JVM), subprocess daemon (not embedded, process management). |
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
│  picat.wasm (built from emu/ via Emscripten, Nix)   │
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

Per job, Java builds a virtual WASI filesystem and runs `picat shim.pi` in a
fresh instance:

```
/lib/*.pi        Picat stdlib (bundled in jar, read-only)
/user.pi         the program string
/request.json    {"goal": ..., "vars": [...], "max": 1, "bind": {...}}
/response.json   ← shim writes this
```

Shim (~80 lines of Picat, bundled in jar):

1. `cl("/user.pi")` — compile errors abort; Java relays Picat's stderr.
2. `parse_term(GoalStr, Goal, Bindings)` — name→variable map; this is how
   `bind` unifies in and `vars` read out by name.
3. Unify `bind` entries (JSON→term).
4. `catch`-wrapped collection of up to `max` solutions.
5. `term_to_json` each solution → `/response.json`:
   `{"status":"ok","solutions":[...]}` or `{"status":"failed"|"error","message":...}`.

Java parses the JSON into `Map`/`List`; CC:Tweaked converts those to Lua
tables natively. JSON never reaches Lua.

## Build pipeline

Nix flake, two outputs:

1. **`picat.wasm`** — `emu/` via **Emscripten** (`-sSTANDALONE_WASM`), new
   `Makefile.wasm` cloned from `Makefile.linux64`. Source audit findings:
   - Almost all plain C; kissat, espresso, fann are vendored pure C; only
     system dep is `-lm`. No GMP (own `bigint.c`).
   - `setjmp` only in `toam.c` — Emscripten SjLj lowering; **whether Chicory
     executes the lowered form is risk #1** (see spike).
   - `pthread` only in `event.c` — stub socket/timer paths under `#ifdef WASM`
     (B-Prolog event features the FFI never uses); drop `-lpthread`.
2. **Mod jar** — Gradle/Loom Fabric project. Resources: `picat.wasm`,
   `lib/*.pi`, `shim.pi`. Shaded deps: Chicory runtime + WASI module + minimal
   JSON parser.

### Spike (do FIRST, before any mod code, ~half a day)

```
nix develop → emcc build → plain JUnit test:
  Chicory loads picat.wasm, mounts lib/, runs the quarry
  program end-to-end, asserts on response.json
```

SjLj fallback ladder if Chicory chokes: (a) Chicory's exception-handling
proposal support, (b) wasi-sdk `-wasm-enable-sjlj`, (c) Asyncify lowering.
Precedent exists (SWI/Trealla/Ciao ship on WASM); the spike determines flags.
The JUnit harness then remains the permanent fast iteration loop — shim and
marshalling development never requires launching Minecraft.

## Limits and error handling

Server config `picat-cc.toml`:

| Limit | Default | Enforced by |
|---|---|---|
| Per-call timeout | 60s (max 300s) | epoch interrupt on Chicory interpreter |
| WASM memory | 256 MB per instance | max memory pages at instantiation |
| Worker pool | 2 threads | mod executor; never the server tick thread |
| Queue per computer | 1 pending job | stops one turtle hogging the pool |

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
