package com.waterfall.aarch;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the aarch64 versions of Waterfall's native libraries
 * (libdirection-0.0.1.so / libheavy-0.0.1.so) bundled inside this mod's jar,
 * and registers them with the JVM and JNA BEFORE the Waterfall mod's own
 * NativeLoader gets a chance to run.
 *
 * Why this exists:
 *   Waterfall ships x86_64 .so files inside its jar at /natives/. On an aarch64
 *   host those .so files cannot be dlopen-ed, so Waterfall's NativeLoader throws
 *   UnsatisfiedLinkError and JNA's Native.load("direction-0.0.1", ...) fails.
 *
 *   This mod declares an ordering="BEFORE" dependency on the "waterfall" mod,
 *   so its @Mod constructor (and therefore this loader) runs first. We extract
 *   our aarch64 .so files to a temp dir, System.load() them into the JVM, and
 *   prepend that temp dir to jna.library.path. When Waterfall's NativeLoader
 *   later tries to load its bundled x86_64 .so, that System.load() fails (wrong
 *   arch) but our libraries are already loaded and discoverable by JNA - so
 *   DirectionLibrary.INSTANCE / HeavyLibrary.INSTANCE bind to our aarch64
 *   symbols instead.
 */
public final class AarchNativeLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Library short name -> bundled resource path. The on-disk file name MUST
    // match what JNA looks up via Native.load(shortName, ...) - i.e.
    // "lib" + shortName + ".so" on Linux. Waterfall uses "direction-0.0.1"
    // and "heavy-0.0.1" as the short names (see DirectionLibrary.INSTANCE /
    // HeavyLibrary.INSTANCE), so our .so files keep the same naming.
    private static final Map<String, String> LIBRARIES = new LinkedHashMap<>();
    static {
        LIBRARIES.put("direction-0.0.1", "/natives/libdirection-0.0.1.so");
        LIBRARIES.put("heavy-0.0.1",     "/natives/libheavy-0.0.1.so");
    }

    private static volatile boolean loaded = false;

    private AarchNativeLoader() {}

    /**
     * Extract and System.load() every bundled aarch64 library, then prepend
     * the temp dir to jna.library.path. Safe to call more than once.
     */
    public static synchronized void loadAll() {
        if (loaded) {
            LOGGER.debug("[waterfall-aarch] Libraries already loaded, skipping");
            return;
        }

        // Only meaningful on aarch64 Linux. On any other arch this mod is a
        // no-op - Waterfall's own x86_64 libraries are correct in that case,
        // and trying to dlopen our aarch64 .so there would just throw.
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        if (!osArch.contains("aarch64") && !osArch.contains("arm64")) {
            LOGGER.info("[waterfall-aarch] os.arch={} - not aarch64, skipping native load "
                    + "(letting Waterfall use its bundled libs)", osArch);
            loaded = true;
            return;
        }

        try {
            Path tempDir = Files.createTempDirectory("waterfall-aarch-natives");
            LOGGER.info("[waterfall-aarch] Extracting aarch64 natives to {}", tempDir);

            for (Map.Entry<String, String> entry : LIBRARIES.entrySet()) {
                String shortName = entry.getKey();
                String resourcePath = entry.getValue();

                try (InputStream in = AarchNativeLoader.class.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new UnsatisfiedLinkError(
                                "Bundled aarch64 native not found in jar: " + resourcePath);
                    }
                    String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                    Path target = tempDir.resolve(fileName);
                    try (OutputStream out = Files.newOutputStream(target)) {
                        in.transferTo(out);
                    }
                    // .so needs to be readable+executable for dlopen
                    target.toFile().setExecutable(true, false);

                    // System.load() the absolute path so the JVM registers the
                    // library by its real path. Later, when JNA's Native.load
                    // dlopens the same path, glibc returns the cached handle.
                    System.load(target.toAbsolutePath().toString());
                    LOGGER.info("[waterfall-aarch] Loaded {} (resource {}) from {}",
                            shortName, resourcePath, target);
                }
            }

            // Prepend (not append) so JNA finds our aarch64 libraries BEFORE
            // any directory Waterfall's NativeLoader might append later.
            prependJnaLibraryPath(tempDir.toAbsolutePath().toString());

            loaded = true;
            LOGGER.info("[waterfall-aarch] All aarch64 natives loaded; jna.library.path={}",
                    System.getProperty("jna.library.path", ""));
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[waterfall-aarch] Failed to load aarch64 natives", e);
            throw e;
        } catch (Exception e) {
            LOGGER.error("[waterfall-aarch] Failed to load aarch64 natives", e);
            throw new UnsatisfiedLinkError("Failed to load aarch64 natives: " + e.getMessage());
        }
    }

    /**
     * Prepend a directory to the jna.library.path system property. JNA reads
     * this property when resolving short library names via Native.load(...).
     * Prepending (rather than appending) guarantees our directory wins over
     * any path Waterfall's NativeLoader may add later.
     */
    private static synchronized void prependJnaLibraryPath(String dir) {
        String existing = System.getProperty("jna.library.path", "");
        String sep = System.getProperty("path.separator", ":");
        String updated = existing.isEmpty() ? dir : dir + sep + existing;
        System.setProperty("jna.library.path", updated);
    }
}
