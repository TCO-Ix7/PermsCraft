package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.models.Group;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GroupInheritanceGui extends BaseGui {

    private final String groupName;

    public GroupInheritanceGui(PermsCraft plugin, GuiManager guiManager, Player viewer, String groupName) {
        super(plugin, guiManager, viewer);
        this.groupName = groupName;
    }

    @Override
    public void open(Player player) {
        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) { guiManager.openGroupList(player); return; }

        List<Group> allGroups = new ArrayList<>(plugin.getGroupManager().getAllGroups());
        allGroups.sort((a, b) -> Integer.compare(b.getWeight(), a.getWeight()));
        allGroups.removeIf(gr -> gr.getName().equals(groupName));

        int rows = Math.max(3, (int) Math.ceil(allGroups.size() / 7.0) + 2);
        rows = Math.min(rows, 6);

        inventory = createInv(rows, "&1&l▐ &bInheritance&8: &f" + groupName + " &1&l▐");
        fillBorder(inventory);

        // Inheritance chain display — slot 4
        List<String> chain = buildChain(groupName, new LinkedHashSet<>(), 0);
        String chainDisplay = String.join(" &8→ &b", chain);
        inventory.setItem(4, glowItem(Material.KNOWLEDGE_BOOK,
                "&b&lInheritance Chain",
                "&7" + groupName + " &8inherits:",
                chain.size() <= 1 ? "&8  (none)" : "&b  " + chainDisplay,
                "",
                "&aGreen &7= currently inherited",
                "&cRed &7= not inherited",
                "&6Orange &7= would cause circular dependency"));

        int slot = 10;
        for (Group gr : allGroups) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= inventory.getSize() - 9) break;

            boolean inherited = g.getInheritedGroups().contains(gr.getName());

            // Circular detection: would adding gr as parent of groupName create a cycle?
            boolean wouldCycle = !inherited && wouldCreateCycle(groupName, gr.getName());

            Material mat;
            String statusLine;
            String actionLine;

            if (inherited) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                statusLine = "&a✔ Currently inherited";
                actionLine = "&cClick to remove inheritance";
            } else if (wouldCycle) {
                mat = Material.ORANGE_STAINED_GLASS_PANE;
                statusLine = "&6⚠ Would create circular dependency";
                actionLine = "&8Click disabled — would cause loop";
            } else {
                mat = Material.RED_STAINED_GLASS_PANE;
                statusLine = "&c✘ Not inherited";
                actionLine = "&aClick to add inheritance";
            }

            inventory.setItem(slot, item(mat,
                    (inherited ? "&a✔ " : wouldCycle ? "&6⚠ " : "&c✘ ") + gr.getName(),
                    "&7Weight: &f" + gr.getWeight(),
                    "&7Permissions: &a" + gr.getPermissions().size(),
                    "&7Children: &e" + gr.getInheritedGroups().size(),
                    "",
                    statusLine,
                    actionLine));
            slot++;
        }

        inventory.setItem(inventory.getSize() - 9, backButton());
        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == inventory.getSize() - 9) { guiManager.openGroupEditor(player, groupName); return; }

        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;

        String raw = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (raw == null || raw.isBlank()) return;

        String rawName = raw.replace("✔ ", "").replace("✘ ", "").replace("⚠ ", "")
                           .replace("✔", "").replace("✘", "").replace("⚠", "").trim();
        if (rawName.isBlank()) return;

        Group g = plugin.getGroupManager().getGroup(groupName);
        if (g == null) return;

        if (g.getInheritedGroups().contains(rawName)) {
            plugin.getGroupManager().removeInheritance(groupName, rawName);
            plugin.getLogManager().log(player, ir.permscraft.logging.LogEntry.Action.GROUP_PARENT_REMOVE, groupName, rawName);
            player.sendMessage(MessageUtil.colorizeString("&cRemoved inheritance of &b" + rawName + " &cfrom &e" + groupName));
        } else {
            // Check for cycle before adding
            if (wouldCreateCycle(groupName, rawName)) {
                player.sendMessage(MessageUtil.colorizeString(
                        "&c⚠ Cannot add &e" + rawName + " &cas parent of &b" + groupName
                        + " &c— would create a circular dependency!"));
                return;
            }
            plugin.getGroupManager().addInheritance(groupName, rawName);
            plugin.getLogManager().log(player, ir.permscraft.logging.LogEntry.Action.GROUP_PARENT_ADD, groupName, rawName);
            player.sendMessage(MessageUtil.colorizeString("&aAdded &b" + rawName + " &aas parent of &e" + groupName));
        }

        org.bukkit.Bukkit.getOnlinePlayers().forEach(p ->
                plugin.getUserManager().refreshPermissions(p.getUniqueId()));
        open(player);
    }

    /**
     * Returns true if making `candidate` a parent of `child` would create a cycle.
     * A cycle exists if `child` is already (transitively) a parent of `candidate`.
     */
    private boolean wouldCreateCycle(String child, String candidate) {
        // DFS: traverse upwards from candidate, see if we reach child
        return isAncestor(candidate, child, new LinkedHashSet<>());
    }

    private boolean isAncestor(String current, String target, Set<String> visited) {
        if (current.equals(target)) return true;
        if (visited.contains(current)) return false;
        visited.add(current);
        Group g = plugin.getGroupManager().getGroup(current);
        if (g == null) return false;
        for (String parent : g.getInheritedGroups()) {
            if (isAncestor(parent, target, visited)) return true;
        }
        return false;
    }

    /**
     * Builds the full inheritance chain for display (BFS, max depth 8).
     */
    private List<String> buildChain(String start, Set<String> visited, int depth) {
        List<String> chain = new ArrayList<>();
        if (depth > 8 || visited.contains(start)) return chain;
        visited.add(start);
        chain.add(start);
        Group g = plugin.getGroupManager().getGroup(start);
        if (g == null) return chain;
        for (String parent : g.getInheritedGroups()) {
            chain.addAll(buildChain(parent, visited, depth + 1));
        }
        return chain;
    }
}
