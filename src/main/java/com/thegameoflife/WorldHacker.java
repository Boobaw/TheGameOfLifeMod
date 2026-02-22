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
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class WorldHacker {
    // УМНАЯ ОЧЕРЕДЬ: хранит задачи, которые сейчас в процессе, чтобы не спамить потоки
    private static final ConcurrentHashMap<Long, Integer> PENDING_TASKS = new ConcurrentHashMap<>();

    public static void tickRadar(MinecraftServer server) {
        int targetVersion = TheGameOfLifeMod.currentRuleVersion;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = (ServerLevel) player.level();
            ChunkPos pPos = player.chunkPosition();
            int viewDist = server.getPlayerList().getViewDistance();

            for (int x = -viewDist; x <= viewDist; x++) {
                for (int z = -viewDist; z <= viewDist; z++) {
                    long posLong = ChunkPos.asLong(pPos.x + x, pPos.z + z);

                    // 1. Проверяем, не обновлен ли уже чанк, И не находится ли он прямо сейчас в обработке
                    if (TheGameOfLifeMod.CHUNK_VERSIONS.getOrDefault(posLong, 0) < targetVersion &&
                            PENDING_TASKS.getOrDefault(posLong, 0) < targetVersion) {

                        LevelChunk chunk = level.getChunkSource().getChunkNow(pPos.x + x, pPos.z + z);

                        if (chunk != null) {
                            // 2. Бронируем задачу в очереди (но НЕ помечаем как выполненную!)
                            PENDING_TASKS.put(posLong, targetVersion);

                            // Передаем targetVersion, чтобы поток знал свою "Эпоху"
                            processChunkRulesAsync(level, chunk, targetVersion);
                        }
                    }
                }
            }
        }
    }

    // =========================================
    // МЕТОДЫ УПРАВЛЕНИЯ ЭПОХАМИ
    // При каждом вызове currentRuleVersion увеличивается,
    // что автоматически "убивает" все старые асинхронные задачи.
    // =========================================
    public static void banBlocksAndSnap(Set<Block> targets) {
        TheGameOfLifeMod.UNBANNED_BLOCKS.removeAll(targets);
        TheGameOfLifeMod.BANNED_BLOCKS.addAll(targets);
        TheGameOfLifeMod.currentRuleVersion++;
        System.out.println("!!! БАН БЛОКОВ: Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    public static void unbanBlocksAndSnap(Set<Block> targets) {
        TheGameOfLifeMod.BANNED_BLOCKS.removeAll(targets);
        TheGameOfLifeMod.UNBANNED_BLOCKS.addAll(targets);
        TheGameOfLifeMod.currentRuleVersion++;
        System.out.println("!!! РАЗБАН БЛОКОВ: Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    public static void banBreakFiltersAndSnap(Set<StateFilter> targets) {
        TheGameOfLifeMod.UNBANNED_BREAK_FILTERS.removeAll(targets);
        TheGameOfLifeMod.BANNED_BREAK_FILTERS.addAll(targets);
        TheGameOfLifeMod.currentRuleVersion++;
        System.out.println("!!! БАН ФИЛЬТРОВ (УНИЧТОЖЕНИЕ): Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    public static void unbanBreakFiltersAndSnap(Set<StateFilter> targets) {
        TheGameOfLifeMod.BANNED_BREAK_FILTERS.removeAll(targets);
        TheGameOfLifeMod.UNBANNED_BREAK_FILTERS.addAll(targets);
        TheGameOfLifeMod.currentRuleVersion++;
        System.out.println("!!! РАЗБАН ФИЛЬТРОВ (УНИЧТОЖЕНИЕ): Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    public static void banResetFiltersAndSnap(Set<StateFilter> targets) {
        TheGameOfLifeMod.UNBANNED_RESET_FILTERS.removeAll(targets);
        TheGameOfLifeMod.BANNED_RESET_FILTERS.addAll(targets);
        TheGameOfLifeMod.currentRuleVersion++;
        System.out.println("!!! БАН ФИЛЬТРОВ (ОБНУЛЕНИЕ): Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    public static void unbanResetFiltersAndSnap(Set<StateFilter> targets) {
        TheGameOfLifeMod.BANNED_RESET_FILTERS.removeAll(targets);
        TheGameOfLifeMod.UNBANNED_RESET_FILTERS.addAll(targets);
        TheGameOfLifeMod.currentRuleVersion++;
        System.out.println("!!! РАЗБАН ФИЛЬТРОВ (ОБНУЛЕНИЕ): Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }


    public static void processChunkRulesAsync(ServerLevel level, LevelChunk chunk, int taskVersion) {
        boolean nothingToBan = TheGameOfLifeMod.BANNED_BLOCKS.isEmpty() &&
                TheGameOfLifeMod.BANNED_BREAK_FILTERS.isEmpty() &&
                TheGameOfLifeMod.BANNED_RESET_FILTERS.isEmpty();

        boolean nothingToUnban = TheGameOfLifeMod.UNBANNED_BLOCKS.isEmpty() &&
                TheGameOfLifeMod.UNBANNED_BREAK_FILTERS.isEmpty() &&
                TheGameOfLifeMod.UNBANNED_RESET_FILTERS.isEmpty();

        if (nothingToBan && nothingToUnban) {
            PENDING_TASKS.remove(chunk.getPos().toLong());
            return;
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        long chunkPosLong = chunk.getPos().toLong();

        CompletableFuture.supplyAsync(() -> {
            try {
                // KILL SWITCH #1: Проверка на старте потока
                if (TheGameOfLifeMod.currentRuleVersion != taskVersion) return null;

                ChunkUpdateData data = new ChunkUpdateData(chunk);
                BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

                // ФАЗА 1: ВОССТАНОВЛЕНИЕ ИЗ АРХИВА (UNBAN)
                ConcurrentHashMap<Long, BlockState> chunkMemory = TheGameOfLifeMod.CHUNK_MEMORY.get(chunkPosLong);

                if (chunkMemory != null && !chunkMemory.isEmpty() && !nothingToUnban) {
                    for (var entry : chunkMemory.entrySet()) {
                        long blockPosLong = entry.getKey();
                        BlockState savedState = entry.getValue();

                        boolean isUnbanned = TheGameOfLifeMod.UNBANNED_BLOCKS.contains(savedState.getBlock()) ||
                                StateFilter.check(TheGameOfLifeMod.UNBANNED_BREAK_FILTERS, savedState) ||
                                StateFilter.check(TheGameOfLifeMod.UNBANNED_RESET_FILTERS, savedState);

                        if (isUnbanned) {
                            mPos.set(blockPosLong);
                            BlockState currentState = chunk.getBlockState(mPos);

                            boolean canOverwrite = currentState.isAir() ||
                                    currentState.getBlock() == Blocks.WATER ||
                                    currentState.getBlock() == Blocks.LAVA ||
                                    currentState.getBlock() == savedState.getBlock();

                            if (canOverwrite) {
                                data.blocksToRestore.put(blockPosLong, savedState);

                                int lx = mPos.getX() - chunk.getPos().getMinBlockX();
                                int lz = mPos.getZ() - chunk.getPos().getMinBlockZ();
                                int idx = lx + lz * 16;
                                if (mPos.getY() > data.highestY[idx]) data.highestY[idx] = mPos.getY();
                                if (mPos.getY() < data.lowestY[idx]) data.lowestY[idx] = mPos.getY();
                            }
                        }
                    }
                }

                // ФАЗА 2: УДАЛЕНИЕ / ОБНУЛЕНИЕ (BAN)
                if (!nothingToBan) {
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {

                        // KILL SWITCH #2: Периодическая проверка между секциями для экономии CPU
                        if (TheGameOfLifeMod.currentRuleVersion != taskVersion) return null;

                        LevelChunkSection section = sections[i];
                        if (section == null || section.hasOnlyAir()) continue;

                        boolean hasBannedTargets = section.getStates().maybeHas(state ->
                                TheGameOfLifeMod.BANNED_BLOCKS.contains(state.getBlock()) ||
                                        StateFilter.check(TheGameOfLifeMod.BANNED_BREAK_FILTERS, state) ||
                                        StateFilter.check(TheGameOfLifeMod.BANNED_RESET_FILTERS, state)
                        );

                        if (!hasBannedTargets) continue;

                        int startY = -64 + (i * 16);
                        int startX = chunk.getPos().getMinBlockX();
                        int startZ = chunk.getPos().getMinBlockZ();

                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                for (int y = 0; y < 16; y++) {
                                    int realY = startY + y;
                                    mPos.set(startX + x, realY, startZ + z);

                                    BlockState currentState = chunk.getBlockState(mPos);

                                    // ЛОГИКА SNAPSHOT: Сохраняем оригинал ДО применения изменений
                                    if (TheGameOfLifeMod.BANNED_BLOCKS.contains(currentState.getBlock()) ||
                                            StateFilter.check(TheGameOfLifeMod.BANNED_BREAK_FILTERS, currentState)) {

                                        data.blocksToBackup.put(mPos.asLong(), currentState); // Снимок
                                        data.blocksToModify.put(mPos.asLong(), air);

                                    } else if (WorldHacker.StateFilter.check(TheGameOfLifeMod.BANNED_RESET_FILTERS, currentState)) {
                                        BlockState resetState = currentState;

                                        for (WorldHacker.StateFilter f : TheGameOfLifeMod.BANNED_RESET_FILTERS) {
                                            if (f.matches(currentState)) {
                                                resetState = applyReset(resetState, f.property());
                                            }
                                        }
                                        data.blocksToBackup.put(mPos.asLong(), currentState); // Снимок
                                        data.blocksToModify.put(mPos.asLong(), resetState);
                                    } else {
                                        continue;
                                    }

                                    int idx = x + z * 16;
                                    if (realY > data.highestY[idx]) data.highestY[idx] = realY;
                                    if (realY < data.lowestY[idx]) data.lowestY[idx] = realY;
                                }
                            }
                        }
                    }
                }

                return (data.blocksToModify.isEmpty() && data.blocksToRestore.isEmpty()) ? null : data;

            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }).thenAcceptAsync(data -> {
            // Очищаем статус задачи в любом случае
            PENDING_TASKS.remove(chunkPosLong);

            // KILL SWITCH #3 (Финальный): Если эпоха сменилась ИЛИ данных нет — откат транзакции
            if (data == null || TheGameOfLifeMod.currentRuleVersion != taskVersion) return;

            try {
                BlockPos.MutableBlockPos syncPos = new BlockPos.MutableBlockPos();
                ConcurrentHashMap<Long, BlockState> chunkMemory =
                        TheGameOfLifeMod.CHUNK_MEMORY.computeIfAbsent(chunkPosLong, k -> new ConcurrentHashMap<>());

                // 1. ПРИМЕНЯЕМ ВОССТАНОВЛЕНИЕ
                for (var entry : data.blocksToRestore.long2ObjectEntrySet()) {
                    long posLong = entry.getLongKey();
                    chunk.setBlockState(syncPos.set(posLong), entry.getValue());
                    chunkMemory.remove(posLong);
                }

                // 2. ПРИМЕНЯЕМ УДАЛЕНИЕ / ОБНУЛЕНИЕ С БЭКАПОМ
                for (var entry : data.blocksToModify.long2ObjectEntrySet()) {
                    long posLong = entry.getLongKey();
                    BlockState newState = entry.getValue();
                    syncPos.set(posLong);

                    // Достаем снимок состояния, который мы сделали ПЕРЕД изменением, и кладем в архив
                    BlockState originalState = data.blocksToBackup.get(posLong);
                    if (originalState != null) {
                        chunkMemory.putIfAbsent(posLong, originalState);
                    }

                    // Применяем новое состояние в мир
                    chunk.setBlockState(syncPos, newState);
                }

                chunk.markUnsaved();
                broadcastUpdate(level, chunk);
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.add(data);

                // === АТОМАРНЫЙ КОММИТ ===
                // Отмечаем чанк как выполненный ТОЛЬКО здесь, когда всё успешно завершилось
                TheGameOfLifeMod.CHUNK_VERSIONS.put(chunkPosLong, taskVersion);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, level.getServer());
    }

    public record StateFilter(Block block, Property<?> property, Comparable<?> value) {
        public boolean matches(BlockState state) {
            if (block != null && !state.is(block)) return false;
            return state.hasProperty(property) && state.getValue(property).equals(value);
        }

        public static boolean check(Iterable<StateFilter> filters, BlockState state) {
            for (StateFilter f : filters) {
                if (f.matches(state)) return true;
            }
            return false;
        }
    }

    private static <T extends Comparable<T>> BlockState applyReset(BlockState state, Property<T> prop) {
        return state.setValue(prop, state.getBlock().defaultBlockState().getValue(prop));
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
        int sent = 0;
        while (!TheGameOfLifeMod.LIGHT_PACKET_QUEUE.isEmpty() && sent < 2) {
            ChunkUpdateData d = TheGameOfLifeMod.LIGHT_PACKET_QUEUE.poll();
            if (d != null) broadcastUpdate((ServerLevel) d.chunk.getLevel(), d.chunk);
            sent++;
        }

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

        public final Long2ObjectOpenHashMap<BlockState> blocksToModify = new Long2ObjectOpenHashMap<>();
        public final Long2ObjectOpenHashMap<BlockState> blocksToRestore = new Long2ObjectOpenHashMap<>();

        // НОВОЕ: Временное хранилище снимков блоков до их изменения
        public final Long2ObjectOpenHashMap<BlockState> blocksToBackup = new Long2ObjectOpenHashMap<>();

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
