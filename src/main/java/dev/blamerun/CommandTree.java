package dev.blamerun;

import static dev.blamerun.Messages.mm;
import static dev.blamerun.Messages.p;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class CommandTree {

    private final BlameRun plugin;

    public CommandTree(BlameRun plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    public void register(Commands commands) {
        commands.register(deaths().build(), "Per-player death counter", List.of());
        commands.register(run().build(), "Speedrun status, records and control", List.of("speedrun"));
        commands.register(catchup().build(), "Bring a player to you and give them comparable gear", List.of());
        commands.register(admin().build(), "BlameRun: reload and version", List.of("br"));
    }

    private static Predicate<CommandSourceStack> perm(String permission) {
        return src -> src.getSender().hasPermission(permission);
    }

    // ------------------------------------------------------------------ /blamerun

    private LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("blamerun")
                .executes(this::version)
                .then(Commands.literal("version").executes(this::version))
                .then(Commands.literal("reload")
                        .requires(perm(Perms.RELOAD))
                        .executes(ctx -> {
                            plugin.reload();
                            ctx.getSource().getSender().sendMessage(msg().prefixed("general.reloaded"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private int version(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(msg().prefixed("general.version",
                p("version", plugin.getPluginMeta().getVersion()), p("language", msg().code())));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /deaths

    private LiteralArgumentBuilder<CommandSourceStack> deaths() {
        SuggestionProvider<CommandSourceStack> knownNames = (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            plugin.deaths().names().stream()
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };

        return Commands.literal("deaths")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (sender instanceof Player pl) {
                        showOne(sender, plugin.deaths().touch(pl));
                    } else {
                        showTop(sender);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("top").executes(ctx -> {
                    showTop(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("hud")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (HudMode m : HudMode.values()) {
                                        b.suggest(m.name().toLowerCase(Locale.ROOT));
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getSender() instanceof Player pl)) {
                                        ctx.getSource().getSender().sendMessage(msg().prefixed("general.only-players"));
                                        return 0;
                                    }
                                    HudMode mode = HudMode.parse(StringArgumentType.getString(ctx, "mode"), null);
                                    if (mode == null) {
                                        pl.sendMessage(msg().prefixed("deaths.hud-usage"));
                                        return 0;
                                    }
                                    plugin.deaths().setHud(pl, mode);
                                    if (mode == HudMode.OFF) {
                                        pl.sendActionBar(Component.empty());
                                    }
                                    pl.sendMessage(msg().prefixed("deaths.hud-changed",
                                            p("mode", mode.name().toLowerCase(Locale.ROOT))));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("set")
                        .requires(perm(Perms.DEATHS_EDIT))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(knownNames)
                                .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getSender();
                                            Optional<DeathStore.Record> r = plugin.deaths().byName(StringArgumentType.getString(ctx, "player"));
                                            if (r.isEmpty()) {
                                                sender.sendMessage(msg().prefixed("general.unknown-player"));
                                                return 0;
                                            }
                                            int n = IntegerArgumentType.getInteger(ctx, "count");
                                            plugin.deaths().set(r.get(), n);
                                            plugin.deaths().save();
                                            sender.sendMessage(msg().prefixed("deaths.set-done",
                                                    p("player", r.get().name()), p("count", n)));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("reset")
                        .requires(perm(Perms.DEATHS_EDIT))
                        .executes(ctx -> {
                            ctx.getSource().getSender().sendMessage(msg().prefixed("deaths.reset-warning"));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("confirm").executes(ctx -> {
                            plugin.deaths().resetAll();
                            plugin.deaths().save();
                            ctx.getSource().getSender().sendMessage(msg().prefixed("deaths.reset-done"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(knownNames)
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Optional<DeathStore.Record> r = plugin.deaths().byName(StringArgumentType.getString(ctx, "player"));
                            if (r.isEmpty()) {
                                sender.sendMessage(msg().prefixed("general.no-history"));
                                return 0;
                            }
                            showOne(sender, r.get());
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private void showOne(CommandSender to, DeathStore.Record r) {
        List<DeathStore.Record> ranking = plugin.deaths().ranking();
        to.sendMessage(msg().prefix().append(Text.head(r.id())).append(msg().get("deaths.one",
                p("player", r.name()), p("count", r.deaths()),
                p("word", msg().raw(r.deaths() == 1 ? "death.singular" : "death.plural")),
                p("rank", ranking.indexOf(r) + 1), p("of", ranking.size()))));
    }

    private void showTop(CommandSender to) {
        List<DeathStore.Record> ranking = plugin.deaths().ranking();
        to.sendMessage(msg().get("deaths.top-title", p("total", plugin.deaths().total())));
        if (ranking.isEmpty()) {
            to.sendMessage(msg().get("hud.nobody-died"));
            return;
        }
        String[] medals = {"<gold>", "<gray>", "<#cd7f32>"};
        int i = 0;
        for (DeathStore.Record r : ranking.stream().limit(10).toList()) {
            String color = i < medals.length ? medals[i] : "<white>";
            to.sendMessage(Text.MM.deserialize(color + "#" + (i + 1) + " ").append(Text.head(r.id()))
                    .append(msg().get("deaths.top-line", p("player", r.name()), p("count", r.deaths()))));
            i++;
        }
    }

    // ------------------------------------------------------------------ /run

    private LiteralArgumentBuilder<CommandSourceStack> run() {
        return Commands.literal("run")
                .executes(this::stats)
                .then(Commands.literal("stats").executes(this::stats))
                .then(Commands.literal("seed").executes(this::seed))
                .then(Commands.literal("start")
                        .requires(perm(Perms.RUN_CONTROL))
                        .executes(ctx -> {
                            if (!plugin.runs().startCountdown()) {
                                ctx.getSource().getSender().sendMessage(msg().prefixed("run.cannot-start"));
                                return 0;
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reset")
                        .requires(perm(Perms.RUN_CONTROL))
                        .executes(ctx -> reset(ctx, null))
                        .then(Commands.argument("seed", StringArgumentType.greedyString())
                                .executes(ctx -> reset(ctx, WorldReset.parseSeed(StringArgumentType.getString(ctx, "seed"))))))
                .then(Commands.literal("cancel")
                        .requires(perm(Perms.RUN_CONTROL))
                        .executes(ctx -> {
                            boolean cancelled = plugin.worldReset().cancelPending();
                            ctx.getSource().getSender().sendMessage(msg().prefixed(
                                    cancelled ? "reset.cancelled" : "reset.nothing-to-cancel"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("new")
                        .requires(perm(Perms.RUN_CONTROL))
                        .executes(ctx -> {
                            // New attempt in the same world (useful with an external reset, or for testing).
                            RunManager run = plugin.runs();
                            run.newRun(plugin.worldReset().enabled() ? run.runWorld() : null);
                            for (World w : plugin.getServer().getWorlds()) {
                                run.checkWorld(w);
                            }
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private int reset(CommandContext<CommandSourceStack> ctx, Long seed) {
        if (!plugin.worldReset().enabled()) {
            ctx.getSource().getSender().sendMessage(msg().prefixed("reset.disabled"));
            return 0;
        }
        plugin.worldReset().reset(seed);
        return Command.SINGLE_SUCCESS;
    }

    private int seed(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        RunManager run = plugin.runs();
        boolean finished = run.state() == RunManager.State.ENDED || run.state() == RunManager.State.WON;
        if (!finished && !sender.hasPermission(Perms.SEED)
                && !plugin.getConfig().getBoolean("world-reset.public-seed", false)) {
            sender.sendMessage(msg().prefixed("reset.seed-hidden"));
            return 0;
        }
        sender.sendMessage(msg().prefixed("reset.seed", p("attempt", run.attempt()), p("seed", run.seed())));
        return Command.SINGLE_SUCCESS;
    }

    private int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSender to = ctx.getSource().getSender();
        RunManager run = plugin.runs();
        to.sendMessage(msg().get("run.summary.separator"));
        to.sendMessage(msg().get("run.stats.header", p("attempt", run.attempt()),
                p("state", msg().raw("states." + run.state().name())), p("time", Text.time(run.elapsedMs()))));
        to.sendMessage(msg().get("run.stats.splits", mm("splits", splitLine(run.splits(), run.bestSplits()))));
        to.sendMessage(msg().get("run.stats.record", p("time", Text.time(run.bestTimeMs())),
                p("team", run.bestTeam().isEmpty() ? "" : "(" + run.bestTeam() + ")"), p("wins", run.wins())));
        to.sendMessage(msg().get("run.stats.best-splits", mm("splits", splitLine(run.bestSplits(), Map.of()))));
        if (run.fastestDeathMs() >= 0) {
            to.sendMessage(msg().get("run.stats.shame", p("time", Text.time(run.fastestDeathMs())),
                    p("player", run.fastestDeathBy())));
        }
        to.sendMessage(msg().get("run.stats.deaths", p("total", plugin.deaths().total())));
        to.sendMessage(msg().get("run.summary.separator"));
        return Command.SINGLE_SUCCESS;
    }

    /** Splits with their delta against the best (when there is one and it isn't this same time). */
    private String splitLine(Map<Split, Long> times, Map<Split, Long> best) {
        if (times.isEmpty()) {
            return msg().raw("run.stats.none");
        }
        StringBuilder sb = new StringBuilder();
        String format = msg().raw("run.split-format");
        times.forEach((s, t) -> {
            sb.append(format.replace("<split>", Text.MM.escapeTags(msg().split(s))).replace("<time>", Text.time(t)));
            Long b = best.get(s);
            if (b != null && !b.equals(t)) {
                sb.append(" ").append(Text.delta(t - b));
            }
            sb.append("  ");
        });
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ /catchup

    private LiteralArgumentBuilder<CommandSourceStack> catchup() {
        return Commands.literal("catchup")
                .requires(perm(Perms.CATCHUP))
                .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(ctx -> catchUp(ctx, plugin.catchUp().defaultMode()))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    b.suggest("admin");
                                    b.suggest("average");
                                    return b.buildFuture();
                                })
                                .executes(ctx -> {
                                    CatchUp.Mode mode = CatchUp.Mode.parse(StringArgumentType.getString(ctx, "mode"), null);
                                    if (mode == null) {
                                        ctx.getSource().getSender().sendMessage(msg().prefixed("catchup.invalid-mode"));
                                        return 0;
                                    }
                                    return catchUp(ctx, mode);
                                })));
    }

    private int catchUp(CommandContext<CommandSourceStack> ctx, CatchUp.Mode mode) throws CommandSyntaxException {
        if (!(ctx.getSource().getSender() instanceof Player admin)) {
            ctx.getSource().getSender().sendMessage(msg().prefixed("general.only-players"));
            return 0;
        }
        List<Player> targets = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (targets.isEmpty()) {
            admin.sendMessage(msg().prefixed("catchup.not-found"));
            return 0;
        }
        plugin.catchUp().apply(admin, targets.getFirst(), mode);
        return Command.SINGLE_SUCCESS;
    }
}
