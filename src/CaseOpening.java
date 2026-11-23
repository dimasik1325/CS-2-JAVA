import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CS-2 JAVA 10.0 — Открытие кейсов в стиле CS2: рулетка с замедлением, редкости, износ, StatTrak™,
 * инвентарь, экипировка скина и контракт обмена «3 предмета → 1 ключ».
 */
public final class CaseOpening {

    /** Мост к движку (рисование стекла/силуэтов/кнопок, настройки, звук, экипировка). */
    public interface Host {
        void glassPanel(Graphics2D g, int x, int y, int w, int h, int tintRgb, int alpha, Color border, Color accent);
        void silhouette(Graphics2D g, Weapon.Type t, int x, int y, int w, int h, Color body, Color accent);
        void button(Graphics2D g, Rectangle r, String label, Color base, boolean darkText, Runnable onClick);
        void clickable(Rectangle r, Runnable action);
        int mouseX();
        int mouseY();
        GameSettings settings();
        SoundSystem sound();
        void equip(Weapon.Type type, GameSettings.WeaponSkin skin);
        void back();
        void achievement(String id);
    }

    public static final String[] RARITY_NAMES = {"Армейское", "Запрещённое", "Засекреченное", "Тайное", "★ Редкий особый"};
    public static final int[] RARITY_RGB = {0x4B69FF, 0x8847FF, 0xD32CE6, 0xEB4B4B, 0xE4AE39};
    private static final double[] RARITY_ODDS = {0.69, 0.20, 0.07, 0.03, 0.01};
    private static final String[] WEAR_NAMES = {"Прямо с завода", "Немного поношенное", "После полевых испытаний", "Поношенное", "Закалённое в боях"};

    public static final class Item {
        public final Weapon.Type type;
        public final GameSettings.WeaponSkin skin;
        public final String skinName;
        public final int rarity;
        Item(Weapon.Type type, GameSettings.WeaponSkin skin, String skinName, int rarity) {
            this.type = type; this.skin = skin; this.skinName = skinName; this.rarity = rarity;
        }
        public String weaponName() { return (rarity == 4 ? "★ " : "") + Weapon.nameOf(type); }
    }

    static final Item[] POOL = {
        new Item(Weapon.Type.GLOCK,     GameSettings.WeaponSkin.HYPER_BEAST, "Неоновый Зверь", 0),
        new Item(Weapon.Type.P250,      GameSettings.WeaponSkin.REDLINE,     "Красная линия", 0),
        new Item(Weapon.Type.MAC10,     GameSettings.WeaponSkin.ASIIMOV,     "Азимов", 0),
        new Item(Weapon.Type.NOVA,      GameSettings.WeaponSkin.PRINTSTREAM, "Принтстрим", 0),
        new Item(Weapon.Type.FAMAS,     GameSettings.WeaponSkin.REDLINE,     "Красная линия", 0),
        new Item(Weapon.Type.UMP45,     GameSettings.WeaponSkin.HYPER_BEAST, "Неоновый Зверь", 0),
        new Item(Weapon.Type.GALIL,     GameSettings.WeaponSkin.GOLD_FADE,   "Градиент", 1),
        new Item(Weapon.Type.MP9,       GameSettings.WeaponSkin.PRINTSTREAM, "Принтстрим", 1),
        new Item(Weapon.Type.FIVESEVEN, GameSettings.WeaponSkin.ASIIMOV,     "Азимов", 1),
        new Item(Weapon.Type.SG553,     GameSettings.WeaponSkin.HYPER_BEAST, "Неоновый Зверь", 1),
        new Item(Weapon.Type.M4A4,      GameSettings.WeaponSkin.ASIIMOV,     "Азимов", 2),
        new Item(Weapon.Type.AUG,       GameSettings.WeaponSkin.GOLD_FADE,   "Градиент", 2),
        new Item(Weapon.Type.DEAGLE,    GameSettings.WeaponSkin.PRINTSTREAM, "Принтстрим", 2),
        new Item(Weapon.Type.AK47,      GameSettings.WeaponSkin.HYPER_BEAST, "Неоновый Зверь", 3),
        new Item(Weapon.Type.AWP,       GameSettings.WeaponSkin.ASIIMOV,     "Азимов", 3),
        new Item(Weapon.Type.M4A1S,     GameSettings.WeaponSkin.PRINTSTREAM, "Принтстрим", 3),
        new Item(Weapon.Type.KNIFE,     GameSettings.WeaponSkin.GOLD_FADE,   "Градиент", 4),
        new Item(Weapon.Type.KNIFE,     GameSettings.WeaponSkin.HYPER_BEAST, "Неоновый Зверь", 4),
    };

