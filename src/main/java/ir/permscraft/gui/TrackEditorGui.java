package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.models.Track;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

public class TrackEditorGui extends BaseGui {

    private final String trackName;

    public TrackEditorGui(PermsCraft plugin, GuiManager guiManager, Player viewer, String trackName) {
        super(plugin, guiManager, viewer);
        this.trackName = trackName;
    }

    @Override
    public void open(Player player) {
        Track track = plugin.getTrackManager().getTrack(trackName);
        if (track == null) { guiManager.openTrackList(player); return; }

        inventory = createInv(6, "&1&l▐ &6Track&8: &f" + trackName + " &1&l▐");
        fillBorder(inventory);
        fillRow(inventory, 2);
        fillRow(inventory, 4);

        // Track info header (slot 4)
        List<String> groups = track.getGroups();
        String chainDisplay = groups.isEmpty() ? "&8(empty)"
                : groups.stream().map(g -> "&b" + g).reduce((a, b) -> a + " &8→ " + b).orElse("");

        inventory.setItem(4, glowItem(Material.RAIL,
                "&6&l" + track.getName(),
                "&7Steps: &a" + track.size(),
                "&7Chain: " + chainDisplay,
                "",
                "&8▸ &7Click a step to remove it",
                "&8▸ &7Click a group below to append it",
                "&8▸ &eLeft-click step &7to move left",
                "&8▸ &bRight-click step &7to move right"));

        // Row 1 (slots 10-16): current track order with reorder support
        for (int i = 0; i < groups.size() && i < 7; i++) {
            String gName = groups.get(i);
            Group g      = plugin.getGroupManager().getGroup(gName);

            List<String> lore = new ArrayList<>();
            lore.add("&7Step &f" + (i + 1) + " &7of &f" + groups.size());
            if (g != null) lore.add("&7Prefix: &r" + MessageUtil.colorizeString(g.getPrefix()));
            if (g != null) lore.add("&7Weight: &f" + g.getWeight());
            lore.add("");
            lore.add("&cMiddle-click &8▸ &7remove from track");
            if (i > 0)                    lore.add("&eLeft-click &8▸ &7move left");
            if (i < groups.size() - 1)    lore.add("&bRight-click &8▸ &7move right");

            inventory.setItem(10 + i, glowItem(Material.SHIELD,
                    "&b&l" + gName,
                    lore.toArray(new String[0])));

            // Arrow between steps
            if (i < groups.size() - 1 && i < 6) {
                inventory.setItem(19 + i, item(Material.ARROW, "&8→ &7next step"));
            }
        }

        // Row 3 label
        inventory.setItem(18, item(Material.LIME_DYE,
                "&a&lAvailable Groups",
                "&7Click any group below to append it to the track",
                "&7Groups already in track are grayed out"));

        // Rows 3-4 (slots 27-44): all available groups
        List<Group> allGroups = new ArrayList<>(plugin.getGroupManager().getAllGroups());
        allGroups.sort((a, b) -> Integer.compare(b.getWeight(), a.getWeight()));

        int slot = 27;
        for (Group g : allGroups) {
            if (slot >= 45) break;
            if (slot % 9 == 8) slot++;

            boolean inTrack = track.containsGroup(g.getName());
            inventory.setItem(slot, item(
                    inTrack ? Material.GRAY_STAINED_GLASS_PANE : Material.SHIELD,
                    inTrack ? "&8" + g.getName() + " &8(in track)" : "&b" + g.getName(),
                    "&7Weight: &f" + g.getWeight(),
                    inTrack ? "&7Already in track" : "&aClick to append to track"));
            slot++;
        }

        inventory.setItem(45, backButton());
        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 45) { guiManager.openTrackList(player); return; }

        Track track = plugin.getTrackManager().getTrack(trackName);
        if (track == null) { guiManager.openTrackList(player); return; }

        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;
        String rawName = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName()).trim();
        // Strip "(in track)" suffix
        rawName = rawName.replaceAll("\\s*\\(in track\\)$", "").trim();

        // Current track step slots (10-16): remove or reorder
        if (slot >= 10 && slot <= 16 && item.getType() == Material.SHIELD) {
            List<String> groups = new ArrayList<>(track.getGroups());
            int idx = slot - 10;
            if (idx >= groups.size()) return;

            if (click == ClickType.MIDDLE) {
                // Remove from track
                plugin.getTrackManager().removeGroupFromTrack(trackName, groups.get(idx));
                player.sendMessage(MessageUtil.colorizeString("&cRemoved &b" + groups.get(idx) + " &cfrom track &e" + trackName));
                open(player);
                return;
            }

            if (click == ClickType.LEFT && idx > 0) {
                // Move left: swap with previous
                String current  = groups.get(idx);
                String previous = groups.get(idx - 1);
                plugin.getTrackManager().removeGroupFromTrack(trackName, current);
                plugin.getTrackManager().removeGroupFromTrack(trackName, previous);
                // Re-insert in swapped order at correct position
                // Rebuild whole order
                groups.set(idx, previous);
                groups.set(idx - 1, current);
                rebuildTrack(track, groups);
                player.sendMessage(MessageUtil.colorizeString("&eMoved &b" + current + " &eleft in track"));
                open(player);
                return;
            }

            if (click == ClickType.RIGHT && idx < groups.size() - 1) {
                // Move right: swap with next
                String current = groups.get(idx);
                String next    = groups.get(idx + 1);
                groups.set(idx, next);
                groups.set(idx + 1, current);
                rebuildTrack(track, groups);
                player.sendMessage(MessageUtil.colorizeString("&eMoved &b" + current + " &eright in track"));
                open(player);
                return;
            }

            // Plain left-click with no movement possible: notify
            if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                player.sendMessage(MessageUtil.colorizeString("&7Use &eMiddle-click &7to remove, or click arrows to reorder."));
            }
            return;
        }

        // Add group from available list (slots 27-44)
        if (slot >= 27 && slot <= 44 && item.getType() == Material.SHIELD) {
            if (!track.containsGroup(rawName)) {
                plugin.getTrackManager().addGroupToTrack(trackName, rawName);
                player.sendMessage(MessageUtil.colorizeString("&aAppended &b" + rawName + " &ato track &e" + trackName));
                open(player);
            }
        }
    }

    /**
     * Rebuilds the track's group list from scratch after a reorder.
     * Clears all groups and re-adds in new order.
     */
    private void rebuildTrack(Track track, List<String> newOrder) {
        // Remove all then re-add in new order
        List<String> copy = new ArrayList<>(track.getGroups());
        for (String g : copy) {
            plugin.getTrackManager().removeGroupFromTrack(trackName, g);
        }
        for (String g : newOrder) {
            plugin.getTrackManager().addGroupToTrack(trackName, g);
        }
    }
}
