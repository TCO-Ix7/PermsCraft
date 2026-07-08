package ir.permscraft.api.rest;

import io.javalin.http.Context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple per-IP sliding-window rate limiter.
 *
 * Uses a 60-second window. Each IP gets `requestsPerMinute` tokens.
 * When the window expires (> 60 s since first request in window) the
 * counter resets automatically.
 *
 * This is intentionally lightweight — no external deps, no Guava.
 * For production deployments behind a reverse proxy (nginx/Caddy),
 * consider delegating rate-limiting to the proxy instead and disabling
 * this via config.
 */
public final class RateLimiter {

    private RateLimiter() {}

    private record Window(AtomicInteger count, long windowStart) {}

    private static final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private static final long WINDOW_MS = 60_000L;

    /**
     * Check the rate limit for the requesting IP.
     * Throws {@link ApiException} 429 if the limit is exceeded.
     *
     * @param ctx               Javalin context
     * @param requestsPerMinute maximum allowed requests in 60 s
     */
    public static void check(Context ctx, int requestsPerMinute) {
        String ip = realIp(ctx);
        long now  = System.currentTimeMillis();

        Window w = windows.compute(ip, (k, existing) -> {
            if (existing == null || (now - existing.windowStart()) > WINDOW_MS) {
                return new Window(new AtomicInteger(0), now);
            }
            return existing;
        });

        int current = w.count().incrementAndGet();
        ctx.header("X-RateLimit-Limit",     String.valueOf(requestsPerMinute));
        ctx.header("X-RateLimit-Remaining", String.valueOf(Math.max(0, requestsPerMinute - current)));
        ctx.header("X-RateLimit-Reset",     String.valueOf((w.windowStart() + WINDOW_MS) / 1000));

        if (current > requestsPerMinute) {
            ctx.status(429);
            throw ApiException.forbidden("Rate limit exceeded. Try again in "
                    + ((w.windowStart() + WINDOW_MS - now) / 1000) + " seconds.");
        }
    }

    /** Respect X-Forwarded-For when behind a reverse proxy. */
    private static String realIp(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return ctx.ip();
    }

    /** Remove stale entries (call periodically, e.g. from a timer). */
    public static void evictStale() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS * 2;
        windows.entrySet().removeIf(e -> e.getValue().windowStart() < cutoff);
    }
}
