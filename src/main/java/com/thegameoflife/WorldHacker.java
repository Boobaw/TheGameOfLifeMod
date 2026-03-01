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

    // Переменная на случай если человек захочет инвертировать воздух
    private static boolean IS_AIR_INVERTED = false;
    private static boolean IS_AIR_ONCE_TOGGLED = false;

    // УМНАЯ ОЧЕРЕДЬ: защищает от дублирования задач для одного чанка
    private static final ConcurrentHashMap<Long, Integer> PENDING_TASKS = new ConcurrentHashMap<>();

    public static void tickBlockRadar(MinecraftServer server) {
        final int targetVersion = TheGameOfLifeMod.currentRuleVersion;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = (ServerLevel) player.level();
            ChunkPos pPos = player.chunkPosition();
            int viewDist = server.getPlayerList().getViewDistance();

            for (int x = -viewDist; x <= viewDist; x++) {
                for (int z = -viewDist; z <= viewDist; z++) {
                    long posLong = ChunkPos.asLong(pPos.x + x, pPos.z + z);

                    // 1. Фильтр: пропускаем, если чанк уже обновлен ИЛИ прямо сейчас обрабатывается
                    if (TheGameOfLifeMod.CHUNK_VERSIONS.getOrDefault(posLong, 0) >= targetVersion ||
                            PENDING_TASKS.getOrDefault(posLong, 0) >= targetVersion) {
                        continue;
                    }

                    // 2. Берем чанк без пролагов генерации
                    LevelChunk chunk = level.getChunkSource().getChunkNow(pPos.x + x, pPos.z + z);

                    if (chunk != null) {
                        PENDING_TASKS.put(posLong, targetVersion);

                        // ==========================================
                        // ХАРДКОРНАЯ АМНЕЗИЯ (Убиваем Scheduled Ticks глобально для чанка)
                        // ==========================================
                        net.minecraft.world.level.levelgen.structure.BoundingBox chunkBox =
                                new net.minecraft.world.level.levelgen.structure.BoundingBox(
                                        chunk.getPos().getMinBlockX(), -64, chunk.getPos().getMinBlockZ(),
                                        chunk.getPos().getMaxBlockX(), 320, chunk.getPos().getMaxBlockZ()
                                );

                        level.getFluidTicks().clearArea(chunkBox);
                        level.getBlockTicks().clearArea(chunkBox);
                        // ==========================================

                        processChunkRulesAsync(level, chunk, targetVersion);
                    }
                }
            }
        }
    }

    // =========================================
    // УМНЫЙ ПЕРЕКЛЮЧАТЕЛЬ И СКАНЕР (TOGGLE)
    // =========================================
    // =========================================
    // УНИВЕРСАЛЬНЫЙ ПЕРЕКЛЮЧАТЕЛЬ (Блоки + Фильтры)
    // =========================================
    // =========================================
