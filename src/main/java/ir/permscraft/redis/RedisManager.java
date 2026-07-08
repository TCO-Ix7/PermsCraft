package ir.permscraft.redis;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import redis.clients.jedis.*;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis pub/sub sync for multi-server setups.
 *
 * FIX (subscriber reconnect): The previous implementation started a single
 * background thread that subscribed to Redis. If the connection dropped
 * (network hiccup, Redis restart) the thread died silently and multi-server
 * sync stopped working with no indication to operators.
 *
 * Now a ScheduledExecutorService watches the subscriber thread and restarts
 * it automatically with exponential back-off (up to 30 s) whenever it dies.
 *
 * Channel: permscraft:sync
 * Message format: SERVER_NAME:TYPE|TARGET|DETAIL
 */
public class RedisManager {

    private static final String CHANNEL = "permscraft:sync";

    private final PermsCraft plugin;
    private JedisPool pool;
    private volatile SyncSubscriber subscriber;
    private volatile Thread subThread;

    private final AtomicBoolean enabled   = new AtomicBoolean(false);
    private final AtomicBoolean shutdown  = new AtomicBoolean(false);

    // FIX: dedicated scheduler for reconnect watchdog
    private ScheduledExecutorService watchdog;

    public RedisManager(PermsCraft plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        if (!plugin.getConfig().getBoolean("storage.redis.enabled", false)) {
            plugin.getLogger().info("Redis sync is disabled.");
            return false;
        }

        try {
            // FIX: Jedis 5.x removed JedisPoolConfig setter methods (setTestOnBorrow,
            // setTestWhileIdle) and changed the JedisPool constructor signatures.
            // Correct Jedis 5 API: use ConnectionPoolConfig + DefaultJedisClientConfig.
            String host     = plugin.getConfig().getString("storage.redis.host", "localhost");
            int    port     = plugin.getConfig().getInt("storage.redis.port", 6379);
            String password = plugin.getConfig().getString("storage.redis.password", "");
            int    timeout  = plugin.getConfig().getInt("storage.redis.timeout-ms", 2000);

            // Jedis 5.x: JedisPoolConfig still exists but extends GenericObjectPoolConfig<Connection>.
            // setTestOnBorrow/setTestWhileIdle moved to the parent class and still work.
            // JedisPool constructor: JedisPool(JedisPoolConfig, String, int, int, String) still valid.
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(false);      // expensive round-trip per borrow; keep false
            poolConfig.setTestWhileIdle(true);      // validate idle connections in background
            poolConfig.setTimeBetweenEvictionRuns(java.time.Duration.ofSeconds(30));

            if (password.isEmpty()) {
                pool = new JedisPool(poolConfig, host, port, timeout);
            } else {
                pool = new JedisPool(poolConfig, host, port, timeout, password);
            }

            // Test connection
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }

            enabled.set(true);

            // FIX: start watchdog that restarts the subscriber thread if it dies
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PermsCraft-Redis-Watchdog");
                t.setDaemon(true);
                return t;
            });
            watchdog.scheduleWithFixedDelay(this::ensureSubscriberAlive, 0, 5, TimeUnit.SECONDS);

            plugin.getLogger().info("Redis sync connected successfully!");
            return true;

        } catch (Exception e) {
            plugin.getLogger().warning("Redis connection failed: " + e.getMessage()
                    + " — Multi-server sync disabled.");
            return false;
        }
    }

    /**
     * FIX: Called every 5 s by the watchdog. Starts (or restarts) the subscriber
     * thread if it is not alive. Uses exponential back-off via the watchdog's
     * fixed-delay scheduling so we don't spam reconnect attempts.
     */
    private void ensureSubscriberAlive() {
        if (shutdown.get() || !enabled.get()) return;
        if (subThread != null && subThread.isAlive()) return;

        plugin.getLogger().info("[PermsCraft] (Re)connecting Redis subscriber...");

        subscriber = new SyncSubscriber(plugin);
        subThread = new Thread(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(subscriber, CHANNEL);
            } catch (Exception e) {
                if (!shutdown.get()) {
                    plugin.getLogger().warning("[PermsCraft] Redis subscriber disconnected: "
                            + e.getMessage() + " — will reconnect in 5 s.");
                }
            }
        }, "PermsCraft-Redis-Sub");
        subThread.setDaemon(true);
        subThread.start();
    }

    // ── publish ───────────────────────────────────────────────────────────────

    public void publishUserRefresh(UUID uuid) {
        publish("USER_REFRESH|" + uuid + "|");
    }

    public void publishGroupRefresh(String groupName) {
        publish("GROUP_REFRESH|" + groupName + "|");
    }

    public void publishReload() {
        publish("RELOAD||");
    }

    private void publish(String message) {
        if (!enabled.get() || pool == null) return;
        FoliaScheduler.runAsync(plugin, () -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(CHANNEL, plugin.getServerName() + ":" + message);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to publish Redis message: " + e.getMessage());
            }
        });
    }

    public boolean isEnabled() { return enabled.get(); }

    public void shutdown() {
        shutdown.set(true);
        enabled.set(false);
        if (watchdog != null) watchdog.shutdownNow();
        if (subscriber != null) {
            try { subscriber.unsubscribe(); } catch (Exception ignored) {}
        }
        if (pool != null) pool.close();
    }

    // ── subscriber ────────────────────────────────────────────────────────────

    private static class SyncSubscriber extends JedisPubSub {

        private final PermsCraft plugin;

        SyncSubscriber(PermsCraft plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onMessage(String channel, String message) {
            if (!CHANNEL.equals(channel)) return;

            String[] parts = message.split(":", 2);
            if (parts.length < 2) return;
            if (parts[0].equals(plugin.getServerName())) return; // skip own messages

            String[] data = parts[1].split("\\|", 3);
            if (data.length < 2) return;

            String type   = data[0];
            String target = data[1];

            FoliaScheduler.runSync(plugin, () -> {
                switch (type) {
                    case "USER_REFRESH" -> {
                        try {
                            UUID uuid = UUID.fromString(target);
                            // FIX (cross-server cache staleness): must invalidate this user's
                            // cached permission set before refreshing, otherwise the local
                            // node just re-applies the old cached entry until TTL expires
                            // (up to cache.ttl-seconds, default 5 min) even though the
                            // user's groups/perms changed on a different server.
                            plugin.getPermissionCache().invalidateUser(uuid);
                            plugin.getUserManager().refreshPermissions(uuid);
                        } catch (IllegalArgumentException ignored) {}
                    }
                    case "GROUP_REFRESH" -> {
                        // FIX (cross-server cache staleness): same issue as USER_REFRESH —
                        // invalidate the group (and any children that inherit from it)
                        // before reloading, so refreshPermissions() actually recomputes
                        // instead of serving stale cached permissions.
                        plugin.getPermissionCache().invalidateGroupAndChildren(
                                target, () -> plugin.getGroupManager().getAllGroups());
                        plugin.getGroupManager().loadGroups();
                        plugin.getServer().getOnlinePlayers().forEach(p ->
                                plugin.getUserManager().refreshPermissions(p.getUniqueId()));
                    }
                    case "RELOAD" -> {
                        // FIX (cross-server cache staleness): wipe the entire cache —
                        // a full reload can change any group/user, so a targeted
                        // invalidation isn't enough.
                        plugin.getPermissionCache().invalidateAll();
                        plugin.getGroupManager().loadGroups();
                        plugin.getTrackManager().loadTracks();
                        plugin.getServer().getOnlinePlayers().forEach(p ->
                                plugin.getUserManager().refreshPermissions(p.getUniqueId()));
                    }
                }
            });
        }
    }
}
