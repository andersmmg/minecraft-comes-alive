package mca.network.s2c;

import mca.cobalt.network.Message;
import mca.server.world.data.Village;
import mca.server.world.data.VillageManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Serial;

public class SaveVillageMessage implements Message.ServerMessage {
    @Serial
    private static final long serialVersionUID = -4830365225086158551L;

    private final int id;
    private final int taxes;
    private final int populationThreshold;
    private final int marriageThreshold;

    public SaveVillageMessage(Village village) {
        this.id = village.getId();
        this.taxes = village.getTaxes();
        this.populationThreshold = village.getPopulationThreshold();
        this.marriageThreshold = village.getMarriageThreshold();
    }

    @Override
    public void receive(ServerPlayerEntity player) {
        VillageManager.get(player.getWorld()).getOrEmpty(id).ifPresent(village -> {
            village.setTaxes(taxes);
            village.setPopulationThreshold(populationThreshold);
            village.setMarriageThreshold(marriageThreshold);
        });
    }
}
