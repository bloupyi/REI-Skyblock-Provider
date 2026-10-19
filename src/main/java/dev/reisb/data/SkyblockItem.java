package dev.reisb.data;

import java.util.List;

public record SkyblockItem(String id, String minecraftId, int damage, String nbt,
                           String name, List<String> lore, List<String> info, String officialMetadata, String levelData) {
    public SkyblockItem(String id, String minecraftId, int damage, String nbt,
                        String name, List<String> lore, List<String> info, String officialMetadata) {
        this(id, minecraftId, damage, nbt, name, lore, info, officialMetadata, "");
    }
    public SkyblockItem(String id, String minecraftId, int damage, String nbt,
                        String name, List<String> lore, List<String> info) {
        this(id, minecraftId, damage, nbt, name, lore, info, "");
    }
    public SkyblockItem {
        if (id == null || id.isBlank() || minecraftId == null || minecraftId.isBlank()) {
            throw new IllegalArgumentException("Missing item identifier");
        }
        lore = List.copyOf(lore);
        info = List.copyOf(info);
        levelData = levelData == null ? "" : levelData;
    }
}
