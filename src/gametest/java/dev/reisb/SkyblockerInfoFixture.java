package dev.reisb;

import java.util.List;
import java.util.Optional;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Model of upstream's info response, which remains valid while its recipe repository is empty. */
public class SkyblockerInfoFixture implements DynamicDisplayGenerator<SkyblockerInfoFixture.Info> {
    public static final CategoryIdentifier<Info> CATEGORY = CategoryIdentifier.of("skyblocker", "skyblock_info");

    @Override
    public Optional<List<Info>> getRecipeFor(EntryStack<?> entry) {
        if (!(entry.getValue() instanceof ItemStack stack) || dev.reisb.item.SkyblockIds.of(stack).isEmpty()) return Optional.empty();
        return Optional.of(List.of(new Info(stack.copyWithCount(1))));
    }

    @Override
    public Optional<List<Info>> getUsageFor(EntryStack<?> entry) { return getRecipeFor(entry); }

    public record Info(ItemStack stack) implements Display {
        @Override public List<EntryIngredient> getInputEntries() { return List.of(); }
        @Override public List<EntryIngredient> getOutputEntries() { return List.of(EntryIngredient.of(EntryStacks.of(stack))); }
        @Override public CategoryIdentifier<?> getCategoryIdentifier() { return CATEGORY; }
        @Override public DisplaySerializer<? extends Display> getSerializer() { return null; }
        @Override public Optional<net.minecraft.resources.Identifier> getDisplayLocation() { return Optional.empty(); }
    }

    public static final class Category implements DisplayCategory<Info> {
        @Override public CategoryIdentifier<Info> getCategoryIdentifier() { return CATEGORY; }
        @Override public Component getTitle() { return Component.literal("Skyblock Item Info (fixture)"); }
        @Override public Renderer getIcon() { return EntryStacks.of(Items.KNOWLEDGE_BOOK); }
        @Override public List<Widget> setupDisplay(Info display, Rectangle bounds) { return List.of(Widgets.createRecipeBase(bounds)); }
    }
}
