package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.User;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.UUID;

public class UserEditorGui extends BaseGui {

    private final UUID targetUUID;
    private final String targetName;

    public UserEditorGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                         UUID targetUUID, String targetName) {
        super(plugin, guiManager, viewer);
        this.targetUUID = targetUUID;
        this.targetName = targetName;
    }

    @Override
    public void open(Player player) {
        User user = getUser();
        var timedList = plugin.getTimedPermissionManager().getTimedPermissions(targetUUID.toString());
        long activeTimedCount = timedList.stream().filter(t -> !t.isExpired()).count();

        inventory = createInv(4, "&8User: &a" + targetName);
        fillBorder(inventory);

        inventory.setItem(4, skull(targetUUID,
                "&a&l" + targetName,
                "&7UUID: &8" + targetUUID.toString().substring(0, 8) + "...",
                "&7Primary Group: &e" + user.getPrimaryGroup(),
                "&7Groups: &a" + String.join("&7, &a", user.getGroups()),
                "&7Personal Perms: &e" + user.getPermissions().size(),
                "&7Active Timed: &6" + activeTimedCount,
                "&7Meta: &7" + user.getMeta().size() + " entries",
                "&7Prefix: &r" + MessageUtil.colorizeString(user.getPrefix()),
                "&7Suffix: &r" + MessageUtil.colorizeString(user.getSuffix())));

        inventory.setItem(10, item(Material.SHIELD,
                "&b&lGroups",
                "&7Current: &a" + String.join("&7, &a", user.getGroups()),
                "&7Count: &e" + user.getGroups().size(),
                "", "&eClick to manage"));

        inventory.setItem(12, item(Material.PAPER,
                "&a&lPermissions",
                "&7Personal override nodes",
                "&7Count: &e" + user.getPermissions().size(),
                "", "&eClick to manage"));

        inventory.setItem(14, item(Material.NAME_TAG,
                "&b&lSet Prefix",
                "&7Current: &r" + MessageUtil.colorizeString(user.getPrefix()),
                "", "&eLeft-click to change", "&cRight-click to clear"));

        inventory.setItem(16, item(Material.NAME_TAG,
                "&d&lSet Suffix",
                "&7Current: &r" + MessageUtil.colorizeString(user.getSuffix()),
                "", "&eLeft-click to change", "&cRight-click to clear"));

        inventory.setItem(19, item(Material.EXPERIENCE_BOTTLE,
                "&6&lPromote / Demote",
                "&aLeft-click &7to promote on a track",
                "&cRight-click &7to demote on a track",
                "", "&7Tracks: &f" + plugin.getTrackManager().getAllTracks().size()));

        inventory.setItem(21, item(Material.CLOCK,
                "&e&lTimed Permissions",
                "&7Active: &a" + activeTimedCount,
                "&7Total: &f" + timedList.size(),
                "", "&eClick to manage"));

        inventory.setItem(23, item(Material.CHEST,
                "&7&lMeta",
                "&7Key-value metadata",
                "&7Entries: &f" + user.getMeta().size(),
                "", "&eClick to manage"));

        inventory.setItem(25, item(Material.GLOBE_BANNER_PATTERN,
                "&3&lContext Permissions",
                "&7World-specific permissions",
                "", "&eClick to manage"));

        inventory.setItem(27, backButton());
        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        switch (slot) {
            case 27 -> guiManager.openUserSearch(player);
            case 10 -> guiManager.openUserGroups(player, targetUUID, targetName);
            case 12 -> guiManager.openUserPermissions(player, targetUUID, targetName, 0);

            case 14 -> {
                if (click == ClickType.RIGHT) {
                    plugin.getUserManager().setPrefix(targetUUID, "");
                    player.sendMessage(MessageUtil.colorizeString("&aCleared prefix for &b" + targetName));
                    open(player);
                } else {
                    guiManager.awaitInput(player, "Enter prefix for &b" + targetName + " &e(use & for colors):", prefix -> {
                        plugin.getUserManager().setPrefix(targetUUID, prefix);
                        player.sendMessage(MessageUtil.colorizeString("&aSet prefix: &r" + MessageUtil.colorizeString(prefix)));
                        guiManager.openUserEditor(player, targetUUID, targetName);
                    });
                }
            }

            case 16 -> {
                if (click == ClickType.RIGHT) {
                    plugin.getUserManager().setSuffix(targetUUID, "");
                    player.sendMessage(MessageUtil.colorizeString("&aCleared suffix for &b" + targetName));
                    open(player);
                } else {
                    guiManager.awaitInput(player, "Enter suffix for &b" + targetName + " &e(use & for colors):", suffix -> {
                        plugin.getUserManager().setSuffix(targetUUID, suffix);
                        player.sendMessage(MessageUtil.colorizeString("&aSet suffix: &r" + MessageUtil.colorizeString(suffix)));
                        guiManager.openUserEditor(player, targetUUID, targetName);
                    });
                }
            }

            case 19 -> {
                var tracks = plugin.getTrackManager().getAllTracks();
                if (tracks.isEmpty()) {
                    player.sendMessage(MessageUtil.colorizeString("&cNo tracks defined."));
                    return;
                }
                String trackList = "[" + tracks.stream().map(t -> t.getName())
                        .reduce((a, b) -> a + ", " + b).orElse("") + "]";
                final boolean doPromote = (click != ClickType.RIGHT);
                guiManager.awaitInput(player, "Enter track name " + trackList + ":", trackName -> {
                    if (doPromote) {
                        String next = plugin.getTrackManager().promote(targetUUID, trackName);
                        player.sendMessage(next == null
                                ? MessageUtil.colorizeString("&eAlready at top of track &b" + trackName)
                                : MessageUtil.colorizeString("&aPromoted &b" + targetName + " &ato &e" + next));
                    } else {
                        String prev = plugin.getTrackManager().demote(targetUUID, trackName);
                        player.sendMessage(prev == null
                                ? MessageUtil.colorizeString("&cCannot demote further on &b" + trackName)
                                : MessageUtil.colorizeString("&cDemoted &b" + targetName + " &cto &e" + prev));
                    }
                    guiManager.openUserEditor(player, targetUUID, targetName);
                });
            }

            case 21 -> guiManager.openUserTimedPermissions(player, targetUUID, targetName);
            case 23 -> guiManager.openUserMeta(player, targetUUID, targetName);
            case 25 -> guiManager.openUserContextPerms(player, targetUUID, targetName);
        }
    }

    private User getUser() {
        User u = plugin.getUserManager().getUser(targetUUID);
        return u != null ? u : plugin.getStorage().loadUser(targetUUID, targetName);
    }
}
