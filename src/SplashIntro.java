import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CS-2 JAVA 10.0 — кинематографическая заставка «ИГРА СДЕЛАНА АЛКАШОМ».
 *
 * Хронометраж (сек):
 *   0.0  нарастающий гул, угольки поднимаются из темноты, анаморфный блик-полоса раскрывается от центра
 *   0.9  «ИГРА СДЕЛАНА» печатается по буквам с глитчем и щелчками
 *   1.75 бутылка, вращаясь, падает в центр кадра со шлейфом
 *   2.55 УДАР: бутылка разбивается — осколки стекла, пена, искры, ударная волна, белая вспышка, тряска камеры,
 *        «АЛКАШОМ» влетает с упругим перелётом
 *   2.6+ вращающиеся золотые лучи, блики по буквам, RGB-глитч срезами, зерно плёнки и сканлайны
 *   3.3  «ПРЕДСТАВЛЯЕТ» раскрывается из золотой линии
 *   4.2  логотип CS-2 JAVA 10.0
 *   5.9  наезд камеры и уход в белое → главное меню
 */
public final class SplashIntro {
    public static final double IMPACT_T = 2.55;
    public static final double FADE_START_T = 5.9;
    public static final double END_T = 6.6;

    private static final String LINE1 = "ИГРА СДЕЛАНА";
    private static final String LINE2 = "АЛКАШОМ";
    private static final String LINE3 = "П Р Е Д С Т А В Л Я Е Т";

    private final SoundSystem sound;
    private final Random rng = new Random(20261121L);
    private final List<P> particles = new ArrayList<>();
    private final List<double[]> rings = new ArrayList<>(); // {x, y, age, speed, width}

    private double t = 0.0;
    private double lastT = 0.0;
    private int typedLetters = 0;
    private boolean riserPlayed, impactDone, subtitleWhoosh, logoWhoosh, fadeWhoosh;
    private double shake = 0.0;
    private double emberAcc = 0.0;
    private boolean finished = false;

    private BufferedImage frame;
    private BufferedImage atmo;
    private BufferedImage zoomBuf;          // мягкие слои (фон, лучи, свечения, дым) в 1/4 разрешения
    private short[] vigScan;             // предрасчёт: виньетка × сканлайны (0..256) на пиксель
    private byte[] noise;                // плёночное зерно
    private BufferedImage glowImg;
    private int glowW = -1;
    private int cachedW = -1, cachedH = -1;
    private Shape titleShape;           // контур «АЛКАШОМ» в координатах (0,0) = центр
    private Font line1Font, line3Font, logoFont, hintFont, tagFont;

    /** Частица заставки: уголёк / искра / осколок стекла / пена / дым. */
    private static final class P {
        int kind; // 0 ember, 1 spark, 2 glass, 3 foam, 4 smoke
        double x, y, vx, vy, life, max, size, rot, vrot;
        int rgb;
        double[] shapeXs, shapeYs;
    }

    public SplashIntro(SoundSystem sound) {
        this.sound = sound;
    }

    public double getTime() { return t; }
    public boolean isFinished() { return finished; }

    /** Пропуск по клавише/клику: сразу уходим в финальный наезд и белую вспышку. */
    public void skip() {
        if (t < 0.35) return;
        if (t < FADE_START_T) {
            if (!impactDone) triggerImpact((cachedW > 0 ? cachedW : 1280) * 0.5, (cachedH > 0 ? cachedH : 720) * 0.53, false);
            t = FADE_START_T;
            lastT = t;
        }
    }

    /** Для самотеста: перемотать заставку в нужный момент со всеми событиями. */
    public void seekForTest(double target, int w, int h) {
        ensureResources(w, h);
        while (t < target - 1e-6) {
            update(Math.min(1.0 / 60.0, target - t));
        }
    }

    public void update(double dt) {
        if (finished) return;
        lastT = t;
        t += dt;
        int w = cachedW > 0 ? cachedW : 1280, h = cachedH > 0 ? cachedH : 720;

        if (!riserPlayed) { riserPlayed = true; sound.playIntroRiser(); }

        // Печать «ИГРА СДЕЛАНА»
        int shouldTyped = (int) Math.max(0, Math.min(LINE1.length(), Math.floor((t - 0.9) / 0.075) + 1));
        while (typedLetters < shouldTyped) {
            char c = LINE1.charAt(typedLetters);
            typedLetters++;
            if (c != ' ') {
                sound.playTypeTick();
                double lx = w * 0.5 + (typedLetters - LINE1.length() / 2.0 - 0.5) * w * 0.032;
                for (int i = 0; i < 6; i++) spawnSpark(lx, h * 0.33, 160, 0.35, 0x8FD8FF);
            }
        }

        if (!impactDone && t >= IMPACT_T) triggerImpact(w * 0.5, h * 0.53, true);
        if (!subtitleWhoosh && t >= 3.3) { subtitleWhoosh = true; sound.playWhoosh(); }
        if (!logoWhoosh && t >= 4.2) { logoWhoosh = true; sound.playTypeTick(); }
        if (!fadeWhoosh && t >= FADE_START_T) { fadeWhoosh = true; sound.playWhoosh(); }

        // Угольки поднимаются со дна кадра (после удара — гуще и ярче)
        double rate = t < IMPACT_T ? 26.0 : 48.0;
        emberAcc += dt * rate;
        while (emberAcc >= 1.0) {
            emberAcc -= 1.0;
            P p = new P();
            p.kind = 0;
            p.x = rng.nextDouble() * w;
            p.y = h + 10;
            p.vx = (rng.nextDouble() - 0.5) * 30;
            p.vy = -(40 + rng.nextDouble() * 110);
            p.max = p.life = 2.5 + rng.nextDouble() * 3.0;
            p.size = 1.2 + rng.nextDouble() * 2.6;
            p.rgb = rng.nextDouble() < 0.7 ? 0xFFA23A : 0xFFD978;
            p.rot = rng.nextDouble() * 6.28;
            particles.add(p);
        }

        for (int i = particles.size() - 1; i >= 0; i--) {
            P p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) { particles.remove(i); continue; }
            switch (p.kind) {
                case 0:
                    p.rot += dt * 2.0;
                    p.vx += Math.sin(p.rot) * 18 * dt;
                    break;
                case 1:
                    p.vy += 520 * dt;
                    p.vx *= (1.0 - 1.6 * dt);
                    break;
                case 2:
                    p.vy += 900 * dt;
                    p.vx *= (1.0 - 0.6 * dt);
                    p.rot += p.vrot * dt;
                    break;
                case 3:
                    p.vy += 700 * dt;
                    p.vx *= (1.0 - 1.2 * dt);
                    break;
                default:
                    p.vx *= (1.0 - 1.4 * dt);
                    p.vy *= (1.0 - 1.4 * dt);
                    p.size += dt * 60;
                    break;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }

        for (int i = rings.size() - 1; i >= 0; i--) {
            double[] r = rings.get(i);
            r[2] += dt;
            if (r[2] > 1.4) rings.remove(i);
        }
        shake = Math.max(0.0, shake - dt * 26.0);

        if (t >= END_T) finished = true;
    }

