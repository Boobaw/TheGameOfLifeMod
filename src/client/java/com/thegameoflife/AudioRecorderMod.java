package com.thegameoflife;

import javax.sound.sampled.*;
import java.io.File;

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

    public File record5s(File dir, String name) {
        try {
            dir.mkdirs();
            File out = new File(dir, name + ".wav");

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
                            return recordFixed5s(out, line, format);
                        }
                    } catch (Exception e) {
                        last = e;
                    }
                }

                // 2) потом пробуем дефолтный вход (часто это и есть настоящий микрофон)
                try {
                    if (AudioSystem.isLineSupported(info)) {
                        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                        return recordFixed5s(out, line, format);
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

    private File recordFixed5s(File out, TargetDataLine line, AudioFormat format) throws Exception {
        line.open(format);
        line.start();

        AudioInputStream stream = new AudioInputStream(line);

        Thread writer = new Thread(() -> {
            try { AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out); }
            catch (Exception ignored) {}
        });
        writer.start();

        Thread.sleep(5000);

        line.stop();
        line.close();

        log("Chunk recorded: " + out.getAbsolutePath() + " format=" + format);
        return out;
    }


}
