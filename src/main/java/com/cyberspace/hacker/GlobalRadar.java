package com.cyberspace.hacker;

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
        // 1. ИГРОКИ
        // =========================================
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            EntityHacker.processEntity(player);
            if (!player.isRemoved()) {
                EntityStateHacker.processPlayer(player);
                ItemHacker.processPlayerInventory(player);
                DataHacker.processPlayerInventory(player);
            }
        }

        // Перебираем все измерения (Обычный мир, Незер, Энд)
        for (ServerLevel level : server.getAllLevels()) {

            // =========================================
            // 2. ВСЕ СУЩНОСТИ В МИРЕ (Мобы, Дроп, Рамки, Вагонетки)
            // =========================================
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ServerPlayer) continue;

                EntityHacker.processEntity(entity);

                if (!entity.isRemoved()) {
                    EntityStateHacker.processEntity(entity);
                    ItemHacker.processEntity(entity);
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