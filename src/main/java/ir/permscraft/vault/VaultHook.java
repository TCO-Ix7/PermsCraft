package ir.permscraft.vault;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import ir.permscraft.utils.MessageUtil;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class VaultHook {

    private final PermsCraft plugin;

    /**
     * FIX (Bug: VaultHook thread leak): a single, small, shared, daemon-thread
     * executor used for synchronous offline-user lookups in
     * {@code PCVaultPermission.playerHas()}. Previously every call to
     * {@code playerHas()} for an offline player created (and never shut down)
     * a brand-new {@code Executors.newSingleThreadExecutor()}, leaking one
     * thread per call on busy economy servers. A small fixed pool is reused
     * across calls and is shut down in {@link #shutdown()} on plugin disable.
     */
    private static final ExecutorService OFFLINE_LOOKUP_EXECUTOR = Executors.newFixedThreadPool(
            2,
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "PermsCraft-VaultOfflineLookup-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * Shuts down the shared offline-lookup executor. Must be called from
     * {@code PermsCraft#onDisable()} so the threads don't linger across
     * plugin reloads/disables.
     */
    public static void shutdown() {
        OFFLINE_LOOKUP_EXECUTOR.shutdownNow();
    }

    public VaultHook(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public boolean hook() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().info("Vault not found. Skipping Vault integration.");
            return false;
        }

        plugin.getServer().getServicesManager().register(Permission.class,
                new PCVaultPermission(plugin), plugin, ServicePriority.Highest);
        plugin.getServer().getServicesManager().register(Chat.class,
                new PCVaultChat(plugin), plugin, ServicePriority.Highest);

        plugin.getLogger().info("Vault hooked successfully!");
        return true;
    }

    // ===== VAULT PERMISSION =====
    public static class PCVaultPermission extends Permission {
        private final PermsCraft plugin;

        public PCVaultPermission(PermsCraft plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getName() { return "PermsCraft"; }

        @Override
        public boolean isEnabled() { return plugin.isEnabled(); }

        @Override
        public boolean hasSuperPermsCompat() { return true; }

        @Override
        public boolean hasGroupSupport() { return true; }

        @Override
        public boolean playerHas(String world, OfflinePlayer player, String permission) {
            // Online players: use Bukkit's live attachment (fastest path)
            Player online = player.getPlayer();
            if (online != null) return online.hasPermission(permission);
            // Offline: try in-memory cache first
            ir.permscraft.models.User user = plugin.getUserManager().getUser(player.getUniqueId());
            if (user != null) return plugin.getInheritanceGraph().hasPermission(user, permission);
            // FIX (Bug #VaultOffline): if user is not in cache (e.g. EssentialsX shop checking a
            // buyer who just quit), do a synchronous DB load so we return a real answer instead
            // of always false. We cap the wait at 500 ms so a slow/unavailable DB cannot freeze
            // the server if Vault happens to call us from the main thread.
            try {
                java.util.concurrent.Future<ir.permscraft.models.User> future =
                        OFFLINE_LOOKUP_EXECUTOR.submit(() ->
                                plugin.getStorage().loadUser(
                                        player.getUniqueId(),
                                        player.getName() != null ? player.getName() : "unknown"));
                ir.permscraft.models.User loaded = future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (loaded != null) return plugin.getInheritanceGraph().hasPermission(loaded, permission);
            } catch (java.util.concurrent.TimeoutException e) {
                plugin.getLogger().warning("[PermsCraft] VaultHook: timed out loading offline user "
                        + player.getUniqueId() + " (>500 ms) — defaulting to false");
            } catch (Exception e) {
                plugin.getLogger().warning("[PermsCraft] VaultHook: failed to load offline user "
                        + player.getUniqueId() + " for permission check: " + e.getMessage());
            }
            return false;
        }

        @Override
        public boolean playerAdd(String world, OfflinePlayer player, String permission) {
            plugin.getUserManager().addPermission(player.getUniqueId(), permission);
            return true;
        }

        @Override
        public boolean playerRemove(String world, OfflinePlayer player, String permission) {
            plugin.getUserManager().removePermission(player.getUniqueId(), permission);
            return true;
        }

        @Override
        public boolean groupHas(String world, String group, String permission) {
            Group g = plugin.getGroupManager().getGroup(group);
            return g != null && g.hasPermission(permission);
        }

        @Override
        public boolean groupAdd(String world, String group, String permission) {
            plugin.getGroupManager().addPermission(group, permission);
            return true;
        }

        @Override
        public boolean groupRemove(String world, String group, String permission) {
            plugin.getGroupManager().removePermission(group, permission);
            return true;
        }

        @Override
        public boolean playerInGroup(String world, OfflinePlayer player, String group) {
            User user = plugin.getUserManager().getUser(player.getUniqueId());
            return user != null && user.inGroup(group);
        }

        @Override
        public boolean playerAddGroup(String world, OfflinePlayer player, String group) {
            plugin.getUserManager().addToGroup(player.getUniqueId(), group);
            return true;
        }

        @Override
        public boolean playerRemoveGroup(String world, OfflinePlayer player, String group) {
            plugin.getUserManager().removeFromGroup(player.getUniqueId(), group);
            return true;
        }

        @Override
        public String[] getPlayerGroups(String world, OfflinePlayer player) {
            User user = plugin.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                // Try loading from storage for offline players
                user = plugin.getStorage().loadUser(player.getUniqueId(),
                        player.getName() != null ? player.getName() : "unknown");
            }
            if (user == null) return new String[]{"default"};
            return user.getGroups().toArray(new String[0]);
        }

        @Override
        public String getPrimaryGroup(String world, OfflinePlayer player) {
            User user = plugin.getUserManager().getUser(player.getUniqueId());
            return user != null ? user.getPrimaryGroup() : "default";
        }

        @Override
        public String[] getGroups() {
            return plugin.getGroupManager().getAllGroups().stream()
                    .map(Group::getName).toArray(String[]::new);
        }

        // Legacy methods
        @Override public boolean playerHas(String world, String playerName, String permission) { return false; }
        @Override public boolean playerAdd(String world, String playerName, String permission) { return false; }
        @Override public boolean playerRemove(String world, String playerName, String permission) { return false; }
        @Override public boolean playerInGroup(String world, String playerName, String group) { return false; }
        @Override public boolean playerAddGroup(String world, String playerName, String group) { return false; }
        @Override public boolean playerRemoveGroup(String world, String playerName, String group) { return false; }
        @Override public String[] getPlayerGroups(String world, String playerName) { return new String[0]; }
        @Override public String getPrimaryGroup(String world, String playerName) { return "default"; }
    }

    // ===== VAULT CHAT =====
    public static class PCVaultChat extends Chat {
        private final PermsCraft plugin;

        public PCVaultChat(PermsCraft plugin) {
            super(new PCVaultPermission(plugin));
            this.plugin = plugin;
        }

        @Override
        public String getName() { return "PermsCraft"; }

        @Override
        public boolean isEnabled() { return plugin.isEnabled(); }

        @Override
        public String getPlayerPrefix(String world, OfflinePlayer player) {
            User user = plugin.getUserManager().getUser(player.getUniqueId());
            if (user == null) return "";
            if (user.getPrefix() != null && !user.getPrefix().isEmpty())
                return MessageUtil.colorizeString(user.getPrefix());
            Group g = plugin.getGroupManager().getGroup(user.getPrimaryGroup());
            return g != null ? MessageUtil.colorizeString(g.getPrefix()) : "";
        }

        @Override
        public void setPlayerPrefix(String world, OfflinePlayer player, String prefix) {
            plugin.getUserManager().setPrefix(player.getUniqueId(), prefix);
        }

        @Override
        public String getPlayerSuffix(String world, OfflinePlayer player) {
            User user = plugin.getUserManager().getUser(player.getUniqueId());
            if (user == null) return "";
            if (user.getSuffix() != null && !user.getSuffix().isEmpty())
                return MessageUtil.colorizeString(user.getSuffix());
            Group g = plugin.getGroupManager().getGroup(user.getPrimaryGroup());
            return g != null ? MessageUtil.colorizeString(g.getSuffix()) : "";
        }

        @Override
        public void setPlayerSuffix(String world, OfflinePlayer player, String suffix) {
            plugin.getUserManager().setSuffix(player.getUniqueId(), suffix);
        }

        @Override
        public String getGroupPrefix(String world, String group) {
            Group g = plugin.getGroupManager().getGroup(group);
            return g != null ? MessageUtil.colorizeString(g.getPrefix()) : "";
        }

        @Override
        public void setGroupPrefix(String world, String group, String prefix) {
            plugin.getGroupManager().setPrefix(group, prefix);
        }

        @Override
        public String getGroupSuffix(String world, String group) {
            Group g = plugin.getGroupManager().getGroup(group);
            return g != null ? MessageUtil.colorizeString(g.getSuffix()) : "";
        }

        @Override
        public void setGroupSuffix(String world, String group, String suffix) {
            plugin.getGroupManager().setSuffix(group, suffix);
        }

        // ── Meta-backed info methods (LP-style) ─────────────────────────────

        private User vaultUser(OfflinePlayer player) {
            User u = plugin.getUserManager().getUser(player.getUniqueId());
            if (u == null) {
                // Offline player not in cache — build a temporary User with default group.
                // Vault API is synchronous so we cannot do a DB call here.
                String name = player.getName() != null ? player.getName() : player.getUniqueId().toString();
                u = new User(player.getUniqueId(), name);
                u.addGroup("default");
            }
            return u;
        }

        @Override
        public String getPlayerInfoString(String world, OfflinePlayer player, String node, String def) {
            User u = vaultUser(player);
            String val = u != null ? u.getMetaValue(node) : null;
            return val != null ? val : def;
        }
        @Override
        public void setPlayerInfoString(String world, OfflinePlayer player, String node, String value) {
            plugin.getUserManager().setMeta(player.getUniqueId(), node, value);
        }
        @Override
        public int getPlayerInfoInteger(String world, OfflinePlayer player, String node, int def) {
            String val = getPlayerInfoString(world, player, node, null);
            if (val == null) return def;
            try { return Integer.parseInt(val); } catch (NumberFormatException e) { return def; }
        }
        @Override
        public void setPlayerInfoInteger(String world, OfflinePlayer player, String node, int value) {
            setPlayerInfoString(world, player, node, String.valueOf(value));
        }
        @Override
        public double getPlayerInfoDouble(String world, OfflinePlayer player, String node, double def) {
            String val = getPlayerInfoString(world, player, node, null);
            if (val == null) return def;
            try { return Double.parseDouble(val); } catch (NumberFormatException e) { return def; }
        }
        @Override
        public void setPlayerInfoDouble(String world, OfflinePlayer player, String node, double value) {
            setPlayerInfoString(world, player, node, String.valueOf(value));
        }
        @Override
        public boolean getPlayerInfoBoolean(String world, OfflinePlayer player, String node, boolean def) {
            String val = getPlayerInfoString(world, player, node, null);
            if (val == null) return def;
            return Boolean.parseBoolean(val);
        }
        @Override
        public void setPlayerInfoBoolean(String world, OfflinePlayer player, String node, boolean value) {
            setPlayerInfoString(world, player, node, String.valueOf(value));
        }
        @Override
        public String getGroupInfoString(String world, String group, String node, String def) {
            Group g = plugin.getGroupManager().getGroup(group);
            String val = g != null ? g.getMetaValue(node) : null;
            return val != null ? val : def;
        }
        @Override
        public void setGroupInfoString(String world, String group, String node, String value) {
            Group g = plugin.getGroupManager().getGroup(group);
            if (g != null) { g.setMeta(node, value); plugin.getStorage().saveGroup(g); }
        }
        @Override
        public int getGroupInfoInteger(String world, String group, String node, int def) {
            String val = getGroupInfoString(world, group, node, null);
            if (val == null) return def;
            try { return Integer.parseInt(val); } catch (NumberFormatException e) { return def; }
        }
        @Override
        public void setGroupInfoInteger(String world, String group, String node, int value) {
            setGroupInfoString(world, group, node, String.valueOf(value));
        }
        @Override
        public double getGroupInfoDouble(String world, String group, String node, double def) {
            String val = getGroupInfoString(world, group, node, null);
            if (val == null) return def;
            try { return Double.parseDouble(val); } catch (NumberFormatException e) { return def; }
        }
        @Override
        public void setGroupInfoDouble(String world, String group, String node, double value) {
            setGroupInfoString(world, group, node, String.valueOf(value));
        }
        @Override
        public boolean getGroupInfoBoolean(String world, String group, String node, boolean def) {
            String val = getGroupInfoString(world, group, node, null);
            if (val == null) return def;
            return Boolean.parseBoolean(val);
        }
        @Override
        public void setGroupInfoBoolean(String world, String group, String node, boolean value) {
            setGroupInfoString(world, group, node, String.valueOf(value));
        }

        // Legacy
        @Override public String getPlayerPrefix(String world, String playerName) { return ""; }
        @Override public void setPlayerPrefix(String world, String playerName, String prefix) {}
        @Override public String getPlayerSuffix(String world, String playerName) { return ""; }
        @Override public void setPlayerSuffix(String world, String playerName, String suffix) {}
        @Override public int getPlayerInfoInteger(String world, String playerName, String node, int def) { return def; }
        @Override public void setPlayerInfoInteger(String world, String playerName, String node, int value) {}
        @Override public double getPlayerInfoDouble(String world, String playerName, String node, double def) { return def; }
        @Override public void setPlayerInfoDouble(String world, String playerName, String node, double value) {}
        @Override public boolean getPlayerInfoBoolean(String world, String playerName, String node, boolean def) { return def; }
        @Override public void setPlayerInfoBoolean(String world, String playerName, String node, boolean value) {}
        @Override public String getPlayerInfoString(String world, String playerName, String node, String def) { return def; }
        @Override public void setPlayerInfoString(String world, String playerName, String node, String value) {}
    }
}
