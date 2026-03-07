package net.billstark001.worlddownloader.util;

import java.util.ArrayList;
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
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;

@Environment(EnvType.CLIENT)
public class EntityTracker {
    private static final Map<ChunkPos, List<NbtCompound>> chunkEntities = new ConcurrentHashMap<>();

    public static void captureAllEntities() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;

        if (world == null) {
            WDLogger.warn("ClientWorld is null, cannot capture entities");;

            return;
        }
        chunkEntities.clear();
        int totalEntities = 0;

        WDLogger.info("Starting entity capture...");

        for (Entity entity : world.getEntities()) {
            if (entity == null) {
                continue;
            }


            if (entity instanceof net.minecraft.entity.player.PlayerEntity) {
                continue;
            }

            Vec3d pos = entity.getEntityPos();
            int chunkX = (int) Math.floor(pos.x) >> 4;
            int chunkZ = (int) Math.floor(pos.z) >> 4;
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);


            if (ChunkListener.getAll().containsKey(chunkPos)) {
                try {
                    NbtCompound entityNbt = serializeEntity(entity);
                    if (entityNbt != null) {
                        chunkEntities.computeIfAbsent(chunkPos, k -> new ArrayList()).add(entityNbt);
                        totalEntities++;

                        String entityType = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
                        WDLogger.debug("Captured " + entityType + " at " + pos + " in chunk " + chunkPos);
                    }
                } catch (Exception e) {
                    WDLogger.warn("Failed to serialize entity at " + pos + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        WDLogger.info("Captured " + totalEntities + " entities across " + chunkEntities.size() + " chunks");


        for (Map.Entry<ChunkPos, List<NbtCompound>> entry : chunkEntities.entrySet()) {
            WDLogger.debug("Chunk " + entry.getKey() + ": " + entry.getValue().size() + " entities");
        }
    }

    private static NbtCompound serializeEntity(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return null;
        }

        try {
            NbtCompound entityNbt = new NbtCompound();


            String entityTypeId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            entityNbt.putString("id", entityTypeId);


            Vec3d pos = entity.getEntityPos();
            NbtList posNbt = new NbtList();
            posNbt.add(NbtDouble.of(pos.x));
            posNbt.add(NbtDouble.of(pos.y));
            posNbt.add(NbtDouble.of(pos.z));
            entityNbt.put("Pos", posNbt);


            Vec3d velocity = entity.getVelocity();
            NbtList motionNbt = new NbtList();
            motionNbt.add(NbtDouble.of(velocity.x));
            motionNbt.add(NbtDouble.of(velocity.y));
            motionNbt.add(NbtDouble.of(velocity.z));
            entityNbt.put("Motion", motionNbt);


            NbtList rotationNbt = new NbtList();
            rotationNbt.add(NbtFloat.of(entity.getYaw()));
            rotationNbt.add(NbtFloat.of(entity.getPitch()));
            entityNbt.put("Rotation", rotationNbt);


            UUID uuid = entity.getUuid();
            long most = uuid.getMostSignificantBits();
            long least = uuid.getLeastSignificantBits();
            int[] uuidArray = {(int) (most >>> 32L), (int) most, (int) (least >>> 32L), (int) least};


            entityNbt.putIntArray("UUID", uuidArray);


            entityNbt.putShort("Air", (short) entity.getAir());
            entityNbt.putShort("Fire", (short) entity.getFireTicks());
            entityNbt.putBoolean("OnGround", entity.isOnGround());
            entityNbt.putBoolean("Invulnerable", entity.isInvulnerable());
            entityNbt.putInt("PortalCooldown", entity.getPortalCooldown());


            entityNbt.putBoolean("Silent", entity.isSilent());
            entityNbt.putBoolean("NoGravity", entity.hasNoGravity());
            entityNbt.putBoolean("Glowing", entity.isGlowing());


            if (entity.hasCustomName()) {
                Text customName = entity.getCustomName();
                if (customName != null) {

                    try {
                        String jsonName = customName.getString();
                        entityNbt.putString("CustomName", jsonName);
                        entityNbt.putBoolean("CustomNameVisible", entity.isCustomNameVisible());
                    } catch (Exception e) {

                        entityNbt.putString("CustomName", "\"" + customName.getString() + "\"");
                        entityNbt.putBoolean("CustomNameVisible", entity.isCustomNameVisible());
                    }
                }
            }


            if (!entity.getCommandTags().isEmpty()) {
                NbtList tags = new NbtList();
                for (String tag : entity.getCommandTags()) {
                    tags.add(NbtString.of(tag));
                }
                entityNbt.put("Tags", tags);
            }


            if (entity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) entity;
                addLivingEntityData(entityNbt, living);
            }


            if (entity instanceof ItemEntity) {
                ItemEntity itemEntity = (ItemEntity) entity;
                addItemEntityData(entityNbt, itemEntity, client);
            }


            return entityNbt;
        } catch (Exception e) {
            WDLogger.warn("Failed to serialize entity " + entity.getType() + ": " + e.getMessage());
            return null;
        }
    }

