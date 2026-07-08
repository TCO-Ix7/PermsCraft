package ir.permscraft.brigadier;

import ir.permscraft.PermsCraft;
import org.bukkit.command.Command;

/**
 * Registers PermsCraft's /pc command with Brigadier for rich tab-completion.
 *
 * Uses the Commodore library (available via Paper's bundled Brigadier).
 * Falls back silently if Commodore is not present.
 *
 */
public final class PCBrigadier {

    private PCBrigadier() {}

    public static void register(PermsCraft plugin, Command command) {
        try {
            Class<?> commodoreProvider = Class.forName("me.lucko.commodore.CommodoreProvider");
            Class<?> commodoreClass    = Class.forName("me.lucko.commodore.Commodore");
            Object commodore = commodoreProvider
                    .getMethod("getCommodore", org.bukkit.plugin.Plugin.class)
                    .invoke(null, plugin);

            com.mojang.brigadier.tree.LiteralCommandNode<?> node = buildCommandNode();

            commodoreClass.getMethod(
                    "register",
                    Command.class,
                    com.mojang.brigadier.tree.LiteralCommandNode.class,
                    java.util.function.Predicate.class)
                .invoke(commodore, command, node,
                    (java.util.function.Predicate<org.bukkit.entity.Player>)
                        p -> p.hasPermission("permscraft.admin"));

            plugin.getLogger().info("[PermsCraft] Brigadier tab-complete registered.");
        } catch (ClassNotFoundException e) {
            // Commodore not present — PCCommand's own TabCompleter handles tab-complete
        } catch (Exception e) {
            plugin.getLogger().warning("[PermsCraft] Brigadier registration failed: " + e.getMessage());
        }
    }

    private static com.mojang.brigadier.tree.LiteralCommandNode<?> buildCommandNode() {
        var root = com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("pc");
        for (String sub : new String[]{
                "user", "group", "track", "timed", "log", "backup",
                "verbose", "check", "sync", "reload", "info", "debug",
                "search", "tree", "export", "import", "context",
                "bulkupdate", "migrate", "listgroups", "setup"}) {
            root.then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal(sub));
        }
        return root.build();
    }
}
