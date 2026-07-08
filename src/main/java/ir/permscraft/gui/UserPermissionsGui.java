package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.logging.LogEntry;
import ir.permscraft.models.User;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class UserPermissionsGui extends BaseGui {

    private final UUID targetUUID;
    private final String targetName;
    private int page;
    private String filter;
    private static final int PAGE_SIZE = 28;

    public UserPermissionsGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                              UUID targetUUID, String targetName, int page) {
        super(plugin, guiManager, viewer);
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        this.page       = page;
        this.filter     = null;
    }

    public UserPermissionsGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                              UUID targetUUID, String targetName, int page, String filter) {
        super(plugin, guiManager, viewer);
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        this.page       = page;
        this.filter     = filter;
    }

    @Override
    public void open(Player player) {
        User user = getUser();
        List<String> allPerms = new ArrayList<>(user.getPermissions());
        allPerms.sort(String::compareTo);

        List<String> perms = filter == null || filter.isBlank()
                ? allPerms
                : allPerms.stream()
                    .filter(p -> p.toLowerCase().contains(filter.toLowerCase()))
                    .collect(Collectors.toList());

        inventory = createInv(6, "&1&l▐ &aPermissions&8: &f" + targetName + " &8(&f" + perms.size() + "&8) &1&l▐");
        fillBorder(inventory);
        fillRow(inventory, 4);

        inventory.setItem(4, skull(targetUUID,
                "&f&l" + targetName,
                "&7Personal permissions: &a" + allPerms.size(),
                filter != null ? "&7Filter: &e" + filter + " &8(&f" + perms.size() + " &7matches)" : "",
                "",
                "&eLeft-click &8▸ &7toggle grant/deny",
                "&cRight-click &8▸ &7remove"));

        int start = page * PAGE_SIZE;
        int slot  = 10;
        int count = 0;

        for (int i = start; i < perms.size() && count < PAGE_SIZE; i++) {
            if (slot % 9 == 8) slot += 2;
            String perm    = perms.get(i);
            boolean negated = perm.startsWith("-");
            Material mat   = negated ? Material.RED_DYE : Material.LIME_DYE;
            String status  = negated ? "&c✘ Denied" : "&a✔ Granted";

            inventory.setItem(slot, glowItem(mat,
                    (negated ? "&c" : "&a") + perm,
                    "&7Status: " + status,
                    "&7Source: &ePersonal",
                    "",
                    "&eLeft-click &8▸ &7toggle grant/deny",
                    "&cRight-click &8▸ &7remove"));
            slot++;
            count++;
        }

        // Navigation row (row 5, slots 36-44)
        inventory.setItem(36, backButton());
        if (page > 0) inventory.setItem(38, prevPage());
        if (start + PAGE_SIZE < perms.size()) inventory.setItem(42, nextPage());

        // Search
        inventory.setItem(37, item(Material.COMPASS,
                filter != null ? "&e&l⌕ Filter: &f" + filter : "&7&l⌕ Search / Filter",
                "&7Click to filter by keyword",
                filter != null ? "&cRight-click to clear filter" : ""));

        inventory.setItem(40, item(Material.NETHER_STAR,
                "&7Page &f" + (page + 1),
                "&7Showing &a" + Math.min(PAGE_SIZE, perms.size() - start) + " &7/ &f" + perms.size()));

        inventory.setItem(38, item(Material.LIME_DYE,
                "&a&l✚ Grant Permission",
                "&7Left-click &8▸ &7type node manually",
                "&7Right-click &8▸ &7browse registered permissions",
                "&7Tip: prefix with &c- &7to deny"));
        inventory.setItem(39, glowItem(Material.NETHER_STAR,
                "&a&l⊞ Browse & Grant",
                "&7Opens the Permission Browser",
                "&7Select any registered node to grant",
                "",
                "&eClick to open browser"));
        inventory.setItem(41, glowItem(Material.COMPARATOR,
                "&c&l⊟ Browse & Deny",
                "&7Opens the Permission Browser",
                "&7Select any registered node to deny",
                "",
                "&eClick to open browser"));
        inventory.setItem(42, item(Material.RED_DYE,
                "&c&l✘ Deny Permission",
                "&7Left-click &8▸ &7type node manually",
                "&7Right-click &8▸ &7browse registered permissions"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 36) { guiManager.openUserEditor(player, targetUUID, targetName); return; }
        if (slot == 38 && page > 0) { page--; open(player); return; }
        if (slot == 42) { page++; open(player); return; }

        if (slot == 37) {
            if (click == ClickType.RIGHT && filter != null) {
                filter = null;
                page   = 0;
                open(player);
                return;
            }
            guiManager.awaitInput(player, "Enter filter keyword (or 'clear' to reset):", input -> {
                filter = input.equalsIgnoreCase("clear") ? null : input.toLowerCase();
                page   = 0;
                guiManager.openGui(player, new UserPermissionsGui(plugin, guiManager, player,
                        targetUUID, targetName, 0, filter));
            });
            return;
        }

        // Manual grant (left-click) or browse grant (right-click)
        if (slot == 38) {
            if (click == ClickType.RIGHT) {
                // Browse registered permissions → grant
                guiManager.openUserPermissionBrowser(player, targetUUID, targetName, false);
            } else {
                guiManager.awaitInput(player, "Enter permission node to GRANT (e.g. essentials.home):", perm -> {
                    String node = perm.toLowerCase();
                    plugin.getUserManager().addPermission(targetUUID, node);
                    plugin.getLogManager().log(player, LogEntry.Action.USER_PERM_ADD, targetName, node);
                    player.sendMessage(MessageUtil.colorizeString("&aGranted &e" + node + " &ato &b" + targetName));
                    guiManager.openUserPermissions(player, targetUUID, targetName, page);
                });
            }
            return;
        }

        // Browse & grant
        if (slot == 39) {
            guiManager.openUserPermissionBrowser(player, targetUUID, targetName, false);
            return;
        }

        // Browse & deny
        if (slot == 41) {
            guiManager.openUserPermissionBrowser(player, targetUUID, targetName, true);
            return;
        }

        // Manual deny (left-click) or browse deny (right-click)
        if (slot == 42) {
            if (click == ClickType.RIGHT) {
                guiManager.openUserPermissionBrowser(player, targetUUID, targetName, true);
            } else {
                guiManager.awaitInput(player, "Enter permission node to DENY:", perm -> {
                    String node = perm.toLowerCase().startsWith("-") ? perm.toLowerCase() : "-" + perm.toLowerCase();
                    plugin.getUserManager().addPermission(targetUUID, node);
                    plugin.getLogManager().log(player, LogEntry.Action.USER_PERM_ADD, targetName, node);
                    player.sendMessage(MessageUtil.colorizeString("&cDenied &e" + perm + " &cfor &b" + targetName));
                    guiManager.openUserPermissions(player, targetUUID, targetName, page);
                });
            }
            return;
        }

        // Clicked a permission item
        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;
        String raw = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (raw == null || raw.isBlank()) return;

        if (click == ClickType.RIGHT) {
            plugin.getUserManager().removePermission(targetUUID, raw);
            plugin.getLogManager().log(player, LogEntry.Action.USER_PERM_REMOVE, targetName, raw);
            player.sendMessage(MessageUtil.colorizeString("&cRemoved &e" + raw + " &cfrom &b" + targetName));
        } else {
            // Toggle granted ↔ denied
            plugin.getUserManager().removePermission(targetUUID, raw);
            String toggled = raw.startsWith("-") ? raw.substring(1) : "-" + raw;
            plugin.getUserManager().addPermission(targetUUID, toggled);
            plugin.getLogManager().log(player, LogEntry.Action.USER_PERM_ADD, targetName, toggled);
            player.sendMessage(MessageUtil.colorizeString("&eToggled &f" + raw + " &e→ &f" + toggled));
        }
        open(player);
    }

    private User getUser() {
        User u = plugin.getUserManager().getUser(targetUUID);
        return u != null ? u : plugin.getStorage().loadUser(targetUUID, targetName);
    }
}
