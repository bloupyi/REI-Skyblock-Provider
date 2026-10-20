package dev.reisb.rei;

import dev.reisb.data.Ingredient;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.LegacyText;
import java.util.ArrayList;
import java.util.List;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

public final class SkyblockCategory implements DisplayCategory<SkyblockDisplay> {
    private final SkyblockRecipe.Kind kind;

    public SkyblockCategory(SkyblockRecipe.Kind kind) { this.kind = kind; }

    @Override
    public CategoryIdentifier<? extends SkyblockDisplay> getCategoryIdentifier() { return SkyblockDisplay.category(kind); }

    @Override
    public Component getTitle() { return Component.translatable("category.rei_skyblock_provider." + kind.name().toLowerCase(java.util.Locale.ROOT)); }

    @Override
    public Renderer getIcon() {
        return EntryStacks.of(switch (kind) {
            case CRAFTING -> Items.CRAFTING_TABLE;
            case FORGE -> Items.ANVIL;
            case KAT -> Items.BONE;
            case NPC -> Items.EMERALD;
            case DROPS -> Items.ROTTEN_FLESH;
            case INFO -> Items.KNOWLEDGE_BOOK;
        });
    }

    @Override
    public int getDisplayHeight() { return kind == SkyblockRecipe.Kind.CRAFTING ? 72 : (kind == SkyblockRecipe.Kind.INFO ? 150 : kind == SkyblockRecipe.Kind.NPC || kind == SkyblockRecipe.Kind.KAT ? 140 : 112) + 26; }

    @Override
    public int getDisplayWidth(SkyblockDisplay display) { return 200; }

    @Override
    public List<Widget> setupDisplay(SkyblockDisplay display, Rectangle bounds) {
        var widgets = new ArrayList<Widget>();
        widgets.add(Widgets.createRecipeBase(bounds));
        var recipe = display.recipe();
        int x = bounds.getX();
        int y = bounds.getY();
        if (kind == SkyblockRecipe.Kind.INFO) {
            widgets.add(Widgets.createSlot(new Point(x + 8, y + 8)).entry(display.entry(recipe.output())).markOutput());
            var item = display.repository().source().items().get(recipe.output().id());
            label(widgets, x + 32, y + 12, dev.reisb.item.PetLevels.name(item), 160);
            label(widgets, x + 8, y + 34, item.id(), 182);
            label(widgets, x + 8, y + 48, "Hover item for complete lore", 182);
            var lore = dev.reisb.item.PetLevels.lore(item, dev.reisb.item.OfficialItemData.lore(item));
            for (int i = 0; i < Math.min(5, lore.size()); i++) label(widgets, x + 8, y + 64 + i * 10, lore.get(i), 182);
            var links = DisplayText.wikiLinks(item.info());
            for (int i = 0; i < links.size(); i++) {
                var link = links.get(i);
                widgets.add(Widgets.createButton(new Rectangle(x + 8 + (i % 2) * 94, y + 120 + (i / 2) * 21, 90, 18),
                        Component.literal(links.size() == 1 ? "Open wiki" : "Wiki " + (i + 1)))
                        .onClick(button -> {
                            var client = Minecraft.getInstance();
                            client.gui.setScreen(new WikiLinkScreen(client.gui.screen(), link));
                        }));
            }
            addPlanningButtons(widgets, display, bounds);
            return widgets;
        }
        if (kind == SkyblockRecipe.Kind.NPC || kind == SkyblockRecipe.Kind.KAT) {
            var costs = recipe.inputs().stream().filter(input -> !input.id().equals("SKYBLOCK_COIN")).toList();
            var coins = recipe.inputs().stream().filter(input -> input.id().equals("SKYBLOCK_COIN"))
                    .map(Ingredient::amount).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            label(widgets, x + 8, y + 8, kind == SkyblockRecipe.Kind.KAT ? "Pet upgrade" : "Purchase / exchange", 184);
            for (int i = 0; i < costs.size(); i++) {
                widgets.add(Widgets.createSlot(new Point(x + 12 + i % 4 * 18, y + 27 + i / 4 * 18))
                        .entry(display.entry(costs.get(i))).markInput());
            }
            if (costs.isEmpty()) label(widgets, x + 12, y + 36, "Coins only", 80);
            widgets.add(Widgets.createArrow(new Point(x + 100, y + 35)));
            widgets.add(Widgets.createSlot(new Point(x + 146, y + 35)).entry(display.entry(recipe.output())).markOutput());
            label(widgets, x + 8, y + 76, "§7Price: §6" + DisplayText.coins(coins), 184);
            if (!recipe.station().isEmpty()) {
                var station = display.repository().stacks().get(recipe.station());
                label(widgets, x + 8, y + 92, station == null ? recipe.station() : station.getHoverName().getString(), 184);
            }
            if (recipe.seconds() > 0) label(widgets, x + 8, y + 108, "Duration: " + duration(recipe.seconds()), 184);
            if (!recipe.extra().isEmpty()) label(widgets, x + 8, y + 124, recipe.extra(), 184);
            addPlanningButtons(widgets, display, bounds);
            return widgets;
        }
        int columns = kind == SkyblockRecipe.Kind.CRAFTING ? 3 : 4;
        int inputY = y + (kind == SkyblockRecipe.Kind.CRAFTING ? 4 : 14);
        for (int i = 0; i < recipe.inputs().size(); i++) {
            Ingredient ingredient = recipe.inputs().get(i);
            widgets.add(Widgets.createSlot(new Point(x + (kind == SkyblockRecipe.Kind.CRAFTING ? 8 : 12) + (i % columns) * 18, inputY + (i / columns) * 18))
                    .entries(List.of(display.entry(ingredient))).markInput());
        }
        int outputY = kind == SkyblockRecipe.Kind.CRAFTING ? inputY + 18 : y + 32;
        widgets.add(Widgets.createArrow(new Point(x + (kind == SkyblockRecipe.Kind.CRAFTING ? 65 : 100), outputY)));
        widgets.add(Widgets.createSlot(new Point(x + (kind == SkyblockRecipe.Kind.CRAFTING ? 90 : 146), outputY)).entry(display.entry(recipe.output())).markOutput());
        if (recipe.seconds() > 0) label(widgets, x + 96, y + 16, duration(recipe.seconds()), 98);
        if (kind == SkyblockRecipe.Kind.CRAFTING) {
            var station = display.repository().stacks().get(recipe.station());
            String stationName = station == null ? recipe.station() : station.getHoverName().getString();
            String details = stationName + (stationName.isEmpty() || recipe.extra().isEmpty() ? "" : " | ") + recipe.extra();
            if (!details.isEmpty()) label(widgets, x + 8, y + 60, details, bounds.width - 16);
            addPlanningButtons(widgets, display, bounds);
            return widgets;
        }
        if (!recipe.station().isEmpty()) {
            var station = display.repository().stacks().get(recipe.station());
            String name = station == null ? recipe.station() : station.getHoverName().getString();
            label(widgets, x + 8, y + 78, name, 184);
        }
        if (!recipe.extra().isEmpty()) label(widgets, x + 8, y + 94, recipe.extra(), 184);
        addPlanningButtons(widgets, display, bounds);
        return widgets;
    }

