package dev.reisb.mixin;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public abstract class TestServerShutdownMixin {
    @Inject(method = "halt", at = @At("HEAD"), cancellable = true)
    private void reisb$avoidTestPhaseDeadlock(boolean wait, CallbackInfo callback) {
        var server = (IntegratedServer) (Object) this;
        if (!server.isSameThread() && net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning) {
            // The test runner advances server phases only after the render thread returns.
            server.execute(() -> server.halt(false));
            callback.cancel();
        }
    }
}
