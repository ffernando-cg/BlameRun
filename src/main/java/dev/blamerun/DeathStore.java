package dev.blamerun;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Deaths per player. Stored in plugins/BlameRun/deaths.yml, outside any world,
 * so it survives every world reset.
 */
public final class DeathStore {

    public static final class Record {
        final UUID id;
        String name;
        int deaths;
        HudMode hud;

        Record(UUID id, String name, int deaths, HudMode hud) {
            this.id = id;
            this.name = name;
            this.deaths = deaths;
            this.hud = hud;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public int deaths() {
            return deaths;
        }

        public HudMode hud() {
            return hud;
        }
    }

    private final BlameRun plugin;
    private final File file;
    private final Map<UUID, Record> records = new HashMap<>();
    private boolean dirty;

    public DeathStore(BlameRun plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "deaths.yml");
    }

    private HudMode defaultHud() {
        return HudMode.parse(plugin.getConfig().getString("hud.default-mode"), HudMode.FACES);
    }

    public void load() {
        records.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                records.put(id, new Record(id, s.getString("name", "?"), s.getInt("deaths"),
                        HudMode.parse(s.getString("hud"), defaultHud())));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in deaths.yml: " + key);
            }
        }
    }

    public void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Record r : records.values()) {
            String base = "players." + r.id;
            yaml.set(base + ".name", r.name);
            yaml.set(base + ".deaths", r.deaths);
            yaml.set(base + ".hud", r.hud.name());
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save deaths.yml", e);
        }
    }

    /** Returns (creating it if needed) the player's record and keeps their name up to date. */
    public Record touch(Player player) {
        Record r = records.get(player.getUniqueId());
        if (r == null) {
            r = new Record(player.getUniqueId(), player.getName(), 0, defaultHud());
            records.put(r.id, r);
            dirty = true;
        } else if (!r.name.equals(player.getName())) {
            r.name = player.getName();
            dirty = true;
        }
        return r;
    }

    public int increment(Player player) {
        Record r = touch(player);
        r.deaths++;
        dirty = true;
        return r.deaths;
    }

    public void set(Record r, int deaths) {
        r.deaths = Math.max(0, deaths);
        dirty = true;
    }

    public void setHud(Player player, HudMode mode) {
        touch(player).hud = mode;
        dirty = true;
    }

    public void resetAll() {
        records.values().forEach(r -> r.deaths = 0);
        dirty = true;
    }

    public Optional<Record> byName(String name) {
        return records.values().stream().filter(r -> r.name.equalsIgnoreCase(name)).findFirst();
    }

    /** Most deaths first; ties sorted by name. */
    public List<Record> ranking() {
        List<Record> list = new ArrayList<>(records.values());
        list.sort(Comparator.comparingInt(Record::deaths).reversed()
                .thenComparing(r -> r.name.toLowerCase(Locale.ROOT)));
        return list;
    }

    public List<String> names() {
        return records.values().stream().map(Record::name).sorted().toList();
    }

    public int total() {
        return records.values().stream().mapToInt(Record::deaths).sum();
    }
}
