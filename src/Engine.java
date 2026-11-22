import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Polygon;
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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Центральный движок CS-2 JAVA 4.0 (Source 2 Panorama UI Edition):
 * - Двойная буферизация кадра на базе легковесного JPanel (без сбоев BufferStrategy и чёрного экрана при F11!)
 * - Атомарные снимки UI-кнопок и потокобезопасная очередь ввода с мгновенным пробуждением игрового потока
 * - 3 арены (Dust II, Mirage, Nuke), Гонка вооружений, отряд ИИ-союзников CT, Zeus x27 и 3D-граффити
 */
public class Engine extends JPanel implements Runnable {
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_WINDOW_WIDTH = 1280;
    public static final int DEFAULT_WINDOW_HEIGHT = 720;
    private static final double ROUND_TIME_SECONDS = 100.0;

    public enum AppScreen {
        MAIN_MENU,
        SKIN_ARMORY,
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

    /**
     * Неизменяемый снимок всех активных кнопок текущего отрисованного кадра.
     * Читается потоком событий мыши (EDT) без блокировок и никогда не бывает пустым во время 3D-рендера!
     */
    private static final class UiSnapshot {
        static final UiSnapshot EMPTY = new UiSnapshot(
                Collections.emptyList(), DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        final List<UiClickable> items;
        final int width;
        final int height;

        UiSnapshot(List<UiClickable> items, int width, int height) {
            this.items = Collections.unmodifiableList(items);
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }
    }

    private static final String[] BOT_NAMES = {
        "BOT Phoenix", "BOT Viper", "BOT Sabre", "BOT Jackal",
        "BOT Corsair", "BOT Mirage", "BOT Anubis", "BOT Titan"
    };

