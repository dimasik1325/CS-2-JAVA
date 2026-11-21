import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Центральное хранилище настроек игры, настроек графики, управления, прицела и звука.
 * Автоматически сохраняет и загружает конфигурацию игрока в файл settings.properties.
 */
public class GameSettings {

    public enum QualityPreset {
        LOW("Низкое"),
        MEDIUM("Среднее"),
        HIGH("Высокое"),
        ULTRA("Ультра");

        public final String title;
        QualityPreset(String title) { this.title = title; }
    }

    public enum ResolutionScale {
        LOW_480("480×270 (Макс. FPS)", 480, 270),
        MED_640("640×360 (Стандарт)", 640, 360),
        HIGH_854("854×480 (HD Четкость)", 854, 480),
        ULTRA_960("960×540 (Ультра)", 960, 540);

        public final String label;
        public final int width;
        public final int height;

        ResolutionScale(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }
    }

    public enum ShadowMode {
        OFF("Выкл"),
        BLOBS("Мягкие под объектами"),
        SUN_PROJECTED("Реалистичные солнечные");

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
        GREEN("Зеленый CS2", new Color(86, 255, 134)),
        CYAN("Голубой Pro", new Color(72, 240, 255)),
        YELLOW("Желтый", new Color(255, 228, 72)),
        WHITE("Белый", new Color(248, 250, 252)),
        MAGENTA("Пурпурный", new Color(255, 92, 232));

