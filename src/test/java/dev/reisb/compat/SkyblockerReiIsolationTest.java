package dev.reisb.compat;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.common.plugins.REICommonPlugin;
import me.shedaniel.rei.api.common.plugins.REIPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkyblockerReiIsolationTest {
    @Test
    void isolationFiltersOnlySkyblockerFromMixedProviderAndCanBeReversed() {
        var skyblocker = new de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin();
        var retained = new OtherSkyblockerClientPlugin();
        List<REIClientPlugin> plugins = List.of(skyblocker, retained);
        var original = provider(REIClientPlugin.class, plugins);
        var enabled = new AtomicBoolean(true);
        var guarded = SkyblockerReiIsolation.guard(original, enabled::get);
        assertEquals(List.of(retained), guarded.provide());
        assertEquals(original.getPluginProviderName(), guarded.getPluginProviderName());
        assertSame(original.getPluginProviderClass(), guarded.getPluginProviderClass());
        assertSame(guarded, SkyblockerReiIsolation.guard(guarded, enabled::get));
        enabled.set(false);
        assertSame(plugins, guarded.provide());
        enabled.set(true);
        assertEquals(List.of(retained), guarded.provide());
    }

    @Test
    void commonEntrypointAndSubclassesAreAlsoIsolated() {
        var skyblocker = new de.hysky.skyblocker.compatibility.rei.SkyblockerREICommonPlugin();
        var retained = new REICommonPlugin() {};
        assertEquals(List.of(retained), SkyblockerReiIsolation.guard(provider(REICommonPlugin.class,
                List.of(skyblocker, retained)), () -> true).provide());
        assertTrue(SkyblockerReiIsolation.isSkyblockerPlugin(new ClientSubclass()));
        assertFalse(SkyblockerReiIsolation.isSkyblockerPlugin(new OtherSkyblockerClientPlugin()));
    }

    @Test
    void absentSkyblockerKeepsOtherPluginsWithoutResolvingOptionalClasses() {
        var retained = new OtherSkyblockerClientPlugin();
        assertEquals(List.of(retained), SkyblockerReiIsolation.guard(provider(REIClientPlugin.class,
                List.of(retained)), () -> true).provide());
    }

    static <P extends REIPlugin<?>> REIPluginProvider<P> provider(Class<P> type, Collection<P> plugins) {
        return new REIPluginProvider<>() {
            @Override public Collection<P> provide() { return plugins; }
            @Override public Class<P> getPluginProviderClass() { return type; }
            @Override public String getPluginProviderName() { return "Mixed provider fixture"; }
        };
    }

    private static final class OtherSkyblockerClientPlugin implements REIClientPlugin {}
    private static final class ClientSubclass extends de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin {}
}
