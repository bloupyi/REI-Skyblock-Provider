package dev.reisb.pricing;

import dev.reisb.ReiSkyblockProvider;
import dev.reisb.item.SkyblockIds;
import dev.reisb.rei.DisplayText;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import me.shedaniel.rei.api.client.gui.widgets.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MarketPrices {
    private static MarketPriceService service = new MarketPriceService(MarketPriceService.LOWEST_BIN, MarketPriceService.BAZAAR, Clock.systemUTC());
    private static final List<String> TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL");

    private MarketPrices() {}

    public static void request() { service.refresh(); }
    public static String status() { return ReiSkyblockProvider.config().enableMarketPrices ? service.status() : "disabled"; }

    public static void append(ItemStack stack, Tooltip tooltip) {
        String id = SkyblockIds.of(stack);
        if (id.isEmpty() || id.equals("SKYBLOCK_COIN")) return;
        long now = System.currentTimeMillis();
        var bazaar = service.bazaar();
        var auction = service.lowestBin();
        var quote = bazaar.fresh(now) ? bazaar.items().get(bazaarId(stack, id)) : null;
        boolean bazaarQuote = quote != null;
        var lines = new ArrayList<Component>();
        if (quote != null) {
            add(lines, "bazaar_buy", quote.buy(), stack.getCount());
            add(lines, "bazaar_sell", quote.sell(), stack.getCount());
        } else if (auction.fresh(now)) {
            quote = auction.items().get(id);
            if (quote != null) add(lines, "lowest_bin", quote.lowestBin(), stack.getCount());
            if (quote == null && id.matches(".+;[0-8]")) {
                int separator = id.lastIndexOf(';');
                String pet = TIERS.get(Integer.parseInt(id.substring(separator + 1))) + "_" + id.substring(0, separator);
                for (int level : new int[]{1, 100, 200}) {
                    var price = auction.items().get("LVL_" + level + "_" + pet);
                    if (price != null) lines.add(Component.translatable("pricing.rei_skyblock_provider.pet_bin", level, DisplayText.coins(price.lowestBin())));
                }
            }
        }
        if (lines.isEmpty()) {
            String state = bazaar.fresh(now) && auction.fresh(now) ? "no_listing"
                    : service.status().contains("loading") ? "loading" : "unavailable";
            tooltip.add(Component.translatable("pricing.rei_skyblock_provider." + state));
            return;
        }
        tooltip.add(Component.translatable("pricing.rei_skyblock_provider.header"));
        lines.forEach(tooltip::add);
        if (!bazaarQuote) {
            tooltip.add(Component.translatable("pricing.rei_skyblock_provider.base_item"));
        }
    }

    private static String bazaarId(ItemStack stack, String id) {
        if (!stack.is(Items.ENCHANTED_BOOK) || !id.contains(";")) return id;
        return "ENCHANTMENT_" + id.replace(';', '_');
    }

    private static void add(List<Component> lines, String key, BigDecimal amount, int count) {
        if (amount == null) return;
        lines.add(Component.translatable("pricing.rei_skyblock_provider." + key, DisplayText.coins(amount)));
        if (count > 1) lines.add(Component.translatable("pricing.rei_skyblock_provider.total", count,
                DisplayText.coins(amount.multiply(BigDecimal.valueOf(count)))));
    }
}
