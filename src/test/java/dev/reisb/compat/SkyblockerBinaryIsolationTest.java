package dev.reisb.compat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import me.shedaniel.rei.api.common.plugins.REIPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SkyblockerBinaryIsolationTest {
    @Test
    void publishedSkyblockerEntrypointBinariesAreIsolatedWithoutCallingTheirHooks() throws Exception {
        String archive = System.getProperty("reisb.skyblockerJar", "");
        assumeTrue(!archive.isEmpty(), "Pass -PskyblockerJar to skyblockerIsolationAudit");
        var path = Path.of(archive).toAbsolutePath();
        assertTrue(Files.isRegularFile(path));
        var names = Set.of("de.hysky.skyblocker.compatibility.rei.SkyblockerREIClientPlugin",
                "de.hysky.skyblocker.compatibility.rei.SkyblockerREICommonPlugin");
        // Load the two real classes from the release JAR, even though unit fixtures use these names too.
        try (var loader = new URLClassLoader(new URL[]{path.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!names.contains(name)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        }) {
            for (String name : names) {
                var type = loader.loadClass(name);
                assertSame(loader, type.getClassLoader());
                assertEquals(path.toUri().toURL(), type.getProtectionDomain().getCodeSource().getLocation());
                var plugin = (REIPlugin<?>) type.getDeclaredConstructor().newInstance();
                assertTrue(SkyblockerReiIsolation.isSkyblockerPlugin(plugin));
                assertTrue(SkyblockerReiIsolation.guard(SkyblockerReiIsolationTest.provider(REIPlugin.class,
                        List.of(plugin)), () -> true).provide().isEmpty());
                System.out.println("Published Skyblocker class isolated: " + name);
            }
        }
    }
}
