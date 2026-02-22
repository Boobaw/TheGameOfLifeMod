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
    public static void tickRadar(MinecraftServer server) {
        // Тут можно вставить блок на сон ядра до старта игры

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

    // =========================================
    // 1. УПРАВЛЕНИЕ ЦЕЛЫМИ БЛОКАМИ (Block -> Air)
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

    // =========================================
    // 2. УНИЧТОЖЕНИЕ СОСТОЯНИЙ (StateFilter -> Воздух)
    // =========================================
    public static void banBreakFiltersAndSnap(Set<StateFilter> targets) {
        TheGameOfLifeMod.UNBANNED_BREAK_FILTERS.removeAll(targets); // Убираем из амнистии
        TheGameOfLifeMod.BANNED_BREAK_FILTERS.addAll(targets);      // Добавляем в бан
        TheGameOfLifeMod.currentRuleVersion++;                      // Щелчок!
        System.out.println("!!! БАН ФИЛЬТРОВ (УНИЧТОЖЕНИЕ): Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    public static void unbanBreakFiltersAndSnap(Set<StateFilter> targets) {
        TheGameOfLifeMod.BANNED_BREAK_FILTERS.removeAll(targets);   // Убираем из бана
        TheGameOfLifeMod.UNBANNED_BREAK_FILTERS.addAll(targets);    // Отправляем в буфер возврата
        TheGameOfLifeMod.currentRuleVersion++;                      // Щелчок!
        System.out.println("!!! РАЗБАН ФИЛЬТРОВ (УНИЧТОЖЕНИЕ): Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    // =========================================
    // 3. ОБНУЛЕНИЕ СОСТОЯНИЙ (StateFilter -> Дефолтное состояние)
    // =========================================
    public static void banResetFiltersAndSnap(Set<StateFilter> targets) {
        TheGameOfLifeMod.UNBANNED_RESET_FILTERS.removeAll(targets); // Убираем из амнистии
        TheGameOfLifeMod.BANNED_RESET_FILTERS.addAll(targets);      // Добавляем в бан
        TheGameOfLifeMod.currentRuleVersion++;                      // Щелчок!
        System.out.println("!!! БАН ФИЛЬТРОВ (ОБНУЛЕНИЕ): Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }

    public static void unbanResetFiltersAndSnap(Set<StateFilter> targets) {
        TheGameOfLifeMod.BANNED_RESET_FILTERS.removeAll(targets);   // Убираем из бана
        TheGameOfLifeMod.UNBANNED_RESET_FILTERS.addAll(targets);    // Отправляем в буфер возврата
        TheGameOfLifeMod.currentRuleVersion++;                      // Щелчок!
        System.out.println("!!! РАЗБАН ФИЛЬТРОВ (ОБНУЛЕНИЕ): Эпоха " + TheGameOfLifeMod.currentRuleVersion);
    }


    public static void processChunkRulesAsync(ServerLevel level, LevelChunk chunk) {
        // 1. Предохранители: проверяем, есть ли вообще работа для Радара по 6 спискам
        boolean nothingToBan = TheGameOfLifeMod.BANNED_BLOCKS.isEmpty() &&
                TheGameOfLifeMod.BANNED_BREAK_FILTERS.isEmpty() &&
                TheGameOfLifeMod.BANNED_RESET_FILTERS.isEmpty();

        boolean nothingToUnban = TheGameOfLifeMod.UNBANNED_BLOCKS.isEmpty() &&
                TheGameOfLifeMod.UNBANNED_BREAK_FILTERS.isEmpty() &&
                TheGameOfLifeMod.UNBANNED_RESET_FILTERS.isEmpty();

        if (nothingToBan && nothingToUnban) return;

        BlockState air = Blocks.AIR.defaultBlockState();
        long chunkPosLong = chunk.getPos().toLong();

        CompletableFuture.supplyAsync(() -> {
            try {
                ChunkUpdateData data = new ChunkUpdateData(chunk);
                BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

                // ==========================================
                // ФАЗА 1: ВОССТАНОВЛЕНИЕ ИЗ АРХИВА (UNBAN)
                // ==========================================
                ConcurrentHashMap<Long, BlockState> chunkMemory = TheGameOfLifeMod.CHUNK_MEMORY.get(chunkPosLong);

                if (chunkMemory != null && !chunkMemory.isEmpty() && !nothingToUnban) {
                    for (var entry : chunkMemory.entrySet()) {
                        long blockPosLong = entry.getKey();
                        BlockState savedState = entry.getValue();

                        // Проверяем по трём спискам амнистии с использованием StateFilter
                        boolean isUnbanned = TheGameOfLifeMod.UNBANNED_BLOCKS.contains(savedState.getBlock()) ||
                                StateFilter.check(TheGameOfLifeMod.UNBANNED_BREAK_FILTERS, savedState) ||
                                StateFilter.check(TheGameOfLifeMod.UNBANNED_RESET_FILTERS, savedState);

                        if (isUnbanned) {
                            mPos.set(blockPosLong);
                            BlockState currentState = chunk.getBlockState(mPos);

                            // Разрешаем вернуть блок, если на его месте пустота, жидкость,
                            // ИЛИ если это тот же самый блок (например, сухая ступенька снова станет мокрой)
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

                // ==========================================
                // ФАЗА 2: УДАЛЕНИЕ / ОБНУЛЕНИЕ (BAN)
                // ==========================================
                if (!nothingToBan) {
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        LevelChunkSection section = sections[i];
                        if (section == null || section.hasOnlyAir()) continue;

                        // ОПТИМИЗАЦИЯ ПАЛИТРЫ: Мгновенная проверка куба 16x16x16 через фильтры
                        boolean hasBannedTargets = section.getStates().maybeHas(state ->
                                TheGameOfLifeMod.BANNED_BLOCKS.contains(state.getBlock()) ||
                                        StateFilter.check(TheGameOfLifeMod.BANNED_BREAK_FILTERS, state) ||
                                        StateFilter.check(TheGameOfLifeMod.BANNED_RESET_FILTERS, state)
                        );

                        if (!hasBannedTargets) continue; // Пропускаем секции, где нет наших целей

                        // Универсальная математика высоты, чтобы избежать ошибок с bottomBlockY()
                        int startY = -64 + (i * 16);
                        int startX = chunk.getPos().getMinBlockX();
                        int startZ = chunk.getPos().getMinBlockZ();

                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                for (int y = 0; y < 16; y++) {
                                    int realY = startY + y;
                                    mPos.set(startX + x, realY, startZ + z);

                                    BlockState currentState = chunk.getBlockState(mPos);

                                    // РАЗВИЛКА ЛОГИКИ: Воздух или Дефолт?
                                    if (TheGameOfLifeMod.BANNED_BLOCKS.contains(currentState.getBlock()) ||
                                            StateFilter.check(TheGameOfLifeMod.BANNED_BREAK_FILTERS, currentState)) {

                                        // Уничтожаем (в Воздух)
                                        data.blocksToModify.put(mPos.asLong(), air);

                                    } else if (WorldHacker.StateFilter.check(TheGameOfLifeMod.BANNED_RESET_FILTERS, currentState)) {
                                        BlockState resetState = currentState;

                                        for (WorldHacker.StateFilter f : TheGameOfLifeMod.BANNED_RESET_FILTERS) {
                                            if (f.matches(currentState)) {
                                                // Вызываем вспомогательный метод для безопасной типизации
                                                resetState = applyReset(resetState, f.property());
                                            }
                                        }
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
            if (data == null) return;

            try {
                BlockPos.MutableBlockPos syncPos = new BlockPos.MutableBlockPos();
                ConcurrentHashMap<Long, BlockState> chunkMemory =
                        TheGameOfLifeMod.CHUNK_MEMORY.computeIfAbsent(chunkPosLong, k -> new ConcurrentHashMap<>());

                // 1. ПРИМЕНЯЕМ ВОССТАНОВЛЕНИЕ
                for (var entry : data.blocksToRestore.long2ObjectEntrySet()) {
                    long posLong = entry.getLongKey();
                    chunk.setBlockState(syncPos.set(posLong), entry.getValue());
                    chunkMemory.remove(posLong); // Удаляем из архива после успешного возвращения в мир
                }

                // 2. ПРИМЕНЯЕМ УДАЛЕНИЕ / ОБНУЛЕНИЕ
                for (var entry : data.blocksToModify.long2ObjectEntrySet()) {
                    long posLong = entry.getLongKey();
                    BlockState newState = entry.getValue();
                    syncPos.set(posLong);

                    // Сохраняем оригинал в Архив ПЕРЕД изменением
                    chunkMemory.putIfAbsent(posLong, chunk.getBlockState(syncPos));

                    // Ставим новое состояние (Воздух или Дефолт)
                    chunk.setBlockState(syncPos, newState);
                }

                chunk.markUnsaved();
                broadcastUpdate(level, chunk);
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.add(data);

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

    // Вспомогательный метод для обхода ограничений дженериков Java
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

        // НОВОЕ: Что на что меняем в Фазе 2 (Удаление/Обнуление)
        public final Long2ObjectOpenHashMap<BlockState> blocksToModify = new Long2ObjectOpenHashMap<>();

        // Что возвращаем из небытия в Фазе 1 (Разбан)
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
            // ... (оставляем старый код updateLight без изменений) ...
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
