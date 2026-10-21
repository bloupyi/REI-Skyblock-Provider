package dev.reisb.item;

import com.mojang.serialization.Dynamic;
import dev.reisb.data.SkyblockItem;
import java.util.ArrayList;
import net.azureaaron.legacyitemdfu.LegacyItemStackFixer;
import net.azureaaron.legacyitemdfu.TypeReferences;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import org.slf4j.Logger;

public final class ItemStackFactory {
    private static final class Registries {
        private static final HolderLookup.Provider LOOKUP = VanillaRegistries.createLookup();
    }
    private final HolderLookup.Provider lookup;
    private final Logger logger;

    public ItemStackFactory(Logger logger) {
        this.lookup = Registries.LOOKUP;
        this.logger = logger;
    }

    public static ItemStack forRegistry(ItemStack stack, HolderLookup.Provider registry) {
        var encoded = ItemStack.CODEC.encodeStart(Registries.LOOKUP.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
        return ItemStack.CODEC.parse(registry.createSerializationContext(NbtOps.INSTANCE), encoded).getOrThrow();
    }

    public ItemStack create(SkyblockItem item) {
        return create(item, false);
    }

    public ItemStack create(SkyblockItem item, boolean strict) {
        ItemStack stack;
        net.minecraft.resources.Identifier itemModel = null;
        CompoundTag legacyTag = new CompoundTag();
        try {
            legacyTag = LegacyStringNbtReader.parse(item.nbt());
            if (item.officialMetadata() != null && !item.officialMetadata().isEmpty()) {
                OfficialItemData.apply(legacyTag, com.google.gson.JsonParser.parseString(item.officialMetadata()).getAsJsonObject());
            }
            String model = legacyTag.getStringOr("ItemModel", "");
            if (!model.isEmpty()) {
                var identifier = net.minecraft.resources.Identifier.tryParse(model);
                var client = net.minecraft.client.Minecraft.getInstance();
                if (identifier != null && (identifier.getNamespace().equals("minecraft") || client != null && client.getResourceManager()
                        .getResource(identifier.withPath(path -> "items/" + path + ".json")).isPresent())) itemModel = identifier;
                else legacyTag.remove("ItemModel");
            }
            var legacy = new CompoundTag();
            legacy.put("tag", legacyTag);
            legacy.putString("id", item.minecraftId());
            legacy.putShort("Damage", (short) item.damage());
            legacy.putInt("Count", 1);
            var ops = lookup.createSerializationContext(NbtOps.INSTANCE);
            var fixed = LegacyItemStackFixer.getFixer().update(TypeReferences.LEGACY_ITEM_STACK,
                    new Dynamic<>(ops, legacy), LegacyItemStackFixer.getFirstVersion(), LegacyItemStackFixer.getLatestVersion());
            stack = ItemStack.CODEC.parse(fixed).getOrThrow();
            if (stack.isEmpty()) throw new IllegalArgumentException("Item conversion produced air");
        } catch (Exception e) {
            if (strict) throw new IllegalArgumentException("Cannot convert " + item.id() + ": " + e.getMessage(), e);
            logger.warn("Cannot convert item {}: {}", item.id(), e.getMessage());
            stack = new ItemStack(Items.BARRIER);
        }
        CompoundTag custom = legacyTag.getCompoundOrEmpty("ExtraAttributes").copy();
        if (!custom.contains("id")) custom.putString("id", item.id());
        if (custom.contains("petInfo")) custom.putString("id", "PET");
        custom.putBoolean(SkyblockIds.MARKER, true);
        custom.putString(SkyblockIds.MARKER + "_id", item.id());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse(PetLevels.name(item)));
        var lore = new ArrayList<Component>();
        PetLevels.lore(item, OfficialItemData.lore(item)).stream().map(LegacyText::parse).forEach(lore::add);
        stack.set(DataComponents.LORE, new ItemLore(lore));
        if (itemModel != null) stack.set(DataComponents.ITEM_MODEL, itemModel);
        if (item.officialMetadata() != null && !item.officialMetadata().isEmpty()) {
            var metadata = com.google.gson.JsonParser.parseString(item.officialMetadata()).getAsJsonObject();
            if (metadata.has("glowing")) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, metadata.get("glowing").getAsBoolean());
        }
        stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT
                .withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true).withHidden(DataComponents.ENCHANTMENTS, true)
                .withHidden(DataComponents.UNBREAKABLE, true).withHidden(DataComponents.DYED_COLOR, true));
        return stack;
    }
}
