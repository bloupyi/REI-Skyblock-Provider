package dev.reisb.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface InventoryScreenAccessor {
    @Accessor("leftPos") int reisb$left();
    @Accessor("topPos") int reisb$top();
}