    private static final String[] CT_ALLY_NAMES = {
        "CT Альфа", "CT Браво", "CT Дельта", "CT Призрак"
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
    private final List<Vec3.FireZone> fireZones = new ArrayList<>();
    private final List<Vec3.Graffiti> graffitis = new ArrayList<>();
    private final List<Vec3.Pickup> pickups = new ArrayList<>();
    private final List<Vec3.Rocket> rockets = new ArrayList<>();
    private final List<Vec3.SentryTurret> sentryTurrets = new ArrayList<>();
    private final List<Vec3.SupplyDrop> supplyDrops = new ArrayList<>();
    private final List<Vec3.AirstrikeBeacon> airstrikeBeacons = new ArrayList<>();
    private final List<DamagePopup> damagePopups = new ArrayList<>();
    private final List<KillFeedEntry> killFeed = new ArrayList<>();

    // Двойная буферизация UI-кнопок и очередь событий ввода (100% отклик кликов без гонок потоков!)
    private final List<UiClickable> buildingUiClickables = new ArrayList<>();
    private volatile UiSnapshot publishedUiSnapshot = UiSnapshot.EMPTY;
    private final ConcurrentLinkedQueue<Runnable> pendingInputTasks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingMouseDx = new AtomicInteger(0);
    private final AtomicInteger pendingMouseDy = new AtomicInteger(0);

    // Двойной внеэкранный буфер кадра для мгновенного переключения Fullscreen F11 без чёрного экрана
    private BufferedImage frameBufferA = null;
    private BufferedImage frameBufferB = null;
    private boolean nextBufferIsA = true;
    private volatile BufferedImage displayedFrame = null;
    private volatile Rectangle clickFlashRect = null;
    private double clickFlashTimer = 0.0;

    private Vec3.BombC4 plantedC4 = null;
    private Vec3.AttackHelicopter bossHelicopter = null;
    private int siegeWave = 1;
    private static final int MAX_SIEGE_WAVES = 5;
    private double siegeInterWaveTimer = 0.0;
    private double dmPlayerRespawnTimer = 0.0;
    private int nextGraffitiStyle = 0;
    private int radioOrderIndex = 0;
    private String radioBannerText = "";
    private double radioBannerTimer = 0.0;

    private final Weapon[] armoryShowcaseWeapons = new Weapon[]{
        new Weapon(Weapon.Type.AK47),
        new Weapon(Weapon.Type.M4A1S),
        new Weapon(Weapon.Type.AWP),
        new Weapon(Weapon.Type.M249),
        new Weapon(Weapon.Type.RPG7),
        new Weapon(Weapon.Type.XM1014),
        new Weapon(Weapon.Type.MP9),
        new Weapon(Weapon.Type.DEAGLE),
        new Weapon(Weapon.Type.GLOCK),
        new Weapon(Weapon.Type.ZEUS),
        new Weapon(Weapon.Type.KNIFE)
    };
    private int armoryWeaponIdx = 0;

    private final SoundSystem soundSystem;
    private final Random rng = new Random(2026L);
    private FullscreenListener fullscreenListener;

    private volatile boolean running = false;
    private volatile Thread gameThread;

    // Экраны меню и состояния матча
    private volatile AppScreen currentScreen = AppScreen.MAIN_MENU;
    private boolean settingsOpenedFromGame = false;
    private int settingsTab = 0; // 0 = Графика, 1 = Геймплей и мышь, 2 = Аудио
    private volatile boolean paused = false;
    private volatile boolean buyMenuOpen = false;

    private volatile MatchState matchState = MatchState.PLAYING;
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
    private volatile boolean mouseCaptured = false;
    private Robot robot;
    private Cursor blankCursor;
    private Cursor handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    private Cursor defaultCursor = Cursor.getDefaultCursor();
    private boolean lastHoveringButton = false;
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    private boolean recenteringCursor = false;
    private long lastCursorWarpMs = 0L;

    private final boolean[] keys = new boolean[512];
    private volatile boolean jumpQueued = false;
    private volatile boolean leftMouseDown = false;
    private volatile boolean scoreTabHeld = false;
    private volatile int mouseClientX = DEFAULT_WINDOW_WIDTH / 2;
    private volatile int mouseClientY = DEFAULT_WINDOW_HEIGHT / 2;

    private double hitMarkerTimer = 0.0;
    private boolean hitMarkerHeadshot = false;
    // Нулевая аллокация шрифтов Java2D: статический кэш на все размеры HUD и меню!
    private static final Font[] SANS_PLAIN_CACHE = new Font[64];
    private static final Font[] SANS_BOLD_CACHE = new Font[64];
    private static final Font[] MONO_BOLD_CACHE = new Font[64];
    static {
        for (int sz = 8; sz < 64; sz++) {
            SANS_PLAIN_CACHE[sz] = new Font("SansSerif", Font.PLAIN, sz);
            SANS_BOLD_CACHE[sz]  = new Font("SansSerif", Font.BOLD, sz);
            MONO_BOLD_CACHE[sz]  = new Font("Monospaced", Font.BOLD, sz);
        }
    }
    private static Font uiFont(String family, int style, int size) {
        int s = Math.max(8, Math.min(63, size));
        if ("Monospaced".equals(family)) return MONO_BOLD_CACHE[s];
        return (style == Font.BOLD) ? SANS_BOLD_CACHE[s] : SANS_PLAIN_CACHE[s];
    }

    private int currentFps = 60;
    private int onePercentLowFps = 55;
    private double lastFrameMs = 8.0;
    private double emaFrameMs = 10.0;
    private final float[] frameMsHistory = new float[120];
    private int frameMsCursor = 0;
    private double dynamicResScaleFactor = 1.0;
    private int dynResAdjustCounter = 0;
    private int graphicsSubPage = 0;

    private BufferedImage cachedVignette = null;
    private BufferedImage cachedRadarBase = null;
    private GameSettings.MapTheme cachedRadarTheme = null;
    private int cachedRadarInnerSize = -1;
    private final int[] radarPolyX = new int[3];
    private final int[] radarPolyZ = new int[3];

    public Engine(boolean headlessMode) {
        this.settings = new GameSettings();
        this.settings.syncDerivedFlags();
        java.util.Arrays.fill(this.frameMsHistory, 11.0f);
        this.renderer = new Renderer3D(
                settings.resolutionScale.resolveWidth(DEFAULT_WINDOW_WIDTH),
                settings.resolutionScale.resolveHeight(DEFAULT_WINDOW_HEIGHT),
                settings.fovDegrees
        );
        this.renderer.configureAdvancedGraphics(settings);
        this.renderer.applyTimeOfDay(settings.timeOfDay);

        this.map = new Map();
        this.player = new Player(map.getPlayerSpawn());
        this.soundSystem = new SoundSystem(!headlessMode);
        this.soundSystem.setMasterVolume(settings.masterVolume);

        setOpaque(true);
        setDoubleBuffered(false);
        setPreferredSize(new Dimension(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT));
        setBackground(new Color(10, 14, 22));
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
            // Заранее рендерим стартовый кадр, чтобы окно открылось мгновенно без чёрного экрана
            // и все кнопки меню реагировали с первой же миллисекунды!
            renderOffscreenSwap(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        BufferedImage img = this.displayedFrame;
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        if (img != null) {
            if (img.getWidth() == w && img.getHeight() == h) {
                g.drawImage(img, 0, 0, null);
            } else {
                // Плавное масштабирование при переходе в полный экран F11 до готовности нового кадра
                g.drawImage(img, 0, 0, w, h, null);
            }
        } else {
            g.setColor(new Color(10, 14, 22));
            g.fillRect(0, 0, w, h);
        }
    }

    public void notifyWindowModeChanged() {
        lastMouseX = -1;
        lastMouseY = -1;
        recenteringCursor = false;
        Thread t = this.gameThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
        repaint();
    }

    private void enqueueInputTask(Runnable task) {
        if (task == null) return;
        pendingInputTasks.offer(task);
        Thread t = this.gameThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    public void flushPendingInputTasks() {
        Runnable task;
        while ((task = pendingInputTasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        int mdx = pendingMouseDx.getAndSet(0);
        int mdy = pendingMouseDy.getAndSet(0);
        if ((mdx != 0 || mdy != 0) && mouseCaptured && !isCursorFreeScreen()) {
            player.applyMouseLook(mdx, mdy, settings.mouseSensitivity, settings.invertMouseY);
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
                final int code = e.getKeyCode();
                final boolean altDown = e.isAltDown();
                if (code >= 0 && code < keys.length) keys[code] = true;

                if (code == KeyEvent.VK_F11 || (code == KeyEvent.VK_ENTER && altDown)) {
                    e.consume();
                    enqueueInputTask(Engine.this::toggleFullscreenMode);
                    return;
                }
                enqueueInputTask(() -> handleKeyPress(code));
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
                updateUiMouseCoords(e.getX(), e.getY());
                if (!isFocusOwner()) {
                    requestFocusInWindow();
                }

                if (SwingUtilities.isLeftMouseButton(e) || e.getButton() == MouseEvent.BUTTON1) {
                    if (isCursorFreeScreen()) {
                        dispatchUiClick(e.getX(), e.getY(), getWidth(), getHeight());
                        return;
                    }
                    leftMouseDown = true;
                    enqueueInputTask(() -> {
                        if (!mouseCaptured && !isCursorFreeScreen()) {
                            setMouseCaptured(true);
                        }
                        tryFireCurrentWeapon(false);
                    });
                } else if (SwingUtilities.isRightMouseButton(e) || e.getButton() == MouseEvent.BUTTON3) {
                    if (!isCursorFreeScreen()) {
                        enqueueInputTask(() -> {
                            if (!isCursorFreeScreen() && matchState == MatchState.PLAYING && player.isAlive()) {
                                Weapon w = player.getCurrentWeapon();
                                if (w.isMelee()) {
                                    tryFireCurrentWeapon(true); // Мощный удар ножом ПКМ!
                                } else {
                                    w.toggleSecondaryAction(); // Оптический прицел AWP / ADS!
                                    soundSystem.playUiClick();
                                }
                            }
                        });
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) || e.getButton() == MouseEvent.BUTTON1) {
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
            final int rot = e.getWheelRotation();
            if (rot != 0 && !isCursorFreeScreen()) {
                enqueueInputTask(() -> {
                    if (!isCursorFreeScreen() && matchState == MatchState.PLAYING) {
                        if (rot > 0) player.cycleWeapon(1);
                        else player.cycleWeapon(-1);
                    }
                });
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

    private void updateUiMouseCoords(int rawX, int rawY) {
        UiSnapshot snap = this.publishedUiSnapshot;
        int compW = getWidth();
        int compH = getHeight();
        if (compW > 0 && compH > 0 && (compW != snap.width || compH != snap.height)) {
            this.mouseClientX = (int) Math.round((rawX * (double) snap.width) / compW);
            this.mouseClientY = (int) Math.round((rawY * (double) snap.height) / compH);
        } else {
            this.mouseClientX = rawX;
            this.mouseClientY = rawY;
        }
    }

    private boolean isCursorFreeScreen() {
        return currentScreen != AppScreen.IN_GAME
                || paused
                || buyMenuOpen
                || matchState == MatchState.MATCH_VICTORY
                || matchState == MatchState.MATCH_DEFEAT;
    }

    /**
     * Мгновенная потокобезопасная обработка клика по кнопке UI.
     * Работает по неизменяемому снимку publishedUiSnapshot (никогда не пустует во время рендера 3D!).
     */
    public boolean dispatchUiClick(int rawX, int rawY, int compW, int compH) {
        UiSnapshot snap = this.publishedUiSnapshot;
        int uiX = (compW > 0 && compW != snap.width)
                ? (int) Math.round((rawX * (double) snap.width) / compW) : rawX;
        int uiY = (compH > 0 && compH != snap.height)
                ? (int) Math.round((rawY * (double) snap.height) / compH) : rawY;

        this.mouseClientX = uiX;
        this.mouseClientY = uiY;

        List<UiClickable> list = snap.items;
        for (int i = list.size() - 1; i >= 0; i--) {
            UiClickable item = list.get(i);
            if (item.rect.contains(uiX, uiY)) {
                this.clickFlashRect = item.rect;
                this.clickFlashTimer = 0.12;
                soundSystem.playUiClick();
                enqueueInputTask(item.action);
                return true;
            }
        }
        return false;
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

        if (currentScreen == AppScreen.ARSENAL_GUIDE || currentScreen == AppScreen.SKIN_ARMORY) {
            if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_BACK_SPACE) {
                settings.save();
                currentScreen = AppScreen.MAIN_MENU;
            } else if (currentScreen == AppScreen.SKIN_ARMORY && code == KeyEvent.VK_RIGHT) {
                armoryWeaponIdx = (armoryWeaponIdx + 1) % armoryShowcaseWeapons.length;
            } else if (currentScreen == AppScreen.SKIN_ARMORY && code == KeyEvent.VK_LEFT) {
                armoryWeaponIdx = (armoryWeaponIdx + armoryShowcaseWeapons.length - 1) % armoryShowcaseWeapons.length;
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
            if (settings.gameMode == GameSettings.GameMode.ARMS_RACE) {
                soundSystem.playEmptyClick();
                streakBannerText = "В ГОНКЕ ВООРУЖЕНИЙ ОРУЖИЕ ВЫДАЕТСЯ ЗА ФРАГИ!";
                streakBannerTimer = 1.8;
                return;
            }
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
            } else if (!player.isAlive() && matchState == MatchState.PLAYING
                    && settings.gameMode != GameSettings.GameMode.DEATHMATCH
                    && settings.gameMode != GameSettings.GameMode.ARMS_RACE) {
                boolean ctWins = countAliveAllies() >= countAliveEnemies() && plantedC4 == null;
                finishRound(ctWins, ctWins ? "Союзники спецназа CT зачистили сектор!" : "Террористы удержали сектор");
            } else {
                jumpQueued = true;
            }
        } else if (code == KeyEvent.VK_1) {
            player.selectWeaponSlot(0);
        } else if (code == KeyEvent.VK_2) {
            player.selectWeaponSlot(1);
        } else if (code == KeyEvent.VK_3) {
            player.selectWeaponSlot(2);
        } else if (code == KeyEvent.VK_8 || code == KeyEvent.VK_T) {
            player.selectWeaponSlot(3);
        } else if (code == KeyEvent.VK_4 || code == KeyEvent.VK_G) {
            throwGrenade(Vec3.Grenade.Kind.HE);
        } else if (code == KeyEvent.VK_5 || code == KeyEvent.VK_Q) {
            throwGrenade(Vec3.Grenade.Kind.SMOKE);
        } else if (code == KeyEvent.VK_6 || code == KeyEvent.VK_V) {
            throwGrenade(Vec3.Grenade.Kind.FLASH);
        } else if (code == KeyEvent.VK_7 || code == KeyEvent.VK_X) {
            throwGrenade(Vec3.Grenade.Kind.MOLOTOV);
        } else if (code == KeyEvent.VK_Y) {
            trySprayGraffiti();
        } else if (code == KeyEvent.VK_J) {
            tryDeploySentryTurret();
        } else if (code == KeyEvent.VK_U) {
            tryCallPrecisionAirstrike();
        } else if (code == KeyEvent.VK_ALT) {
            if (player.tryTriggerDash(keys[KeyEvent.VK_W], keys[KeyEvent.VK_S], keys[KeyEvent.VK_A], keys[KeyEvent.VK_D])) {
                soundSystem.playWeaponShot(Weapon.Type.KNIFE);
            }
        } else if (code == KeyEvent.VK_K) {
            trySummonHelicopterChallenge();
        } else if (code == KeyEvent.VK_Z) {
            issueNextRadioCommand();
        } else if (code == KeyEvent.VK_L) {
            if (player.isAlive()) {
                player.toggleFlashlight();
                soundSystem.playUiClick();
            }
        } else if (code == KeyEvent.VK_H) {
            int nextSkin = (settings.weaponSkin.ordinal() + 1) % GameSettings.WeaponSkin.values().length;
            settings.weaponSkin = GameSettings.WeaponSkin.values()[nextSkin];
            settings.save();
            soundSystem.playUiClick();
            streakBannerText = "СКИН ОРУЖИЯ: " + settings.weaponSkin.label.toUpperCase();
            streakBannerTimer = 1.6;
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
            case KeyEvent.VK_4: tryPurchaseWeapon(Weapon.Type.M249); break;
            case KeyEvent.VK_5: tryPurchaseWeapon(Weapon.Type.RPG7); break;
            case KeyEvent.VK_6: tryPurchaseWeapon(Weapon.Type.XM1014); break;
            case KeyEvent.VK_7: tryPurchaseWeapon(Weapon.Type.DEAGLE); break;
            case KeyEvent.VK_8: tryPurchaseUtility(1); break;
            case KeyEvent.VK_9: tryPurchaseUtility(3); break;
            case KeyEvent.VK_0: tryPurchaseUtility(4); break;
            default: break;
        }
    }

    private void tryPurchaseWeapon(Weapon.Type type) {
        Weapon sample = new Weapon(type);
        if (player.getCurrentWeapon().getType() == type) {
            return;
        }
        int cost = (settings.gameMode == GameSettings.GameMode.DEATHMATCH) ? 0 : sample.getPrice();
        if (player.spendMoney(cost)) {
            player.equipPurchasedWeapon(type);
            soundSystem.playBuy();
        } else {
            soundSystem.playEmptyClick();
        }
    }

    private void tryPurchaseUtility(int kind) {
        boolean freeDm = (settings.gameMode == GameSettings.GameMode.DEATHMATCH);
        if (kind == 1 && (player.getHeGrenades() < 2 || player.getFlashGrenades() < 2)) {
            if (player.spendMoney(freeDm ? 0 : 400)) {
                player.addHeGrenade();
                player.addFlashGrenade();
                soundSystem.playBuy();
            } else soundSystem.playEmptyClick();
        } else if (kind == 2 && (player.getSmokeGrenades() < 2 || player.getMolotovGrenades() < 2)) {
            if (player.spendMoney(freeDm ? 0 : 600)) {
                player.addSmokeGrenade();
                player.addMolotovGrenade();
                soundSystem.playBuy();
            } else soundSystem.playEmptyClick();
        } else if (kind == 3 && (player.getHealth() < 100 || player.getArmor() < 100 || !player.hasDefuseKit() || !player.hasZeus())) {
            if (player.spendMoney(freeDm ? 0 : 850)) {
                player.healAndRestoreArmor(100, 100);
                player.setHasDefuseKit(true);
                player.equipPurchasedWeapon(Weapon.Type.ZEUS);
                player.selectWeaponSlot(0);
                soundSystem.playBuy();
            } else soundSystem.playEmptyClick();
        } else if (kind == 4 && (player.getSentryCharges() < 2 || player.getAirstrikeCharges() < 2)) {
            if (player.spendMoney(freeDm ? 0 : 1200)) {
                player.addSentryCharge();
                player.addAirstrikeCharge(1);
                soundSystem.playBuy();
                streakBannerText = "★ ПОЛУЧЕНА АВТО-ТУРЕЛЬ [J] + АВИАУДАР СПЕЦНАЗА [U]! ★";
                streakBannerTimer = 2.4;
            } else soundSystem.playEmptyClick();
        } else {
            soundSystem.playEmptyClick();
        }
    }

    /**
     * Развёртывание полевой 3D авто-турели спецназа CT перед игроком (клавиша J).
     */
    public void tryDeploySentryTurret() {
        if (!player.isAlive() || matchState != MatchState.PLAYING) return;
        if (!player.consumeSentryCharge()) {
            soundSystem.playEmptyClick();
            streakBannerText = "НЕТ ЗАРЯДОВ ТУРЕЛИ — КУПИТЕ В МАГАЗИНЕ [B] ИЛИ ПОДБЕРИТЕ АВИАСБРОС!";
            streakBannerTimer = 2.0;
            return;
        }
        double yaw = player.getYaw();
        double tx = clampVal(player.getPosition().x + Math.sin(yaw) * 1.45, Map.MIN_X + 1.5, Map.MAX_X - 1.5);
        double tz = clampVal(player.getPosition().z + Math.cos(yaw) * 1.45, Map.MIN_Z + 1.5, Map.MAX_Z - 1.5);
        double floorY = map.getSupportingFloorY(tx, tz, 0.35, player.getPosition().y + 0.4);
        sentryTurrets.add(new Vec3.SentryTurret(new Vec3(tx, floorY, tz), yaw));
        while (sentryTurrets.size() > 3) {
            sentryTurrets.remove(0);
        }
        soundSystem.playC4Keypad();
        streakBannerText = "⚙ АВТО-ТУРЕЛЬ СПЕЦНАЗА УСТАНОВЛЕНА! СЕКТОР ПОД ОХРАНОЙ ⚙";
        streakBannerTimer = 2.2;
    }

    /**
     * Точечный орбитальный/артиллерийский авиаудар спецназа CT по лазерному целеуказателю [U]!
     */
    public void tryCallPrecisionAirstrike() {
        if (!player.isAlive() || matchState != MatchState.PLAYING) return;
        if (!player.consumeAirstrikeCharge()) {
            soundSystem.playEmptyClick();
            streakBannerText = "НЕТ ЗАРЯДОВ АВИАУДАРА [U] — ДАЁТСЯ ЗА 4 ФРАГА ИЛИ В МАГАЗИНЕ [B]!";
            streakBannerTimer = 2.0;
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getLookDirection();
        Vec3.RayHit hit = map.raycast(eye, dir, 75.0);
        Vec3 target;
        if (hit != null) {
            target = new Vec3(
                    clampVal(hit.point.x, Map.MIN_X + 2.0, Map.MAX_X - 2.0),
                    0.0,
                    clampVal(hit.point.z, Map.MIN_Z + 2.0, Map.MAX_Z - 2.0));
        } else {
            target = new Vec3(
                    clampVal(eye.x + dir.x * 16.0, Map.MIN_X + 2.0, Map.MAX_X - 2.0),
                    0.0,
                    clampVal(eye.z + dir.z * 16.0, Map.MIN_Z + 2.0, Map.MAX_Z - 2.0));
        }
        target.y = map.getSupportingFloorY(target.x, target.z, 0.3, 4.0);
        airstrikeBeacons.add(new Vec3.AirstrikeBeacon(target));
        soundSystem.playRadioBeep();
        streakBannerText = "☄ НАВЕДЁН АВИАУДАР СПЕЦНАЗА! ЗАЛП РАКЕТ ЧЕРЕЗ 1.5 СЕК! ☄";
        streakBannerTimer = 2.5;
    }

    private void updateAirstrikeBeacons(double dt) {
        for (Iterator<Vec3.AirstrikeBeacon> it = airstrikeBeacons.iterator(); it.hasNext(); ) {
            Vec3.AirstrikeBeacon ab = it.next();
            ab.animPhase += dt;
            if (ab.countdown > 0.0) {
                ab.countdown -= dt;
                continue;
            }
            ab.nextShellTimer -= dt;
            if (ab.nextShellTimer <= 0.0 && ab.shellsLaunched < 3) {
                ab.nextShellTimer = 0.22;
                double ox = (ab.shellsLaunched == 0) ? 0.0 : ((ab.shellsLaunched == 1) ? -2.2 : 2.2);
                double oz = (ab.shellsLaunched == 0) ? 0.0 : ((ab.shellsLaunched == 1) ? 1.6 : -1.6);
                Vec3 start = new Vec3(ab.targetPos.x + ox + 2.5, 25.0, ab.targetPos.z + oz - 2.0);
                Vec3 dest = new Vec3(ab.targetPos.x + ox, ab.targetPos.y + 0.1, ab.targetPos.z + oz);
                rockets.add(new Vec3.Rocket(start, dest.sub(start), 44.0));
                soundSystem.playWeaponShot(Weapon.Type.RPG7);
                ab.shellsLaunched++;
            }
            if (ab.shellsLaunched >= 3) {
                it.remove();
            }
        }
    }

    /**
     * Ручной вызов Боевого Вертолёта-Босса в небо по клавише [K] для эпичной схватки на любой карте!
     */
    public void trySummonHelicopterChallenge() {
        if (matchState != MatchState.PLAYING) return;
        if (bossHelicopter != null && bossHelicopter.isAlive()) {
            streakBannerText = "⚠ БОЕВОЙ ВЕРТОЛЁТ УЖЕ В ВОЗДУХЕ! СБЕЙТЕ ЕГО ИЗ RPG-7 / M249 / AWP! ⚠";
            streakBannerTimer = 2.2;
            return;
        }
        bossHelicopter = new Vec3.AttackHelicopter(850);
        soundSystem.playRadioBeep();
        streakBannerText = "⚠ ТРЕВОГА: В НЕБЕ УДАРНЫЙ ВЕРТОЛЁТ БОССА (850 HP)! ⚠";
        streakBannerTimer = 3.2;
    }

    private static double clampVal(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void trySprayGraffiti() {
        if (!player.isAlive() || !player.canSprayGraffiti()) return;
        Vec3.RayHit hit = map.raycast(player.getEyePosition(), player.getLookDirection(), 6.8);
        if (hit == null) {
            soundSystem.playEmptyClick();
            return;
        }
        player.triggerGraffitiCooldown();
        int[][] palettes = {
            {0xFFD238, 0xFF5238},
            {0x2CE8FF, 0xFF3E96},
            {0x42FF88, 0xFFE24A}
        };
        int style = nextGraffitiStyle % 3;
        nextGraffitiStyle++;
        graffitis.add(new Vec3.Graffiti(hit.point, hit.normal, palettes[style][0], palettes[style][1], style));
        while (graffitis.size() > 24) {
            graffitis.remove(0);
        }
        soundSystem.playSprayCan();
    }

    public void issueNextRadioCommand() {
        if (!player.isAlive()) return;
        Bot.RadioOrder[] orders = {
            Bot.RadioOrder.FOLLOW_PLAYER,
            Bot.RadioOrder.HOLD_POSITION,
            Bot.RadioOrder.RUSH_SITE,
            Bot.RadioOrder.PATROL_FREE
        };
        String[] messages = {
            "[РАДИО CT] Вы: «Отряд, за мной! Прикройте!»",
            "[РАДИО CT] Вы: «Закрепиться и держать позицию!»",
            "[РАДИО CT] Вы: «Штурмуем точки A и B!»",
            "[РАДИО CT] Вы: «Свободный поиск противника!»"
        };
        Bot.RadioOrder chosen = orders[radioOrderIndex % orders.length];
        String msg = messages[radioOrderIndex % messages.length];
        radioOrderIndex++;

        int responders = 0;
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (b.isAlly() && b.isAlive()) {
                b.setRadioOrder(chosen);
                responders++;
            }
        }
        soundSystem.playRadioBeep();
        radioBannerText = msg + (responders > 0 ? "  •  Принято (" + responders + " CT)" : "  •  Нет союзников");
        radioBannerTimer = 3.8;
    }

    public void throwGrenade(Vec3.Grenade.Kind kind) {
        if (!player.isAlive() || matchState != MatchState.PLAYING) return;
        boolean ok;
        double fuse;
        switch (kind) {
            case HE:      ok = player.consumeHeGrenade();      fuse = 1.65; break;
            case SMOKE:   ok = player.consumeSmokeGrenade();   fuse = 1.35; break;
            case FLASH:   ok = player.consumeFlashGrenade();   fuse = 1.15; break;
            case MOLOTOV:
            default:      ok = player.consumeMolotovGrenade(); fuse = 2.60; break;
        }
        if (!ok) {
            soundSystem.playEmptyClick();
            return;
        }
        Vec3 start = player.getEyePosition().add(player.getLookDirection().scale(0.55));
        Vec3 vel = player.getLookDirection().scale(16.8).add(0.0, 2.8, 0.0);
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
        int mx = e.getX();
        int my = e.getY();
        updateUiMouseCoords(mx, my);

        if (!mouseCaptured || isCursorFreeScreen() || !isShowing()) {
            lastMouseX = mx;
            lastMouseY = my;
            recenteringCursor = false;
            updateHoverCursorShape();
            return;
        }

        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        long nowMs = System.currentTimeMillis();

        if (recenteringCursor) {
            int distFromCenter = Math.abs(mx - w / 2) + Math.abs(my - h / 2);
            if (distFromCenter < 90 || (nowMs - lastCursorWarpMs) > 45L) {
                recenteringCursor = false;
                lastMouseX = mx;
                lastMouseY = my;
            }
            return;
        }

        if (lastMouseX < 0 || lastMouseY < 0) {
            lastMouseX = mx;
            lastMouseY = my;
            return;
        }

        int dx = mx - lastMouseX;
        int dy = my - lastMouseY;
        lastMouseX = mx;
        lastMouseY = my;

        if ((dx != 0 || dy != 0) && Math.abs(dx) < 420 && Math.abs(dy) < 420) {
            pendingMouseDx.addAndGet(dx);
            pendingMouseDy.addAndGet(dy);
        }

        // Возвращаем курсор в центр окна только при подходе к краям (без спама Robot на 1000Hz мышах!)
        if (robot != null && (nowMs - lastCursorWarpMs) >= 24L) {
            int marginX = Math.max(60, w / 4);
            int marginY = Math.max(50, h / 4);
            if (mx < marginX || mx > (w - marginX) || my < marginY || my > (h - marginY)) {
                try {
                    Point winScreen = getLocationOnScreen();
                    lastCursorWarpMs = nowMs;
                    recenteringCursor = true;
                    robot.mouseMove(winScreen.x + w / 2, winScreen.y + h / 2);
                } catch (Throwable ignored) {
                    recenteringCursor = false;
                }
            }
        }
    }

    private void updateHoverCursorShape() {
        if (GraphicsEnvironment.isHeadless()) return;
        UiSnapshot snap = this.publishedUiSnapshot;
        int ux = this.mouseClientX;
        int uy = this.mouseClientY;
        boolean hovering = false;
        List<UiClickable> list = snap.items;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).rect.contains(ux, uy)) {
                hovering = true;
                break;
            }
        }
        if (hovering != lastHoveringButton) {
            lastHoveringButton = hovering;
            setCursor(hovering ? handCursor : defaultCursor);
        }
    }

    private void setMouseCaptured(boolean capture) {
        this.mouseCaptured = capture;
        this.lastMouseX = -1;
        this.lastMouseY = -1;
        this.recenteringCursor = false;
        this.pendingMouseDx.set(0);
        this.pendingMouseDy.set(0);
        if (GraphicsEnvironment.isHeadless()) return;

        SwingUtilities.invokeLater(() -> {
            if (this.mouseCaptured && !isCursorFreeScreen() && blankCursor != null) {
                lastHoveringButton = false;
                setCursor(blankCursor);
                if (robot != null && isShowing() && getWidth() > 0 && getHeight() > 0) {
                    try {
                        Point p = getLocationOnScreen();
                        recenteringCursor = true;
                        lastCursorWarpMs = System.currentTimeMillis();
                        robot.mouseMove(p.x + getWidth() / 2, p.y + getHeight() / 2);
                    } catch (Throwable ignored) {
                        recenteringCursor = false;
                    }
                }
            } else {
                lastHoveringButton = false;
                setCursor(defaultCursor);
            }
        });
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
        if (map.getCurrentTheme() != settings.mapTheme) {
            map.rebuildForTheme(settings.mapTheme);
        }
        this.player.resetMatchStats();
        this.killFeed.clear();
        this.graffitis.clear();
        this.bots.clear();
        this.siegeWave = 1;
        this.siegeInterWaveTimer = 0.0;
        this.bossHelicopter = null;
        applySettingsToSubsystems();
        setupRound(true);
    }

    private void setupRound(boolean resetPlayerWeapons) {
        if (map.getCurrentTheme() != settings.mapTheme) {
            map.rebuildForTheme(settings.mapTheme);
        }
        this.matchState = MatchState.PLAYING;
        boolean respawnMode = (settings.gameMode == GameSettings.GameMode.DEATHMATCH
                || settings.gameMode == GameSettings.GameMode.ARMS_RACE);
        this.roundTimeLeft = (settings.gameMode == GameSettings.GameMode.SIEGE_WAVES)
                ? 260.0
                : (respawnMode ? 210.0 : ROUND_TIME_SECONDS);
        this.roundBannerTimer = 2.4;
        this.roundOverTimer = 0.0;
        this.buyMenuOpen = false;
        this.tracers.clear();
        this.particles.clear();
        this.decals.clear();
        this.shells.clear();
        this.grenades.clear();
        this.smokeClouds.clear();
        this.fireZones.clear();
        this.pickups.clear();
        this.rockets.clear();
        this.sentryTurrets.clear();
        this.supplyDrops.clear();
        this.airstrikeBeacons.clear();
        this.damagePopups.clear();
        this.plantedC4 = null;
        this.bossHelicopter = null;
        this.siegeInterWaveTimer = 0.0;
        this.dmPlayerRespawnTimer = 0.0;
        this.hitMarkerTimer = 0.0;
        this.streakBannerTimer = 0.0;

        player.respawnForRound(map.getPlayerSpawn(), resetPlayerWeapons);
        if (settings.gameMode == GameSettings.GameMode.DEATHMATCH) {
            player.addMoney(16000);
        } else if (settings.gameMode == GameSettings.GameMode.ARMS_RACE) {
            player.applyArmsRaceTierLoadout();
        } else if (settings.gameMode == GameSettings.GameMode.SIEGE_WAVES) {
            this.siegeWave = 1;
            player.addMoney(1600);
            spawnSiegeWave(1, true);
            return;
        }

        List<Vec3> tSpawns = map.getBotSpawns();
        List<Vec3> ctSpawns = map.getAllySpawns();
        List<Vec3> waypoints = map.getPatrolWaypoints();
        int desiredEnemies = settings.botCount;
        int desiredAllies = (settings.gameMode == GameSettings.GameMode.ARMS_RACE) ? 0 : settings.allyCount;
        int totalBots = desiredEnemies + desiredAllies;

        if (bots.size() != totalBots) {
            bots.clear();
            for (int i = 0; i < desiredEnemies; i++) {
                Vec3 sp = tSpawns.get((i + currentRound - 1) % tSpawns.size());
                int wp = (i * 3 + currentRound) % Math.max(1, waypoints.size());
                bots.add(new Bot(BOT_NAMES[i % BOT_NAMES.length], Bot.Team.TERRORIST, sp, wp, 1000L + i * 37L));
            }
            for (int i = 0; i < desiredAllies; i++) {
                Vec3 sp = ctSpawns.get(i % ctSpawns.size());
                int wp = (i * 4 + 1) % Math.max(1, waypoints.size());
                bots.add(new Bot(CT_ALLY_NAMES[i % CT_ALLY_NAMES.length], Bot.Team.COUNTER_TERRORIST, sp, wp, 5000L + i * 53L));
            }
        } else {
            int tIdx = 0, ctIdx = 0;
            for (int i = 0; i < bots.size(); i++) {
                Bot b = bots.get(i);
                if (b.isAlly()) {
                    Vec3 sp = ctSpawns.get(ctIdx % ctSpawns.size());
                    b.respawn(sp, (ctIdx * 4 + currentRound) % Math.max(1, waypoints.size()));
                    ctIdx++;
                } else {
                    Vec3 sp = tSpawns.get((tIdx + currentRound - 1) % tSpawns.size());
                    b.respawn(sp, (tIdx * 3 + currentRound) % Math.max(1, waypoints.size()));
                    tIdx++;
                }
            }
        }

        if (settings.gameMode == GameSettings.GameMode.BOMB_DEFUSAL) {
            for (int i = 0; i < bots.size(); i++) {
                if (!bots.get(i).isAlly()) {
                    boolean goSiteA = (currentRound % 2 == 1);
                    Vec3 targetSite = goSiteA ? map.getBombsiteASpot() : map.getBombsiteBSpot();
                    bots.get(i).assignBombCarrier(targetSite, goSiteA ? "A" : "B");
                    break;
                }
            }
        }
    }

    /**
     * Генератор волн спецоперации «Осада: Волны + Босс-Вертолёт и Джаггернаут» (Волны 1..5).
     */
    private void spawnSiegeWave(int wave, boolean rebuildAllies) {
        List<Vec3> tSpawns = map.getBotSpawns();
        List<Vec3> ctSpawns = map.getAllySpawns();
        List<Vec3> waypoints = map.getPatrolWaypoints();

        if (rebuildAllies) {
            bots.clear();
            int allies = Math.max(1, settings.allyCount);
            for (int i = 0; i < allies; i++) {
                Vec3 sp = ctSpawns.get(i % ctSpawns.size());
                bots.add(new Bot(CT_ALLY_NAMES[i % CT_ALLY_NAMES.length], Bot.Team.COUNTER_TERRORIST,
                        sp, (i * 3) % Math.max(1, waypoints.size()), 7000L + i * 41L));
            }
        } else {
            // Удаляем павших врагов прошлой волны и лечим/возрождаем напарников CT!
            bots.removeIf(b -> !b.isAlly());
            int ctIdx = 0;
            for (int i = 0; i < bots.size(); i++) {
                Bot b = bots.get(i);
                if (!b.isAlive()) {
                    b.respawn(ctSpawns.get(ctIdx % ctSpawns.size()), ctIdx % Math.max(1, waypoints.size()));
                }
                ctIdx++;
            }
        }

        int enemyCount = Math.min(8, 3 + wave);
        int juggernauts = (wave == 3) ? 1 : (wave >= 5 ? 2 : (wave == 4 ? 1 : 0));
        int snipers = (wave >= 2) ? 2 : 0;

        for (int i = 0; i < enemyCount; i++) {
            Vec3 sp = tSpawns.get((i + wave) % tSpawns.size());
            int wp = (i * 3 + wave) % Math.max(1, waypoints.size());
            String bName;
            Bot.Archetype arch;
            if (i < juggernauts) {
                bName = "БОСС ДЖАГГЕРНАУТ #" + (i + 1);
                arch = Bot.Archetype.JUGGERNAUT;
            } else if (i < juggernauts + snipers) {
                bName = "Снайпер «Кобра» #" + (i + 1);
                arch = Bot.Archetype.SNIPER;
            } else {
                bName = BOT_NAMES[(i + wave) % BOT_NAMES.length];
                arch = Bot.Archetype.STANDARD;
            }
            Bot enemy = new Bot(bName, Bot.Team.TERRORIST, sp, wp, 2000L + wave * 101L + i * 37L);
            enemy.setArchetype(arch);
            enemy.respawn(sp, wp);
            bots.add(enemy);
        }

        if (wave == 3 || wave == 5) {
            bossHelicopter = new Vec3.AttackHelicopter(wave == 5 ? 1150 : 850);
            soundSystem.playRadioBeep();
            streakBannerText = "⚠ ВОЛНА " + wave + "/" + MAX_SIEGE_WAVES
                    + ": БОЕВОЙ ВЕРТОЛЁТ И ДЖАГГЕРНАУТ! СБЕЙТЕ ИЗ RPG-7 / M249 [B]! ⚠";
            streakBannerTimer = 4.2;
        } else {
            bossHelicopter = null;
            streakBannerText = "★ ШТУРМОВАЯ ВОЛНА " + wave + " ИЗ " + MAX_SIEGE_WAVES + " НАЧАЛАСЬ! ★";
            streakBannerTimer = 2.8;
        }
        roundTimeLeft = 240.0;
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
        Thread t = this.gameThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
        soundSystem.shutdown();
    }

    private void shutdownAndExit() {
        settings.saveSync();
        stop();
        System.exit(0);
    }

    @Override
    public void run() {
        long lastNano = System.nanoTime();
        long fpsTimer = System.currentTimeMillis();
        int frames = 0;

        while (running) {
            long frameStart = System.nanoTime();
            double dt = (frameStart - lastNano) / 1_000_000_000.0;
            lastNano = frameStart;
            if (dt > 0.05) dt = 0.05;
            if (dt < 0.0001) dt = 0.0001;

            try {
                // 1. Выполняем все клики по кнопкам, клавиши и движения мыши строго в потоке движка!
                flushPendingInputTasks();

                // Автозащита курсора: на любом экране меню/паузы/победы курсор всегда свободен и видим
                if (isCursorFreeScreen() && mouseCaptured) {
                    setMouseCaptured(false);
                }

                globalAnimTime += dt;
                if (clickFlashTimer > 0.0) {
                    clickFlashTimer = Math.max(0.0, clickFlashTimer - dt);
                }

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
            } catch (Throwable frameErr) {
                // Игровой цикл защищён от любых сбоев AWT при смене режима экрана и никогда не зависает
                frameErr.printStackTrace();
            }

            long frameDone = System.nanoTime();
            lastFrameMs = (frameDone - frameStart) / 1_000_000.0;

            if (System.currentTimeMillis() - fpsTimer >= 1000L) {
                currentFps = frames;
                frames = 0;
                fpsTimer = System.currentTimeMillis();
            }

            int cap = settings.fpsLimit;
            if (cap > 0 && pendingInputTasks.isEmpty()) {
                long targetNanos = 1_000_000_000L / cap;
                long sleepNanos = targetNanos - (System.nanoTime() - frameStart);
                if (sleepNanos > 500_000L) {
                    // Мгновенно просыпается по LockSupport.unpark при любом клике мыши или нажатии клавиши!
                    LockSupport.parkNanos(sleepNanos);
                } else {
                    Thread.yield();
                }
            } else if (pendingInputTasks.isEmpty()) {
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
        if (radioBannerTimer > 0.0) radioBannerTimer = Math.max(0.0, radioBannerTimer - dt);

        for (Iterator<Vec3.Graffiti> it = graffitis.iterator(); it.hasNext(); ) {
            if (!it.next().update(dt)) it.remove();
        }

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

        // Обновление брошенных гранат HE, Smoke, Flashbang и Molotov
        for (Iterator<Vec3.Grenade> it = grenades.iterator(); it.hasNext(); ) {
            Vec3.Grenade gr = it.next();
            if (gr.update(dt, map.getBoxes(), Map.MIN_X, Map.MAX_X, Map.MIN_Z, Map.MAX_Z)) {
                triggerGrenadeDetonation(gr);
                it.remove();
            }
        }

        // Обновление огненных зон Молотова (тушатся дымом Smoke и наносят периодический урон от огня!)
        for (Iterator<Vec3.FireZone> it = fireZones.iterator(); it.hasNext(); ) {
            Vec3.FireZone fz = it.next();
            if (!fz.update(dt)) {
                it.remove();
                continue;
            }
            boolean extinguishedBySmoke = false;
            for (int sIdx = 0; sIdx < smokeClouds.size(); sIdx++) {
                Vec3.SmokeCloud sc = smokeClouds.get(sIdx);
                if (sc.center.distXZ(fz.center) < sc.getCurrentRadius() + fz.getCurrentRadius() * 0.55) {
                    extinguishedBySmoke = true;
                    break;
                }
            }
            if (extinguishedBySmoke) {
                soundSystem.playSmokeBloom();
                spawnHitParticles(fz.center.add(0, 0.3, 0), new Vec3(0, 1, 0), 0xDCE3EB, 12);
                it.remove();
                continue;
            }

            fz.tickTimer += dt;
            if (fz.tickTimer >= 0.28) {
                fz.tickTimer = 0.0;
                double fireRad = fz.getCurrentRadius();
                for (int bIdx = 0; bIdx < bots.size(); bIdx++) {
                    Bot b = bots.get(bIdx);
                    if (!b.isAlive() || b.isAlly()) continue;
                    if (b.getPosition().distXZ(fz.center) <= fireRad && Math.abs(b.getPosition().y - fz.center.y) < 1.3) {
                        int burnDmg = 9;
                        if (settings.showDamageNumbers) {
                            damagePopups.add(new DamagePopup(b.getPosition().add(0, 1.85, 0), burnDmg, false));
                        }
                        if (b.takeDamage(burnDmg, player.getPosition())) {
                            onBotKilledByPlayer(b, "Молотов", false, 300, null);
                        }
                    }
                }
                if (player.isAlive() && player.getPosition().distXZ(fz.center) <= fireRad
                        && Math.abs(player.getPosition().y - fz.center.y) < 1.3) {
                    player.takeDamage(5, fz.center);
                }
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

        // Обновление десантируемых на парашюте контейнеров снабжения (Supply Drop)
        updateSupplyDrops(dt);

        // Обновление реактивных 3D-ракет гранатомёта RPG-7 и маяков авиаудара [U]
        updateRockets(dt);
        updateAirstrikeBeacons(dt);

        // Обновление авто-турелей спецназа CT [J] и боевого вертолёта-босса в небе
        if (matchState == MatchState.PLAYING) {
            updateSentryTurrets(dt);
            updateBossHelicopter(dt);
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

        boolean respawnMode = (settings.gameMode == GameSettings.GameMode.DEATHMATCH
                || settings.gameMode == GameSettings.GameMode.ARMS_RACE);
        int aliveEnemies = 0;
        int aliveAllies = 0;
        boolean anyBotHasC4 = false;

        for (int i = 0; i < bots.size(); i++) {
            Bot bot = bots.get(i);
            Bot.ShotEvent shot = bot.update(dt, map, player, bots, smokeClouds, settings.botDifficulty, plantedC4);
            if (bot.isAlive()) {
                if (bot.isAlly()) {
                    aliveAllies++;
                } else {
                    aliveEnemies++;
                    if (bot.isCarryingC4()) anyBotHasC4 = true;
                    if (settings.gameMode == GameSettings.GameMode.BOMB_DEFUSAL
                            && plantedC4 == null && bot.consumeC4PlantReady()) {
                        plantC4AtSpot(bot.getC4TargetSpot(), bot.getC4SiteName());
                    }
                }
            } else if (respawnMode && bot.getRespawnTimer() <= 0.0) {
                List<Vec3> spList = bot.isAlly() ? map.getAllySpawns() : map.getBotSpawns();
                Vec3 rsp = spList.get(rng.nextInt(spList.size()));
                bot.respawn(rsp, rng.nextInt(Math.max(1, map.getPatrolWaypoints().size())));
                if (bot.isAlly()) aliveAllies++;
                else aliveEnemies++;
            }

            if (shot != null && matchState == MatchState.PLAYING) {
                int trCol = bot.isAlly() ? 0x62D8FF : 0xFF9F38;
                tracers.add(new Vec3.Tracer(shot.muzzlePos, shot.endPos, trCol, 0.085));
                soundSystem.playBotShot(bot.getPosition().dist(player.getPosition()));

                if (shot.hitPlayer && player.isAlive()) {
                    boolean wasAlive = player.isAlive();
                    player.takeDamage(shot.damage, bot.getPosition());
                    soundSystem.playPlayerHurt();
                    spawnHitParticles(shot.endPos, new Vec3(0, 1, 0), 0xE03030, 5);
                    if (wasAlive && !player.isAlive()) {
                        bot.recordKill();
                        addKillFeed(bot.getName(), "AK-47", "ВЫ", false, true);
                        if (respawnMode) {
                            dmPlayerRespawnTimer = 1.7;
                        }
                    }
                } else if (shot.hitBot != null && shot.hitBot.isAlive()) {
                    spawnHitParticles(shot.endPos, new Vec3(0, 1, 0), 0xE03030, 5);
                    if (shot.hitBot.takeDamage(shot.damage, bot.getPosition())) {
                        bot.recordKill();
                        if (respawnMode) {
                            shot.hitBot.setRespawnTimer(2.4);
                        }
                        addKillFeed(bot.getName(), bot.isAlly() ? "M4A1-S" : "AK-47",
                                shot.hitBot.getName(), false, false);
                    }
                } else {
                    spawnHitParticles(shot.endPos, new Vec3(0, 1, 0), 0xE2C48E, 4);
                }
            }
        }

        // Если носитель C4 убит до закладки, передаем задачу ближайшему живому T-боту
        if (settings.gameMode == GameSettings.GameMode.BOMB_DEFUSAL && plantedC4 == null && !anyBotHasC4 && aliveEnemies > 0) {
            for (int i = 0; i < bots.size(); i++) {
                Bot cand = bots.get(i);
                if (cand.isAlive() && !cand.isAlly()) {
                    boolean goSiteA = (currentRound % 2 == 1);
                    cand.assignBombCarrier(goSiteA ? map.getBombsiteASpot() : map.getBombsiteBSpot(), goSiteA ? "A" : "B");
                    break;
                }
            }
        }

        // Обновление установленной бомбы C4 (пикающий таймер, обезвреживание клавишей E или союзником CT и взрыв)
        if (plantedC4 != null && matchState == MatchState.PLAYING) {
            updatePlantedC4(dt);
        }

        if (matchState == MatchState.PLAYING) {
            if (respawnMode) {
                roundTimeLeft -= dt;
                if (!player.isAlive()) {
                    dmPlayerRespawnTimer -= dt;
                    if (dmPlayerRespawnTimer <= 0.0) {
                        player.respawnForRound(map.getPlayerSpawn(), false);
                        if (settings.gameMode == GameSettings.GameMode.DEATHMATCH) {
                            player.addMoney(16000);
                        } else {
                            player.applyArmsRaceTierLoadout();
                        }
                    }
                }
                if (settings.gameMode == GameSettings.GameMode.DEATHMATCH && player.getKills() >= 25) {
                    playerRoundWins = settings.roundsToWin;
                    matchState = MatchState.MATCH_VICTORY;
                    setMouseCaptured(false);
                } else if (roundTimeLeft <= 0.0) {
                    roundTimeLeft = 0.0;
                    matchState = MatchState.MATCH_VICTORY;
                    setMouseCaptured(false);
                }
            } else if (settings.gameMode == GameSettings.GameMode.SIEGE_WAVES) {
                roundTimeLeft -= dt;
                boolean heliAlive = (bossHelicopter != null && bossHelicopter.isAlive());
                if (!player.isAlive() && aliveAllies == 0) {
                    finishRound(false, "Оборона прорвана на волне " + siegeWave + " (+$2200 на реванш)");
                } else if (aliveEnemies == 0 && !heliAlive) {
                    if (siegeWave >= MAX_SIEGE_WAVES) {
                        playerRoundWins = settings.roundsToWin;
                        player.addMoney(5000);
                        player.addMvpStar();
                        soundSystem.playRoundWin();
                        matchState = MatchState.MATCH_VICTORY;
                        setMouseCaptured(false);
                    } else if (siegeInterWaveTimer <= 0.0) {
                        siegeInterWaveTimer = 4.5;
                        soundSystem.playLevelUp();
                        player.addMoney(1800);
                        player.addSentryCharge();
                        // Сбрасываем с неба парашютный груз снабжения перед следующей волной!
                        supplyDrops.add(new Vec3.SupplyDrop(
                                clampVal(player.getPosition().x + 2.5, -16.0, 16.0),
                                15.0,
                                clampVal(player.getPosition().z + 3.5, -16.0, 16.0)));
                        streakBannerText = "★ ВОЛНА " + siegeWave + " ОТБИТА! +$1800 И АВИАСБРОС СНАБЖЕНИЯ! ★";
                        streakBannerTimer = 4.2;
                    } else {
                        siegeInterWaveTimer -= dt;
                        if (siegeInterWaveTimer <= 0.0) {
                            siegeWave++;
                            playerRoundWins = siegeWave - 1;
                            spawnSiegeWave(siegeWave, false);
                        }
                    }
                } else if (roundTimeLeft <= 0.0) {
                    finishRound(false, "Время удержания волны истекло");
                }
            } else {
                if (plantedC4 == null) {
                    roundTimeLeft -= dt;
                }
                if (!player.isAlive() && aliveAllies == 0) {
                    finishRound(false, "Весь отряд спецназа погиб (+$1900 бонус раунда)");
                } else if (plantedC4 == null && aliveEnemies == 0) {
                    finishRound(true, "Все террористы нейтрализованы! (+$3250 бонус победы)");
                } else if (plantedC4 == null && roundTimeLeft <= 0.0) {
                    roundTimeLeft = 0.0;
                    if (settings.gameMode == GameSettings.GameMode.BOMB_DEFUSAL) {
                        finishRound(true, "Время истекло — Пленты A и B успешно защищены! (+$3250)");
                    } else {
                        finishRound(false, "Время раунда истекло (+$1900)");
                    }
                }
            }
        } else if (matchState == MatchState.ROUND_OVER) {
            roundOverTimer -= dt;
            if (roundOverTimer <= 0.0) {
                advanceToNextRound();
            }
        }
    }

    public void plantC4AtSpot(Vec3 spot, String siteName) {
        this.plantedC4 = new Vec3.BombC4(new Vec3(spot.x, spot.y + 0.08, spot.z), siteName);
        soundSystem.playC4Keypad();
        streakBannerText = "⚠ БОМБА C4 ЗАЛОЖЕНА НА ПЛЕНТЕ " + siteName + "! ОБЕЗВРЕДЬТЕ НА [E]! ⚠";
        streakBannerTimer = 4.2;
    }

    private void updatePlantedC4(double dt) {
        plantedC4.countdown -= dt;
        plantedC4.blinkPhase += dt * (plantedC4.countdown < 10.0 ? 18.0 : 8.5);
        plantedC4.beepTimer -= dt;
        if (plantedC4.beepTimer <= 0.0 && plantedC4.countdown > 0.0) {
            double interval = Math.max(0.13, Math.min(1.05, plantedC4.countdown / 34.0));
            plantedC4.beepTimer = interval;
            soundSystem.playC4Beep(plantedC4.countdown < 10.0, player.getPosition().dist(plantedC4.pos));
        }

        // Обезвреживание при зажатой клавише E игроком ИЛИ союзным ботом CT рядом с бомбой C4
        boolean nearC4 = player.isAlive() && player.getPosition().dist(plantedC4.pos) < 2.95;
        boolean playerDefusing = nearC4 && keys[KeyEvent.VK_E] && !buyMenuOpen;
        Bot ctDefuser = null;
        if (!playerDefusing) {
            for (int i = 0; i < bots.size(); i++) {
                Bot b = bots.get(i);
                if (b.isAlive() && b.isAlly() && b.getState() != Bot.State.ENGAGE
                        && b.getPosition().dist(plantedC4.pos) < 2.25) {
                    ctDefuser = b;
                    break;
                }
            }
        }

        if (playerDefusing || ctDefuser != null) {
            if (!plantedC4.beingDefused) {
                soundSystem.playC4Keypad();
            }
            plantedC4.beingDefused = true;
            plantedC4.defuseRequired = playerDefusing ? (player.hasDefuseKit() ? 5.0 : 10.0) : 6.5;
            plantedC4.defuseProgress += dt;
            if (plantedC4.defuseProgress >= plantedC4.defuseRequired) {
                plantedC4.defused = true;
                soundSystem.playC4Keypad();
                player.addMoney(300);
                String who = (ctDefuser != null) ? ctDefuser.getName() : "ВЫ";
                plantedC4 = null;
                finishRound(true, "БОМБА C4 ОБЕЗВРЕЖЕНА (" + who + ")! СПЕЦНАЗ ПОБЕДИЛ (+$3550)");
                return;
            }
        } else {
            plantedC4.beingDefused = false;
            plantedC4.defuseProgress = Math.max(0.0, plantedC4.defuseProgress - dt * 2.5);
        }

        if (plantedC4.countdown <= 0.0) {
            Vec3 blastPos = plantedC4.pos.copy();
            String site = plantedC4.siteName;
            plantedC4 = null;
            soundSystem.playExplosion(0.0);
            player.addCameraShake(1.0);
            for (int i = 0; i < 60; i++) {
                double ang = rng.nextDouble() * Math.PI * 2.0;
                double spd = 5.0 + rng.nextDouble() * 14.0;
                int col = (i % 3 == 0) ? 0xFFF3A1 : ((i % 2 == 0) ? 0xFF6B1A : 0x3E3A36);
                particles.add(new Vec3.Particle(
                        blastPos.x, blastPos.y + 0.25, blastPos.z,
                        Math.cos(ang) * spd, 4.0 + rng.nextDouble() * 11.0, Math.sin(ang) * spd,
                        col, 0.22, 0.95));
            }
            if (player.isAlive() && player.getPosition().dist(blastPos) < 16.0) {
                player.takeDamage(140, blastPos);
            }
            finishRound(false, "ВЗРЫВ БОМБЫ C4 НА ПЛЕНТЕ " + site + "! ТЕРРОРИСТЫ ПОБЕДИЛИ");
        }
    }

    private void triggerGrenadeDetonation(Vec3.Grenade gr) {
        if (gr.kind == Vec3.Grenade.Kind.SMOKE) {
            smokeClouds.add(new Vec3.SmokeCloud(gr.pos, 4.3, 14.5));
            soundSystem.playSmokeBloom();
            return;
        }

        if (gr.kind == Vec3.Grenade.Kind.MOLOTOV) {
            fireZones.add(new Vec3.FireZone(gr.pos, 3.25, 7.6));
            soundSystem.playMolotovShatter();
            if (settings.decalsAndShells && gr.pos.y < 0.6) {
                addBulletDecal(new Vec3(gr.pos.x, 0.005, gr.pos.z), new Vec3(0, 1, 0), 1.45, 0x2B1E16);
            }
            for (int i = 0; i < 18; i++) {
                double ang = rng.nextDouble() * Math.PI * 2.0;
                double spd = 2.0 + rng.nextDouble() * 5.0;
                particles.add(new Vec3.Particle(
                        gr.pos.x, gr.pos.y + 0.15, gr.pos.z,
                        Math.cos(ang) * spd, 2.2 + rng.nextDouble() * 4.2, Math.sin(ang) * spd,
                        0xFF8C1A, 0.11, 0.42));
            }
            return;
        }

        if (gr.kind == Vec3.Grenade.Kind.FLASH) {
            double distToPlayer = gr.pos.dist(player.getPosition());
            soundSystem.playFlashbang(distToPlayer);
            for (int i = 0; i < 16; i++) {
                double ang = rng.nextDouble() * Math.PI * 2.0;
                particles.add(new Vec3.Particle(
                        gr.pos.x, gr.pos.y + 0.12, gr.pos.z,
                        Math.cos(ang) * 5.5, 2.5 + rng.nextDouble() * 4.0, Math.sin(ang) * 5.5,
                        0xFFFFFF, 0.12, 0.24));
            }
            // Ослепление вражеских ботов в прямой видимости (до 18 метров)
            for (int i = 0; i < bots.size(); i++) {
                Bot b = bots.get(i);
                if (!b.isAlive() || b.isAlly()) continue;
                double d = b.getEyePosition().dist(gr.pos);
                if (d < 18.5 && map.hasLineOfSight(gr.pos.add(0, 0.2, 0), b.getEyePosition(), smokeClouds)) {
                    double dur = Math.max(1.8, (1.0 - d / 20.0) * 4.8);
                    b.applyFlashBlind(dur);
                }
            }
            // Учет поворота головы игрока (если отвернуться от флешки — почти не слепит, как в CS2!)
            if (player.isAlive() && distToPlayer < 19.0
                    && map.hasLineOfSight(gr.pos.add(0, 0.2, 0), player.getEyePosition(), smokeClouds)) {
                Vec3 toFlash = gr.pos.sub(player.getEyePosition()).normalize();
                double facing = player.getLookDirection().dot(toFlash);
                if (facing > 0.15) {
                    player.applyFlashBlind(facing * (1.0 - distToPlayer / 21.0) * 2.6);
                } else {
                    player.applyFlashBlind(0.28);
                }
            }
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
            if (!b.isAlive() || b.isAlly()) continue;
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
                        onBotKilledByPlayer(b, "HE Граната", false, 300, null);
                    }
                }
            }
        }

        if (player.isAlive() && distToPlayer < 6.5) {
            int selfDmg = (int) ((1.0 - distToPlayer / 6.5) * 58.0);
            if (selfDmg > 3) player.takeDamage(selfDmg, gr.pos);
        }
    }

    private void onBotKilledByPlayer(Bot victim, String weaponLabel, boolean headshot, int cashReward, Weapon.Type killerType) {
        if (settings.gameMode == GameSettings.GameMode.DEATHMATCH
                || settings.gameMode == GameSettings.GameMode.ARMS_RACE) {
            victim.setRespawnTimer(2.2);
        }
        int streak = player.recordKill(headshot, cashReward);
        addKillFeed("ВЫ", weaponLabel, victim.getName(), headshot, true);
        if (player.getKills() > 0 && player.getKills() % 4 == 0) {
            player.addAirstrikeCharge(1);
        }
        if (settings.addCareerXp(headshot ? 55 : 35, true, headshot, false)) {
            soundSystem.playLevelUp();
            streakBannerText = "★ ПОВЫШЕНИЕ ЗВАНИЯ CS2: " + settings.getRankTitle() + "! ★";
            streakBannerTimer = 3.6;
        }

        if (rng.nextDouble() < 0.48) {
            Vec3.Pickup.Type pt = rng.nextBoolean() ? Vec3.Pickup.Type.MEDKIT : Vec3.Pickup.Type.AMMO_PACK;
            pickups.add(new Vec3.Pickup(pt, new Vec3(victim.getPosition().x, victim.getPosition().y, victim.getPosition().z)));
        }

        // Прогрессия уровней оружия в режиме «Гонка вооружений» (Arms Race)!
        if (settings.gameMode == GameSettings.GameMode.ARMS_RACE) {
            int tier = player.getArmsRaceTier();
            int maxTier = Weapon.ARMS_RACE_LADDER.length - 1;
            if (tier >= maxTier && killerType == Weapon.Type.KNIFE) {
                soundSystem.playRoundWin();
                player.addMvpStar();
                playerRoundWins = settings.roundsToWin;
                streakBannerText = "★ ЗОЛОТОЙ НОЖ! ПОБЕДА В ГОНКЕ ВООРУЖЕНИЙ! ★";
                streakBannerTimer = 4.0;
                matchState = MatchState.MATCH_VICTORY;
                setMouseCaptured(false);
                return;
            }
            boolean instantUpgrade = (killerType == Weapon.Type.KNIFE || killerType == Weapon.Type.ZEUS);
            int killsInTier = player.getArmsRaceTierKills() + (instantUpgrade ? 2 : 1);
            if (killsInTier >= 2 && tier < maxTier) {
                int nextTier = tier + 1;
                player.setArmsRaceTier(nextTier);
                player.setArmsRaceTierKills(0);
                player.applyArmsRaceTierLoadout();
                soundSystem.playLevelUp();
                String nextName = (nextTier == maxTier)
                        ? "ЗОЛОТОЙ НОЖ (ФИНАЛ!)"
                        : new Weapon(Weapon.ARMS_RACE_LADDER[nextTier]).getDisplayName();
                streakBannerText = "▲ УРОВЕНЬ " + (nextTier + 1) + "/" + Weapon.ARMS_RACE_LADDER.length + ": " + nextName + " ▲";
                streakBannerTimer = 2.6;
                return;
            } else {
                player.setArmsRaceTierKills(Math.min(1, killsInTier));
            }
        }

        int enemyTeamSize = settings.botCount;
        if (streak >= enemyTeamSize && enemyTeamSize >= 3) {
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
            if (player.getRoundKills() > 0) {
                player.addMvpStar();
            }
            soundSystem.playRoundWin();
        } else {
            botRoundWins++;
            player.addMoney(1900);
            soundSystem.playRoundLose();
        }

        int targetWins = settings.roundsToWin;
        if (playerRoundWins >= targetWins) {
            settings.addCareerXp(220, false, false, true);
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

        soundSystem.playWeaponShot(weapon.getType());

        if (weapon.isMelee()) {
            weapon.onFire(altHeavyMelee);
            performKnifeAttack(weapon, altHeavyMelee);
        } else {
            performGunshot(weapon);
            weapon.onFire(altHeavyMelee);
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
            if (!bot.isAlive() || bot.isAlly()) continue;

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
                boolean goldenKnife = (settings.gameMode == GameSettings.GameMode.ARMS_RACE
                        && player.getArmsRaceTier() == Weapon.ARMS_RACE_LADDER.length - 1);
                onBotKilledByPlayer(bestBot, goldenKnife ? "Золотой Нож" : knife.getDisplayName(),
                        headshot, knife.getKillReward(), Weapon.Type.KNIFE);
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

        // Реактивный гранатомёт RPG-7 выпускает физическую 3D-ракету со шлейфом дыма!
        if (gun.getType() == Weapon.Type.RPG7) {
            Vec3 rocketDir = look.add(right.scale(rng.nextGaussian() * spread))
                    .add(up.scale(rng.nextGaussian() * spread)).normalize();
            rockets.add(new Vec3.Rocket(muzzle.add(rocketDir.scale(0.35)), rocketDir, 38.0));
            spawnHitParticles(muzzle, rocketDir.scale(-1.0), 0xFFB848, 10);
            player.recordShot(true);
            return;
        }

        // Выброс латунной 3D-гильзы (кроме электрошокера Zeus x27)!
        if (settings.decalsAndShells && gun.getType() != Weapon.Type.ZEUS) {
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
            boolean penetratedWallbang = false;
            boolean hitHelicopter = false;

            // Проверка попадания пули по Боевому Вертолёту-Боссу в небе!
            if (bossHelicopter != null && bossHelicopter.isAlive()) {
                Vec3 hp = bossHelicopter.pos;
                Vec3.Box heliBox = new Vec3.Box(hp.x - 2.3, hp.y - 1.0, hp.z - 2.3,
                        hp.x + 2.3, hp.y + 1.35, hp.z + 2.3, 0, 0, 0, true);
                double tHeli = heliBox.intersectRay(eye, shotDir, maxDist, null);
                if (tHeli >= 0.0 && tHeli < maxDist) {
                    maxDist = tHeli;
                    endPoint = eye.add(shotDir.scale(tHeli));
                    hitHelicopter = true;
                }
            }

            for (int i = 0; i < bots.size(); i++) {
                Bot bot = bots.get(i);
                if (bot.isAlly()) continue;
                Bot.HitResult hr = bot.intersectShotRay(eye, shotDir, maxDist);
                if (hr != null && hr.distance < maxDist) {
                    maxDist = hr.distance;
                    bestBotHit = hr;
                    hitBot = bot;
                    hitHelicopter = false;
                    endPoint = hr.point;
                }
            }

            // Механика прострела деревянных ящиков и двойных дверей CS2 (Wallbang)!
            if (hitBot == null && wallHit != null && gun.canWallbang() && map.isBoxPenetrable(wallHit.box)) {
                Vec3.RayHit secondWall = map.raycastIgnoringBox(eye, shotDir, maxRange, wallHit.box);
                double wbMaxDist = (secondWall != null) ? secondWall.distance : maxRange;
                for (int i = 0; i < bots.size(); i++) {
                    Bot bot = bots.get(i);
                    if (bot.isAlly()) continue;
                    Bot.HitResult hr = bot.intersectShotRay(eye, shotDir, wbMaxDist);
                    if (hr != null && hr.distance > wallHit.distance && hr.distance < wbMaxDist) {
                        wbMaxDist = hr.distance;
                        bestBotHit = hr;
                        hitBot = bot;
                        endPoint = hr.point;
                        penetratedWallbang = true;
                    }
                }
                if (penetratedWallbang && settings.decalsAndShells) {
                    addBulletDecal(wallHit.point, wallHit.normal, 0.11, 0x2C2620);
                    spawnHitParticles(wallHit.point, wallHit.normal, 0xDEC08E, 5);
                }
            }

            // Выстрел сквозь дым CS2 на мгновение рассеивает дымовую завесу!
            for (int i = 0; i < smokeClouds.size(); i++) {
                Vec3.SmokeCloud sc = smokeClouds.get(i);
                if (sc.blocksSegment(eye, endPoint)) {
                    sc.bulletHoleTimer = (gun.getType() == Weapon.Type.AWP) ? 0.95 : 0.32;
                }
            }

            if (gun.getType() == Weapon.Type.ZEUS) {
                // Яркая ломаная электрическая дуга молнии Zeus x27!
                Vec3 mid1 = muzzle.add(endPoint.sub(muzzle).scale(0.35)).add((rng.nextDouble() - 0.5) * 0.22, (rng.nextDouble() - 0.5) * 0.22, (rng.nextDouble() - 0.5) * 0.22);
                Vec3 mid2 = muzzle.add(endPoint.sub(muzzle).scale(0.70)).add((rng.nextDouble() - 0.5) * 0.22, (rng.nextDouble() - 0.5) * 0.22, (rng.nextDouble() - 0.5) * 0.22);
                tracers.add(new Vec3.Tracer(muzzle, mid1, 0x52F6FF, 0.18));
                tracers.add(new Vec3.Tracer(mid1, mid2, 0xDCF9FF, 0.18));
                tracers.add(new Vec3.Tracer(mid2, endPoint, 0x52F6FF, 0.18));
                spawnHitParticles(endPoint, shotDir.scale(-1.0), 0x48F5FF, 12);
            } else {
                int tracerCol = gun.isSilenced() ? 0xB8E4FF : 0xFFE37E;
                tracers.add(new Vec3.Tracer(muzzle, endPoint, tracerCol, gun.getType() == Weapon.Type.AWP ? 0.14 : 0.075));
            }

            if (hitBot != null && bestBotHit != null) {
                anyPelletHit = true;
                if (bestBotHit.headshot) anyHeadshot = true;

                int baseDmg = bestBotHit.headshot ? gun.getHeadDamage() : gun.getBodyDamage();
                double falloff = (gun.getType() == Weapon.Type.AWP || gun.getType() == Weapon.Type.ZEUS)
                        ? 1.0
                        : Math.max(0.65, 1.0 - bestBotHit.distance / 145.0);
                double wbFactor = penetratedWallbang ? gun.getWallbangDamageFactor() : 1.0;
                int finalDamage = Math.max(1, (int) Math.round(baseDmg * falloff * wbFactor));

                if (settings.showDamageNumbers) {
                    damagePopups.add(new DamagePopup(bestBotHit.point.add(0, 0.25, 0), finalDamage, bestBotHit.headshot));
                }

                boolean killed = hitBot.takeDamage(finalDamage, player.getPosition());
                int particleColor = (gun.getType() == Weapon.Type.ZEUS) ? 0x48F5FF
                        : (bestBotHit.headshot ? 0xFFAA22 : 0xDC2626);
                spawnHitParticles(bestBotHit.point, shotDir.scale(-1.0), particleColor, bestBotHit.headshot ? 10 : 6);

                if (killed) {
                    String wTag = gun.getDisplayName() + (penetratedWallbang ? " • ПРОСТРЕЛ" : "");
                    int reward = hitBot.getArchetype() == Bot.Archetype.JUGGERNAUT ? 1000 : gun.getKillReward();
                    onBotKilledByPlayer(hitBot, wTag, bestBotHit.headshot, reward, gun.getType());
                }
            } else if (hitHelicopter && bossHelicopter != null && bossHelicopter.isAlive()) {
                anyPelletHit = true;
                int heliDmg = Math.max(8, gun.getBodyDamage());
                if (gun.getType() == Weapon.Type.AWP) heliDmg = 210;
                else if (gun.getType() == Weapon.Type.M249) heliDmg = 46;
                spawnHitParticles(endPoint, shotDir.scale(-1.0), 0xFFB838, 7);
                damageBossHelicopter(heliDmg, gun.getDisplayName(), endPoint);
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
        double mult = (settings.particleDensity != null) ? settings.particleDensity.multiplier : 1.0;
        int scaledCount = Math.max(1, (int) Math.round(count * mult));
        int maxCap = (int) (240 * mult);
        if (particles.size() > maxCap) return;
        for (int i = 0; i < scaledCount; i++) {
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

    /**
     * Физика полёта и взрыва 3D-ракет RPG-7 с дымовым шлейфом и сокрушительным осколочным радиусом!
     */
    private void updateRockets(double dt) {
        for (Iterator<Vec3.Rocket> it = rockets.iterator(); it.hasNext(); ) {
            Vec3.Rocket r = it.next();
            r.life -= dt;
            if (r.life <= 0.0) {
                detonateRocket(r.pos);
                it.remove();
                continue;
            }

            double stepDist = r.vel.length() * dt;
            Vec3 dir = r.vel.normalize();

            // Дымово-огненный шлейф позади ракеты
            r.smokeEmitTimer -= dt;
            if (r.smokeEmitTimer <= 0.0) {
                r.smokeEmitTimer = 0.018;
                particles.add(new Vec3.Particle(
                        r.pos.x, r.pos.y, r.pos.z,
                        (rng.nextDouble() - 0.5) * 1.1, 0.6 + rng.nextDouble() * 0.8, (rng.nextDouble() - 0.5) * 1.1,
                        rng.nextBoolean() ? 0xD8DCE2 : 0xFFA236, 0.14, 0.36, 0.0));
            }

            Vec3.RayHit wallHit = map.raycast(r.pos, dir, stepDist + 0.25);
            Vec3 impactPos = null;
            if (wallHit != null && wallHit.distance <= stepDist + 0.15) {
                impactPos = wallHit.point;
            }

            // Прямое попадание в Боевой Вертолёт-Босс!
            if (impactPos == null && bossHelicopter != null && bossHelicopter.isAlive()) {
                Vec3 hp = bossHelicopter.pos;
                Vec3.Box heliBox = new Vec3.Box(hp.x - 2.6, hp.y - 1.2, hp.z - 2.6,
                        hp.x + 2.6, hp.y + 1.5, hp.z + 2.6, 0, 0, 0, true);
                double th = heliBox.intersectRay(r.pos, dir, stepDist + 0.4, null);
                if (th >= 0.0) {
                    impactPos = r.pos.add(dir.scale(th));
                }
            }

            // Прямое попадание во вражеского бота
            if (impactPos == null) {
                for (int i = 0; i < bots.size(); i++) {
                    Bot b = bots.get(i);
                    if (!b.isAlive() || b.isAlly()) continue;
                    Bot.HitResult hr = b.intersectShotRay(r.pos, dir, stepDist + 0.35);
                    if (hr != null) {
                        impactPos = hr.point;
                        break;
                    }
                }
            }

            if (impactPos != null) {
                detonateRocket(impactPos);
                it.remove();
            } else {
                r.pos.x += r.vel.x * dt;
                r.pos.y += r.vel.y * dt;
                r.pos.z += r.vel.z * dt;
            }
        }
    }

    private void detonateRocket(Vec3 pos) {
        double distToPlayer = pos.dist(player.getPosition());
        soundSystem.playExplosion(distToPlayer * 0.7);
        if (distToPlayer < 22.0) {
            player.addCameraShake(Math.max(0.25, (1.0 - distToPlayer / 22.0) * 1.0));
        }

        for (int i = 0; i < 48; i++) {
            double ang = rng.nextDouble() * Math.PI * 2.0;
            double spd = 4.0 + rng.nextDouble() * 12.5;
            int col = (i % 3 == 0) ? 0xFFF4A8 : ((i % 2 == 0) ? 0xFF7020 : 0x484440);
            particles.add(new Vec3.Particle(
                    pos.x, pos.y + 0.15, pos.z,
                    Math.cos(ang) * spd, 3.5 + rng.nextDouble() * 9.0, Math.sin(ang) * spd,
                    col, 0.18 + rng.nextDouble() * 0.14, 0.55 + rng.nextDouble() * 0.45));
        }
        if (settings.decalsAndShells && pos.y < 0.7) {
            addBulletDecal(new Vec3(pos.x, 0.005, pos.z), new Vec3(0, 1, 0), 1.85, 0x221E1A);
        }

        double blastRadius = 9.6;
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (!b.isAlive() || b.isAlly()) continue;
            double d = b.getPosition().add(0, 0.9, 0).dist(pos);
            if (d <= blastRadius) {
                int dmg = (int) Math.round((1.0 - d / blastRadius) * 265.0) + 25;
                if (settings.showDamageNumbers) {
                    damagePopups.add(new DamagePopup(b.getPosition().add(0, 2.0, 0), dmg, false));
                }
                boolean killed = b.takeDamage(dmg, player.getPosition());
                hitMarkerTimer = 0.24;
                hitMarkerHeadshot = false;
                if (killed) {
                    int reward = b.getArchetype() == Bot.Archetype.JUGGERNAUT ? 1000 : 300;
                    onBotKilledByPlayer(b, "RPG-7", false, reward, Weapon.Type.RPG7);
                }
            }
        }

        if (bossHelicopter != null && bossHelicopter.isAlive()) {
            double dh = bossHelicopter.pos.dist(pos);
            if (dh <= 8.5) {
                int hDmg = (int) Math.round(420.0 * Math.max(0.35, 1.0 - dh / 9.5));
                damageBossHelicopter(hDmg, "RPG-7", bossHelicopter.pos);
            }
        }

        if (player.isAlive() && distToPlayer < 5.2) {
            int selfDmg = (int) ((1.0 - distToPlayer / 5.2) * 48.0);
            if (selfDmg > 3) player.takeDamage(selfDmg, pos);
        }
    }

    /**
     * ИИ развёрнутых полевых авто-турелей спецназа CT [J]: автоматический поиск цели и стрельба!
     */
    private void updateSentryTurrets(double dt) {
        for (Iterator<Vec3.SentryTurret> it = sentryTurrets.iterator(); it.hasNext(); ) {
            Vec3.SentryTurret t = it.next();
            t.life -= dt;
            if (t.muzzleFlash > 0.0) t.muzzleFlash = Math.max(0.0, t.muzzleFlash - dt);
            if (t.fireCooldown > 0.0) t.fireCooldown = Math.max(0.0, t.fireCooldown - dt);
            if (!t.isAlive()) {
                it.remove();
                continue;
            }

            Vec3 sensorPos = new Vec3(t.pos.x, t.pos.y + 0.88, t.pos.z);
            Bot bestBot = null;
            double bestDist = 28.0;

            for (int i = 0; i < bots.size(); i++) {
                Bot b = bots.get(i);
                if (!b.isAlive() || b.isAlly()) continue;
                double d = sensorPos.dist(b.getEyePosition());
                if (d < bestDist && map.hasLineOfSight(sensorPos, b.getEyePosition(), smokeClouds)) {
                    bestDist = d;
                    bestBot = b;
                }
            }

            boolean lockHeli = (bestBot == null && bossHelicopter != null && bossHelicopter.isAlive()
                    && sensorPos.dist(bossHelicopter.pos) < 36.0
                    && map.hasLineOfSight(sensorPos, bossHelicopter.pos, smokeClouds));

            if (bestBot != null || lockHeli) {
                t.hasTarget = true;
                Vec3 aim = (bestBot != null)
                        ? new Vec3(bestBot.getPosition().x, bestBot.getPosition().y + 1.25, bestBot.getPosition().z)
                        : bossHelicopter.pos;
                Vec3 diff = aim.sub(sensorPos);
                double targetYaw = Math.atan2(diff.x, diff.z);
                double diffYaw = targetYaw - t.yaw;
                while (diffYaw > Math.PI) diffYaw -= Math.PI * 2.0;
                while (diffYaw < -Math.PI) diffYaw += Math.PI * 2.0;
                double step = Math.min(Math.abs(diffYaw), dt * 8.5);
                t.yaw += Math.copySign(step, diffYaw);
                t.pitch = -Math.asin(clampVal(diff.y / Math.max(0.5, diff.length()), -0.8, 0.8));

                if (Math.abs(diffYaw) < 0.26 && t.fireCooldown <= 0.0) {
                    t.fireCooldown = 0.16;
                    t.muzzleFlash = 0.06;
                    Vec3 muz = new Vec3(t.pos.x + Math.sin(t.yaw) * 0.65, t.pos.y + 0.85, t.pos.z + Math.cos(t.yaw) * 0.65);
                    tracers.add(new Vec3.Tracer(muz, aim, 0x52EAFF, 0.07));
                    soundSystem.playTurretShot(t.pos.dist(player.getPosition()));

                    if (bestBot != null) {
                        int dmg = 16;
                        if (settings.showDamageNumbers) {
                            damagePopups.add(new DamagePopup(bestBot.getPosition().add(0, 1.9, 0), dmg, false));
                        }
                        if (bestBot.takeDamage(dmg, t.pos)) {
                            int reward = bestBot.getArchetype() == Bot.Archetype.JUGGERNAUT ? 1000 : 250;
                            onBotKilledByPlayer(bestBot, "Авто-Турель CT", false, reward, null);
                        }
                    } else if (bossHelicopter != null && bossHelicopter.isAlive()) {
                        damageBossHelicopter(14, "Авто-Турель CT", aim);
                    }
                }
            } else {
                t.hasTarget = false;
                t.scanPhase += dt * 1.4;
                t.yaw += Math.sin(t.scanPhase) * dt * 0.85;
                t.pitch *= 0.92;
            }
        }
    }

    /**
     * Полёт, вращение лопастей и пулемётные атаки Ударного Вертолёта-Босса в 3D-небе!
     */
    private void updateBossHelicopter(double dt) {
        if (bossHelicopter == null || !bossHelicopter.isAlive()) return;

        bossHelicopter.orbitAngle += dt * 0.36;
        bossHelicopter.rotorPhase += dt * 26.0;
        if (bossHelicopter.hitFlashTimer > 0.0) {
            bossHelicopter.hitFlashTimer = Math.max(0.0, bossHelicopter.hitFlashTimer - dt);
        }

        double rx = Math.cos(bossHelicopter.orbitAngle) * 14.2;
        double rz = Math.sin(bossHelicopter.orbitAngle * 0.85) * 13.5;
        double ry = 11.4 + Math.sin(bossHelicopter.orbitAngle * 2.1) * 0.85;
        double vx = -Math.sin(bossHelicopter.orbitAngle);
        double vz = Math.cos(bossHelicopter.orbitAngle * 0.85) * 0.85;
        bossHelicopter.pos.set(rx, ry, rz);

        if (player.isAlive()) {
            bossHelicopter.yaw = Math.atan2(player.getPosition().x - rx, player.getPosition().z - rz);
        } else {
            bossHelicopter.yaw = Math.atan2(vx, vz);
        }

        // Дым из двигателя при критических повреждениях (< 40% HP)
        if (bossHelicopter.health < bossHelicopter.maxHealth * 0.40 && rng.nextDouble() < 0.35) {
            particles.add(new Vec3.Particle(
                    rx, ry + 0.5, rz,
                    (rng.nextDouble() - 0.5) * 1.8, 1.8 + rng.nextDouble() * 1.5, (rng.nextDouble() - 0.5) * 1.8,
                    rng.nextBoolean() ? 0x363A40 : 0xFF7B24, 0.22, 0.55, 0.0));
        }

        bossHelicopter.fireCooldown -= dt;
        if (bossHelicopter.fireCooldown <= 0.0 && bossHelicopter.burstShotsLeft <= 0) {
            bossHelicopter.burstShotsLeft = 4;
            bossHelicopter.burstShotTimer = 0.0;
            bossHelicopter.fireCooldown = 2.4;
        }

        if (bossHelicopter.burstShotsLeft > 0) {
            bossHelicopter.burstShotTimer -= dt;
            if (bossHelicopter.burstShotTimer <= 0.0) {
                bossHelicopter.burstShotTimer = 0.13;
                bossHelicopter.burstShotsLeft--;

                Vec3 gunMuzzle = new Vec3(
                        rx + Math.sin(bossHelicopter.yaw) * 2.1,
                        ry - 0.58,
                        rz + Math.cos(bossHelicopter.yaw) * 2.1);

                Vec3 target = null;
                boolean targetIsPlayer = false;
                Bot targetAlly = null;

                if (player.isAlive() && map.hasLineOfSight(gunMuzzle, player.getCenterTargetPosition(), smokeClouds)) {
                    target = player.getCenterTargetPosition();
                    targetIsPlayer = true;
                } else {
                    for (int i = 0; i < bots.size(); i++) {
                        Bot b = bots.get(i);
                        if (b.isAlive() && b.isAlly() && map.hasLineOfSight(gunMuzzle, b.getEyePosition(), smokeClouds)) {
                            target = b.getEyePosition();
                            targetAlly = b;
                            break;
                        }
                    }
                }

                if (target != null) {
                    Vec3 dir = target.sub(gunMuzzle).normalize();
                    Vec3 spreadDir = new Vec3(
                            dir.x + rng.nextGaussian() * 0.045,
                            dir.y + rng.nextGaussian() * 0.045,
                            dir.z + rng.nextGaussian() * 0.045).normalize();
                    Vec3.RayHit wh = map.raycast(gunMuzzle, spreadDir, 55.0);
                    Vec3 endPt = (wh != null) ? wh.point : gunMuzzle.add(spreadDir.scale(40.0));
                    tracers.add(new Vec3.Tracer(gunMuzzle, endPt, 0xFF5630, 0.10));
                    soundSystem.playBotShot(gunMuzzle.dist(player.getPosition()) * 0.65);

                    if (targetIsPlayer && player.isAlive() && endPt.dist(player.getCenterTargetPosition()) < 1.35) {
                        boolean wasAlive = player.isAlive();
                        player.takeDamage(10, bossHelicopter.pos);
                        soundSystem.playPlayerHurt();
                        if (wasAlive && !player.isAlive()) {
                            addKillFeed("ВЕРТОЛЁТ БОССА", "Авиапушка 30мм", "ВЫ", false, true);
                        }
                    } else if (targetAlly != null && targetAlly.isAlive() && endPt.dist(targetAlly.getEyePosition()) < 1.5) {
                        if (targetAlly.takeDamage(12, bossHelicopter.pos)) {
                            addKillFeed("ВЕРТОЛЁТ БОССА", "Авиапушка 30мм", targetAlly.getName(), false, false);
                        }
                    } else if (wh != null) {
                        spawnHitParticles(wh.point, wh.normal, 0xFFD27D, 4);
                    }
                }
            }
        }
    }

    private void damageBossHelicopter(int dmg, String weaponName, Vec3 hitPos) {
        if (bossHelicopter == null || !bossHelicopter.isAlive()) return;
        if (settings.showDamageNumbers) {
            damagePopups.add(new DamagePopup(hitPos.copy(), dmg, dmg >= 150));
        }
        hitMarkerTimer = 0.20;
        hitMarkerHeadshot = (dmg >= 150);
        soundSystem.playHitMarker(dmg >= 150);

        if (bossHelicopter.takeDamage(dmg)) {
            // ГРАНДИОЗНЫЙ ВЗРЫВ СБИТОГО БОЕВОГО ВЕРТОЛЁТА В НЕБЕ!
            Vec3 crashPos = bossHelicopter.pos.copy();
            soundSystem.playExplosion(0.0);
            player.addCameraShake(1.0);
            player.recordKill(true, 2000);
            settings.addCareerXp(260, true, true, false);
            addKillFeed("ВЫ", weaponName, "БОЕВОЙ ВЕРТОЛЁТ", true, true);
            streakBannerText = "★ УДАРНЫЙ ВЕРТОЛЁТ БОССА СБИТ! +$2000 И АВИАСБРОС СНАБЖЕНИЯ! ★";
            streakBannerTimer = 4.5;

            for (int i = 0; i < 68; i++) {
                double ang = rng.nextDouble() * Math.PI * 2.0;
                double spd = 4.0 + rng.nextDouble() * 14.0;
                int col = (i % 3 == 0) ? 0xFFF6B0 : ((i % 2 == 0) ? 0xFF6A1A : 0x2E3238);
                particles.add(new Vec3.Particle(
                        crashPos.x, crashPos.y, crashPos.z,
                        Math.cos(ang) * spd, -2.0 + rng.nextDouble() * 11.0, Math.sin(ang) * spd,
                        col, 0.26, 1.15));
            }
            supplyDrops.add(new Vec3.SupplyDrop(
                    clampVal(crashPos.x, -16.0, 16.0),
                    crashPos.y,
                    clampVal(crashPos.z, -16.0, 16.0)));
        }
    }

    private void updateSupplyDrops(double dt) {
        for (Iterator<Vec3.SupplyDrop> it = supplyDrops.iterator(); it.hasNext(); ) {
            Vec3.SupplyDrop sd = it.next();
            if (!sd.update(dt)) {
                it.remove();
                continue;
            }
            if (sd.landed && player.isAlive() && player.getPosition().dist(sd.pos) < 1.85) {
                player.healAndRestoreArmor(100, 100);
                for (Weapon w : player.getWeapons()) w.refillReserveAmmo();
                player.addHeGrenade();
                player.addSentryCharge();
                player.addMoney(1200);
                soundSystem.playLevelUp();
                streakBannerText = "★ АВИАСБРОС ПОДОБРАН: 100 HP/AP + БОЕЗАПАС + АВТО-ТУРЕЛЬ [J] + $1200! ★";
                streakBannerTimer = 3.0;
                it.remove();
            }
        }
    }

    private void renderWindowFrame() {
        int outW = getWidth();
        int outH = getHeight();
        if (outW < 64 || outH < 64) {
            outW = DEFAULT_WINDOW_WIDTH;
            outH = DEFAULT_WINDOW_HEIGHT;
        }
        renderOffscreenSwap(outW, outH);
        repaint();
        Toolkit.getDefaultToolkit().sync();
    }

    private void renderOffscreenSwap(int outW, int outH) {
        BufferedImage target;
        if (nextBufferIsA) {
            if (frameBufferA == null || frameBufferA.getWidth() != outW || frameBufferA.getHeight() != outH) {
                frameBufferA = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
            }
            target = frameBufferA;
        } else {
            if (frameBufferB == null || frameBufferB.getWidth() != outW || frameBufferB.getHeight() != outH) {
                frameBufferB = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
            }
            target = frameBufferB;
        }

        long t0 = System.nanoTime();
        Graphics2D g = target.createGraphics();
        try {
            renderCompleteFrame(g, outW, outH);
        } finally {
            g.dispose();
        }
        long elapsedNs = System.nanoTime() - t0;
        double ms = elapsedNs / 1_000_000.0;
        recordFrameTelemetryAndDynamicRes(ms);
        this.displayedFrame = target;
        this.nextBufferIsA = !this.nextBufferIsA;
    }

    private void recordFrameTelemetryAndDynamicRes(double renderMs) {
        if (renderMs <= 0.01 || renderMs > 2000.0) return;
        this.lastFrameMs = renderMs;
        this.emaFrameMs = this.emaFrameMs * 0.88 + renderMs * 0.12;
        this.frameMsHistory[this.frameMsCursor] = (float) renderMs;
        this.frameMsCursor = (this.frameMsCursor + 1) % this.frameMsHistory.length;

        if (++dynResAdjustCounter >= 12) {
            dynResAdjustCounter = 0;
            // Обновляем 1% Low FPS по худшему кадру в скользящем окне
            float worstMs = 1.0f;
            for (int i = 0; i < frameMsHistory.length; i++) {
                if (frameMsHistory[i] > worstMs) worstMs = frameMsHistory[i];
            }
            this.onePercentLowFps = Math.max(1, (int) Math.round(1000.0 / worstMs));

            if (settings.dynamicResolution == GameSettings.DynamicResolutionMode.OFF) {
                dynamicResScaleFactor = 1.0;
            } else {
                double targetMs = 1000.0 / Math.max(30, settings.dynamicResolution.targetFps);
                if (emaFrameMs > targetMs * 1.07 && dynamicResScaleFactor > 0.58) {
                    dynamicResScaleFactor = Math.max(0.58, dynamicResScaleFactor - 0.06);
                } else if (emaFrameMs < targetMs * 0.78 && dynamicResScaleFactor < 1.0) {
                    dynamicResScaleFactor = Math.min(1.0, dynamicResScaleFactor + 0.04);
                }
            }
        }
    }

    /**
     * Полный рендер кадра CS-2 JAVA 7.0 (Hyper-Turbo Analytic Rasterizer + Deferred Skybox + Parallel Post-FX).
     */
    public void renderCompleteFrame(Graphics2D g, int outW, int outH) {
        buildingUiClickables.clear();
        settings.syncDerivedFlags();

        double zoomFactor = (currentScreen == AppScreen.IN_GAME && player.isAlive())
                ? player.getCurrentWeapon().getEffectiveFovFactor()
                : 1.0;
        double dashBoost = (currentScreen == AppScreen.IN_GAME && player.isAlive())
                ? player.getDashFovBoostDegrees()
                : 0.0;
        double effectiveFov = Math.min(94.0, (settings.fovDegrees + dashBoost) * zoomFactor);

        int base3dW = settings.resolutionScale.resolveWidth(outW);
        int base3dH = settings.resolutionScale.resolveHeight(outH);
        double dynScale = (settings.dynamicResolution == GameSettings.DynamicResolutionMode.OFF)
                ? 1.0 : dynamicResScaleFactor;
        int target3dW = (dynScale >= 0.995) ? base3dW : Math.max(480, ((int) Math.round(base3dW * dynScale) + 7) & ~15);
        int target3dH = (dynScale >= 0.995) ? base3dH : Math.max(270, ((int) Math.round(base3dH * dynScale) + 4) & ~7);

        renderer.ensureViewport(target3dW, target3dH, effectiveFov);
        renderer.configureAdvancedGraphics(settings);
        renderer.applyTimeOfDay(settings.timeOfDay);
        renderer.setGraphicsFlags(
                settings.fogEnabled,
                settings.skyCloudsAndSunFlare,
                settings.leftHandedModel,
                globalAnimTime
        );

        // Установка камеры (от первого лица в бою или плавный кинематографический облет в меню)
        if (currentScreen == AppScreen.IN_GAME || (currentScreen == AppScreen.SETTINGS && settingsOpenedFromGame)) {
            Vec3 eye = player.getEyePosition();
            renderer.setCamera(eye.x, eye.y, eye.z, player.getEffectiveYaw(), player.getEffectivePitch());
        } else {
            double orbitAngle = globalAnimTime * 0.14;
            double camX = Math.sin(orbitAngle) * 14.2;
            double camZ = Math.cos(orbitAngle) * 14.2 - 3.0;
            double camY = 4.4 + Math.sin(globalAnimTime * 0.32) * 0.55;
            double lookYaw = Math.atan2(-camX, 2.5 - camZ);
            renderer.setCamera(camX, camY, camZ, lookYaw, -0.15);
        }

        // Очистка ТОЛЬКО Z-буфера (~0.1 мс) перед непрозрачной геометрией!
        renderer.beginFrame();

        // 1. EARLY-Z ОПТИМИЗАЦИЯ: сначала стены, здания, колонны и ящики!
        map.renderStructures(renderer);

        // 2. Затем пол (пропуская перекрытые стенами спаны за O(1) по Early-Z!)
        map.renderFloorOnly(renderer);

        // 3. Непрозрачные 3D-объекты арены, боты, турели, ракеты и Босс-Вертолёт
        if (settings.decalsAndShells) {
            for (int i = 0; i < decals.size(); i++) {
                renderer.drawDecal(decals.get(i));
            }
        }
        for (int i = 0; i < graffitis.size(); i++) {
            renderer.drawGraffiti(graffitis.get(i));
        }
        for (int i = 0; i < fireZones.size(); i++) {
            renderer.drawFireZone(fireZones.get(i));
        }
        if (plantedC4 != null) {
            Vec3 cp = plantedC4.pos;
            renderer.drawOrientedWorldBox(cp.x, cp.y + 0.06, cp.z, 0.36, 0.14, 0.26, 0.35, 0.0, Renderer3D.TEX_SOLID, 0xC2A35E);
            renderer.drawOrientedWorldBox(cp.x, cp.y + 0.14, cp.z, 0.16, 0.04, 0.14, 0.35, 0.0, Renderer3D.TEX_SOLID, 0x262C34);
            if (Math.sin(plantedC4.blinkPhase) > 0.0) {
                renderer.drawOrientedWorldBox(cp.x, cp.y + 0.20, cp.z, 0.12, 0.12, 0.12, 0.0, 0.0, Renderer3D.TEX_SOLID, 0xFF2B22);
            }
        }
        for (int i = 0; i < pickups.size(); i++) {
            Vec3.Pickup pk = pickups.get(i);
            double py = pk.pos.y + 0.22 + Math.sin(pk.bobPhase) * 0.06;
            int col = (pk.type == Vec3.Pickup.Type.MEDKIT) ? 0x38C96B : 0xE6B444;
            renderer.drawOrientedWorldBox(pk.pos.x, py, pk.pos.z, 0.36, 0.24, 0.36, pk.bobPhase * 0.7, 0.0, Renderer3D.TEX_SOLID, col);
        }
        for (int i = 0; i < grenades.size(); i++) {
            Vec3.Grenade gr = grenades.get(i);
            int gCol;
            switch (gr.kind) {
                case HE:      gCol = 0x455A38; break;
                case SMOKE:   gCol = 0x707882; break;
                case FLASH:   gCol = 0xE6EEF6; break;
                default:      gCol = 0xDF5A1E; break;
            }
            renderer.drawOrientedWorldBox(gr.pos.x, gr.pos.y, gr.pos.z, 0.16, 0.22, 0.16, gr.yaw, gr.yaw * 0.8, Renderer3D.TEX_SOLID, gCol);
        }
        if (settings.decalsAndShells) {
            for (int i = 0; i < shells.size(); i++) {
                Vec3.ShellCasing sc = shells.get(i);
                renderer.drawOrientedWorldBox(sc.x, sc.y, sc.z, 0.025, 0.025, 0.065, sc.yaw, sc.pitch, Renderer3D.TEX_SOLID, sc.color);
            }
        }
        for (int i = 0; i < bots.size(); i++) {
            bots.get(i).render(renderer);
        }
        for (int i = 0; i < sentryTurrets.size(); i++) {
            renderer.drawSentryTurret(sentryTurrets.get(i));
        }
        for (int i = 0; i < supplyDrops.size(); i++) {
            renderer.drawSupplyDrop(supplyDrops.get(i));
        }
        for (int i = 0; i < airstrikeBeacons.size(); i++) {
            renderer.drawAirstrikeBeacon(airstrikeBeacons.get(i));
        }
        for (int i = 0; i < rockets.size(); i++) {
            renderer.drawRocketProjectile(rockets.get(i));
        }
        if (bossHelicopter != null && bossHelicopter.isAlive()) {
            renderer.drawAttackHelicopter(bossHelicopter);
        } else if (currentScreen == AppScreen.MAIN_MENU && settings.gameMode == GameSettings.GameMode.SIEGE_WAVES) {
            Vec3.AttackHelicopter menuHeli = new Vec3.AttackHelicopter(850);
            double a = globalAnimTime * 0.42;
            menuHeli.pos.set(Math.cos(a) * 10.5, 9.2 + Math.sin(a * 2.0) * 0.6, Math.sin(a) * 10.5);
            menuHeli.yaw = Math.atan2(-Math.sin(a), Math.cos(a));
            menuHeli.rotorPhase = globalAnimTime * 25.0;
            renderer.drawAttackHelicopter(menuHeli);
        }

        // 4. Растеризуем всю непрозрачную геометрию + Deferred Skybox (небо считается ТОЛЬКО на открытых пикселях!)
        renderer.flushRasterQueue();
        renderer.fillVisibleSkyParallel();

        // 5. Мягкие солнечные и контактные тени поверх готового пола
        if (settings.shadowMode != GameSettings.ShadowMode.OFF) {
            map.renderShadowsOnly(renderer, settings.shadowMode, bots);
            renderer.flushRasterQueue();
        }

        // 6. Субпиксельные трассеры Ву, частицы, 3D-погода и объемные облака дыма CS2
        for (int i = 0; i < tracers.size(); i++) {
            Vec3.Tracer tr = tracers.get(i);
            renderer.draw3DLine(tr.start, tr.end, tr.color, 2);
        }
        for (int i = 0; i < particles.size(); i++) {
            renderer.drawParticle(particles.get(i));
        }
        renderer.drawWeatherParticles(globalAnimTime, map);
        for (int i = 0; i < smokeClouds.size(); i++) {
            renderer.drawSmokeCloud(smokeClouds.get(i));
        }

        // 6.5. Направленный луч тактического 3D-фонарика (клавиша L)
        if (currentScreen == AppScreen.IN_GAME && player.isAlive() && player.isFlashlightOn()) {
            renderer.applyFlashlightBeam();
        }

        // 7. Оружие от 1-го лица / 3D-Оперативник Спецназа SAS в Главном меню / 3D-Витрина скинов
        if (currentScreen == AppScreen.IN_GAME && player.isAlive()) {
            renderer.clearDepthBuffer();
            GameSettings.WeaponSkin activeSkin = settings.weaponSkin;
            if (settings.gameMode == GameSettings.GameMode.ARMS_RACE
                    && player.getArmsRaceTier() == Weapon.ARMS_RACE_LADDER.length - 1
                    && player.getCurrentWeapon().getType() == Weapon.Type.KNIFE) {
                activeSkin = GameSettings.WeaponSkin.GOLD_FADE;
            }
            player.getCurrentWeapon().renderViewModel(
                renderer,
                player.getWalkBobPhase(),
                player.getWalkSpeedNormalized(),
                player.getSwayX(),
                player.getSwayY(),
                activeSkin
            );
            renderer.flushRasterQueue();
        } else if (currentScreen == AppScreen.MAIN_MENU) {
            renderer.clearDepthBuffer();
            renderer.setGraphicsFlags(settings.fogEnabled, settings.skyCloudsAndSunFlare, false, globalAnimTime);
            renderer.drawMenuOperatorShowcase(globalAnimTime, settings.weaponSkin,
                    armoryShowcaseWeapons[armoryWeaponIdx].getType());
            renderer.flushRasterQueue();
        } else if (currentScreen == AppScreen.SKIN_ARMORY) {
            renderer.clearDepthBuffer();
            renderer.setGraphicsFlags(settings.fogEnabled, settings.skyCloudsAndSunFlare, false, globalAnimTime);
            armoryShowcaseWeapons[armoryWeaponIdx].renderShowcaseModel(
                    renderer, globalAnimTime * 1.15, settings.weaponSkin);
            renderer.flushRasterQueue();
        }

        // 8. Многопоточный Post-FX конвейер 7.0: FXAA Turbo / Span 3.11 + CAS резкость + Насыщенность + Яркость + Виньетка!
        renderer.applyPostProcessingPass();

        // Многопоточный SWAR-апскейлер/даунскейлер + прямой 1:1 блит (в 15x быстрее Java2D drawImage!)
        g.drawImage(renderer.getPresentedImage(outW, outH, settings.bilinearFiltering), 0, 0, null);

        boolean fastCombatHud = (currentScreen == AppScreen.IN_GAME && !buyMenuOpen && matchState == MatchState.PLAYING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                fastCombatHud ? RenderingHints.VALUE_ANTIALIAS_OFF : RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Отрисовка текущего экрана Panorama Glass UI 7.0
        if (currentScreen == AppScreen.MAIN_MENU) {
            drawMainMenuScreen(g, outW, outH);
        } else if (currentScreen == AppScreen.SKIN_ARMORY) {
            drawSkinArmoryScreen(g, outW, outH);
        } else if (currentScreen == AppScreen.SETTINGS) {
            drawSettingsScreen(g, outW, outH);
        } else if (currentScreen == AppScreen.ARSENAL_GUIDE) {
            drawArsenalGuideScreen(g, outW, outH);
        } else {
            drawInGameUi(g, outW, outH);
        }

        Rectangle flash = this.clickFlashRect;
        if (clickFlashTimer > 0.0 && flash != null && isCursorFreeScreen()) {
            int alpha = Math.min(130, (int) (clickFlashTimer * 950.0));
            g.setColor(new Color(255, 236, 150, alpha));
            g.fillRoundRect(flash.x, flash.y, flash.width, flash.height, 10, 10);
            g.setColor(new Color(255, 255, 255, Math.min(220, alpha + 70)));
            g.drawRoundRect(flash.x, flash.y, flash.width, flash.height, 10, 10);
        }

        this.publishedUiSnapshot = new UiSnapshot(new ArrayList<>(buildingUiClickables), outW, outH);
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
    //  1. ГЛАВНОЕ МЕНЮ COUNTER-STRIKE 2 (PANORAMA GLASS UI 6.0)
    // =========================================================================
    private void drawMainMenuScreen(Graphics2D g, int outW, int outH) {
        // 1. ВЕРХНЯЯ НАВИГАЦИОННАЯ ПАНЕЛЬ PANORAMA UI (матовое тёмное стекло + неоновый акцент)
        int navH = 64;
        GradientPaint topGrad = new GradientPaint(0, 0, new Color(10, 15, 24, 236), 0, navH, new Color(14, 21, 34, 212));
        g.setPaint(topGrad);
        g.fillRect(0, 0, outW, navH);
        g.setColor(new Color(248, 192, 68, 175));
        g.fillRect(0, navH - 2, outW, 2);

        // Скошенный киберспортивный щит-логотип CS2 слева
        Polygon crest = new Polygon(
                new int[]{18, 72, 64, 18},
                new int[]{10, 10, 54, 54}, 4);
        g.setPaint(new GradientPaint(18, 10, new Color(255, 206, 78), 64, 54, new Color(224, 134, 24)));
        g.fillPolygon(crest);
        g.setColor(new Color(14, 18, 26));
        g.setFont(uiFont("SansSerif", Font.BOLD, 21));
        g.drawString("CS2", 22, 39);

        g.setColor(Color.WHITE);
        g.setFont(uiFont("SansSerif", Font.BOLD, 21));
        g.drawString("CS-2 JAVA 7.0", 80, 33);
        g.setColor(new Color(108, 216, 255));
        g.setFont(uiFont("SansSerif", Font.BOLD, 10));
        g.drawString("HYPER-TURBO • PRO STUDIO", 81, 49);

        // Центральные вкладки навигации Panorama UI
        int tabsStartX = 256;
        int tabGap = 6;
        String[] navLabels = {"★ ИГРАТЬ", "3D СКИНЫ", "ГРАФИКА", "УПРАВЛЕНИЕ", "АУДИО", "СПРАВКА", "F11 ЭКРАН", "ВЫХОД"};
        Runnable[] navActions = {
            this::launchMatchFromMenu,
            () -> currentScreen = AppScreen.SKIN_ARMORY,
            () -> openSettingsScreen(false, 0),
            () -> openSettingsScreen(false, 1),
            () -> openSettingsScreen(false, 2),
            () -> currentScreen = AppScreen.ARSENAL_GUIDE,
            this::toggleFullscreenMode,
            this::shutdownAndExit
        };
        int availNavW = Math.max(440, outW - tabsStartX - 278);
        int navBtnW = Math.min(106, (availNavW - (navLabels.length - 1) * tabGap) / navLabels.length);
        for (int i = 0; i < navLabels.length; i++) {
            int tx = tabsStartX + i * (navBtnW + tabGap);
            Rectangle tr = new Rectangle(tx, 12, navBtnW, 40);
            boolean hov = tr.contains(mouseClientX, mouseClientY);
            boolean primary = (i == 0);
            boolean danger = (i == navLabels.length - 1);
            Color bg = primary ? (hov ? new Color(248, 174, 46) : new Color(228, 148, 30))
                    : (danger ? (hov ? new Color(168, 52, 58) : new Color(112, 36, 42, 210))
                    : (hov ? new Color(42, 62, 92, 235) : new Color(24, 34, 50, 195)));
            g.setColor(bg);
            g.fillRoundRect(tr.x, tr.y, tr.width, tr.height, 8, 8);
            if (primary || hov) {
                g.setColor(primary ? new Color(255, 238, 168) : new Color(110, 212, 255));
                g.fillRect(tr.x + 6, tr.y + tr.height - 3, tr.width - 12, 3);
            }
            drawCenteredText(g, navLabels[i], tr.x + tr.width / 2, tr.y + 25,
                    uiFont("SansSerif", Font.BOLD, 11),
                    primary ? new Color(16, 18, 24) : Color.WHITE);
            uiRegisterClickable(tr, navActions[i]);
        }

        // Карточка Профиля и Воинского Звания CS2 справа в шапке
        int profW = 252, profX = outW - profW - 14, profY = 8;
        g.setColor(new Color(18, 26, 40, 226));
        g.fillRoundRect(profX, profY, profW, 48, 10, 10);
        Color rankCol = settings.getRankColor();
        g.setColor(new Color(rankCol.getRed(), rankCol.getGreen(), rankCol.getBlue(), 185));
        g.drawRoundRect(profX, profY, profW, 48, 10, 10);

        // Герб-эмблема ранга
        int sx = profX + 22, sy = profY + 24;
        Polygon shield = new Polygon(
                new int[]{sx, sx + 13, sx + 10, sx, sx - 10, sx - 13},
                new int[]{sy - 15, sy - 9, sy + 7, sy + 15, sy + 7, sy - 9}, 6);
        g.setColor(rankCol);
        g.fillPolygon(shield);
        g.setColor(Color.WHITE);
        g.drawPolygon(shield);

        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        g.setColor(Color.WHITE);
        g.drawString(settings.getRankTitle(), profX + 42, profY + 20);
        g.setFont(uiFont("SansSerif", Font.PLAIN, 10));
        g.setColor(new Color(176, 194, 216));
        g.drawString("XP: " + settings.careerXp + " • Фрагов: " + settings.careerKills, profX + 42, profY + 34);

        // Полоса прогресса до следующего звания
        int xpBarX = profX + 42, xpBarW = profW - 54;
        g.setColor(new Color(34, 46, 64));
        g.fillRoundRect(xpBarX, profY + 39, xpBarW, 5, 4, 4);
        g.setColor(rankCol);
        g.fillRoundRect(xpBarX, profY + 39, (int) (xpBarW * settings.getRankProgress()), 5, 4, 4);

        // 2. ПЛАВАЮЩАЯ ВИТРИНА 3D-ОПЕРАТИВНИКА СПЕЦНАЗА SAS (СЛЕВА СНИЗУ ПОД 3D- БОЙЦОМ)
        int opCardX = 24;
        int opCardW = 356;
        int opCardH = 108;
        int opCardY = outH - opCardH - 42;
        g.setPaint(new GradientPaint(opCardX, opCardY, new Color(12, 18, 28, 224),
                opCardX + opCardW, opCardY + opCardH, new Color(18, 28, 44, 206)));
        g.fillRoundRect(opCardX, opCardY, opCardW, opCardH, 14, 14);
        g.setColor(new Color(88, 196, 255, 155));
        g.drawRoundRect(opCardX, opCardY, opCardW, opCardH, 14, 14);

        Weapon showcaseW = armoryShowcaseWeapons[armoryWeaponIdx];
        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(102, 208, 255));
        g.drawString("◆ ОПЕРАТИВНИК СПЕЦНАЗА SAS CT (3D PREVIEW)", opCardX + 16, opCardY + 22);
        g.setFont(uiFont("SansSerif", Font.BOLD, 16));
        g.setColor(Color.WHITE);
        g.drawString(showcaseW.getDisplayName() + "  |  " + settings.weaponSkin.label, opCardX + 16, opCardY + 45);

        addMenuButton(g, new Rectangle(opCardX + 16, opCardY + 58, 156, 36),
                "⟳ СМЕНИТЬ ОРУЖИЕ", new Color(36, 58, 86), false,
                () -> armoryWeaponIdx = (armoryWeaponIdx + 1) % armoryShowcaseWeapons.length);
        addMenuButton(g, new Rectangle(opCardX + 182, opCardY + 58, 158, 36),
                "★ 3D СКИНЫ (" + GameSettings.WeaponSkin.values().length + ")", new Color(124, 52, 164), false,
                () -> currentScreen = AppScreen.SKIN_ARMORY);

        // 3. ПРАВАЯ КОМАНДНАЯ ПАНЕЛЬ ЗАПУСКА СПЕЦОПЕРАЦИИ (КАРТОЧКИ 4 КАРТ + РЕЖИМЫ + НАСТРОЙКИ БОЯ)
        int deckW = Math.min(548, outW - 418);
        int deckH = Math.min(542, outH - navH - 46);
        int deckX = outW - deckW - 22;
        int deckY = navH + 14;

        g.setPaint(new GradientPaint(deckX, deckY, new Color(11, 16, 26, 232),
                deckX, deckY + deckH, new Color(15, 22, 36, 238)));
        g.fillRoundRect(deckX, deckY, deckW, deckH, 18, 18);
        g.setColor(new Color(255, 255, 255, 52));
        g.drawRoundRect(deckX, deckY, deckW, deckH, 18, 18);

        g.setFont(uiFont("SansSerif", Font.BOLD, 15));
        g.setColor(new Color(252, 204, 84));
        g.drawString("ВЫБЕРИТЕ КАРТУ ОПЕРАЦИИ (ЖИВОЙ 3D-ПРЕДПРОСМОТР)", deckX + 20, deckY + 26);

        // Интерактивная сетка 2×2 карточек 3D-карт CS2!
        GameSettings.MapTheme[] themes = GameSettings.MapTheme.values();
        String[] mapNames = {"INFERNO", "DUST II", "MIRAGE", "NUKE"};
        String[] mapSubs = {"Фонтан • Колокольня", "Длина A • Тоннель B", "Дворец • Арки Мида", "Ядерные силосы A/B"};
        Color[] mapAccents = {
            new Color(236, 118, 54), new Color(228, 178, 76),
            new Color(214, 122, 86), new Color(68, 168, 242)
        };
        int mCols = 2;
        int mGap = 10;
        int mCardW = (deckW - 40 - mGap) / mCols;
        int mCardH = 54;
        int mStartY = deckY + 36;
        for (int i = 0; i < themes.length; i++) {
            final GameSettings.MapTheme th = themes[i];
            int mc = i % mCols, mr = i / mCols;
            Rectangle mrRect = new Rectangle(deckX + 20 + mc * (mCardW + mGap), mStartY + mr * (mCardH + 8), mCardW, mCardH);
            boolean active = (settings.mapTheme == th);
            boolean hov = mrRect.contains(mouseClientX, mouseClientY);

            Color cTop = active ? new Color(mapAccents[i].getRed() / 3 + 22, mapAccents[i].getGreen() / 3 + 26, mapAccents[i].getBlue() / 3 + 36, 242)
                    : (hov ? new Color(32, 44, 64, 232) : new Color(20, 28, 42, 218));
            g.setColor(cTop);
            g.fillRoundRect(mrRect.x, mrRect.y, mrRect.width, mrRect.height, 10, 10);
            g.setColor(mapAccents[i]);
            g.fillRoundRect(mrRect.x, mrRect.y, 6, mrRect.height, 6, 6);

            g.setStroke(new BasicStroke(active ? 2.0f : 1.0f));
            g.setColor(active ? mapAccents[i] : new Color(255, 255, 255, 38));
            g.drawRoundRect(mrRect.x, mrRect.y, mrRect.width, mrRect.height, 10, 10);
            g.setStroke(new BasicStroke(1.0f));

            g.setFont(uiFont("SansSerif", Font.BOLD, 14));
            g.setColor(active ? Color.WHITE : new Color(224, 232, 244));
            g.drawString((active ? "★ " : "") + mapNames[i], mrRect.x + 16, mrRect.y + 23);
            g.setFont(uiFont("SansSerif", Font.PLAIN, 11));
            g.setColor(active ? new Color(255, 232, 176) : new Color(162, 178, 198));
            g.drawString(mapSubs[i], mrRect.x + 16, mrRect.y + 42);

            uiRegisterClickable(mrRect, () -> {
                settings.mapTheme = th;
                settings.save();
                startNewMatch();
            });
        }

        // Интерактивные таблетки выбора режима игры
        int modeY = mStartY + 2 * (mCardH + 8) + 8;
        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(176, 194, 216));
        g.drawString("РЕЖИМ СПЕЦОПЕРАЦИИ:", deckX + 20, modeY + 12);

        GameSettings.GameMode[] modes = GameSettings.GameMode.values();
        String[] shortModes = {"ОСАДА: БОСС", "БОМБА C4", "ГОНКА ПУШЕК", "ЗАЧИСТКА", "БОЙ НАСМЕРТЬ"};
        int modePillY = modeY + 18;
        int mpGap = 6;
        int mpW = (deckW - 40 - (modes.length - 1) * mpGap) / modes.length;
        for (int i = 0; i < modes.length; i++) {
            final GameSettings.GameMode gm = modes[i];
            Rectangle pr = new Rectangle(deckX + 20 + i * (mpW + mpGap), modePillY, mpW, 32);
            boolean sel = (settings.gameMode == gm);
            boolean hov = pr.contains(mouseClientX, mouseClientY);
            g.setColor(sel ? new Color(46, 132, 216) : (hov ? new Color(36, 50, 72) : new Color(22, 31, 46)));
            g.fillRoundRect(pr.x, pr.y, pr.width, pr.height, 8, 8);
            g.setColor(sel ? new Color(148, 224, 255) : new Color(255, 255, 255, 36));
            g.drawRoundRect(pr.x, pr.y, pr.width, pr.height, 8, 8);
            drawCenteredText(g, shortModes[i], pr.x + pr.width / 2, pr.y + 20,
                    uiFont("SansSerif", Font.BOLD, 10), Color.WHITE);
            uiRegisterClickable(pr, () -> {
                settings.gameMode = gm;
                settings.save();
                startNewMatch();
            });
        }

        // Компактные переключатели параметров отряда и атмосферы
        int rowY = modePillY + 42;
        int stepH = 37;
        String allyLabel = (settings.allyCount == 0) ? "Соло (Без ботов CT)" : (settings.allyCount + " спецназовца CT");
        drawOptionStepperRow(g, deckX + 20, rowY, deckW - 40, "Союзники спецназа CT", allyLabel, () -> {
            settings.allyCount = (settings.allyCount + 1) % 5;
            settings.save();
            startNewMatch();
        });
        rowY += stepH;

        drawOptionStepperRow(g, deckX + 20, rowY, deckW - 40, "Силы противника T", settings.botCount + " бойцов T", () -> {
            settings.botCount = (settings.botCount >= 8) ? 3 : settings.botCount + 1;
            settings.save();
            startNewMatch();
        });
        rowY += stepH;

        drawOptionStepperRow(g, deckX + 20, rowY, deckW - 40, "Уровень ИИ и тактики", settings.botDifficulty.label, () -> {
            int ord = (settings.botDifficulty.ordinal() + 1) % GameSettings.BotDifficulty.values().length;
            settings.botDifficulty = GameSettings.BotDifficulty.values()[ord];
            settings.save();
        });
        rowY += stepH;

        drawOptionStepperRow(g, deckX + 20, rowY, deckW - 40, "Время суток и свет", settings.timeOfDay.label, () -> {
            int ord = (settings.timeOfDay.ordinal() + 1) % GameSettings.TimeOfDay.values().length;
            settings.timeOfDay = GameSettings.TimeOfDay.values()[ord];
            renderer.applyTimeOfDay(settings.timeOfDay);
            settings.save();
        });
        rowY += stepH;

        drawOptionStepperRow(g, deckX + 20, rowY, deckW - 40, "Атмосферная 3D-погода", settings.weatherMode.label, () -> {
            int ord = (settings.weatherMode.ordinal() + 1) % GameSettings.WeatherMode.values().length;
            settings.weatherMode = GameSettings.WeatherMode.values()[ord];
            settings.save();
        });
        rowY += stepH + 8;

        // ГЛАВНАЯ КНОПКА ЗАПУСКА МАТЧА С ЯРКИМ ГРАДИЕНТОМ CS2!
        int ctaH = Math.max(44, deckY + deckH - rowY - 14);
        Rectangle playRect = new Rectangle(deckX + 20, rowY, deckW - 40, Math.min(54, ctaH));
        boolean playHov = playRect.contains(mouseClientX, mouseClientY);
        g.setPaint(new GradientPaint(playRect.x, playRect.y,
                playHov ? new Color(255, 206, 74) : new Color(242, 168, 38),
                playRect.x + playRect.width, playRect.y + playRect.height,
                playHov ? new Color(246, 138, 28) : new Color(216, 108, 20)));
        g.fillRoundRect(playRect.x, playRect.y, playRect.width, playRect.height, 12, 12);
        g.setColor(new Color(255, 244, 196));
        g.drawRoundRect(playRect.x, playRect.y, playRect.width, playRect.height, 12, 12);
        drawCenteredText(g, "▶   НАЧАТЬ СПЕЦОПЕРАЦИЮ  •  " + settings.mapTheme.name().replace("_", " "),
                playRect.x + playRect.width / 2, playRect.y + playRect.height / 2 + 6,
                uiFont("SansSerif", Font.BOLD, 17), new Color(16, 18, 24));
        uiRegisterClickable(playRect, this::launchMatchFromMenu);

        // 4. НИЖНЯЯ СТРОКА ТЕЛЕМЕТРИИ ДВИЖКА 6.0
        g.setColor(new Color(10, 14, 22, 216));
        g.fillRect(0, outH - 28, outW, 28);
        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(122, 228, 164));
        String perfInfo = String.format("● %d FPS (%.1f мс | 1%%: %d)  •  3D: %dx%d  •  %s  •  Потоков: %d  •  Culling: %d",
                currentFps, lastFrameMs, onePercentLowFps, renderer.getWidth(), renderer.getHeight(),
                settings.qualityPreset.name(), renderer.getWorkerThreads(), renderer.getLastFrameCulledObjects());
        g.drawString(perfInfo, 22, outH - 10);

        String hotkeysHint = "[Alt] Тактический рывок  •  [U] Авиаудар  •  [J] Авто-Турель  •  [K] Босс-Вертолёт";
        g.setColor(new Color(210, 222, 238));
        g.drawString(hotkeysHint, outW - g.getFontMetrics().stringWidth(hotkeysHint) - 22, outH - 10);
    }

    // =========================================================================
    //  1.5. 3D ОРУЖЕЙНАЯ СКИНОВ CS2 (PANORAMA GLASS ARMORY 6.0)
    // =========================================================================
    private void drawSkinArmoryScreen(Graphics2D g, int outW, int outH) {
        int panelW = 438;
        g.setPaint(new GradientPaint(0, 0, new Color(10, 15, 25, 238), panelW, 0, new Color(16, 22, 36, 224)));
        g.fillRect(0, 0, panelW, outH);
        g.setColor(new Color(196, 108, 255, 185));
        g.fillRect(panelW - 2, 0, 2, outH);

        g.setFont(uiFont("SansSerif", Font.BOLD, 24));
        g.setColor(Color.WHITE);
        g.drawString("3D АРСЕНАЛ И СКИНЫ", 28, 42);
        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(214, 152, 255));
        g.drawString("ВЫСОКОТОЧНЫЕ МОДЕЛИ ГУРО • КЕРАМБИТ • ОПТИКА", 28, 60);

        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(185, 198, 216));
        g.drawString("ОРУЖИЕ НА 3D-ПОДИУМЕ (11 МОДЕЛЕЙ):", 28, 86);

        int wx = 28, wy = 94;
        int cols = 4;
        int btnW = (panelW - 56 - (cols - 1) * 6) / cols;
        for (int i = 0; i < armoryShowcaseWeapons.length; i++) {
            final int idx = i;
            Weapon w = armoryShowcaseWeapons[i];
            int col = i % cols;
            int row = i / cols;
            Rectangle r = new Rectangle(wx + col * (btnW + 6), wy + row * 38, btnW, 32);
            boolean sel = (armoryWeaponIdx == i);
            String label = w.getType() == Weapon.Type.DEAGLE ? "Deagle"
                    : (w.getType() == Weapon.Type.M249 ? "M249"
                    : (w.getType() == Weapon.Type.GLOCK ? "Glock"
                    : (w.getType() == Weapon.Type.ZEUS ? "Zeus"
                    : (w.getType() == Weapon.Type.KNIFE ? "Керамбит" : w.getDisplayName()))));
            addMenuButton(g, r, label,
                    sel ? new Color(232, 156, 38) : new Color(30, 42, 60), sel,
                    () -> armoryWeaponIdx = idx);
        }

        int sy = 224;
        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(185, 198, 216));
        g.drawString("КОЛЛЕКЦИОННАЯ РАСКРАСКА CS2:", 28, sy);
        sy += 10;

        GameSettings.WeaponSkin[] allSkins = GameSettings.WeaponSkin.values();
        for (int i = 0; i < allSkins.length; i++) {
            final GameSettings.WeaponSkin sk = allSkins[i];
            boolean active = (settings.weaponSkin == sk);
            Color base = active ? new Color(142, 58, 184) : new Color(26, 36, 52);
            addMenuButton(g, new Rectangle(28, sy + i * 39, panelW - 56, 33),
                    (active ? "★  " : "    ") + sk.label, base, false, () -> {
                        settings.weaponSkin = sk;
                        settings.save();
                    });
        }

        addMenuButton(g, new Rectangle(28, outH - 64, panelW - 56, 44),
                "← ПРИМЕНИТЬ И В ГЛАВНОЕ МЕНЮ", new Color(48, 154, 96), false, () -> {
            settings.save();
            currentScreen = AppScreen.MAIN_MENU;
        });

        // Информационная стеклянная карточка ТТХ со шкалами урона и бронебойности!
        Weapon cw = armoryShowcaseWeapons[armoryWeaponIdx];
        int infoW = Math.min(680, outW - panelW - 48);
        int infoH = 136;
        int infoX = panelW + (outW - panelW - infoW) / 2;
        int infoY = outH - infoH - 28;

        g.setColor(new Color(11, 16, 26, 224));
        g.fillRoundRect(infoX, infoY, infoW, infoH, 16, 16);
        g.setColor(new Color(248, 196, 72, 175));
        g.drawRoundRect(infoX, infoY, infoW, infoH, 16, 16);

        String fullTitle = cw.getDisplayName().toUpperCase() + "  •  " + settings.weaponSkin.label.toUpperCase();
        drawCenteredText(g, fullTitle, infoX + infoW / 2, infoY + 30,
                uiFont("SansSerif", Font.BOLD, 20), new Color(252, 212, 92));

        String statsLine = cw.isMelee()
                ? "Изогнутый клинок Керамбит  •  ЛКМ: 55  •  Удар ПКМ: 92 / 155 HS  •  Вращение на кольце [F]!"
                : String.format("Урон (тело / голова): %d / %d   •   Стоимость: $%d   •   Награда за фраг: $%d",
                        cw.getBodyDamage(), cw.getHeadDamage(), cw.getPrice(), cw.getKillReward());
        drawCenteredText(g, statsLine, infoX + infoW / 2, infoY + 56,
                uiFont("SansSerif", Font.PLAIN, 13), new Color(226, 234, 246));

        // 3 визуальные шкалы характеристик оружия
        double dmgFrac = Math.min(1.0, cw.getHeadDamage() / 220.0);
        double penFrac = cw.isMelee() ? 0.35 : Math.min(1.0, cw.getWallbangDamageFactor() + 0.35);
        double rngFrac = Math.min(1.0, cw.getMaxRange() / 110.0);
        int barW = (infoW - 96) / 3;
        String[] bNames = {"МОЩНОСТЬ", "ПРОБИТИЕ", "ДАЛЬНОСТЬ"};
        double[] bVals = {dmgFrac, penFrac, rngFrac};
        Color[] bCols = {new Color(248, 114, 68), new Color(248, 198, 68), new Color(76, 198, 255)};
        for (int b = 0; b < 3; b++) {
            int bx = infoX + 24 + b * (barW + 24);
            int by = infoY + 86;
            g.setFont(uiFont("SansSerif", Font.BOLD, 10));
            g.setColor(new Color(178, 194, 214));
            g.drawString(bNames[b], bx, by - 6);
            g.setColor(new Color(32, 44, 62));
            g.fillRoundRect(bx, by, barW, 8, 4, 4);
            g.setColor(bCols[b]);
            g.fillRoundRect(bx, by, (int) (barW * bVals[b]), 8, 4, 4);
        }
        drawCenteredText(g, "В бою: [H] мгновенная смена скина • [F] 3D-осмотр оружия и вращение Керамбита!",
                infoX + infoW / 2, infoY + 120,
                uiFont("SansSerif", Font.PLAIN, 12), new Color(168, 186, 208));
    }

    // =========================================================================
    //  2. МЕНЮ НАСТРОЕК ГРАФИКИ, МНОГОПОТОЧНОСТИ И УПРАВЛЕНИЯ (PANORAMA 6.0)
    // =========================================================================
    private void drawSettingsScreen(Graphics2D g, int outW, int outH) {
        g.setColor(new Color(7, 11, 18, 204));
        g.fillRect(0, 0, outW, outH);

        int pw = Math.min(1024, outW - 44);
        int ph = Math.min(666, outH - 36);
        int px = outW / 2 - pw / 2;
        int py = outH / 2 - ph / 2;

        g.setPaint(new GradientPaint(px, py, new Color(12, 18, 29, 248), px, py + ph, new Color(16, 24, 38, 248)));
        g.fillRoundRect(px, py, pw, ph, 18, 18);
        g.setColor(new Color(245, 194, 72, 185));
        g.drawRoundRect(px, py, pw, ph, 18, 18);

        g.setFont(uiFont("SansSerif", Font.BOLD, 22));
        g.setColor(Color.WHITE);
        g.drawString("СТУДИЯ ГРАФИКИ И ДВИЖКА CS-2 JAVA 7.0", px + 28, py + 36);

        String engineBadge = String.format("HYPER-TURBO • %d ПОТОКОВ CPU • %d FPS (%.1f мс)",
                renderer.getWorkerThreads(), currentFps, lastFrameMs);
        g.setFont(uiFont("Monospaced", Font.BOLD, 12));
        g.setColor(new Color(116, 236, 172));
        g.drawString(engineBadge, px + pw - g.getFontMetrics().stringWidth(engineBadge) - 28, py + 34);

        String[] tabs = {
            "1. ПРО-ГРАФИКА И ОПТИМИЗАЦИЯ (24)",
            "2. ГЕЙМПЛЕЙ, МЫШЬ И ПРИЦЕЛ",
            "3. АУДИО И HUD-ТЕЛЕМЕТРИЯ"
        };
        int tabGap = 10;
        int tabW = (pw - 56 - tabGap * 2) / 3;
        for (int i = 0; i < tabs.length; i++) {
            final int tabIdx = i;
            Rectangle tr = new Rectangle(px + 28 + i * (tabW + tabGap), py + 48, tabW, 34);
            boolean active = (settingsTab == i);
            addMenuButton(g, tr, tabs[i], active ? new Color(228, 152, 38) : new Color(30, 42, 60), active, () -> settingsTab = tabIdx);
        }

        int listX = px + 28;
        int listW = pw - 56;
        int rowY = py + 92;

        if (settingsTab == 0) {
            // Быстрая полоса выбора из 5 профилей производительности CS2
            g.setFont(uiFont("SansSerif", Font.BOLD, 12));
            g.setColor(new Color(186, 204, 226));
            g.drawString("ПРОФИЛЬ:", listX + 4, rowY + 21);

            GameSettings.QualityPreset[] presets = GameSettings.QualityPreset.values();
            String[] shortPresetNames = {"⚡ КИБЕРСПОРТ", "▸ НИЗКОЕ", "⚖ БАЛАНС HD", "★ ВЫСОКОЕ", "♦ УЛЬТРА 7.0"};
            int pStartX = listX + 86;
            int pGap = 7;
            int pBtnW = (listW - 86 - pGap * (presets.length - 1)) / presets.length;
            for (int i = 0; i < presets.length; i++) {
                final GameSettings.QualityPreset pr = presets[i];
                boolean sel = (settings.qualityPreset == pr);
                Rectangle prRect = new Rectangle(pStartX + i * (pBtnW + pGap), rowY, pBtnW, 30);
                addMenuButton(g, prRect, shortPresetNames[i],
                        sel ? new Color(64, 178, 118) : new Color(34, 48, 68), false, () -> {
                    settings.applyGraphicsPreset(pr);
                    dynamicResScaleFactor = 1.0;
                    renderer.configureAdvancedGraphics(settings);
                    renderer.applyTimeOfDay(settings.timeOfDay);
                    settings.save();
                });
            }
            rowY += 38;

            // Переключатель 2 подстраниц студии графики (по 12 карточек в 2 колонки!)
            int subW = (listW - 12) / 2;
            addMenuButton(g, new Rectangle(listX, rowY, subW, 30),
                    "СТР. 1: ЭКРАН, FPS, СГЛАЖИВАНИЕ И LOD (12)",
                    graphicsSubPage == 0 ? new Color(56, 132, 208) : new Color(26, 36, 52),
                    false, () -> graphicsSubPage = 0);
            addMenuButton(g, new Rectangle(listX + subW + 12, rowY, subW, 30),
                    "СТР. 2: ТЕНИ, ЦВЕТ, РЕЗКОСТЬ, ТУМАН И ПОГОДА (12)",
                    graphicsSubPage == 1 ? new Color(56, 132, 208) : new Color(26, 36, 52),
                    false, () -> graphicsSubPage = 1);
            rowY += 38;

            int colGap = 14;
            int colW = (listW - colGap) / 2;
            int leftX = listX;
            int rightX = listX + colW + colGap;
            int cardStepY = 41;

            if (graphicsSubPage == 0) {
                // Строка 1: 3D Разрешение | Динамическое Авто-Разрешение
                drawCompactSettingCard(g, leftX, rowY, colW, "3D-Разрешение рендера", settings.resolutionScale.label, () -> {
                    int n = (settings.resolutionScale.ordinal() + 1) % GameSettings.ResolutionScale.values().length;
                    settings.resolutionScale = GameSettings.ResolutionScale.values()[n];
                    settings.save();
                });
                String dynLbl = settings.dynamicResolution.label
                        + (settings.dynamicResolution != GameSettings.DynamicResolutionMode.OFF
                           ? (" [" + (int) Math.round(dynamicResScaleFactor * 100) + "%]") : "");
                drawCompactSettingCard(g, rightX, rowY, colW, "Авто-FPS (Динамическое разр.)", dynLbl, () -> {
                    int n = (settings.dynamicResolution.ordinal() + 1) % GameSettings.DynamicResolutionMode.values().length;
                    settings.dynamicResolution = GameSettings.DynamicResolutionMode.values()[n];
                    dynamicResScaleFactor = 1.0;
                    settings.save();
                });
                rowY += cardStepY;

                // Строка 2: Сглаживание FXAA | Адаптивная резкость CAS
                drawCompactSettingCard(g, leftX, rowY, colW, "Сглаживание краёв (Anti-Aliasing)", settings.antiAliasingMode.label, () -> {
                    int n = (settings.antiAliasingMode.ordinal() + 1) % GameSettings.AntiAliasingMode.values().length;
                    settings.antiAliasingMode = GameSettings.AntiAliasingMode.values()[n];
                    settings.syncDerivedFlags();
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Резкость текстур AMD CAS", settings.casSharpening.label, () -> {
                    int n = (settings.casSharpening.ordinal() + 1) % GameSettings.CasSharpeningMode.values().length;
                    settings.casSharpening = GameSettings.CasSharpeningMode.values()[n];
                    settings.save();
                });
                rowY += cardStepY;

                // Строка 3: Качество текстур Mipmap | Детализация 3D-геометрии LOD
                drawCompactSettingCard(g, leftX, rowY, colW, "Фильтрация текстур 512×512", settings.textureQuality.label, () -> {
                    int n = (settings.textureQuality.ordinal() + 1) % GameSettings.TextureQualityMode.values().length;
                    settings.textureQuality = GameSettings.TextureQualityMode.values()[n];
                    settings.syncDerivedFlags();
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Детализация 3D-моделей (LOD)", settings.geometryLod.label, () -> {
                    int n = (settings.geometryLod.ordinal() + 1) % GameSettings.GeometryLodMode.values().length;
                    settings.geometryLod = GameSettings.GeometryLodMode.values()[n];
                    settings.save();
                });
                rowY += cardStepY;

                // Строка 4: Дальность прорисовки | Многопоточный рендер CPU
                drawCompactSettingCard(g, leftX, rowY, colW, "Дальность прорисовки арены", settings.renderDistance.label, () -> {
                    int n = (settings.renderDistance.ordinal() + 1) % GameSettings.RenderDistanceMode.values().length;
                    settings.renderDistance = GameSettings.RenderDistanceMode.values()[n];
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Многопоточность Work-Stealing",
                        settings.multiCoreRendering ? ("Все ядра (" + renderer.getWorkerThreads() + " пот.)") : "1 поток", () -> {
                    settings.multiCoreRendering = !settings.multiCoreRendering;
                    settings.save();
                });
                rowY += cardStepY;

                // Строка 5: Лимит FPS | Фильтр масштабирования окна
                String capLabel = (settings.fpsLimit == 0) ? "Без лимита (Макс.)" : (settings.fpsLimit + " FPS");
                drawCompactSettingCard(g, leftX, rowY, colW, "Лимит частоты кадров (FPS Cap)", capLabel, () -> {
                    if (settings.fpsLimit == 60) settings.fpsLimit = 120;
                    else if (settings.fpsLimit == 120) settings.fpsLimit = 144;
                    else if (settings.fpsLimit == 144) settings.fpsLimit = 240;
                    else if (settings.fpsLimit == 240) settings.fpsLimit = 0;
                    else settings.fpsLimit = 60;
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Сглаживание апскейла окна",
                        settings.bilinearFiltering ? "Билинейное (Мягко)" : "Чёткий пиксель", () -> {
                    settings.bilinearFiltering = !settings.bilinearFiltering;
                    settings.save();
                });
                rowY += cardStepY;

                // Строка 6: Угол обзора FOV (-/+) | Полноэкранный режим F11
                drawCompactPlusMinusCard(g, leftX, rowY, colW, "Угол обзора камеры (FOV)", settings.fovDegrees + "°",
                        () -> { settings.fovDegrees = Math.max(56, settings.fovDegrees - 2); settings.save(); },
                        () -> { settings.fovDegrees = Math.min(90, settings.fovDegrees + 2); settings.save(); });
                drawCompactSettingCard(g, rightX, rowY, colW, "Режим отображения окна (F11)",
                        settings.fullscreen ? "Полный экран" : "Оконный 16:9", this::toggleFullscreenMode);
                rowY += cardStepY;
            } else {
                // СТР. 2: Освещение, Цветокоррекция, Атмосфера и Частицы
                drawCompactSettingCard(g, leftX, rowY, colW, "Качество солнечных 3D-теней", settings.shadowMode.label, () -> {
                    int n = (settings.shadowMode.ordinal() + 1) % GameSettings.ShadowMode.values().length;
                    settings.shadowMode = GameSettings.ShadowMode.values()[n];
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Мягкое затенение углов (Gouraud AO)",
                        settings.ambientOcclusion ? "Включено (Глубина)" : "Выкл (Киберспорт FPS+)", () -> {
                    settings.ambientOcclusion = !settings.ambientOcclusion;
                    settings.save();
                });
                rowY += cardStepY;

                drawCompactSettingCard(g, leftX, rowY, colW, "Цифровая насыщенность (Vibrance)", settings.colorVibrance.label, () -> {
                    int n = (settings.colorVibrance.ordinal() + 1) % GameSettings.ColorVibranceMode.values().length;
                    settings.colorVibrance = GameSettings.ColorVibranceMode.values()[n];
                    settings.save();
                });
                drawCompactPlusMinusCard(g, rightX, rowY, colW, "Яркость / Гамма экспозиции", settings.brightnessPct + "%",
                        () -> { settings.brightnessPct = Math.max(80, settings.brightnessPct - 5); settings.save(); },
                        () -> { settings.brightnessPct = Math.min(135, settings.brightnessPct + 5); settings.save(); });
                rowY += cardStepY;

                drawCompactSettingCard(g, leftX, rowY, colW, "Атмосферная дымка горизонта (Fog)",
                        settings.fogEnabled ? "Включена" : "Выкл (Чистый обзор)", () -> {
                    settings.fogEnabled = !settings.fogEnabled;
                    settings.syncDerivedFlags();
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Кинематографическая виньетка",
                        settings.vignetteEnabled ? "Включена (Post-FX)" : "Выключена", () -> {
                    settings.vignetteEnabled = !settings.vignetteEnabled;
                    settings.syncDerivedFlags();
                    settings.save();
                });
                rowY += cardStepY;

                drawCompactSettingCard(g, leftX, rowY, colW, "3D-Облака и солнечная корона",
                        settings.skyCloudsAndSunFlare ? "Включены (Deferred Sky)" : "Выкл (Чистый градиент)", () -> {
                    settings.skyCloudsAndSunFlare = !settings.skyCloudsAndSunFlare;
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Плотность искр, дыма и частиц", settings.particleDensity.label, () -> {
                    int n = (settings.particleDensity.ordinal() + 1) % GameSettings.ParticleDensityMode.values().length;
                    settings.particleDensity = GameSettings.ParticleDensityMode.values()[n];
                    settings.save();
                });
                rowY += cardStepY;

                drawCompactSettingCard(g, leftX, rowY, colW, "Пробоины от пуль и 3D-гильзы",
                        settings.decalsAndShells ? "Включены" : "Выключены (FPS+)", () -> {
                    settings.decalsAndShells = !settings.decalsAndShells;
                    if (!settings.decalsAndShells) { decals.clear(); shells.clear(); }
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Время суток и свет солнца", settings.timeOfDay.label, () -> {
                    int n = (settings.timeOfDay.ordinal() + 1) % GameSettings.TimeOfDay.values().length;
                    settings.timeOfDay = GameSettings.TimeOfDay.values()[n];
                    renderer.applyTimeOfDay(settings.timeOfDay);
                    settings.save();
                });
                rowY += cardStepY;

                drawCompactSettingCard(g, leftX, rowY, colW, "Динамическая 3D-погода арены", settings.weatherMode.label, () -> {
                    int n = (settings.weatherMode.ordinal() + 1) % GameSettings.WeatherMode.values().length;
                    settings.weatherMode = GameSettings.WeatherMode.values()[n];
                    settings.save();
                });
                drawCompactSettingCard(g, rightX, rowY, colW, "Оверлей телеметрии и графика мс", settings.telemetryMode.label, () -> {
                    int n = (settings.telemetryMode.ordinal() + 1) % GameSettings.HudTelemetryMode.values().length;
                    settings.telemetryMode = GameSettings.HudTelemetryMode.values()[n];
                    settings.syncDerivedFlags();
                    settings.save();
                });
                rowY += cardStepY;
            }

            // ЖИВОЙ ОСЦИЛЛОГРАФ ПРОИЗВОДИТЕЛЬНОСТИ И СТАТИСТИКА HYPER-TURBO 7.0 ВНИЗУ СТУДИИ!
            drawStudioTelemetryMonitor(g, listX, rowY + 4, listW, 88);

        } else if (settingsTab == 1) {
            rowY += 6;
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
            rowY += 44;

            drawOptionStepperRow(g, listX, rowY, listW, "Цвет прицела CS2", settings.crosshairColor.label, () -> {
                int next = (settings.crosshairColor.ordinal() + 1) % GameSettings.CrosshairColor.values().length;
                settings.crosshairColor = GameSettings.CrosshairColor.values()[next];
                settings.save();
            });
            rowY += 44;

            drawOptionStepperRow(g, listX, rowY, listW, "Поведение прицела при стрельбе",
                    settings.dynamicCrosshair ? "Динамический (расширяется)" : "Статический (Киберспорт)", () -> {
                settings.dynamicCrosshair = !settings.dynamicCrosshair;
                settings.save();
            });
            rowY += 44;

            drawOptionStepperRow(g, listX, rowY, listW, "Ведущая рука с оружием (cl_righthand)",
                    settings.leftHandedModel ? "Левая рука" : "Правая рука", () -> {
                settings.leftHandedModel = !settings.leftHandedModel;
                settings.save();
            });
            rowY += 44;

            drawOptionStepperRow(g, listX, rowY, listW, "Всплывающие цифры урона при попадании",
                    settings.showDamageNumbers ? "Показывать" : "Скрыть", () -> {
                settings.showDamageNumbers = !settings.showDamageNumbers;
                settings.save();
            });
            rowY += 44;

            drawOptionStepperRow(g, listX, rowY, listW, "Сложность ИИ ботов", settings.botDifficulty.label, () -> {
                int next = (settings.botDifficulty.ordinal() + 1) % GameSettings.BotDifficulty.values().length;
                settings.botDifficulty = GameSettings.BotDifficulty.values()[next];
                settings.save();
            });
            rowY += 44;

            drawOptionStepperRow(g, listX, rowY, listW, "Полноэкранный режим окна (F11)",
                    settings.fullscreen ? "Полный экран" : "Оконный режим", this::toggleFullscreenMode);

        } else {
            rowY += 10;
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
            rowY += 52;

            drawOptionStepperRow(g, listX, rowY, listW, "Звук шагов и звон гильз",
                    settings.footstepSounds ? "Включено" : "Выключено", () -> {
                settings.footstepSounds = !settings.footstepSounds;
                settings.save();
            });
            rowY += 50;

            drawOptionStepperRow(g, listX, rowY, listW, "Режим HUD-телеметрии FPS и графика времени кадра",
                    settings.telemetryMode.label, () -> {
                int next = (settings.telemetryMode.ordinal() + 1) % GameSettings.HudTelemetryMode.values().length;
                settings.telemetryMode = GameSettings.HudTelemetryMode.values()[next];
                settings.syncDerivedFlags();
                settings.save();
            });
            rowY += 56;

            drawStudioTelemetryMonitor(g, listX, rowY, listW, 96);
            rowY += 112;

            addMenuButton(g, new Rectangle(listX, rowY, 340, 42), "♪  ПРОВЕРИТЬ ЗВУК ВЫСТРЕЛА AK-47",
                    new Color(48, 118, 178), false, () -> soundSystem.playWeaponShot(Weapon.Type.AK47));
        }

        String backLabel = settingsOpenedFromGame ? "← СОХРАНИТЬ И ВЕРНУТЬСЯ В БОЙ (ESC)" : "← СОХРАНИТЬ И В ГЛАВНОЕ МЕНЮ (ESC)";
        addMenuButton(g, new Rectangle(px + pw / 2 - 205, py + ph - 50, 410, 38),
                backLabel, new Color(48, 154, 96), false, this::closeSettingsScreen);
    }

    private void drawCompactSettingCard(Graphics2D g, int x, int y, int w, String title, String valueLabel, Runnable onToggle) {
        Rectangle rect = new Rectangle(x, y, w, 35);
        boolean hovered = rect.contains(mouseClientX, mouseClientY);
        g.setColor(hovered ? new Color(34, 48, 68, 228) : new Color(21, 29, 43, 212));
        g.fillRoundRect(x, y, w, 35, 8, 8);
        g.setColor(hovered ? new Color(248, 198, 76, 185) : new Color(255, 255, 255, 34));
        g.drawRoundRect(x, y, w, 35, 8, 8);

        g.setFont(uiFont("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(224, 232, 244));
        g.drawString(title, x + 11, y + 22);

        String pill = "◄ " + valueLabel + " ►";
        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        int pw = g.getFontMetrics().stringWidth(pill) + 14;
        int px = x + w - pw - 7;
        g.setColor(new Color(44, 66, 96));
        g.fillRoundRect(px, y + 6, pw, 23, 6, 6);
        g.setColor(new Color(252, 210, 92));
        g.drawString(pill, px + 7, y + 21);

        uiRegisterClickable(rect, onToggle);
    }

    private void drawCompactPlusMinusCard(Graphics2D g, int x, int y, int w, String title, String valueLabel,
                                          Runnable onMinus, Runnable onPlus) {
        g.setColor(new Color(21, 29, 43, 212));
        g.fillRoundRect(x, y, w, 35, 8, 8);
        g.setColor(new Color(255, 255, 255, 34));
        g.drawRoundRect(x, y, w, 35, 8, 8);

        g.setFont(uiFont("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(224, 232, 244));
        g.drawString(title, x + 11, y + 22);

        Rectangle minusR = new Rectangle(x + w - 142, y + 5, 32, 25);
        Rectangle plusR  = new Rectangle(x + w - 39,  y + 5, 32, 25);
        addMenuButton(g, minusR, "−", new Color(50, 68, 92), false, onMinus);
        drawCenteredText(g, valueLabel, x + w - 74, y + 22, uiFont("Monospaced", Font.BOLD, 13), new Color(252, 210, 92));
        addMenuButton(g, plusR, "+", new Color(50, 68, 92), false, onPlus);
    }

    /**
     * Панель живой телеметрии и осциллограф времени кадра (мс) внутри Студии Графики 7.0!
     */
    private void drawStudioTelemetryMonitor(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(10, 16, 26, 232));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(new Color(88, 192, 255, 110));
        g.drawRoundRect(x, y, w, h, 10, 10);

        int totalPixels = Math.max(1, renderer.getWidth() * renderer.getHeight());
        int skySavedPct = Math.max(0, Math.min(99, 100 - (renderer.getLastFrameSkyPixelsShaded() * 100 / totalPixels)));

        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(118, 238, 174));
        g.drawString(String.format("● РЕАЛЬНОЕ ВРЕМЯ: %d FPS  (%.2f мс/кадр  •  1%% Low: %d FPS)",
                currentFps, lastFrameMs, onePercentLowFps), x + 14, y + 22);

        g.setFont(uiFont("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(208, 222, 240));
        g.drawString(String.format("3D Буфер: %d×%d (%d%%)  •  Треугольников: %d  •  Frustum Culling: %d",
                renderer.getWidth(), renderer.getHeight(),
                (int) Math.round(dynamicResScaleFactor * 100),
                renderer.getLastFrameRenderedTris(),
                renderer.getLastFrameCulledObjects()), x + 14, y + 44);

        g.setColor(new Color(168, 198, 232));
        g.drawString(String.format("Deferred Sky экономия: %d%% экрана  •  AA: %s  •  CAS: %s  •  Потоков: %d",
                skySavedPct, settings.antiAliasingMode.name(), settings.casSharpening.name(),
                settings.multiCoreRendering ? renderer.getWorkerThreads() : 1), x + 14, y + 66);

        int gx = x + w - 316;
        int gy = y + 10;
        int gw = 302;
        int gh = h - 20;
        drawFrameTimeGraphBox(g, gx, gy, gw, gh, true);
    }

    private static final Color COL_GRAPH_BG = new Color(6, 10, 18, 215);
    private static final Color COL_GRAPH_BORDER = new Color(72, 134, 188, 95);
    private static final Color COL_GRAPH_60LINE = new Color(92, 224, 148, 82);
    private static final Color COL_FPS_GOOD = new Color(84, 240, 152);
    private static final Color COL_FPS_WARN = new Color(252, 204, 78);
    private static final Color COL_FPS_BAD = new Color(255, 96, 78);

    private void drawFrameTimeGraphBox(Graphics2D g, int gx, int gy, int gw, int gh, boolean detailedLabels) {
        g.setColor(COL_GRAPH_BG);
        g.fillRect(gx, gy, gw, gh);
        g.setColor(COL_GRAPH_BORDER);
        g.drawRect(gx, gy, gw, gh);

        // Опорная линия 16.7 мс (60 FPS)
        int y60 = gy + gh - Math.min(gh - 4, (int) ((16.67 / 40.0) * (gh - 8))) - 3;
        g.setColor(COL_GRAPH_60LINE);
        g.fillRect(gx + 3, y60, gw - 6, 1);

        int n = frameMsHistory.length;
        int cur = frameMsCursor;
        int innerW = Math.max(1, gw - 8);
        int barW = Math.max(1, innerW / n);
        Color lastCol = null;
        for (int i = 0; i < n; i++) {
            float ms = frameMsHistory[(cur + i) % n];
            int sx = gx + 4 + (i * innerW) / n;
            int barH = Math.max(2, Math.min(gh - 7, (int) ((ms / 40.0f) * (gh - 7))));
            int sy = gy + gh - 2 - barH;
            Color c = (ms <= 17.5f) ? COL_FPS_GOOD : ((ms <= 28.0f) ? COL_FPS_WARN : COL_FPS_BAD);
            if (c != lastCol) {
                g.setColor(c);
                lastCol = c;
            }
            g.fillRect(sx, sy, barW, Math.max(2, Math.min(3, barH)));
        }

        if (detailedLabels) {
            g.setFont(uiFont("Monospaced", Font.BOLD, 10));
            g.setColor(new Color(148, 228, 180));
            g.drawString("60FPS (16.7мс)", gx + 7, gy + 13);
        }
    }

    // =========================================================================
    //  3. СПРАВОЧНИК АРСЕНАЛА И ТАКТИКИ (CS-2 JAVA 7.0)
    // =========================================================================
    private void drawArsenalGuideScreen(Graphics2D g, int outW, int outH) {
        g.setColor(new Color(8, 12, 18, 204));
        g.fillRect(0, 0, outW, outH);

        int pw = Math.min(920, outW - 56);
        int ph = Math.min(600, outH - 52);
        int px = outW / 2 - pw / 2;
        int py = outH / 2 - ph / 2;

        g.setPaint(new GradientPaint(px, py, new Color(13, 19, 30, 246), px, py + ph, new Color(17, 25, 40, 246)));
        g.fillRoundRect(px, py, pw, ph, 18, 18);
        g.setColor(new Color(245, 194, 72, 175));
        g.drawRoundRect(px, py, pw, ph, 18, 18);

        drawCenteredText(g, "ТАКТИЧЕСКИЙ СПРАВОЧНИК И НОВИНКИ CS-2 JAVA 7.0", outW / 2, py + 42,
                uiFont("SansSerif", Font.BOLD, 22), new Color(252, 208, 88));

        String[] leftLines = {
            "БОЕВОЕ УПРАВЛЕНИЕ И СПЕЦСРЕДСТВА CT:",
            "• WASD / Space / Ctrl / Shift — Передвижение и тихий шаг",
            "• Alt — Тактический рывок (Dash) с импульсом скорости!",
            "• U — Точечный Авиаудар Спецназа (залп 3 ракет с неба)!",
            "• J — Развернуть 3D Авто-Турель • K — Вызвать Вертолёт-Босс",
            "• B — 3-колоночный Магазин CS2 • F — Осмотр (Керамбит 720°!)",
            "• H — Мгновенная смена скина • L — 3D-Фонарик • Z — Радио",
            "• 4/G: HE • 5/Q: Дым • 6/V: Флешка • 7/X: Молотов • T: Zeus",
            "• E — Обезвреживание C4 • Y — Цветное 3D-граффити",
            "• Пробел (после гибели) — Ускорить раунд ботов CT"
        };

        String[] rightLines = {
            "НОВИНКИ HYPER-TURBO 7.0 (ОПТИМИЗАЦИЯ И ГРАФИКА):",
            "• O(1) Аналитический растеризатор + Early-Z отсечение спанов!",
            "• Deferred Skybox: небо рисуется только там, где нет стен/пола",
            "• Авто-LOD 3D-сфер/цилиндров + отсечение тыльных граней",
            "• Студия Графики: 24 параметра + 5 пресетов (Киберспорт..Ультра)",
            "• Авто-FPS (Динамическое 3D-разрешение 60/120 FPS) + AMD CAS",
            "• Насыщенность Digital Vibrance + Гамма LUT + Живой график мс!",
            "",
            "СИСТЕМНЫЕ КЛАВИШИ:",
            "• Tab — Счет команд • F5 — Рестарт • F11 — Полный экран"
        };

        int ly = py + 86;
        for (int i = 0; i < leftLines.length; i++) {
            g.setFont(uiFont("SansSerif", i == 0 ? Font.BOLD : Font.PLAIN, i == 0 ? 15 : 13));
            g.setColor(i == 0 ? new Color(102, 208, 255) : new Color(224, 232, 244));
            g.drawString(leftLines[i], px + 34, ly);
            ly += 33;
        }

        int ry = py + 86;
        for (int i = 0; i < rightLines.length; i++) {
            boolean header = i == 0 || i == 8;
            g.setFont(uiFont("SansSerif", header ? Font.BOLD : Font.PLAIN, header ? 15 : 13));
            g.setColor(header ? new Color(252, 206, 84) : new Color(224, 232, 244));
            g.drawString(rightLines[i], px + pw / 2 + 16, ry);
            ry += 33;
        }

        addMenuButton(g, new Rectangle(px + pw / 2 - 165, py + ph - 58, 330, 44),
                "← ВЕРНУТЬСЯ В ГЛАВНОЕ МЕНЮ", new Color(48, 154, 96), false,
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

        if (streakBannerTimer > 0.0 && !streakBannerText.isEmpty()) {
            g.setFont(uiFont("SansSerif", Font.BOLD, 16));
            int sbW = g.getFontMetrics().stringWidth(streakBannerText) + 36;
            int sbX = outW / 2 - sbW / 2;
            int sbY = outH - 190;
            g.setColor(new Color(11, 17, 28, 222));
            g.fillRoundRect(sbX, sbY, sbW, 34, 10, 10);
            g.setColor(new Color(252, 204, 78, 190));
            g.drawRoundRect(sbX, sbY, sbW, 34, 10, 10);
            drawCenteredText(g, streakBannerText, outW / 2, sbY + 23,
                    uiFont("SansSerif", Font.BOLD, 16), new Color(255, 220, 92));
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

        g.setFont(uiFont("Monospaced", Font.BOLD, 13));
        g.setColor(new Color(120, 230, 170));
        g.drawString("AWP OPTICS • ZOOM " + (zoomLevel == 1 ? "2.8X" : "6.0X") + " (ПКМ)", cx + 22, cy + radius - 26);
    }

    private void drawBuyMenuOverlay(Graphics2D g, int outW, int outH) {
        g.setColor(new Color(7, 11, 18, 204));
        g.fillRect(0, 0, outW, outH);

        int bw = Math.min(1040, outW - 44);
        int bh = Math.min(548, outH - 48);
        int bx = outW / 2 - bw / 2;
        int by = outH / 2 - bh / 2;

        g.setPaint(new GradientPaint(bx, by, new Color(12, 18, 30, 246), bx, by + bh, new Color(16, 24, 38, 246)));
        g.fillRoundRect(bx, by, bw, bh, 18, 18);
        g.setColor(new Color(92, 232, 148, 180));
        g.drawRoundRect(bx, by, bw, bh, 18, 18);

        g.setFont(uiFont("SansSerif", Font.BOLD, 22));
        g.setColor(Color.WHITE);
        g.drawString("МАГАЗИН ВООРУЖЕНИЯ СПЕЦНАЗА CS2", bx + 26, by + 38);

        boolean freeDm = (settings.gameMode == GameSettings.GameMode.DEATHMATCH);
        String cashStr = freeDm ? "РЕЖИМ DM: $ БЕСПЛАТНО" : ("БАЛАНС: $ " + player.getMoney());
        g.setFont(uiFont("Monospaced", Font.BOLD, 20));
        int cw = g.getFontMetrics().stringWidth(cashStr) + 28;
        g.setColor(new Color(22, 46, 34, 225));
        g.fillRoundRect(bx + bw - cw - 24, by + 13, cw, 34, 10, 10);
        g.setColor(new Color(96, 242, 142));
        g.drawRoundRect(bx + bw - cw - 24, by + 13, cw, 34, 10, 10);
        g.drawString(cashStr, bx + bw - cw - 10, by + 36);

        // 3 КОЛОНКИ КАТЕГОРИЙ CS2 PANORAMA: ВИНТОВКИ | ТЯЖЁЛОЕ ОРУЖИЕ | СНАРЯЖЕНИЕ И ПОДДЕРЖКА
        int colGap = 16;
        int colW = (bw - 52 - colGap * 2) / 3;
        int colY = by + 62;

        // Колонка 1: Винтовки и Пистолеты
        int c1X = bx + 26;
        drawBuyColumnHeader(g, c1X, colY, colW, "ВИНТОВКИ И ТОЧНОСТЬ", new Color(76, 178, 255));
        int y1 = colY + 36;
        drawBuyCard(g, c1X, y1, colW, "1", "AK-47", "Штурмовая • 142 ур. в голову (Ваншот!)", freeDm ? 0 : 2700, new Color(76, 178, 255),
                () -> tryPurchaseWeapon(Weapon.Type.AK47)); y1 += 88;
        drawBuyCard(g, c1X, y1, colW, "2", "M4A1-S", "С глушителем • Кучная стрельба CT", freeDm ? 0 : 2900, new Color(76, 178, 255),
                () -> tryPurchaseWeapon(Weapon.Type.M4A1S)); y1 += 88;
        drawBuyCard(g, c1X, y1, colW, "3", "AWP Снайперская", "Оптика 2.8x/6x ПКМ • Урон 115/440", freeDm ? 0 : 4750, new Color(188, 118, 255),
                () -> tryPurchaseWeapon(Weapon.Type.AWP)); y1 += 88;
        drawBuyCard(g, c1X, y1, colW, "7", "Desert Eagle .50", "Крупный калибр • 168 ур. в голову", freeDm ? 0 : 700, new Color(76, 178, 255),
                () -> tryPurchaseWeapon(Weapon.Type.DEAGLE));

        // Колонка 2: Тяжёлое оружие и RPG
        int c2X = c1X + colW + colGap;
        drawBuyColumnHeader(g, c2X, colY, colW, "ТЯЖЁЛОЕ И ОСАДНОЕ", new Color(248, 158, 56));
        int y2 = colY + 36;
        drawBuyCard(g, c2X, y2, colW, "4", "M249 Para (Пулемёт)", "Короб 100 патронов • Подавление волн!", freeDm ? 0 : 5200, new Color(248, 158, 56),
                () -> tryPurchaseWeapon(Weapon.Type.M249)); y2 += 88;
        drawBuyCard(g, c2X, y2, colW, "5", "RPG-7 Гранатомёт", "3D-ракеты против Вертолёта и толп!", freeDm ? 0 : 4500, new Color(252, 92, 68),
                () -> tryPurchaseWeapon(Weapon.Type.RPG7)); y2 += 88;
        drawBuyCard(g, c2X, y2, colW, "6", "XM1014 Дробовик", "Автоматический • 8 картечин за залп", freeDm ? 0 : 2000, new Color(248, 158, 56),
                () -> tryPurchaseWeapon(Weapon.Type.XM1014)); y2 += 88;
        drawBuyCard(g, c2X, y2, colW, "M", "MP9 Тактический ПП", "Скорострельность 850 в/мин • $600/фраг", freeDm ? 0 : 1250, new Color(248, 158, 56),
                () -> tryPurchaseWeapon(Weapon.Type.MP9));

        // Колонка 3: Гранаты и Спецсредства CT
        int c3X = c2X + colW + colGap;
        drawBuyColumnHeader(g, c3X, colY, colW, "ГРАНАТЫ И ПОДДЕРЖКА CT", new Color(88, 234, 152));
        int y3 = colY + 36;
        drawBuyCard(g, c3X, y3, colW, "8", "Полный пояс гранат", "HE [G] + Дым [Q] + Флеш [V] + Молотов [X]", freeDm ? 0 : 500, new Color(88, 234, 152),
                () -> { tryPurchaseUtility(1); tryPurchaseUtility(2); }); y3 += 88;
        drawBuyCard(g, c3X, y3, colW, "9", "Броня 100 AP + Zeus", "Полное ХП/Броня + Набор C4 + Шокер [T]", freeDm ? 0 : 850, new Color(88, 234, 152),
                () -> tryPurchaseUtility(3)); y3 += 88;
        drawBuyCard(g, c3X, y3, colW, "0", "Турель + Авиаудар", "Полевая 3D-Турель [J] + Залп 3 ракет [U]!", freeDm ? 0 : 1200, new Color(96, 224, 255),
                () -> tryPurchaseUtility(4)); y3 += 88;

        // Сводка текущего снаряжения внизу 3-й колонки
        g.setColor(new Color(20, 30, 46, 210));
        g.fillRoundRect(c3X, y3, colW, 76, 10, 10);
        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(148, 224, 255));
        g.drawString("ВАШ ЗАПАС СПЕЦСРЕДСТВ:", c3X + 14, y3 + 24);
        g.setFont(uiFont("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(224, 232, 244));
        g.drawString("Авто-Турелей [J]: " + player.getSentryCharges() + "   •   Авиаударов [U]: " + player.getAirstrikeCharges(), c3X + 14, y3 + 46);
        g.drawString("Сапёрный набор C4: " + (player.hasDefuseKit() ? "ЕСТЬ (5с)" : "НЕТ") + "  •  Рывок [Alt]: ГОТОВ", c3X + 14, y3 + 65);

        addMenuButton(g, new Rectangle(bx + bw / 2 - 155, by + bh - 50, 310, 38),
                "ВЕРНУТЬСЯ В БОЙ (B / ESC)", new Color(46, 138, 92), false, () -> {
            buyMenuOpen = false;
            setMouseCaptured(true);
        });
    }

    private void drawBuyColumnHeader(Graphics2D g, int x, int y, int w, String title, Color accent) {
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 36));
        g.fillRoundRect(x, y, w, 28, 8, 8);
        g.setColor(accent);
        g.fillRoundRect(x, y, 5, 28, 4, 4);
        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        g.drawString(title, x + 14, y + 19);
    }

    private void drawBuyCard(Graphics2D g, int x, int y, int w, String keyBadge, String name, String desc,
                             int cost, Color accent, Runnable onBuy) {
        Rectangle rect = new Rectangle(x, y, w, 78);
        boolean hovered = rect.contains(mouseClientX, mouseClientY);
        boolean canAfford = player.getMoney() >= cost;

        g.setColor(hovered ? new Color(36, 52, 76, 238) : new Color(22, 31, 46, 224));
        g.fillRoundRect(x, y, w, 78, 10, 10);
        g.setColor(accent);
        g.fillRoundRect(x, y + 8, 4, 62, 4, 4);
        g.setColor(hovered ? new Color(252, 206, 86) : new Color(255, 255, 255, 38));
        g.drawRoundRect(x, y, w, 78, 10, 10);

        // Клавиша быстрого вызова
        g.setColor(new Color(38, 52, 72));
        g.fillRoundRect(x + 12, y + 12, 24, 24, 6, 6);
        drawCenteredText(g, keyBadge, x + 24, y + 29, uiFont("Monospaced", Font.BOLD, 13), new Color(252, 210, 92));

        g.setFont(uiFont("SansSerif", Font.BOLD, 15));
        g.setColor(canAfford ? Color.WHITE : new Color(148, 156, 170));
        g.drawString(name, x + 44, y + 29);

        String priceTag = (cost == 0) ? "FREE" : ("$" + cost);
        g.setFont(uiFont("Monospaced", Font.BOLD, 15));
        g.setColor(canAfford ? new Color(96, 242, 140) : new Color(242, 88, 82));
        g.drawString(priceTag, x + w - g.getFontMetrics().stringWidth(priceTag) - 14, y + 29);

        g.setFont(uiFont("SansSerif", Font.PLAIN, 11));
        g.setColor(new Color(176, 192, 214));
        g.drawString(desc, x + 14, y + 58);

        uiRegisterClickable(rect, onBuy);
    }

    private void drawBotFloatingBarsAndDamagePopups(Graphics2D g, int outW, int outH) {
        double[] proj = new double[3];
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookDirection();
        double scaleX = (double) outW / renderer.getWidth();
        double scaleY = (double) outH / renderer.getHeight();

        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (!b.isAlive()) continue;

            Vec3 headTop = b.getPosition().add(0.0, 2.02, 0.0);
            Vec3 toBot = b.getPosition().add(0.0, 1.2, 0.0).sub(eye);
            double dist = toBot.length();
            boolean aimedAt = dist > 0.1 && look.dot(toBot.scale(1.0 / dist)) > 0.985;

            boolean ally = b.isAlly();
            if (!ally && !b.shouldShowHealthBar() && !aimedAt) continue;
            if (!ally && !map.hasLineOfSight(eye, b.getEyePosition(), smokeClouds)) continue;

            if (renderer.projectToScreen(headTop.x, headTop.y + (b.getArchetype() == Bot.Archetype.JUGGERNAUT ? 0.45 : 0.0), headTop.z, proj)) {
                int sx = (int) (proj[0] * scaleX);
                int sy = (int) (proj[1] * scaleY);
                int barW = Math.max(38, Math.min(92, (int) ((b.getArchetype() == Bot.Archetype.JUGGERNAUT ? 560.0 : 420.0) / Math.max(3.5, proj[2]))));
                int barH = b.getArchetype() == Bot.Archetype.JUGGERNAUT ? 8 : 6;
                int bx = sx - barW / 2;
                int by = sy - barH / 2;

                g.setColor(new Color(15, 18, 22, 195));
                g.fillRoundRect(bx - 2, by - 2, barW + 4, barH + 4, 4, 4);
                if (ally) {
                    g.setColor(new Color(64, 188, 255));
                } else if (b.getArchetype() == Bot.Archetype.JUGGERNAUT) {
                    g.setColor(new Color(255, 62, 48));
                } else {
                    g.setColor(b.getHealth() > 45 ? new Color(235, 72, 65) : new Color(255, 165, 45));
                }
                g.fillRect(bx, by, (int) ((barW * (double) b.getHealth()) / Math.max(1, b.getMaxHealth())), barH);

                if (ally) {
                    String allyTag = "▲ " + b.getName() + " (" + b.getHealth() + " HP)";
                    FontMetrics fm = g.getFontMetrics();
                    g.setColor(new Color(128, 224, 255));
                    g.drawString(allyTag, sx - fm.stringWidth(allyTag) / 2, by - 5);
                } else if (b.isBlinded()) {
                    drawCenteredText(g, "★ ОСЛЕПЛЁН ★", sx, by - 6,
                            uiFont("SansSerif", Font.BOLD, 11), new Color(255, 245, 150));
                } else if (aimedAt || b.isCarryingC4() || b.getArchetype() == Bot.Archetype.JUGGERNAUT) {
                    String tag = (b.getArchetype() == Bot.Archetype.JUGGERNAUT ? "☠ " : "")
                            + b.getName() + " (" + b.getHealth() + " HP)" + (b.isCarryingC4() ? " [C4]" : "");
                    FontMetrics fm = g.getFontMetrics();
                    g.setColor(b.isCarryingC4() ? new Color(255, 212, 92) : new Color(255, 220, 210));
                    g.drawString(tag, sx - fm.stringWidth(tag) / 2, by - 5);
                }
            }
        }

        // 3D-маркер заложенной бомбы C4 на пленте
        if (plantedC4 != null && renderer.projectToScreen(plantedC4.pos.x, plantedC4.pos.y + 0.55, plantedC4.pos.z, proj)) {
            int sx = (int) (proj[0] * scaleX);
            int sy = (int) (proj[1] * scaleY);
            drawCenteredText(g, "▼ C4 ПЛЕНТ " + plantedC4.siteName + " (" + String.format("%.1fс", Math.max(0.0, plantedC4.countdown)) + ")",
                    sx, sy, uiFont("SansSerif", Font.BOLD, 13), new Color(255, 92, 82));
        }

        // Всплывающие цифры урона
        if (settings.showDamageNumbers) {
            g.setFont(uiFont("SansSerif", Font.BOLD, 15));
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

        // Светошумовая вспышка от Flashbang
        double blindT = player.getFlashBlindTimer();
        if (blindT > 0.01) {
            int flashAlpha = Math.max(0, Math.min(242, (int) (blindT * 185.0)));
            g.setColor(new Color(255, 253, 246, flashAlpha));
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
                    uiFont("SansSerif", Font.BOLD, 12), new Color(255, 235, 175));
        }
    }

    private final int[] hudPolyX4 = new int[4];
    private final int[] hudPolyY4 = new int[4];
    private static final Color COL_HUD_BG_DARK = new Color(13, 19, 30);
    private static final Color COL_HUD_BG_LOWER = new Color(17, 25, 39);
    private static final Color COL_HUD_BORDER = new Color(64, 92, 128);
    private static final Color COL_HUD_CT_WING = new Color(42, 118, 214);
    private static final Color COL_HUD_T_WING = new Color(214, 128, 34);
    private static final Color COL_HUD_CT_PIP = new Color(78, 188, 255);
    private static final Color COL_HUD_T_PIP = new Color(248, 168, 56);
    private static final Color COL_HUD_TRACK = new Color(34, 44, 58);

    private static void drawFastHudPanel(Graphics2D g, int x, int y, int w, int h, Color accentTop) {
        int half = h >> 1;
        g.setColor(COL_HUD_BG_DARK);
        g.fillRect(x, y, w, half);
        g.setColor(COL_HUD_BG_LOWER);
        g.fillRect(x, y + half, w, h - half);
        g.setColor(COL_HUD_BORDER);
        g.drawRect(x, y, w - 1, h - 1);
        if (accentTop != null) {
            g.setColor(accentTop);
            g.fillRect(x + 1, y + 1, w - 2, 2);
        }
    }

    private void drawTacticalHud(Graphics2D g, int outW, int outH) {
        // 1. ВЕРХНИЙ КИБЕРСПОРТИВНЫЙ ТАБЛО-ХЕДЕР PANORAMA 7.0 (0 аллокаций GradientPaint/Polygon!)
        int topW = 468, topH = 60;
        int topX = outW / 2 - topW / 2, topY = 12;

        drawFastHudPanel(g, topX, topY, topW, topH, COL_HUD_CT_PIP);

        // Левое скошенное крыло CT
        hudPolyX4[0] = topX + 8;  hudPolyX4[1] = topX + 104; hudPolyX4[2] = topX + 92; hudPolyX4[3] = topX + 8;
        hudPolyY4[0] = topY + 8;  hudPolyY4[1] = topY + 8;   hudPolyY4[2] = topY + 52; hudPolyY4[3] = topY + 52;
        g.setColor(COL_HUD_CT_WING);
        g.fillPolygon(hudPolyX4, hudPolyY4, 4);
        g.setColor(Color.WHITE);
        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        g.drawString("CT", topX + 18, topY + 25);
        g.setFont(uiFont("SansSerif", Font.BOLD, 25));
        g.drawString(String.valueOf(playerRoundWins), topX + 56, topY + 41);

        // Правое скошенное крыло T
        hudPolyX4[0] = topX + topW - 104; hudPolyX4[1] = topX + topW - 8;
        hudPolyX4[2] = topX + topW - 8;   hudPolyX4[3] = topX + topW - 92;
        g.setColor(COL_HUD_T_WING);
        g.fillPolygon(hudPolyX4, hudPolyY4, 4);
        g.setColor(Color.WHITE);
        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        g.drawString("TERR", topX + topW - 48, topY + 25);
        g.setFont(uiFont("SansSerif", Font.BOLD, 25));
        g.drawString(String.valueOf(botRoundWins), topX + topW - 82, topY + 41);

        // Ромбики живых бойцов слева (CT) и справа (T) от табло!
        int ctAlive = (player.isAlive() ? 1 : 0) + countAliveAllies();
        int tAlive = countAliveEnemies();
        g.setColor(COL_HUD_CT_PIP);
        for (int i = 0; i < Math.min(5, ctAlive); i++) {
            int px = topX - 14 - i * 15, py = topY + 30;
            hudPolyX4[0] = px; hudPolyX4[1] = px + 6; hudPolyX4[2] = px; hudPolyX4[3] = px - 6;
            hudPolyY4[0] = py - 6; hudPolyY4[1] = py; hudPolyY4[2] = py + 6; hudPolyY4[3] = py;
            g.fillPolygon(hudPolyX4, hudPolyY4, 4);
        }
        g.setColor(COL_HUD_T_PIP);
        for (int i = 0; i < Math.min(8, tAlive); i++) {
            int px = topX + topW + 14 + i * 15, py = topY + 30;
            hudPolyX4[0] = px; hudPolyX4[1] = px + 6; hudPolyX4[2] = px; hudPolyX4[3] = px - 6;
            hudPolyY4[0] = py - 6; hudPolyY4[1] = py; hudPolyY4[2] = py + 6; hudPolyY4[3] = py;
            g.fillPolygon(hudPolyX4, hudPolyY4, 4);
        }

        if (plantedC4 != null) {
            boolean blink = Math.sin(plantedC4.blinkPhase) > 0.0;
            String c4Str = String.format("C4: %04.1f", Math.max(0.0, plantedC4.countdown));
            drawCenteredText(g, c4Str, outW / 2, topY + 31,
                    uiFont("Monospaced", Font.BOLD, 22), blink ? new Color(255, 76, 68) : new Color(255, 206, 92));
            String subStr = "⚠ ПЛЕНТ " + plantedC4.siteName + " ЗАМИНИРОВАН • ЗАЖМИТЕ [E]";
            drawCenteredText(g, subStr, outW / 2, topY + 50,
                    uiFont("SansSerif", Font.BOLD, 10), new Color(255, 185, 175));
        } else {
            int totalSec = Math.max(0, (int) Math.ceil(roundTimeLeft));
            String timeStr = String.format("%02d:%02d", totalSec / 60, totalSec % 60);
            drawCenteredText(g, timeStr, outW / 2, topY + 31,
                    uiFont("Monospaced", Font.BOLD, 23), totalSec <= 15 ? new Color(255, 95, 85) : Color.WHITE);

            String subStr;
            if (settings.gameMode == GameSettings.GameMode.ARMS_RACE) {
                int tIdx = player.getArmsRaceTier();
                int totalTiers = Weapon.ARMS_RACE_LADDER.length;
                subStr = "ГОНКА ПУШЕК: " + (tIdx + 1) + "/" + totalTiers
                        + (tIdx == totalTiers - 1 ? " (ЗОЛОТОЙ КЕРАМБИТ!)" : " • ФРАГОВ: " + player.getArmsRaceTierKills() + "/2");
            } else if (settings.gameMode == GameSettings.GameMode.SIEGE_WAVES) {
                subStr = "ОСАДА: ВОЛНА " + siegeWave + "/" + MAX_SIEGE_WAVES
                        + " • ВРАГОВ: " + tAlive
                        + ((bossHelicopter != null && bossHelicopter.isAlive()) ? " + ВЕРТОЛЁТ!" : "");
            } else if (settings.gameMode == GameSettings.GameMode.DEATHMATCH) {
                subStr = "БОЙ НАСМЕРТЬ • ФРАГИ: " + player.getKills() + "/25";
            } else {
                subStr = "РАУНД " + currentRound + " • CT " + ctAlive + " VS " + tAlive + " T";
            }
            drawCenteredText(g, subStr, outW / 2, topY + 50,
                    uiFont("SansSerif", Font.BOLD, 11), new Color(198, 214, 234));
        }

        // Верхняя шкала здоровья БОЕВОГО ВЕРТОЛЁТА-БОССА
        if (bossHelicopter != null && bossHelicopter.isAlive()) {
            int hbW = 450, hbH = 30;
            int hbX = outW / 2 - hbW / 2, hbY = topY + topH + 8;
            g.setColor(new Color(22, 12, 16));
            g.fillRect(hbX, hbY, hbW, hbH);
            g.setColor(new Color(255, 86, 62));
            g.drawRect(hbX, hbY, hbW - 1, hbH - 1);

            double frac = Math.max(0.0, Math.min(1.0, (double) bossHelicopter.health / bossHelicopter.maxHealth));
            g.setColor(new Color(236, 62, 44));
            g.fillRect(hbX + 6, hbY + 16, (int) ((hbW - 12) * frac), 9);
            drawCenteredText(g, "▲ БОСС: УДАРНЫЙ БОЕВОЙ ВЕРТОЛЁТ  [" + bossHelicopter.health + " / " + bossHelicopter.maxHealth + " HP] ▲",
                    outW / 2, hbY + 12, uiFont("SansSerif", Font.BOLD, 11), new Color(255, 218, 132));
        }

        // Баннер активной радиокоманды спецназу CT [Z]
        if (radioBannerTimer > 0.0 && !radioBannerText.isEmpty()) {
            int rw = 510, rh = 30;
            int rx = 20, ry = 206;
            drawFastHudPanel(g, rx, ry, rw, rh, COL_HUD_CT_PIP);
            g.setFont(uiFont("SansSerif", Font.BOLD, 12));
            g.setColor(new Color(148, 232, 255));
            g.drawString(radioBannerText, rx + 12, ry + 20);
        }

        // Индикатор обезвреживания C4 по клавише E
        if (plantedC4 != null && player.isAlive() && player.getPosition().dist(plantedC4.pos) < 2.95) {
            int dw = 340, dh = 54;
            int dx = outW / 2 - dw / 2, dy = outH / 2 + 56;
            drawFastHudPanel(g, dx, dy, dw, dh, COL_HUD_CT_PIP);

            if (plantedC4.beingDefused) {
                double p = Math.min(1.0, plantedC4.defuseProgress / plantedC4.defuseRequired);
                drawCenteredText(g, String.format("ОБЕЗВРЕЖИВАНИЕ C4... %.1f с",
                        Math.max(0.0, plantedC4.defuseRequired - plantedC4.defuseProgress)),
                        outW / 2, dy + 22, uiFont("SansSerif", Font.BOLD, 13), new Color(110, 235, 255));
                g.setColor(COL_HUD_TRACK);
                g.fillRect(dx + 20, dy + 32, dw - 40, 11);
                g.setColor(new Color(72, 226, 255));
                g.fillRect(dx + 20, dy + 32, (int) ((dw - 40) * p), 11);
            } else {
                String kitNote = player.hasDefuseKit() ? " (Набор сапёра: 5 сек)" : " (Без набора: 10 сек)";
                drawCenteredText(g, "ЗАЖМИТЕ [E] — ОБЕЗВРЕДИТЬ БОМБУ C4", outW / 2, dy + 24,
                        uiFont("SansSerif", Font.BOLD, 13), new Color(120, 240, 175));
                drawCenteredText(g, "Дистанция в норме" + kitNote, outW / 2, dy + 43,
                        uiFont("SansSerif", Font.PLAIN, 11), new Color(205, 218, 232));
            }
        }

        // 2. БИОМЕТРИЧЕСКАЯ ПАНЕЛЬ ЗДОРОВЬЯ HP, БРОНИ AP, РЫВКА [Alt] И СПЕЦСРЕДСТВ (СНИЗУ СЛЕВА)
        int hpW = 336, hpH = 88;
        int hpX = 20, hpY = outH - hpH - 20;

        // Верхняя плашка валюты $ и тактических зарядов [J] / [U] / [K]
        drawFastHudPanel(g, hpX, hpY - 38, 424, 32, COL_FPS_GOOD);

        g.setFont(uiFont("Monospaced", Font.BOLD, 17));
        g.setColor(new Color(98, 244, 138));
        g.drawString("$ " + player.getMoney(), hpX + 12, hpY - 17);
        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(248, 206, 88));
        String rightBadge = "[B]Маг • [J]Турель:" + player.getSentryCharges()
                + " • [U]Авиаудар:" + player.getAirstrikeCharges() + " • [K]Босс";
        g.drawString(rightBadge, hpX + 94, hpY - 17);

        int hp = player.getHealth();
        Color hpColor = hp > 50 ? COL_FPS_GOOD : (hp > 25 ? COL_FPS_WARN : COL_FPS_BAD);
        drawFastHudPanel(g, hpX, hpY, hpW, hpH, hpColor);

        g.setColor(hpColor);
        g.setFont(uiFont("SansSerif", Font.BOLD, 36));
        g.drawString("+" + hp, hpX + 14, hpY + 44);
        g.setFont(uiFont("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(182, 198, 218));
        g.drawString("ЗДОРОВЬЕ", hpX + 16, hpY + 62);
        String dashReadyTxt = player.getDashReadyProgress() >= 0.999 ? "[Alt] РЫВОК ГОТОВ" : "[Alt] ЗАРЯДКА...";
        g.setColor(player.getDashReadyProgress() >= 0.999 ? new Color(96, 228, 255) : new Color(138, 156, 178));
        g.setFont(uiFont("SansSerif", Font.BOLD, 10));
        g.drawString(dashReadyTxt, hpX + 16, hpY + 78);

        int barStartX = hpX + 132, barWidth = 186;
        g.setColor(COL_HUD_TRACK);
        g.fillRect(barStartX, hpY + 15, barWidth, 10);
        g.setColor(hpColor);
        g.fillRect(barStartX, hpY + 15, (int) (barWidth * (hp / 100.0)), 10);

        int ap = player.getArmor();
        g.setColor(new Color(98, 184, 255));
        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        String stance = player.isCrouching() ? " • ПРИСЕД" : (player.isSilentWalking() ? " • ШАГ" : "");
        g.drawString("БРОНЯ: " + ap + " AP" + stance, barStartX, hpY + 42);
        g.setColor(COL_HUD_TRACK);
        g.fillRect(barStartX, hpY + 48, barWidth, 7);
        g.setColor(new Color(78, 172, 252));
        g.fillRect(barStartX, hpY + 48, (int) (barWidth * (ap / 100.0)), 7);

        // Индикатор перезарядки Тактического рывка [Alt]
        g.setColor(COL_HUD_TRACK);
        g.fillRect(barStartX, hpY + 68, barWidth, 6);
        g.setColor(new Color(72, 228, 255));
        g.fillRect(barStartX, hpY + 68, (int) (barWidth * player.getDashReadyProgress()), 6);

        // 3. ПАНЕЛЬ ОРУЖИЯ, ШКАЛЫ МАГАЗИНА, STATTRAK™ И 4 ГРАНАТ CS2 (СНИЗУ СПРАВА)
        int amW = 356, amH = 132;
        int amX = outW - amW - 20, amY = outH - amH - 20;

        drawFastHudPanel(g, amX, amY, amW, amH, COL_HUD_T_PIP);

        Weapon activeWeapon = player.getCurrentWeapon();
        g.setFont(uiFont("SansSerif", Font.BOLD, 14));
        g.setColor(new Color(252, 206, 84));
        g.drawString(activeWeapon.getDisplayName().toUpperCase(), amX + 16, amY + 23);

        // Цифровой LED-счетчик StatTrak™
        g.setColor(new Color(44, 24, 12));
        g.fillRect(amX + amW - 140, amY + 7, 126, 21);
        g.setColor(new Color(220, 124, 38));
        g.drawRect(amX + amW - 140, amY + 7, 125, 20);
        g.setColor(new Color(255, 146, 44));
        g.setFont(uiFont("Monospaced", Font.BOLD, 11));
        g.drawString(String.format("StatTrak™ %04d", player.getKills()), amX + amW - 132, amY + 21);

        if (activeWeapon.isMelee()) {
            g.setFont(uiFont("SansSerif", Font.BOLD, 20));
            g.setColor(Color.WHITE);
            g.drawString("КЕРАМБИТ • [F] ВРАЩЕНИЕ", amX + 16, amY + 55);
        } else {
            int mag = activeWeapon.getMagAmmo();
            int maxMag = Math.max(1, activeWeapon.getMaxMagAmmo());
            g.setFont(uiFont("Monospaced", Font.BOLD, 34));
            g.setColor(mag <= Math.max(2, maxMag / 4) ? COL_FPS_BAD : Color.WHITE);
            g.drawString(String.format("%2d", mag), amX + 16, amY + 58);

            g.setFont(uiFont("Monospaced", Font.BOLD, 19));
            g.setColor(new Color(175, 190, 210));
            int reserveX = (mag >= 100) ? (amX + 86) : (amX + 68);
            g.drawString("/ " + activeWeapon.getReserveAmmo(), reserveX, amY + 56);

            // Визуальная шкала наполненности магазина
            int mbX = amX + 158, mbW = amW - 174;
            g.setColor(COL_HUD_TRACK);
            g.fillRect(mbX, amY + 38, mbW, 8);
            g.setColor(mag <= Math.max(2, maxMag / 4) ? COL_FPS_BAD : COL_FPS_WARN);
            g.fillRect(mbX, amY + 38, (int) (mbW * Math.min(1.0, (double) mag / maxMag)), 8);

            g.setFont(uiFont("SansSerif", Font.PLAIN, 10));
            g.setColor(new Color(168, 186, 208));
            g.drawString("[ПКМ] Зум  •  [F] Осмотр  •  [H] Скин", mbX, amY + 59);
        }

        // Индивидуальные цветные плашки 4 гранат CS2
        String[] gTags = {"[G] HE:" + player.getHeGrenades(), "[Q] Дым:" + player.getSmokeGrenades(),
                          "[V] Флеш:" + player.getFlashGrenades(), "[X] Огонь:" + player.getMolotovGrenades()};
        int[] gCounts = {player.getHeGrenades(), player.getSmokeGrenades(), player.getFlashGrenades(), player.getMolotovGrenades()};
        int gpW = (amW - 30 - 15) / 4;
        for (int i = 0; i < 4; i++) {
            int gx = amX + 15 + i * (gpW + 5);
            int gy = amY + 69;
            g.setColor(gCounts[i] > 0 ? new Color(30, 46, 66) : new Color(20, 26, 36));
            g.fillRect(gx, gy, gpW, 22);
            drawCenteredText(g, gTags[i], gx + gpW / 2, gy + 15, uiFont("SansSerif", Font.BOLD, 10),
                    gCounts[i] > 0 ? new Color(224, 238, 255) : new Color(118, 130, 146));
        }

        // Вкладки слотов оружия
        Weapon[] allW = player.getWeapons();
        int slotCount = player.hasZeus() ? 4 : 3;
        int slotGap = 5;
        int slotW = (amW - 30 - (slotCount - 1) * slotGap) / slotCount;
        for (int i = 0; i < slotCount; i++) {
            int sx = amX + 15 + i * (slotW + slotGap);
            int sy = amY + 98;
            boolean sel = (i == player.getCurrentWeaponIndex());
            g.setColor(sel ? new Color(248, 192, 66) : new Color(30, 40, 54));
            g.fillRect(sx, sy, slotW, 24);
            String keyNum = (i == 3) ? "T" : String.valueOf(i + 1);
            String shortName = (i == 2) ? "Керамбит" : (i == 3 ? "Zeus"
                    : (allW[i].getDisplayName().length() > 7
                            ? allW[i].getDisplayName().substring(0, 6) : allW[i].getDisplayName()));
            drawCenteredText(g, keyNum + ":" + shortName, sx + slotW / 2, sy + 16,
                    uiFont("SansSerif", Font.BOLD, 10), sel ? new Color(16, 18, 24) : new Color(202, 214, 228));
        }

        // 4. Карточки фрагов раунда CS2 внизу по центру экрана
        int rk = Math.min(8, player.getRoundKills());
        if (rk > 0) {
            int cardW = 36, cardGap = 6;
            int totalW = rk * cardW + (rk - 1) * cardGap;
            int startX = outW / 2 - totalW / 2;
            int cardY = outH - 64;
            for (int i = 0; i < rk; i++) {
                boolean hs = player.isRoundKillHeadshot(i);
                int cx = startX + i * (cardW + cardGap);
                g.setColor(hs ? new Color(245, 172, 38) : new Color(34, 48, 68));
                g.fillRect(cx, cardY, cardW, 42);
                g.setColor(hs ? new Color(255, 236, 140) : COL_HUD_CT_PIP);
                g.drawRect(cx, cardY, cardW - 1, 41);
                drawCenteredText(g, "☠", cx + cardW / 2, cardY + 23,
                        uiFont("SansSerif", Font.BOLD, 17), Color.WHITE);
                drawCenteredText(g, hs ? "HS" : ("K" + (i + 1)), cx + cardW / 2, cardY + 37,
                        uiFont("SansSerif", Font.BOLD, 9), hs ? new Color(24, 18, 10) : new Color(215, 230, 248));
            }
        }
    }

    private void ensureRadarBaseCached(int inner) {
        if (cachedRadarBase != null && cachedRadarInnerSize == inner && cachedRadarTheme == map.getCurrentTheme()) {
            return;
        }
        cachedRadarInnerSize = inner;
        cachedRadarTheme = map.getCurrentTheme();
        cachedRadarBase = new BufferedImage(inner, inner, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = cachedRadarBase.createGraphics();
        rg.setColor(new Color(20, 26, 36));
        rg.fillRect(0, 0, inner, inner);

        double span = Map.MAX_X - Map.MIN_X;
        List<Vec3.Box> boxes = map.getBoxes();
        for (int i = 0; i < boxes.size(); i++) {
            Vec3.Box b = boxes.get(i);
            if (!b.collidable) continue;
            int bx0 = (int) (((b.minX - Map.MIN_X) / span) * inner);
            int bz0 = (int) (((Map.MAX_Z - b.maxZ) / span) * inner);
            int bw = Math.max(2, (int) (((b.maxX - b.minX) / span) * inner));
            int bh = Math.max(2, (int) (((b.maxZ - b.minZ) / span) * inner));
            rg.setColor(b.maxY > 2.2 ? new Color(156, 140, 108) : new Color(98, 90, 72));
            rg.fillRect(bx0, bz0, bw, bh);
        }
        rg.setFont(uiFont("SansSerif", Font.BOLD, 9));
        rg.setColor(new Color(156, 186, 220));
        rg.drawString("N", inner / 2 - 3, 9);
        rg.drawString("S", inner / 2 - 3, inner - 2);
        rg.dispose();
    }

    private void drawMinimapRadar(Graphics2D g, int rx, int ry, int size) {
        boolean showTel = settings.telemetryMode != GameSettings.HudTelemetryMode.OFF;
        boolean showGraph = settings.telemetryMode == GameSettings.HudTelemetryMode.FULL_GRAPH;
        int totalH = size + (showTel ? 24 : 4);

        drawFastHudPanel(g, rx, ry, size, totalH, COL_HUD_CT_PIP);

        int pad = 11;
        int inner = size - pad * 2;
        double span = Map.MAX_X - Map.MIN_X;

        // Кэшированная статическая подложка карты (1 блит вместо 120 fillRect в каждом кадре!)
        ensureRadarBaseCached(inner);
        g.drawImage(cachedRadarBase, rx + pad, ry + pad, null);

        for (int i = 0; i < fireZones.size(); i++) {
            Vec3.FireZone fz = fireZones.get(i);
            int fx = rx + pad + (int) (((fz.center.x - Map.MIN_X) / span) * inner);
            int fzR = ry + pad + (int) (((Map.MAX_Z - fz.center.z) / span) * inner);
            int fr = Math.max(4, (int) ((fz.getCurrentRadius() / span) * inner));
            g.setColor(new Color(255, 108, 28, 175));
            g.fillOval(fx - fr, fzR - fr, fr * 2, fr * 2);
        }
        for (int i = 0; i < smokeClouds.size(); i++) {
            Vec3.SmokeCloud sc = smokeClouds.get(i);
            int sx = rx + pad + (int) (((sc.center.x - Map.MIN_X) / span) * inner);
            int sz = ry + pad + (int) (((Map.MAX_Z - sc.center.z) / span) * inner);
            int sr = Math.max(4, (int) ((sc.getCurrentRadius() / span) * inner));
            g.setColor(new Color(200, 210, 220, 145));
            g.fillOval(sx - sr, sz - sr, sr * 2, sr * 2);
        }
        for (int i = 0; i < airstrikeBeacons.size(); i++) {
            Vec3.AirstrikeBeacon ab = airstrikeBeacons.get(i);
            int ax = rx + pad + (int) (((ab.targetPos.x - Map.MIN_X) / span) * inner);
            int az = ry + pad + (int) (((Map.MAX_Z - ab.targetPos.z) / span) * inner);
            g.setColor(new Color(255, 58, 48, 220));
            g.drawOval(ax - 7, az - 7, 14, 14);
            g.fillOval(ax - 2, az - 2, 5, 5);
        }
        if (plantedC4 != null) {
            int cx = rx + pad + (int) (((plantedC4.pos.x - Map.MIN_X) / span) * inner);
            int cz = ry + pad + (int) (((Map.MAX_Z - plantedC4.pos.z) / span) * inner);
            g.setColor(Math.sin(plantedC4.blinkPhase) > 0.0 ? new Color(255, 52, 42) : new Color(255, 215, 75));
            g.fillOval(cx - 5, cz - 5, 10, 10);
        }
        for (int i = 0; i < sentryTurrets.size(); i++) {
            Vec3.SentryTurret t = sentryTurrets.get(i);
            if (!t.isAlive()) continue;
            int tx = rx + pad + (int) (((t.pos.x - Map.MIN_X) / span) * inner);
            int tz = ry + pad + (int) (((Map.MAX_Z - t.pos.z) / span) * inner);
            g.setColor(new Color(64, 240, 255));
            g.fillRect(tx - 3, tz - 3, 7, 7);
        }
        for (int i = 0; i < supplyDrops.size(); i++) {
            Vec3.SupplyDrop sd = supplyDrops.get(i);
            if (sd.life <= 0.0) continue;
            int dx = rx + pad + (int) (((sd.pos.x - Map.MIN_X) / span) * inner);
            int dz = ry + pad + (int) (((Map.MAX_Z - sd.pos.z) / span) * inner);
            g.setColor(new Color(255, 214, 68));
            g.fillRect(dx - 3, dz - 3, 7, 7);
        }
        if (bossHelicopter != null && bossHelicopter.isAlive()) {
            int hx = rx + pad + (int) (((bossHelicopter.pos.x - Map.MIN_X) / span) * inner);
            int hz = ry + pad + (int) (((Map.MAX_Z - bossHelicopter.pos.z) / span) * inner);
            g.setColor(new Color(255, 72, 48));
            g.fillOval(hx - 5, hz - 5, 11, 11);
            g.setColor(new Color(255, 226, 120));
            g.drawOval(hx - 6, hz - 6, 13, 13);
        }

        Vec3 eye = player.getEyePosition();
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (!b.isAlive()) continue;
            if (b.isAlly()) {
                int mx = rx + pad + (int) (((b.getPosition().x - Map.MIN_X) / span) * inner);
                int mz = ry + pad + (int) (((Map.MAX_Z - b.getPosition().z) / span) * inner);
                g.setColor(new Color(72, 196, 255));
                g.fillOval(mx - 4, mz - 4, 8, 8);
                continue;
            }
            boolean visibleOnRadar = b.isFiringRecently()
                    || b.getState() == Bot.State.ENGAGE
                    || (eye.dist(b.getEyePosition()) < 34.0 && map.hasLineOfSight(eye, b.getEyePosition(), smokeClouds));
            if (!visibleOnRadar) continue;

            int mx = rx + pad + (int) (((b.getPosition().x - Map.MIN_X) / span) * inner);
            int mz = ry + pad + (int) (((Map.MAX_Z - b.getPosition().z) / span) * inner);
            g.setColor(new Color(255, 68, 62));
            g.fillOval(mx - 4, mz - 4, 8, 8);
        }

        int px = rx + pad + (int) (((player.getPosition().x - Map.MIN_X) / span) * inner);
        int pz = ry + pad + (int) (((Map.MAX_Z - player.getPosition().z) / span) * inner);
        double yaw = player.getYaw();
        radarPolyX[0] = px;
        radarPolyX[1] = px + (int) (Math.sin(yaw - 0.5) * 18);
        radarPolyX[2] = px + (int) (Math.sin(yaw + 0.5) * 18);
        radarPolyZ[0] = pz;
        radarPolyZ[1] = pz - (int) (Math.cos(yaw - 0.5) * 18);
        radarPolyZ[2] = pz - (int) (Math.cos(yaw + 0.5) * 18);

        g.setColor(new Color(95, 225, 255, 85));
        g.fillPolygon(radarPolyX, radarPolyZ, 3);
        g.setColor(new Color(90, 248, 156));
        g.fillOval(px - 4, pz - 4, 9, 9);

        if (showTel) {
            g.setFont(uiFont("SansSerif", Font.BOLD, 10));
            g.setColor(new Color(128, 232, 172));
            g.drawString(String.format("%d FPS • %.1fмс • 1%%:%d",
                    currentFps, lastFrameMs, onePercentLowFps), rx + 10, ry + totalH - 8);
        }
        if (showGraph) {
            drawFrameTimeGraphBox(g, rx, ry + totalH + 6, size, 38, false);
        }
    }

    private void drawKillFeedAndKd(Graphics2D g, int outW) {
        String kdText = String.format("K: %d   D: %d   K/D: %.2f   HS: %d",
                player.getKills(), player.getDeaths(), player.getKdRatio(), player.getHeadshots());
        g.setFont(uiFont("SansSerif", Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        int boxW = fm.stringWidth(kdText) + 28;
        int boxX = outW - boxW - 18, boxY = 16;

        g.setColor(new Color(14, 18, 25, 212));
        g.fillRoundRect(boxX, boxY, boxW, 32, 10, 10);
        g.setColor(new Color(245, 195, 72, 140));
        g.drawRoundRect(boxX, boxY, boxW, 32, 10, 10);
        g.setColor(new Color(245, 247, 250));
        g.drawString(kdText, boxX + 14, boxY + 21);

        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
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
        int bw = 472, bh = 66;
        int bx = outW / 2 - bw / 2;
        int by = (bossHelicopter != null && bossHelicopter.isAlive()) ? 120 : 88;
        g.setColor(new Color(16, 20, 28, 198));
        g.fillRoundRect(bx, by, bw, bh, 12, 12);
        g.setColor(new Color(245, 195, 72, 175));
        g.drawRoundRect(bx, by, bw, bh, 12, 12);

        String topTitle = (settings.gameMode == GameSettings.GameMode.SIEGE_WAVES)
                ? ("ОСАДА: ВОЛНА " + siegeWave + "/" + MAX_SIEGE_WAVES + " • СЛОЖНОСТЬ: " + settings.botDifficulty.label.toUpperCase())
                : ("РАУНД " + currentRound + " • СЛОЖНОСТЬ: " + settings.botDifficulty.label.toUpperCase());
        drawCenteredText(g, topTitle,
                outW / 2, by + 28, uiFont("SansSerif", Font.BOLD, 16), new Color(252, 212, 92));
        drawCenteredText(g, "Магазин: [B] • Авто-Турель: [J] • Вызов Босс-Вертолёта: [K]",
                outW / 2, by + 50, uiFont("SansSerif", Font.PLAIN, 12), new Color(220, 226, 235));
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
        drawCenteredText(g, title, outW / 2, by + 36, uiFont("SansSerif", Font.BOLD, 21), accent);
        drawCenteredText(g, roundResultReason, outW / 2, by + 64, uiFont("SansSerif", Font.PLAIN, 15), Color.WHITE);
        drawCenteredText(g, String.format("Следующий раунд через %.1f с (Space/Enter — ускорить)",
                        Math.max(0.0, roundOverTimer)),
                outW / 2, by + 95, uiFont("SansSerif", Font.PLAIN, 12), new Color(195, 205, 218));
    }

    private void drawScoreboardOverlay(Graphics2D g, int outW, int outH) {
        int sh = Math.min(outH - 40, 175 + bots.size() * 29);
        int sw = 684;
        int sx = outW / 2 - sw / 2, sy = outH / 2 - sh / 2;

        g.setColor(new Color(12, 16, 24, 238));
        g.fillRoundRect(sx, sy, sw, sh, 14, 14);
        g.setColor(new Color(255, 255, 255, 65));
        g.drawRoundRect(sx, sy, sw, sh, 14, 14);

        drawCenteredText(g, "ТАБЛИЦА СЧЕТА — " + settings.mapTheme.label.toUpperCase() + " • " + settings.gameMode.label,
                outW / 2, sy + 32, uiFont("SansSerif", Font.BOLD, 17), new Color(248, 205, 86));
        drawCenteredText(g, "Счет по раундам: СПЕЦНАЗ CT " + playerRoundWins + " : " + botRoundWins + " ТЕРРОРИСТЫ T",
                outW / 2, sy + 54, uiFont("SansSerif", Font.BOLD, 13), new Color(200, 212, 228));

        g.setFont(uiFont("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(145, 162, 184));
        g.drawString("БОЕЦ ОТРЯДА", sx + 26, sy + 82);
        g.drawString("СТАТУС", sx + 245, sy + 82);
        g.drawString("HP", sx + 355, sy + 82);
        g.drawString("K / D / НАГРАДЫ", sx + 420, sy + 82);

        g.setColor(new Color(45, 108, 178, 155));
        g.fillRoundRect(sx + 18, sy + 90, sw - 36, 28, 8, 8);
        g.setColor(Color.WHITE);
        String mvpStr = player.getMvpStars() > 0 ? (" ★" + player.getMvpStars()) : "";
        g.drawString("ВЫ (Командир CT)" + mvpStr, sx + 26, sy + 109);
        g.drawString(player.isAlive() ? "В БОЮ" : "УБИТ", sx + 245, sy + 109);
        g.drawString(String.valueOf(player.getHealth()), sx + 355, sy + 109);
        g.drawString(player.getKills() + " / " + player.getDeaths() + "  ($" + player.getMoney() + ")", sx + 420, sy + 109);

        int rowY = sy + 124;
        // Сначала союзники CT, затем противники T
        for (int pass = 0; pass < 2; pass++) {
            boolean wantAlly = (pass == 0);
            for (int i = 0; i < bots.size(); i++) {
                Bot b = bots.get(i);
                if (b.isAlly() != wantAlly) continue;
                if (rowY + 26 > sy + sh - 10) break;
                g.setColor(wantAlly ? new Color(32, 68, 112, 135) : new Color(72, 42, 34, 135));
                g.fillRoundRect(sx + 18, rowY, sw - 36, 24, 7, 7);
                g.setColor(b.isAlive()
                        ? (wantAlly ? new Color(165, 228, 255) : new Color(248, 214, 182))
                        : new Color(132, 140, 152));
                g.drawString(b.getName() + (b.isCarryingC4() ? " [C4]" : ""), sx + 26, rowY + 16);
                g.drawString(b.isAlive() ? b.getState().name() : "УБИТ", sx + 245, rowY + 16);
                g.drawString(String.valueOf(b.getHealth()), sx + 355, rowY + 16);
                g.drawString(b.getKills() + " / " + b.getDeaths() + (wantAlly ? "  (Союзник CT)" : "  (Террорист T)"), sx + 420, rowY + 16);
                rowY += 27;
            }
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
                outW / 2, cy + 50, uiFont("SansSerif", Font.BOLD, 32), accent);
        drawCenteredText(g, victory
                ? ("Спецназ успешно зачистил арену " + settings.mapTheme.label + "!")
                : "Отряд противников одержал верх",
                outW / 2, cy + 80, uiFont("SansSerif", Font.PLAIN, 15), new Color(214, 222, 234));

        int statX = cx + 44, statY = cy + 102, statW = cw - 88, statH = 152;
        g.setColor(new Color(25, 32, 44));
        g.fillRoundRect(statX, statY, statW, statH, 12, 12);

        drawCenteredText(g, "ИТОГОВЫЙ СЧЕТ РАУНДОВ:  " + playerRoundWins + " : " + botRoundWins,
                outW / 2, statY + 32, uiFont("SansSerif", Font.BOLD, 18), new Color(248, 206, 90));

        g.setFont(uiFont("SansSerif", Font.PLAIN, 15));
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
        g.setColor(new Color(8, 12, 18, 204));
        g.fillRect(0, 0, outW, outH);

        int mw = 476, mh = 412;
        int mx = outW / 2 - mw / 2, my = outH / 2 - mh / 2;

        g.setPaint(new GradientPaint(mx, my, new Color(14, 20, 32, 246), mx, my + mh, new Color(18, 26, 42, 246)));
        g.fillRoundRect(mx, my, mw, mh, 18, 18);
        g.setColor(new Color(248, 202, 78, 185));
        g.drawRoundRect(mx, my, mw, mh, 18, 18);

        drawCenteredText(g, "ПАУЗА  •  CS-2 JAVA 6.0", outW / 2, my + 40,
                uiFont("SansSerif", Font.BOLD, 23), new Color(252, 208, 88));
        drawCenteredText(g, "Звание: " + settings.getRankTitle() + "  •  XP: " + settings.careerXp, outW / 2, my + 60,
                uiFont("SansSerif", Font.PLAIN, 12), new Color(172, 194, 220));

        int bx = mx + 56, bw = mw - 112, by = my + 78;
        addMenuButton(g, new Rectangle(bx, by, bw, 44), "▶  ПРОДОЛЖИТЬ БОЙ (ESC)",
                new Color(46, 134, 212), true, () -> { paused = false; setMouseCaptured(true); });
        by += 54;

        addMenuButton(g, new Rectangle(bx, by, bw, 44), "⚙  НАСТРОЙКИ И ГРАФИКА 6.0",
                new Color(42, 92, 150), false, () -> openSettingsScreen(true, 0));
        by += 54;

        addMenuButton(g, new Rectangle(bx, by, bw, 44), "$  МАГАЗИН СПЕЦНАЗА (B)",
                new Color(48, 142, 92), false, () -> { paused = false; buyMenuOpen = true; setMouseCaptured(false); });
        by += 54;

        addMenuButton(g, new Rectangle(bx, by, bw, 44), "⟳  НАЧАТЬ МАТЧ ЗАНОВО (F5)",
                new Color(186, 128, 42), false, () -> { paused = false; startNewMatch(); setMouseCaptured(true); });
        by += 54;

        addMenuButton(g, new Rectangle(bx, by, bw, 44), "←  ВЫЙТИ В ГЛАВНОЕ МЕНЮ",
                new Color(128, 46, 52), false, () -> { paused = false; currentScreen = AppScreen.MAIN_MENU; setMouseCaptured(false); });
    }

    // =========================================================================
    //  UI-ХЕЛПЕРЫ ГРАДИЕНТНЫХ СТЕКЛЯННЫХ КНОПОК PANORAMA 6.0
    // =========================================================================
    private void addMenuButton(Graphics2D g, Rectangle rect, String label, Color baseColor, boolean accentTextDark, Runnable onClick) {
        boolean hovered = rect.contains(mouseClientX, mouseClientY);
        Color c1 = hovered ? baseColor.brighter() : baseColor;
        Color c2 = new Color(
                Math.max(0, c1.getRed() - 22),
                Math.max(0, c1.getGreen() - 22),
                Math.max(0, c1.getBlue() - 18),
                c1.getAlpha());
        g.setPaint(new GradientPaint(rect.x, rect.y, c1, rect.x, rect.y + rect.height, c2));
        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
        g.setColor(hovered ? new Color(255, 240, 178) : new Color(255, 255, 255, 75));
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);

        Color txtCol = (accentTextDark && baseColor.getRed() > 200 && baseColor.getGreen() > 140)
                ? new Color(16, 18, 22) : Color.WHITE;
        drawCenteredText(g, label, rect.x + rect.width / 2, rect.y + rect.height / 2 + 5,
                uiFont("SansSerif", Font.BOLD, 13), txtCol);
        uiRegisterClickable(rect, onClick);
    }

    private void drawOptionStepperRow(Graphics2D g, int x, int y, int w, String title, String valueLabel, Runnable onToggle) {
        Rectangle fullRect = new Rectangle(x, y, w, 34);
        boolean hovered = fullRect.contains(mouseClientX, mouseClientY);

        g.setColor(hovered ? new Color(34, 46, 64, 220) : new Color(24, 32, 45, 200));
        g.fillRoundRect(x, y, w, 34, 8, 8);
        g.setColor(hovered ? new Color(245, 195, 76, 175) : new Color(255, 255, 255, 32));
        g.drawRoundRect(x, y, w, 34, 8, 8);

        g.setFont(uiFont("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(224, 232, 242));
        g.drawString(title, x + 14, y + 22);

        String pill = "◄  " + valueLabel + "  ►";
        g.setFont(uiFont("SansSerif", Font.BOLD, 13));
        int pw = g.getFontMetrics().stringWidth(pill) + 18;
        int px = x + w - pw - 10;
        g.setColor(new Color(46, 68, 96));
        g.fillRoundRect(px, y + 5, pw, 24, 6, 6);
        g.setColor(new Color(248, 206, 88));
        g.drawString(pill, px + 9, y + 21);

        uiRegisterClickable(fullRect, onToggle);
    }

    private void uiRegisterClickable(Rectangle rect, Runnable action) {
        buildingUiClickables.add(new UiClickable(rect, action));
    }

    private void drawOptionPlusMinusRow(Graphics2D g, int x, int y, int w, String title, String valueLabel,
                                        Runnable onMinus, Runnable onPlus) {
        g.setColor(new Color(24, 32, 45, 200));
        g.fillRoundRect(x, y, w, 38, 8, 8);
        g.setColor(new Color(255, 255, 255, 32));
        g.drawRoundRect(x, y, w, 38, 8, 8);

        g.setFont(uiFont("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(224, 232, 242));
        g.drawString(title, x + 14, y + 24);

        Rectangle minusR = new Rectangle(x + w - 180, y + 5, 38, 28);
        Rectangle plusR  = new Rectangle(x + w - 48,  y + 5, 38, 28);

        addMenuButton(g, minusR, "−", new Color(52, 68, 90), false, onMinus);
        drawCenteredText(g, valueLabel, x + w - 95, y + 24, uiFont("Monospaced", Font.BOLD, 14), new Color(248, 206, 88));
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
        return countAliveEnemies();
    }

    public int countAliveEnemies() {
        int c = 0;
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (b.isAlive() && !b.isAlly()) c++;
        }
        return c;
    }

    public int countAliveAllies() {
        int c = 0;
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (b.isAlive() && b.isAlly()) c++;
        }
        return c;
    }

    public AppScreen getCurrentScreen() { return currentScreen; }
    public void setCurrentScreen(AppScreen screen) { this.currentScreen = screen; }
    public void openSettingsForTest(int tab, int subPage) {
        this.settingsTab = tab;
        this.graphicsSubPage = subPage;
        this.currentScreen = AppScreen.SETTINGS;
    }
    public void setBuyMenuOpen(boolean open) { this.buyMenuOpen = open; }
    public void setScoreTabHeld(boolean held) { this.scoreTabHeld = held; }
    public GameSettings getSettings() { return settings; }
    public Player getPlayer() { return player; }
    public Map getMap() { return map; }
    public List<Bot> getBots() { return bots; }
    public List<Vec3.SmokeCloud> getSmokeClouds() { return smokeClouds; }
    public List<Vec3.FireZone> getFireZones() { return fireZones; }
    public List<Vec3.Graffiti> getGraffitis() { return graffitis; }
    public Vec3.BombC4 getPlantedC4() { return plantedC4; }
    public void setPlantedC4(Vec3.BombC4 c4) { this.plantedC4 = c4; }
    public MatchState getMatchState() { return matchState; }
    public int getPlayerRoundWins() { return playerRoundWins; }
    public int getBotRoundWins() { return botRoundWins; }
    public BufferedImage getDisplayedFrame() { return displayedFrame; }
    public void renderOffscreenForTest(int w, int h) { renderOffscreenSwap(w, h); }
    public int getPublishedButtonCount() { return publishedUiSnapshot.items.size(); }
    public Point getPublishedButtonCenter(int index) {
        List<UiClickable> items = publishedUiSnapshot.items;
        if (index < 0 || index >= items.size()) return null;
        Rectangle r = items.get(index).rect;
        return new Point(r.x + r.width / 2, r.y + r.height / 2);
    }
}
