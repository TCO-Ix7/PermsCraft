package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

public class GroupListGui extends BaseGui {

    private int page;
    private static final int PAGE_SIZE = 28;

    public GroupListGui(PermsCraft plugin, GuiManager guiManager, Player viewer) {
        super(plugin, guiManager, viewer);
        this.page = 0;
    }

    @Override
    public void open(Player player) {
        List<Group> groups = new ArrayList<>(plugin.getGroupManager().getAllGroups());
        groups.sort((a, b) -> Integer.compare(b.getWeight(), a.getWeight()));

        inventory = createInv(6, "&1&l▐ &b&lGroups &8(&f" + groups.size() + "&8) &1&l▐");
        fillBorder(inventory);
        fillRow(inventory, 4);

        // Blue border accent - top row header items
        inventory.setItem(4, glowItem(Material.SHIELD,
                "&b&lGroup List",
                "&7Sorted by weight &8(highest first)",
                "&7Total groups: &b" + groups.size(),
                "",
                "&8▸ &7Left-click to edit",
                "&8▸ &cRight-click to delete"));

        int start = page * PAGE_SIZE;
        int slot   = 10;
        int count  = 0;

        for (int i = start; i < groups.size() && count < PAGE_SIZE; i++) {
            Group g = groups.get(i);
            if (slot % 9 == 8) slot += 2;

            // Weight bar: ░░░ fill based on weight (max 100 assumed)
            int weight = g.getWeight();
            int bars = Math.min(10, Math.max(0, weight / 10));
            String bar = "&b" + "█".repeat(bars) + "&8" + "░".repeat(10 - bars);

            // Color by weight tier
            String tierColor = weight >= 100 ? "&6" : weight >= 50 ? "&a" : weight >= 10 ? "&e" : "&7";

            inventory.setItem(slot, glowItem(Material.SHIELD,
                    "&b&l" + g.getName(),
                    "&7Display: &f" + (g.getDisplayName().isEmpty() ? g.getName() : g.getDisplayName()),
                    "&7Weight: " + tierColor + weight + "  " + bar,
                    "&7Permissions: &a" + g.getPermissions().size(),
                    "&7Parents: &e" + (g.getInheritedGroups().isEmpty() ? "&8none" : String.join("&8, &e", g.getInheritedGroups())),
                    "&7Prefix: &r" + MessageUtil.colorizeString(g.getPrefix()),
                    "",
                    "&eLeft-click &8▸ &7edit",
                    "&cRight-click &8▸ &7delete"));
            slot++;
            count++;
        }

        inventory.setItem(36, backButton());
        if (page > 0) inventory.setItem(38, prevPage());
        if (start + PAGE_SIZE < groups.size()) inventory.setItem(42, nextPage());

        inventory.setItem(40, item(Material.NETHER_STAR,
                "&7Page &f" + (page + 1),
                "&7Showing &b" + Math.min(PAGE_SIZE, groups.size() - start) + " &7/ &f" + groups.size()));

        inventory.setItem(44, glowItem(Material.LIME_DYE,
                "&a&l✚ Create Group",
                "&7Click to create a new permission group"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 36) { guiManager.openMain(player); return; }
        if (slot == 38 && page > 0) { page--; open(player); return; }
        if (slot == 42) { page++; open(player); return; }

        if (slot == 44) {
            guiManager.awaitInput(player, "Enter new group name:", name -> {
                name = name.toLowerCase().replaceAll("[^a-z0-9_]", "");
                if (name.isEmpty()) {
                    player.sendMessage(MessageUtil.colorizeString("&cInvalid name. Only letters, numbers and _ allowed."));
                    guiManager.openGroupList(player);
                    return;
                }
                if (plugin.getGroupManager().groupExists(name)) {
                    player.sendMessage(MessageUtil.colorizeString("&cGroup &e" + name + " &calready exists."));
                } else {
                    plugin.getGroupManager().createGroup(name);
                    player.sendMessage(MessageUtil.colorizeString("&aCreated group &e" + name));
                }
                guiManager.openGroupList(player);
            });
            return;
        }

        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;
        String displayName = item.getItemMeta().getDisplayName();
        String groupName   = org.bukkit.ChatColor.stripColor(displayName).trim();
        if (groupName.isBlank()) return;

        if (!plugin.getGroupManager().groupExists(groupName)) return;

        if (click == ClickType.RIGHT) {
            if (groupName.equalsIgnoreCase("default")) {
                player.sendMessage(MessageUtil.colorizeString("&cCannot delete the default group."));
                return;
            }
            guiManager.openConfirm(player,
                    "Delete group: " + groupName,
                    "&cThis will remove all members and permissions!",
                    () -> {
                        plugin.getGroupManager().deleteGroup(groupName);
                        plugin.getLogManager().log(player,
                                ir.permscraft.logging.LogEntry.Action.GROUP_DELETE,
                                groupName, "");
                        player.sendMessage(MessageUtil.colorizeString("&cDeleted group &e" + groupName));
                        guiManager.openGroupList(player);
                    },
                    () -> guiManager.openGroupList(player));
        } else {
            guiManager.openGroupEditor(player, groupName);
        }
    }
}
