package net.mca.resources.data.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.mca.MCA;
import net.mca.cobalt.network.NetworkHandler;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.LongTermMemory;
import net.mca.network.s2c.InteractionDialogueResponse;
import net.mca.resources.Dialogues;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.JsonHelper;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class Actions {
    public static final Map<String, Factory<JsonElement>> TYPES = new HashMap<>();

    static {
        register("next", JsonHelper::asString, id -> (villager, player) -> {
            if (id != null) {
                Question newQuestion = Dialogues.getInstance().getRandomQuestion(id);
                if (newQuestion != null) {
                    if (newQuestion.isAuto()) {
                        // this is basically a placeholder and fires an answer automatically
                        // use cases are n to 1 links or to split file size
                        Dialogues.getInstance().selectAnswer(villager, player, newQuestion.getId(), newQuestion.getAnswers().get(0).getName());
                        return;
                    } else {
                        NetworkHandler.sendToPlayer(new InteractionDialogueResponse(newQuestion, player, villager), player);
                    }
                } else {
                    // we send nevertheless and assume it's a final question
                    villager.sendChatMessage(player, "dialogue." + id);
                }

                // close screen
                if (newQuestion == null || newQuestion.isCloseScreen()) {
                    villager.getInteractions().stopInteracting();
                }
            } else {
                villager.getInteractions().stopInteracting();
            }
        });

        register("say", JsonHelper::asString, id -> (villager, player) -> villager.sendChatMessage(player, "dialogue." + id));

        register("remember", JsonHelper::asObject, json -> (villager, player) -> {
            String id = LongTermMemory.parseId(json, player);
            if (json.has("time")) {
                villager.getLongTermMemory().remember(id, json.get("time").getAsLong());
            } else {
                villager.getLongTermMemory().remember(id);
            }
        });

        register("quit", (a, b) -> a, id -> (villager, player) -> villager.getInteractions().stopInteracting());

        register("negative", JsonHelper::asInt, hearts -> (villager, player) -> {
            villager.getVillagerBrain().modifyMoodValue(-hearts);
            villager.getVillagerBrain().rewardHearts(player, -hearts);
        });

        register("positive", JsonHelper::asInt, hearts -> (villager, player) -> {
            villager.getVillagerBrain().modifyMoodValue(hearts);
            villager.getVillagerBrain().rewardHearts(player, hearts);
        });

        register("command", JsonHelper::asString, command -> (villager, player) ->
                villager.getInteractions().handle(player, command));
    }

    public static <T> void register(String name, BiFunction<JsonElement, String, T> jsonParser, Factory<T> predicate) {
        TYPES.put(name, json -> predicate.parse(jsonParser.apply(json, name)));
    }

    public interface Factory<T> {
        Action parse(T value);
    }

    public static Actions fromJson(JsonObject json) {
        List<Action> actions = new LinkedList<>();
        boolean positive = false;
        boolean negative = false;

        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (TYPES.containsKey(entry.getKey())) {
                Action parsed = TYPES.get(entry.getKey()).parse(entry.getValue());
                actions.add(parsed);

                if (entry.getKey().equals("positive")) {
                    positive = true;
                }
                if (entry.getKey().equals("negative")) {
                    negative = true;
                }
            } else {
                MCA.LOGGER.info("Unknown dialogue action " + entry.getKey());
            }
        }

        if (!json.has("next")) {
            Action parsed = TYPES.get("quit").parse(json);
            actions.add(parsed);
        }

        return new Actions(actions, positive, negative);
    }

    public interface Action {
        void trigger(VillagerEntityMCA villager, ServerPlayerEntity player);
    }

    private final List<Action> actions;
    private final boolean positive;
    private final boolean negative;

    public Actions(List<Action> actions, boolean positive, boolean negative) {
        this.actions = actions;
        this.positive = positive;
        this.negative = negative;
    }

    public void trigger(VillagerEntityMCA villager, ServerPlayerEntity player) {
        for (Action c : actions) {
            c.trigger(villager, player);
        }
    }

    public boolean isPositive() {
        return positive;
    }

    public boolean isNegative() {
        return negative;
    }
}
