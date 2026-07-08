package ir.permscraft.gui;

import ir.permscraft.FoliaScheduler;
import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.permissions.Permission;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * PermissionBrowserGui — GUI مرور permission برای groups و users.
 *
 * ویژگی‌ها:
 * - لیست تمام permission های ثبت‌شده در سرور (از Bukkit.getPluginManager())
 * - گروه‌بندی بر اساس plugin prefix (essentials.*, minecraft.*, ...)
 * - جستجو / فیلتر real-time
 * - صفحه‌بندی (28 item در هر صفحه)
 * - cache invalidation وقتی plugin جدید load میشه
 * - callback: وقتی permission انتخاب شد، اعمال میشه
 * - backAction: برگشت به صفحه قبل (group یا user) — تعریف شده توسط caller
 *
 * برای هر دو Group و User قابل استفاده‌ست.
 */
public class PermissionBrowserGui extends BaseGui {

    // ── Static cache: shared across all open browsers ─────────────────────────
    // Key: server instance identity → sorted list of all known permissions
    // invalidated by PluginLoadListener when a new plugin loads
    private static volatile List<PermNode> cachedNodes = null;
    private static final Object CACHE_LOCK = new Object();

    // Open browser instances — used by PluginLoadListener to refresh live GUIs
    private static final Set<PermissionBrowserGui> openBrowsers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ── Instance state ─────────────────────────────────────────────────────────
    private final String           targetLabel; // group name or player name — shown in title
    private final Consumer<String> callback;    // called with selected perm node
    private final boolean          deny;        // true = prefix with "-"
    private final Runnable         backAction;  // what to do when Back is clicked

    private int    page   = 0;
    private String filter = null;               // null = no filter
    private String selectedPlugin = null;       // null = show all plugins

