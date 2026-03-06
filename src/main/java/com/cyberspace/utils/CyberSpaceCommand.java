package com.cyberspace.utils;

import com.cyberspace.CyberSpaceMod;
import com.cyberspace.hacker.DataHacker;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.cyberspace.hacker.DataHacker.ACTIVE_RULES;
import static com.cyberspace.hacker.EntityHacker.BANNED_ENTITIES;
import static com.cyberspace.hacker.ItemHacker.BANNED_ITEMS;
import static com.cyberspace.hacker.WorldHacker.*;

public class CyberSpaceCommand {

    // Точка входа для регистрации команды
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // ==========================================
            // НАСТРОЙКА 1: /cs set maxResponseTime <значение>
            // ==========================================
            var maxResponseTimeNode = Commands.literal("maxResponseTime")
                    .then(Commands.argument("value", IntegerArgumentType.integer()).executes(context -> setMaxResponseTime(context, false)))
                    .then(Commands.literal("inf").executes(context -> setMaxResponseTime(context, true)))
                    .then(Commands.literal("infinite").executes(context -> setMaxResponseTime(context, true)));

            // ==========================================
            // НАСТРОЙКА 2: /cs set gamemode <survival | speedrun | fun>
            // Используем литералы, чтобы в игре работал Tab-комплит (автодополнение)
            // ==========================================
            var gamemodeNode = Commands.literal("gamemode")
                    .then(Commands.literal("survival").executes(context -> setGamemode(context, "survival")))
                    .then(Commands.literal("speedrun").executes(context -> setGamemode(context, "speedrun")))
                    .then(Commands.literal("fun").executes(context -> setGamemode(context, "fun")));

            var voicemodNode = Commands.literal("voicemod")
                    .then(Commands.literal("balagan").executes(context -> setVoicemode(context, "balagan")))
                    .then(Commands.literal("queue").executes(context -> setVoicemode(context, "queue")))
                    .then(Commands.literal("random").executes(context -> setVoicemode(context, "random")));

            var aiLookAtChatNode = Commands.literal("aiLookAtChat")
                    .then(Commands.literal("true").executes(context -> setAiLookAtChat(context, true)))
                    .then(Commands.literal("false").executes(context -> setAiLookAtChat(context, false)));



            // ==========================================
            // Основное дерево команд
            // ==========================================
            LiteralCommandNode<CommandSourceStack> cyberspaceNode = dispatcher.register(
                    Commands.literal("cyberspace")

                            // Подкоманда: /cyberspace setting ...
                            .then(Commands.literal("setting")
                                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                    .then(maxResponseTimeNode)
                                    .then(gamemodeNode)
                                    .then(voicemodNode)
                            )

                            // Подкоманда: /cyberspace set ...
                            .then(Commands.literal("set")
                                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                    .then(maxResponseTimeNode)
                                    .then(gamemodeNode)
                                    .then(voicemodNode)
                            )

                            .then(Commands.literal("start")
                                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                    .executes(CyberSpaceCommand::executeStart)
                            )

//                            .then(Commands.literal("stop")
//                                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
//
//                            )

                            .then(Commands.literal("rule")
                                    // Передаем название подкоманды и то, откуда брать список (преобразуя всё в строки)
                                    .then(buildRuleCommand("block", () -> BANNED_BLOCKS.stream().map(b -> b.getName().getString()).toList()))
                                    .then(buildRuleCommand("item", () -> BANNED_ITEMS.stream().map(i -> i.getName().getString()).toList()))
                                    .then(buildRuleCommand("entity", () -> BANNED_ENTITIES.stream().map(e -> e.getDescription().getString()).toList()))
                                    .then(buildRuleCommand("component", () -> ACTIVE_RULES)) // Уже список строк
                                    .then(buildRuleCommand("blockstate", CyberSpaceCommand::getCombinedBlockstates))
                                    // Для all и other передаем пока пустые списки или объединяем существующие
                                    .then(buildRuleCommand("all", CyberSpaceCommand::getAllRules))
                                    .then(buildRuleCommand("other", () -> List.of("Здесь будут прочие правила")))
                            )
            );

