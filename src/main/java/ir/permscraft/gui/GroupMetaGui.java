package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GroupMetaGui extends BaseGui {

    private final String groupName;

    public GroupMetaGui(PermsCraft plugin, GuiManager guiManager, Player viewer, String groupName) {
        super(plugin, guiManager, viewer);
        this.groupName = groupName;
    }

    @Override
    public void open(Player player) {
        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) { guiManager.openGroupList(player); return; }

        Map<String, String> meta = g.getMeta();
        List<Map.Entry<String, String>> entries = new ArrayList<>(meta.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        inventory = createInv(4, "&8Meta: &b" + groupName + " &8(" + entries.size() + ")");
        fillBorder(inventory);

        inventory.setItem(4, item(Material.CHEST,
                "&b&lMeta: " + groupName,
                "&7Key-value metadata entries",
                "&7Total: &f" + entries.size(),
                "",
                "&eLeft-click &7to edit a value",
                "&cRight-click &7to delete a key"));

        int slot = 10;
        for (Map.Entry<String, String> e : entries) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 27) break;
            inventory.setItem(slot, item(Material.BOOK,
                    "&e" + e.getKey(),
                    "&7Value: &f" + e.getValue(),
                    "",
                    "&eLeft-click &7to edit",
                    "&cRight-click &7to delete"));
            slot++;
        }

        inventory.setItem(27, backButton());
        inventory.setItem(31, item(Material.LIME_DYE,
                "&a&l+ Add Meta",
                "&7Format: &ekey=value",
                "", "&eClick to add"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 27) { guiManager.openGroupEditor(player, groupName); return; }

        if (slot == 31) {
            guiManager.awaitInput(player, "Enter &bkey=value &e(e.g. color=#FF0000):", input -> {
                String[] parts = input.split("=", 2);
                if (parts.length != 2) {
                    player.sendMessage(MessageUtil.colorizeString("&cFormat: key=value"));
                    guiManager.openGroupMeta(player, groupName);
                    return;
                }
                Group g = plugin.getGroupManager().getGroup(groupName);
                if (g != null) {
                    g.setMeta(parts[0].trim(), parts[1].trim());
                    plugin.getStorage().saveGroup(g);
                    player.sendMessage(MessageUtil.colorizeString("&aSet &e" + parts[0] + " &a= &f" + parts[1]));
                }
                guiManager.openGroupMeta(player, groupName);
            });
            return;
        }

        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;
        String key = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName()).trim();

        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null || !g.getMeta().containsKey(key)) return;

        if (click == ClickType.RIGHT) {
            g.unsetMeta(key);
            plugin.getStorage().saveGroup(g);
            player.sendMessage(MessageUtil.colorizeString("&cRemoved meta key &e" + key));
            open(player);
        } else {
            guiManager.awaitInput(player, "Enter new value for &b" + key + "&e:", value -> {
                g.setMeta(key, value.trim());
                plugin.getStorage().saveGroup(g);
                player.sendMessage(MessageUtil.colorizeString("&aUpdated &e" + key + " &a= &f" + value));
                guiManager.openGroupMeta(player, groupName);
            });
        }
    }
}
