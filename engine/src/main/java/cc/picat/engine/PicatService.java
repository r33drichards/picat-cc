package cc.picat.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.gson.Gson;

/**
 * Public engine API for the Minecraft mod. Submits Picat goals to a fixed
 * worker pool, enforces a wall-clock timeout, and maps every raw outcome onto
 * the error taxonomy contracted with the Lua layer:
 *
 * <pre>
 *   compile: &lt;detail&gt;
 *   goal failed
 *   timeout
 *   memory limit
 *   bind: &lt;detail&gt;
 *   error: &lt;picat error msg&gt;
 *   busy: solver saturated by timed-out jobs
 *   internal: &lt;detail&gt;
 * </pre>
 *
 * <h2>Timeout-by-abandonment</h2>
 * Chicory cannot interrupt running WASM, so a runaway goal is handled by
 * <em>abandoning</em> the worker thread (it keeps burning a daemon thread until
 * the JVM exits) and surfacing {@code timeout} to the caller immediately.
 *
 * <p>Bookkeeping is made race-free with a single {@link AtomicBoolean} per job:
 * the timeout firing and the worker finishing both try to CAS {@code settled}
 * from false to true; exactly one wins.
 * <ul>
 *   <li>If the <b>worker</b> wins it completes the public future with the real
 *       result and the abandoned counter is never touched.</li>
 *   <li>If the <b>timeout</b> wins it completes the public future with
 *       {@code timeout} and increments {@code abandoned}. The worker future
 *       carries a {@code whenComplete} continuation that, having lost the CAS,
 *       decrements {@code abandoned} when the zombie eventually finishes. A
 *       goal that truly never returns (e.g. {@code spin(N) =&gt; spin(N+1)})
 *       leaves the counter permanently incremented — that is the intended
 *       saturation pressure.</li>
 * </ul>
 * Because the increment (timeout path) happens-before the decrement can run
 * (the decrement only fires inside the same continuation that already lost the
 * CAS, and only when the worker actually completes), the counter never goes
 * negative and never leaks on the fast path where the worker beats the clock.
 *
 * <p>Saturation guard: new work is rejected with {@code busy: ...} the moment
 * {@code abandoned.get() >= maxAbandoned}; such jobs are never enqueued, so a
 * pool drowning in zombies stops accepting load instead of growing unbounded.
 */
public final class PicatService {

    private static final Gson GSON = new Gson();

    /** Picat variable syntax: [A-Z_][A-Za-z0-9_]* . */
    private static final Pattern VAR_NAME = Pattern.compile("[A-Z_][A-Za-z0-9_]*");

    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    /** Result of a query/eval. {@code ok} discriminates: on success
     *  {@code solutions}/{@code stdout} are populated and {@code error} is null;
     *  on failure {@code error} carries a taxonomy string and the others are
     *  empty/null. */
    public record Result(boolean ok, List<Map<String, Object>> solutions,
                         String stdout, String error) {

        static Result ok(List<Map<String, Object>> sols) {
            return new Result(true, sols, null, null);
        }

        static Result okOut(String stdout) {
            return new Result(true, List.of(), stdout, null);
        }

        static Result err(String error) {
            return new Result(false, List.of(), null, error);
        }
    }

    private final ExecutorService pool;
    private final ScheduledThreadPoolExecutor timer;
    private final int maxAbandoned;
    private final long maxTimeoutMs;
    private final AtomicInteger abandoned = new AtomicInteger(0);

