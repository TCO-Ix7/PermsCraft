package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

public class GroupEditorGui extends BaseGui {

    private final String groupName;

    public GroupEditorGui(PermsCraft plugin, GuiManager guiManager, Player viewer, String groupName) {
        super(plugin, guiManager, viewer);
        this.groupName = groupName;
    }

    @Override
    public void open(Player player) {
        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) { guiManager.openGroupList(player); return; }

        inventory = createInv(4, "&8Group: &b" + groupName);
        fillBorder(inventory);

        inventory.setItem(4, glowItem(Material.SHIELD,
                "&b&l" + g.getName(),
                "&7Weight: &f" + g.getWeight(),
                "&7Prefix: &r" + MessageUtil.colorizeString(g.getPrefix()),
                "&7Suffix: &r" + MessageUtil.colorizeString(g.getSuffix()),
                "&7Permissions: &a" + g.getPermissions().size(),
                "&7Parents: &e" + (g.getInheritedGroups().isEmpty() ? "none" : String.join(", ", g.getInheritedGroups())),
                "&7Meta: &7" + g.getMeta().size() + " entries"));

        inventory.setItem(10, item(Material.PAPER,
                "&a&lPermissions",
                "&7Manage permission nodes",
                "&7Granted: &a" + g.getPermissions().stream().filter(p -> !p.startsWith("-")).count(),
                "&7Denied: &c" + g.getPermissions().stream().filter(p -> p.startsWith("-")).count(),
                "", "&eClick to manage"));

        inventory.setItem(12, item(Material.BOOKSHELF,
                "&6&lInheritance",
                "&7Manage parent groups",
                "&7Parents: &e" + (g.getInheritedGroups().isEmpty() ? "none" : String.join(", ", g.getInheritedGroups())),
                "", "&eClick to manage"));

        inventory.setItem(14, item(Material.NAME_TAG,
                "&b&lSet Prefix",
                "&7Current: &r" + MessageUtil.colorizeString(g.getPrefix()),
                "", "&eClick to change", "&cRight-click to clear"));

        inventory.setItem(16, item(Material.NAME_TAG,
                "&d&lSet Suffix",
                "&7Current: &r" + MessageUtil.colorizeString(g.getSuffix()),
                "", "&eClick to change", "&cRight-click to clear"));

        inventory.setItem(19, item(Material.COMPARATOR,
                "&e&lSet Weight",
                "&7Current: &f" + g.getWeight(),
                "&7Higher weight = higher priority",
                "", "&eClick to change"));

        inventory.setItem(21, item(Material.OAK_SIGN,
                "&f&lDisplay Name",
                "&7Current: &f" + (g.getDisplayName().isEmpty() ? g.getName() : g.getDisplayName()),
                "", "&eClick to change"));

        inventory.setItem(23, item(Material.CHEST,
                "&7&lMeta",
                "&7Key-value metadata",
                "&7Entries: &f" + g.getMeta().size(),
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
        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) { guiManager.openGroupList(player); return; }

        switch (slot) {
            case 27 -> guiManager.openGroupList(player);
            case 10 -> guiManager.openGroupPermissions(player, groupName, 0);
            case 12 -> guiManager.openGroupInheritance(player, groupName);

            case 14 -> {
                if (click == ClickType.RIGHT) {
                    plugin.getGroupManager().setPrefix(groupName, "");
                    player.sendMessage(MessageUtil.colorizeString("&aCleared prefix for &b" + groupName));
                    open(player);
                } else {
                    guiManager.awaitInput(player, "Enter prefix for &b" + groupName + " &e(use & for colors):", prefix -> {
                        plugin.getGroupManager().setPrefix(groupName, prefix);
                        player.sendMessage(MessageUtil.colorizeString("&aSet prefix: &r" + MessageUtil.colorizeString(prefix)));
                        guiManager.openGroupEditor(player, groupName);
                    });
                }
            }

            case 16 -> {
                if (click == ClickType.RIGHT) {
                    plugin.getGroupManager().setSuffix(groupName, "");
                    player.sendMessage(MessageUtil.colorizeString("&aCleared suffix for &b" + groupName));
                    open(player);
                } else {
                    guiManager.awaitInput(player, "Enter suffix for &b" + groupName + " &e(use & for colors):", suffix -> {
                        plugin.getGroupManager().setSuffix(groupName, suffix);
                        player.sendMessage(MessageUtil.colorizeString("&aSet suffix: &r" + MessageUtil.colorizeString(suffix)));
                        guiManager.openGroupEditor(player, groupName);
                    });
                }
            }

            case 19 -> guiManager.awaitInput(player, "Enter weight for &b" + groupName + " &e(number):", input -> {
                try {
                    int weight = Integer.parseInt(input.trim());
                    plugin.getGroupManager().setWeight(groupName, weight);
                    player.sendMessage(MessageUtil.colorizeString("&aSet weight to &e" + weight));
                } catch (NumberFormatException e) {
                    player.sendMessage(MessageUtil.colorizeString("&cInvalid number."));
                }
                guiManager.openGroupEditor(player, groupName);
            });

            case 21 -> guiManager.awaitInput(player, "Enter display name for &b" + groupName + "&e:", name -> {
                g.setDisplayName(name);
                plugin.getStorage().saveGroup(g);
                player.sendMessage(MessageUtil.colorizeString("&aSet display name to &f" + name));
                guiManager.openGroupEditor(player, groupName);
            });

            case 23 -> guiManager.openGroupMeta(player, groupName);
            case 25 -> guiManager.openGroupContextPerms(player, groupName);
        }
    }
}