    /** Выпавший экземпляр предмета. */
    public static final class Drop {
        public final int itemIdx;
        public final int wear1000;
        public final boolean statTrak;
        Drop(int itemIdx, int wear1000, boolean statTrak) { this.itemIdx = itemIdx; this.wear1000 = wear1000; this.statTrak = statTrak; }
        public Item item() { return POOL[itemIdx]; }
        String wearName() {
            double f = wear1000 / 1000.0;
            return WEAR_NAMES[f < 0.07 ? 0 : f < 0.15 ? 1 : f < 0.38 ? 2 : f < 0.45 ? 3 : 4];
        }
        String encode() { return itemIdx + ":" + wear1000 + ":" + (statTrak ? 1 : 0); }
        static Drop decode(String s) {
            try {
                String[] p = s.split(":");
                int idx = Integer.parseInt(p[0]);
                if (idx < 0 || idx >= POOL.length) return null;
                return new Drop(idx, Integer.parseInt(p[1]), p.length > 2 && "1".equals(p[2]));
            } catch (Exception e) {
                return null;
            }
        }
    }

    private enum State { IDLE, SPINNING, REVEAL }

    private static final int REEL_LEN = 58;
    private static final int WIN_IDX = 50;

    private final Host host;
    private final Random rng = new Random();
    private State state = State.IDLE;
    private int[] reel = new int[REEL_LEN];
    private double spinT = 0.0, spinDur = 6.2;
    private double startOffset = 0.0, targetOffset = 0.0, offset = 0.0;
    private int lastTickIdx = -1;
    private double revealT = 0.0;
    private double time = 0.0;
    private Drop pending;
    private final List<double[]> burst = new ArrayList<>(); // x, y, vx, vy, life, rgb
    private double stepPx = 164.0;

    public CaseOpening(Host host) {
        this.host = host;
        for (int i = 0; i < REEL_LEN; i++) reel[i] = rollItem();
    }

    public boolean isBusy() { return state == State.SPINNING; }

    public void onEscape() {
        if (state == State.REVEAL) state = State.IDLE;
        else if (state == State.IDLE) host.back();
    }

    private int rollItem() {
        double r = rng.nextDouble(), acc = 0.0;
        int tier = 0;
        for (int i = 0; i < RARITY_ODDS.length; i++) {
            acc += RARITY_ODDS[i];
            if (r < acc) { tier = i; break; }
        }
        List<Integer> c = new ArrayList<>();
        for (int i = 0; i < POOL.length; i++) if (POOL[i].rarity == tier) c.add(i);
        return c.get(rng.nextInt(c.size()));
    }

    /** Старт прокрутки. Возвращает false, если нет ключей. */
    public boolean tryOpen() {
        GameSettings st = host.settings();
        if (state == State.SPINNING) return false;
        if (st.caseKeys <= 0) {
            host.sound().playEmptyClick();
            return false;
        }
        st.caseKeys--;
        int win = rollItem();
        double f = Math.pow(rng.nextDouble(), 1.35);
        pending = new Drop(win, (int) Math.round(f * 1000), rng.nextDouble() < 0.10);
        for (int i = 0; i < REEL_LEN; i++) reel[i] = rollItem();
        reel[WIN_IDX] = win;
        // «Почти выпало»: рядом с выигрышем иногда кладём редкий предмет — интрига как в CS2
        if (rng.nextDouble() < 0.45) reel[WIN_IDX + (rng.nextBoolean() ? 1 : -1)] = 13 + rng.nextInt(5);
        startOffset = 4 * stepPx;
        targetOffset = WIN_IDX * stepPx + (rng.nextDouble() - 0.5) * stepPx * 0.84;
        offset = startOffset;
        spinT = 0.0;
        spinDur = 5.8 + rng.nextDouble() * 0.8;
        lastTickIdx = -1;
        state = State.SPINNING;
        host.sound().playCaseOpen();
        st.save();
        return true;
    }