    private void triggerImpact(double cx, double cy, boolean withSound) {
        impactDone = true;
        if (withSound) {
            sound.playGlassShatter();
            sound.playIntroImpact();
        }
        shake = 22.0;
        rings.add(new double[]{cx, cy, 0.0, 1150.0, 10.0});
        rings.add(new double[]{cx, cy, -0.08, 760.0, 4.0});
        rings.add(new double[]{cx, cy, -0.18, 480.0, 2.0});
        // Осколки зелёного бутылочного стекла
        for (int i = 0; i < 70; i++) {
            P p = new P();
            p.kind = 2;
            double a = rng.nextDouble() * Math.PI * 2.0;
            double sp = 220 + rng.nextDouble() * 820;
            p.x = cx + (rng.nextDouble() - 0.5) * 20;
            p.y = cy + (rng.nextDouble() - 0.5) * 20;
            p.vx = Math.cos(a) * sp;
            p.vy = Math.sin(a) * sp - 260;
            p.max = p.life = 1.2 + rng.nextDouble() * 1.6;
            p.size = 5 + rng.nextDouble() * 15;
            p.rot = rng.nextDouble() * 6.28;
            p.vrot = (rng.nextDouble() - 0.5) * 22;
            p.rgb = rng.nextDouble() < 0.8 ? 0x3FBF6A : 0xA8F0C0;
            int n = 3 + rng.nextInt(2);
            p.shapeXs = new double[n];
            p.shapeYs = new double[n];
            for (int k = 0; k < n; k++) {
                double aa = k * Math.PI * 2.0 / n + rng.nextDouble() * 0.9;
                double rr = 0.4 + rng.nextDouble() * 0.6;
                p.shapeXs[k] = Math.cos(aa) * rr;
                p.shapeYs[k] = Math.sin(aa) * rr;
            }
            particles.add(p);
        }
        // Пена и брызги
        for (int i = 0; i < 46; i++) {
            P p = new P();
            p.kind = 3;
            double a = -Math.PI / 2 + (rng.nextDouble() - 0.5) * 2.6;
            double sp = 160 + rng.nextDouble() * 520;
            p.x = cx; p.y = cy;
            p.vx = Math.cos(a) * sp;
            p.vy = Math.sin(a) * sp;
            p.max = p.life = 0.8 + rng.nextDouble() * 1.0;
            p.size = 3 + rng.nextDouble() * 7;
            p.rgb = rng.nextDouble() < 0.55 ? 0xFFF4D8 : 0xF2B33D;
            particles.add(p);
        }
        // Золотые искры
        for (int i = 0; i < 140; i++) spawnSpark(cx, cy, 1100, 1.4, rng.nextDouble() < 0.6 ? 0xFFC444 : 0xFFF2B0);
        // Дым удара
        for (int i = 0; i < 18; i++) {
            P p = new P();
            p.kind = 4;
            double a = rng.nextDouble() * Math.PI * 2.0;
            double sp = 60 + rng.nextDouble() * 200;
            p.x = cx; p.y = cy;
            p.vx = Math.cos(a) * sp;
            p.vy = Math.sin(a) * sp * 0.5;
            p.max = p.life = 1.6 + rng.nextDouble() * 1.2;
            p.size = 30 + rng.nextDouble() * 40;
            p.rgb = 0x6A5A48;
            particles.add(p);
        }
    }

    private void spawnSpark(double x, double y, double speed, double life, int rgb) {
        P p = new P();
        p.kind = 1;
        double a = rng.nextDouble() * Math.PI * 2.0;
        double sp = speed * (0.25 + rng.nextDouble() * 0.75);
        p.x = x; p.y = y;
        p.vx = Math.cos(a) * sp;
        p.vy = Math.sin(a) * sp - speed * 0.2;
        p.max = p.life = life * (0.5 + rng.nextDouble() * 0.7);
        p.size = 1.2 + rng.nextDouble() * 2.0;
        p.rgb = rgb;
        particles.add(p);
    }

