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
    private static final double MAX_OFFSET_DEG = 2.5;   // max. Abweichung von Pitch/Yaw (wenige Grad)
    private static final double TREMOR_DEG = 0.06;     // minimales Handzittern
    private static final double MAX_CURVE = 0.10;      // seitliche Bogenform des Wegs (Anteil der Distanz)

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

        ClientTickEvents.START_CLIENT_TICK.register(AngleClient::tick);
    }

    private static double offset() {
        // Gauss-verteilt, begrenzt, damit es meistens klein ist
        double g = RNG.nextGaussian() * (MAX_OFFSET_DEG / 2.0);
        return Mth.clamp(g, -MAX_OFFSET_DEG, MAX_OFFSET_DEG);
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

    private static void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null) { active = false; return; }

        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        double t = Mth.clamp(elapsedMs / durationMs, 0.0, 1.0);
        double s = minJerk(t);

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
