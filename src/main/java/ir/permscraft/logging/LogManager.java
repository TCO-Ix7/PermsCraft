package ir.permscraft.logging;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.storage.LogFilter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;

/**
 * FIX: LogManager now delegates ALL persistence to StorageBackend.
 *
 * Previously it bypassed StorageBackend and called getConnection() directly,
 * which caused an UnsupportedOperationException crash on MongoDB startup.
 * Logs were also completely silent (no warning) when running MongoDB.
 *
 * Now:
 *   - SQL backends: log entries are persisted as before.
 *   - MongoDB: log entries are persisted in the pc_log collection.
 *   - All backends: writes are async to avoid blocking the main thread.
 */
public class LogManager {

    private final PermsCraft plugin;

    public LogManager(PermsCraft plugin) {
        this.plugin = plugin;
    }

    // ── write ─────────────────────────────────────────────────────────────────

    public void log(CommandSender actor, LogEntry.Action action, String target, String detail) {
        String actorName = actor instanceof Player p ? p.getName() : "CONSOLE";
        logAsync(actorName, action, target, detail);
    }

    public void log(String actorName, LogEntry.Action action, String target, String detail) {
        logAsync(actorName, action, target, detail);
    }

    private void logAsync(String actor, LogEntry.Action action, String target, String detail) {
        long timestamp = Instant.now().getEpochSecond();
        FoliaScheduler.runAsync(plugin, () ->
            plugin.getStorage().saveLog(timestamp, actor, action.name(), target, detail)
        );
    }

    // ── read ──────────────────────────────────────────────────────────────────

    public List<LogEntry> getRecent(int limit) {
        return plugin.getStorage().loadRecentLogs(limit);
    }

    public List<LogEntry> getForTarget(String target, int limit) {
        return plugin.getStorage().loadLogsByTarget(target, limit);
    }

    public List<LogEntry> getByActor(String actor, int limit) {
        return plugin.getStorage().loadLogsByActor(actor, limit);
    }

    /**
     * Run a fully-filtered query (actor/target/action/time range + pagination).
     * Delegates to StorageBackend#queryLogs, which already implements all
     * filter dimensions (used by the REST API at /api/v1/logs).
     */
    public List<LogEntry> query(LogFilter filter) {
        return plugin.getStorage().queryLogs(filter);
    }

    /**
     * Total number of entries matching a filter, ignoring its limit/offset.
     * Useful for "X matches, showing N" style output.
     */
    public long count(LogFilter filter) {
        return plugin.getStorage().countLogs(filter);
    }

    /**
     * Distinct action type names that actually appear in the log (not just
     * the full enum — see StorageBackend#getDistinctLogActions).
     */
    public List<String> getDistinctActions() {
        return plugin.getStorage().getDistinctLogActions();
    }

    public void clearAll() {
        plugin.getStorage().deleteLogsOlderThan(Long.MAX_VALUE);
    }

    public void clearOlderThan(int days) {
        long cutoff = Instant.now().getEpochSecond() - ((long) days * 86400);
        plugin.getStorage().deleteLogsOlderThan(cutoff);
    }
}
