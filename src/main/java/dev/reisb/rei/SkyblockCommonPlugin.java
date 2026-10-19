package dev.reisb.rei;

import dev.reisb.item.SkyblockIds;
import me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry;
import me.shedaniel.rei.api.common.plugins.REICommonPlugin;

public final class SkyblockCommonPlugin implements REICommonPlugin {
    @Override
    public void registerItemComparators(ItemComparatorRegistry registry) {
        registry.registerGlobal((context, stack) -> {
            String id = SkyblockIds.of(stack);
            if (id.isEmpty()) return 1L;
            long hash = 0xcbf29ce484222325L;
            for (int i = 0; i < id.length(); i++) hash = (hash ^ id.charAt(i)) * 0x100000001b3L;
            return hash;
        });
    }
}
