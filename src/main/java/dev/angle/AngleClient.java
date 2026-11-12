package dev.angle;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.function.Consumer;

public class AngleClient implements ClientModInitializer {

    // ---- Einstellungen ----
    private static final double MIN_OFFSET_DEG = 0.1;   // min. Abweichung von Pitch/Yaw
    private static final double MAX_OFFSET_DEG = 0.4;   // max. Abweichung von Pitch/Yaw (wenige Grad)
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

    // ---- Block-/Hebel-Modus ----
    // Zielpunkt liegt immer INNERHALB der Hitbox: Streuung gaussverteilt um die Mitte, mindestens EDGE_MARGIN
    // (Anteil der Boxgroesse) Abstand zum Rand. So ist die "Abweichung" immer noch ein Treffer.
    private static final double BLOCK_SPREAD = 0.22;   // Streuung relativ zur Boxgroesse
    private static final double EDGE_MARGIN = 0.15;    // Mindestabstand zum Rand relativ zur Boxgroesse
    private static final double BLOCK_REACH = 4.5;     // Interaktionsreichweite (Warnung, falls weiter weg)
    private static boolean blockMode = false;   // Ziel ist ein Block: Hitbox-Pruefung beim Nachdriften
    private static boolean blockTrack = false;  // keep: Ziel laufend nachfuehren / once: Winkel einmalig fest
    private static AABB targetBox;      // Hitbox in Weltkoordinaten
    private static Vec3 targetPoint;    // gewaehlter Zielpunkt in der Hitbox (Weltkoordinaten)

    // Nachlauf: nach dem Ziel noch kurz minimal weiterdriften (unter 1 Sekunde)
    private static final double SETTLE_MIN_MS = 400, SETTLE_MAX_MS = 900;
    private static final double SETTLE_MIN_DEG = 0.05, SETTLE_MAX_DEG = 0.25;

    // Echter Maus-Input stoppt die Bewegung: Wenn sich die Kamera zwischen zwei Ticks von dem unterscheidet,
    // was der Mod zuletzt gesetzt hat, kam der Unterschied von dir. Summe ueber die Bewegung > Schwelle = Abbruch.
    private static final double INPUT_CANCEL_DEG = 0.5;
    private static float lastSetPitch, lastSetYaw;
    private static double inputAccum;

