package net.billstark001.worlddownloader.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.world.World;

@Environment(EnvType.CLIENT)
public class EntityTracker {

    // dimension → (chunkPos → entity list)
    private static final ConcurrentHashMap<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>>
            dimChunkEntities = new ConcurrentHashMap<>();

    /**
     * Captures all non-player entities in the given world and stores them under
     * that world's dimension key.  Must be called on the game thread.
     */
    public static void captureEntitiesForWorld(ClientWorld world) {
        if (world == null) {
            WDLogger.warn("ClientWorld is null, cannot capture entities.");
            return;
        }

        RegistryKey<World> dimension = world.getRegistryKey();
        Map<ChunkPos, ChunkListener.CapturedChunk> capturedChunks =
                ChunkListener.getDimension(dimension);

        Map<ChunkPos, List<NbtCompound>> dimEntities = new ConcurrentHashMap<>();
        int total = 0;

        for (Entity entity : world.getEntities()) {
            if (entity == null || entity instanceof net.minecraft.entity.player.PlayerEntity) continue;

            Vec3d pos = entity.getEntityPos();
            int cx = (int) Math.floor(pos.x) >> 4;
            int cz = (int) Math.floor(pos.z) >> 4;
            ChunkPos chunkPos = new ChunkPos(cx, cz);

            if (capturedChunks.containsKey(chunkPos)) {
                try {
                    NbtCompound nbt = serializeEntity(entity);
                    if (nbt != null) {
                        dimEntities.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(nbt);
                        total++;
                        WDLogger.debug("Captured " + Registries.ENTITY_TYPE.getId(entity.getType())
                                + " at " + pos + " in chunk " + chunkPos);
                    }
                } catch (Exception e) {
                    WDLogger.warn("Failed to serialize entity at " + pos + ": " + e.getMessage());
                }
            }
        }

        dimChunkEntities.put(dimension, dimEntities);
        WDLogger.info("Captured " + total + " entities for [" + dimension.getValue() + "]");
    }

    /**
     * Returns an immutable snapshot of all entity data, safe to read from any thread.
     */
    public static Map<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> snapshot() {
        Map<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> result = new HashMap<>();
        for (Map.Entry<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> dimEntry
                : dimChunkEntities.entrySet()) {
            Map<ChunkPos, List<NbtCompound>> dimCopy = new HashMap<>();
            for (Map.Entry<ChunkPos, List<NbtCompound>> chunkEntry : dimEntry.getValue().entrySet()) {
                dimCopy.put(chunkEntry.getKey(), new ArrayList<>(chunkEntry.getValue()));
            }
            result.put(dimEntry.getKey(), dimCopy);
        }
        return result;
    }

    /** Looks up entities in a pre-fetched per-dimension entity map (for use inside Exporter). */
    public static List<NbtCompound> getEntitiesForChunk(
            Map<ChunkPos, List<NbtCompound>> dimEntities, ChunkPos pos) {
        if (dimEntities == null) return List.of();
        List<NbtCompound> list = dimEntities.get(pos);
        return (list != null) ? list : List.of();
    }

    public static void clear() {
        int total = getTotalTrackedEntities();
        dimChunkEntities.clear();
        WDLogger.info("Cleared " + total + " tracked entities");
    }

    public static int getTotalTrackedEntities() {
        return dimChunkEntities.values().stream()
                .mapToInt(m -> m.values().stream().mapToInt(List::size).sum())
                .sum();
    }

    // ── Entity serialisation ──────────────────────────────────────────────────

