# picat-cc acceptance procedure

This is the manual acceptance gate for the picat-cc mod. It is the procedure
a human runs in a live Minecraft client/server to confirm the mod works
end-to-end. **None of the in-game steps below have been executed by the
implementers** -- they are the checklist a tester follows. Leave the boxes
unchecked until a real run is done.

Verified automatically elsewhere: the engine unit/contract tests
(`nix develop -c ./gradlew :engine:test`) and the mod build
(`nix develop -c ./gradlew :mod:build`). What is NOT covered by automated
tests is the actual in-game `picat` global and the turtle physically mining,
which is what this document gates.

## 1. Build the mod

```
nix develop -c ./gradlew :mod:build
```

Output jar: `mod/build/libs/mod.jar`. (Loom does not rename it; the project is
named `mod`, so the artifact is `mod.jar`. The engine and its WASM/stdlib
resources are bundled jar-in-jar -- no separate engine jar to ship.)

- [ ] build succeeds, `mod/build/libs/mod.jar` exists

## 2. Launch a dev run

CC:Tweaked 1.116.1 is on the runtime classpath via `modLocalRuntime`, so a
Loom dev run already has the mod + CC:Tweaked loaded. The client is the
easiest path (no EULA file to edit).

```
nix develop -c ./gradlew :mod:runClient
```

(`:mod:runServer` also works but needs `run/eula.txt` to contain
`eula=true` -- accept the Minecraft EULA first, then re-run.)

Create a new creative world.

- [ ] dev client launches with picat-cc and CC:Tweaked active
- [ ] no errors in the log mentioning `picat` or `chicory`

## 3. Get the smoke test onto a computer

Place a **Computer** (or Advanced Computer) and turn it on.

Important: the script ships inside the mod under
`data/picat-cc/lua/picat_test.lua`, but **data-pack files are NOT visible on a
computer's filesystem** -- CC computers only see their own mount, not mod data
packs. So you must copy the file onto the computer. Pick one:

- **pastebin / wget** (needs `http` enabled in CC config):
  upload `mod/src/main/resources/data/picat-cc/lua/picat_test.lua` to a paste
  service, then on the computer `wget <url> picat_test.lua` (or
  `pastebin get <code> picat_test`).
- **drag-and-drop**: with the computer GUI open, drag the `.lua` file from your
  OS file manager onto the terminal window -- CC writes it into the computer's
  mount directory.
- **copy from the world save folder** (most reliable, no http): every computer
  has a folder on disk. For a singleplayer dev world it is:

  ```
  run/saves/<WorldName>/computercraft/computer/<id>/
  ```

  (On a server it is `run/world/computercraft/computer/<id>/`.) `<id>` is the
  computer's number, shown by the `id` command on the computer; the first
  computer is `0`. Copy the smoke script in:

  ```
  cp mod/src/main/resources/data/picat-cc/lua/picat_test.lua \
     run/saves/<WorldName>/computercraft/computer/<id>/picat_test.lua
  ```

  The file appears immediately in the computer's root.

- [ ] `picat_test.lua` is present on the computer (`ls` shows it)

## 4. Run the smoke test

On the computer:

```
picat_test
```

Expect each check to print `ok`, then a summary and `ALL PASS`.

- [ ] `ALL PASS` (covers: global/version, arith, struct, atom, list,
      backtracking, bind, goal-failure, compile-error, eval, eval-goal,
      planner, fs round-trip — ~14 checks; a couple of fs sub-checks may
      print `(skipped)`, which is benign)
- [ ] any check that prints `(skipped)` did so for a benign reason (fs
      cross-check only) and not a real failure

## 5. Turtle quarry (Task 15 example)

This drives a mining turtle from a Picat plan, mining a 2x2x2 pit.

Get both example files onto a **Turtle** (same methods as step 3; the files are
`examples/quarry_small.pi` and `examples/quarry_turtle.lua`):

```
cp examples/quarry_small.pi   run/saves/<WorldName>/computercraft/computer/<id>/
cp examples/quarry_turtle.lua run/saves/<WorldName>/computercraft/computer/<id>/
```

Set up per the header comment in `quarry_turtle.lua`:

- a turtle with a **pickaxe** equipped and some **fuel** in it,
- the turtle on the platform block, **facing south (+z)**,
- a **storage chest** at the start block column (`{0,2,0}`) and a **fuel
  chest** one block east (`{1,2,0}`), placed so the turtle can drop/suck into
  them (the script drops/sucks DOWNWARD by default -- see the comments to
  switch to forward chests),
- clear air above the 2x2 mining area so the turtle can traverse.

Run on the turtle:

```
quarry_turtle
```

It prints the plan size, then logs each `mine`/`dump`/`refuel` action.

- [ ] turtle requests a plan and prints "got plan with N actions"
- [ ] turtle mines a 2x2x2 pit (8 blocks) below the platform
- [ ] mined blocks end up in the storage chest
- [ ] turtle returns to the start block when done, prints "done"

## 6. Server deploy (real 1.21.8 server)

Confirm the shipped jar works on a real server, not just a dev run.

1. Install Fabric loader for MC 1.21.8 and drop **CC:Tweaked 1.116.1**
   (Fabric build) into the server's `mods/`.
2. Copy the mod jar:

   ```
   cp mod/build/libs/mod.jar <server>/mods/picat-cc.jar
   ```

3. Start the server, place a computer, copy `picat_test.lua` onto it (step 3,
   server save path is `world/computercraft/computer/<id>/`), and run it.

- [ ] server starts with both mods loaded
- [ ] `picat_test` on a real-server computer prints `ALL PASS`
