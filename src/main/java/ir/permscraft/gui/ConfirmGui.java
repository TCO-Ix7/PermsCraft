package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Generic Yes / No confirmation GUI.
 * Used instead of typing "DELETE" in chat for dangerous operations.
 */
public class ConfirmGui extends BaseGui {

    private final String title;
    private final String description;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public ConfirmGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                      String title, String description,
                      Runnable onConfirm, Runnable onCancel) {
        super(plugin, guiManager, viewer);
        this.title       = title;
        this.description = description;
        this.onConfirm   = onConfirm;
        this.onCancel    = onCancel;
    }

    @Override
    public void open(Player player) {
        inventory = createInv(3, "&1&l▐ &eConfirm&8: &f" + title + " &1&l▐");
        fillBorder(inventory);

        // Center info item
        inventory.setItem(13, item(Material.NETHER_STAR,
                "&e&l" + title,
                description,
                "",
                "&aSlot 11 &8▸ &7Confirm",
                "&cSlot 15 &8▸ &7Cancel"));

        // Confirm (green glow)
        inventory.setItem(11, glowItem(Material.LIME_STAINED_GLASS_PANE,
                "&a&l✔ CONFIRM",
                "&7Click to proceed with:",
                "&e" + title,
                "",
                description));

        // Cancel (red, no glow to make difference obvious)
        inventory.setItem(15, glowItem(Material.RED_STAINED_GLASS_PANE,
                "&c&l✘ CANCEL",
                "&7Click to go back"));

        player.openInventory(inventory);
    }

    @Override
    public void handleClick(int slot, ClickType click, Player player) {
        if (slot == 11) {
            onConfirm.run();
        } else if (slot == 15) {
            onCancel.run();
        }
    }
}
