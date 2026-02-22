package com.thegameoflife;

import com.thegameoflife.TheGameOfLifeMod;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class WorldHacker {
    public static void tickRadar(MinecraftServer server) {
        // Предохранитель: если банить нечего, отдыхаем
        if (TheGameOfLifeMod.BANNED_BLOCKS.isEmpty()) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = (ServerLevel) player.level();
            ChunkPos pPos = player.chunkPosition();
            int viewDist = server.getPlayerList().getViewDistance();

            for (int x = -viewDist; x <= viewDist; x++) {
                for (int z = -viewDist; z <= viewDist; z++) {
                    long posLong = ChunkPos.asLong(pPos.x + x, pPos.z + z);

                    // 1. Сначала быстрая проверка по памяти (чтобы не дергать ядро игры)
                    if (TheGameOfLifeMod.CHUNK_VERSIONS.getOrDefault(posLong, 0) < TheGameOfLifeMod.currentRuleVersion) {

                        // 2. Достаем чанк без пролагов генерации
                        LevelChunk chunk = level.getChunkSource().getChunkNow(pPos.x + x, pPos.z + z);

                        if (chunk != null) {
                            // 3. Бронируем новую эпоху и отправляем в мясорубку
                            TheGameOfLifeMod.CHUNK_VERSIONS.put(posLong, TheGameOfLifeMod.currentRuleVersion);
                            cleanSingleChunkAsync(level, chunk, TheGameOfLifeMod.BANNED_BLOCKS);
                        }
                    }
                }
            }
        }
    }

    // Добавь этот метод для ТЕСТА (вызови его, например, при чате)
    public static void banAndSnap(ServerLevel level, Set<Block> targets) {
        TheGameOfLifeMod.BANNED_BLOCKS.addAll(targets);
        TheGameOfLifeMod.currentRuleVersion++;
        System.out.println("!!! ЩЕЛЧОК: Эпоха " + TheGameOfLifeMod.currentRuleVersion + ", Блоков в бане: " + TheGameOfLifeMod.BANNED_BLOCKS.size());
    }

    public static void cleanSingleChunkAsync(ServerLevel level, LevelChunk chunk, Set<Block> targets) {
        BlockState air = Blocks.AIR.defaultBlockState();

        CompletableFuture.supplyAsync(() -> {
            try {
                ChunkUpdateData data = new ChunkUpdateData(chunk);
                boolean modified = false;
                BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];

                    if (section == null || section.hasOnlyAir()) continue;

                    // УНИВЕРСАЛЬНАЯ ФОРМУЛА ВЫСОТЫ:
                    // Берем самое дно чанка (chunk.getMinBuildHeight(), обычно это -64)
                    // И прибавляем к нему номер секции, умноженный на её высоту (16)
                    int startY = -64 + (i * 16);

                    int startX = chunk.getPos().getMinBlockX();
                    int startZ = chunk.getPos().getMinBlockZ();

                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 0; y < 16; y++) {
                                int realY = startY + y;
                                mPos.set(startX + x, realY, startZ + z);

                                Block currentBlock = chunk.getBlockState(mPos).getBlock();
                                if (targets.contains(currentBlock)) {
                                    data.changedPositions.add(mPos.asLong());
                                    modified = true;
                                }
                            }
                        }
                    }
                }
                return modified ? data : null;
            } catch (Exception e) {
                e.printStackTrace(); // Увидим ошибку, если поток упал
                return null;
            }
        }).thenAcceptAsync(data -> {
            if (data == null) return;

            try {
                BlockPos.MutableBlockPos syncPos = new BlockPos.MutableBlockPos();
                for (int i = 0; i < data.changedPositions.size(); i++) {
                    chunk.setBlockState(syncPos.set(data.changedPositions.getLong(i)), air);
                }
                chunk.markUnsaved();

                broadcastUpdate(level, chunk);
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.add(data);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, level.getServer());
    }

    private static void broadcastUpdate(ServerLevel level, LevelChunk chunk) {
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        int dist = level.getServer().getPlayerList().getViewDistance();
        for (ServerPlayer p : level.players()) {
            if (Math.abs(p.chunkPosition().x - chunk.getPos().x) <= dist &&
                    Math.abs(p.chunkPosition().z - chunk.getPos().z) <= dist) {
                p.connection.send(packet);
            }
        }
    }

    public static void processLightQueues(MinecraftServer server) {
        // Рассылка готового света
        int sent = 0;
        while (!TheGameOfLifeMod.LIGHT_PACKET_QUEUE.isEmpty() && sent < 2) {
            ChunkUpdateData d = TheGameOfLifeMod.LIGHT_PACKET_QUEUE.poll();
            if (d != null) broadcastUpdate((ServerLevel) d.chunk.getLevel(), d.chunk);
            sent++;
        }

        // Просчет столбов света
        int cols = 0;
        while (!TheGameOfLifeMod.LIGHT_CALC_QUEUE.isEmpty() && cols < 1000) {
            ChunkUpdateData d = TheGameOfLifeMod.LIGHT_CALC_QUEUE.peek();
            if (d != null && d.updateLight(1000 - cols)) {
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.poll();
                TheGameOfLifeMod.LIGHT_PACKET_QUEUE.add(d);
            }
            cols += 1000;
        }
    }

    public static class ChunkUpdateData {
        public final LevelChunk chunk;
        public final LongArrayList changedPositions = new LongArrayList();
        public final int[] highestY = new int[256];
        public final int[] lowestY = new int[256];
        private int colIdx = 0;

        public ChunkUpdateData(LevelChunk chunk) {
            this.chunk = chunk;
            for (int i = 0; i < 256; i++) {
                highestY[i] = Integer.MIN_VALUE;
                lowestY[i] = Integer.MAX_VALUE;
            }
        }

        public boolean updateLight(int limit) {
            LevelLightEngine le = chunk.getLevel().getLightEngine();
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            int startX = chunk.getPos().getMinBlockX();
            int startZ = chunk.getPos().getMinBlockZ();

            int done = 0;
            while (colIdx < 256 && done < limit) {
                if (highestY[colIdx] != Integer.MIN_VALUE) {
                    for (int y = lowestY[colIdx]; y <= highestY[colIdx]; y++) {
                        le.checkBlock(p.set(startX + (colIdx % 16), y, startZ + (colIdx / 16)));
                    }
                }
                colIdx++;
                done++;
            }
            return colIdx >= 256;
        }
    }
}
