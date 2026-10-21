package dev.reisb.rei;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.SkyblockIds;
import java.util.List;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.registry.ReloadStage;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.world.item.ItemStack;

public final class SkyblockClientPlugin implements REIClientPlugin {
    private static volatile boolean ready;
    private static volatile long applied = -1;
    private static long retryAfter;
    private static net.minecraft.client.multiplayer.ClientPacketListener connection;
    private static long joinedAt;
    private static boolean initializationRequested;

    @Override
    public void registerEntryRenderers(me.shedaniel.rei.api.client.entry.renderer.EntryRendererRegistry registry) {
        registry.register(me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes.ITEM, (entry, last) ->
                new me.shedaniel.rei.api.client.entry.renderer.ForwardingEntryRenderer<ItemStack>(last) {
                    @Override
                    public me.shedaniel.rei.api.client.gui.widgets.Tooltip getTooltip(EntryStack<ItemStack> stack,
                            me.shedaniel.rei.api.client.gui.widgets.TooltipContext context) {
                        boolean marketPrices = ReiSkyblockProvider.config().enableMarketPrices
                                && SkyblockIds.owned(stack.getValue()) && ReiSkyblockProvider.active();
                        if (marketPrices && !context.isSearch()) dev.reisb.pricing.MarketPrices.request();
                        if (!marketPrices) dev.reisb.compat.SkyCoflCompatibility.observe(stack.getValue(), context.isSearch());
                        var tooltip = super.getTooltip(stack, context);
                        if (tooltip != null && marketPrices && !context.isSearch()) dev.reisb.pricing.MarketPrices.append(stack.getValue(), tooltip);
                        if (tooltip != null && context.isSearch() && SkyblockIds.owned(stack.getValue())) {
                            tooltip.add(net.minecraft.network.chat.Component.literal(SkyblockIds.of(stack.getValue())));
                        }
                        return tooltip;
                    }
                });
    }

    @Override
    public void registerCategories(CategoryRegistry registry) {
        for (var kind : SkyblockRecipe.Kind.values()) registry.add(new SkyblockCategory(kind));
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        registry.registerVisibilityPredicate((category, display) -> {
            if (ReiSkyblockProvider.active()
                    && display.getCategoryIdentifier().equals(me.shedaniel.rei.plugin.common.BuiltinPlugin.ANVIL)) {
                var inputs = display.getInputEntries().stream().flatMap(java.util.Collection::stream)
                        .map(EntryStack::getValue).filter(ItemStack.class::isInstance).map(ItemStack.class::cast).toList();
                if (inputs.stream().anyMatch(stack -> stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK))
                        && inputs.stream().anyMatch(stack -> !SkyblockIds.of(stack).isEmpty())) {
                    return dev.architectury.event.EventResult.interruptFalse();
                }
            }
            return dev.architectury.event.EventResult.pass();
        });
        if (registry.getGlobalDisplayGenerators().stream().noneMatch(SkyblockDisplayGenerator.class::isInstance)) {
            registry.registerGlobalDisplayGenerator(new SkyblockDisplayGenerator());
        }
    }

    @Override
    public void registerTransferHandlers(me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry registry) {
        registry.register(new SkyblockTransferHandler());
    }

    @Override
    public void preStage(PluginManager<REIClientPlugin> manager, ReloadStage stage) { ready = false; }

    @Override
    public void postStage(PluginManager<REIClientPlugin> manager, ReloadStage stage) {
        if (stage == ReloadStage.END) { ready = true; applied = -1; }
    }

    @Override
    public void registerEntries(EntryRegistry registry) { registry.addEntries(entries()); }

    private static List<EntryStack<ItemStack>> entries() {
        if (!ReiSkyblockProvider.active() || !ReiSkyblockProvider.config().enableSkyblockItems) return List.of();
        return ReiSkyblockProvider.repository().stacks().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> EntryStacks.of(entry.getValue())).toList();
    }

    public static void tick() {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (connection != client.getConnection()) {
            connection = client.getConnection();
            joinedAt = System.nanoTime();
            initializationRequested = false;
            ready = false;
            applied = -1;
            retryAfter = 0;
        }
        if (client.level == null || client.getConnection() == null) return;
        if (!ready && !initializationRequested && !PluginManager.areAnyReloading()
                && System.nanoTime() - joinedAt >= java.time.Duration.ofSeconds(5).toNanos()) {
            initializationRequested = true;
            ReiSkyblockProvider.LOGGER.info("Initializing REI because the server did not complete its recipe reload");
            me.shedaniel.rei.RoughlyEnoughItemsCoreClient.reloadPlugins(null, null);
            return;
        }
        if (!ready || applied == ReiSkyblockProvider.revision() || System.nanoTime() < retryAfter || PluginManager.areAnyReloading()) return;
        EntryRegistry registry = EntryRegistry.getInstance();
        if (registry.isReloading()) return;
        long current = ReiSkyblockProvider.revision();
        var previous = registry.getEntryStacks().filter(entry -> entry.getValue() instanceof ItemStack stack && SkyblockIds.owned(stack)).toList();
        try {
            var next = entries();
            var desired = next.stream().collect(java.util.stream.Collectors.toMap(entry -> SkyblockIds.of(entry.getValue()), EntryStack::getValue));
            registry.removeEntryIf(entry -> entry.getValue() instanceof ItemStack stack && SkyblockIds.owned(stack)
                    && (!desired.containsKey(SkyblockIds.of(stack)) || !ItemStack.matches(stack, desired.get(SkyblockIds.of(stack)))));
            registry.addEntries(next);
            applied = current;
        } catch (Exception e) {
            retryAfter = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            ReiSkyblockProvider.LOGGER.warn("REI refresh failed; restoring provider entries and retrying later", e);
            try {
                registry.removeEntryIf(entry -> entry.getValue() instanceof ItemStack stack && SkyblockIds.owned(stack));
                registry.addEntries(previous);
                registry.refilter();
            } catch (Exception restoreError) { ReiSkyblockProvider.LOGGER.warn("Cannot restore provider entries", restoreError); }
        }
    }

    public static boolean isSynchronized() { return ready && applied == ReiSkyblockProvider.revision(); }
}
