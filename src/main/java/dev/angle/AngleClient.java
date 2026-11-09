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
    private static final double MAX_CURVE = 0.05;      // seitliche Bogenform des Wegs (Anteil der Distanz)

    // Geschwindigkeitsprofil: Mitte schneller, Endphase unveraendert.
    // Bis ENDPHASE_START (Anteil der alten Gesamtzeit) laeuft die schnelle Phase, danach exakt das alte Abbremsen.
    private static final double ENDPHASE_START = 0.752;  // ab hier (alte Zeitachse) wird abgebremst
    private static final double FAST_PHASE_TIME = 0.55;  // Dauer der schnellen Phase relativ zu frueher (0.752 -> 0.55)

    private static final Random RNG = new Random();

    private static boolean active = false;
    private static double startPitch, startYaw, dPitch, dYaw;
    private static double curve;          // seitliche Ausbuchtung
    private static long startNanos;
    private static double durationMs;
    private static double lastPitchNoise, lastYawNoise;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("angle")
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    active = false;
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
        // Fitts-aehnlich: weiter = laenger, plus zufaellige Reaktionszeit/Tempo
        durationMs = (40 + 1.5 * Math.pow(dist, 0.9)) * (0.85 + RNG.nextDouble() * 0.35);
        durationMs = Mth.clamp(durationMs, 50, 400);
        curve = (RNG.nextDouble() * 2 - 1) * MAX_CURVE;
        startNanos = System.nanoTime();
        lastPitchNoise = lastYawNoise = 0;
        active = true;
    }

    /** Minimum-Jerk-Profil: langsamer Start, schnelle Mitte, sanftes Abbremsen. */
    private static double minJerk(double t) {
        return t * t * t * (10 + t * (-15 + 6 * t));
    }

    /** Fortschritt 0..1 zur Zeit elapsedMs; d0 = alte Gesamtdauer. */
    private static double progress(double elapsedMs, double d0) {
        double t0 = ENDPHASE_START;
        double tFast = FAST_PHASE_TIME * d0;
        double a = minJerk(t0);                                  // Strecke, die die schnelle Phase schafft
        if (elapsedMs < tFast) {
            double tau = elapsedMs / tFast;
            double vEnd = 30 * t0 * t0 * (1 - t0) * (1 - t0);    // Geschwindigkeit am Uebergang (wie frueher)
            double m1 = vEnd * FAST_PHASE_TIME;
            return a * (-2 * tau * tau * tau + 3 * tau * tau) + m1 * (tau * tau * tau - tau * tau);
        }
        double tt = t0 + (elapsedMs - tFast) / d0;               // Endphase wie frueher
        return minJerk(Math.min(tt, 1.0));
    }

    private static void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null) { active = false; return; }

        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        double totalMs = durationMs * (FAST_PHASE_TIME + (1.0 - ENDPHASE_START));
        double t = Mth.clamp(elapsedMs / totalMs, 0.0, 1.0);
        double s = progress(Math.min(elapsedMs, totalMs), durationMs);

        // Bogen: Auslenkung senkrecht zur Bewegungsrichtung, 0 am Start und am Ziel
        double bow = Math.sin(Math.PI * t) * curve;
        double pitch = startPitch + dPitch * s + (-dYaw) * bow * 0.5;
        double yaw = startYaw + dYaw * s + dPitch * bow;

        // Zittern, das gegen Ende abklingt (Ziel wird sauber erreicht)
        double fade = 1.0 - t;
        lastPitchNoise = lastPitchNoise * 0.6 + RNG.nextGaussian() * TREMOR_DEG * fade;
        lastYawNoise = lastYawNoise * 0.6 + RNG.nextGaussian() * TREMOR_DEG * fade;
        pitch += lastPitchNoise;
        yaw += lastYawNoise;

        p.setXRot((float) Mth.clamp(pitch, -90.0, 90.0));
        p.setYRot((float) yaw);

        if (t >= 1.0) active = false;
    }
}
