package dev.reisb.mixin;

import dev.reisb.CompatibilityFixture;
import dev.reisb.util.SkyblockDetector;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SkyblockDetector.class)
public abstract class PriceContextMixin {
    @Inject(method = "isSkyblock", at = @At("HEAD"), cancellable = true)
    private static void reisb$mockPriceContext(Minecraft client, CallbackInfoReturnable<Boolean> callback) {
        if (CompatibilityFixture.priceContext) callback.setReturnValue(true);
    }
}
