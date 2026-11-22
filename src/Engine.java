import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
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
        } else if (kind == 4 && player.getSentryCharges() < 2) {
            if (player.spendMoney(freeDm ? 0 : 1200)) {
                player.addSentryCharge();
                soundSystem.playBuy();
                streakBannerText = "★ ПОЛУЧЕНА АВТО-ТУРЕЛЬ СПЕЦНАЗА (НАЖМИТЕ [J] ДЛЯ УСТАНОВКИ) ★";
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

        // Обновление реактивных 3D-ракет гранатомёта RPG-7
        updateRockets(dt);

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

        Graphics2D g = target.createGraphics();
        try {
            renderCompleteFrame(g, outW, outH);
        } finally {
            g.dispose();
        }
        this.displayedFrame = target;
        this.nextBufferIsA = !this.nextBufferIsA;
    }

    /**
     * Полный рендер кадра (3D-сцена с настройками графики + HUD / Главное меню / Настройки).
     */
    public void renderCompleteFrame(Graphics2D g, int outW, int outH) {
        buildingUiClickables.clear();

        double zoomFactor = (currentScreen == AppScreen.IN_GAME && player.isAlive())
                ? player.getCurrentWeapon().getEffectiveFovFactor()
                : 1.0;
        double effectiveFov = settings.fovDegrees * zoomFactor;

        renderer.ensureViewport(
                settings.resolutionScale.width,
                settings.resolutionScale.height,
                effectiveFov
        );
        renderer.setSmoothQualityFlags(
                settings.smoothTextures,
                settings.fxaaAntiAliasing,
                settings.weatherMode
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

        // 3. Следы от пуль (декали), цветные 3D-граффити [Y] и огненные зоны Молотова
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

        // 4. Установленная взрывчатка C4 на пленте + подбираемые аптечки и боеприпасы
        if (plantedC4 != null) {
            Vec3 cp = plantedC4.pos;
            renderer.drawOrientedWorldBox(cp.x, cp.y + 0.06, cp.z, 0.36, 0.14, 0.26, 0.35, 0.0, Renderer3D.TEX_SOLID, 0xC2A35E);
            renderer.drawOrientedWorldBox(cp.x, cp.y + 0.14, cp.z, 0.16, 0.04, 0.14, 0.35, 0.0, Renderer3D.TEX_SOLID, 0x262C34);
            boolean ledOn = Math.sin(plantedC4.blinkPhase) > 0.0;
            if (ledOn) {
                renderer.drawOrientedWorldBox(cp.x, cp.y + 0.20, cp.z, 0.12, 0.12, 0.12, 0.0, 0.0, Renderer3D.TEX_SOLID, 0xFF2B22);
            }
        }
        for (int i = 0; i < pickups.size(); i++) {
            Vec3.Pickup pk = pickups.get(i);
            double py = pk.pos.y + 0.22 + Math.sin(pk.bobPhase) * 0.06;
            int col = (pk.type == Vec3.Pickup.Type.MEDKIT) ? 0x38C96B : 0xE6B444;
            renderer.drawOrientedWorldBox(pk.pos.x, py, pk.pos.z, 0.36, 0.24, 0.36, pk.bobPhase * 0.7, 0.0, Renderer3D.TEX_SOLID, col);
        }

        // 5. Летящие гранаты (HE, Smoke, Flash, Molotov) и 3D-гильзы
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

        // 6. 3D-боты, полевые авто-турели спецназа [J], авиасбросы, ракеты RPG-7 и Ударный Вертолёт-Босс!
        for (int i = 0; i < bots.size(); i++) {
            bots.get(i).render(renderer);
        }
        for (int i = 0; i < sentryTurrets.size(); i++) {
            renderer.drawSentryTurret(sentryTurrets.get(i));
        }
        for (int i = 0; i < supplyDrops.size(); i++) {
            renderer.drawSupplyDrop(supplyDrops.get(i));
        }
        for (int i = 0; i < rockets.size(); i++) {
            renderer.drawRocketProjectile(rockets.get(i));
        }
        if (bossHelicopter != null && bossHelicopter.isAlive()) {
            renderer.drawAttackHelicopter(bossHelicopter);
        } else if (currentScreen == AppScreen.MAIN_MENU && settings.gameMode == GameSettings.GameMode.SIEGE_WAVES) {
            // Кинематографический пролёт Боевого Вертолёта на живом фоне Главного меню в режиме «Осада»!
            Vec3.AttackHelicopter menuHeli = new Vec3.AttackHelicopter(850);
            double a = globalAnimTime * 0.42;
            menuHeli.pos.set(Math.cos(a) * 10.5, 9.2 + Math.sin(a * 2.0) * 0.6, Math.sin(a) * 10.5);
            menuHeli.yaw = Math.atan2(-Math.sin(a), Math.cos(a));
            menuHeli.rotorPhase = globalAnimTime * 25.0;
            renderer.drawAttackHelicopter(menuHeli);
        }

        // 7. Трассеры, частицы, 3D-погода (ливень / снегопад) и объемные облака дыма CS2
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

        // 7.5. Направленный луч тактического 3D-фонарика (клавиша L)
        if (currentScreen == AppScreen.IN_GAME && player.isAlive() && player.isFlashlightOn()) {
            renderer.applyFlashlightBeam();
        }

        // 8. Модель оружия от первого лица (с активным скином CS2 / Золотым ножом в Гонке вооружений) или 3D-витрина скинов в меню
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
        } else if (currentScreen == AppScreen.SKIN_ARMORY) {
            renderer.clearDepthBuffer();
            renderer.setGraphicsFlags(settings.fogAndVignette, settings.skyCloudsAndSunFlare, false, globalAnimTime);
            armoryShowcaseWeapons[armoryWeaponIdx].renderShowcaseModel(
                    renderer, globalAnimTime * 1.15, settings.weaponSkin);
        }

        // Сглаживание диагональных граней FXAA (убирает любые «лесенки» на архитектуре, солдатах и оружии!)
        renderer.applyFxaaPass();

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
        } else if (currentScreen == AppScreen.SKIN_ARMORY) {
            drawSkinArmoryScreen(g, outW, outH);
        } else if (currentScreen == AppScreen.SETTINGS) {
            drawSettingsScreen(g, outW, outH);
        } else if (currentScreen == AppScreen.ARSENAL_GUIDE) {
            drawArsenalGuideScreen(g, outW, outH);
        } else {
            drawInGameUi(g, outW, outH);
        }

        // Визуальный отклик мгновенного нажатия кнопки
        Rectangle flash = this.clickFlashRect;
        if (clickFlashTimer > 0.0 && flash != null && isCursorFreeScreen()) {
            int alpha = Math.min(130, (int) (clickFlashTimer * 950.0));
            g.setColor(new Color(255, 236, 150, alpha));
            g.fillRoundRect(flash.x, flash.y, flash.width, flash.height, 10, 10);
            g.setColor(new Color(255, 255, 255, Math.min(220, alpha + 70)));
            g.drawRoundRect(flash.x, flash.y, flash.width, flash.height, 10, 10);
        }

        // Атомарно публикуем готовый список кнопок ТОЛЬКО ПОСЛЕ полной сборки UI!
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
        g.drawString("CS-2 JAVA 5.0", 104, 62);
        g.setColor(new Color(245, 195, 76));
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString("SMOOTH HD • INFERNO • SIEGE BOSSES", 105, 83);

        int btnX = 34;
        int btnW = leftW - 68;
        int btnY = 122;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 52),
                "▶  ИГРАТЬ (НАЧАТЬ МАТЧ)", new Color(232, 152, 36), true, this::launchMatchFromMenu);
        btnY += 62;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 44),
                "★  СКИНЫ ОРУЖИЯ (3D ВИТРИНА)", new Color(138, 56, 168), false,
                () -> currentScreen = AppScreen.SKIN_ARMORY);
        btnY += 54;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 44),
                "⚙  НАСТРОЙКИ ГРАФИКИ", new Color(42, 108, 178), false, () -> openSettingsScreen(false, 0));
        btnY += 54;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 44),
                "◆  НАСТРОЙКИ ИГРЫ И МЫШИ", new Color(44, 62, 86), false, () -> openSettingsScreen(false, 1));
        btnY += 54;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 44),
                "♪  НАСТРОЙКИ АУДИО", new Color(44, 62, 86), false, () -> openSettingsScreen(false, 2));
        btnY += 54;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 44),
                "☰  АРСЕНАЛ И УПРАВЛЕНИЕ", new Color(44, 62, 86), false, () -> currentScreen = AppScreen.ARSENAL_GUIDE);
        btnY += 58;

        addMenuButton(g, new Rectangle(btnX, btnY, btnW, 42),
                "✖  ВЫЙТИ НА РАБОЧИЙ СТОЛ", new Color(118, 42, 46), false, this::shutdownAndExit);

        // Нижняя сводка графики и скина
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(165, 178, 196));
        g.drawString("Рендер: " + settings.resolutionScale.label + " • " + currentFps + " FPS", 34, outH - 42);
        g.drawString("Скин: " + settings.weaponSkin.label + " • F11 Полный экран", 34, outH - 22);

        // Правая карточка быстрой настройки параметров боя (с живым предпросмотром 3D-карты!)
        int cardW = 486;
        int cardH = 434;
        int cardX = outW - cardW - 34;
        int cardY = outH - cardH - 24;

        g.setColor(new Color(12, 17, 26, 214));
        g.fillRoundRect(cardX, cardY, cardW, cardH, 16, 16);
        g.setColor(new Color(255, 255, 255, 48));
        g.drawRoundRect(cardX, cardY, cardW, cardH, 16, 16);

        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.setColor(new Color(248, 204, 84));
        g.drawString("ОПЕРАЦИЯ: " + settings.mapTheme.label.toUpperCase(), cardX + 24, cardY + 28);

        int rowY = cardY + 42;
        int cStep = 43;
        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "3D-Карта арены", settings.mapTheme.label, () -> {
            int ord = (settings.mapTheme.ordinal() + 1) % GameSettings.MapTheme.values().length;
            settings.mapTheme = GameSettings.MapTheme.values()[ord];
            settings.save();
            startNewMatch();
        });
        rowY += cStep;

        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Режим игры", settings.gameMode.label, () -> {
            int ord = (settings.gameMode.ordinal() + 1) % GameSettings.GameMode.values().length;
            settings.gameMode = GameSettings.GameMode.values()[ord];
            settings.save();
            startNewMatch();
        });
        rowY += cStep;

        String allyLabel = (settings.allyCount == 0) ? "Без напарников (Соло)" : (settings.allyCount + " бойца CT");
        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Отряд союзников", allyLabel, () -> {
            settings.allyCount = (settings.allyCount + 1) % 5;
            settings.save();
            startNewMatch();
        });
        rowY += cStep;

        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Противников", settings.botCount + " ботов T", () -> {
            settings.botCount = (settings.botCount >= 8) ? 3 : settings.botCount + 1;
            settings.save();
            startNewMatch();
        });
        rowY += cStep;

        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Сложность ИИ", settings.botDifficulty.label, () -> {
            int ord = (settings.botDifficulty.ordinal() + 1) % GameSettings.BotDifficulty.values().length;
            settings.botDifficulty = GameSettings.BotDifficulty.values()[ord];
            settings.save();
        });
        rowY += cStep;

        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "Время суток", settings.timeOfDay.label, () -> {
            int ord = (settings.timeOfDay.ordinal() + 1) % GameSettings.TimeOfDay.values().length;
            settings.timeOfDay = GameSettings.TimeOfDay.values()[ord];
            renderer.applyTimeOfDay(settings.timeOfDay);
            settings.save();
        });
        rowY += cStep;

        drawOptionStepperRow(g, cardX + 24, rowY, cardW - 48, "3D-Погода", settings.weatherMode.label, () -> {
            int ord = (settings.weatherMode.ordinal() + 1) % GameSettings.WeatherMode.values().length;
            settings.weatherMode = GameSettings.WeatherMode.values()[ord];
            settings.save();
        });

        drawCenteredText(g, "RPG-7 & M249 [B] • Авто-Турель [J] • Вызов Вертолёта [K] • Радио [Z]",
                cardX + cardW / 2, cardY + cardH - 12,
                new Font("SansSerif", Font.PLAIN, 12), new Color(184, 198, 216));
    }

    // =========================================================================
    //  1.5. 3D ОРУЖЕЙНАЯ СКИНОВ CS2 (ИНТЕРАКТИВНЫЙ ВРАЩАЮЩИЙСЯ СТЕНД)
    // =========================================================================
    private void drawSkinArmoryScreen(Graphics2D g, int outW, int outH) {
        // Левая панель выбора пушки и скина, справа — открытое окно с живой 3D-моделью оружия!
        int panelW = 430;
        g.setColor(new Color(11, 15, 24, 228));
        g.fillRect(0, 0, panelW, outH);
        g.setColor(new Color(196, 108, 255, 175));
        g.fillRect(panelW - 2, 0, 2, outH);

        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.setColor(Color.WHITE);
        g.drawString("3D ОРУЖЕЙНАЯ СКИНОВ", 30, 46);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(214, 152, 255));
        g.drawString("КОЛЛЕКЦИОННЫЕ РАСКРАСКИ CS-2 JAVA", 30, 66);

        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(new Color(185, 198, 216));
        g.drawString("ВЫБЕРИТЕ ОРУЖИЕ ДЛЯ 3D-ОСМОТРА:", 30, 96);

        int wx = 30, wy = 104;
        int cols = 4;
        int btnW = (panelW - 60 - (cols - 1) * 6) / cols;
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
                    : (w.getType() == Weapon.Type.KNIFE ? "Нож" : w.getDisplayName()))));
            addMenuButton(g, r, label,
                    sel ? new Color(228, 152, 38) : new Color(34, 45, 62), sel,
                    () -> armoryWeaponIdx = idx);
        }

        int sy = 236;
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(new Color(185, 198, 216));
        g.drawString("КОЛЛЕКЦИОННЫЙ СКИН (ДЛЯ ВСЕГО АРСЕНАЛА):", 30, sy);
        sy += 12;

        GameSettings.WeaponSkin[] allSkins = GameSettings.WeaponSkin.values();
        for (int i = 0; i < allSkins.length; i++) {
            final GameSettings.WeaponSkin sk = allSkins[i];
            boolean active = (settings.weaponSkin == sk);
            Color base = active ? new Color(142, 58, 178) : new Color(28, 38, 52);
            addMenuButton(g, new Rectangle(30, sy + i * 40, panelW - 60, 34),
                    (active ? "★ " : "   ") + sk.label, base, false, () -> {
                        settings.weaponSkin = sk;
                        settings.save();
                    });
        }

        addMenuButton(g, new Rectangle(30, outH - 66, panelW - 60, 44),
                "← СОХРАНИТЬ СКИН И В ГЛАВНОЕ МЕНЮ", new Color(56, 156, 96), false, () -> {
            settings.save();
            currentScreen = AppScreen.MAIN_MENU;
        });

        // Информационная карточка ТТХ выбранного оружия снизу справа под 3D-моделью
        Weapon cw = armoryShowcaseWeapons[armoryWeaponIdx];
        int infoW = 640, infoH = 112;
        int infoX = panelW + (outW - panelW - infoW) / 2;
        int infoY = outH - infoH - 32;

        g.setColor(new Color(12, 16, 25, 216));
        g.fillRoundRect(infoX, infoY, infoW, infoH, 16, 16);
        g.setColor(new Color(245, 194, 72, 165));
        g.drawRoundRect(infoX, infoY, infoW, infoH, 16, 16);

        String fullTitle = cw.getDisplayName().toUpperCase() + "  |  " + settings.weaponSkin.label.toUpperCase();
        drawCenteredText(g, fullTitle, infoX + infoW / 2, infoY + 34,
                new Font("SansSerif", Font.BOLD, 20), new Color(252, 212, 92));

        String statsLine = cw.isMelee()
                ? "Урон ЛКМ: 55  •  Выпад ПКМ: 92 / 155 HS  •  Награда: $1500  •  Скорость: +15%"
                : String.format("Урон (тело / голова): %d / %d   •   Цена: $%d   •   Награда за фраг: $%d",
                        cw.getBodyDamage(), cw.getHeadDamage(), cw.getPrice(), cw.getKillReward());
        drawCenteredText(g, statsLine, infoX + infoW / 2, infoY + 66,
                new Font("SansSerif", Font.PLAIN, 14), new Color(224, 232, 244));
        drawCenteredText(g, "Подсказка: в бою клавиша [H] мгновенно переключает скин, а [F] запускает осмотр!",
                infoX + infoW / 2, infoY + 94,
                new Font("SansSerif", Font.PLAIN, 12), new Color(168, 184, 204));
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

        if (settingsTab == 0) {
            // ВКЛАДКА: НАСТРОЙКИ ГЛАДКОЙ HD-ГРАФИКИ (БЕЗ ПИКСЕЛИЗАЦИИ!)
            int gStep = 39;
            drawOptionStepperRow(g, listX, rowY, listW, "Общий пресет качества графики", settings.qualityPreset.title, () -> {
                int next = (settings.qualityPreset.ordinal() + 1) % GameSettings.QualityPreset.values().length;
                settings.applyGraphicsPreset(GameSettings.QualityPreset.values()[next]);
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Внутреннее 3D-разрешение (до Full HD 1080p)", settings.resolutionScale.label, () -> {
                int next = (settings.resolutionScale.ordinal() + 1) % GameSettings.ResolutionScale.values().length;
                settings.resolutionScale = GameSettings.ResolutionScale.values()[next];
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Гладкие HD-текстуры 256×256 (Bilinear + Mipmap)",
                    settings.smoothTextures ? "Включено (Гладко, без пикселей)" : "Выключено (Ретро)", () -> {
                settings.smoothTextures = !settings.smoothTextures;
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Сглаживание краёв геометрии (FXAA)",
                    settings.fxaaAntiAliasing ? "Включено (Гладкие грани)" : "Выключено", () -> {
                settings.fxaaAntiAliasing = !settings.fxaaAntiAliasing;
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Качество солнечных 3D-теней", settings.shadowMode.label, () -> {
                int next = (settings.shadowMode.ordinal() + 1) % GameSettings.ShadowMode.values().length;
                settings.shadowMode = GameSettings.ShadowMode.values()[next];
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Масштабирование окна / Fullscreen",
                    settings.bilinearFiltering ? "Билинейное сглаживание (Мягко)" : "Чёткие пиксели", () -> {
                settings.bilinearFiltering = !settings.bilinearFiltering;
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "3D-Погода на арене", settings.weatherMode.label, () -> {
                int next = (settings.weatherMode.ordinal() + 1) % GameSettings.WeatherMode.values().length;
                settings.weatherMode = GameSettings.WeatherMode.values()[next];
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Декали пробоин и вылетающие 3D-гильзы",
                    settings.decalsAndShells ? "Включено (Реализм)" : "Выключено", () -> {
                settings.decalsAndShells = !settings.decalsAndShells;
                if (!settings.decalsAndShells) { decals.clear(); shells.clear(); }
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Угол обзора камеры (FOV)", settings.fovDegrees + "°", () -> {
                settings.fovDegrees += 4;
                if (settings.fovDegrees > 84) settings.fovDegrees = 56;
                settings.save();
            });
            rowY += gStep;

            String capLabel = (settings.fpsLimit == 0) ? "Без лимита (Макс.)" : (settings.fpsLimit + " FPS");
            drawOptionStepperRow(g, listX, rowY, listW, "Ограничение частоты кадров", capLabel, () -> {
                if (settings.fpsLimit == 60) settings.fpsLimit = 120;
                else if (settings.fpsLimit == 120) settings.fpsLimit = 0;
                else settings.fpsLimit = 60;
                settings.save();
            });
            rowY += gStep;

            drawOptionStepperRow(g, listX, rowY, listW, "Время суток / Освещение неба", settings.timeOfDay.label, () -> {
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
            "УПРАВЛЕНИЕ И ТАКТИЧЕСКИЕ СПЕЦСРЕДСТВА:",
            "• WASD / Space / Ctrl / Shift — Движение и тихий шаг",
            "• J — Установить 3D Авто-Турель Спецназа CT!",
            "• K — Вызвать в небо Боевой Вертолёт-Босс (850 HP)!",
            "• 8 / T — Электрошокер Zeus x27 • Z — Радиокоманды CT",
            "• Y — Цветное 3D-граффити • E — Обезвреживание C4",
            "• B — Магазин закупки ($), F — Осмотр, H — Скин",
            "• L — Тактический 3D-фонарик (для Ночной операции!)",
            "• 4/G: HE • 5/Q: Smoke • 6/V: Flash • 7/X: Молотов",
            "• Прострел (Wallbang): винтовки, M249 и AWP бьют сквозь ящики",
            "• F11 / Alt+Enter — Безрамочный полный экран Windows",
            "• Пробел (после гибели) — Итог раунда союзников CT"
        };

        String[] rightLines = {
            "НОВИНКИ CS-2 JAVA 5.0 (SMOOTH HD & SIEGE):",
            "• Гладкий движок: HD 720p/1080p, 256×256 Mipmap + FXAA!",
            "• 4 Карты: Inferno (Фонтан), Dust II, Mirage, Nuke",
            "• Режим «Осада»: 5 волн, Босс-Вертолёт и Джаггернаут!",
            "• Пулемёт M249 ($5200, 100 патр.) и Гранатомёт RPG-7 ($4500)",
            "• 3D-Погода: Ливень с грозой и Снегопад прямо в бою",
            "• Десантные Авиасбросы (Supply Drop) на парашютах",
            "• 11 видов оружия с круглыми стволами и оптикой Гуро",
            "",
            "СИСТЕМНЫЕ КЛАВИШИ:",
            "• Tab — Командный счет  • F5 — Быстрый рестарт",
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

        int bw = 720;
        int bh = 548;
        int bx = outW / 2 - bw / 2;
        int by = outH / 2 - bh / 2;

        g.setColor(new Color(15, 20, 30, 244));
        g.fillRoundRect(bx, by, bw, bh, 18, 18);
        g.setColor(new Color(102, 226, 138, 175));
        g.drawRoundRect(bx, by, bw, bh, 18, 18);

        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(Color.WHITE);
        g.drawString("МАГАЗИН СНАРЯЖЕНИЯ И ТЯЖЁЛОГО ОРУЖИЯ", bx + 28, by + 38);

        boolean freeDm = (settings.gameMode == GameSettings.GameMode.DEATHMATCH);
        g.setFont(new Font("Monospaced", Font.BOLD, 21));
        g.setColor(new Color(104, 240, 136));
        String cashStr = freeDm ? "РЕЖИМ DM: БЕСПЛАТНО" : ("БАЛАНС: $" + player.getMoney());
        g.drawString(cashStr, bx + bw - g.getFontMetrics().stringWidth(cashStr) - 28, by + 38);

        int itemX = bx + 28;
        int itemW = bw - 56;
        int iy = by + 56;
        int step = 42;

        drawBuyRow(g, itemX, iy, itemW, "[1] AK-47 — Штурмовая винтовка (ваншот в голову)", freeDm ? 0 : 2700,
                () -> tryPurchaseWeapon(Weapon.Type.AK47)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[2] M4A1-S — Винтовка спецназа с глушителем", freeDm ? 0 : 2900,
                () -> tryPurchaseWeapon(Weapon.Type.M4A1S)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[3] AWP — Снайперская винтовка с оптическим прицелом ПКМ", freeDm ? 0 : 4750,
                () -> tryPurchaseWeapon(Weapon.Type.AWP)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[4] M249 Para — Тяжёлый пулемёт (короб 100 патронов!)", freeDm ? 0 : 5200,
                () -> tryPurchaseWeapon(Weapon.Type.M249)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[5] RPG-7 — Реактивный гранатомёт (против Вертолёта и толп!)", freeDm ? 0 : 4500,
                () -> tryPurchaseWeapon(Weapon.Type.RPG7)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[6] XM1014 — Автоматический дробовик (8 картечин)", freeDm ? 0 : 2000,
                () -> tryPurchaseWeapon(Weapon.Type.XM1014)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[7] Desert Eagle — Крупнокалиберный пистолет", freeDm ? 0 : 700,
                () -> tryPurchaseWeapon(Weapon.Type.DEAGLE)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[8] Полный набор гранат: HE [G] + Flash [V] + Дым [Q]", freeDm ? 0 : 500,
                () -> { tryPurchaseUtility(1); tryPurchaseUtility(2); }); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[9] Броня 100 AP + Аптечка + Щипцы C4 + Шокер Zeus x27 [T]", freeDm ? 0 : 850,
                () -> tryPurchaseUtility(3)); iy += step;
        drawBuyRow(g, itemX, iy, itemW, "[0] Полевая 3D Авто-Турель Спецназа CT (установка на [J])", freeDm ? 0 : 1200,
                () -> tryPurchaseUtility(4));

        addMenuButton(g, new Rectangle(bx + bw / 2 - 140, by + bh - 50, 280, 38),
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

        uiRegisterClickable(rect, onBuy);
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
                            new Font("SansSerif", Font.BOLD, 11), new Color(255, 245, 150));
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
                    sx, sy, new Font("SansSerif", Font.BOLD, 13), new Color(255, 92, 82));
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

        if (plantedC4 != null) {
            boolean blink = Math.sin(plantedC4.blinkPhase) > 0.0;
            String c4Str = String.format("C4: %04.1f", Math.max(0.0, plantedC4.countdown));
            drawCenteredText(g, c4Str, outW / 2, topY + 30,
                    new Font("Monospaced", Font.BOLD, 21), blink ? new Color(255, 76, 68) : new Color(255, 206, 92));
            String subStr = "⚠ ПЛЕНТ " + plantedC4.siteName + " ЗАМИНИРОВАН! ПОДОЙДИТЕ И ЗАЖМИТЕ [E]";
            drawCenteredText(g, subStr, outW / 2, topY + 49,
                    new Font("SansSerif", Font.BOLD, 10), new Color(255, 185, 175));
        } else {
            int totalSec = Math.max(0, (int) Math.ceil(roundTimeLeft));
            String timeStr = String.format("%02d:%02d", totalSec / 60, totalSec % 60);
            drawCenteredText(g, timeStr, outW / 2, topY + 30,
                    new Font("Monospaced", Font.BOLD, 22), totalSec <= 15 ? new Color(255, 95, 85) : Color.WHITE);

            String subStr;
            if (settings.gameMode == GameSettings.GameMode.ARMS_RACE) {
                int tIdx = player.getArmsRaceTier();
                int totalTiers = Weapon.ARMS_RACE_LADDER.length;
                subStr = "ГОНКА ВООРУЖЕНИЙ: УРОВЕНЬ " + (tIdx + 1) + "/" + totalTiers
                        + (tIdx == totalTiers - 1 ? " (ЗОЛОТОЙ НОЖ!)" : " • ФРАГОВ: " + player.getArmsRaceTierKills() + "/2");
            } else if (settings.gameMode == GameSettings.GameMode.SIEGE_WAVES) {
                subStr = "ОСАДА: ВОЛНА " + siegeWave + "/" + MAX_SIEGE_WAVES
                        + " • ВРАГОВ: " + countAliveEnemies()
                        + ((bossHelicopter != null && bossHelicopter.isAlive()) ? " + ВЕРТОЛЁТ!" : "");
            } else if (settings.gameMode == GameSettings.GameMode.DEATHMATCH) {
                subStr = "БОЙ НАСМЕРТЬ (ДО 25 ФРАГОВ) • СЧЕТ: " + player.getKills() + "/25";
            } else {
                int ctAlive = (player.isAlive() ? 1 : 0) + countAliveAllies();
                subStr = "РАУНД " + currentRound + " • СПЕЦНАЗ: " + ctAlive + " VS " + countAliveEnemies() + " ТЕРР.";
            }
            drawCenteredText(g, subStr, outW / 2, topY + 49,
                    new Font("SansSerif", Font.BOLD, 11), new Color(205, 214, 226));
        }

        // Эпичная верхняя шкала здоровья БОЕВОГО ВЕРТОЛЁТА-БОССА в небе!
        if (bossHelicopter != null && bossHelicopter.isAlive()) {
            int hbW = 430, hbH = 28;
            int hbX = outW / 2 - hbW / 2, hbY = topY + topH + 8;
            g.setColor(new Color(20, 12, 14, 222));
            g.fillRoundRect(hbX, hbY, hbW, hbH, 8, 8);
            g.setColor(new Color(255, 86, 62, 200));
            g.drawRoundRect(hbX, hbY, hbW, hbH, 8, 8);

            double frac = Math.max(0.0, Math.min(1.0, (double) bossHelicopter.health / bossHelicopter.maxHealth));
            g.setColor(new Color(236, 58, 46));
            g.fillRoundRect(hbX + 6, hbY + 15, (int) ((hbW - 12) * frac), 8, 4, 4);
            drawCenteredText(g, "▲ БОСС: УДАРНЫЙ БОЕВОЙ ВЕРТОЛЁТ  [" + bossHelicopter.health + " / " + bossHelicopter.maxHealth + " HP] ▲",
                    outW / 2, hbY + 12, new Font("SansSerif", Font.BOLD, 11), new Color(255, 214, 130));
        }

        // Баннер активной радиокоманды спецназу CT [Z]
        if (radioBannerTimer > 0.0 && !radioBannerText.isEmpty()) {
            int rw = 500, rh = 30;
            int rx = 20, ry = 200;
            g.setColor(new Color(12, 24, 38, 214));
            g.fillRoundRect(rx, ry, rw, rh, 8, 8);
            g.setColor(new Color(78, 198, 255, 185));
            g.drawRoundRect(rx, ry, rw, rh, 8, 8);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            g.setColor(new Color(148, 232, 255));
            g.drawString(radioBannerText, rx + 12, ry + 20);
        }

        // Индикатор обезвреживания C4 по клавише E
        if (plantedC4 != null && player.isAlive() && player.getPosition().dist(plantedC4.pos) < 2.95) {
            int dw = 340, dh = 54;
            int dx = outW / 2 - dw / 2, dy = outH / 2 + 56;
            g.setColor(new Color(10, 16, 24, 226));
            g.fillRoundRect(dx, dy, dw, dh, 12, 12);
            g.setColor(new Color(82, 220, 255));
            g.drawRoundRect(dx, dy, dw, dh, 12, 12);

            if (plantedC4.beingDefused) {
                double p = Math.min(1.0, plantedC4.defuseProgress / plantedC4.defuseRequired);
                drawCenteredText(g, String.format("ОБЕЗВРЕЖИВАНИЕ C4... %.1f с",
                        Math.max(0.0, plantedC4.defuseRequired - plantedC4.defuseProgress)),
                        outW / 2, dy + 22, new Font("SansSerif", Font.BOLD, 13), new Color(110, 235, 255));
                g.setColor(new Color(32, 46, 62));
                g.fillRoundRect(dx + 20, dy + 32, dw - 40, 11, 6, 6);
                g.setColor(new Color(72, 226, 255));
                g.fillRoundRect(dx + 20, dy + 32, (int) ((dw - 40) * p), 11, 6, 6);
            } else {
                String kitNote = player.hasDefuseKit() ? " (Набор сапёра: 5 сек)" : " (Без набора: 10 сек)";
                drawCenteredText(g, "ЗАЖМИТЕ [E] — ОБЕЗВРЕДИТЬ БОМБУ C4", outW / 2, dy + 24,
                        new Font("SansSerif", Font.BOLD, 13), new Color(120, 240, 175));
                drawCenteredText(g, "Дистанция в норме" + kitNote, outW / 2, dy + 43,
                        new Font("SansSerif", Font.PLAIN, 11), new Color(205, 218, 232));
            }
        }

        // 2. Панель денег CS2 + здоровья HP и брони AP (снизу слева)
        int hpW = 302, hpH = 74;
        int hpX = 20, hpY = outH - hpH - 22;

        // Плашка валюты $, радио [Z], авто-турели [J] и фонарика [L]
        g.setColor(new Color(14, 20, 26, 208));
        g.fillRoundRect(hpX, hpY - 38, 388, 32, 10, 10);
        g.setFont(new Font("Monospaced", Font.BOLD, 17));
        g.setColor(new Color(102, 242, 136));
        g.drawString("$ " + player.getMoney(), hpX + 12, hpY - 17);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(245, 202, 86));
        String rightBadge = "[B]Маг • [J]Турель:" + player.getSentryCharges() + " • [Z]Радио • [K]Босс";
        g.drawString(rightBadge, hpX + 94, hpY - 17);

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

        // 3. Панель оружия, StatTrak™, патронов и всех 4 гранат CS2 (снизу справа)
        int amW = 338, amH = 124;
        int amX = outW - amW - 20, amY = outH - amH - 22;

        g.setColor(new Color(14, 18, 25, 214));
        g.fillRoundRect(amX, amY, amW, amH, 12, 12);
        g.setColor(new Color(255, 255, 255, 35));
        g.drawRoundRect(amX, amY, amW, amH, 12, 12);

        Weapon activeWeapon = player.getCurrentWeapon();
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.setColor(new Color(248, 202, 82));
        g.drawString(activeWeapon.getDisplayName().toUpperCase(), amX + 16, amY + 23);

        // Оранжевый цифровой счетчик StatTrak™
        g.setColor(new Color(42, 24, 14, 220));
        g.fillRoundRect(amX + 196, amY + 8, 128, 20, 6, 6);
        g.setColor(new Color(255, 132, 36));
        g.setFont(new Font("Monospaced", Font.BOLD, 11));
        g.drawString(String.format("StatTrak™ %04d", player.getKills()), amX + 204, amY + 22);

        // Строка всех 4 гранат CS2
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(206, 220, 236));
        String nades = String.format("[G]HE:%d  [Q]Дым:%d  [V]Флеш:%d  [X]Огонь:%d",
                player.getHeGrenades(), player.getSmokeGrenades(),
                player.getFlashGrenades(), player.getMolotovGrenades());
        g.drawString(nades, amX + 16, amY + 82);

        if (activeWeapon.isMelee()) {
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            g.setColor(Color.WHITE);
            g.drawString("ЛКМ / ПКМ УДАР", amX + 16, amY + 57);
        } else {
            int mag = activeWeapon.getMagAmmo();
            g.setFont(new Font("Monospaced", Font.BOLD, 34));
            g.setColor(mag <= Math.max(2, activeWeapon.getMaxMagAmmo() / 4) ? new Color(255, 92, 82) : Color.WHITE);
            g.drawString(String.format("%2d", mag), amX + 16, amY + 59);

            g.setFont(new Font("Monospaced", Font.BOLD, 20));
            g.setColor(new Color(175, 188, 204));
            int reserveX = (mag >= 100) ? (amX + 86) : (amX + 68);
            g.drawString("/ " + activeWeapon.getReserveAmmo(), reserveX, amY + 57);

            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.setColor(new Color(160, 178, 198));
            g.drawString("[ПКМ] Зум  [F] Осмотр  [H] Скин", amX + 150, amY + 56);
        }

        Weapon[] allW = player.getWeapons();
        int slotCount = player.hasZeus() ? 4 : 3;
        int slotGap = 5;
        int slotW = (amW - 28 - (slotCount - 1) * slotGap) / slotCount;
        for (int i = 0; i < slotCount; i++) {
            int sx = amX + 14 + i * (slotW + slotGap);
            int sy = amY + 93;
            boolean sel = (i == player.getCurrentWeaponIndex());
            g.setColor(sel ? new Color(245, 188, 66, 228) : new Color(36, 44, 56, 190));
            g.fillRoundRect(sx, sy, slotW, 22, 6, 6);
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.setColor(sel ? new Color(18, 20, 24) : new Color(196, 206, 218));
            String keyNum = (i == 3) ? "T" : String.valueOf(i + 1);
            String shortName = (i == 2) ? "Нож" : (i == 3 ? "Zeus"
                    : (allW[i].getDisplayName().length() > 7
                            ? allW[i].getDisplayName().substring(0, 6) : allW[i].getDisplayName()));
            g.drawString(keyNum + ":" + shortName, sx + 5, sy + 15);
        }

        // 4. Фирменные карточки фрагов раунда CS2 внизу по центру экрана!
        int rk = Math.min(8, player.getRoundKills());
        if (rk > 0) {
            int cardW = 36, cardGap = 6;
            int totalW = rk * cardW + (rk - 1) * cardGap;
            int startX = outW / 2 - totalW / 2;
            int cardY = outH - 64;
            for (int i = 0; i < rk; i++) {
                boolean hs = player.isRoundKillHeadshot(i);
                int cx = startX + i * (cardW + cardGap);
                g.setColor(hs ? new Color(245, 172, 38, 228) : new Color(34, 48, 68, 224));
                g.fillRoundRect(cx, cardY, cardW, 42, 8, 8);
                g.setColor(hs ? new Color(255, 236, 140) : new Color(104, 196, 255));
                g.drawRoundRect(cx, cardY, cardW, 42, 8, 8);
                drawCenteredText(g, "☠", cx + cardW / 2, cardY + 23,
                        new Font("SansSerif", Font.BOLD, 17), Color.WHITE);
                drawCenteredText(g, hs ? "HS" : ("K" + (i + 1)), cx + cardW / 2, cardY + 37,
                        new Font("SansSerif", Font.BOLD, 9), hs ? new Color(24, 18, 10) : new Color(215, 230, 248));
            }
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

        // Дымовые завесы, огонь Молотова и мигающий маяк бомбы C4 на радаре
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
        if (plantedC4 != null) {
            int cx = rx + pad + (int) (((plantedC4.pos.x - Map.MIN_X) / span) * inner);
            int cz = ry + pad + (int) (((Map.MAX_Z - plantedC4.pos.z) / span) * inner);
            g.setColor(Math.sin(plantedC4.blinkPhase) > 0.0 ? new Color(255, 52, 42) : new Color(255, 215, 75));
            g.fillOval(cx - 5, cz - 5, 10, 10);
        }
        // Авто-турели CT, десантные ящики Supply Drop и Боевой Вертолёт на радаре!
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
                // Союзники спецназа CT всегда видны на радаре голубыми маркерами!
                int mx = rx + pad + (int) (((b.getPosition().x - Map.MIN_X) / span) * inner);
                int mz = ry + pad + (int) (((Map.MAX_Z - b.getPosition().z) / span) * inner);
                g.setColor(new Color(72, 196, 255));
                g.fillOval(mx - 4, mz - 4, 8, 8);
                continue;
            }
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
            String mapShort = settings.mapTheme.name().replace("_", " ");
            g.drawString(String.format("%s • %d FPS (%.1f мс)", mapShort, currentFps, lastFrameMs), rx + 11, ry + totalH - 8);
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
                outW / 2, by + 28, new Font("SansSerif", Font.BOLD, 16), new Color(252, 212, 92));
        drawCenteredText(g, "Магазин: [B] • Авто-Турель: [J] • Вызов Босс-Вертолёта: [K]",
                outW / 2, by + 50, new Font("SansSerif", Font.PLAIN, 12), new Color(220, 226, 235));
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
        int sh = Math.min(outH - 40, 175 + bots.size() * 29);
        int sw = 684;
        int sx = outW / 2 - sw / 2, sy = outH / 2 - sh / 2;

        g.setColor(new Color(12, 16, 24, 238));
        g.fillRoundRect(sx, sy, sw, sh, 14, 14);
        g.setColor(new Color(255, 255, 255, 65));
        g.drawRoundRect(sx, sy, sw, sh, 14, 14);

        drawCenteredText(g, "ТАБЛИЦА СЧЕТА — " + settings.mapTheme.label.toUpperCase() + " • " + settings.gameMode.label,
                outW / 2, sy + 32, new Font("SansSerif", Font.BOLD, 17), new Color(248, 205, 86));
        drawCenteredText(g, "Счет по раундам: СПЕЦНАЗ CT " + playerRoundWins + " : " + botRoundWins + " ТЕРРОРИСТЫ T",
                outW / 2, sy + 54, new Font("SansSerif", Font.BOLD, 13), new Color(200, 212, 228));

        g.setFont(new Font("SansSerif", Font.BOLD, 12));
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
                outW / 2, cy + 50, new Font("SansSerif", Font.BOLD, 32), accent);
        drawCenteredText(g, victory
                ? ("Спецназ успешно зачистил арену " + settings.mapTheme.label + "!")
                : "Отряд противников одержал верх",
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
        uiRegisterClickable(rect, onClick);
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
