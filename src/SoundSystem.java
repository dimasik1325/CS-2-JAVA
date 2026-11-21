import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Процедурный звуковой движок на стандартном javax.sound.sampled.
 * Генерирует звуки выстрелов AK-47, Glock-18, взмаха ножа, перезарядки,
 * хитмаркера, попадания в голову и окончания раунда без внешних аудиофайлов.
 */
public class SoundSystem {
    private static final float SAMPLE_RATE = 22050.0f;
    private static final int MAX_CONCURRENT_SOUNDS = 6;

    private final AudioFormat format;
    private final ExecutorService audioPool;
    private final AtomicInteger activeSounds = new AtomicInteger(0);
    private volatile boolean audioAvailable = true;

    private final byte[] sfxAkShot;
    private final byte[] sfxGlockShot;
    private final byte[] sfxBotShot;
    private final byte[] sfxKnifeSlash;
    private final byte[] sfxReload;
    private final byte[] sfxEmptyClick;
    private final byte[] sfxHitMarker;
    private final byte[] sfxHeadshot;
    private final byte[] sfxPlayerHurt;
    private final byte[] sfxRoundWin;
    private final byte[] sfxRoundLose;

    public SoundSystem(boolean enabled) {
        this.format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        this.audioAvailable = enabled && probeAudioDevice();

        this.sfxAkShot = buildGunshot(0.21, 145.0, 42.0, 0.92, 1337L);
        this.sfxGlockShot = buildGunshot(0.14, 240.0, 75.0, 0.72, 2026L);
        this.sfxBotShot = buildGunshot(0.17, 165.0, 52.0, 0.45, 7777L);
        this.sfxKnifeSlash = buildKnifeSlash();
        this.sfxReload = buildReloadSound();
        this.sfxEmptyClick = buildClickSound();
        this.sfxHitMarker = buildHitSound(false);
        this.sfxHeadshot = buildHitSound(true);
        this.sfxPlayerHurt = buildHurtSound();
        this.sfxRoundWin = buildFanfare(true);
        this.sfxRoundLose = buildFanfare(false);

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

    public void playAkShot() { playAsync(sfxAkShot, 1.0); }
    public void playGlockShot() { playAsync(sfxGlockShot, 0.95); }
    public void playBotShot(double distance) {
        double atten = Math.max(0.12, Math.min(0.85, 1.0 - distance / 52.0));
        playAsync(sfxBotShot, atten);
    }
    public void playKnifeSlash() { playAsync(sfxKnifeSlash, 0.85); }
    public void playReload() { playAsync(sfxReload, 0.85); }
    public void playEmptyClick() { playAsync(sfxEmptyClick, 0.75); }
    public void playHitMarker(boolean headshot) {
        playAsync(headshot ? sfxHeadshot : sfxHitMarker, 0.95);
    }
    public void playPlayerHurt() { playAsync(sfxPlayerHurt, 0.9); }
    public void playRoundWin() { playAsync(sfxRoundWin, 0.85); }
    public void playRoundLose() { playAsync(sfxRoundLose, 0.85); }

    private void playAsync(final byte[] pcmData, final double volume) {
        if (!audioAvailable || audioPool == null || pcmData == null) {
            return;
        }
        if (activeSounds.get() >= MAX_CONCURRENT_SOUNDS) {
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
                byte[] scaled = scalePcm(pcmData, volume);
                line.write(scaled, 0, scaled.length);
                line.drain();
            } catch (Throwable ignored) {
                audioAvailable = false;
            } finally {
                if (line != null) {
                    try {
                        line.close();
                    } catch (Throwable ignored) {
                    }
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
        if (Math.abs(gain - 1.0) < 0.02) {
            return src;
        }
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
            double env = Math.pow(1.0 - t, 2.4);
            double transientBurst = (i < SAMPLE_RATE * 0.012) ? 1.35 : 1.0;
            double freq = startFreq + (endFreq - startFreq) * Math.pow(t, 0.55);
            phase += (2.0 * Math.PI * freq) / SAMPLE_RATE;

            double noise = rng.nextDouble() * 2.0 - 1.0;
            lowPass = lowPass * 0.62 + noise * 0.38;
            double punch = Math.sin(phase) * 0.62 + lowPass * 0.85 + noise * 0.35 * (1.0 - t);
            buf[i] = Math.tanh(punch * 1.6 * intensity * transientBurst) * env;
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
            // Щелчок извлечения магазина + щелчок затвора
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

    private static byte[] buildClickSound() {
        int count = (int) (SAMPLE_RATE * 0.05);
        double[] buf = new double[count];
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            buf[i] = Math.sin(2.0 * Math.PI * 1100.0 * (i / SAMPLE_RATE)) * Math.pow(1.0 - t, 3.0) * 0.6;
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
                // Металлический "динк" попадания в шлем из CS
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
}
