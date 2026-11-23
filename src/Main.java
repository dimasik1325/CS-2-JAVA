import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Точка входа в игру CS-2 JAVA 8.0 (Hyper-Turbo Multi-Core Engine • Pro Graphics Studio Edition).
 * Управляет окном JFrame, безрамочным полноэкранным режимом F11 / Alt+Enter (без чёрного экрана!)
 * и запуском сверхбыстрого многоядерного 3D-движка Engine.
 */
public class Main {

    static {
        // Отключаем нестабильный Direct3D/DDraw оверлей на Windows (причина чёрного экрана при F11)
        // и фиксируем чёткие координаты 1:1 до инициализации подсистемы AWT/Swing
        System.setProperty("sun.java2d.d3d", "false");
        System.setProperty("sun.java2d.noddraw", "true");
        System.setProperty("sun.java2d.uiScale", "1.0");
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            String outDir = args.length > 1 ? args[1] : ".";
            runHeadlessSelfTest(outDir);
            return;
        }
        if (args.length > 1 && "--export-icon".equals(args[0])) {
            try {
                File outDir = new File(args[1]);
                outDir.mkdirs();
                for (int sz : new int[]{16, 32, 48, 64, 128, 256}) {
                    ImageIO.write(renderAppIcon(sz), "png", new File(outDir, "icon_" + sz + ".png"));
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            return;
        }

        SwingUtilities.invokeLater(() -> {
            final JFrame frame = new JFrame("CS-2 JAVA 8.0 | Source 2 Panorama • 4 Multi-Level Maps 68×68 м • Realistic Models");
            frame.setIconImages(createAppIcons());
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.getContentPane().setBackground(new Color(10, 14, 22));

            final Engine engine = new Engine(false);
            frame.add(engine, BorderLayout.CENTER);
            frame.pack();
            frame.setMinimumSize(new Dimension(960, 540));
            frame.setLocationRelativeTo(null);

            final Rectangle[] savedWindowBounds = new Rectangle[]{frame.getBounds()};

            frame.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    engine.notifyWindowModeChanged();
                }
            });

            engine.setFullscreenListener(fullscreen -> SwingUtilities.invokeLater(
                    () -> applyWindowMode(frame, engine, fullscreen, savedWindowBounds)));

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    engine.getSettings().saveSync();
                    engine.stop();
                    frame.dispose();
                    System.exit(0);
                }
            });

            if (engine.getSettings().fullscreen) {
                applyWindowMode(frame, engine, true, savedWindowBounds);
            } else {
                frame.setVisible(true);
            }
            engine.requestFocusInWindow();
            engine.start();
        });
    }

    /**
     * Надёжное переключение между оконным и безрамочным полноэкранным режимом (Borderless Fullscreen).
     */
    private static void applyWindowMode(JFrame frame, Engine engine, boolean fullscreen, Rectangle[] savedBoundsHolder) {
        GraphicsConfiguration gc = frame.getGraphicsConfiguration();
        if (gc == null) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle screenBounds = gc.getBounds();

        if (fullscreen) {
            if (frame.isVisible() && !frame.isUndecorated()) {
                Rectangle cur = frame.getBounds();
                if (cur.width >= 640 && cur.height >= 480) {
                    savedBoundsHolder[0] = cur;
                }
            }
            if (!frame.isUndecorated()) {
                frame.dispose();
                frame.setUndecorated(true);
            }
            frame.setResizable(false);
            frame.setExtendedState(JFrame.NORMAL);
            frame.setBounds(screenBounds);
            frame.setVisible(true);
            frame.setBounds(screenBounds);
        } else {
            GraphicsDevice gd = gc.getDevice();
            if (gd != null && gd.getFullScreenWindow() == frame) {
                try {
                    gd.setFullScreenWindow(null);
                } catch (Throwable ignored) {
                }
            }
            if (frame.isUndecorated()) {
                frame.dispose();
                frame.setUndecorated(false);
            }
            frame.setResizable(true);
            frame.setExtendedState(JFrame.NORMAL);
            Rectangle restore = savedBoundsHolder[0];
            if (restore != null && restore.width >= 640 && restore.height >= 480) {
                frame.setBounds(restore);
            } else {
                engine.setPreferredSize(new Dimension(Engine.DEFAULT_WINDOW_WIDTH, Engine.DEFAULT_WINDOW_HEIGHT));
                frame.pack();
                frame.setLocationRelativeTo(null);
            }
            frame.setVisible(true);
        }

        frame.validate();
        frame.toFront();
        engine.revalidate();
        engine.notifyWindowModeChanged();
        engine.requestFocusInWindow();
        engine.repaint();
    }

    /**
     * Автономная проверка CS-2 JAVA 8.0: Hyper-Turbo аналитический растеризатор, Deferred Skybox,
     * Студия Графики (24 настройки + 5 пресетов), живой осциллограф FPS/мс, динамическое Авто-FPS разрешение,
     * а также проверка Wallbang и стресс-тест 1000 кликов!
     */
    private static void runHeadlessSelfTest(String outDir) {
        try {
            File dir = new File(outDir);
            if (!dir.exists()) dir.mkdirs();

            Engine engine = new Engine(true);
            Player player = engine.getPlayer();

            // Проверяем все 5 пресетов графики (ESPORTS .. ULTRA)
            for (GameSettings.QualityPreset pr : GameSettings.QualityPreset.values()) {
                engine.getSettings().applyGraphicsPreset(pr);
                engine.renderOffscreenForTest(1280, 720);
            }

            // Включаем профиль HIGH (1:1 Native + FXAA Turbo + CAS + Vibrance + График FPS)
            engine.getSettings().applyGraphicsPreset(GameSettings.QualityPreset.HIGH);
            engine.getSettings().antiAliasingMode = GameSettings.AntiAliasingMode.SPAN_FXAA_ULTRA;
            engine.getSettings().casSharpening = GameSettings.CasSharpeningMode.CRISP;
            engine.getSettings().colorVibrance = GameSettings.ColorVibranceMode.VIBRANT;
            engine.getSettings().telemetryMode = GameSettings.HudTelemetryMode.FULL_GRAPH;
            engine.getSettings().weatherMode = GameSettings.WeatherMode.CLEAR;
            engine.getSettings().syncDerivedFlags();

            // 1. Главное меню Panorama Glass 8.0
            engine.getSettings().mapTheme = GameSettings.MapTheme.INFERNO;
            engine.getSettings().gameMode = GameSettings.GameMode.SIEGE_WAVES;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.PRINTSTREAM;
            engine.startNewMatch();
            engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
            for (int i = 0; i < 24; i++) {
                engine.updateGame(1.0 / 60.0);
                engine.renderOffscreenForTest(1280, 720);
            }
            saveTestScreenshot(engine, new File(dir, "01_main_menu_v8.png"));

            // 2. Студия Графики 8.0 — Страница 1 (Экран, FPS, Сглаживание, CAS, LOD, Дальность)
            engine.openSettingsForTest(0, 0);
            saveTestScreenshot(engine, new File(dir, "02_graphics_studio_page1_v8.png"));

            // 3. Студия Графики 8.0 — Страница 2 (Тени, Gouraud AO, Vibrance, Яркость, Туман, Виньетка, Частицы)
            engine.openSettingsForTest(0, 1);
            saveTestScreenshot(engine, new File(dir, "03_graphics_studio_page2_v8.png"));

            // 4. Боевой HUD Panorama 8.0 + Живой график мс под радаром + Керамбит + Авиаудар + Авто-Турель + Босс!
            engine.launchMatchFromMenu();
            player.addMoney(9000);
            player.addAirstrikeCharge(2);
            player.selectWeaponSlot(2);
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.GOLD_FADE;
            player.getCurrentWeapon().triggerInspect();
            for (int i = 0; i < 16; i++) engine.updateGame(1.0 / 60.0);
            player.getPosition().set(-3.2, 0.0, -13.0);
            player.setAngles(0.22, -0.06);
            engine.tryDeploySentryTurret();
            engine.tryCallPrecisionAirstrike();
            engine.trySummonHelicopterChallenge();
            player.tryTriggerDash(true, false, false, false);
            for (int i = 0; i < 10; i++) {
                engine.updateGame(1.0 / 60.0);
                engine.renderOffscreenForTest(1280, 720);
            }
            for (Bot b : engine.getBots()) {
                if (!b.isAlly()) {
                    b.setArchetype(Bot.Archetype.JUGGERNAUT);
                    b.getPosition().set(1.8, 0.0, -4.2);
                    break;
                }
            }
            player.setAngles(0.24, 0.16);
            saveTestScreenshot(engine, new File(dir, "04_combat_hud_graph_v8.png"));

            // 5. Проверка чёткости CAS + 512x512 Mip0 вплотную + M4A1-S Printstream
            player.equipPurchasedWeapon(Weapon.Type.M4A1S);
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.PRINTSTREAM;
            for (int i = 0; i < 16; i++) engine.updateGame(1.0 / 60.0);
            player.getPosition().set(-2.5, 0.0, -7.5);
            player.setAngles(0.55, -0.10);
            saveTestScreenshot(engine, new File(dir, "05_closeup_cas_crisp_v8.png"));

            // 6. Тур по всем 4 многоуровневым картам 8.0: Боевой HUD + Радар с callout-зонами
            GameSettings.MapTheme[] tourMaps = {GameSettings.MapTheme.INFERNO, GameSettings.MapTheme.DUST_II,
                    GameSettings.MapTheme.MIRAGE, GameSettings.MapTheme.NUKE};
            Weapon.Type[] tourGuns = {Weapon.Type.AK47, Weapon.Type.AWP, Weapon.Type.M4A1S, Weapon.Type.M249};
            for (int mi = 0; mi < tourMaps.length; mi++) {
                engine.getSettings().mapTheme = tourMaps[mi];
                engine.getSettings().gameMode = GameSettings.GameMode.BOMB_DEFUSAL;
                engine.getSettings().weaponSkin = GameSettings.WeaponSkin.STANDARD;
                engine.launchMatchFromMenu();
                player.equipPurchasedWeapon(tourGuns[mi]);
                for (int i = 0; i < 40; i++) engine.updateGame(1.0 / 60.0);
                Vec3 sp = engine.getMap().getPlayerSpawn();
                Vec3 site = engine.getMap().getBombsiteASpot();
                player.getPosition().set(sp.x, sp.y, sp.z);
                double dx = site.x - sp.x, dz = site.z - sp.z;
                player.setAngles(Math.atan2(dx, dz), -0.02);
                for (int i = 0; i < 6; i++) {
                    engine.updateGame(1.0 / 60.0);
                    engine.renderOffscreenForTest(1280, 720);
                }
                saveTestScreenshot(engine, new File(dir, "06_map_" + tourMaps[mi].name().toLowerCase() + "_v8.png"));
                if (mi == 0) {
                    engine.setBuyMenuOpen(true);
                    saveTestScreenshot(engine, new File(dir, "07_buy_menu_v8.png"));
                    engine.setBuyMenuOpen(false);
                }
            }

            // Проверка механики Wallbang на Dust II
            engine.getSettings().mapTheme = GameSettings.MapTheme.DUST_II;
            engine.getSettings().gameMode = GameSettings.GameMode.BOMB_DEFUSAL;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.STANDARD;
            engine.launchMatchFromMenu();
            player.equipPurchasedWeapon(Weapon.Type.AWP);
            for (int i = 0; i < 20; i++) engine.updateGame(1.0 / 60.0);
            Bot wbTarget = null;
            for (Bot b : engine.getBots()) {
                if (!b.isAlly()) { wbTarget = b; break; }
            }
            if (wbTarget != null) {
                for (Bot b : engine.getBots()) {
                    if (b != wbTarget) b.getPosition().set(14.0, 0.0, 14.0);
                }
                player.getPosition().set(-1.5, 0.0, -3.5);
                wbTarget.getPosition().set(-1.5, 0.0, 2.8);
                int hpBefore = wbTarget.getHealth();
                Vec3 dirWb = wbTarget.getPosition().add(0, 1.2, 0).sub(player.getEyePosition()).normalize();
                player.setAngles(Math.atan2(dirWb.x, dirWb.z), Math.asin(dirWb.y));
                player.getCurrentWeapon().toggleSecondaryAction();
                engine.tryFireCurrentWeapon(false);
                if (wbTarget.getHealth() >= hpBefore && wbTarget.isAlive()) {
                    throw new IllegalStateException("AWP must penetrate Dust II Mid Double Doors (Wallbang)!");
                }
            }

            // Бенчмарк НАТИВНОГО HD 1280x720 с Hyper-Turbo 8.0!
            BufferedImage benchImg = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
            Graphics2D bg = benchImg.createGraphics();
            engine.getSettings().mapTheme = GameSettings.MapTheme.INFERNO;
            engine.getSettings().applyGraphicsPreset(GameSettings.QualityPreset.HIGH);
            engine.startNewMatch();
            for (int i = 0; i < 20; i++) {
                engine.updateGame(1.0 / 60.0);
                engine.renderCompleteFrame(bg, 1280, 720);
            }
            long t0 = System.nanoTime();
            int benchFrames = 60;
            for (int i = 0; i < benchFrames; i++) {
                engine.updateGame(1.0 / 60.0);
                engine.renderCompleteFrame(bg, 1280, 720);
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            // Доп. бенчмарк профиля КИБЕРСПОРТ (Esports Preset)
            engine.getSettings().applyGraphicsPreset(GameSettings.QualityPreset.ESPORTS);
            for (int i = 0; i < 15; i++) {
                engine.updateGame(1.0 / 60.0);
                engine.renderCompleteFrame(bg, 1280, 720);
            }
            long tEsports0 = System.nanoTime();
            for (int i = 0; i < benchFrames; i++) {
                engine.updateGame(1.0 / 60.0);
                engine.renderCompleteFrame(bg, 1280, 720);
            }
            long esportsMs = (System.nanoTime() - tEsports0) / 1_000_000L;
            bg.dispose();

            // Стресс-тест динамического авто-разрешения и мульти-разрешений Fullscreen
            engine.getSettings().applyGraphicsPreset(GameSettings.QualityPreset.HIGH);
            engine.getSettings().dynamicResolution = GameSettings.DynamicResolutionMode.TARGET_120;
            engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
            int[][] testResolutions = {{1280, 720}, {1920, 1080}, {2560, 1440}, {1280, 720}};
            for (int[] res : testResolutions) {
                engine.renderOffscreenForTest(res[0], res[1]);
                BufferedImage disp = engine.getDisplayedFrame();
                if (disp == null || disp.getWidth() != res[0] || disp.getHeight() != res[1]) {
                    throw new IllegalStateException("Fullscreen offscreen swap failed for " + res[0] + "x" + res[1]);
                }
            }

            engine.getSettings().fpsLimit = 0;
            engine.start();
            int registeredClicks = 0;
            for (int i = 0; i < 1000; i++) {
                engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
                int btnCount = engine.getPublishedButtonCount();
                if (btnCount <= 0) {
                    throw new IllegalStateException("publishedUiSnapshot became empty on click #" + i);
                }
                Point pt = engine.getPublishedButtonCenter(btnCount - 1 - (i % 4));
                if (pt != null && engine.dispatchUiClick(pt.x, pt.y, 1280, 720)) {
                    registeredClicks++;
                }
                if (i % 50 == 0) Thread.sleep(1);
            }
            engine.stop();
            engine.flushPendingInputTasks();

            if (registeredClicks != 1000) {
                throw new IllegalStateException("Lost UI clicks under load! Registered " + registeredClicks + "/1000");
            }

            // Возвращаем дефолтную чистую конфигурацию 8.0 для игрока
            engine.getSettings().applyGraphicsPreset(GameSettings.QualityPreset.HIGH);
            engine.getSettings().mapTheme = GameSettings.MapTheme.INFERNO;
            engine.getSettings().gameMode = GameSettings.GameMode.SIEGE_WAVES;
            engine.getSettings().botDifficulty = GameSettings.BotDifficulty.NORMAL;
            engine.getSettings().timeOfDay = GameSettings.TimeOfDay.DAY;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.ASIIMOV;
            engine.getSettings().weatherMode = GameSettings.WeatherMode.CLEAR;
            engine.getSettings().saveSync();

            System.out.printf(
                    "CS-2 JAVA 8.0 PANORAMA SELF-TEST PASSED! Native-720p HIGH: %d frames in %d ms (%.1f FPS avg) | ESPORTS: %d ms (%.1f FPS avg) | %d/1000 async clicks hit!%n",
                    benchFrames, elapsedMs, (benchFrames * 1000.0) / Math.max(1L, elapsedMs),
                    esportsMs, (benchFrames * 1000.0) / Math.max(1L, esportsMs),
                    registeredClicks);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    private static void saveTestScreenshot(Engine engine, File file) throws Exception {
        BufferedImage out = new BufferedImage(
                Engine.DEFAULT_WINDOW_WIDTH,
                Engine.DEFAULT_WINDOW_HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        engine.renderCompleteFrame(g, out.getWidth(), out.getHeight());
        g.dispose();
        ImageIO.write(out, "png", file);
    }

    private static List<Image> createAppIcons() {
        List<Image> icons = new ArrayList<>();
        for (int sz : new int[]{16, 32, 48, 64, 128, 256}) {
            icons.add(renderAppIcon(sz));
        }
        return icons;
    }

    public static BufferedImage renderAppIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int pad = Math.max(1, size / 18);
        int box = size - pad * 2;
        int arc = Math.max(4, size / 4);

        // Золотисто-оранжевый тактический щит CS2
        g.setPaint(new GradientPaint(pad, pad, new Color(255, 208, 74), pad + box, pad + box, new Color(220, 114, 22)));
        g.fillRoundRect(pad, pad, box, box, arc, arc);

        int inset = Math.max(2, size / 11);
        int innerBox = box - inset * 2;
        int innerArc = Math.max(3, arc - inset);
        g.setPaint(new GradientPaint(pad + inset, pad + inset, new Color(14, 22, 36),
                pad + inset, pad + inset + innerBox, new Color(22, 34, 54)));
        g.fillRoundRect(pad + inset, pad + inset, innerBox, innerBox, innerArc, innerArc);

        // Неоновое прицельное перекрестие на фоне
        int cx = size / 2;
        int cy = size / 2;
        int cr = Math.max(3, (int) (size * 0.30));
        g.setColor(new Color(84, 240, 152, 95));
        g.setStroke(new BasicStroke(Math.max(1.0f, size / 28.0f)));
        g.drawOval(cx - cr, cy - cr, cr * 2, cr * 2);
        int tick = Math.max(2, size / 9);
        g.drawLine(cx - cr - tick / 2, cy, cx - cr + tick / 2, cy);
        g.drawLine(cx + cr - tick / 2, cy, cx + cr + tick / 2, cy);
        g.drawLine(cx, cy - cr - tick / 2, cx, cy - cr + tick / 2);
        g.drawLine(cx, cy + cr - tick / 2, cx, cy + cr + tick / 2);

        // Чёткая надпись CS2
        if (size >= 24) {
            Font font = new Font("SansSerif", Font.BOLD, Math.max(9, (int) (size * 0.36)));
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            String label = "CS2";
            int tx = cx - fm.stringWidth(label) / 2;
            int ty = cy + (fm.getAscent() - fm.getDescent()) / 2 - Math.max(0, size / 22);
            g.setColor(new Color(0, 0, 0, 170));
            g.drawString(label, tx + Math.max(1, size / 48), ty + Math.max(1, size / 48));
            g.setColor(new Color(255, 212, 82));
            g.drawString(label, tx, ty);

            if (size >= 64) {
                Font sub = new Font("SansSerif", Font.BOLD, Math.max(8, (int) (size * 0.125)));
                g.setFont(sub);
                FontMetrics sfm = g.getFontMetrics();
                String subTxt = "8.0 HD";
                g.setColor(new Color(98, 240, 162));
                g.drawString(subTxt, cx - sfm.stringWidth(subTxt) / 2, pad + inset + innerBox - Math.max(4, size / 14));
            }
        } else {
            g.setColor(new Color(255, 212, 82));
            int dot = Math.max(3, size / 4);
            g.fillOval(cx - dot / 2, cy - dot / 2, dot, dot);
        }

        g.dispose();
        return img;
    }
}
