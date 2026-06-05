package cc.picat.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the {@code /data} host mount (Task 11b): a real {@link Path} handed
 * to the engine is mounted read-write at {@code /data} in the WASM guest, so
 * Picat can persist files (nn models, etc.) that survive the fresh-instance
 * lifecycle and are visible to the host (and, in production, to CC Lua).
 */
class DataMountTest {
    PicatService svc;
    @BeforeEach void setUp() { svc = new PicatService(1, 2, 120_000); }
    @AfterEach void tearDown() { svc.shutdown(); }

    @Test void picatWritesFileVisibleOnHost(@TempDir Path tmp) throws Exception {
        String prog = """
            save() =>
                FD = open("/data/out.txt", write),
                print(FD, hello),
                close(FD).
            """;
        var r = svc.query(prog, "save()", List.of(),
            Map.of("fsPath", tmp)).get();
        assertTrue(r.ok(), () -> "error: " + r.error());
        assertEquals("hello", Files.readString(tmp.resolve("out.txt")));
    }

    @Test void picatReadsPreexistingHostFile(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("in.txt"), "41");
        String prog = """
            load() = X =>
                FD = open("/data/in.txt"),
                X = read_int(FD),
                close(FD).
            """;
        var r = svc.query(prog, "Y = load()", List.of("Y"),
            Map.of("fsPath", tmp)).get();
        assertTrue(r.ok(), () -> "error: " + r.error());
        assertEquals(41.0, r.solutions().get(0).get("Y"));
    }

    @Test void noFsOptMeansNoDataMount() throws Exception {
        String prog = """
            load() = X =>
                FD = open("/data/x.txt"),
                X = read_int(FD),
                close(FD).
            """;
        var r = svc.query(prog, "Y = load()", List.of("Y"), Map.of()).get();
        assertFalse(r.ok(), () -> "unexpectedly ok: " + r.solutions());
        // No /data mount => Picat cannot open the path. The exact message is
        // Picat's (existence_error / cannot_open / ...); we only pin that it is
        // surfaced as a real error, not silently swallowed.
        assertTrue(r.error().startsWith("error:") || r.error().startsWith("compile:"),
            r.error());
    }

    /**
     * THE MOTIVATING CASE: train a tiny XOR net via the nn (FANN) module, save
     * to {@code /data}, then reload + predict in a SECOND fresh instance.
     * Characterization probe — see class result/@Disabled message if FANN
     * proves unusable under WASM.
     */
    @Test void nnModuleRoundTrip(@TempDir Path tmp) throws Exception {
        // FANN train-file format: "<numPairs> <numInput> <numOutput>" then,
        // per pair, an input line and an output line. We stage it under /data
        // rather than passing in-memory Data: nn_train serializes in-memory data
        // to a "__tmp.data" file in the (read-only) cwd, which traps under WASI.
        Files.writeString(tmp.resolve("xor.data"), """
            4 2 1
            0 0
            0
            0 1
            1
            1 0
            1
            1 1
            0
            """);
        String trainProg = """
            import nn.
            train() =>
                NN = new_nn([2,3,1]),
                nn_train(NN, "/data/xor.data", [$maxep(5000), $derror(0.001), $report(0)]),
                nn_save(NN, "/data/xor.net"),
                nn_destroy(NN).
            """;
        var rt = svc.query(trainProg, "train()", List.of(),
            Map.of("fsPath", tmp)).get();
        assertTrue(rt.ok(), () -> "nn train error: " + rt.error());
        Path net = tmp.resolve("xor.net");
        assertTrue(Files.exists(net), "xor.net not written to host");
        assertTrue(Files.size(net) > 0, "xor.net is empty");

        String predProg = """
            import nn.
            predict(In) = Out =>
                NN = nn_load("/data/xor.net"),
                R = nn_run(NN, In),
                Out = R[1],
                nn_destroy(NN).
            """;
        var rp = svc.query(predProg, "Y = predict([1.0,0.0])",
            List.of("Y"), Map.of("fsPath", tmp)).get();
        assertTrue(rp.ok(), () -> "nn predict error: " + rp.error());
        Object y = rp.solutions().get(0).get("Y");
        assertTrue(y instanceof Double, "prediction not a number: " + y);
        double out = (Double) y;
        assertTrue(out > 0.5, "xor(1,0) should be ~1, got " + out);
    }
}
