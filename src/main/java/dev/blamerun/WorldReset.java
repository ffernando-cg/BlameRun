package dev.blamerun;

import static dev.blamerun.Messages.p;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Built-in world reset: every attempt is played in its own three worlds
 * (&lt;prefix&gt;_&lt;attempt&gt;, _nether and _the_end) with portals linked to each other.
 * The server's main world stays as a waiting room, because Paper can't unload it.
 */
public final class WorldReset implements Listener {

    private static final String NETHER_SUFFIX = "_nether";
    private static final String END_SUFFIX = "_the_end";

    private final BlameRun plugin;
    private BukkitTask pending;
    private int secondsLeft = -1;

    public WorldReset(BlameRun plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public boolean enabled() {
        return cfg().getBoolean("world-reset.enabled", true);
    }

    public boolean automatic() {
        return cfg().getBoolean("world-reset.automatic", true);
    }

    // ------------------------------------------------------------------ run worlds

    private String name() {
        return plugin.runs().runWorld();
    }

    public World overworld() {
        return name() == null ? null : Bukkit.getWorld(name());
    }

    public World nether() {
        return name() == null ? null : Bukkit.getWorld(name() + NETHER_SUFFIX);
    }

    public World end() {
        return name() == null ? null : Bukkit.getWorld(name() + END_SUFFIX);
    }

    public boolean isRunWorld(World world) {
        String base = name();
        if (base == null || world == null) {
            return false;
        }
        String n = world.getName();
        return n.equals(base) || n.equals(base + NETHER_SUFFIX) || n.equals(base + END_SUFFIX);
    }

    private String worldName(int attempt) {
        String prefix = cfg().getString("world-reset.world-prefix", "blamerun").toLowerCase(Locale.ROOT);
        return prefix + "_" + attempt;
    }

    /** On startup: loads the current attempt's worlds, or creates the first ones. */
    public void init() {
        if (!enabled()) {
            return;
        }
        RunManager runs = plugin.runs();
        if (runs.runWorld() != null) {
            createWorlds(runs.runWorld(), runs.seed());
            return;
        }
        String name = worldName(runs.attempt());
        long seed = pickSeed();
        createWorlds(name, seed);
        runs.setRunWorld(name, seed);
        plugin.getLogger().info("Created world for attempt #" + runs.attempt() + ": " + name + " (seed " + seed + ")");
    }

    private long pickSeed() {
        String configured = cfg().getString("world-reset.seed", "").trim();
        if (configured.isEmpty()) {
            return ThreadLocalRandom.current().nextLong();
        }
        return parseSeed(configured);
    }

    /** Same as vanilla: a number is used as-is; any other text by its hash. */
    public static long parseSeed(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return raw.trim().hashCode();
        }
    }

    private World createWorlds(String name, long seed) {
        boolean hardcore = cfg().getBoolean("world-reset.hardcore", true);
        Difficulty difficulty;
        try {
            difficulty = Difficulty.valueOf(cfg().getString("world-reset.difficulty", "HARD").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            difficulty = Difficulty.HARD;
        }
        World overworld = null;
        for (World.Environment env : List.of(World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END)) {
            String worldName = switch (env) {
                case NETHER -> name + NETHER_SUFFIX;
                case THE_END -> name + END_SUFFIX;
                default -> name;
            };
            World w = new WorldCreator(worldName).environment(env).seed(seed).hardcore(hardcore).createWorld();
            if (w == null) {
                plugin.getLogger().severe("Could not create world " + worldName);
                continue;
            }
            w.setDifficulty(difficulty);
            w.setHardcore(hardcore);
            if (env == World.Environment.NORMAL) {
                overworld = w;
            }
        }
        return overworld;
    }

    public Location spawn() {
        World w = overworld();
        if (w == null) {
            return Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        Location s = w.getSpawnLocation();
        Block top = w.getHighestBlockAt(s);
        return top.getLocation().add(0.5, 1, 0.5);
    }

    // ------------------------------------------------------------------ reset

    /** Countdown before an automatic reset, so players can read the summary. */
    public void scheduleReset(int seconds) {
        if (!enabled() || pending != null) {
            return;
        }
        secondsLeft = Math.max(0, seconds);
        pending = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (secondsLeft <= 0) {
                reset(null);
                return;
            }
            if (secondsLeft <= 5 || secondsLeft % 10 == 0) {
                Bukkit.getServer().sendMessage(plugin.messages().prefixed("reset.warning", p("seconds", secondsLeft)));
            }
            secondsLeft--;
        }, 20L, 20L);
    }

    public boolean cancelPending() {
        if (pending == null) {
            return false;
        }
        pending.cancel();
        pending = null;
        secondsLeft = -1;
        return true;
    }

    /** Seconds until the pending reset, or -1 if there is none. */
    public int secondsLeft() {
        return pending == null ? -1 : secondsLeft;
    }

