import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * CS-2 JAVA 10.0 — эффекты интерфейса: угольки в меню, круги-волны от кликов, плавная анимация наведения кнопок,
 * пробегающие блики, HUD-уголки панелей, бегущая строка новостей и всплывающие достижения.
 */
public final class UiFx {

    // ===================== ДОСТИЖЕНИЯ =====================
    public static final String[][] ACHIEVEMENTS = {
        {"first_blood", "Первая кровь", "Сделать первый фраг"},
        {"kills_25", "Опытный боец", "25 фрагов за карьеру"},
        {"kills_100", "Машина смерти", "100 фрагов за карьеру"},
        {"hs_10", "Меткий глаз", "10 попаданий в голову"},
        {"hs_50", "Мастер хедшотов", "50 попаданий в голову"},
        {"win_1", "Первая победа", "Выиграть матч"},
        {"win_10", "Чемпион", "Выиграть 10 матчей"},
        {"rank_up", "Повышение", "Получить новое звание"},
        {"case_1", "Азартный", "Открыть первый кейс"},
        {"case_10", "Лудоман", "Открыть 10 кейсов"},
        {"covert", "Тайное оружие", "Выбить предмет «Тайное»"},
        {"knife", "★ Золотые руки", "Выбить нож из кейса"},
        {"contract", "Контрактник", "Заключить контракт обмена"},
    };

    public static String[] findAchievement(String id) {
        for (String[] a : ACHIEVEMENTS) if (a[0].equals(id)) return a;
        return null;
    }

    private static final class Toast {
        final String title, desc;
        double t = 0.0;
        Toast(String title, String desc) { this.title = title; this.desc = desc; }
    }

    private final ArrayDeque<Toast> toasts = new ArrayDeque<>();

    public void pushAchievementToast(String id) {
        String[] a = findAchievement(id);
        if (a != null) toasts.add(new Toast(a[1], a[2] + "  •  +1 ключ от кейса"));
    }

    // ===================== ЧАСТИЦЫ И ВОЛНЫ =====================
    private static final class Ember { double x, y, vx, vy, life, max, size, ph; int rgb; }
    private final List<Ember> embers = new ArrayList<>();
    private final List<double[]> ripples = new ArrayList<>(); // x, y, age
    private final HashMap<Long, double[]> hover = new HashMap<>(); // key -> {anim, lastSeenFrame}
    private final Random rng = new Random(9501L);
    private double time = 0.0;
    private long frameNo = 0;
    private double emberAcc = 0.0;

    public double getTime() { return time; }

    public void update(double dt, int w, int h, boolean emitEmbers) {
        time += dt;
        if (emitEmbers) {
            emberAcc += dt * 16.0;
            while (emberAcc >= 1.0 && embers.size() < 110) {
                emberAcc -= 1.0;
                Ember e = new Ember();
                e.x = rng.nextDouble() * w;
                e.y = h + 6;
                e.vx = (rng.nextDouble() - 0.5) * 18;
                e.vy = -(22 + rng.nextDouble() * 60);
                e.max = e.life = 4 + rng.nextDouble() * 5;
                e.size = 1.0 + rng.nextDouble() * 2.2;
                e.ph = rng.nextDouble() * 6.28;
                e.rgb = rng.nextDouble() < 0.75 ? 0xFFA040 : 0xFFE08A;
                embers.add(e);
            }
            if (emberAcc > 1.0) emberAcc = 0.0;
        }
        for (int i = embers.size() - 1; i >= 0; i--) {
            Ember e = embers.get(i);
            e.life -= dt;
            if (e.life <= 0 || e.y < -10) { embers.remove(i); continue; }
            e.ph += dt * 1.7;
            e.x += (e.vx + Math.sin(e.ph) * 14) * dt;
            e.y += e.vy * dt;
        }
        for (int i = ripples.size() - 1; i >= 0; i--) {
            double[] r = ripples.get(i);
            r[2] += dt;
            if (r[2] > 0.55) ripples.remove(i);
        }
        Toast tt = toasts.peek();
        if (tt != null) {
            tt.t += dt;
            if (tt.t > 4.2) toasts.poll();
        }
    }

    public void addRipple(int x, int y) {
        ripples.add(new double[]{x, y, 0.0});
    }

    public void beginFrame() {
        frameNo++;
        if ((frameNo & 255) == 0) hover.entrySet().removeIf(en -> frameNo - (long) en.getValue()[1] > 600);
    }