// УНИВЕРСАЛЬНЫЙ ПЕРЕКЛЮЧАТЕЛЬ (Блоки + Фильтры)
// =========================================
    public static void toggle(MinecraftServer server, Set<Block> targetBlocks, Set<StateFilter> targetFilters, boolean isReset) {
        boolean rulesChanged = false;

        // ==========================================
        // 0. ОБРАБОТКА ДВОЙНОГО ЗАПРОСА (Мгновенная инверсия)
        // ==========================================
        if (targetBlocks.contains(Blocks.AIR) && targetBlocks.contains(Blocks.BARRIER)) {
            // Жестко меняем их статусы в списках (Качели)
            if (TheGameOfLifeMod.BANNED_BLOCKS.contains(Blocks.AIR)) {
                TheGameOfLifeMod.BANNED_BLOCKS.remove(Blocks.AIR);
                TheGameOfLifeMod.BANNED_BLOCKS.add(Blocks.BARRIER);
            } else {
                TheGameOfLifeMod.BANNED_BLOCKS.remove(Blocks.BARRIER);
                TheGameOfLifeMod.BANNED_BLOCKS.add(Blocks.AIR);
            }

            rulesChanged = true;
            System.out.println("[WorldHacker] Глобальная инверсия Воздух <-> Барьер выполнена!");

            // Убираем их из сета, чтобы сканер не пытался их искать
            targetBlocks.remove(Blocks.AIR);
            targetBlocks.remove(Blocks.BARRIER);

            // Если больше целей нет - запускаем перерисовку чанков и выходим
            if (targetBlocks.isEmpty() && targetFilters.isEmpty()) {
                IS_AIR_INVERTED = TheGameOfLifeMod.BANNED_BLOCKS.contains(Blocks.AIR);
                IS_AIR_ONCE_TOGGLED = true;
                TheGameOfLifeMod.currentRuleVersion++; // ИСПРАВЛЕНА ОШИБКА 2: Теперь чанки обновятся!
                return;
            }
        }

        // Ссылки на нужные списки в зависимости от режима
        Set<StateFilter> bannedFiltersSet = isReset ? TheGameOfLifeMod.BANNED_RESET_FILTERS : TheGameOfLifeMod.BANNED_BREAK_FILTERS;
        Set<StateFilter> unbannedFiltersSet = isReset ? TheGameOfLifeMod.UNBANNED_RESET_FILTERS : TheGameOfLifeMod.UNBANNED_BREAK_FILTERS;

        // --- 1. СОРТИРОВКА БЛОКОВ ---
        Set<Block> toBanBlocks = new java.util.HashSet<>();
        Set<Block> toUnbanBlocks = new java.util.HashSet<>();
        Set<Block> toScanBlocks = new java.util.HashSet<>();

        if (targetBlocks != null) {
            for (Block block : targetBlocks) {
                if (TheGameOfLifeMod.BANNED_BLOCKS.contains(block)) toUnbanBlocks.add(block);
                else if (TheGameOfLifeMod.UNBANNED_BLOCKS.contains(block)) toBanBlocks.add(block);
                else toScanBlocks.add(block);
            }
        }

        // --- 2. СОРТИРОВКА ФИЛЬТРОВ ---
        Set<StateFilter> toBanFilters = new java.util.HashSet<>();
        Set<StateFilter> toUnbanFilters = new java.util.HashSet<>();
        Set<StateFilter> toScanFilters = new java.util.HashSet<>();

        if (targetFilters != null) {
            for (StateFilter filter : targetFilters) {
                if (bannedFiltersSet.contains(filter)) toUnbanFilters.add(filter);
                else if (unbannedFiltersSet.contains(filter)) toBanFilters.add(filter);
                else toScanFilters.add(filter);
            }
        }

        // --- 3. ОБРАБАТЫВАЕМ ИЗВЕСТНЫЕ ---
        if (!toUnbanBlocks.isEmpty()) { TheGameOfLifeMod.BANNED_BLOCKS.removeAll(toUnbanBlocks); TheGameOfLifeMod.UNBANNED_BLOCKS.addAll(toUnbanBlocks); rulesChanged = true; }
        if (!toBanBlocks.isEmpty()) { TheGameOfLifeMod.UNBANNED_BLOCKS.removeAll(toBanBlocks); TheGameOfLifeMod.BANNED_BLOCKS.addAll(toBanBlocks); rulesChanged = true; }
        if (!toUnbanFilters.isEmpty()) { bannedFiltersSet.removeAll(toUnbanFilters); unbannedFiltersSet.addAll(toUnbanFilters); rulesChanged = true; }
        if (!toBanFilters.isEmpty()) { unbannedFiltersSet.removeAll(toBanFilters); bannedFiltersSet.addAll(toBanFilters); rulesChanged = true; }

        // --- 4. ЕДИНЫЙ ГЛОБАЛЬНЫЙ СКАНЕР ---
        if (!toScanBlocks.isEmpty() || !toScanFilters.isEmpty()) {
            ScanResult result = scanServer(server, toScanBlocks, toScanFilters);

            if (!result.foundBlocks().isEmpty()) {
                TheGameOfLifeMod.BANNED_BLOCKS.addAll(result.foundBlocks());
                rulesChanged = true;
            }

            // Обрабатываем качели Воздух-Барьер, если их нашел сканер
            if (result.foundBlocks().contains(Blocks.AIR)) {
                TheGameOfLifeMod.BANNED_BLOCKS.remove(Blocks.BARRIER);
                TheGameOfLifeMod.UNBANNED_BLOCKS.add(Blocks.BARRIER);
            }
            else if (result.foundBlocks().contains(Blocks.BARRIER)) {
                TheGameOfLifeMod.BANNED_BLOCKS.remove(Blocks.AIR);
                TheGameOfLifeMod.UNBANNED_BLOCKS.add(Blocks.AIR);
            }

            Set<Block> notFoundBlocks = new java.util.HashSet<>(toScanBlocks);
            notFoundBlocks.removeAll(result.foundBlocks());
            if (!notFoundBlocks.isEmpty()) {
                TheGameOfLifeMod.UNBANNED_BLOCKS.addAll(notFoundBlocks);
                rulesChanged = true;
                for (Block block : notFoundBlocks) {
                    System.out.println("Игроку выдан блок: " + block.getName().getString());
                }
            }

            if (!result.foundFilters().isEmpty()) {
                bannedFiltersSet.addAll(result.foundFilters());
                rulesChanged = true;
            }
            Set<StateFilter> notFoundFilters = new java.util.HashSet<>(toScanFilters);
            notFoundFilters.removeAll(result.foundFilters());
            if (!notFoundFilters.isEmpty()) {
                unbannedFiltersSet.addAll(notFoundFilters);
                rulesChanged = true;
            }
        }

        // ==========================================
        // 5. ФИНАЛЬНЫЙ ЩЕЛЧОК И СИНХРОНИЗАЦИЯ
        // ==========================================
        // ИСПРАВЛЕНА ОШИБКА 1: Железобетонная привязка переменной к реальности
        IS_AIR_INVERTED = TheGameOfLifeMod.BANNED_BLOCKS.contains(Blocks.AIR);

        if (IS_AIR_INVERTED || TheGameOfLifeMod.BANNED_BLOCKS.contains(Blocks.BARRIER)) {
            IS_AIR_ONCE_TOGGLED = true;
        }

        if (rulesChanged) TheGameOfLifeMod.currentRuleVersion++;
    }

    // =========================================
    // СУПЕР-БЫСТРЫЙ ГЛОБАЛЬНЫЙ СКАНЕР ПАЛИТР
    // =========================================
    // =========================================
    // ЕДИНЫЙ СУПЕР-СКАНЕР (Ищет и блоки, и фильтры за один проход)
    // =========================================
    private record ScanResult(Set<Block> foundBlocks, Set<StateFilter> foundFilters) {}

    private static ScanResult scanServer(MinecraftServer server, Set<Block> targetBlocks, Set<StateFilter> targetFilters) {
        // Проверка на наличие исключений в списке
        // Сюда не должно попадать одновременно и Air и Barrier
        boolean hasTargetAir = false;
        boolean hasTargetBarrier = false;
        if ((targetBlocks.contains(Blocks.AIR))){
            hasTargetAir = true;
        }
        if ((targetBlocks.contains(Blocks.BARRIER))){
            hasTargetBarrier = true;
        }
        Set<Block> foundBlocks = new java.util.HashSet<>();
        Set<StateFilter> foundFilters = new java.util.HashSet<>();

        Set<Block> remainingBlocks = new java.util.HashSet<>(targetBlocks);
        Set<StateFilter> remainingFilters = new java.util.HashSet<>(targetFilters);

        int viewDist = server.getPlayerList().getViewDistance();

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerLevel level = p.level();
            ChunkPos center = p.chunkPosition();

            for (int x = -viewDist; x <= viewDist; x++) {
                for (int z = -viewDist; z <= viewDist; z++) {

                    // Ранний выход: если мы уже нашли ВСЁ, что искали — прерываем глобальный скан!
                    if (remainingBlocks.isEmpty() && remainingFilters.isEmpty()) {
                        return new ScanResult(foundBlocks, foundFilters);
                    }

                    LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + x, center.z + z);
                    if (chunk == null) continue;

                    for (LevelChunkSection section : chunk.getSections()) {
                        if (hasOnlyVoid(section)) {
                            if (!hasTargetAir && !hasTargetBarrier) continue;
                            if (hasTargetAir) foundBlocks.add(Blocks.AIR);
                            else foundBlocks.add(Blocks.BARRIER);
                        }

                        // Сканируем палитру на наличие нужных блоков
                        if (!remainingBlocks.isEmpty()) {
                            remainingBlocks.removeIf(block -> {
                                boolean isPresent = section.getStates().maybeHas(state -> state.is(block));
                                if (isPresent) foundBlocks.add(block);
                                return isPresent;
                            });
                        }

                        // Сканируем ЭТУ ЖЕ палитру на наличие нужных свойств (WATERLOGGED и т.д.)
                        if (!remainingFilters.isEmpty()) {
                            remainingFilters.removeIf(filter -> {
                                boolean isPresent = section.getStates().maybeHas(state -> filter.matches(state));
                                if (isPresent) foundFilters.add(filter);
                                return isPresent;
                            });
                        }

                        if (remainingBlocks.isEmpty() && remainingFilters.isEmpty()) {
                            return new ScanResult(foundBlocks, foundFilters);
                        }
                    }
                }
            }
        }
        return new ScanResult(foundBlocks, foundFilters);
    }

    private static boolean hasOnlyVoid(LevelChunkSection section) {
        if (section.hasOnlyAir()) {
            return true;
        }
        if (section.getStates().maybeHas(state -> state.is(Blocks.BARRIER))) {
            return true;
        }
        return false;
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

        CompletableFuture.supplyAsync(() -> {
            try {
                if (TheGameOfLifeMod.currentRuleVersion != taskVersion) return null;

                ChunkUpdateData data = new ChunkUpdateData(chunk);
                BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
                BlockState replacementState = Blocks.AIR.defaultBlockState();
                BlockState wrongPlaceholder = Blocks.BARRIER.defaultBlockState();
                if (IS_AIR_INVERTED) {
                    replacementState = Blocks.BARRIER.defaultBlockState();
                    wrongPlaceholder = Blocks.AIR.defaultBlockState();
                }

                final int startX = chunk.getPos().getMinBlockX();
                final int startZ = chunk.getPos().getMinBlockZ();

                // ФАЗА 1: ВОССТАНОВЛЕНИЕ (UNBAN) И СИНХРОНИЗАЦИЯ ПЛЕЙСХОЛДЕРОВ
                ConcurrentHashMap<Long, BlockState> chunkMemory = TheGameOfLifeMod.CHUNK_MEMORY.get(chunkPosLong);

                // ЗАПУСКАЕМ ПРОВЕРКУ ПАМЯТИ, ЕСЛИ:
                // 1. Есть что разбанивать (!nothingToUnban)
                // 2. ИЛИ хоть раз за игру менялся режим Воздух/Барьер (IS_AIR_ONCE_TOGGLED)
                boolean needsMemoryCheck = !nothingToUnban || IS_AIR_ONCE_TOGGLED;

                if (chunkMemory != null && !chunkMemory.isEmpty() && needsMemoryCheck) {
                    for (var entry : chunkMemory.entrySet()) {
                        long blockPosLong = entry.getKey();
                        BlockState savedState = entry.getValue();

                        boolean isUnbanned = TheGameOfLifeMod.UNBANNED_BLOCKS.contains(savedState.getBlock()) ||
                                StateFilter.check(TheGameOfLifeMod.UNBANNED_BREAK_FILTERS, savedState) ||
                                StateFilter.check(TheGameOfLifeMod.UNBANNED_RESET_FILTERS, savedState);

                        BlockState currentState = chunk.getBlockState(mPos.set(blockPosLong));

                        if (isUnbanned) {
                            // Классический разбан
                            if (currentState.isAir() || currentState.is(Blocks.BARRIER) || currentState.is(Blocks.WATER) || currentState.is(Blocks.LAVA) || currentState.is(savedState.getBlock())) {
                                data.blocksToRestore.put(blockPosLong, savedState);
                                data.recordHeight(mPos.getX() - startX, mPos.getY(), mPos.getZ() - startZ);
                            }
                        }
                        else if (IS_AIR_ONCE_TOGGLED) {
                            // СИНХРОНИЗАЦИЯ: Блок всё еще в бане, но вдруг у него устаревший плейсхолдер?
                            // Если вместо барьера стоит воздух (или наоборот) — перекрашиваем!
                            if (currentState.is(wrongPlaceholder.getBlock())) {
                                data.blocksToModify.put(blockPosLong, replacementState);
                                data.recordHeight(mPos.getX() - startX, mPos.getY(), mPos.getZ() - startZ);
                            }
                        }
                    }
                }

                // ФАЗА 2: УДАЛЕНИЕ / ОБНУЛЕНИЕ (BAN)
                if (!nothingToBan) {
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        if (TheGameOfLifeMod.currentRuleVersion != taskVersion) return null;

                        LevelChunkSection section = sections[i];
                        if (section == null) continue;
                        if (!IS_AIR_ONCE_TOGGLED) {
                            if (hasOnlyVoid(section)) continue;
                        }

                        boolean hasTargets = section.getStates().maybeHas(state -> {
                            return TheGameOfLifeMod.BANNED_BLOCKS.contains(state.getBlock()) ||
                                    StateFilter.check(TheGameOfLifeMod.BANNED_BREAK_FILTERS, state) ||
                                    StateFilter.check(TheGameOfLifeMod.BANNED_RESET_FILTERS, state);
                        });

                        if (!hasTargets) continue;

                        final int startY = -64 + (i << 4);

                        for (int y = 0; y < 16; y++) {
                            int realY = startY + y;
                            for (int z = 0; z < 16; z++) {
                                int realZ = startZ + z;
                                for (int x = 0; x < 16; x++) {
                                    BlockState currentState = section.getBlockState(x, y, z);

                                    if (TheGameOfLifeMod.BANNED_BLOCKS.contains(currentState.getBlock()) ||
                                            StateFilter.check(TheGameOfLifeMod.BANNED_BREAK_FILTERS, currentState)) {

                                        long p = mPos.set(startX + x, realY, realZ).asLong();
                                        data.blocksToBackup.put(p, currentState);
                                        data.blocksToModify.put(p, replacementState);
                                        data.recordHeight(x, realY, z);

                                    } else if (WorldHacker.StateFilter.check(TheGameOfLifeMod.BANNED_RESET_FILTERS, currentState)) {
                                        BlockState resetState = currentState;
                                        for (WorldHacker.StateFilter f : TheGameOfLifeMod.BANNED_RESET_FILTERS) {
                                            if (f.matches(currentState)) {
                                                resetState = applyReset(resetState, f.property());
                                            }
                                        }
                                        long p = mPos.set(startX + x, realY, realZ).asLong();
                                        data.blocksToBackup.put(p, currentState);
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

        }).thenAcceptAsync(data -> {
            PENDING_TASKS.remove(chunkPosLong);

            if (data == null || TheGameOfLifeMod.currentRuleVersion != taskVersion) return;

            BlockPos.MutableBlockPos syncPos = new BlockPos.MutableBlockPos();
            ConcurrentHashMap<Long, BlockState> chunkMemory =
                    TheGameOfLifeMod.CHUNK_MEMORY.computeIfAbsent(chunkPosLong, k -> new ConcurrentHashMap<>());

            LevelChunkSection[] sections = chunk.getSections();
            int minBuildHeight = -64;

            // =========================================================
            // РЕЖИМ НИНДЗЯ: Прямая запись в секции (Без обновления соседей)
            // =========================================================
            data.blocksToRestore.forEach((pos, state) -> {
                syncPos.set(pos);
                int y = syncPos.getY();
                int secIdx = (y - minBuildHeight) >> 4;

                if (secIdx >= 0 && secIdx < sections.length) {
                    LevelChunkSection section = sections[secIdx];
                    if (section != null) {
                        section.setBlockState(syncPos.getX() & 15, y & 15, syncPos.getZ() & 15, state);
                    }
                }
                chunkMemory.remove(pos);
            });

            data.blocksToModify.forEach((pos, newState) -> {
                syncPos.set(pos);
                int y = syncPos.getY();
                int secIdx = (y - minBuildHeight) >> 4;
                BlockState originalState = data.blocksToBackup.get(pos);

                if (secIdx >= 0 && secIdx < sections.length) {
                    LevelChunkSection section = sections[secIdx];
                    if (section != null) {
                        section.setBlockState(syncPos.getX() & 15, y & 15, syncPos.getZ() & 15, newState);
                    }
                }

                // Убиваем тайлы (сундуки, спавнеры), чтобы не висели в памяти
                if (originalState != null) {
                    if (originalState.hasBlockEntity()) {
                        chunk.removeBlockEntity(syncPos);
                    }
                    chunkMemory.putIfAbsent(pos, originalState);
                }
            });

            chunk.markUnsaved();

            // Если игрок ушел — не считаем свет
            if (hasPlayersWatching(level, chunk.getPos())) {
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.add(data);
            }
            broadcastUpdate(level, chunk);

            TheGameOfLifeMod.CHUNK_VERSIONS.put(chunkPosLong, taskVersion);

        }, level.getServer());
    }

    // =========================================
    // СВЕТ И СЕТЬ
    // =========================================
    private static void broadcastUpdate(ServerLevel level, LevelChunk chunk) {
        if (!hasPlayersWatching(level, chunk.getPos())) return; // Экономим на сборке пакета

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
        int sent = 0;
        while (!TheGameOfLifeMod.LIGHT_PACKET_QUEUE.isEmpty() && sent < 10) {
            ChunkUpdateData d = TheGameOfLifeMod.LIGHT_PACKET_QUEUE.poll();
            if (d != null) broadcastUpdate((ServerLevel) d.chunk.getLevel(), d.chunk);
            sent++;
        }

        int cols = 0;
        while (!TheGameOfLifeMod.LIGHT_CALC_QUEUE.isEmpty() && cols < 15000) {
            ChunkUpdateData d = TheGameOfLifeMod.LIGHT_CALC_QUEUE.peek();
            if (d == null) {
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.poll();
                continue;
            }

            // Динамически скипаем расчет, если игроки покинули зону во время ожидания в очереди
            if (!hasPlayersWatching((ServerLevel) d.chunk.getLevel(), d.chunk.getPos())) {
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.poll();
                continue;
            }

            if (d.updateLight(15000 - cols)) {
                TheGameOfLifeMod.LIGHT_CALC_QUEUE.poll();
                TheGameOfLifeMod.LIGHT_PACKET_QUEUE.add(d);
            }
            cols += 5000;
        }
    }

    // Быстрая проверка: смотрит ли хоть один игрок на этот чанк
    private static boolean hasPlayersWatching(ServerLevel level, ChunkPos pos) {
        int dist = level.getServer().getPlayerList().getViewDistance();
        for (ServerPlayer p : level.players()) {
            int dx = p.chunkPosition().x - pos.x;
            int dz = p.chunkPosition().z - pos.z;
            if (dx >= -dist && dx <= dist && dz >= -dist && dz <= dist) {
                return true;
            }
        }
        return false;
    }

    public static boolean isChunkFrozen(long chunkPosLong) {
        return PENDING_TASKS.containsKey(chunkPosLong);
    }

    // =========================================
    // ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ
    // =========================================
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
}