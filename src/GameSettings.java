import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Центральное хранилище настроек и карьерного прогресса CS-2 JAVA 6.0
 * (Multi-Core Ultra-Smooth Engine & Panorama UI Edition):
 * - Нативное 1:1 разрешение под размер окна/монитора (0% пикселизации!), 512x512 Mipmap + Span FXAA
 * - Многопоточный рендер на всех ядрах процессора + Frustum Culling + Early-Z
 * - Система званий CS2 (Silver -> Global Elite), XP-прогресс, статистика карьеры
 */
public class GameSettings {

    public static final int CONFIG_VERSION = 6;

    public enum QualityPreset {
        LOW("Низкое (Турбо FPS)"),
        MEDIUM("Среднее (Плавный HD)"),
        HIGH("Высокое (Native 1:1 + FXAA)"),
        ULTRA("Ультра (1:1 FullHD + Все эффекты)");

        public final String title;
        QualityPreset(String title) { this.title = title; }
    }

    public enum ResolutionScale {
        LOW_854("960×540 (Турбо FPS)", 960, 540),
        MED_1024("1152×648 (Быстрый HD)", 1152, 648),
        HIGH_1280("1280×720 (HD 720p)", 1280, 720),
        ULTRA_1600("1600×900 (HD+ 900p)", 1600, 900),
        EXTREME_1920("1920×1080 (Full HD 1080p)", 1920, 1080),
        NATIVE_WINDOW("Нативное 1:1 (Без пикселей!)", 0, 0);

        public final String label;
        public final int width;
        public final int height;

        ResolutionScale(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        public int resolveWidth(int windowW) {
            if (this == NATIVE_WINDOW) {
                return Math.max(960, Math.min(1920, windowW));
            }
            return width;
        }

        public int resolveHeight(int windowH) {
            if (this == NATIVE_WINDOW) {
                return Math.max(540, Math.min(1080, windowH));
            }
            return height;
        }
    }

    public enum ShadowMode {
        OFF("Выкл"),
        BLOBS("Мягкие контактные"),
        SUN_PROJECTED("Солнечные 3D-тени");

        public final String label;
        ShadowMode(String label) { this.label = label; }
    }

    public enum TimeOfDay {
        DAY("Полдень (Солнечно)"),
        SUNSET("Золотой закат"),
        NIGHT("Ночная операция");

        public final String label;
        TimeOfDay(String label) { this.label = label; }
    }

    public enum WeatherMode {
        CLEAR("Ясно"),
        RAIN_STORM("Ливень и гроза"),
        SNOW("Снегопад");

        public final String label;
        WeatherMode(String label) { this.label = label; }
    }

    public enum BotDifficulty {
        EASY("Новичок", 0.55, 0.072, 3.0, 9),
        NORMAL("Спецназ", 0.34, 0.046, 4.2, 13),
        HARD("Global Elite", 0.17, 0.025, 5.1, 18);

        public final String label;
        public final double reactionDelay;
        public final double aimSpread;
        public final double combatSpeed;
        public final int baseDamage;

        BotDifficulty(String label, double reactionDelay, double aimSpread, double combatSpeed, int baseDamage) {
            this.label = label;
            this.reactionDelay = reactionDelay;
            this.aimSpread = aimSpread;
            this.combatSpeed = combatSpeed;
            this.baseDamage = baseDamage;
        }
    }

    public enum CrosshairColor {
        GREEN("Изумрудный CS2", new Color(76, 255, 138)),
        CYAN("Кибер-Голубой", new Color(64, 240, 255)),
        YELLOW("Золотой", new Color(255, 226, 68)),
        WHITE("Кристально-белый", new Color(248, 250, 255)),
        MAGENTA("Неон-Пурпурный", new Color(255, 88, 232));

