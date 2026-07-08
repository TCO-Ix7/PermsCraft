package ir.permscraft.api.rest;

import io.javalin.Javalin;
import io.javalin.http.Context;
import ir.permscraft.PermsCraft;
import ir.permscraft.api.rest.auth.ApiKeyManager;
import ir.permscraft.api.rest.routes.*;

import java.util.logging.Logger;

/**
 * Embedded REST API server for PermsCraft.
 *
 * Lifecycle:
 *   start()  — called from PermsCraft.onEnable()  after all managers are ready
 *   stop()   — called from PermsCraft.onDisable() before storage closes
 *
 * All routes are registered under /api/v1/.
 * Authentication: every request must carry a valid API key in the
 *   Authorization: Bearer <key>
 * header. Keys are stored hashed in plugins/PermsCraft/apikeys.yml and
 * managed via /pc apikey <create|list|revoke> in-game or from console.
 *
 * Config block (config.yml):
 * ─────────────────────────
 * rest-api:
 *   enabled: true
 *   port: 4567
 *   bind: "0.0.0.0"          # use 127.0.0.1 to restrict to localhost
 *   rate-limit:
 *     requests-per-minute: 120
 */
public class RestApiServer {

    private final PermsCraft plugin;
    private final Logger log;
    private Javalin app;
    private ApiKeyManager keyManager;

    public RestApiServer(PermsCraft plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
        // Always available — /pc apikey must work even when the HTTP server
        // itself is disabled in config, so keys can be pre-provisioned.
        this.keyManager = new ApiKeyManager(plugin);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        if (!plugin.getConfig().getBoolean("rest-api.enabled", false)) {
            log.info("[PermsCraft REST] API disabled in config. (/pc apikey management is still available.)");
            return;
        }

        int    port = plugin.getConfig().getInt("rest-api.port", 4567);
        String bind = plugin.getConfig().getString("rest-api.bind", "0.0.0.0");
        int    rpm  = plugin.getConfig().getInt("rest-api.rate-limit.requests-per-minute", 120);

        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jetty.defaultHost = bind;
            // JSON via Jackson (bundled with Javalin)
            cfg.jsonMapper(new io.javalin.json.JavalinJackson());
        });

        // ── Global before-handler: auth + rate limit ──────────────────────────
        app.before("/api/*", ctx -> {
            // Rate limiting
            RateLimiter.check(ctx, rpm);
            // Auth (skip OPTIONS for CORS preflight)
            if (!ctx.method().name().equals("OPTIONS")) {
                keyManager.authenticate(ctx); // throws 401 if invalid
            }
        });

        // ── CORS headers ──────────────────────────────────────────────────────
        app.after(ctx -> {
            ctx.header("Access-Control-Allow-Origin",  "*");
            ctx.header("Access-Control-Allow-Headers", "Authorization, Content-Type");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        });
        app.options("/api/*", ctx -> ctx.status(204));

        // ── Route registration ────────────────────────────────────────────────
        new GroupRoutes(plugin).register(app);
        new UserRoutes(plugin).register(app);
        new LogRoutes(plugin).register(app);
        new SyncRoutes(plugin).register(app);
        new BackupRoutes(plugin).register(app);
        new ServerRoutes(plugin).register(app);

        // ── Health / root ─────────────────────────────────────────────────────
        app.get("/api/v1/health", ctx -> ctx.json(new ir.permscraft.api.rest.dto.HealthDto(
                "ok",
                plugin.getPluginMeta().getVersion(),
                plugin.getServerName(),
                System.currentTimeMillis()
        )));

        // ── Global error handlers ─────────────────────────────────────────────
        app.exception(ApiException.class, (e, ctx) ->
                ctx.status(e.status()).json(error(e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            log.warning("[PermsCraft REST] Unhandled exception on "
                    + ctx.path() + ": " + e.getMessage());
            ctx.status(500).json(error("Internal server error"));
        });
        app.error(404, ctx -> ctx.json(error("Not found")));
        app.error(405, ctx -> ctx.json(error("Method not allowed")));

        // ── Security warning for external binding ─────────────────────────────
        if ("0.0.0.0".equals(bind)) {
            log.warning("╔══════════════════════════════════════════════════════════╗");
            log.warning("║  [PermsCraft REST] SECURITY WARNING                      ║");
            log.warning("║  The REST API is bound to 0.0.0.0 (all interfaces).      ║");
            log.warning("║  API keys are transmitted over plain HTTP — anyone on    ║");
            log.warning("║  your network can intercept them.                        ║");
            log.warning("║                                                          ║");
            log.warning("║  Recommended: use a reverse proxy (nginx/Caddy) with    ║");
            log.warning("║  TLS, and set bind: \"127.0.0.1\" in config.yml.          ║");
            log.warning("║  See: https://github.com/your-repo/wiki/REST-API-TLS    ║");
            log.warning("╚══════════════════════════════════════════════════════════╝");
        }

        app.start(bind, port);
        log.info("[PermsCraft REST] API listening on " + bind + ":" + port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
            log.info("[PermsCraft REST] API stopped.");
        }
    }

    public ApiKeyManager getKeyManager() { return keyManager; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static java.util.Map<String, String> error(String message) {
        return java.util.Map.of("error", message);
    }
}
