package dev.blamerun;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Translatable messages. Files are copied to plugins/BlameRun/lang/ so each server can edit them;
 * keys missing from an edited file (e.g. after an update) fall back to the copy bundled in the jar.
 */
public final class Messages {

    public static final List<String> BUNDLED = List.of("en", "es");
    private static final String FALLBACK = "en";

    private final BlameRun plugin;
    private YamlConfiguration lang = new YamlConfiguration();
    private String code = FALLBACK;
    private Component prefix = Component.empty();

    public Messages(BlameRun plugin) {
        this.plugin = plugin;
    }

    public void load() {
        for (String bundled : BUNDLED) {
            if (!new File(plugin.getDataFolder(), "lang/" + bundled + ".yml").exists()) {
                plugin.saveResource("lang/" + bundled + ".yml", false);
            }
        }
        code = plugin.getConfig().getString("language", FALLBACK).toLowerCase(Locale.ROOT);
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("lang/" + code + ".yml not found; falling back to English.");
            code = FALLBACK;
            file = new File(plugin.getDataFolder(), "lang/" + FALLBACK + ".yml");
        }
        lang = YamlConfiguration.loadConfiguration(file);

        String bundledCode = BUNDLED.contains(code) ? code : FALLBACK;
        try (InputStream in = plugin.getResource("lang/" + bundledCode + ".yml")) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    lang.setDefaults(YamlConfiguration.loadConfiguration(reader));
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled language file: " + e.getMessage());
        }
        prefix = Text.MM.deserialize(raw("prefix"));
    }

    public String code() {
        return code;
    }

    public String raw(String key) {
        return lang.getString(key, key);
    }

    public List<String> list(String key) {
        return lang.getStringList(key);
    }

    public Component get(String key, TagResolver... resolvers) {
        return Text.MM.deserialize(raw(key), resolvers);
    }

    public Component prefixed(String key, TagResolver... resolvers) {
        return prefix.append(get(key, resolvers));
    }

    public Component prefix() {
        return prefix;
    }

    public String split(Split split) {
        return raw("splits." + split.name());
    }

    /** Literal text (names, numbers): never parsed as MiniMessage. */
    public static TagResolver p(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** A fragment that is already MiniMessage (e.g. a coloured delta). */
    public static TagResolver mm(String name, String miniMessage) {
        return Placeholder.parsed(name, miniMessage);
    }

    public static TagResolver c(String name, Component component) {
        return Placeholder.component(name, component);
    }
}
