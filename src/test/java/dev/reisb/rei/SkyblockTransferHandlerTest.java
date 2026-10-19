package dev.reisb.rei;

import dev.reisb.data.Ingredient;
import dev.reisb.data.RepositorySnapshot;
import dev.reisb.data.SkyblockRecipe;
import dev.reisb.item.PreparedRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SkyblockTransferHandlerTest {
    private SkyblockDisplay display(SkyblockRecipe.Kind kind, String id) {
        var craft = new SkyblockRecipe(SkyblockRecipe.Kind.CRAFTING, Collections.nCopies(9, new Ingredient("INGREDIENT", 1)),
                new Ingredient(id, 1), 0, "", "");
        var repository = new PreparedRepository(RepositorySnapshot.EMPTY, Map.of(), Map.of(id, List.of(craft)), Map.of());
        return new SkyblockDisplay(kind == SkyblockRecipe.Kind.CRAFTING ? craft
                : new SkyblockRecipe(kind, List.of(), craft.output(), 0, "", ""), repository);
    }

    @Test
    void previewsNeverSendCommandsAndOneClickSendsOneRecipeRequest() {
        var commands = new ArrayList<String>();
        var handler = new SkyblockTransferHandler(() -> true, commands::add);
        var display = display(SkyblockRecipe.Kind.CRAFTING, "HYPERION");
        var preview = TransferHandler.Context.create(false, false, null, display);
        assertTrue(handler.checkApplicable(preview).isSuccessful());
        for (int i = 0; i < 10; i++) assertTrue(handler.handle(preview).isSuccessful());
        assertTrue(commands.isEmpty());
        assertTrue(handler.handle(TransferHandler.Context.create(true, false, null, display)).isSuccessful());
        assertEquals(List.of("viewrecipe HYPERION"), commands);
        assertTrue(handler.checkApplicable(TransferHandler.Context.create(false, false, null, display(SkyblockRecipe.Kind.INFO, "HYPERION"))).isSuccessful());
    }

    @Test
    void offlineAndNonCraftingDisplaysCannotSendCommands() {
        var commands = new ArrayList<String>();
        var offline = new SkyblockTransferHandler(() -> false, commands::add);
        var context = TransferHandler.Context.create(true, false, null, display(SkyblockRecipe.Kind.CRAFTING, "HYPERION"));
        assertFalse(offline.checkApplicable(context).isSuccessful());
        assertFalse(offline.handle(context).isSuccessful());
        var online = new SkyblockTransferHandler(() -> true, commands::add);
        assertFalse(online.checkApplicable(TransferHandler.Context.create(true, false, null, display(SkyblockRecipe.Kind.DROPS, "HYPERION"))).isApplicable());
        assertFalse(online.checkApplicable(TransferHandler.Context.create(true, false, null, display(SkyblockRecipe.Kind.CRAFTING, "SHARPNESS;6"))).isApplicable());
        var unknown = new SkyblockDisplay(new SkyblockRecipe(SkyblockRecipe.Kind.INFO, List.of(), new Ingredient("UNKNOWN", 1), 0, "", ""), PreparedRepository.EMPTY);
        assertFalse(online.checkApplicable(TransferHandler.Context.create(true, false, null, unknown)).isApplicable());
        assertTrue(commands.isEmpty());
    }
}
