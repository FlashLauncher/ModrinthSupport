package modrinth;

import Launcher.Plugin;
import Launcher.PluginContext;
import minecraft.MinecraftSupport;

public class ModrinthSupport extends Plugin {
    public final MinecraftSupport mcPlugin;

    public ModrinthSupport(final PluginContext context) {
        super(context);
        mcPlugin = (MinecraftSupport) getContext(MinecraftSupport.ID).getPlugin();
        addMarket(new ModrinthMarket(this, context.getIcon(), context.getPluginCache()));
    }
}