package dev.reisb.util;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;

public final class SkyblockDetector {
    private SkyblockDetector() {}

    public static boolean isSkyblock(Minecraft client) {
        if (client.level == null || client.player == null || client.isLocalServer()) return false;
        String address = client.getCurrentServer() == null ? "" : client.getCurrentServer().ip;
        String brand = client.player.connection.serverBrand();
        var sidebar = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        String title = sidebar == null ? "" : sidebar.getDisplayName().getString();
        boolean tabSignal = client.player.connection.getOnlinePlayers().stream()
                .map(player -> player.getTabListDisplayName())
                .filter(java.util.Objects::nonNull)
                .map(component -> clean(component.getString()))
                .anyMatch(text -> text.startsWith("AREA:") || text.startsWith("PROFILE:") || text.startsWith("SKILLS:"));
        return matches(address, brand, title, tabSignal);
    }

    public static boolean matches(String address, String brand, String sidebarTitle, boolean skyblockTab) {
        String host = address.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "").replaceFirst("\\.$", "");
        boolean network = host.equals("hypixel.net") || host.endsWith(".hypixel.net")
                || host.equals("hypixel.io") || host.endsWith(".hypixel.io")
                || (brand != null && brand.toLowerCase(Locale.ROOT).contains("hypixel"));
        String title = clean(sidebarTitle);
        boolean objective = title.equals("SKYBLOCK") || title.startsWith("SKYBLOCK ") || title.equals("SKYBLOCK CO-OP");
        return network && (objective || skyblockTab);
    }

    private static String clean(String text) {
        return text.replaceAll("\u00a7[0-9A-FK-ORa-fk-or]", "").strip().toUpperCase(Locale.ROOT);
    }
}
