package cc.picat.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
        // No /data mount => the open fails with Picat's
        //   error(existence_error(source_sink,/data/x.txt),open)
        // surfaced through the taxonomy as an "error:" string. We pin the
        // existence_error AND the path so a regression that silently mounts
        // /data (making the open succeed, or fail some other way) is caught.
        assertTrue(r.error().startsWith("error:"), r.error());
        assertTrue(r.error().contains("existence_error"), r.error());
        assertTrue(r.error().contains("/data/x.txt"), r.error());
    }

    /**
     * THE MOTIVATING CASE: train a tiny XOR net via the nn (FANN) module, save
     * it to {@code /data}, then reload + predict in a SEPARATE fresh instance.
     * The two-instances structure is the point — it proves the saved model
     * round-trips through the host mount and is usable from a clean engine
     * instance, not that FANN always converges.
     *
     * <p><b>Nondeterminism, handled by retry:</b> FANN seeds {@code rand()} from
     * {@code gettimeofday} under WASI (no {@code /dev/urandom}, and
     * {@code FANN_NO_SEED} is not defined — see {@code fann.c:1834-1857}), so
     * every run starts from a different random weight init. A 2-3-1 net
     * occasionally lands in a local minimum that does not separate the XOR
     * classes. We therefore (a) retry training up to 3 times and (b) assert
     * class <em>separation</em> on the loaded model — the two "true" inputs
     * (1,0)/(0,1) must both score above the two "false" inputs (0,0)/(1,1) by a
     * margin — rather than an absolute threshold.
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
        // Load the saved model and score all four XOR inputs in one go, so the
        // separation check runs against the persisted, reloaded weights.
        String predProg = """
            import nn.
            predict() = Outs =>
                NN = nn_load("/data/xor.net"),
                R10 = nn_run(NN, [1.0,0.0]),
                R01 = nn_run(NN, [0.0,1.0]),
                R00 = nn_run(NN, [0.0,0.0]),
                R11 = nn_run(NN, [1.0,1.0]),
                Outs = [R10[1], R01[1], R00[1], R11[1]],
                nn_destroy(NN).
            """;
        Path net = tmp.resolve("xor.net");
        AssertionError last = null;
        for (int a = 1; a <= 3; a++) {
            final int attempt = a;
            var rt = svc.query(trainProg, "train()", List.of(),
                Map.of("fsPath", tmp)).get();
            assertTrue(rt.ok(), () -> "nn train error: " + rt.error());
            assertTrue(Files.exists(net), "xor.net not written to host");
            assertTrue(Files.size(net) > 0, "xor.net is empty");

            var rp = svc.query(predProg, "Ys = predict()",
                List.of("Ys"), Map.of("fsPath", tmp)).get();
            assertTrue(rp.ok(), () -> "nn predict error: " + rp.error());
            @SuppressWarnings("unchecked")
            var ys = (List<Object>) rp.solutions().get(0).get("Ys");
            assertEquals(4, ys.size(), "expected 4 predictions, got " + ys);
            double t10 = (Double) ys.get(0), t01 = (Double) ys.get(1);
            double f00 = (Double) ys.get(2), f11 = (Double) ys.get(3);
            // Separation: both true-class outputs exceed both false-class
            // outputs by a clear margin on the reloaded model.
            double margin = 0.2;
            double minTrue = Math.min(t10, t01), maxFalse = Math.max(f00, f11);
            try {
                assertTrue(minTrue - maxFalse > margin, () -> String.format(
                    "XOR classes not separated (attempt %d): true{(1,0)=%.3f,(0,1)=%.3f}"
                    + " false{(0,0)=%.3f,(1,1)=%.3f}", attempt, t10, t01, f00, f11));
                return; // converged + round-tripped: done
            } catch (AssertionError e) {
                last = e; // local minimum this attempt; retrain
            }
        }
        throw new AssertionError("XOR net failed to separate after 3 attempts", last);
    }
}
