package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Track;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

public class TrackListGui extends BaseGui {

    public TrackListGui(PermsCraft plugin, GuiManager guiManager, Player viewer) {
        super(plugin, guiManager, viewer);
    }

    @Override
    public void open(Player player) {
        List<Track> tracks = new ArrayList<>(plugin.getTrackManager().getAllTracks());

        inventory = createInv(4, "&1&l▐ &6&lTracks &8(&f" + tracks.size() + "&8) &1&l▐");
        fillBorder(inventory);

        // Header
        inventory.setItem(4, glowItem(Material.RAIL,
                "&6&lTrack List",
                "&7Total tracks: &f" + tracks.size(),
                "",
                "&7Tracks define rank progression chains",
                "&7for promoting/demoting players",
                "",
                "&8▸ &7Left-click to edit",
                "&8▸ &cRight-click to delete"));

        int slot = 10;
        for (Track t : tracks) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 27) break;

            // Build visual progression chain display
            List<String> groups = t.getGroups();
            String chain = groups.isEmpty() ? "&8(empty)"
                    : groups.stream()
                        .map(g -> "&b" + g)
                        .reduce((a, b) -> a + " &8→ " + b)
                        .orElse("");

            inventory.setItem(slot, glowItem(Material.RAIL,
                    "&6&l" + t.getName(),
                    "&7Steps: &a" + t.size(),
                    "&7Chain: " + chain,
                    "",
                    "&eLeft-click &8▸ &7edit",
                    "&cRight-click &8▸ &7delete"));
            slot++;
        }

        if (tracks.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER,
                    "&7No tracks yet",
                    "&7Create one using the button below"));
        }

        inventory.setItem(31, glowItem(Material.POWERED_RAIL,
                "&a&l✚ Create Track",
                "&7Create a new rank progression track",
                "",
                "&eClick to create"));

        inventory.setItem(27, backButton());
        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 27) { guiManager.openMain(player); return; }

        if (slot == 31) {
            guiManager.awaitInput(player, "Enter track name:", name -> {
                name = name.toLowerCase().replaceAll("[^a-z0-9_]", "");
                if (name.isEmpty()) {
                    player.sendMessage(MessageUtil.colorizeString("&cInvalid track name."));
                    guiManager.openTrackList(player);
                    return;
                }
                if (plugin.getTrackManager().trackExists(name)) {
                    player.sendMessage(MessageUtil.colorizeString("&cTrack &e" + name + " &calready exists."));
                } else {
                    plugin.getTrackManager().createTrack(name);
                    player.sendMessage(MessageUtil.colorizeString("&aCreated track &e" + name));
                }
                guiManager.openTrackList(player);
            });
            return;
        }

        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;
        String rawName = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName()).trim();
        if (rawName.isBlank() || !plugin.getTrackManager().trackExists(rawName)) return;

        if (click == ClickType.RIGHT) {
            guiManager.openConfirm(player,
                    "Delete track: " + rawName,
                    "&7This will not affect player groups.",
                    () -> {
                        plugin.getTrackManager().deleteTrack(rawName);
                        player.sendMessage(MessageUtil.colorizeString("&cDeleted track &e" + rawName));
                        guiManager.openTrackList(player);
                    },
                    () -> guiManager.openTrackList(player));
        } else {
            guiManager.openTrackEditor(player, rawName);
        }
    }
}
