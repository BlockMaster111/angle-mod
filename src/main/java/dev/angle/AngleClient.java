package dev.angle;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Random;

public class AngleClient implements ClientModInitializer {

    // ---- Einstellungen ----
    private static final double MIN_OFFSET_DEG = 1.0;   // min. Abweichung von Pitch/Yaw
    private static final double MAX_OFFSET_DEG = 2.0;   // max. Abweichung von Pitch/Yaw (wenige Grad)
    private static final double TREMOR_DEG = 0.02;     // minimales Handzittern

    // Aus deiner Aufnahme abgeleitetes Bewegungsmodell (Median ueber ~80 Drehungen >= 40 Grad):
    // Fortschritt (0..1) ueber normalisierte Zeit 0.0, 0.1, ... 1.0 - frueh schnell, langes weiches Auslaufen.
    private static final double[] SHAPE = { 0.0, 0.033, 0.141, 0.288, 0.442, 0.605, 0.757, 0.88, 0.957, 0.99, 1.0 };
    private static double[] shapeSlopes;   // Tangenten fuer monotone kubische Interpolation

    // Gesamtdauer: 240 + 35*ln(1 + Winkel/20) ms (bei dir kaum von der Strecke abhaengig), mal SPEED_SCALE.
    // SPEED_SCALE < 1 = schneller als dein Median. 1.0 = exakt dein Median, 0.75 = deine schnelleren Drehungen.
    private static final double SPEED_SCALE = 0.75;
    private static final double DURATION_SPREAD = 0.20;   // Streuung der Dauer (log-normal)
    private static final double CURVE_MEDIAN = 0.023;     // seitliche Ausbuchtung relativ zur Strecke (dein Median)

    private static final Random RNG = new Random();

    // Nachlauf: nach dem Ziel noch kurz minimal weiterdriften (unter 1 Sekunde)
    private static final double SETTLE_MIN_MS = 400, SETTLE_MAX_MS = 900;
    private static final double SETTLE_MIN_DEG = 0.05, SETTLE_MAX_DEG = 0.25;

    // Echter Maus-Input stoppt die Bewegung: Wenn sich die Kamera zwischen zwei Ticks von dem unterscheidet,
    // was der Mod zuletzt gesetzt hat, kam der Unterschied von dir. Summe ueber die Bewegung > Schwelle = Abbruch.
    private static final double INPUT_CANCEL_DEG = 0.5;
    private static float lastSetPitch, lastSetYaw;
    private static double inputAccum;

