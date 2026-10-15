package dev.blamerun;

import static dev.blamerun.Messages.c;
import static dev.blamerun.Messages.p;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.WorldLoadEvent;

public final class GameListener implements Listener {

    /** Vanilla advancements that mark splits. */
    private static final Map<String, Split> ADVANCEMENT_SPLITS = Map.of(
            "story/smelt_iron", Split.IRON,
            "story/enter_the_nether", Split.NETHER,
            "nether/find_fortress", Split.FORTRESS,
            "nether/obtain_blaze_rod", Split.BLAZE_ROD,
            "story/follow_ender_eye", Split.STRONGHOLD,
            "story/enter_the_end", Split.END);

    private static final Map<Material, Split> PICKUP_SPLITS = Map.of(
            Material.IRON_INGOT, Split.IRON,
            Material.BLAZE_ROD, Split.BLAZE_ROD,
            Material.ENDER_PEARL, Split.PEARL);

    private final BlameRun plugin;

    public GameListener(BlameRun plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    /** Worlds where the attempt is being played (the run worlds, or any non-ignored world with an external reset). */
    private boolean inRun(World world) {
        WorldReset reset = plugin.worldReset();
        return reset.enabled() ? reset.isRunWorld(world) : !plugin.runs().isIgnored(world);
    }

    // ------------------------------------------------------------------ deaths

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!inRun(player.getWorld())) {
            return;
        }
        int count = plugin.deaths().increment(player);
        plugin.deaths().save();

        long alive = Bukkit.getOnlinePlayers().stream()
                .filter(o -> !o.equals(player) && !o.isDead() && inRun(o.getWorld())
                        && (o.getGameMode() == GameMode.SURVIVAL || o.getGameMode() == GameMode.ADVENTURE))
                .count();
        boolean allMustDie = "all-dead".equalsIgnoreCase(plugin.getConfig().getString("run.ends-when", "first-death"));
        boolean endedRun = plugin.runs().onDeath(player, !allMustDie || alive == 0);

        if (plugin.getConfig().getBoolean("on-death.lightning", true)) {
            player.getWorld().strikeLightningEffect(player.getLocation());
        }

        List<String> phrases = msg().list("death.phrases");
        Component phrase = phrases.isEmpty()
                ? Component.empty()
                : Text.MM.deserialize(phrases.get(ThreadLocalRandom.current().nextInt(phrases.size())),
                        p("player", player.getName()));

