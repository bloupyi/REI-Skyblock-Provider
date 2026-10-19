package dev.reisb.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SkyblockDetectorTest {
    @Test
    void requiresNetworkAndIndependentGameSignal() {
        assertTrue(SkyblockDetector.matches("mc.hypixel.net:25565", "BungeeCord", "§e§lSKYBLOCK", false));
        assertTrue(SkyblockDetector.matches("proxy.example", "Hypixel BungeeCord", "SKYBLOCK CO-OP", false));
        assertTrue(SkyblockDetector.matches("hypixel.io", "", "", true));
        assertFalse(SkyblockDetector.matches("mc.hypixel.net", "", "BED WARS", false));
        assertFalse(SkyblockDetector.matches("hypixel.net.evil.example", "", "SKYBLOCK", true));
        assertFalse(SkyblockDetector.matches("localhost", null, "SKYBLOCK", false));
    }
}
