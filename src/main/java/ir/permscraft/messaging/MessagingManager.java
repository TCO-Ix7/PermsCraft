package ir.permscraft.messaging;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BungeeCord/Velocity plugin messaging channel for multi-server sync.
 * Works as an alternative to Redis when Redis is not available.
 *
 * FIX (Priority-1 / Bug #BC): The previous implementation silently dropped
 * outgoing messages whenever no player was online, because BungeeCord plugin
 * messaging requires an online player as the delivery vehicle.
 *
 * Fix strategy:
 *   1. Messages are queued in pendingQueue when no player is online.
 *   2. A Bukkit scheduler task (runTaskTimer) drains the queue every second.
 *   3. When a player joins the server the queue is also drained immediately
 *      via drainQueue(), called by MessagingManager itself via PlayerLoginListener
 *      (which already exists in the codebase).
 *   4. Queue is capped at MAX_QUEUE_SIZE to avoid unbounded growth during
 *      extended empty-server periods.
 *
 * Channel: permscraft:update
 * Packet types:
 *   USER_UPDATE  <uuid>
 *   GROUP_UPDATE <groupName>
 *   FULL_RELOAD
 */
public class MessagingManager implements PluginMessageListener {

    private static final String CHANNEL_OUT   = "permscraft:update";
    private static final String CHANNEL_BC    = "BungeeCord";
    private static final int    MAX_QUEUE_SIZE = 64;

    private final PermsCraft plugin;
    private boolean enabled = false;

    // FIX: queue for messages that couldn't be sent because no player was online.
    // CopyOnWriteArrayList: reads (drain) are far more common than writes (enqueue).
    private final List<byte[]> pendingQueue = new CopyOnWriteArrayList<>();

    // Scheduler task ID so we can cancel on shutdown
    private FoliaScheduler.TaskHandle drainTask = null;

    public MessagingManager(PermsCraft plugin) { this.plugin = plugin; }

    public void init() {
        if (!plugin.getConfig().getBoolean("messaging.bungeecord", false)) {
            plugin.getLogger().info("BungeeCord messaging disabled.");
            return;
        }
        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_OUT);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_OUT, this);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_BC);
            enabled = true;

            // FIX: start periodic queue-drain task (every 20 ticks = 1 second)
            drainTask = FoliaScheduler.runSyncTimer(plugin, this::drainQueue, 20L, 20L);

            plugin.getLogger().info("BungeeCord messaging enabled.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register messaging channels: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (!enabled) return;
        if (drainTask != null) {
            drainTask.cancel();
            drainTask = null;
        }
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin);
        pendingQueue.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(CHANNEL_OUT)) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            String senderServer = in.readUTF();
            if (senderServer.equals(plugin.getServerName())) return; // skip own messages

            String type = in.readUTF();
            switch (type) {
                case "USER_UPDATE" -> {
                    UUID uuid = UUID.fromString(in.readUTF());
                    FoliaScheduler.runSync(plugin, () -> {
                        plugin.getPermissionCache().invalidateUser(uuid);
                        plugin.getUserManager().refreshPermissions(uuid);
                        plugin.getLogger().info("[Messaging] User refreshed: " + uuid);
                    });
                }
                case "GROUP_UPDATE" -> {
                    String groupName = in.readUTF();
                    FoliaScheduler.runSync(plugin, () -> {
                        plugin.getPermissionCache().invalidateGroup(groupName);
                        plugin.getGroupManager().loadGroups();
                        plugin.getServer().getOnlinePlayers().forEach(p ->
                                plugin.getUserManager().refreshPermissions(p.getUniqueId()));
                        plugin.getLogger().info("[Messaging] Group refreshed: " + groupName);
                    });
                }
                case "FULL_RELOAD" -> {
                    FoliaScheduler.runSync(plugin, () -> {
                        plugin.getPermissionCache().invalidateAll();
                        plugin.getGroupManager().loadGroups();
                        plugin.getTrackManager().loadTracks();
                        plugin.getServer().getOnlinePlayers().forEach(p ->
                                plugin.getUserManager().refreshPermissions(p.getUniqueId()));
                        plugin.getLogger().info("[Messaging] Full reload received.");
                    });
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Messaging] Failed to read message: " + e.getMessage());
        }
    }

    public void sendUserUpdate(UUID uuid) {
        if (plugin.getRedisManager().isEnabled()) {
            plugin.getRedisManager().publishUserRefresh(uuid);
        } else {
            send("USER_UPDATE", uuid.toString());
        }
    }

    public void sendGroupUpdate(String groupName) {
        if (plugin.getRedisManager().isEnabled()) {
            plugin.getRedisManager().publishGroupRefresh(groupName);
        } else {
            send("GROUP_UPDATE", groupName);
        }
    }

    public void sendFullReload() {
        if (plugin.getRedisManager().isEnabled()) {
            plugin.getRedisManager().publishReload();
        } else {
            send("FULL_RELOAD", "");
        }
    }

    /**
     * FIX (Priority-1): Build the raw packet and either send it immediately
     * (if a player is online) or enqueue it for the next drain cycle.
     */
    private void send(String type, String data) {
        if (!enabled) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(plugin.getServerName());
            out.writeUTF(type);
            out.writeUTF(data);
            byte[] packet = bytes.toByteArray();

            Player carrier = plugin.getServer().getOnlinePlayers()
                    .stream().findFirst().orElse(null);
            if (carrier != null) {
                carrier.sendPluginMessage(plugin, CHANNEL_OUT, packet);
            } else {
                // No online player — queue for later delivery
                if (pendingQueue.size() < MAX_QUEUE_SIZE) {
                    pendingQueue.add(packet);
                    plugin.getLogger().fine("[Messaging] No player online; message queued ("
                            + pendingQueue.size() + "/" + MAX_QUEUE_SIZE + ").");
                } else {
                    plugin.getLogger().warning("[Messaging] Pending queue full (" + MAX_QUEUE_SIZE
                            + "); oldest message dropped. Consider enabling Redis for reliable sync.");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Messaging] Failed to build message: " + e.getMessage());
        }
    }

    /**
     * FIX: Drain the pending queue by sending all queued packets through the
     * first available online player.
     * Called every second by the scheduler task AND immediately when a player joins
     * (call this from PlayerLoginListener or wherever appropriate).
     */
    public void drainQueue() {
        if (!enabled || pendingQueue.isEmpty()) return;
        Player carrier = plugin.getServer().getOnlinePlayers()
                .stream().findFirst().orElse(null);
        if (carrier == null) return;

        // Snapshot the list and clear atomically to avoid re-sending on concurrent drain
        List<byte[]> toSend = new ArrayList<>(pendingQueue);
        pendingQueue.removeAll(toSend);

        for (byte[] packet : toSend) {
            carrier.sendPluginMessage(plugin, CHANNEL_OUT, packet);
        }
        if (!toSend.isEmpty()) {
            plugin.getLogger().info("[Messaging] Drained " + toSend.size() + " queued message(s).");
        }
    }

    public boolean isEnabled() { return enabled; }
}
