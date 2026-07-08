package ir.permscraft.gui;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class GuiManager implements Listener {

    private final PermsCraft plugin;
    private final Map<UUID, BaseGui>          openGuis      = new ConcurrentHashMap<>();
    final Map<UUID, Consumer<String>> awaitingInput = new ConcurrentHashMap<>();

    /**
     * Players currently in the middle of a GUI-to-GUI transition.
     * When openGui() is called while a GUI is already open, Bukkit fires
     * InventoryCloseEvent for the old inventory AFTER the new one is registered.
     * Without this guard, onClose() would see the new GUI's inventory and wipe it.
     *
     * Flow:
     *   1. openGui(p, gui2) → put uuid in transitioning, put gui2 in openGuis, call gui2.open()
     *   2. Bukkit fires CloseEvent for gui1's inventory
     *   3. onClose() sees uuid in transitioning → skip removal, remove from transitioning
     */
    private final Set<UUID> transitioning = ConcurrentHashMap.newKeySet();

    public GuiManager(PermsCraft plugin) { this.plugin = plugin; }

    public void init() {
        boolean isPaper = false;
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            isPaper = true;
        } catch (ClassNotFoundException ignored) {}

        if (isPaper) {
            plugin.getServer().getPluginManager().registerEvents(new PaperChatListener(plugin, this), plugin);
        } else {
            plugin.getServer().getPluginManager().registerEvents(new LegacyChatListener(plugin, this), plugin);
        }
    }

    // ── Open helpers ──────────────────────────────────────────────────────────

    public void openGui(Player p, BaseGui gui) {
        UUID uuid = p.getUniqueId();

        // If a GUI is already registered for this player, the old inventory is
        // about to be closed by Bukkit — mark as transitioning so onClose() skips it.
        if (openGuis.containsKey(uuid)) {
            transitioning.add(uuid);
        }

        openGuis.put(uuid, gui);
        gui.open(p);
    }

    // Original screens
    public void openMain(Player p)             { openGui(p, new MainEditorGui(plugin, this, p)); }
    public void openGroupList(Player p)        { openGui(p, new GroupListGui(plugin, this, p)); }
    public void openGroupEditor(Player p, String g)                  { openGui(p, new GroupEditorGui(plugin, this, p, g)); }
    public void openGroupPermissions(Player p, String g, int page)   { openGui(p, new GroupPermissionsGui(plugin, this, p, g, page)); }
    public void openGroupInheritance(Player p, String g)             { openGui(p, new GroupInheritanceGui(plugin, this, p, g)); }
    public void openPermissionBrowser(Player p, String g, boolean deny, java.util.function.Consumer<String> cb) {
        openGui(p, new PermissionBrowserGui(plugin, this, p, g, deny, cb));
    }

    // New screens from v3
    public void openGroupMeta(Player p, String g)                    { openGui(p, new GroupMetaGui(plugin, this, p, g)); }
    public void openGroupContextPerms(Player p, String g)            { openGui(p, new GroupContextPermsGui(plugin, this, p, g)); }

    public void openUserSearch(Player p)       { openGui(p, new UserSearchGui(plugin, this, p)); }
    public void openUserEditor(Player p, UUID uuid, String name)     { openGui(p, new UserEditorGui(plugin, this, p, uuid, name)); }
    public void openUserGroups(Player p, UUID uuid, String name)     { openGui(p, new UserGroupsGui(plugin, this, p, uuid, name)); }
    public void openUserPermissions(Player p, UUID uuid, String name, int page) { openGui(p, new UserPermissionsGui(plugin, this, p, uuid, name, page)); }
    public void openUserTimedPermissions(Player p, UUID uuid, String name)      { openGui(p, new UserTimedPermissionsGui(plugin, this, p, uuid, name)); }

    /**
     * Open the Permission Browser for a USER (personal permissions).
     * On selection → addPermission to the user then return to UserPermissionsGui.
     */
    public void openUserPermissionBrowser(Player p, UUID uuid, String name, boolean deny) {
        java.util.function.Consumer<String> cb = node -> {
            String finalNode = deny
                    ? (node.startsWith("-") ? node : "-" + node)
                    : (node.startsWith("-") ? node.substring(1) : node);
            plugin.getUserManager().addPermission(uuid, finalNode);
            plugin.getLogManager().log(p,
                    ir.permscraft.logging.LogEntry.Action.USER_PERM_ADD, name, finalNode);
            p.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                    (deny ? "&cDenied" : "&aGranted") + " &e" + finalNode + (deny ? " &cfor" : " &ato") + " &b" + name));
            openUserPermissions(p, uuid, name, 0);
        };
        Runnable back = () -> openUserPermissions(p, uuid, name, 0);
        openGui(p, new PermissionBrowserGui(plugin, this, p, name, deny, cb, back));
    }

    /**
     * Open the Permission Browser for adding a TIMED permission to a user.
     * On selection → asks for duration → addTimedPermission → return to UserTimedPermissionsGui.
     */
    public void openUserTimedPermissionBrowser(Player p, UUID uuid, String name) {
        java.util.function.Consumer<String> cb = node -> {
            String clean = node.startsWith("-") ? node.substring(1) : node;
            // After picking a node, ask for duration via chat input
            awaitInput(p, "Duration for &e" + clean + " &7(e.g. &b1d&7, &b1w&7, &b1mo&7, &b1y&7, &b1d10h&7):", durStr -> {
                long secs;
                try {
                    secs = ir.permscraft.managers.TimedPermissionManager.parseDuration(durStr);
                } catch (Exception ex) {
                    secs = 0;
                }
                if (secs <= 0) {
                    p.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                            "&cInvalid duration. Units: s m h d w mo y  | Compound: 1d10h, 2w3d"));
                    openUserTimedPermissions(p, uuid, name);
                    return;
                }
                plugin.getTimedPermissionManager().addTimedPermission(uuid.toString(), false, clean, secs);
                plugin.getLogManager().log(p,
                        ir.permscraft.logging.LogEntry.Action.USER_PERM_ADD,
                        name, clean + " (timed " + durStr + ")");
                p.sendMessage(ir.permscraft.utils.MessageUtil.colorizeString(
                        "&aAdded timed permission &e" + clean
                                + " &afor &e" + durStr + " &ato &b" + name));
                openUserTimedPermissions(p, uuid, name);
            });
        };
        Runnable back = () -> openUserTimedPermissions(p, uuid, name);
        openGui(p, new PermissionBrowserGui(plugin, this, p, name + " &8(timed)", false, cb, back));
    }

    // New screens from v3
    public void openUserMeta(Player p, UUID uuid, String name)              { openGui(p, new UserMetaGui(plugin, this, p, uuid, name)); }
    public void openUserContextPerms(Player p, UUID uuid, String name)      { openGui(p, new UserContextPermsGui(plugin, this, p, uuid, name)); }

    public void openTrackList(Player p)                { openGui(p, new TrackListGui(plugin, this, p)); }
    public void openTrackEditor(Player p, String t)    { openGui(p, new TrackEditorGui(plugin, this, p, t)); }
    public void openLogViewer(Player p, int page)      { openGui(p, new LogViewerGui(plugin, this, p, page)); }
    public void openBackup(Player p)                   { openGui(p, new BackupGui(plugin, this, p)); }

    public void openConfirm(Player p, String title, String description,
                            Runnable onConfirm, Runnable onCancel) {
        openGui(p, new ConfirmGui(plugin, this, p, title, description, onConfirm, onCancel));
    }

    // ── Chat input ────────────────────────────────────────────────────────────

    public void awaitInput(Player player, String prompt, Consumer<String> callback) {
        awaitingInput.put(player.getUniqueId(), callback);
        FoliaScheduler.runSync(plugin, () -> {
            player.closeInventory();
            player.sendMessage(MessageUtil.colorizeString(
                    "&8[&bPermsCraft&8] &e" + prompt + " &8(&ccancel &8to abort)"));
        });
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        BaseGui gui = openGuis.get(player.getUniqueId());
        if (gui == null) return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(gui.inventory)) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;

        gui.handleClick(event.getSlot(), event.getClick(), player);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        // GUI-to-GUI transition: openGui() already registered the next GUI.
        // The CloseEvent firing here is for the *previous* inventory — skip removal.
        if (transitioning.remove(uuid)) return;

        // Normal close (player pressed Escape or server closed the inventory):
        // only remove if the closing inventory is the one we currently track.
        BaseGui current = openGuis.get(uuid);
        if (current == null) return;
        if (event.getInventory().equals(current.inventory)) {
            openGuis.remove(uuid);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openGuis.remove(uuid);
        awaitingInput.remove(uuid);
        transitioning.remove(uuid);
    }

    // ── Inner chat listener classes ───────────────────────────────────────────

    /** Paper 1.19+ chat listener using the non-deprecated AsyncChatEvent. */
    public static class PaperChatListener implements Listener {
        private final PermsCraft plugin;
        private final GuiManager guiManager;

        public PaperChatListener(PermsCraft plugin, GuiManager guiManager) {
            this.plugin = plugin;
            this.guiManager = guiManager;
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
            Consumer<String> cb = guiManager.awaitingInput.remove(event.getPlayer().getUniqueId());
            if (cb == null) return;
            event.setCancelled(true);
            String msg = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(event.message());
            FoliaScheduler.runSync(plugin, () -> {
                if (msg.equalsIgnoreCase("cancel"))
                    event.getPlayer().sendMessage(MessageUtil.colorizeString("&cCancelled."));
                else
                    cb.accept(msg);
            });
        }
    }

    /** Legacy Bukkit/Spigot chat listener for servers older than Paper 1.19. */
    public static class LegacyChatListener implements Listener {
        private final PermsCraft plugin;
        private final GuiManager guiManager;

        public LegacyChatListener(PermsCraft plugin, GuiManager guiManager) {
            this.plugin = plugin;
            this.guiManager = guiManager;
        }

        @SuppressWarnings("deprecation")
        @EventHandler(priority = EventPriority.LOW)
        public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
            Consumer<String> cb = guiManager.awaitingInput.remove(event.getPlayer().getUniqueId());
            if (cb == null) return;
            event.setCancelled(true);
            String msg = event.getMessage();
            FoliaScheduler.runSync(plugin, () -> {
                if (msg.equalsIgnoreCase("cancel"))
                    event.getPlayer().sendMessage(MessageUtil.colorizeString("&cCancelled."));
                else
                    cb.accept(msg);
            });
        }
    }
}
