import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Расширенный процедурный звуковой движок CS-2 JAVA 2.0 (javax.sound.sampled).
 * Синтезирует звуки всех видов оружия (AK-47, M4A1-S с глушителем, AWP, XM1014,
 * Glock-18, Desert Eagle, Нож), взрывов гранат, дыма, шагов по песку, звона гильз и UI.
 */
public class SoundSystem {
    private static final float SAMPLE_RATE = 22050.0f;
    private static final int MAX_CONCURRENT_SOUNDS = 12;

    private final AudioFormat format;
    private final ExecutorService audioPool;
    private final AtomicInteger activeSounds = new AtomicInteger(0);
    private volatile boolean audioAvailable;
    private volatile double masterVolume = 0.85;

    private final byte[] sfxAkShot;
    private final byte[] sfxM4Silenced;
    private final byte[] sfxAwpBoom;
    private final byte[] sfxShotgun;
    private final byte[] sfxMp9Shot;
    private final byte[] sfxGlockShot;
    private final byte[] sfxDeagleShot;
    private final byte[] sfxBotShot;
    private final byte[] sfxKnifeSlash;
    private final byte[] sfxReload;
    private final byte[] sfxEmptyClick;
    private final byte[] sfxHitMarker;
    private final byte[] sfxHeadshot;
    private final byte[] sfxPlayerHurt;
    private final byte[] sfxExplosion;
    private final byte[] sfxSmokeHiss;
    private final byte[] sfxFlashbang;
    private final byte[] sfxMolotov;
    private final byte[] sfxC4Beep;
    private final byte[] sfxC4Urgent;
    private final byte[] sfxC4Keypad;
    private final byte[] sfxFootstep;
    private final byte[] sfxShellClink;
    private final byte[] sfxBuyCash;
    private final byte[] sfxUiClick;
    private final byte[] sfxRoundWin;
    private final byte[] sfxRoundLose;
    private final byte[] sfxZeusZap;
    private final byte[] sfxSprayCan;
    private final byte[] sfxRadioChirp;
    private final byte[] sfxLevelUp;
    private final byte[] sfxM249Shot;
    private final byte[] sfxRpgLaunch;
    private final byte[] sfxTurretShot;

