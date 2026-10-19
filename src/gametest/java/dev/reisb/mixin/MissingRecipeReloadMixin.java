package dev.reisb.mixin;

import dev.reisb.RecipeReloadSuppression;
import me.shedaniel.rei.api.common.registry.ReloadStage;
import net.minecraft.core.RegistryAccess;
import org.apache.commons.lang3.mutable.MutableLong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = me.shedaniel.rei.RoughlyEnoughItemsCoreClient.class, remap = false)
public class MissingRecipeReloadMixin {
    @Inject(method = "reloadPlugins(Lorg/apache/commons/lang3/mutable/MutableLong;Lme/shedaniel/rei/api/common/registry/ReloadStage;Lnet/minecraft/core/RegistryAccess;)V",
            at = @At("HEAD"), cancellable = true)
    private static void suppressRecipeStages(MutableLong lastReload, ReloadStage stage, RegistryAccess access, CallbackInfo callback) {
        if (!RecipeReloadSuppression.enabled) return;
        if (stage != null) callback.cancel();
        else {
            RecipeReloadSuppression.recovered = true;
            RecipeReloadSuppression.enabled = false;
        }
    }
}
