package dev.blamerun;

import static dev.blamerun.Messages.p;

import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;

/** Everything on screen: boss bar, sidebar, tab list and action bar. */
public final class Hud {

    private static final String SIDEBAR_OBJECTIVE = "br_deaths";
    private static final String HEALTH_OBJECTIVE = "br_health";

    private final BlameRun plugin;
    private final BossBar bossBar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);

    public Hud(BlameRun plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    public void update() {
        FileConfiguration cfg = plugin.getConfig();
        RunManager run = plugin.runs();
        DeathStore deaths = plugin.deaths();
        int maxFaces = Math.max(1, cfg.getInt("hud.max-faces", 10));

        updateBossBar(run);
        updateScoreboard(cfg, run, deaths);

        Component header = msg().get("hud.tab-header", p("attempt", run.attempt()), p("time", Text.time(run.elapsedMs())));
        Component footer = Component.text()
                .append(msg().get("hud.tab-wall"))
                .append(faceRow(deaths.ranking().stream().limit(maxFaces).toList()))
                .append(msg().get("hud.tab-footer", p("record", Text.time(run.bestTimeMs())),
                        p("wins", run.wins()), p("total", deaths.total())))
                .build();

        List<DeathStore.Record> onlineRecords = Bukkit.getOnlinePlayers().stream()
                .map(pl -> deaths.touch(pl))
                .sorted(Comparator.comparingInt(DeathStore.Record::deaths).reversed())
                .limit(maxFaces)
                .toList();
        Component faces = faceRow(onlineRecords);
        Component text = Component.join(JoinConfiguration.separator(Text.MM.deserialize("  <dark_gray>|</dark_gray>  ")),
                onlineRecords.stream().map(r -> Text.MM.deserialize("<white><n></white> " + skull(r.deaths()),
                        p("n", r.name()))).toList());

        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (cfg.getBoolean("hud.bossbar", true)) {
                pl.showBossBar(bossBar);
            } else {
                pl.hideBossBar(bossBar);
            }
            if (cfg.getBoolean("hud.tablist", true)) {
                pl.sendPlayerListHeaderAndFooter(header, footer);
            }
            switch (deaths.touch(pl).hud()) {
                case FACES -> pl.sendActionBar(faces);
                case TEXT -> pl.sendActionBar(text);
                case OFF -> {
                }
            }
        }
    }

    /** A row of skin faces, each followed by its death count. */
    private Component faceRow(List<DeathStore.Record> records) {
        if (records.isEmpty()) {
            return msg().get("hud.nobody-died");
        }
        return Component.join(JoinConfiguration.separator(Component.text("   ")), records.stream()
                .map(r -> Component.text().append(Text.head(r.id())).append(Component.space())
                        .append(Text.MM.deserialize(skull(r.deaths()))).build())
                .toList());
    }

    private static String skull(int deaths) {
        String color = deaths == 0 ? "green" : deaths < 3 ? "yellow" : deaths < 6 ? "red" : "dark_red";
        return "<" + color + ">☠" + deaths + "</" + color + ">";
    }

    private void updateBossBar(RunManager run) {
        Component prefix = msg().get("hud.bossbar-prefix", p("attempt", run.attempt()));
        float progress;
        BossBar.Color color;
        Component body;
        int resetIn = plugin.worldReset().secondsLeft();
        if (resetIn >= 0) {
            bossBar.name(prefix.append(msg().get("hud.bossbar-resetting", p("seconds", resetIn))));
            bossBar.progress(1f);
            bossBar.color(BossBar.Color.YELLOW);
            return;
        }
        switch (run.state()) {
            case WAITING -> {
                body = msg().get(plugin.getConfig().getBoolean("run.auto-start", true)
                        ? "hud.bossbar-waiting-auto" : "hud.bossbar-waiting-manual");
                progress = 0f;
                color = BossBar.Color.BLUE;
            }
            case COUNTDOWN -> {
                body = msg().get("hud.bossbar-countdown");
                progress = 0f;
                color = BossBar.Color.YELLOW;
            }
            case RUNNING -> {
                Split next = run.nextSplit();
                long best = run.bestTimeMs();
                var b = Component.text().append(msg().get("hud.bossbar-time", p("time", Text.time(run.elapsedMs()))));
                b.append(next != null ? msg().get("hud.bossbar-next", p("split", msg().split(next)))
                        : msg().get("hud.bossbar-dragon"));
                if (best > 0) {
                    b.append(msg().get("hud.bossbar-record", p("time", Text.time(best))));
                    progress = Math.min(1f, run.elapsedMs() / (float) best);
                    color = run.elapsedMs() > best ? BossBar.Color.RED : BossBar.Color.GREEN;
                } else {
                    progress = run.splits().size() / (float) Split.values().length;
                    color = BossBar.Color.GREEN;
                }
                body = b.build();
            }
            case ENDED -> {
                body = msg().get("hud.bossbar-ended", p("player", String.valueOf(run.endedBy())),
                        p("time", Text.time(run.elapsedMs())));
                progress = 1f;
                color = BossBar.Color.RED;
            }
            case WON -> {
                body = msg().get("hud.bossbar-won", p("time", Text.time(run.elapsedMs())));
                progress = 1f;
                color = BossBar.Color.PURPLE;
            }
            default -> throw new IllegalStateException();
        }
        bossBar.name(prefix.append(body));
        bossBar.progress(progress);
        bossBar.color(color);
    }

    private void updateScoreboard(FileConfiguration cfg, RunManager run, DeathStore deaths) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();

        Objective sidebar = board.getObjective(SIDEBAR_OBJECTIVE);
        if (cfg.getBoolean("hud.sidebar", true)) {
            if (sidebar == null) {
                sidebar = board.registerNewObjective(SIDEBAR_OBJECTIVE, Criteria.DUMMY, Component.text("Deaths"));
            }
            sidebar.displayName(msg().get("hud.sidebar-title", p("attempt", run.attempt())));
            sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (DeathStore.Record r : deaths.ranking()) {
                sidebar.getScore(r.name()).setScore(r.deaths());
            }
        } else if (sidebar != null) {
            sidebar.unregister();
        }

        Objective health = board.getObjective(HEALTH_OBJECTIVE);
        if (cfg.getBoolean("hud.health-in-tab", true)) {
            if (health == null) {
                health = board.registerNewObjective(HEALTH_OBJECTIVE, Criteria.HEALTH,
                        Component.text("❤", NamedTextColor.RED), RenderType.HEARTS);
            }
            health.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        } else if (health != null) {
            health.unregister();
        }
    }

    public void shutdown() {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.hideBossBar(bossBar);
        }
    }
}