    private static NbtCompound serializeEntity(Entity entity) {
        try {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("id", Registries.ENTITY_TYPE.getId(entity.getType()).toString());

            Vec3d pos = entity.getEntityPos();
            NbtList posNbt = new NbtList();
            posNbt.add(NbtDouble.of(pos.x));
            posNbt.add(NbtDouble.of(pos.y));
            posNbt.add(NbtDouble.of(pos.z));
            nbt.put("Pos", posNbt);

            Vec3d vel = entity.getVelocity();
            NbtList motionNbt = new NbtList();
            motionNbt.add(NbtDouble.of(vel.x));
            motionNbt.add(NbtDouble.of(vel.y));
            motionNbt.add(NbtDouble.of(vel.z));
            nbt.put("Motion", motionNbt);

            NbtList rotNbt = new NbtList();
            rotNbt.add(NbtFloat.of(entity.getYaw()));
            rotNbt.add(NbtFloat.of(entity.getPitch()));
            nbt.put("Rotation", rotNbt);

            UUID uuid = entity.getUuid();
            long most = uuid.getMostSignificantBits();
            long least = uuid.getLeastSignificantBits();
            nbt.putIntArray("UUID", new int[]{
                    (int)(most >>> 32), (int)most, (int)(least >>> 32), (int)least});

            nbt.putShort("Air",          (short) entity.getAir());
            nbt.putShort("Fire",         (short) entity.getFireTicks());
            nbt.putBoolean("OnGround",   entity.isOnGround());
            nbt.putBoolean("Invulnerable", entity.isInvulnerable());
            nbt.putInt("PortalCooldown", entity.getPortalCooldown());
            nbt.putBoolean("Silent",     entity.isSilent());
            nbt.putBoolean("NoGravity",  entity.hasNoGravity());
            nbt.putBoolean("Glowing",    entity.isGlowing());

            if (entity.hasCustomName()) {
                Text name = entity.getCustomName();
                if (name != null) {
                    nbt.putString("CustomName", name.getString());
                    nbt.putBoolean("CustomNameVisible", entity.isCustomNameVisible());
                }
            }

            if (!entity.getCommandTags().isEmpty()) {
                NbtList tags = new NbtList();
                entity.getCommandTags().forEach(t -> tags.add(NbtString.of(t)));
                nbt.put("Tags", tags);
            }

            if (entity instanceof LivingEntity living) addLivingEntityData(nbt, living);
            if (entity instanceof ItemEntity ie)      addItemEntityData(nbt, ie);

            return nbt;
        } catch (Exception e) {
            WDLogger.warn("Failed to serialize entity " + entity.getType() + ": " + e.getMessage());
            return null;
        }
    }

    private static void addLivingEntityData(NbtCompound nbt, LivingEntity living) {
        nbt.putFloat("Health", living.getHealth());
        nbt.putFloat("AbsorptionAmount", living.getAbsorptionAmount());
        nbt.putShort("HurtTime", (short) living.hurtTime);
        nbt.putInt("HurtByTimestamp", living.getLastAttackedTime());
        nbt.putShort("DeathTime", (short) living.deathTime);

        NbtList attributes = new NbtList();
        NbtCompound maxHealth = new NbtCompound();
        maxHealth.putString("Name", "minecraft:generic.max_health");
        maxHealth.putDouble("Base", living.getMaxHealth());
        attributes.add(maxHealth);
        nbt.put("Attributes", attributes);

        NbtList armorItems = new NbtList();
        NbtList handItems  = new NbtList();
        for (int i = 0; i < 4; i++) armorItems.add(new NbtCompound());
        for (int i = 0; i < 2; i++) handItems.add(new NbtCompound());
        nbt.put("ArmorItems", armorItems);
        nbt.put("HandItems",  handItems);

        NbtList armorChances = new NbtList();
        NbtList handChances  = new NbtList();
        for (int i = 0; i < 4; i++) armorChances.add(NbtFloat.of(0.085F));
        for (int i = 0; i < 2; i++) handChances.add(NbtFloat.of(0.085F));
        nbt.put("ArmorDropChances", armorChances);
        nbt.put("HandDropChances",  handChances);

        if (living instanceof MobEntity mob) {
            nbt.putBoolean("CanPickUpLoot",       mob.canPickUpLoot());
            nbt.putBoolean("PersistenceRequired", mob.isPersistent());
            nbt.putBoolean("LeftHanded",          mob.isLeftHanded());
            nbt.putBoolean("NoAI",                mob.isAiDisabled());
        }
        if (living instanceof AnimalEntity animal) {
            nbt.putInt("Age",       animal.getBreedingAge());
            nbt.putInt("ForcedAge", animal.getForcedAge());
            nbt.putInt("InLove",    animal.getLoveTicks());
        }
    }

    private static void addItemEntityData(NbtCompound nbt, ItemEntity ie) {
        nbt.putShort("Age", (short) ie.getItemAge());
        nbt.putShort("PickupDelay", (short) 10);

        ItemStack stack = ie.getStack();
        if (!stack.isEmpty()) {
            try {
                NbtCompound itemNbt = new NbtCompound();
                itemNbt.putString("id", Registries.ITEM.getId(stack.getItem()).toString());
                itemNbt.putInt("count", stack.getCount());
                try {
                    ContainerData.test1(MinecraftClient.getInstance(), itemNbt, stack);
                } catch (Exception e) {
                    WDLogger.warn("Failed to encode item components: " + e.getMessage());
                }
                nbt.put("Item", itemNbt);
            } catch (Exception e) {
                WDLogger.warn("Failed to serialize ItemEntity stack: " + e.getMessage());
                NbtCompound basic = new NbtCompound();
                basic.putString("id", Registries.ITEM.getId(stack.getItem()).toString());
                basic.putInt("count", stack.getCount());
                nbt.put("Item", basic);
            }
        }
    }
}
