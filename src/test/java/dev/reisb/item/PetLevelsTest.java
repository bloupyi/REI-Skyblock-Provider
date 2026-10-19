package dev.reisb.item;

import dev.reisb.data.SkyblockItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PetLevelsTest {
    private SkyblockItem pet(String id) {
        return new SkyblockItem(id, "minecraft:skull", 3, "{}", "[Lvl {LVL}] Pet",
                List.of("Strength: +{STRENGTH}", "Ability: {0}%"), List.of());
    }

    @Test
    void oldCachesUseRealPetRangesOffline() {
        var dragon = pet("ENDER_DRAGON;3");
        assertEquals("[Lvl 1 → 100] Pet", PetLevels.name(dragon));
        assertEquals(List.of("Strength: +0.5 → 50", "Ability: 2 → 200%"), PetLevels.lore(dragon, dragon.lore()));
        var golden = pet("GOLDEN_DRAGON;4");
        assertEquals("[Lvl 1 → 200] Pet", PetLevels.name(golden));
        assertTrue(PetLevels.lore(golden, golden.lore()).getLast().contains("101 → 200"));
        var eagle = new SkyblockItem("EAGLE;4", "minecraft:skull", 3, "{}", "[Lvl {LVL}] Eagle", List.of("Health: +25", "§6§lLEGENDARY PET"), List.of());
        assertEquals("[Lvl 1 → 100] Eagle", PetLevels.name(eagle));
        assertEquals("Health: +25", PetLevels.lore(eagle, eagle.lore()).getFirst());
        assertTrue(PetLevels.lore(eagle, eagle.lore()).getLast().contains("unavailable"));
    }

    @Test
    void downloadedConstantsOverrideBundledValues() {
        var old = pet("ENDER_DRAGON;3");
        var item = new SkyblockItem(old.id(), old.minecraftId(), old.damage(), old.nbt(), old.name(), old.lore(), old.info(), "",
                "{\"1\":{\"statNums\":{\"STRENGTH\":1},\"otherNums\":[3]},\"100\":{\"statNums\":{\"STRENGTH\":200},\"otherNums\":[400]}}");
        assertEquals(List.of("Strength: +1 → 200", "Ability: 3 → 400%"), PetLevels.lore(item, item.lore()));
        assertEquals("[Lvl 1 → 100] Pet", PetLevels.name(item));
    }
}
