package dev.reisb.item;

import dev.reisb.data.RepositoryParser;
import dev.reisb.data.RepositoryParserTest;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

public class ItemConversionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.ITEM.forEach(item ->
                item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY));
    }

    @Test
    void actualNeuItemsRetainIdentityLoreAndHeadTexture() throws Exception {
        var parser = new RepositoryParser();
        var factory = new ItemStackFactory(LoggerFactory.getLogger("test"));
        var hyperion = factory.create(parser.parseItem(RepositoryParserTest.fixture("HYPERION")));
        assertTrue(hyperion.is(Items.IRON_SWORD));
        assertEquals("HYPERION", SkyblockIds.of(hyperion));
        assertEquals("Hyperion", hyperion.getHoverName().getString());
        assertTrue(hyperion.get(DataComponents.LORE).lines().size() > 10);
        assertTrue(SkyblockIds.owned(hyperion));
        var metadata = hyperion.get(DataComponents.CUSTOM_DATA).copyTag();
        assertEquals("HYPERION", metadata.getStringOr("id", ""));
        assertFalse(metadata.contains("ExtraAttributes"));
        var pet = factory.create(parser.parseItem(RepositoryParserTest.fixture("ARMADILLO;0")));
        assertTrue(pet.is(Items.PLAYER_HEAD));
        assertNotNull(pet.get(DataComponents.PROFILE));
        assertEquals("ARMADILLO;0", SkyblockIds.of(pet));
        assertFalse(SkyblockIds.owned(Items.IRON_SWORD.getDefaultInstance()));
        assertEquals("", SkyblockIds.of(Items.DIAMOND.getDefaultInstance()));
    }

    @Test
    void officialMetadataKeepsGameLoreAndModernSkyblockAttributes() {
        var item = new dev.reisb.data.SkyblockItem("TEST", "minecraft:bow", 0,
                "{ExtraAttributes:{id:\"TEST\",enchantments:{power:7},modifier:\"hasty\",dungeon_item_level:5},Unbreakable:1b,ItemModel:\"minecraft:bow\"}",
                "§6Test", java.util.List.of("§7Damage: §c+10", "§7Crit Damage: §9+50%", "", "§6§lLEGENDARY BOW"), java.util.List.of(),
                "{\"tier\":\"LEGENDARY\",\"glowing\":true,\"stats\":{\"DAMAGE\":310,\"CRITICAL_DAMAGE\":250}}");
        var stack = new ItemStackFactory(LoggerFactory.getLogger("test")).create(item, true);
        var custom = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        assertEquals(7, custom.getCompoundOrEmpty("enchantments").getIntOr("power", 0));
        assertEquals("hasty", custom.getStringOr("modifier", ""));
        assertEquals(5, custom.getIntOr("dungeon_item_level", 0));
        assertFalse(custom.contains("ExtraAttributes"));
        assertEquals(java.util.List.of("Damage: +310", "Crit Damage: +250%", "", "LEGENDARY BOW"),
                stack.get(DataComponents.LORE).lines().stream().map(net.minecraft.network.chat.Component::getString).toList());
        assertEquals(Boolean.TRUE, stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        assertEquals(net.minecraft.resources.Identifier.withDefaultNamespace("bow"), stack.get(DataComponents.ITEM_MODEL));
        assertFalse(stack.get(DataComponents.TOOLTIP_DISPLAY).shows(DataComponents.UNBREAKABLE));
    }

    @Test
    void vanillaIngredientsAndLiveBooksResolveWithoutAnotherRecipe() throws Exception {
        var pearl = new dev.reisb.data.SkyblockItem("ENDER_PEARL", "minecraft:ender_pearl", 0, "{}", "Ender Pearl", java.util.List.of(), java.util.List.of());
        var snapshot = new dev.reisb.data.RepositorySnapshot(java.util.Map.of("ENDER_PEARL", pearl), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        assertEquals("ENDER_PEARL", SkyblockIds.resolve(Items.ENDER_PEARL.getDefaultInstance(), snapshot));
        assertEquals("", SkyblockIds.resolve(Items.DIAMOND.getDefaultInstance(), snapshot));
        var book = new ItemStackFactory(LoggerFactory.getLogger("test")).create(new dev.reisb.data.SkyblockItem("LOOTING;4", "minecraft:enchanted_book", 0,
                "{ExtraAttributes:{id:\"ENCHANTED_BOOK\",enchantments:{looting:4}}}", "Looting IV", java.util.List.of(), java.util.List.of()), true);
        assertEquals("LOOTING;4", SkyblockIds.resolve(book, snapshot));
    }

    @Test
    void liveHypixelPetBookAndRuneIdsMatchRepositoryVariants() throws Exception {
        var pet = new CompoundTag();
        pet.putString("id", "PET");
        pet.putString("petInfo", "{\"type\":\"ARMADILLO\",\"tier\":\"RARE\"}");
        assertEquals("ARMADILLO;2", SkyblockIds.fromTag(pet));
        var book = new CompoundTag();
        book.putString("id", "ENCHANTED_BOOK");
        var enchants = new CompoundTag();
        enchants.putInt("sharpness", 6);
        book.put("enchantments", enchants);
        assertEquals("SHARPNESS;6", SkyblockIds.fromTag(book));
        var wrapped = new CompoundTag();
        wrapped.put("ExtraAttributes", book);
        assertEquals("SHARPNESS;6", SkyblockIds.fromTag(wrapped));
        assertThrows(Exception.class, () -> LegacyStringNbtReader.parse("{broken"));
    }
}
