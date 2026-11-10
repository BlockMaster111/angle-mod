package dev.angle;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * Zeichnet die Kameradrehung (= deine Mausbewegung) mit hoher Zeitaufloesung in eine CSV-Datei auf.
 * Ein eigener Thread prueft ca. alle 0,5 ms, ob sich Pitch/Yaw geaendert haben, und schreibt nur dann eine Zeile.
 * Datei: .minecraft/angle-recordings/recording-<Datum>.csv
 */
public final class Recorder {
    private static volatile boolean running = false;
    private static volatile long samples = 0;
    private static Thread thread;
    private static Path file;

    private Recorder() {}

    public static boolean isRunning() {
        return running;
    }

    public static long samples() {
        return samples;
    }

    public static synchronized Path start() throws IOException {
        if (running) return file;
        Path dir = FabricLoader.getInstance().getGameDir().resolve("angle-recordings");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        file = dir.resolve("recording-" + stamp + ".csv");
        BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        w.write("# angle-recording v1\n");
        w.write("# t_ms = Millisekunden seit Start, pitch/yaw in Grad (yaw nicht normalisiert), nur Zeilen bei Aenderung\n");
        w.write("t_ms,pitch,yaw\n");
        samples = 0;
        running = true;
        final long t0 = System.nanoTime();
        thread = new Thread(() -> loop(w, t0), "angle-recorder");
        thread.setDaemon(true);
        thread.start();
        return file;
    }

    /** Beendet die Aufnahme und gibt den Pfad der Datei zurueck (oder null, wenn keine lief). */
    public static synchronized Path stop() {
        if (!running) return null;
        running = false;
        try {
            if (thread != null) thread.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return file;
    }

    private static void loop(BufferedWriter w, long t0) {
        float lastPitch = Float.NaN, lastYaw = Float.NaN;
        long lastFlush = System.nanoTime();
        try {
            while (running) {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null) {
                    float pitch = p.getXRot();
                    float yaw = p.getYRot();
                    if (Float.isNaN(lastPitch)
                            || Math.abs(pitch - lastPitch) > 1e-4f
                            || Math.abs(yaw - lastYaw) > 1e-4f) {
                        double ms = (System.nanoTime() - t0) / 1_000_000.0;
                        w.write(String.format(Locale.ROOT, "%.3f,%.4f,%.4f%n", ms, pitch, yaw));
                        lastPitch = pitch;
                        lastYaw = yaw;
                        samples++;
                    }
                }
                long now = System.nanoTime();
                if (now - lastFlush > 2_000_000_000L) {
                    w.flush();
                    lastFlush = now;
                }
                LockSupport.parkNanos(500_000L);
            }
        } catch (IOException e) {
            running = false;
        } finally {
            try {
                w.flush();
                w.close();
            } catch (IOException ignored) {
            }
        }
    }
}
