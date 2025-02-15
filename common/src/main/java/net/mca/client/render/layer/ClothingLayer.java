package net.mca.client.render.layer;

import net.mca.client.model.CommonVillagerModel;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;

public class ClothingLayer<T extends LivingEntity, M extends BipedEntityModel<T>> extends VillagerLayer<T, M> {
    private final String variant;

    public ClothingLayer(FeatureRendererContext<T, M> renderer, M model, String variant) {
        super(renderer, model);
        this.variant = variant;
    }

    @Override
    protected Identifier getSkin(T villager) {
        String v = CommonVillagerModel.getVillager(villager).isBurned() ? "burnt" : variant;
        return cached(CommonVillagerModel.getVillager(villager).getClothes() + v, clothes -> {
            Identifier id = new Identifier(CommonVillagerModel.getVillager(villager).getClothes());

            Identifier idNew = new Identifier(id.getNamespace(), id.getPath().replace("normal", v));
            if (canUse(idNew)) {
                return idNew;
            }

            return id;
        });
    }
}
