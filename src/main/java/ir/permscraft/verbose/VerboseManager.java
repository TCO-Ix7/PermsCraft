package ir.permscraft.verbose;

import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verbose mode — shows real-time permission checks to admins.
 *
 * Usage:
 *   /pc verbose on [player|*]   — start recording (filter optional, * = all)
 *   /pc verbose off             — stop recording
 *   /pc verbose paste           — print last N entries to chat
 *   /pc verbose export          — save ALL recorded entries to a file
 *
 * Improvements over original:
 *   - Retained 500 entries per session (was 100)
 *   - /pc verbose export → saves to plugins/PermsCraft/verbose/<date>-<admin>.txt
 *   - Shows source of permission (group name or "personal") in chat output
 *   - Filter supports player name OR permission node prefix (e.g. "essentials.*")
 *   - Thread-safe session management
 */
public class VerboseManager {

    private static final int  MAX_ENTRIES    = 500;
    private static final int  PASTE_LIMIT    = 50;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

    private final PermsCraft plugin;

    // admin UUID → session
    private final Map<UUID, VerboseSession> sessions  = new ConcurrentHashMap<>();
    // admin UUID → circular buffer of entries
    private final Map<UUID, Deque<VerboseEntry>> recorded = new ConcurrentHashMap<>();

    public VerboseManager(PermsCraft plugin) { this.plugin = plugin; }

    // ── Session management ────────────────────────────────────────────────────

    public void startSession(Player admin, String filter) {
        sessions.put(admin.getUniqueId(), new VerboseSession(filter.toLowerCase()));
        recorded.put(admin.getUniqueId(), new ArrayDeque<>());
        MessageUtil.send(admin, "&aVerbose mode &2ON");
        MessageUtil.send(admin, "&7Filter: &e" + filter + " &8| &7Max entries: &e" + MAX_ENTRIES);
        MessageUtil.send(admin, "&7Filter types: &b* &7all, &bplayer &7name, &bperm &7prefix, &br:regex");
        MessageUtil.send(admin, "&7Use &b/pc verbose off &7to stop, &b/pc verbose export &7to save.");
    }

    public void stopSession(Player admin) {
        VerboseSession s = sessions.remove(admin.getUniqueId());
        if (s == null) {
            MessageUtil.send(admin, "&cVerbose is not active.");
            return;
        }
        Deque<VerboseEntry> entries = recorded.getOrDefault(admin.getUniqueId(), new ArrayDeque<>());
        MessageUtil.send(admin, "&cVerbose mode &4OFF &7(" + entries.size()
                + " entries recorded). Use &b/pc verbose paste &7or &b/pc verbose export&7.");
    }

    public boolean hasSession(UUID adminUUID) { return sessions.containsKey(adminUUID); }

    // ── Recording ─────────────────────────────────────────────────────────────

    /**
     * Called on every permission check. Lightweight — returns immediately if
     * no sessions are active.
     *
     * @param checkedPlayer player whose permission was checked
     * @param permission    the permission node
     * @param result        true = granted, false = denied
     * @param reason        source description (e.g. "group:admin", "personal", "timed")
     */
    public void record(String checkedPlayer, String permission, boolean result, String reason) {
        if (sessions.isEmpty()) return;

        VerboseEntry entry = new VerboseEntry(checkedPlayer, permission, result, reason,
                System.currentTimeMillis());

        for (Map.Entry<UUID, VerboseSession> e : sessions.entrySet()) {
            UUID          adminUUID = e.getKey();
            VerboseSession ses      = e.getValue();

            // Filter: matches player name OR permission starts with filter prefix
            if (!ses.matches(checkedPlayer, permission)) continue;

            Player admin = plugin.getServer().getPlayer(adminUUID);
            if (admin == null) { sessions.remove(adminUUID); continue; }

            // Real-time chat output
            String color  = result ? "&a" : "&c";
            String symbol = result ? "✔" : "✘";
            admin.sendMessage(MessageUtil.colorizeString(
                    "&8[&bVerbose&8] " + color + symbol
                    + " &f" + checkedPlayer
                    + " &8» &f" + permission
                    + " &8| " + color + (result ? "true" : "false")
                    + " &8| &7" + reason));

            // Buffer
            Deque<VerboseEntry> queue = recorded.computeIfAbsent(adminUUID,
                    k -> new ArrayDeque<>());
            queue.addLast(entry);
            if (queue.size() > MAX_ENTRIES) queue.removeFirst();
        }
    }

    // ── Output ────────────────────────────────────────────────────────────────

    /**
     * Print the last PASTE_LIMIT entries to the admin's chat.
     */
    public void paste(Player admin) {
        Deque<VerboseEntry> entries = recorded.get(admin.getUniqueId());
        if (entries == null || entries.isEmpty()) {
            MessageUtil.send(admin, "&7No recorded entries. Start with &b/pc verbose on&7.");
            return;
        }
        List<VerboseEntry> list = new ArrayList<>(entries);
        int start = Math.max(0, list.size() - PASTE_LIMIT);
        MessageUtil.send(admin, "&7--- &bVerbose &7(showing last " + (list.size() - start)
                + " of " + list.size() + ") ---");
        for (int i = start; i < list.size(); i++) {
            VerboseEntry entry = list.get(i);
            String color  = entry.result ? "&a" : "&c";
            String symbol = entry.result ? "✔" : "✘";
            admin.sendMessage(MessageUtil.colorizeString(
                    "&8[" + FMT.format(Instant.ofEpochMilli(entry.timestamp)) + "] "
                    + color + symbol + " &f" + entry.player
                    + " &8» &f" + entry.permission
                    + " &8| " + color + (entry.result ? "true" : "false")
                    + " &8| &7" + entry.reason));
        }
        MessageUtil.send(admin, "&7Use &b/pc verbose export &7to save all " + list.size() + " entries to file.");
    }

