package com.veinbuddy.mixin.client;

import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ItemPickupParticle.class)
public class ParticleMixin {
  @Inject(method = "buildGeometry", at = @At("HEAD"), cancellable = true)
  public void particles(VertexConsumer pBuffer, Camera pRenderInfo, float pPartialTicks, CallbackInfo ci) {
    ci.cancel();
  }
}
