package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.managers.TimedPermissionManager;
import ir.permscraft.models.TimedPermission;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class UserTimedPermissionsGui extends BaseGui {

    private final UUID targetUUID;
    private final String targetName;

    public UserTimedPermissionsGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                                   UUID targetUUID, String targetName) {
        super(plugin, guiManager, viewer);
        this.targetUUID  = targetUUID;
        this.targetName  = targetName;
    }

    @Override
    public void open(Player player) {
        List<TimedPermission> all     = plugin.getTimedPermissionManager().getTimedPermissions(targetUUID.toString());
        List<TimedPermission> active  = all.stream().filter(t -> !t.isExpired()).collect(Collectors.toList());
        List<TimedPermission> expired = all.stream().filter(TimedPermission::isExpired).collect(Collectors.toList());

        inventory = createInv(6, "&1&l▐ &eTimed Permissions&8: &f" + targetName + " &1&l▐");
        fillBorder(inventory);
        fillRow(inventory, 2);
        fillRow(inventory, 4);

        // Header
        inventory.setItem(4, skull(targetUUID,
                "&f&l" + targetName,
                "&7Active timed: &a" + active.size(),
                "&7Expired: &c" + expired.size(),
                "",
                "&7Duration: &b1s &7| &b1m &7| &b1h &7| &b1d &7| &b1w &7| &b1mo &7| &b1y",
                "&8▸ &7Click a permission to remove"));

        // Active section label
        inventory.setItem(9, item(Material.LIME_DYE,
                "&a&lActive Timed Permissions &8(&a" + active.size() + "&8)", ""));

        // Active permissions (row 1, slots 10-16)
        int slot = 10;
        for (TimedPermission tp : active) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 18) break;

            long remaining = tp.getExpiry().getEpochSecond() - Instant.now().getEpochSecond();
            String timeColor = remaining > 86400 ? "&a" : remaining > 3600 ? "&e" : "&c";

            inventory.setItem(slot, glowItem(Material.CLOCK,
                    "&e" + tp.getPermission(),
                    "&7Expires in: " + timeColor + tp.getFormattedExpiry(),
                    "&7Type: &fTimed",
                    "",
                    "&cClick to remove immediately"));
            slot++;
        }

        if (active.isEmpty()) {
            inventory.setItem(13, item(Material.BARRIER,
                    "&7No active timed permissions",
                    "&7Add one using the button below"));
        }

        // Expired section label — row 3
        inventory.setItem(27, item(Material.RED_DYE,
                "&c&lExpired Permissions &8(&c" + expired.size() + "&8)", ""));

        // Expired permissions (row 3, slots 28-34)
        int expSlot = 28;
        for (TimedPermission tp : expired) {
            if (expSlot % 9 == 8) expSlot += 2;
            if (expSlot >= 36) break;

            inventory.setItem(expSlot, item(Material.PAPER,
                    "&8✘ &7" + tp.getPermission(),
                    "&7Status: &cExpired",
                    "",
                    "&cClick to remove record"));
            expSlot++;
        }

        // Controls (row 5)
        inventory.setItem(45, backButton());
        inventory.setItem(47, item(Material.LIME_DYE,
                "&a&l✚ Add Timed Permission",
                "&7Type the node manually",
                "&7Format: &epermission &bduration",
                "&7Units: &es m h d w mo y  &8| compound: &b1d10h",
                "&8• &b1d10h &8| &b2w3d &8| &b1mo &8| &b1y6mo",
                "",
                "",
                "&eClick to type manually"));

        inventory.setItem(49, glowItem(Material.NETHER_STAR,
                "&b&l⊞ Browse & Add Timed",
                "&7Opens the Permission Browser",
                "&7Pick a node, then enter duration",
                "",
                "&7You can browse all registered",
                "&7permissions and pick one to",
                "&7add as a timed permission",
                "",
                "&eClick to open browser"));

        inventory.setItem(51, item(Material.CLOCK,
                "&e&lActive: &a" + active.size(),
                "&7Timed permissions currently active",
                "&7They expire and remove automatically"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 45) { guiManager.openUserEditor(player, targetUUID, targetName); return; }

        // Manual type
        if (slot == 47) {
            guiManager.awaitInput(player,
                    "Enter: &epermission duration &7(e.g. essentials.fly 1d30m):",
                    input -> {
                        String[] parts = input.trim().split("\\s+", 2);
                        if (parts.length < 2) {
                            player.sendMessage(MessageUtil.colorizeString(
                                    "&cFormat: &epermission &aduration &c(e.g. essentials.fly 1d30m)"));
                            guiManager.openUserTimedPermissions(player, targetUUID, targetName);
                            return;
                        }
                        long secs = TimedPermissionManager.parseDuration(parts[1]);
                        if (secs <= 0) {
                            player.sendMessage(MessageUtil.colorizeString(
                                    "&cInvalid duration. " + ir.permscraft.utils.DurationParser.HINT));
                            guiManager.openUserTimedPermissions(player, targetUUID, targetName);
                            return;
                        }
                        plugin.getTimedPermissionManager().addTimedPermission(
                                targetUUID.toString(), false, parts[0].toLowerCase(), secs);
                        plugin.getLogManager().log(player,
                                ir.permscraft.logging.LogEntry.Action.USER_PERM_ADD,
                                targetName, parts[0] + " (timed " + parts[1] + ")");
                        player.sendMessage(MessageUtil.colorizeString(
                                "&aAdded timed permission &e" + parts[0]
                                        + " &afor &e" + parts[1] + " &ato &b" + targetName));
                        guiManager.openUserTimedPermissions(player, targetUUID, targetName);
                    });
            return;
        }

        // Browse & Add Timed — open permission browser, then ask for duration
        if (slot == 49) {
            guiManager.openUserTimedPermissionBrowser(player, targetUUID, targetName);
            return;
        }

        // Clicked a timed permission item
        var itm = inventory.getItem(slot);
        if (itm == null || itm.getItemMeta() == null) return;
        if (itm.getType() != Material.CLOCK && itm.getType() != Material.PAPER) return;

        String raw = org.bukkit.ChatColor.stripColor(itm.getItemMeta().getDisplayName());
        if (raw == null || raw.isBlank()) return;

        // Strip "✘ " prefix if from expired section
        String permNode = raw.replace("✘ ", "").replace("✘", "").trim();
        if (permNode.isBlank()) return;

        guiManager.openConfirm(player,
                "Remove timed permission?",
                "&7Permission: &e" + permNode,
                () -> {
                    plugin.getUserManager().removePermission(targetUUID, permNode);
                    plugin.getTimedPermissionManager().removeTimedPermission(targetUUID.toString(), permNode);
                    player.sendMessage(MessageUtil.colorizeString("&cRemoved timed permission &e" + permNode));
                    guiManager.openUserTimedPermissions(player, targetUUID, targetName);
                },
                () -> guiManager.openUserTimedPermissions(player, targetUUID, targetName));
    }
}
