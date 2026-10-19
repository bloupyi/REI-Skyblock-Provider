package dev.reisb.data;

import java.util.List;

public record SkyblockRecipe(Kind kind, List<Ingredient> inputs, Ingredient output,
                             long seconds, String extra, String station) {
    public enum Kind { CRAFTING, FORGE, KAT, NPC, DROPS, INFO }

    public SkyblockRecipe {
        inputs = List.copyOf(inputs);
        if (output.empty() || seconds < 0) throw new IllegalArgumentException("Invalid recipe output/duration");
        if (kind == Kind.CRAFTING && inputs.size() != 9) throw new IllegalArgumentException("Crafting grid must contain nine slots");
    }
}