    // =====================================================================================
    //                                         РЕНДЕР
    // =====================================================================================

    private void ensureResources(int w, int h) {
        if (w == cachedW && h == cachedH && frame != null) return;
        cachedW = w;
        cachedH = h;
        frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        atmo = new BufferedImage(Math.max(16, w / 4), Math.max(9, h / 4), BufferedImage.TYPE_INT_RGB);
        vigScan = new short[w * h];
        double vcx = w * 0.5, vcy = h * 0.5, vr = Math.max(w, h) * 0.72;
        for (int y = 0; y < h; y++) {
            double scan = (y % 3 == 0) ? 0.86 : 1.0;
            for (int x = 0; x < w; x++) {
                double d = Math.hypot(x - vcx, y - vcy) / vr;
                double v = d < 0.5 ? 1.0 : Math.max(0.2, 1.0 - (d - 0.5) / 0.5 * 0.8);
                vigScan[y * w + x] = (short) Math.round(256 * v * scan);
            }
        }
        noise = new byte[w * h + 8192];
        Random gr = new Random(77L);
        for (int i = 0; i < noise.length; i++) noise[i] = (byte) (gr.nextInt(25) - 12);

        double scale = w / 1280.0;
        line1Font = new Font("SansSerif", Font.BOLD, (int) Math.round(40 * scale));
        line3Font = new Font("SansSerif", Font.BOLD, (int) Math.round(19 * scale));
        logoFont = new Font("SansSerif", Font.BOLD, (int) Math.round(24 * scale));
        hintFont = new Font("SansSerif", Font.PLAIN, (int) Math.round(13 * scale));
        tagFont = new Font("SansSerif", Font.BOLD, (int) Math.round(12 * scale));

        Font big = new Font("SansSerif", Font.BOLD, (int) Math.round(150 * scale));
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector gv = big.createGlyphVector(frc, LINE2);
        Shape raw = gv.getOutline();
        Rectangle2D b = raw.getBounds2D();
        // Растягиваем по ширине до ~74% экрана, центрируем в (0,0)
        double sx = (w * 0.74) / b.getWidth();
        double sy = sx * 0.92;
        AffineTransform at = new AffineTransform();
        at.scale(sx, sy);
        at.translate(-b.getCenterX(), -b.getCenterY());
        titleShape = at.createTransformedShape(raw);

        // Предрасчёт размытого свечения надписи (один раз)
        Rectangle2D tb = titleShape.getBounds2D();
        int pad = (int) (40 * scale) + 8;
        glowW = (int) tb.getWidth() + pad * 2;
        int glowH = (int) tb.getHeight() + pad * 2;
        glowImg = new BufferedImage(glowW, glowH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = glowImg.createGraphics();
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gg.translate(glowW / 2.0, glowH / 2.0);
        gg.setColor(new Color(255, 170, 40));
        gg.setStroke(new BasicStroke((float) (10 * scale)));
        gg.fill(titleShape);
        gg.draw(titleShape);
        gg.dispose();
        int[] gp = ((DataBufferInt) glowImg.getRaster().getDataBuffer()).getData();
        int r = Math.max(4, (int) (14 * scale));
        for (int pass = 0; pass < 3; pass++) {
            boxBlurAlpha(gp, glowW, glowH, r, true);
            boxBlurAlpha(gp, glowW, glowH, r, false);
        }
    }

    /** Разделяемое box-размытие альфа-канала (цвет фиксирован, золотой). */
    private static void boxBlurAlpha(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] tmp = new int[len];
        for (int l = 0; l < lines; l++) {
            int sum = 0;
            for (int i = -r; i <= r; i++) {
                int ii = Math.max(0, Math.min(len - 1, i));
                sum += px[horizontal ? l * w + ii : ii * w + l] >>> 24;
            }
            for (int i = 0; i < len; i++) {
                tmp[i] = sum / (2 * r + 1);
                int out = Math.max(0, i - r), in = Math.min(len - 1, i + r + 1);
                sum += (px[horizontal ? l * w + in : in * w + l] >>> 24) - (px[horizontal ? l * w + out : out * w + l] >>> 24);
            }
            for (int i = 0; i < len; i++) {
                int idx = horizontal ? l * w + i : i * w + l;
                px[idx] = (Math.min(255, tmp[i]) << 24) | 0xFFAA28;
            }
        }
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static double easeOutCubic(double x) { x = clamp01(x); return 1 - Math.pow(1 - x, 3); }
    private static double easeOutElastic(double x) {
        x = clamp01(x);
        if (x == 0 || x == 1) return x;
        return Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * (2 * Math.PI) / 3) + 1;
    }
    private static Color rgba(int rgb, double a) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, (int) Math.max(0, Math.min(255, a * 255)));
    }

    public void render(Graphics2D out, int w, int h) {
        ensureResources(w, h);
        double s = w / 1280.0;
        double cx = w * 0.5, cy = h * 0.53;
        double postImpact = t - IMPACT_T;

        // ===== Мягкие слои в 1/4 разрешения (размытые по природе — апскейл их не портит, а экономит ~20 мс) =====
        Graphics2D g = atmo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(atmo.getWidth() / (double) w, atmo.getHeight() / (double) h);

        // 1. Фон: глубокий тёмный градиент, после удара — тёплое ядро света
        g.setPaint(new GradientPaint(0, 0, new Color(6, 8, 14), 0, h, new Color(14, 10, 8)));
        g.fillRect(0, 0, w, h);
        double warm = impactDone ? Math.min(1.0, 0.55 + 0.45 * Math.exp(-postImpact * 1.5)) : clamp01((t - 0.3) / 2.0) * 0.35;
        float rad = (float) (Math.max(w, h) * (0.55 + 0.1 * Math.sin(t * 1.3)));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), rad, new float[]{0f, 0.45f, 1f},
                new Color[]{rgba(impactDone ? 0x5A3410 : 0x10243C, warm), rgba(impactDone ? 0x2A1606 : 0x081220, warm * 0.6), new Color(0, 0, 0, 0)}));
        g.fillRect(0, 0, w, h);

        // 2. Вращающиеся золотые лучи за надписью
        if (impactDone) {
            double ra = clamp01(postImpact / 0.6) * (t > FADE_START_T ? 1.0 + (t - FADE_START_T) : 1.0);
            int rays = 18;
            double len = Math.max(w, h) * 0.9;
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1.0, 0.16 * ra)));
            for (int i = 0; i < rays; i++) {
                double a0 = t * 0.22 + i * Math.PI * 2 / rays;
                double a1 = a0 + Math.PI / rays * 0.55;
                Polygon poly = new Polygon(
                        new int[]{(int) cx, (int) (cx + Math.cos(a0) * len), (int) (cx + Math.cos(a1) * len)},
                        new int[]{(int) cy, (int) (cy + Math.sin(a0) * len), (int) (cy + Math.sin(a1) * len)}, 3);
                g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) len, new float[]{0f, 1f},
                        new Color[]{new Color(255, 200, 90, 255), new Color(255, 140, 30, 0)}));
                g.fill(poly);
            }
            g.setComposite(oc);
        }

        // 3. Анаморфная полоса-блик (до удара — холодная синяя, после — золотая)
        double streakOpen = easeOutCubic((t - 0.3) / 1.1);
        if (streakOpen > 0) {
            double sw = w * 0.95 * streakOpen;
            double sy = impactDone ? cy : h * 0.42;
            double pulse = impactDone ? 0.45 + 0.55 * Math.exp(-postImpact * 3.0) : 0.6 + 0.2 * Math.sin(t * 9);
            int col = impactDone ? 0xFFC260 : 0x6FC8FF;
            g.setPaint(new GradientPaint((float) (cx - sw / 2), 0, rgba(col, 0), (float) cx, 0, rgba(col, 0.75 * pulse), true));
            g.fill(new Rectangle2D.Double(cx - sw / 2, sy - 1.5 * s, sw / 2, 3 * s));
            g.fill(new Rectangle2D.Double(cx, sy - 1.5 * s, sw / 2, 3 * s));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, sy), (float) (90 * s), new float[]{0f, 1f},
                    new Color[]{rgba(0xFFFFFF, 0.55 * pulse), rgba(col, 0)}));
            g.fill(new Ellipse2D.Double(cx - 90 * s, sy - 90 * s, 180 * s, 180 * s));
            // Призрачные блики линзы
            for (int k = 1; k <= 3; k++) {
                double fx = cx + (cx - w * 0.3) * 0.35 * k * (impactDone ? -1 : 1) * Math.sin(t * 0.4 + k);
                double fr = (14 + k * 9) * s;
                g.setColor(rgba(col, 0.10 * pulse));
                g.fill(new Ellipse2D.Double(fx - fr, sy - fr, fr * 2, fr * 2));
            }
        }

        // 4. Дым удара (под текстом)
        for (P p : particles) {
            if (p.kind != 4) continue;
            double a = clamp01(p.life / p.max) * 0.22;
            g.setColor(rgba(p.rgb, a));
            g.fill(new Ellipse2D.Double(p.x - p.size, p.y - p.size, p.size * 2, p.size * 2));
        }
        // Свечение и мягкая тень титра «АЛКАШОМ» — в мягком слое (в 16 раз меньше пикселей)
        if (impactDone) {
            double slam = easeOutElastic(postImpact / 0.75);
            double tscale = (2.6 - 1.6 * slam) * (1.0 + 0.012 * Math.sin(t * 2.4));
            double alpha = clamp01(postImpact / 0.12);
            AffineTransform saved = g.getTransform();
            g.translate(cx, cy);
            g.scale(tscale, tscale);
            g.setColor(rgba(0x2A1000, 0.9 * alpha));
            g.translate(0, 7 * s); g.fill(titleShape); g.translate(0, -7 * s);
            Composite oc = g.getComposite();
            double glowA = 0.65 + 0.35 * Math.exp(-postImpact * 2.5) + 0.08 * Math.sin(t * 5);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp01(glowA * alpha)));
            g.drawImage(glowImg, -glowW / 2, -glowImg.getHeight() / 2, null);
            g.setComposite(oc);
            g.setTransform(saved);
        }
        // Ореолы угольков и вспышка искр — тоже в мягком слое
        for (P p : particles) {
            if (p.kind != 0) continue;
            double lf = clamp01(p.life / p.max);
            double fl = 0.55 + 0.45 * Math.sin(p.rot * 3.0);
            g.setColor(rgba(p.rgb, 0.30 * lf * fl));
            g.fill(new Ellipse2D.Double(p.x - p.size * 4, p.y - p.size * 4, p.size * 8, p.size * 8));
        }
        g.dispose();

        // ===== Полноразмерный кадр: чёткие элементы =====
        bilinearScale(((DataBufferInt) atmo.getRaster().getDataBuffer()).getData(), atmo.getWidth(), atmo.getHeight(),
                0, 0, atmo.getWidth(), atmo.getHeight(), ((DataBufferInt) frame.getRaster().getDataBuffer()).getData(), w, h);
        g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        // Тонкая анаморфная нить поверх (чёткая)
        if (streakOpen > 0) {
            double sw = w * 0.95 * streakOpen;
            double sy = impactDone ? cy : h * 0.42;
            int col = impactDone ? 0xFFE0A0 : 0xBFE6FF;
            g.setPaint(new GradientPaint((float) (cx - sw / 2), 0, rgba(col, 0), (float) cx, 0, rgba(col, 0.9), true));
            g.fill(new Rectangle2D.Double(cx - sw / 2, sy - 0.75 * s, sw, 1.5 * s));
        }

        // 5. Ударные волны
        for (double[] r : rings) {
            if (r[2] < 0) continue;
            double rr = r[2] * r[3];
            double a = clamp01(1.0 - r[2] / 1.1);
            g.setStroke(new BasicStroke((float) (r[4] * s * (0.4 + a))));
            g.setColor(rgba(0xFFE2A0, a * 0.85));
            g.draw(new Ellipse2D.Double(r[0] - rr, r[1] - rr * 0.62, rr * 2, rr * 1.24));
        }
        g.setStroke(new BasicStroke(1f));

        // 6. «ИГРА СДЕЛАНА» — печать по буквам с падением и глитчем
        if (t > 0.85) {
            g.setFont(line1Font);
            java.awt.FontMetrics fm = g.getFontMetrics();
            double tracking = 10 * s;
            double totalW = 0;
            for (int i = 0; i < LINE1.length(); i++) totalW += fm.charWidth(LINE1.charAt(i)) + tracking;
            totalW -= tracking;
            double x = cx - totalW / 2;
            double baseY = h * 0.33 + fm.getAscent() * 0.35;
            if (impactDone) baseY -= Math.min(1.0, postImpact * 3) * 14 * s; // приподнимается после удара
            for (int i = 0; i < LINE1.length(); i++) {
                char c = LINE1.charAt(i);
                double appear = (t - 0.9 - i * 0.075) / 0.22;
                if (appear > 0) {
                    double e = easeOutCubic(appear);
                    double dy = (1 - e) * -26 * s;
                    double alpha = clamp01(appear * 1.6);
                    if (t > FADE_START_T) alpha *= 1.0;
                    String ch = String.valueOf(c);
                    boolean glitch = appear < 1.0 && rng.nextDouble() < 0.4;
                    if (glitch) {
                        g.setColor(rgba(0xFF3050, alpha * 0.7));
                        g.drawString(ch, (float) (x - 3 * s), (float) (baseY + dy));
                        g.setColor(rgba(0x30E0FF, alpha * 0.7));
                        g.drawString(ch, (float) (x + 3 * s), (float) (baseY + dy));
                    }
                    g.setColor(rgba(0xE8F2FF, alpha));
                    g.drawString(ch, (float) x, (float) (baseY + dy));
                }
                x += fm.charWidth(c) + tracking;
            }
            // Мигающий курсор печатной машинки
            if (typedLetters < LINE1.length() || (t < IMPACT_T && ((int) (t * 4)) % 2 == 0)) {
                double cxp = cx - totalW / 2;
                for (int i = 0; i < typedLetters; i++) cxp += fm.charWidth(LINE1.charAt(i)) + tracking;
                g.setColor(new Color(120, 210, 255, 220));
                g.fill(new Rectangle2D.Double(cxp + 2 * s, baseY - fm.getAscent() * 0.8, 4 * s, fm.getAscent() * 0.85));
            }
        }

        // 7. Падающая бутылка
        if (t > 1.75 && !impactDone) {
            double p = clamp01((t - 1.75) / (IMPACT_T - 1.75));
            double by = -120 * s + (cy + 120 * s) * p * p;
            double rot = p * Math.PI * 3.2;
            for (int k = 3; k >= 1; k--) {
                double pp = Math.max(0, p - k * 0.035);
                double tby = -120 * s + (cy + 120 * s) * pp * pp;
                drawBottle(g, cx, tby, pp * Math.PI * 3.2, s, 0.18 / k);
            }
            drawBottle(g, cx, by, rot, s, 1.0);
        }

        // 8. «АЛКАШОМ» — главный титр
        if (impactDone) {
            double slam = easeOutElastic(postImpact / 0.75);
            double scale = 2.6 - 1.6 * slam;
            double breath = 1.0 + 0.012 * Math.sin(t * 2.4);
            double alpha = clamp01(postImpact / 0.12);
            AffineTransform saved = g.getTransform();
            g.translate(cx, cy);
            g.scale(scale * breath, scale * breath);

            // Хроматическая аберрация (сильная сразу после удара + периодические глитчи)
            double ab = 10 * s * Math.exp(-postImpact * 4.0) + (isGlitchMoment() ? 7 * s : 0);
            if (ab > 0.6) {
                g.setColor(rgba(0xFF2040, 0.55 * alpha));
                g.translate(-ab, 0); g.fill(titleShape); g.translate(ab, 0);
                g.setColor(rgba(0x20E8FF, 0.55 * alpha));
                g.translate(ab, 0); g.fill(titleShape); g.translate(-ab, 0);
            }

            // Золотая заливка с вертикальным градиентом
            Rectangle2D tb = titleShape.getBounds2D();
            g.setPaint(new GradientPaint(0, (float) tb.getMinY(), rgba(0xFFF3B8, alpha),
                    0, (float) tb.getMaxY(), rgba(0xE07A10, alpha)));
            g.fill(titleShape);
            // Средняя полоса металла
            Shape oldClip = g.getClip();
            g.clip(titleShape);
            g.setColor(rgba(0xFFFFFF, 0.16 * alpha));
            g.fill(new Rectangle2D.Double(tb.getMinX(), tb.getMinY(), tb.getWidth(), tb.getHeight() * 0.42));
            // Пробегающий блик
            double cycle = ((t - IMPACT_T - 0.5) % 1.7) / 1.7;
            if (postImpact > 0.5) {
                double bx = tb.getMinX() - tb.getWidth() * 0.3 + cycle * tb.getWidth() * 1.6;
                g.setPaint(new GradientPaint((float) (bx - 60 * s), 0, new Color(255, 255, 255, 0),
                        (float) bx, 0, new Color(255, 255, 255, (int) (200 * alpha)), true));
                AffineTransform sh = g.getTransform();
                g.shear(-0.35, 0);
                g.fill(new Rectangle2D.Double(bx - 60 * s, tb.getMinY() - 20, 120 * s, tb.getHeight() + 40));
                g.setTransform(sh);
            }
            g.setClip(oldClip);
            // Контур
            g.setStroke(new BasicStroke((float) (2.2 * s)));
            g.setColor(rgba(0x5A2A00, alpha));
            g.draw(titleShape);
            g.setStroke(new BasicStroke(1f));
            g.setTransform(saved);
        }

        // 9. «ПРЕДСТАВЛЯЕТ» из раскрывающейся золотой линии
        if (t > 3.3) {
            double e = easeOutCubic((t - 3.3) / 0.7);
            double ly = h * 0.71;
            double lw = w * 0.42 * e;
            g.setPaint(new GradientPaint((float) (cx - lw / 2), 0, new Color(255, 196, 80, 0), (float) cx, 0, new Color(255, 214, 120, 230), true));
            g.fill(new Rectangle2D.Double(cx - lw / 2, ly, lw, 2 * s));
            double ta = clamp01((t - 3.55) / 0.6);
            if (ta > 0) {
                g.setFont(line3Font);
                java.awt.FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(LINE3);
                double spread = 1.0 + (1.0 - easeOutCubic(ta)) * 0.35;
                AffineTransform saved = g.getTransform();
                g.translate(cx, ly + 30 * s);
                g.scale(spread, 1.0);
                g.setColor(rgba(0xF5E6C8, ta));
                g.drawString(LINE3, -tw / 2f, 0f);
                g.setTransform(saved);
            }
        }

        // 10. Логотип CS-2 JAVA 10.0
        if (t > 4.2) {
            double a = clamp01((t - 4.2) / 0.5);
            double ly = h * 0.83;
            g.setFont(logoFont);
            java.awt.FontMetrics fm = g.getFontMetrics();
            String logo = "CS-2 JAVA 10.0";
            int tw = fm.stringWidth(logo);
            double crestW = 46 * s, crestH = 34 * s;
            double totalW = crestW + 12 * s + tw;
            double x0 = cx - totalW / 2;
            double y0 = ly - crestH / 2;
            Polygon crest = new Polygon(
                    new int[]{(int) x0, (int) (x0 + crestW), (int) (x0 + crestW - 7 * s), (int) x0},
                    new int[]{(int) y0, (int) y0, (int) (y0 + crestH), (int) (y0 + crestH)}, 4);
            g.setPaint(new GradientPaint((float) x0, (float) y0, rgba(0xFFD654, a), (float) (x0 + crestW), (float) (y0 + crestH), rgba(0xE08014, a)));
            g.fill(crest);
            g.setFont(tagFont.deriveFont((float) (15 * s)));
            g.setColor(rgba(0x0E121A, a));
            g.drawString("CS2", (float) (x0 + 5 * s), (float) (y0 + crestH * 0.70));
            g.setFont(logoFont);
            g.setColor(rgba(0xFFFFFF, a));
            g.drawString(logo, (float) (x0 + crestW + 12 * s), (float) (ly + fm.getAscent() * 0.36));
            g.setFont(tagFont);
            String tag = "ALKASH EDITION  •  26 СТВОЛОВ  •  КЕЙСЫ  •  ДОСТИЖЕНИЯ";
            int tagW = g.getFontMetrics().stringWidth(tag);
            g.setColor(rgba(0x8FD8FF, a * 0.9));
            g.drawString(tag, (float) (cx - tagW / 2.0), (float) (ly + 30 * s));
        }

        // 11. Частицы поверх: угольки, искры, стекло, пена
        for (P p : particles) {
            double lf = clamp01(p.life / p.max);
            switch (p.kind) {
                case 0: {
                    double fl = 0.55 + 0.45 * Math.sin(p.rot * 3.0);
                    g.setColor(rgba(p.rgb, 0.9 * lf * fl));
                    int sz = Math.max(1, (int) Math.round(p.size));
                    g.fillRect((int) (p.x - sz / 2.0), (int) (p.y - sz / 2.0), sz, sz);
                    break;
                }
                case 1: {
                    g.setColor(rgba(p.rgb, lf));
                    g.setStroke(new BasicStroke((float) (p.size * s)));
                    g.draw(new java.awt.geom.Line2D.Double(p.x, p.y, p.x - p.vx * 0.022, p.y - p.vy * 0.022));
                    break;
                }
                case 2: {
                    AffineTransform saved = g.getTransform();
                    g.translate(p.x, p.y);
                    g.rotate(p.rot);
                    double sz = p.size * s;
                    int n = p.shapeXs.length;
                    int[] xs = new int[n], ys = new int[n];
                    for (int k = 0; k < n; k++) { xs[k] = (int) (p.shapeXs[k] * sz); ys[k] = (int) (p.shapeYs[k] * sz); }
                    g.setColor(rgba(p.rgb, 0.65 * lf));
                    g.fillPolygon(xs, ys, n);
                    g.setColor(rgba(0xFFFFFF, 0.8 * lf));
                    g.drawLine(xs[0], ys[0], xs[1], ys[1]);
                    g.setTransform(saved);
                    break;
                }
                case 3: {
                    g.setColor(rgba(p.rgb, 0.85 * lf));
                    double sz = p.size * s;
                    g.fill(new Ellipse2D.Double(p.x - sz / 2, p.y - sz / 2, sz, sz));
                    break;
                }
                default:
                    break;
            }
        }
        g.setStroke(new BasicStroke(1f));

        // 12. Подсказка пропуска
        if (t > 0.8 && t < FADE_START_T) {
            double a = clamp01((t - 0.8) / 0.6) * (0.55 + 0.25 * Math.sin(t * 3));
            g.setFont(hintFont);
            String hint = "Нажмите любую клавишу или кликните, чтобы пропустить  ▸▸";
            int hw = g.getFontMetrics().stringWidth(hint);
            g.setColor(rgba(0xC8D4E4, a));
            g.drawString(hint, w - hw - (int) (24 * s), h - (int) (22 * s));
        }

        // 13. Кинематографические полосы (зерно/сканлайны/виньетка — одним проходом ниже)
        double bars = (1.0 - easeOutCubic(t / 0.9)) * h * 0.5 + h * 0.06;
        if (t > FADE_START_T) bars *= Math.max(0, 1 - (t - FADE_START_T) / 0.4);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, (int) bars);
        g.fillRect(0, h - (int) bars, w, (int) bars + 1);

        // 14. Белая вспышка удара
        if (impactDone && postImpact < 0.45) {
            g.setColor(rgba(0xFFFFFF, Math.pow(1.0 - postImpact / 0.45, 2.0)));
            g.fillRect(0, 0, w, h);
        }
        g.dispose();
        applyFilmPass();

        // ===== Композитинг в окно: тряска камеры, глитч-срезы, финальный наезд и уход в белое =====
        out.setColor(Color.BLACK);
        out.fillRect(0, 0, w, h);
        double shx = shake > 0 ? (rng.nextDouble() - 0.5) * shake * 2 * s : 0;
        double shy = shake > 0 ? (rng.nextDouble() - 0.5) * shake * 2 * s : 0;
        double zoom = 1.0;
        if (t > FADE_START_T) zoom = 1.0 + Math.pow((t - FADE_START_T) / (END_T - FADE_START_T), 2) * 0.35;
        int dw = (int) (w * zoom), dh = (int) (h * zoom);
        int dx = (int) ((w - dw) / 2 + shx), dy = (int) ((h - dh) / 2 + shy);
        if (zoom > 1.0001) {
            if (zoomBuf == null || zoomBuf.getWidth() != w || zoomBuf.getHeight() != h) {
                zoomBuf = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            double cw = w / zoom, ch = h / zoom;
            double sx0 = Math.max(0, Math.min(w - cw, (w - cw) / 2 - shx / zoom));
            double sy0 = Math.max(0, Math.min(h - ch, (h - ch) / 2 - shy / zoom));
            bilinearScale(((DataBufferInt) frame.getRaster().getDataBuffer()).getData(), w, h, sx0, sy0, cw, ch,
                    ((DataBufferInt) zoomBuf.getRaster().getDataBuffer()).getData(), w, h);
            out.drawImage(zoomBuf, 0, 0, null);
        } else {
            out.drawImage(frame, dx, dy, null);
        }

        if (isGlitchMoment() || (impactDone && postImpact < 0.25)) {
            int slices = 6 + rng.nextInt(6);
            for (int i = 0; i < slices; i++) {
                int sy0 = rng.nextInt(Math.max(1, h - 20));
                int sh = 4 + rng.nextInt((int) (30 * s) + 1);
                int off = (int) ((rng.nextDouble() - 0.5) * 70 * s);
                out.drawImage(frame, off, sy0, w + off, sy0 + sh, 0, sy0, w, sy0 + sh, null);
            }
        }

        if (t > FADE_START_T) {
            double fa = clamp01((t - FADE_START_T) / (END_T - FADE_START_T - 0.05));
            out.setColor(new Color(255, 255, 255, (int) (255 * Math.pow(fa, 1.4))));
            out.fillRect(0, 0, w, h);
        }
    }

    /** Один проход по пикселям: виньетка × сканлайны + плёночное зерно (меняется 24 раза в секунду). */
    private void applyFilmPass() {
        final int[] px = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        final int n = px.length;
        final int off = (((int) (t * 24.0)) * 1543) & 8191;
        final int bands = 16;
        java.util.stream.IntStream.range(0, bands).parallel().forEach(bnd -> {
            int from = (int) ((long) n * bnd / bands), to = (int) ((long) n * (bnd + 1) / bands);
            filmRange(px, from, to, off);
        });
    }

    private void filmRange(int[] px, int from, int to, int off) {
        short[] vs = vigScan;
        byte[] nz = noise;
        for (int i = from; i < to; i++) {
            int c = px[i];
            int f = vs[i];
            int gn = nz[i + off];
            int r = ((((c >> 16) & 255) * f) >> 8) + gn;
            int gg = ((((c >> 8) & 255) * f) >> 8) + gn;
            int b = (((c & 255) * f) >> 8) + gn;
            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            gg = gg < 0 ? 0 : (gg > 255 ? 255 : gg);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);
            px[i] = (r << 16) | (gg << 8) | b;
        }
    }

    /** Быстрый параллельный билинейный ресемплинг (8-битные веса) прямоугольника src → весь dst. */
    private static void bilinearScale(final int[] src, final int sw, final int sh,
                                      double rx, double ry, double rw, double rh,
                                      final int[] dst, final int dw, final int dh) {
        final int[] x0 = new int[dw], x1 = new int[dw], wx = new int[dw];
        double kx = rw / dw, ky = rh / dh;
        for (int x = 0; x < dw; x++) {
            double fx = rx + (x + 0.5) * kx - 0.5;
            if (fx < 0) fx = 0;
            int ix = (int) fx;
            if (ix > sw - 1) ix = sw - 1;
            x0[x] = ix;
            x1[x] = Math.min(sw - 1, ix + 1);
            wx[x] = (int) ((fx - ix) * 256);
        }
        final double fry = ry, fky = ky;
        final int bands = 16;
        java.util.stream.IntStream.range(0, bands).parallel().forEach(bnd -> {
            int yFrom = dh * bnd / bands, yTo = dh * (bnd + 1) / bands;
            for (int y = yFrom; y < yTo; y++) {
                double fy = fry + (y + 0.5) * fky - 0.5;
                if (fy < 0) fy = 0;
                int iy = (int) fy;
                if (iy > sh - 1) iy = sh - 1;
                int iy1 = Math.min(sh - 1, iy + 1);
                int wy = (int) ((fy - iy) * 256), wy0 = 256 - wy;
                int r0 = iy * sw, r1 = iy1 * sw, o = y * dw;
                for (int x = 0; x < dw; x++) {
                    int a = src[r0 + x0[x]], b = src[r0 + x1[x]], c = src[r1 + x0[x]], d = src[r1 + x1[x]];
                    int w1 = wx[x], w0 = 256 - w1;
                    int rb0 = ((a & 0xFF00FF) * w0 + (b & 0xFF00FF) * w1) >>> 8 & 0xFF00FF;
                    int g0 = ((a & 0x00FF00) * w0 + (b & 0x00FF00) * w1) >>> 8 & 0x00FF00;
                    int rb1 = ((c & 0xFF00FF) * w0 + (d & 0xFF00FF) * w1) >>> 8 & 0xFF00FF;
                    int g1 = ((c & 0x00FF00) * w0 + (d & 0x00FF00) * w1) >>> 8 & 0x00FF00;
                    int rb = ((rb0 * wy0 + rb1 * wy) >>> 8) & 0xFF00FF;
                    int gg = ((g0 * wy0 + g1 * wy) >>> 8) & 0x00FF00;
                    dst[o + x] = rb | gg;
                }
            }
        });
    }

    private boolean isGlitchMoment() {
        double[] at = {3.32, 4.62, 5.35};
        for (double a : at) if (t > a && t < a + 0.11) return true;
        return false;
    }

    /** Стилизованная пивная бутылка (зелёное стекло, этикетка, блик). */
    private void drawBottle(Graphics2D g, double x, double y, double rot, double s, double alpha) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.rotate(rot);
        double bw = 30 * s, bh = 92 * s;
        Area body = new Area(new RoundRectangle2D.Double(-bw / 2, -bh * 0.15, bw, bh * 0.65, 12 * s, 12 * s));
        Polygon shoulder = new Polygon(
                new int[]{(int) (-bw / 2), (int) (bw / 2), (int) (bw * 0.18), (int) (-bw * 0.18)},
                new int[]{(int) (-bh * 0.10), (int) (-bh * 0.10), (int) (-bh * 0.32), (int) (-bh * 0.32)}, 4);
        body.add(new Area(shoulder));
        body.add(new Area(new Rectangle2D.Double(-bw * 0.18, -bh * 0.52, bw * 0.36, bh * 0.22)));
        g.setPaint(new GradientPaint((float) (-bw / 2), 0, rgba(0x1F6E3A, 0.92 * alpha), (float) (bw / 2), 0, rgba(0x0B3A1C, 0.92 * alpha)));
        g.fill(body);
        g.setColor(rgba(0xE8C25A, alpha));
        g.fill(new Rectangle2D.Double(-bw / 2, bh * 0.04, bw, bh * 0.20));
        g.setColor(rgba(0x7A1E14, alpha));
        g.fill(new Rectangle2D.Double(-bw / 2, bh * 0.10, bw, bh * 0.05));
        g.setColor(rgba(0xC8C8C8, alpha));
        g.fill(new Rectangle2D.Double(-bw * 0.22, -bh * 0.56, bw * 0.44, bh * 0.06));
        g.setColor(rgba(0xFFFFFF, 0.38 * alpha));
        g.fill(new RoundRectangle2D.Double(-bw * 0.34, -bh * 0.08, bw * 0.12, bh * 0.5, 4 * s, 4 * s));
        g.setTransform(saved);
    }
}
