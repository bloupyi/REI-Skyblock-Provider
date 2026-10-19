package dev.reisb.rei;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

public final class DisplayText {
    private DisplayText() {}

    public static String coins(BigDecimal amount) { return quantity(amount) + " coins"; }

    public static String quantity(BigDecimal amount) {
        String[] parts = amount.stripTrailingZeros().toPlainString().split("\\.", 2);
        String whole = parts[0].replaceAll("\\B(?=(\\d{3})+(?!\\d))", ",");
        return whole + (parts.length == 2 ? "." + parts[1] : "");
    }

    public static List<URI> wikiLinks(List<String> info) {
        return info.stream().map(value -> {
            try {
                URI uri = URI.create(value);
                return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) ? uri : null;
            } catch (IllegalArgumentException e) { return null; }
        }).filter(java.util.Objects::nonNull).distinct().toList();
    }
}
