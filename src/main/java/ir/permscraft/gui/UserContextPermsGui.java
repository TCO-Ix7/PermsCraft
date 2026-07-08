package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.context.Context;
import ir.permscraft.context.ContextualPermission;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.UUID;

public class UserContextPermsGui extends BaseGui {

    private final UUID targetUUID;
    private final String targetName;

    public UserContextPermsGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                               UUID targetUUID, String targetName) {
        super(plugin, guiManager, viewer);
        this.targetUUID = targetUUID;
        this.targetName = targetName;
    }

    @Override
    public void open(Player player) {
        String key = targetUUID.toString();
        List<ContextualPermission> perms = plugin.getContextManager().getPermissions(key);

        inventory = createInv(4, "&8Context Perms: &a" + targetName + " &8(" + perms.size() + ")");
        fillBorder(inventory);

        inventory.setItem(4, skull(targetUUID,
                "&a&l" + targetName,
                "&7Context permissions: &f" + perms.size(),
                "", "&7Format: world=worldname:permission"));

        int slot = 10;
        for (ContextualPermission cp : perms) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 27) break;
            boolean granted = cp.getValue();
            String ctx = cp.isGlobal() ? "global"
                    : cp.getRequiredContext().toString();
            inventory.setItem(slot, item(
                    granted ? Material.LIME_DYE : Material.RED_DYE,
                    (granted ? "&a" : "&c") + cp.getPermission(),
                    "&7Status: " + (granted ? "&aGranted" : "&cDenied"),
                    "&7Context: &3" + ctx,
                    "", "&cRight-click &7to remove"));
            slot++;
        }

        inventory.setItem(27, backButton());
        inventory.setItem(31, item(Material.LIME_DYE,
                "&a&l+ Add Context Permission",
                "&7Format: &eworld=worldname:permission",
                "", "&eClick to add"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 27) { guiManager.openUserEditor(player, targetUUID, targetName); return; }

        if (slot == 31) {
            guiManager.awaitInput(player, "Enter &bworld=worldname:permission&e:", input -> {
                String[] parts = input.split(":", 2);
                if (parts.length != 2) {
                    player.sendMessage(MessageUtil.colorizeString("&cFormat: world=worldname:permission"));
                    guiManager.openUserContextPerms(player, targetUUID, targetName);
                    return;
                }
                String contextStr = parts[0].trim();
                String perm = parts[1].trim().toLowerCase();
                boolean granted = !perm.startsWith("-");
                if (!granted) perm = perm.substring(1);

                Context ctx = (contextStr.equals("world=*") || contextStr.equals("*"))
                        ? Context.global()
                        : contextStr.contains("=")
                            ? new Context(contextStr.split("=")[0], contextStr.split("=")[1])
                            : Context.world(contextStr);

                plugin.getContextManager().addContextPermission(targetUUID.toString(), false, perm, ctx, granted);
                player.sendMessage(MessageUtil.colorizeString("&aAdded context perm &e" + perm));
                guiManager.openUserContextPerms(player, targetUUID, targetName);
            });
            return;
        }

        if (click == ClickType.RIGHT) {
            var item = inventory.getItem(slot);
            if (item == null || item.getItemMeta() == null) return;
            String rawPerm = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName()).trim();
            if (rawPerm.isBlank()) return;

            plugin.getContextManager().getPermissions(targetUUID.toString()).stream()
                    .filter(cp -> cp.getPermission().equalsIgnoreCase(rawPerm))
                    .findFirst()
                    .ifPresent(cp -> {
                        plugin.getContextManager().removeContextPermission(
                                targetUUID.toString(), cp.getPermission(), cp.getContext());
                        player.sendMessage(MessageUtil.colorizeString("&cRemoved context perm &e" + rawPerm));
                    });
            open(player);
        }
    }
}
