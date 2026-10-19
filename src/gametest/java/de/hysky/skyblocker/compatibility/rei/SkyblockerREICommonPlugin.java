package de.hysky.skyblocker.compatibility.rei;

public final class SkyblockerREICommonPlugin implements me.shedaniel.rei.api.common.plugins.REICommonPlugin {
    @Override
    public void registerItemComparators(me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry registry) {
        throw new AssertionError("Competing common provider registered comparators");
    }
}
