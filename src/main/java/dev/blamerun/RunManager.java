package dev.blamerun;

import static dev.blamerun.Messages.mm;
import static dev.blamerun.Messages.p;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * Current attempt state and all-time records. Stored in plugins/BlameRun/run.yml.
 *
 * <p>Lifecycle: WAITING → (first movement or /run start) → RUNNING → ENDED (death)
 * or WON (dragon killed) → new world → WAITING with attempt + 1.
 */
public final class RunManager {

    public enum State {
        WAITING,
        COUNTDOWN,
        RUNNING,
        ENDED,
        WON
    }

    private final BlameRun plugin;
    private final File file;

    // Current attempt
    private int attempt = 1;
    private String worldKey;
    private String runWorld;
    private long seed;
    private State state = State.WAITING;
    private long elapsedMs;
    private long lastTick = System.currentTimeMillis();
    private String endedBy;
    private final Map<Split, Long> splits = new EnumMap<>(Split.class);
    private final Set<UUID> participants = new HashSet<>();

    // Records
    private long bestTimeMs = -1;
    private String bestTeam = "";
    private long fastestDeathMs = -1;
    private String fastestDeathBy = "";
    private int wins;
    private final Map<Split, Long> bestSplits = new EnumMap<>(Split.class);

    private BukkitTask countdownTask;

