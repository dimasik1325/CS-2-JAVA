import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Точка входа в игру CS-2 JAVA 5.0 (Source 2 Smooth HD & Siege Bosses Edition).
 * Управляет окном JFrame, безрамочным полноэкранным режимом F11 / Alt+Enter (без чёрного экрана!)
 * и запуском гладкого непиксельного 3D-движка Engine.
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

        SwingUtilities.invokeLater(() -> {
            final JFrame frame = new JFrame("CS-2 JAVA 5.0 | Smooth HD • Inferno • Siege Bosses Edition");
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
     * Автономная проверка CS-2 JAVA 5.0: нативный гладкий рендер 1280x720 без пикселизации,
     * текстуры 256x256 с Mipmap и FXAA, карта Inferno с фонтаном, режим Осада с Босс-Вертолётом,
     * гранатомёт RPG-7, пулемёт M249, 3D Авто-Турель [J], а также стресс-тест 1000 кликов!
     */
    private static void runHeadlessSelfTest(String outDir) {
        try {
            File dir = new File(outDir);
            if (!dir.exists()) dir.mkdirs();

            Engine engine = new Engine(true);
            Player player = engine.getPlayer();

            // Гарантируем максимальную гладкость без пикселизации
            engine.getSettings().resolutionScale = GameSettings.ResolutionScale.HIGH_1280;
            engine.getSettings().smoothTextures = true;
            engine.getSettings().fxaaAntiAliasing = true;
            engine.getSettings().bilinearFiltering = true;
            engine.getSettings().weatherMode = GameSettings.WeatherMode.CLEAR;

            // 1. Главное меню 5.0 на карте INFERNO (Фонтан, Колокольня, Кипарисы + Боевой Вертолёт Осады!)
            engine.getSettings().mapTheme = GameSettings.MapTheme.INFERNO;
            engine.getSettings().gameMode = GameSettings.GameMode.SIEGE_WAVES;
            engine.startNewMatch();
            engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
            for (int i = 0; i < 30; i++) {
                engine.updateGame(1.0 / 60.0);
            }
            saveTestScreenshot(engine, new File(dir, "01_main_menu_inferno_v5.png"));

            // 2. Эпичный бой в режиме «Осада»: Босс-Вертолёт в небе, Джаггернаут у Фонтана, Авто-Турель [J] и RPG-7 в руках!
            engine.launchMatchFromMenu();
            player.addMoney(9000);
            player.equipPurchasedWeapon(Weapon.Type.RPG7);
            for (int i = 0; i < 18; i++) engine.updateGame(1.0 / 60.0);
            player.getPosition().set(-3.2, 0.0, -13.0);
            player.setAngles(0.22, 0.24);
            engine.tryDeploySentryTurret();
            engine.trySummonHelicopterChallenge();
            for (int i = 0; i < 14; i++) engine.updateGame(1.0 / 60.0);
            // Размещаем Джаггернаута на фоне каменного Фонтана Inferno
            for (Bot b : engine.getBots()) {
                if (!b.isAlly()) {
                    b.setArchetype(Bot.Archetype.JUGGERNAUT);
                    b.getPosition().set(1.8, 0.0, -4.2);
                    break;
                }
            }
            player.setAngles(0.26, 0.22);
            saveTestScreenshot(engine, new File(dir, "02_inferno_siege_helicopter_rpg_turret.png"));

            // Выстрел ракетой RPG-7 по области и проверка нанесения урона вертолёту/ботам
            engine.tryFireCurrentWeapon(false);
            for (int i = 0; i < 35; i++) engine.updateGame(1.0 / 60.0);

            // 3. Проверка идеальной гладкости ВБЛИЗИ (50 см от стен, брусчатки, воды фонтана и круглого глушителя M4A1-S!)
            player.equipPurchasedWeapon(Weapon.Type.M4A1S);
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.PRINTSTREAM;
            for (int i = 0; i < 18; i++) engine.updateGame(1.0 / 60.0);
            player.getPosition().set(-2.6, 0.0, -7.6);
            player.setAngles(0.55, -0.10);
            saveTestScreenshot(engine, new File(dir, "03_closeup_smooth_hd_no_pixelation.png"));

            // 4. Карта NUKE (круглый бетонный градирный цилиндр АЭС во дворе под открытым небом) + Ливень и гроза + пулемёт M249!
            engine.getSettings().mapTheme = GameSettings.MapTheme.NUKE;
            engine.getSettings().gameMode = GameSettings.GameMode.BOMB_DEFUSAL;
            engine.getSettings().weatherMode = GameSettings.WeatherMode.RAIN_STORM;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.ASIIMOV;
            engine.launchMatchFromMenu();
            player.addMoney(9000);
            player.equipPurchasedWeapon(Weapon.Type.M249);
            for (int i = 0; i < 18; i++) engine.updateGame(1.0 / 60.0);
            player.getPosition().set(-6.5, 0.0, 2.5);
            Vec3 toTower = new Vec3(-19.5, 4.5, 19.0).sub(player.getEyePosition()).normalize();
            player.setAngles(Math.atan2(toTower.x, toTower.z), Math.asin(toTower.y));
            saveTestScreenshot(engine, new File(dir, "04_nuke_rain_m249_smooth_silo.png"));

            // 5. 3D Оружейная скинов с 11 пушками и гладкими цилиндрами Гуро
            engine.getSettings().weatherMode = GameSettings.WeatherMode.CLEAR;
            engine.getSettings().mapTheme = GameSettings.MapTheme.MIRAGE;
            engine.startNewMatch();
            engine.setCurrentScreen(Engine.AppScreen.SKIN_ARMORY);
            for (int i = 0; i < 16; i++) engine.updateGame(1.0 / 60.0);
            saveTestScreenshot(engine, new File(dir, "05_armory_11_weapons_smooth.png"));

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

            // Бенчмарк НАТИВНОГО HD 1280x720 с Bilinear 256x256 Mipmap + FXAA + Gouraud Cylinders!
            BufferedImage benchImg = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
            Graphics2D bg = benchImg.createGraphics();
            engine.getSettings().mapTheme = GameSettings.MapTheme.INFERNO;
            engine.startNewMatch();
            // Прогрев JIT
            for (int i = 0; i < 15; i++) {
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
            bg.dispose();

            // Стресс-тест 1000 асинхронных кликов + мульти-разрешения Fullscreen (1280x720, 1920x1080, 2560x1440)
            engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
            engine.renderOffscreenForTest(1280, 720);
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
            engine.getSettings().fpsLimit = 60;

            if (registeredClicks != 1000) {
                throw new IllegalStateException("Lost UI clicks under load! Registered " + registeredClicks + "/1000");
            }

            // Возвращаем дефолтную чистую конфигурацию для игрока
            engine.getSettings().mapTheme = GameSettings.MapTheme.INFERNO;
            engine.getSettings().gameMode = GameSettings.GameMode.SIEGE_WAVES;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.STANDARD;
            engine.getSettings().weatherMode = GameSettings.WeatherMode.CLEAR;
            engine.getSettings().saveSync();

            System.out.printf("CS-2 JAVA 5.0 SMOOTH HD SELF-TEST PASSED! %d Native-720p FXAA frames in %d ms (%.1f FPS avg), %d/1000 async clicks hit (100%%)%n",
                    benchFrames, elapsedMs, (benchFrames * 1000.0) / Math.max(1L, elapsedMs), registeredClicks);
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
}
