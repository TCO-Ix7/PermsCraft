package ir.permscraft.gui;

import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

public abstract class BaseGui {

    protected final PermsCraft plugin;
    protected final GuiManager guiManager;
    protected final Player viewer;
    protected Inventory inventory;

    public BaseGui(PermsCraft plugin, GuiManager guiManager, Player viewer) {
        this.plugin     = plugin;
        this.guiManager = guiManager;
        this.viewer     = viewer;
    }

    public abstract void open(Player player);
    public abstract void handleClick(int slot, ClickType click, Player player);

    protected ItemStack item(Material mat, String name, String... lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        meta.setDisplayName(MessageUtil.colorizeString(name));
        if (lore.length > 0)
            meta.setLore(Arrays.stream(lore).map(MessageUtil::colorizeString).collect(Collectors.toList()));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_UNBREAKABLE);
        try {
            meta.addItemFlags(ItemFlag.valueOf("HIDE_ADDITIONAL_TOOLTIP"));
        } catch (Exception ignored) {}
        is.setItemMeta(meta);
        return is;
    }

    protected ItemStack glowItem(Material mat, String name, String... lore) {
        ItemStack is = item(mat, name, lore);
        ItemMeta meta = is.getItemMeta();
        meta.addEnchant(Enchantment.INFINITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        is.setItemMeta(meta);
        return is;
    }

    protected ItemStack skull(UUID uuid, String name, String... lore) {
        ItemStack is = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) is.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        meta.setDisplayName(MessageUtil.colorizeString(name));
        if (lore.length > 0)
            meta.setLore(Arrays.stream(lore).map(MessageUtil::colorizeString).collect(Collectors.toList()));
        is.setItemMeta(meta);
        return is;
    }

    /** Blue stained glass pane — used for the border accent. */
    protected ItemStack filler()       { return item(Material.BLUE_STAINED_GLASS_PANE, " "); }
    protected ItemStack backButton()   { return item(Material.ARROW, "&7« Back"); }
    protected ItemStack prevPage()     { return item(Material.ARROW, "&7« Previous"); }
    protected ItemStack nextPage()     { return item(Material.ARROW, "&aNext »"); }

    protected void fillBorder(Inventory inv) {
        int size = inv.getSize();
        for (int i = 0; i < 9; i++) inv.setItem(i, filler());
        for (int i = size - 9; i < size; i++) inv.setItem(i, filler());
        for (int i = 9; i < size - 9; i += 9) inv.setItem(i, filler());
        for (int i = 17; i < size - 9; i += 9) inv.setItem(i, filler());
    }

    protected void fillRow(Inventory inv, int row) {
        for (int i = row * 9; i < row * 9 + 9; i++) inv.setItem(i, filler());
    }

    protected Inventory createInv(int rows, String title) {
        return Bukkit.createInventory(null, rows * 9, MessageUtil.colorizeString(title));
    }
}
