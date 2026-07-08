package ir.permscraft.managers;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.api.event.EventBus;
import ir.permscraft.api.event.PermsCraftEvent;
import ir.permscraft.api.node.Node;
import ir.permscraft.PermsCraft;
import ir.permscraft.context.Context;
import ir.permscraft.context.ContextSet;
import ir.permscraft.inject.PCPermissible;
import ir.permscraft.inject.PermissibleInjector;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import ir.permscraft.utils.WildcardUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.util.*;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class UserManager {

    private final PermsCraft plugin;
    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    private final Map<String, UUID> usernameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private final Set<UUID> pendingUnload = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Set<String>> cachedServerKnown = new AtomicReference<>(null);

    public UserManager(PermsCraft plugin) {
        this.plugin = plugin;
    }

    // ── Pre-login ─────────────────────────────────────────────────────────────

    public void preloadUser(UUID uuid, String username) {
        pendingUnload.remove(uuid);
        User user = plugin.getStorage().loadUser(uuid, username);
        if (pendingUnload.contains(uuid)) {
            pendingUnload.remove(uuid);
            return;
        }
        users.put(uuid, user);
        usernameIndex.put(user.getUsername().toLowerCase(), uuid);
    }

    public void applyPermissionsOnJoin(Player player) {
        User user = users.get(player.getUniqueId());
        if (user == null) {
            plugin.getLogger().warning("[PermsCraft] User " + player.getName()
                    + " was not pre-loaded; falling back to sync load.");
            user = plugin.getStorage().loadUser(player.getUniqueId(), player.getName());
            users.put(player.getUniqueId(), user);
            usernameIndex.put(user.getUsername().toLowerCase(), player.getUniqueId());
        }
        // FIX (LP Feature #1 — Permissible Injection): inject our PCPermissible
        // into the player before applying permissions. This replaces the old
        // PermissionAttachment path so that player.hasPermission() goes directly
        // through our ConcurrentHashMap — no attachment overhead, thread-safe.
        PCPermissible pc = new PCPermissible(player, plugin);
        PermissibleInjector.inject(player, pc, plugin);
        applyPermissions(player, user);
    }

    // ── Async load ────────────────────────────────────────────────────────────

    public java.util.concurrent.CompletableFuture<User> getOrLoadUserAsync(UUID uuid, String username) {
        User cached = users.get(uuid);
        if (cached != null) {
            return java.util.concurrent.CompletableFuture.completedFuture(cached);
        }
        java.util.concurrent.CompletableFuture<User> future = new java.util.concurrent.CompletableFuture<>();
        // FOLIA: async I/O always uses AsyncScheduler
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                User user = plugin.getStorage().loadUser(uuid, username);
                if (Bukkit.getPlayer(uuid) != null && !pendingUnload.contains(uuid)) {
                    users.putIfAbsent(uuid, user);
                    usernameIndex.put(user.getUsername().toLowerCase(), uuid);
                }
                future.complete(user);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    // ── Unload ────────────────────────────────────────────────────────────────

    public void unloadUser(UUID uuid) {
        pendingUnload.add(uuid);
        User removed = users.remove(uuid);
        if (removed != null) usernameIndex.remove(removed.getUsername().toLowerCase());
        // FIX (Injection): uninject PCPermissible on quit (dummy=true — player leaving)
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            PermissibleInjector.uninject(player, true);
        }
        // Legacy attachment cleanup (no-op when injection is active, safety net otherwise)
        PermissionAttachment att = attachments.remove(uuid);
        if (att != null) {
            try { att.remove(); } catch (Exception ignored) {}
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Collection<User> getAllLoadedUsers() {
        return Collections.unmodifiableCollection(users.values());
    }

    public Collection<User> getAllUsers() {
        return getAllLoadedUsers();
    }

    public User getUser(UUID uuid) { return users.get(uuid); }

    public User getUser(String username) {
        UUID uuid = usernameIndex.get(username.toLowerCase());
        return uuid != null ? users.get(uuid) : null;
    }

    // ── Offline user loading (FIX: LP Gap #3 — Offline User Loading) ───────────

    /**
     * Returns the user if already cached (online or pre-loaded), otherwise
     * loads it from storage SYNCHRONOUSLY and registers it into the in-memory
     * map/index so subsequent calls (hasPermissionAsync, addPermission,
     * setPrefix, etc.) see a consistent, mutable User instance instead of
     * silently failing or re-reading storage every time.
     *
     * ⚠ BLOCKING I/O WARNING: when the user isn't cached this performs a
     * synchronous storage read (DB query or file read). Safe to call from
     * the REST API (Javalin worker threads) or any FoliaScheduler.runAsync
     * task. Do NOT call directly from the Bukkit main thread (command
     * handlers, listeners) for a user that might not be cached — setPrefix()
     * and setSuffix() already handle this internally by branching to async
     * automatically, so prefer those over calling this directly when on the
     * main thread.
     *
     * Does NOT inject a PCPermissible or touch Bukkit — the player isn't online.
     * username may be null if unknown (storage will reuse the last known name).
     */
    public User getOrLoadUserSync(UUID uuid, String username) {
        User cached = users.get(uuid);
        if (cached != null) return cached;

        User loaded = plugin.getStorage().loadUser(uuid, username);
        if (loaded == null) return null;

        // Don't clobber a real online/preloaded entry that may have been
        // inserted concurrently between the get() above and now.
        User existing = users.putIfAbsent(uuid, loaded);
        User effective = existing != null ? existing : loaded;
        usernameIndex.put(effective.getUsername().toLowerCase(), uuid);
        return effective;
    }

    public User getOrLoadUserSync(UUID uuid) {
        return getOrLoadUserSync(uuid, null);
    }

    // ── Mutations (all DB writes are async) ───────────────────────────────────

    public void addToGroup(UUID uuid, String groupName) {
        User user = users.get(uuid);
        if (user != null) user.addGroup(groupName);
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().addUserToGroup(uuid, groupName));
        if (user != null) {
            refreshPermissions(uuid);
            plugin.getRedisManager().publishUserRefresh(uuid);
            EventBus.fireNodeAdd(plugin, PermsCraftEvent.TargetType.USER, uuid,
                    user.getUsername(), Node.group(groupName).build(), "internal");
        }
    }

    public void removeFromGroup(UUID uuid, String groupName) {
        User user = users.get(uuid);
        boolean restoredDefault = false;
        if (user != null) {
            user.removeGroup(groupName);
            // FIX (default-group edge case): a user must always belong to at
            // least one group, otherwise they silently lose ALL group-based
            // permissions (including whatever "default" normally grants).
            // This can happen via REST API, GUI, /pc user ... group remove,
            // Vault, migration, or track demote/promote on a user whose only
            // guarantee that every user is at minimum a member of "default".
            if (user.getGroups().isEmpty()
                    && plugin.getTimedGroupManager().getActiveGroupNames(uuid.toString()).isEmpty()) {
                user.addGroup("default");
                restoredDefault = true;
                final UUID finalUuid = uuid;
                FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().addUserToGroup(finalUuid, "default"));
                plugin.getLogger().info("[PermsCraft] User " + user.getUsername()
                        + " was left with no groups after removing '" + groupName
                        + "' — re-added to 'default'.");
            }
        }
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().removeUserFromGroup(uuid, groupName));
        if (user != null) {
            refreshPermissions(uuid);
            plugin.getRedisManager().publishUserRefresh(uuid);
            EventBus.fireNodeRemove(plugin, PermsCraftEvent.TargetType.USER, uuid,
                    user.getUsername(), Node.group(groupName).build(), "internal");
            if (restoredDefault) {
                EventBus.fireNodeAdd(plugin, PermsCraftEvent.TargetType.USER, uuid,
                        user.getUsername(), Node.group("default").build(), "internal");
            }
        }
    }

    public void addPermission(UUID uuid, String permission) {
        User user = users.get(uuid);
        if (user != null) user.addPermission(permission);
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().addUserPermission(uuid, permission));
        if (user != null) {
            refreshPermissions(uuid);
            plugin.getRedisManager().publishUserRefresh(uuid);
            EventBus.fireNodeAdd(plugin, PermsCraftEvent.TargetType.USER, uuid,
                    user.getUsername(), EventBus.nodeFromString(permission), "internal");
        }
    }

    public void removePermission(UUID uuid, String permission) {
        User user = users.get(uuid);
        if (user != null) user.removePermission(permission);
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().removeUserPermission(uuid, permission));
        if (user != null) {
            refreshPermissions(uuid);
            plugin.getRedisManager().publishUserRefresh(uuid);
            EventBus.fireNodeRemove(plugin, PermsCraftEvent.TargetType.USER, uuid,
                    user.getUsername(), EventBus.nodeFromString(permission), "internal");
        }
    }

    public void setPrefix(UUID uuid, String prefix) {
        // FIX (LP Gap #3 — Offline Users): previously this was a silent no-op
        // for any user not currently cached in memory (i.e. anyone offline who
        // hadn't been pre-loaded), so prefixes set via REST/commands on offline
        // players were dropped entirely — not even written to storage.
        User cached = users.get(uuid);
        if (cached != null) {
            cached.setPrefix(prefix);
            FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().saveUser(cached));
            refreshPermissions(uuid);
            return;
        }
        // Not cached: the user may be genuinely offline. Loading from storage
        // is blocking I/O, so never do it inline on the main thread — always
        // hop async first, exactly like every other storage write in this class.
        FoliaScheduler.runAsync(plugin, () -> {
            User user = getOrLoadUserSync(uuid);
            if (user == null) return;
            user.setPrefix(prefix);
            plugin.getStorage().saveUser(user);
        });
    }

    public void setSuffix(UUID uuid, String suffix) {
        // FIX (LP Gap #3 — Offline Users): see setPrefix() above.
        User cached = users.get(uuid);
        if (cached != null) {
            cached.setSuffix(suffix);
            FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().saveUser(cached));
            refreshPermissions(uuid);
            return;
        }
        FoliaScheduler.runAsync(plugin, () -> {
            User user = getOrLoadUserSync(uuid);
            if (user == null) return;
            user.setSuffix(suffix);
            plugin.getStorage().saveUser(user);
        });
    }

    public void clearUser(UUID uuid, String username) {
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().clearUser(uuid));
        User user = users.get(uuid);
        if (user != null) {
            user.getPermissions().clear();
            user.getGroups().clear();
            user.addGroup("default");
            user.getMeta().clear();
            user.setPrefix("");
            user.setSuffix("");
            refreshPermissions(uuid);
            plugin.getRedisManager().publishUserRefresh(uuid);
        }
    }

    public void switchPrimaryGroup(UUID uuid, String newPrimary) {
        User user = users.get(uuid);
        if (user == null) return;
        newPrimary = newPrimary.toLowerCase();
        if (!user.getGroups().contains(newPrimary)) return;
        user.setPrimaryGroup(newPrimary);
        final String primary = newPrimary;
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().saveUserPrimaryGroup(uuid, primary));
        refreshPermissions(uuid);
        plugin.getRedisManager().publishUserRefresh(uuid);
    }

    // ── Meta ─────────────────────────────────────────────────────────────────

    public void setMeta(UUID uuid, String key, String value) {
        User user = users.get(uuid);
        if (user != null) user.setMeta(key, value);
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().saveMeta(uuid.toString(), false, key, value));
    }

    public void unsetMeta(UUID uuid, String key) {
        User user = users.get(uuid);
        if (user != null) user.unsetMeta(key);
        FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().deleteMeta(uuid.toString(), false, key));
    }

    public void setTimedMeta(UUID uuid, String key, String value, long expiryEpochSecond) {
        User user = users.get(uuid);
        if (user != null) user.setMeta(key, value);
        FoliaScheduler.runAsync(plugin, () ->
                plugin.getStorage().saveTimedMeta(uuid.toString(), false, key, value, expiryEpochSecond));
    }

    // ── Permission refresh ────────────────────────────────────────────────────

    /**
     * Refresh a player's permissions.
     *
     * FOLIA: PermissionAttachment must be modified on the entity's own region
     * thread. We use FoliaScheduler.runForEntity() which dispatches to
     * EntityScheduler on Folia and main thread on Bukkit.
     */
    public void refreshPermissions(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        User user = users.get(uuid);
        if (user == null) return;

        if (FoliaScheduler.isFolia()) {
            // Always schedule via EntityScheduler on Folia — never assume current thread
            FoliaScheduler.runForEntity(plugin, player, () -> applyPermissions(player, user));
        } else {
            // Bukkit: check primary thread as before
            if (Bukkit.isPrimaryThread()) {
                applyPermissions(player, user);
            } else {
                FoliaScheduler.runSync(plugin, () -> applyPermissions(player, user));
            }
        }
    }

    private void applyPermissions(Player player, User user) {
        // ── Step 1: Resolve the full permission map ───────────────────────────
        Set<String> serverKnown = buildServerKnownPermissions();
        String worldName = player.getWorld().getName();
        UUID uuid = player.getUniqueId();

        Map<String, Boolean> resolved;
        Set<String> cachedFull = plugin.getPermissionCache().getUserPermissions(uuid, worldName);
        if (cachedFull != null) {
            resolved = new java.util.LinkedHashMap<>();
            for (String node : cachedFull) {
                boolean negated = node.startsWith("-");
                resolved.put(negated ? node.substring(1) : node, !negated);
            }
        } else {
            resolved = plugin.getInheritanceGraph().resolveUser(user);

            ContextSet activeContext = plugin.getContextManager().getActiveContextSet(player);

            List<String> sortedGroups = user.getGroups().stream()
                    .map(plugin.getGroupManager()::getGroup)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Group::getWeight))
                    .map(Group::getName)
                    .toList();

            for (String groupName : sortedGroups) {
                Map<String, Boolean> groupCtxPerms = plugin.getContextManager()
                        .resolvePermissionsWithDenied(groupName, activeContext);
                resolved.putAll(groupCtxPerms);
            }

            Map<String, Boolean> userCtxPerms = plugin.getContextManager()
                    .resolvePermissionsWithDenied(uuid.toString(), activeContext);
            resolved.putAll(userCtxPerms);

            Set<String> toCache = new HashSet<>();
            resolved.forEach((p, v) -> toCache.add(v ? p : "-" + p));
            plugin.getPermissionCache().setUserPermissions(uuid, worldName, toCache);
        }

        // Expand wildcards against serverKnown (minecraft.* etc.)
        Map<String, Boolean> expanded = new java.util.LinkedHashMap<>(resolved);
        for (Map.Entry<String, Boolean> entry : resolved.entrySet()) {
            String perm = entry.getKey();
            if (perm.contains("*")) {
                boolean value = entry.getValue();
                for (String known : serverKnown) {
                    if (WildcardUtil.matches(perm, known)) {
                        expanded.putIfAbsent(known, value);
                    }
                }
            }
        }

        // ── Step 2: Push into PCPermissible (fast, thread-safe) ───────────────
        // FIX (LP Feature #1 — Permissible Injection): instead of rebuilding a
        // PermissionAttachment on every refresh we update the live ConcurrentHashMap
        // inside PCPermissible directly. This makes hasPermission() thread-safe and
        // eliminates the O(n) attachment rebuild cost.
        PCPermissible pc = PermissibleInjector.get(player);
        if (pc != null) {
            pc.updatePermissions(expanded);
        } else {
            // Fallback: injection unavailable on this fork — use old attachment mode.
            PermissionAttachment old = attachments.remove(uuid);
            if (old != null) { try { old.remove(); } catch (Exception ignored) {} }
            PermissionAttachment att = player.addAttachment(plugin);
            attachments.put(uuid, att);
            expanded.forEach(att::setPermission);
        }

        player.recalculatePermissions();

        if (PermsCraft.getInstance() != null
                && PermsCraft.getInstance().getAutoOpListener() != null) {
            PermsCraft.getInstance().getAutoOpListener().refreshAutoOp(player);
        }
    }

    private Set<String> buildServerKnownPermissions() {
        Set<String> cached = cachedServerKnown.get();
        if (cached != null) return cached;
        Set<String> known = new HashSet<>();
        for (org.bukkit.permissions.Permission p : Bukkit.getPluginManager().getPermissions()) {
            known.add(p.getName().toLowerCase());
        }
        Set<String> immutable = Collections.unmodifiableSet(known);
        cachedServerKnown.compareAndSet(null, immutable);
        return cachedServerKnown.get();
    }

    public void invalidateServerKnownCache() {
        cachedServerKnown.set(null);
    }

    // ── LP Feature #2: Thread-safe async permission check ─────────────────────

    /**
     * Check a permission for an online player from ANY thread (main or async).
     *
     * Safe because PCPermissible uses a ConcurrentHashMap internally.
     * Falls back to the inheritance graph for offline/uninjected players.
     *
     * FIX (LP Gap #3 — Offline Users): an offline user who hadn't been
     * pre-loaded into the in-memory map previously returned false
     * unconditionally — indistinguishable from "permission denied" even
     * though the user's data was never actually read. This now falls back to
     * a storage load so the result reflects the user's real permissions.
     *
     * THREADING: for an already-cached user (online, or previously loaded)
     * this is non-blocking, as before. For a genuinely offline user not yet
     * in the cache, this performs a blocking storage read — do not call this
     * fallback path from the main thread for such users. Callers that need to
     * be main-thread-safe should pre-load via getOrLoadUserSync() from an
     * async task first (see UserRoutes, which does this before calling here).
     */
    public boolean hasPermissionAsync(UUID uuid, String permission) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            PCPermissible pc = PermissibleInjector.get(player);
            if (pc != null) return pc.hasPermission(permission);
            // Fallback: player online but not injected (rare edge-case)
            return player.hasPermission(permission);
        }
        // Offline: try cache first, then fall back to a storage load so we
        // don't silently treat "never loaded" the same as "denied".
        User user = getOrLoadUserSync(uuid);
        if (user == null) return false;
        return plugin.getInheritanceGraph().hasPermission(user, permission);
    }

    /**
     * FIX (Bug #4 — import/restore desync for online players): backup
     * import/restore previously called plugin.getStorage().loadUser()/
     * saveUser() directly, bypassing UserManager entirely. If the target
     * player was online at that moment, their in-memory User and live
     * Bukkit permissions were never updated — the DB changed but the
     * player's actual permissions stayed stale until their next relog.
     *
     * This routes the merge through the correct cached-vs-offline path
     * (same pattern as setPrefix/setSuffix) so the in-memory User used by
     * an online player is mutated directly and refreshPermissions() is
     * called, while an offline player's data is safely loaded/merged/saved
     * off the calling thread.
     *
     * @param merge receives the resolved User (online in-memory instance,
     *              or a freshly loaded/offline instance) to apply
     *              prefix/suffix/groups/permissions/meta onto. Called
     *              off the calling thread when the user isn't cached.
     */
    public void importUserData(UUID uuid, String username, java.util.function.Consumer<User> merge) {
        User cached = users.get(uuid);
        if (cached != null) {
            merge.accept(cached);
            FoliaScheduler.runAsync(plugin, () -> plugin.getStorage().saveUser(cached));
            refreshPermissions(uuid);
            plugin.getRedisManager().publishUserRefresh(uuid);
            return;
        }
        FoliaScheduler.runAsync(plugin, () -> {
            User user = getOrLoadUserSync(uuid, username);
            if (user == null) return;
            merge.accept(user);
            plugin.getStorage().saveUser(user);
        });
    }
}
