package com.waterfall.aarch.mixin;

import com.waterfall.aarch.AarchNativeLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts Waterfall's {@code com.waterfall.natives.NativeLoader} so that
 * its own x86_64 loading path never runs after our aarch64 libraries have
 * been loaded.
 *
 * Why this is needed (and the previous "BEFORE + jna.library.path prepend"
 * approach alone was not enough):
 *   Waterfall's NativeLoader.loadLibrary catches only {@code Exception}, not
 *   {@code Throwable}. When it System.load()-s its bundled x86_64 .so on an
 *   aarch64 host, the resulting {@code UnsatisfiedLinkError} (an Error, not
 *   an Exception) escapes its catch block, propagates up through
 *   WaterfallMod's {@code catch (Throwable)} (which swallows it), and leaves
 *   NativeLoader's internal {@code loaded} map in a "not loaded" state.
 *   Worse, just before the failure it has already appended its own temp dir
 *   to jna.library.path, polluting the search path.
 *
 *   Cancelling the method at HEAD when our aarch64 libraries are already
 *   loaded avoids all of that: Waterfall's x86_64 extraction/System.load/
 *   registerJnaLibraryPath code simply never executes.
 *
 * Why @Inject + cancel rather than @Overwrite:
 *   @Overwrite would require copying the entire 80-line loadLibrary body and
 *   @Shadow-ing the private loaded/loadedNames maps, tightly coupling this
 *   mod to Waterfall's internal implementation details. @Inject at HEAD only
 *   depends on the public method signature (name + descriptor), which is a
 *   much more stable contract. If Waterfall later rewrites loadLibrary
 *   internals, this mixin still works as long as the method name and
 *   (String, String) signature stay the same.
 *
 * Mixin target uses the fully-qualified class name string so this mod does
 * NOT need Waterfall on its compile classpath - {@code @Mixin(targets = ...)}
 * is resolved at runtime by the mixin engine, not at compile time.
 *
 * Mixin application order:
 *   The mixin bytecode transform phase runs during modlauncher bootstrap,
 *   BEFORE any mod's @Mod constructor. So even though Waterfall's @Mod
 *   constructor calls NativeLoader.loadHeavy()/loadDirection(), by the time
 *   that call happens the target methods have already been patched to call
 *   our @Inject first. The "BEFORE" mod ordering in neoforge.mods.toml is
 *   kept as belt-and-suspenders to guarantee AarchNativeLoader.loadAll() has
 *   run (and isLoaded() returns true) before Waterfall's constructor is even
 *   entered.
 */
@Mixin(targets = "com.waterfall.natives.NativeLoader")
public abstract class MixinNativeLoader {

    /**
     * Intercept {@code NativeLoader.loadLibrary(String, String)} at HEAD.
     * If our aarch64 libraries are already loaded, cancel the call so
     * Waterfall's x86_64 extraction/load path never runs.
     *
     * This is the only @Inject that actually cancels. loadHeavy() and
     * loadDirection() both delegate to loadLibrary(), so cancelling here
     * covers them transitively. The two caller-side @Injects below only
     * exist to log once per public entry point (otherwise loadLibrary would
     * be cancelled silently and debugging would be harder).
     */
    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private static void waterfall_aarch$cancelLoadLibrary(String libraryName, String version, CallbackInfo ci) {
        if (AarchNativeLoader.isLoaded()) {
            ci.cancel();
        }
    }

    /**
     * Intercept {@code NativeLoader.loadHeavy()} at HEAD. Does NOT cancel -
     * just logs that we're letting it fall through to loadLibrary, which the
     * @Inject above will cancel. This gives the user one log line per public
     * entry point instead of zero (which would be confusing) or two (one
     * from loadHeavy, one from the delegated loadLibrary call).
     */
    @Inject(method = "loadHeavy", at = @At("HEAD"))
    private static void waterfall_aarch$logSkipHeavy(CallbackInfo ci) {
        if (AarchNativeLoader.isLoaded()) {
            AarchNativeLoader.logSkip("loadHeavy");
        }
    }

    /** Same as {@link #waterfall_aarch$logSkipHeavy} but for loadDirection(). */
    @Inject(method = "loadDirection", at = @At("HEAD"))
    private static void waterfall_aarch$logSkipDirection(CallbackInfo ci) {
        if (AarchNativeLoader.isLoaded()) {
            AarchNativeLoader.logSkip("loadDirection");
        }
    }
}
