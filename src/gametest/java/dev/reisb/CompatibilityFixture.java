package dev.reisb;

import java.util.Collection;
import java.util.List;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;

public final class CompatibilityFixture implements REIPluginProvider<REIClientPlugin> {
    public static boolean retained;
    public static boolean priceContext;
    public static boolean skyblockerPresent = !System.getProperty("reisb.fixture.mode", "enabled").equals("absent");
    public static boolean skyblockerEnabled = !System.getProperty("reisb.fixture.mode", "enabled").equals("disabled");
    public static boolean integrationEnabled() { return skyblockerPresent && skyblockerEnabled; }

    @Override
    public Collection<REIClientPlugin> provide() {
        return List.of(new de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin(), new RetainedPlugin());
    }

    @Override
    public Class<REIClientPlugin> getPluginProviderClass() { return REIClientPlugin.class; }

    private static final class RetainedPlugin implements REIClientPlugin {
        @Override
        public void registerEntries(me.shedaniel.rei.api.client.registry.entry.EntryRegistry registry) { retained = true; }
    }
}
