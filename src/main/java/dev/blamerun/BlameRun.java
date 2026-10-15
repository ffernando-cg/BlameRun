package dev.blamerun;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class BlameRun extends JavaPlugin {

    private Messages messages;
    private DeathStore deaths;
    private RunManager runs;
    private WorldReset worldReset;
    private Hud hud;
    private CatchUp catchUp;

    @Override
    public void onEnable() {
        loadConfig();
        messages = new Messages(this);
        messages.load();

        deaths = new DeathStore(this);
        deaths.load();
        runs = new RunManager(this);
        runs.load();
        worldReset = new WorldReset(this);
        worldReset.init();
        hud = new Hud(this);
        catchUp = new CatchUp(this);

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(worldReset, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> new CommandTree(this).register(event.registrar()));

        for (World world : getServer().getWorlds()) {
            runs.checkWorld(world);
        }

        // Timer + HUD every second; autosave every 30 s.
        getServer().getScheduler().runTaskTimer(this, () -> {
            runs.tick();
            hud.update();
        }, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            deaths.save();
            runs.save();
        }, 600L, 600L);
    }

    /** Copies config.yml if missing and adds keys introduced by newer versions without touching user values. */
    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    public void reload() {
        loadConfig();
        messages.load();
    }

    @Override
    public void onDisable() {
        if (worldReset != null) {
            worldReset.cancelPending();
        }
        if (runs != null) {
            runs.tick();
            runs.save();
        }
        if (deaths != null) {
            deaths.save();
        }
        if (hud != null) {
            hud.shutdown();
        }
    }

    public Messages messages() {
        return messages;
    }

    public DeathStore deaths() {
        return deaths;
    }

    public RunManager runs() {
        return runs;
    }

    public WorldReset worldReset() {
        return worldReset;
    }

    public Hud hud() {
        return hud;
    }

    public CatchUp catchUp() {
        return catchUp;
    }
}
