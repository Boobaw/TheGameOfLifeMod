package com.thegameoflife;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason; // <-- ИСПОЛЬЗУЕМ НОВЫЙ КЛАСС
import net.minecraft.world.phys.AABB;

public class EntityHacker {

    // =========================================
    // УМНЫЙ ПЕРЕКЛЮЧАТЕЛЬ ЭНТИТИ
    // =========================================
    public static void toggleEntity(MinecraftServer server, EntityType<?> targetType) {
        boolean foundAndDeleted = false;

        // Радиус поиска равен дальности прорисовки сервера (умножаем чанки на 16 блоков)
        int searchRadius = server.getPlayerList().getViewDistance() * 16;

        // 1. ФАЗА ПОИСКА И УДАЛЕНИЯ (Щелчок Таноса)
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();

            // Создаем коробку (AABB) вокруг игрока
            AABB searchBox = player.getBoundingBox().inflate(searchRadius);

            // Достаем всех мобов заданного типа в этом радиусе
            var entities = level.getEntities(targetType, searchBox, e -> true);

            if (!entities.isEmpty()) {
                foundAndDeleted = true;
                for (Entity e : entities) {
                    // Идеальное удаление: без звуков, без анимации смерти, без лута
                    e.discard();
                }
            }
        }

        // 2. ФАЗА БАНА ИЛИ СПАВНА
        if (foundAndDeleted) {
            TheGameOfLifeMod.UNBANNED_ENTITIES.remove(targetType);
            TheGameOfLifeMod.BANNED_ENTITIES.add(targetType);
            System.out.println("Энтити " + EntityType.getKey(targetType) + " удален и ЗАБАНЕН!");
        } else {
            TheGameOfLifeMod.BANNED_ENTITIES.remove(targetType);
            TheGameOfLifeMod.UNBANNED_ENTITIES.add(targetType);
            System.out.println("Энтити " + EntityType.getKey(targetType) + " не найден, РАЗБАНЕН и заспавнен!");

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerLevel level = player.level();
                BlockPos spawnPos = findSafeSpawnNearPlayer(level, player.blockPosition());

                // Бронебойный метод создания для новых маппингов (передаем причину спавна)
                Entity newEntity = targetType.create(level, EntitySpawnReason.COMMAND);

                if (newEntity != null) {
                    // Явно указываем тип double (D)
                    newEntity.setPos(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D);
                    level.addFreshEntity(newEntity);
                }
            }
        }
    }

    // =========================================
    // БЕЗОПАСНЫЙ ПОИСК МЕСТА (Вертикальный скан)
    // =========================================
    private static BlockPos findSafeSpawnNearPlayer(ServerLevel level, BlockPos playerPos) {
        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        // Делаем 15 попыток найти подходящий столб X/Z
        for (int i = 0; i < 15; i++) {
            int targetX = playerPos.getX() + random.nextInt(21) - 10;
            int targetZ = playerPos.getZ() + random.nextInt(21) - 10;

            // Сканируем вертикаль сверху вниз
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