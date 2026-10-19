package dev.reisb.planning;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ObservedMenus {
    private static final Pattern STORED = Pattern.compile("^Stored: ([0-9][0-9,]*)/\\S+$");
    private static final Pattern LEVEL = Pattern.compile("^(.+?) ([0-9]+|[IVXLCDM]+)(?: ✯)?$");
    private static final Pattern SLAYER = Pattern.compile("(?:LVL UP! ➜ )?(Wolf|Zombie|Spider|Enderman|Blaze|Vampire) Slayer LVL ([0-9]+)(?: -.*)?");
    public record Level(String key, int value) {}
    private ObservedMenus() {}

    public static String clean(String text) { return text.replaceAll("§[0-9A-FK-ORa-fk-or]", "").strip(); }
    public static String key(String text) { return clean(text).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", ""); }

    public static Optional<BigDecimal> stored(String title, String itemName, List<String> lore) {
        return stored(title, "", itemName, lore);
    }

    public static Optional<BigDecimal> stored(String title, String id, String itemName, List<String> lore) {
        String menu = clean(title);
        if (!menu.matches(".*\\bSacks?(?: .*)?") && !menu.endsWith("Gemstones")) return Optional.empty();
        if (clean(itemName).endsWith("Gemstone")) {
            if (!id.matches("(ROUGH|FLAWED|FINE|FLAWLESS|PERFECT)_[A-Z_]+_GEM")) return Optional.empty();
            for (String line : lore) {
                var amount = Pattern.compile("^Amount: ([0-9][0-9,]*)$").matcher(clean(line));
                if (amount.matches()) return Optional.of(new BigDecimal(amount.group(1).replace(",", "")));
            }
            return Optional.empty();
        }
        for (String line : lore) {
            var matcher = STORED.matcher(clean(line));
            if (matcher.matches()) {
                try { return Optional.of(new BigDecimal(matcher.group(1).replace(",", ""))); }
                catch (NumberFormatException ignored) { return Optional.empty(); }
            }
        }
        return Optional.empty();
    }

    public static Optional<Level> level(String title, String itemName) {
        String menu = clean(title);
        var matcher = LEVEL.matcher(clean(itemName));
        if (!matcher.matches()) return Optional.empty();
        String name = key(matcher.group(1));
        int value = number(matcher.group(2));
        if (value < 0) return Optional.empty();
        if (menu.matches("\\w+ Collections(?: .*)?")) return Optional.of(new Level("COLLECTION:" + name, value));
        if (menu.matches("(?:Your )?Skills(?: .*)?")) return Optional.of(new Level("SKILL:" + name, value));
        if (menu.startsWith("Dungeoneering") && (name.equals("CATACOMBS") || name.equals("THE_CATACOMBS"))) return Optional.of(new Level("DUNGEON_SKILL:CATACOMBS", value));
        return Optional.empty();
    }

    public static String resourceId(String itemName, String fallback, java.util.Map<String, String> names) {
        String name = clean(itemName).replaceAll(" [0-9IVXLCDM]+(?: ✯)?$", "");
        return names.getOrDefault(key(name), fallback);
    }

    public static java.util.Map<String, String> resourceNames(java.util.Collection<dev.reisb.data.SkyblockItem> items) {
        var names = new java.util.HashMap<String, String>();
        var ambiguous = new java.util.HashSet<String>();
        for (var item : items) {
            String name = key(item.name());
            String previous = names.putIfAbsent(name, item.id());
            if (previous != null && !previous.equals(item.id())) ambiguous.add(name);
        }
        ambiguous.forEach(names::remove);
        return java.util.Map.copyOf(names);
    }

    public static Optional<Level> slayer(String text) {
        var matcher = SLAYER.matcher(clean(text));
        return matcher.matches() ? Optional.of(new Level("SLAYER:" + key(matcher.group(1)), Integer.parseInt(matcher.group(2)))) : Optional.empty();
    }

    public static int number(String text) {
        if (text.matches("[0-9]{1,4}")) return Integer.parseInt(text);
        if (!text.matches("M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})") || text.isEmpty()) return -1;
        int result = 0, previous = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int current = switch (text.charAt(i)) { case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; case 'C' -> 100; case 'D' -> 500; default -> 1000; };
            result += current < previous ? -current : current;
            previous = current;
        }
        return result;
    }
}
