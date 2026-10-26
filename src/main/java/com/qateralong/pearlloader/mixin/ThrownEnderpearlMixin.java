package com.qateralong.pearlloader.mixin;

import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ThrownEnderpearl.class)
public class ThrownEnderpearlMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean pearlLoader$keepOnDeath(GameRules rules, GameRules.Key<GameRules.BooleanValue> key) {
        return false;
    }
}