    private static final int PAGE_SIZE = 28;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Legacy constructor for Group (back → GroupPermissionsGui). */
    public PermissionBrowserGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                                String groupName, boolean deny, Consumer<String> callback) {
        super(plugin, guiManager, viewer);
        this.targetLabel = groupName;
        this.deny        = deny;
        this.callback    = callback;
        this.backAction  = () -> guiManager.openGroupPermissions(viewer, groupName, 0);
    }

    /**
     * Generic constructor for User or Group with custom back action.
     *
     * @param targetLabel  display name shown in the GUI title (group or player name)
     * @param deny         true = selected node gets "-" prefix (deny)
     * @param callback     called with the selected/entered permission node
     * @param backAction   called when the Back button is clicked
     */
    public PermissionBrowserGui(PermsCraft plugin, GuiManager guiManager, Player viewer,
                                String targetLabel, boolean deny,
                                Consumer<String> callback, Runnable backAction) {
        super(plugin, guiManager, viewer);
        this.targetLabel = targetLabel;
        this.deny        = deny;
        this.callback    = callback;
        this.backAction  = backAction;
    }

    // ── Cache management (called by PluginLoadListener) ───────────────────────

    /** Invalidate the shared cache. Called whenever a plugin loads/unloads. */
    public static void invalidateCache() {
        synchronized (CACHE_LOCK) {
            cachedNodes = null;
        }
        // Refresh any currently open browsers on the main thread
        openBrowsers.forEach(b -> {
            if (b.viewer != null && b.viewer.isOnline()) {
               FoliaScheduler.runSync(b.plugin, () -> b.open(b.viewer));
            }
        });
    }

    /** Get or build the sorted permission node list. Thread-safe. */
    private static List<PermNode> getNodes() {
        List<PermNode> nodes = cachedNodes;
        if (nodes != null) return nodes;
        synchronized (CACHE_LOCK) {
            if (cachedNodes != null) return cachedNodes;
            Map<String, List<String>> byPlugin = new LinkedHashMap<>();

            for (Permission perm : Bukkit.getPluginManager().getPermissions()) {
                String name = perm.getName().toLowerCase();
                // Detect plugin prefix: everything before the first dot
                String prefix = name.contains(".") ? name.substring(0, name.indexOf('.')) : "other";
                byPlugin.computeIfAbsent(prefix, k -> new ArrayList<>()).add(name);
            }

            List<PermNode> built = new ArrayList<>();
            // Sort plugins alphabetically; "other" goes last
            byPlugin.entrySet().stream()
                    .sorted((a, b) -> {
                        if (a.getKey().equals("other")) return 1;
                        if (b.getKey().equals("other")) return -1;
                        return a.getKey().compareTo(b.getKey());
                    })
                    .forEach(entry -> {
                        List<String> perms = entry.getValue();
                        Collections.sort(perms);
                        perms.forEach(p -> built.add(new PermNode(p, entry.getKey())));
                    });

            cachedNodes = Collections.unmodifiableList(built);
            return cachedNodes;
        }
    }

    /** Get a sorted, deduplicated list of plugin prefixes. */
    private static List<String> getPluginPrefixes() {
        return getNodes().stream()
                .map(n -> n.plugin)
                .distinct()
                .sorted((a, b) -> {
                    if (a.equals("other")) return 1;
                    if (b.equals("other")) return -1;
                    return a.compareTo(b);
                })
                .collect(Collectors.toList());
    }

    // ── GUI rendering ─────────────────────────────────────────────────────────

    @Override
    public void open(Player player) {
        openBrowsers.add(this);

        List<PermNode> all = getNodes();

        // Apply plugin filter
        List<PermNode> filtered = selectedPlugin == null
                ? all
                : all.stream().filter(n -> n.plugin.equals(selectedPlugin)).collect(Collectors.toList());

        // Apply text filter
        if (filter != null && !filter.isBlank()) {
            String f = filter.toLowerCase();
            filtered = filtered.stream()
                    .filter(n -> n.name.contains(f))
                    .collect(Collectors.toList());
        }

        int total  = filtered.size();
        int start  = page * PAGE_SIZE;
        int end    = Math.min(start + PAGE_SIZE, total);

        String modeColor = deny ? "&c" : "&a";
        String modeLabel = deny ? "DENY" : "GRANT";
        String title = "&1&l▐ &bBrowse Permissions &8▸ &f" + targetLabel
                + " &8[" + modeColor + modeLabel + "&8] &1&l▐";

        inventory = createInv(6, title);
        fillBorder(inventory);
        fillRow(inventory, 4);

        // ── Row 0 (top bar) ───────────────────────────────────────────────────
        // Slot 4: info
        inventory.setItem(4, item(Material.BOOKSHELF,
                "&b&lPermission Browser",
                "&7Target: &f" + targetLabel,
                "&7Mode: " + modeColor + modeLabel,
                "&7Total matching: &f" + total,
                "",
                "&8Use the buttons below to filter",
                "&8Click a &apermission &8to select it"));

        // ── Permission list (rows 1-3, slots 10-43 skip col 0 and 8) ─────────
        int slot  = 10;
        int count = 0;
        for (int i = start; i < end; i++) {
            if (slot % 9 == 8) slot += 2; // skip right border
            PermNode node = filtered.get(i);

            Material mat = pluginMaterial(node.plugin);
            String color = deny ? "&c" : "&a";

            inventory.setItem(slot, glowItem(mat,
                    color + node.name,
                    "&7Plugin: &e" + node.plugin,
                    "",
                    "&eClick &8▸ &7" + (deny ? "deny" : "grant") + " to &b" + targetLabel));
            slot++;
            count++;
        }

        // ── Row 4 (nav bar) ───────────────────────────────────────────────────
        // Slot 36: back
        inventory.setItem(36, backButton());

        // Slot 37: plugin filter
        inventory.setItem(37, item(Material.CHEST,
                selectedPlugin != null ? "&e&l⊞ Plugin: &f" + selectedPlugin : "&7&l⊞ Filter by Plugin",
                selectedPlugin != null ? "&cRight-click to clear" : "&7Click to filter by plugin",
                "&7" + getPluginPrefixes().size() + " plugins available"));

        // Slot 38: prev page
        if (page > 0) inventory.setItem(38, prevPage());

        // Slot 40: page indicator
        inventory.setItem(40, item(Material.PAPER,
                "&7Page &f" + (page + 1) + " &8/ &f" + Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE)),
                "&7Showing &a" + (total == 0 ? 0 : (start + 1)) + " &8- &a" + end + " &7of &f" + total));

        // Slot 42: next page
        if (end < total) inventory.setItem(42, nextPage());

        // Slot 39: text search
        inventory.setItem(39, item(Material.COMPASS,
                filter != null ? "&e&l⌕ Search: &f" + filter : "&7&l⌕ Search",
                "&7Click to type a keyword",
                filter != null ? "&cRight-click to clear" : ""));

        // Slot 41: toggle mode (grant ↔ deny) — informational, actual mode set by caller
        inventory.setItem(41, item(Material.COMPARATOR,
                "&7Mode: " + modeColor + "&l" + modeLabel,
                "&7You opened this browser in " + modeLabel + " mode",
                "&7Go back to change mode"));

        // Slot 44: manual entry fallback
        inventory.setItem(44, item(Material.WRITABLE_BOOK,
                "&e&l✎ Type manually",
                "&7Can't find the permission?",
                "&7Click to type it manually"));

        player.openInventory(inventory);
    }

    // ── Click handler ─────────────────────────────────────────────────────────

    @Override
    public void handleClick(int slot, ClickType click, Player player) {

        // Back
        if (slot == 36) {
            openBrowsers.remove(this);
            backAction.run();
            return;
        }

        // Plugin filter
        if (slot == 37) {
            if (click == ClickType.RIGHT && selectedPlugin != null) {
                selectedPlugin = null;
                page = 0;
                open(player);
                return;
            }
            // Open plugin picker sub-GUI
            openPluginPicker(player);
            return;
        }

        // Prev page
        if (slot == 38 && page > 0) {
            page--;
            open(player);
            return;
        }

        // Next page
        if (slot == 42) {
            page++;
            open(player);
            return;
        }

        // Text search
        if (slot == 39) {
            if (click == ClickType.RIGHT && filter != null) {
                filter = null;
                page   = 0;
                open(player);
                return;
            }
            guiManager.awaitInput(player, "Type a permission keyword to search (or 'clear'):", input -> {
                if (input.equalsIgnoreCase("clear")) filter = null;
                else filter = input.toLowerCase();
                page = 0;
                guiManager.openGui(player, this);
            });
            return;
        }

        // Manual entry
        if (slot == 44) {
            openBrowsers.remove(this);
            String prompt = deny
                    ? "Enter permission node to DENY (e.g. essentials.fly):"
                    : "Enter permission node to GRANT (e.g. essentials.home):";
            guiManager.awaitInput(player, prompt, perm -> {
                String node = perm.toLowerCase();
                if (deny && !node.startsWith("-")) node = "-" + node;
                callback.accept(node);
            });
            return;
        }

        // Permission item click — figure out which node was clicked
        var item = inventory.getItem(slot);
        if (item == null || item.getItemMeta() == null) return;
        String rawName = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (rawName == null || rawName.isBlank()) return;
        // Skip UI chrome items
        if (rawName.equals(" ") || rawName.startsWith("Page") || rawName.startsWith("Back")
                || rawName.startsWith("Next") || rawName.startsWith("Previous")
                || rawName.startsWith("Permission") || rawName.startsWith("Type")
                || rawName.startsWith("Mode") || rawName.startsWith("Filter")
                || rawName.startsWith("Search")) return;

        openBrowsers.remove(this);
        String node = rawName.toLowerCase();
        if (deny && !node.startsWith("-")) node = "-" + node;
        callback.accept(node);
    }

    // ── Plugin picker sub-GUI ─────────────────────────────────────────────────

    private void openPluginPicker(Player player) {
        List<String> plugins = getPluginPrefixes();
        int rows = Math.min(6, (int) Math.ceil((plugins.size() + 2) / 9.0) + 1);
        rows = Math.max(rows, 2);

        org.bukkit.inventory.Inventory picker = Bukkit.createInventory(null, rows * 9,
                MessageUtil.colorizeString("&1&l▐ &bSelect Plugin &1&l▐"));

        // Fill top/bottom border
        for (int i = 0; i < 9; i++) picker.setItem(i, filler());
        for (int i = (rows - 1) * 9; i < rows * 9; i++) picker.setItem(i, filler());

        // "All plugins" button at slot 0
        picker.setItem(0, item(Material.NETHER_STAR, "&a&lAll Plugins",
                "&7Show permissions from all plugins",
                "&7Count: &f" + getNodes().size()));

        int slot = 9;
        for (String pluginName : plugins) {
            long count = getNodes().stream().filter(n -> n.plugin.equals(pluginName)).count();
            Material mat = pluginMaterial(pluginName);
            picker.setItem(slot, item(mat,
                    "&e" + pluginName + ".*",
                    "&7Plugin: &f" + pluginName,
                    "&7Permissions: &a" + count,
                    "",
                    "&eClick to filter"));
            slot++;
            if (slot >= rows * 9 - 9) break; // don't overflow into bottom border
        }

        // Back button at last row first slot
        picker.setItem((rows - 1) * 9, item(Material.ARROW, "&7« Back"));

        // Register a one-off click handler via a temporary BaseGui wrapper
        PermissionBrowserGui self = this;
                            final int finalRows = rows;

        guiManager.openGui(player, new BaseGui(plugin, guiManager, player) {
            { this.inventory = picker; }

            @Override public void open(Player p) { p.openInventory(picker); }

            @Override
            public void handleClick(int s, ClickType c, Player p) {
                var itm = picker.getItem(s);
                if (itm == null || itm.getItemMeta() == null) return;
                String raw = org.bukkit.ChatColor.stripColor(itm.getItemMeta().getDisplayName());
                if (raw == null) return;

                if (raw.equals("« Back") || s == (finalRows - 1) * 9) {
                    guiManager.openGui(p, self);
                    return;
                }
                if (raw.equals("All Plugins")) {
                    self.selectedPlugin = null;
                    self.page = 0;
                    guiManager.openGui(p, self);
                    return;
                }
                // raw is "pluginname.*" → extract prefix
                String chosen = raw.replace(".*", "").toLowerCase();
                self.selectedPlugin = chosen;
                self.page = 0;
                guiManager.openGui(p, self);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Maps a plugin prefix to a representative material for visual variety. */
    private static Material pluginMaterial(String plugin) {
        return switch (plugin) {
            case "minecraft"    -> Material.GRASS_BLOCK;
            case "essentials",
                 "essentialsx"  -> Material.DIAMOND;
            case "worldguard"   -> Material.SHIELD;
            case "vault"        -> Material.GOLD_INGOT;
            case "permsscraft",
                 "permscraft"   -> Material.NETHER_STAR;
            case "worldedit"    -> Material.IRON_AXE;
            case "coreprotect"  -> Material.BOOK;
            case "multiverse"   -> Material.ENDER_EYE;
            case "skript"       -> Material.WRITABLE_BOOK;
            default             -> Material.PAPER;
        };
    }

    // ── PermNode record ───────────────────────────────────────────────────────

    private record PermNode(String name, String plugin) {}
}
