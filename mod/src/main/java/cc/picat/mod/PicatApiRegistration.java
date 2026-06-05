package cc.picat.mod;

import cc.picat.engine.PicatService;
import dan200.computercraft.api.ComputerCraftAPI;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the Picat engine into ComputerCraft once, at mod init, and manages its
 * lifecycle against the Minecraft server.
 *
 * <h2>Lifecycle choice</h2>
 * {@link ComputerCraftAPI#registerAPIFactory} is a process-wide, one-shot
 * registration: the factory lambda is the only thing CC ever holds, and it is
 * registered exactly once here. The heavy, mutable resource is the
 * {@link PicatService} (a thread pool + WASM machinery), so that is what we
 * bind to the server lifecycle:
 *
 * <ul>
 *   <li><b>SERVER_STARTING</b> — (re)create the service from freshly-loaded
 *       config and publish it. This covers the integrated client too, where a
 *       player may open and close several singleplayer worlds in one JVM: each
 *       world gets a clean engine, and an old engine never leaks its pool.</li>
 *   <li><b>SERVER_STOPPING</b> — shut the service down and null it out, so the
 *       worker/timeout threads do not survive the world.</li>
 * </ul>
 *
 * The factory lambda reads the {@code volatile} {@link #service} field at
 * computer-creation time, so it always sees the engine for the currently
 * running server (and never an instance from a previous world).
 */
public final class PicatApiRegistration {
    private static final Logger LOG = LoggerFactory.getLogger("picat-cc");

    /** The engine for the currently-running server, or {@code null} between worlds. */
    private static volatile PicatService service;

    private PicatApiRegistration() {}

    static PicatService service() {
        return service;
    }

    public static void register() {
        // One process-wide factory. Each CC computer gets its own PicatLuaAPI
        // instance (verified against ILuaAPIFactory: create(IComputerSystem)).
        // The lambda binds late to the current server's service via service().
        ComputerCraftAPI.registerAPIFactory(
            computer -> new PicatLuaAPI(computer, PicatApiRegistration::service));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            PicatCcConfig cfg = PicatCcConfig.load();
            service = new PicatService(cfg.workerThreads, cfg.maxAbandonedJobs,
                cfg.maxTimeoutSeconds * 1000L);
            LOG.info("Picat engine started (threads={}, maxAbandoned={}, maxTimeout={}s)",
                cfg.workerThreads, cfg.maxAbandonedJobs, cfg.maxTimeoutSeconds);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PicatService s = service;
            service = null;
            if (s != null) {
                s.shutdown();
                LOG.info("Picat engine stopped");
            }
        });
    }
}