    private static void addPlanningButtons(List<Widget> widgets, SkyblockDisplay display, Rectangle bounds) {
        String id = display.recipe().output().id();
        boolean compact = display.recipe().kind() == SkyblockRecipe.Kind.CRAFTING;
        widgets.add(Widgets.createButton(new Rectangle(bounds.x + (compact ? 110 : 8), compact ? bounds.y + 7 : bounds.getMaxY() - 23, compact ? 82 : 89, 18), PlanningScreen.tr("tree"))
                .onClick(button -> {
                    var client = Minecraft.getInstance();
                    client.gui.setScreen(new PlanningScreen(client.gui.screen(), id, PlanningScreen.View.TREE));
                }));
        widgets.add(Widgets.createButton(new Rectangle(bounds.x + (compact ? 110 : 101), compact ? bounds.y + 29 : bounds.getMaxY() - 23, compact ? 82 : 89, 18), PlanningScreen.tr("requirements"))
                .onClick(button -> {
                    var client = Minecraft.getInstance();
                    client.gui.setScreen(new PlanningScreen(client.gui.screen(), id, PlanningScreen.View.REQUIREMENTS));
                }));
    }

    private static void label(List<Widget> widgets, int x, int y, String text, int width) {
        Component full = LegacyText.parse(text);
        var font = Minecraft.getInstance().font;
        boolean truncated = font.width(full) > width;
        String visible = truncated ? font.plainSubstrByWidth(full.getString(), Math.max(0, width - font.width("..."))) : full.getString();
        widgets.add(Widgets.createLabel(new Point(x, y), truncated ? Component.literal(visible + "...") : full)
                .leftAligned().tooltip(full));
    }

    private static String duration(long seconds) {
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        return (days > 0 ? days + "d " : "") + (hours > 0 ? hours + "h " : "")
                + (minutes > 0 ? minutes + "m " : "") + (seconds % 60 > 0 ? seconds % 60 + "s" : "");
    }
}