    // Sperre: Nach einem angenommenen /angle wird 0.3 s lang (ab Start der Bewegung) kein neuer angenommen.
    private static final long COOLDOWN_NANOS = 300_000_000L;
    private static long lastAcceptedNanos = 0;
    private static boolean hasAccepted = false;

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
                .then(blockTree("block", false))
                .then(blockTree("lever", true))
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
        // immer ein paar Grad daneben: Betrag 0.7 bis 1.5, Vorzeichen zufaellig
        double mag = MIN_OFFSET_DEG + RNG.nextDouble() * (MAX_OFFSET_DEG - MIN_OFFSET_DEG);
        return RNG.nextBoolean() ? mag : -mag;
    }

    private static boolean onCooldown() {
        return hasAccepted && System.nanoTime() - lastAcceptedNanos < COOLDOWN_NANOS;
    }

    private static void markAccepted() {
        lastAcceptedNanos = System.nanoTime();
        hasAccepted = true;
    }

    private static void start(double pitch, double yaw) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        if (onCooldown()) return;   // noch gesperrt
        markAccepted();

        double targetPitch = Mth.clamp(pitch + offset(), -90.0, 90.0);
        double targetYaw = yaw + offset();
        blockMode = false;
        blockTrack = false;
        beginMove(p, targetPitch, targetYaw);
    }

    /**
     * /angle block|lever [x y z] [keep|once]
     * keep (Standard): Ziel wird waehrend der Drehung laufend nachgefuehrt (Spieler darf sich bewegen).
     * once: Blickrichtung wird nur beim Ausfuehren berechnet, auch wenn der Block out of range ist; kein Nachjustieren.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> blockTree(String name, boolean lever) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal(name)
            .executes(ctx -> lookedCommand(ctx, lever, true))
            .then(ClientCommands.literal("keep").executes(ctx -> lookedCommand(ctx, lever, true)))
            .then(ClientCommands.literal("once").executes(ctx -> lookedCommand(ctx, lever, false)))
            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                    .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> blockCommand(ctx, lever, true))
                        .then(ClientCommands.literal("keep").executes(ctx -> blockCommand(ctx, lever, true)))
                        .then(ClientCommands.literal("once").executes(ctx -> blockCommand(ctx, lever, false))))));
        if (!lever) root.then(blockTree("lever", true));   // /angle block lever ...
        return root;
    }

    private static int blockCommand(CommandContext<FabricClientCommandSource> ctx, boolean lever, boolean keep) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        startBlock(x, y, z, lever, keep, m -> ctx.getSource().sendFeedback(Component.literal(m)));
        return 1;
    }

    /** Nimmt den Block, auf den das Fadenkreuz gerade zeigt. */
    private static int lookedCommand(CommandContext<FabricClientCommandSource> ctx, boolean lever, boolean keep) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            startBlock(pos.getX(), pos.getY(), pos.getZ(), lever, keep, m -> ctx.getSource().sendFeedback(Component.literal(m)));
        } else {
            ctx.getSource().sendFeedback(Component.literal("Du schaust gerade keinen Block an."));
        }
        return 1;
    }

    /** Hitbox des Zielblocks in Weltkoordinaten. Hebel: echte Hebel-Form (Boden/Wand/Decke), sonst Standardbox. */
    private static AABB hitboxOf(Minecraft mc, int x, int y, int z, boolean lever, Consumer<String> msg) {
        if (!lever) return new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0);
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = mc.level.getBlockState(pos);
        if (st.is(Blocks.LEVER)) {
            return st.getShape(mc.level, pos).bounds().move(x, y, z);
        }
        msg.accept("Hinweis: An " + x + " " + y + " " + z + " ist (client-seitig) kein Hebel geladen - nutze eine Standard-Hebelbox in der Blockmitte.");
        return new AABB(x + 5 / 16.0, y + 4 / 16.0, z + 5 / 16.0, x + 11 / 16.0, y + 12 / 16.0, z + 11 / 16.0);
    }

    private static void startBlock(int x, int y, int z, boolean lever, boolean keep, Consumer<String> msg) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (onCooldown()) return;

        AABB box = hitboxOf(mc, x, y, z, lever, msg);
        Vec3 point = pickPoint(box);
        Vec3 eye = p.getEyePosition();
        if (keep && box.distanceToSqr(eye) > BLOCK_REACH * BLOCK_REACH) {
            msg.accept("Warnung: Ziel ist weiter als " + BLOCK_REACH + " Bloecke entfernt (ausser Reichweite).");
        }
        markAccepted();

        targetBox = box;
        targetPoint = point;
        double[] a = anglesTo(eye, point);
        beginMove(p, Mth.clamp(a[0], -90.0, 90.0), a[1]);
        blockMode = true;
        blockTrack = keep;
    }

    /** Zufaelliger Punkt in der Box: gaussverteilt um die Mitte, aber nie naeher als EDGE_MARGIN am Rand. */
    private static Vec3 pickPoint(AABB b) {
        return new Vec3(pick(b.minX, b.maxX), pick(b.minY, b.maxY), pick(b.minZ, b.maxZ));
    }

    private static double pick(double min, double max) {
        double size = max - min;
        double v = (min + max) / 2.0 + RNG.nextGaussian() * size * BLOCK_SPREAD;
        double m = size * EDGE_MARGIN;
        return Mth.clamp(v, min + m, max - m);
    }

    /** {pitch, yaw} in Grad, um vom Auge zum Punkt zu schauen (yaw normalisiert). */
    private static double[] anglesTo(Vec3 eye, Vec3 t) {
        double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new double[] { pitch, yaw };
    }

    /** Aktuelles Ziel {pitch, yaw}; yaw als Rohwert nahe an der aktuellen Kamera (kein Sprung bei +-180). */
    private static double[] currentTarget(LocalPlayer p) {
        double[] a = anglesTo(p.getEyePosition(), targetPoint);
        double ref = lastSetYaw;
        return new double[] { Mth.clamp(a[0], -90.0, 90.0), ref + Mth.wrapDegrees(a[1] - ref) };
    }

    /** Trifft ein Blick mit (pitch, yaw) von eye aus die Hitbox? */
    private static boolean hits(Vec3 eye, double pitch, double yaw) {
        if (targetBox.contains(eye)) return true;
        double pr = Math.toRadians(pitch), yr = Math.toRadians(yaw);
        Vec3 dir = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        return targetBox.clip(eye, eye.add(dir.scale(16.0))).isPresent();
    }

    private static void beginMove(LocalPlayer p, double targetPitch, double targetYaw) {
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
        // In den ersten 0.3 s nach dem Start zaehlt Maus-Input nicht (die Bewegung laeuft ungestoert weiter).
        if (System.nanoTime() - lastAcceptedNanos < COOLDOWN_NANOS) {
            inputAccum = 0;
        } else {
            double devPitch = p.getXRot() - lastSetPitch;
            double devYaw = Mth.wrapDegrees(p.getYRot() - lastSetYaw);
            inputAccum += Math.hypot(devPitch, devYaw);
            if (inputAccum > INPUT_CANCEL_DEG) {
                active = false;
                settling = false;
                return;
            }
        }

        if (settling) {
            if (blockMode && blockTrack) {
                double[] tg = currentTarget(p);
                settleBasePitch = tg[0];
                settleBaseYaw = tg[1];
            }
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

        if (blockMode && blockTrack) {
            // Spieler laeuft evtl. waehrend der Drehung: Ziel (Punkt in der Hitbox) laufend neu berechnen
            double[] tg = currentTarget(p);
            dPitch = tg[0] - startPitch;
            dYaw = tg[1] - startYaw;
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

        if (t >= 1.0) beginSettle(p);
    }

    /** Kurzes, minimales Weiterdriften in grob gleicher Richtung, dann Stillstand. */
    private static void beginSettle(LocalPlayer p) {
        settleBasePitch = startPitch + dPitch;
        settleBaseYaw = startYaw + dYaw;
        settleDYaw = 0;
        settleDPitch = 0;
        for (int i = 0; i < 12; i++) {
            double heading = RNG.nextDouble() * 2 * Math.PI;   // in deiner Aufnahme keine Vorzugsrichtung
            double mag = SETTLE_MIN_DEG + RNG.nextDouble() * (SETTLE_MAX_DEG - SETTLE_MIN_DEG);
            double dy = Math.cos(heading) * mag, dp = Math.sin(heading) * mag;
            // Block-Modus: der Endpunkt des Driftens muss noch in der Hitbox liegen, sonst neu wuerfeln
            if (!blockMode || hits(p.getEyePosition(), settleBasePitch + dp, settleBaseYaw + dy)) {
                settleDYaw = dy;
                settleDPitch = dp;
                break;
            }
        }
        settleMs = SETTLE_MIN_MS + RNG.nextDouble() * (SETTLE_MAX_MS - SETTLE_MIN_MS);
        settleStartNanos = System.nanoTime();
        settling = true;
    }
}
