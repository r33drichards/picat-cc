package cc.picat.mod;

import cc.picat.engine.PicatService;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The {@code picat} Lua global, one instance per CC computer (the
 * {@code ILuaAPIFactory} contract creates one per {@code IComputerSystem}).
 *
 * <p>This layer is deliberately THIN: it decodes Lua arguments, validates and
 * resolves the optional {@code fs} mount path, submits to the engine's
 * {@link PicatService}, and plumbs the asynchronous result back through CC's
 * yield/event mechanism. All real work happens in the engine.
 *
 * <h2>Yield + event pattern</h2>
 * A blocking {@code query}/{@code eval} cannot run on CC's Lua/main thread, so
 * we:
 * <ol>
 *   <li>submit the job to the engine (returns a {@link CompletableFuture});</li>
 *   <li>return {@link MethodResult#pullEvent} so the Lua coroutine yields and
 *       waits for the {@code picat_done} event;</li>
 *   <li>in the future's {@code whenComplete}, {@code queueEvent("picat_done",
 *       token, ok, payload)} — kept cheap because the future may complete on an
 *       engine-internal thread (worker, or the shared timeout scheduler);</li>
 *   <li>the {@link TokenCallback} resumes, filters on its token (so concurrent
 *       {@code picat_done} events from other sources don't cross wires), and
 *       returns {@code ok, payload} to Lua.</li>
 * </ol>
 *
 * <h2>In-flight cap</h2>
 * One job per computer. {@link #inFlight} is set when a job is submitted and
 * cleared in {@code whenComplete} (NOT in {@code resume}): if the Lua program
 * is terminated (Ctrl+T) while yielded, {@code pullEvent} raises a
 * {@code LuaException} and {@code resume} never runs, but the engine job keeps
 * going server-side and must still release the slot when it finishes.
 *
 * <h2>fs mount resolution (option (b))</h2>
 * The {@code fs} opt names a sub-directory, relative to this computer's own
 * save directory, to mount read-write at {@code /data} inside the Picat guest.
 * We derive the host path directly:
 * <pre>{@code <worldPath(ROOT)>/computercraft/computer/<id>/<fs>}</pre>
 * This layout is verified against CC:Tweaked 1.116.1 (ServerContext.storageDir
 * resolves {@code worldPath(ROOT)/computercraft}; computers live under the
 * {@code computer} kind / numeric id; createSaveDirMount uses the same root).
 * It couples us to that on-disk layout, which has been stable across CC
 * versions — documented as a known coupling. The alternative,
 * {@code createSaveDirMount}, yields a {@code WritableMount} not a {@code Path},
 * so it cannot feed the engine's {@code fsPath} (a {@code java.nio.file.Path}).
 */
public final class PicatLuaAPI implements ILuaAPI {
    private static final Logger LOG = LoggerFactory.getLogger("picat-cc");
    private static final String VERSION = "0.1.0";

    private final IComputerSystem computer;
    private final Supplier<PicatService> serviceSupplier;

    /** One job per computer. */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    /** Distinguishes this computer's picat_done events from any other source. */
    private final AtomicLong nextToken = new AtomicLong(0);

    public PicatLuaAPI(IComputerSystem computer, Supplier<PicatService> serviceSupplier) {
        this.computer = computer;
        this.serviceSupplier = serviceSupplier;
    }

    @Override
    public String[] getNames() {
        return new String[]{"picat"};
    }

    // --- Lua-callable surface ---------------------------------------------

    /** {@code picat.version()} → version string (cheap; handy for smoke tests). */
    @LuaFunction
    public final String version() {
        return VERSION;
    }

    /**
     * {@code picat.query(prog, goal [, vars] [, opts])} → {@code ok, solutions|err}.
     *
     * <p>{@code vars} is an optional array of goal-variable names to capture
     * (default {@code {}}); {@code opts} an optional table:
     * {@code timeout} (seconds), {@code max} (solution cap), {@code bind}
     * (var→value map), {@code fs} (sub-dir to mount at {@code /data}).
     */
    @LuaFunction
    public final MethodResult query(IArguments args) throws LuaException {
        String prog = args.getString(0);
        String goal = args.getString(1);
        List<String> vars = decodeVars(args, 2);
        Map<String, Object> opts = decodeOpts(args, 3);

        PicatService svc = service();
        if (svc == null) {
            return MethodResult.of(false, "internal: picat engine not available");
        }
        // fs resolution may fail validation → immediate error, no submission.
        try {
            resolveFsInto(opts);
        } catch (LuaException fsErr) {
            return MethodResult.of(false, fsErr.getMessage());
        }
        if (!inFlight.compareAndSet(false, true)) {
            return MethodResult.of(false,
                "busy: a picat job is already running on this computer");
        }
        return dispatch(svc.query(prog, goal, vars, opts), Result.SOLUTIONS);
    }

    /**
     * {@code picat.eval(prog [, goal] [, opts])} → {@code ok, stdout|err}.
     *
     * <p>{@code goal} is optional/nil (defaults to {@code main}). {@code opts}:
     * {@code timeout} (seconds), {@code fs} (sub-dir to mount at {@code /data}).
     */
    @LuaFunction
    public final MethodResult eval(IArguments args) throws LuaException {
        String prog = args.getString(0);
        // goal: optional, may be nil/absent ⇒ engine defaults to "main".
        String goal = args.optString(1).orElse(null);
        Map<String, Object> opts = decodeOpts(args, 2);

        PicatService svc = service();
        if (svc == null) {
            return MethodResult.of(false, "internal: picat engine not available");
        }
        try {
            resolveFsInto(opts);
        } catch (LuaException fsErr) {
            return MethodResult.of(false, fsErr.getMessage());
        }
        if (!inFlight.compareAndSet(false, true)) {
            return MethodResult.of(false,
                "busy: a picat job is already running on this computer");
        }
        return dispatch(svc.eval(prog, goal, opts), Result.STDOUT);
    }

    // --- dispatch / event plumbing ----------------------------------------

    private enum Result { SOLUTIONS, STDOUT }

    /**
     * Attach the completion hook (clears in-flight, queues the done event) and
     * yield waiting for it. {@code which} selects the success payload field.
     */
    private MethodResult dispatch(CompletableFuture<PicatService.Result> future, Result which) {
        long token = nextToken.incrementAndGet();
        future.whenComplete((res, ex) -> {
            // Cheap body only — may run on an engine worker or the timeout
            // scheduler thread. Release the slot FIRST so a terminated coroutine
            // (which never resumes) still frees the computer.
            inFlight.set(false);
            Object[] event;
            if (ex != null) {
                LOG.warn("picat job failed on computer {}", safeId(), ex);
                event = new Object[]{token, false, "internal: " + ex.getClass().getSimpleName()};
            } else if (res.ok()) {
                Object payload = which == Result.SOLUTIONS ? res.solutions() : res.stdout();
                event = new Object[]{token, true, payload};
            } else {
                event = new Object[]{token, false, res.error()};
            }
            try {
                computer.queueEvent("picat_done", event);
            } catch (RuntimeException qe) {
                // Computer may have been unloaded/shut down between submit and
                // completion; nothing more we can do — the slot is already freed.
                LOG.debug("queueEvent picat_done dropped for computer {}", safeId(), qe);
            }
        });
        return MethodResult.pullEvent("picat_done", new TokenCallback(token, which));
    }

    /**
     * Resumes on each {@code picat_done} event; returns to Lua only for the
     * event carrying our token, otherwise re-pulls. Terminate (Ctrl+T) is
     * handled by CC: {@code pullEvent} raises a LuaException automatically and
     * this callback is never invoked — the in-flight flag is freed by
     * {@code whenComplete}, so that path is safe.
     */
    private final class TokenCallback implements ILuaCallback {
        private final long token;
        private final Result which;

        TokenCallback(long token, Result which) {
            this.token = token;
            this.which = which;
        }

        @Override
        public MethodResult resume(Object[] event) {
            // event = {"picat_done", token, ok, payload}
            if (event.length >= 4
                    && event[1] instanceof Number n
                    && n.longValue() == token) {
                return MethodResult.of(event[2], event[3]);
            }
            // Not ours (or malformed) — keep waiting.
            return MethodResult.pullEvent("picat_done", this);
        }
    }

    // --- argument decoding -------------------------------------------------

    /**
     * Decode the optional {@code vars} array at {@code index} into an ordered
     * {@code List<String>}. CC delivers array tables as {@code Map<?,?>} with
     * {@code Double} keys 1.0..n; we sort by key to PRESERVE ORDER (the engine
     * pairs captured values to this list positionally).
     */
    private static List<String> decodeVars(IArguments args, int index) throws LuaException {
        if (index >= args.count() || args.get(index) == null) {
            return List.of();
        }
        Map<?, ?> table = args.getTable(index);
        SortedMap<Double, String> ordered = new TreeMap<>();
        for (Map.Entry<?, ?> e : table.entrySet()) {
            if (!(e.getKey() instanceof Number key)) {
                throw new LuaException("bad argument: vars must be an array of strings");
            }
            if (!(e.getValue() instanceof String name)) {
                throw new LuaException("bad argument: vars must be an array of strings");
            }
            ordered.put(key.doubleValue(), name);
        }
        return new ArrayList<>(ordered.values());
    }

    /**
     * Decode the optional {@code opts} table at {@code index} into a mutable map
     * the engine understands. Recognised keys: {@code timeout} (Number→opts),
     * {@code max} (Number), {@code bind} (Map). The {@code fs} key is carried
     * through verbatim (a String) and resolved later by {@link #resolveFsInto}.
     * Unknown keys are ignored.
     */
    private static Map<String, Object> decodeOpts(IArguments args, int index) throws LuaException {
        Map<String, Object> opts = new LinkedHashMap<>();
        if (index >= args.count() || args.get(index) == null) {
            return opts;
        }
        Map<?, ?> table = args.getTable(index);
        copyNumber(table, "timeout", opts);
        copyNumber(table, "max", opts);
        Object bind = table.get("bind");
        if (bind instanceof Map<?, ?> bindMap) {
            Map<String, Object> binds = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : bindMap.entrySet()) {
                binds.put(String.valueOf(e.getKey()), e.getValue());
            }
            opts.put("bind", binds);
        } else if (bind != null) {
            throw new LuaException("bad argument: opts.bind must be a table");
        }
        Object fs = table.get("fs");
        if (fs instanceof String fsStr) {
            opts.put("fs", fsStr);
        } else if (fs != null) {
            throw new LuaException("bad argument: opts.fs must be a string");
        }
        return opts;
    }

    private static void copyNumber(Map<?, ?> table, String key, Map<String, Object> out)
            throws LuaException {
        Object v = table.get(key);
        if (v instanceof Number n) {
            out.put(key, n);
        } else if (v != null) {
            throw new LuaException("bad argument: opts." + key + " must be a number");
        }
    }

    // --- fs path resolution (option (b)) ----------------------------------

    /**
     * If {@code opts} carries an {@code fs} string, validate it and replace it
     * with the engine's {@code fsPath} key (a resolved {@link Path}). Creates
     * the directory. On any validation failure throws {@link LuaException} whose
     * message extends the engine taxonomy with an {@code fs:} prefix.
     */
    private void resolveFsInto(Map<String, Object> opts) throws LuaException {
        Object fsObj = opts.remove("fs");
        if (fsObj == null) {
            return;
        }
        String sub = (String) fsObj;
        if (sub.isBlank()) {
            throw new LuaException("fs: invalid path");
        }
        Path root = computerRoot();
        if (root == null) {
            throw new LuaException("fs: storage unavailable");
        }
        Path resolved;
        try {
            Path rel = Path.of(sub);
            // Reject absolute paths and any ".." segment (defence in depth — the
            // startsWith check below is the real guard, but bail early & clearly).
            if (rel.isAbsolute()) {
                throw new LuaException("fs: invalid path");
            }
            for (Path part : rel) {
                if ("..".equals(part.toString())) {
                    throw new LuaException("fs: invalid path");
                }
            }
            resolved = root.resolve(rel).normalize();
        } catch (java.nio.file.InvalidPathException bad) {
            throw new LuaException("fs: invalid path");
        }
        // Final containment guard: resolved must stay under the computer root.
        Path normRoot = root.normalize();
        if (!resolved.startsWith(normRoot)) {
            throw new LuaException("fs: invalid path");
        }
        try {
            Files.createDirectories(resolved);
        } catch (Exception e) {
            throw new LuaException("fs: cannot create directory");
        }
        opts.put("fsPath", resolved);
    }

    /**
     * This computer's save directory:
     * {@code <worldPath(ROOT)>/computercraft/computer/<id>}. Returns null if the
     * server context is somehow unavailable.
     */
    private Path computerRoot() {
        try {
            Path world = computer.getLevel().getServer().getWorldPath(LevelResource.ROOT);
            return world.resolve("computercraft").resolve("computer")
                .resolve(Integer.toString(computer.getID()));
        } catch (RuntimeException e) {
            LOG.warn("Could not resolve computer storage root", e);
            return null;
        }
    }

    // --- misc --------------------------------------------------------------

    private PicatService service() {
        return serviceSupplier.get();
    }

    private int safeId() {
        try {
            return computer.getID();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
