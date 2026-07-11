package com.waterfall.aarch;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for the Waterfall AArch64 Natives plugin.
 *
 * This mod does only one thing: it loads the bundled aarch64 versions of
 * Waterfall's native libraries (libdirection / libheavy) BEFORE the Waterfall
 * mod itself initializes, so that JNA binds to our aarch64 symbols rather than
 * the x86_64 ones Waterfall ships.
 *
 * The actual work happens in {@link AarchNativeLoader#loadAll()}. See its
 * javadoc for the rationale.
 *
 * Defense in depth - two layers ensure Waterfall's x86_64 load path never runs
 * on aarch64 hosts:
 *   1. (primary) {@code MixinNativeLoader} patches {@code NativeLoader.loadLibrary/
 *      loadHeavy/loadDirection} at HEAD to cancel the call once our aarch64
 *      libraries are loaded. Mixin transform runs during modlauncher bootstrap,
 *      before any mod's @Mod constructor, so the patch is in place before
 *      Waterfall's constructor calls NativeLoader.
 *   2. (belt-and-suspenders) An ordering="BEFORE" dependency on "waterfall" in
 *      neoforge.mods.toml makes our @Mod constructor run first, so
 *      {@link AarchNativeLoader#loadAll()} has executed (and isLoaded() returns
 *      true) by the time Waterfall's constructor runs. If the mixin somehow
 *      fails to apply, this ordering alone still ensures our libraries are on
 *      jna.library.path before Waterfall's NativeLoader appends its own (wrong)
 *      temp dir.
 */
@Mod(WaterfallAarchMod.MODID)
public class WaterfallAarchMod {
    public static final String MODID = "waterfall_aarch";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WaterfallAarchMod(IEventBus modEventBus) {
        LOGGER.info("[{}] Initializing - loading aarch64 native libraries for Waterfall", MODID);
        try {
            AarchNativeLoader.loadAll();
        } catch (Throwable t) {
            // Log and swallow: we don't want to crash game startup if our
            // libraries can't be loaded (e.g. running on x86_64 where they
            // aren't needed). Waterfall's own NativeLoader will then either
            // succeed with its bundled libs (x86_64 host) or fail loudly itself.
            LOGGER.error("[{}] Failed to load aarch64 natives; falling back to Waterfall's "
                    + "bundled libraries. On aarch64 hosts this will likely be fatal later.", MODID, t);
        }
        LOGGER.info("[{}] Initialization complete", MODID);
    }
}
