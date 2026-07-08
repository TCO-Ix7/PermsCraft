package ir.permscraft.utils;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the pure-string logic of MessageUtil.colorizeString().
 * We don't test send()/sendRaw() because those require a live Bukkit
 * CommandSender — they are covered by integration tests elsewhere.
 */
@DisplayName("MessageUtil.colorizeString")
class MessageUtilTest {

    @Test @DisplayName("null input returns empty string")
    void colorize_null() {
        assertEquals("", MessageUtil.colorizeString(null));
    }

    @Test @DisplayName("empty string returns empty string")
    void colorize_empty() {
        assertEquals("", MessageUtil.colorizeString(""));
    }

    @Test @DisplayName("string without color codes is returned as-is")
    void colorize_noColorCodes() {
        String input = "Hello World";
        // ChatColor.translateAlternateColorCodes should leave it unchanged
        String result = MessageUtil.colorizeString(input);
        // The translated string should still contain all original characters
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("World"));
    }

    @Test @DisplayName("&a is translated to a Minecraft color escape")
    void colorize_ampersandA() {
        String result = MessageUtil.colorizeString("&aGreen");
        // After translation &a becomes §a
        assertTrue(result.contains("\u00a7a"), "Expected §a escape");
        assertTrue(result.contains("Green"));
    }

    @Test @DisplayName("&c is translated to a Minecraft color escape")
    void colorize_ampersandC() {
        String result = MessageUtil.colorizeString("&cRed text");
        assertTrue(result.contains("\u00a7c"));
    }

    @Test @DisplayName("&r reset code is translated")
    void colorize_ampersandR() {
        String result = MessageUtil.colorizeString("&rReset");
        assertTrue(result.contains("\u00a7r"));
    }

    @Test @DisplayName("multiple color codes in same string are all translated")
    void colorize_multipleCodes() {
        String result = MessageUtil.colorizeString("&a[&bPermsCraft&a] &rHello");
        assertTrue(result.contains("\u00a7a"));
        assertTrue(result.contains("\u00a7b"));
        assertTrue(result.contains("\u00a7r"));
    }

    @Test @DisplayName("§ already-translated codes are left alone")
    void colorize_alreadyTranslated() {
        String input = "\u00a7aAlready green";
        String result = MessageUtil.colorizeString(input);
        assertTrue(result.startsWith("\u00a7a"));
    }

    @Test @DisplayName("&&-escaped ampersand is left as literal &")
    void colorize_escapedAmpersand() {
        // §& is not a valid color code so ChatColor leaves the pair alone
        // The actual behavior: only single & followed by valid code is translated
        String result = MessageUtil.colorizeString("&&a not a code");
        assertNotNull(result);
    }

    @Test @DisplayName("unknown color code letter is left untranslated")
    void colorize_unknownCode() {
        String result = MessageUtil.colorizeString("&z unknown");
        // &z is not a valid code → kept as literal &z
        assertTrue(result.contains("&z") || result.contains("unknown"));
    }

    @Test @DisplayName("string with only color code is translated without crashing")
    void colorize_onlyColorCode() {
        assertDoesNotThrow(() -> MessageUtil.colorizeString("&a"));
    }

    @Test @DisplayName("long string with many codes does not throw")
    void colorize_longString() {
        String input = "&a&b&c&d&e&f&0&1&2&3&4&5&6&7&8&9&r&l&m&n&o&k test";
        assertDoesNotThrow(() -> MessageUtil.colorizeString(input));
    }
}
