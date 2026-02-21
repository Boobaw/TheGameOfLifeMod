package com.thegameoflife;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class AudioRecorderMod {

    private Mixer.Info selectedMixer;
    private ChatLogger logger;

    public void setLogger(ChatLogger logger) {
        this.logger = logger;
    }

    private void log(String s) {
        if (logger != null) logger.log("[AudioRecorder] " + s);
        System.out.println("[AudioRecorder] " + s);
    }

    public void setSelectedMixer(Mixer.Info info) {
        selectedMixer = info;
        log("Selected mixer: " + (info == null ? "Default" : info.getName()));
    }

    public String getSelectedMixerName() {
        return selectedMixer == null ? "Default" : selectedMixer.getName();
    }

    public Mixer.Info[] listMixers() {
        return AudioSystem.getMixerInfo();
    }

    public byte[] record5sBytes() {
        try {
            AudioFormat[] formats = new AudioFormat[] {
                    new AudioFormat(16000f, 16, 1, true, false),
                    new AudioFormat(44100f, 16, 1, true, false),
                    new AudioFormat(48000f, 16, 1, true, false),
                    new AudioFormat(44100f, 16, 2, true, false),
                    new AudioFormat(48000f, 16, 2, true, false),
            };

            Exception last = null;

            for (AudioFormat format : formats) {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                // 1) сначала пробуем выбранный mixer (если он реально поддерживает)
                if (selectedMixer != null) {
                    try {
                        Mixer m = AudioSystem.getMixer(selectedMixer);
                        if (m.isLineSupported(info)) {
                            TargetDataLine line = (TargetDataLine) m.getLine(info);
                            return recordFixed5sBytes(line, format);
                        }
                    } catch (Exception e) {
                        last = e;
                    }
                }

                // 2) потом пробуем дефолтный вход (часто это и есть настоящий микрофон)
                try {
                    if (AudioSystem.isLineSupported(info)) {
                        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                        return recordFixed5sBytes(line, format);
                    }
                } catch (Exception e) {
                    last = e;
                }
            }

            log("record5s error: " + last);
            return null;

        } catch (Exception e) {
            log("record5s error: " + e);
            return null;
        }
    }

    private byte[] recordFixed5sBytes(TargetDataLine line, AudioFormat format) throws Exception {
        line.open(format);
        line.start();

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];

        long endAt = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < endAt) {
            int read = line.read(buffer, 0, buffer.length);
            if (read > 0) {
                pcmOut.write(buffer, 0, read);
            }
        }

        line.stop();
        line.close();

        byte[] pcm = pcmOut.toByteArray();
        ByteArrayOutputStream wavOut = new ByteArrayOutputStream();
        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(pcm),
                format,
                pcm.length / format.getFrameSize()
        )) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut);
        }

        byte[] data = wavOut.toByteArray();
        log("Chunk recorded: " + data.length + " bytes format=" + format);
        return data;
    }


}
