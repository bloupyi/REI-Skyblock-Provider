package dev.reisb.rei;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.util.SkyblockDetector;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class SkyblockTransferHandler implements TransferHandler {
    private final BooleanSupplier onSkyblock;
    private final Consumer<String> sendCommand;

    public SkyblockTransferHandler() {
        this(() -> ReiSkyblockProvider.config().enabled && SkyblockDetector.isSkyblock(Minecraft.getInstance()),
                command -> Minecraft.getInstance().getConnection().sendCommand(command));
    }

    public SkyblockTransferHandler(BooleanSupplier onSkyblock, Consumer<String> sendCommand) {
        this.onSkyblock = onSkyblock;
        this.sendCommand = sendCommand;
    }

    private static String target(Context context) {
        if (!(context.getDisplay() instanceof SkyblockDisplay display)) return "";
        var recipe = display.recipe();
        if (recipe.kind() != SkyblockRecipe.Kind.CRAFTING && recipe.kind() != SkyblockRecipe.Kind.INFO) return "";
        String id = recipe.output().id();
        if (!id.matches("[A-Z0-9_]+")) return "";
        return display.repository().recipes().getOrDefault(id, java.util.List.of()).stream()
                .anyMatch(candidate -> candidate.kind() == SkyblockRecipe.Kind.CRAFTING) ? id : "";
    }

    @Override
    public ApplicabilityResult checkApplicable(Context context) {
        if (target(context).isEmpty()) return ApplicabilityResult.createNotApplicable();
        return onSkyblock.getAsBoolean() ? ApplicabilityResult.createApplicable()
                : ApplicabilityResult.createApplicableWithError(Component.literal("Supercraft is available on Hypixel SkyBlock."));
    }

    @Override
    public Result handle(Context context) {
        String id = target(context);
        if (id.isEmpty()) return Result.createNotApplicable();
        if (!onSkyblock.getAsBoolean()) return Result.createFailed(Component.literal("Join Hypixel SkyBlock to open Supercraft."));
        if (context.isActuallyCrafting()) sendCommand.accept("viewrecipe " + id);
        return Result.createSuccessful().overrideTooltipRenderer((point, sink) -> sink.accept(
                me.shedaniel.rei.api.client.gui.widgets.Tooltip.from(point, java.util.List.of(
                        me.shedaniel.rei.api.client.gui.widgets.Tooltip.entry(Component.literal("Open Supercraft"))))))
                .blocksFurtherHandling(false);
    }
}
