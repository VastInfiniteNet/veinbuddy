package com.github.sbobicus.mixins;

import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemPickupParticle.class)
public abstract class ParticleMixin {
    @Inject(
        method = "renderCustom",
        at = @At("HEAD"),
        cancellable = true
    )
    public void veinbuddy$preventItemCustomRender(
        final @NotNull MatrixStack matrices,
        final @NotNull VertexConsumerProvider vertexConsumers,
        final @NotNull Camera camera,
        final float tickDelta,
        final @NotNull CallbackInfo ci
    ) {
        ci.cancel();
    }
}