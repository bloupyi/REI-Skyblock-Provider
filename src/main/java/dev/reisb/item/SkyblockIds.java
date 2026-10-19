package dev.reisb.item;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class SkyblockIds {
    public static final String MARKER = "rei_skyblock_provider";
    private static final List<String> TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL");
    private SkyblockIds() {}

    public static boolean owned(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBooleanOr(MARKER, false);
    }

    public static String of(ItemStack stack) {
        if (stack.isEmpty()) return "";
        return fromTag(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag());
    }

    public static String resolve(ItemStack stack, dev.reisb.data.RepositorySnapshot repository) {
        String id = of(stack);
        if (!id.isEmpty() || stack.isEmpty()) return id;
        var custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (custom.contains("id") || custom.contains("ExtraAttributes")) return "";
        var material = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!material.getNamespace().equals("minecraft")) return "";
        String candidate = material.getPath().toUpperCase(Locale.ROOT);
        var item = repository.items().get(candidate);
        return item != null && item.minecraftId().equals(material.toString()) ? candidate : "";
    }

    public static String fromTag(CompoundTag data) {
        String own = data.getStringOr(MARKER + "_id", "");
        if (!own.isEmpty()) return own;
        CompoundTag attributes = data.getCompound("ExtraAttributes").orElse(data);
        String id = attributes.getStringOr("id", "");
        if (id.equals("PET")) {
            try {
                var pet = JsonParser.parseString(attributes.getStringOr("petInfo", "{}")).getAsJsonObject();
                int tier = TIERS.indexOf(pet.get("tier").getAsString());
                return tier >= 0 ? pet.get("type").getAsString() + ";" + tier : id;
            } catch (RuntimeException e) { return id; }
        }
        if (id.equals("ENCHANTED_BOOK")) return variant(attributes, "enchantments", "");
        if (id.equals("RUNE") || id.equals("UNIQUE_RUNE")) return variant(attributes, "runes", "_RUNE");
        if (id.equals("POTION")) return "POTION_" + attributes.getStringOr("potion", "").toUpperCase(Locale.ROOT)
                + ";" + attributes.getIntOr("potion_level", 0);
        return id.replace(':', '-');
    }

    private static String variant(CompoundTag attributes, String key, String suffix) {
        CompoundTag values = attributes.getCompoundOrEmpty(key);
        return values.keySet().stream().sorted().findFirst()
                .map(name -> name.toUpperCase(Locale.ROOT) + suffix + ";" + values.getIntOr(name, 0)).orElse("");
    }
}
