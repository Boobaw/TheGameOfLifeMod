package com.thegameoflife;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MicrophoneSelectionScreen extends Screen {

    private final Screen parent;
    private final AudioRecorderMod recorder;
    private final List<Mixer.Info> inputDevices = new ArrayList<>();
    private int currentIndex = 0;

    public MicrophoneSelectionScreen(Screen parent, AudioRecorderMod recorder) {
        super(Component.literal("Microphone Selection"));
        this.parent = parent;
        this.recorder = recorder;
    }

    @Override
    protected void init() {
        inputDevices.clear();
        // Добавляем вариант "По умолчанию" (null)
        inputDevices.add(null);

        // Фильтруем микшеры, оставляя только те, что поддерживают запись (TargetDataLine)
        try {
            Mixer.Info[] allMixers = AudioSystem.getMixerInfo();
            if (allMixers != null) {
                for (Mixer.Info info : allMixers) {
                    try {
                        Mixer mixer = AudioSystem.getMixer(info);
                        if (mixer.isLineSupported(new Line.Info(TargetDataLine.class))) {
                            inputDevices.add(info);
                        }
                    } catch (Throwable e) {
                        // Игнорируем устройства, которые не удалось открыть
                    }
                }
            }
        } catch (Throwable e) {
            // Игнорируем ошибки аудиосистемы
        }

        // Пытаемся найти текущий выбранный микрофон в списке
        String currentName = recorder.getSelectedMixerName();
        currentIndex = 0; // По умолчанию 0 (Default)

        if (!"Default".equals(currentName)) {
            for (int i = 1; i < inputDevices.size(); i++) {
                Mixer.Info info = inputDevices.get(i);
                if (info != null && info.getName().equals(currentName)) {
                    currentIndex = i;
                    break;
                }
            }
        }

        int centerY = this.height / 2;
        int centerX = this.width / 2;

        // Сдвигаем кнопки ниже, чтобы освободить место для текста
        int buttonY = centerY + 10;

        // Кнопка "Назад" (<)
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (!inputDevices.isEmpty()) {
                currentIndex = (currentIndex - 1 + inputDevices.size()) % inputDevices.size();
            }
        }).bounds(centerX - 120, buttonY, 20, 20).build());

        // Кнопка "Вперед" (>)
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if (!inputDevices.isEmpty()) {
                currentIndex = (currentIndex + 1) % inputDevices.size();
            }
        }).bounds(centerX + 100, buttonY, 20, 20).build());

        // Кнопка "Выбрать"
        this.addRenderableWidget(Button.builder(Component.literal("Select"), b -> {
            if (!inputDevices.isEmpty()) {
                recorder.setSelectedMixer(inputDevices.get(currentIndex));
            }
        }).bounds(centerX - 50, buttonY + 30, 100, 20).build());

        // Кнопка "Готово"
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            this.minecraft.setScreen(parent);
        }).bounds(centerX - 50, buttonY + 55, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        try {
            this.renderBackground(g, mouseX, mouseY, delta);
        } catch (Throwable ignored) {
            // Если метод не найден (разные версии MC) или ошибка, рисуем фон вручную
            g.fill(0, 0, this.width, this.height, 0xCC000000);
        }
        super.render(g, mouseX, mouseY, delta);

        g.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);

        int centerY = this.height / 2;

        if (inputDevices.isEmpty()) {
            g.drawCenteredString(this.font, "No microphones found", this.width / 2, centerY - 20, 0xFFFF5555);
            return;
        }

        if (currentIndex >= inputDevices.size()) currentIndex = 0;
        Mixer.Info info = inputDevices.get(currentIndex);
        String name = (info == null) ? "Default System Device" : info.getName();
        String desc = (info == null) ? "Uses the OS default microphone" : info.getDescription();

        // Позиция текста выше кнопок
        int textY = centerY - 40;

        // Индикация текущего выбора (над названием)
        String selectedName = recorder.getSelectedMixerName();
        boolean isSelected = (info == null && "Default".equals(selectedName)) ||
                             (info != null && info.getName().equals(selectedName));

        if (isSelected) {
            g.drawCenteredString(this.font, Component.literal("[ Selected ]"), this.width / 2, textY - 15, 0xFF55FF55);
        }

        // Отображаем имя устройства
        g.drawCenteredString(this.font, Component.literal(name), this.width / 2, textY, 0xFFFFFFFF);
        
        // Отображаем описание (серым)
        g.drawCenteredString(this.font, Component.literal(desc), this.width / 2, textY + 15, 0xFFAAAAAA);
    }
}
