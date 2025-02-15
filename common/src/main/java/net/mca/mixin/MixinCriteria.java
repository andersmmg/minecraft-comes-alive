package net.mca.mixin;

import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.advancement.criterion.Criterion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Criteria.class)
public interface MixinCriteria {
    @Invoker("register")
    static <T extends Criterion<?>> T register(T object) {
        return null;
    }
}
