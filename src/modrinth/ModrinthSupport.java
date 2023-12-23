package modrinth;

import Launcher.Plugin;
import Launcher.PluginContext;
import minecraft.MinecraftSupport;

public class ModrinthSupport extends Plugin {
    public final MinecraftSupport mcPlugin;
    public final ModrinthMarket market;

    public ModrinthSupport(final PluginContext context) {
        super(context);
        mcPlugin = (MinecraftSupport) getContext(MinecraftSupport.ID).getPlugin();
        addMarket(market = new ModrinthMarket(this, context.getIcon(), context.getPluginCache()));
    }

    @Override protected void onEnable() {
        mcPlugin.addScanListener(market.onScan);
        mcPlugin.addLaunchListener(market.onPreLaunch);

    }

    @Override protected void onDisable() {
        mcPlugin.removeScanListener(market.onScan);
        mcPlugin.removeLaunchListener(market.onPreLaunch);
    }
}