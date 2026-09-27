package dev.aegistitan;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class AegisTitan extends JavaPlugin {

    private Terrain terrain;
    private TitanTrident trident;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Items items = new Items(this);
        terrain = new Terrain(this);
        WallManager walls = new WallManager(this, items);
        TitanAxe axe = new TitanAxe(this, items, walls, terrain);
        trident = new TitanTrident(this, items, walls, terrain);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(walls, this);
        pm.registerEvents(terrain, this);
        pm.registerEvents(axe, this);
        pm.registerEvents(trident, this);
        walls.start();

        Commands commands = new Commands(this, items, walls, axe, trident);
        for (String name : List.of("getshield", "shieldsize", "getaxe", "axesize", "axecooldown", "gettrident", "tridentsize", "tridentcooldown", "aegistitan")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }
        getLogger().info("AegisTitan ready: /getshield, /shieldsize, /getaxe, /axesize, /axecooldown, /gettrident, /tridentsize, /tridentcooldown");
    }

    @Override
    public void onDisable() {
        if (trident != null) {
            trident.shutdown();
        }
        if (terrain != null) {
            terrain.restoreAll();
        }
    }
}
