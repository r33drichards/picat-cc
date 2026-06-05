package cc.picat.engine;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Runs one Picat goal in a fresh, fully isolated WASM instance. */
public final class PicatRunner {

    public record RawResult(int exitCode, String stdout, String stderr,
                            String responseJson) {}

    private static final WasmModule MODULE = loadModule();

    /** Compile the 5.5MB module to JVM bytecode ONCE, at class load, and reuse
     *  the resulting machine factory across every instance. Recompiling per call
     *  cost ~7s/call (dominating the Task 8-11 TDD loops). */
    private static final Function<Instance, Machine> MACHINE =
        MachineFactoryCompiler.compile(MODULE);

    private static final List<String> STDLIB = listStdlib();

    private PicatRunner() {}

    /** files: guest path -> content, all under /work/. argv[0] = "picat". */
    public static RawResult runRaw(Map<String, String> files, List<String> argv,
                                   long timeoutMs) {
        return runRaw(files, argv, timeoutMs, null);
    }

    /**
     * As {@link #runRaw(Map, List, long)} but, when {@code dataDir} is non-null,
     * mounts that <em>real</em> host {@link Path} read-write at {@code /data} in
     * the guest so a goal can persist files (nn models, etc.) that outlive the
     * fresh WASM instance.
     *
     * <p><b>Security:</b> {@code dataDir} is mounted <em>as-is</em>. The engine
     * layer trusts its caller; path validation (rejecting absolute/{@code ..}
     * escapes, confining to the computer's own CC save dir) is the mod layer's
     * responsibility — see the "fs option" security invariants in
     * {@code docs/plans/2026-06-04-picat-cc-ffi-design.md}. The {@code dataDir}
     * may live on a different {@link FileSystem} provider than the jimfs
     * {@code /work} and {@code /picat} mounts; Chicory's WASI layer resolves
     * each guest path against its own mounted {@link Path}, so the providers do
     * not mix (a {@code /data/...} guest path stays on {@code dataDir}'s
     * provider).
     */
    public static RawResult runRaw(Map<String, String> files, List<String> argv,
                                   long timeoutMs, Path dataDir) {
        for (String key : files.keySet()) {
            if (!key.startsWith("/work/")) {
                throw new IllegalArgumentException(
                    "file paths must be under /work/, got: " + key);
            }
        }
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        // Chicory's WASI pathFilestatGet calls Files.readAttributes(path, "unix:*").
        // jimfs Configuration.unix() does not enable the "unix" attribute view by
        // default, so we add it here, otherwise the provider lookup returns null
        // and Picat's first stat() of a lib file NPEs deep in jimfs.
        Configuration config = Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
        try (FileSystem fs = Jimfs.newFileSystem(config)) {
            Path picatDir = fs.getPath("/picat");
            Path libDir = picatDir.resolve("lib");
            Files.createDirectories(libDir);
            for (String name : STDLIB) {
                try (var in = PicatRunner.class
                        .getResourceAsStream("/picat/lib/" + name)) {
                    if (in == null) {
                        throw new IllegalStateException("stdlib file missing from "
                            + "classpath: /picat/lib/" + name
                            + " (STDLIB list drift vs `make resources`)");
                    }
                    Files.copy(in, libDir.resolve(name));
                }
            }
            Path workDir = fs.getPath("/work");
            Files.createDirectories(workDir);
            for (var e : files.entrySet()) {
                Files.writeString(fs.getPath(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            }
            // The FFI harness ships in the jar; copy it alongside the caller's
            // files so `picat /work/shim.pi` finds it (Task 8).
            try (var in = PicatRunner.class.getResourceAsStream("/picat/shim.pi")) {
                if (in == null) throw new IllegalStateException("shim.pi missing from resources");
                Files.copy(in, workDir.resolve("shim.pi"), StandardCopyOption.REPLACE_EXISTING);
            }

            WasiOptions.Builder optsBuilder = WasiOptions.builder()
                .withStdout(stdout)
                .withStderr(stderr)
                .withStdin(new ByteArrayInputStream(new byte[0]))
                .withArguments(argv)
                .withEnvironment("PICATPATH", "/picat/lib")
                .withDirectory("/picat", picatDir)
                .withDirectory("/work", workDir);
            if (dataDir != null) {
                // Real host path mounted at /data. Distinct FileSystem provider
                // from the jimfs mounts above is fine: WASI resolves /data/...
                // against this Path only.
                optsBuilder.withDirectory("/data", dataDir);
            }
            WasiOptions opts = optsBuilder.build();

            int exit = 0;
            try (var wasi = WasiPreview1.builder().withOptions(opts).build()) {
                var store = new Store().addFunction(wasi.toHostFunctions());
                Instance.builder(MODULE)
                    .withMachineFactory(MACHINE)
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

    private static WasmModule loadModule() {
        InputStream in = PicatRunner.class.getResourceAsStream("/picat/picat.wasm");
        if (in == null) {
            throw new IllegalStateException(
                "picat.wasm not on classpath; run `make resources`");
        }
        return Parser.parse(in);
    }

    private static List<String> listStdlib() {
        return List.of("basic.pi", "common_constr.pi", "cp.pi",
            "cp_sat_mip_smt.pi", "datetime.pi", "io.pi", "math.pi", "mip.pi",
            "mip_aux.pi", "mip_smt.pi", "nn.pi", "ordset.pi", "os.pi",
            "picat_lib_aux.pi", "planner.pi", "prism.pi", "prism_ex.pi",
            "sat.pi", "sat_bv.pi", "sat_mip.pi", "sat_mip_smt.pi", "smt.pi",
            "smt_aux.pi", "sys.pi", "temp.pi", "util.pi");
    }
}