    /** Плавная (≈0.12 с) анимация наведения 0..1 для элемента с данным прямоугольником. */
    public double hoverAnim(Rectangle r, boolean hovered, double dt) {
        long key = ((long) r.x * 73856093L) ^ ((long) r.y * 19349663L) ^ ((long) r.width * 83492791L) ^ r.height;
        double[] st = hover.get(key);
        if (st == null) { st = new double[]{0, frameNo}; hover.put(key, st); }
        st[1] = frameNo;
        double target = hovered ? 1.0 : 0.0;
        double k = Math.min(1.0, dt * 11.0);
        st[0] += (target - st[0]) * k;
        if (Math.abs(st[0] - target) < 0.002) st[0] = target;
        return st[0];
    }

    private static Color rgba(int rgb, double a) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, (int) Math.max(0, Math.min(255, a * 255)));
    }

    public void drawEmbers(Graphics2D g) {
        for (Ember e : embers) {
            double lf = Math.min(1.0, e.life / e.max * 2.0) * Math.min(1.0, (e.max - e.life) * 2.0);
            double fl = 0.6 + 0.4 * Math.sin(e.ph * 4.0);
            g.setColor(rgba(e.rgb, 0.16 * lf * fl));
            g.fill(new Ellipse2D.Double(e.x - e.size * 3, e.y - e.size * 3, e.size * 6, e.size * 6));
            g.setColor(rgba(e.rgb, 0.85 * lf * fl));
            g.fill(new Ellipse2D.Double(e.x - e.size / 2, e.y - e.size / 2, e.size, e.size));
        }
    }

    public void drawRipples(Graphics2D g) {
        for (double[] r : ripples) {
            double p = r[2] / 0.55;
            double rad = 8 + p * 46;
            g.setStroke(new BasicStroke((float) (3.0 * (1 - p) + 0.6)));
            g.setColor(new Color(255, 214, 110, (int) (220 * (1 - p))));
            g.draw(new Ellipse2D.Double(r[0] - rad, r[1] - rad, rad * 2, rad * 2));
            g.setColor(new Color(255, 255, 255, (int) (110 * (1 - p) * (1 - p))));
            g.fill(new Ellipse2D.Double(r[0] - rad * 0.3, r[1] - rad * 0.3, rad * 0.6, rad * 0.6));
        }
        g.setStroke(new BasicStroke(1f));
    }

    /** Диагональный пробегающий блик внутри формы. phase 0..1. */
    public static void drawShine(Graphics2D g, Shape clip, Rectangle b, double phase, double alpha) {
        if (phase < 0 || phase > 1 || alpha <= 0.01) return;
        Shape old = g.getClip();
        g.clip(clip);
        double bx = b.x - b.height * 1.2 + phase * (b.width + b.height * 2.4);
        double bw = Math.max(26, b.height * 0.9);
        Polygon band = new Polygon(
                new int[]{(int) bx, (int) (bx + bw), (int) (bx + bw - b.height * 0.6), (int) (bx - b.height * 0.6)},
                new int[]{b.y - 2, b.y - 2, b.y + b.height + 2, b.y + b.height + 2}, 4);
        g.setPaint(new GradientPaint((float) bx, 0, new Color(255, 255, 255, 0),
                (float) (bx + bw / 2), 0, new Color(255, 255, 255, (int) (150 * alpha)), true));
        g.fill(band);
        g.setClip(old);
    }

    /** HUD-уголки по периметру панели (как прицельная рамка). */
    public static void drawCornerBrackets(Graphics2D g, int x, int y, int w, int h, int len, Color c, float stroke) {
        g.setColor(c);
        g.setStroke(new BasicStroke(stroke));
        g.drawLine(x, y, x + len, y); g.drawLine(x, y, x, y + len);
        g.drawLine(x + w, y, x + w - len, y); g.drawLine(x + w, y, x + w, y + len);
        g.drawLine(x, y + h, x + len, y + h); g.drawLine(x, y + h, x, y + h - len);
        g.drawLine(x + w, y + h, x + w - len, y + h); g.drawLine(x + w, y + h, x + w, y + h - len);
        g.setStroke(new BasicStroke(1f));
    }

    /** Бегущий световой отрезок по верхней кромке панели. */
    public void drawRunningEdge(Graphics2D g, int x, int y, int w, Color c, double speed) {
        double p = (time * speed) % 1.0;
        int segW = Math.max(40, w / 5);
        int sx = (int) (x - segW + p * (w + segW));
        Shape old = g.getClip();
        g.clipRect(x, y - 2, w, 6);
        g.setPaint(new GradientPaint(sx, 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0),
                sx + segW / 2f, 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 255), true));
        g.fillRect(sx, y, segW, 2);
        g.setClip(old);
    }

    /** Бегущая строка новостей в прямоугольнике. */
    public void drawTicker(Graphics2D g, int x, int y, int w, int h, String text, Font f, Color c) {
        Shape old = g.getClip();
        g.clipRect(x, y, w, h);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 80;
        int off = (int) ((time * 60.0) % tw);
        g.setColor(c);
        int base = y + (h + fm.getAscent()) / 2 - 2;
        for (int k = 0; k < 3; k++) g.drawString(text, x + w - off + k * tw - tw, base);
        g.setClip(old);
        g.setPaint(new GradientPaint(x, 0, new Color(8, 12, 20, 255), x + 30, 0, new Color(8, 12, 20, 0)));
        g.fillRect(x, y, 30, h);
        g.setPaint(new GradientPaint(x + w - 30, 0, new Color(8, 12, 20, 0), x + w, 0, new Color(8, 12, 20, 255)));
        g.fillRect(x + w - 30, y, 30, h);
    }

    /** Значок-медаль достижения. */
    public static void drawMedal(Graphics2D g, double cx, double cy, double r, boolean unlocked, double glow) {
        if (unlocked && glow > 0) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) (r * 2.2), new float[]{0f, 1f},
                    new Color[]{new Color(255, 196, 70, (int) (120 * glow)), new Color(255, 196, 70, 0)}));
            g.fill(new Ellipse2D.Double(cx - r * 2.2, cy - r * 2.2, r * 4.4, r * 4.4));
        }
        // ленточки
        g.setColor(unlocked ? new Color(200, 50, 60) : new Color(60, 66, 78));
        g.fillPolygon(new int[]{(int) (cx - r * 0.7), (int) (cx - r * 0.1), (int) (cx - r * 0.4), (int) (cx - r)},
                new int[]{(int) (cy - r * 1.6), (int) (cy - r * 1.6), (int) (cy - r * 0.4), (int) (cy - r * 0.6)}, 4);
        g.setColor(unlocked ? new Color(40, 90, 200) : new Color(52, 58, 70));
        g.fillPolygon(new int[]{(int) (cx + r * 0.1), (int) (cx + r * 0.7), (int) (cx + r), (int) (cx + r * 0.4)},
                new int[]{(int) (cy - r * 1.6), (int) (cy - r * 1.6), (int) (cy - r * 0.6), (int) (cy - r * 0.4)}, 4);
        Ellipse2D disc = new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
        g.setPaint(unlocked
                ? new GradientPaint((float) cx, (float) (cy - r), new Color(255, 236, 150), (float) cx, (float) (cy + r), new Color(214, 128, 20))
                : new GradientPaint((float) cx, (float) (cy - r), new Color(96, 104, 118), (float) cx, (float) (cy + r), new Color(50, 56, 66)));
        g.fill(disc);
        g.setColor(unlocked ? new Color(120, 60, 0) : new Color(34, 38, 46));
        g.draw(disc);
        // звезда
        Polygon star = new Polygon();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            double rr = (i % 2 == 0) ? r * 0.62 : r * 0.26;
            star.addPoint((int) Math.round(cx + Math.cos(a) * rr), (int) Math.round(cy + Math.sin(a) * rr));
        }
        g.setColor(unlocked ? new Color(255, 250, 220) : new Color(120, 128, 140));
        g.fillPolygon(star);
    }

    /** Всплывающее уведомление о достижении (справа сверху, выезжает и уезжает). */
    public void drawToasts(Graphics2D g, int w, int h) {
        Toast t = toasts.peek();
        if (t == null) return;
        double in = Math.min(1.0, t.t / 0.35), out = Math.max(0.0, (t.t - 3.7) / 0.5);
        double e = 1 - Math.pow(1 - in, 3);
        double slide = (1 - e) + out * out;
        int tw = 360, th = 70;
        int x = (int) (w - tw - 20 + slide * (tw + 40));
        int y = 84;
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, 1 - out))));
        RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, tw, th, 14, 14);
        g.setPaint(new GradientPaint(x, y, new Color(30, 24, 10, 240), x + tw, y + th, new Color(12, 14, 22, 240)));
        g.fill(rr);
        g.setColor(new Color(255, 200, 80, 220));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(rr);
        g.setStroke(new BasicStroke(1f));
        drawShine(g, rr, new Rectangle(x, y, tw, th), (t.t % 1.8) / 1.0 - 0.2, 0.6);
        drawMedal(g, x + 38, y + th / 2.0 + 6, 17, true, 0.6 + 0.4 * Math.sin(t.t * 6));
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.setColor(new Color(255, 200, 90));
        g.drawString("ДОСТИЖЕНИЕ РАЗБЛОКИРОВАНО", x + 70, y + 20);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.setColor(Color.WHITE);
        g.drawString(t.title, x + 70, y + 41);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(new Color(196, 206, 222));
        g.drawString(t.desc, x + 70, y + 58);
        // прогресс-полоска показа
        g.setColor(new Color(255, 200, 80, 160));
        g.fill(new Rectangle2D.Double(x + 8, y + th - 4, (tw - 16) * Math.max(0, 1 - t.t / 4.2), 2));
        g.setComposite(oc);
    }
}
