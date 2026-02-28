package com.thegameoflife;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static com.thegameoflife.DataHacker.toggleRule;
import static com.thegameoflife.EntityHacker.toggleEntity;
import static com.thegameoflife.ItemHacker.toggleItem;

public class CommandRouter {

    public static void processChunk(String chunk, ServerPlayer player, MinecraftServer server) {
        boolean isReset = CyberSpaceParser.hasSystemTrigger(chunk, "mode_reset");
        boolean requireDefault = CyberSpaceParser.hasSystemTrigger(chunk, "require_default");

        // Коллекции для передачи в сканер WorldHacker
        Set<Block> blocksToToggle = new HashSet<>();
        Set<WorldHacker.StateFilter> filtersToToggle = new HashSet<>();

        // --- 1. БЛОКИ И СОСТОЯНИЯ (WorldHacker) ---
        for (CyberSpaceParser.BlockContext blockCtx : CyberSpaceParser.BLOCK_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, blockCtx.triggers())) {

                Set<WorldHacker.StateFilter> tempFilters = new HashSet<>();

                // Ищем состояния
                for (CyberSpaceParser.BlockStateContext stateCtx : CyberSpaceParser.BLOCKSTATE_RULES) {
                    if (CyberSpaceParser.checkMatch(chunk, stateCtx.triggers())) {
                        for (Block targetBlock : blockCtx.targets()) {
                            WorldHacker.StateFilter filter = buildStateFilter(targetBlock, stateCtx.property(), stateCtx.value());
                            if (filter != null) tempFilters.add(filter);
                        }
                    }
                }

                // Распределяем: если есть фильтры - отдаем в фильтры. Иначе - баним блок целиком.
                for (Block targetBlock : blockCtx.targets()) {
                    if (!tempFilters.isEmpty()) {
                        filtersToToggle.addAll(tempFilters);
                    } else if (!isReset) {
                        // Блок целиком баним/тоглим только если это не режим "состояние"
                        blocksToToggle.add(targetBlock);
                    }
                }
            }
        }

        // 🚀 ФИНАЛЬНЫЙ ЗАЛП В WORLDHACKER
        if (!blocksToToggle.isEmpty() || !filtersToToggle.isEmpty()) {
            System.out.println("[Router] Запуск сканера WorldHacker...");
            // Передаем флаг isReset, чтобы сканер понял, в какие списки класть фильтры (Break или Reset)
            WorldHacker.toggle(server, blocksToToggle, filtersToToggle, isReset);
        }

        // --- 2. ПРЕДМЕТЫ (ItemRuleManager) ---
        Set<Item> targetItems = new HashSet<>();
        for (CyberSpaceParser.ItemContext itemsCtx : CyberSpaceParser.ITEM_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, itemsCtx.triggers())) {
                // Достаем целевой предмет из контекста (замени на свой реальный метод, если он отличается)
                targetItems.addAll(itemsCtx.targets());
            }
        }
        if (!targetItems.isEmpty()) {
            System.out.println("[Router] ItemRuleManager.toggle(" + targetItems + ")");
            toggleItem(server, targetItems);
        }

        // --- 3. КОМПОНЕНТЫ (DataHacker) ---
        for (CyberSpaceParser.ComponentContext compCtx : CyberSpaceParser.COMPONENT_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, compCtx.triggers())) {
                // Задаем параметры (в будущем можно вытягивать из chunk или compCtx)
                boolean isBan = true; // TRUE - сжигаем, FALSE - баффаем экипировку
                String ruleId = compCtx.ruleName();

                // Создаем templateRule на основе предоставленного тобой record
                DataHacker.RuleData<?> templateRule = new DataHacker.RuleData<>(
                        compCtx.type(),
                        compCtx.subcomponents(), // список ключей подкомпонентов
                        compCtx.targetItem(), // targetItem (если для компонента предмет не нужен, оставляем null)
                        requireDefault,
                        isBan
                );

                System.out.println("[Router] DataHacker.toggle(" + compCtx.type() + ", default=" + requireDefault + ")");
                toggleRule(server, ruleId, templateRule);
            }
        }

        // --- 4. СУЩНОСТИ (EntityRuleManager) ---
        Set<EntityType<?>> targetEntities = new HashSet<>();
        for (CyberSpaceParser.EntityContext entityCtx : CyberSpaceParser.ENTITY_RULES) {
            if (CyberSpaceParser.checkMatch(chunk, entityCtx.triggers())) {
                // Достаем тип сущности из контекста
                targetEntities.addAll(entityCtx.targets());
            }
        }
        if (!targetEntities.isEmpty()) {
            System.out.println("[Router] ItemRuleManager.toggle(" + targetItems + ")");
            toggleEntity(server, targetEntities);
        }
    }

    private static WorldHacker.StateFilter buildStateFilter(Block block, String propName, String propValue) {
        BlockState defaultState = block.defaultBlockState();
        for (Property<?> prop : defaultState.getProperties()) {
            if (prop.getName().equals(propName)) {
                Optional<? extends Comparable<?>> valueOpt = ((Property) prop).getValue(propValue);
                if (valueOpt.isPresent()) {
                    return new WorldHacker.StateFilter(block, prop, valueOpt.get());
                }
            }
        }
        return null;
    }
}