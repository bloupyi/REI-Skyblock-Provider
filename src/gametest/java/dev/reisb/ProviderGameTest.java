package dev.reisb;

import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.SkyblockIds;
import dev.reisb.rei.SkyblockDisplay;
import dev.reisb.rei.SkyblockDisplayGenerator;
import java.nio.file.Files;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.search.SearchProvider;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ProviderGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        require(!FabricLoader.getInstance().isModLoaded("skyblocker"), "Skyblocker must not be installed");
        RecipeReloadSuppression.enabled = true;
        RecipeReloadSuppression.recovered = false;
        try (var world = context.worldBuilder().create()) {
            context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
            context.waitFor(client -> !PluginManager.areAnyReloading()
                    && dev.reisb.rei.SkyblockClientPlugin.isSynchronized()
                    && ReiSkyblockProvider.repository().stacks().containsKey("HYPERION") && ownedEntries() > 8000, 6000);
            context.runOnClient(client -> {
                require(RecipeReloadSuppression.recovered, "Missing recipe reload was not recovered");
                require(CompatibilityFixture.retained, "Unrelated plugin in mixed provider was removed");
                require(de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin.registered
                        != dev.reisb.compat.SkyblockerReiIsolation.requested(), "Unexpected Skyblocker client isolation state");
                require(de.hysky.skyblocker.compatibility.rei.SkyblockerREICommonPlugin.registered
                        != dev.reisb.compat.SkyblockerReiIsolation.requested(), "Unexpected Skyblocker common isolation state");
                for (var kind : SkyblockRecipe.Kind.values()) require(CategoryRegistry.getInstance().tryGet(SkyblockDisplay.category(kind)).isPresent(), "Missing category: " + kind);
                var registry = EntryRegistry.getInstance();
                require(registry.getEntryStacks().anyMatch(entry -> entry.getValue() instanceof ItemStack stack
                        && stack.is(Items.DIAMOND) && !SkyblockIds.owned(stack)), "Vanilla diamond entry removed");
                var repository = ReiSkyblockProvider.repository();
                var terminator = repository.stacks().get("TERMINATOR");
                require(net.minecraft.resources.Identifier.parse("hypixel_skyblock:item/slayer/enderman/weapons/terminator")
                        .equals(terminator.get(net.minecraft.core.component.DataComponents.ITEM_MODEL)), "Pack model was not transferred to the item component");
                require(terminator.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag()
                        .getStringOr("id", "").equals("TERMINATOR"), "Modern SkyBlock metadata is missing");
                require(terminator.get(net.minecraft.core.component.DataComponents.LORE).lines().stream()
                        .noneMatch(line -> line.getString().contains("SkyBlock ID:") || line.getString().contains("NEU reference")), "Technical lore leaked into the tooltip");
                var generator = new SkyblockDisplayGenerator();
                for (int i = 0; i < 3; i++) new dev.reisb.rei.SkyblockClientPlugin().registerDisplays(me.shedaniel.rei.api.client.registry.display.DisplayRegistry.getInstance());
                verifyUniqueRecipes();
                require(generator.getRecipeFor(EntryStacks.of(Items.ENDER_PEARL)).orElseThrow().stream()
                        .anyMatch(display -> display.recipe().kind() == SkyblockRecipe.Kind.DROPS), "Direct vanilla Ender Pearl drop lookup failed");
                var bookDrop = repository.source().recipes().stream().filter(recipe -> recipe.kind() == SkyblockRecipe.Kind.DROPS)
                        .filter(recipe -> repository.stacks().get(recipe.output().id()).is(Items.ENCHANTED_BOOK)).findFirst().orElseThrow();
                require(generator.getRecipeFor(EntryStacks.of(repository.stacks().get(bookDrop.output().id()))).orElseThrow().stream()
                        .anyMatch(display -> display.recipe().equals(bookDrop)), "Direct dropped book lookup failed");
                for (String petId : new String[]{"ENDER_DRAGON;3", "GOLDEN_DRAGON;4"}) {
                    var pet = repository.stacks().get(petId);
                    require(!pet.getHoverName().getString().contains("{"), "Pet level name contains a placeholder");
                    require(pet.get(net.minecraft.core.component.DataComponents.LORE).lines().stream()
                            .noneMatch(line -> line.getString().matches(".*\\{(?:[A-Z_]+|[0-9]+)\\}.*")), "Pet lore contains a placeholder");
                }
                var info = generator.getRecipeFor(EntryStacks.of(repository.stacks().get("HYPERION"))).orElseThrow().stream()
                        .filter(display -> display.recipe().kind() == SkyblockRecipe.Kind.INFO).findFirst().orElseThrow();
                var widgets = new dev.reisb.rei.SkyblockCategory(SkyblockRecipe.Kind.INFO)
                        .setupDisplay(info, new me.shedaniel.math.Rectangle(0, 0, 200, 150));
                var wiki = widgets.stream().filter(widget -> widget instanceof me.shedaniel.rei.api.client.gui.widgets.Button)
                        .map(widget -> (me.shedaniel.rei.api.client.gui.widgets.Button) widget).findFirst().orElseThrow();
                var previousScreen = client.gui.screen();
                var escape = new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
                for (int attempt = 0; attempt < 3; attempt++) {
                    require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(repository.stacks().get("HYPERION")))
                            .filterCategory(SkyblockDisplay.category(SkyblockRecipe.Kind.INFO)).open(), "Wiki recipe screen did not open");
                    var recipeScreen = client.gui.screen();
                    wiki.onClick();
                    require(client.gui.screen() instanceof net.minecraft.client.gui.screens.ConfirmLinkScreen, "Wiki link did not open confirmation screen");
                    require(me.shedaniel.rei.api.client.REIRuntime.getInstance().getPreviousScreen() == previousScreen,
                            "Wiki confirmation replaced REI's return screen");
                    client.gui.screen().keyPressed(escape);
                    require(client.gui.screen() == recipeScreen, "Escape did not return from wiki confirmation to recipes");
                    client.gui.screen().keyPressed(escape);
                    require(client.gui.screen() == previousScreen, "Escape from recipes reopened wiki confirmation");
                }
                client.gui.screen().keyPressed(escape);
                require(client.gui.screen() == null, "Inventory could not be closed after wiki confirmation");
                client.gui.setScreen(previousScreen);
                require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(Items.CRAFTING_TABLE)).open(), "Vanilla recipe screen did not open");
                require(!((me.shedaniel.rei.api.client.gui.screen.DisplayScreen) client.gui.screen()).getCurrentCategoryId()
                        .equals(SkyblockDisplay.category(SkyblockRecipe.Kind.CRAFTING)), "Vanilla recipe lookup was redirected to SkyBlock");
                require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(repository.stacks().get("HYPERION"))).open(), "SkyBlock recipe screen did not open");
                require(((me.shedaniel.rei.api.client.gui.screen.DisplayScreen) client.gui.screen()).getCurrentCategoryId()
                        .equals(SkyblockDisplay.category(SkyblockRecipe.Kind.CRAFTING)), "SkyBlock crafting tab was not selected first");
                require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(repository.stacks().get("HYPERION")))
                        .filterCategory(SkyblockDisplay.category(SkyblockRecipe.Kind.INFO)).open(), "Explicit item info did not open");
                require(((me.shedaniel.rei.api.client.gui.screen.DisplayScreen) client.gui.screen()).getCurrentCategoryId()
                        .equals(SkyblockDisplay.category(SkyblockRecipe.Kind.INFO)), "Explicit category selection was overridden");
                require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(repository.stacks().get("HYPERION"))).open(), "Recipe lookup from info did not open");
                require(((me.shedaniel.rei.api.client.gui.screen.DisplayScreen) client.gui.screen()).getCurrentCategoryId()
                        .equals(SkyblockDisplay.category(SkyblockRecipe.Kind.CRAFTING)), "Recipe lookup inherited the previous info tab");
                var craft = generator.getRecipeFor(EntryStacks.of(repository.stacks().get("HYPERION"))).orElseThrow().stream()
                        .filter(display -> display.recipe().kind() == SkyblockRecipe.Kind.CRAFTING).findFirst().orElseThrow();
                var commands = new java.util.ArrayList<String>();
                var transfer = new dev.reisb.rei.SkyblockTransferHandler(() -> true, commands::add);
                me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry.getInstance().register(transfer);
                var preview = me.shedaniel.rei.impl.client.gui.widget.AutoCraftingEvaluator.evaluateAutoCrafting(false, false, craft, null);
                require(preview.successful && preview.successfulHandler == transfer, "Native REI plus button did not use the SkyBlock handler");
                require(commands.isEmpty(), "Plus button preview sent a command");
                me.shedaniel.rei.impl.client.gui.widget.AutoCraftingEvaluator.evaluateAutoCrafting(true, false, craft, null);
                require(commands.equals(java.util.List.of("viewrecipe HYPERION")), "Plus button did not issue exactly one server recipe request");
                for (String id : new String[]{"HYPERION", "ASPECT_OF_THE_END", "ENCHANTED_DIAMOND"}) {
                    var entry = EntryStacks.of(repository.stacks().get(id));
                    require(SearchProvider.getInstance().createFilter(id).test(entry), "SkyBlock ID search failed: " + id);
                    require(generator.getRecipeFor(entry).orElseThrow().stream().anyMatch(d -> d.recipe().kind() == SkyblockRecipe.Kind.CRAFTING), "Missing craft: " + id);
                    require(ViewSearchBuilder.builder().addRecipesFor(entry).streamDisplays().findAny().isPresent(), "REI recipe lookup failed: " + id);
                }
                var diamond = EntryStacks.of(repository.stacks().get("ENCHANTED_DIAMOND"));
                require(SearchProvider.getInstance().createFilter("Enchanted Diamond").test(diamond), "Item name search failed");
                require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(Items.CRAFTING_TABLE)).streamDisplays().findAny().isPresent(), "Vanilla crafting table recipe removed");
                require(!generator.getUsageFor(diamond).orElseThrow().isEmpty(), "Missing ingredient usages");
                require(ViewSearchBuilder.builder().addUsagesFor(diamond).streamDisplays().findAny().isPresent(), "REI usage lookup failed");
                require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(repository.stacks().get("HYPERION"))).open(), "Recipe screen failed to open");
            });
            context.waitTicks(10);
            context.takeScreenshot("hyperion-recipe");
            CoexistenceAssertions.run(context);
            try { verifyMarketPrices(context); verifyVirtualPrices(context); }
            catch (Throwable error) { ReiSkyblockProvider.LOGGER.error("Virtual item price integration test failed", error); throw error; }
            context.runOnClient(client -> {
                var npc = ReiSkyblockProvider.repository().source().recipes().stream().filter(recipe -> recipe.kind() == SkyblockRecipe.Kind.NPC
                        && recipe.inputs().stream().anyMatch(input -> input.id().equals("SKYBLOCK_COIN") && input.amount().compareTo(java.math.BigDecimal.valueOf(1_000_000)) >= 0))
                        .findFirst().orElseThrow();
                require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(ReiSkyblockProvider.repository().stacks().get(npc.output().id())))
                        .filterCategory(SkyblockDisplay.category(SkyblockRecipe.Kind.NPC)).open(), "NPC purchase screen did not open");
            });
            context.waitTicks(10);
            context.takeScreenshot("npc-purchase");
            context.runOnClient(client -> require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(ReiSkyblockProvider.repository().stacks().get("ENDER_DRAGON;3")))
                    .filterCategory(SkyblockDisplay.category(SkyblockRecipe.Kind.INFO)).open(), "Pet info screen did not open"));
            context.waitTicks(10);
            context.takeScreenshot("pet-level-ranges");
            var old = ReiSkyblockProvider.repository();
            long count = context.computeOnClient(client -> ownedEntries());
            context.runOnClient(client -> ReiSkyblockProvider.reload());
            context.waitFor(client -> ReiSkyblockProvider.repository() != old && ownedEntries() == count
                    && dev.reisb.rei.SkyblockClientPlugin.isSynchronized(), 6000);
            context.runOnClient(client -> {
                verifyUniqueRecipes();
                require(ownedEntries() == ReiSkyblockProvider.repository().stacks().size(), "Provider count after reload: entries="
                        + ownedEntries() + ", expected=" + ReiSkyblockProvider.repository().stacks().size());
                var configPath = FabricLoader.getInstance().getConfigDir().resolve(ReiSkyblockProvider.ID + ".json");
                try { Files.writeString(configPath, "{\"onlyOnHypixelSkyblock\":true,\"autoUpdateRepository\":false,\"enableProfileApi\":false}"); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                ReiSkyblockProvider.reload();
            });
            context.waitFor(client -> ownedEntries() == 0, 1000);
            context.runOnClient(client -> require(EntryRegistry.getInstance().getEntryStacks().anyMatch(entry ->
                    entry.getValue() instanceof ItemStack stack && stack.is(Items.DIAMOND)), "Vanilla entries lost when disabled"));
            context.runOnClient(client -> require(ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(Items.CRAFTING_TABLE))
                    .streamDisplays().findAny().isPresent(), "Vanilla recipes lost when disabled"));
            ReiSkyblockProvider.LOGGER.info("Provider integration assertions passed, including virtual prices and menu cache restoration");
        }
    }

    private static void verifyMarketPrices(ClientGameTestContext context) {
        com.sun.net.httpserver.HttpServer server = null;
        dev.reisb.pricing.MarketPriceService previous = null;
        try {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                String json = exchange.getRequestURI().getPath().equals("/bin")
                        ? "{\"TERMINATOR\":123456789,\"LVL_1_LEGENDARY_ENDER_DRAGON\":10000000,\"LVL_100_LEGENDARY_ENDER_DRAGON\":20000000}"
                        : "{\"success\":true,\"products\":{\"DIAMOND\":{\"buy_summary\":[{\"amount\":100,\"pricePerUnit\":12.5}],\"sell_summary\":[{\"amount\":100,\"pricePerUnit\":10.5}]},\"ENCHANTMENT_ULTIMATE_WISE_5\":{\"buy_summary\":[{\"amount\":1,\"pricePerUnit\":500000}],\"sell_summary\":[]}}}";
                byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            var base = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var field = dev.reisb.pricing.MarketPrices.class.getDeclaredField("service");
            field.setAccessible(true);
            previous = (dev.reisb.pricing.MarketPriceService) field.get(null);
            var service = new dev.reisb.pricing.MarketPriceService(base.resolve("/bin"), base.resolve("/bazaar"), java.time.Clock.systemUTC());
            field.set(null, service);
            context.runOnClient(client -> {
                ReiSkyblockProvider.config().enableMarketPrices = true;
                require(client.player.getInventory().getNonEquipmentItems().stream().noneMatch(item -> SkyblockIds.of(item).equals("TERMINATOR")),
                        "Market test item was already in the inventory");
                var sidebar = EntryRegistry.getInstance().getEntryStacks()
                        .filter(entry -> entry.getValue() instanceof ItemStack item && SkyblockIds.owned(item) && SkyblockIds.of(item).equals("TERMINATOR"))
                        .findFirst().orElseThrow();
                sidebar.getTooltip(me.shedaniel.rei.api.client.gui.widgets.TooltipContext.of(net.minecraft.world.item.Item.TooltipContext.of(client.level)));
            });
            context.waitFor(client -> service.lowestBin().items().containsKey("TERMINATOR") && service.bazaar().items().containsKey("DIAMOND"), 400);
            context.runOnClient(client -> {
                var repository = ReiSkyblockProvider.repository();
                var sidebar = EntryRegistry.getInstance().getEntryStacks()
                        .filter(entry -> entry.getValue() instanceof ItemStack item && SkyblockIds.owned(item) && SkyblockIds.of(item).equals("TERMINATOR"))
                        .findFirst().orElseThrow();
                var tooltipContext = me.shedaniel.rei.api.client.gui.widgets.TooltipContext.of(net.minecraft.world.item.Item.TooltipContext.of(client.level));
                require(sidebar.getTooltip(tooltipContext).entries().stream().filter(entry -> entry.isText())
                        .anyMatch(entry -> entry.getAsText().getString().contains("123,456,789")), "Independent LBIN missing from the sidebar tooltip");
                var recipe = new SkyblockDisplayGenerator().getRecipeFor(EntryStacks.of(repository.stacks().get("TERMINATOR"))).orElseThrow().getFirst();
                require(recipe.entry(recipe.recipe().output()).getTooltip(tooltipContext).entries().stream().filter(entry -> entry.isText())
                        .anyMatch(entry -> entry.getAsText().getString().contains("123,456,789")), "Independent LBIN missing from the recipe output");
                var diamonds = EntryStacks.of(repository.stack(new dev.reisb.data.Ingredient("DIAMOND", 64))).getTooltip(tooltipContext);
                require(diamonds.entries().stream().filter(entry -> entry.isText()).anyMatch(entry -> entry.getAsText().getString().contains("12.5 coins")), "Instant buy side incorrect");
                require(diamonds.entries().stream().filter(entry -> entry.isText()).anyMatch(entry -> entry.getAsText().getString().contains("10.5 coins")), "Instant sell side incorrect");
                require(diamonds.entries().stream().filter(entry -> entry.isText()).anyMatch(entry -> entry.getAsText().getString().contains("800 coins")), "Stack total missing");
                var book = EntryStacks.of(repository.stacks().get("ULTIMATE_WISE;5")).getTooltip(tooltipContext);
                require(book.entries().stream().filter(entry -> entry.isText()).anyMatch(entry -> entry.getAsText().getString().contains("500,000 coins")), "Enchantment Bazaar ID did not resolve");
                for (int i = 0; i < 100; i++) sidebar.getTooltip(tooltipContext);
                require(requests.get() == 2, "Hovering triggered repeated market downloads");
                require(com.coflnet.CoflModClient.requests.get() == 0, "Independent prices still depend on SkyCofl requests");
            });
            ReiSkyblockProvider.LOGGER.info("Independent market tooltip assertions passed: sidebar, recipe output, Bazaar sides, stack totals, books and no inventory prerequisite");
        } catch (Exception error) { throw new IllegalStateException(error); }
        finally {
            if (server != null) server.stop(0);
            if (previous != null) {
                try {
                    var field = dev.reisb.pricing.MarketPrices.class.getDeclaredField("service");
                    field.setAccessible(true);
                    field.set(null, previous);
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            }
        }
    }

    private static void verifyVirtualPrices(ClientGameTestContext context) {
        var stack = context.computeOnClient(client -> ReiSkyblockProvider.repository().stacks().get("DIVER_BOOTS"));
        var variant = stack.copyWithCount(2);
        context.runOnClient(client -> {
            CompatibilityFixture.priceContext = true;
            ReiSkyblockProvider.config().enableMarketPrices = false;
            net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((item, tooltipContext, flags, lines) -> {
                String id = com.coflnet.CoflModClient.getIdFromStack(item);
                var data = com.coflnet.CoflModClient.knownIds.containsKey(id)
                        ? com.coflnet.CoflModClient.getMappedTooltipData(id) : null;
                if (data != null) for (var line : data) lines.add(net.minecraft.network.chat.Component.literal(line.value()));
            });
            require(client.player.getInventory().getNonEquipmentItems().stream().noneMatch(item -> SkyblockIds.of(item).equals("DIVER_BOOTS")),
                    "Virtual item was already in the player inventory");
            require(ItemStack.CODEC.encodeStart(client.player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack)
                    .error().isPresent(), "Price regression fixture does not reproduce the disconnected enchantment registry");
            var first = com.coflnet.CoflModClient.getIdFromStack(stack);
            require(first.equals(dev.reisb.compat.SkyCoflCompatibility.key(stack)), "SkyCofl virtual identity mixin did not apply");
            require(!first.equals(com.coflnet.CoflModClient.getIdFromStack(variant)), "Stack counts share a price key");
            var modified = stack.copy();
            var metadata = modified.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
            metadata.putString("modifier", "hasty");
            modified.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(metadata));
            require(!first.equals(com.coflnet.CoflModClient.getIdFromStack(modified)), "Metadata variants share a price key");
            dev.reisb.compat.SkyCoflCompatibility.observe(stack, true);
            require(com.coflnet.CoflModClient.requests.get() == 0, "Search indexing requested prices");
            EntryStacks.of(stack).getTooltip(me.shedaniel.rei.api.client.gui.widgets.TooltipContext.of(
                    net.minecraft.world.item.Item.TooltipContext.of(client.level)));
        });
        context.waitFor(client -> com.coflnet.CoflModClient.getMappedTooltipData(
                com.coflnet.CoflModClient.getIdFromStack(stack)) != null, 200);
        context.runOnClient(client -> {
            String id = com.coflnet.CoflModClient.getIdFromStack(stack);
            require(com.coflnet.CoflModClient.knownIds.containsKey(id), "Price response was not visible to SkyCofl's tooltip callback");
            require(com.coflnet.CoflModClient.getMappedTooltipData(id)[0].value().contains("123 coins"), "Price response was not cached");
            var tooltip = EntryStacks.of(stack).getTooltip(me.shedaniel.rei.api.client.gui.widgets.TooltipContext.of(
                    net.minecraft.world.item.Item.TooltipContext.of(client.level)));
            require(tooltip.entries().stream().filter(entry -> entry.isText())
                    .anyMatch(entry -> entry.getAsText().getString().contains("123 coins")), "Price missing from real REI tooltip");
            for (int i = 0; i < 100; i++) dev.reisb.compat.SkyCoflCompatibility.observe(stack, false);
            require(com.coflnet.CoflModClient.requests.get() == 1, "Repeated hovering flooded price requests");
            com.coflnet.CoflModClient.knownIds.clear();
            dev.reisb.compat.SkyCoflCompatibility.observe(stack, false);
            require(com.coflnet.CoflModClient.knownIds.containsKey(id), "Cached price did not restore SkyCofl tooltip eligibility");
            CoflCore.handlers.DescriptionHandler.tooltipItemIdMap.clear();
            com.coflnet.CoflModClient.knownIds.clear();
            dev.reisb.compat.SkyCoflCompatibility.observe(stack, false);
            require(com.coflnet.CoflModClient.getMappedTooltipData(id) != null, "Menu change lost a fresh REI price");
            require(com.coflnet.CoflModClient.requests.get() == 1, "Menu change sent a duplicate price request");
        });
        context.waitTicks(25);
        context.waitFor(client -> {
            dev.reisb.compat.SkyCoflCompatibility.observe(variant, false);
            return com.coflnet.CoflModClient.getMappedTooltipData(com.coflnet.CoflModClient.getIdFromStack(variant)) != null;
        }, 2000);
        context.runOnClient(client -> {
            require(com.coflnet.CoflModClient.requests.get() == 2, "Recipe ingredient price request missing");
        });
        context.waitTicks(25);
        context.runOnClient(client -> {
            com.coflnet.CoflModClient.failCapture = true;
            int before = com.coflnet.CoflModClient.captures.get();
            for (int i = 0; i < 100; i++) dev.reisb.compat.SkyCoflCompatibility.observe(stack.copyWithCount(5), false);
            require(com.coflnet.CoflModClient.captures.get() == before + 1, "Capture failure did not throttle repeated attempts");
            com.coflnet.CoflModClient.failCapture = false;
        });
        context.waitTicks(25);
        context.waitFor(client -> {
            var next = stack.copyWithCount(6);
            EntryStacks.of(next).getTooltip(me.shedaniel.rei.api.client.gui.widgets.TooltipContext.of(
                    net.minecraft.world.item.Item.TooltipContext.of(client.level)));
            return com.coflnet.CoflModClient.getMappedTooltipData(com.coflnet.CoflModClient.getIdFromStack(next)) != null;
        }, 2000);
        context.runOnClient(client -> {
            require(com.coflnet.CoflModClient.requests.get() == 3, "One bad item disabled later virtual price requests");
            com.coflnet.CoflModClient.failRequest = true;
        });
        context.waitFor(client -> {
            dev.reisb.compat.SkyCoflCompatibility.observe(stack.copyWithCount(3), false);
            return com.coflnet.CoflModClient.requests.get() == 4;
        }, 2000);
        context.waitTicks(25);
        context.runOnClient(client -> {
            for (int i = 0; i < 100; i++) dev.reisb.compat.SkyCoflCompatibility.observe(stack.copyWithCount(4), false);
            require(com.coflnet.CoflModClient.requests.get() == 4, "Price service failure did not impose a cooldown");
            com.coflnet.CoflModClient.failRequest = false;
            ReiSkyblockProvider.config().enableMarketPrices = true;
            CompatibilityFixture.priceContext = false;
        });
    }

    private static long ownedEntries() {
        return EntryRegistry.getInstance().getEntryStacks().filter(entry -> entry.getValue() instanceof ItemStack stack && SkyblockIds.owned(stack)).count();
    }

    private static void verifyUniqueRecipes() {
        var repository = ReiSkyblockProvider.repository();
        var displays = ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(repository.stacks().get("HYPERION"))).streamDisplays()
                .map(spec -> spec.provideInternalDisplay()).filter(display -> display instanceof SkyblockDisplay)
                .map(display -> ((SkyblockDisplay) display).recipe()).toList();
        require(displays.size() == new java.util.HashSet<>(displays).size(), "Duplicate SkyBlock recipe methods in REI");
        long expected = repository.source().recipes().stream().filter(recipe -> recipe.output().id().equals("HYPERION") && recipe.kind() == SkyblockRecipe.Kind.CRAFTING).distinct().count();
        require(displays.stream().filter(recipe -> recipe.kind() == SkyblockRecipe.Kind.CRAFTING).count() == expected, "Crafting method count differs from source data");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
