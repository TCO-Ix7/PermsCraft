package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

public class UserSearchGui extends BaseGui {

    public UserSearchGui(PermsCraft plugin, GuiManager guiManager, Player viewer) {
        super(plugin, guiManager, viewer);
    }

    @Override
    public void open(Player player) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int rows = Math.max(3, (int) Math.ceil(online.size() / 7.0) + 2);
        rows = Math.min(rows, 6);

        inventory = createInv(rows, "&1&l▐ &aUsers &8(Online: &f" + online.size() + "&8) &1&l▐");
        fillBorder(inventory);

        // Header info
        inventory.setItem(4, item(Material.PLAYER_HEAD,
                "&a&lOnline Players",
                "&7Total online: &f" + online.size(),
                "",
                "&7Click a player to edit their",
                "&7permissions and groups",
                "",
                "&8▸ &7Shows ping, group, prefix"));

        int slot = 10;
        for (Player p : online) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= inventory.getSize() - 9) break;

            var user = plugin.getUserManager().getUser(p.getUniqueId());
            String primaryGroup = user != null ? user.getPrimaryGroup() : "default";
            var g = plugin.getGroupManager().getGroup(primaryGroup);
            String prefix = g != null ? g.getPrefix() : "";

            // Ping color
            int ping = p.getPing();
            String pingColor = ping < 50 ? "&a" : ping < 150 ? "&e" : ping < 300 ? "&6" : "&c";
            String pingBar   = ping < 50 ? "▮▮▮▮" : ping < 150 ? "▮▮▮&8▯" : ping < 300 ? "▮▮&8▯▯" : "▮&8▯▯▯";

            // Group count
            int groupCount = user != null ? user.getGroups().size() : 0;

            inventory.setItem(slot, skull(p.getUniqueId(),
                    "&f&l" + p.getName(),
                    "&7Primary Group: &b★ " + primaryGroup,
                    "&7Groups: &a" + groupCount,
                    "&7Prefix: &r" + MessageUtil.colorizeString(prefix),
                    "&7Permissions: &e" + (user != null ? user.getPermissions().size() : 0) + " personal",
                    "&7Ping: " + pingColor + ping + "ms &8[" + pingColor + pingBar + "&8]",
                    "",
                    "&eClick to edit"));
            slot++;
        }

        // Search offline player
        inventory.setItem(inventory.getSize() - 5, glowItem(Material.COMPASS,
                "&e&l⌕ Search Offline Player",
                "&7Click to enter a player name",
                "&7(must have joined before)",
                "",
                "&8▸ &7Searches storage by username"));

        inventory.setItem(inventory.getSize() - 9, backButton());
        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == inventory.getSize() - 9) { guiManager.openMain(player); return; }

        if (slot == inventory.getSize() - 5) {
            guiManager.awaitInput(player, "Enter player name:", name -> {
                Player online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    guiManager.openUserEditor(player, online.getUniqueId(), online.getName());
                    return;
                }
                java.util.UUID uuid = plugin.getStorage().findUUIDByUsername(name);
                if (uuid == null) {
                    player.sendMessage(MessageUtil.colorizeString("&cPlayer &e" + name + " &cnot found."));
                    guiManager.openUserSearch(player);
                } else {
                    guiManager.openUserEditor(player, uuid, name);
                }
            });
            return;
        }

        var item = inventory.getItem(slot);
        if (item == null || item.getType() != org.bukkit.Material.PLAYER_HEAD) return;
        if (item.getItemMeta() == null) return;

        String rawName = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName());
        Player target = Bukkit.getPlayerExact(rawName);
        if (target != null) guiManager.openUserEditor(player, target.getUniqueId(), target.getName());
    }
}
