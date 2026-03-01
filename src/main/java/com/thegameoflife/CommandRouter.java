package com.thegameoflife;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.*;

import static com.thegameoflife.DataHacker.toggleRule;
import static com.thegameoflife.EntityHacker.toggleEntity;
import static com.thegameoflife.ItemHacker.toggleItem;

public class CommandRouter {

    public static void processChunk(String chunk, ServerPlayer player, MinecraftServer server) {
        System.out.println("[Router] Анализ команды: " + chunk);

        // ==========================================
        // ШАГ 1: ЧТЕНИЕ ГЛОБАЛЬНЫХ ФЛАГОВ (Модификаторов)
        // ==========================================
        boolean isReset = CyberSpaceParser.hasSystemTrigger(chunk, "mode_reset");
        boolean reqDefault = CyberSpaceParser.hasSystemTrigger(chunk, "require_default");
        boolean isCompMod = CyberSpaceParser.hasSystemTrigger(chunk, "modifier_component");
        boolean isItemMod = CyberSpaceParser.hasSystemTrigger(chunk, "modifier_item");

        // Коллекции для пакетной отправки в Хакеры
        Set<Block> blocksToToggle = new HashSet<>();
        Set<WorldHacker.StateFilter> filtersToToggle = new HashSet<>();
        Set<Item> itemsToToggle = new HashSet<>();
        Set<EntityType<?>> targetEntities = new HashSet<>();

        // ==========================================
        // ШАГ 2: ПРОВЕРКА МАКРОСОВ (Глобальные действия)
        // ==========================================
        for (Map.Entry<String, List<List<String>>> action : CyberSpaceParser.ACTIONS.entrySet()) {
            if (CyberSpaceParser.checkMatch(chunk, action.getValue())) {
                String macroId = action.getKey();
                System.out.println("[Router] Запуск макроса: " + macroId);
                // Здесь ты можешь вызывать свои хардкод-методы. Например:
                // if (macroId.equals("clear_inventories")) PlayerHacker.clearAll(server);
                // if (macroId.equals("all_items")) ItemHacker.banAll(server);
            }
        }

        // ==========================================
        // ШАГ 3: УНИВЕРСАЛЬНЫЕ ПРЕДМЕТЫ И "ЯДЕРНЫЙ УДАР"
        // ==========================================
        for (CyberSpaceParser.ItemContext itemCtx : CyberSpaceParser.ITEM_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, itemCtx.triggers())) {
                Item targetItem = itemCtx.target();

                if (isCompMod) {
                    // ПРОТОКОЛ "ЯДЕРНЫЙ УДАР" (Снос всех компонентов предмета)
                    System.out.println("[Router] Ядерный ALL активирован для: " + BuiltInRegistries.ITEM.getKey(targetItem));

                    Map<String, Object> nukeMap = new HashMap<>();
                    nukeMap.put("NUKE_COMPONENT", true); // Секретный ключ для DataHacker

                    for (DataComponentType<?> compType : targetItem.components().keySet()) {
                        // Создаем уникальный ID правила (напр: diamond_sword_weapon)
                        String ruleId = BuiltInRegistries.ITEM.getKey(targetItem).getPath() + "_" + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(compType).getPath();

                        DataHacker.RuleData<?> rule = new DataHacker.RuleData<>(compType, nukeMap, targetItem, reqDefault, true);
                        toggleRule(server, ruleId, rule);
                    }
                } else {
                    // Обычный бан предмета (если не сказано "компонент")
                    itemsToToggle.add(targetItem);
                }
            }
        }

        // ==========================================
        // ШАГ 4: ТОЧЕЧНЫЕ КОМПОНЕНТЫ (Из блока components)
        // ==========================================
        for (CyberSpaceParser.ComponentContext compCtx : CyberSpaceParser.COMPONENT_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, compCtx.triggers())) {
                // Если компонент точечный (напр. "эпичная редкость"), он применяется глобально (targetItem = null)
                DataHacker.RuleData<?> rule = new DataHacker.RuleData<>(compCtx.type(), compCtx.subcomponents(), null, reqDefault, true);
                System.out.println("[Router] Точечный компонент: " + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(compCtx.type()));
                toggleRule(server, compCtx.ruleName(), rule);
            }
        }

        // ==========================================
        // ШАГ 5: БЛОКИ И ИХ СОСТОЯНИЯ В МИРЕ
        // ==========================================
        // Блоки мы трогаем ТОЛЬКО если игрок не уточнил, что работает с предметом или компонентом
        if (!isCompMod && !isItemMod) {
            for (CyberSpaceParser.BlockContext blockCtx : CyberSpaceParser.BLOCK_RULES) {
                if (CyberSpaceParser.checkMatch(chunk, blockCtx.triggers())) {
                    boolean hasActiveStateFilter = false;

                    // Проверяем, не наложил ли игрок фильтр (например, "горящая")
                    for (CyberSpaceParser.BlockStateContext stateCtx : CyberSpaceParser.BLOCKSTATE_RULES) {
                        if (CyberSpaceParser.checkMatch(chunk, stateCtx.triggers())) {
                            WorldHacker.StateFilter filter = buildStateFilter(blockCtx.target(), stateCtx.property(), stateCtx.value());
                            if (filter != null) {
                                filtersToToggle.add(filter);
                                hasActiveStateFilter = true;
                            }
                        }
                    }

                    // Если фильтров нет и это не команда сброса состояний — баним блок целиком
                    if (!hasActiveStateFilter && !isReset) {
                        blocksToToggle.add(blockCtx.target());
                    }
                }
            }
        }

        // ==========================================
        // ШАГ 6: СУЩНОСТИ (Мобы)
        // ==========================================
        for (CyberSpaceParser.EntityContext entityCtx : CyberSpaceParser.ENTITY_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, entityCtx.triggers())) {
                targetEntities.addAll(entityCtx.targets());
            }
        }

        // ==========================================
        // ФИНАЛЬНЫЙ ЗАЛП: Отправка собранных данных в движки
        // ==========================================
        if (!blocksToToggle.isEmpty() || !filtersToToggle.isEmpty()) {
            System.out.println("[Router] WorldHacker: Блоков=" + blocksToToggle.size() + ", Фильтров=" + filtersToToggle.size());
            WorldHacker.toggle(server, blocksToToggle, filtersToToggle, isReset);
        }

        if (!itemsToToggle.isEmpty()) {
            System.out.println("[Router] ItemHacker: Предметов=" + itemsToToggle.size());
            toggleItem(server, itemsToToggle);
        }

        if (!targetEntities.isEmpty()) {
            System.out.println("[Router] EntityHacker: Сущностей=" + targetEntities.size());
            toggleEntity(server, targetEntities);
        }
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