    /**
     * Export ALL recorded entries for this admin to a text file.
     * File: plugins/PermsCraft/verbose/<timestamp>-<adminName>.txt
     */
    public void export(Player admin) {
        Deque<VerboseEntry> entries = recorded.get(admin.getUniqueId());
        if (entries == null || entries.isEmpty()) {
            MessageUtil.send(admin, "&7No recorded entries to export.");
            return;
        }

        File dir = new File(plugin.getDataFolder(), "verbose");
        dir.mkdirs();

        String filename = FILE_FMT.format(Instant.now()) + "-" + admin.getName() + ".txt";
        File   file     = new File(dir, filename);

        try (FileWriter fw = new FileWriter(file)) {
            fw.write("PermsCraft Verbose Export\n");
            fw.write("Admin: " + admin.getName() + "\n");
            fw.write("Exported: " + Instant.now() + "\n");
            fw.write("Entries: " + entries.size() + "\n");
            fw.write("─".repeat(80) + "\n");
            for (VerboseEntry entry : entries) {
                fw.write(String.format("[%s] %s %s | %s | %s | %s%n",
                        FMT.format(Instant.ofEpochMilli(entry.timestamp)),
                        entry.result ? "✔" : "✘",
                        entry.player,
                        entry.permission,
                        entry.result ? "true" : "false",
                        entry.reason));
            }
            MessageUtil.send(admin, "&aVerbose exported → &e" + file.getPath());
            MessageUtil.send(admin, "&7" + entries.size() + " entries written.");
        } catch (IOException e) {
            MessageUtil.send(admin, "&cFailed to export: " + e.getMessage());
            plugin.getLogger().warning("[PermsCraft] Verbose export failed: " + e.getMessage());
        }
    }

    public void clearRecorded(UUID adminUUID) {
        Deque<VerboseEntry> q = recorded.get(adminUUID);
        if (q != null) q.clear();
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    /**
     * Verbose session with advanced filter support.
     *
     * Filter syntax:
     *   *                → match everything
     *   steve            → match only player named "steve" (exact, case-insensitive)
     *   essentials       → permission starts with "essentials" (prefix match)
     *   r:essentials\..*  → regex match on permission node
     *   p:steve          → explicit player name filter (same as writing just "steve")
     *   n:essentials\.fly → exact permission node filter (no wildcard)
     *
     * which supports regex but with simpler syntax.
     *
     * Examples:
     *   r:essentials\.(fly|kit|home)   → matches essentials.fly, essentials.kit, essentials.home
     *   r:(?!essentials).*             → everything EXCEPT essentials.*
     *   p:Steve r:vault\..*            → only Steve's vault permission checks
     */
    private static final class VerboseSession {
        private final String  rawFilter;
        private final boolean isRegex;
        private final boolean isPlayerFilter;
        private final boolean isExactNode;
        private final java.util.regex.Pattern regexPattern;
        private final String  simpleFilter;

        VerboseSession(String filter) {
            this.rawFilter = filter;
            if (filter.startsWith("r:")) {
                isRegex = true;
                isPlayerFilter = false;
                isExactNode = false;
                java.util.regex.Pattern p;
                try {
                    p = java.util.regex.Pattern.compile(filter.substring(2),
                            java.util.regex.Pattern.CASE_INSENSITIVE);
                } catch (java.util.regex.PatternSyntaxException e) {
                    p = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(filter.substring(2)));
                }
                regexPattern = p;
                simpleFilter = null;
            } else if (filter.startsWith("p:")) {
                isRegex = false;
                isPlayerFilter = true;
                isExactNode = false;
                regexPattern = null;
                simpleFilter = filter.substring(2).toLowerCase();
            } else if (filter.startsWith("n:")) {
                isRegex = false;
                isPlayerFilter = false;
                isExactNode = true;
                regexPattern = null;
                simpleFilter = filter.substring(2).toLowerCase();
            } else {
                isRegex = false;
                isPlayerFilter = false;
                isExactNode = false;
                regexPattern = null;
                simpleFilter = filter.toLowerCase();
            }
        }

        boolean matches(String player, String permission) {
            if (simpleFilter != null && simpleFilter.equals("*")) return true;
            if (isRegex) {
                // Regex matches against "player:permission" — allows filtering both at once
                String subject = player.toLowerCase() + ":" + permission.toLowerCase();
                return regexPattern.matcher(subject).find()
                        || regexPattern.matcher(permission.toLowerCase()).find();
            }
            if (isPlayerFilter) return player.equalsIgnoreCase(simpleFilter);
            if (isExactNode)    return permission.equalsIgnoreCase(simpleFilter);
            // Default: player name OR permission prefix
            if (player.equalsIgnoreCase(simpleFilter)) return true;
            return permission.toLowerCase().startsWith(simpleFilter);
        }

        @Override public String toString() { return rawFilter; }
    }

    public record VerboseEntry(
            String  player,
            String  permission,
            boolean result,
            String  reason,
            long    timestamp
    ) {}
}