    private static boolean active = false;
    private static boolean settling = false;
    private static long settleStartNanos;
    private static double settleMs, settleDPitch, settleDYaw, settleBasePitch, settleBaseYaw;
    private static double startPitch, startYaw, dPitch, dYaw;
    private static double curve;          // seitliche Ausbuchtung
    private static long startNanos;
    private static double durationMs;
    private static double lastPitchNoise, lastYawNoise;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("angle")
                .then(ClientCommands.literal("record")
                    .then(ClientCommands.literal("start").executes(ctx -> {
                        try {
                            java.nio.file.Path path = Recorder.start();
                            ctx.getSource().sendFeedback(Component.literal(
                                "Aufnahme gestartet. Beenden mit /angle record stop. Datei: " + path));
                        } catch (java.io.IOException e) {
                            ctx.getSource().sendFeedback(Component.literal("Aufnahme konnte nicht gestartet werden: " + e.getMessage()));
                        }
                        return 1;
                    }))
                    .then(ClientCommands.literal("stop").executes(ctx -> {
                        long n = Recorder.samples();
                        java.nio.file.Path path = Recorder.stop();
                        if (path == null) {
                            ctx.getSource().sendFeedback(Component.literal("Es laeuft keine Aufnahme."));
                        } else {
                            ctx.getSource().sendFeedback(Component.literal(
                                "Aufnahme beendet (" + n + " Zeilen). Schick mir diese Datei: " + path));
                        }
                        return 1;
                    })))
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    active = false;
                    settling = false;
                    return 1;
                }))
                .then(ClientCommands.argument("pitch", DoubleArgumentType.doubleArg(-90, 90))
                    .then(ClientCommands.argument("yaw", DoubleArgumentType.doubleArg(-100000, 100000))
                        .executes(ctx -> {
                            double pitch = DoubleArgumentType.getDouble(ctx, "pitch");
                            double yaw = DoubleArgumentType.getDouble(ctx, "yaw");
                            start(pitch, yaw);
                            return 1;
                        })))));

        ClientTickEvents.END_CLIENT_TICK.register(AngleClient::tick);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
            .register(client -> Recorder.stop());
    }

    private static double offset() {
        // immer ein paar Grad daneben: Betrag 1.0 bis 2.0, Vorzeichen zufaellig
        double mag = MIN_OFFSET_DEG + RNG.nextDouble() * (MAX_OFFSET_DEG - MIN_OFFSET_DEG);
        return RNG.nextBoolean() ? mag : -mag;
    }

    private static void start(double pitch, double yaw) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;

        double targetPitch = Mth.clamp(pitch + offset(), -90.0, 90.0);
        double targetYaw = yaw + offset();

        startPitch = p.getXRot();
        startYaw = p.getYRot();
        dPitch = targetPitch - startPitch;
        dYaw = Mth.wrapDegrees(targetYaw - startYaw); // kuerzester Weg

        double dist = Math.hypot(dPitch, dYaw);
        double base = (240 + 35 * Math.log(1 + dist / 20.0)) * SPEED_SCALE;
        durationMs = base * Math.exp(RNG.nextGaussian() * DURATION_SPREAD);
        durationMs = Mth.clamp(durationMs, 80, 900);
        double mag = Mth.clamp(CURVE_MEDIAN * Math.exp(RNG.nextGaussian() * 0.7), 0.003, 0.08);
        curve = RNG.nextBoolean() ? mag : -mag;
        startNanos = System.nanoTime();
        lastPitchNoise = lastYawNoise = 0;
        lastSetPitch = p.getXRot();
        lastSetYaw = p.getYRot();
        inputAccum = 0;
        settling = false;
        active = true;
    }

    /** Minimum-Jerk-Profil: langsamer Start, schnelle Mitte, sanftes Abbremsen. */
    private static double minJerk(double t) {
        return t * t * t * (10 + t * (-15 + 6 * t));
    }

    /** Fortschritt 0..1 bei normalisierter Zeit t (0..1): monotone kubische Interpolation durch SHAPE. */
    private static double shape(double t) {
        if (shapeSlopes == null) shapeSlopes = computeSlopes();
        int n = SHAPE.length - 1;
        double x = Mth.clamp(t, 0.0, 1.0) * n;
        int i = Math.min((int) x, n - 1);
        double u = x - i;
        double u2 = u * u, u3 = u2 * u;
        double h = 1.0 / n;
        return (2 * u3 - 3 * u2 + 1) * SHAPE[i]
             + (u3 - 2 * u2 + u) * h * shapeSlopes[i]
             + (-2 * u3 + 3 * u2) * SHAPE[i + 1]
             + (u3 - u2) * h * shapeSlopes[i + 1];
    }

    /** Fritsch-Carlson: Tangenten so waehlen, dass die Kurve nie rueckwaerts laeuft; Start/Ende mit Geschwindigkeit 0. */
    private static double[] computeSlopes() {
        int n = SHAPE.length - 1;
        double h = 1.0 / n;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = (SHAPE[i + 1] - SHAPE[i]) / h;
        double[] m = new double[n + 1];
        m[0] = 0;
        m[n] = 0;
        for (int i = 1; i < n; i++) m[i] = (d[i - 1] + d[i]) / 2.0;
        for (int i = 0; i < n; i++) {
            if (d[i] == 0) { m[i] = 0; m[i + 1] = 0; continue; }
            double a = m[i] / d[i], b = m[i + 1] / d[i];
            double r = a * a + b * b;
            if (r > 9) {
                double k = 3 / Math.sqrt(r);
                m[i] = k * a * d[i];
                m[i + 1] = k * b * d[i];
            }
        }
        return m;
    }

    private static void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null) { active = false; settling = false; return; }

        // Hat der Spieler die Maus bewegt? Dann sofort aufhoeren und seine Kamera nicht ueberschreiben.
        double devPitch = p.getXRot() - lastSetPitch;
        double devYaw = Mth.wrapDegrees(p.getYRot() - lastSetYaw);
        inputAccum += Math.hypot(devPitch, devYaw);
        if (inputAccum > INPUT_CANCEL_DEG) {
            active = false;
            settling = false;
            return;
        }

        if (settling) {
            double u = Mth.clamp((System.nanoTime() - settleStartNanos) / 1_000_000.0 / settleMs, 0.0, 1.0);
            double e = minJerk(u);
            double fadeS = 1.0 - u;
            lastPitchNoise = lastPitchNoise * 0.6 + RNG.nextGaussian() * 0.01 * fadeS;
            lastYawNoise = lastYawNoise * 0.6 + RNG.nextGaussian() * 0.01 * fadeS;
            p.setXRot((float) Mth.clamp(settleBasePitch + settleDPitch * e + lastPitchNoise, -90.0, 90.0));
            p.setYRot((float) (settleBaseYaw + settleDYaw * e + lastYawNoise));
            lastSetPitch = p.getXRot();
            lastSetYaw = p.getYRot();
            if (u >= 1.0) { active = false; settling = false; }
            return;
        }

        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        double t = Mth.clamp(elapsedMs / durationMs, 0.0, 1.0);
        double s = shape(t);

        // Bogen: Auslenkung senkrecht zur Bewegungsrichtung, 0 am Start und am Ziel
        double bow = Math.sin(Math.PI * t) * curve;
        double pitch = startPitch + dPitch * s + dYaw * bow;     // senkrecht zur Bewegungsrichtung
        double yaw = startYaw + dYaw * s - dPitch * bow;

        // Zittern, das gegen Ende abklingt (Ziel wird sauber erreicht)
        double fade = 1.0 - t;
        lastPitchNoise = lastPitchNoise * 0.6 + RNG.nextGaussian() * TREMOR_DEG * fade;
        lastYawNoise = lastYawNoise * 0.6 + RNG.nextGaussian() * TREMOR_DEG * fade;
        pitch += lastPitchNoise;
        yaw += lastYawNoise;

        p.setXRot((float) Mth.clamp(pitch, -90.0, 90.0));
        p.setYRot((float) yaw);
        lastSetPitch = p.getXRot();
        lastSetYaw = p.getYRot();

        if (t >= 1.0) beginSettle();
    }

    /** Kurzes, minimales Weiterdriften in grob gleicher Richtung, dann Stillstand. */
    private static void beginSettle() {
        settleBasePitch = startPitch + dPitch;
        settleBaseYaw = startYaw + dYaw;
        double heading = RNG.nextDouble() * 2 * Math.PI;   // in deiner Aufnahme keine Vorzugsrichtung
        double mag = SETTLE_MIN_DEG + RNG.nextDouble() * (SETTLE_MAX_DEG - SETTLE_MIN_DEG);
        settleDYaw = Math.cos(heading) * mag;
        settleDPitch = Math.sin(heading) * mag;
        settleMs = SETTLE_MIN_MS + RNG.nextDouble() * (SETTLE_MAX_MS - SETTLE_MIN_MS);
        settleStartNanos = System.nanoTime();
        settling = true;
    }
}
