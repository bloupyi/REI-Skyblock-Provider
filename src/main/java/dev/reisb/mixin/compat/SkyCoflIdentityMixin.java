package dev.reisb.mixin.compat;

import dev.reisb.compat.SkyCoflCompatibility;
import dev.reisb.item.SkyblockIds;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.coflnet.CoflModClient", remap = false)
public abstract class SkyCoflIdentityMixin {
    @Inject(method = "getIdFromStack", at = @At("HEAD"), cancellable = true, require = 0)
    private static void reisb$identifyVirtualItem(ItemStack stack, CallbackInfoReturnable<String> callback) {
        if (SkyblockIds.owned(stack)) callback.setReturnValue(SkyCoflCompatibility.key(stack));
    }
}
