package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

public class MainEditorGui extends BaseGui {

    public MainEditorGui(PermsCraft plugin, GuiManager guiManager, Player viewer) {
        super(plugin, guiManager, viewer);
    }

    @Override
    public void open(Player player) {
        inventory = createInv(4, "&0&lPermsCraft &8| &bEditor");
        fillBorder(inventory);

        inventory.setItem(11, glowItem(Material.SHIELD,
                "&b&lGroups",
                "&7Create, edit, and delete groups",
                "&7Set permissions, prefix, suffix,",
                "&7weight, and inheritance",
                "",
                "&7Total: &a" + plugin.getGroupManager().getAllGroups().size(),
                "", "&eClick to open"));

        inventory.setItem(13, item(Material.PLAYER_HEAD,
                "&a&lUsers",
                "&7Edit player permissions and groups",
                "&7Set personal prefix/suffix",
                "&7Promote/demote on tracks",
                "",
                "&7Online: &a" + org.bukkit.Bukkit.getOnlinePlayers().size(),
                "", "&eClick to open"));

        inventory.setItem(15, item(Material.RAIL,
                "&6&lTracks",
                "&7Create rank progression tracks",
                "&7Promote and demote players",
                "",
                "&7Total: &a" + plugin.getTrackManager().getAllTracks().size(),
                "", "&eClick to open"));

        inventory.setItem(20, item(Material.BOOK,
                "&d&lRecent Log",
                "&7View recent permission changes",
                "&7Color-coded by action type",
                "", "&eClick to view"));

        inventory.setItem(22, item(Material.NETHER_STAR,
                "&f&lPlugin Info",
                "&7PermsCraft v" + plugin.getDescription().getVersion(),
                "&7Redis: " + (plugin.getRedisManager().isEnabled() ? "&aConnected" : "&cDisabled"),
                "&7Cache: &e" + plugin.getPermissionCache().getStats(),
                "&7Storage: &f" + plugin.getStorage().getClass().getSimpleName(),
                "&7Groups: &a" + plugin.getGroupManager().getAllGroups().size(),
                "&7Online: &a" + org.bukkit.Bukkit.getOnlinePlayers().size(),
                "",
                "&eLeft-click &7to view  &cRight-click &7to flush cache"));

        inventory.setItem(24, item(Material.SUNFLOWER,
                "&e&lBackup & Restore",
                "&7Export/import all data as YAML",
                "", "&eClick to open"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        switch (slot) {
            case 11 -> guiManager.openGroupList(player);
            case 13 -> guiManager.openUserSearch(player);
            case 15 -> guiManager.openTrackList(player);
            case 20 -> guiManager.openLogViewer(player, 0);
            case 22 -> {
                if (click == ClickType.RIGHT) {
                    plugin.getPermissionCache().invalidateAll();
                    plugin.getUserManager().invalidateServerKnownCache();
                    player.sendMessage(MessageUtil.colorizeString("&a[PermsCraft] Cache flushed."));
                    open(player);
                }
            }
            case 24 -> guiManager.openBackup(player);
        }
    }
}
