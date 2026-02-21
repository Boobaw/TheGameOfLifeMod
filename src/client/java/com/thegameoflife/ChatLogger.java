package com.thegameoflife;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ChatLogger {

    private final File file;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");

    public ChatLogger(File file) {
        this.file = file;
        file.getParentFile().mkdirs();
        log("Logger started");
    }

    public void log(String msg) {
        String line = "[" + fmt.format(new Date()) + "] " + msg;
        System.out.println(line);

        try (BufferedWriter w = new BufferedWriter(new FileWriter(file, true))) {
            w.write(line);
            w.newLine();
        } catch (Exception ignored) {}
    }
}
