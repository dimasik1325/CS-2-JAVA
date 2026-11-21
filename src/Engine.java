import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Центральный движок CS-2 JAVA 2.0 (Source 2 Panorama UI Edition):
 * - Главное меню с живой пролетающей 3D-камерой над ареной Dust II
 * - Многовкладочные Настройки и Настройки Графики с мгновенным применением и сохранением
 * - Магазин закупки оружия CS2 (клавиша B), 7 пушек, гранаты HE и интерактивный дым Smoke
 * - Реалистичные солнечные тени, 3D-гильзы, декали пробоин, серии убийств (ACE) и прицел AWP
 */
public class Engine extends Canvas implements Runnable {
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_WINDOW_WIDTH = 1280;
    public static final int DEFAULT_WINDOW_HEIGHT = 720;
    private static final double ROUND_TIME_SECONDS = 100.0;

    public enum AppScreen {
        MAIN_MENU,
        SETTINGS,
        ARSENAL_GUIDE,
        IN_GAME
    }

    public enum MatchState {
        PLAYING,
        ROUND_OVER,
        MATCH_VICTORY,
        MATCH_DEFEAT
    }

    public interface FullscreenListener {
        void onToggleFullscreen(boolean fullscreen);
    }

    private static class KillFeedEntry {
        final String killer;
        final String weapon;
        final String victim;
        final boolean headshot;
        final boolean playerInvolved;
        double timer;

        KillFeedEntry(String killer, String weapon, String victim, boolean headshot, boolean playerInvolved) {
            this.killer = killer;
            this.weapon = weapon;
            this.victim = victim;
            this.headshot = headshot;
            this.playerInvolved = playerInvolved;
            this.timer = 5.5;
        }
    }

    private static class DamagePopup {
        final Vec3 worldPos;
        final int damage;
        final boolean headshot;
        double life = 0.95;

        DamagePopup(Vec3 pos, int damage, boolean headshot) {
            this.worldPos = pos.copy();
            this.damage = damage;
            this.headshot = headshot;
        }
    }

    private static class UiClickable {
        final Rectangle rect;
        final Runnable action;

        UiClickable(Rectangle rect, Runnable action) {
            this.rect = rect;
            this.action = action;
        }
    }

    private static final String[] BOT_NAMES = {
        "BOT Phoenix", "BOT Viper", "BOT Sabre", "BOT Jackal",
        "BOT Corsair", "BOT Mirage", "BOT Anubis", "BOT Titan"
    };

    private final GameSettings settings;
    private final Renderer3D renderer;
    private final Map map;
    private final Player player;
    private final List<Bot> bots = new ArrayList<>();
    private final List<Vec3.Tracer> tracers = new ArrayList<>();
    private final List<Vec3.Particle> particles = new ArrayList<>();
    private final List<Vec3.Decal> decals = new ArrayList<>();
    private final List<Vec3.ShellCasing> shells = new ArrayList<>();
    private final List<Vec3.Grenade> grenades = new ArrayList<>();
    private final List<Vec3.SmokeCloud> smokeClouds = new ArrayList<>();
    private final List<Vec3.Pickup> pickups = new ArrayList<>();
    private final List<DamagePopup> damagePopups = new ArrayList<>();
    private final List<KillFeedEntry> killFeed = new ArrayList<>();
    private final List<UiClickable> uiClickables = new ArrayList<>();

    private final SoundSystem soundSystem;
    private final Random rng = new Random(2026L);
    private FullscreenListener fullscreenListener;

    private volatile boolean running = false;
    private Thread gameThread;

    // Экраны меню и состояния матча
    private AppScreen currentScreen = AppScreen.MAIN_MENU;
    private boolean settingsOpenedFromGame = false;
    private int settingsTab = 0; // 0 = Графика, 1 = Геймплей и мышь, 2 = Аудио
    private boolean paused = false;
    private boolean buyMenuOpen = false;

    private MatchState matchState = MatchState.PLAYING;
    private int currentRound = 1;
    private int playerRoundWins = 0;
    private int botRoundWins = 0;
    private double roundTimeLeft = ROUND_TIME_SECONDS;
    private double roundBannerTimer = 2.2;
    private double roundOverTimer = 0.0;
    private boolean lastRoundWonByPlayer = false;
    private String roundResultReason = "";

    // Таймеры пролета камеры в меню и баннеров серий убийств
    private double globalAnimTime = 0.0;
    private String streakBannerText = "";
    private double streakBannerTimer = 0.0;

    // Управление и курсор
    private boolean mouseCaptured = false;
    private Robot robot;
    private Cursor blankCursor;
    private int lastMouseX = -1;
    private int lastMouseY = -1;

    private final boolean[] keys = new boolean[512];
    private boolean jumpQueued = false;
    private boolean leftMouseDown = false;
    private boolean scoreTabHeld = false;
    private int mouseClientX = DEFAULT_WINDOW_WIDTH / 2;
    private int mouseClientY = DEFAULT_WINDOW_HEIGHT / 2;

    private double hitMarkerTimer = 0.0;
    private boolean hitMarkerHeadshot = false;
    private int currentFps = 60;
    private double lastFrameMs = 8.0;
    private BufferedImage cachedVignette = null;

    public Engine(boolean headlessMode) {
        this.settings = new GameSettings();
        this.renderer = new Renderer3D(
                settings.resolutionScale.width,
                settings.resolutionScale.height,
                settings.fovDegrees
        );
        this.renderer.applyTimeOfDay(settings.timeOfDay);

        this.map = new Map();
        this.player = new Player(map.getPlayerSpawn());
        this.soundSystem = new SoundSystem(!headlessMode);
        this.soundSystem.setMasterVolume(settings.masterVolume);

        setPreferredSize(new Dimension(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);

        if (!headlessMode && !GraphicsEnvironment.isHeadless()) {
            initCursorAndRobot();
            initInputListeners();
        }

        // Подготавливаем фоновую сцену для живой камеры Главного меню
        startNewMatch();
        if (headlessMode) {
            this.currentScreen = AppScreen.IN_GAME;
        } else {
            this.currentScreen = AppScreen.MAIN_MENU;
        }
    }

    public void setFullscreenListener(FullscreenListener listener) {
        this.fullscreenListener = listener;
    }

    private void initCursorAndRobot() {
        try {
            BufferedImage cursorImg = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                    cursorImg, new Point(0, 0), "CS2BlankCursor");
        } catch (Throwable ignored) {
            blankCursor = Cursor.getDefaultCursor();
        }
        try {
            robot = new Robot();
        } catch (Throwable ignored) {
            robot = null;
        }
    }

