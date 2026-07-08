package ir.permscraft.commands;

import ir.permscraft.PermsCraft;
import ir.permscraft.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class EditorCommand implements CommandExecutor {

    private final PermsCraft plugin;

    public EditorCommand(PermsCraft plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        if (!player.hasPermission("permscraft.admin")) {
            MessageUtil.send(player, "&cYou don't have permission to use the editor.");
            return true;
        }
        plugin.getGuiManager().openMain(player);
        return true;
    }
}