    /** Creates the next attempt's worlds, moves everyone there and deletes the previous ones. */
    public void reset(Long forcedSeed) {
        cancelPending();
        RunManager runs = plugin.runs();
        String old = runs.runWorld();
        int next = runs.attempt() + 1;
        String name = worldName(next);
        long seed = forcedSeed != null ? forcedSeed : pickSeed();

        Bukkit.getServer().sendMessage(plugin.messages().prefixed("reset.generating", p("attempt", next)));
        deleteLeftovers(name);
        World overworld = createWorlds(name, seed);
        if (overworld == null) {
            plugin.getLogger().severe("Reset aborted: could not create " + name);
            return;
        }
        runs.setRunWorld(name, seed);

        Location spawn = spawn();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!runs.isIgnored(player.getWorld())) {
                prepare(player);
                player.teleport(spawn);
            }
        }
        runs.newRun(name);

        if (old != null && !old.equals(name)) {
            boolean delete = cfg().getBoolean("world-reset.delete-old-worlds", true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> unloadRun(old, delete), 40L);
        }
    }

    /** Turns the player into a fresh arrival: survival, empty inventory, no XP, no advancements. */
    public void prepare(Player player) {
        FileConfiguration c = cfg();
        player.setGameMode(GameMode.SURVIVAL);
        if (c.getBoolean("world-reset.clear-players.inventory", true)) {
            player.getInventory().clear();
            player.setItemOnCursor(null);
        }
        if (c.getBoolean("world-reset.clear-players.ender-chest", true)) {
            player.getEnderChest().clear();
        }
        if (c.getBoolean("world-reset.clear-players.experience", true)) {
            player.setLevel(0);
            player.setExp(0f);
            player.setTotalExperience(0);
        }
        if (c.getBoolean("world-reset.clear-players.effects", true)) {
            player.clearActivePotionEffects();
        }
        if (c.getBoolean("world-reset.clear-players.advancements", true)) {
            revokeAdvancements(player);
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setRespawnLocation(null);
    }

    private static void revokeAdvancements(Player player) {
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            AdvancementProgress progress = player.getAdvancementProgress(it.next());
            for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) {
                progress.revokeCriteria(criterion);
            }
        }
    }

    private void unloadRun(String base, boolean delete) {
        for (String n : List.of(base, base + NETHER_SUFFIX, base + END_SUFFIX)) {
            World w = Bukkit.getWorld(n);
            List<Path> paths = w != null ? List.of(w.getWorldPath()) : candidatePaths(n);
            if (w != null) {
                for (Player player : w.getPlayers()) {
                    player.teleport(spawn());
                }
                if (!Bukkit.unloadWorld(w, false)) {
                    plugin.getLogger().warning("Could not unload world " + n + "; it will not be deleted.");
                    continue;
                }
            }
            if (delete) {
                boolean loaded = w != null;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> paths.stream()
                        .filter(path -> loaded || looksLikeWorld(path))
                        .forEach(this::deleteFolder));
            }
        }
    }

    /** If a folder with the new world's name was left behind (failed delete), remove it before creating. */
    private void deleteLeftovers(String base) {
        for (String n : List.of(base, base + NETHER_SUFFIX, base + END_SUFFIX)) {
            if (Bukkit.getWorld(n) == null) {
                candidatePaths(n).stream().filter(WorldReset::looksLikeWorld).forEach(this::deleteFolder);
            }
        }
    }

    /**
     * Where an unloaded world's folder can be: on Paper 26 extra worlds live together
     * (world/dimensions/minecraft/&lt;name&gt;); on older versions, in the server root.
     */
    private static List<Path> candidatePaths(String name) {
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Set<Path> parents = new LinkedHashSet<>();
        for (World w : Bukkit.getWorlds()) {
            Path parent = w.getWorldPath().toAbsolutePath().normalize().getParent();
            if (parent != null) {
                parents.add(parent);
            }
        }
        parents.add(root);
        return parents.stream().map(parent -> parent.resolve(name)).toList();
    }

    /** Safety net: only folders that look like a Minecraft world are ever deleted. */
    private static boolean looksLikeWorld(Path path) {
        return Files.exists(path.resolve("level.dat")) || Files.isDirectory(path.resolve("region"))
                || Files.isDirectory(path.resolve("DIM-1")) || Files.isDirectory(path.resolve("DIM1"));
    }

    private void deleteFolder(Path path) {
        if (!Files.isDirectory(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not delete " + path, e);
        }
    }

    // ------------------------------------------------------------------ portals, respawn and join

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        World from = event.getFrom().getWorld();
        if (!enabled() || !isRunWorld(from)) {
            return;
        }
        Location origin = event.getFrom();
        switch (event.getCause()) {
            case NETHER_PORTAL -> {
                boolean toNether = from.getEnvironment() != World.Environment.NETHER;
                World target = toNether ? nether() : overworld();
                if (target == null) {
                    return;
                }
                double scale = toNether ? 1 / 8.0 : 8.0;
                event.setTo(new Location(target, origin.getX() * scale, origin.getY(), origin.getZ() * scale,
                        origin.getYaw(), origin.getPitch()));
            }
            case END_PORTAL -> {
                if (from.getEnvironment() == World.Environment.THE_END) {
                    Location bed = event.getPlayer().getRespawnLocation();
                    event.setTo(bed != null && isRunWorld(bed.getWorld()) ? bed : spawn());
                } else {
                    World end = end();
                    if (end == null) {
                        return;
                    }
                    buildEndPlatform(end);
                    event.setTo(new Location(end, 100.5, 49, 0.5, 90f, 0f));
                }
            }
            default -> {
            }
        }
    }

    /** Obsidian arrival platform in the End, like vanilla. */
    private static void buildEndPlatform(World end) {
        for (int x = 98; x <= 102; x++) {
            for (int z = -2; z <= 2; z++) {
                end.getBlockAt(x, 48, z).setType(Material.OBSIDIAN);
                for (int y = 49; y <= 51; y++) {
                    end.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (enabled() && overworld() != null && !isRunWorld(event.getRespawnLocation().getWorld())) {
            event.setRespawnLocation(spawn());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || overworld() == null || isRunWorld(player.getWorld())
                || plugin.runs().isIgnored(player.getWorld())) {
            return;
        }
        // Coming from outside the run (first join, or their world was deleted): they start fresh.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                prepare(player);
                player.teleport(spawn());
            }
        });
    }
}
