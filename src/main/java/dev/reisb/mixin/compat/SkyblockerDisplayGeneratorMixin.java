package dev.reisb.mixin.compat;

import dev.reisb.rei.ReiDiagnostics;
import java.util.List;
import java.util.Optional;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** REI retains global generators across reloads, including when their categories disappear. */
@Pseudo
@Mixin(targets = {"de.hysky.skyblocker.compatibility.rei.info.SkyblockInfoDisplayGenerator",
        "de.hysky.skyblocker.compatibility.rei.recipe.SkyblockRecipeDisplayGenerator"}, remap = false)
public abstract class SkyblockerDisplayGeneratorMixin {
    @Inject(method = {"getRecipeFor", "getUsageFor"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void reisb$disableIsolatedGenerator(EntryStack<?> entry, CallbackInfoReturnable<Optional<List<Display>>> callback) {
        if (dev.reisb.compat.SkyblockerReiIsolation.requested()) callback.setReturnValue(Optional.empty());
    }

    @Inject(method = {"getRecipeFor", "getUsageFor"}, at = @At("RETURN"), cancellable = true, require = 0)
    private void reisb$skipOrphanedDisplays(EntryStack<?> entry, CallbackInfoReturnable<Optional<List<Display>>> callback) {
        var result = callback.getReturnValue();
        if (result.isEmpty()) return;
        var available = result.get().stream().filter(display -> CategoryRegistry.getInstance()
                .tryGet(display.getCategoryIdentifier()).isPresent()).toList();
        if (available.size() != result.get().size()) {
            ReiDiagnostics.log("Skyblocker stale generator: skipped {} displays whose categories are no longer registered",
                    result.get().size() - available.size());
            callback.setReturnValue(Optional.of(available));
        }
    }
}
