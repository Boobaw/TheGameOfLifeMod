package com.thegameoflife;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.util.ArrayList;
import java.util.List;

public class MicrophoneSelectionScreen extends Screen {

    private final Screen parent;
    private final AudioRecorderMod recorder;

    private Mixer.Info[] mics = new Mixer.Info[0];
    private int index = 0;

    public MicrophoneSelectionScreen(Screen parent, AudioRecorderMod recorder) {
        super(Component.literal("Microphone Selection"));
        this.parent = parent;
        this.recorder = recorder;
    }

    @Override
    protected void init() {
        mics = listAllMixers(); // сначала просто ВСЕ, чтобы точно что-то было
        if (mics.length == 0) index = 0;
        else if (index >= mics.length) index = 0;

        int w = 220;
        int h = 20;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2;

        this.addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {
            if (mics.length == 0) return;
            index = (index - 1 + mics.length) % mics.length;
        }).bounds(x, y, 105, h).build());

        this.addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {
            if (mics.length == 0) return;
            index = (index + 1) % mics.length;
        }).bounds(x + 115, y, 105, h).build());

        this.addRenderableWidget(Button.builder(Component.literal("Select"), b -> {
            if (mics.length == 0) return;
            recorder.setSelectedMixer(mics[index]);
        }).bounds(x, y + 30, w, h).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            this.minecraft.setScreen(parent);
        }).bounds(x, y + 60, w, h).build());
    }

    private static Mixer.Info[] listAllMixers() {
        return AudioSystem.getMixerInfo();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        int boxW = Math.min(this.width - 40, 520);
        int boxX1 = centerX - boxW / 2;
        int boxX2 = centerX + boxW / 2;
        int boxY1 = 15;
        int boxY2 = 110;

        g.fill(boxX1, boxY1, boxX2, boxY2, 0xCC000000);

        String browse = (mics.length == 0) ? "<none>" : mics[index].getName();
        String selected = recorder.getSelectedMixerName();

        g.drawCenteredString(this.font, Component.literal("MIC DEBUG"), centerX, 22, 0xFFFFFF);
        g.drawCenteredString(this.font, Component.literal("count=" + mics.length + " index=" + index), centerX, 38, 0xFFFFFF);
        g.drawCenteredString(this.font, Component.literal("browse: " + browse), centerX, 54, 0xFFFF55);
        g.drawCenteredString(this.font, Component.literal("selected: " + selected), centerX, 70, 0x55FF55);
        g.drawCenteredString(this.font, Component.literal("If count=0 -> JavaSound sees no mixers"), centerX, 90, 0xFF5555);
    }

    private String trim(String s, int boxW) {
        // грубо: если очень длинно — обрежем
        int max = Math.max(10, boxW / 6);
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