    public RunManager(BlameRun plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "run.yml");
    }

    private Messages msg() {
        return plugin.messages();
    }

    // ------------------------------------------------------------------ persistence

    public void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        attempt = y.getInt("attempt", 1);
        worldKey = y.getString("world-key");
        runWorld = y.getString("run-world");
        seed = y.getLong("seed");
        try {
            state = State.valueOf(y.getString("state", "WAITING"));
        } catch (IllegalArgumentException e) {
            state = State.WAITING;
        }
        if (state == State.COUNTDOWN) {
            state = State.WAITING;
        }
        elapsedMs = y.getLong("elapsed-ms");
        endedBy = y.getString("ended-by");
        readSplits(y.getConfigurationSection("splits"), splits);
        participants.clear();
        for (String s : y.getStringList("participants")) {
            try {
                participants.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // corrupt entry: skip it
            }
        }
        bestTimeMs = y.getLong("records.best-time-ms", -1);
        bestTeam = y.getString("records.best-team", "");
        fastestDeathMs = y.getLong("records.fastest-death-ms", -1);
        fastestDeathBy = y.getString("records.fastest-death-by", "");
        wins = y.getInt("records.wins");
        readSplits(y.getConfigurationSection("records.best-splits"), bestSplits);
    }

    private static void readSplits(ConfigurationSection section, Map<Split, Long> into) {
        into.clear();
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                into.put(Split.valueOf(key), section.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // split from an older version
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("attempt", attempt);
        y.set("world-key", worldKey);
        y.set("run-world", runWorld);
        y.set("seed", seed);
        y.set("state", state.name());
        y.set("elapsed-ms", elapsedMs);
        y.set("ended-by", endedBy);
        splits.forEach((k, v) -> y.set("splits." + k.name(), v));
        y.set("participants", participants.stream().map(UUID::toString).toList());
        y.set("records.best-time-ms", bestTimeMs);
        y.set("records.best-team", bestTeam);
        y.set("records.fastest-death-ms", fastestDeathMs);
        y.set("records.fastest-death-by", fastestDeathBy);
        y.set("records.wins", wins);
        bestSplits.forEach((k, v) -> y.set("records.best-splits." + k.name(), v));
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save run.yml", e);
        }
    }

    // ------------------------------------------------------------------ external reset detection

    public boolean isIgnored(World world) {
        String name = world.getName().toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("ignored-worlds").stream()
                .anyMatch(w -> w.toLowerCase(Locale.ROOT).equals(name));
    }

    /**
     * External reset mode only (another plugin deletes the world): an overworld whose UID/seed differs from
     * the stored one opens a new attempt. If the current attempt hasn't started yet, it just adopts the world.
     */
    public void checkWorld(World world) {
        if (plugin.worldReset().enabled() || world == null
                || world.getEnvironment() != World.Environment.NORMAL || isIgnored(world)) {
            return;
        }
        String key = world.getUID() + ":" + world.getSeed();
        if (key.equals(worldKey)) {
            return;
        }
        if (worldKey == null || isFresh()) {
            worldKey = key;
            seed = world.getSeed();
            save();
            return;
        }
        seed = world.getSeed();
        newRun(key);
    }

    /** External reset mode only: someone goes back to survival after an attempt ended by a death. */
    public void onSurvivalSeen() {
        if (!plugin.worldReset().enabled() && state == State.ENDED
                && plugin.getConfig().getBoolean("run.detect-reset-by-gamemode", true)) {
            newRun(worldKey);
        }
    }

    private boolean isFresh() {
        return state == State.WAITING && elapsedMs == 0 && splits.isEmpty();
    }

    public void newRun(String key) {
        cancelCountdown();
        attempt++;
        worldKey = key;
        state = State.WAITING;
        elapsedMs = 0;
        endedBy = null;
        splits.clear();
        participants.clear();
        Bukkit.getOnlinePlayers().forEach(pl -> participants.add(pl.getUniqueId()));
        save();

        boolean auto = plugin.getConfig().getBoolean("run.auto-start", true);
        Bukkit.getServer().showTitle(Title.title(
                msg().get("run.attempt-title", p("attempt", attempt)),
                msg().get(auto ? "run.subtitle-auto" : "run.subtitle-manual"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        playAll("block.beacon.activate", 1f);
        Bukkit.getServer().sendMessage(msg().prefixed("run.new-attempt",
                p("attempt", attempt), p("total", plugin.deaths().total())));
    }

    // ------------------------------------------------------------------ timer

    public void tick() {
        long now = System.currentTimeMillis();
        if (state == State.RUNNING && !Bukkit.getOnlinePlayers().isEmpty()) {
            elapsedMs += now - lastTick;
        }
        lastTick = now;
    }

    public void startTimer(String reason) {
        if (state != State.WAITING && state != State.COUNTDOWN) {
            return;
        }
        state = State.RUNNING;
        lastTick = System.currentTimeMillis();
        save();
        Bukkit.getServer().sendMessage(msg().prefixed("run.timer-started", p("reason", reason)));
        playAll("entity.player.levelup", 1.2f);
    }

    public boolean startCountdown() {
        if (state != State.WAITING) {
            return false;
        }
        int seconds = Math.max(1, plugin.getConfig().getInt("run.countdown-seconds", 5));
        state = State.COUNTDOWN;
        int[] remaining = {seconds};
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100));
            if (remaining[0] > 0) {
                String color = remaining[0] <= 3 ? "red" : "yellow";
                Bukkit.getServer().showTitle(Title.title(
                        Text.MM.deserialize("<" + color + "><bold>" + remaining[0] + "</bold></" + color + ">"),
                        msg().get("run.countdown-subtitle", p("attempt", attempt)), times));
                playAll("block.note_block.pling", remaining[0] <= 3 ? 1.5f : 1f);
                remaining[0]--;
                return;
            }
            Bukkit.getServer().showTitle(Title.title(msg().get("run.countdown-go"), Component.empty(), times));
            cancelCountdown();
            startTimer(msg().raw("run.reason-countdown"));
        }, 0L, 20L);
        return true;
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    // ------------------------------------------------------------------ game events

    public void reachSplit(Split split, Player player) {
        if (state != State.RUNNING || splits.containsKey(split)) {
            return;
        }
        long t = elapsedMs;
        splits.put(split, t);
        Long best = bestSplits.get(split);
        String comparison = best == null ? msg().raw("run.split-first-time") : Text.delta(t - best);
        if (best == null || t < best) {
            bestSplits.put(split, t);
        }
        save();
        Bukkit.getServer().sendMessage(msg().prefixed("run.split",
                p("split", msg().split(split)), p("time", Text.time(t)),
                mm("comparison", comparison), p("player", player.getName())));
        playAll("ui.toast.challenge_complete", 1f);
    }

    /** Records a death. If {@code endsRun} and the attempt was still alive, ends it and returns true. */
    public boolean onDeath(Player player, boolean endsRun) {
        participants.add(player.getUniqueId());
        if (!endsRun || state == State.ENDED || state == State.WON) {
            return false;
        }
        boolean wasRunning = state == State.RUNNING;
        cancelCountdown();
        tick();
        state = State.ENDED;
        endedBy = player.getName();

        boolean shameRecord = false;
        if (wasRunning && (fastestDeathMs < 0 || elapsedMs < fastestDeathMs)) {
            fastestDeathMs = elapsedMs;
            fastestDeathBy = player.getName();
            shameRecord = true;
        }
        save();

        if (plugin.getConfig().getBoolean("on-death.summary", true)) {
            sendSummary(shameRecord, wasRunning);
        }
        return true;
    }

    private void sendSummary(boolean shameRecord, boolean wasRunning) {
        var server = Bukkit.getServer();
        server.sendMessage(msg().get("run.summary.separator"));
        server.sendMessage(msg().get("run.summary.ended", p("attempt", attempt), p("player", endedBy)));
        server.sendMessage(wasRunning
                ? msg().get("run.summary.duration", p("time", Text.time(elapsedMs)))
                : msg().get("run.summary.not-started"));
        if (!splits.isEmpty()) {
            server.sendMessage(msg().get("run.summary.splits", mm("splits", splitList(splits))));
        } else if (wasRunning) {
            server.sendMessage(msg().get("run.summary.no-splits"));
        }
        if (shameRecord) {
            server.sendMessage(msg().get("run.summary.shame-record"));
        }
        if (bestTimeMs > 0) {
            server.sendMessage(msg().get("run.summary.record-to-beat", p("time", Text.time(bestTimeMs))));
        }
        server.sendMessage(msg().get("run.summary.separator"));
    }

    /** "Nether 05:12  Fortress 09:40" as MiniMessage, using the language's split format. */
    public String splitList(Map<Split, Long> times) {
        StringBuilder sb = new StringBuilder();
        String format = msg().raw("run.split-format");
        times.forEach((s, t) -> sb.append(format
                .replace("<split>", Text.MM.escapeTags(msg().split(s)))
                .replace("<time>", Text.time(t))).append("  "));
        return sb.toString().trim();
    }

    /** Returns true if this dragon kill won the attempt. */
    public boolean onWin(Collection<? extends Player> team) {
        if (state != State.RUNNING) {
            return false;
        }
        tick();
        state = State.WON;
        wins++;
        boolean record = bestTimeMs < 0 || elapsedMs < bestTimeMs;
        String names = String.join(", ", team.stream().map(Player::getName).toList());
        if (record) {
            bestTimeMs = elapsedMs;
            bestTeam = names;
        }
        save();

        Bukkit.getServer().showTitle(Title.title(
                msg().get("run.win.title"),
                msg().get(record ? "run.win.subtitle-record" : "run.win.subtitle", p("time", Text.time(elapsedMs))),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(6), Duration.ofSeconds(2))));
        playAll("ui.toast.challenge_complete", 1f);
        playAll("entity.ender_dragon.death", 1f);
        Bukkit.getServer().sendMessage(msg().prefixed("run.win.chat",
                p("attempt", attempt), p("time", Text.time(elapsedMs)),
                p("total", plugin.deaths().total()), p("team", names)));
        for (Player pl : team) {
            launchFireworks(pl);
        }
        return true;
    }

    private void launchFireworks(Player player) {
        Color[] colors = {Color.ORANGE, Color.PURPLE, Color.AQUA, Color.LIME, Color.RED};
        for (int i = 0; i < 4; i++) {
            final int n = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.getWorld().spawn(player.getLocation().add(0, 1, 0), Firework.class, fw -> {
                    FireworkMeta meta = fw.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder()
                            .with(FireworkEffect.Type.BALL_LARGE)
                            .withColor(colors[n % colors.length], colors[(n + 2) % colors.length])
                            .withFade(Color.WHITE)
                            .flicker(true).trail(true).build());
                    meta.setPower(1);
                    fw.setFireworkMeta(meta);
                });
            }, i * 15L);
        }
    }

    static void playAll(String key, float pitch) {
        Bukkit.getServer().playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, 1f, pitch));
    }

    // ------------------------------------------------------------------ queries

    /** Returns true if the player wasn't registered in this attempt yet. */
    public boolean addParticipant(UUID id) {
        return participants.add(id);
    }

    public void setRunWorld(String name, long seed) {
        this.runWorld = name;
        this.seed = seed;
        save();
    }

    public Split nextSplit() {
        for (Split s : Split.values()) {
            if (!splits.containsKey(s)) {
                return s;
            }
        }
        return null;
    }

    public int attempt() {
        return attempt;
    }

    public String runWorld() {
        return runWorld;
    }

    public long seed() {
        return seed;
    }

    public State state() {
        return state;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public String endedBy() {
        return endedBy;
    }

    public Map<Split, Long> splits() {
        return splits;
    }

    public long bestTimeMs() {
        return bestTimeMs;
    }

    public String bestTeam() {
        return bestTeam;
    }

    public long fastestDeathMs() {
        return fastestDeathMs;
    }

    public String fastestDeathBy() {
        return fastestDeathBy;
    }

    public int wins() {
        return wins;
    }

    public Map<Split, Long> bestSplits() {
        return bestSplits;
    }
}
