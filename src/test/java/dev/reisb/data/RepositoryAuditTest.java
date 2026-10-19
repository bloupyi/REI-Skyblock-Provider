package dev.reisb.data;

import dev.reisb.item.ItemStackFactory;
import dev.reisb.item.SkyblockIds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

public class RepositoryAuditTest {
    @Test
    @EnabledIfSystemProperty(named = "reisb.repositoryArchive", matches = ".+")
    void parseAndConvertRealRepository() throws Exception {
        var snapshot = new RepositoryParser().parse(Path.of(System.getProperty("reisb.repositoryArchive")));
        String official = System.getProperty("reisb.hypixelItems", "");
        if (!official.isEmpty()) snapshot = new HypixelItems().merge(snapshot, Files.readString(Path.of(official)));
        System.out.println("Repository: " + snapshot.items().size() + " items, " + snapshot.recipes().size() + " recipes, " + snapshot.warnings().size() + " warnings");
        snapshot.warnings().stream().limit(20).forEach(System.out::println);
        assertTrue(snapshot.items().size() > 8000);
        assertTrue(snapshot.recipes().size() > 2000);
        for (String id : new String[]{"HYPERION", "ASPECT_OF_THE_END", "ENCHANTED_DIAMOND"}) {
            assertTrue(snapshot.items().containsKey(id));
            assertTrue(snapshot.recipes().stream().anyMatch(r -> r.output().id().equals(id)));
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.ITEM.forEach(item ->
                item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY));
        var factory = new ItemStackFactory(LoggerFactory.getLogger("audit"));
        var errors = new java.util.ArrayList<String>();
        var heads = new AtomicInteger();
        var unresolvedLevels = new java.util.ArrayList<String>();
        for (var item : snapshot.items().values()) {
            var stack = factory.create(item);
            if (!item.minecraftId().equals("minecraft:barrier") && stack.is(Items.BARRIER)) errors.add(item.id());
            assertEquals(item.id(), SkyblockIds.of(stack));
            if (stack.getHoverName().getString().contains("{LVL}") || stack.get(DataComponents.LORE).lines().stream()
                    .anyMatch(line -> line.getString().matches(".*\\{(?:[A-Z_]+|[0-9]+)\\}.*"))) unresolvedLevels.add(item.id());
            if (stack.is(Items.PLAYER_HEAD) && stack.get(DataComponents.PROFILE) != null) heads.incrementAndGet();
        }
        var kinds = new EnumMap<SkyblockRecipe.Kind, Integer>(SkyblockRecipe.Kind.class);
        snapshot.recipes().forEach(r -> kinds.merge(r.kind(), 1, Integer::sum));
        Path report = Path.of("build/reports/repository-audit.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "Items: " + snapshot.items().size() + "\nRecipes: " + snapshot.recipes().size()
                + "\nCategories: " + kinds + "\nWarnings: " + snapshot.warnings().size()
                + "\nUnsupported types: " + snapshot.unsupported() + "\nTextured heads: " + heads.get()
                + "\nConversion fallbacks: " + errors.size() + " " + errors
                + "\nUnresolved level placeholders: " + unresolvedLevels.size() + " " + unresolvedLevels
                + "\n\nWarnings:\n" + String.join("\n", snapshot.warnings()));
        assertTrue(heads.get() > 100);
        assertTrue(errors.isEmpty(), () -> "Conversion failures: " + errors);
        assertTrue(unresolvedLevels.isEmpty(), () -> "Unresolved pet levels: " + unresolvedLevels);
    }
}
