package dev.reisb.mixin.compat;

import dev.reisb.compat.ProviderCompatibility;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import me.shedaniel.rei.impl.common.plugins.PluginManagerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = PluginManagerImpl.class, remap = false)
public abstract class ReiPluginManagerMixin {
    @ModifyVariable(method = "registerPlugin", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private REIPluginProvider<?> reisb$filterProvider(REIPluginProvider<?> provider) {
        return ProviderCompatibility.filter(provider);
    }
}
