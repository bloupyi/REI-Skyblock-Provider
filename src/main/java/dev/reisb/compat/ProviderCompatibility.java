package dev.reisb.compat;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import me.shedaniel.rei.api.common.plugins.REIPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import org.slf4j.LoggerFactory;

public final class ProviderCompatibility {
    private static final Set<String> BLOCKED = Set.of(
            "de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin",
            "de.hysky.skyblocker.compatibility.rei.SkyblockerREICommonPlugin");
    private static final Set<String> detected = new ConcurrentSkipListSet<>();

    private ProviderCompatibility() {}

    public static <P extends REIPlugin<?>> REIPluginProvider<P> filter(REIPluginProvider<P> provider) {
        return new REIPluginProvider<>() {
            @Override
            public Collection<P> provide() {
                return provider.provide().stream().filter(plugin -> {
                    String name = plugin.getClass().getName();
                    if (!BLOCKED.contains(name)) return true;
                    if (detected.add(name)) LoggerFactory.getLogger("rei_skyblock_provider")
                            .info("Disabled competing SkyBlock REI plugin: {}", name);
                    return false;
                }).toList();
            }

            @Override
            public Class<P> getPluginProviderClass() { return provider.getPluginProviderClass(); }

            @Override
            public String getPluginProviderName() { return provider.getPluginProviderName(); }
        };
    }

    public static String status() { return "Disabled competing REI providers: " + detected; }
}
