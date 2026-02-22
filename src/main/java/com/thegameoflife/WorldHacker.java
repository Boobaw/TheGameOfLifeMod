package com.thegameoflife;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
    // УМНАЯ ОЧЕРЕДЬ: защищает от дублирования задач для одного чанка
    private static final ConcurrentHashMap<Long, Integer> PENDING_TASKS = new ConcurrentHashMap<>();

    public static void tickRadar(MinecraftServer server) {
        final int targetVersion = TheGameOfLifeMod.currentRuleVersion;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = (ServerLevel) player.level();
            ChunkPos pPos = player.chunkPosition();
            int viewDist = server.getPlayerList().getViewDistance();

            for (int x = -viewDist; x <= viewDist; x++) {
                for (int z = -viewDist; z <= viewDist; z++) {
                    long posLong = ChunkPos.asLong(pPos.x + x, pPos.z + z);

                    // 1. Фильтр: пропускаем, если чанк уже обновлен ИЛИ прямо сейчас обрабатывается этой версией
                    if (TheGameOfLifeMod.CHUNK_VERSIONS.getOrDefault(posLong, 0) >= targetVersion ||
                            PENDING_TASKS.getOrDefault(posLong, 0) >= targetVersion) {
                        continue;
                    }

                    // 2. Берем чанк без генерации (избегаем лагов)
                    LevelChunk chunk = level.getChunkSource().getChunkNow(pPos.x + x, pPos.z + z);

                    if (chunk != null) {
                        // 3. Бронируем чанк и отправляем в конвейер
                        PENDING_TASKS.put(posLong, targetVersion);
                        processChunkRulesAsync(level, chunk, targetVersion);
                    }
                }
            }
        }
    }

    // =========================================
    // ЭПОХИ (Атомарные инкременты)
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

    // =========================================
    // АСИНХРОННЫЙ КОНВЕЙЕР (Fast Math Version)
    // =========================================
    public static void processChunkRulesAsync(ServerLevel level, LevelChunk chunk, int taskVersion) {
        final boolean nothingToBan = TheGameOfLifeMod.BANNED_BLOCKS.isEmpty() &&
                TheGameOfLifeMod.BANNED_BREAK_FILTERS.isEmpty() &&
                TheGameOfLifeMod.BANNED_RESET_FILTERS.isEmpty();

        final boolean nothingToUnban = TheGameOfLifeMod.UNBANNED_BLOCKS.isEmpty() &&
                TheGameOfLifeMod.UNBANNED_BREAK_FILTERS.isEmpty() &&
                TheGameOfLifeMod.UNBANNED_RESET_FILTERS.isEmpty();

        final long chunkPosLong = chunk.getPos().toLong();

        if (nothingToBan && nothingToUnban) {
            PENDING_TASKS.remove(chunkPosLong);
            TheGameOfLifeMod.CHUNK_VERSIONS.put(chunkPosLong, taskVersion);
            return;
        }

        // ВАЖНО: Без level.getServer() сканирование летит на ForkJoinPool (свободные ядра процессора)
        CompletableFuture.supplyAsync(() -> {
            try {
                // Kill Switch #1
                if (TheGameOfLifeMod.currentRuleVersion != taskVersion) return null;

                ChunkUpdateData data = new ChunkUpdateData(chunk);
                BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
                BlockState air = Blocks.AIR.defaultBlockState();

                final int startX = chunk.getPos().getMinBlockX();
                final int startZ = chunk.getPos().getMinBlockZ();

                // ФАЗА 1: ВОССТАНОВЛЕНИЕ (UNBAN)
                ConcurrentHashMap<Long, BlockState> chunkMemory = TheGameOfLifeMod.CHUNK_MEMORY.get(chunkPosLong);
                if (chunkMemory != null && !chunkMemory.isEmpty() && !nothingToUnban) {
                    for (var entry : chunkMemory.entrySet()) {
                        long blockPosLong = entry.getKey();
                        BlockState savedState = entry.getValue();

                        boolean isUnbanned = TheGameOfLifeMod.UNBANNED_BLOCKS.contains(savedState.getBlock()) ||
                                StateFilter.check(TheGameOfLifeMod.UNBANNED_BREAK_FILTERS, savedState) ||
                                StateFilter.check(TheGameOfLifeMod.UNBANNED_RESET_FILTERS, savedState);

                        if (isUnbanned) {
                            BlockState currentState = chunk.getBlockState(mPos.set(blockPosLong));
                            if (currentState.isAir() || currentState.getBlock() == Blocks.WATER || currentState.getBlock() == Blocks.LAVA || currentState.getBlock() == savedState.getBlock()) {
                                data.blocksToRestore.put(blockPosLong, savedState);
                                data.recordHeight(mPos.getX() - startX, mPos.getY(), mPos.getZ() - startZ);
                            }
                        }
                    }
                }

                // ФАЗА 2: УДАЛЕНИЕ / ОБНУЛЕНИЕ (BAN)
                if (!nothingToBan) {
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        // Kill Switch #2 (Проверка между секциями)
                        if (TheGameOfLifeMod.currentRuleVersion != taskVersion) return null;

                        LevelChunkSection section = sections[i];
                        if (section == null || section.hasOnlyAir()) continue;

                        // БЫСТРЫЙ ФИЛЬТР: Проверяем палитру, чтобы мгновенно скипать пустые для нас секции
                        boolean hasTargets = section.getStates().maybeHas(state -> {
                            if (state.isAir()) return false;
                            return TheGameOfLifeMod.BANNED_BLOCKS.contains(state.getBlock()) ||
                                    StateFilter.check(TheGameOfLifeMod.BANNED_BREAK_FILTERS, state) ||
                                    StateFilter.check(TheGameOfLifeMod.BANNED_RESET_FILTERS, state);
                        });

                        if (!hasTargets) continue;

                        final int startY = -64 + (i << 4); // i * 16 через побитовый сдвиг

                        for (int y = 0; y < 16; y++) {
                            int realY = startY + y;
                            for (int z = 0; z < 16; z++) {
                                int realZ = startZ + z;
                                for (int x = 0; x < 16; x++) {
                                    BlockState currentState = section.getBlockState(x, y, z);

                                    if (TheGameOfLifeMod.BANNED_BLOCKS.contains(currentState.getBlock()) ||
                                            StateFilter.check(TheGameOfLifeMod.BANNED_BREAK_FILTERS, currentState)) {

                                        long p = mPos.set(startX + x, realY, realZ).asLong();
                                        data.blocksToBackup.put(p, currentState); // SNAPSHOT
                                        data.blocksToModify.put(p, air);
                                        data.recordHeight(x, realY, z);

                                    } else if (WorldHacker.StateFilter.check(TheGameOfLifeMod.BANNED_RESET_FILTERS, currentState)) {
                                        BlockState resetState = currentState;
                                        for (WorldHacker.StateFilter f : TheGameOfLifeMod.BANNED_RESET_FILTERS) {
                                            if (f.matches(currentState)) {
                                                resetState = applyReset(resetState, f.property());
                                            }
                                        }
                                        long p = mPos.set(startX + x, realY, realZ).asLong();
                                        data.blocksToBackup.put(p, currentState); // SNAPSHOT
                                        data.blocksToModify.put(p, resetState);
                                        data.recordHeight(x, realY, z);
                                    }
                                }
                            }
                        }
                    }
                }

                return (data.blocksToModify.isEmpty() && data.blocksToRestore.isEmpty()) ? null : data;
            } catch (Exception e) {
                return null;
            }

            // ВАЖНО: Применение происходит строго в главном потоке сервера для избежания десинков
        }).thenAcceptAsync(data -> {
            PENDING_TASKS.remove(chunkPosLong); // Снимаем блокировку

            // Kill Switch #3 (Финальная проверка перед коммитом)
            if (data == null || TheGameOfLifeMod.currentRuleVersion != taskVersion) return;

            BlockPos.MutableBlockPos syncPos = new BlockPos.MutableBlockPos();
            ConcurrentHashMap<Long, BlockState> chunkMemory =
                    TheGameOfLifeMod.CHUNK_MEMORY.computeIfAbsent(chunkPosLong, k -> new ConcurrentHashMap<>());

            LevelChunkSection[] sections = chunk.getSections();
            int minBuildHeight = -64; // Дно мира

            // =========================================================
            // РЕЖИМ НИНДЗЯ: Прямая запись в секции (БЕЗ ОБНОВЛЕНИЯ СОСЕДЕЙ)
            // =========================================================
            data.blocksToRestore.forEach((pos, state) -> {
                syncPos.set(pos);
                int y = syncPos.getY();
                int secIdx = (y - minBuildHeight) >> 4;

                if (secIdx >= 0 && secIdx < sections.length) {
                    LevelChunkSection section = sections[secIdx];
                    if (section != null) {
                        // Пишем напрямую в память. Никакой физики, никаких onRemove и onPlace!
                        section.setBlockState(syncPos.getX() & 15, y & 15, syncPos.getZ() & 15, state);
                    }
                }
                chunkMemory.remove(pos);
            });

            data.blocksToModify.forEach((pos, newState) -> {
                syncPos.set(pos);
                int y = syncPos.getY();
                int secIdx = (y - minBuildHeight) >> 4;

                if (secIdx >= 0 && secIdx < sections.length) {
                    LevelChunkSection section = sections[secIdx];
                    if (section != null) {
                        // Бесшумное удаление. Вода за границей чанка ничего не узнает.
                        section.setBlockState(syncPos.getX() & 15, y & 15, syncPos.getZ() & 15, newState);
                    }
                }

                BlockState originalState = data.blocksToBackup.get(pos);
                if (originalState != null) chunkMemory.putIfAbsent(pos, originalState);
            });

            chunk.markUnsaved();
            broadcastUpdate(level, chunk);
            TheGameOfLifeMod.LIGHT_CALC_QUEUE.add(data);

            // Завершение транзакции
            TheGameOfLifeMod.CHUNK_VERSIONS.put(chunkPosLong, taskVersion);

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
            int dx = p.chunkPosition().x - chunk.getPos().x;
            int dz = p.chunkPosition().z - chunk.getPos().z;
            if ((dx >= -dist && dx <= dist) && (dz >= -dist && dz <= dist)) {
                p.connection.send(packet);
            }
        }
    }

    public static void processLightQueues(MinecraftServer server) {
        // OVERDRIVE: Увеличили рассылку готовых пакетов в 5 раз
        int sent = 0;
        while (!TheGameOfLifeMod.LIGHT_PACKET_QUEUE.isEmpty() && sent < 10) {
            ChunkUpdateData d = TheGameOfLifeMod.LIGHT_PACKET_QUEUE.poll();
            if (d != null) broadcastUpdate((ServerLevel) d.chunk.getLevel(), d.chunk);
            sent++;
        }

        // OVERDRIVE: Увеличили просчет световых столбов в 15 раз
        int cols = 0;
        while (!TheGameOfLifeMod.LIGHT_CALC_QUEUE.isEmpty() && cols < 15000) {
            ChunkUpdateData d = TheGameOfLifeMod.LIGHT_CALC_QUEUE.peek();
            if (d != null && d.updateLight(15000 - cols)) {
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.poll();
                TheGameOfLifeMod.LIGHT_PACKET_QUEUE.add(d);
            }
            cols += 5000;
        }
    }

    public static class ChunkUpdateData {
        public final LevelChunk chunk;
        public final Long2ObjectOpenHashMap<BlockState> blocksToModify = new Long2ObjectOpenHashMap<>();
        public final Long2ObjectOpenHashMap<BlockState> blocksToRestore = new Long2ObjectOpenHashMap<>();
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

        public void recordHeight(int lx, int y, int lz) {
            int idx = lx | (lz << 4);
            if (y > highestY[idx]) highestY[idx] = y;
            if (y < lowestY[idx]) lowestY[idx] = y;
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
                        le.checkBlock(p.set(startX + (colIdx & 15), y, startZ + (colIdx >> 4)));
                    }
                }
                colIdx++;
                done++;
            }
            return colIdx >= 256;
        }
    }

    public static boolean isChunkFrozen(long chunkPosLong) {
        return PENDING_TASKS.containsKey(chunkPosLong);
    }
}