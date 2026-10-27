package dev.reisb.mixin.compat;

import dev.reisb.rei.ReiDiagnostics;
import java.util.function.Predicate;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep Skyblocker's additive registrations, but do not let it clear other providers' entries. */
@Pseudo
@Mixin(targets = "de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin", remap = false)
public abstract class SkyblockerEntryRegistryMixin {
    @Redirect(method = "registerEntries", at = @At(value = "INVOKE",
            target = "Lme/shedaniel/rei/api/client/registry/entry/EntryRegistry;removeEntryIf(Ljava/util/function/Predicate;)Z"), require = 0)
    private boolean reisb$preserveExistingEntries(EntryRegistry registry, Predicate<? extends EntryStack<?>> predicate) {
        ReiDiagnostics.log("Prevented Skyblocker's entry-list clear; its categories, generators and comparators remain active");
        return false;
    }
}