        public final String label;
        public final Color color;
        CrosshairColor(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    public enum MapTheme {
        INFERNO("Inferno", "Старый город • Фонтан", new Color(235, 118, 52)),
        DUST_II("Dust II", "Песчаный каньон • Мид", new Color(228, 182, 78)),
        MIRAGE("Mirage", "Королевский дворец", new Color(214, 96, 132)),
        NUKE("Nuke", "Реакторный комплекс", new Color(66, 178, 245));

        public final String shortName;
        public final String subtitle;
        public final Color accent;
        public final String label;

        MapTheme(String shortName, String subtitle, Color accent) {
            this.shortName = shortName;
            this.subtitle = subtitle;
            this.accent = accent;
            this.label = shortName + " (" + subtitle.split(" • ")[0] + ")";
        }
    }

    public enum GameMode {
        SIEGE_WAVES("Осада: Босс-Вертолёт", "5 волн • Джаггернауты • ПВО"),
        BOMB_DEFUSAL("Операция C4", "Пленты A/B • Тактика 5v5"),
        ARMS_RACE("Гонка вооружений", "8 уровней до Золотого Керамбита"),
        ELIMINATION("Зачистка спецназа", "Командный бой на выбывание"),
        DEATHMATCH("Бой насмерть", "Мгновенное возрождение • Все пушки");

        public final String label;
        public final String description;

        GameMode(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    public enum WeaponSkin {
        STANDARD("Военный Стандарт", 0, 0, 0),
        ASIIMOV("Asiimov (Азимов)", 0xF0F4F8, 0xFF6B1A, 0x1B2029),
        GOLD_FADE("Золотой Градиент (Fade)", 0xF6C63C, 0xE8387C, 0x662C9A),
        REDLINE("Redline (Карбон)", 0x1A1E26, 0xEC2B3D, 0x343C48),
        PRINTSTREAM("Printstream (Перламутр)", 0xECF2FA, 0x32EAFF, 0x1A2334),
        HYPER_BEAST("Неоновый Зверь", 0x1E2968, 0xFF3C8E, 0x2EFFB4);

        public final String label;
        public final int primary;
        public final int accent;
        public final int trim;

        WeaponSkin(String label, int primary, int accent, int trim) {
            this.label = label;
            this.primary = primary;
            this.accent = accent;
            this.trim = trim;
        }
    }

    private static final String[] RANK_NAMES = {
        "Silver I (Серебро)",
        "Silver Elite",
        "Gold Nova I",
        "Gold Nova Master",
        "Master Guardian",
        "Master Guardian Elite",
        "Distinguished MG",
        "Legendary Eagle (Беркут)",
        "Supreme Master",
        "★ THE GLOBAL ELITE ★"
    };

    private static final Color[] RANK_COLORS = {
        new Color(168, 178, 192),
        new Color(192, 204, 218),
        new Color(242, 198, 72),
        new Color(255, 214, 64),
        new Color(74, 196, 255),
        new Color(58, 162, 255),
        new Color(158, 112, 255),
        new Color(238, 134, 56),
        new Color(248, 76, 96),
        new Color(255, 215, 58)
    };

    private static final String SETTINGS_FILE = "settings.properties";

    // --- НАСТРОЙКИ ГЛАДКОЙ НЕПИКСЕЛЬНОЙ HD-ГРАФИКИ И МНОГОЯДЕРНОЙ ОПТИМИЗАЦИИ ---
    public QualityPreset qualityPreset = QualityPreset.HIGH;
    public ResolutionScale resolutionScale = ResolutionScale.NATIVE_WINDOW;
    public ShadowMode shadowMode = ShadowMode.SUN_PROJECTED;
    public boolean bilinearFiltering = true;
    public boolean smoothTextures = true;
    public boolean fxaaAntiAliasing = true;
    public boolean multiCoreRendering = true;
    public boolean skyCloudsAndSunFlare = true;
    public boolean decalsAndShells = true;
    public boolean fogAndVignette = true;
    public int fovDegrees = 70;         // Вертикальный FOV (56..86)
    public int fpsLimit = 120;          // 60, 120, 240 или 0 (без лимита)
    public boolean showFpsTelemetry = true;
    public boolean fullscreen = false;
    public TimeOfDay timeOfDay = TimeOfDay.DAY;
    public WeatherMode weatherMode = WeatherMode.CLEAR;

    // --- НАСТРОЙКИ ГЕЙМПЛЕЯ И УПРАВЛЕНИЯ ---
    public double mouseSensitivity = 0.0024;
    public boolean invertMouseY = false;
    public CrosshairColor crosshairColor = CrosshairColor.GREEN;
    public boolean dynamicCrosshair = true;
    public boolean leftHandedModel = false;
    public boolean showDamageNumbers = true;

    // --- ПАРАМЕТРЫ МАТЧА ---
    public MapTheme mapTheme = MapTheme.INFERNO;
    public GameMode gameMode = GameMode.SIEGE_WAVES;
    public WeaponSkin weaponSkin = WeaponSkin.ASIIMOV;
    public BotDifficulty botDifficulty = BotDifficulty.NORMAL;
    public int botCount = 5;
    public int allyCount = 2;
    public int roundsToWin = 5;

    // --- КАРЬЕРА И РАНГ ИГРОКА CS2 ---
    public int careerXp = 220;
    public int careerKills = 0;
    public int careerHeadshots = 0;
    public int careerWins = 0;

    // --- НАСТРОЙКИ АУДИО ---
    public double masterVolume = 0.85;
    public boolean footstepSounds = true;

    private final Object saveLock = new Object();
    private volatile boolean savePending = false;

    public GameSettings() {
        load();
    }

    public int getRankIndex() {
        return Math.max(0, Math.min(RANK_NAMES.length - 1, careerXp / 400));
    }

    public String getRankTitle() {
        return RANK_NAMES[getRankIndex()];
    }

    public Color getRankColor() {
        return RANK_COLORS[getRankIndex()];
    }

    public double getRankProgress() {
        if (getRankIndex() >= RANK_NAMES.length - 1) return 1.0;
        return (careerXp % 400) / 400.0;
    }

    /**
     * Начисляет карьерный опыт и возвращает true, если игрок повысил своё звание CS2!
     */
    public boolean addCareerXp(int xpDelta, boolean kill, boolean headshot, boolean matchWin) {
        int oldRank = getRankIndex();
        careerXp = Math.max(0, careerXp + xpDelta);
        if (kill) careerKills++;
        if (headshot) careerHeadshots++;
        if (matchWin) careerWins++;
        save();
        return getRankIndex() > oldRank;
    }

    public void applyGraphicsPreset(QualityPreset preset) {
        this.qualityPreset = preset;
        switch (preset) {
            case LOW:
                resolutionScale = ResolutionScale.LOW_854;
                shadowMode = ShadowMode.OFF;
                bilinearFiltering = true;
                smoothTextures = true;
                fxaaAntiAliasing = false;
                multiCoreRendering = true;
                skyCloudsAndSunFlare = false;
                decalsAndShells = false;
                fogAndVignette = false;
                break;
            case MEDIUM:
                resolutionScale = ResolutionScale.HIGH_1280;
                shadowMode = ShadowMode.BLOBS;
                bilinearFiltering = true;
                smoothTextures = true;
                fxaaAntiAliasing = true;
                multiCoreRendering = true;
                skyCloudsAndSunFlare = true;
                decalsAndShells = true;
                fogAndVignette = true;
                break;
            case HIGH:
                resolutionScale = ResolutionScale.NATIVE_WINDOW;
                shadowMode = ShadowMode.SUN_PROJECTED;
                bilinearFiltering = true;
                smoothTextures = true;
                fxaaAntiAliasing = true;
                multiCoreRendering = true;
                skyCloudsAndSunFlare = true;
                decalsAndShells = true;
                fogAndVignette = true;
                break;
            case ULTRA:
                resolutionScale = ResolutionScale.EXTREME_1920;
                shadowMode = ShadowMode.SUN_PROJECTED;
                bilinearFiltering = true;
                smoothTextures = true;
                fxaaAntiAliasing = true;
                multiCoreRendering = true;
                skyCloudsAndSunFlare = true;
                decalsAndShells = true;
                fogAndVignette = true;
                break;
        }
    }

    public void load() {
        File file = new File(SETTINGS_FILE);
        if (!file.exists()) return;
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            p.load(fis);
            int ver = clampInt(p.getProperty("configVersion"), 1, 99, 1);

            qualityPreset = parseEnum(p.getProperty("qualityPreset"), QualityPreset.class, qualityPreset);
            resolutionScale = parseEnum(p.getProperty("resolutionScale"), ResolutionScale.class, resolutionScale);
            shadowMode = parseEnum(p.getProperty("shadowMode"), ShadowMode.class, shadowMode);
            bilinearFiltering = Boolean.parseBoolean(p.getProperty("bilinearFiltering", String.valueOf(bilinearFiltering)));
            smoothTextures = Boolean.parseBoolean(p.getProperty("smoothTextures", String.valueOf(smoothTextures)));
            fxaaAntiAliasing = Boolean.parseBoolean(p.getProperty("fxaaAntiAliasing", String.valueOf(fxaaAntiAliasing)));
            multiCoreRendering = Boolean.parseBoolean(p.getProperty("multiCoreRendering", String.valueOf(multiCoreRendering)));
            skyCloudsAndSunFlare = Boolean.parseBoolean(p.getProperty("skyCloudsAndSunFlare", String.valueOf(skyCloudsAndSunFlare)));
            decalsAndShells = Boolean.parseBoolean(p.getProperty("decalsAndShells", String.valueOf(decalsAndShells)));
            fogAndVignette = Boolean.parseBoolean(p.getProperty("fogAndVignette", String.valueOf(fogAndVignette)));
            fovDegrees = clampInt(p.getProperty("fovDegrees"), 55, 86, fovDegrees);
            fpsLimit = clampInt(p.getProperty("fpsLimit"), 0, 240, fpsLimit);
            showFpsTelemetry = Boolean.parseBoolean(p.getProperty("showFpsTelemetry", String.valueOf(showFpsTelemetry)));
            timeOfDay = parseEnum(p.getProperty("timeOfDay"), TimeOfDay.class, timeOfDay);
            weatherMode = parseEnum(p.getProperty("weatherMode"), WeatherMode.class, weatherMode);

            // Автоматическая миграция старых конфигураций v1..v5 на 100% гладкий непиксельный рендер v6.0!
            if (ver < CONFIG_VERSION) {
                resolutionScale = ResolutionScale.NATIVE_WINDOW;
                bilinearFiltering = true;
                smoothTextures = true;
                fxaaAntiAliasing = true;
                multiCoreRendering = true;
                if (fpsLimit == 60) fpsLimit = 120;
            }

            mouseSensitivity = clampDouble(p.getProperty("mouseSensitivity"), 0.0005, 0.0080, mouseSensitivity);
            invertMouseY = Boolean.parseBoolean(p.getProperty("invertMouseY", String.valueOf(invertMouseY)));
            crosshairColor = parseEnum(p.getProperty("crosshairColor"), CrosshairColor.class, crosshairColor);
            dynamicCrosshair = Boolean.parseBoolean(p.getProperty("dynamicCrosshair", String.valueOf(dynamicCrosshair)));
            leftHandedModel = Boolean.parseBoolean(p.getProperty("leftHandedModel", String.valueOf(leftHandedModel)));
            showDamageNumbers = Boolean.parseBoolean(p.getProperty("showDamageNumbers", String.valueOf(showDamageNumbers)));

            mapTheme = parseEnum(p.getProperty("mapTheme"), MapTheme.class, mapTheme);
            gameMode = parseEnum(p.getProperty("gameMode"), GameMode.class, gameMode);
            weaponSkin = parseEnum(p.getProperty("weaponSkin"), WeaponSkin.class, weaponSkin);
            botDifficulty = parseEnum(p.getProperty("botDifficulty"), BotDifficulty.class, botDifficulty);
            botCount = clampInt(p.getProperty("botCount"), 2, 8, botCount);
            allyCount = clampInt(p.getProperty("allyCount"), 0, 4, allyCount);
            roundsToWin = clampInt(p.getProperty("roundsToWin"), 2, 10, roundsToWin);

            careerXp = clampInt(p.getProperty("careerXp"), 0, 999999, careerXp);
            careerKills = clampInt(p.getProperty("careerKills"), 0, 999999, careerKills);
            careerHeadshots = clampInt(p.getProperty("careerHeadshots"), 0, 999999, careerHeadshots);
            careerWins = clampInt(p.getProperty("careerWins"), 0, 999999, careerWins);

            masterVolume = clampDouble(p.getProperty("masterVolume"), 0.0, 1.0, masterVolume);
            footstepSounds = Boolean.parseBoolean(p.getProperty("footstepSounds", String.valueOf(footstepSounds)));
        } catch (Throwable ignored) {
        }
    }

    public void save() {
        final Properties snapshot = buildPropertiesSnapshot();
        savePending = true;
        Thread writer = new Thread(() -> {
            synchronized (saveLock) {
                if (!savePending) return;
                savePending = false;
                try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
                    snapshot.store(fos, "CS-2 JAVA 6.0 Ultra-Smooth Panorama Configuration");
                } catch (Throwable ignored) {
                }
            }
        }, "CS2-Settings-Save");
        writer.setDaemon(true);
        writer.start();
    }

    public void saveSync() {
        final Properties snapshot = buildPropertiesSnapshot();
        synchronized (saveLock) {
            savePending = false;
            try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
                snapshot.store(fos, "CS-2 JAVA 6.0 Ultra-Smooth Panorama Configuration");
            } catch (Throwable ignored) {
            }
        }
    }

