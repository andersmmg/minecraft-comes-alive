package net.mca.item;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.mca.ClientProxy;
import net.mca.Config;
import net.mca.advancement.criterion.CriterionMCA;
import net.mca.cobalt.network.NetworkHandler;
import net.mca.entity.Status;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.VillagerFactory;
import net.mca.entity.VillagerLike;
import net.mca.entity.ai.Memories;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.relationship.Gender;
import net.mca.entity.ai.relationship.family.FamilyTree;
import net.mca.network.c2s.GetChildDataRequest;
import net.mca.network.s2c.OpenGuiRequest;
import net.mca.server.world.data.BabyTracker;
import net.mca.util.WorldUtils;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.*;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class BabyItem extends Item {
    public static final LoadingCache<UUID, Optional<BabyTracker.ChildSaveState>> CLIENT_STATE_CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build(CacheLoader.from(id -> {
                NetworkHandler.sendToServer(new GetChildDataRequest(id));
                return Optional.empty();
            }));

    private final Gender gender;

    public BabyItem(Gender gender, Item.Settings properties) {
        super(properties);
        this.gender = gender;
    }

    public Gender getGender() {
        return gender;
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        return ActionResult.PASS;
    }

    public boolean onDropped(ItemStack stack, PlayerEntity player) {
        if (!hasBeenInvalidated(stack)) {
            if (!player.getWorld().isClient) {
                int count = 0;
                if (stack.getOrCreateNbt().contains("dropAttempts", NbtElement.INT_TYPE)) {
                    count = stack.getOrCreateNbt().getInt("dropAttempts") + 1;
                }
                stack.getOrCreateNbt().putInt("dropAttempts", count);
                CriterionMCA.BABY_DROPPED_CRITERION.trigger((ServerPlayerEntity) player, count);
                player.sendMessage(new TranslatableText("item.mca.baby.no_drop"), true);
            }
            return false;
        }

        return true;
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (world.isClient) {
            return;
        }

        // remove duplicates
        if (entity instanceof ServerPlayerEntity player) {
            if (world.getTime() % 20 == 0) {
                Set<UUID> found = new HashSet<>();
                for (int i = player.getInventory().size() - 1; i >= 0; i--) {
                    ItemStack s = player.getInventory().getStack(i);
                    final int sl = i;
                    BabyTracker.getStateId(s).ifPresent(id -> {
                        if (found.contains(id)) {
                            player.getInventory().removeStack(sl);
                        } else {
                            found.add(id);
                        }
                    });
                }
            }
        }

        // update
        if (BabyTracker.hasState(stack)) {
            Optional<BabyTracker.MutableChildSaveState> state = BabyTracker.getState(stack, (ServerWorld)world);
            if (state.isPresent()) {

                // use an anvil to rename your baby (in case of typos like I did)
                if (stack.hasCustomName()) {
                    state.get().setName(stack.getName().getString());
                    state.get().writeToItem(stack);
                    stack.removeCustomName();

                    if (entity instanceof ServerPlayerEntity player) {
                        CriterionMCA.GENERIC_EVENT_CRITERION.trigger(player, "rename_baby");
                    }
                }

                if (state.get().getName().isPresent() && world.getTime() % 1200 == 0) {
                    stack.getNbt().putInt("age", stack.getNbt().getInt("age") + 1200);
                }
            } else {
                BabyTracker.invalidate(stack);
            }
        } else if (!stack.hasNbt() || !stack.getNbt().getBoolean("invalidated")) {
            // legacy and items obtained by creative
            BabyTracker.get((ServerWorld)world).getPairing(entity.getUuid(), entity.getUuid()).addChild(state -> {
                state.setGender(gender);
                state.setOwner(entity);
                state.writeToItem(stack);
            });
        }
    }

    @Override
    public Text getName(ItemStack stack) {
        return getClientCheckedState(stack).flatMap(BabyTracker.ChildSaveState::getName).map(s ->
                (Text)new TranslatableText(getTranslationKey(stack) + ".named", s)
        ).orElseGet(() -> super.getName(stack));
    }

    @Override
    public String getTranslationKey(ItemStack stack) {
        if (hasBeenInvalidated(stack)) {
            return super.getTranslationKey(stack) + ".blanket";
        }
        return super.getTranslationKey(stack);
    }

    @Override
    public final TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {

        ItemStack stack = player.getStackInHand(hand);

        if (world.isClient) {
            return TypedActionResult.pass(stack);
        }

        return BabyTracker.getState(stack, (ServerWorld)world).map(state -> {
            // Right-clicking an unnamed baby allows you to name it
            if (state.getName().isEmpty()) {
                if (player instanceof ServerPlayerEntity serverPlayer) {
                    NetworkHandler.sendToPlayer(new OpenGuiRequest(OpenGuiRequest.Type.BABY_NAME), serverPlayer);
                }

                return TypedActionResult.pass(stack);
            }

            if (!isReadyToGrowUp(stack)) {
                return TypedActionResult.pass(stack);
            }

            if (player instanceof ServerPlayerEntity serverPlayer) {
                // Name is good and we're ready to grow
                birthChild(state, (ServerWorld)world, serverPlayer);
            }
            stack.decrement(1);

            return TypedActionResult.success(stack);
        }).orElseGet(() -> {
            if (BabyTracker.getState(stack).isPresent()) {
                world.sendEntityStatus(player, Status.PLAYER_CLOUD_EFFECT);
                player.playSound(SoundEvents.UI_TOAST_OUT, 1, 1);
                BabyTracker.invalidate(stack);
                return TypedActionResult.fail(stack);
            }
            return TypedActionResult.fail(stack);
        });
    }

    protected VillagerEntityMCA birthChild(BabyTracker.ChildSaveState state, ServerWorld world, ServerPlayerEntity player) {
        VillagerEntityMCA child = VillagerFactory.newVillager(world)
                .withName(state.getName().orElse("Unnamed"))
                .withPosition(player.getPos())
                .withGender(gender)
                .withAge(-AgeState.getMaxAge())
                .build();

        List<Entity> parents = state.getParents().map(world::getEntity).filter(Objects::nonNull).toList();

        Optional<Entity> mother = parents.stream().findFirst();
        Optional<Entity> father = parents.stream().skip(1).findFirst();

        // combine genes
        child.getGenetics().combine(
                mother.map(VillagerLike::toVillager).map(VillagerLike::getGenetics),
                father.map(VillagerLike::toVillager).map(VillagerLike::getGenetics),
                state.getSeed()
        );

        // inherit traits
        mother.map(VillagerLike::toVillager).map(VillagerLike::getTraits).ifPresent(t -> child.getTraits().inherit(t, state.getSeed()));
        father.map(VillagerLike::toVillager).map(VillagerLike::getTraits).ifPresent(t -> child.getTraits().inherit(t, state.getSeed()));

        // assign parents
        state.getParents().forEach(p ->
                FamilyTree.get(world).getOrEmpty(p).ifPresent(parent ->
                        child.getRelationships().getFamilyEntry().assignParent(parent)
                )
        );

        WorldUtils.spawnEntity(world, child, SpawnReason.BREEDING);

        // notify parents
        Stream.concat(Stream.of(mother, father).filter(Optional::isPresent).map(Optional::get), Stream.of(player))
                .filter(e -> e instanceof ServerPlayerEntity)
                .map(ServerPlayerEntity.class::cast)
                .distinct()
                .forEach(ply -> {
                    // advancement
                    CriterionMCA.FAMILY.trigger(ply);

                    // set proper dialogue type
                    Memories memories = child.getVillagerBrain().getMemoriesForPlayer(ply);
                    memories.setHearts(Config.getInstance().childInitialHearts);
                });

        BabyTracker.get(world).getPairing(state).removeChild(state);

        return child;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext flag) {

        getClientState(stack).ifPresent(state -> {
            PlayerEntity player = ClientProxy.getClientPlayer();
            NbtCompound nbt = stack.getNbt();

            assert nbt != null;
            int age = nbt.getInt("age") + (int)(world == null ? 0 : world.getTime() % 1200);

            if (state.getName().isEmpty()) {
                tooltip.add(new TranslatableText("item.mca.baby.give_name").formatted(Formatting.YELLOW));
            } else {
                final LiteralText text = new LiteralText(state.getName().get());
                tooltip.add(new TranslatableText("item.mca.baby.name", text.setStyle(text.getStyle().withColor(gender.getColor()))).formatted(Formatting.GRAY));

                if (age > 0) {
                    tooltip.add(new TranslatableText("item.mca.baby.age", StringHelper.formatTicks(age)).formatted(Formatting.GRAY));
                }
            }

            tooltip.add(LiteralText.EMPTY);

            state.getOwner().ifPresent(owner ->
                    tooltip.add(new TranslatableText("item.mca.baby.owner",
                            player != null && owner.getLeft().equals(player.getUuid())
                    ? new TranslatableText("item.mca.baby.owner.you")
                    : owner.getRight()
            ).formatted(Formatting.GRAY)));

            if (state.getName().isPresent() && canGrow(age)) {
                tooltip.add(new TranslatableText("item.mca.baby.state.ready").formatted(Formatting.DARK_GREEN));
            }

            if (state.isInfected()) {
                tooltip.add(new TranslatableText("item.mca.baby.state.infected").formatted(Formatting.DARK_GREEN));
            }
        });
    }

    /**
     * Callable on both sides. If a request is out for details, use that, otherwise keep using the stack's data.
     */
    private static Optional<BabyTracker.ChildSaveState> getClientCheckedState(ItemStack stack) {
        return BabyTracker.getState(stack).map(state -> {
            Optional<BabyTracker.ChildSaveState> loaded = CLIENT_STATE_CACHE.getIfPresent(state.getId());

            //noinspection OptionalAssignedToNull
            if (loaded == null) {
                return state;
            }

            if (loaded.isPresent()) {
                BabyTracker.ChildSaveState l = loaded.get();
                if (
                        (state.getName().isPresent() && l.getName().isEmpty())
                                || (state.getName().isPresent() && l.getName().isPresent() && !state.getName().get().contentEquals(l.getName().get()))
                ) {
                    CLIENT_STATE_CACHE.refresh(state.getId());
                    return state;
                }
                return l;
            }
            return state;
        });
    }

    /**
     * Callable on the client only. Starts a request for the stack's data and returns an empty until resolution is complete.
     */
    private static Optional<BabyTracker.ChildSaveState> getClientState(ItemStack stack) {
        return BabyTracker.getState(stack).flatMap(state -> {
            try {
                return CLIENT_STATE_CACHE.get(state.getId());
            } catch (ExecutionException e) {
                return Optional.of(state);
            }
        });
    }

    public static boolean hasBeenInvalidated(ItemStack stack) {
        //noinspection ConstantConditions
        return (stack.hasNbt() && stack.getNbt().getBoolean("invalidated")) || BabyTracker.getStateId(stack).map(id -> {
            Optional<BabyTracker.ChildSaveState> loaded = CLIENT_STATE_CACHE.getIfPresent(id);

            //noinspection OptionalAssignedToNull
            return loaded != null && loaded.isEmpty();
        }).orElse(false);
    }

    private static boolean canGrow(int age) {
        return age >= Config.getInstance().babyItemGrowUpTime;
    }

    private static boolean isReadyToGrowUp(ItemStack stack) {
        //noinspection ConstantConditions
        return stack.hasNbt() && canGrow(stack.getNbt().getInt("age"));
    }
}
