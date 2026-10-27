package dev.reisb;

import dev.reisb.config.ProviderConfig;
import dev.reisb.data.RepositoryService;
import dev.reisb.data.RepositorySnapshot;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.data.SyncMetadata;
import dev.reisb.data.SyncSchedule;
import dev.reisb.item.PreparedRepository;
import dev.reisb.rei.SkyblockClientPlugin;
import dev.reisb.util.SkyblockDetector;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReiSkyblockProvider implements ClientModInitializer {
    public static final String ID = "rei_skyblock_provider";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);
    private static volatile ProviderConfig config = new ProviderConfig();
    private static volatile PreparedRepository repository = PreparedRepository.EMPTY;
    private static volatile boolean skyblock;
    private static final AtomicLong revision = new AtomicLong();
    private static RepositoryService service;
    private static Path configPath;
    private int ticks;
    private record Publication(RepositorySnapshot data, SyncMetadata metadata, boolean force, boolean notifyUpdate,
                               java.util.concurrent.CompletableFuture<Runnable> completion) {}
    private static final java.util.concurrent.atomic.AtomicReference<Publication> pending = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicBoolean preparing = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String notification;
    private static volatile boolean resourcesChanged;
    private static volatile Runnable requestedScreen;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve(ID + ".json");
        config = ProviderConfig.load(configPath, LOGGER);
        dev.reisb.planning.PlanningService.initialize(FabricLoader.getInstance().getConfigDir().resolve(ID));
        net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                .registerReloadListener(net.minecraft.resources.Identifier.fromNamespaceAndPath(ID, "item_models"),
                        new net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener<Boolean>() {
                            @Override
                            protected Boolean prepare(net.minecraft.server.packs.resources.PreparableReloadListener.SharedState state) { return true; }
                            @Override
                            protected void apply(Boolean prepared, net.minecraft.server.packs.resources.PreparableReloadListener.SharedState state) { resourcesChanged = true; }
                        });
        service = new RepositoryService(FabricLoader.getInstance().getConfigDir().resolve(ID), LOGGER, ReiSkyblockProvider::stage);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommands.literal("reisbshopping").executes(context -> {
                requestedScreen = () -> Minecraft.getInstance().gui.setScreen(
                        new dev.reisb.rei.PlanningScreen(Minecraft.getInstance().gui.screen(), "", dev.reisb.rei.PlanningScreen.View.SHOPPING));
                return 1;
            }));
            dispatcher.register(ClientCommands.literal("reisbplan").then(ClientCommands.argument("item", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(context -> openPlan(com.mojang.brigadier.arguments.StringArgumentType.getString(context, "item")))));
            dispatcher.register(ClientCommands.literal("reisbreload").executes(context -> {
                reload();
                context.getSource().sendFeedback(Component.literal("REI SkyBlock: reload started. Use /reisbstatus to check progress."));
                return 1;
            }));
            dispatcher.register(ClientCommands.literal("reisbstatus").executes(context -> {
                service.statusLines().forEach(line -> context.getSource().sendFeedback(Component.literal(line)));
                context.getSource().sendFeedback(Component.literal(dev.reisb.rei.ReiDiagnostics.status()));
                context.getSource().sendFeedback(Component.literal("Profile API: " + dev.reisb.planning.ProfileApiSync.status()));
                context.getSource().sendFeedback(Component.literal("Market prices: " + dev.reisb.pricing.MarketPrices.status()));
                context.getSource().sendFeedback(Component.literal("REI visible: " + repository.stacks().size() + " items, "
                        + repository.source().recipes().size() + " recipes | active=" + active() + " | preparing=" + preparing.get()
                        + " | automatic refresh=" + config.enableAutomaticReiRefresh + " | synchronized=" + SkyblockClientPlugin.isSynchronized()));
                return 1;
            }));
            dispatcher.register(ClientCommands.literal("reisbprofilesync").executes(context -> {
                dev.reisb.planning.ProfileApiSync.refresh();
                context.getSource().sendFeedback(Component.literal("REI SkyBlock: profile sync requested; rate limits still apply. Use /reisbstatus."));
                return 1;
            }));
            dispatcher.register(ClientCommands.literal("reisbupdate").executes(context -> {
                config = ProviderConfig.load(configPath, LOGGER);
                revision.incrementAndGet();
                service.request(config, SyncSchedule.Trigger.MANUAL);
                context.getSource().sendFeedback(Component.literal("REI SkyBlock: remote check requested; API backoff limits still apply. Use /reisbstatus for progress."));
                return 1;
            }));
            dispatcher.register(ClientCommands.literal("reisbdebug").then(ClientCommands.argument("item", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(context -> {
                        dev.reisb.rei.ReiDiagnostics.inspect(com.mojang.brigadier.arguments.StringArgumentType.getString(context, "item"));
                        context.getSource().sendFeedback(Component.literal("REI diagnostic written to latest.log"));
                        return 1;
                    })));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var screen = requestedScreen;
            requestedScreen = null;
            if (screen != null) screen.run();
            if (++ticks % 20 == 0 || client.level == null) {
                boolean detected = SkyblockDetector.isSkyblock(client);
                if (skyblock != detected) {
                    skyblock = detected;
                    revision.incrementAndGet();
                    if (detected) service.request(config, SyncSchedule.Trigger.JOIN);
                }
                if (client.level != null && active()) service.request(config, SyncSchedule.Trigger.PERIODIC);
            }
            if (resourcesChanged && !synchronizing() && repository != PreparedRepository.EMPTY) {
                resourcesChanged = false;
                stage(repository.source(), SyncMetadata.EMPTY, true, false).thenAccept(Runnable::run)
                        .exceptionally(error -> { LOGGER.warn("Cannot refresh item models after resource reload", error); return null; });
            }
            preparePending(client);
            SkyblockClientPlugin.tick();
            dev.reisb.planning.PlanningService.tick();
            if (notification != null && client.player != null) {
                if (config.notifyWhenItemsUpdated) client.player.sendSystemMessage(Component.literal(notification));
                notification = null;
            }
        });
        service.request(config, SyncSchedule.Trigger.STARTUP);
    }

    private static int openPlan(String id) {
        if (!repository.stacks().containsKey(id)) return 0;
        requestedScreen = () -> Minecraft.getInstance().gui.setScreen(
                new dev.reisb.rei.PlanningScreen(Minecraft.getInstance().gui.screen(), id, dev.reisb.rei.PlanningScreen.View.TREE));
        return 1;
    }

    public static ProviderConfig config() { return config; }
    public static PreparedRepository repository() { return repository; }
    public static long revision() { return revision.get(); }
    public static boolean active() { return config.enabled && (!config.onlyOnHypixelSkyblock || skyblock); }
    public static String loadingStatus() {
        return "repository=" + (service == null ? "not initialized" : service.status())
                + ", loading=" + (service != null && service.loading()) + ", preparing=" + preparing.get()
                + ", pending=" + (pending.get() != null) + ", revision=" + revision()
                + ", items=" + repository.stacks().size() + ", recipes=" + repository.source().recipes().size();
    }
    static boolean synchronizing() { return service.loading() || preparing.get() || pending.get() != null; }

    public static void reload() {
        config = ProviderConfig.load(configPath, LOGGER);
        dev.reisb.planning.ProfileApiSync.refresh();
        revision.incrementAndGet();
        service.reload(config);
    }

    static java.util.concurrent.CompletableFuture<Runnable> stage(RepositorySnapshot data, SyncMetadata metadata,
                                                                  boolean force, boolean notifyUpdate) {
        var completion = new java.util.concurrent.CompletableFuture<Runnable>();
        if (!pending.compareAndSet(null, new Publication(data, metadata, force, notifyUpdate, completion))) {
            completion.completeExceptionally(new IllegalStateException("An item publication is already pending"));
        }
        return completion;
    }

    private static void preparePending(Minecraft client) {
        if (pending.get() == null || preparing.get()) return;
        try { net.minecraft.world.item.Items.DIAMOND.builtInRegistryHolder().components(); }
        catch (NullPointerException e) { return; }
        if (!preparing.compareAndSet(false, true)) return;
        var publication = pending.getAndSet(null);
        Thread.ofVirtual().name("reisb-items").start(() -> {
            try {
                var prepared = PreparedRepository.prepare(publication.data(), LOGGER, true);
                publication.completion().complete(() -> client.execute(() -> {
                    if (publication.force() || config.enableAutomaticReiRefresh || repository == PreparedRepository.EMPTY) {
                        repository = prepared;
                        revision.incrementAndGet();
                        dev.reisb.rei.ReiDiagnostics.log("Published repository: {}", loadingStatus());
                        if (publication.notifyUpdate() && publication.metadata().changes().changed()) {
                            notification = "REI SkyBlock updated: " + publication.metadata().changes().summary();
                        }
                    }
                }));
            } catch (Exception e) {
                LOGGER.error("Cannot prepare SkyBlock item stacks", e);
                publication.completion().completeExceptionally(e);
            } finally { preparing.set(false); }
        });
    }

    public static boolean enabled(SkyblockRecipe.Kind kind) {
        if (!active()) return false;
        return switch (kind) {
            case CRAFTING -> config.enableCraftingRecipes;
            case FORGE -> config.enableForgeRecipes;
            case KAT -> config.enableKatRecipes;
            case NPC -> config.enableNpcRecipes;
            case DROPS -> config.enableItemInfo;
            case INFO -> config.enableItemInfo;
        };
    }
}
