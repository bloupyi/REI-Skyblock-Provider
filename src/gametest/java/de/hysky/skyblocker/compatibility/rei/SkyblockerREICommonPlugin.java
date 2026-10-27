package de.hysky.skyblocker.compatibility.rei;

public final class SkyblockerREICommonPlugin implements me.shedaniel.rei.api.common.plugins.REICommonPlugin {
    public static boolean registered;
    @Override
    public void registerItemComparators(me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry registry) {
        registered = true;
        if (!dev.reisb.CompatibilityFixture.integrationEnabled()) return;
        registry.registerGlobal((context, stack) -> {
            var tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            tag.remove(dev.reisb.item.SkyblockIds.MARKER + "_id");
            String id = dev.reisb.item.SkyblockIds.fromTag(tag);
            return id.isEmpty() ? 1 : id.hashCode();
        });
    }
}
