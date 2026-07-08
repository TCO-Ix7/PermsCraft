package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class GroupPermissionsGui extends BaseGui {

    private final String groupName;
    private int page;
    private String filter; // null = no filter
    private static final int PAGE_SIZE = 28;

    public GroupPermissionsGui(PermsCraft plugin, GuiManager guiManager, Player viewer, String groupName, int page) {
        super(plugin, guiManager, viewer);
        this.groupName = groupName;
        this.page      = page;
        this.filter    = null;
    }

    public GroupPermissionsGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                               String groupName, int page, String filter) {
        super(plugin, guiManager, viewer);
        this.groupName = groupName;
        this.page      = page;
        this.filter    = filter;
    }

    @Override
    public void open(Player player) {
        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) { guiManager.openGroupList(player); return; }

        List<String> allPerms = new ArrayList<>(g.getPermissions());
        allPerms.sort(String::compareTo);

        // Apply filter
        List<String> perms = filter == null || filter.isBlank()
                ? allPerms
                : allPerms.stream()
                    .filter(p -> p.toLowerCase().contains(filter.toLowerCase()))
                    .collect(Collectors.toList());

        inventory = createInv(6, "&1&l▐ &bPermissions&8: &f" + groupName + " &8(&f" + perms.size() + "&8) &1&l▐");
        fillBorder(inventory);
        fillRow(inventory, 4);

        // Header info
        inventory.setItem(4, item(Material.SHIELD,
                "&b&l" + groupName,
                "&7Total permissions: &a" + allPerms.size(),
                filter != null ? "&7Filter: &e" + filter + " &8(&f" + perms.size() + " &7matches)" : "&7Use search to filter",
                "",
                "&8▸ &aGreen &7= granted",
                "&8▸ &cRed &7= denied"));

        int start = page * PAGE_SIZE;
        int slot   = 10;
        int count  = 0;

        for (int i = start; i < perms.size() && count < PAGE_SIZE; i++) {
            if (slot % 9 == 8) slot += 2;
            String perm = perms.get(i);
            boolean negated = perm.startsWith("-");
            Material mat = negated ? Material.RED_DYE : Material.LIME_DYE;
            String status = negated ? "&c✘ Denied" : "&a✔ Granted";
            String displayPerm = negated ? "&c" + perm : "&a" + perm;

            inventory.setItem(slot, glowItem(mat,
                    displayPerm,
                    "&7Status: " + status,
                    "",
                    "&eLeft-click &8▸ &7toggle grant/deny",
                    "&cRight-click &8▸ &7remove"));
            slot++;
            count++;
        }

        // Nav row
        inventory.setItem(36, backButton());
        if (page > 0) inventory.setItem(38, prevPage());
        if (start + PAGE_SIZE < perms.size()) inventory.setItem(42, nextPage());

        // Search/filter button
        inventory.setItem(37, item(Material.COMPASS,
                filter != null ? "&e&l⌕ Filter: &f" + filter : "&7&l⌕ Search / Filter",
                "&7Click to filter permissions by keyword",
                filter != null ? "" : "",
                filter != null ? "&cRight-click to clear filter" : "&eClick to set filter"));

        inventory.setItem(40, item(Material.NETHER_STAR,
                "&7Page &f" + (page + 1),
                "&7Showing &a" + Math.min(PAGE_SIZE, perms.size() - start) + " &7/ &f" + perms.size()));

        inventory.setItem(39, item(Material.LIME_DYE,
                "&a&l✚ Grant Permission",
                "&7Click to add a grant node",
                "&7Tip: prefix with &c- &7to deny"));

        inventory.setItem(41, item(Material.RED_DYE,
                "&c&l✘ Deny Permission",
                "&7Click to add a deny node",
                "&7Will override group grants"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 36) { guiManager.openGroupEditor(player, groupName); return; }
        if (slot == 38 && page > 0) { page--; open(player); return; }
        if (slot == 42) { page++; open(player); return; }

        // Search/filter
        if (slot == 37) {
            if (click == ClickType.RIGHT && filter != null) {
                filter = null;
                page   = 0;
                open(player);
                return;
            }
            guiManager.awaitInput(player, "Enter filter keyword (or 'clear' to reset):", input -> {
                if (input.equalsIgnoreCase("clear")) {
                    filter = null;
                } else {
                    filter = input.toLowerCase();
                }
                page = 0;
                guiManager.openGui(player, new GroupPermissionsGui(plugin, guiManager, player, groupName, 0, filter));
            });
            return;
        }

        if (slot == 39) {
            // Open PermissionBrowserGui — player picks from server permission list
            guiManager.openGui(player, new PermissionBrowserGui(plugin, guiManager, player,
                    groupName, false, node -> {
                        plugin.getGroupManager().addPermission(groupName, node);
                        plugin.getLogManager().log(player,
                                ir.permscraft.logging.LogEntry.Action.GROUP_PERM_ADD, groupName, node);
                        refreshOnlinePlayers();
                        player.sendMessage(MessageUtil.colorizeString(
                                "&aGranted &e" + node + " &ato group &b" + groupName));
                        guiManager.openGroupPermissions(player, groupName, page);
                    }));
            return;
        }

        if (slot == 41) {
            // Open PermissionBrowserGui in deny mode
            guiManager.openGui(player, new PermissionBrowserGui(plugin, guiManager, player,
                    groupName, true, node -> {
                        plugin.getGroupManager().addPermission(groupName, node);
                        plugin.getLogManager().log(player,
                                ir.permscraft.logging.LogEntry.Action.GROUP_PERM_ADD, groupName, node);
                        refreshOnlinePlayers();
                        player.sendMessage(MessageUtil.colorizeString(
                                "&cDenied &e" + node.replace("-","") + " &cfor group &b" + groupName));
                        guiManager.openGroupPermissions(player, groupName, page);
                    }));
            return;
        }

        // Toggle or remove clicked permission
        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;
        String rawName = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (rawName == null || rawName.isBlank()) return;

        if (click == ClickType.RIGHT) {
            // Remove
            plugin.getGroupManager().removePermission(groupName, rawName);
            plugin.getLogManager().log(player, ir.permscraft.logging.LogEntry.Action.GROUP_PERM_REMOVE, groupName, rawName);
            refreshOnlinePlayers();
            player.sendMessage(MessageUtil.colorizeString("&cRemoved &e" + rawName + " &cfrom group &b" + groupName));
        } else {
            // Toggle grant ↔ deny
            plugin.getGroupManager().removePermission(groupName, rawName);
            String toggled = rawName.startsWith("-") ? rawName.substring(1) : "-" + rawName;
            plugin.getGroupManager().addPermission(groupName, toggled);
            plugin.getLogManager().log(player, ir.permscraft.logging.LogEntry.Action.GROUP_PERM_ADD, groupName, toggled);
            refreshOnlinePlayers();
            player.sendMessage(MessageUtil.colorizeString("&eToggled &f" + rawName + " &e→ &f" + toggled));
        }
        open(player);
    }

    private void refreshOnlinePlayers() {
        plugin.getPermissionCache().invalidateGroup(groupName);
        org.bukkit.Bukkit.getOnlinePlayers().forEach(p ->
                plugin.getUserManager().refreshPermissions(p.getUniqueId()));
        if (plugin.getRedisManager().isEnabled())
            plugin.getRedisManager().publishGroupRefresh(groupName);
    }
}
