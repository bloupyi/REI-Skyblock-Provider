package dev.reisb.data;

import java.math.BigDecimal;

public record Ingredient(String id, BigDecimal amount) {
    public static final Ingredient EMPTY = new Ingredient("", 0);

    public Ingredient(String id, long count) { this(id, BigDecimal.valueOf(count)); }

    public Ingredient {
        if (id == null || amount == null || amount.signum() < 0 || (id.isEmpty() != (amount.signum() == 0))) {
            throw new IllegalArgumentException("Invalid ingredient: " + id + ":" + amount);
        }
        if (!id.isEmpty() && !id.matches("[A-Za-z0-9_:.+;#\\-]+")) throw new IllegalArgumentException("Invalid ID: " + id);
        if (!id.equals("SKYBLOCK_COIN")) amount.longValueExact();
        amount = amount.stripTrailingZeros();
    }

    public static Ingredient parse(String value) {
        if (value == null || value.isBlank() || value.equals("NEU_SENTINEL_EMPTY")) return EMPTY;
        int separator = value.lastIndexOf(':');
        if (separator < 0) return new Ingredient(value, 1);
        if (separator == 0) throw new IllegalArgumentException("Missing identifier: " + value);
        String id = value.substring(0, separator);
        if (!id.matches("[A-Za-z0-9_:.+;#\\-]+")) throw new IllegalArgumentException("Invalid ID: " + id);
        var count = new BigDecimal(value.substring(separator + 1));
        if (count.signum() <= 0) throw new IllegalArgumentException("Non-positive quantity: " + value);
        return new Ingredient(id, count);
    }

    public boolean empty() { return id.isEmpty(); }
    public long count() { return amount.longValueExact(); }
    public String quantityText() { return amount.toPlainString(); }
}
