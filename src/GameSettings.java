import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Центральное хранилище настроек графики, оптимизации и карьеры CS-2 JAVA 8.0
 * (Hyper-Turbo Multi-Core Engine & Pro Graphics Studio Edition):
 * - 20+ профессиональных настроек графики CS2 (Пресеты, 1:1 / 540p..1080p, Авто-FPS Dynamic Res,
 *   FXAA Turbo / Span FXAA 3.11, Резкость CAS, Насыщенность Digital Vibrance, Яркость, LOD геометрии,
 *   Дальность прорисовки, Качество Mipmap, Тени, AO Гуро, Туман, Виньетка, Плотность частиц, График FPS)
 * - Система званий CS2 (Silver -> Global Elite) и сохранение конфигурации
 */
public class GameSettings {

    public static final int CONFIG_VERSION = 12;

    public enum QualityPreset {
        ESPORTS("Киберспорт (Макс. FPS)"),
        LOW("Низкое (Турбо)"),
        MEDIUM("Среднее (Баланс HD)"),
        HIGH("Высокое (HD 720p + FXAA + Авто-FPS)"),
        ULTRA("Ультра (1:1 + CAS + Макс.)");

        public final String title;
        QualityPreset(String title) { this.title = title; }
    }

    public enum ResolutionScale {
        TURBO_768("768×432 (Кибер-Турбо)", 768, 432),
        LOW_854("960×540 (Турбо 540p)", 960, 540),
        MED_1024("1152×648 (Быстрый HD)", 1152, 648),
        HIGH_1280("1280×720 (HD 720p)", 1280, 720),
        ULTRA_1600("1600×900 (HD+ 900p)", 1600, 900),
        EXTREME_1920("1920×1080 (Full HD 1080p)", 1920, 1080),
        NATIVE_WINDOW("Нативное 1:1 (Без мыла!)", 0, 0);

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
                return Math.max(800, Math.min(1920, windowW));
            }
            return width;
        }

        public int resolveHeight(int windowH) {
            if (this == NATIVE_WINDOW) {
                return Math.max(450, Math.min(1080, windowH));
            }
            return height;
        }
    }

    public enum DynamicResolutionMode {
        OFF("Выкл (Фикс. 100%)", 0),
        TARGET_60("Авто-удержание 60 FPS", 60),
        TARGET_120("Авто-удержание 120 FPS", 120);

        public final String label;
        public final int targetFps;
        DynamicResolutionMode(String label, int targetFps) {
            this.label = label;
            this.targetFps = targetFps;
        }
    }

    public enum AntiAliasingMode {
        OFF("Выключено (Макс. FPS)"),
        FXAA_TURBO("FXAA Turbo (Быстрое)"),
        SPAN_FXAA_ULTRA("Span FXAA 3.11 (Ультра)");

        public final String label;
        AntiAliasingMode(String label) { this.label = label; }
    }

    public enum TextureQualityMode {
        FAST_MIP("Быстрая (Чёткий Mipmap)"),
        BILINEAR_MIP("Гладкая Bilinear + Mipmap"),
        ULTRA_ANISO_MIP("Ультра 512 (Дальний Mip)");

        public final String label;
        TextureQualityMode(String label) { this.label = label; }
    }

    public enum GeometryLodMode {
        PERFORMANCE("Киберспорт (Быстрый LOD)", 0.55),
        BALANCED("Адаптивный Авто-LOD", 0.82),
        ULTRA("Ультра (Макс. сегментов)", 1.00);

        public final String label;
        public final double detailScale;
        GeometryLodMode(String label, double detailScale) {
            this.label = label;
            this.detailScale = detailScale;
        }
    }

    public enum RenderDistanceMode {
        SHORT_48("64 м (Макс. FPS)", 64.0),
        MEDIUM_68("88 м (Стандарт)", 88.0),
        FAR_88("112 м (Далеко)", 112.0),
        EXTREME_115("140 м (Вся арена 68×68)", 140.0);

        public final String label;
        public final double meters;
        RenderDistanceMode(String label, double meters) {
            this.label = label;
            this.meters = meters;
        }
    }

    public enum CasSharpeningMode {
        OFF("Выключена (0%)", 0),
        SOFT("Мягкая (35%)", 35),
        CRISP("Чёткая CS2 (65%)", 65),
        MAX("Максимум (100%)", 100);

        public final String label;
        public final int strengthPct;
        CasSharpeningMode(String label, int strengthPct) {
            this.label = label;
            this.strengthPct = strengthPct;
        }
    }

    public enum ColorVibranceMode {
        CINEMATIC("Кино-мягкая (90%)", 90),
        NATURAL("Стандарт (100%)", 100),
        VIBRANT("Сочная CS2 (128%)", 128),
        ESPORTS_VIVID("Киберспорт (155%)", 155);

        public final String label;
        public final int vibrancePct;
        ColorVibranceMode(String label, int vibrancePct) {
            this.label = label;
            this.vibrancePct = vibrancePct;
        }
    }

    public enum ParticleDensityMode {
        LOW("Низкая (40% — FPS+)", 0.40),
        MEDIUM("Стандарт (100%)", 1.00),
        ULTRA("Ультра (150% искр)", 1.50);

        public final String label;
        public final double multiplier;
        ParticleDensityMode(String label, double multiplier) {
            this.label = label;
            this.multiplier = multiplier;
        }
    }

    public enum HudTelemetryMode {
        OFF("Скрыта"),
        COMPACT("Компактная (FPS + мс)"),
        FULL_GRAPH("Полная + График мс");

        public final String label;
        HudTelemetryMode(String label) { this.label = label; }
    }

    public enum ShadowMode {
        OFF("Выкл (FPS+)"),
        BLOBS("Контактные (Быстро)"),
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
        // 11.0 BALANCE: урон снижен ~35-40%, реакция медленнее, разброс шире — враги больше не «лазером»
        EASY("Новичок", 0.80, 0.085, 3.0, 6),
        NORMAL("Спецназ", 0.55, 0.058, 4.2, 8),
        HARD("Global Elite", 0.32, 0.036, 5.1, 11);

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
        INFERNO("Inferno", "DE_INFERNO", "Банан • Фонтан • 2-эт. Апартаменты", "68×68м • 2 этажа • Узкий Банан", new Color(242, 122, 54)),
        DUST_II("Dust II", "DE_DUST2", "Длина А • Коробка • Тёмка B", "68×68м • Плато А • Крытый тоннель", new Color(235, 188, 76)),
        MIRAGE("Mirage", "DE_MIRAGE", "Окно Снайпера • Дворец • Маркет", "68×68м • Снайп. Окно • Зал Дворца", new Color(224, 98, 138)),
        NUKE("Nuke", "DE_NUKE", "Улица • Силос • Мостки Девятка", "68×68м • 2 яруса • Heaven + Реактор", new Color(64, 192, 255));

        public final String shortName;
        public final String codeName;
        public final String subtitle;
        public final String layoutBadge;
        public final Color accent;
        public final String label;

        MapTheme(String shortName, String codeName, String subtitle, String layoutBadge, Color accent) {
            this.shortName = shortName;
            this.codeName = codeName;
            this.subtitle = subtitle;
            this.layoutBadge = layoutBadge;
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

    // --- РАСШИРЕННЫЕ НАСТРОЙКИ ГРАФИКИ И ОПТИМИЗАЦИИ CS-2 JAVA 8.0 ---
    public QualityPreset qualityPreset = QualityPreset.HIGH;
    public ResolutionScale resolutionScale = ResolutionScale.NATIVE_WINDOW;
    public DynamicResolutionMode dynamicResolution = DynamicResolutionMode.OFF;
    public AntiAliasingMode antiAliasingMode = AntiAliasingMode.FXAA_TURBO;
    public TextureQualityMode textureQuality = TextureQualityMode.BILINEAR_MIP;
    public GeometryLodMode geometryLod = GeometryLodMode.BALANCED;
    public RenderDistanceMode renderDistance = RenderDistanceMode.FAR_88;
    public CasSharpeningMode casSharpening = CasSharpeningMode.SOFT;
    public ColorVibranceMode colorVibrance = ColorVibranceMode.VIBRANT;
    public ParticleDensityMode particleDensity = ParticleDensityMode.MEDIUM;
    public HudTelemetryMode telemetryMode = HudTelemetryMode.FULL_GRAPH;
    public int brightnessPct = 100;             // Яркость / гамма (80..135%)
    public boolean ambientOcclusion = true;     // Мягкое затенение углов Гуро (AO)
    public boolean fogEnabled = true;           // Атмосферная дымка глубины
    public boolean vignetteEnabled = true;      // Кинематографическое затемнение краёв

    // Совместимые флаги рендера
    public ShadowMode shadowMode = ShadowMode.SUN_PROJECTED;
    public boolean bilinearFiltering = true;
    public boolean smoothTextures = true;
    public boolean fxaaAntiAliasing = true;
    public boolean multiCoreRendering = true;
    public boolean skyCloudsAndSunFlare = true;
    public boolean decalsAndShells = true;
    public boolean fogAndVignette = true;
    public int fovDegrees = 72;                 // Вертикальный FOV (56..90)
    public int fpsLimit = 144;                  // 60, 120, 144, 240 или 0 (без лимита)
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
    // --- РЕАЛИЗМ 9.0 ---
    public boolean bloodDecals = true;          // кровь на стенах/полу
    public boolean fallDamage = true;           // урон от падения с высоты
    public boolean bulletWhizSounds = true;     // свист пролетающих пуль

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

    // --- 9.5: КЕЙСЫ, ИНВЕНТАРЬ, ДОСТИЖЕНИЯ И ЗАСТАВКА ---
    public int caseKeys = 5;                 // ключи от кейсов (+1 за каждые 5 фрагов, +2 за победу)
    public int casesOpened = 0;
    public String caseInventory = "";        // "itemIdx:wear1000:st|..." — последние дропы из кейсов
    public String achievements = "";         // id разблокированных достижений через запятую
    public boolean showIntroSplash = true;   // заставка «Игра сделана Алкашом» при запуске
    public boolean menuParticles = true;     // искры и угольки в меню

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

    public boolean addCareerXp(int xpDelta, boolean kill, boolean headshot, boolean matchWin) {
        int oldRank = getRankIndex();
        careerXp = Math.max(0, careerXp + xpDelta);
        if (kill) {
            careerKills++;
            if (careerKills % 5 == 0) caseKeys = Math.min(999, caseKeys + 1);
        }
        if (headshot) careerHeadshots++;
        if (matchWin) {
            careerWins++;
            caseKeys = Math.min(999, caseKeys + 2);
        }
        save();
        return getRankIndex() > oldRank;
    }

    public boolean hasAchievement(String id) {
        if (achievements == null || achievements.isEmpty()) return false;
        for (String a : achievements.split(",")) if (a.equals(id)) return true;
        return false;
    }

    /** @return true, если достижение получено впервые. */
    public boolean unlockAchievement(String id) {
        if (hasAchievement(id)) return false;
        achievements = (achievements == null || achievements.isEmpty()) ? id : achievements + "," + id;
        save();
        return true;
    }

    public int getAchievementCount() {
        return (achievements == null || achievements.isEmpty()) ? 0 : achievements.split(",").length;
    }

    public void syncDerivedFlags() {
        this.smoothTextures = (textureQuality != TextureQualityMode.FAST_MIP);
        this.fxaaAntiAliasing = (antiAliasingMode != AntiAliasingMode.OFF);
        this.fogAndVignette = fogEnabled || vignetteEnabled;
        this.showFpsTelemetry = (telemetryMode != HudTelemetryMode.OFF);
    }

    public void applyGraphicsPreset(QualityPreset preset) {
        this.qualityPreset = preset;
        switch (preset) {
            case ESPORTS:
                resolutionScale = ResolutionScale.LOW_854;
                dynamicResolution = DynamicResolutionMode.OFF;
                antiAliasingMode = AntiAliasingMode.OFF;
                textureQuality = TextureQualityMode.FAST_MIP;
                geometryLod = GeometryLodMode.PERFORMANCE;
                renderDistance = RenderDistanceMode.MEDIUM_68;
                casSharpening = CasSharpeningMode.CRISP;
                colorVibrance = ColorVibranceMode.ESPORTS_VIVID;
                particleDensity = ParticleDensityMode.LOW;
                shadowMode = ShadowMode.OFF;
                ambientOcclusion = false;
                fogEnabled = false;
                vignetteEnabled = false;
                skyCloudsAndSunFlare = false;
                decalsAndShells = false;
                bilinearFiltering = false;
                multiCoreRendering = true;
                fpsLimit = 0;
                break;
            case LOW:
                // 11.2 FPS-FIX: низкие — это реально низкие: 960x540 + авто-FPS
                resolutionScale = ResolutionScale.LOW_854;
                dynamicResolution = DynamicResolutionMode.TARGET_60;
                antiAliasingMode = AntiAliasingMode.FXAA_TURBO;
                textureQuality = TextureQualityMode.BILINEAR_MIP;
                geometryLod = GeometryLodMode.PERFORMANCE;
                renderDistance = RenderDistanceMode.MEDIUM_68;
                casSharpening = CasSharpeningMode.OFF;
                colorVibrance = ColorVibranceMode.NATURAL;
                particleDensity = ParticleDensityMode.LOW;
                shadowMode = ShadowMode.BLOBS;
                ambientOcclusion = true;
                fogEnabled = true;
                vignetteEnabled = false;
                skyCloudsAndSunFlare = false;
                decalsAndShells = false;
                bilinearFiltering = true;
                multiCoreRendering = true;
                break;
            case MEDIUM:
                resolutionScale = ResolutionScale.HIGH_1280;
                dynamicResolution = DynamicResolutionMode.OFF;
                antiAliasingMode = AntiAliasingMode.FXAA_TURBO;
                textureQuality = TextureQualityMode.BILINEAR_MIP;
                geometryLod = GeometryLodMode.BALANCED;
                renderDistance = RenderDistanceMode.FAR_88;
                casSharpening = CasSharpeningMode.SOFT;
                colorVibrance = ColorVibranceMode.VIBRANT;
                particleDensity = ParticleDensityMode.MEDIUM;
                shadowMode = ShadowMode.BLOBS;
                ambientOcclusion = true;
                fogEnabled = true;
                vignetteEnabled = true;
                skyCloudsAndSunFlare = true;
                decalsAndShells = true;
                bilinearFiltering = true;
                multiCoreRendering = true;
                break;
            case HIGH:
                // 11.2 FPS-FIX: фиксированные 720p + динамическое разрешение —
                // раньше NATIVE_WINDOW рендерил 3D в разрешении окна (на 1440p/4K — слайд-шоу)
                resolutionScale = ResolutionScale.HIGH_1280;
                dynamicResolution = DynamicResolutionMode.TARGET_60;
                antiAliasingMode = AntiAliasingMode.FXAA_TURBO;
                textureQuality = TextureQualityMode.BILINEAR_MIP;
                geometryLod = GeometryLodMode.BALANCED;
                renderDistance = RenderDistanceMode.FAR_88;
                casSharpening = CasSharpeningMode.SOFT;
                colorVibrance = ColorVibranceMode.VIBRANT;
                particleDensity = ParticleDensityMode.MEDIUM;
                shadowMode = ShadowMode.SUN_PROJECTED;
                ambientOcclusion = true;
                fogEnabled = true;
                vignetteEnabled = true;
                skyCloudsAndSunFlare = true;
                decalsAndShells = true;
                bilinearFiltering = true;
                multiCoreRendering = true;
                break;
            case ULTRA:
                resolutionScale = ResolutionScale.EXTREME_1920;
                dynamicResolution = DynamicResolutionMode.OFF;
                antiAliasingMode = AntiAliasingMode.SPAN_FXAA_ULTRA;
                textureQuality = TextureQualityMode.ULTRA_ANISO_MIP;
                geometryLod = GeometryLodMode.ULTRA;
                renderDistance = RenderDistanceMode.EXTREME_115;
                casSharpening = CasSharpeningMode.CRISP;
                colorVibrance = ColorVibranceMode.VIBRANT;
                particleDensity = ParticleDensityMode.ULTRA;
                shadowMode = ShadowMode.SUN_PROJECTED;
                ambientOcclusion = true;
                fogEnabled = true;
                vignetteEnabled = true;
                skyCloudsAndSunFlare = true;
                decalsAndShells = true;
                bilinearFiltering = true;
                multiCoreRendering = true;
                break;
        }
        syncDerivedFlags();
    }

    public void load() {
        File file = new File(SETTINGS_FILE);
        if (!file.exists()) {
            syncDerivedFlags();
            return;
        }
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            p.load(fis);
            int ver = clampInt(p.getProperty("configVersion"), 1, 99, 1);

            qualityPreset = parseEnum(p.getProperty("qualityPreset"), QualityPreset.class, qualityPreset);
            resolutionScale = parseEnum(p.getProperty("resolutionScale"), ResolutionScale.class, resolutionScale);
            dynamicResolution = parseEnum(p.getProperty("dynamicResolution"), DynamicResolutionMode.class, dynamicResolution);
            // 11.2 FPS-МИГРАЦИЯ (config < 12): у пресета HIGH было NATIVE_WINDOW — на 1440p/4K
            // это рендер 3D в разрешении окна и слайд-шоу даже на мощных ПК. Переводим на 720p+авто-FPS.
            if (ver < 12 && qualityPreset == QualityPreset.HIGH && resolutionScale == ResolutionScale.NATIVE_WINDOW) {
                resolutionScale = ResolutionScale.HIGH_1280;
                dynamicResolution = DynamicResolutionMode.TARGET_60;
            }
            antiAliasingMode = parseEnum(p.getProperty("antiAliasingMode"), AntiAliasingMode.class, antiAliasingMode);
            textureQuality = parseEnum(p.getProperty("textureQuality"), TextureQualityMode.class, textureQuality);
            geometryLod = parseEnum(p.getProperty("geometryLod"), GeometryLodMode.class, geometryLod);
            renderDistance = parseEnum(p.getProperty("renderDistance"), RenderDistanceMode.class, renderDistance);
            casSharpening = parseEnum(p.getProperty("casSharpening"), CasSharpeningMode.class, casSharpening);
            colorVibrance = parseEnum(p.getProperty("colorVibrance"), ColorVibranceMode.class, colorVibrance);
            particleDensity = parseEnum(p.getProperty("particleDensity"), ParticleDensityMode.class, particleDensity);
            telemetryMode = parseEnum(p.getProperty("telemetryMode"), HudTelemetryMode.class, telemetryMode);
            brightnessPct = clampInt(p.getProperty("brightnessPct"), 80, 135, brightnessPct);
            ambientOcclusion = Boolean.parseBoolean(p.getProperty("ambientOcclusion", String.valueOf(ambientOcclusion)));
            fogEnabled = Boolean.parseBoolean(p.getProperty("fogEnabled", String.valueOf(fogEnabled)));
            vignetteEnabled = Boolean.parseBoolean(p.getProperty("vignetteEnabled", String.valueOf(vignetteEnabled)));

            shadowMode = parseEnum(p.getProperty("shadowMode"), ShadowMode.class, shadowMode);
            bilinearFiltering = Boolean.parseBoolean(p.getProperty("bilinearFiltering", String.valueOf(bilinearFiltering)));
            multiCoreRendering = Boolean.parseBoolean(p.getProperty("multiCoreRendering", String.valueOf(multiCoreRendering)));
            skyCloudsAndSunFlare = Boolean.parseBoolean(p.getProperty("skyCloudsAndSunFlare", String.valueOf(skyCloudsAndSunFlare)));
            decalsAndShells = Boolean.parseBoolean(p.getProperty("decalsAndShells", String.valueOf(decalsAndShells)));
            fovDegrees = clampInt(p.getProperty("fovDegrees"), 56, 90, fovDegrees);
            fpsLimit = clampInt(p.getProperty("fpsLimit"), 0, 360, fpsLimit);
            timeOfDay = parseEnum(p.getProperty("timeOfDay"), TimeOfDay.class, timeOfDay);
            weatherMode = parseEnum(p.getProperty("weatherMode"), WeatherMode.class, weatherMode);

            if (ver < CONFIG_VERSION) {
                multiCoreRendering = true;
                if (fpsLimit == 60) fpsLimit = 144;
            }
            syncDerivedFlags();

            mouseSensitivity = clampDouble(p.getProperty("mouseSensitivity"), 0.0005, 0.0080, mouseSensitivity);
            invertMouseY = Boolean.parseBoolean(p.getProperty("invertMouseY", String.valueOf(invertMouseY)));
            crosshairColor = parseEnum(p.getProperty("crosshairColor"), CrosshairColor.class, crosshairColor);
            dynamicCrosshair = Boolean.parseBoolean(p.getProperty("dynamicCrosshair", String.valueOf(dynamicCrosshair)));
            leftHandedModel = Boolean.parseBoolean(p.getProperty("leftHandedModel", String.valueOf(leftHandedModel)));
            showDamageNumbers = Boolean.parseBoolean(p.getProperty("showDamageNumbers", String.valueOf(showDamageNumbers)));
            bloodDecals = Boolean.parseBoolean(p.getProperty("bloodDecals", String.valueOf(bloodDecals)));
            fallDamage = Boolean.parseBoolean(p.getProperty("fallDamage", String.valueOf(fallDamage)));
            bulletWhizSounds = Boolean.parseBoolean(p.getProperty("bulletWhizSounds", String.valueOf(bulletWhizSounds)));

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
            caseKeys = clampInt(p.getProperty("caseKeys"), 0, 999, caseKeys);
            casesOpened = clampInt(p.getProperty("casesOpened"), 0, 999999, casesOpened);
            caseInventory = p.getProperty("caseInventory", caseInventory);
            achievements = p.getProperty("achievements", achievements);
            showIntroSplash = Boolean.parseBoolean(p.getProperty("showIntroSplash", String.valueOf(showIntroSplash)));
            menuParticles = Boolean.parseBoolean(p.getProperty("menuParticles", String.valueOf(menuParticles)));

            masterVolume = clampDouble(p.getProperty("masterVolume"), 0.0, 1.0, masterVolume);
            footstepSounds = Boolean.parseBoolean(p.getProperty("footstepSounds", String.valueOf(footstepSounds)));
        } catch (Throwable ignored) {
        }
    }

    public void save() {
        syncDerivedFlags();
        final Properties snapshot = buildPropertiesSnapshot();
        savePending = true;
        Thread writer = new Thread(() -> {
            synchronized (saveLock) {
                if (!savePending) return;
                savePending = false;
                try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
                    snapshot.store(fos, "CS-2 JAVA 9.0 Hyper-Turbo & Pro Graphics Studio Configuration");
                } catch (Throwable ignored) {
                }
            }
        }, "CS2-Settings-Save");
        writer.setDaemon(true);
        writer.start();
    }

    public void saveSync() {
        syncDerivedFlags();
        final Properties snapshot = buildPropertiesSnapshot();
        synchronized (saveLock) {
            savePending = false;
            try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
                snapshot.store(fos, "CS-2 JAVA 9.0 Hyper-Turbo & Pro Graphics Studio Configuration");
            } catch (Throwable ignored) {
            }
        }
    }

    private Properties buildPropertiesSnapshot() {
        Properties p = new Properties();
        p.setProperty("configVersion", String.valueOf(CONFIG_VERSION));
        p.setProperty("qualityPreset", qualityPreset.name());
        p.setProperty("resolutionScale", resolutionScale.name());
        p.setProperty("dynamicResolution", dynamicResolution.name());
        p.setProperty("antiAliasingMode", antiAliasingMode.name());
        p.setProperty("textureQuality", textureQuality.name());
        p.setProperty("geometryLod", geometryLod.name());
        p.setProperty("renderDistance", renderDistance.name());
        p.setProperty("casSharpening", casSharpening.name());
        p.setProperty("colorVibrance", colorVibrance.name());
        p.setProperty("particleDensity", particleDensity.name());
        p.setProperty("telemetryMode", telemetryMode.name());
        p.setProperty("brightnessPct", String.valueOf(brightnessPct));
        p.setProperty("ambientOcclusion", String.valueOf(ambientOcclusion));
        p.setProperty("fogEnabled", String.valueOf(fogEnabled));
        p.setProperty("vignetteEnabled", String.valueOf(vignetteEnabled));

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
        p.setProperty("bloodDecals", String.valueOf(bloodDecals));
        p.setProperty("fallDamage", String.valueOf(fallDamage));
        p.setProperty("bulletWhizSounds", String.valueOf(bulletWhizSounds));

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
        p.setProperty("caseKeys", String.valueOf(caseKeys));
        p.setProperty("casesOpened", String.valueOf(casesOpened));
        p.setProperty("caseInventory", caseInventory == null ? "" : caseInventory);
        p.setProperty("achievements", achievements == null ? "" : achievements);
        p.setProperty("showIntroSplash", String.valueOf(showIntroSplash));
        p.setProperty("menuParticles", String.valueOf(menuParticles));

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
