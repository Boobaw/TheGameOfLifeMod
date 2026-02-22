package com.thegameoflife;

import com.thegameoflife.TheGameOfLifeMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import java.util.concurrent.ConcurrentHashMap;

public class WorldHacker {
    public static void tickRadar(MinecraftServer server) {
        // Если оба списка пустые (нечего удалять и нечего возвращать), тогда выходим
        if (TheGameOfLifeMod.BANNED_BLOCKS.isEmpty() && TheGameOfLifeMod.UNBANNED_BLOCKS.isEmpty()) return;

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
                            processChunkRulesAsync(level, chunk);
                        }
                    }
                }
            }
        }
    }

    // Команда: УДАЛИТЬ
    public static void banAndSnap(Set<Block> targets) {
        TheGameOfLifeMod.UNBANNED_BLOCKS.removeAll(targets); // Убираем из амнистии
        TheGameOfLifeMod.BANNED_BLOCKS.addAll(targets);       // Добавляем в бан
        TheGameOfLifeMod.currentRuleVersion++;                // Сдвигаем эпоху
        System.out.println("!!! БАН: Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    // Команда: ВЕРНУТЬ
    public static void unbanAndSnap(Set<Block> targets) {
        TheGameOfLifeMod.BANNED_BLOCKS.removeAll(targets);    // Убираем из бана
        TheGameOfLifeMod.UNBANNED_BLOCKS.addAll(targets);     // Отправляем в буфер возврата
        TheGameOfLifeMod.currentRuleVersion++;                // Сдвигаем эпоху, чтобы Радар начал работу
        System.out.println("!!! РАЗБАН: Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    public static void processChunkRulesAsync(ServerLevel level, LevelChunk chunk) {
        // Если оба списка пустые, делать нечего
        if (TheGameOfLifeMod.BANNED_BLOCKS.isEmpty() && TheGameOfLifeMod.UNBANNED_BLOCKS.isEmpty()) return;

        BlockState air = Blocks.AIR.defaultBlockState();
        long chunkPosLong = chunk.getPos().toLong();

        CompletableFuture.supplyAsync(() -> {
            try {
                ChunkUpdateData data = new ChunkUpdateData(chunk);
                BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

                // ==========================================
                // ФАЗА 1: ВОССТАНОВЛЕНИЕ (UNBAN)
                // ==========================================
                ConcurrentHashMap<Long, BlockState> chunkMemory = TheGameOfLifeMod.CHUNK_MEMORY.get(chunkPosLong);

                if (chunkMemory != null && !chunkMemory.isEmpty() && !TheGameOfLifeMod.UNBANNED_BLOCKS.isEmpty()) {
                    for (var entry : chunkMemory.entrySet()) {
                        long blockPosLong = entry.getKey();
                        BlockState savedState = entry.getValue();

                        // Если блок амнистирован
                        if (TheGameOfLifeMod.UNBANNED_BLOCKS.contains(savedState.getBlock())) {
                            mPos.set(blockPosLong);

                            BlockState currentState = chunk.getBlockState(mPos);

                            // ЗАЩИТА С УЧЕТОМ ЖИДКОСТЕЙ:
                            // Восстанавливаем, если там Воздух ИЛИ Вода ИЛИ Лава
                            boolean canOverwrite = currentState.isAir() ||
                                    currentState.getBlock() == Blocks.WATER ||
                                    currentState.getBlock() == Blocks.LAVA;

                            if (canOverwrite) {
                                data.blocksToRestore.put(blockPosLong, savedState);

                                // Обновляем данные для света
                                int lx = mPos.getX() - chunk.getPos().getMinBlockX();
                                int lz = mPos.getZ() - chunk.getPos().getMinBlockZ();
                                int idx = lx + lz * 16;
                                if (mPos.getY() > data.highestY[idx]) data.highestY[idx] = mPos.getY();
                                if (mPos.getY() < data.lowestY[idx]) data.lowestY[idx] = mPos.getY();
                            }
                        }
                    }
                }

                // ==========================================
                // ФАЗА 2: УДАЛЕНИЕ (BAN)
                // ==========================================
                if (!TheGameOfLifeMod.BANNED_BLOCKS.isEmpty()) {
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        LevelChunkSection section = sections[i];
                        if (section == null || section.hasOnlyAir()) continue;

                        int startY = -64 + (i * 16);
                        int startX = chunk.getPos().getMinBlockX();
                        int startZ = chunk.getPos().getMinBlockZ();

                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                for (int y = 0; y < 16; y++) {
                                    int realY = startY + y;
                                    mPos.set(startX + x, realY, startZ + z);

                                    BlockState currentState = chunk.getBlockState(mPos);
                                    if (TheGameOfLifeMod.BANNED_BLOCKS.contains(currentState.getBlock())) {
                                        data.positionsToAir.add(mPos.asLong());

                                        // Обновляем свет
                                        int idx = x + z * 16;
                                        if (realY > data.highestY[idx]) data.highestY[idx] = realY;
                                        if (realY < data.lowestY[idx]) data.lowestY[idx] = realY;
                                    }
                                }
                            }
                        }
                    }
                }

                // Отменяем апдейт, если нечего менять
                return (data.positionsToAir.isEmpty() && data.blocksToRestore.isEmpty()) ? null : data;

            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }).thenAcceptAsync(data -> {
            if (data == null) return;

            try {
                BlockPos.MutableBlockPos syncPos = new BlockPos.MutableBlockPos();

                // Получаем или создаем память чанка
                ConcurrentHashMap<Long, BlockState> chunkMemory =
                        TheGameOfLifeMod.CHUNK_MEMORY.computeIfAbsent(chunkPosLong, k -> new ConcurrentHashMap<>());

                // 1. ПРИМЕНЯЕМ ВОССТАНОВЛЕНИЕ (UNBAN)
                for (var entry : data.blocksToRestore.long2ObjectEntrySet()) {
                    long posLong = entry.getLongKey();
                    chunk.setBlockState(syncPos.set(posLong), entry.getValue());
                    chunkMemory.remove(posLong); // Стираем запись после успешного возврата
                }

                // 2. ПРИМЕНЯЕМ УДАЛЕНИЕ (BAN)
                for (int i = 0; i < data.positionsToAir.size(); i++) {
                    long posLong = data.positionsToAir.getLong(i);
                    syncPos.set(posLong);

                    // Используем putIfAbsent, чтобы не перезаписать оригинальный блок Воздухом,
                    // если мы удаляем что-то дважды
                    chunkMemory.putIfAbsent(posLong, chunk.getBlockState(syncPos));

                    chunk.setBlockState(syncPos, air);
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
        public final LongArrayList positionsToAir = new LongArrayList(); // Блоки на удаление
        // НОВОЕ: Блоки на восстановление (Координата -> Состояние)
        public final Long2ObjectOpenHashMap<BlockState> blocksToRestore = new Long2ObjectOpenHashMap<>();

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
