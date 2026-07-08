package ir.permscraft.gui;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

public class BackupGui extends BaseGui {

    public BackupGui(PermsCraft plugin, GuiManager guiManager, Player viewer) {
        super(plugin, guiManager, viewer);
    }

    @Override
    public void open(Player player) {
        List<String> backups = plugin.getYamlBackup().listBackups();

        inventory = createInv(4, "&1&l▐ &e&lBackup & Restore &1&l▐");
        fillBorder(inventory);

        // Header
        inventory.setItem(4, glowItem(Material.CHEST,
                "&e&lBackup & Restore",
                "&7Total backups: &f" + backups.size(),
                "&7Location: &fplugins/PermsCraft/backups/",
                "",
                "&7Export creates a YAML snapshot",
                "&7Import restores from a snapshot"));

        // Export
        inventory.setItem(11, glowItem(Material.CHEST,
                "&a&l✚ Export to YAML",
                "&7Saves all groups, users, tracks",
                "&7and permissions to a backup file.",
                "&7Location: &fplugins/PermsCraft/backups/",
                "",
                "&eClick to export now"));

        // Import
        inventory.setItem(13, item(Material.HOPPER,
                "&6&l⟳ Import from YAML",
                "&7Restore data from a backup file.",
                "&cWarning: &7This will overwrite current data!",
                "",
                "&7Available backups: &f" + backups.size(),
                "",
                "&eClick to enter filename"));

        // List backups — show up to 8 recent in lore
        String recentLine;
        if (backups.isEmpty()) {
            recentLine = "&8(none)";
        } else {
            StringBuilder sb = new StringBuilder();
            int show = Math.min(backups.size(), 5);
            for (int i = 0; i < show; i++) {
                sb.append("  &f").append(backups.get(i));
                if (i < show - 1) sb.append("\n");
            }
            recentLine = sb.toString();
        }

        inventory.setItem(15, glowItem(Material.BOOK,
                "&b&l☰ List Backups",
                "&7Available backup files:",
                recentLine,
                backups.size() > 5 ? "&7...and &e" + (backups.size() - 5) + " &7more" : "",
                "",
                "&eClick to list in chat"));

        // Status info
        inventory.setItem(22, item(Material.COMPARATOR,
                "&d&lStorage Info",
                "&7Backend: &f" + plugin.getStorage().getClass().getSimpleName(),
                "&7Redis: " + (plugin.getRedisManager().isEnabled() ? "&aConnected" : "&cDisabled"),
                "&7Total backups: &f" + backups.size(),
                "",
                "&7Use /pc backup &7for CLI access"));

        inventory.setItem(31, backButton());
        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        switch (slot) {
            case 11 -> {
                player.closeInventory();
                plugin.getYamlBackup().export(player);
            }
            case 13 -> {
                guiManager.awaitInput(player,
                        "Enter backup filename to import (include .yml):",
                        name -> {
                            guiManager.openConfirm(player,
                                    "Import backup: " + name,
                                    "&cThis will overwrite all current data!",
                                    () -> {
                                        FoliaScheduler.runAsync(plugin, () -> {
                                            plugin.getYamlBackup().importBackup(player, name);
                                            FoliaScheduler.runSync(plugin,
                                                    () -> guiManager.openBackup(player));
                                        });
                                    },
                                    () -> guiManager.openBackup(player));
                        });
            }
            case 15 -> {
                List<String> backups = plugin.getYamlBackup().listBackups();
                if (backups.isEmpty()) {
                    player.sendMessage(MessageUtil.colorizeString("&7No backups found."));
                } else {
                    player.sendMessage(MessageUtil.colorizeString("&7Available backups:"));
                    backups.forEach(f ->
                            player.sendMessage(MessageUtil.colorizeString("  &8- &f" + f)));
                }
                player.closeInventory();
            }
            case 31 -> guiManager.openMain(player);
        }
    }
}