    public void update(double dt) {
        time += dt;
        if (state == State.SPINNING) {
            spinT += dt;
            double p = Math.min(1.0, spinT / spinDur);
            double e = 1.0 - Math.pow(1.0 - p, 4.2);
            offset = startOffset + (targetOffset - startOffset) * e;
            int idx = (int) Math.floor((offset + stepPx / 2) / stepPx);
            if (idx != lastTickIdx) {
                if (lastTickIdx >= 0) host.sound().playCaseTick();
                lastTickIdx = idx;
            }
            if (spinT >= spinDur + 0.35) finishSpin();
        } else if (state == State.REVEAL) {
            revealT += dt;
        }
        for (int i = burst.size() - 1; i >= 0; i--) {
            double[] b = burst.get(i);
            b[4] -= dt;
            if (b[4] <= 0) { burst.remove(i); continue; }
            b[3] += 380 * dt;
            b[2] *= (1.0 - 1.2 * dt);
            b[0] += b[2] * dt;
            b[1] += b[3] * dt;
        }
    }

    private void finishSpin() {
        state = State.REVEAL;
        revealT = 0.0;
        GameSettings st = host.settings();
        st.casesOpened++;
        List<String> inv = new ArrayList<>(parseInventoryRaw(st));
        inv.add(pending.encode());
        while (inv.size() > 40) inv.remove(0);
        st.caseInventory = String.join("|", inv);
        st.save();
        int rar = pending.item().rarity;
        host.sound().playCaseReveal(rar);
        host.achievement("case_1");
        if (st.casesOpened >= 10) host.achievement("case_10");
        if (rar >= 3) host.achievement("covert");
        if (rar == 4) host.achievement("knife");
        int n = 60 + rar * 40;
        for (int i = 0; i < n; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double sp = 120 + rng.nextDouble() * 520;
            burst.add(new double[]{0, 0, Math.cos(a) * sp, Math.sin(a) * sp - 160, 0.8 + rng.nextDouble() * 1.1,
                    rng.nextDouble() < 0.7 ? RARITY_RGB[rar] : 0xFFFFFF});
        }
    }

    private static List<String> parseInventoryRaw(GameSettings st) {
        List<String> out = new ArrayList<>();
        if (st.caseInventory == null || st.caseInventory.isEmpty()) return out;
        for (String s : st.caseInventory.split("\\|")) if (Drop.decode(s) != null) out.add(s);
        return out;
    }

    public static List<Drop> parseInventory(GameSettings st) {
        List<Drop> out = new ArrayList<>();
        for (String s : parseInventoryRaw(st)) out.add(Drop.decode(s));
        return out;
    }

    private void tradeUpContract() {
        GameSettings st = host.settings();
        List<String> inv = parseInventoryRaw(st);
        if (inv.size() < 3) { host.sound().playEmptyClick(); return; }
        // Отдаём 3 самых дешёвых предмета (по редкости), самые ценные остаются
        for (int k = 0; k < 3; k++) {
            int worst = 0;
            for (int i = 1; i < inv.size(); i++) {
                if (Drop.decode(inv.get(i)).item().rarity < Drop.decode(inv.get(worst)).item().rarity) worst = i;
            }
            inv.remove(worst);
        }
        st.caseInventory = String.join("|", inv);
        st.caseKeys = Math.min(999, st.caseKeys + 1);
        st.save();
        host.sound().playBuy();
        host.achievement("contract");
    }

    // =====================================================================================
    //                                       РЕНДЕР
    // =====================================================================================

