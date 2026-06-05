# picat-cc Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Picat compiled to WASM, embedded in a Fabric mod via Chicory, exposed as a global `picat` Lua API on every ComputerCraft computer, returning structured solutions (plans as Lua tables).

**Architecture:** Three layers per the design doc (`docs/plans/2026-06-04-picat-cc-ffi-design.md` — read it first): (1) `picat.wasm` built from the Picat C engine with wasi-sdk, SjLj lowered to the Wasm EH proposal; (2) `engine/` — a plain Java library wrapping Chicory + jimfs + the Picat shim, fully testable with JUnit, no Minecraft; (3) `mod/` — a thin Fabric mod (MC 1.21.8) registering the `picat` global via CC:Tweaked's `ILuaAPIFactory`, with the yield+event pattern for long solves.

**Tech Stack:** Nix (devShell: wasi-sdk 25, JDK 21, Gradle), Chicory 1.7.5 (`runtime`, `wasi`, `compiler`), jimfs 1.3.0, Gson 2.11.0, JUnit 5, Fabric Loom, CC:Tweaked for MC 1.21.8.

**Phase gate:** Tasks 1–6 are the SPIKE. If Task 6 (hello-world through Chicory) cannot be made to pass after exhausting the fallback ladder in Task 5, STOP and report — the rest of the plan depends on it. Everything after Task 6 is low-risk plumbing.

**Source of Picat:** `/Users/robertwendt/Picat` (vendored into the repo in Task 1).

**Repo:** `/Users/robertwendt/picat-cc` (already exists with `docs/plans/`).

---

## Phase 1: Spike — picat.wasm runs under Chicory

### Task 1: Vendor Picat source

**Files:**
- Create: `third_party/picat/` (copy of emu + lib + LICENSE)
- Create: `.gitignore`

**Step 1: Copy the source**

```bash
cd /Users/robertwendt/picat-cc
mkdir -p third_party/picat
cp -R /Users/robertwendt/Picat/emu third_party/picat/emu
cp -R /Users/robertwendt/Picat/lib third_party/picat/lib
cp /Users/robertwendt/Picat/LICENSE third_party/picat/LICENSE
# drop prebuilt objects — we only want source
find third_party/picat/emu -name '*.o' -delete
```

**Step 2: Create `.gitignore`**

```gitignore
*.o
*.wasm
.gradle/
build/
result
.DS_Store
```

Exception we'll need later: the engine's bundled wasm is a build product copied
into `engine/src/main/resources/` by the Makefile — that path is force-added in
Task 6.

**Step 3: Verify and commit**

```bash
ls third_party/picat/emu/toam.c third_party/picat/lib/planner.pi  # both must exist
git add -A && git commit -m "Vendor Picat source (emu + lib) from picat-lang distribution"
```

### Task 2: Nix devShell with wasi-sdk 25

**Files:**
- Create: `flake.nix`

**Step 1: Write `flake.nix`**

```nix
{
  description = "picat-cc: Picat FFI for ComputerCraft";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f s);
      wasiArch = {
        "aarch64-darwin" = "arm64-macos";
        "x86_64-linux" = "x86_64-linux";
      };
      # Fill in after first `nix develop` attempt reports the real hashes.
      wasiHash = {
        "aarch64-darwin" = nixpkgs.lib.fakeHash;
        "x86_64-linux" = nixpkgs.lib.fakeHash;
      };
    in {
      devShells = forAll (system:
        let
          pkgs = import nixpkgs { inherit system; };
          wasiSdk = pkgs.stdenvNoCC.mkDerivation rec {
            pname = "wasi-sdk";
            version = "25.0";
            src = pkgs.fetchurl {
              url = "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-25/wasi-sdk-25.0-${wasiArch.${system}}.tar.gz";
              hash = wasiHash.${system};
            };
            dontStrip = true;
            dontPatchShebangs = true;
            # prebuilt clang toolchain; on Linux the ELF binaries need patching
            nativeBuildInputs = pkgs.lib.optionals pkgs.stdenv.isLinux
              [ pkgs.autoPatchelfHook ];
            buildInputs = pkgs.lib.optionals pkgs.stdenv.isLinux
              [ pkgs.stdenv.cc.cc.lib pkgs.zlib ];
            installPhase = ''
              mkdir -p $out
              cp -r . $out/
            '';
          };
        in {
          default = pkgs.mkShell {
            packages = [ pkgs.jdk21 pkgs.gradle pkgs.gnumake ];
            WASI_SDK_PATH = "${wasiSdk}";
            JAVA_HOME = "${pkgs.jdk21}";
          };
        });
    };
}
```

**Step 2: Get the real hashes**

```bash
cd /Users/robertwendt/picat-cc
git add flake.nix && nix develop -c true
```

Expected: FAIL with `hash mismatch ... got: sha256-...`. Paste the reported
hash into `wasiHash."aarch64-darwin"`. Repeat (Linux hash can stay fake until
CI; note that in a comment).

**Step 3: Verify the toolchain**

```bash
nix develop -c sh -c '$WASI_SDK_PATH/bin/clang --version && ls $WASI_SDK_PATH/share/wasi-sysroot/lib/wasm32-wasip1/libsetjmp.a && java -version'
```

Expected: clang 19/20 banner, `libsetjmp.a` present, `openjdk 21`.
If `libsetjmp.a` is at a different sysroot path, find it: `find $WASI_SDK_PATH -name 'libsetjmp*'` — adjust Task 3's link line to match.

**Step 4: Commit**

```bash
git add flake.nix && git commit -m "Nix devShell: wasi-sdk 25, JDK 21, Gradle"
```

### Task 3: Makefile.wasm

**Files:**
- Create: `third_party/picat/emu/Makefile.wasm`

This is cloned from `Makefile.linux64` + `common.mak` (read both first), with
the wasi-sdk toolchain, SjLj→EH flags, WASI emulation shims, **no `-DM64BITS`**
(wasm32 = 32-bit pointers), no pthread, and link-time memory caps.

**Step 1: Write `third_party/picat/emu/Makefile.wasm`**

```makefile
# Build picat.wasm with wasi-sdk (>= 24). Requires WASI_SDK_PATH.
# SjLj is lowered to the Wasm exception-handling proposal so the module
# runs in non-JS runtimes that support EH (Chicory >= 1.5.0).

WASI_SDK_PATH ?= /opt/wasi-sdk
CC  = $(WASI_SDK_PATH)/bin/clang
CPP = $(WASI_SDK_PATH)/bin/clang++

DEFS = -DGC -DGCC -DPICAT -DSAT -DFANN_NO_DLL -DFANN -DPICAT_WASM \
       -Dunix -DLINUX -DPOSIX \
       -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_PROCESS_CLOCKS

SJLJ = -mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false

CFLAGS = -O2 -fno-strict-aliasing $(DEFS) $(SJLJ) \
         -Wno-error=unused-label -Wno-implicit-function-declaration

LFLAGS = -lm -lsetjmp -lwasi-emulated-signal -lwasi-emulated-process-clocks \
         -Wl,-z,stack-size=8388608 \
         -Wl,--max-memory=268435456

CCC  = $(CC) -c $(CFLAGS)
CPPC = $(CPP) -c $(CFLAGS)

include common.mak

# common.mak's `picat$(EXT)` rule links with $(CPP); EXT picks the suffix
EXT = .wasm
```

Check `common.mak`'s link rule: it is `$(CPP) -o picat$(EXT) $(CFLAGS) $(OBJ) ... $(LFLAGS)` and its
implicit `.c.o` rules use `$(CCC)`. If `common.mak` hardcodes anything
incompatible (e.g. `EXT` set before include), inline the `OBJ`/link rule into
`Makefile.wasm` instead of including — copy the `OBJ`, `ESPRESSO_OBJ`,
`KISSAT_OBJ` lists verbatim.

**Step 2: First build attempt — expect failures, that's the point**

