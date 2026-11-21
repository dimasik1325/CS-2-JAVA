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
 * Точка входа в игру CS-2 JAVA 4.0 (Maps, Arms Race, Allies & Zeus Edition).
 * Управляет окном JFrame, безрамочным полноэкранным режимом F11 / Alt+Enter (без чёрного экрана!)
 * и запуском игрового движка Engine.
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
            final JFrame frame = new JFrame("CS-2 JAVA 4.0 | Dust II • Mirage • Nuke Edition");
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
     * Не использует проблемный эксклюзивный DirectDraw/D3D режим setFullScreenWindow, который давал
     * чёрный экран на Windows 10/11, и сохраняет мгновенный рендер JPanel без потери фокуса и кнопок.
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
     * Полная автономная проверка всех новых режимов: Главное меню, Настройки графики,
     * реалистичные солнечные тени, облака, дымовые гранаты CS2, магазин закупки и прицел AWP.
     */
    private static void runHeadlessSelfTest(String outDir) {
        try {
            File dir = new File(outDir);
            if (!dir.exists()) dir.mkdirs();

            Engine engine = new Engine(true);
            Player player = engine.getPlayer();

            // 1. Главное меню 4.0 с картой MIRAGE (марокканский дворец и пальмы) на живом 3D-фоне
            engine.getSettings().mapTheme = GameSettings.MapTheme.MIRAGE;
            engine.startNewMatch();
            engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
            for (int i = 0; i < 35; i++) {
                engine.updateGame(1.0 / 60.0);
            }
            saveTestScreenshot(engine, new File(dir, "01_main_menu_mirage.png"));

            // 2. Карта MIRAGE в бою: союзник спецназа CT (▲ CT Альфа), 3D-граффити [Y] на стене, радиокоманда [Z] и молния Zeus x27!
            engine.launchMatchFromMenu();
            player.getPosition().set(-0.6, 0.0, -5.0);
            player.setAngles(0.0, -0.14);
            engine.trySprayGraffiti();

            // Размещаем союзника CT слева и врага T перед электрошокером Zeus x27
            Bot enemyT = null;
            Bot allyCt = null;
            for (Bot b : engine.getBots()) {
                if (b.isAlly() && allyCt == null) allyCt = b;
                else if (!b.isAlly() && enemyT == null) enemyT = b;
            }
            player.selectWeaponSlot(3); // Zeus x27
            for (int i = 0; i < 16; i++) engine.updateGame(1.0 / 60.0);
            engine.issueNextRadioCommand();
            if (allyCt != null) {
                allyCt.getPosition().set(-2.3, 0.0, -2.5);
            }
            if (enemyT != null) {
                enemyT.getPosition().set(1.25, 0.0, -2.4);
                Vec3 toEnemy = enemyT.getPosition().add(0, 1.25, 0).sub(player.getEyePosition()).normalize();
                player.setAngles(Math.atan2(toEnemy.x, toEnemy.z), Math.asin(toEnemy.y));
                engine.tryFireCurrentWeapon(false);
            }
            saveTestScreenshot(engine, new File(dir, "02_mirage_allies_zeus_graffiti.png"));

            // 3. Карта NUKE (ядерный комплекс с желтым реактором Silo) + режим «Гонка вооружений» + скин Неоновый Зверь
            engine.getSettings().mapTheme = GameSettings.MapTheme.NUKE;
            engine.getSettings().gameMode = GameSettings.GameMode.ARMS_RACE;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.HYPER_BEAST;
            engine.startNewMatch();
            player.setArmsRaceTier(3); // AK-47 в Гонке вооружений
            player.setArmsRaceTierKills(1);
            player.applyArmsRaceTierLoadout();
            player.getPosition().set(12.2, 0.0, 7.8);
            Vec3 toSilo = new Vec3(19.2, 1.8, 17.8).sub(player.getEyePosition()).normalize();
            player.setAngles(Math.atan2(toSilo.x, toSilo.z), Math.asin(toSilo.y));
            for (int i = 0; i < 20; i++) engine.updateGame(1.0 / 60.0);
            saveTestScreenshot(engine, new File(dir, "03_nuke_arms_race_hyperbeast.png"));

            // 4. Обновленная 3D-оружейная 4.0 (9 видов оружия включая Zeus x27 + 6 коллекций скинов)
            engine.setCurrentScreen(Engine.AppScreen.SKIN_ARMORY);
            for (int i = 0; i < 15; i++) engine.updateGame(1.0 / 60.0);
            saveTestScreenshot(engine, new File(dir, "04_skin_armory_v4.png"));

            // 5. Двухкомандная таблица счета Tab (Отряд Спецназа CT со звездой MVP vs Террористы T) на Dust II
            engine.getSettings().mapTheme = GameSettings.MapTheme.DUST_II;
            engine.getSettings().gameMode = GameSettings.GameMode.BOMB_DEFUSAL;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.STANDARD;
            engine.launchMatchFromMenu();
            player.addMvpStar();
            for (int i = 0; i < 10; i++) engine.updateGame(1.0 / 60.0);
            engine.setScoreTabHeld(true);
            saveTestScreenshot(engine, new File(dir, "05_scoreboard_two_teams.png"));
            engine.setScoreTabHeld(false);

            // Проверка механики Wallbang (прострел двойных дверей Dust II из AWP)!
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
                    throw new IllegalStateException("AWP must penetrate Dust II wooden/metal Mid Double Doors (Wallbang)!");
                }
            }

            // Проверка механики CS2: Дымовая шашка тушит огонь Молотова!
            engine.getFireZones().clear();
            engine.getSmokeClouds().clear();
            Vec3 testSpot = new Vec3(0.0, 0.0, 0.0);
            engine.getFireZones().add(new Vec3.FireZone(testSpot, 2.5, 7.0));
            engine.getSmokeClouds().add(new Vec3.SmokeCloud(new Vec3(0.5, 0.2, 0.0), 3.6, 12.0));
            for (int i = 0; i < 45; i++) engine.updateGame(1.0 / 30.0);
            if (!engine.getFireZones().isEmpty()) {
                throw new IllegalStateException("Smoke grenade must extinguish overlapping Molotov FireZone!");
            }

            // Проверка блокировки обзора дымом (Smoke LOS check)
            Vec3 a = new Vec3(0, 1.5, -6);
            Vec3 b = new Vec3(0, 1.5, 6);
            engine.getSmokeClouds().clear();
            engine.getSmokeClouds().add(new Vec3.SmokeCloud(new Vec3(0, 0.5, 0), 4.0, 12.0));
            engine.updateGame(0.8);
            if (engine.getMap().hasLineOfSight(a, b, engine.getSmokeClouds())) {
                throw new IllegalStateException("Smoke cloud must block bot line of sight!");
            }

            // Бенчмарк рендера 854x480 HD -> 1280x720 со всеми тенями, картами и союзниками CT
            BufferedImage benchImg = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
            Graphics2D bg = benchImg.createGraphics();
            engine.startNewMatch();
            long t0 = System.nanoTime();
            int benchFrames = 60;
            for (int i = 0; i < benchFrames; i++) {
                engine.updateGame(1.0 / 60.0);
                engine.renderCompleteFrame(bg, 1280, 720);
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            bg.dispose();
            // 6. Многопоточный стресс-тест: 1000 асинхронных кликов мыши по кнопкам меню
            // параллельно с живым игровым потоком CS2-Engine-Loop + смена разрешений Fullscreen!
            engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
            engine.renderOffscreenForTest(1280, 720);
            if (engine.getPublishedButtonCount() < 10) {
                throw new IllegalStateException("Main Menu must publish all interactive buttons!");
            }

            // Проверка смены разрешений (имитация переходов Оконный <-> Fullscreen 1080p <-> 1440p) без чёрных кадров
            int[][] testResolutions = {{1280, 720}, {1920, 1080}, {2560, 1440}, {1280, 720}};
            for (int[] res : testResolutions) {
                engine.renderOffscreenForTest(res[0], res[1]);
                BufferedImage disp = engine.getDisplayedFrame();
                if (disp == null || disp.getWidth() != res[0] || disp.getHeight() != res[1]) {
                    throw new IllegalStateException("Fullscreen offscreen swap failed for " + res[0] + "x" + res[1]);
                }
                int nonBlack = 0;
                for (int sy = 40; sy < disp.getHeight(); sy += 37) {
                    for (int sx = 40; sx < disp.getWidth(); sx += 53) {
                        if ((disp.getRGB(sx, sy) & 0x00FFFFFF) != 0) nonBlack++;
                    }
                }
                if (nonBlack < 20) {
                    throw new IllegalStateException("Rendered fullscreen frame appeared black at " + res[0] + "x" + res[1]);
                }
            }

            // Живой параллельный тест: запускаем поток рендера и бьём 1000 кликами подряд с другого потока!
            engine.getSettings().fpsLimit = 0;
            engine.start();
            int registeredClicks = 0;
            for (int i = 0; i < 1000; i++) {
                engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
                int btnCount = engine.getPublishedButtonCount();
                if (btnCount <= 0) {
                    throw new IllegalStateException("publishedUiSnapshot became empty during live 3D render on click #" + i);
                }
                // Кликаем по переключателям карты / режима / ботов / вкладок без закрытия процесса
                Point pt = engine.getPublishedButtonCenter(btnCount - 1 - (i % 4));
                if (pt != null && engine.dispatchUiClick(pt.x, pt.y, 1280, 720)) {
                    registeredClicks++;
                }
                if (i % 50 == 0) {
                    Thread.sleep(1);
                }
            }
            engine.stop();
            engine.flushPendingInputTasks();
            engine.getSettings().fpsLimit = 60;

            if (registeredClicks != 1000) {
                throw new IllegalStateException("Lost UI clicks under load! Registered " + registeredClicks + "/1000");
            }

            System.out.printf("CS-2 JAVA 4.0 SELF-TEST PASSED! %d HD frames rendered in %d ms (%.1f FPS avg), %d/1000 async clicks hit (100%%)%n",
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