    private Properties buildPropertiesSnapshot() {
        Properties p = new Properties();
        p.setProperty("configVersion", String.valueOf(CONFIG_VERSION));
        p.setProperty("qualityPreset", qualityPreset.name());
        p.setProperty("resolutionScale", resolutionScale.name());
        p.setProperty("shadowMode", shadowMode.name());
        p.setProperty("bilinearFiltering", String.valueOf(bilinearFiltering));
        p.setProperty("smoothTextures", String.valueOf(smoothTextures));
        p.setProperty("fxaaAntiAliasing", String.valueOf(fxaaAntiAliasing));
        p.setProperty("multiCoreRendering", String.valueOf(multiCoreRendering));
        p.setProperty("skyCloudsAndSunFlare", String.valueOf(skyCloudsAndSunFlare));
        p.setProperty("decalsAndShells", String.valueOf(decalsAndShells));
        p.setProperty("fogAndVignette", String.valueOf(fogAndVignette));
        p.setProperty("fovDegrees", String.valueOf(fovDegrees));
        p.setProperty("fpsLimit", String.valueOf(fpsLimit));
        p.setProperty("showFpsTelemetry", String.valueOf(showFpsTelemetry));
        p.setProperty("timeOfDay", timeOfDay.name());
        p.setProperty("weatherMode", weatherMode.name());

        p.setProperty("mouseSensitivity", String.valueOf(mouseSensitivity));
        p.setProperty("invertMouseY", String.valueOf(invertMouseY));
        p.setProperty("crosshairColor", crosshairColor.name());
        p.setProperty("dynamicCrosshair", String.valueOf(dynamicCrosshair));
        p.setProperty("leftHandedModel", String.valueOf(leftHandedModel));
        p.setProperty("showDamageNumbers", String.valueOf(showDamageNumbers));

        p.setProperty("mapTheme", mapTheme.name());
        p.setProperty("gameMode", gameMode.name());
        p.setProperty("weaponSkin", weaponSkin.name());
        p.setProperty("botDifficulty", botDifficulty.name());
        p.setProperty("botCount", String.valueOf(botCount));
        p.setProperty("allyCount", String.valueOf(allyCount));
        p.setProperty("roundsToWin", String.valueOf(roundsToWin));

        p.setProperty("careerXp", String.valueOf(careerXp));
        p.setProperty("careerKills", String.valueOf(careerKills));
        p.setProperty("careerHeadshots", String.valueOf(careerHeadshots));
        p.setProperty("careerWins", String.valueOf(careerWins));

        p.setProperty("masterVolume", String.valueOf(masterVolume));
        p.setProperty("footstepSounds", String.valueOf(footstepSounds));
        return p;
    }

    private static <E extends Enum<E>> E parseEnum(String val, Class<E> cls, E fallback) {
        if (val == null) return fallback;
        try {
            return Enum.valueOf(cls, val.trim());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static int clampInt(String val, int min, int max, int fallback) {
        if (val == null) return fallback;
        try {
            int v = Integer.parseInt(val.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double clampDouble(String val, double min, double max, double fallback) {
        if (val == null) return fallback;
        try {
            double v = Double.parseDouble(val.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
