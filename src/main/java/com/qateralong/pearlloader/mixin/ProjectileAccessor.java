package com.qateralong.pearlloader.mixin;

import java.util.UUID;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Projectile.class)
public interface ProjectileAccessor {
    @Accessor("ownerUUID")
    UUID pearlLoader$ownerUuid();
}
