package de.hysky.skyblocker.compatibility.rei;

public final class SkyblockerREIClientPlugin implements me.shedaniel.rei.api.client.plugins.REIClientPlugin {
    @Override
    public void registerEntries(me.shedaniel.rei.api.client.registry.entry.EntryRegistry registry) {
        throw new AssertionError("Competing provider was allowed to erase REI entries");
    }
}
