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
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Центральный игровой движок CS-2 JAVA:
 * управляет главным циклом (Game Loop), вводом мыши/клавиатуры, захватом курсора,
 * стрельбой, ИИ ботов, системой раундов, счетом K/D, 3D-сценой и тактическим HUD.
 */
public class Engine extends Canvas implements Runnable {
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_WINDOW_WIDTH = 1280;
    public static final int DEFAULT_WINDOW_HEIGHT = 720;
    private static final int RENDER_WIDTH = 640;
    private static final int RENDER_HEIGHT = 360;

    public static final int BOT_COUNT = 5;
    public static final int ROUNDS_TO_WIN_MATCH = 3;
    private static final double ROUND_TIME_SECONDS = 95.0;

    public enum MatchState {
        PLAYING,
        ROUND_OVER,
        MATCH_VICTORY,
        MATCH_DEFEAT
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

    private static final String[] BOT_NAMES = {
        "BOT Phoenix", "BOT Viper", "BOT Sabre", "BOT Jackal", "BOT Corsair"
    };

    private final Renderer3D renderer;
    private final Map map;
    private final Player player;
    private final List<Bot> bots = new ArrayList<>();
    private final List<Vec3.Tracer> tracers = new ArrayList<>();
    private final List<Vec3.Particle> particles = new ArrayList<>();
    private final List<KillFeedEntry> killFeed = new ArrayList<>();
    private final SoundSystem soundSystem;
    private final Random rng = new Random(2026L);

    private volatile boolean running = false;
    private Thread gameThread;

    // Состояние раундов и матча
    private MatchState matchState = MatchState.PLAYING;
    private int currentRound = 1;
    private int playerRoundWins = 0;
    private int botRoundWins = 0;
    private double roundTimeLeft = ROUND_TIME_SECONDS;
    private double roundBannerTimer = 2.2;
    private double roundOverTimer = 0.0;
    private boolean lastRoundWonByPlayer = false;
    private String roundResultReason = "";

    // Пауза и состояние курсора/ввода
    private boolean paused = false;
    private boolean mouseCaptured = false;
    private Robot robot;
    private Cursor blankCursor;
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    private double mouseSensitivity = 0.0024;

    private final boolean[] keys = new boolean[512];
    private boolean jumpQueued = false;
    private boolean leftMouseDown = false;
    private boolean scoreTabHeld = false;
    private int mouseClientX = DEFAULT_WINDOW_WIDTH / 2;
    private int mouseClientY = DEFAULT_WINDOW_HEIGHT / 2;

    // Кнопки интерактивных экранов (пауза / победа / поражение)
    private final Rectangle primaryButtonRect = new Rectangle();
    private final Rectangle secondaryButtonRect = new Rectangle();
    private final Rectangle quitButtonRect = new Rectangle();

    // Индикаторы HUD (хитмаркер, FPS)
    private double hitMarkerTimer = 0.0;
    private boolean hitMarkerHeadshot = false;
    private int currentFps = 60;

