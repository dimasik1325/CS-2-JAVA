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
 * Точка входа в игру CS-2 JAVA 2.0 (Source 2 Tactical Edition).
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
            final JFrame frame = new JFrame("CS-2 JAVA 2.0 | Dust II Source 2 Edition");
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

            // 1. Главное меню с живой 3D-камерой над ареной Dust II
            engine.setCurrentScreen(Engine.AppScreen.MAIN_MENU);
            for (int i = 0; i < 35; i++) {
                engine.updateGame(1.0 / 60.0);
            }
            saveTestScreenshot(engine, new File(dir, "01_main_menu.png"));

            // 2. Экран Настроек Графики
            engine.openSettingsScreen(false, 0);
            saveTestScreenshot(engine, new File(dir, "02_graphics_settings.png"));

            // 3. Вход в матч: реалистичные тени на песке, облака, дым CS2 и выстрел из AK-47 с гильзами
            engine.launchMatchFromMenu();
            player.getPosition().set(-1.2, 0.0, -7.2);
            player.setAngles(0.06, -0.03);
            engine.getSmokeClouds().add(new Vec3.SmokeCloud(new Vec3(-4.2, 0.2, -1.8), 3.4, 12.0));
            Bot target = engine.getBots().get(0);
            target.getPosition().set(0.1, 0.0, 4.8);
            engine.updateGame(1.0 / 60.0);
            Vec3 toBot = target.getPosition().add(0.0, 1.32, 0.0).sub(player.getEyePosition()).normalize();
            player.setAngles(Math.atan2(toBot.x, toBot.z), Math.asin(toBot.y));
            engine.tryFireCurrentWeapon(false);
            saveTestScreenshot(engine, new File(dir, "03_combat_shadows_smoke.png"));

            // 4. Золотой закат + покупка AWP + оптический снайперский прицел ПКМ
            engine.getSettings().timeOfDay = GameSettings.TimeOfDay.SUNSET;
            player.addMoney(8000);
            player.equipPurchasedWeapon(Weapon.Type.AWP);
            for (int i = 0; i < 20; i++) engine.updateGame(1.0 / 60.0);
            player.getCurrentWeapon().toggleSecondaryAction();
            saveTestScreenshot(engine, new File(dir, "04_awp_scope_sunset.png"));

            // 5. Магазин закупки CS2 [B] + пистолет Desert Eagle в руках (Полдень)
            engine.getSettings().timeOfDay = GameSettings.TimeOfDay.DAY;
            player.equipPurchasedWeapon(Weapon.Type.DEAGLE);
            for (int i = 0; i < 20; i++) engine.updateGame(1.0 / 60.0);
            engine.setBuyMenuOpen(true);
            saveTestScreenshot(engine, new File(dir, "05_buy_menu_deagle.png"));
            engine.setBuyMenuOpen(false);

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

            System.out.printf("CS-2 JAVA 2.0 SELF-TEST PASSED! %d HD frames rendered in %d ms (%.1f FPS avg)%n",
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
