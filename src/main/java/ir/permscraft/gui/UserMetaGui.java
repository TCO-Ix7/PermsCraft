package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.User;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UserMetaGui extends BaseGui {

    private final UUID targetUUID;
    private final String targetName;

    public UserMetaGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                       UUID targetUUID, String targetName) {
        super(plugin, guiManager, viewer);
        this.targetUUID = targetUUID;
        this.targetName = targetName;
    }

    @Override
    public void open(Player player) {
        User user = getUser();
        Map<String, String> meta = user.getMeta();
        List<Map.Entry<String, String>> entries = new ArrayList<>(meta.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        inventory = createInv(4, "&8Meta: &a" + targetName + " &8(" + entries.size() + ")");
        fillBorder(inventory);

        inventory.setItem(4, skull(targetUUID,
                "&a&l" + targetName,
                "&7Meta entries: &f" + entries.size(),
                "",
                "&8Tip: use %permscraft_meta_<key>%"));

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
        if (slot == 27) { guiManager.openUserEditor(player, targetUUID, targetName); return; }

        if (slot == 31) {
            guiManager.awaitInput(player, "Enter &bkey=value &e(e.g. rank=vip):", input -> {
                String[] parts = input.split("=", 2);
                if (parts.length != 2) {
                    player.sendMessage(MessageUtil.colorizeString("&cFormat: key=value"));
                    guiManager.openUserMeta(player, targetUUID, targetName);
                    return;
                }
                plugin.getUserManager().setMeta(targetUUID, parts[0].trim(), parts[1].trim());
                player.sendMessage(MessageUtil.colorizeString("&aSet &e" + parts[0] + " &a= &f" + parts[1]));
                guiManager.openUserMeta(player, targetUUID, targetName);
            });
            return;
        }

        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;
        String key = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName()).trim();
        User user = getUser();
        if (!user.getMeta().containsKey(key)) return;

        if (click == ClickType.RIGHT) {
            plugin.getUserManager().unsetMeta(targetUUID, key);
            player.sendMessage(MessageUtil.colorizeString("&cRemoved meta &e" + key));
            open(player);
        } else {
            guiManager.awaitInput(player, "Enter new value for &b" + key + "&e:", value -> {
                plugin.getUserManager().setMeta(targetUUID, key, value.trim());
                player.sendMessage(MessageUtil.colorizeString("&aUpdated &e" + key + " &a= &f" + value));
                guiManager.openUserMeta(player, targetUUID, targetName);
            });
        }
    }

    private User getUser() {
        User u = plugin.getUserManager().getUser(targetUUID);
        return u != null ? u : plugin.getStorage().loadUser(targetUUID, targetName);
    }
}
