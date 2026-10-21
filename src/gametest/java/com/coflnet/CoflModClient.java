package com.coflnet;

import CoflCore.handlers.DescriptionHandler;
import com.coflnet.core.DescriptionRequest;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

public final class CoflModClient {
    public static final java.util.Map<String, Boolean> knownIds = new java.util.HashMap<>();
    public static volatile boolean failRequest;
    public static volatile boolean failCapture;
    public static final java.util.concurrent.atomic.AtomicInteger captures = new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

    public static String getIdFromStack(ItemStack stack) { return stack.getHoverName().getString() + ";" + stack.getCount(); }

    public static DescriptionRequest captureDescriptionRequest(String title, NonNullList<ItemStack> items) {
        captures.incrementAndGet();
        if (failCapture) throw new IllegalStateException("Simulated item serialization failure");
        var ops = net.minecraft.client.Minecraft.getInstance().player.registryAccess()
                .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        for (var stack : items) ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        return new DescriptionRequest(items.stream().map(ItemStack::copy).toList());
    }

    public static DescriptionHandler.DescModification[] getMappedTooltipData(String id) {
        return DescriptionHandler.tooltipItemIdMap.get(id);
    }

    public static void onDescriptionsApplied(String title) {}
}