    private static void addLivingEntityData(NbtCompound entityNbt, LivingEntity living) {
        entityNbt.putFloat("Health", living.getHealth());
        entityNbt.putFloat("AbsorptionAmount", living.getAbsorptionAmount());
        entityNbt.putShort("HurtTime", (short) living.hurtTime);
        entityNbt.putInt("HurtByTimestamp", living.getLastAttackedTime());
        entityNbt.putShort("DeathTime", (short) living.deathTime);


        NbtList attributes = new NbtList();
        NbtCompound maxHealthAttr = new NbtCompound();
        maxHealthAttr.putString("Name", "minecraft:generic.max_health");
        maxHealthAttr.putDouble("Base", living.getMaxHealth());
        attributes.add(maxHealthAttr);
        entityNbt.put("Attributes", attributes);


        NbtList armorItems = new NbtList();
        NbtList handItems = new NbtList();
        int i;
        for (i = 0; i < 4; i++) {
            armorItems.add(new NbtCompound());
        }
        for (i = 0; i < 2; i++) {
            handItems.add(new NbtCompound());
        }
        entityNbt.put("ArmorItems", armorItems);
        entityNbt.put("HandItems", handItems);


        NbtList armorDropChances = new NbtList();
        NbtList handDropChances = new NbtList();
        int j;
        for (j = 0; j < 4; j++) {
            armorDropChances.add(NbtFloat.of(0.085F));
        }
        for (j = 0; j < 2; j++) {
            handDropChances.add(NbtFloat.of(0.085F));
        }
        entityNbt.put("ArmorDropChances", armorDropChances);
        entityNbt.put("HandDropChances", handDropChances);


        if (living instanceof MobEntity) {
            MobEntity mob = (MobEntity) living;
            entityNbt.putBoolean("CanPickUpLoot", mob.canPickUpLoot());
            entityNbt.putBoolean("PersistenceRequired", mob.isPersistent());
            entityNbt.putBoolean("LeftHanded", mob.isLeftHanded());
            entityNbt.putBoolean("NoAI", mob.isAiDisabled());
        }


        if (living instanceof AnimalEntity) {
            AnimalEntity animal = (AnimalEntity) living;
            entityNbt.putInt("Age", animal.getBreedingAge());
            entityNbt.putInt("ForcedAge", animal.getForcedAge());
            entityNbt.putInt("InLove", animal.getLoveTicks());

            if (animal.getLovingPlayer() != null) {
                UUID lovingPlayerUuid = animal.getLovingPlayer().getUuid();
                long most = lovingPlayerUuid.getMostSignificantBits();
                long least = lovingPlayerUuid.getLeastSignificantBits();
                int[] lovingPlayerUuidArray = {(int) (most >>> 32L), (int) most, (int) (least >>> 32L), (int) least};


                entityNbt.putIntArray("LoveCause", lovingPlayerUuidArray);
            }
        }

    }

    private static void addItemEntityData(NbtCompound entityNbt, ItemEntity itemEntity, MinecraftClient client) {
        entityNbt.putShort("Age", (short) itemEntity.getItemAge());
        entityNbt.putShort("PickupDelay", (short) 10);


        ItemStack itemStack = itemEntity.getStack();
        if (!itemStack.isEmpty()) {
            try {
                NbtCompound itemNbt = new NbtCompound();


                itemNbt.putString("id", Registries.ITEM.getId(itemStack.getItem()).toString());
                itemNbt.putInt("count", itemStack.getCount());


                try {
                    ContainerData.test1(client, itemNbt, itemStack);
                } catch (Exception e) {
                    WDLogger.warn("Failed to encode item components: " + e.getMessage());
                }

                entityNbt.put("Item", itemNbt);
                WDLogger.debug("Item: " + Registries.ITEM.getId(itemStack.getItem()) + " x" + itemStack.getCount());
            } catch (Exception e) {
                WDLogger.warn("Failed to serialize item stack for ItemEntity: " + e.getMessage());


                NbtCompound basicItem = new NbtCompound();
                basicItem.putString("id", Registries.ITEM.getId(itemStack.getItem()).toString());
                basicItem.putInt("count", itemStack.getCount());
                entityNbt.put("Item", basicItem);
            }
        }
    }

    public static List<NbtCompound> getEntitiesForChunk(ChunkPos chunkPos) {
        List<NbtCompound> entities = chunkEntities.getOrDefault(chunkPos, new ArrayList<>());
        if (!entities.isEmpty()) {
            WDLogger.debug("Retrieved " + entities.size() + " entities for chunk " + chunkPos);
        }
        return entities;
    }

    public static void clear() {
        int totalEntities = getTotalTrackedEntities();
        chunkEntities.clear();
        WDLogger.info("Cleared " + totalEntities + " tracked entities");
    }

    public static int getTotalTrackedEntities() {
        return chunkEntities.values().stream().mapToInt(List::size).sum();
    }
}
