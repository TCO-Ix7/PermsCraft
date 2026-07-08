package ir.permscraft.gui;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.logging.LogEntry;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;

public class LogViewerGui extends BaseGui {

    private int page;
    private static final int PAGE_SIZE = 36;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public LogViewerGui(PermsCraft plugin, GuiManager guiManager, Player viewer, int page) {
        super(plugin, guiManager, viewer);
        this.page = page;
    }

    @Override
    public void open(Player player) {
        int limit = (page + 1) * PAGE_SIZE + 1;
        // Load log entries async to avoid blocking the main thread (Bug 14 fix)
        FoliaScheduler.runAsync(plugin, () -> {
            List<LogEntry> entries = plugin.getLogManager().getRecent(limit);
            FoliaScheduler.runSync(plugin, () -> renderPage(player, entries));
        });
    }

    private void renderPage(Player player, List<LogEntry> entries) {
        int start = page * PAGE_SIZE;
        List<LogEntry> pageEntries = entries.subList(
                Math.min(start, entries.size()),
                Math.min(start + PAGE_SIZE, entries.size()));

        inventory = createInv(6, "&1&l▐ &dLog Viewer &8(Page &f" + (page + 1) + "&8) &1&l▐");
        fillRow(inventory, 5);

        int slot = 0;
        for (LogEntry e : pageEntries) {
            if (slot >= 45) break;

            // Color-coded by action type
            Material mat = switch (e.getAction()) {
                case USER_PERM_ADD,   GROUP_PERM_ADD    -> Material.LIME_DYE;
                case USER_PERM_REMOVE, GROUP_PERM_REMOVE -> Material.RED_DYE;
                case USER_GROUP_ADD,  GROUP_PARENT_ADD  -> Material.CYAN_DYE;
                case USER_GROUP_REMOVE, GROUP_PARENT_REMOVE -> Material.ORANGE_DYE;
                case GROUP_DELETE                         -> Material.BARRIER;
                default                                  -> Material.PAPER;
            };

            // Color-coded action name
            String actionColor = switch (e.getAction()) {
                case USER_PERM_ADD,   GROUP_PERM_ADD    -> "&a";
                case USER_PERM_REMOVE, GROUP_PERM_REMOVE -> "&c";
                case USER_GROUP_ADD,  GROUP_PARENT_ADD  -> "&b";
                case USER_GROUP_REMOVE, GROUP_PARENT_REMOVE -> "&6";
                case GROUP_DELETE                         -> "&4";
                default                                  -> "&7";
            };

            String action = e.getAction().name().toLowerCase().replace("_", " ");

            inventory.setItem(slot++, item(mat,
                    "&f" + e.getActor() + " &8→ &7" + e.getTarget(),
                    "&7Action: " + actionColor + action,
                    "&7Detail: &f" + e.getDetail(),
                    "&8" + FMT.format(e.getTimestamp())));
        }

        if (pageEntries.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER,
                    "&7No log entries",
                    "&7No actions have been recorded yet"));
        }

        // Navigation row (row 6)
        inventory.setItem(45, backButton());
        if (page > 0) inventory.setItem(47, prevPage());
        if (entries.size() > start + PAGE_SIZE) inventory.setItem(51, nextPage());

        inventory.setItem(49, item(Material.NETHER_STAR,
                "&7Page &f" + (page + 1),
                "&7Showing &a" + pageEntries.size() + " &7entries",
                "",
                "&aGreen &8▸ &7perm added",
                "&cRed &8▸ &7perm removed",
                "&bCyan &8▸ &7group added",
                "&6Orange &8▸ &7group removed",
                "&4Dark Red &8▸ &7group deleted"));

        // Clear log button
        inventory.setItem(53, item(Material.LAVA_BUCKET,
                "&c&lClear Log",
                "&7Click to wipe all log entries",
                "&8(cannot be undone)"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 45) { guiManager.openMain(player); return; }
        if (slot == 47 && page > 0) { page--; open(player); return; }
        if (slot == 51) { page++; open(player); return; }

        if (slot == 53) {
            guiManager.openConfirm(player,
                    "Clear all logs?",
                    "&cThis will permanently delete all log entries!",
                    () -> {
                        plugin.getLogManager().clearAll();
                        player.sendMessage(MessageUtil.colorizeString("&c[PermsCraft] Log cleared."));
                        guiManager.openLogViewer(player, 0);
                    },
                    () -> guiManager.openLogViewer(player, page));
        }
    }
}
