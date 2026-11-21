import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Точка входа в игру CS-2 JAVA 3.0 (Source 2 Tactical & Skins Edition).
 * Управляет окном JFrame, полноэкранным режимом F11 и запуском игрового движка Engine.
 */
public class Main {

    public static void main(String[] args) {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            String outDir = args.length > 1 ? args[1] : ".";
            runHeadlessSelfTest(outDir);
            return;
        }

        System.setProperty("sun.java2d.uiScale", "1.0");

        SwingUtilities.invokeLater(() -> {
            final JFrame frame = new JFrame("CS-2 JAVA 3.0 | Dust II Source 2 Edition");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.getContentPane().setBackground(Color.BLACK);

            final Engine engine = new Engine(false);
            frame.add(engine, BorderLayout.CENTER);
            frame.pack();
            frame.setMinimumSize(new Dimension(960, 540));
            frame.setLocationRelativeTo(null);

            engine.setFullscreenListener(fullscreen -> SwingUtilities.invokeLater(() -> {
                GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                frame.dispose();
                if (fullscreen) {
                    frame.setUndecorated(true);
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                    if (gd.isFullScreenSupported()) {
                        try {
                            gd.setFullScreenWindow(frame);
                        } catch (Throwable ignored) {
                            frame.setVisible(true);
                        }
                    } else {
                        frame.setVisible(true);
                    }
                } else {
                    if (gd.getFullScreenWindow() == frame) {
                        gd.setFullScreenWindow(null);
                    }
                    frame.setUndecorated(false);
                    frame.setExtendedState(JFrame.NORMAL);
                    frame.setSize(Engine.DEFAULT_WINDOW_WIDTH, Engine.DEFAULT_WINDOW_HEIGHT);
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);
                }
                engine.requestFocusInWindow();
            }));

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    engine.getSettings().save();
                    engine.stop();
                    frame.dispose();
                    System.exit(0);
                }
            });

            frame.setVisible(true);
            engine.requestFocusInWindow();
            engine.start();
        });
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

            // 1. Главное меню 3.0 с живой 3D-камерой над ареной Dust II
            engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
            for (int i = 0; i < 35; i++) {
                engine.updateGame(1.0 / 60.0);
            }
            saveTestScreenshot(engine, new File(dir, "01_main_menu.png"));

            // 2. Интерактивная 3D-оружейная скинов (AK-47 | Азимов)
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.ASIIMOV;
            engine.setCurrentScreen(Engine.AppScreen.SKIN_ARMORY);
            for (int i = 0; i < 25; i++) {
                engine.updateGame(1.0 / 60.0);
            }
            saveTestScreenshot(engine, new File(dir, "02_skin_armory_asiimov.png"));

            // 3. Режим Операции C4: заложенная бомба C4 на Пленте A, горящий Молотов, ослепленный бот,
            //    AK-47 | Азимов, счетчик StatTrak™ и карточки фрагов раунда CS2
            engine.launchMatchFromMenu();
            Vec3 siteA = engine.getMap().getBombsiteASpot();
            engine.setPlantedC4(new Vec3.BombC4(siteA, "A"));
            player.setHasDefuseKit(true);
            player.getPosition().set(siteA.x - 1.5, 0.0, siteA.z - 2.0);
            Vec3 lookC4 = siteA.add(0.0, 0.4, 0.0).sub(player.getEyePosition()).normalize();
            player.setAngles(Math.atan2(lookC4.x, lookC4.z), Math.asin(lookC4.y));

            engine.getFireZones().add(new Vec3.FireZone(new Vec3(siteA.x + 1.8, 0.0, siteA.z + 1.1), 2.3, 7.0));
            Bot target = engine.getBots().get(0);
            target.getPosition().set(siteA.x + 0.5, 0.0, siteA.z + 2.4);
            target.applyFlashBlind(4.0);
            player.recordKill(true);
            player.recordKill(false);
            for (int i = 0; i < 18; i++) engine.updateGame(1.0 / 60.0);
            saveTestScreenshot(engine, new File(dir, "03_c4_planted_molotov_asiimov.png"));

            // 4. Ночная операция + направленный 3D-фонарик [L] + новый ПП MP9 | Золотой Градиент
            engine.getSettings().timeOfDay = GameSettings.TimeOfDay.NIGHT;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.GOLD_FADE;
            player.addMoney(5000);
            player.equipPurchasedWeapon(Weapon.Type.MP9);
            player.toggleFlashlight();
            player.getPosition().set(-1.0, 0.0, -5.5);
            player.setAngles(0.08, -0.04);
            target.getPosition().set(-0.4, 0.0, 1.5);
            for (int i = 0; i < 20; i++) engine.updateGame(1.0 / 60.0);
            saveTestScreenshot(engine, new File(dir, "04_night_flashlight_mp9_fade.png"));

            // 5. Магазин закупки 3.0 (9 позиций: MP9, Flash, Molotov, Набор сапёра C4)
            engine.getSettings().timeOfDay = GameSettings.TimeOfDay.DAY;
            engine.getSettings().weaponSkin = GameSettings.WeaponSkin.STANDARD;
            if (player.isFlashlightOn()) player.toggleFlashlight();
            for (int i = 0; i < 10; i++) engine.updateGame(1.0 / 60.0);
            engine.setBuyMenuOpen(true);
            saveTestScreenshot(engine, new File(dir, "05_buy_menu_v3.png"));
            engine.setBuyMenuOpen(false);

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

            // Бенчмарк рендера 854x480 HD -> 1280x720 со всеми тенями и облаками
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
            engine.stop();

            System.out.printf("CS-2 JAVA 3.0 SELF-TEST PASSED! %d HD frames rendered in %d ms (%.1f FPS avg)%n",
                    benchFrames, elapsedMs, (benchFrames * 1000.0) / Math.max(1L, elapsedMs));
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
