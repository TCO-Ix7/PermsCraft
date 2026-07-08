package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.models.User;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UserGroupsGui extends BaseGui {

    private final UUID targetUUID;
    private final String targetName;

    public UserGroupsGui(PermsCraft plugin, GuiManager guiManager, Player viewer, UUID targetUUID, String targetName) {
        super(plugin, guiManager, viewer);
        this.targetUUID = targetUUID;
        this.targetName = targetName;
    }

    @Override
    public void open(Player player) {
        User user = plugin.getUserManager().getUser(targetUUID);
        if (user == null) user = plugin.getStorage().loadUser(targetUUID, targetName);

        List<Group> allGroups = new ArrayList<>(plugin.getGroupManager().getAllGroups());
        allGroups.sort((a, b) -> Integer.compare(b.getWeight(), a.getWeight()));

        int rows = Math.max(3, (int) Math.ceil(allGroups.size() / 7.0) + 2);
        rows = Math.min(rows, 6);

        final User finalUser = user;
        String primaryGroup = finalUser != null ? finalUser.getPrimaryGroup() : "default";

        inventory = createInv(rows, "&1&l▐ &aGroups&8: &f" + targetName + " &1&l▐");
        fillBorder(inventory);

        // Header: show primary group info
        inventory.setItem(4, skull(targetUUID,
                "&f&l" + targetName,
                "&7Primary Group: &b★ " + primaryGroup,
                "&7Total Groups: &a" + (finalUser != null ? finalUser.getGroups().size() : 0),
                "",
                "&eLeft-click &8▸ &7toggle group membership",
                "&bMiddle-click &8▸ &7set as primary group"));

        int slot = 10;
        for (Group g : allGroups) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= inventory.getSize() - 9) break;

            boolean inGroup  = finalUser != null && finalUser.inGroup(g.getName());
            boolean isPrimary = g.getName().equals(primaryGroup);

            // Primary group gets star + special material
            Material mat = isPrimary ? Material.GOLD_BLOCK
                         : inGroup   ? Material.LIME_STAINED_GLASS_PANE
                                     : Material.RED_STAINED_GLASS_PANE;

            String nameLine = isPrimary ? "&6★ " + g.getName() + " &8(primary)"
                            : (inGroup   ? "&a✔ " : "&c✘ ") + g.getName();

            inventory.setItem(slot, item(mat,
                    nameLine,
                    "&7Weight: &f" + g.getWeight(),
                    "&7Prefix: &r" + MessageUtil.colorizeString(g.getPrefix()),
                    "&7Permissions: &a" + g.getPermissions().size(),
                    "",
                    inGroup ? "&cLeft-click &8▸ &7remove from group" : "&aLeft-click &8▸ &7add to group",
                    inGroup && !isPrimary ? "&bMiddle-click &8▸ &7set as primary" : ""));
            slot++;
        }

        inventory.setItem(inventory.getSize() - 9, backButton());
        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == inventory.getSize() - 9) {
            guiManager.openUserEditor(player, targetUUID, targetName);
            return;
        }

        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;

        String raw2 = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (raw2 == null || raw2.isBlank()) return;

        // Strip star/checkmark prefix and "(primary)" suffix
        String rawName = raw2.replace("★ ", "").replace("✔ ", "").replace("✘ ", "")
                            .replace("★", "").replace("✔", "").replace("✘", "")
                            .replaceAll("\\s*\\(primary\\)$", "").trim();
        if (rawName.isBlank()) return;

        User user = plugin.getUserManager().getUser(targetUUID);
        if (user == null) user = plugin.getStorage().loadUser(targetUUID, targetName);

        // Middle-click: set primary group
        if (click == ClickType.MIDDLE) {
            if (!user.inGroup(rawName)) {
                player.sendMessage(MessageUtil.colorizeString("&cPlayer is not in group &e" + rawName));
                return;
            }
            plugin.getUserManager().switchPrimaryGroup(targetUUID, rawName);
            player.sendMessage(MessageUtil.colorizeString("&b★ Set primary group of &f" + targetName + " &bto &e" + rawName));
            if (plugin.getRedisManager().isEnabled())
                plugin.getRedisManager().publishUserRefresh(targetUUID);
            open(player);
            return;
        }

        // Left-click: toggle membership
        if (user.inGroup(rawName)) {
            plugin.getUserManager().removeFromGroup(targetUUID, rawName);
            plugin.getLogManager().log(player, ir.permscraft.logging.LogEntry.Action.USER_GROUP_REMOVE, targetName, rawName);
            player.sendMessage(MessageUtil.colorizeString("&cRemoved &b" + targetName + " &cfrom group &e" + rawName));
        } else {
            plugin.getUserManager().addToGroup(targetUUID, rawName);
            plugin.getLogManager().log(player, ir.permscraft.logging.LogEntry.Action.USER_GROUP_ADD, targetName, rawName);
            player.sendMessage(MessageUtil.colorizeString("&aAdded &b" + targetName + " &ato group &e" + rawName));
        }

        if (plugin.getRedisManager().isEnabled())
            plugin.getRedisManager().publishUserRefresh(targetUUID);

        open(player);
    }
}
