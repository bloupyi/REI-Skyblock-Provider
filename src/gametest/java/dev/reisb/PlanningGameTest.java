package dev.reisb;

import dev.reisb.planning.PlanningService;
import dev.reisb.rei.PlanningScreen;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

public final class PlanningGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            try { run(context); }
            catch (Throwable error) { ReiSkyblockProvider.LOGGER.error("Planning integration assertions failed", error); throw error; }
        }
    }

    private static void run(ClientGameTestContext context) {
        context.runOnClient(client -> {
            try { Files.writeString(FabricLoader.getInstance().getConfigDir().resolve(ReiSkyblockProvider.ID + ".json"), "{\"onlyOnHypixelSkyblock\":false,\"autoUpdateRepository\":false,\"enableProfileApi\":false}"); }
            catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            ReiSkyblockProvider.reload();
        });
        context.waitFor(client -> dev.reisb.rei.SkyblockClientPlugin.isSynchronized()
                && ReiSkyblockProvider.repository().stacks().containsKey("ENCHANTED_DIAMOND"), 6000);
        context.runOnClient(client -> {
            PlanningService.account().goals.clear();
            PlanningService.account().choices.clear();
            PlanningService.account().stopped.clear();
            PlanningService.account().excluded.clear();
            client.player.getInventory().clearContent();
            client.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 32));
            client.gui.setScreen(new InventoryScreen(client.player));
            var display = new dev.reisb.rei.SkyblockDisplayGenerator().getRecipeFor(
                    me.shedaniel.rei.api.common.util.EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("ENCHANTED_DIAMOND")))
                    .orElseThrow().stream().filter(recipe -> recipe.recipe().kind() == dev.reisb.data.SkyblockRecipe.Kind.CRAFTING).findFirst().orElseThrow();
            var category = new dev.reisb.rei.SkyblockCategory(dev.reisb.data.SkyblockRecipe.Kind.CRAFTING);
            category.setupDisplay(display, new me.shedaniel.math.Rectangle(0, 0, 200, category.getDisplayHeight())).stream()
                    .filter(widget -> widget instanceof me.shedaniel.rei.api.client.gui.widgets.Button)
                    .map(widget -> (me.shedaniel.rei.api.client.gui.widgets.Button) widget).findFirst().orElseThrow().onClick();
            require(client.gui.screen() instanceof PlanningScreen, "Recipe planning button did not open the planner");
            require(missing("DIAMOND") == 128, "Inventory was not deducted from the recursive plan");
            var screen = (PlanningScreen) client.gui.screen();
            screen.children().stream().filter(widget -> widget instanceof EditBox).map(widget -> (EditBox) widget).findFirst().orElseThrow().setValue("2");
            press("apply");
            require(missing("DIAMOND") == 288, "Quantity change did not recalculate batches");
            press("add_goal");
            require(((PlanningScreen) client.gui.screen()).view() == PlanningScreen.View.SHOPPING, "Add goal did not open the shopping list");
            require(PlanningService.account().goals.get("ENCHANTED_DIAMOND").intValueExact() == 2, "Shopping goal was not stored");
            press("materials");
            PlanningService.toggleReserve("DIAMOND");
        });
        context.waitFor(client -> missing("DIAMOND") == 320, 300);
        context.runOnClient(client -> PlanningService.toggleReserve("DIAMOND"));
        context.waitFor(client -> missing("DIAMOND") == 288, 300);
        context.takeScreenshot("planning-shopping-inventory");
        context.runOnClient(client -> {
            try {
                var directory = Files.createTempDirectory(FabricLoader.getInstance().getGameDir(), "profile-api-test-");
                Files.writeString(directory.resolve("profile-api-key.txt"), "");
                dev.reisb.planning.ProfileApiSync.initialize(directory);
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            ReiSkyblockProvider.config().profileApiUrl = "https://example.invalid/profiles?uuid={uuid}";
            ReiSkyblockProvider.config().profileApiAuthHeader = "API-Key";
            ReiSkyblockProvider.config().enableProfileApi = true;
            CompatibilityFixture.priceContext = true;
            client.getConnection().getOnlinePlayers().iterator().next().setTabListDisplayName(Component.literal("Profile: REISB_TEST_APPLE"));
        });
        context.waitFor(client -> PlanningService.profileName().equals("REISB_TEST_APPLE"), 300);
        context.waitFor(client -> dev.reisb.planning.ProfileApiSync.status().equals("missing_key"), 300);
        context.runOnClient(client -> {
            ReiSkyblockProvider.config().enableProfileApi = false;
            ReiSkyblockProvider.config().profileApiUrl = new dev.reisb.config.ProviderConfig().profileApiUrl;
            ReiSkyblockProvider.config().profileApiAuthHeader = new dev.reisb.config.ProviderConfig().profileApiAuthHeader;
            dev.reisb.planning.ProfileApiSync.initialize(FabricLoader.getInstance().getConfigDir().resolve(ReiSkyblockProvider.ID));
        });
        context.runOnClient(client -> client.gui.setScreen(sackScreen(100)));
        context.waitFor(client -> PlanningService.sackObservations().containsKey("DIAMOND")
                && PlanningService.sackObservations().get("DIAMOND").amount().intValueExact() == 100, 300);
        context.runOnClient(client -> client.gui.setScreen(sackScreen(100)));
        context.waitTicks(25);
        context.runOnClient(client -> {
            require(PlanningService.stock(true).sacks().get("DIAMOND").intValueExact() == 100, "Sack stock was counted twice");
            client.gui.setScreen(new PlanningScreen(new InventoryScreen(client.player), "ENCHANTED_DIAMOND", PlanningScreen.View.SHOPPING));
            require(missing("DIAMOND") == 188, "Sack stock was not deducted from shopping materials");
        });
        context.takeScreenshot("planning-shopping-sacks");
        context.runOnClient(client -> {
            var menu = sackScreen(77);
            var icon = menu.getMenu().getContainer().getItem(0);
            icon.remove(DataComponents.CUSTOM_DATA);
            icon.set(DataComponents.CUSTOM_NAME, Component.literal("Enchanted Diamond"));
            client.gui.setScreen(new ContainerScreen(menu.getMenu(), client.player.getInventory(), Component.literal("Enchanted Mining Sack (1/2)")));
        });
        context.waitFor(client -> PlanningService.sackObservations().containsKey("ENCHANTED_DIAMOND")
                && PlanningService.sackObservations().get("ENCHANTED_DIAMOND").amount().intValueExact() == 77, 300);
        context.runOnClient(client -> require(PlanningService.sackObservations().get("DIAMOND").amount().intValueExact() == 100,
                "Enchanted sack icon overwrote the raw resource stock"));
        context.runOnClient(client -> client.gui.setScreen(new PlanningScreen(new InventoryScreen(client.player), "ENCHANTED_DIAMOND", PlanningScreen.View.TREE)));
        context.takeScreenshot("planning-craft-tree");
        context.runOnClient(client -> client.getConnection().getOnlinePlayers().iterator().next().setTabListDisplayName(Component.literal("Profile: REISB_TEST_BANANA")));
        context.waitFor(client -> PlanningService.profileName().equals("REISB_TEST_BANANA"), 300);
        context.runOnClient(client -> {
            require(PlanningService.sackObservations().get("DIAMOND") == null || PlanningService.sackObservations().get("DIAMOND").amount().signum() == 0, "Sack stock leaked between profiles");
            client.gui.setScreen(sackScreen(0));
        });
        context.waitFor(client -> PlanningService.sackObservations().containsKey("DIAMOND") && PlanningService.sackObservations().get("DIAMOND").amount().signum() == 0, 300);
        context.runOnClient(client -> {
            var menu = sackScreen(0);
            menu.getMenu().getContainer().getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Diamond IV"));
            client.gui.setScreen(new ContainerScreen(menu.getMenu(), client.player.getInventory(), Component.literal("Mining Collections")));
        });
        context.waitFor(client -> PlanningService.progress().containsKey("COLLECTION:DIAMOND"), 300);
        context.runOnClient(client -> {
            var menu = sackScreen(0);
            var icon = menu.getMenu().getContainer().getItem(0);
            icon.remove(DataComponents.CUSTOM_DATA);
            icon.set(DataComponents.CUSTOM_NAME, Component.literal("Combat XXX"));
            client.gui.setScreen(new ContainerScreen(menu.getMenu(), client.player.getInventory(), Component.literal("Your Skills (1/2)")));
        });
        context.waitFor(client -> PlanningService.progress().containsKey("SKILL:COMBAT")
                && PlanningService.progress().get("SKILL:COMBAT").amount().intValueExact() == 30, 300);
        context.runOnClient(client -> {
            net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(
                    Component.literal("Enderman Slayer LVL 7 - Next LVL in 123 XP!"), false);
            var item = ReiSkyblockProvider.repository().source().items().get("TERMINATOR");
            var requirements = dev.reisb.planning.Requirements.read(item, ReiSkyblockProvider.repository().recipes().get("TERMINATOR"));
            require(requirements.stream().filter(condition -> condition.key().equals("SLAYER:ENDERMAN"))
                    .allMatch(condition -> condition.status(PlanningService.progress(), System.currentTimeMillis()) == dev.reisb.planning.Requirements.Status.MET),
                    "Observed Slayer progress was not applied to requirements");
            client.gui.setScreen(new PlanningScreen(new InventoryScreen(client.player), "TERMINATOR", PlanningScreen.View.REQUIREMENTS));
            require(((PlanningScreen) client.gui.screen()).view() == PlanningScreen.View.REQUIREMENTS, "Requirements screen did not open");
        });
        context.runOnClient(client -> {
            var registry = me.shedaniel.rei.api.client.registry.display.DisplayRegistry.getInstance();
            var skyAnvil = new me.shedaniel.rei.plugin.common.displays.anvil.DefaultAnvilDisplay(
                    List.of(me.shedaniel.rei.api.common.util.EntryIngredients.of(ReiSkyblockProvider.repository().stacks().get("HYPERION")),
                            me.shedaniel.rei.api.common.util.EntryIngredients.of(Items.ENCHANTED_BOOK)),
                    List.of(me.shedaniel.rei.api.common.util.EntryIngredients.of(Items.DIAMOND_SWORD)), java.util.Optional.empty());
            var vanillaAnvil = new me.shedaniel.rei.plugin.common.displays.anvil.DefaultAnvilDisplay(
                    List.of(me.shedaniel.rei.api.common.util.EntryIngredients.of(Items.DIAMOND_SWORD),
                            me.shedaniel.rei.api.common.util.EntryIngredients.of(Items.ENCHANTED_BOOK)),
                    List.of(me.shedaniel.rei.api.common.util.EntryIngredients.of(Items.DIAMOND_SWORD)), java.util.Optional.empty());
            require(!registry.isDisplayVisible(skyAnvil), "Vanilla enchanting remained visible for a SkyBlock weapon");
            require(registry.isDisplayVisible(vanillaAnvil), "Vanilla enchanting was hidden for a vanilla weapon");
        });
        context.getInput().setCursorPos(0, 0);
        context.takeScreenshot("planning-requirements");
        context.runOnClient(client -> {
            String account = client.getUser().getProfileId().toString();
            var remote = new dev.reisb.planning.ProfileApiData("banana-api",
                    java.util.Map.of("ENCHANTED_DIAMOND", BigDecimal.valueOf(5), "DIAMOND", BigDecimal.valueOf(999)),
                    java.util.Map.of("DUNGEON_SKILL:CATACOMBS", BigDecimal.valueOf(25), "COLLECTION:INK_SACK_3", BigDecimal.valueOf(5)));
            PlanningService.applyApi(account + ":REISB_TEST_APPLE", remote, System.currentTimeMillis());
            require(!PlanningService.progress().containsKey("DUNGEON_SKILL:CATACOMBS"), "Wrong-profile API result was applied");
            PlanningService.applyApi(account + ":REISB_TEST_BANANA", remote, System.currentTimeMillis());
            require(PlanningService.sackObservations().get("ENCHANTED_DIAMOND").amount().intValueExact() == 5, "API sack count did not enter the active profile cache");
            require(PlanningService.sackObservations().get("DIAMOND").amount().signum() == 0, "API result replaced a recent live sack reading");
            require(PlanningService.progress().get("DUNGEON_SKILL:CATACOMBS").amount().intValueExact() == 25, "API progression did not enter the active profile cache");
            require(PlanningService.progress().containsKey("COLLECTION:COCOA_BEANS")
                    && PlanningService.progress().get("COLLECTION:COCOA_BEANS").amount().intValueExact() == 5,
                    "API collection IDs did not resolve to NEU requirement names");
        });
        context.runOnClient(client -> {
            var repository = ReiSkyblockProvider.repository();
            var multiple = repository.recipes().entrySet().stream()
                    .filter(entry -> entry.getValue().stream().filter(recipe -> recipe.kind() == dev.reisb.data.SkyblockRecipe.Kind.CRAFTING).distinct().count() >= 2)
                    .findFirst().orElseThrow();
            require(me.shedaniel.rei.api.client.view.ViewSearchBuilder.builder()
                    .addRecipesFor(me.shedaniel.rei.api.common.util.EntryStacks.of(repository.stacks().get(multiple.getKey())))
                    .filterCategory(dev.reisb.rei.SkyblockDisplay.category(dev.reisb.data.SkyblockRecipe.Kind.CRAFTING)).open(), "Multiple recipe screen did not open");
            require(client.gui.screen() instanceof me.shedaniel.rei.impl.client.gui.screen.DefaultDisplayViewingScreen screen
                    && screen.getCurrentDisplayed().size() >= 2, "Fewer than two crafts fit on a page: " + client.gui.screen().getClass().getName() + " / " + client.gui.screen().height + " / " + multiple.getKey());
        });
        context.runOnClient(client -> {
            var screen = (me.shedaniel.rei.impl.client.gui.screen.DefaultDisplayViewingScreen) client.gui.screen();
            var frame = screen.getBounds();
            var recipes = screen.children().stream()
                    .filter(widget -> widget instanceof me.shedaniel.rei.impl.client.gui.widget.DisplayCompositeWidget)
                    .map(widget -> ((me.shedaniel.rei.impl.client.gui.widget.DisplayCompositeWidget) widget).getBounds()).toList();
            require(recipes.size() >= 2, "Fewer than two recipe panels are visible");
            for (var recipe : recipes) {
                require(recipe.x >= frame.x && recipe.getMaxX() <= frame.getMaxX(), "Recipe panel exceeds the REI frame horizontally");
            }
        });
        context.takeScreenshot("planning-two-crafts");
        context.runOnClient(client -> {
            require(me.shedaniel.rei.api.client.view.ViewSearchBuilder.builder()
                    .addRecipesFor(me.shedaniel.rei.api.common.util.EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("TERMINATOR")))
                    .filterCategory(dev.reisb.rei.SkyblockDisplay.category(dev.reisb.data.SkyblockRecipe.Kind.CRAFTING)).open(),
                    "Terminator recipe screen did not open");
        });
        context.takeScreenshot("terminator-layout");
        context.runOnClient(client -> {
            client.gui.setScreen(new PlanningScreen(new InventoryScreen(client.player), "ENCHANTED_DIAMOND", PlanningScreen.View.TREE));
            client.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
            require(client.gui.screen() instanceof InventoryScreen, "Escape did not return to the parent inventory");
            require(net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher().getRoot().getChild("reisbplan") != null, "Planning command missing");
            require(net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher().getRoot().getChild("reisbshopping") != null, "Shopping command missing");
            CompatibilityFixture.priceContext = false;
            client.getConnection().getOnlinePlayers().iterator().next().setTabListDisplayName(null);
            PlanningService.removeGoal("ENCHANTED_DIAMOND");
            ReiSkyblockProvider.LOGGER.info("Planning integration assertions passed: inventory, sacks, profile isolation, reservation and screens");
        });
    }

    private static ContainerScreen sackScreen(long count) {
        var client = net.minecraft.client.Minecraft.getInstance();
        var inventory = new SimpleContainer(27);
        var icon = new ItemStack(Items.DIAMOND);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", "DIAMOND");
        icon.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        icon.set(DataComponents.CUSTOM_NAME, Component.literal("Diamond"));
        icon.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Stored: " + count + "/20k"))));
        inventory.setItem(0, icon);
        return new ContainerScreen(ChestMenu.threeRows(99, client.player.getInventory(), inventory), client.player.getInventory(), Component.literal("Mining Sack"));
    }

    private static long missing(String id) {
        var screen = (PlanningScreen) net.minecraft.client.Minecraft.getInstance().gui.screen();
        return screen.result().materials().stream().filter(row -> row.id().equals(id)).map(row -> row.missing()).reduce(BigDecimal.ZERO, BigDecimal::add).longValueExact();
    }

    private static void press(String key) {
        var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
        screen.children().stream().filter(widget -> widget instanceof Button).map(widget -> (Button) widget)
                .filter(button -> button.getMessage().getString().equals(PlanningScreen.tr(key).getString())).findFirst().orElseThrow()
                .onPress(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
