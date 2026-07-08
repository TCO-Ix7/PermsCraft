package ir.permscraft.utils;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public class MessageUtil {

    private static final String PREFIX = "&8[&bPermsCraft&8] &r";

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(colorizeString(PREFIX + message));
    }

    public static void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(colorizeString(message));
    }

    public static String colorizeString(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
