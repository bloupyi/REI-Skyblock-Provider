package dev.reisb.compat;

import dev.reisb.ReiSkyblockProvider;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.BooleanSupplier;
import me.shedaniel.rei.api.common.plugins.REIPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;

/** Optional runtime isolation of Skyblocker's REI entrypoints, without linking against Skyblocker. */
public final class SkyblockerReiIsolation {
    private static final Set<String> TARGETS = Set.of(
            "de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin",
            "de.hysky.skyblocker.compatibility.rei.SkyblockerREICommonPlugin");
    private static final Set<String> observed = new ConcurrentSkipListSet<>();

    private SkyblockerReiIsolation() {}

    public static boolean requested() {
        var config = ReiSkyblockProvider.config();
        return config.enabled && config.disableSkyblockerReiIntegration;
    }

    public static <P extends REIPlugin<?>> REIPluginProvider<P> guard(REIPluginProvider<P> provider) {
        return guard(provider, SkyblockerReiIsolation::requested);
    }

    static <P extends REIPlugin<?>> REIPluginProvider<P> guard(REIPluginProvider<P> provider, BooleanSupplier isolation) {
        return provider instanceof GuardedProvider<?> ? provider : new GuardedProvider<>(provider, isolation);
    }

    // Inspect only types already loaded by Fabric. No Class.forName, private-field writes or optional-mod initialization.
    static boolean isSkyblockerPlugin(Object plugin) {
        for (Class<?> type = plugin.getClass(); type != null; type = type.getSuperclass()) {
            if (TARGETS.contains(type.getName())) return true;
        }
        return false;
    }

    public static String status() { return "Skyblocker REI isolation=" + requested() + "; isolated entrypoints observed=" + observed; }

    private record GuardedProvider<P extends REIPlugin<?>>(REIPluginProvider<P> delegate,
                                                          BooleanSupplier isolation) implements REIPluginProvider<P> {
        @Override
        public Collection<P> provide() {
            var plugins = delegate.provide();
            if (!isolation.getAsBoolean()) return plugins;
            return plugins.stream().filter(plugin -> {
                if (!isSkyblockerPlugin(plugin)) return true;
                if (observed.add(plugin.getClass().getName())) ReiSkyblockProvider.LOGGER.info(
                        "Isolated Skyblocker REI entrypoint {} (provider option disableSkyblockerReiIntegration=true)", plugin.getClass().getName());
                return false;
            }).toList();
        }

        @Override public Class<P> getPluginProviderClass() { return delegate.getPluginProviderClass(); }
        @Override public String getPluginProviderName() { return delegate.getPluginProviderName(); }
    }
}
