package com.cyberspace.utils;

import com.cyberspace.hacker.*;
import java.util.HashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;


import java.util.*;

import static com.cyberspace.hacker.DataHacker.toggleRule;

public class CommandRouter {

    public static void processChunk(String chunk, ServerPlayer player, MinecraftServer server) {
        System.out.println("[Router] Анализ команды: " + chunk);

        // ==========================================
        // ШАГ 1: ЧТЕНИЕ ГЛОБАЛЬНЫХ ФЛАГОВ
        // ==========================================
        boolean isReset = CyberSpaceParser.hasSystemTrigger(chunk, "mode_reset");
        boolean reqDefault = CyberSpaceParser.hasSystemTrigger(chunk, "require_default");
        boolean isCompMod = CyberSpaceParser.hasSystemTrigger(chunk, "modifier_component");
        boolean isItemMod = CyberSpaceParser.hasSystemTrigger(chunk, "modifier_item");
        boolean isBlockMod = CyberSpaceParser.hasSystemTrigger(chunk, "modifier_block");

        Set<Block> blocksToToggle = new HashSet<>();
        Set<WorldHacker.StateFilter> filtersToToggle = new HashSet<>();
        Set<Item> itemsToToggle = new HashSet<>();
        Set<EntityType<?>> targetEntities = new HashSet<>();

        // ==========================================
        // ШАГ 2: МАКРОСЫ
        // ==========================================
        for (Map.Entry<String, List<List<String>>> action : CyberSpaceParser.ACTIONS.entrySet()) {
            if (CyberSpaceParser.checkMatch(chunk, action.getValue())) {
                System.out.println("[Router] Запуск макроса: " + action.getKey());
            }
        }

        // ==========================================
        // ШАГ 3: ПРЕДМЕТЫ И КВАНТОВЫЕ СЛЕПКИ
        // ==========================================
        for (CyberSpaceParser.ItemContext itemCtx : CyberSpaceParser.ITEM_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, itemCtx.triggers())) {
                Item targetItem = itemCtx.target();

                if (isCompMod) {
                    System.out.println("[Router] Запуск Квантового Слепка для: " + BuiltInRegistries.ITEM.getKey(targetItem));

                    // Создаем ОДНО правило, которое хранит в себе донора целиком.
                    // DataHacker.applyRule сам разберет его на компоненты и решит, кого банить!
                    String ruleId = "snapshot_" + BuiltInRegistries.ITEM.getKey(targetItem).getPath();

                    DataHacker.RuleData<?> snapshotRule = new DataHacker.RuleData<>(
                            null,          // type: null (applyRule сам переберет все компоненты донора)
                            null,          // subcomponents: null
                            targetItem,    // targetItem: наш донор (Алмазный меч)
                            reqDefault    // reqDefault: решает, будет ли это только Вирус или Вирус + Кувалда
                    );

                    toggleRule(server, ruleId, snapshotRule);

                } else if (!isBlockMod) {
                    // Обычный бан предмета (если не сказано "блок")
                    itemsToToggle.add(targetItem);
                }
            }
        }

        // ==========================================
        // ШАГ 4: ТОЧЕЧНЫЕ КОМПОНЕНТЫ
        // ==========================================
        for (CyberSpaceParser.ComponentContext compCtx : CyberSpaceParser.COMPONENT_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, compCtx.triggers())) {
                System.out.println("[Router] Точечный компонент: " + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(compCtx.type()));

                // Строгие 6 параметров для точечного глобального правила
                DataHacker.RuleData<?> rule = new DataHacker.RuleData<>(
                        compCtx.type(),
                        compCtx.subcomponents(),
                        null,       // targetItem: нет донора, правило глобальное
                        reqDefault
                );

                toggleRule(server, compCtx.ruleName(), rule);
            }
        }

        // =========================================
        // 1. Атрибуты
        // =========================================
        for (com.cyberspace.utils.CyberSpaceParser.AttributeContext attrCtx : com.cyberspace.utils.CyberSpaceParser.ATTRIBUTE_RULES) {
            if (com.cyberspace.utils.CyberSpaceParser.checkMatch(chunk, attrCtx.triggers())) {
                EntityStateHacker.toggleAttribute(server, attrCtx);
                System.out.println("[Router] Атрибут: " + attrCtx.ruleName());
            }
        }

        // =========================================
        // 2. Флаги сущностей (entity, living, player, network)
        // Один флаг может встречаться в нескольких секциях конфига под одним именем.
        // Дедупликация по имени флага гарантирует ровно один toggleFlag на имя.
        // =========================================
        Set<String> toggledFlags = new HashSet<>();

        for (com.cyberspace.utils.CyberSpaceParser.EntityFlagContext ctx : com.cyberspace.utils.CyberSpaceParser.ENTITY_FLAG_RULES) {
            if (com.cyberspace.utils.CyberSpaceParser.checkMatch(chunk, ctx.triggers()) && toggledFlags.add(ctx.flag())) {
                EntityStateHacker.toggleFlag(ctx);
                System.out.println("[Router] Entity-флаг: " + ctx.ruleName());
            }
        }
        for (com.cyberspace.utils.CyberSpaceParser.EntityFlagContext ctx : com.cyberspace.utils.CyberSpaceParser.LIVING_FLAG_RULES) {
            if (com.cyberspace.utils.CyberSpaceParser.checkMatch(chunk, ctx.triggers()) && toggledFlags.add(ctx.flag())) {
                EntityStateHacker.toggleFlag(ctx);
                System.out.println("[Router] Living-флаг: " + ctx.ruleName());
            }
        }
        for (com.cyberspace.utils.CyberSpaceParser.EntityFlagContext ctx : com.cyberspace.utils.CyberSpaceParser.PLAYER_FLAG_RULES) {
            if (com.cyberspace.utils.CyberSpaceParser.checkMatch(chunk, ctx.triggers()) && toggledFlags.add(ctx.flag())) {
                EntityStateHacker.toggleFlag(ctx);
                System.out.println("[Router] Player-флаг: " + ctx.ruleName());
            }
        }
        for (com.cyberspace.utils.CyberSpaceParser.EntityFlagContext ctx : com.cyberspace.utils.CyberSpaceParser.NETWORK_FLAG_RULES) {
            if (com.cyberspace.utils.CyberSpaceParser.checkMatch(chunk, ctx.triggers()) && toggledFlags.add(ctx.flag())) {
                EntityStateHacker.toggleFlag(ctx);
                System.out.println("[Router] Network-флаг: " + ctx.ruleName());
            }
        }

        // ==========================================
        // ШАГ 5: БЛОКИ В МИРЕ
        // ==========================================
        if (!isCompMod && !isItemMod) {
            // ВАЖНО: Если игрок сказал "предмет обсидиана", мы игнорируем блоки
            for (CyberSpaceParser.BlockContext blockCtx : CyberSpaceParser.BLOCK_RULES) {
                if (CyberSpaceParser.checkMatch(chunk, blockCtx.triggers())) {
                    boolean hasActiveStateFilter = false;

                    for (CyberSpaceParser.BlockStateContext stateCtx : CyberSpaceParser.BLOCKSTATE_RULES) {
                        if (CyberSpaceParser.checkMatch(chunk, stateCtx.triggers())) {
                            WorldHacker.StateFilter filter = buildStateFilter(blockCtx.target(), stateCtx.property(), stateCtx.value());
                            if (filter != null) {
                                filtersToToggle.add(filter);
                                hasActiveStateFilter = true;
                            }
                        }
                    }

                    if (!hasActiveStateFilter && !isReset) {
                        blocksToToggle.add(blockCtx.target());
                    }
                }
            }
        }

        // ==========================================
        // ШАГ 6: СУЩНОСТИ
        // ==========================================
        for (CyberSpaceParser.EntityContext entityCtx : CyberSpaceParser.ENTITY_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, entityCtx.triggers())) {
                targetEntities.addAll(entityCtx.targets());
            }
        }

        // ==========================================
        // ФИНАЛЬНЫЙ ЗАЛП: Умная гибридная логика
        // ==========================================
        executeToggleLogic(server, itemsToToggle, blocksToToggle, filtersToToggle, isReset, isItemMod, isBlockMod, isCompMod);

        if (!targetEntities.isEmpty()) {
            EntityHacker.toggleEntity(server, targetEntities);
        }
    }

    // ==========================================
    // ЛОГИКА СИНХРОНИЗАЦИИ И РАССИНХРОНА
    // ==========================================
    private static void executeToggleLogic(MinecraftServer server, Set<Item> itemsToToggle, Set<Block> blocksToToggle, Set<WorldHacker.StateFilter> filtersToToggle, boolean isReset, boolean isItemMod, boolean isBlockMod, boolean isCompMod) {

        // 1. Собираем списки того, что СЕЙЧАС ЛЕГАЛЬНО (unbanned), чтобы отсканировать это
        Set<Item> itemsToScan = new HashSet<>();
        for (Item i : itemsToToggle) if (!ItemHacker.BANNED_ITEMS.contains(i)) itemsToScan.add(i);

        Set<Block> blocksToScan = new HashSet<>();
        for (Block b : blocksToToggle) if (!WorldHacker.BANNED_BLOCKS.contains(b)) blocksToScan.add(b);

        // 2. Выполняем разведку ТОЛЬКО для легальных объектов
        Set<Item> foundItems = new HashSet<>();
        if (!itemsToScan.isEmpty()) {
            System.out.println("[Router] Разведка инвентарей...");
            foundItems = ItemHacker.scanAndCollect(server, itemsToScan);
        }

        WorldHacker.ScanResult scanResult = new WorldHacker.ScanResult(new HashSet<>(), new HashSet<>());
        if (!blocksToScan.isEmpty() || !filtersToToggle.isEmpty()) {
            System.out.println("[Router] Разведка чанков...");
            scanResult = WorldHacker.scanServer(server, blocksToScan, filtersToToggle);
        }

        // 3. Подготовка итоговых списков
        Set<Item> finalItemsToBan = new HashSet<>();
        Set<Item> finalItemsToUnban = new HashSet<>();
        Set<Block> finalBlocksToBan = new HashSet<>();
        Set<Block> finalBlocksToUnban = new HashSet<>();

        // Проверяем условия для СИНХРОНИЗАЦИИ:
        // Нет модификаторов + Запрос содержит и блок и предмет + Оба объекта сейчас полностью легальны
        boolean hasNoModifiers = !isItemMod && !isBlockMod && !isCompMod;
        boolean bothPresent = !itemsToToggle.isEmpty() && !blocksToToggle.isEmpty();
        boolean allAreUnbanned = itemsToScan.size() == itemsToToggle.size() && blocksToScan.size() == blocksToToggle.size();

        if (hasNoModifiers && bothPresent && allAreUnbanned) {
            System.out.println("[Router] Режим: СИНХРОНИЗАЦИЯ (Оба объекта легальны)");
            boolean foundAnywhere = !foundItems.isEmpty() || !scanResult.foundBlocks().isEmpty();

            if (foundAnywhere) {
                System.out.println("[Router] Найдено в мире. БАНИМ И БЛОК И ПРЕДМЕТ.");
                finalItemsToBan.addAll(itemsToToggle);
                finalBlocksToBan.addAll(blocksToToggle);
            } else {
                System.out.println("[Router] Ничего не найдено. РАЗБАНИВАЕМ (Выдаем стак).");
                finalItemsToUnban.addAll(itemsToToggle);
                finalBlocksToUnban.addAll(blocksToToggle);
            }
        } else {
            System.out.println("[Router] Режим: НЕЗАВИСИМЫЙ (Точечный запрос или есть рассинхрон)");

            // Обрабатываем предметы
            for (Item i : itemsToToggle) {
                if (ItemHacker.BANNED_ITEMS.contains(i)) {
                    finalItemsToUnban.add(i); // Был в бане -> разбан
                } else {
                    if (foundItems.contains(i)) finalItemsToBan.add(i); // Легален и найден -> бан
                    else finalItemsToUnban.add(i); // Легален, но не найден -> разбан (выдаст стак)
                }
            }

            // Обрабатываем блоки
            for (Block b : blocksToToggle) {
                if (WorldHacker.BANNED_BLOCKS.contains(b)) {
                    finalBlocksToUnban.add(b); // Был в бане -> разбан
                } else {
                    if (scanResult.foundBlocks().contains(b)) finalBlocksToBan.add(b);
                    else finalBlocksToUnban.add(b);
                }
            }
        }

        // 4. Исполнение приговора
        if (!finalItemsToBan.isEmpty()) {
            ItemHacker.applyState(server, finalItemsToBan, true, false);
        }

        if (!finalItemsToUnban.isEmpty()) {
            // Халява разрешена ТОЛЬКО если мы не разбаниваем блоки параллельно
            boolean allowFallback = finalBlocksToUnban.isEmpty();
            ItemHacker.applyState(server, finalItemsToUnban, false, allowFallback);
        }

        if (!finalBlocksToBan.isEmpty()) WorldHacker.applyState(server, finalBlocksToBan, filtersToToggle, isReset, true);
        if (!finalBlocksToUnban.isEmpty()) WorldHacker.applyState(server, finalBlocksToUnban, new HashSet<>(), isReset, false);
    }

    private static WorldHacker.StateFilter buildStateFilter(Block block, String propName, String propValue) {
        BlockState defaultState = block.defaultBlockState();
        for (Property<?> prop : defaultState.getProperties()) {
            if (prop.getName().equals(propName)) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Optional<? extends Comparable<?>> valueOpt = ((Property) prop).getValue(propValue);
                if (valueOpt.isPresent()) {
                    return new WorldHacker.StateFilter(block, prop, valueOpt.get());
                }
            }
        }
        return null;
    }
}