    /**
     * @param threads      worker pool size (concurrent in-flight goals)
     * @param maxAbandoned reject new work once this many timed-out zombies are
     *                     still outstanding
     * @param maxTimeoutMs hard cap on any single job's timeout, in millis
     */
    public PicatService(int threads, int maxAbandoned, long maxTimeoutMs) {
        this.maxAbandoned = maxAbandoned;
        this.maxTimeoutMs = maxTimeoutMs;
        ThreadFactory workerFactory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(0);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "picat-worker-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = Executors.newFixedThreadPool(threads, workerFactory);
        // setRemoveOnCancelPolicy purges cancelled timeout tasks from the queue
        // immediately rather than leaving tombstones until their delay elapses,
        // so the common worker-wins path does not accumulate dead scheduled
        // tasks for the full timeout duration.
        this.timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "picat-timeout");
            t.setDaemon(true);
            return t;
        });
        this.timer.setRemoveOnCancelPolicy(true);
    }

    public int abandonedCount() {
        return abandoned.get();
    }

    public void shutdown() {
        pool.shutdownNow();   // do not await zombies; they are daemon threads
        timer.shutdownNow();
    }

    /**
     * Run a goal against a user program, capturing the named goal variables for
     * up to {@code max} solutions.
     *
     * <p><b>Completion thread:</b> the returned future may be completed on an
     * internal engine thread — a worker thread on the normal path, or the
     * scheduler thread ({@code picat-timeout}) on the timeout path. Consumers
     * must keep {@code whenComplete}/{@code thenApply} bodies cheap, or use the
     * {@code *Async} variants, to avoid blocking the timeout scheduler (which is
     * shared by every in-flight job).
     *
     * @param prog user Picat source (becomes /work/user.pi; may be empty)
     * @param goal goal text WITHOUT a trailing '.' terminator
     * @param vars goal variable names to capture per solution
     * @param opts optional knobs: {@code timeout} (Number, seconds),
     *             {@code max} (Number, solution cap), {@code bind}
     *             (Map&lt;String,Object&gt; var-name -&gt; value), {@code fsPath}
     *             (a {@link java.nio.file.Path} mounted read-write at
     *             {@code /data} in the guest; see {@link #fsPathOpt})
     */
    public CompletableFuture<Result> query(String prog, String goal,
            List<String> vars, Map<String, Object> opts) {
        long timeoutMs = resolveTimeout(opts);
        int max = resolveMax(opts);

        // Build request.pi up front so a bad bind is reported synchronously
        // (and never consumes a worker).
        String request;
        try {
            request = buildRequest(goal, vars, bindOpt(opts), max);
        } catch (IllegalArgumentException bad) {
            return CompletableFuture.completedFuture(Result.err(bindMessage(bad)));
        }

        Map<String, String> files = Map.of(
            "/work/user.pi", prog == null ? "" : prog,
            "/work/request.pi", request);
        return submit(files, PicatService::interpretQuery, timeoutMs,
            fsPathOpt(opts));
    }

    /**
     * Run a self-contained program for its stdout (a {@code main}-style driver).
     * Wraps {@code prog} with a driver predicate so output is captured exactly.
     *
     * <p><b>Completion thread:</b> same contract as {@link #query} — the future
     * may complete on an internal worker or the timeout-scheduler thread; keep
     * continuation bodies cheap or use the {@code *Async} variants.
     *
     * <p>The 2-arg form runs with the default (capped) timeout and no
     * {@code /data} mount; use {@link #eval(String, String, Map)} for those.
     *
     * @param prog user Picat source defining the entry point
     * @param goal entry goal text (without trailing '.'); null/blank ⇒ "main"
     */
    public CompletableFuture<Result> eval(String prog, String goal) {
        return eval(prog, goal, Map.of());
    }

    /**
     * As {@link #eval(String, String)} but honors {@code opts}: {@code timeout}
     * (Number, seconds) and {@code fsPath} (a {@link java.nio.file.Path} mounted
     * read-write at {@code /data}; see {@link #fsPathOpt}). {@code max} and
     * {@code bind} are ignored — eval is single-solution and var-free by design.
     *
     * @param prog user Picat source defining the entry point
     * @param goal entry goal text (without trailing '.'); null/blank ⇒ "main"
     * @param opts optional knobs: {@code timeout}, {@code fsPath}
     */
    public CompletableFuture<Result> eval(String prog, String goal,
            Map<String, Object> opts) {
        long timeoutMs = resolveTimeout(opts);
        String entry = (goal == null || goal.isBlank()) ? "main" : goal;
        // Drive through a fixed predicate so the captured goal is var-free and
        // single-solution; user output goes to stdout, which runRaw captures.
        String driverProg = (prog == null ? "" : prog)
            + "\npicat_cc_eval_main => " + entry + ".\n";
        String request;
        try {
            request = buildRequest("picat_cc_eval_main", List.of(), Map.of(), 1);
        } catch (IllegalArgumentException bad) {
            return CompletableFuture.completedFuture(Result.err(bindMessage(bad)));
        }
        Map<String, String> files = Map.of(
            "/work/user.pi", driverProg,
            "/work/request.pi", request);
        return submit(files, PicatService::interpretEval, timeoutMs,
            fsPathOpt(opts));
    }

    // --- core submit + timeout-by-abandonment ------------------------------

    @FunctionalInterface
    private interface Interpreter {
        Result apply(PicatRunner.RawResult raw);
    }

    private CompletableFuture<Result> submit(Map<String, String> files,
            Interpreter interp, long timeoutMs, java.nio.file.Path dataDir) {
        // Saturation guard: reject without enqueuing.
        if (abandoned.get() >= maxAbandoned) {
            return CompletableFuture.completedFuture(
                Result.err("busy: solver saturated by timed-out jobs"));
        }

        CompletableFuture<Result> publicFuture = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        // Forward-reference cell for the timeout task: the whenComplete hook
        // (registered below) needs to cancel it, but it is scheduled after the
        // hook is attached. Single-element array gives the closure a mutable
        // slot. A benign race exists where the worker finishes and runs the
        // hook before the schedule call populates the cell (handle still null);
        // then we skip cancel and the timeout task fires once, sees settled
        // already true, and is a no-op — harmless.
        final ScheduledFuture<?>[] timeoutHandle = new ScheduledFuture<?>[1];

        // The worker future: runs the blocking WASM call, maps the outcome.
        CompletableFuture<Result> worker = CompletableFuture.supplyAsync(() -> {
            try {
                PicatRunner.RawResult raw = PicatRunner.runRaw(files,
                    List.of("picat", "/work/shim.pi"), timeoutMs, dataDir);
                return interp.apply(raw);
            } catch (RuntimeException e) {
                return Result.err(mapRuntime(e));
            }
        }, pool);

        // Worker completion races the timeout to settle the public future.
        worker.whenComplete((res, ex) -> {
            if (settled.compareAndSet(false, true)) {
                // Worker won the race: cancel the now-moot timeout task so it
                // does not linger in the scheduler queue, then complete
                // normally (abandoned untouched). cancel(false): never
                // interrupt — the task may already be running and is a no-op.
                ScheduledFuture<?> h = timeoutHandle[0];
                if (h != null) h.cancel(false);
                if (ex != null) {
                    publicFuture.complete(Result.err(mapRuntime(ex)));
                } else {
                    publicFuture.complete(res);
                }
            } else {
                // Timeout already fired and counted this as abandoned; the
                // zombie has now finished, so release its saturation slot.
                abandoned.decrementAndGet();
            }
        });

        // Timeout: if it wins the CAS, the worker is still running -> abandon.
        timeoutHandle[0] = timer.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                abandoned.incrementAndGet();
                publicFuture.complete(Result.err("timeout"));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        return publicFuture;
    }

    // --- option parsing ----------------------------------------------------

    private long resolveTimeout(Map<String, Object> opts) {
        long def = Math.min(DEFAULT_TIMEOUT_MS, maxTimeoutMs);
        Object t = opts == null ? null : opts.get("timeout");
        if (t instanceof Number n) {
            long ms = (long) (n.doubleValue() * 1000.0);
            if (ms <= 0) return def;
            return Math.min(ms, maxTimeoutMs);
        }
        return def;
    }

    private int resolveMax(Map<String, Object> opts) {
        Object m = opts == null ? null : opts.get("max");
        if (m instanceof Number n) {
            int v = (int) n.doubleValue();
            return v < 1 ? 1 : v;
        }
        return 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bindOpt(Map<String, Object> opts) {
        Object b = opts == null ? null : opts.get("bind");
        if (b instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /** The {@code fsPath} opt: a real {@link java.nio.file.Path} to mount at
     *  {@code /data} (read-write) in the guest, or null for the isolated
     *  default (no {@code /data}). This is set by the <em>mod layer</em> after
     *  it has resolved and validated the CC-sandbox path the Lua caller named
     *  ({@code opts.fs}); it is NEVER a raw value from Lua. Any non-Path value
     *  is ignored (treated as no mount). */
    private static java.nio.file.Path fsPathOpt(Map<String, Object> opts) {
        Object p = opts == null ? null : opts.get("fsPath");
        return p instanceof java.nio.file.Path path ? path : null;
    }

    // --- request.pi generation ---------------------------------------------

    /** Builds the {@code module request} source. Throws
     *  {@link IllegalArgumentException} (with a {@code bind:}-friendly message)
     *  on a bad var name or bad bind value. */
    private static String buildRequest(String goal, List<String> vars,
            Map<String, Object> bind, int max) {
        for (String v : vars) {
            requireVarName(v);
        }
        StringBuilder varList = new StringBuilder();
        for (int i = 0; i < vars.size(); i++) {
            if (i > 0) varList.append(',');
            varList.append('"').append(escapePicatString(vars.get(i))).append('"');
        }
        StringBuilder bindList = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, Object> e : bind.entrySet()) {
            String name = e.getKey();
            requireVarName(name);
            if (!first) bindList.append(',');
            first = false;
            // ('Name', <literal>) — the name is a quoted atom, the value a term.
            // We can splice `name` between single quotes WITHOUT escaping ONLY
            // because requireVarName ran just above: it constrains name to
            // [A-Z_][A-Za-z0-9_]*, which contains no quote/backslash chars.
            bindList.append("('").append(name).append("', ")
                    .append(PicatLiterals.toLiteral(e.getValue())).append(')');
        }
        bindList.append(']');

        return """
            module request.
            goal_str() = "%s".
            var_names() = [%s].
            max_sols() = %d.
            bind_list() = %s.
            """.formatted(escapePicatString(goal), varList, max, bindList);
    }

    private static void requireVarName(String name) {
        if (name == null || !VAR_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("bind: bad var name: " + name);
        }
    }

    /** Escape for a Picat double-quoted string literal: backslash first, then
     *  double-quote (order matters so the inserted backslashes aren't
     *  re-escaped). */
    private static String escapePicatString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Normalize a PicatLiterals message to the {@code bind:} taxonomy. Its
     *  messages already start with "bind: "; pass through, else prefix. */
    private static String bindMessage(IllegalArgumentException e) {
        String m = e.getMessage();
        if (m == null) return "bind: invalid binding";
        return m.startsWith("bind:") ? m : "bind: " + m;
    }

    // --- response interpretation -------------------------------------------

    /** Maps the {@code "ok"} status of a parsed response to a success Result.
     *  The two callers differ only here — query reads solutions, eval reads
     *  stdout — so the rest of the taxonomy lives in {@link #interpret}. */
    @FunctionalInterface
    private interface OkMapper {
        Result apply(Map<String, Object> resp, PicatRunner.RawResult raw);
    }

    private static Result interpretQuery(PicatRunner.RawResult raw) {
        return interpret(raw, PicatService::okQuery);
    }

    private static Result interpretEval(PicatRunner.RawResult raw) {
        return interpret(raw, (resp, r) -> Result.okOut(r.stdout()));
    }

    @SuppressWarnings("unchecked")
    private static Result okQuery(Map<String, Object> resp, PicatRunner.RawResult raw) {
        Object sols = resp.get("solutions");
        return Result.ok(sols instanceof List
            ? (List<Map<String, Object>>) sols : List.of());
    }

    /** Single source of truth for response interpretation: the missing-response
     *  fallback, the json parse guard, the status switch, and the error
     *  taxonomy. Only the {@code "ok"} branch is parameterized so query and eval
     *  cannot drift on every other outcome. */
    @SuppressWarnings("unchecked")
    private static Result interpret(PicatRunner.RawResult raw, OkMapper okMapper) {
        if (raw.responseJson() == null) {
            return noResponse(raw);
        }
        Map<String, Object> resp;
        try {
            resp = GSON.fromJson(raw.responseJson(), Map.class);
        } catch (RuntimeException parse) {
            return Result.err("internal: malformed response json: "
                + parse.getMessage());
        }
        String status = resp == null ? null : (String) resp.get("status");
        if ("ok".equals(status)) {
            return okMapper.apply(resp, raw);
        }
        if ("failed".equals(status)) {
            return Result.err("goal failed");
        }
        if ("error".equals(status)) {
            return Result.err(mapError((String) resp.get("message"), raw.stderr()));
        }
        return Result.err("internal: unknown status " + status);
    }

    /** No response.json: a compile that died before the catch, or a crash.
     *  Nonempty stderr is the compiler talking -> compile error; else internal. */
    private static Result noResponse(PicatRunner.RawResult raw) {
        String err = raw.stderr() == null ? "" : raw.stderr().strip();
        if (!err.isEmpty()) {
            return Result.err("compile: " + err);
        }
        return Result.err("internal: no response (exit " + raw.exitCode() + ")");
    }

    /** A status:error message from the shim. A user.pi compile failure surfaces
     *  as a syntax_error message AND a "SYNTAX ERROR" on stderr from cl/1 — best
     *  effort reclassify those as {@code compile:}. Everything else is an
     *  honest runtime/thrown error. */
    private static String mapError(String message, String stderr) {
        String msg = message == null ? "" : message;
        boolean syntaxErr = msg.contains("syntax_error");
        boolean stderrSyntax = stderr != null
            && stderr.toUpperCase().contains("SYNTAX ERROR");
        if (syntaxErr && stderrSyntax) {
            return "compile: " + msg;
        }
        return "error: " + msg;
    }

    /** Map a Chicory/runtime exception escaping runRaw. OOM detection here is
     *  best-effort string-sniffing of the message for a Chicory-reported memory
     *  trap; it deliberately does NOT special-case {@link OutOfMemoryError}.
     *  A genuine JVM OutOfMemoryError is an Error (not RuntimeException), so it
     *  arrives via the worker future's {@code ex != null} path and falls
     *  through to {@code internal:} — acceptable, since a real heap exhaustion
     *  is an engine fault, not a per-goal memory-limit signal. */
    private static String mapRuntime(Throwable e) {
        String m = e.getMessage();
        String lower = m == null ? "" : m.toLowerCase();
        if (lower.contains("memory") || lower.contains("oom")
                || lower.contains("out of memory")) {
            return "memory limit";
        }
        return "internal: " + (m == null ? e.getClass().getSimpleName() : m);
    }
}
