package com.thegameoflife;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityHacker {

    // =========================================
    // ЭПОХА И ХРАНИЛИЩЕ ДУШ
    // =========================================
    public static int ENTITY_EPOCH = 0;
    public static final Map<EntityType<?>, List<CompoundTag>> SAVED_ENTITIES = new ConcurrentHashMap<>();

    // =========================================
    // ОБРАБОТЧИК ИЗ КОНВЕЙЕРА (Вызывать из GlobalRadar)
    // =========================================
    public static void processEntity(Entity e) {
        // Формируем тег актуальной эпохи
        String currentEpochTag = "eh_epoch_" + ENTITY_EPOCH;

        // Если сущность уже проверялась в текущей эпохе — скипаем (О(1) скорость)
        if (e.getTags().contains(currentEpochTag)) {
            return;
        }

        // Если тип сущности в бане — она не имеет права существовать в мире
        if (TheGameOfLifeMod.BANNED_ENTITIES.contains(e.getType())) {
            e.discard();
            return; // Уничтожили и вышли, дальше обновлять тег не у кого
        }

        // Если сущность легальна и пережила проверку — обновляем её паспорт
        e.getTags().removeIf(tag -> tag.startsWith("eh_epoch_"));
        e.addTag(currentEpochTag);
    }

    // =========================================
    // УМНЫЙ ПЕРЕКЛЮЧАТЕЛЬ ЭНТИТИ (Щелчок Таноса)
    // =========================================
    public static void toggleEntity(MinecraftServer server, EntityType<?> targetType) {
        boolean foundAndDeleted = false;

        List<CompoundTag> savedTags = SAVED_ENTITIES.computeIfAbsent(targetType, k -> new ArrayList<>());

        // 1. ФАЗА БАНА: Ищем и удаляем везде
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {

                // === ДОБАВЬ ЭТУ СТРОКУ (Защита от удаленных в этом же тике сущностей) ===
                if (entity == null) continue;

                if (entity.getType() == targetType && !(entity instanceof ServerPlayer)) {

                    // ... (дальше идет твой код с ProblemReporter и TagValueOutput) ...

                    // ==========================================
                    // НОВАЯ СИСТЕМА СОХРАНЕНИЯ NBT (1.21+)
                    // ==========================================
                    net.minecraft.util.ProblemReporter.Collector collector = new net.minecraft.util.ProblemReporter.Collector();
                    net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(collector, level.registryAccess());

                    // Сохраняем сущность
                    entity.saveWithoutId(output);

                    // Извлекаем готовый тег
                    CompoundTag tag = (CompoundTag) output.buildResult();

                    // Вручную дописываем метаданные
                    tag.putString("id", EntityType.getKey(entity.getType()).toString());
                    // Извлекаем чистый ID измерения
                    tag.putString("datahacker_dim", level.dimension().identifier().toString());
                    savedTags.add(tag);
                    entity.discard();
                    foundAndDeleted = true;
                }
            }
        }

        // 2. ФАЗА БАНА ИЛИ ВОСКРЕШЕНИЯ
        if (foundAndDeleted) {
            TheGameOfLifeMod.UNBANNED_ENTITIES.remove(targetType);
            TheGameOfLifeMod.BANNED_ENTITIES.add(targetType);
            System.out.println("[EntityHacker] " + EntityType.getKey(targetType) + " ЗАБАНЕН! Собрано душ: " + savedTags.size());
        } else {
            TheGameOfLifeMod.BANNED_ENTITIES.remove(targetType);
            TheGameOfLifeMod.UNBANNED_ENTITIES.add(targetType);
            System.out.println("[EntityHacker] " + EntityType.getKey(targetType) + " РАЗБАНЕН!");

            if (!savedTags.isEmpty()) {
                // А. ВОСКРЕШЕНИЕ
                System.out.println("[EntityHacker] Воскрешаем " + savedTags.size() + " сущностей...");

                for (CompoundTag tag : savedTags) {
                    // Чтение с .orElse для обхода Optional
                    String savedDim = tag.getString("datahacker_dim").orElse("minecraft:overworld");

                    ResourceKey<net.minecraft.world.level.Level> dimKey = ResourceKey.create(
                            Registries.DIMENSION,
                            Identifier.parse(savedDim)
                    );

                    ServerLevel targetLevel = server.getLevel(dimKey);
                    if (targetLevel != null) {
                        EntityType.loadEntityRecursive(tag, targetLevel, EntitySpawnReason.COMMAND, entity -> {
                            targetLevel.addFreshEntity(entity);
                            return entity;
                        });
                    }
                }
                savedTags.clear();

            } else {
                // Б. ЗАПАСНОЙ СПАВН
                System.out.println("[EntityHacker] Сохраненных душ нет. Спавним дефолтных возле игроков.");

                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    ServerLevel level = player.level();
                    BlockPos spawnPos = findSafeSpawnNearPlayer(level, player.blockPosition());

                    Entity newEntity = targetType.create(level, EntitySpawnReason.COMMAND);
                    if (newEntity != null) {
                        newEntity.setPos(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D);
                        level.addFreshEntity(newEntity);
                    }
                }
            }
        }

        // ==========================================
        // ПОВЫШАЕМ ЭПОХУ!
        // ==========================================
        // GlobalRadar начнет скармливать всех мобов в метод processEntity,
        // так как их тег `eh_epoch_X` мгновенно устареет.
        ENTITY_EPOCH++;
    }

    // =========================================
    // БЕЗОПАСНЫЙ ПОИСК МЕСТА
    // =========================================
    private static BlockPos findSafeSpawnNearPlayer(ServerLevel level, BlockPos playerPos) {
        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < 15; i++) {
            int targetX = playerPos.getX() + random.nextInt(21) - 10;
            int targetZ = playerPos.getZ() + random.nextInt(21) - 10;

            for (int y = playerPos.getY() + 5; y >= playerPos.getY() - 10; y--) {
                mPos.set(targetX, y, targetZ);

                if (level.getBlockState(mPos).getCollisionShape(level, mPos).isEmpty() &&
                        level.getBlockState(mPos.above()).getCollisionShape(level, mPos.above()).isEmpty()) {

                    if (!level.getBlockState(mPos.below()).getCollisionShape(level, mPos.below()).isEmpty()) {
                        return mPos.immutable();
                    }
                }
            }
        }

        return playerPos.offset(random.nextInt(3) - 1, 0, random.nextInt(3) - 1);
    }
}