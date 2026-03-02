package com.thegameoflife;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.Set;

public class GlobalRadar {

    // Вызывать каждый тик из главного серверного цикла (TickEvent.ServerTickEvent)
    public static void tickEntityRadar(MinecraftServer server) {

        // =========================================
        // 1. ИГРОКИ (Инвентарь и Броня)
        // =========================================
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemHacker.processPlayerInventory(player);
            DataHacker.processPlayerInventory(player);
        }

        // Перебираем все измерения (Обычный мир, Незер, Энд)
        for (ServerLevel level : server.getAllLevels()) {

            // =========================================
            // 2. ВСЕ СУЩНОСТИ В МИРЕ (Мобы, Дроп, Рамки, Вагонетки)
            // =========================================
            // level.getAllEntities() работает напрямую с внутренним списком сервера.
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ServerPlayer) continue; // Игроков уже отсканировали

                // Передаем сущность каждому хакеру.
                // Внутри они сами за миллисекунду проверят свои эпохи и решат, нужно ли что-то делать.
                // Баним сами энтити
                EntityHacker.processEntity(entity);

                // Баним предметы у энтити
                if (!entity.isRemoved()) {
                    ItemHacker.processEntity(entity);
                }

                // Баним компоненты у предметов у энтити
                if (!entity.isRemoved()) {
                    DataHacker.processEntity(entity);
                }
            }

            // =========================================
            // 3. СУНДУКИ И ХРАНИЛИЩА (BlockEntities)
            // =========================================
            // Сканируем только те сундуки, которые находятся рядом с живыми игроками
            int viewDist = server.getPlayerList().getViewDistance();
            Set<Long> scannedChunks = new HashSet<>();

            for (ServerPlayer player : level.players()) {
                ChunkPos pPos = player.chunkPosition();

                for (int x = -viewDist; x <= viewDist; x++) {
                    for (int z = -viewDist; z <= viewDist; z++) {
                        long chunkPosLong = ChunkPos.asLong(pPos.x + x, pPos.z + z);

                        // Если этот чанк уже отсканирован (например, если два игрока стоят рядом) - пропускаем
                        if (scannedChunks.add(chunkPosLong)) {
                            LevelChunk chunk = level.getChunkSource().getChunkNow(pPos.x + x, pPos.z + z);

                            if (chunk != null) {
                                for (BlockEntity be : chunk.getBlockEntities().values()) {
                                    ItemHacker.processBlockEntity(be);
                                    DataHacker.processBlockEntity(be);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}