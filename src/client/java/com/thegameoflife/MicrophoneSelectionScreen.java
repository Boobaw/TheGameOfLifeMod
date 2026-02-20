package com.thegameoflife;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import javax.sound.sampled.Mixer;

public class MicrophoneSelectionScreen extends Screen {

    private final Screen parent;
    private final AudioRecorderMod recorder;
    private Mixer.Info[] mixers;
    private int index = 0;

    public MicrophoneSelectionScreen(Screen parent, AudioRecorderMod recorder) {
        super(Component.literal("Microphone Selection"));
        this.parent = parent;
        this.recorder = recorder;
    }

    @Override
    protected void init() {
        mixers = recorder.listMixers();

        int x = this.width / 2 - 110;
        int y = this.height / 2;

        this.addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> index = (index - 1 + mixers.length) % mixers.length)
                .bounds(x, y, 100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> index = (index + 1) % mixers.length)
                .bounds(x + 120, y, 100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Select"),
                        b -> recorder.setSelectedMixer(mixers[index]))
                .bounds(x, y + 30, 220, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"),
                        b -> this.minecraft.setScreen(parent))
                .bounds(x, y + 60, 220, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);

        g.drawCenteredString(this.font,
                Component.literal("Selected: " + recorder.getSelectedMixerName()),
                this.width / 2, 30, 0x00FF00);
    }
}
