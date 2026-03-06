package com.cyberspace;


import com.cyberspace.hacker.DataHackerEvents;
import com.cyberspace.hacker.GlobalRadar;
import com.cyberspace.hacker.WorldHacker;
import com.cyberspace.utils.CommandRouter;
import com.cyberspace.utils.CyberSpaceCommand;
import com.cyberspace.utils.CyberSpaceConfig;
import com.cyberspace.utils.CyberSpaceParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;


public class CyberSpaceMod implements ModInitializer {
    public static final String MOD_ID = "cyberspace";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinecraftServer SERVER;


    public static final Map<String, Consumer<ServerPlayer>> COMMAND_MAP = new HashMap<>();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server; // Теперь переменная не null
        });

        VoiceNetworking.registerPayloads();
        VoiceServer.registerNetworking(); // Регистрируем прием пакетов СРАЗУ
        // Загружаем конфиг
        CyberSpaceConfig.loadConfig();

        // Инициализация парсера (подгружаем json в память)
        CyberSpaceParser.initialize();

        CyberSpaceCommand.register();

    }

    private static void runCommand(String command) {
        CommandSourceStack source = SERVER.createCommandSourceStack();
        try {
            SERVER.getCommands()
                    .getDispatcher()
                    .execute(command, source);
        } catch (Exception e) {
            LOGGER.error("Failed to execute command: /{}", command, e);
        }
    }

    public static void startGame() {
        // Подписываемся на ивент использования пкм для корректной работы с компонентами, зависящих от пкм
        DataHackerEvents.register();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // 1. Вызываем радар (теперь это одна чистая строчка)
            WorldHacker.tickBlockRadar(server);

            GlobalRadar.tickEntityRadar(server);

            // 2. Дозатор света
            WorldHacker.processLightQueues(server);
        });


        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.signedContent(); // текст сообщения от ИИ или игрока
            ServerPlayer player = sender;

            // 1. Разбиваем пакет команд по запятым
            String[] commandChunks = text.split(",");

            // 2. Прогоняем каждый кусок через роутер
            for (String rawChunk : commandChunks) {
                String chunk = rawChunk.trim().toLowerCase();
                if (chunk.isEmpty()) continue;

                // Отправляем кусок на анализ и исполнение
                CommandRouter.processChunk(chunk, player, SERVER);
            }
        });

    }
}