```bash
cd /Users/robertwendt/picat-cc/third_party/picat/emu
nix develop /Users/robertwendt/picat-cc -c make -f Makefile.wasm picat.wasm 2>&1 | tee /tmp/wasm-build.log | tail -40
```

Expected first-pass failures and their fixes (apply ONLY what actually fails):

| Symptom | Fix |
|---|---|
| `pthread.h not found` (event.c) | Wrap the offending includes/usages in `#ifndef PICAT_WASM` … `#endif`. The event subsystem (sockets/timers) is dead code for us. |
| socket headers (`netinet/in.h` etc.) | Same `#ifndef PICAT_WASM` treatment. |
| `sigaction`/`alarm` undefined (toam.c) | `_WASI_EMULATED_SIGNAL` covers `signal()`; wrap anything beyond it in `#ifndef PICAT_WASM`. |
| getline.c terminal ioctls | Interactive REPL line-editing — wrap in `#ifndef PICAT_WASM`, keep a plain `fgets` path if one exists (look for existing `#ifdef` fallbacks first). |
| 32-bit pointer breakage (casts to `long`, `BPLONG` assumptions) | `basic.h`/`basicd.h` historically support 32-bit (B-Prolog legacy). If errors are few, fix per-site; if they cascade across the VM core, STOP — report that wasm32 is not viable and we must evaluate wasm64 (memory64) support in Chicory before continuing. |
| `times()` missing (espresso `cpu_time.c`) | Already covered by `_WASI_EMULATED_PROCESS_CLOCKS`; if a hole remains, stub the function under `#ifdef PICAT_WASM` to return 0. |

Rules for source edits: smallest possible `#ifndef PICAT_WASM` guards, never
delete code, comment each guard with `/* PICAT_WASM: no sockets/signals in
WASI */`. Commit after each file compiles, message like
`wasm: guard event.c socket paths for WASI`.

**Step 3: Link and inspect**

```bash
nix develop /Users/robertwendt/picat-cc -c make -f Makefile.wasm picat.wasm
ls -la picat.wasm   # expect a multi-MB file
```

**Step 4: Commit**

```bash
git add -A && git commit -m "wasm: Picat builds to picat.wasm with wasi-sdk SjLj->EH"
```

### Task 4: Gradle skeleton for `engine/`

**Files:**
- Create: `settings.gradle`
- Create: `engine/build.gradle`
- Create: `gradle.properties`

**Step 1: Write the build files**

`settings.gradle`:
```gradle
rootProject.name = 'picat-cc'
include 'engine'
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2G
```

`engine/build.gradle`:
```gradle
plugins { id 'java-library' }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
    api 'com.dylibso.chicory:runtime:1.7.5'
    api 'com.dylibso.chicory:wasi:1.7.5'
    api 'com.dylibso.chicory:compiler:1.7.5'
    implementation 'com.google.jimfs:jimfs:1.3.0'
    implementation 'com.google.code.gson:gson:2.11.0'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test { useJUnitPlatform() }
```

**Step 2: Verify it resolves**

```bash
cd /Users/robertwendt/picat-cc
nix develop -c gradle :engine:dependencies --configuration testRuntimeClasspath -q | head -20
```

