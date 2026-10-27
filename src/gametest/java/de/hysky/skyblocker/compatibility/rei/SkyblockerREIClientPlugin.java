package de.hysky.skyblocker.compatibility.rei;

public final class SkyblockerREIClientPlugin implements me.shedaniel.rei.api.client.plugins.REIClientPlugin {
    public static boolean registered;
    @Override
    public void registerCategories(me.shedaniel.rei.api.client.registry.category.CategoryRegistry registry) {
        if (dev.reisb.CompatibilityFixture.integrationEnabled()) registry.add(new dev.reisb.SkyblockerInfoFixture.Category());
    }

    @Override
    public void registerDisplays(me.shedaniel.rei.api.client.registry.display.DisplayRegistry registry) {
        if (dev.reisb.CompatibilityFixture.integrationEnabled() && registry.getGlobalDisplayGenerators().stream()
                .noneMatch(dev.reisb.SkyblockerInfoFixture.class::isInstance))
            registry.registerGlobalDisplayGenerator(new de.hysky.skyblocker.compatibility.rei.info.SkyblockInfoDisplayGenerator());
    }
    @Override
    public void registerEntries(me.shedaniel.rei.api.client.registry.entry.EntryRegistry registry) {
        registered = true;
        if (!dev.reisb.CompatibilityFixture.integrationEnabled()) return;
        // Mirrors the destructive operation in upstream; the optional mixin must intercept it.
        registry.removeEntryIf(entry -> true);
    }
}
