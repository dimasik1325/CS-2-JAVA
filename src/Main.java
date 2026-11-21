import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Точка входа в игру CS-2 JAVA.
 * Запускает графическое окно и игровой движок Engine.
 */
public class Main {

    public static void main(String[] args) {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            String outDir = args.length > 1 ? args[1] : ".";
            runHeadlessSelfTest(outDir);
            return;
        }

        // Отключаем масштабирование UI там, где требуется точный захват мыши Robot
        System.setProperty("sun.java2d.uiScale", "1.0");

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("CS-2 JAVA | Dust II Tactical 3D Arena");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.getContentPane().setBackground(Color.BLACK);

            Engine engine = new Engine(false);
            frame.add(engine, BorderLayout.CENTER);
            frame.pack();
            frame.setMinimumSize(new java.awt.Dimension(960, 540));
            frame.setLocationRelativeTo(null);

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
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
     * Автономная проверка физики, коллизий, стрельбы, ИИ ботов, смены раундов и 3D-рендера.
     */
    private static void runHeadlessSelfTest(String outDir) {
        try {
            File dir = new File(outDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            Engine engine = new Engine(true);
            Player player = engine.getPlayer();

            // 1. Проверка старта и рендера вида с AK-47 на спавне CT (вид на Двойные двери Мида)
            for (int i = 0; i < 30; i++) {
                engine.updateGame(1.0 / 60.0);
            }
            saveTestScreenshot(engine, new File(dir, "01_spawn_ak47.png"));

            // 2. Перемещение к центру Мида, сближение с ботами, проверка Glock-18 и Ножа
            player.getPosition().set(0.0, 0.0, -3.2);
            player.setAngles(0.0, -0.02);
            for (int i = 0; i < 90; i++) {
                engine.updateGame(1.0 / 60.0);
            }

            // Наводим камеру на ближайшего живого бота и делаем выстрел из AK-47 (вспышка + трассер)
            Bot target = engine.getBots().get(0);
            target.getPosition().set(0.2, 0.0, 5.2);
            engine.updateGame(1.0 / 60.0);
            Vec3 toBot = target.getPosition().add(0.0, 1.35, 0.0).sub(player.getEyePosition()).normalize();
            double aimYaw = Math.atan2(toBot.x, toBot.z);
            double aimPitch = Math.asin(toBot.y);
            player.setAngles(aimYaw, aimPitch);
            engine.tryFireCurrentWeapon();
            saveTestScreenshot(engine, new File(dir, "02_combat_ak47_fire.png"));

            // 3. Смена оружия на Glock-18 (слот 2) на фоне Плента A
            player.selectWeaponSlot(1);
            player.getPosition().set(15.2, 0.0, 6.2);
            player.setAngles(0.28, 0.03);
            for (int i = 0; i < 35; i++) {
                engine.updateGame(1.0 / 60.0);
            }
            saveTestScreenshot(engine, new File(dir, "03_asite_glock18.png"));

            // 4. Смена оружия на Боевой нож (слот 3)
            player.selectWeaponSlot(2);
            for (int i = 0; i < 25; i++) {
                engine.updateGame(1.0 / 60.0);
            }
            engine.tryFireCurrentWeapon();
            engine.updateGame(0.08);
            saveTestScreenshot(engine, new File(dir, "04_knife_slash.png"));

            // 5. Проверка прохождения раундов до экрана ПОБЕДЫ
            player.selectWeaponSlot(0);
            for (int round = 0; round < Engine.ROUNDS_TO_WIN_MATCH; round++) {
                for (Bot b : engine.getBots()) {
                    if (b.isAlive()) {
                        b.takeDamage(200, player.getPosition());
                        player.recordKill(true);
                        player.recordShot(true);
                    }
                }
                engine.updateGame(1.0 / 60.0);
                if (engine.getMatchState() == Engine.MatchState.ROUND_OVER) {
                    engine.updateGame(4.0);
                }
            }
            saveTestScreenshot(engine, new File(dir, "05_victory_screen.png"));

            if (engine.getMatchState() != Engine.MatchState.MATCH_VICTORY) {
                throw new IllegalStateException("Expected MATCH_VICTORY, got: " + engine.getMatchState());
            }

            // Замер скорости рендера кадра 1280x720
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

            System.out.printf("SELF-TEST PASSED! Rendered %d full 3D+HUD frames in %d ms (%.1f FPS avg)%n",
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