        if (plugin.getConfig().getBoolean("on-death.title", true)) {
            Bukkit.getServer().showTitle(Title.title(
                    Component.text().append(Text.head(player.getUniqueId())).append(Component.space())
                            .append(msg().get("death.title", p("player", player.getName()))).build(),
                    phrase,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        }
        RunManager.playAll(endedRun ? "entity.wither.spawn" : "entity.player.hurt", endedRun ? 0.8f : 1f);

        Component cause = event.deathMessage();
        Component line = msg().prefixed("death.counter", p("player", player.getName()), p("count", count),
                p("word", msg().raw(count == 1 ? "death.singular" : "death.plural")));
        if (cause != null) {
            line = line.append(msg().get("death.cause", c("cause", cause.color(NamedTextColor.GRAY))));
        }
        Bukkit.getServer().sendMessage(line);
        if (allMustDie && !endedRun && alive > 0) {
            Bukkit.getServer().sendMessage(msg().prefixed("death.alive-left", p("alive", alive)));
        }

        if (!endedRun) {
            return;
        }
        WorldReset reset = plugin.worldReset();
        if (reset.enabled() && reset.automatic()) {
            reset.scheduleReset(plugin.getConfig().getInt("world-reset.delay-seconds", 10));
        }
        String command = plugin.getConfig().getString("on-death.command", "");
        if (command != null && !command.isBlank()) {
            long delay = Math.max(0, plugin.getConfig().getLong("on-death.command-delay-seconds", 8)) * 20L;
            String resolved = command.replace("{player}", player.getName());
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved), delay);
        }
    }

    // ------------------------------------------------------------------ attempt start / external reset

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.deaths().touch(player);
        plugin.runs().checkWorld(player.getWorld());

        RunManager run = plugin.runs();
        boolean newcomer = run.addParticipant(player.getUniqueId());
        long threshold = plugin.getConfig().getLong("catchup.notify-after-minutes", 3) * 60_000L;
        if (newcomer && run.state() == RunManager.State.RUNNING && run.elapsedMs() >= threshold
                && plugin.getConfig().getBoolean("catchup.notify-admins", true)) {
            Component button = msg().get("catchup.button")
                    .clickEvent(ClickEvent.runCommand("/catchup " + player.getName()))
                    .hoverEvent(HoverEvent.showText(msg().get("catchup.button-hover")));
            Component notice = msg().prefixed("catchup.newcomer-notice",
                    p("player", player.getName()), p("time", Text.time(run.elapsedMs()))).append(button);
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.hasPermission(Perms.NOTIFY) && !o.equals(player)) {
                    o.sendMessage(notice);
                }
            }
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.runs().checkWorld(event.getWorld());
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.SPECTATOR && event.getNewGameMode() == GameMode.SURVIVAL) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.runs().onSurvivalSeen());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        RunManager run = plugin.runs();
        Player player = event.getPlayer();
        if (run.state() == RunManager.State.COUNTDOWN && inRun(player.getWorld())) {
            // Frozen during the countdown (they can still look around).
            Location frozen = event.getFrom().clone();
            frozen.setYaw(event.getTo().getYaw());
            frozen.setPitch(event.getTo().getPitch());
            event.setTo(frozen);
            return;
        }
        if (run.state() == RunManager.State.WAITING
                && plugin.getConfig().getBoolean("run.auto-start", true)
                && player.getGameMode() == GameMode.SURVIVAL
                && inRun(player.getWorld())) {
            run.startTimer(msg().raw("run.reason-first-step").replace("<player>", player.getName()));
        }
    }

    // ------------------------------------------------------------------ splits

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (!inRun(world)) {
            return;
        }
        switch (world.getEnvironment()) {
            case NETHER -> plugin.runs().reachSplit(Split.NETHER, player);
            case THE_END -> plugin.runs().reachSplit(Split.END, player);
            case NORMAL -> plugin.runs().checkWorld(world);
            default -> {
            }
        }
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Split split = ADVANCEMENT_SPLITS.get(event.getAdvancement().getKey().getKey());
        if (split != null && inRun(event.getPlayer().getWorld())) {
            plugin.runs().reachSplit(split, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && inRun(player.getWorld())) {
            Split split = PICKUP_SPLITS.get(event.getItem().getItemStack().getType());
            if (split != null) {
                plugin.runs().reachSplit(split, player);
            }
        }
    }

    @EventHandler
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        if (event.getItemType() == Material.IRON_INGOT && inRun(event.getPlayer().getWorld())) {
            plugin.runs().reachSplit(Split.IRON, event.getPlayer());
        }
    }

    // ------------------------------------------------------------------ win

    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !inRun(dragon.getWorld())) {
            return;
        }
        List<Player> team = dragon.getWorld().getPlayers().stream()
                .filter(pl -> pl.getGameMode() == GameMode.SURVIVAL)
                .map(pl -> (Player) pl)
                .toList();
        boolean won = plugin.runs().onWin(team.isEmpty() ? List.copyOf(Bukkit.getOnlinePlayers()) : team);
        WorldReset reset = plugin.worldReset();
        if (won && reset.enabled() && plugin.getConfig().getBoolean("world-reset.reset-after-win", false)) {
            reset.scheduleReset(plugin.getConfig().getInt("world-reset.seconds-after-win", 60));
        }
    }
}