    private static Color rgba(int rgb, double a) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, (int) Math.max(0, Math.min(255, a * 255)));
    }

    private static Font font(int style, double size) {
        return new Font("SansSerif", style, (int) Math.max(8, Math.round(size)));
    }

    private static void centered(Graphics2D g, String s, double cx, double y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) y);
    }

    private static Color skinBody(GameSettings.WeaponSkin sk) { return sk.primary == 0 ? new Color(0xB8C0CC) : new Color(sk.primary); }
    private static Color skinAccent(GameSettings.WeaponSkin sk) { return sk.accent == 0 ? new Color(0xFFB040) : new Color(sk.accent); }

    private void drawItemArt(Graphics2D g, Item it, double x, double y, double w, double h) {
        AffineTransform saved = g.getTransform();
        double sc = Math.min(w / 92.0, h / 44.0);
        g.translate(x + (w - 92 * sc) / 2, y + (h - 44 * sc) / 2);
        g.scale(sc, sc);
        // Тень силуэта
        g.translate(1.5, 2.0);
        host.silhouette(g, it.type, 0, 0, 92, 44, new Color(0, 0, 0, 120), new Color(0, 0, 0, 120));
        g.translate(-1.5, -2.0);
        host.silhouette(g, it.type, 0, 0, 92, 44, skinBody(it.skin), skinAccent(it.skin));
        g.setTransform(saved);
    }

    private void drawItemCard(Graphics2D g, Item it, double x, double y, double w, double h, double s, boolean big, boolean statTrak) {
        int rc = RARITY_RGB[it.rarity];
        RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, w, h, 10 * s, 10 * s);
        g.setPaint(new GradientPaint((float) x, (float) y, new Color(28, 34, 46, 240), (float) x, (float) (y + h), new Color(14, 18, 26, 245)));
        g.fill(rr);
        Shape oc = g.getClip();
        g.clip(rr);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x + w / 2, y + h * 0.95), (float) (w * 0.75),
                new float[]{0f, 1f}, new Color[]{rgba(rc, 0.55), rgba(rc, 0.0)}));
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(rgba(rc, 1.0));
        g.fill(new Rectangle2D.Double(x, y + h - 5 * s, w, 5 * s));
        g.setClip(oc);
        drawItemArt(g, it, x + w * 0.08, y + h * 0.10, w * 0.84, h * (big ? 0.55 : 0.50));
        g.setFont(font(Font.PLAIN, (big ? 12 : 10) * s));
        g.setColor(new Color(170, 184, 204));
        String wn = (statTrak ? "StatTrak™ " : "") + it.weaponName();
        centered(g, wn, x + w / 2, y + h - (big ? 34 : 26) * s);
        g.setFont(font(Font.BOLD, (big ? 15 : 12) * s));
        g.setColor(Color.WHITE);
        centered(g, it.skinName, x + w / 2, y + h - (big ? 16 : 11) * s);
        g.setColor(rgba(rc, 0.65));
        g.draw(rr);
    }

    public void render(Graphics2D g, int w, int h) {
        GameSettings st = host.settings();
        double s = Math.min(w / 1280.0, h / 720.0);
        stepPx = 164.0 * s;
        if (state != State.SPINNING) {
            // При смене разрешения держим смещение в шагах карточек
            startOffset = 4 * stepPx;
            if (offset < startOffset) offset = startOffset;
        }
        int mx = host.mouseX(), my = host.mouseY();

        host.glassPanel(g, 0, 0, w, h, 0x060A12, 150, null, null);
        g.setPaint(new GradientPaint(0, 0, new Color(10, 6, 20, 120), 0, h, new Color(4, 6, 10, 200)));
        g.fillRect(0, 0, w, h);

        // ===== Шапка =====
        int pad = (int) (28 * s);
        g.setFont(font(Font.BOLD, 26 * s));
        g.setPaint(new GradientPaint(pad, 0, new Color(255, 222, 120), pad + (int) (420 * s), 0, new Color(255, 140, 40)));
        g.drawString("КЕЙС «АЛКАШ» 2026", pad, (int) (48 * s));
        g.setFont(font(Font.PLAIN, 13 * s));
        g.setColor(new Color(176, 192, 214));
        g.drawString("18 предметов • шанс ножа ★ 1% • StatTrak™ 10% • скин применяется к оружию в бою и в меню", pad, (int) (70 * s));

        // Ключи
        String keysTxt = "КЛЮЧИ: " + st.caseKeys;
        g.setFont(font(Font.BOLD, 16 * s));
        int kw = g.getFontMetrics().stringWidth(keysTxt) + (int) (62 * s);
        int kx = w - pad - kw, ky = (int) (22 * s), kh = (int) (40 * s);
        g.setColor(new Color(22, 30, 44, 230));
        g.fillRoundRect(kx, ky, kw, kh, (int) (10 * s), (int) (10 * s));
        g.setColor(new Color(255, 200, 80, 200));
        g.drawRoundRect(kx, ky, kw, kh, (int) (10 * s), (int) (10 * s));
        drawKeyIcon(g, kx + 22 * s, ky + kh / 2.0, s);
        g.setColor(new Color(255, 220, 120));
        g.drawString(keysTxt, kx + (int) (46 * s), ky + (int) (26 * s));
        g.setFont(font(Font.PLAIN, 11 * s));
        g.setColor(new Color(150, 166, 188));
        String earn = "Открыто: " + st.casesOpened + "  •  +1 ключ за 5 фрагов, +2 за победу";
        g.drawString(earn, w - pad - g.getFontMetrics().stringWidth(earn), ky + kh + (int) (16 * s));

        // ===== Рулетка =====
        int reelY = (int) (104 * s), reelH = (int) (170 * s);
        int vx0 = pad, vx1 = w - pad;
        g.setColor(new Color(8, 10, 16, 220));
        g.fillRoundRect(vx0, reelY, vx1 - vx0, reelH, (int) (12 * s), (int) (12 * s));
        Shape oldClip = g.getClip();
        g.clipRect(vx0, reelY, vx1 - vx0, reelH);
        double cardW = 158 * s, cardH = 150 * s;
        double cx = w / 2.0;
        for (int i = 0; i < REEL_LEN; i++) {
            double ccx = cx + i * stepPx - offset;
            if (ccx + cardW < vx0 || ccx - cardW > vx1) continue;
            drawItemCard(g, POOL[reel[i]], ccx - cardW / 2, reelY + (reelH - cardH) / 2, cardW, cardH, s, false, false);
        }
        // Затухание к краям
        int fadeW = (int) (170 * s);
        g.setPaint(new GradientPaint(vx0, 0, new Color(8, 10, 16, 255), vx0 + fadeW, 0, new Color(8, 10, 16, 0)));
        g.fillRect(vx0, reelY, fadeW, reelH);
        g.setPaint(new GradientPaint(vx1 - fadeW, 0, new Color(8, 10, 16, 0), vx1, 0, new Color(8, 10, 16, 255)));
        g.fillRect(vx1 - fadeW, reelY, fadeW, reelH);
        g.setClip(oldClip);
        // Центральный маркер
        double glow = state == State.SPINNING ? 0.7 + 0.3 * Math.sin(time * 18) : 0.85;
        g.setColor(rgba(0xFFC83C, 0.35 * glow));
        g.fill(new Rectangle2D.Double(cx - 5 * s, reelY - 6 * s, 10 * s, reelH + 12 * s));
        g.setColor(new Color(255, 206, 70));
        g.fill(new Rectangle2D.Double(cx - 1.5 * s, reelY - 6 * s, 3 * s, reelH + 12 * s));
        g.fillPolygon(new int[]{(int) (cx - 11 * s), (int) (cx + 11 * s), (int) cx}, new int[]{reelY - (int) (10 * s), reelY - (int) (10 * s), reelY + (int) (6 * s)}, 3);
        g.fillPolygon(new int[]{(int) (cx - 11 * s), (int) (cx + 11 * s), (int) cx}, new int[]{reelY + reelH + (int) (10 * s), reelY + reelH + (int) (10 * s), reelY + reelH - (int) (6 * s)}, 3);
        g.setColor(new Color(255, 255, 255, 30));
        g.drawRoundRect(vx0, reelY, vx1 - vx0, reelH, (int) (12 * s), (int) (12 * s));

        // ===== Содержимое кейса =====
        int contY = reelY + reelH + (int) (30 * s);
        g.setFont(font(Font.BOLD, 13 * s));
        g.setColor(new Color(200, 214, 232));
        g.drawString("СОДЕРЖИМОЕ КЕЙСА", pad, contY);
        // Легенда редкостей
        int lx = pad + g.getFontMetrics().stringWidth("СОДЕРЖИМОЕ КЕЙСА") + (int) (26 * s);
        g.setFont(font(Font.BOLD, 10 * s));
        for (int r = 0; r < 5; r++) {
            g.setColor(new Color(RARITY_RGB[r]));
            g.fillRoundRect(lx, contY - (int) (9 * s), (int) (10 * s), (int) (10 * s), 3, 3);
            String lbl = RARITY_NAMES[r] + " " + (int) Math.round(RARITY_ODDS[r] * 100) + "%";
            g.setColor(new Color(190, 202, 220));
            g.drawString(lbl, lx + (int) (14 * s), contY);
            lx += g.getFontMetrics().stringWidth(lbl) + (int) (30 * s);
        }
        int cols = 9;
        double gap = 8 * s;
        double cw = (w - pad * 2 - gap * (cols - 1)) / cols;
        double ch = 86 * s;
        Item hoverItem = null;
        for (int i = 0; i < POOL.length; i++) {
            int c = i % cols, r = i / cols;
            double x = pad + c * (cw + gap), y = contY + 10 * s + r * (ch + gap);
            drawMiniCard(g, POOL[i], x, y, cw, ch, s);
            if (mx >= x && mx <= x + cw && my >= y && my <= y + ch) hoverItem = POOL[i];
        }

        // ===== Инвентарь =====
        List<Drop> inv = parseInventory(st);
        int invY = (int) (contY + 10 * s + 2 * (ch + gap) + 26 * s);
        g.setFont(font(Font.BOLD, 13 * s));
        g.setColor(new Color(200, 214, 232));
        g.drawString("ИНВЕНТАРЬ (" + inv.size() + ")  —  клик по предмету = экипировать", pad, invY);
        double icw = cw, ich = 66 * s;
        int shown = Math.min(cols, inv.size());
        for (int i = 0; i < shown; i++) {
            final Drop d = inv.get(inv.size() - 1 - i);
            double x = pad + i * (icw + gap), y = invY + 10 * s;
            Rectangle rr = new Rectangle((int) x, (int) y, (int) icw, (int) ich);
            boolean hov = rr.contains(mx, my);
            boolean equipped = st.weaponSkin == d.item().skin;
            int rc = RARITY_RGB[d.item().rarity];
            g.setColor(hov ? new Color(40, 52, 72, 240) : new Color(20, 26, 38, 235));
            g.fillRoundRect(rr.x, rr.y, rr.width, rr.height, (int) (8 * s), (int) (8 * s));
            g.setColor(rgba(rc, 1));
            g.fillRect(rr.x, rr.y + rr.height - (int) (3 * s), rr.width, (int) (3 * s));
            drawItemArt(g, d.item(), x + 6 * s, y + 4 * s, icw * 0.5, ich - 12 * s);
            g.setFont(font(Font.BOLD, 9 * s));
            g.setColor(Color.WHITE);
            g.drawString(d.item().skinName, (int) (x + icw * 0.50), (int) (y + 20 * s));
            g.setFont(font(Font.PLAIN, 9 * s));
            g.setColor(new Color(160, 176, 198));
            g.drawString((d.statTrak ? "ST™ " : "") + Weapon.nameOf(d.item().type), (int) (x + icw * 0.50), (int) (y + 33 * s));
            g.setColor(rgba(rc, 1));
            g.drawString(String.format("%.3f", d.wear1000 / 1000.0), (int) (x + icw * 0.50), (int) (y + 46 * s));
            if (hov) {
                g.setColor(new Color(255, 214, 110));
                g.drawRoundRect(rr.x, rr.y, rr.width, rr.height, (int) (8 * s), (int) (8 * s));
            }
            if (state != State.SPINNING) host.clickable(rr, () -> host.equip(d.item().type, d.item().skin));
        }
        if (inv.isEmpty()) {
            g.setFont(font(Font.PLAIN, 12 * s));
            g.setColor(new Color(140, 154, 176));
            g.drawString("Пока пусто — откройте первый кейс!", pad, (int) (invY + 36 * s));
        }

        // ===== Кнопки =====
        int by = h - (int) (64 * s), bh = (int) (46 * s);
        host.button(g, new Rectangle(pad, by, (int) (200 * s), bh), "← НАЗАД (ESC)", new Color(52, 64, 84), false,
                () -> { if (state != State.SPINNING) { state = State.IDLE; host.back(); } });
        int ow = (int) (440 * s);
        Rectangle openR = new Rectangle(w / 2 - ow / 2, by, ow, bh);
        boolean canOpen = st.caseKeys > 0 && state != State.SPINNING;
        if (canOpen) {
            double pulse = 0.5 + 0.5 * Math.sin(time * 4.0);
            g.setColor(rgba(0xFFB030, 0.18 + 0.18 * pulse));
            g.fillRoundRect(openR.x - (int) (6 * s), openR.y - (int) (6 * s), openR.width + (int) (12 * s), openR.height + (int) (12 * s), (int) (18 * s), (int) (18 * s));
        }
        String openLbl = state == State.SPINNING ? "КРУТИМ…" : (st.caseKeys > 0 ? "◈ ОТКРЫТЬ КЕЙС  •  1 КЛЮЧ" : "НЕТ КЛЮЧЕЙ — СЫГРАЙТЕ МАТЧ");
        host.button(g, openR, openLbl, canOpen ? new Color(236, 160, 34) : new Color(70, 74, 84), true,
                () -> { if (state == State.REVEAL) state = State.IDLE; tryOpen(); });
        boolean canTrade = inv.size() >= 3 && state != State.SPINNING;
        host.button(g, new Rectangle(w - pad - (int) (300 * s), by, (int) (300 * s), bh),
                "КОНТРАКТ: 3 ПРЕДМЕТА → 1 КЛЮЧ", canTrade ? new Color(110, 52, 168) : new Color(60, 62, 74), false,
                () -> { if (state != State.SPINNING) tradeUpContract(); });

        // Подсказка по предмету под курсором
        if (hoverItem != null && state != State.REVEAL) {
            String tip = hoverItem.weaponName() + " | " + hoverItem.skinName + "  —  " + RARITY_NAMES[hoverItem.rarity];
            g.setFont(font(Font.BOLD, 12 * s));
            int tw = g.getFontMetrics().stringWidth(tip) + (int) (20 * s);
            int tx = Math.min(w - tw - 8, mx + 14), ty = my - (int) (30 * s);
            g.setColor(new Color(10, 14, 22, 240));
            g.fillRoundRect(tx, ty, tw, (int) (24 * s), 8, 8);
            g.setColor(new Color(RARITY_RGB[hoverItem.rarity]));
            g.drawRoundRect(tx, ty, tw, (int) (24 * s), 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(tip, tx + (int) (10 * s), ty + (int) (17 * s));
        }

        if (state == State.REVEAL && pending != null) renderReveal(g, w, h, s);
    }

    private void drawMiniCard(Graphics2D g, Item it, double x, double y, double w, double h, double s) {
        int rc = RARITY_RGB[it.rarity];
        g.setColor(new Color(18, 24, 36, 230));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 8 * s, 8 * s));
        g.setPaint(new GradientPaint(0, (float) (y + h * 0.4), rgba(rc, 0.0), 0, (float) (y + h), rgba(rc, 0.35)));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 8 * s, 8 * s));
        g.setColor(rgba(rc, 1));
        g.fill(new Rectangle2D.Double(x, y + h - 3 * s, w, 3 * s));
        drawItemArt(g, it, x + w * 0.12, y + 4 * s, w * 0.76, h * 0.52);
        g.setFont(font(Font.BOLD, 10 * s));
        g.setColor(Color.WHITE);
        centered(g, it.skinName, x + w / 2, y + h - 18 * s);
        g.setFont(font(Font.PLAIN, 9 * s));
        g.setColor(new Color(160, 176, 198));
        centered(g, it.weaponName(), x + w / 2, y + h - 7 * s);
    }

    private void renderReveal(Graphics2D g, int w, int h, double s) {
        Item it = pending.item();
        int rc = RARITY_RGB[it.rarity];
        double e = 1 - Math.pow(1 - Math.min(1.0, revealT / 0.45), 3);
        g.setColor(new Color(0, 0, 0, (int) (170 * e)));
        g.fillRect(0, 0, w, h);
        double cx = w / 2.0, cy = h * 0.43;

        // Вращающиеся лучи цвета редкости
        int rays = 16;
        double len = Math.max(w, h) * 0.6 * e;
        for (int i = 0; i < rays; i++) {
            double a0 = time * 0.35 + i * Math.PI * 2 / rays;
            double a1 = a0 + Math.PI / rays * 0.6;
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) Math.max(1, len), new float[]{0f, 1f},
                    new Color[]{rgba(rc, 0.45), rgba(rc, 0.0)}));
            g.fillPolygon(new int[]{(int) cx, (int) (cx + Math.cos(a0) * len), (int) (cx + Math.cos(a1) * len)},
                    new int[]{(int) cy, (int) (cy + Math.sin(a0) * len), (int) (cy + Math.sin(a1) * len)}, 3);
        }
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) (260 * s), new float[]{0f, 1f},
                new Color[]{rgba(rc, 0.5 * e), rgba(rc, 0)}));
        g.fill(new Ellipse2D.Double(cx - 260 * s, cy - 260 * s, 520 * s, 520 * s));

        // Частицы-вспышка
        for (double[] b : burst) {
            double a = Math.min(1.0, b[4]);
            g.setColor(rgba((int) b[5], a));
            double sz = 3 * s;
            g.fill(new Ellipse2D.Double(cx + b[0] * s - sz / 2, cy + b[1] * s - sz / 2, sz, sz));
        }

        // Карточка
        double sc = 0.6 + 0.4 * (1 - Math.pow(1 - Math.min(1.0, revealT / 0.5), 4)) + 0.04 * Math.sin(revealT * 9) * Math.exp(-revealT * 3);
        double cw = 420 * s * sc, ch = 270 * s * sc;
        drawItemCard(g, it, cx - cw / 2, cy - ch / 2, cw, ch, s * sc, true, pending.statTrak);
        if (pending.statTrak) {
            g.setFont(font(Font.BOLD, 11 * s));
            g.setColor(new Color(207, 106, 50));
            g.fillRoundRect((int) (cx - cw / 2 + 10 * s), (int) (cy - ch / 2 + 10 * s), (int) (84 * s), (int) (20 * s), 6, 6);
            g.setColor(Color.WHITE);
            g.drawString("StatTrak™", (int) (cx - cw / 2 + 17 * s), (int) (cy - ch / 2 + 24 * s));
        }

        // Подпись
        double ta = Math.min(1.0, Math.max(0, (revealT - 0.25) / 0.4));
        g.setFont(font(Font.BOLD, 13 * s));
        g.setColor(rgba(rc, ta));
        centered(g, RARITY_NAMES[it.rarity].toUpperCase(), cx, cy + ch / 2 + 26 * s);
        g.setFont(font(Font.BOLD, 22 * s));
        g.setColor(rgba(0xFFFFFF, ta));
        centered(g, (pending.statTrak ? "StatTrak™ " : "") + it.weaponName() + " | " + it.skinName, cx, cy + ch / 2 + 54 * s);
        g.setFont(font(Font.PLAIN, 13 * s));
        g.setColor(rgba(0xB8C8DC, ta));
        centered(g, pending.wearName() + "  •  Float " + String.format("%.4f", pending.wear1000 / 1000.0), cx, cy + ch / 2 + 76 * s);

        // Модальное окно перехватывает клики по всему, что под ним
        host.clickable(new Rectangle(0, 0, w, h), () -> { });
        if (revealT > 0.5) {
            int bw = (int) (210 * s), bh = (int) (44 * s), gapB = (int) (14 * s);
            int by = (int) (cy + ch / 2 + 94 * s);
            int bx = (int) (cx - (bw * 3 + gapB * 2) / 2.0);
            final Drop d = pending;
            host.button(g, new Rectangle(bx, by, bw, bh), "✔ ЭКИПИРОВАТЬ", new Color(40, 150, 90), false,
                    () -> { host.equip(d.item().type, d.item().skin); state = State.IDLE; });
            host.button(g, new Rectangle(bx + bw + gapB, by, bw, bh),
                    host.settings().caseKeys > 0 ? "⟳ ЕЩЁ ОДИН (" + host.settings().caseKeys + ")" : "НЕТ КЛЮЧЕЙ",
                    host.settings().caseKeys > 0 ? new Color(236, 160, 34) : new Color(70, 74, 84), true,
                    () -> { state = State.IDLE; tryOpen(); });
            host.button(g, new Rectangle(bx + (bw + gapB) * 2, by, bw, bh), "ЗАКРЫТЬ", new Color(52, 64, 84), false,
                    () -> state = State.IDLE);
        }
    }

    private static void drawKeyIcon(Graphics2D g, double x, double y, double s) {
        g.setColor(new Color(255, 206, 80));
        g.setStroke(new BasicStroke((float) (2.6 * s)));
        g.draw(new Ellipse2D.Double(x - 9 * s, y - 6 * s, 12 * s, 12 * s));
        g.draw(new java.awt.geom.Line2D.Double(x + 3 * s, y, x + 14 * s, y));
        g.draw(new java.awt.geom.Line2D.Double(x + 10 * s, y, x + 10 * s, y + 5 * s));
        g.draw(new java.awt.geom.Line2D.Double(x + 14 * s, y, x + 14 * s, y + 4 * s));
        g.setStroke(new BasicStroke(1f));
    }

    /** Для самотеста: мгновенно провернуть рулетку до показа результата. */
    public void forceSpinForTest() {
        if (!tryOpen()) return;
        for (int i = 0; i < 600 && state == State.SPINNING; i++) update(1.0 / 60.0);
        for (int i = 0; i < 40; i++) update(1.0 / 60.0);
    }

    /** Для самотеста: середина прокрутки. */
    public void halfSpinForTest() {
        if (!tryOpen()) return;
        for (int i = 0; i < 90; i++) update(1.0 / 60.0);
    }
}
