package dev.reisb.compat;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.item.SkyblockIds;
import dev.reisb.util.SkyblockDetector;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

public final class SkyCoflCompatibility {
    private record Api(Method capture, Method fetch, Method identify, Method mapped, Method changed,
                       Field cache, Field known) {}
    private record Cached(long time, Object description) {}
    private static final Map<String, Long> requested = new LinkedHashMap<>();
    private static final Map<String, Cached> prices = new LinkedHashMap<>();
    private static Api api;
    private static boolean initialized;
    private static boolean running;
    private static long nextRequest;
    private static net.minecraft.client.multiplayer.ClientPacketListener connection;

    private SkyCoflCompatibility() {}

    public static String key(ItemStack stack) {
        return "reisb:" + SkyblockIds.of(stack) + ":" + stack.getCount() + ":"
                + Integer.toUnsignedString(stack.getComponents().hashCode(), 16);
    }

    public static void observe(ItemStack stack, boolean search) {
        var client = Minecraft.getInstance();
        if (search || !SkyblockIds.owned(stack) || !ReiSkyblockProvider.active()
                || !SkyblockDetector.isSkyblock(client)) return;
        if (connection != client.getConnection()) {
            connection = client.getConnection();
            requested.clear();
            prices.clear();
            nextRequest = 0;
        }
        if (!initialized) initialize();
        if (api == null) return;
        long now = System.nanoTime();
        Api bridge = api;
        try {
            String id = (String) bridge.identify.invoke(null, stack);
            boolean cached = bridge.mapped.invoke(null, id) != null;
            Cached price = prices.get(id);
            if (price != null && now - price.time < java.time.Duration.ofSeconds(60).toNanos()) {
                if (!cached) publish(bridge, id, price.description);
                return;
            }
            if (running || now < nextRequest) return;
            long previous = requested.getOrDefault(id, 0L);
            if (previous != 0 && now - previous < java.time.Duration.ofSeconds(cached ? 60 : 15).toNanos()) return;
            var items = NonNullList.<ItemStack>create();
            items.add(stack.copy());
            Object request = bridge.capture.invoke(null, "REI SkyBlock", items);
            var origin = client.getConnection();
            requested.put(id, now);
            if (requested.size() > 512) requested.remove(requested.keySet().iterator().next());
            nextRequest = now + java.time.Duration.ofSeconds(1).toNanos();
            running = true;
            CompletableFuture.supplyAsync(() -> {
                try { return bridge.fetch.invoke(request); }
                catch (ReflectiveOperationException error) { throw new java.util.concurrent.CompletionException(error); }
            }, runnable -> Thread.ofVirtual().name("reisb-cofl-prices").start(runnable)).whenComplete((response, error) ->
                    client.execute(() -> {
                        running = false;
                        if (origin != client.getConnection() || !SkyblockDetector.isSkyblock(client)) return;
                        if (error != null) {
                            nextRequest = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
                            ReiSkyblockProvider.LOGGER.debug("SkyCofl price request failed", error);
                            return;
                        }
                        try {
                            if (response == null || Array.getLength(response) < 1) return;
                            Object description = Array.get(response, 0);
                            if (description == null) return;
                            prices.put(id, new Cached(System.nanoTime(), description));
                            if (prices.size() > 512) prices.remove(prices.keySet().iterator().next());
                            publish(bridge, id, description);
                        } catch (ReflectiveOperationException | RuntimeException failure) { disable(failure); }
                    }));
        } catch (ReflectiveOperationException | RuntimeException error) { disable(error); }
    }

    private static void publish(Api bridge, String id, Object description) throws ReflectiveOperationException {
        put(bridge.cache.get(null), id, description);
        Object known = bridge.known.get(null);
        known.getClass().getMethod("put", Object.class, Object.class).invoke(known, id, true);
        bridge.changed.invoke(null, "REI SkyBlock");
    }

    @SuppressWarnings("unchecked")
    private static void put(Object map, String key, Object value) {
        ((Map<String, Object>) map).put(key, value);
    }

    private static void initialize() {
        initialized = true;
        try {
            Class<?> client = Class.forName("com.coflnet.CoflModClient");
            Class<?> request = Class.forName("com.coflnet.core.DescriptionRequest");
            Class<?> handler = Class.forName("CoflCore.handlers.DescriptionHandler");
            api = new Api(client.getMethod("captureDescriptionRequest", String.class, NonNullList.class),
                    request.getMethod("fetch"), client.getMethod("getIdFromStack", ItemStack.class),
                    client.getMethod("getMappedTooltipData", String.class),
                    client.getMethod("onDescriptionsApplied", String.class), handler.getField("tooltipItemIdMap"),
                    client.getField("knownIds"));
            ReiSkyblockProvider.LOGGER.info("SkyCofl virtual item price bridge enabled");
        } catch (ClassNotFoundException absent) {
            api = null;
        } catch (ReflectiveOperationException | LinkageError incompatible) { disable(incompatible); }
    }

    private static void disable(Throwable error) {
        api = null;
        ReiSkyblockProvider.LOGGER.warn("SkyCofl virtual item price bridge unavailable for this version", error);
    }
}
