package com.thegameoflife;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

public class AudioRecorderMod {

    private Mixer.Info selectedMixer;
    private ChatLogger logger;
    private TargetDataLine currentLine;
    private AudioFormat currentFormat;

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

    public byte[] recordChunkBytes() {
        if (currentLine == null || !currentLine.isOpen()) {
            if (!openLine()) {
                return null;
            }
        }

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        // Буфер примерно на 100мс
        int bufferSize = (int) (currentFormat.getSampleRate() * currentFormat.getFrameSize() / 10);
        byte[] buffer = new byte[bufferSize];

        long startTime = System.currentTimeMillis();
        long maxDuration = 5000; // Максимум 5 секунд записи
        long silenceStart = 0;
        boolean speechDetected = false;

        try {
            while (System.currentTimeMillis() - startTime < maxDuration) {
                int read = currentLine.read(buffer, 0, buffer.length);
                if (read > 0) {
                    pcmOut.write(buffer, 0, read);

                    // VAD: Анализируем громкость текущего кусочка (100мс)
                    double rms = calculateRMS(buffer, read);
                    
                    if (rms > 150) {
                        speechDetected = true;
                        silenceStart = 0;
                    } else if (speechDetected) {
                        // Если речь была, но началась тишина
                        if (silenceStart == 0) silenceStart = System.currentTimeMillis();
                        
                        // Если тишина длится больше 800мс — считаем фразу законченной и отправляем
                        if (System.currentTimeMillis() - silenceStart > 800) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("Error reading line: " + e);
            closeLine();
            return null;
        }

        byte[] pcm = pcmOut.toByteArray();
        if (pcm.length == 0) return null;

        // Если за всё время записи не было обнаружено речи (даже короткой) — не отправляем
        if (!speechDetected) {
            return null;
        }

        ByteArrayOutputStream wavOut = new ByteArrayOutputStream();
        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(pcm),
                currentFormat,
                pcm.length / currentFormat.getFrameSize()
        )) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut);
        } catch (Exception e) {
            log("WAV encode error: " + e);
            return null;
        }

        byte[] data = wavOut.toByteArray();
        // log("Chunk recorded: " + data.length + " bytes");
        return data;
    }

    private boolean openLine() {
        AudioFormat[] formats = new AudioFormat[] {
                new AudioFormat(16000f, 16, 1, true, false),
                new AudioFormat(44100f, 16, 1, true, false),
                new AudioFormat(48000f, 16, 1, true, false),
                new AudioFormat(44100f, 16, 2, true, false),
                new AudioFormat(48000f, 16, 2, true, false),
        };

        for (AudioFormat format : formats) {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            try {
                if (selectedMixer != null) {
                    Mixer m = AudioSystem.getMixer(selectedMixer);
                    if (m.isLineSupported(info)) {
                        currentLine = (TargetDataLine) m.getLine(info);
                        currentLine.open(format);
                        currentLine.start();
                        currentFormat = format;
                        log("Opened line on mixer: " + m.getMixerInfo().getName() + " format: " + format);
                        return true;
                    }
                }

                if (AudioSystem.isLineSupported(info)) {
                    currentLine = (TargetDataLine) AudioSystem.getLine(info);
                    currentLine.open(format);
                    currentLine.start();
                    currentFormat = format;
                    log("Opened default line format: " + format);
                    return true;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        log("Failed to open any microphone line");
        return false;
    }

    private void closeLine() {
        if (currentLine != null) {
            currentLine.stop();
            currentLine.close();
            currentLine = null;
        }
    }

    private double calculateRMS(byte[] pcmData, int length) {
        long sum = 0;
        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            sum += sample * sample;
        }
        int samples = length / 2;
        return samples == 0 ? 0 : Math.sqrt((double) sum / samples);
    }

}
