package ir.permscraft.listeners;

import ir.permscraft.PermsCraft;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerLoginListener implements Listener {

    private final PermsCraft plugin;

    public PlayerLoginListener(PermsCraft plugin) {
        this.plugin = plugin;
    }

    /**
     * FIX (permission gap): The previous implementation used PlayerJoinEvent,
     * meaning the player was briefly on the server with NO permissions attached
     * while the async DB load was in flight.
     *
     * We now pre-load user data during AsyncPlayerPreLoginEvent (which fires
     * BEFORE the player actually joins the server). The result is cached so
     * that when PlayerJoinEvent fires we can apply permissions instantly on
     * the main thread with no DB round-trip.
     *
     * AsyncPlayerPreLoginEvent is always asynchronous — blocking here is safe
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // Block until user data is loaded. If it fails, we still allow login
        // (the player will be in the "default" group with no extra perms).
        try {
            plugin.getUserManager().preloadUser(event.getUniqueId(), event.getName());
        } catch (Exception ex) {
            plugin.getLogger().severe("[PermsCraft] Failed to pre-load user "
                    + event.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * By the time PlayerJoinEvent fires the user data is already in the cache
     * (loaded by onPreLogin above). We just need to attach the Bukkit
     * PermissionAttachment on the main thread.
     *
     * FIX (Priority-1): Also drain the BungeeCord pending message queue here.
     * Messages queued while the server was empty will now be delivered as soon
     * as the first player joins — the earliest possible moment.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getUserManager().applyPermissionsOnJoin(event.getPlayer());
        // Drain queued BungeeCord messages (safe to call even when queue is empty
        // or when Redis is the active transport — MessagingManager guards both).
        plugin.getMessagingManager().drainQueue();
    }

    /**
     * FIX (memory leak): preloadUser() above always inserts the player into
     * UserManager's in-memory map during AsyncPlayerPreLoginEvent. If the
     * login is ultimately rejected — by a whitelist/anti-bot plugin, a ban,
     * or any other plugin calling event.disallow() — neither PlayerJoinEvent
     * nor PlayerQuitEvent will ever fire for this connection, so unloadUser()
     * (normally called from onQuit) would never run and the preloaded User
     * entry would stay in memory forever. On servers that reject many
     * connections (whitelist, anti-bot, rate limiting) this leaks unboundedly.
     *
     * PlayerLoginEvent fires after AsyncPlayerPreLoginEvent and after every
     * other plugin's login checks have run, so by MONITOR priority here the
     * final result is known. If it's not ALLOWED, clean up immediately.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginDenied(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            plugin.getUserManager().unloadUser(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getUserManager().unloadUser(event.getPlayer().getUniqueId());
    }

    /**
     * FIX Bug #2: When a player changes worlds, we must invalidate only the
     * OLD world's cache entry before refreshing. The previous code called
     * refreshPermissions() directly — which re-uses the cache if it hasn't
     * expired yet — so the player kept the old world's context permissions
     * until TTL expiry.
     *
     * Now we explicitly evict uuid:oldWorldName from the cache first, so
     * the recompute picks up the new world's context layer immediately.
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        String oldWorld = event.getFrom().getName();
        plugin.getPermissionCache().invalidateUserInWorld(
                event.getPlayer().getUniqueId(), oldWorld);
        plugin.getUserManager().refreshPermissions(event.getPlayer().getUniqueId());
    }
}
