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
        System.out.println("[Router] Анализ команды (Smart Matcher): " + chunk);

        // Контекст исполнения (наполняется в процессе фильтрации)
        class ExecutionContext {
            boolean isReset = false;
            boolean reqDefault = false;
            boolean isCompMod = false;
            boolean isItemMod = false;
            boolean isBlockMod = false;

            final Set<Block> blocksToToggle = new HashSet<>();
            final Set<WorldHacker.StateFilter> filtersToToggle = new HashSet<>();
            final Set<Item> itemsToToggle = new HashSet<>();
            final Set<EntityType<?>> targetEntities = new HashSet<>();
        }
        ExecutionContext ctx = new ExecutionContext();

        // Умный коллектор с поддержкой приоритетов
        class MatchCollector {
            record PendingAction(CyberSpaceParser.MatchResult res, Runnable action, String debugName) {}
            final List<PendingAction> actions = new ArrayList<>();

            void add(CyberSpaceParser.MatchResult res, Runnable action, String debugName) {
                if (res != null) {
                    System.out.println("[Router] Найден кандидат: " + debugName + " (индексы: " + res.indices() + ")");
                    actions.add(new PendingAction(res, action, debugName));
                }
            }

            void execute() {
                if (actions.isEmpty()) return;

                // Группируем кандидатов с одинаковыми индексами:
                // они "заявляют права" на одну и ту же фразу в строке
                // и должны либо все исполниться, либо все пропуститься вместе.
                Map<BitSet, List<PendingAction>> groups = new LinkedHashMap<>();
                for (PendingAction pending : actions) {
                    groups.computeIfAbsent(pending.res.indices(), k -> new ArrayList<>()).add(pending);
                }

                // Сортируем группы по максимальному весу (убыванием)
                List<Map.Entry<BitSet, List<PendingAction>>> sortedGroups = new ArrayList<>(groups.entrySet());
                sortedGroups.sort((a, b) -> {
                    int wA = a.getValue().stream().mapToInt(p -> p.res.weight()).max().orElse(0);
                    int wB = b.getValue().stream().mapToInt(p -> p.res.weight()).max().orElse(0);
                    return Integer.compare(wB, wA);
                });

                System.out.println("[Router] Всего кандидатов: " + actions.size() + " в " + sortedGroups.size() + " группах. Начинаю фильтрацию...");

                BitSet coveredIndices = new BitSet();
                for (Map.Entry<BitSet, List<PendingAction>> entry : sortedGroups) {
                    BitSet groupIndices = entry.getKey();
                    List<PendingAction> groupActions = entry.getValue();

                    BitSet intersection = (BitSet) groupIndices.clone();
                    intersection.and(coveredIndices);

                    if (intersection.isEmpty()) {
                        // Группа не пересекается с уже покрытым — исполняем всё скопом
                        for (PendingAction pending : groupActions) {
                            System.out.println("[Router] Исполнение: " + pending.debugName + " (вес: " + pending.res.weight() + ")");
                            pending.action.run();
                        }
                        coveredIndices.or(groupIndices);
                    } else {
                        // Группа пересекается с более тяжёлым — пропускаем всё скопом
                        for (PendingAction pending : groupActions) {
                            System.out.println("[Router] Пропуск (ПЕРЕСЕКАЕТСЯ С БОЛЕЕ ТЯЖЁЛЫМ): " + pending.debugName);
                        }
                    }
                }
            }
        }
        MatchCollector collector = new MatchCollector();

        // ==========================================
        // ШАГ 1: СИСТЕМНЫЕ ТРИГГЕРЫ (Модификаторы)
        // ==========================================
        collector.add(CyberSpaceParser.getSystemMatchResult(chunk, "mode_reset"), () -> ctx.isReset = true, "System: Reset");
        collector.add(CyberSpaceParser.getSystemMatchResult(chunk, "require_default"), () -> ctx.reqDefault = true, "System: Default");
        collector.add(CyberSpaceParser.getSystemMatchResult(chunk, "modifier_component"), () -> ctx.isCompMod = true, "System: ComponentMod");
        collector.add(CyberSpaceParser.getSystemMatchResult(chunk, "modifier_item"), () -> ctx.isItemMod = true, "System: ItemMod");
        collector.add(CyberSpaceParser.getSystemMatchResult(chunk, "modifier_block"), () -> ctx.isBlockMod = true, "System: BlockMod");

        // ==========================================
        // ШАГ 2: МАКРОСЫ (Actions)
        // ==========================================
        for (Map.Entry<String, List<List<String>>> entry : CyberSpaceParser.ACTIONS.entrySet()) {
            collector.add(CyberSpaceParser.getMatchResult(chunk, entry.getValue()), 
                () -> System.out.println("[Router] Макрос активен: " + entry.getKey()), "Macro: " + entry.getKey());
        }

        // ==========================================
        // ШАГ 3: ПРАВИЛА (Предметы, Блоки, Атрибуты)
        // ==========================================

        // 1. Предметы
        for (CyberSpaceParser.ItemContext itemCtx : CyberSpaceParser.ITEM_RULES) {
            collector.add(CyberSpaceParser.getMatchResult(chunk, itemCtx.triggers()), () -> {
                Item targetItem = itemCtx.target();
                if (ctx.isCompMod) {
                    String ruleId = "snapshot_" + BuiltInRegistries.ITEM.getKey(targetItem).getPath();
                    toggleRule(server, ruleId, new DataHacker.RuleData<>(null, null, targetItem, ctx.reqDefault));
                } else if (!ctx.isBlockMod) {
                    ctx.itemsToToggle.add(targetItem);
                }
            }, "Item: " + BuiltInRegistries.ITEM.getKey(itemCtx.target()));
        }

        // 2. Блоки
        for (CyberSpaceParser.BlockContext blockCtx : CyberSpaceParser.BLOCK_RULES) {
            collector.add(CyberSpaceParser.getMatchResult(chunk, blockCtx.triggers()), () -> {
                if (!ctx.isCompMod && !ctx.isItemMod) {
                    boolean hasActiveStateFilter = false;
                    for (CyberSpaceParser.BlockStateContext stateCtx : CyberSpaceParser.BLOCKSTATE_RULES) {
                        if (CyberSpaceParser.checkMatch(chunk, stateCtx.triggers())) {
                            WorldHacker.StateFilter filter = buildStateFilter(blockCtx.target(), stateCtx.property(), stateCtx.value());
                            if (filter != null) {
                                ctx.filtersToToggle.add(filter);
                                hasActiveStateFilter = true;
                            }
                        }
                    }
                    if (!hasActiveStateFilter && !ctx.isReset) {
                        ctx.blocksToToggle.add(blockCtx.target());
                    }
                }
            }, "Block: " + BuiltInRegistries.BLOCK.getKey(blockCtx.target()));
        }

        // 3. Компоненты
        for (CyberSpaceParser.ComponentContext compCtx : CyberSpaceParser.COMPONENT_RULES) {
            collector.add(CyberSpaceParser.getMatchResult(chunk, compCtx.triggers()), () -> {
                toggleRule(server, compCtx.ruleName(), new DataHacker.RuleData<>(compCtx.type(), compCtx.subcomponents(), null, ctx.reqDefault));
            }, "Component: " + compCtx.ruleName());
        }

        // 4. Атрибуты
        for (CyberSpaceParser.AttributeContext attrCtx : CyberSpaceParser.ATTRIBUTE_RULES) {
            collector.add(CyberSpaceParser.getMatchResult(chunk, attrCtx.triggers()), () -> EntityStateHacker.toggleAttribute(server, attrCtx), "Attribute: " + attrCtx.ruleName());
        }

        // 5. Флаги
        for (CyberSpaceParser.EntityFlagContext f : CyberSpaceParser.FLAG_RULES) collector.add(CyberSpaceParser.getMatchResult(chunk, f.triggers()), () -> EntityStateHacker.toggleFlag(f), "Flag: " + f.ruleName());

        // 6. Сущности
        for (CyberSpaceParser.EntityContext entityCtx : CyberSpaceParser.ENTITY_RULES) {
            collector.add(CyberSpaceParser.getMatchResult(chunk, entityCtx.triggers()), () -> ctx.targetEntities.addAll(entityCtx.targets()), "Entities: " + entityCtx.targets());
        }

        // ==========================================
        // ФИНАЛЬНЫЙ ЭТАП: Фильтрация и запуск
        // ==========================================
        collector.execute();

        executeToggleLogic(server, ctx.itemsToToggle, ctx.blocksToToggle, ctx.filtersToToggle, ctx.isReset, ctx.isItemMod, ctx.isBlockMod, ctx.isCompMod);

        if (!ctx.targetEntities.isEmpty()) {
            EntityHacker.toggleEntity(server, ctx.targetEntities);
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