    public Engine(boolean headlessMode) {
        this.renderer = new Renderer3D(RENDER_WIDTH, RENDER_HEIGHT, 65.0);
        this.map = new Map();
        this.player = new Player(map.getPlayerSpawn());
        this.soundSystem = new SoundSystem(!headlessMode);

        setPreferredSize(new Dimension(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);

        if (!headlessMode && !GraphicsEnvironment.isHeadless()) {
            initCursorAndRobot();
            initInputListeners();
        }

        startNewMatch();
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
                if (code >= 0 && code < keys.length) {
                    keys[code] = true;
                }
                handleKeyPress(code);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int code = e.getKeyCode();
                if (code >= 0 && code < keys.length) {
                    keys[code] = false;
                }
                if (code == KeyEvent.VK_TAB) {
                    scoreTabHeld = false;
                }
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                mouseClientX = e.getX();
                mouseClientY = e.getY();
                requestFocusInWindow();

                if (e.getButton() == MouseEvent.BUTTON1) {
                    if (isInteractiveMenuOpen()) {
                        handleMenuClick(e.getX(), e.getY());
                        return;
                    }
                    if (!mouseCaptured) {
                        setMouseCaptured(true);
                    }
                    leftMouseDown = true;
                    tryFireCurrentWeapon();
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
            public void mouseMoved(MouseEvent e) {
                handleMouseMotion(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                handleMouseMotion(e);
            }
        });

        addMouseWheelListener((MouseWheelEvent e) -> {
            if (!paused && matchState == MatchState.PLAYING) {
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

    private void handleKeyPress(int code) {
        if (code == KeyEvent.VK_ESCAPE) {
            if (matchState == MatchState.MATCH_VICTORY || matchState == MatchState.MATCH_DEFEAT) {
                setMouseCaptured(false);
            } else {
                paused = !paused;
                setMouseCaptured(!paused);
                leftMouseDown = false;
            }
            return;
        }

        if (code == KeyEvent.VK_F5 || code == KeyEvent.VK_N) {
            startNewMatch();
            paused = false;
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

        // Настройка чувствительности мыши клавишами [ и ] или - и =
        if (code == KeyEvent.VK_OPEN_BRACKET || code == KeyEvent.VK_MINUS) {
            mouseSensitivity = Math.max(0.0006, mouseSensitivity - 0.0003);
        } else if (code == KeyEvent.VK_CLOSE_BRACKET || code == KeyEvent.VK_EQUALS) {
            mouseSensitivity = Math.min(0.0080, mouseSensitivity + 0.0003);
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
        } else if (code == KeyEvent.VK_R) {
            if (player.isAlive() && player.getCurrentWeapon().startReload()) {
                soundSystem.playReload();
            }
        }
    }

    private void handleMouseMotion(MouseEvent e) {
        mouseClientX = e.getX();
        mouseClientY = e.getY();

        if (!mouseCaptured || paused || isInteractiveMenuOpen() || !isShowing()) {
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

                if (dx == 0 && dy == 0) {
                    return;
                }
                if (Math.abs(dx) < 600 && Math.abs(dy) < 600) {
                    player.applyMouseLook(dx, dy, mouseSensitivity);
                }
                robot.mouseMove(centerX, centerY);
                return;
            } catch (Throwable ignored) {
                // Фолбэк на относительные координаты, если Robot недоступен
            }
        }

        if (lastMouseX >= 0 && lastMouseY >= 0) {
            int dx = e.getX() - lastMouseX;
            int dy = e.getY() - lastMouseY;
            player.applyMouseLook(dx, dy, mouseSensitivity);
        }
        lastMouseX = e.getX();
        lastMouseY = e.getY();
    }

    private boolean isInteractiveMenuOpen() {
        return paused
                || matchState == MatchState.MATCH_VICTORY
                || matchState == MatchState.MATCH_DEFEAT;
    }

    private void handleMenuClick(int mx, int my) {
        if (paused) {
            if (primaryButtonRect.contains(mx, my)) {
                paused = false;
                setMouseCaptured(true);
            } else if (secondaryButtonRect.contains(mx, my)) {
                paused = false;
                startNewMatch();
                setMouseCaptured(true);
            } else if (quitButtonRect.contains(mx, my)) {
                shutdownAndExit();
            }
        } else if (matchState == MatchState.MATCH_VICTORY || matchState == MatchState.MATCH_DEFEAT) {
            if (primaryButtonRect.contains(mx, my)) {
                startNewMatch();
                setMouseCaptured(true);
            } else if (quitButtonRect.contains(mx, my)) {
                shutdownAndExit();
            }
        }
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
        for (int i = 0; i < keys.length; i++) {
            keys[i] = false;
        }
        leftMouseDown = false;
        jumpQueued = false;
        scoreTabHeld = false;
    }

    /**
     * Полный перезапуск матча (сброс раундов и статистики K/D).
     */
    public void startNewMatch() {
        this.currentRound = 1;
        this.playerRoundWins = 0;
        this.botRoundWins = 0;
        this.player.resetMatchStats();
        this.killFeed.clear();
        setupRound();
    }

    private void setupRound() {
        this.matchState = MatchState.PLAYING;
        this.roundTimeLeft = ROUND_TIME_SECONDS;
        this.roundBannerTimer = 2.2;
        this.roundOverTimer = 0.0;
        this.tracers.clear();
        this.particles.clear();
        this.hitMarkerTimer = 0.0;

        player.respawn(map.getPlayerSpawn());

        List<Vec3> spawns = map.getBotSpawns();
        List<Vec3> waypoints = map.getPatrolWaypoints();
        if (bots.isEmpty()) {
            for (int i = 0; i < BOT_COUNT; i++) {
                Vec3 sp = spawns.get(i % spawns.size());
                int wp = (i * 3) % Math.max(1, waypoints.size());
                bots.add(new Bot(BOT_NAMES[i % BOT_NAMES.length], sp, wp, 1000L + i * 37L));
            }
        } else {
            for (int i = 0; i < bots.size(); i++) {
                Vec3 sp = spawns.get((i + currentRound - 1) % spawns.size());
                int wp = (i * 3 + currentRound) % Math.max(1, waypoints.size());
                bots.get(i).respawn(sp, wp);
            }
        }
    }

    private void advanceToNextRound() {
        currentRound++;
        setupRound();
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
        stop();
        System.exit(0);
    }

    @Override
    public void run() {
        createBufferStrategy(3);
        long lastNano = System.nanoTime();
        long fpsTimer = System.currentTimeMillis();
        int frames = 0;

        // Автозахват курсора при старте окна
        if (isShowing()) {
            requestFocusInWindow();
            setMouseCaptured(true);
        }

        final long targetFrameNanos = 1_000_000_000L / 60L;

        while (running) {
            long frameStart = System.nanoTime();
            double dt = (frameStart - lastNano) / 1_000_000_000.0;
            lastNano = frameStart;
            if (dt > 0.05) dt = 0.05;
            if (dt < 0.0001) dt = 0.0001;

            if (!paused) {
                updateGame(dt);
            }
            renderWindowFrame();
            frames++;

            if (System.currentTimeMillis() - fpsTimer >= 1000L) {
                currentFps = frames;
                frames = 0;
                fpsTimer += 1000L;
            }

            long elapsed = System.nanoTime() - frameStart;
            long sleepNanos = targetFrameNanos - elapsed;
            if (sleepNanos > 1_000_000L) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } else {
                Thread.yield();
            }
        }
    }

    /**
     * Один шаг игровой симуляции (может вызываться и из headless-тестов).
     */
    public void updateGame(double dt) {
        if (hitMarkerTimer > 0.0) {
            hitMarkerTimer = Math.max(0.0, hitMarkerTimer - dt);
        }
        if (roundBannerTimer > 0.0) {
            roundBannerTimer = Math.max(0.0, roundBannerTimer - dt);
        }

        // Обновление трассеров, частиц и ленты убийств
        for (Iterator<Vec3.Tracer> it = tracers.iterator(); it.hasNext(); ) {
            Vec3.Tracer tr = it.next();
            tr.life -= dt;
            if (tr.life <= 0.0) it.remove();
        }
        for (Iterator<Vec3.Particle> it = particles.iterator(); it.hasNext(); ) {
            if (!it.next().update(dt)) it.remove();
        }
        for (Iterator<KillFeedEntry> it = killFeed.iterator(); it.hasNext(); ) {
            KillFeedEntry kf = it.next();
            kf.timer -= dt;
            if (kf.timer <= 0.0) it.remove();
        }

        if (matchState == MatchState.MATCH_VICTORY || matchState == MatchState.MATCH_DEFEAT) {
            return;
        }

        boolean w = keys[KeyEvent.VK_W];
        boolean s = keys[KeyEvent.VK_S];
        boolean a = keys[KeyEvent.VK_A];
        boolean d = keys[KeyEvent.VK_D];
        boolean ctrl = keys[KeyEvent.VK_CONTROL] || keys[KeyEvent.VK_C];
        boolean jump = jumpQueued || keys[KeyEvent.VK_SPACE];
        jumpQueued = false;

        player.update(dt, map, w, s, a, d, jump, ctrl);

        if (leftMouseDown && matchState == MatchState.PLAYING && player.isAlive()) {
            tryFireCurrentWeapon();
        }

        // Обновление всех ботов и обработка их ответных выстрелов
        int aliveBots = 0;
        for (int i = 0; i < bots.size(); i++) {
            Bot bot = bots.get(i);
            Bot.ShotEvent shot = bot.update(dt, map, player, bots);
            if (bot.isAlive()) {
                aliveBots++;
            }
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
                finishRound(false, "Игрок погиб в перестрелке");
            } else if (aliveBots == 0) {
                finishRound(true, "Все противники ликвидированы!");
            } else if (roundTimeLeft <= 0.0) {
                roundTimeLeft = 0.0;
                finishRound(false, "Время раунда истекло");
            }
        } else if (matchState == MatchState.ROUND_OVER) {
            roundOverTimer -= dt;
            if (roundOverTimer <= 0.0) {
                advanceToNextRound();
            }
        }
    }

    private void finishRound(boolean playerWon, String reason) {
        this.lastRoundWonByPlayer = playerWon;
        this.roundResultReason = reason;
        leftMouseDown = false;

        if (playerWon) {
            playerRoundWins++;
            soundSystem.playRoundWin();
        } else {
            botRoundWins++;
            soundSystem.playRoundLose();
        }

        if (playerRoundWins >= ROUNDS_TO_WIN_MATCH) {
            matchState = MatchState.MATCH_VICTORY;
            setMouseCaptured(false);
        } else if (botRoundWins >= ROUNDS_TO_WIN_MATCH) {
            matchState = MatchState.MATCH_DEFEAT;
            setMouseCaptured(false);
        } else {
            matchState = MatchState.ROUND_OVER;
            roundOverTimer = 3.3;
        }
    }

    /**
     * Выстрел или удар ножом текущим оружием игрока.
     */
    public void tryFireCurrentWeapon() {
        if (!player.isAlive() || matchState != MatchState.PLAYING) return;

        Weapon weapon = player.getCurrentWeapon();
        if (!weapon.isMelee() && weapon.getMagAmmo() <= 0) {
            if (!weapon.isReloading()) {
                if (weapon.startReload()) {
                    soundSystem.playReload();
                } else {
                    soundSystem.playEmptyClick();
                }
            }
            return;
        }

        if (!weapon.canFire()) {
            return;
        }

        weapon.onFire();

        if (weapon.isMelee()) {
            soundSystem.playKnifeSlash();
            performKnifeAttack(weapon);
        } else {
            if (weapon.getType() == Weapon.Type.AK47) {
                soundSystem.playAkShot();
            } else {
                soundSystem.playGlockShot();
            }
            performGunshot(weapon);
        }
    }

    private void performKnifeAttack(Weapon knife) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookDirection();
        double range = knife.getMaxRange();

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

            // Широкая дуга взмаха ножа вблизи
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
            int dmg = headshot ? knife.getHeadDamage() : knife.getBodyDamage();
            boolean killed = bestBot.takeDamage(dmg, player.getPosition());
            hitMarkerTimer = 0.17;
            hitMarkerHeadshot = headshot;
            soundSystem.playHitMarker(headshot);
            spawnHitParticles(bestBot.getPosition().add(0.0, 1.2, 0.0), look.scale(-1.0), 0xE62E2E, 10);
            if (killed) {
                player.recordKill(headshot);
                addKillFeed("ВЫ", knife.getDisplayName(), bestBot.getName(), headshot, true);
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
        double spread = gun.computeCurrentSpread(moving, airborne, player.isCrouching());

        double spreadX = rng.nextGaussian() * spread;
        double spreadY = rng.nextGaussian() * spread;
        Vec3 shotDir = look.add(right.scale(spreadX)).add(up.scale(spreadY)).normalize();

        player.applyWeaponRecoilKick(gun.getType());

        for (int i = 0; i < bots.size(); i++) {
            bots.get(i).notifyPlayerGunshot(player.getPosition());
        }

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

        Vec3 muzzle = gun.getMuzzleWorldPosition(player);
        tracers.add(new Vec3.Tracer(muzzle, endPoint, 0xFFE37E, 0.075));

        if (hitBot != null && bestBotHit != null) {
            player.recordShot(true);
            int baseDmg = bestBotHit.headshot ? gun.getHeadDamage() : gun.getBodyDamage();
            double falloff = Math.max(0.72, 1.0 - bestBotHit.distance / 150.0);
            int finalDamage = Math.max(1, (int) Math.round(baseDmg * falloff));

            boolean killed = hitBot.takeDamage(finalDamage, player.getPosition());
            hitMarkerTimer = 0.16;
            hitMarkerHeadshot = bestBotHit.headshot;
            soundSystem.playHitMarker(bestBotHit.headshot);

            int particleColor = bestBotHit.headshot ? 0xFFAA22 : 0xDC2626;
            spawnHitParticles(bestBotHit.point, shotDir.scale(-1.0), particleColor, bestBotHit.headshot ? 14 : 9);

            if (killed) {
                player.recordKill(bestBotHit.headshot);
                addKillFeed("ВЫ", gun.getDisplayName(), hitBot.getName(), bestBotHit.headshot, true);
            }
        } else {
            player.recordShot(false);
            if (wallHit != null) {
                spawnHitParticles(wallHit.point, wallHit.normal, 0xE4C896, 7);
                spawnHitParticles(wallHit.point, wallHit.normal, 0xFFD56B, 3);
            }
        }

        // Автоперезарядка при опустевшем магазине
        if (gun.getMagAmmo() == 0 && gun.getReserveAmmo() > 0) {
            if (gun.startReload()) {
                soundSystem.playReload();
            }
        }
    }

    private void spawnHitParticles(Vec3 pos, Vec3 normal, int color, int count) {
        for (int i = 0; i < count; i++) {
            double vx = normal.x * (1.8 + rng.nextDouble() * 2.5) + (rng.nextDouble() - 0.5) * 3.4;
            double vy = normal.y * (1.8 + rng.nextDouble() * 2.5) + rng.nextDouble() * 2.8;
            double vz = normal.z * (1.8 + rng.nextDouble() * 2.5) + (rng.nextDouble() - 0.5) * 3.4;
            double size = 0.045 + rng.nextDouble() * 0.045;
            double life = 0.22 + rng.nextDouble() * 0.28;
            particles.add(new Vec3.Particle(
                pos.x + normal.x * 0.03,
                pos.y + normal.y * 0.03,
                pos.z + normal.z * 0.03,
                vx, vy, vz, color, size, life
            ));
        }
    }

    private void addKillFeed(String killer, String weapon, String victim, boolean headshot, boolean playerInvolved) {
        killFeed.add(0, new KillFeedEntry(killer, weapon, victim, headshot, playerInvolved));
        while (killFeed.size() > 5) {
            killFeed.remove(killFeed.size() - 1);
        }
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
     * Полный рендер кадра (3D-мир + 3D-оружие + весь 2D HUD и экраны) в любой Graphics2D.
     */
    public void renderCompleteFrame(Graphics2D g, int outW, int outH) {
        Vec3 eye = player.getEyePosition();
        renderer.setCamera(eye.x, eye.y, eye.z, player.getYaw(), player.getPitch());
        renderer.beginFrame();

        // 1. 3D-карта Dust 2
        map.render(renderer);

        // 2. 3D-модели ботов
        for (int i = 0; i < bots.size(); i++) {
            bots.get(i).render(renderer);
        }

        // 3. Трассеры пуль и частицы попаданий
        for (int i = 0; i < tracers.size(); i++) {
            Vec3.Tracer tr = tracers.get(i);
            renderer.draw3DLine(tr.start, tr.end, tr.color, 2);
        }
        for (int i = 0; i < particles.size(); i++) {
            renderer.drawParticle(particles.get(i));
        }

        // 4. Модель оружия в руках от первого лица (с очисткой Z-буфера, чтобы ствол не проваливался в стены)
        if (player.isAlive()) {
            renderer.clearDepthBuffer();
            player.getCurrentWeapon().renderViewModel(
                renderer,
                player.getWalkBobPhase(),
                player.getWalkSpeedNormalized(),
                player.getSwayX(),
                player.getSwayY()
            );
        }

        // 5. Вывод 3D-буфера на экран и отрисовка четкого векторного HUD поверх
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(renderer.getFrameImage(), 0, 0, outW, outH, null);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawBotFloatingBars(g, outW, outH);
        drawScreenEffects(g, outW, outH);
        drawCrosshairAndHitmarker(g, outW, outH);
        drawTacticalHud(g, outW, outH);
        drawMinimapRadar(g, 18, 18, 166);
        drawKillFeedAndKd(g, outW);

        if (scoreTabHeld) {
            drawScoreboardOverlay(g, outW, outH);
        }
        if (matchState == MatchState.ROUND_OVER) {
            drawRoundOverBanner(g, outW, outH);
        } else if (roundBannerTimer > 0.0 && matchState == MatchState.PLAYING) {
            drawRoundStartBanner(g, outW, outH);
        }

        if (matchState == MatchState.MATCH_VICTORY || matchState == MatchState.MATCH_DEFEAT) {
            drawMatchEndScreen(g, outW, outH, matchState == MatchState.MATCH_VICTORY);
        } else if (paused) {
            drawPauseMenu(g, outW, outH);
        }
    }

    private void drawBotFloatingBars(Graphics2D g, int outW, int outH) {
        double[] proj = new double[3];
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookDirection();
        double scaleX = (double) outW / RENDER_WIDTH;
        double scaleY = (double) outH / RENDER_HEIGHT;

        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (!b.isAlive()) continue;

            Vec3 headTop = b.getPosition().add(0.0, 2.02, 0.0);
            Vec3 toBot = b.getPosition().add(0.0, 1.2, 0.0).sub(eye);
            double dist = toBot.length();
            boolean aimedAt = dist > 0.1 && look.dot(toBot.scale(1.0 / dist)) > 0.985;

            if (!b.shouldShowHealthBar() && !aimedAt) continue;
            if (!map.hasLineOfSight(eye, b.getEyePosition())) continue;

            if (renderer.projectToScreen(headTop.x, headTop.y, headTop.z, proj)) {
                int sx = (int) (proj[0] * scaleX);
                int sy = (int) (proj[1] * scaleY);
                int barW = Math.max(38, Math.min(76, (int) (420.0 / Math.max(3.5, proj[2]))));
                int barH = 6;
                int bx = sx - barW / 2;
                int by = sy - barH / 2;

                g.setColor(new Color(15, 18, 22, 195));
                g.fillRoundRect(bx - 2, by - 2, barW + 4, barH + 4, 4, 4);

                int hpW = (int) ((barW * b.getHealth()) / 100.0);
                g.setColor(b.getHealth() > 45 ? new Color(235, 72, 65) : new Color(255, 165, 45));
                g.fillRect(bx, by, hpW, barH);

                if (aimedAt) {
                    String tag = b.getName() + " (" + b.getHealth() + " HP)";
                    FontMetrics fm = g.getFontMetrics();
                    g.setColor(new Color(255, 220, 210));
                    g.drawString(tag, sx - fm.stringWidth(tag) / 2, by - 5);
                }
            }
        }
    }

    private void drawScreenEffects(Graphics2D g, int outW, int outH) {
        // Легкая теплая вспышка освещения при выстреле
        if (player.isAlive() && player.getCurrentWeapon().hasMuzzleFlash()) {
            g.setColor(new Color(255, 210, 110, 22));
            g.fillRect(0, 0, outW, outH);
        }

        // Красная виньетка получения урона и дуга направления атаки
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
                double worldAngle = Math.atan2(dx, dz);
                double relAngle = worldAngle - player.getYaw();
                int cx = outW / 2;
                int cy = outH / 2;
                int radius = Math.min(outW, outH) / 5;
                int ix = cx + (int) (Math.sin(relAngle) * radius);
                int iy = cy - (int) (Math.cos(relAngle) * radius);
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

        int cx = outW / 2;
        int cy = outH / 2;
        Weapon w = player.getCurrentWeapon();

        // Динамический прицел в стиле Counter-Strike 2
        double spread = w.computeCurrentSpread(
                player.getHorizontalSpeed() > 1.2, !player.isOnGround(), player.isCrouching());
        int gap = 5 + (int) (spread * 190.0);
        int len = 9;

        g.setColor(new Color(0, 0, 0, 185));
        g.fillRect(cx - gap - len - 1, cy - 2, len + 2, 4);
        g.fillRect(cx + gap - 1, cy - 2, len + 2, 4);
        g.fillRect(cx - 2, cy - gap - len - 1, 4, len + 2);
        g.fillRect(cx - 2, cy + gap - 1, 4, len + 2);

        g.setColor(new Color(90, 255, 135));
        g.fillRect(cx - gap - len, cy - 1, len, 2);
        g.fillRect(cx + gap, cy - 1, len, 2);
        g.fillRect(cx - 1, cy - gap - len, 2, len);
        g.fillRect(cx - 1, cy + gap, 2, len);
        g.fillRect(cx - 1, cy - 1, 2, 2);

        // Хитмаркер попадания по боту
        if (hitMarkerTimer > 0.0) {
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(hitMarkerHeadshot ? new Color(255, 80, 65) : Color.WHITE);
            int hmInner = 5;
            int hmOuter = 13;
            g.drawLine(cx - hmOuter, cy - hmOuter, cx - hmInner, cy - hmInner);
            g.drawLine(cx + hmOuter, cy - hmOuter, cx + hmInner, cy - hmInner);
            g.drawLine(cx - hmOuter, cy + hmOuter, cx - hmInner, cy + hmInner);
            g.drawLine(cx + hmOuter, cy + hmOuter, cx + hmInner, cy + hmInner);
            g.setStroke(new BasicStroke(1.0f));
        }

        // Индикатор перезарядки возле прицела
        if (w.isReloading()) {
            int barW = 110;
            int barH = 7;
            int bx = cx - barW / 2;
            int by = cy + 36;
            g.setColor(new Color(15, 20, 26, 200));
            g.fillRoundRect(bx - 2, by - 2, barW + 4, barH + 4, 6, 6);
            g.setColor(new Color(245, 196, 68));
            g.fillRoundRect(bx, by, (int) (barW * w.getReloadProgress()), barH, 4, 4);

            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            String txt = "ПЕРЕЗАРЯДКА...";
            FontMetrics fm = g.getFontMetrics();
            g.setColor(new Color(255, 235, 175));
            g.drawString(txt, cx - fm.stringWidth(txt) / 2, by - 6);
        }
    }

    private void drawTacticalHud(Graphics2D g, int outW, int outH) {
        // 1. Верхняя центральная панель раунда, таймера и счета команд (CT vs T)
        int topW = 412;
        int topH = 58;
        int topX = outW / 2 - topW / 2;
        int topY = 14;

        g.setColor(new Color(16, 20, 28, 212));
        g.fillRoundRect(topX, topY, topW, topH, 12, 12);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect(topX, topY, topW, topH, 12, 12);

        // Счет спецназа CT (слева)
        g.setColor(new Color(74, 158, 245, 220));
        g.fillRoundRect(topX + 10, topY + 8, 76, 42, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("CT", topX + 18, topY + 24);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString(String.valueOf(playerRoundWins), topX + 54, topY + 38);

        // Счет террористов T (справа)
        g.setColor(new Color(224, 152, 52, 220));
        g.fillRoundRect(topX + topW - 86, topY + 8, 76, 42, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("BOTS", topX + topW - 50, topY + 24);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString(String.valueOf(botRoundWins), topX + topW - 76, topY + 38);

        // Таймер раунда и номер раунда
        int totalSec = Math.max(0, (int) Math.ceil(roundTimeLeft));
        String timeStr = String.format("%02d:%02d", totalSec / 60, totalSec % 60);
        g.setFont(new Font("Monospaced", Font.BOLD, 22));
        FontMetrics fmTime = g.getFontMetrics();
        g.setColor(totalSec <= 15 ? new Color(255, 95, 85) : Color.WHITE);
        g.drawString(timeStr, outW / 2 - fmTime.stringWidth(timeStr) / 2, topY + 30);

        int aliveBots = countAliveBots();
        String subStr = "РАУНД " + currentRound + " (ДО " + ROUNDS_TO_WIN_MATCH + ") • ВРАГОВ: " + aliveBots;
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        FontMetrics fmSub = g.getFontMetrics();
        g.setColor(new Color(205, 214, 226));
        g.drawString(subStr, outW / 2 - fmSub.stringWidth(subStr) / 2, topY + 49);

        // 2. Нижняя левая панель здоровья (HP) и брони (AP)
        int hpW = 298;
        int hpH = 74;
        int hpX = 20;
        int hpY = outH - hpH - 22;

        g.setColor(new Color(14, 18, 25, 212));
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

        // Полоса HP
        int barStartX = hpX + 124;
        int barWidth = 156;
        g.setColor(new Color(45, 52, 62));
        g.fillRoundRect(barStartX, hpY + 18, barWidth, 10, 5, 5);
        g.setColor(hpColor);
        g.fillRoundRect(barStartX, hpY + 18, (int) (barWidth * (hp / 100.0)), 10, 5, 5);

        // Броня AP + статус приседания
        int ap = player.getArmor();
        g.setColor(new Color(95, 178, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        String stance = player.isCrouching() ? " [ПРИСЕД]" : "";
        g.drawString("БРОНЯ: " + ap + stance, barStartX, hpY + 47);
        g.setColor(new Color(45, 52, 62));
        g.fillRoundRect(barStartX, hpY + 55, barWidth, 7, 4, 4);
        g.setColor(new Color(82, 168, 250));
        g.fillRoundRect(barStartX, hpY + 55, (int) (barWidth * (ap / 100.0)), 7, 4, 4);

        // 3. Нижняя правая панель патронов и слотов оружия (1, 2, 3)
        int amW = 290;
        int amH = 98;
        int amX = outW - amW - 20;
        int amY = outH - amH - 22;

        g.setColor(new Color(14, 18, 25, 212));
        g.fillRoundRect(amX, amY, amW, amH, 12, 12);
        g.setColor(new Color(255, 255, 255, 35));
        g.drawRoundRect(amX, amY, amW, amH, 12, 12);

        Weapon activeWeapon = player.getCurrentWeapon();
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(new Color(248, 202, 82));
        g.drawString(activeWeapon.getDisplayName().toUpperCase(), amX + 16, amY + 25);

        if (activeWeapon.isMelee()) {
            g.setFont(new Font("SansSerif", Font.BOLD, 28));
            g.setColor(Color.WHITE);
            g.drawString("БЛИЖНИЙ БОЙ", amX + 16, amY + 58);
        } else {
            int mag = activeWeapon.getMagAmmo();
            g.setFont(new Font("Monospaced", Font.BOLD, 34));
            g.setColor(mag <= 6 ? new Color(255, 92, 82) : Color.WHITE);
            String magStr = String.format("%2d", mag);
            g.drawString(magStr, amX + 16, amY + 60);

            g.setFont(new Font("Monospaced", Font.BOLD, 20));
            g.setColor(new Color(175, 188, 204));
            g.drawString("/ " + activeWeapon.getReserveAmmo(), amX + 68, amY + 58);
        }

        // Индикатор слотов 1, 2, 3
        Weapon[] allW = player.getWeapons();
        int slotW = 84;
        for (int i = 0; i < allW.length; i++) {
            int sx = amX + 14 + i * (slotW + 6);
            int sy = amY + 70;
            boolean sel = (i == player.getCurrentWeaponIndex());
            g.setColor(sel ? new Color(245, 188, 66, 225) : new Color(36, 44, 56, 190));
            g.fillRoundRect(sx, sy, slotW, 20, 6, 6);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(sel ? new Color(18, 20, 24) : new Color(196, 206, 218));
            String label = (i + 1) + ": " + (i == 2 ? "Нож" : allW[i].getDisplayName());
            g.drawString(label, sx + 6, sy + 14);
        }

        // Короткая подсказка управления внизу по центру
        String help = "WASD Движение | Space Прыжок | Ctrl Присед | ЛКМ Огонь | R Перезарядка | 1-3 Оружие | Tab Счет | F5 Рестарт | ESC Пауза";
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        FontMetrics fmHelp = g.getFontMetrics();
        int hw = fmHelp.stringWidth(help) + 20;
        if (hw < outW - hpW - amW - 60) {
            g.setColor(new Color(12, 16, 22, 155));
            g.fillRoundRect(outW / 2 - hw / 2, outH - 28, hw, 20, 8, 8);
            g.setColor(new Color(205, 214, 225));
            g.drawString(help, outW / 2 - fmHelp.stringWidth(help) / 2, outH - 14);
        }
    }

    private void drawMinimapRadar(Graphics2D g, int rx, int ry, int size) {
        int totalH = size + 18;
        g.setColor(new Color(14, 18, 25, 214));
        g.fillRoundRect(rx, ry, size, totalH, 12, 12);
        g.setColor(new Color(255, 255, 255, 45));
        g.drawRoundRect(rx, ry, size, totalH, 12, 12);

        int pad = 10;
        int inner = size - pad * 2;
        double span = Map.MAX_X - Map.MIN_X;

        g.setColor(new Color(24, 28, 36, 200));
        g.fillRect(rx + pad, ry + pad, inner, inner);

        // Отрисовка блоков арены на радаре
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

        // Враги на радаре (отображаются при прямой видимости или во время стрельбы, как в CS2)
        Vec3 eye = player.getEyePosition();
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            if (!b.isAlive()) continue;
            boolean visibleOnRadar = b.isFiringRecently()
                    || b.getState() == Bot.State.ENGAGE
                    || map.hasLineOfSight(eye, b.getEyePosition());
            if (!visibleOnRadar) continue;

            int mx = rx + pad + (int) (((b.getPosition().x - Map.MIN_X) / span) * inner);
            int mz = ry + pad + (int) (((Map.MAX_Z - b.getPosition().z) / span) * inner);
            g.setColor(new Color(255, 68, 62));
            g.fillOval(mx - 4, mz - 4, 8, 8);
        }

        // Маркер игрока и конус направления взгляда
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

        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.setColor(new Color(214, 222, 234));
        g.drawString("DUST II RADAR  •  " + currentFps + " FPS", rx + 12, ry + totalH - 8);
    }

    private void drawKillFeedAndKd(Graphics2D g, int outW) {
        // Постоянная панель K/D справа вверху
        String kdText = String.format("K: %d   D: %d   K/D: %.2f   HS: %d",
                player.getKills(), player.getDeaths(), player.getKdRatio(), player.getHeadshots());
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        int boxW = fm.stringWidth(kdText) + 28;
        int boxX = outW - boxW - 18;
        int boxY = 16;

        g.setColor(new Color(14, 18, 25, 212));
        g.fillRoundRect(boxX, boxY, boxW, 32, 10, 10);
        g.setColor(new Color(245, 195, 72, 140));
        g.drawRoundRect(boxX, boxY, boxW, 32, 10, 10);
        g.setColor(new Color(245, 247, 250));
        g.drawString(kdText, boxX + 14, boxY + 21);

        // Лента убийств (Killfeed) под счетчиком K/D
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics kfm = g.getFontMetrics();
        int ky = boxY + 42;
        for (int i = 0; i < killFeed.size(); i++) {
            KillFeedEntry e = killFeed.get(i);
            String hsTag = e.headshot ? " [HS]" : "";
            String line = e.killer + "  [" + e.weapon + hsTag + "]  " + e.victim;
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
        int bw = 420;
        int bh = 66;
        int bx = outW / 2 - bw / 2;
        int by = 92;
        g.setColor(new Color(16, 20, 28, 195));
        g.fillRoundRect(bx, by, bw, bh, 12, 12);
        g.setColor(new Color(245, 195, 72, 175));
        g.drawRoundRect(bx, by, bw, bh, 12, 12);

        drawCenteredText(g, "РАУНД " + currentRound + " НАЧАЛСЯ", outW / 2, by + 30,
                new Font("SansSerif", Font.BOLD, 20), new Color(252, 212, 92));
        drawCenteredText(g, "Уничтожьте отряд из " + bots.size() + " ботов на арене Dust II", outW / 2, by + 52,
                new Font("SansSerif", Font.PLAIN, 13), new Color(220, 226, 235));
    }

    private void drawRoundOverBanner(Graphics2D g, int outW, int outH) {
        int bw = 510;
        int bh = 118;
        int bx = outW / 2 - bw / 2;
        int by = 104;

        Color accent = lastRoundWonByPlayer ? new Color(76, 176, 255) : new Color(238, 118, 62);
        g.setColor(new Color(14, 18, 26, 228));
        g.fillRoundRect(bx, by, bw, bh, 14, 14);
        g.setColor(accent);
        g.drawRoundRect(bx, by, bw, bh, 14, 14);

        String title = lastRoundWonByPlayer
                ? "РАУНД ВЫИГРАН! (COUNTER-TERRORISTS)"
                : "РАУНД ПРОИГРАН! (TERRORISTS WIN)";
        drawCenteredText(g, title, outW / 2, by + 36, new Font("SansSerif", Font.BOLD, 21), accent);
        drawCenteredText(g, roundResultReason, outW / 2, by + 64,
                new Font("SansSerif", Font.PLAIN, 15), Color.WHITE);
        drawCenteredText(g, String.format("Следующий раунд через %.1f сек. (Enter / Space — ускорить, F5 — рестарт)",
                        Math.max(0.0, roundOverTimer)),
                outW / 2, by + 95, new Font("SansSerif", Font.PLAIN, 12), new Color(195, 205, 218));
    }

    private void drawScoreboardOverlay(Graphics2D g, int outW, int outH) {
        int sw = 560;
        int sh = 340;
        int sx = outW / 2 - sw / 2;
        int sy = outH / 2 - sh / 2;

        g.setColor(new Color(12, 16, 24, 232));
        g.fillRoundRect(sx, sy, sw, sh, 14, 14);
        g.setColor(new Color(255, 255, 255, 65));
        g.drawRoundRect(sx, sy, sw, sh, 14, 14);

        drawCenteredText(g, "ТАБЛИЦА СЧЕТА — DUST II ARENA", outW / 2, sy + 34,
                new Font("SansSerif", Font.BOLD, 19), new Color(248, 205, 86));
        drawCenteredText(g, "Счет по раундам: CT " + playerRoundWins + " : " + botRoundWins + " BOTS",
                outW / 2, sy + 58, new Font("SansSerif", Font.BOLD, 14), new Color(200, 212, 228));

        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(new Color(145, 162, 184));
        g.drawString("БОЕЦ", sx + 26, sy + 92);
        g.drawString("СТАТУС", sx + 220, sy + 92);
        g.drawString("HP", sx + 335, sy + 92);
        g.drawString("СТАТИСТИКА", sx + 405, sy + 92);

        // Строка игрока
        g.setColor(new Color(45, 92, 148, 130));
        g.fillRoundRect(sx + 18, sy + 102, sw - 36, 30, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString("ВЫ (Counter-Terrorist)", sx + 26, sy + 122);
        g.drawString(player.isAlive() ? "ЖИВ" : "УБИТ", sx + 220, sy + 122);
        g.drawString(String.valueOf(player.getHealth()), sx + 335, sy + 122);
        g.drawString(String.format("%d K / %d D (%.2f)", player.getKills(), player.getDeaths(), player.getKdRatio()),
                sx + 405, sy + 122);

        int rowY = sy + 148;
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            g.setColor(new Color(65, 42, 34, 120));
            g.fillRoundRect(sx + 18, rowY, sw - 36, 28, 8, 8);
            g.setColor(b.isAlive() ? new Color(245, 215, 185) : new Color(138, 144, 154));
            g.drawString(b.getName(), sx + 26, rowY + 19);
            g.drawString(b.isAlive() ? b.getState().name() : "УБИТ", sx + 220, rowY + 19);
            g.drawString(String.valueOf(b.getHealth()), sx + 335, rowY + 19);
            g.drawString("Террорист (ИИ)", sx + 405, rowY + 19);
            rowY += 34;
        }
    }

    private void drawMatchEndScreen(Graphics2D g, int outW, int outH, boolean victory) {
        Composite oldComp = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.78f));
        g.setColor(new Color(8, 11, 16));
        g.fillRect(0, 0, outW, outH);
        g.setComposite(oldComp);

        int cw = 540;
        int ch = 400;
        int cx = outW / 2 - cw / 2;
        int cy = outH / 2 - ch / 2;

        Color accent = victory ? new Color(82, 224, 146) : new Color(245, 84, 76);

        g.setColor(new Color(16, 21, 30, 245));
        g.fillRoundRect(cx, cy, cw, ch, 18, 18);
        g.setStroke(new BasicStroke(2.2f));
        g.setColor(accent);
        g.drawRoundRect(cx, cy, cw, ch, 18, 18);
        g.setStroke(new BasicStroke(1.0f));

        String header = victory ? "ПОБЕДА В МАТЧЕ!" : "ПОРАЖЕНИЕ В МАТЧЕ";
        String sub = victory
                ? "Спецназ успешно зачистил арену Dust II!"
                : "Отряд противников одержал верх в серии раундов";

        drawCenteredText(g, header, outW / 2, cy + 52, new Font("SansSerif", Font.BOLD, 32), accent);
        drawCenteredText(g, sub, outW / 2, cy + 82, new Font("SansSerif", Font.PLAIN, 15), new Color(214, 222, 234));

        // Итоговая карточка статистики
        int statX = cx + 44;
        int statY = cy + 106;
        int statW = cw - 88;
        int statH = 152;
        g.setColor(new Color(25, 32, 44));
        g.fillRoundRect(statX, statY, statW, statH, 12, 12);

        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.setColor(new Color(248, 206, 90));
        drawCenteredText(g, "ИТОГОВЫЙ СЧЕТ РАУНДОВ:  " + playerRoundWins + " : " + botRoundWins,
                outW / 2, statY + 32, new Font("SansSerif", Font.BOLD, 18), new Color(248, 206, 90));

        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.setColor(Color.WHITE);
        g.drawString("Убийств (Kills): " + player.getKills(), statX + 28, statY + 70);
        g.drawString("Смертей (Deaths): " + player.getDeaths(), statX + 250, statY + 70);
        g.drawString(String.format("Рейтинг K/D: %.2f", player.getKdRatio()), statX + 28, statY + 102);
        g.drawString("В голову (Headshots): " + player.getHeadshots(), statX + 250, statY + 102);
        g.drawString(String.format("Точность стрельбы: %.1f%% (%d/%d)",
                player.getAccuracyPercent(), player.getShotsHit(), player.getShotsFired()),
                statX + 28, statY + 134);

        // Кнопка рестарта и кнопка выхода
        primaryButtonRect.setBounds(cx + 80, cy + 282, cw - 160, 46);
        quitButtonRect.setBounds(cx + 150, cy + 338, cw - 300, 38);

        drawButton(g, primaryButtonRect, "НАЧАТЬ НОВЫЙ МАТЧ (ENTER / F5)",
                primaryButtonRect.contains(mouseClientX, mouseClientY), new Color(56, 158, 98));
        drawButton(g, quitButtonRect, "ВЫЙТИ НА РАБОЧИЙ СТОЛ",
                quitButtonRect.contains(mouseClientX, mouseClientY), new Color(92, 50, 54));
    }

    private void drawPauseMenu(Graphics2D g, int outW, int outH) {
        g.setColor(new Color(8, 12, 18, 188));
        g.fillRect(0, 0, outW, outH);

        int mw = 460;
        int mh = 340;
        int mx = outW / 2 - mw / 2;
        int my = outH / 2 - mh / 2;

        g.setColor(new Color(16, 22, 32, 244));
        g.fillRoundRect(mx, my, mw, mh, 16, 16);
        g.setColor(new Color(245, 196, 76, 170));
        g.drawRoundRect(mx, my, mw, mh, 16, 16);

        drawCenteredText(g, "ПАУЗА — CS-2 JAVA", outW / 2, my + 44,
                new Font("SansSerif", Font.BOLD, 24), new Color(248, 206, 88));
        drawCenteredText(g, String.format("Чувствительность мыши: %.4f  (клавиши [ и ])", mouseSensitivity),
                outW / 2, my + 74, new Font("SansSerif", Font.PLAIN, 13), new Color(198, 208, 222));

        primaryButtonRect.setBounds(mx + 65, my + 108, mw - 130, 46);
        secondaryButtonRect.setBounds(mx + 65, my + 168, mw - 130, 46);
        quitButtonRect.setBounds(mx + 65, my + 228, mw - 130, 44);

        drawButton(g, primaryButtonRect, "ПРОДОЛЖИТЬ ИГРУ (ESC)",
                primaryButtonRect.contains(mouseClientX, mouseClientY), new Color(52, 132, 206));
        drawButton(g, secondaryButtonRect, "ПЕРЕЗАПУСТИТЬ МАТЧ (F5)",
                secondaryButtonRect.contains(mouseClientX, mouseClientY), new Color(188, 132, 42));
        drawButton(g, quitButtonRect, "ВЫЙТИ ИЗ ИГРЫ",
                quitButtonRect.contains(mouseClientX, mouseClientY), new Color(138, 52, 52));

        drawCenteredText(g, "Курсор освобожден — нажмите на кнопку или клавишу ESC",
                outW / 2, my + 306, new Font("SansSerif", Font.PLAIN, 12), new Color(168, 178, 192));
    }

    private void drawButton(Graphics2D g, Rectangle rect, String label, boolean hovered, Color baseColor) {
        Color fill = hovered ? baseColor.brighter() : baseColor;
        g.setColor(fill);
        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
        g.setColor(hovered ? Color.WHITE : new Color(255, 255, 255, 110));
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
        drawCenteredText(g, label, rect.x + rect.width / 2, rect.y + rect.height / 2 + 5,
                new Font("SansSerif", Font.BOLD, 14), Color.WHITE);
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

    // Геттеры для интеграции и самотестирования
    public Player getPlayer() { return player; }
    public Map getMap() { return map; }
    public List<Bot> getBots() { return bots; }
    public MatchState getMatchState() { return matchState; }
    public int getPlayerRoundWins() { return playerRoundWins; }
    public int getBotRoundWins() { return botRoundWins; }
}
