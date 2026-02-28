package com.thegameoflife;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class CyberSpaceConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static JsonObject brainData;

    public static void loadConfig() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        // Новое имя файла в папке config на сервере
        Path jsonFile = configDir.resolve("CyberSpace_config.json");

        try {
            if (!Files.exists(jsonFile)) {
                System.out.println("[CyberSpace] Конфиг не найден. Распаковываем стандартную матрицу...");

                // Ищем шаблон внутри собранного .jar мода
                try (InputStream in = CyberSpaceConfig.class.getResourceAsStream("/cyberspace_config_default.json")) {
                    if (in != null) {
                        Files.copy(in, jsonFile, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("[CyberSpace] Шаблон успешно скопирован в config/CyberSpace_config.json");
                    } else {
                        System.err.println("[CyberSpace] ВНИМАНИЕ: Шаблон не найден в ресурсах мода! Создаю пустой.");
                        Files.writeString(jsonFile, "{\n  \"registry\": {}\n}");
                    }
                }
            }

            // Читаем JSON в память
            String content = Files.readString(jsonFile);
            brainData = GSON.fromJson(content, JsonObject.class);
            System.out.println("[CyberSpace] Матрица инициализирована! Готов к приему команд от ИИ.");

        } catch (Exception e) {
            System.err.println("[CyberSpace] КРИТИЧЕСКАЯ ОШИБКА ЧТЕНИЯ/КОПИРОВАНИЯ КОНФИГА!");
            e.printStackTrace();
        }
    }

    public static JsonObject getConfig() {
        return brainData;
    }
}