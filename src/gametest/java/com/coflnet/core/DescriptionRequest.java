package com.coflnet.core;

import CoflCore.handlers.DescriptionHandler;
import java.util.List;
import net.minecraft.world.item.ItemStack;

public record DescriptionRequest(List<ItemStack> items) {
    public DescriptionHandler.DescModification[][] fetch() {
        com.coflnet.CoflModClient.requests.incrementAndGet();
        if (com.coflnet.CoflModClient.failRequest) throw new IllegalStateException("Simulated price service failure");
        return new DescriptionHandler.DescModification[][]{{new DescriptionHandler.DescModification("Estimated Value: 123 coins")}};
    }
}