            // Алиас /cs
            dispatcher.register(
                    Commands.literal("cs")
                            .redirect(cyberspaceNode)
            );
        });
    }

    // =========================================
    // ЛОГИКА ВЫПОЛНЕНИЯ КОМАНД
    // =========================================

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        int activeCount = ACTIVE_RULES.size();
        int totalCount = DataHacker.REGISTERED_RULES.size();

        source.sendSuccess(() -> Component.literal("=== CyberSpace Status ===").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal("Текущая Эпоха: " + DataHacker.DATA_EPOCH).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Активных правил: " + activeCount + " / " + totalCount).withStyle(ChatFormatting.GREEN), false);

        if (activeCount > 0) {
            source.sendSuccess(() -> Component.literal("Список активных банов:").withStyle(ChatFormatting.GRAY), false);
            for (String ruleId : ACTIVE_RULES) {
                source.sendSuccess(() -> Component.literal("- " + ruleId).withStyle(ChatFormatting.RED), false);
            }
        }

        return 1; // 1 означает успех
    }

    private static int executeClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // Жесткий сброс всех правил
        ACTIVE_RULES.clear();
        DataHacker.REGISTERED_RULES.clear();
        DataHacker.DATA_EPOCH++; // Обязательно двигаем эпоху, чтобы предметы обновились и очистились от клейма!

        source.sendSuccess(() -> Component.literal("ВНИМАНИЕ: Все правила стерты. Конвейер запущен на очистку мира.")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), true); // true = отправить в лог сервера

        return 1;
    }

    private static int executeStart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // 1. Вызываем вашу функцию запуска игры!
        // Если функция в этом же классе, пишите просто startGame();
        CyberSpaceMod.startGame();

        // 2. Отправляем сообщение игроку, что всё сработало
        source.sendSuccess(() -> Component.literal("§aИгра CyberSpace успешно запущена!"), false);

        // 3. Возвращаем 1 (успех)
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRuleCommand(String categoryName, Supplier<List<String>> listSupplier) {
        return Commands.literal(categoryName)
                // Если игрок пишет просто: /cs rule block
                .executes(context -> showPage(context, categoryName, listSupplier.get(), 1))
                // Если игрок пишет со страницей: /cs rule block 2
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> showPage(context, categoryName, listSupplier.get(), IntegerArgumentType.getInteger(context, "page")))
                );
    }

    // Вынесенный метод для удобства (чтобы не писать код три раза для каждого режима)
    private static int setGamemode(CommandContext<CommandSourceStack> context, String mode) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("§aИгровой режим CyberSpace изменен на: §e" + mode), false);

        // TODO: CyberSpaceConfig.setMode(mode);
        return 1;
    }

    private static int setVoicemode(CommandContext<CommandSourceStack> context, String mode) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("§aГолосовой режим CyberSpace изменен на: §e" + mode), false);

        // TODO: CyberSpaceConfig.setMode(mode);
        return 1;
    }

    private static int setAiLookAtChat(CommandContext<CommandSourceStack> context, boolean mode) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("§aРежим просмотра чата AI CyberSpace изменен на: §e" + mode), false);

        // TODO: CyberSpaceConfig.setMode(mode);
        return 1;
    }

    // Вынесенный метод для удобства (чтобы не писать код три раза для каждого режима)
    private static int setMaxResponseTime(CommandContext<CommandSourceStack> context, boolean inf) {
        CommandSourceStack source = context.getSource();
        if (inf) {
            source.sendSuccess(() -> Component.literal("§aВремя на ответ игроком CyberSpace изменен на: §eбесконечность" ), false);
        } else {
            int time = IntegerArgumentType.getInteger(context, "value");
            source.sendSuccess(() -> Component.literal("§aВремя на ответ игроком CyberSpace изменен на: §e" + time + " сек"), false);
        }


        // TODO: CyberSpaceConfig.setMode(mode);
        return 1;
    }

    // ==========================================
    // ЛОГИКА ПАГИНАЦИИ (СТРАНИЦ)
    // ==========================================
    private static int showPage(CommandContext<CommandSourceStack> context, String category, List<String> items, int page) {
        CommandSourceStack source = context.getSource();

        if (items.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§eСписок правил для §b" + category + " §eпуст."), false);
            return 1;
        }

        int itemsPerPage = 8; // Сколько элементов выводить на одной странице
        int totalPages = (int) Math.ceil((double) items.size() / itemsPerPage);

        // Защита от ввода слишком большой страницы
        if (page > totalPages) page = totalPages;

        // Заголовок
        int finalPage = page;
        source.sendSuccess(() -> Component.literal("§6--- Правила: §e" + category + " §6(Страница §f" + finalPage + " §6из §f" + totalPages + "§6) ---"), false);

        // Вычисляем индексы для вырезки нужной части списка
        int start = (page - 1) * itemsPerPage;
        int end = Math.min(start + itemsPerPage, items.size());

        // Выводим сами элементы
        for (int i = start; i < end; i++) {
            int index = i + 1;
            String itemText = items.get(i);
            source.sendSuccess(() -> Component.literal("§7" + index + ". §f" + itemText), false);
        }

        // Создаем кликабельные кнопки [Назад] и [Вперед]
        MutableComponent footer = Component.literal("§6-----------------------------------");

        if (totalPages > 1) {
            MutableComponent buttons = Component.literal("\n");

            // Кнопка НАЗАД
            if (page > 1) {
                buttons.append(Component.literal("§a[<- Назад] ")
                        .withStyle(Style.EMPTY
                                // Используем новый синтаксис
                                .withClickEvent(new ClickEvent.RunCommand("/cs rule " + category + " " + (page - 1)))
                        ));
            }

            // Кнопка ВПЕРЕД
            if (page < totalPages) {
                buttons.append(Component.literal("§a[Вперед ->]")
                        .withStyle(Style.EMPTY
                                // Используем новый синтаксис
                                .withClickEvent(new ClickEvent.RunCommand("/cs rule " + category + " " + (page + 1)))
                        ));
            }
            footer.append(buttons);
        }

        source.sendSuccess(() -> footer, false);
        return 1;
    }

    // Метод для объединения BANNED_BREAK_FILTERS и BANNED_RESET_FILTERS
    private static List<String> getCombinedBlockstates() {
        List<String> combined = new ArrayList<>();

        // Добавляем префиксы, чтобы игрок понимал, к какому типу относится фильтр
        BANNED_BREAK_FILTERS.forEach(filter -> combined.add("§c[Break] §f" + filter.toString()));
        BANNED_RESET_FILTERS.forEach(filter -> combined.add("§b[Reset] §f" + filter.toString()));

        return combined;
    }

    // Метод для сборки абсолютно всех правил в один мега-список
    private static List<String> getAllRules() {
        List<String> all = new ArrayList<>();

        BANNED_BLOCKS.forEach(b -> all.add("§7[Блок] §f" + b.getName().getString()));
        BANNED_ITEMS.forEach(i -> all.add("§7[Предмет] §f" + i.getName().getString()));
        BANNED_ENTITIES.forEach(e -> all.add("§7[Сущность] §f" + e.getDescription().getString()));
        ACTIVE_RULES.forEach(r -> all.add("§7[Компонент] §f" + r));

        // Добавляем наши уже объединенные фильтры
        all.addAll(getCombinedBlockstates());

        return all;
    }
}