    private void initInputListeners() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code >= 0 && code < keys.length) keys[code] = true;
                handleKeyPress(code);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int code = e.getKeyCode();
                if (code >= 0 && code < keys.length) keys[code] = false;
                if (code == KeyEvent.VK_TAB) scoreTabHeld = false;
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                mouseClientX = e.getX();
                mouseClientY = e.getY();
                requestFocusInWindow();

                if (e.getButton() == MouseEvent.BUTTON1) {
                    if (isCursorFreeScreen()) {
                        handleUiClick(e.getX(), e.getY());
                        return;
                    }
                    if (!mouseCaptured) {
                        setMouseCaptured(true);
                    }
                    leftMouseDown = true;
                    tryFireCurrentWeapon(false);
                } else if (e.getButton() == MouseEvent.BUTTON3) {
                    if (!isCursorFreeScreen() && matchState == MatchState.PLAYING && player.isAlive()) {
                        Weapon w = player.getCurrentWeapon();
                        if (w.isMelee()) {
                            tryFireCurrentWeapon(true); // Мощный удар ножом ПКМ!
                        } else {
                            w.toggleSecondaryAction(); // Оптический прицел AWP / ADS!
                            soundSystem.playUiClick();
                        }
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    leftMouseDown = false;
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) { handleMouseMotion(e); }

            @Override
            public void mouseDragged(MouseEvent e) { handleMouseMotion(e); }
        });

        addMouseWheelListener((MouseWheelEvent e) -> {
            if (!isCursorFreeScreen() && matchState == MatchState.PLAYING) {
                int rot = e.getWheelRotation();
                if (rot > 0) player.cycleWeapon(1);
                else if (rot < 0) player.cycleWeapon(-1);
            }
        });

        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                clearHeldInputs();
                setMouseCaptured(false);
            }
        });
    }

    private boolean isCursorFreeScreen() {
        return currentScreen != AppScreen.IN_GAME
                || paused
                || buyMenuOpen
                || matchState == MatchState.MATCH_VICTORY
                || matchState == MatchState.MATCH_DEFEAT;
    }

    private void handleUiClick(int mx, int my) {
        List<UiClickable> snapshot = new ArrayList<>(uiClickables);
        for (int i = 0; i < snapshot.size(); i++) {
            UiClickable item = snapshot.get(i);
            if (item.rect.contains(mx, my)) {
                soundSystem.playUiClick();
                item.action.run();
                return;
            }
        }
    }

    private void handleKeyPress(int code) {
        if (code == KeyEvent.VK_F11) {
            toggleFullscreenMode();
            return;
        }

        if (currentScreen == AppScreen.SETTINGS) {
            if (code == KeyEvent.VK_ESCAPE) {
                closeSettingsScreen();
            }
            return;
        }

        if (currentScreen == AppScreen.ARSENAL_GUIDE) {
            if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_ENTER || code == KeyEvent.VK_SPACE) {
                currentScreen = AppScreen.MAIN_MENU;
            }
            return;
        }

        if (currentScreen == AppScreen.MAIN_MENU) {
            if (code == KeyEvent.VK_ENTER) {
                launchMatchFromMenu();
            }
            return;
        }

        // Режим IN_GAME
        if (code == KeyEvent.VK_ESCAPE) {
            if (buyMenuOpen) {
                buyMenuOpen = false;
                setMouseCaptured(true);
            } else if (matchState == MatchState.MATCH_VICTORY || matchState == MatchState.MATCH_DEFEAT) {
                currentScreen = AppScreen.MAIN_MENU;
                setMouseCaptured(false);
            } else {
                paused = !paused;
                leftMouseDown = false;
                setMouseCaptured(!paused);
            }
            return;
        }

        if (code == KeyEvent.VK_B && !paused && matchState == MatchState.PLAYING && player.isAlive()) {
            buyMenuOpen = !buyMenuOpen;
            leftMouseDown = false;
            setMouseCaptured(!buyMenuOpen);
            soundSystem.playUiClick();
            return;
        }

        if (buyMenuOpen) {
            handleBuyShortcut(code);
            return;
        }

        if (code == KeyEvent.VK_F5) {
            startNewMatch();
            paused = false;
            buyMenuOpen = false;
            if (isShowing()) setMouseCaptured(true);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            if (matchState == MatchState.MATCH_VICTORY || matchState == MatchState.MATCH_DEFEAT) {
                startNewMatch();
                paused = false;
                if (isShowing()) setMouseCaptured(true);
                return;
            } else if (matchState == MatchState.ROUND_OVER) {
                advanceToNextRound();
                return;
            }
        }

        if (code == KeyEvent.VK_TAB) {
            scoreTabHeld = true;
        }

        if (code == KeyEvent.VK_OPEN_BRACKET || code == KeyEvent.VK_MINUS) {
            settings.mouseSensitivity = Math.max(0.0005, settings.mouseSensitivity - 0.00025);
            settings.save();
        } else if (code == KeyEvent.VK_CLOSE_BRACKET || code == KeyEvent.VK_EQUALS) {
            settings.mouseSensitivity = Math.min(0.0080, settings.mouseSensitivity + 0.00025);
            settings.save();
        }

        if (paused) return;

        if (code == KeyEvent.VK_SPACE) {
            if (matchState == MatchState.ROUND_OVER && roundOverTimer < 2.6) {
                advanceToNextRound();
            } else {
                jumpQueued = true;
            }
        } else if (code == KeyEvent.VK_1) {
            player.selectWeaponSlot(0);
        } else if (code == KeyEvent.VK_2) {
            player.selectWeaponSlot(1);
        } else if (code == KeyEvent.VK_3) {
            player.selectWeaponSlot(2);
        } else if (code == KeyEvent.VK_4 || code == KeyEvent.VK_G) {
            throwGrenade(Vec3.Grenade.Kind.HE);
        } else if (code == KeyEvent.VK_5 || code == KeyEvent.VK_Q) {
            throwGrenade(Vec3.Grenade.Kind.SMOKE);
        } else if (code == KeyEvent.VK_F) {
            if (player.isAlive()) player.getCurrentWeapon().triggerInspect();
        } else if (code == KeyEvent.VK_R) {
            if (player.isAlive() && player.getCurrentWeapon().startReload()) {
                soundSystem.playReload();
            }
        }
    }

    private void handleBuyShortcut(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_1: tryPurchaseWeapon(Weapon.Type.AK47); break;
            case KeyEvent.VK_2: tryPurchaseWeapon(Weapon.Type.M4A1S); break;
            case KeyEvent.VK_3: tryPurchaseWeapon(Weapon.Type.AWP); break;
            case KeyEvent.VK_4: tryPurchaseWeapon(Weapon.Type.XM1014); break;
            case KeyEvent.VK_5: tryPurchaseWeapon(Weapon.Type.DEAGLE); break;
            case KeyEvent.VK_6: tryPurchaseUtility(1); break;
            case KeyEvent.VK_7: tryPurchaseUtility(2); break;
            case KeyEvent.VK_8: tryPurchaseUtility(3); break;
            default: break;
        }
    }

    private void tryPurchaseWeapon(Weapon.Type type) {
        Weapon sample = new Weapon(type);
        if (player.getCurrentWeapon().getType() == type) {
            return;
        }
        if (player.spendMoney(sample.getPrice())) {
            player.equipPurchasedWeapon(type);
            soundSystem.playBuy();
        } else {
            soundSystem.playEmptyClick();
        }
    }

    private void tryPurchaseUtility(int kind) {
        if (kind == 1 && player.getHeGrenades() < 2) {
            if (player.spendMoney(300)) {
                player.addHeGrenade();
                soundSystem.playBuy();
            } else soundSystem.playEmptyClick();
        } else if (kind == 2 && player.getSmokeGrenades() < 2) {
            if (player.spendMoney(300)) {
                player.addSmokeGrenade();
                soundSystem.playBuy();
            } else soundSystem.playEmptyClick();
        } else if (kind == 3 && (player.getHealth() < 100 || player.getArmor() < 100)) {
            if (player.spendMoney(650)) {
                player.healAndRestoreArmor(100, 100);
                soundSystem.playBuy();
            } else soundSystem.playEmptyClick();
        } else {
            soundSystem.playEmptyClick();
        }
    }

    private void throwGrenade(Vec3.Grenade.Kind kind) {
        if (!player.isAlive() || matchState != MatchState.PLAYING) return;
        boolean ok = (kind == Vec3.Grenade.Kind.HE) ? player.consumeHeGrenade() : player.consumeSmokeGrenade();
        if (!ok) {
            soundSystem.playEmptyClick();
            return;
        }
        Vec3 start = player.getEyePosition().add(player.getLookDirection().scale(0.55));
        Vec3 vel = player.getLookDirection().scale(16.8).add(0.0, 2.8, 0.0);
        double fuse = (kind == Vec3.Grenade.Kind.HE) ? 1.65 : 1.35;
        grenades.add(new Vec3.Grenade(kind, start, vel, fuse));
        soundSystem.playWeaponShot(Weapon.Type.KNIFE);
    }

    private void toggleFullscreenMode() {
        settings.fullscreen = !settings.fullscreen;
        settings.save();
        if (fullscreenListener != null) {
            fullscreenListener.onToggleFullscreen(settings.fullscreen);
        }
    }

    public void launchMatchFromMenu() {
        this.currentScreen = AppScreen.IN_GAME;
        this.paused = false;
        this.buyMenuOpen = false;
        startNewMatch();
        if (isShowing()) {
            setMouseCaptured(true);
        }
    }

    public void openSettingsScreen(boolean fromGame, int initialTab) {
        this.settingsOpenedFromGame = fromGame;
        this.settingsTab = initialTab;
        this.currentScreen = AppScreen.SETTINGS;
        setMouseCaptured(false);
    }

    private void closeSettingsScreen() {
        settings.save();
        applySettingsToSubsystems();
        if (settingsOpenedFromGame) {
            currentScreen = AppScreen.IN_GAME;
            paused = true;
            setMouseCaptured(false);
        } else {
            currentScreen = AppScreen.MAIN_MENU;
            setMouseCaptured(false);
        }
    }

    public void applySettingsToSubsystems() {
        renderer.applyTimeOfDay(settings.timeOfDay);
        soundSystem.setMasterVolume(settings.masterVolume);
    }

    private void handleMouseMotion(MouseEvent e) {
        mouseClientX = e.getX();
        mouseClientY = e.getY();

        if (!mouseCaptured || isCursorFreeScreen() || !isShowing()) {
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            return;
        }

        if (robot != null) {
            try {
                Point winScreen = getLocationOnScreen();
                int centerX = winScreen.x + getWidth() / 2;
                int centerY = winScreen.y + getHeight() / 2;
                int dx = e.getXOnScreen() - centerX;
                int dy = e.getYOnScreen() - centerY;

                if (dx == 0 && dy == 0) return;
                if (Math.abs(dx) < 600 && Math.abs(dy) < 600) {
                    player.applyMouseLook(dx, dy, settings.mouseSensitivity, settings.invertMouseY);
                }
                robot.mouseMove(centerX, centerY);
                return;
            } catch (Throwable ignored) {
            }
        }

        if (lastMouseX >= 0 && lastMouseY >= 0) {
            int dx = e.getX() - lastMouseX;
            int dy = e.getY() - lastMouseY;
            player.applyMouseLook(dx, dy, settings.mouseSensitivity, settings.invertMouseY);
        }
        lastMouseX = e.getX();
        lastMouseY = e.getY();
    }

    private void setMouseCaptured(boolean capture) {
        this.mouseCaptured = capture;
        if (GraphicsEnvironment.isHeadless()) return;

        if (capture && blankCursor != null) {
            setCursor(blankCursor);
            if (robot != null && isShowing()) {
                try {
                    Point p = getLocationOnScreen();
                    robot.mouseMove(p.x + getWidth() / 2, p.y + getHeight() / 2);
                } catch (Throwable ignored) {
                }
            }
        } else {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private void clearHeldInputs() {
        for (int i = 0; i < keys.length; i++) keys[i] = false;
        leftMouseDown = false;
        jumpQueued = false;
        scoreTabHeld = false;
    }

    public void startNewMatch() {
        this.currentRound = 1;
        this.playerRoundWins = 0;
        this.botRoundWins = 0;
        this.player.resetMatchStats();
        this.killFeed.clear();
        this.bots.clear();
        applySettingsToSubsystems();
        setupRound(true);
    }

    private void setupRound(boolean resetPlayerWeapons) {
        this.matchState = MatchState.PLAYING;
        this.roundTimeLeft = ROUND_TIME_SECONDS;
        this.roundBannerTimer = 2.2;
        this.roundOverTimer = 0.0;
        this.buyMenuOpen = false;
        this.tracers.clear();
        this.particles.clear();
        this.decals.clear();
        this.shells.clear();
        this.grenades.clear();
        this.smokeClouds.clear();
        this.pickups.clear();
        this.damagePopups.clear();
        this.hitMarkerTimer = 0.0;
        this.streakBannerTimer = 0.0;

        player.respawnForRound(map.getPlayerSpawn(), resetPlayerWeapons);

        List<Vec3> spawns = map.getBotSpawns();
        List<Vec3> waypoints = map.getPatrolWaypoints();
        int desiredBots = settings.botCount;
        while (bots.size() > desiredBots) {
            bots.remove(bots.size() - 1);
        }
        for (int i = 0; i < desiredBots; i++) {
            Vec3 sp = spawns.get((i + currentRound - 1) % spawns.size());
            int wp = (i * 3 + currentRound) % Math.max(1, waypoints.size());
            if (i < bots.size()) {
                bots.get(i).respawn(sp, wp);
            } else {
                bots.add(new Bot(BOT_NAMES[i % BOT_NAMES.length], sp, wp, 1000L + i * 37L));
            }
        }
    }

    private void advanceToNextRound() {
        currentRound++;
        setupRound(false);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        gameThread = new Thread(this, "CS2-Engine-Loop");
        gameThread.start();
    }

    public synchronized void stop() {
        running = false;
        soundSystem.shutdown();
    }

    private void shutdownAndExit() {
        settings.save();
        stop();
        System.exit(0);
    }

    @Override
    public void run() {
        createBufferStrategy(3);
        long lastNano = System.nanoTime();
        long fpsTimer = System.currentTimeMillis();
        int frames = 0;

        while (running) {
            long frameStart = System.nanoTime();
            double dt = (frameStart - lastNano) / 1_000_000_000.0;
            lastNano = frameStart;
            if (dt > 0.05) dt = 0.05;
            if (dt < 0.0001) dt = 0.0001;

            globalAnimTime += dt;

            if (currentScreen == AppScreen.IN_GAME) {
                if (!paused) {
                    updateGame(dt);
                }
            } else {
                // В Главном меню боты патрулируют арену для живого фона!
                updateMenuBackgroundScene(dt);
            }

            renderWindowFrame();
            frames++;

            long frameDone = System.nanoTime();
            lastFrameMs = (frameDone - frameStart) / 1_000_000.0;

            if (System.currentTimeMillis() - fpsTimer >= 1000L) {
                currentFps = frames;
                frames = 0;
                fpsTimer += 1000L;
            }

            int cap = settings.fpsLimit;
            if (cap > 0) {
                long targetNanos = 1_000_000_000L / cap;
                long sleepNanos = targetNanos - (System.nanoTime() - frameStart);
                if (sleepNanos > 1_000_000L) {
                    try {
                        Thread.sleep(sleepNanos / 1_000_000L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    Thread.yield();
                }
            } else {
                Thread.yield();
            }
        }
    }

    private void updateMenuBackgroundScene(double dt) {
        // Держим игрока за пределами карты для спокойного патруля ботов на фоне меню
        player.getPosition().set(0.0, -50.0, 0.0);
        for (int i = 0; i < bots.size(); i++) {
            bots.get(i).update(dt, map, player, bots, smokeClouds, settings.botDifficulty);
        }
    }

    public void updateGame(double dt) {
        globalAnimTime += dt * 0.2;
        if (hitMarkerTimer > 0.0) hitMarkerTimer = Math.max(0.0, hitMarkerTimer - dt);
        if (roundBannerTimer > 0.0) roundBannerTimer = Math.max(0.0, roundBannerTimer - dt);
        if (streakBannerTimer > 0.0) streakBannerTimer = Math.max(0.0, streakBannerTimer - dt);

        for (Iterator<Vec3.Tracer> it = tracers.iterator(); it.hasNext(); ) {
            Vec3.Tracer tr = it.next();
            tr.life -= dt;
            if (tr.life <= 0.0) it.remove();
        }
        for (Iterator<Vec3.Particle> it = particles.iterator(); it.hasNext(); ) {
            if (!it.next().update(dt)) it.remove();
        }
        for (Iterator<Vec3.Decal> it = decals.iterator(); it.hasNext(); ) {
            Vec3.Decal d = it.next();
            d.life -= dt;
            if (d.life <= 0.0) it.remove();
        }
        for (Iterator<Vec3.ShellCasing> it = shells.iterator(); it.hasNext(); ) {
            Vec3.ShellCasing sc = it.next();
            double prevY = sc.y;
            if (!sc.update(dt)) {
                it.remove();
            } else if (!sc.bouncedSoundPlayed && prevY > 0.03 && sc.y <= 0.026) {
                sc.bouncedSoundPlayed = true;
                soundSystem.playShellClink();
            }
        }
        for (Iterator<Vec3.SmokeCloud> it = smokeClouds.iterator(); it.hasNext(); ) {
            if (!it.next().update(dt)) it.remove();
        }
        for (Iterator<DamagePopup> it = damagePopups.iterator(); it.hasNext(); ) {
            DamagePopup dp = it.next();
            dp.life -= dt;
            dp.worldPos.y += dt * 0.65;
            if (dp.life <= 0.0) it.remove();
        }
        for (Iterator<KillFeedEntry> it = killFeed.iterator(); it.hasNext(); ) {
            KillFeedEntry kf = it.next();
            kf.timer -= dt;
            if (kf.timer <= 0.0) it.remove();
        }

        // Обновление брошенных гранат HE и Smoke
        for (Iterator<Vec3.Grenade> it = grenades.iterator(); it.hasNext(); ) {
            Vec3.Grenade gr = it.next();
            if (gr.update(dt, map.getBoxes(), Map.MIN_X, Map.MAX_X, Map.MIN_Z, Map.MAX_Z)) {
                triggerGrenadeDetonation(gr);
                it.remove();
            }
        }

        // Подбор выпавших аптечек и боеприпасов
        for (Iterator<Vec3.Pickup> it = pickups.iterator(); it.hasNext(); ) {
            Vec3.Pickup pk = it.next();
            if (!pk.update(dt)) {
                it.remove();
                continue;
            }
            if (player.isAlive() && player.getPosition().dist(pk.pos) < 1.35) {
                if (pk.type == Vec3.Pickup.Type.MEDKIT) {
                    player.healAndRestoreArmor(30, 25);
                } else {
                    for (Weapon w : player.getWeapons()) w.refillReserveAmmo();
                    player.addMoney(200);
                }
                soundSystem.playBuy();
                it.remove();
            }
        }

        if (matchState == MatchState.MATCH_VICTORY || matchState == MatchState.MATCH_DEFEAT) {
            return;
        }

        boolean w = !buyMenuOpen && keys[KeyEvent.VK_W];
        boolean s = !buyMenuOpen && keys[KeyEvent.VK_S];
        boolean a = !buyMenuOpen && keys[KeyEvent.VK_A];
        boolean d = !buyMenuOpen && keys[KeyEvent.VK_D];
        boolean ctrl = !buyMenuOpen && (keys[KeyEvent.VK_CONTROL] || keys[KeyEvent.VK_C]);
        boolean shift = !buyMenuOpen && keys[KeyEvent.VK_SHIFT];
        boolean jump = !buyMenuOpen && (jumpQueued || keys[KeyEvent.VK_SPACE]);
        jumpQueued = false;

        player.update(dt, map, w, s, a, d, jump, ctrl, shift);
        if (settings.footstepSounds && player.consumeFootstepTrigger()) {
            soundSystem.playFootstep();
        }

        if (leftMouseDown && !buyMenuOpen && matchState == MatchState.PLAYING && player.isAlive()) {
            if (player.getCurrentWeapon().isAutomatic()) {
                tryFireCurrentWeapon(false);
            }
        }

        int aliveBots = 0;
        for (int i = 0; i < bots.size(); i++) {
            Bot bot = bots.get(i);
            Bot.ShotEvent shot = bot.update(dt, map, player, bots, smokeClouds, settings.botDifficulty);
            if (bot.isAlive()) aliveBots++;

            if (shot != null && matchState == MatchState.PLAYING) {
                tracers.add(new Vec3.Tracer(shot.muzzlePos, shot.endPos, 0xFF9F38, 0.085));
                soundSystem.playBotShot(bot.getPosition().dist(player.getPosition()));
                if (shot.hitPlayer && player.isAlive()) {
                    boolean wasAlive = player.isAlive();
                    player.takeDamage(shot.damage, bot.getPosition());
                    soundSystem.playPlayerHurt();
                    spawnHitParticles(shot.endPos, new Vec3(0, 1, 0), 0xE03030, 5);
                    if (wasAlive && !player.isAlive()) {
                        addKillFeed(bot.getName(), "AK-47", "ВЫ", false, true);
                    }
                } else {
                    spawnHitParticles(shot.endPos, new Vec3(0, 1, 0), 0xE2C48E, 4);
                }
            }
        }

        if (matchState == MatchState.PLAYING) {
            roundTimeLeft -= dt;
            if (!player.isAlive()) {
                finishRound(false, "Игрок погиб в бою (+$1900 бонус раунда)");
            } else if (aliveBots == 0) {
                finishRound(true, "Все противники нейтрализованы! (+$3250 бонус победы)");
            } else if (roundTimeLeft <= 0.0) {
                roundTimeLeft = 0.0;
                finishRound(false, "Время раунда истекло (+$1900)");
            }
        } else if (matchState == MatchState.ROUND_OVER) {
            roundOverTimer -= dt;
            if (roundOverTimer <= 0.0) {
                advanceToNextRound();
            }
        }
    }

    private void triggerGrenadeDetonation(Vec3.Grenade gr) {
        if (gr.kind == Vec3.Grenade.Kind.SMOKE) {
            smokeClouds.add(new Vec3.SmokeCloud(gr.pos, 4.3, 14.5));
            soundSystem.playSmokeBloom();
            return;
        }

        // Осколочный взрыв HE-гранаты!
        double distToPlayer = gr.pos.dist(player.getPosition());
        soundSystem.playExplosion(distToPlayer);
        if (distToPlayer < 16.0) {
            player.addCameraShake(Math.max(0.18, (1.0 - distToPlayer / 16.0) * 0.85));
        }

        for (int i = 0; i < 34; i++) {
            double ang = rng.nextDouble() * Math.PI * 2.0;
            double spd = 3.5 + rng.nextDouble() * 9.5;
            int col = (i % 3 == 0) ? 0xFFF099 : ((i % 2 == 0) ? 0xFF7B29 : 0x54504C);
            particles.add(new Vec3.Particle(
                gr.pos.x, gr.pos.y + 0.12, gr.pos.z,
                Math.cos(ang) * spd, 3.0 + rng.nextDouble() * 7.5, Math.sin(ang) * spd,
                col, 0.14 + rng.nextDouble() * 0.14, 0.45 + rng.nextDouble() * 0.45
            ));
        }
        if (settings.decalsAndShells && gr.pos.y < 0.5) {
            addBulletDecal(new Vec3(gr.pos.x, 0.005, gr.pos.z), new Vec3(0, 1, 0), 1.35, 0x26221E);
        }

        double blastRadius = 8.2;
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (!b.isAlive()) continue;
            double d = b.getPosition().add(0, 0.9, 0).dist(gr.pos);
            if (d <= blastRadius) {
                int dmg = (int) Math.round((1.0 - d / blastRadius) * 118.0);
                if (dmg > 4) {
                    if (settings.showDamageNumbers) {
                        damagePopups.add(new DamagePopup(b.getPosition().add(0, 1.9, 0), dmg, false));
                    }
                    boolean killed = b.takeDamage(dmg, player.getPosition());
                    hitMarkerTimer = 0.20;
                    hitMarkerHeadshot = false;
                    if (killed) {
                        onBotKilledByPlayer(b, "HE Граната", false, 300);
                    }
                }
            }
        }

        if (player.isAlive() && distToPlayer < 6.5) {
            int selfDmg = (int) ((1.0 - distToPlayer / 6.5) * 58.0);
            if (selfDmg > 3) player.takeDamage(selfDmg, gr.pos);
        }
    }

    private void onBotKilledByPlayer(Bot victim, String weaponLabel, boolean headshot, int cashReward) {
        int streak = player.recordKill(headshot, cashReward);
        addKillFeed("ВЫ", weaponLabel, victim.getName(), headshot, true);

        if (rng.nextDouble() < 0.48) {
            Vec3.Pickup.Type pt = rng.nextBoolean() ? Vec3.Pickup.Type.MEDKIT : Vec3.Pickup.Type.AMMO_PACK;
            pickups.add(new Vec3.Pickup(pt, new Vec3(victim.getPosition().x, victim.getPosition().y, victim.getPosition().z)));
        }

        if (streak >= bots.size() && bots.size() >= 3) {
            streakBannerText = "★ ЭЙС! ВСЯ КОМАНДА УНИЧТОЖЕНА (+$500) ★";
            streakBannerTimer = 3.0;
            player.addMoney(500);
        } else if (streak == 4) {
            streakBannerText = "КВАДРО-КИЛЛ! (4 УБИЙСТВА)";
            streakBannerTimer = 2.2;
        } else if (streak == 3) {
            streakBannerText = "ТРОЙНОЕ УБИЙСТВО! (TRIPLE KILL)";
            streakBannerTimer = 2.0;
        } else if (streak == 2) {
            streakBannerText = "ДВОЙНОЕ УБИЙСТВО! (DOUBLE KILL)";
            streakBannerTimer = 1.7;
        }
    }

    private void finishRound(boolean playerWon, String reason) {
        this.lastRoundWonByPlayer = playerWon;
        this.roundResultReason = reason;
        this.leftMouseDown = false;
        this.buyMenuOpen = false;

        if (playerWon) {
            playerRoundWins++;
            player.addMoney(3250);
            soundSystem.playRoundWin();
        } else {
            botRoundWins++;
            player.addMoney(1900);
            soundSystem.playRoundLose();
        }

        int targetWins = settings.roundsToWin;
        if (playerRoundWins >= targetWins) {
            matchState = MatchState.MATCH_VICTORY;
            setMouseCaptured(false);
        } else if (botRoundWins >= targetWins) {
            matchState = MatchState.MATCH_DEFEAT;
            setMouseCaptured(false);
        } else {
            matchState = MatchState.ROUND_OVER;
            roundOverTimer = 3.3;
        }
    }

    public void tryFireCurrentWeapon() {
        tryFireCurrentWeapon(false);
    }

    public void tryFireCurrentWeapon(boolean altHeavyMelee) {
        if (!player.isAlive() || matchState != MatchState.PLAYING || buyMenuOpen) return;

        Weapon weapon = player.getCurrentWeapon();
        if (!weapon.isMelee() && weapon.getMagAmmo() <= 0) {
            if (!weapon.isReloading()) {
                if (weapon.startReload()) soundSystem.playReload();
                else soundSystem.playEmptyClick();
            }
            return;
        }

        if (!weapon.canFire()) return;

        weapon.onFire(altHeavyMelee);
        soundSystem.playWeaponShot(weapon.getType());

        if (weapon.isMelee()) {
            performKnifeAttack(weapon, altHeavyMelee);
        } else {
            performGunshot(weapon);
        }
    }

    private void performKnifeAttack(Weapon knife, boolean heavyStab) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookDirection();
        double range = heavyStab ? 2.40 : knife.getMaxRange();

        Bot bestBot = null;
        double bestDist = range;
        boolean headshot = false;

        for (int i = 0; i < bots.size(); i++) {
            Bot bot = bots.get(i);
            if (!bot.isAlive()) continue;

            Bot.HitResult rayHit = bot.intersectShotRay(eye, look, range);
            if (rayHit != null && rayHit.distance < bestDist && map.hasLineOfSight(eye, rayHit.point)) {
                bestDist = rayHit.distance;
                bestBot = bot;
                headshot = rayHit.headshot;
                continue;
            }

            Vec3 botCenter = bot.getPosition().add(0.0, 1.05, 0.0);
            double dist = eye.dist(botCenter);
            if (dist < bestDist) {
                Vec3 toBot = botCenter.sub(eye).normalize();
                if (look.dot(toBot) > 0.55 && map.hasLineOfSight(eye, botCenter)) {
                    bestDist = dist;
                    bestBot = bot;
                    headshot = false;
                }
            }
        }

        if (bestBot != null) {
            int dmg = heavyStab ? (headshot ? 155 : 92) : (headshot ? knife.getHeadDamage() : knife.getBodyDamage());
            if (settings.showDamageNumbers) {
                damagePopups.add(new DamagePopup(bestBot.getPosition().add(0, 1.9, 0), dmg, headshot));
            }
            boolean killed = bestBot.takeDamage(dmg, player.getPosition());
            hitMarkerTimer = 0.18;
            hitMarkerHeadshot = headshot;
            soundSystem.playHitMarker(headshot);
            spawnHitParticles(bestBot.getPosition().add(0.0, 1.2, 0.0), look.scale(-1.0), 0xE62E2E, 10);
            if (killed) {
                onBotKilledByPlayer(bestBot, knife.getDisplayName(), headshot, knife.getKillReward());
            }
        } else {
            Vec3.RayHit wallHit = map.raycast(eye, look, range);
            if (wallHit != null) {
                spawnHitParticles(wallHit.point, wallHit.normal, 0xFFE082, 6);
            }
        }
    }

    private void performGunshot(Weapon gun) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookDirection();
        Vec3 right = player.getRightVector();
        Vec3 up = right.cross(look).scale(-1.0).normalize();

        boolean moving = player.getHorizontalSpeed() > 1.2;
        boolean airborne = !player.isOnGround();
        double spread = gun.computeCurrentSpread(moving, player.isSilentWalking(), airborne, player.isCrouching());

        player.applyWeaponRecoilKick(gun.getType());

        for (int i = 0; i < bots.size(); i++) {
            bots.get(i).notifyPlayerGunshot(player.getPosition(), gun.isSilenced());
        }

        Vec3 muzzle = gun.getMuzzleWorldPosition(player, settings.leftHandedModel);

        // Выброс латунной 3D-гильзы!
        if (settings.decalsAndShells) {
            double sideSign = settings.leftHandedModel ? -1.0 : 1.0;
            Vec3 ejectVel = right.scale(sideSign * (2.2 + rng.nextDouble() * 1.4))
                    .add(0.0, 2.4 + rng.nextDouble() * 1.1, 0.0)
                    .add(look.scale(-0.6));
            int shellCol = (gun.getType() == Weapon.Type.XM1014) ? 0xC62828 : 0xE6B84C;
            shells.add(new Vec3.ShellCasing(
                    muzzle.x - look.x * 0.25, muzzle.y + 0.04, muzzle.z - look.z * 0.25,
                    ejectVel.x, ejectVel.y, ejectVel.z, player.getYaw(), shellCol));
            while (shells.size() > 48) shells.remove(0);
        }

        int pellets = gun.getPelletsPerShot();
        boolean anyPelletHit = false;
        boolean anyHeadshot = false;

        for (int p = 0; p < pellets; p++) {
            double sx = rng.nextGaussian() * spread;
            double sy = rng.nextGaussian() * spread;
            Vec3 shotDir = look.add(right.scale(sx)).add(up.scale(sy)).normalize();

            double maxRange = gun.getMaxRange();
            Vec3.RayHit wallHit = map.raycast(eye, shotDir, maxRange);
            double maxDist = (wallHit != null) ? wallHit.distance : maxRange;
            Vec3 endPoint = (wallHit != null) ? wallHit.point : eye.add(shotDir.scale(maxRange));

            Bot hitBot = null;
            Bot.HitResult bestBotHit = null;
            for (int i = 0; i < bots.size(); i++) {
                Bot bot = bots.get(i);
                Bot.HitResult hr = bot.intersectShotRay(eye, shotDir, maxDist);
                if (hr != null && hr.distance < maxDist) {
                    maxDist = hr.distance;
                    bestBotHit = hr;
                    hitBot = bot;
                    endPoint = hr.point;
                }
            }

            // Выстрел сквозь дым CS2 на мгновение рассеивает дымовую завесу!
            for (int i = 0; i < smokeClouds.size(); i++) {
                Vec3.SmokeCloud sc = smokeClouds.get(i);
                if (sc.blocksSegment(eye, endPoint)) {
                    sc.bulletHoleTimer = (gun.getType() == Weapon.Type.AWP) ? 0.95 : 0.32;
                }
            }

            int tracerCol = gun.isSilenced() ? 0xB8E4FF : 0xFFE37E;
            tracers.add(new Vec3.Tracer(muzzle, endPoint, tracerCol, gun.getType() == Weapon.Type.AWP ? 0.14 : 0.075));

            if (hitBot != null && bestBotHit != null) {
                anyPelletHit = true;
                if (bestBotHit.headshot) anyHeadshot = true;

                int baseDmg = bestBotHit.headshot ? gun.getHeadDamage() : gun.getBodyDamage();
                double falloff = (gun.getType() == Weapon.Type.AWP)
                        ? 1.0
                        : Math.max(0.65, 1.0 - bestBotHit.distance / 145.0);
                int finalDamage = Math.max(1, (int) Math.round(baseDmg * falloff));

                if (settings.showDamageNumbers) {
                    damagePopups.add(new DamagePopup(bestBotHit.point.add(0, 0.25, 0), finalDamage, bestBotHit.headshot));
                }

                boolean killed = hitBot.takeDamage(finalDamage, player.getPosition());
                int particleColor = bestBotHit.headshot ? 0xFFAA22 : 0xDC2626;
                spawnHitParticles(bestBotHit.point, shotDir.scale(-1.0), particleColor, bestBotHit.headshot ? 10 : 6);

                if (killed) {
                    onBotKilledByPlayer(hitBot, gun.getDisplayName(), bestBotHit.headshot, gun.getKillReward());
                }
            } else if (wallHit != null) {
                spawnHitParticles(wallHit.point, wallHit.normal, 0xE4C896, pellets > 1 ? 3 : 7);
                if (p == 0) {
                    spawnHitParticles(wallHit.point, wallHit.normal, 0xFFD56B, 3);
                }
                if (settings.decalsAndShells) {
                    addBulletDecal(wallHit.point, wallHit.normal, 0.11, 0x2C2620);
                }
            }
        }

        player.recordShot(anyPelletHit);
        if (anyPelletHit) {
            hitMarkerTimer = 0.16;
            hitMarkerHeadshot = anyHeadshot;
            soundSystem.playHitMarker(anyHeadshot);
        }

        if (gun.getMagAmmo() == 0 && gun.getReserveAmmo() > 0) {
            if (gun.startReload()) soundSystem.playReload();
        }
    }

    private void addBulletDecal(Vec3 pt, Vec3 normal, double size, int color) {
        decals.add(new Vec3.Decal(pt, normal, size, color, 22.0));
        while (decals.size() > 70) {
            decals.remove(0);
        }
    }

    private void spawnHitParticles(Vec3 pos, Vec3 normal, int color, int count) {
        for (int i = 0; i < count; i++) {
            double vx = normal.x * (1.8 + rng.nextDouble() * 2.5) + (rng.nextDouble() - 0.5) * 3.4;
            double vy = normal.y * (1.8 + rng.nextDouble() * 2.5) + rng.nextDouble() * 2.8;
            double vz = normal.z * (1.8 + rng.nextDouble() * 2.5) + (rng.nextDouble() - 0.5) * 3.4;
            particles.add(new Vec3.Particle(
                pos.x + normal.x * 0.03, pos.y + normal.y * 0.03, pos.z + normal.z * 0.03,
                vx, vy, vz, color, 0.045 + rng.nextDouble() * 0.045, 0.22 + rng.nextDouble() * 0.28
            ));
        }
    }

    private void addKillFeed(String killer, String weapon, String victim, boolean headshot, boolean playerInvolved) {
        killFeed.add(0, new KillFeedEntry(killer, weapon, victim, headshot, playerInvolved));
        while (killFeed.size() > 5) killFeed.remove(killFeed.size() - 1);
    }

    private void renderWindowFrame() {
        BufferStrategy bs = getBufferStrategy();
        if (bs == null) {
            createBufferStrategy(3);
            return;
        }
        int outW = Math.max(1, getWidth());
        int outH = Math.max(1, getHeight());

        Graphics2D g = (Graphics2D) bs.getDrawGraphics();
        try {
            renderCompleteFrame(g, outW, outH);
        } finally {
            g.dispose();
        }
        if (!bs.contentsLost()) {
            bs.show();
        }
        Toolkit.getDefaultToolkit().sync();
    }

    /**
     * Полный рендер кадра (3D-сцена с настройками графики + HUD / Главное меню / Настройки).
     */
    public void renderCompleteFrame(Graphics2D g, int outW, int outH) {
        uiClickables.clear();

        double zoomFactor = (currentScreen == AppScreen.IN_GAME && player.isAlive())
                ? player.getCurrentWeapon().getEffectiveFovFactor()
                : 1.0;
        double effectiveFov = settings.fovDegrees * zoomFactor;

        renderer.ensureViewport(
                settings.resolutionScale.width,
                settings.resolutionScale.height,
                effectiveFov
        );
        renderer.applyTimeOfDay(settings.timeOfDay);
        renderer.setGraphicsFlags(
                settings.fogAndVignette,
                settings.skyCloudsAndSunFlare,
                settings.leftHandedModel,
                globalAnimTime
        );

        // Установка камеры (от первого лица в бою или плавный кинематографический облет в меню)
        if (currentScreen == AppScreen.IN_GAME || (currentScreen == AppScreen.SETTINGS && settingsOpenedFromGame)) {
            Vec3 eye = player.getEyePosition();
            renderer.setCamera(eye.x, eye.y, eye.z, player.getEffectiveYaw(), player.getEffectivePitch());
        } else {
            double orbitAngle = globalAnimTime * 0.16;
            double camX = Math.sin(orbitAngle) * 14.5;
            double camZ = Math.cos(orbitAngle) * 14.5 - 3.0;
            double camY = 4.6 + Math.sin(globalAnimTime * 0.35) * 0.7;
            double lookYaw = Math.atan2(-camX, 2.5 - camZ);
            renderer.setCamera(camX, camY, camZ, lookYaw, -0.17);
        }

        renderer.beginFrame();

        // 1. Пол и реалистичные солнечные тени
        map.renderFloorAndShadows(renderer, settings.shadowMode, bots);

        // 2. Стены, ящики, ворота и архитектура Dust II
        map.renderStructures(renderer);

        // 3. Следы от пуль (декали)
        if (settings.decalsAndShells) {
            for (int i = 0; i < decals.size(); i++) {
                renderer.drawDecal(decals.get(i));
            }
        }

        // 4. Подбираемые аптечки и боеприпасы
        for (int i = 0; i < pickups.size(); i++) {
            Vec3.Pickup pk = pickups.get(i);
            double py = pk.pos.y + 0.22 + Math.sin(pk.bobPhase) * 0.06;
            int col = (pk.type == Vec3.Pickup.Type.MEDKIT) ? 0x38C96B : 0xE6B444;
            renderer.drawOrientedWorldBox(pk.pos.x, py, pk.pos.z, 0.36, 0.24, 0.36, pk.bobPhase * 0.7, 0.0, Renderer3D.TEX_SOLID, col);
        }

        // 5. Летящие гранаты и 3D-гильзы
        for (int i = 0; i < grenades.size(); i++) {
            Vec3.Grenade gr = grenades.get(i);
            int gCol = (gr.kind == Vec3.Grenade.Kind.HE) ? 0x455A38 : 0x707882;
            renderer.drawOrientedWorldBox(gr.pos.x, gr.pos.y, gr.pos.z, 0.16, 0.22, 0.16, gr.yaw, gr.yaw * 0.8, Renderer3D.TEX_SOLID, gCol);
        }
        if (settings.decalsAndShells) {
            for (int i = 0; i < shells.size(); i++) {
                Vec3.ShellCasing sc = shells.get(i);
                renderer.drawOrientedWorldBox(sc.x, sc.y, sc.z, 0.025, 0.025, 0.065, sc.yaw, sc.pitch, Renderer3D.TEX_SOLID, sc.color);
            }
        }

        // 6. 3D-боты
        for (int i = 0; i < bots.size(); i++) {
            bots.get(i).render(renderer);
        }

        // 7. Трассеры, частицы и объемные облака дыма CS2
        for (int i = 0; i < tracers.size(); i++) {
            Vec3.Tracer tr = tracers.get(i);
            renderer.draw3DLine(tr.start, tr.end, tr.color, 2);
        }
        for (int i = 0; i < particles.size(); i++) {
            renderer.drawParticle(particles.get(i));
        }
        for (int i = 0; i < smokeClouds.size(); i++) {
            renderer.drawSmokeCloud(smokeClouds.get(i));
        }

        // 8. Модель оружия от первого лица
        if (currentScreen == AppScreen.IN_GAME && player.isAlive()) {
            renderer.clearDepthBuffer();
            player.getCurrentWeapon().renderViewModel(
                renderer,
                player.getWalkBobPhase(),
                player.getWalkSpeedNormalized(),
                player.getSwayX(),
                player.getSwayY()
            );
        }

        // Вывод 3D-буфера с выбранным в настройках сглаживанием
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                settings.bilinearFiltering
                        ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                        : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(renderer.getFrameImage(), 0, 0, outW, outH, null);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (settings.fogAndVignette) {
            drawCinematicVignette(g, outW, outH);
        }

        // Отрисовка текущего экрана UI
        if (currentScreen == AppScreen.MAIN_MENU) {
            drawMainMenuScreen(g, outW, outH);
        } else if (currentScreen == AppScreen.SETTINGS) {
            drawSettingsScreen(g, outW, outH);
        } else if (currentScreen == AppScreen.ARSENAL_GUIDE) {
            drawArsenalGuideScreen(g, outW, outH);
        } else {
            drawInGameUi(g, outW, outH);
        }
    }

    private void drawCinematicVignette(Graphics2D g, int outW, int outH) {
        if (cachedVignette == null || cachedVignette.getWidth() != outW || cachedVignette.getHeight() != outH) {
            cachedVignette = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D vg = cachedVignette.createGraphics();
            Point2D center = new Point2D.Float(outW * 0.5f, outH * 0.5f);
            float radius = Math.max(outW, outH) * 0.72f;
            float[] dist = {0.55f, 1.0f};
            Color[] colors = {new Color(0, 0, 0, 0), new Color(0, 0, 0, 108)};
            vg.setPaint(new RadialGradientPaint(center, radius, dist, colors));
            vg.fillRect(0, 0, outW, outH);
            vg.dispose();
        }
        g.drawImage(cachedVignette, 0, 0, null);
    }

    // =========================================================================
    //  1. ГЛАВНОЕ МЕНЮ В СТИЛЕ COUNTER-STRIKE 2 (PANORAMA UI)
    // =========================================================================
    private void drawMainMenuScreen(Graphics2D g, int outW, int outH) {
        // Левая полупрозрачная панель навигации
        int leftW = 390;
        g.setColor(new Color(10, 14, 22, 218));
        g.fillRect(0, 0, leftW, outH);
        g.setColor(new Color(245, 188, 66, 165));
        g.fillRect(leftW - 2, 0, 2, outH);

        // Логотип CS-2 JAVA
        g.setColor(new Color(245, 188, 66));
        g.fillRoundRect(34, 34, 56, 56, 12, 12);
        g.setColor(new Color(14, 18, 26));
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString("CS2", 39, 71);

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        g.drawString("CS-2 JAVA", 104, 62);
        g.setColor(new Color(245, 195, 76));
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString("DUST II • SOURCE 2 TACTICAL EDITION", 105, 83);

        int btnX = 34;
        int btnW = leftW - 68;
        int btnY = 132;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 54),
                "▶  ИГРАТЬ (НАЧАТЬ МАТЧ)", new Color(232, 152, 36), true, this::launchMatchFromMenu);
        btnY += 68;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 46),
                "⚙  НАСТРОЙКИ ГРАФИКИ", new Color(42, 108, 178), false, () -> openSettingsScreen(false, 0));
        btnY += 58;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 46),
                "◆  НАСТРОЙКИ ИГРЫ И МЫШИ", new Color(44, 62, 86), false, () -> openSettingsScreen(false, 1));
        btnY += 58;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 46),
                "♪  НАСТРОЙКИ АУДИО", new Color(44, 62, 86), false, () -> openSettingsScreen(false, 2));
        btnY += 58;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 46),
                "☰  АРСЕНАЛ И УПРАВЛЕНИЕ", new Color(44, 62, 86), false, () -> currentScreen = AppScreen.ARSENAL_GUIDE);
        btnY += 66;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 44),
                "✖  ВЫЙТИ НА РАБОЧИЙ СТОЛ", new Color(118, 42, 46), false, this::shutdownAndExit);

        // Нижняя сводка графики
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(165, 178, 196));
        g.drawString("Рендер: " + settings.resolutionScale.label + " • " + currentFps + " FPS", 34, outH - 42);
        g.drawString("Тени: " + settings.shadowMode.label + " • F11 Полный экран", 34, outH - 22);

        // Правая карточка быстрой настройки параметров боя (с живым предпросмотром 3D-сцены!)
        int cardW = 456;
        int cardH = 310;
        int cardX = outW - cardW - 34;
        int cardY = outH - cardH - 34;

        g.setColor(new Color(12, 17, 26, 214));
        g.fillRoundRect(cardX, cardY, cardW, cardH, 16, 16);
        g.setColor(new Color(255, 255, 255, 48));
        g.drawRoundRect(cardX, cardY, cardW, cardH, 16, 16);

        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.setColor(new Color(248, 204, 84));
        g.drawString("ПАРАМЕТРЫ ОПЕРАЦИИ: DUST II", cardX + 24, cardY + 34);

        int rowY = cardY + 58;
        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Сложность ИИ", settings.botDifficulty.label, () -> {
            int ord = (settings.botDifficulty.ordinal() + 1) % GameSettings.BotDifficulty.values().length;
            settings.botDifficulty = GameSettings.BotDifficulty.values()[ord];
            settings.save();
        });
        rowY += 50;

        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Противников", settings.botCount + " ботов", () -> {
            settings.botCount = (settings.botCount >= 8) ? 3 : settings.botCount + 1;
            settings.save();
            startNewMatch();
        });
        rowY += 50;

        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Освещение карты", settings.timeOfDay.label, () -> {
            int ord = (settings.timeOfDay.ordinal() + 1) % GameSettings.TimeOfDay.values().length;
            settings.timeOfDay = GameSettings.TimeOfDay.values()[ord];
            renderer.applyTimeOfDay(settings.timeOfDay);
            settings.save();
        });
        rowY += 50;

        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Матч до побед", settings.roundsToWin + " раундов", () -> {
            if (settings.roundsToWin == 3) settings.roundsToWin = 5;
            else if (settings.roundsToWin == 5) settings.roundsToWin = 8;
            else settings.roundsToWin = 3;
            settings.save();
        });

        drawCenteredText(g, "AWP • M4A1-S • Deagle • Магазин [B] • Гранаты [G / Q]",
                cardX + cardW / 2, cardY + cardH - 17,
                new Font("SansSerif", Font.PLAIN, 12), new Color(184, 198, 216));
    }

    // =========================================================================
    //  2. МЕНЮ НАСТРОЕК И НАСТРОЕК ГРАФИКИ (3 ВКЛАДКИ)
    // =========================================================================
    private void drawSettingsScreen(Graphics2D g, int outW, int outH) {
        g.setColor(new Color(8, 12, 18, 185));
        g.fillRect(0, 0, outW, outH);

        int pw = Math.min(880, outW - 60);
        int ph = Math.min(620, outH - 50);
        int px = outW / 2 - pw / 2;
        int py = outH / 2 - ph / 2;

        g.setColor(new Color(14, 19, 28, 240));
        g.fillRoundRect(px, py, pw, ph, 18, 18);
        g.setColor(new Color(245, 194, 72, 165));
        g.drawRoundRect(px, py, pw, ph, 18, 18);

        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.setColor(Color.WHITE);
        g.drawString("НАСТРОЙКИ CS-2 JAVA", px + 30, py + 42);

        // Вкладки: ГРАФИКА | ГЕЙМПЛЕЙ И МЫШЬ | АУДИО
        String[] tabs = {"1. НАСТРОЙКИ ГРАФИКИ", "2. ГЕЙМПЛЕЙ И МЫШЬ", "3. АУДИО И ЗВУК"};
        int tabW = (pw - 80) / 3;
        for (int i = 0; i < tabs.length; i++) {
            final int tabIdx = i;
            Rectangle tr = new Rectangle(px + 30 + i * (tabW + 10), py + 58, tabW, 38);
            boolean active = (settingsTab == i);
            addMenuButton(g, tr, tabs[i], active ? new Color(228, 152, 38) : new Color(34, 44, 58), active, () -> settingsTab = tabIdx);
        }

        int listX = px + 34;
        int listW = pw - 68;
        int rowY = py + 114;
        int rowStep = 41;

        if (settingsTab == 0) {
            // ВКЛАДКА: НАСТРОЙКИ ГРАФИКИ
            drawOptionStepperRow(g, listX, rowY, listW, "Общий пресет качества графики", settings.qualityPreset.title, () -> {
                int next = (settings.qualityPreset.ordinal() + 1) % GameSettings.QualityPreset.values().length;
                settings.applyGraphicsPreset(GameSettings.QualityPreset.values()[next]);
                settings.save();
            });
            rowY += rowStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Внутреннее 3D-разрешение рендера", settings.resolutionScale.label, () -> {
                int next = (settings.resolutionScale.ordinal() + 1) % GameSettings.ResolutionScale.values().length;
                settings.resolutionScale = GameSettings.ResolutionScale.values()[next];
                settings.save();
            });
            rowY += rowStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Качество солнечных 3D-теней", settings.shadowMode.label, () -> {
                int next = (settings.shadowMode.ordinal() + 1) % GameSettings.ShadowMode.values().length;
                settings.shadowMode = GameSettings.ShadowMode.values()[next];
                settings.save();
            });
            rowY += rowStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Фильтрация / Сглаживание кадра",
                    settings.bilinearFiltering ? "Билинейное сглаживание (Мягко)" : "Четкие пиксели (Резко)", () -> {
                settings.bilinearFiltering = !settings.bilinearFiltering;
                settings.save();
            });
            rowY += rowStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Объемные облака и ореол солнца",
                    settings.skyCloudsAndSunFlare ? "Включено" : "Выключено", () -> {
                settings.skyCloudsAndSunFlare = !settings.skyCloudsAndSunFlare;
                settings.save();
            });
            rowY += rowStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Декали пробоин и вылетающие 3D-гильзы",
                    settings.decalsAndShells ? "Включено (Реализм)" : "Выключено", () -> {
                settings.decalsAndShells = !settings.decalsAndShells;
                if (!settings.decalsAndShells) { decals.clear(); shells.clear(); }
                settings.save();
            });
            rowY += rowStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Атмосферная дымка и кино-виньетка",
                    settings.fogAndVignette ? "Включено" : "Выключено", () -> {
                settings.fogAndVignette = !settings.fogAndVignette;
                settings.save();
            });
            rowY += rowStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Угол обзора камеры (FOV)", settings.fovDegrees + "°", () -> {
                settings.fovDegrees += 4;
                if (settings.fovDegrees > 84) settings.fovDegrees = 56;
                settings.save();
            });
            rowY += rowStep;

            String capLabel = (settings.fpsLimit == 0) ? "Без лимита (Макс.)" : (settings.fpsLimit + " FPS");
            drawOptionStepperRow(g, listX, rowY, listW, "Ограничение частоты кадров", capLabel, () -> {
                if (settings.fpsLimit == 60) settings.fpsLimit = 120;
                else if (settings.fpsLimit == 120) settings.fpsLimit = 0;
                else settings.fpsLimit = 60;
                settings.save();
            });
            rowY += rowStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Освещение / Время суток Dust II", settings.timeOfDay.label, () -> {
                int next = (settings.timeOfDay.ordinal() + 1) % GameSettings.TimeOfDay.values().length;
                settings.timeOfDay = GameSettings.TimeOfDay.values()[next];
                renderer.applyTimeOfDay(settings.timeOfDay);
                settings.save();
            });

        } else if (settingsTab == 1) {
            // ВКЛАДКА: ГЕЙМПЛЕЙ И МЫШЬ
            drawOptionPlusMinusRow(g, listX, rowY, listW, "Чувствительность мыши",
                    String.format("%.4f", settings.mouseSensitivity),
                    () -> { settings.mouseSensitivity = Math.max(0.0005, settings.mouseSensitivity - 0.0003); settings.save(); },
                    () -> { settings.mouseSensitivity = Math.min(0.0080, settings.mouseSensitivity + 0.0003); settings.save(); });
            rowY += 46;

            drawOptionStepperRow(g, listX, rowY, listW, "Инверсия вертикальной оси мыши (Y)",
                    settings.invertMouseY ? "Включена" : "Выключена", () -> {
                settings.invertMouseY = !settings.invertMouseY;
                settings.save();
            });
            rowY += 46;

            drawOptionStepperRow(g, listX, rowY, listW, "Цвет прицела CS2", settings.crosshairColor.label, () -> {
                int next = (settings.crosshairColor.ordinal() + 1) % GameSettings.CrosshairColor.values().length;
                settings.crosshairColor = GameSettings.CrosshairColor.values()[next];
                settings.save();
            });
            rowY += 46;

            drawOptionStepperRow(g, listX, rowY, listW, "Поведение прицела при стрельбе",
                    settings.dynamicCrosshair ? "Динамический (расширяется)" : "Статический (Киберспорт)", () -> {
                settings.dynamicCrosshair = !settings.dynamicCrosshair;
                settings.save();
            });
            rowY += 46;

            drawOptionStepperRow(g, listX, rowY, listW, "Ведущая рука с оружием (cl_righthand)",
                    settings.leftHandedModel ? "Левая рука" : "Правая рука", () -> {
                settings.leftHandedModel = !settings.leftHandedModel;
                settings.save();
            });
            rowY += 46;

            drawOptionStepperRow(g, listX, rowY, listW, "Всплывающие цифры урона при попадании",
                    settings.showDamageNumbers ? "Показывать" : "Скрыть", () -> {
                settings.showDamageNumbers = !settings.showDamageNumbers;
                settings.save();
            });
            rowY += 46;

            drawOptionStepperRow(g, listX, rowY, listW, "Сложность ИИ ботов", settings.botDifficulty.label, () -> {
                int next = (settings.botDifficulty.ordinal() + 1) % GameSettings.BotDifficulty.values().length;
                settings.botDifficulty = GameSettings.BotDifficulty.values()[next];
                settings.save();
            });
            rowY += 46;

            drawOptionStepperRow(g, listX, rowY, listW, "Полноэкранный режим окна (F11)",
                    settings.fullscreen ? "Полный экран" : "Оконный режим", this::toggleFullscreenMode);

        } else {
            // ВКЛАДКА: АУДИО
            int pct = (int) Math.round(settings.masterVolume * 100.0);
            drawOptionPlusMinusRow(g, listX, rowY, listW, "Общая громкость звуковых эффектов", pct + "%",
                    () -> {
                        settings.masterVolume = Math.max(0.0, settings.masterVolume - 0.10);
                        soundSystem.setMasterVolume(settings.masterVolume);
                        settings.save();
                    },
                    () -> {
                        settings.masterVolume = Math.min(1.0, settings.masterVolume + 0.10);
                        soundSystem.setMasterVolume(settings.masterVolume);
                        settings.save();
                    });
            rowY += 54;

            drawOptionStepperRow(g, listX, rowY, listW, "Звук шагов по песку и звон гильз",
                    settings.footstepSounds ? "Включено" : "Выключено", () -> {
                settings.footstepSounds = !settings.footstepSounds;
                settings.save();
            });
            rowY += 54;

            drawOptionStepperRow(g, listX, rowY, listW, "Счетчик FPS и время кадра на радаре",
                    settings.showFpsTelemetry ? "Показывать" : "Скрыть", () -> {
                settings.showFpsTelemetry = !settings.showFpsTelemetry;
                settings.save();
            });
            rowY += 64;

            addMenuButton(g, new Rectangle(listX, rowY, 320, 42), "♪  ПРОВЕРИТЬ ЗВУК ВЫСТРЕЛА AK-47",
                    new Color(48, 118, 178), false, () -> soundSystem.playWeaponShot(Weapon.Type.AK47));
        }

        String backLabel = settingsOpenedFromGame ? "← СОХРАНИТЬ И ВЕРНУТЬСЯ В БОЙ (ESC)" : "← СОХРАНИТЬ И В ГЛАВНОЕ МЕНЮ (ESC)";
        addMenuButton(g, new Rectangle(px + pw / 2 - 190, py + ph - 58, 380, 44),
                backLabel, new Color(56, 156, 96), false, this::closeSettingsScreen);
    }

    // =========================================================================
    //  3. СПРАВОЧНИК АРСЕНАЛА И УПРАВЛЕНИЯ
    // =========================================================================
    private void drawArsenalGuideScreen(Graphics2D g, int outW, int outH) {
        g.setColor(new Color(8, 12, 18, 196));
        g.fillRect(0, 0, outW, outH);

        int pw = Math.min(860, outW - 60);
        int ph = Math.min(580, outH - 60);
        int px = outW / 2 - pw / 2;
        int py = outH / 2 - ph / 2;

        g.setColor(new Color(15, 20, 30, 244));
        g.fillRoundRect(px, py, pw, ph, 18, 18);
        g.setColor(new Color(245, 194, 72, 160));
        g.drawRoundRect(px, py, pw, ph, 18, 18);

        drawCenteredText(g, "АРСЕНАЛ И ТАКТИЧЕСКОЕ УПРАВЛЕНИЕ CS-2 JAVA", outW / 2, py + 42,
                new Font("SansSerif", Font.BOLD, 22), new Color(248, 206, 88));

        String[] leftLines = {
            "УПРАВЛЕНИЕ ПЕРСОНАЖЕМ И ОГНЕМ:",
            "• WASD — Передвижение по арене",
            "• Space — Прыжок (каскады ящиков Dust II)",
            "• Ctrl (или C) — Приседание за укрытиями",
            "• Shift — Тихий шаг (без шума + высокая кучность)",
            "• ЛКМ — Стрельба / Быстрый взмах ножом",
            "• ПКМ — Оптический зум AWP / Прицел ADS / Удар ножом",
            "• R — Перезарядка магазина",
            "• F — Осмотр 3D-модели оружия в руках (Inspect)",
            "• B — Открыть магазин закупки оружия ($)",
            "• 4 (или G) — Бросок осколочной гранаты HE",
            "• 5 (или Q) — Дымовая шашка CS2 (скрывает от ботов!)"
        };

        String[] rightLines = {
            "ВООРУЖЕНИЕ И СНАРЯЖЕНИЕ (МАГАЗИН [B]):",
            "• AK-47 ($2700) — Автомат, ваншот в голову (116 ед.)",
            "• M4A1-S ($2900) — Бесшумный глушитель, мягкая отдача",
            "• AWP ($4750) — Снайперская, 1 выстрел = ликвидация",
            "• XM1014 ($2000) — Авто-дробовик (8 картечин за залп)",
            "• Desert Eagle ($700) — Крупный калибр (186 в голову)",
            "• Glock-18 ($200) — Скорострельный табельный пистолет",
            "• Боевой нож — +15% к скорости бега, $1500 за фраг",
            "",
            "СИСТЕМНЫЕ КЛАВИШИ:",
            "• Tab — Таблица счета   • F5 — Быстрый рестарт",
            "• F11 — Полный экран    • ESC — Пауза и настройки"
        };

        int ly = py + 86;
        for (int i = 0; i < leftLines.length; i++) {
            g.setFont(new Font("SansSerif", i == 0 ? Font.BOLD : Font.PLAIN, i == 0 ? 15 : 13));
            g.setColor(i == 0 ? new Color(102, 204, 255) : new Color(222, 230, 242));
            g.drawString(leftLines[i], px + 36, ly);
            ly += 31;
        }

        int ry = py + 86;
        for (int i = 0; i < rightLines.length; i++) {
            boolean header = i == 0 || i == 9;
            g.setFont(new Font("SansSerif", header ? Font.BOLD : Font.PLAIN, header ? 15 : 13));
            g.setColor(header ? new Color(248, 202, 82) : new Color(222, 230, 242));
            g.drawString(rightLines[i], px + pw / 2 + 16, ry);
            ry += 31;
        }

        addMenuButton(g, new Rectangle(px + pw / 2 - 160, py + ph - 60, 320, 44),
                "← ВЕРНУТЬСЯ В ГЛАВНОЕ МЕНЮ", new Color(56, 152, 98), false,
                () -> currentScreen = AppScreen.MAIN_MENU);
    }

    // =========================================================================
    //  4. БОЕВОЙ ИНТЕРФЕЙС IN-GAME (ПРИЦЕЛ AWP, РАДАР, МАГАЗИН [B], ПАУЗА)
    // =========================================================================
    private void drawInGameUi(Graphics2D g, int outW, int outH) {
        if (player.isAlive() && player.getCurrentWeapon().getScopeZoomLevel() > 0) {
            drawSniperScopeOverlay(g, outW, outH, player.getCurrentWeapon().getScopeZoomLevel());
        }

        drawBotFloatingBarsAndDamagePopups(g, outW, outH);
        drawScreenEffects(g, outW, outH);
        drawCrosshairAndHitmarker(g, outW, outH);
        drawTacticalHud(g, outW, outH);
        drawMinimapRadar(g, 18, 18, 166);
        drawKillFeedAndKd(g, outW);

        if (streakBannerTimer > 0.0) {
            drawCenteredText(g, streakBannerText, outW / 2, outH - 135,
                    new Font("SansSerif", Font.BOLD, 22), new Color(255, 214, 72));
        }

        if (scoreTabHeld) {
            drawScoreboardOverlay(g, outW, outH);
        }
        if (buyMenuOpen && !paused && matchState == MatchState.PLAYING) {
            drawBuyMenuOverlay(g, outW, outH);
        }

        if (matchState == MatchState.ROUND_OVER) {
            drawRoundOverBanner(g, outW, outH);
        } else if (roundBannerTimer > 0.0 && matchState == MatchState.PLAYING && !buyMenuOpen) {
            drawRoundStartBanner(g, outW, outH);
        }

        if (matchState == MatchState.MATCH_VICTORY || matchState == MatchState.MATCH_DEFEAT) {
            drawMatchEndScreen(g, outW, outH, matchState == MatchState.MATCH_VICTORY);
        } else if (paused) {
            drawPauseMenu(g, outW, outH);
        }
    }

    private void drawSniperScopeOverlay(Graphics2D g, int outW, int outH, int zoomLevel) {
        int radius = (int) (Math.min(outW, outH) * 0.44);
        int cx = outW / 2;
        int cy = outH / 2;

        // Затемнение вокруг линзы снайперского прицела AWP
        java.awt.geom.Area screenArea = new java.awt.geom.Area(new Rectangle(0, 0, outW, outH));
        java.awt.geom.Area lensArea = new java.awt.geom.Area(
                new java.awt.geom.Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
        screenArea.subtract(lensArea);
        g.setColor(new Color(6, 8, 12, 246));
        g.fill(screenArea);

        g.setStroke(new BasicStroke(3.0f));
        g.setColor(new Color(28, 34, 42));
        g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

        // Тонкая прицельная сетка Mil-Dot
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(15, 18, 22, 230));
        g.drawLine(cx - radius, cy, cx + radius, cy);
        g.drawLine(cx, cy - radius, cx, cy + radius);
        for (int m = -4; m <= 4; m++) {
            if (m == 0) continue;
            int step = m * 36;
            g.drawLine(cx + step, cy - 4, cx + step, cy + 4);
            g.drawLine(cx - 4, cy + step, cx + 4, cy + step);
        }
        g.setColor(new Color(255, 65, 55));
        g.fillOval(cx - 2, cy - 2, 5, 5);
        g.setStroke(new BasicStroke(1.0f));

        g.setFont(new Font("Monospaced", Font.BOLD, 13));
        g.setColor(new Color(120, 230, 170));
        g.drawString("AWP OPTICS • ZOOM " + (zoomLevel == 1 ? "2.8X" : "6.0X") + " (ПКМ)", cx + 22, cy + radius - 26);
    }

    private void drawBuyMenuOverlay(Graphics2D g, int outW, int outH) {
        g.setColor(new Color(8, 12, 18, 186));
        g.fillRect(0, 0, outW, outH);

        int bw = 660;
        int bh = 470;
        int bx = outW / 2 - bw / 2;
        int by = outH / 2 - bh / 2;

        g.setColor(new Color(15, 20, 30, 244));
        g.fillRoundRect(bx, by, bw, bh, 18, 18);
        g.setColor(new Color(102, 226, 138, 175));
        g.drawRoundRect(bx, by, bw, bh, 18, 18);

        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(Color.WHITE);
        g.drawString("МАГАЗИН СНАРЯЖЕНИЯ CS2", bx + 28, by + 40);

        g.setFont(new Font("Monospaced", Font.BOLD, 22));
        g.setColor(new Color(104, 240, 136));
        String cashStr = "БАЛАНС: $" + player.getMoney();
        g.drawString(cashStr, bx + bw - g.getFontMetrics().stringWidth(cashStr) - 28, by + 40);

        int itemX = bx + 28;
        int itemW = bw - 56;
        int iy = by + 64;
        int step = 44;

        drawBuyRow(g, itemX, iy, itemW, "[1] AK-47 — Штурмовая винтовка (ваншот в голову)", 2700,
                () -> tryPurchaseWeapon(Weapon.Type.AK47)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[2] M4A1-S — Винтовка спецназа с глушителем", 2900,
                () -> tryPurchaseWeapon(Weapon.Type.M4A1S)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[3] AWP — Снайперская винтовка с оптическим прицелом ПКМ", 4750,
                () -> tryPurchaseWeapon(Weapon.Type.AWP)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[4] XM1014 — Полуавтоматический дробовик (8 картечин)", 2000,
                () -> tryPurchaseWeapon(Weapon.Type.XM1014)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[5] Desert Eagle — Крупнокалиберный пистолет", 700,
                () -> tryPurchaseWeapon(Weapon.Type.DEAGLE)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[6] Осколочная граната HE (бросок на 4 / G)", 300,
                () -> tryPurchaseUtility(1)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[7] Дымовая граната Smoke (бросок на 5 / Q)", 300,
                () -> tryPurchaseUtility(2)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[8] Полный комплект брони + аптечка (100 HP / 100 AP)", 650,
                () -> tryPurchaseUtility(3));

        addMenuButton(g, new Rectangle(bx + bw / 2 - 140, by + bh - 52, 280, 38),
                "ЗАКРЫТЬ МАГАЗИН (B / ESC)", new Color(54, 72, 96), false, () -> {
            buyMenuOpen = false;
            setMouseCaptured(true);
        });
    }

    private void drawBuyRow(Graphics2D g, int x, int y, int w, String label, int cost, Runnable onBuy) {
        Rectangle rect = new Rectangle(x, y, w, 37);
        boolean hovered = rect.contains(mouseClientX, mouseClientY);
        boolean canAfford = player.getMoney() >= cost;

        g.setColor(hovered ? new Color(42, 62, 86) : new Color(25, 33, 46));
        g.fillRoundRect(x, y, w, 37, 8, 8);
        g.setColor(hovered ? new Color(245, 195, 76) : new Color(255, 255, 255, 35));
        g.drawRoundRect(x, y, w, 37, 8, 8);

        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(canAfford ? Color.WHITE : new Color(145, 152, 165));
        g.drawString(label, x + 14, y + 23);

        String priceTag = "$" + cost;
        g.setFont(new Font("Monospaced", Font.BOLD, 15));
        g.setColor(canAfford ? new Color(102, 238, 136) : new Color(238, 86, 82));
        g.drawString(priceTag, x + w - g.getFontMetrics().stringWidth(priceTag) - 16, y + 24);

        uiClickables.add(new UiClickable(rect, onBuy));
    }

    private void drawBotFloatingBarsAndDamagePopups(Graphics2D g, int outW, int outH) {
        double[] proj = new double[3];
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookDirection();
        double scaleX = (double) outW / renderer.getWidth();
        double scaleY = (double) outH / renderer.getHeight();

        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (!b.isAlive()) continue;

            Vec3 headTop = b.getPosition().add(0.0, 2.02, 0.0);
            Vec3 toBot = b.getPosition().add(0.0, 1.2, 0.0).sub(eye);
            double dist = toBot.length();
            boolean aimedAt = dist > 0.1 && look.dot(toBot.scale(1.0 / dist)) > 0.985;

            if (!b.shouldShowHealthBar() && !aimedAt) continue;
            if (!map.hasLineOfSight(eye, b.getEyePosition(), smokeClouds)) continue;

            if (renderer.projectToScreen(headTop.x, headTop.y, headTop.z, proj)) {
                int sx = (int) (proj[0] * scaleX);
                int sy = (int) (proj[1] * scaleY);
                int barW = Math.max(38, Math.min(76, (int) (420.0 / Math.max(3.5, proj[2]))));
                int barH = 6;
                int bx = sx - barW / 2;
                int by = sy - barH / 2;

                g.setColor(new Color(15, 18, 22, 195));
                g.fillRoundRect(bx - 2, by - 2, barW + 4, barH + 4, 4, 4);
                g.setColor(b.getHealth() > 45 ? new Color(235, 72, 65) : new Color(255, 165, 45));
                g.fillRect(bx, by, (int) ((barW * b.getHealth()) / 100.0), barH);

                if (aimedAt) {
                    String tag = b.getName() + " (" + b.getHealth() + " HP)";
                    FontMetrics fm = g.getFontMetrics();
                    g.setColor(new Color(255, 220, 210));
                    g.drawString(tag, sx - fm.stringWidth(tag) / 2, by - 5);
                }
            }
        }

        // Всплывающие цифры урона
        if (settings.showDamageNumbers) {
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            for (int i = 0; i < damagePopups.size(); i++) {
                DamagePopup dp = damagePopups.get(i);
                if (renderer.projectToScreen(dp.worldPos.x, dp.worldPos.y, dp.worldPos.z, proj)) {
                    int sx = (int) (proj[0] * scaleX);
                    int sy = (int) (proj[1] * scaleY);
                    int alpha = Math.max(0, Math.min(255, (int) (dp.life * 265.0)));
                    String txt = "-" + dp.damage + (dp.headshot ? " HS!" : "");
                    g.setColor(new Color(0, 0, 0, alpha));
                    g.drawString(txt, sx + 1, sy + 1);
                    g.setColor(dp.headshot ? new Color(255, 208, 64, alpha) : new Color(255, 102, 92, alpha));
                    g.drawString(txt, sx, sy);
                }
            }
        }
    }

    private void drawScreenEffects(Graphics2D g, int outW, int outH) {
        if (player.isAlive() && player.getCurrentWeapon().hasMuzzleFlash() && !player.getCurrentWeapon().isSilenced()) {
            g.setColor(new Color(255, 210, 110, 22));
            g.fillRect(0, 0, outW, outH);
        }

        double flash = player.getDamageFlash();
        if (flash > 0.01) {
            int alpha = Math.min(135, (int) (flash * 135.0));
            g.setColor(new Color(210, 25, 25, alpha));
            int border = 36;
            g.fillRect(0, 0, outW, border);
            g.fillRect(0, outH - border, outW, border);
            g.fillRect(0, border, border, outH - border * 2);
            g.fillRect(outW - border, border, border, outH - border * 2);

            Vec3 src = player.getLastDamageSource();
            if (src != null) {
                double dx = src.x - player.getPosition().x;
                double dz = src.z - player.getPosition().z;
                double relAngle = Math.atan2(dx, dz) - player.getYaw();
                int cx = outW / 2, cy = outH / 2;
                int rad = Math.min(outW, outH) / 5;
                int ix = cx + (int) (Math.sin(relAngle) * rad);
                int iy = cy - (int) (Math.cos(relAngle) * rad);
                g.setColor(new Color(255, 55, 55, Math.min(210, alpha + 60)));
                g.fillOval(ix - 8, iy - 8, 16, 16);
            }
        }

        if (!player.isAlive()) {
            g.setColor(new Color(95, 12, 12, 95));
            g.fillRect(0, 0, outW, outH);
        }
    }

    private void drawCrosshairAndHitmarker(Graphics2D g, int outW, int outH) {
        if (!player.isAlive()) return;
        Weapon w = player.getCurrentWeapon();
        int cx = outW / 2;
        int cy = outH / 2;

        if (w.getScopeZoomLevel() == 0) {
            int gap = 5;
            if (settings.dynamicCrosshair) {
                double spread = w.computeCurrentSpread(
                        player.getHorizontalSpeed() > 1.2, player.isSilentWalking(),
                        !player.isOnGround(), player.isCrouching());
                gap += (int) (spread * 175.0);
            }
            int len = 9;

            g.setColor(new Color(0, 0, 0, 185));
            g.fillRect(cx - gap - len - 1, cy - 2, len + 2, 4);
            g.fillRect(cx + gap - 1, cy - 2, len + 2, 4);
            g.fillRect(cx - 2, cy - gap - len - 1, 4, len + 2);
            g.fillRect(cx - 2, cy + gap - 1, 4, len + 2);

            g.setColor(settings.crosshairColor.color);
            g.fillRect(cx - gap - len, cy - 1, len, 2);
            g.fillRect(cx + gap, cy - 1, len, 2);
            g.fillRect(cx - 1, cy - gap - len, 2, len);
            g.fillRect(cx - 1, cy + gap, 2, len);
            g.fillRect(cx - 1, cy - 1, 2, 2);
        }

        if (hitMarkerTimer > 0.0) {
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(hitMarkerHeadshot ? new Color(255, 80, 65) : Color.WHITE);
            int hmInner = 5, hmOuter = 13;
            g.drawLine(cx - hmOuter, cy - hmOuter, cx - hmInner, cy - hmInner);
            g.drawLine(cx + hmOuter, cy - hmOuter, cx + hmInner, cy - hmInner);
            g.drawLine(cx - hmOuter, cy + hmOuter, cx - hmInner, cy + hmInner);
            g.drawLine(cx + hmOuter, cy + hmOuter, cx + hmInner, cy + hmInner);
            g.setStroke(new BasicStroke(1.0f));
        }

        if (w.isReloading()) {
            int barW = 116, barH = 7;
            int bx = cx - barW / 2, by = cy + 36;
            g.setColor(new Color(15, 20, 26, 205));
            g.fillRoundRect(bx - 2, by - 2, barW + 4, barH + 4, 6, 6);
            g.setColor(new Color(245, 196, 68));
            g.fillRoundRect(bx, by, (int) (barW * w.getReloadProgress()), barH, 4, 4);
            drawCenteredText(g, "ПЕРЕЗАРЯДКА...", cx, by - 6,
                    new Font("SansSerif", Font.BOLD, 12), new Color(255, 235, 175));
        }
    }

    private void drawTacticalHud(Graphics2D g, int outW, int outH) {
        // 1. Верхняя панель счета команд и таймера раунда
        int topW = 418, topH = 58;
        int topX = outW / 2 - topW / 2, topY = 14;

        g.setColor(new Color(16, 20, 28, 212));
        g.fillRoundRect(topX, topY, topW, topH, 12, 12);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect(topX, topY, topW, topH, 12, 12);

        g.setColor(new Color(74, 158, 245, 220));
        g.fillRoundRect(topX + 10, topY + 8, 76, 42, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("CT", topX + 18, topY + 24);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString(String.valueOf(playerRoundWins), topX + 54, topY + 38);

        g.setColor(new Color(224, 152, 52, 220));
        g.fillRoundRect(topX + topW - 86, topY + 8, 76, 42, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("BOTS", topX + topW - 50, topY + 24);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString(String.valueOf(botRoundWins), topX + topW - 76, topY + 38);

        int totalSec = Math.max(0, (int) Math.ceil(roundTimeLeft));
        String timeStr = String.format("%02d:%02d", totalSec / 60, totalSec % 60);
        drawCenteredText(g, timeStr, outW / 2, topY + 30,
                new Font("Monospaced", Font.BOLD, 22), totalSec <= 15 ? new Color(255, 95, 85) : Color.WHITE);

        String subStr = "РАУНД " + currentRound + " (ДО " + settings.roundsToWin + ") • ВРАГОВ: " + countAliveBots();
        drawCenteredText(g, subStr, outW / 2, topY + 49,
                new Font("SansSerif", Font.BOLD, 11), new Color(205, 214, 226));

        // 2. Панель денег CS2 + здоровья HP и брони AP (снизу слева)
        int hpW = 302, hpH = 74;
        int hpX = 20, hpY = outH - hpH - 22;

        // Плашка валюты $ и кнопки магазина [B]
        g.setColor(new Color(14, 20, 26, 208));
        g.fillRoundRect(hpX, hpY - 38, 220, 32, 10, 10);
        g.setFont(new Font("Monospaced", Font.BOLD, 17));
        g.setColor(new Color(102, 242, 136));
        g.drawString("$ " + player.getMoney(), hpX + 14, hpY - 17);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(245, 202, 86));
        g.drawString("[B] МАГАЗИН", hpX + 124, hpY - 17);

        g.setColor(new Color(14, 18, 25, 214));
        g.fillRoundRect(hpX, hpY, hpW, hpH, 12, 12);
        g.setColor(new Color(255, 255, 255, 35));
        g.drawRoundRect(hpX, hpY, hpW, hpH, 12, 12);

        int hp = player.getHealth();
        Color hpColor = hp > 50 ? new Color(96, 238, 132)
                : (hp > 25 ? new Color(250, 196, 62) : new Color(248, 72, 68));
        g.setColor(hpColor);
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.drawString("+" + hp, hpX + 16, hpY + 42);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(185, 198, 212));
        g.drawString("ЗДОРОВЬЕ", hpX + 18, hpY + 61);

        int barStartX = hpX + 124, barWidth = 160;
        g.setColor(new Color(45, 52, 62));
        g.fillRoundRect(barStartX, hpY + 18, barWidth, 10, 5, 5);
        g.setColor(hpColor);
        g.fillRoundRect(barStartX, hpY + 18, (int) (barWidth * (hp / 100.0)), 10, 5, 5);

        int ap = player.getArmor();
        g.setColor(new Color(95, 178, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        String stance = player.isCrouching() ? " [ПРИСЕД]" : (player.isSilentWalking() ? " [ШАГ]" : "");
        g.drawString("БРОНЯ: " + ap + stance, barStartX, hpY + 47);
        g.setColor(new Color(45, 52, 62));
        g.fillRoundRect(barStartX, hpY + 55, barWidth, 7, 4, 4);
        g.setColor(new Color(82, 168, 250));
        g.fillRoundRect(barStartX, hpY + 55, (int) (barWidth * (ap / 100.0)), 7, 4, 4);

        // 3. Панель оружия, патронов и гранат (снизу справа)
        int amW = 316, amH = 114;
        int amX = outW - amW - 20, amY = outH - amH - 22;

        g.setColor(new Color(14, 18, 25, 214));
        g.fillRoundRect(amX, amY, amW, amH, 12, 12);
        g.setColor(new Color(255, 255, 255, 35));
        g.drawRoundRect(amX, amY, amW, amH, 12, 12);

        Weapon activeWeapon = player.getCurrentWeapon();
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.setColor(new Color(248, 202, 82));
        g.drawString(activeWeapon.getDisplayName().toUpperCase(), amX + 16, amY + 23);

        // Гранаты справа сверху в карточке оружия
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(210, 224, 238));
        g.drawString("[G] HE:" + player.getHeGrenades() + "  [Q] Дым:" + player.getSmokeGrenades(), amX + 166, amY + 22);

        if (activeWeapon.isMelee()) {
            g.setFont(new Font("SansSerif", Font.BOLD, 24));
            g.setColor(Color.WHITE);
            g.drawString("ЛКМ / ПКМ УДАР", amX + 16, amY + 57);
        } else {
            int mag = activeWeapon.getMagAmmo();
            g.setFont(new Font("Monospaced", Font.BOLD, 34));
            g.setColor(mag <= Math.max(2, activeWeapon.getMaxMagAmmo() / 4) ? new Color(255, 92, 82) : Color.WHITE);
            g.drawString(String.format("%2d", mag), amX + 16, amY + 59);

            g.setFont(new Font("Monospaced", Font.BOLD, 20));
            g.setColor(new Color(175, 188, 204));
            g.drawString("/ " + activeWeapon.getReserveAmmo(), amX + 68, amY + 57);

            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.setColor(new Color(160, 178, 198));
            g.drawString("[ПКМ] Прицел  [F] Осмотр", amX + 158, amY + 55);
        }

        Weapon[] allW = player.getWeapons();
        int slotW = 92;
        for (int i = 0; i < allW.length; i++) {
            int sx = amX + 14 + i * (slotW + 6);
            int sy = amY + 82;
            boolean sel = (i == player.getCurrentWeaponIndex());
            g.setColor(sel ? new Color(245, 188, 66, 228) : new Color(36, 44, 56, 190));
            g.fillRoundRect(sx, sy, slotW, 22, 6, 6);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(sel ? new Color(18, 20, 24) : new Color(196, 206, 218));
            String shortName = (i == 2) ? "Нож" : (allW[i].getDisplayName().length() > 8
                    ? allW[i].getDisplayName().substring(0, 7) : allW[i].getDisplayName());
            g.drawString((i + 1) + ": " + shortName, sx + 6, sy + 15);
        }
    }

    private void drawMinimapRadar(Graphics2D g, int rx, int ry, int size) {
        int totalH = size + (settings.showFpsTelemetry ? 20 : 4);
        g.setColor(new Color(14, 18, 25, 214));
        g.fillRoundRect(rx, ry, size, totalH, 12, 12);
        g.setColor(new Color(255, 255, 255, 45));
        g.drawRoundRect(rx, ry, size, totalH, 12, 12);

        int pad = 10;
        int inner = size - pad * 2;
        double span = Map.MAX_X - Map.MIN_X;

        g.setColor(new Color(24, 28, 36, 205));
        g.fillRect(rx + pad, ry + pad, inner, inner);

        List<Vec3.Box> boxes = map.getBoxes();
        for (int i = 0; i < boxes.size(); i++) {
            Vec3.Box b = boxes.get(i);
            if (!b.collidable) continue;
            int bx0 = rx + pad + (int) (((b.minX - Map.MIN_X) / span) * inner);
            int bz0 = ry + pad + (int) (((Map.MAX_Z - b.maxZ) / span) * inner);
            int bw = Math.max(2, (int) (((b.maxX - b.minX) / span) * inner));
            int bh = Math.max(2, (int) (((b.maxZ - b.minZ) / span) * inner));
            g.setColor(b.maxY > 2.2 ? new Color(188, 165, 124, 190) : new Color(135, 115, 82, 165));
            g.fillRect(bx0, bz0, bw, bh);
        }

        // Дымовые завесы на радаре
        for (int i = 0; i < smokeClouds.size(); i++) {
            Vec3.SmokeCloud sc = smokeClouds.get(i);
            int sx = rx + pad + (int) (((sc.center.x - Map.MIN_X) / span) * inner);
            int sz = ry + pad + (int) (((Map.MAX_Z - sc.center.z) / span) * inner);
            int sr = Math.max(4, (int) ((sc.getCurrentRadius() / span) * inner));
            g.setColor(new Color(200, 210, 220, 145));
            g.fillOval(sx - sr, sz - sr, sr * 2, sr * 2);
        }

        Vec3 eye = player.getEyePosition();
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (!b.isAlive()) continue;
            boolean visibleOnRadar = b.isFiringRecently()
                    || b.getState() == Bot.State.ENGAGE
                    || map.hasLineOfSight(eye, b.getEyePosition(), smokeClouds);
            if (!visibleOnRadar) continue;

            int mx = rx + pad + (int) (((b.getPosition().x - Map.MIN_X) / span) * inner);
            int mz = ry + pad + (int) (((Map.MAX_Z - b.getPosition().z) / span) * inner);
            g.setColor(new Color(255, 68, 62));
            g.fillOval(mx - 4, mz - 4, 8, 8);
        }

        int px = rx + pad + (int) (((player.getPosition().x - Map.MIN_X) / span) * inner);
        int pz = ry + pad + (int) (((Map.MAX_Z - player.getPosition().z) / span) * inner);
        double yaw = player.getYaw();
        int lx1 = px + (int) (Math.sin(yaw - 0.5) * 18);
        int lz1 = pz - (int) (Math.cos(yaw - 0.5) * 18);
        int lx2 = px + (int) (Math.sin(yaw + 0.5) * 18);
        int lz2 = pz - (int) (Math.cos(yaw + 0.5) * 18);

        g.setColor(new Color(95, 225, 255, 80));
        g.fillPolygon(new int[]{px, lx1, lx2}, new int[]{pz, lz1, lz2}, 3);
        g.setColor(new Color(90, 245, 155));
        g.fillOval(px - 4, pz - 4, 9, 9);

        if (settings.showFpsTelemetry) {
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.setColor(new Color(214, 222, 234));
            g.drawString(String.format("DUST II • %d FPS (%.1f мс)", currentFps, lastFrameMs), rx + 11, ry + totalH - 8);
        }
    }

    private void drawKillFeedAndKd(Graphics2D g, int outW) {
        String kdText = String.format("K: %d   D: %d   K/D: %.2f   HS: %d",
                player.getKills(), player.getDeaths(), player.getKdRatio(), player.getHeadshots());
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        int boxW = fm.stringWidth(kdText) + 28;
        int boxX = outW - boxW - 18, boxY = 16;

        g.setColor(new Color(14, 18, 25, 212));
        g.fillRoundRect(boxX, boxY, boxW, 32, 10, 10);
        g.setColor(new Color(245, 195, 72, 140));
        g.drawRoundRect(boxX, boxY, boxW, 32, 10, 10);
        g.setColor(new Color(245, 247, 250));
        g.drawString(kdText, boxX + 14, boxY + 21);

        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics kfm = g.getFontMetrics();
        int ky = boxY + 42;
        for (int i = 0; i < killFeed.size(); i++) {
            KillFeedEntry e = killFeed.get(i);
            String line = e.killer + "  [" + e.weapon + (e.headshot ? " • HS" : "") + "]  " + e.victim;
            int lw = kfm.stringWidth(line) + 22;
            int lx = outW - lw - 18;
            int alpha = (int) Math.min(215.0, e.timer * 140.0);

            g.setColor(new Color(18, 22, 30, alpha));
            g.fillRoundRect(lx, ky, lw, 24, 8, 8);
            if (e.playerInvolved) {
                g.setColor(new Color(235, 80, 70, alpha));
                g.drawRoundRect(lx, ky, lw, 24, 8, 8);
            }
            g.setColor(new Color(245, 238, 220, Math.min(255, alpha + 35)));
            g.drawString(line, lx + 11, ky + 16);
            ky += 29;
        }
    }

    private void drawRoundStartBanner(Graphics2D g, int outW, int outH) {
        int bw = 460, bh = 68;
        int bx = outW / 2 - bw / 2, by = 88;
        g.setColor(new Color(16, 20, 28, 198));
        g.fillRoundRect(bx, by, bw, bh, 12, 12);
        g.setColor(new Color(245, 195, 72, 175));
        g.drawRoundRect(bx, by, bw, bh, 12, 12);

        drawCenteredText(g, "РАУНД " + currentRound + " • СЛОЖНОСТЬ: " + settings.botDifficulty.label.toUpperCase(),
                outW / 2, by + 29, new Font("SansSerif", Font.BOLD, 17), new Color(252, 212, 92));
        drawCenteredText(g, "Магазин оружия: [B] • Осмотр: [F] • Гранаты: [G / Q]",
                outW / 2, by + 52, new Font("SansSerif", Font.PLAIN, 13), new Color(220, 226, 235));
    }

    private void drawRoundOverBanner(Graphics2D g, int outW, int outH) {
        int bw = 530, bh = 118;
        int bx = outW / 2 - bw / 2, by = 102;
        Color accent = lastRoundWonByPlayer ? new Color(76, 176, 255) : new Color(238, 118, 62);
        g.setColor(new Color(14, 18, 26, 228));
        g.fillRoundRect(bx, by, bw, bh, 14, 14);
        g.setColor(accent);
        g.drawRoundRect(bx, by, bw, bh, 14, 14);

        String title = lastRoundWonByPlayer
                ? "РАУНД ВЫИГРАН! (COUNTER-TERRORISTS)"
                : "РАУНД ПРОИГРАН! (TERRORISTS WIN)";
        drawCenteredText(g, title, outW / 2, by + 36, new Font("SansSerif", Font.BOLD, 21), accent);
        drawCenteredText(g, roundResultReason, outW / 2, by + 64, new Font("SansSerif", Font.PLAIN, 15), Color.WHITE);
        drawCenteredText(g, String.format("Следующий раунд через %.1f с (Space/Enter — ускорить)",
                        Math.max(0.0, roundOverTimer)),
                outW / 2, by + 95, new Font("SansSerif", Font.PLAIN, 12), new Color(195, 205, 218));
    }

    private void drawScoreboardOverlay(Graphics2D g, int outW, int outH) {
        int sh = 165 + bots.size() * 32;
        int sw = 580;
        int sx = outW / 2 - sw / 2, sy = outH / 2 - sh / 2;

        g.setColor(new Color(12, 16, 24, 235));
        g.fillRoundRect(sx, sy, sw, sh, 14, 14);
        g.setColor(new Color(255, 255, 255, 65));
        g.drawRoundRect(sx, sy, sw, sh, 14, 14);

        drawCenteredText(g, "ТАБЛИЦА СЧЕТА — DUST II (" + settings.botDifficulty.label + ")", outW / 2, sy + 34,
                new Font("SansSerif", Font.BOLD, 18), new Color(248, 205, 86));
        drawCenteredText(g, "Счет по раундам: CT " + playerRoundWins + " : " + botRoundWins + " BOTS",
                outW / 2, sy + 58, new Font("SansSerif", Font.BOLD, 14), new Color(200, 212, 228));

        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(new Color(145, 162, 184));
        g.drawString("БОЕЦ", sx + 26, sy + 90);
        g.drawString("СТАТУС", sx + 225, sy + 90);
        g.drawString("HP", sx + 340, sy + 90);
        g.drawString("K / D / БАЛАНС", sx + 405, sy + 90);

        g.setColor(new Color(45, 92, 148, 135));
        g.fillRoundRect(sx + 18, sy + 100, sw - 36, 30, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString("ВЫ (Counter-Terrorist)", sx + 26, sy + 120);
        g.drawString(player.isAlive() ? "ЖИВ" : "УБИТ", sx + 225, sy + 120);
        g.drawString(String.valueOf(player.getHealth()), sx + 340, sy + 120);
        g.drawString(player.getKills() + " / " + player.getDeaths() + "  ($" + player.getMoney() + ")", sx + 405, sy + 120);

        int rowY = sy + 142;
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            g.setColor(new Color(65, 42, 34, 120));
            g.fillRoundRect(sx + 18, rowY, sw - 36, 26, 8, 8);
            g.setColor(b.isAlive() ? new Color(245, 215, 185) : new Color(138, 144, 154));
            g.drawString(b.getName(), sx + 26, rowY + 18);
            g.drawString(b.isAlive() ? b.getState().name() : "УБИТ", sx + 225, rowY + 18);
            g.drawString(String.valueOf(b.getHealth()), sx + 340, rowY + 18);
            g.drawString("Отряд ИИ", sx + 405, rowY + 18);
            rowY += 31;
        }
    }

    private void drawMatchEndScreen(Graphics2D g, int outW, int outH, boolean victory) {
        Composite oldComp = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.78f));
        g.setColor(new Color(8, 11, 16));
        g.fillRect(0, 0, outW, outH);
        g.setComposite(oldComp);

        int cw = 550, ch = 430;
        int cx = outW / 2 - cw / 2, cy = outH / 2 - ch / 2;
        Color accent = victory ? new Color(82, 224, 146) : new Color(245, 84, 76);

        g.setColor(new Color(16, 21, 30, 245));
        g.fillRoundRect(cx, cy, cw, ch, 18, 18);
        g.setStroke(new BasicStroke(2.2f));
        g.setColor(accent);
        g.drawRoundRect(cx, cy, cw, ch, 18, 18);
        g.setStroke(new BasicStroke(1.0f));

        drawCenteredText(g, victory ? "ПОБЕДА В МАТЧЕ!" : "ПОРАЖЕНИЕ В МАТЧЕ",
                outW / 2, cy + 50, new Font("SansSerif", Font.BOLD, 32), accent);
        drawCenteredText(g, victory ? "Спецназ успешно зачистил арену Dust II!" : "Отряд противников одержал верх",
                outW / 2, cy + 80, new Font("SansSerif", Font.PLAIN, 15), new Color(214, 222, 234));

        int statX = cx + 44, statY = cy + 102, statW = cw - 88, statH = 152;
        g.setColor(new Color(25, 32, 44));
        g.fillRoundRect(statX, statY, statW, statH, 12, 12);

        drawCenteredText(g, "ИТОГОВЫЙ СЧЕТ РАУНДОВ:  " + playerRoundWins + " : " + botRoundWins,
                outW / 2, statY + 32, new Font("SansSerif", Font.BOLD, 18), new Color(248, 206, 90));

        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.setColor(Color.WHITE);
        g.drawString("Убийств (Kills): " + player.getKills(), statX + 28, statY + 70);
        g.drawString("Смертей (Deaths): " + player.getDeaths(), statX + 250, statY + 70);
        g.drawString(String.format("Рейтинг K/D: %.2f", player.getKdRatio()), statX + 28, statY + 102);
        g.drawString("В голову (Headshots): " + player.getHeadshots(), statX + 250, statY + 102);
        g.drawString(String.format("Точность стрельбы: %.1f%% (%d/%d)",
                player.getAccuracyPercent(), player.getShotsHit(), player.getShotsFired()), statX + 28, statY + 134);

        addMenuButton(g, new Rectangle(cx + 85, cy + 274, cw - 170, 44),
                "ИГРАТЬ СНОВА (ENTER / F5)", new Color(56, 158, 98), true, () -> {
            startNewMatch();
            setMouseCaptured(true);
        });
        addMenuButton(g, new Rectangle(cx + 85, cy + 328, cw - 170, 40),
                "ВЫЙТИ В ГЛАВНОЕ МЕНЮ (ESC)", new Color(54, 72, 98), false, () -> {
            currentScreen = AppScreen.MAIN_MENU;
            setMouseCaptured(false);
        });
        addMenuButton(g, new Rectangle(cx + 155, cy + 378, cw - 310, 34),
                "ВЫЙТИ НА РАБОЧИЙ СТОЛ", new Color(108, 48, 52), false, this::shutdownAndExit);
    }

    private void drawPauseMenu(Graphics2D g, int outW, int outH) {
        g.setColor(new Color(8, 12, 18, 192));
        g.fillRect(0, 0, outW, outH);

        int mw = 460, mh = 396;
        int mx = outW / 2 - mw / 2, my = outH / 2 - mh / 2;

        g.setColor(new Color(16, 22, 32, 245));
        g.fillRoundRect(mx, my, mw, mh, 16, 16);
        g.setColor(new Color(245, 196, 76, 170));
        g.drawRoundRect(mx, my, mw, mh, 16, 16);

        drawCenteredText(g, "ПАУЗА — CS-2 JAVA", outW / 2, my + 42,
                new Font("SansSerif", Font.BOLD, 24), new Color(248, 206, 88));

        int bx = mx + 60, bw = mw - 120, by = my + 68;
        addMenuButton(g, new Rectangle(bx, by, bw, 44), "ПРОДОЛЖИТЬ БОЙ (ESC)",
                new Color(52, 136, 208), true, () -> { paused = false; setMouseCaptured(true); });
        by += 54;

        addMenuButton(g, new Rectangle(bx, by, bw, 44), "НАСТРОЙКИ И ГРАФИКА",
                new Color(46, 98, 152), false, () -> openSettingsScreen(true, 0));
        by += 54;

        addMenuButton(g, new Rectangle(bx, by, bw, 44), "МАГАЗИН ОРУЖИЯ (B)",
                new Color(52, 138, 88), false, () -> { paused = false; buyMenuOpen = true; setMouseCaptured(false); });
        by += 54;

        addMenuButton(g, new Rectangle(bx, by, bw, 44), "НАЧАТЬ МАТЧ ЗАНОВО (F5)",
                new Color(186, 128, 42), false, () -> { paused = false; startNewMatch(); setMouseCaptured(true); });
        by += 54;

        addMenuButton(g, new Rectangle(bx, by, bw, 44), "ВЫЙТИ В ГЛАВНОЕ МЕНЮ",
                new Color(128, 48, 52), false, () -> { paused = false; currentScreen = AppScreen.MAIN_MENU; setMouseCaptured(false); });
    }

    // =========================================================================
    //  UI-ХЕЛПЕРЫ КНОПОК И ПЕРЕКЛЮЧАТЕЛЕЙ
    // =========================================================================
    private void addMenuButton(Graphics2D g, Rectangle rect, String label, Color baseColor, boolean accentTextDark, Runnable onClick) {
        boolean hovered = rect.contains(mouseClientX, mouseClientY);
        Color fill = hovered ? baseColor.brighter() : baseColor;
        g.setColor(fill);
        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
        g.setColor(hovered ? Color.WHITE : new Color(255, 255, 255, 85));
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);

        Color txtCol = (accentTextDark && baseColor.getRed() > 200 && baseColor.getGreen() > 140)
                ? new Color(16, 18, 22) : Color.WHITE;
        drawCenteredText(g, label, rect.x + rect.width / 2, rect.y + rect.height / 2 + 5,
                new Font("SansSerif", Font.BOLD, 14), txtCol);
        uiClickables.add(new UiClickable(rect, onClick));
    }

    private void drawOptionStepperRow(Graphics2D g, int x, int y, int w, String title, String valueLabel, Runnable onToggle) {
        Rectangle fullRect = new Rectangle(x, y, w, 34);
        boolean hovered = fullRect.contains(mouseClientX, mouseClientY);

        g.setColor(hovered ? new Color(34, 46, 64, 220) : new Color(24, 32, 45, 200));
        g.fillRoundRect(x, y, w, 34, 8, 8);
        g.setColor(hovered ? new Color(245, 195, 76, 175) : new Color(255, 255, 255, 32));
        g.drawRoundRect(x, y, w, 34, 8, 8);

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(224, 232, 242));
        g.drawString(title, x + 14, y + 22);

        String pill = "◄  " + valueLabel + "  ►";
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        int pw = g.getFontMetrics().stringWidth(pill) + 18;
        int px = x + w - pw - 10;
        g.setColor(new Color(46, 68, 96));
        g.fillRoundRect(px, y + 5, pw, 24, 6, 6);
        g.setColor(new Color(248, 206, 88));
        g.drawString(pill, px + 9, y + 21);

        uiClickables.add(new UiClickable(fullRect, onToggle));
    }

    private void drawOptionPlusMinusRow(Graphics2D g, int x, int y, int w, String title, String valueLabel,
                                        Runnable onMinus, Runnable onPlus) {
        g.setColor(new Color(24, 32, 45, 200));
        g.fillRoundRect(x, y, w, 38, 8, 8);
        g.setColor(new Color(255, 255, 255, 32));
        g.drawRoundRect(x, y, w, 38, 8, 8);

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(224, 232, 242));
        g.drawString(title, x + 14, y + 24);

        Rectangle minusR = new Rectangle(x + w - 180, y + 5, 38, 28);
        Rectangle plusR  = new Rectangle(x + w - 48,  y + 5, 38, 28);

        addMenuButton(g, minusR, "−", new Color(52, 68, 90), false, onMinus);
        drawCenteredText(g, valueLabel, x + w - 95, y + 24, new Font("Monospaced", Font.BOLD, 14), new Color(248, 206, 88));
        addMenuButton(g, plusR, "+", new Color(52, 68, 90), false, onPlus);
    }

    private static void drawCenteredText(Graphics2D g, String text, int centerX, int baselineY,
                                         Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, centerX - fm.stringWidth(text) / 2, baselineY);
    }

    public int countAliveBots() {
        int c = 0;
        for (int i = 0; i < bots.size(); i++) {
            if (bots.get(i).isAlive()) c++;
        }
        return c;
    }

    public AppScreen getCurrentScreen() { return currentScreen; }
    public void setCurrentScreen(AppScreen screen) { this.currentScreen = screen; }
    public void setBuyMenuOpen(boolean open) { this.buyMenuOpen = open; }
    public GameSettings getSettings() { return settings; }
    public Player getPlayer() { return player; }
    public Map getMap() { return map; }
    public List<Bot> getBots() { return bots; }
    public List<Vec3.SmokeCloud> getSmokeClouds() { return smokeClouds; }
    public MatchState getMatchState() { return matchState; }
    public int getPlayerRoundWins() { return playerRoundWins; }
    public int getBotRoundWins() { return botRoundWins; }
}