Expected: chicory 1.7.5, jimfs, gson resolve. (No gradle wrapper yet — Nix
provides Gradle; generate a wrapper later only if CI needs it. If chicory
1.7.5 doesn't resolve, check the latest on
https://mvnrepository.com/artifact/com.dylibso.chicory/runtime and pin that.)

**Step 3: Commit**

```bash
git add -A && git commit -m "engine: gradle skeleton with Chicory 1.7.5 + jimfs + gson"
```

### Task 5: Resource bundling

**Files:**
- Create: `Makefile` (repo root — orchestrates wasm build + resource copy)
- Create: `engine/src/main/resources/picat/` (wasm + stdlib, build products)

**Step 1: Root `Makefile`**

```makefile
WASM = third_party/picat/emu/picat.wasm
RES  = engine/src/main/resources/picat

.PHONY: wasm resources clean

wasm:
	$(MAKE) -C third_party/picat/emu -f Makefile.wasm picat.wasm

resources: wasm
	mkdir -p $(RES)/lib
	cp $(WASM) $(RES)/picat.wasm
	cp third_party/picat/lib/*.pi $(RES)/lib/

clean:
	$(MAKE) -C third_party/picat/emu -f Makefile.wasm clean || true
	rm -rf $(RES)
```

**Step 2: Run it, force-add the products**

```bash
nix develop -c make resources
ls engine/src/main/resources/picat/picat.wasm engine/src/main/resources/picat/lib/planner.pi
git add -f engine/src/main/resources/picat
git add Makefile && git commit -m "build: bundle picat.wasm + stdlib as engine resources"
```

(We commit the wasm so Java-side devs don't need wasi-sdk; `make resources`
refreshes it after C-side changes.)

### Task 6: SPIKE GATE — hello world through Chicory

**Files:**
- Create: `engine/src/test/java/cc/picat/engine/SpikeTest.java`
- Create: `engine/src/main/java/cc/picat/engine/PicatRunner.java`

**Step 1: Write the failing test**

```java
package cc.picat.engine;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SpikeTest {
    @Test
    void helloWorldRunsUnderChicory() {
        PicatRunner.RawResult r = PicatRunner.runRaw(
            Map.of("/work/main.pi", "main => println(hello).\n"),
            java.util.List.of("picat", "/work/main.pi"),
            30_000);
        assertEquals("hello", r.stdout().trim(),
            "stderr was: " + r.stderr());
        assertEquals(0, r.exitCode());
    }
}
```

**Step 2: Run to verify it fails**

```bash
nix develop -c gradle :engine:test --tests 'cc.picat.engine.SpikeTest' 2>&1 | tail -5
```
Expected: FAIL — `PicatRunner` does not exist.

**Step 3: Implement `PicatRunner`**

```java
package cc.picat.engine;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/** Runs one Picat goal in a fresh, fully isolated WASM instance. */
public final class PicatRunner {

    public record RawResult(int exitCode, String stdout, String stderr,
                            String responseJson) {}

    private static final WasmModule MODULE =
        Parser.parse(PicatRunner.class.getResourceAsStream("/picat/picat.wasm"));

    private static final List<String> STDLIB = listStdlib();

    private PicatRunner() {}

    /**
     * @param files   guest path -> content; all paths must start with /work/
     * @param argv    argv[0] is conventionally "picat"
     * @param timeoutMs ignored here (enforced by PicatService); kept for the
     *                  raw entry point used in tests
     */
    public static RawResult runRaw(Map<String, String> files, List<String> argv,
                                   long timeoutMs) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path picatDir = fs.getPath("/picat");
            Path libDir = picatDir.resolve("lib");
            Files.createDirectories(libDir);
            for (String name : STDLIB) {
                try (var in = PicatRunner.class
                        .getResourceAsStream("/picat/lib/" + name)) {
                    Files.copy(in, libDir.resolve(name));
                }
            }
            Path workDir = fs.getPath("/work");
            Files.createDirectories(workDir);
            for (var e : files.entrySet()) {
                Path p = fs.getPath(e.getKey());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }

            WasiOptions opts = WasiOptions.builder()
                .withStdout(stdout)
                .withStderr(stderr)
                .withStdin(new ByteArrayInputStream(new byte[0]))
                .withArguments(argv)
                .withEnvironment("PICATPATH", "/picat/lib")
                .withDirectory("/picat", picatDir)
                .withDirectory("/work", workDir)
                .build();

            int exit = 0;
            try (var wasi = WasiPreview1.builder().withOptions(opts).build()) {
                var store = new Store().addFunction(wasi.toHostFunctions());
                Instance.builder(MODULE)
                    .withMachineFactory(MachineFactoryCompiler::compile)
                    .withImportValues(store.toImportValues())
                    .build(); // WASI command module: _start runs on build
            } catch (WasiExitException e) {
                exit = e.exitCode();
            }

            String response = null;
            Path resp = workDir.resolve("response.json");
            if (Files.exists(resp)) {
                response = Files.readString(resp, StandardCharsets.UTF_8);
            }
            return new RawResult(exit,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8),
                response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> listStdlib() {
        // Stdlib file list is fixed at build time; keep it explicit so the
        // jar needs no classpath scanning.
        return List.of("basic.pi", "common_constr.pi", "cp.pi",
            "cp_sat_mip_smt.pi", "datetime.pi", "io.pi", "math.pi", "mip.pi",
            "mip_aux.pi", "mip_smt.pi", "nn.pi", "ordset.pi", "os.pi",
            "picat_lib_aux.pi", "planner.pi", "prism.pi", "prism_ex.pi",
            "sat.pi", "sat_bv.pi", "sat_mip.pi", "sat_mip_smt.pi", "smt.pi",
            "smt_aux.pi", "sys.pi", "temp.pi", "util.pi");
    }
}
```

API notes for whoever implements this: the exact Chicory API surface
(`Store#addFunction`, whether `_start` runs on instantiate or needs
`instance.export("_start").apply()`, the exit-exception class name) moved
between Chicory versions. If a call doesn't compile, check
https://chicory.dev/docs/usage/wasi/ for 1.7.x and adjust — the SHAPE above
(parse once statically, fresh Store+WASI+Instance per call, jimfs mounts,
captured streams) is the contract; method names are not.

**Step 4: Run the test — this is the spike verdict**

```bash
nix develop -c gradle :engine:test --tests 'cc.picat.engine.SpikeTest' 2>&1 | tail -15
```

Expected: PASS, stdout `hello`.

**Fallback ladder if it fails** (work top to bottom, re-run after each):
1. EH-related parse/validation error → try interpreter backend (drop
   `withMachineFactory` line). If interpreter works but compiler doesn't,
   proceed with interpreter and file a note in the README.
2. Still EH errors → rebuild wasm with legacy EH encoding: remove
   `-mllvm -wasm-use-legacy-eh=false` from `Makefile.wasm`, `make resources`,
   retest both backends.
3. Trap inside Picat init (32-bit issue) → reproduce outside Java if possible
   (`wasmtime --wasm exceptions picat.wasm` after `nix shell nixpkgs#wasmtime`)
   to separate "engine broken" from "Chicory broken". Use
   @superpowers:systematic-debugging.
4. All exhausted → STOP. Report findings; wasm64/alternative runtimes is a
   design-level decision, not a plan deviation to improvise.

**Step 5: Commit**

```bash
git add -A && git commit -m "engine: SPIKE PASSES - picat.wasm hello world under Chicory"
```

---

## Phase 2: The FFI — shim, marshalling, service

### Task 7: Request writer (Lua/Java values → Picat literals)

**Files:**
- Create: `engine/src/main/java/cc/picat/engine/PicatLiterals.java`
- Test: `engine/src/test/java/cc/picat/engine/PicatLiteralsTest.java`

The inbound half of the term mapping: Java `Map`/`List`/`Number`/`String`
(what CC:Tweaked hands us from Lua) → Picat term literal text. Mapping per
design: number→int/float, string→quoted atom, array-like map (keys 1..n) →
list, `{f=..., args={...}}` map → struct `$f(args...)`.

**Step 1: Write the failing tests**

```java
package cc.picat.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PicatLiteralsTest {
    @Test void integers()      { assertEquals("42", PicatLiterals.toLiteral(42.0)); }
    @Test void floats()        { assertEquals("3.5", PicatLiterals.toLiteral(3.5)); }
    @Test void atoms()         { assertEquals("'kelp'", PicatLiterals.toLiteral("kelp")); }
    @Test void atomEscaping()  { assertEquals("'it''s'", PicatLiterals.toLiteral("it's")); }
    @Test void lists() {
        assertEquals("[1,2,3]", PicatLiterals.toLiteral(List.of(1.0, 2.0, 3.0)));
    }
    @Test void luaArrayTablesAreLists() {
        // CC:Tweaked delivers Lua tables as Map<Double,Object> with keys 1..n
        assertEquals("[1,2]", PicatLiterals.toLiteral(Map.of(1.0, 1.0, 2.0, 2.0)));
    }
    @Test void structs() {
        assertEquals("$swap(3,7)", PicatLiterals.toLiteral(
            Map.of("f", "swap", "args", Map.of(1.0, 3.0, 2.0, 7.0))));
    }
    @Test void nestedListInStruct() {
        assertEquals("$mine([0,63,0])", PicatLiterals.toLiteral(
            Map.of("f", "mine", "args", Map.of(1.0, Map.of(1.0, 0.0, 2.0, 63.0, 3.0, 0.0)))));
    }
    @Test void wholeNumberDoublesAreInts() {
        assertEquals("63", PicatLiterals.toLiteral(63.0)); // Lua numbers arrive as Double
    }
    @Test void rejectsNonArrayMapsWithoutF() {
        assertThrows(IllegalArgumentException.class,
            () -> PicatLiterals.toLiteral(Map.of("x", 1.0)));
    }
}
```

**Step 2: Run to verify failure** — `nix develop -c gradle :engine:test --tests '*PicatLiteralsTest*'` → FAIL (class missing).

**Step 3: Implement**

```java
package cc.picat.engine;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Serializes Java/Lua values as Picat term literal source text (the inbound
 *  half of the FFI mapping — see the design doc's contract table). */
public final class PicatLiterals {
    private PicatLiterals() {}

    public static String toLiteral(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > 64) throw new IllegalArgumentException("bind: nesting too deep");
        switch (v) {
            case null -> throw new IllegalArgumentException("bind: nil not mappable");
            case Boolean b -> sb.append(b ? "true" : "false");
            case Number n -> writeNumber(sb, n);
            case String s -> writeAtom(sb, s);
            case List<?> l -> writeList(sb, l, depth);
            case Map<?, ?> m -> writeMap(sb, m, depth);
            default -> throw new IllegalArgumentException(
                "bind: unmappable type " + v.getClass().getSimpleName());
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        double d = n.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d)) sb.append((long) d);
        else sb.append(d);
    }

    private static void writeAtom(StringBuilder sb, String s) {
        sb.append('\'').append(s.replace("'", "''")).append('\'');
    }

    private static void writeList(StringBuilder sb, List<?> l, int depth) {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            write(sb, l.get(i), depth + 1);
        }
        sb.append(']');
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> m, int depth) {
        if (m.containsKey("f") && m.containsKey("args")) { // struct
            sb.append('$').append(structName((String) m.get("f"))).append('(');
            List<Object> args = asArray(m.get("args"));
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(',');
                write(sb, args.get(i), depth + 1);
            }
            sb.append(')');
            return;
        }
        writeList(sb, asArray(m), depth); // Lua array table
    }

    /** Lua tables arrive as Map with Double keys 1.0..n; verify and order. */
    private static List<Object> asArray(Object v) {
        if (v instanceof List<?> l) return List.copyOf(l);
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("bind: expected array, got " + v);
        }
        var sorted = new TreeMap<Long, Object>();
        for (var e : m.entrySet()) {
            if (!(e.getKey() instanceof Number k) || k.doubleValue() != Math.rint(k.doubleValue())) {
                throw new IllegalArgumentException(
                    "bind: table is neither array nor {f=...,args=...}: key " + e.getKey());
            }
            sorted.put((long) k.doubleValue(), e.getValue());
        }
        long expect = 1;
        for (long k : sorted.keySet()) {
            if (k != expect++) throw new IllegalArgumentException(
                "bind: sparse array at index " + k);
        }
        return List.copyOf(sorted.values());
    }

    private static String structName(String f) {
        if (!f.matches("[a-z][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("bind: bad functor name " + f);
        }
        return f;
    }
}
```

**Step 4: Run tests** → all `PicatLiteralsTest` PASS.

**Step 5: Commit** — `git add -A && git commit -m "engine: inbound marshalling (Java/Lua values -> Picat literals)"`

### Task 8: The shim — `shim.pi`

**Files:**
- Create: `engine/src/main/resources/picat/shim.pi` (force-add; it lives with resources but is hand-written — move it out of the `make clean` path by adjusting the root Makefile's `clean` to keep it: change `rm -rf $(RES)` to `rm -f $(RES)/picat.wasm && rm -rf $(RES)/lib`)
- Modify: `Makefile` (clean rule, as above)
- Test: `engine/src/test/java/cc/picat/engine/ShimTest.java`

The shim reads `request.pi` (generated module), loads `user.pi`, runs the
goal, writes `response.json`. Develop it test-first from the Java side — every
shim behavior gets a JUnit test before the shim code that implements it.

**Step 1: Write the first failing test (success path, single det solution)**

```java
package cc.picat.engine;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShimTest {
    static final Gson GSON = new Gson();

    @SuppressWarnings("unchecked")
    static Map<String, Object> run(String userProg, String goal,
                                   List<String> vars, int max, String bindList) {
        String request = """
            module request.
            goal_str() = "%s".
            var_names() = %s.
            max_sols() = %d.
            bind_list() = %s.
            """.formatted(goal.replace("\"", "\\\""),
                toPicatStringList(vars), max, bindList);
        var r = PicatRunner.runRaw(Map.of(
                "/work/user.pi", userProg,
                "/work/request.pi", request),
            List.of("picat", "/picat/lib/../../work/shim.pi"), 30_000);
        // NOTE: shim ships inside the jar; PicatRunner copies it to /work/shim.pi.
        assertNotNull(r.responseJson(), "no response.json; stderr: " + r.stderr());
        return GSON.fromJson(r.responseJson(), Map.class);
    }

    static String toPicatStringList(List<String> xs) {
        return "[" + String.join(",", xs.stream().map(s -> "\"" + s + "\"").toList()) + "]";
    }

    @Test void singleSolution() {
        var resp = run("double(X) = X * 2.\n", "Y = double(21)",
            List.of("Y"), 1, "[]");
        assertEquals("ok", resp.get("status"));
        var sols = (List<Map<String, Object>>) resp.get("solutions");
        assertEquals(1, sols.size());
        assertEquals(42.0, sols.get(0).get("Y"));
    }
}
```

Before this can run, `PicatRunner.runRaw` must also copy `shim.pi` from
resources into `/work/shim.pi`. Add to `PicatRunner` (after writing `files`):

```java
try (var in = PicatRunner.class.getResourceAsStream("/picat/shim.pi")) {
    Files.copy(in, workDir.resolve("shim.pi"),
        StandardCopyOption.REPLACE_EXISTING);
}
```
and simplify the test's argv to `List.of("picat", "/work/shim.pi")`.

**Step 2: Run, verify failure** (shim.pi missing).

**Step 3: Write `engine/src/main/resources/picat/shim.pi`**

```picat
/* shim.pi — picat-cc FFI harness.
 * Reads /work/request.pi (generated by Java), loads /work/user.pi,
 * runs the goal, writes /work/response.json.
 * Contract: docs/plans/2026-06-04-picat-cc-ffi-design.md
 */
import util.
import request.

main =>
    catch(run_request(), E, write_error(to_fstring("%w", E))).

run_request() =>
    cl("/work/user.pi"),
    GoalStr = goal_str(),
    parse_term(GoalStr, Goal, VarPairs),
    bind_all(bind_list(), VarPairs),
    CaptureVars = [lookup_var(Name, VarPairs) : Name in var_names()],
    Map = get_global_map(),
    Map.put(sols, []),
    Map.put(cnt, 0),
    ( call(Goal),
      Sol = sol_json(var_names(), CaptureVars),
      Map.put(sols, [Sol | Map.get(sols)]),
      Map.put(cnt, Map.get(cnt) + 1),
      Map.get(cnt) >= max_sols()      % fail here => backtrack for next sol
    ;
      true
    ),
    Sols = reverse(Map.get(sols)),
    ( Sols == [], Map.get(cnt) == 0 ->
        write_response("{\"status\":\"failed\"}")
    ;
        write_response("{\"status\":\"ok\",\"solutions\":[" ++
                       join(Sols, ",") ++ "]}")
    ).

%% --- request plumbing -----------------------------------------------------

bind_all([], _VarPairs) => true.
bind_all([(Name, Value) | Rest], VarPairs) =>
    V = lookup_var(Name, VarPairs),
    V = Value,
    bind_all(Rest, VarPairs).

lookup_var(Name, VarPairs) = V =>
    ( member(Pair, VarPairs), Pair = (PName = PVar), PName == Name ->
        V = PVar
    ;
        throw $var_not_in_goal(Name)
    ).

sol_json(Names, Vars) = "{" ++ join(Pairs, ",") ++ "}" =>
    Pairs = [json_str(N) ++ ":" ++ term_json(V)
             : {N, V} in zip(Names, Vars)].

%% --- term -> JSON (outbound mapping; contract table in design doc) --------

term_json(T) = to_string(T), integer(T) => true.
term_json(T) = to_string(T), float(T) => true.
term_json(T) = "{\"var\":\"_\"}", var(T) => true.
term_json(T) = json_str(to_string(T)), atom(T) => true.
term_json(T) = J, string(T) => J = json_str(T).            % char list
term_json(T) = J, is_list(T) => J = "[" ++ join([term_json(X) : X in T], ",") ++ "]".
term_json(T) = J, array(T) => J = "[" ++ join([term_json(X) : X in T], ",") ++ "]".
term_json(T) = J, struct(T) =>
    J = "{\"f\":" ++ json_str(to_string(name(T))) ++ ",\"args\":[" ++
        join([term_json(T[I]) : I in 1 .. arity(T)], ",") ++ "]}".
term_json(T) = _ => throw $unmappable_term(T).

json_str(S) = "\"" ++ Escaped ++ "\"" =>
    Escaped = flatten([esc(C) : C in S]).

esc('"')  = "\\\"".
esc('\\') = "\\\\".
esc('\n') = "\\n".
esc('\r') = "\\r".
esc('\t') = "\\t".
esc(C)    = [C].

%% --- output ----------------------------------------------------------------

write_response(S) =>
    FD = open("/work/response.json", write),
    print(FD, S),
    close(FD).

write_error(Msg) =>
    write_response("{\"status\":\"error\",\"message\":" ++ json_str(Msg) ++ "}").
```

Implementation notes (expect to iterate — that's why each behavior has a JUnit
test): exact predicate availability differs between Picat docs and practice.
Verify as you go with tiny probe programs through `PicatRunner.runRaw`:
- `parse_term/3`'s `VarPairs` element shape: probe with
  `parse_term("foo(X)", G, V), writeln(V)` — if pairs are `(Name = Var)` with
  Name an ATOM not a string, compare with `==` against `to_atom(Name)`.
- `string/1` vs `is_list`/char-list overlap: order of `term_json` clauses
  matters; strings must match before generic lists.
- If `join/2` on a list of strings isn't in `util`, use
  `join(List, Sep)` arity from util docs or fold manually.
- `zip/2` produces `{A,B}` pairs in Picat comprehensions; if not, index with
  `nth`.

**Step 4: Run the test until green**, fixing the shim against probe findings.

**Step 5: Commit** — `git add -A && git commit -m "engine: shim.pi runs goals and emits JSON solutions"`

### Task 9: Outbound marshalling test matrix + multi-solution + failure modes

**Files:**
- Modify: `engine/src/test/java/cc/picat/engine/ShimTest.java`

**Step 1: Add the failing tests — one per contract row + error taxonomy**

```java
@Test void atomsBecomeStrings() {
    var resp = run("", "X = kelp", List.of("X"), 1, "[]");
    assertEquals("kelp", firstSol(resp).get("X"));
}
@Test void listsBecomeArrays() {
    var resp = run("", "X = [1,2,3]", List.of("X"), 1, "[]");
    assertEquals(List.of(1.0, 2.0, 3.0), firstSol(resp).get("X"));
}
@Test void tuplesBecomeArrays() {
    var resp = run("", "X = {0,63,5}", List.of("X"), 1, "[]");
    assertEquals(List.of(0.0, 63.0, 5.0), firstSol(resp).get("X"));
}
@Test void structsBecomeFArgs() {
    var resp = run("", "X = $mine({1,2,3})", List.of("X"), 1, "[]");
    Map<String, Object> x = (Map<String, Object>) firstSol(resp).get("X");
    assertEquals("mine", x.get("f"));
    assertEquals(List.of(List.of(1.0, 2.0, 3.0)), x.get("args"));
}
@Test void multipleSolutionsViaBacktracking() {
    var resp = run("", "member(X, [a,b,c])", List.of("X"), 2, "[]");
    var sols = (List<Map<String, Object>>) resp.get("solutions");
    assertEquals(List.of("a", "b"),
        sols.stream().map(s -> s.get("X")).toList());
}
@Test void goalFailureIsStatusFailed() {
    var resp = run("", "1 = 2", List.of(), 1, "[]");
    assertEquals("failed", resp.get("status"));
}
@Test void thrownErrorIsStatusError() {
    var resp = run("boom() => throw $custom_err.\n", "boom()", List.of(), 1, "[]");
    assertEquals("error", resp.get("status"));
    assertTrue(((String) resp.get("message")).contains("custom_err"));
}
@Test void compileErrorSurfacesViaStderr() {
    var r = PicatRunner.runRaw(Map.of(
            "/work/user.pi", "this is not picat ===",
            "/work/request.pi", "module request.\ngoal_str() = \"true\".\n"
                + "var_names() = [].\nmax_sols() = 1.\nbind_list() = []."),
        List.of("picat", "/work/shim.pi"), 30_000);
    // cl/1 failure may abort before response.json exists — Java layer treats
    // missing response + nonempty stderr as a compile error (Task 10)
    assertTrue(r.responseJson() == null || !r.stderr().isEmpty()
        || r.responseJson().contains("error"));
}
@Test void bindInjectsValues() {
    var resp = run("sum_list(L) = sum(L).\n", "S = sum_list(Xs)",
        List.of("S"), 1, "[('Xs', [1,2,3])]");
    assertEquals(6.0, firstSol(resp).get("S"));
}
```

(`firstSol` helper: first element of `solutions`.)

**Step 2: Run** — expect several FAIL initially. Fix shim clause order /
predicate usage per failure. Each red→green cycle, one commit.

**Step 3: Final run, all green; commit** —
`git add -A && git commit -m "engine: full outbound marshalling contract + error taxonomy tests"`

### Task 10: `PicatService` — public API, timeout, abandonment

**Files:**
- Create: `engine/src/main/java/cc/picat/engine/PicatService.java`
- Test: `engine/src/test/java/cc/picat/engine/PicatServiceTest.java`

This is the class the mod calls. It owns the executor, builds `request.pi`
(via `PicatLiterals`), interprets `RawResult` into the error taxonomy, and
implements timeout-by-abandonment.

**Step 1: Write the failing tests**

```java
package cc.picat.engine;

import org.junit.jupiter.api.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PicatServiceTest {
    PicatService svc;
    @BeforeEach void setUp() { svc = new PicatService(2, 4, 60_000); }
    @AfterEach void tearDown() { svc.shutdown(); }

    @Test void querySuccess() throws Exception {
        var r = svc.query("inc(X) = X + 1.", "Y = inc(41)",
            List.of("Y"), Map.of()).get();
        assertTrue(r.ok());
        assertEquals(42.0, r.solutions().get(0).get("Y"));
    }

    @Test void compileErrorTaxonomy() throws Exception {
        var r = svc.query("not picat ===", "true", List.of(), Map.of()).get();
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("compile:"), r.error());
    }

    @Test void goalFailedTaxonomy() throws Exception {
        var r = svc.query("", "1 = 2", List.of(), Map.of()).get();
        assertFalse(r.ok());
        assertEquals("goal failed", r.error());
    }

    @Test void timeoutAbandonsAndReports() throws Exception {
        var r = svc.query("spin() => spin().", "spin()",
            List.of(), Map.of("timeout", 2.0)).get();
        assertFalse(r.ok());
        assertEquals("timeout", r.error());
        assertEquals(1, svc.abandonedCount());
    }

    @Test void saturationRejects() throws Exception {
        for (int i = 0; i < 4; i++) {
            svc.query("spin() => spin().", "spin()",
                List.of(), Map.of("timeout", 1.0)).get();
        }
        var r = svc.query("", "X = 1", List.of("X"), Map.of()).get();
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("busy:"), r.error());
    }

    @Test void evalCapturesStdout() throws Exception {
        var r = svc.eval("main => println(hi).", null).get();
        assertTrue(r.ok());
        assertEquals("hi\n", r.stdout());
    }
}
```

**Step 2: Run, verify failure.**

**Step 3: Implement `PicatService`**

```java
package cc.picat.engine;

import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread-pooled, timeout-guarded front door to PicatRunner.
 *  Timeout caveat: Chicory cannot interrupt running WASM; timed-out jobs are
 *  ABANDONED (daemon thread runs to completion or memory-trap). While more
 *  than maxAbandoned are outstanding, new jobs are rejected. */
public final class PicatService {
    public record Result(boolean ok, List<Map<String, Object>> solutions,
                         String stdout, String error) {
        static Result okSols(List<Map<String, Object>> s) { return new Result(true, s, null, null); }
        static Result okOut(String out) { return new Result(true, null, out, null); }
        static Result err(String e) { return new Result(false, null, null, e); }
    }

    private static final Gson GSON = new Gson();
    private final ExecutorService pool;
    private final int maxAbandoned;
    private final long defaultTimeoutMs;
    private final long maxTimeoutMs;
    private final AtomicInteger abandoned = new AtomicInteger();

    public PicatService(int threads, int maxAbandoned, long maxTimeoutMs) {
        this.maxAbandoned = maxAbandoned;
        this.maxTimeoutMs = maxTimeoutMs;
        this.defaultTimeoutMs = Math.min(60_000, maxTimeoutMs);
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "picat-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public int abandonedCount() { return abandoned.get(); }
    public void shutdown() { pool.shutdownNow(); }

    public CompletableFuture<Result> query(String prog, String goal,
            List<String> vars, Map<String, Object> opts) {
        return submit(opts, () -> runQuery(prog, goal, vars, opts));
    }

    public CompletableFuture<Result> eval(String prog, String goal) {
        return submit(Map.of(), () -> runEval(prog, goal));
    }

    private CompletableFuture<Result> submit(Map<String, Object> opts,
                                             Callable<Result> job) {
        if (abandoned.get() >= maxAbandoned) {
            return CompletableFuture.completedFuture(
                Result.err("busy: solver saturated by timed-out jobs"));
        }
        long timeoutMs = timeoutMs(opts);
        CompletableFuture<Result> out = new CompletableFuture<>();
        Future<Result> inner = pool.submit(job);
        // watcher: cheap, uses the common scheduler, not a pool thread
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS)
            .execute(() -> {
                if (out.complete(Result.err("timeout"))) {
                    abandoned.incrementAndGet();
                    inner.cancel(false); // best effort; WASM won't stop
                }
            });
        pool.execute(() -> { /* no-op: ordering */ });
        CompletableFuture.runAsync(() -> {
            try {
                Result r = inner.get();
                if (out.complete(r)) { /* finished before timeout */ }
            } catch (Exception e) {
                out.complete(Result.err("internal: " + e.getMessage()));
            }
        });
        // If the job finishes after timeout fired, un-count the abandonment.
        out.whenComplete((r, t) -> inner.cancel(false));
        new Thread(() -> {
            try { inner.get(); }
            catch (Exception ignored) {}
            finally { if (out.isDone() && "timeout".equals(errOf(out))) abandoned.decrementAndGet(); }
        }, "picat-reaper") {{ setDaemon(true); }}.start();
        return out;
    }

    private static String errOf(CompletableFuture<Result> f) {
        try { return f.getNow(null) == null ? null : f.getNow(null).error(); }
        catch (Exception e) { return null; }
    }

    private long timeoutMs(Map<String, Object> opts) {
        Object t = opts.get("timeout");
        if (t instanceof Number n) {
            return Math.min((long) (n.doubleValue() * 1000), maxTimeoutMs);
        }
        return defaultTimeoutMs;
    }

    private Result runQuery(String prog, String goal, List<String> vars,
                            Map<String, Object> opts) {
        int max = opts.get("max") instanceof Number n ? n.intValue() : 1;
        StringBuilder bind = new StringBuilder("[");
        if (opts.get("bind") instanceof Map<?, ?> b) {
            boolean first = true;
            for (var e : b.entrySet()) {
                if (!first) bind.append(',');
                first = false;
                bind.append("('").append(e.getKey()).append("',")
                    .append(PicatLiterals.toLiteral(e.getValue())).append(')');
            }
        }
        bind.append(']');
        String request = """
            module request.
            goal_str() = "%s".
            var_names() = [%s].
            max_sols() = %d.
            bind_list() = %s.
            """.formatted(
                goal.replace("\\", "\\\\").replace("\"", "\\\""),
                String.join(",", vars.stream().map(v -> "\"" + v + "\"").toList()),
                max, bind);

        var raw = PicatRunner.runRaw(
            Map.of("/work/user.pi", prog, "/work/request.pi", request),
            List.of("picat", "/work/shim.pi"), 0);

        if (raw.responseJson() == null) {
            String stderr = raw.stderr().strip();
            return Result.err(stderr.isEmpty()
                ? "internal: no response (exit " + raw.exitCode() + ")"
                : "compile: " + stderr);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = GSON.fromJson(raw.responseJson(), Map.class);
        return switch ((String) resp.get("status")) {
            case "ok" -> {
                @SuppressWarnings("unchecked")
                var sols = (List<Map<String, Object>>) resp.get("solutions");
                yield Result.okSols(sols);
            }
            case "failed" -> Result.err("goal failed");
            default -> Result.err("error: " + resp.get("message"));
        };
    }

    private Result runEval(String prog, String goal) {
        String entry = goal == null ? "main" : goal;
        String driver = prog + "\n\npicat_cc_eval_main => " + entry + ".\n";
        var raw = PicatRunner.runRaw(
            Map.of("/work/user.pi", driver,
                   "/work/request.pi", """
                       module request.
                       goal_str() = "picat_cc_eval_main".
                       var_names() = [].
                       max_sols() = 1.
                       bind_list() = [].
                       """),
            List.of("picat", "/work/shim.pi"), 0);
        if (raw.responseJson() == null) {
            return Result.err("compile: " + raw.stderr().strip());
        }
        return Result.okOut(raw.stdout());
    }
}
```

NOTE to implementer: the timeout/abandonment bookkeeping above is the
trickiest part of the whole plan and the sketch has rough edges (the reaper
thread, double-counting). Simplify if you can — the BEHAVIOR the tests pin
down is what matters: (1) caller gets `timeout` promptly; (2) abandonedCount
reflects still-running zombies and decrements when they finish; (3)
saturation rejects with `busy:`. Consider one dedicated watcher per job via
`inner.get(timeout)` inside a wrapper task on a second small pool — clearer
than callbacks.

**Step 4: Run tests until green.** The `spin()` zombie threads will burn CPU
for the test JVM's lifetime — acceptable in tests (daemon threads, JVM exits).

**Step 5: Commit** — `git add -A && git commit -m "engine: PicatService with timeout-by-abandonment and saturation guard"`

### Task 11: Quarry end-to-end (the acceptance test for the whole engine)

**Files:**
- Create: `engine/src/test/resources/quarry_small.pi`
- Test: `engine/src/test/java/cc/picat/engine/QuarryE2eTest.java`

**Step 1: Create `quarry_small.pi`** — copy
`/Users/robertwendt/Downloads/turtle_quarry.pi` into test resources, then
change ONLY the config section and add the FFI entry point:

```picat
chunk_size()    = 2.
surface_y()     = 1.
bedrock_y()     = 0.
inv_cap()       = 4.
fuel_cap()      = 200.
refuel_amount() = 50.
```
and append:
```picat
do_plan(Plan, Cost) =>
    Start = {start_pos(), fuel_cap(), 0, 0},
    plan(Start, 100000000, Plan, Cost).
```

**Step 2: Write the failing test**

```java
package cc.picat.engine;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QuarryE2eTest {
    @Test void quarryPlanComesBackStructured() throws Exception {
        String prog = new String(getClass().getResourceAsStream("/quarry_small.pi")
            .readAllBytes(), StandardCharsets.UTF_8);
        var svc = new PicatService(1, 2, 120_000);
        try {
            var r = svc.query(prog, "do_plan(Plan, Cost)",
                List.of("Plan", "Cost"), Map.of("timeout", 120.0)).get();
            assertTrue(r.ok(), () -> "error: " + r.error());
            var sol = r.solutions().get(0);
            assertTrue(sol.get("Cost") instanceof Double);
            @SuppressWarnings("unchecked")
            var plan = (List<Object>) sol.get("Plan");
            assertFalse(plan.isEmpty());
            long mines = 0;
            for (Object a : plan) {
                if (a instanceof String s) {
                    assertTrue(s.equals("dump") || s.equals("refuel"), s);
                } else {
                    @SuppressWarnings("unchecked")
                    var struct = (Map<String, Object>) a;
                    assertEquals("mine", struct.get("f"));
                    @SuppressWarnings("unchecked")
                    var pos = (List<Double>) ((List<Object>) struct.get("args")).get(0);
                    assertEquals(3, pos.size());
                    mines++;
                }
            }
            assertEquals(2 * 2 * 2, mines, "2x2 chunk, 2 layers => 8 mined blocks");
        } finally { svc.shutdown(); }
    }
}
```

**Step 3: Run** — `nix develop -c gradle :engine:test --tests '*QuarryE2e*'`.
Expected: PASS (this exercises `import planner`, struct/atom/tuple
marshalling, and real solver work in one shot). If slow (>60s), shrink config
further before blaming the engine.

**Step 4: Commit** — `git add -A && git commit -m "engine: quarry plan end-to-end test passes"`

---

### Task 11b: `/data` host mounts (added 2026-06-05 per user requirement)

**Requirement:** Picat programs (esp. the `nn`/FANN module) must read/write
persistent files. Design (see design doc "The `fs` option"): Lua passes
`opts.fs = "<dir>"` — a path inside the calling computer's CC sandbox; the mod
resolves it to the computer's host save dir and the engine mounts it at
`/data` in the guest.

**Engine side (TDD):**
- `PicatRunner.runRaw` gains an optional `Path dataDir` parameter (nullable);
  when present, adds `.withDirectory("/data", dataDir)` to WasiOptions.
- `PicatService.query/eval` opts gain `"fsPath"` (a `java.nio.Path`, set by
  the mod layer, never by Lua directly).
- Tests (JUnit `@TempDir` real dirs — jimfs not needed here):
  1. Picat writes `/data/out.txt` → file appears in the temp dir.
  2. Pre-existing file in temp dir readable from Picat (`read_file_to_string`).
  3. nn round-trip if feasible: train tiny XOR net, `nn_save('/data/xor.net')`,
     fresh call loads it and predicts. If the nn module turns out broken under
     WASM (FANN was compiled in but never exercised), characterize and report
     — file the finding, don't block the mount feature on FANN.
- No `fs` opt → no `/data` mount (Picat sees ENOENT; test pins that isolation
  default).

**Mod side (folds into Task 13):** resolve `opts.fs` → host path:
computer save dir (via CC API / server save path + computer ID) + the given
relative dir; reject absolute paths and any `..` after normalization
(`resolved.normalize().startsWith(computerRoot)`); create the dir; pass as
`fsPath`. Document quota-bypass caveat in README (Task 16).

## Phase 3: The Fabric mod

### Task 12: Mod skeleton

**Files:**
- Modify: `settings.gradle` (add `include 'mod'`, add Fabric plugin repos)
- Create: `mod/build.gradle`, `mod/src/main/resources/fabric.mod.json`
- Create: `mod/src/main/java/cc/picat/mod/PicatCcMod.java`

**Step 1: Check current versions** (they move; pin what's real today):
- Fabric Loom / loader / fabric-api for MC **1.21.8**: https://fabricmc.net/develop/
- CC:Tweaked for 1.21.8: https://maven.squiddev.cc/cc/tweaked/ — find the
  latest `cc-tweaked-1.21.8-fabric-api` version (1.119.x or newer).

**Step 2: `settings.gradle`**

```gradle
pluginManagement {
    repositories {
        maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
        gradlePluginPortal()
    }
}
rootProject.name = 'picat-cc'
include 'engine'
include 'mod'
```

**Step 3: `mod/build.gradle`** (versions from Step 1 — placeholders marked)

```gradle
plugins { id 'fabric-loom' version '<LOOM_VERSION>' }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories {
    mavenCentral()
    maven { url = 'https://maven.squiddev.cc' }
}

dependencies {
    minecraft "com.mojang:minecraft:1.21.8"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:<LOADER_VERSION>"
    modImplementation "net.fabricmc.fabric-api:fabric-api:<FABRIC_API_VERSION>"

    modCompileOnly "cc.tweaked:cc-tweaked-1.21.8-fabric-api:<CCT_VERSION>"
    modLocalRuntime "cc.tweaked:cc-tweaked-1.21.8-fabric:<CCT_VERSION>"

    implementation project(':engine')
    // jar-in-jar so the engine + its deps ship inside the mod jar
    include project(':engine')
    include 'com.dylibso.chicory:runtime:1.7.5'
    include 'com.dylibso.chicory:wasi:1.7.5'
    include 'com.dylibso.chicory:compiler:1.7.5'
    include 'com.google.jimfs:jimfs:1.3.0'
    include 'com.google.code.gson:gson:2.11.0'
}
```

(If `include` chokes on transitive deps of chicory/jimfs, list them
explicitly from `gradle :mod:dependencies`, or fall back to shadow-jar. Guava
comes with Minecraft; if jimfs's guava version conflicts at runtime, add
`include 'com.google.guava:guava:<version jimfs needs>'`.)

**Step 4: `fabric.mod.json`**

```json
{
  "schemaVersion": 1,
  "id": "picat-cc",
  "version": "0.1.0",
  "name": "Picat for ComputerCraft",
  "description": "Embeds the Picat logic-programming engine; every CC computer gets a `picat` global.",
  "license": "MPL-2.0",
  "environment": "*",
  "entrypoints": { "main": ["cc.picat.mod.PicatCcMod"] },
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "~1.21.8",
    "computercraft": "*"
  }
}
```

**Step 5: Entrypoint stub**

```java
package cc.picat.mod;

import net.fabricmc.api.ModInitializer;

public final class PicatCcMod implements ModInitializer {
    @Override
    public void onInitialize() {
        PicatApiRegistration.register(); // Task 13
    }
}
```

(Leave `PicatApiRegistration.register()` commented out until Task 13 so this
compiles.)

**Step 6: Verify** — `nix develop -c gradle :mod:build -x test 2>&1 | tail -5`
→ BUILD SUCCESSFUL, `mod/build/libs/*.jar` exists.

**Step 7: Commit** — `git add -A && git commit -m "mod: Fabric skeleton for MC 1.21.8 with CC:Tweaked dep and jar-in-jar engine"`

### Task 13: The `picat` global — ILuaAPI with yield+event

**Files:**
- Create: `mod/src/main/java/cc/picat/mod/PicatApiRegistration.java`
- Create: `mod/src/main/java/cc/picat/mod/PicatLuaAPI.java`
- Create: `mod/src/main/java/cc/picat/mod/PicatCcConfig.java`

No JUnit here (CC API needs a game); correctness is covered by the engine
tests + Task 14's in-game script. Keep this layer THIN — argument decoding,
event plumbing, nothing else.

**Step 1: Config (simple JSON file in the Fabric config dir)**

```java
package cc.picat.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PicatCcConfig {
    public int workerThreads = 2;
    public int maxAbandonedJobs = 4;
    public int maxTimeoutSeconds = 300;

    public static PicatCcConfig load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("picat-cc.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.exists(p)) {
                return gson.fromJson(Files.readString(p), PicatCcConfig.class);
            }
            PicatCcConfig def = new PicatCcConfig();
            Files.writeString(p, gson.toJson(def));
            return def;
        } catch (Exception e) {
            return new PicatCcConfig();
        }
    }
}
```

**Step 2: Registration**

```java
package cc.picat.mod;

import cc.picat.engine.PicatService;
import dan200.computercraft.api.ComputerCraftAPI;

public final class PicatApiRegistration {
    static PicatService SERVICE;

    public static void register() {
        PicatCcConfig cfg = PicatCcConfig.load();
        SERVICE = new PicatService(cfg.workerThreads, cfg.maxAbandonedJobs,
            cfg.maxTimeoutSeconds * 1000L);
        ComputerCraftAPI.registerAPIFactory(computer ->
            new PicatLuaAPI(computer, SERVICE));
    }
}
```

**Step 3: The API object — yield + event pattern**

```java
package cc.picat.mod;

import cc.picat.engine.PicatService;
import dan200.computercraft.api.lua.*;
import dan200.computercraft.api.peripheral.IComputerAccess;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The `picat` global. Long solves run on PicatService's pool; the Lua
 *  coroutine yields on a private event and resumes when the job queues it. */
public final class PicatLuaAPI implements ILuaAPI {
    private static final String EVENT = "picat_done";
    private static final AtomicLong TOKENS = new AtomicLong();

    private final IComputerSystem computer;
    private final PicatService service;
    // per-computer in-flight guard (design: 1 pending job per computer)
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    PicatLuaAPI(IComputerSystem computer, PicatService service) {
        this.computer = computer;
        this.service = service;
    }

    @Override public String[] getNames() { return new String[] { "picat" }; }

    @LuaFunction
    public final MethodResult query(IArguments args) throws LuaException {
        String prog = args.getString(0);
        String goal = args.getString(1);
        List<String> vars = toStringList(args.getTable(2));
        Map<String, Object> opts = args.optTable(3).map(t -> {
            Map<String, Object> m = new HashMap<>();
            t.forEach((k, v) -> m.put(String.valueOf(k), v));
            return m;
        }).orElse(Map.of());

        if (!inFlight.isEmpty()) {
            return MethodResult.of(false, "busy: a picat job is already running on this computer");
        }
        long token = TOKENS.incrementAndGet();
        inFlight.add(token);
        service.query(prog, goal, vars, opts).whenComplete((r, t) -> {
            inFlight.remove(token);
            if (t != null) {
                computer.queueEvent(EVENT, new Object[]{ token, false,
                    "internal: " + t.getMessage() });
            } else if (r.ok()) {
                computer.queueEvent(EVENT, new Object[]{ token, true, r.solutions() });
            } else {
                computer.queueEvent(EVENT, new Object[]{ token, false, r.error() });
            }
        });
        return MethodResult.pullEvent(EVENT, new TokenCallback(token));
    }

    @LuaFunction
    public final MethodResult eval(IArguments args) throws LuaException {
        String prog = args.getString(0);
        Optional<String> goal = args.optString(1);
        long token = TOKENS.incrementAndGet();
        service.eval(prog, goal.orElse(null)).whenComplete((r, t) -> {
            if (t != null) {
                computer.queueEvent(EVENT, new Object[]{ token, false,
                    "internal: " + t.getMessage() });
            } else if (r.ok()) {
                computer.queueEvent(EVENT, new Object[]{ token, true, r.stdout() });
            } else {
                computer.queueEvent(EVENT, new Object[]{ token, false, r.error() });
            }
        });
        return MethodResult.pullEvent(EVENT, new TokenCallback(token));
    }

    /** Resumes only on our token; re-yields on anyone else's picat_done. */
    private record TokenCallback(long token) implements ILuaCallback {
        @Override public MethodResult resume(Object[] event) {
            // event = { "picat_done", token, ok, payload }
            if (event.length >= 4 && event[1] instanceof Number n
                    && n.longValue() == token) {
                return MethodResult.of(event[2], event[3]);
            }
            return MethodResult.pullEvent(EVENT, this);
        }
    }

    private static List<String> toStringList(Map<?, ?> t) throws LuaException {
        List<String> out = new ArrayList<>();
        for (int i = 1; t.containsKey((double) i); i++) {
            out.add(String.valueOf(t.get((double) i)));
        }
        return out;
    }
}
```

API-surface caveats (same spirit as Task 6): exact names — `IComputerSystem`
vs `IComputerAccess`, `queueEvent` signature, `IArguments.getTable` value
types, whether `ILuaAPIFactory.create` may return null — verify against the
CC:Tweaked javadoc for the pinned version
(https://tweaked.cc/mc-1.21.x/javadoc/). The pattern (submit → pullEvent with
token filter → queueEvent on completion) is the contract.

**Step 4: Build** — `nix develop -c gradle :mod:build -x test` → SUCCESS.

**Step 5: Uncomment** the `PicatApiRegistration.register()` call in
`PicatCcMod`, rebuild, commit:
`git add -A && git commit -m "mod: picat global with query/eval via yield+event"`

### Task 14: In-game smoke test script + acceptance

**Files:**
- Create: `mod/src/main/resources/data/picat-cc/lua/picat_test.lua` (also usable standalone)
- Create: `docs/acceptance.md`

**Step 1: Write `picat_test.lua`**

```lua
-- picat-cc smoke test: run on any CC computer with the mod installed.
local function check(name, cond, detail)
    print((cond and "ok   " or "FAIL ") .. name .. (cond and "" or (": " .. tostring(detail))))
    return cond
end

local all = true
local function t(name, cond, detail) all = check(name, cond, detail) and all end

-- 1. global exists
t("picat global", type(picat) == "table" and type(picat.query) == "function")

-- 2. trivial query
local ok, sols = picat.query("", "X = 1 + 1", {"X"})
t("arith", ok and sols[1].X == 2, sols)

-- 3. struct marshalling
ok, sols = picat.query("", "A = $mine({0,63,5})", {"A"})
t("struct", ok and sols[1].A.f == "mine" and sols[1].A.args[1][2] == 63, sols)

-- 4. multiple solutions
ok, sols = picat.query("", "member(X,[a,b,c])", {"X"}, {max = 3})
t("backtracking", ok and #sols == 3 and sols[2].X == "b", sols)

-- 5. bind in
ok, sols = picat.query("", "S = sum(L)", {"S"}, {bind = {L = {1, 2, 3}}})
t("bind", ok and sols[1].S == 6, sols)

-- 6. goal failure taxonomy
ok, sols = picat.query("", "1 = 2", {})
t("goal-failed", not ok and sols == "goal failed", sols)

-- 7. eval
ok, sols = picat.eval("main => println(hello).")
t("eval", ok and sols:find("hello"), sols)

-- 8. planner (tiny)
local prog = [[
import planner.
final({Done}) => Done == 3.
action({N}, To, Action, Cost) => To = {N+1}, Action = $step(N), Cost = 1.
go(Plan) => plan({0}, Plan).
]]
ok, sols = picat.query(prog, "go(Plan)", {"Plan"}, {timeout = 30})
t("planner", ok and #sols[1].Plan == 3 and sols[1].Plan[1].f == "step", sols)

print(all and "ALL PASS" or "FAILURES — see above")
```

**Step 2: Write `docs/acceptance.md`** — exact steps:

```markdown
# In-game acceptance

1. `nix develop -c gradle :mod:runClient` (Loom dev client; CC:Tweaked is on
   the runtime classpath via modLocalRuntime).
2. Create a world, place a CC computer.
3. Copy picat_test.lua onto it (drag-drop the file onto the MC window writes
   it to the computer's dir under `saves/<world>/computercraft/computer/0/`),
   then run `picat_test`.
4. Expect `ALL PASS`.
5. Turtle acceptance: place a turtle, give it fuel + a pickaxe; copy
   `examples/quarry_turtle.lua` + `examples/quarry_small.pi`; run it; the
   turtle should mine a 2x2x2 pit, dumping into a chest behind its start.
6. Server deploy: copy `mod/build/libs/picat-cc-0.1.0.jar` to the 1.21.8
   server's `mods/` (CC:Tweaked must already be present). Re-run picat_test
   on a real computer.
```

**Step 3: Run the acceptance** (requires a display; coordinate with the user
if running headless). Record results in `docs/acceptance.md` as a checklist.

**Step 4: Commit** — `git add -A && git commit -m "mod: in-game smoke script and acceptance procedure"`

### Task 15: Turtle quarry example (the original motivation)

**Files:**
- Create: `examples/quarry_small.pi` (the Task 11 test resource, copied)
- Create: `examples/quarry_turtle.lua`

**Step 1: Write `examples/quarry_turtle.lua`** — a minimal executor for the
plan format (goTo via relative tracking, dispatch on `a.f`/atom):

```lua
-- Executes a picat-cc quarry plan. Turtle starts at {0, surface_y+1, 0}
-- facing +z, chest for dumping behind it, fuel chest at {chunk-1, y, 0}.
local f = fs.open("quarry_small.pi", "r")
local prog = f.readAll(); f.close()

print("solving...")
local ok, sols = picat.query(prog, "do_plan(Plan, Cost)", {"Plan", "Cost"},
                             {timeout = 120})
if not ok then error("no plan: " .. tostring(sols)) end
local plan, cost = sols[1].Plan, sols[1].Cost
print(("plan: %d actions, %d fuel"):format(#plan, cost))

local pos = {0, 0, 0}   -- relative to start; y negative = down
local function face(dz) --[[ omitted: track heading, turn ]] end
local function goTo(target) --[[ omitted: axis-by-axis moves updating pos ]] end
-- NOTE for implementer: write real goTo/face (~40 lines, standard turtle
-- nav); the plan's coords are absolute in the .pi model — translate by the
-- configured start_pos.

for i, a in ipairs(plan) do
    if a == "dump" then
        goTo(STORAGE); for s = 1, 16 do turtle.select(s); turtle.drop() end
    elseif a == "refuel" then
        goTo(FUEL); turtle.suck(); turtle.refuel()
    else
        goTo(a.args[1]); turtle.digDown()
    end
end
print("quarry complete")
```

Flesh out `goTo`/`face`/constants for real (the plan file gives exact chest
coordinates; mirror them). Test in the dev client against a real turtle.

**Step 2: Commit** — `git add -A && git commit -m "examples: turtle quarry executor driving a Picat plan"`

### Task 16: README + wrap-up

**Files:**
- Create: `README.md` (what it is, build instructions: `nix develop -c make resources && nix develop -c gradle build`, the Lua API reference from the design doc, the timeout caveat, version matrix)
- Modify: design doc status line → `Implemented through v0.1.0`

Commit: `git add -A && git commit -m "docs: README and design status"`.
Then use @superpowers:finishing-a-development-branch.

---

## Risk register (ordered)

1. **Task 3/6 — wasm32 + SjLj is the whole ballgame.** Everything else is
   plumbing. Budget accordingly; STOP at the gate if the ladder is exhausted.
2. **Picat 32-bit bit-rot** — `-DM64BITS` removal may surface deep VM
   assumptions. The symptom table in Task 3 says when to stop patching.
3. **Chicory/CC:Tweaked API drift** — code sketches pin the SHAPE; method
   names must be verified against the pinned versions' docs (noted inline in
   Tasks 6 and 13).
4. **Shim Picat-isms** — `parse_term` binding shape, string/list overlap.
   Mitigated by probe-first TDD (Task 8 notes).
5. **jar-in-jar/guava conflicts** (Task 12) — fall back to shadow-jar.