        public final String label;
        public final Color color;
        CrosshairColor(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    public enum MapTheme {
        DUST_II("Dust II (Пески)"),
        MIRAGE("Mirage (Дворец)"),
        NUKE("Nuke (Комплекс)");

        public final String label;
        MapTheme(String label) { this.label = label; }
    }

    public enum GameMode {
        BOMB_DEFUSAL("Операция C4 (Пленты A/B)"),
        ELIMINATION("Классика: Зачистка"),
        DEATHMATCH("Бой насмерть (DM)"),
        ARMS_RACE("Гонка вооружений");

        public final String label;
        GameMode(String label) { this.label = label; }
    }

    public enum WeaponSkin {
        STANDARD("Стандарт (Военный)", 0, 0, 0),
        ASIIMOV("Asiimov (Азимов)", 0xEEF2F7, 0xFF6B1A, 0x1D222B),
        GOLD_FADE("Золотой Градиент (Fade)", 0xF4C438, 0xE6397A, 0x622B96),
        REDLINE("Redline (Карбон)", 0x1B1F26, 0xE82C3C, 0x343B46),
        PRINTSTREAM("Printstream (Кибер-Неон)", 0xE8EEF6, 0x2CE8FF, 0x1C2536),
        HYPER_BEAST("Неоновый Зверь", 0x202A68, 0xFF3E8E, 0x2EFFB4);

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

    private static final String SETTINGS_FILE = "settings.properties";

    // --- НАСТРОЙКИ ГРАФИКИ ---
    public QualityPreset qualityPreset = QualityPreset.HIGH;
    public ResolutionScale resolutionScale = ResolutionScale.HIGH_854;
    public ShadowMode shadowMode = ShadowMode.SUN_PROJECTED;
    public boolean bilinearFiltering = false;
    public boolean skyCloudsAndSunFlare = true;
    public boolean decalsAndShells = true;
    public boolean fogAndVignette = true;
    public int fovDegrees = 68;         // Вертикальный FOV (55..82)
    public int fpsLimit = 60;           // 60, 120 или 0 (без лимита)
    public boolean showFpsTelemetry = true;
    public boolean fullscreen = false;
    public TimeOfDay timeOfDay = TimeOfDay.DAY;

    // --- НАСТРОЙКИ ГЕЙМПЛЕЯ И УПРАВЛЕНИЯ ---
    public double mouseSensitivity = 0.0024;
    public boolean invertMouseY = false;
    public CrosshairColor crosshairColor = CrosshairColor.GREEN;
    public boolean dynamicCrosshair = true;
    public boolean leftHandedModel = false;
    public boolean showDamageNumbers = true;

    // --- ПАРАМЕТРЫ МАТЧА И АРСЕНАЛА ---
    public MapTheme mapTheme = MapTheme.DUST_II;
    public GameMode gameMode = GameMode.BOMB_DEFUSAL;
    public WeaponSkin weaponSkin = WeaponSkin.STANDARD;
    public BotDifficulty botDifficulty = BotDifficulty.NORMAL;
    public int botCount = 5;
    public int allyCount = 2;
    public int roundsToWin = 5;

    // --- НАСТРОЙКИ АУДИО ---
    public double masterVolume = 0.85;  // 0.0 .. 1.0
    public boolean footstepSounds = true;

    public GameSettings() {
        load();
    }

    public void applyGraphicsPreset(QualityPreset preset) {
        this.qualityPreset = preset;
        switch (preset) {
            case LOW:
                resolutionScale = ResolutionScale.LOW_480;
                shadowMode = ShadowMode.OFF;
                bilinearFiltering = false;
                skyCloudsAndSunFlare = false;
                decalsAndShells = false;
                fogAndVignette = false;
                break;
            case MEDIUM:
                resolutionScale = ResolutionScale.MED_640;
                shadowMode = ShadowMode.BLOBS;
                bilinearFiltering = false;
                skyCloudsAndSunFlare = true;
                decalsAndShells = true;
                fogAndVignette = true;
                break;
            case HIGH:
                resolutionScale = ResolutionScale.HIGH_854;
                shadowMode = ShadowMode.SUN_PROJECTED;
                bilinearFiltering = false;
                skyCloudsAndSunFlare = true;
                decalsAndShells = true;
                fogAndVignette = true;
                break;
            case ULTRA:
                resolutionScale = ResolutionScale.ULTRA_960;
                shadowMode = ShadowMode.SUN_PROJECTED;
                bilinearFiltering = true;
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
            qualityPreset = parseEnum(p.getProperty("qualityPreset"), QualityPreset.class, qualityPreset);
            resolutionScale = parseEnum(p.getProperty("resolutionScale"), ResolutionScale.class, resolutionScale);
            shadowMode = parseEnum(p.getProperty("shadowMode"), ShadowMode.class, shadowMode);
            bilinearFiltering = Boolean.parseBoolean(p.getProperty("bilinearFiltering", String.valueOf(bilinearFiltering)));
            skyCloudsAndSunFlare = Boolean.parseBoolean(p.getProperty("skyCloudsAndSunFlare", String.valueOf(skyCloudsAndSunFlare)));
            decalsAndShells = Boolean.parseBoolean(p.getProperty("decalsAndShells", String.valueOf(decalsAndShells)));
            fogAndVignette = Boolean.parseBoolean(p.getProperty("fogAndVignette", String.valueOf(fogAndVignette)));
            fovDegrees = clampInt(p.getProperty("fovDegrees"), 55, 84, fovDegrees);
            fpsLimit = clampInt(p.getProperty("fpsLimit"), 0, 240, fpsLimit);
            showFpsTelemetry = Boolean.parseBoolean(p.getProperty("showFpsTelemetry", String.valueOf(showFpsTelemetry)));
            timeOfDay = parseEnum(p.getProperty("timeOfDay"), TimeOfDay.class, timeOfDay);

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

            masterVolume = clampDouble(p.getProperty("masterVolume"), 0.0, 1.0, masterVolume);
            footstepSounds = Boolean.parseBoolean(p.getProperty("footstepSounds", String.valueOf(footstepSounds)));
        } catch (Throwable ignored) {
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("qualityPreset", qualityPreset.name());
        p.setProperty("resolutionScale", resolutionScale.name());
        p.setProperty("shadowMode", shadowMode.name());
        p.setProperty("bilinearFiltering", String.valueOf(bilinearFiltering));
        p.setProperty("skyCloudsAndSunFlare", String.valueOf(skyCloudsAndSunFlare));
        p.setProperty("decalsAndShells", String.valueOf(decalsAndShells));
        p.setProperty("fogAndVignette", String.valueOf(fogAndVignette));
        p.setProperty("fovDegrees", String.valueOf(fovDegrees));
        p.setProperty("fpsLimit", String.valueOf(fpsLimit));
        p.setProperty("showFpsTelemetry", String.valueOf(showFpsTelemetry));
        p.setProperty("timeOfDay", timeOfDay.name());

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

        p.setProperty("masterVolume", String.valueOf(masterVolume));
        p.setProperty("footstepSounds", String.valueOf(footstepSounds));

        try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
            p.store(fos, "CS-2 JAVA Configuration");
        } catch (Throwable ignored) {
        }
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