    public SoundSystem(boolean enabled) {
        this.format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        this.audioAvailable = enabled && probeAudioDevice();

        this.sfxAkShot      = buildGunshot(0.22, 148.0, 40.0, 0.94, 1337L);
        this.sfxM4Silenced  = buildSilencedShot();
        this.sfxAwpBoom     = buildGunshot(0.46, 98.0,  24.0, 1.35, 9999L);
        this.sfxShotgun     = buildGunshot(0.28, 122.0, 34.0, 1.15, 4242L);
        this.sfxMp9Shot     = buildGunshot(0.11, 290.0, 92.0, 0.68, 3131L);
        this.sfxGlockShot   = buildGunshot(0.14, 245.0, 76.0, 0.72, 2026L);
        this.sfxDeagleShot  = buildGunshot(0.29, 132.0, 36.0, 1.12, 7171L);
        this.sfxBotShot     = buildGunshot(0.17, 165.0, 52.0, 0.48, 7777L);
        this.sfxKnifeSlash  = buildKnifeSlash();
        this.sfxReload      = buildReloadSound();
        this.sfxEmptyClick  = buildToneClick(1100.0, 0.045, 0.55);
        this.sfxHitMarker   = buildHitSound(false);
        this.sfxHeadshot    = buildHitSound(true);
        this.sfxPlayerHurt  = buildHurtSound();
        this.sfxExplosion   = buildExplosionSound();
        this.sfxSmokeHiss   = buildSmokeSound();
        this.sfxFlashbang   = buildFlashbangSound();
        this.sfxMolotov     = buildMolotovSound();
        this.sfxC4Beep      = buildToneClick(2680.0, 0.075, 0.72);
        this.sfxC4Urgent    = buildToneClick(3180.0, 0.060, 0.85);
        this.sfxC4Keypad    = buildKeypadSound();
        this.sfxFootstep    = buildFootstepSound();
        this.sfxShellClink  = buildShellClink();
        this.sfxBuyCash     = buildBuySound();
        this.sfxUiClick     = buildToneClick(1560.0, 0.032, 0.40);
        this.sfxRoundWin    = buildFanfare(true);
        this.sfxRoundLose   = buildFanfare(false);
        this.sfxZeusZap     = buildZeusZapSound();
        this.sfxSprayCan    = buildSprayCanSound();
        this.sfxRadioChirp  = buildRadioChirpSound();
        this.sfxLevelUp     = buildLevelUpSound();
        this.sfxM249Shot    = buildGunshot(0.18, 162.0, 44.0, 1.05, 8888L);
        this.sfxRpgLaunch   = buildRpgLaunchSound();
        this.sfxTurretShot  = buildGunshot(0.09, 340.0, 110.0, 0.56, 6161L);

        if (this.audioAvailable) {
            this.audioPool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "CS2-Audio");
                t.setDaemon(true);
                return t;
            });
        } else {
            this.audioPool = null;
        }
    }

    private boolean probeAudioDevice() {
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            return AudioSystem.isLineSupported(info);
        } catch (Throwable t) {
            return false;
        }
    }

    public void setMasterVolume(double vol) {
        this.masterVolume = Math.max(0.0, Math.min(1.0, vol));
    }

    public void playWeaponShot(Weapon.Type type) {
        switch (type) {
            case AK47:   playAsync(sfxAkShot, 1.0); break;
            case M4A1S:  playAsync(sfxM4Silenced, 0.90); break;
            case AWP:    playAsync(sfxAwpBoom, 1.0); break;
            case M249:   playAsync(sfxM249Shot, 0.96); break;
            case RPG7:   playAsync(sfxRpgLaunch, 1.0); break;
            case XM1014: playAsync(sfxShotgun, 0.98); break;
            case MP9:    playAsync(sfxMp9Shot, 0.86); break;
            case GLOCK:  playAsync(sfxGlockShot, 0.90); break;
            case DEAGLE: playAsync(sfxDeagleShot, 1.0); break;
            case ZEUS:   playAsync(sfxZeusZap, 1.0); break;
            case KNIFE:  playAsync(sfxKnifeSlash, 0.85); break;
        }
    }

    public void playTurretShot(double distance) {
        double atten = Math.max(0.12, Math.min(0.80, 1.0 - distance / 52.0));
        playAsync(sfxTurretShot, atten);
    }

    public void playBotShot(double distance) {
        double atten = Math.max(0.10, Math.min(0.85, 1.0 - distance / 56.0));
        playAsync(sfxBotShot, atten);
    }

    public void playReload() { playAsync(sfxReload, 0.85); }
    public void playEmptyClick() { playAsync(sfxEmptyClick, 0.72); }
    public void playHitMarker(boolean headshot) { playAsync(headshot ? sfxHeadshot : sfxHitMarker, 0.95); }
    public void playPlayerHurt() { playAsync(sfxPlayerHurt, 0.90); }
    public void playExplosion(double distance) {
        double atten = Math.max(0.22, Math.min(1.0, 1.15 - distance / 48.0));
        playAsync(sfxExplosion, atten);
    }
    public void playSmokeBloom() { playAsync(sfxSmokeHiss, 0.78); }
    public void playFlashbang(double distance) {
        double atten = Math.max(0.25, Math.min(1.0, 1.15 - distance / 46.0));
        playAsync(sfxFlashbang, atten);
    }
    public void playMolotovShatter() { playAsync(sfxMolotov, 0.90); }
    public void playC4Beep(boolean urgent, double distance) {
        double atten = Math.max(0.18, Math.min(0.92, 1.05 - distance / 52.0));
        playAsync(urgent ? sfxC4Urgent : sfxC4Beep, atten);
    }
    public void playC4Keypad() { playAsync(sfxC4Keypad, 0.85); }
    public void playSprayCan() { playAsync(sfxSprayCan, 0.84); }
    public void playRadioBeep() { playAsync(sfxRadioChirp, 0.80); }
    public void playLevelUp() { playAsync(sfxLevelUp, 0.92); }
    public void playFootstep() { playAsync(sfxFootstep, 0.36); }
    public void playShellClink() { playAsync(sfxShellClink, 0.28); }
    public void playBuy() { playAsyncPriority(sfxBuyCash, 0.80); }
    public void playUiClick() { playAsyncPriority(sfxUiClick, 0.65); }
    public void playRoundWin() { playAsyncPriority(sfxRoundWin, 0.85); }
    public void playRoundLose() { playAsyncPriority(sfxRoundLose, 0.85); }

    private void playAsync(final byte[] pcmData, final double baseGain) {
        playInternal(pcmData, baseGain, false);
    }

    private void playAsyncPriority(final byte[] pcmData, final double baseGain) {
        playInternal(pcmData, baseGain, true);
    }

    private void playInternal(final byte[] pcmData, final double baseGain, final boolean priority) {
        final double effectiveGain = baseGain * masterVolume;
        if (!audioAvailable || audioPool == null || pcmData == null || effectiveGain <= 0.01) {
            return;
        }
        int cap = priority ? (MAX_CONCURRENT_SOUNDS + 4) : MAX_CONCURRENT_SOUNDS;
        if (activeSounds.get() >= cap) {
            return;
        }
        activeSounds.incrementAndGet();
        audioPool.execute(() -> {
            SourceDataLine line = null;
            try {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format, 4096);
                line.start();
                byte[] scaled = scalePcm(pcmData, effectiveGain);
                line.write(scaled, 0, scaled.length);
                line.drain();
            } catch (Throwable ignored) {
                // Отдельная занятая аудиолиния на Windows не должна отключать весь звук игры
            } finally {
                if (line != null) {
                    try { line.close(); } catch (Throwable ignored) {}
                }
                activeSounds.decrementAndGet();
            }
        });
    }

    public void shutdown() {
        audioAvailable = false;
        if (audioPool != null) {
            audioPool.shutdownNow();
        }
    }

    private static byte[] scalePcm(byte[] src, double gain) {
        if (Math.abs(gain - 1.0) < 0.02) return src;
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length - 1; i += 2) {
            short sample = (short) ((src[i] & 0xFF) | (src[i + 1] << 8));
            int v = (int) (sample * gain);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[i] = (byte) (v & 0xFF);
            out[i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    private static byte[] toPcmBytes(double[] samples) {
        byte[] data = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            double clamped = Math.max(-1.0, Math.min(1.0, samples[i]));
            short s = (short) (clamped * 32000.0);
            data[i * 2] = (byte) (s & 0xFF);
            data[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return data;
    }

    private static byte[] buildGunshot(double durationSec, double startFreq, double endFreq,
                                        double intensity, long seed) {
        int count = (int) (SAMPLE_RATE * durationSec);
        double[] buf = new double[count];
        Random rng = new Random(seed);
        double phase = 0.0;
        double lowPass = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double env = Math.pow(1.0 - t, 2.3);
            double transientBurst = (i < SAMPLE_RATE * 0.014) ? 1.4 : 1.0;
            double freq = startFreq + (endFreq - startFreq) * Math.pow(t, 0.52);
            phase += (2.0 * Math.PI * freq) / SAMPLE_RATE;

            double noise = rng.nextDouble() * 2.0 - 1.0;
            lowPass = lowPass * 0.65 + noise * 0.35;
            double punch = Math.sin(phase) * 0.64 + lowPass * 0.85 + noise * 0.32 * (1.0 - t);
            buf[i] = Math.tanh(punch * 1.65 * intensity * transientBurst) * env;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildSilencedShot() {
        int count = (int) (SAMPLE_RATE * 0.11);
        double[] buf = new double[count];
        Random rng = new Random(31337L);
        double lp = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double env = Math.pow(1.0 - t, 2.8);
            double sec = i / SAMPLE_RATE;
            double n = rng.nextDouble() * 2.0 - 1.0;
            lp = lp * 0.58 + n * 0.42;
            double sub = Math.sin(2.0 * Math.PI * (290.0 - 160.0 * t) * sec) * 0.65;
            buf[i] = (sub + lp * 0.60) * env * 0.85;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildExplosionSound() {
        int count = (int) (SAMPLE_RATE * 0.78);
        double[] buf = new double[count];
        Random rng = new Random(8080L);
        double rumble = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double env = Math.pow(1.0 - t, 1.75);
            double n = rng.nextDouble() * 2.0 - 1.0;
            rumble = rumble * 0.84 + n * 0.16;
            double boom = Math.sin(2.0 * Math.PI * (68.0 - 38.0 * t) * sec) * 0.85;
            buf[i] = Math.tanh((boom + rumble * 1.45 + n * 0.25 * (1.0 - t)) * 1.9) * env;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildSmokeSound() {
        int count = (int) (SAMPLE_RATE * 0.48);
        double[] buf = new double[count];
        Random rng = new Random(6161L);
        double bp = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double env = Math.sin(Math.PI * Math.min(1.0, t * 3.5)) * (1.0 - t * 0.75);
            double n = rng.nextDouble() * 2.0 - 1.0;
            bp = bp * 0.35 + n * 0.65;
            buf[i] = bp * env * 0.60;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildFootstepSound() {
        int count = (int) (SAMPLE_RATE * 0.075);
        double[] buf = new double[count];
        Random rng = new Random(1212L);
        double lp = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double env = Math.sin(Math.PI * t) * Math.pow(1.0 - t, 1.2);
            double n = rng.nextDouble() * 2.0 - 1.0;
            lp = lp * 0.62 + n * 0.38;
            buf[i] = (lp * 0.85 + Math.sin(2.0 * Math.PI * 115.0 * (i / SAMPLE_RATE)) * 0.35) * env * 0.75;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildShellClink() {
        int count = (int) (SAMPLE_RATE * 0.065);
        double[] buf = new double[count];
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double env = Math.pow(1.0 - t, 2.8);
            buf[i] = (Math.sin(2.0 * Math.PI * 3420.0 * sec) * 0.55
                    + Math.sin(2.0 * Math.PI * 4890.0 * sec) * 0.45) * env * 0.65;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildBuySound() {
        int count = (int) (SAMPLE_RATE * 0.18);
        double[] buf = new double[count];
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double f = (t < 0.42) ? 987.77 : 1318.51;
            double env = Math.pow(1.0 - t, 1.5);
            buf[i] = (Math.sin(2.0 * Math.PI * f * sec) * 0.65
                    + Math.sin(2.0 * Math.PI * f * 2.0 * sec) * 0.25) * env * 0.65;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildKnifeSlash() {
        int count = (int) (SAMPLE_RATE * 0.15);
        double[] buf = new double[count];
        Random rng = new Random(901L);
        double band = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double env = Math.sin(Math.PI * t) * (1.0 - 0.35 * t);
            double n = rng.nextDouble() * 2.0 - 1.0;
            band = band * 0.45 + n * 0.55;
            double whistle = Math.sin(2.0 * Math.PI * (720.0 - 380.0 * t) * (i / SAMPLE_RATE)) * 0.25;
            buf[i] = (band * 0.55 + whistle) * env * 0.85;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildReloadSound() {
        int count = (int) (SAMPLE_RATE * 0.42);
        double[] buf = new double[count];
        Random rng = new Random(404L);
        for (int i = 0; i < count; i++) {
            double sec = i / SAMPLE_RATE;
            double v = 0.0;
            if (sec >= 0.02 && sec <= 0.08) {
                double local = (sec - 0.02) / 0.06;
                v += (Math.sin(2.0 * Math.PI * 520.0 * sec) * 0.5 + (rng.nextDouble() - 0.5)) * (1.0 - local);
            }
            if (sec >= 0.24 && sec <= 0.34) {
                double local = (sec - 0.24) / 0.10;
                v += (Math.sin(2.0 * Math.PI * 780.0 * sec) * 0.55 + (rng.nextDouble() - 0.5) * 1.1) * (1.0 - local);
            }
            buf[i] = v * 0.65;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildToneClick(double freq, double dur, double amp) {
        int count = (int) (SAMPLE_RATE * dur);
        double[] buf = new double[count];
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            buf[i] = Math.sin(2.0 * Math.PI * freq * (i / SAMPLE_RATE)) * Math.pow(1.0 - t, 3.0) * amp;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildHitSound(boolean headshot) {
        double dur = headshot ? 0.14 : 0.075;
        int count = (int) (SAMPLE_RATE * dur);
        double[] buf = new double[count];
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double env = Math.pow(1.0 - t, 2.2);
            if (headshot) {
                buf[i] = (Math.sin(2.0 * Math.PI * 1880.0 * sec) * 0.6
                        + Math.sin(2.0 * Math.PI * 2670.0 * sec) * 0.4) * env * 0.85;
            } else {
                buf[i] = Math.sin(2.0 * Math.PI * 1250.0 * sec) * env * 0.65;
            }
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildHurtSound() {
        int count = (int) (SAMPLE_RATE * 0.16);
        double[] buf = new double[count];
        Random rng = new Random(555L);
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double env = Math.pow(1.0 - t, 1.9);
            buf[i] = (Math.sin(2.0 * Math.PI * (125.0 - 45.0 * t) * sec) * 0.75
                    + (rng.nextDouble() * 2.0 - 1.0) * 0.25) * env * 0.85;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildFanfare(boolean win) {
        int count = (int) (SAMPLE_RATE * 0.65);
        double[] buf = new double[count];
        double[] notes = win
                ? new double[]{440.0, 554.37, 659.25}
                : new double[]{329.63, 293.66, 220.0};
        for (int i = 0; i < count; i++) {
            double sec = i / SAMPLE_RATE;
            int step = Math.min(2, (int) ((i * 3.0) / count));
            double localT = ((i * 3.0) / count) - step;
            double env = Math.pow(1.0 - localT * 0.75, 1.5);
            double f = notes[step];
            buf[i] = (Math.sin(2.0 * Math.PI * f * sec) * 0.55
                    + Math.sin(2.0 * Math.PI * f * 2.0 * sec) * 0.2) * env * 0.55;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildFlashbangSound() {
        int count = (int) (SAMPLE_RATE * 0.62);
        double[] buf = new double[count];
        Random rng = new Random(1919L);
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double snap = Math.pow(Math.max(0.0, 1.0 - t * 6.5), 2.2) * (rng.nextDouble() * 2.0 - 1.0);
            double ring = Math.sin(2.0 * Math.PI * 3650.0 * sec) * Math.pow(1.0 - t, 1.35) * 0.36;
            buf[i] = snap * 0.92 + ring;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildMolotovSound() {
        int count = (int) (SAMPLE_RATE * 0.52);
        double[] buf = new double[count];
        Random rng = new Random(2929L);
        double lowRoar = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double glass = (t < 0.16)
                    ? Math.sin(2.0 * Math.PI * (3400.0 + (i % 7) * 410.0) * sec) * (1.0 - t / 0.16) * 0.55
                    : 0.0;
            double white = rng.nextDouble() * 2.0 - 1.0;
            lowRoar = lowRoar * 0.88 + white * 0.12;
            double flameEnv = Math.sin(Math.min(1.0, t * 1.4) * Math.PI);
            buf[i] = glass + lowRoar * flameEnv * 1.35;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildKeypadSound() {
        int count = (int) (SAMPLE_RATE * 0.28);
        double[] buf = new double[count];
        double[] freqs = {1950.0, 2340.0, 2920.0};
        for (int i = 0; i < count; i++) {
            int step = Math.min(2, (i * 3) / count);
            double sec = i / SAMPLE_RATE;
            double local = ((i * 3.0) / count) - step;
            double env = Math.max(0.0, 1.0 - local * 1.15);
            buf[i] = Math.sin(2.0 * Math.PI * freqs[step] * sec) * env * 0.58;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildZeusZapSound() {
        int count = (int) (SAMPLE_RATE * 0.34);
        double[] buf = new double[count];
        Random rng = new Random(8080L);
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double env = Math.pow(1.0 - t, 1.45);
            double buzz = Math.signum(Math.sin(2.0 * Math.PI * 135.0 * sec)) * 0.38;
            double crackle = (rng.nextDouble() > 0.35 ? (rng.nextDouble() * 2.0 - 1.0) : 0.0) * 0.65;
            double whine = Math.sin(2.0 * Math.PI * (2200.0 - 1100.0 * t) * sec) * 0.32;
            buf[i] = (buzz + crackle + whine) * env;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildSprayCanSound() {
        int count = (int) (SAMPLE_RATE * 0.26);
        double[] buf = new double[count];
        Random rng = new Random(5150L);
        double prev = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double white = rng.nextDouble() * 2.0 - 1.0;
            double hp = white - prev * 0.68;
            prev = white;
            double env = Math.sin(Math.min(1.0, t * 4.5) * Math.PI * 0.5) * Math.pow(1.0 - t, 0.55);
            buf[i] = hp * env * 0.52;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildRadioChirpSound() {
        int count = (int) (SAMPLE_RATE * 0.16);
        double[] buf = new double[count];
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double freq = (t < 0.45) ? 1680.0 : 2180.0;
            double env = Math.sin(t * Math.PI);
            buf[i] = Math.sin(2.0 * Math.PI * freq * sec) * env * 0.48;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildLevelUpSound() {
        int count = (int) (SAMPLE_RATE * 0.36);
        double[] buf = new double[count];
        double[] notes = {587.33, 739.99, 880.0, 1174.66};
        for (int i = 0; i < count; i++) {
            int idx = Math.min(3, (i * 4) / count);
            double sec = i / SAMPLE_RATE;
            double local = ((i * 4.0) / count) - idx;
            double env = Math.pow(Math.max(0.0, 1.0 - local * 0.72), 1.2);
            buf[i] = Math.sin(2.0 * Math.PI * notes[idx] * sec) * env * 0.55;
        }
        return toPcmBytes(buf);
    }

    private static byte[] buildRpgLaunchSound() {
        int count = (int) (SAMPLE_RATE * 0.52);
        double[] buf = new double[count];
        Random rng = new Random(9090L);
        double lp = 0.0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double sec = i / SAMPLE_RATE;
            double noise = rng.nextDouble() * 2.0 - 1.0;
            lp = lp * 0.72 + noise * 0.28;
            double roar = Math.sin(2.0 * Math.PI * (95.0 + 180.0 * t) * sec) * 0.42;
            double env = Math.sin(Math.min(1.0, t * 8.0) * Math.PI * 0.5) * Math.pow(1.0 - t, 0.85);
            buf[i] = Math.tanh((lp * 1.35 + roar) * 1.45) * env;
        }
        return toPcmBytes(buf);
    }
}
