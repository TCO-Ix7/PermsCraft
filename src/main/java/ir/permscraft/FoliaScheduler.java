package ir.permscraft;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

/**
 * Unified scheduler abstraction for Folia and non-Folia (Paper/Spigot) servers.
 *
 * WHY THIS EXISTS
 * ───────────────
 * Folia splits the server into independent region threads. The old
 * BukkitScheduler.runTask() and runTaskAsynchronously() still exist on Folia
 * but the "sync" variants no longer run on a single main thread — each region
 * has its own thread, and you must schedule work on the *correct* region or
 * Folia throws an exception.
 *
 * PermsCraft never needs to touch world-blocks — its "sync" work is always one of:
 *   (a) Apply a PermissionAttachment to a specific player   → schedule on that entity's region
 *   (b) Broadcast a change to all online players            → schedule on global region
 *   (c) Fire-and-forget logic with no world state           → runGlobal() is fine
 *
 *   • runAsync()         → AsyncScheduler  (Folia) / runTaskAsynchronously (Bukkit)
 *   • runSync()          → GlobalRegionScheduler (Folia) / runTask (Bukkit)
 *   • runForEntity()     → EntityScheduler (Folia) / runTask (Bukkit)
 *   • runTimer()         → GlobalRegionScheduler.runAtFixedRate (Folia) / runTaskTimerAsynchronously (Bukkit)
 *   • runAsyncTimer()    → AsyncScheduler.runAtFixedRate (Folia) / runTaskTimerAsynchronously (Bukkit)
 *
 * Detection: we check for the presence of io.papermc.paper.threadedregions.RegionizedServer
 * at class-load time.  This is the same check Folia itself exposes via
 * Bukkit.isFolia() (added in Paper 1.19.4 build 525 / all Folia builds).
 * We fall back to the class-existence check so the code also works on older
 * Paper builds that have Folia but lack the convenience method.
 */
public final class FoliaScheduler {

    // ── Folia detection ──────────────────────────────────────────────────────

    private static final boolean FOLIA;

    static {
        boolean folia = false;
        try {
            // Folia-specific class — absent on plain Paper/Spigot
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {}
        FOLIA = folia;
    }

    public static boolean isFolia() { return FOLIA; }

    // ── Task handle ──────────────────────────────────────────────────────────

    /**
     * Opaque handle returned by timer methods so callers can cancel without
     * needing to know whether they hold a BukkitTask or a Folia ScheduledTask.
     */
    public interface TaskHandle {
        void cancel();
    }

    private static TaskHandle wrap(org.bukkit.scheduler.BukkitTask t) {
        return t::cancel;
    }

    private static TaskHandle wrap(io.papermc.paper.threadedregions.scheduler.ScheduledTask t) {
        return t::cancel;
    }

    // ── One-shot async ───────────────────────────────────────────────────────

    /**
     * Run {@code task} on a background (I/O) thread.
     * Safe to call from any thread. Used for all DB and Redis operations.
     */
    public static void runAsync(JavaPlugin plugin, Runnable task) {
        if (FOLIA) {
            plugin.getServer().getAsyncScheduler()
                  .runNow(plugin, $ -> task.run());
        } else {
            plugin.getServer().getScheduler()
                  .runTaskAsynchronously(plugin, task);
        }
    }

    // ── One-shot sync (global / no region context needed) ────────────────────

    /**
     * Run {@code task} on the main/global region thread.
     * Use for: applying permission attachments to all online players,
     * reloading group cache, scheduling follow-up work that must be "on-server".
     */
    public static void runSync(JavaPlugin plugin, Runnable task) {
        if (FOLIA) {
            plugin.getServer().getGlobalRegionScheduler()
                  .run(plugin, $ -> task.run());
        } else {
            plugin.getServer().getScheduler()
                  .runTask(plugin, task);
        }
    }

    // ── One-shot on specific entity's region ─────────────────────────────────

    /**
     * Run {@code task} on the region thread that owns {@code entity}.
     * Use when applying PermissionAttachment to a single player.
     * Falls back to runSync() if the entity is no longer valid.
     */
    public static void runForEntity(JavaPlugin plugin, Entity entity, Runnable task) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, $ -> task.run(),
                    // retired callback: entity gone before task ran → no-op
                    null);
        } else {
            plugin.getServer().getScheduler()
                  .runTask(plugin, task);
        }
    }

    // ── Repeating async timer ─────────────────────────────────────────────────

    /**
     * Schedule a repeating task on background threads.
     * {@code initialDelayTicks} and {@code periodTicks} are in Bukkit ticks (20 = 1 s).
     * Returns a {@link TaskHandle} that can be cancelled.
     */
    public static TaskHandle runAsyncTimer(JavaPlugin plugin, Runnable task,
                                           long initialDelayTicks, long periodTicks) {
        if (FOLIA) {
            // Folia AsyncScheduler uses nanoseconds
            long initialNs = ticksToNanos(initialDelayTicks);
            long periodNs  = ticksToNanos(periodTicks);
            return wrap(
                plugin.getServer().getAsyncScheduler()
                      .runAtFixedRate(plugin, $ -> task.run(),
                                      initialNs, periodNs, TimeUnit.NANOSECONDS)
            );
        } else {
            return wrap(
                plugin.getServer().getScheduler()
                      .runTaskTimerAsynchronously(plugin, task, initialDelayTicks, periodTicks)
            );
        }
    }

    // ── Repeating sync (global region) timer ─────────────────────────────────

    /**
     * Schedule a repeating task on the global region (or main thread on Bukkit).
     * Use for: periodic drain of BungeeCord message queue.
     */
    public static TaskHandle runSyncTimer(JavaPlugin plugin, Runnable task,
                                          long initialDelayTicks, long periodTicks) {
        if (FOLIA) {
            return wrap(
                plugin.getServer().getGlobalRegionScheduler()
                      .runAtFixedRate(plugin, $ -> task.run(),
                                      initialDelayTicks, periodTicks)
            );
        } else {
            return wrap(
                plugin.getServer().getScheduler()
                      .runTaskTimer(plugin, task, initialDelayTicks, periodTicks)
            );
        }
    }

    // ── Delayed sync (global region) ─────────────────────────────────────────

    /**
     * Run {@code task} after {@code delayTicks} ticks on the global region.
     */
    public static void runSyncLater(JavaPlugin plugin, Runnable task, long delayTicks) {
        if (FOLIA) {
            plugin.getServer().getGlobalRegionScheduler()
                  .runDelayed(plugin, $ -> task.run(), delayTicks);
        } else {
            plugin.getServer().getScheduler()
                  .runTaskLater(plugin, task, delayTicks);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** 1 Bukkit tick = 50 ms = 50_000_000 ns */
    private static long ticksToNanos(long ticks) {
        return ticks * 50_000_000L;
    }

    private FoliaScheduler() {} // no instances
}
