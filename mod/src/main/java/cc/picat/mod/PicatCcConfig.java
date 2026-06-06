package cc.picat.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code picat-cc.json} in the Fabric config dir; created with defaults on
 * first run. On any read/parse error the defaults are returned (and logged) so
 * a corrupt config never prevents the engine from coming up.
 *
 * <p>Fields are public and plain so Gson can round-trip them with no custom
 * adapters; they double as the live config object handed to
 * {@link PicatApiRegistration}.
 */
public final class PicatCcConfig {
    private static final Logger LOG = LoggerFactory.getLogger("picat-cc");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Worker pool size in the engine (concurrent in-flight goals, process-wide). */
    public int workerThreads = 6;
    /** Reject new work once this many timed-out zombie jobs are still outstanding. */
    public int maxAbandonedJobs = 32;
    /** Hard cap (seconds) on any single job's requested timeout. */
    public int maxTimeoutSeconds = 300;
    /** How many Picat jobs a single computer may have in flight at once. */
    public int maxJobsPerComputer = 8;

    public static PicatCcConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("picat-cc.json");
        if (Files.isRegularFile(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                PicatCcConfig cfg = GSON.fromJson(r, PicatCcConfig.class);
                if (cfg == null) {        // empty/whitespace file ⇒ Gson returns null
                    LOG.warn("picat-cc config {} was empty; using defaults", path);
                    return new PicatCcConfig();
                }
                return cfg.sanitised();
            } catch (Exception e) {
                LOG.warn("Failed to read picat-cc config {}; using defaults", path, e);
                return new PicatCcConfig();
            }
        }
        // First run: write defaults so operators have something to edit.
        PicatCcConfig cfg = new PicatCcConfig();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
        } catch (IOException e) {
            LOG.warn("Failed to write default picat-cc config {}; continuing with defaults", path, e);
        }
        return cfg;
    }

    /** Clamp deserialized values into sane ranges (a hand-edited config may be silly). */
    private PicatCcConfig sanitised() {
        if (workerThreads < 1) workerThreads = 1;
        if (maxAbandonedJobs < 0) maxAbandonedJobs = 0;
        if (maxTimeoutSeconds < 1) maxTimeoutSeconds = 1;
        // Robustness floors: a timed-out solve becomes a zombie holding a worker
        // thread until it dies, and once `maxAbandonedJobs` zombies pile up the
        // engine rejects all new work ("busy: saturated") until restart. Enforce
        // headroom even on an older hand-written/persisted config so a handful of
        // stray timeouts cannot wedge the engine.
        if (workerThreads < 4) workerThreads = 4;
        if (maxAbandonedJobs < 16) maxAbandonedJobs = 16;
        if (maxJobsPerComputer < 2) maxJobsPerComputer = 2;
        return this;
    }
}
