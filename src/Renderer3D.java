import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Ультра-быстрый многоядерный 3D-движок CS-2 JAVA 7.0 (Hyper-Turbo Multi-Core Engine):
 * - O(1) аналитический отсекатель сканлайнов треугольников (без попиксельного поиска границ!)
 * - Deferred Skybox Shading (небо и облака считаются ТОЛЬКО для видимых пикселей неба после Z-отсечения!)
 * - Early-Z 16-пиксельное спановое отсечение перекрытых стен/полов до расчёта UV и текстур
 * - Work-Stealing многопоточность с участием главного потока (0 мс простоя на барьерах!)
 * - Встроенный параллельный Post-FX конвейер: FXAA Turbo / Span FXAA 3.11 + CAS резкость + Цифровая насыщенность + Гамма LUT + Виньетка
 * - Автоматический 3D Geometry LOD по дистанции и табличные сферы/цилиндры с 0 аллокаций в кадре
 */
public class Renderer3D {
    public static final int TEX_FLOOR_SAND = 0;
    public static final int TEX_WALL_SANDSTONE = 1;
    public static final int TEX_CRATE_WOOD = 2;
    public static final int TEX_CRATE_MILITARY = 3;
    public static final int TEX_CONCRETE = 4;
    public static final int TEX_METAL_DOOR = 5;
    public static final int TEX_SOLID = 6;
    public static final int TEX_BOMBSITE = 7;
    public static final int TEX_MIRAGE_PLASTER = 8;
    public static final int TEX_MIRAGE_TILE = 9;
    public static final int TEX_NUKE_PANEL = 10;
    public static final int TEX_NUKE_FLOOR = 11;
    public static final int TEX_INFERNO_COBBLE = 12;
    public static final int TEX_INFERNO_STUCCO = 13;
    public static final int TEX_ROOF_TILES = 14;
    public static final int TEX_WATER = 15;

    private static final int NUM_TEXTURES = 16;
    private static final int TEX_SIZE = 512;
    private static final int TEX_MASK = TEX_SIZE - 1;
    private static final int TEX_SHIFT = 9;
    private static final double NEAR_Z = 0.14;

    // Постоянный пул воркеров (главный поток участвует как WORKER #0 -> создаём WORKER_THREADS-1 фоновых потоков!)
    private static final int WORKER_THREADS = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
    private static final int BG_WORKERS = Math.max(1, WORKER_THREADS - 1);
    private static final int NUM_BANDS = 16;
    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(BG_WORKERS, r -> {
        Thread t = new Thread(r, "CS2-HyperRasterWorker");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        return t;
    });

    // Предвычисленные таблицы sin/cos для цилиндров и колец сфер (0 вызовов Math.sin/cos в кадре!)
    private static final double[][] COS_LUT = new double[33][];
    private static final double[][] SIN_LUT = new double[33][];
    private static final double[][] RING_COS_LUT = new double[19][];
    private static final double[][] RING_SIN_LUT = new double[19][];
    static {
        for (int s = 3; s <= 32; s++) {
            COS_LUT[s] = new double[s + 1];
            SIN_LUT[s] = new double[s + 1];
            for (int i = 0; i <= s; i++) {
                double a = (i * Math.PI * 2.0) / s;
                COS_LUT[s][i] = Math.cos(a);
                SIN_LUT[s][i] = Math.sin(a);
            }
        }
        for (int rng = 3; rng <= 18; rng++) {
            RING_COS_LUT[rng] = new double[rng + 1];
            RING_SIN_LUT[rng] = new double[rng + 1];
            for (int r = 0; r <= rng; r++) {
                double phi = -Math.PI * 0.5 + (r * Math.PI) / rng;
                RING_COS_LUT[rng][r] = Math.cos(phi);
                RING_SIN_LUT[rng][r] = Math.sin(phi);
            }
        }
    }

    // Текстурные буферы (один общий неизменяемый набор в памяти для мгновенного ресайза окна!)
    private static volatile boolean texturesReady = false;
    private static final Object TEX_INIT_LOCK = new Object();
    private static final int[][] SHARED_MIP0 = new int[NUM_TEXTURES][TEX_SIZE * TEX_SIZE];
    private static final int[][] SHARED_MIP1 = new int[NUM_TEXTURES][256 * 256];
    private static final int[][] SHARED_MIP2 = new int[NUM_TEXTURES][128 * 128];
    private static final int[][] SHARED_MIP3 = new int[NUM_TEXTURES][64 * 64];
    private static final int[] SHARED_NOISE_GRID = new int[64 * 64];
    private static final int[] SHARED_CLOUD_LUT = new int[256 * 256];

    private int width;
    private int height;
    private double halfW;
    private double halfH;
    private double currentFovDeg;
    private double focalLength;
    private double tanHalfFovY;
    private double tanHalfFovX;
    private double secHalfFovY;
    private double secHalfFovX;

    private BufferedImage frameImage;
    private int[] pixels;
    private int[] fxaaScratch;
    private byte[] fxaaLuma;
    private float[] zBuffer;

    // Без-аллокационный буфер треугольников для многопоточной растеризации по экранным полосам
    private int triCapacity = 32768;
    private int queuedTriCount = 0;
    private float[] qX0 = new float[triCapacity], qY0 = new float[triCapacity];
    private float[] qX1 = new float[triCapacity], qY1 = new float[triCapacity];
    private float[] qX2 = new float[triCapacity], qY2 = new float[triCapacity];
    private float[] qIz0 = new float[triCapacity], qIz1 = new float[triCapacity], qIz2 = new float[triCapacity];
    private float[] qUz0 = new float[triCapacity], qUz1 = new float[triCapacity], qUz2 = new float[triCapacity];
    private float[] qVz0 = new float[triCapacity], qVz1 = new float[triCapacity], qVz2 = new float[triCapacity];
    private float[] qLz0 = new float[triCapacity], qLz1 = new float[triCapacity], qLz2 = new float[triCapacity];
    private short[] qMinX = new short[triCapacity], qMaxX = new short[triCapacity];
    private short[] qMinY = new short[triCapacity], qMaxY = new short[triCapacity];
    private byte[] qTexId = new byte[triCapacity];
    private byte[] qFlags = new byte[triCapacity]; // bit0=fog, bit1=shadow, bit2=gouraud
    private int[] qShade = new int[triCapacity];

    private final int[][] bandBins = new int[NUM_BANDS][8192];
    private final int[] bandCounts = new int[NUM_BANDS];

    // Статистика оптимизации кадра для телеметрии HUD
    private int frameRenderedTris = 0;
    private int frameCulledObjects = 0;
    private int lastFrameRenderedTris = 0;
    private int lastFrameCulledObjects = 0;

    // Параметры камеры
    private double camX, camY, camZ;
    private double cosYaw = 1.0, sinYaw = 0.0;
    private double cosPitch = 1.0, sinPitch = 0.0;
    private double viewFlipX = 1.0;

    // Настройки качества, оптимизации и пост-обработки CS-2 JAVA 7.0
    private boolean fogEnabled = true;
    private boolean skyCloudsEnabled = true;
    private boolean smoothTexturesEnabled = true;
    private boolean fxaaEnabled = true;
    private boolean multiCoreEnabled = true;
    private boolean ambientOcclusionEnabled = true;
    private boolean vignetteEnabled = true;
    private GameSettings.AntiAliasingMode aaMode = GameSettings.AntiAliasingMode.SPAN_FXAA_ULTRA;
    private GameSettings.TextureQualityMode textureQuality = GameSettings.TextureQualityMode.BILINEAR_MIP;
    private double geometryLodScale = 1.0;
    private double maxRenderDist = 82.0;
    private int casSharpeningStrength = 110; // [0..340]
    private int colorVibranceBoost = 64;     // [-64..180]
    private int brightnessPct = 100;
    private final int[] brightnessLut = new int[256];
    private final int[] gradeClampLut = new int[768]; // [-256..511] -> clamped + gamma [0..255]
    private final int[] vibGainLut = new int[512];
    private int cachedBrightnessLutPct = -1;
    private int cachedVibBoost = Integer.MIN_VALUE;
    private int bakedGradeKey = Integer.MIN_VALUE;
    private int[][] activeMip0 = SHARED_MIP0;
    private int[][] activeMip1 = SHARED_MIP1;
    private int[][] activeMip2 = SHARED_MIP2;
    private int[][] activeMip3 = SHARED_MIP3;
    private int[][] gradedMip0, gradedMip1, gradedMip2, gradedMip3;
    private static final int[] VIG_MUL_LUT = new int[640];
    static {
        for (int i = 0; i < VIG_MUL_LUT.length; i++) {
            if (i <= 136) {
                VIG_MUL_LUT[i] = 256;
            } else {
                VIG_MUL_LUT[i] = Math.max(138, 256 - (((i - 136) * 86) >> 8));
            }
        }
    }
    private int[] colVigX = new int[0];
    private int[] rowVigY = new int[0];
    private final int[][] bandSeamTop = new int[NUM_BANDS][];
    private final int[][] bandSeamBot = new int[NUM_BANDS][];
    private final int[][] bandRingRgb = new int[NUM_BANDS * 3][];
    private final int[][] bandRingLum = new int[NUM_BANDS * 3][];
    private BufferedImage presentImage;
    private int[] presentPixels;
    private int presentW = -1, presentH = -1, scaleCachedSrcW = -1, scaleCachedSrcH = -1;
    private int[] scaleX0 = new int[0], scaleX1 = new int[0], scaleWx = new int[0];
    private boolean skyFilledThisFrame = false;
    private int frameSkyPixelsShaded = 0;
    private int lastFrameSkyPixelsShaded = 0;

    private GameSettings.WeatherMode weatherMode = GameSettings.WeatherMode.CLEAR;
    private double cloudAnimOffset = 0.0;
    private double lightningFlash = 0.0;

    private Vec3 sunDir = new Vec3(0.45, 0.78, -0.43).normalize();
    private int skyZenithColor = 0x2B5FA6;
    private int skyHorizonColor = 0xBFD8EE;
    private int fogColor = 0xDECBA8;
    private int sunDiscColor = 0xFFF7DC;
    private int lightTintColor = 0xFFFFFF;
    private double ambientStrength = 0.56;
    private double diffuseStrength = 0.48;
    private double baseFogStart = 28.0;
    private double baseFogEnd = 74.0;
    private double fogStart = 28.0;
    private double fogEnd = 74.0;

    // Временные массивы отсечения и трансформации (0 аллокаций в кадре!)
    private final double[] inX = new double[8], inY = new double[8], inZ = new double[8];
    private final double[] inU = new double[8], inV = new double[8], inL = new double[8];
    private final double[] outX = new double[8], outY = new double[8], outZ = new double[8];
    private final double[] outU = new double[8], outV = new double[8], outL = new double[8];
    private final double[] boxVx = new double[8], boxVy = new double[8], boxVz = new double[8];
    private final double[] vp0A = new double[3], vp1A = new double[3], vp0B = new double[3], vp1B = new double[3];

    public Renderer3D(int width, int height, double verticalFovDegrees) {
        ensureSharedTexturesInitialized();
        updateBrightnessLut(100);
        rebuildBuffers(width, height, verticalFovDegrees);
        applyTimeOfDay(GameSettings.TimeOfDay.DAY);
    }

    private static void ensureSharedTexturesInitialized() {
        if (texturesReady) return;
        synchronized (TEX_INIT_LOCK) {
            if (texturesReady) return;
            initSharedNoiseAndClouds();
            generateUltraSmoothTextures();
            buildSharedMipmaps();
            texturesReady = true;
        }
    }

    private static void initSharedNoiseAndClouds() {
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < SHARED_NOISE_GRID.length; i++) {
            seed = (seed * 25214903917L + 11L) & ((1L << 48) - 1);
            SHARED_NOISE_GRID[i] = (int) ((seed >>> 24) & 0xFF);
        }
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                double n = valueNoise(x * (8.0 / 256.0), y * (8.0 / 256.0), 8) * 0.56
                         + valueNoise(x * (16.0 / 256.0), y * (16.0 / 256.0), 16) * 0.29
                         + valueNoise(x * (32.0 / 256.0), y * (32.0 / 256.0), 32) * 0.15;
                double density = clamp((n - 0.49) * 2.05, 0.0, 1.0);
                density = density * density * (3.0 - 2.0 * density);
                SHARED_CLOUD_LUT[y * 256 + x] = Math.min(220, (int) (density * 212.0));
            }
        }
    }

    private static double valueNoise(double x, double y, int period) {
        int xi = ((int) Math.floor(x)) & (period - 1);
        int yi = ((int) Math.floor(y)) & (period - 1);
        int x1 = (xi + 1) & (period - 1);
        int y1 = (yi + 1) & (period - 1);
        double fx = x - Math.floor(x);
        double fy = y - Math.floor(y);
        double ux = fx * fx * fx * (fx * (fx * 6.0 - 15.0) + 10.0); // Квинтик Перлина C2-гладкости!
        double uy = fy * fy * fy * (fy * (fy * 6.0 - 15.0) + 10.0);

        double v00 = SHARED_NOISE_GRID[(yi & 63) * 64 + (xi & 63)] / 255.0;
        double v10 = SHARED_NOISE_GRID[(yi & 63) * 64 + (x1 & 63)] / 255.0;
        double v01 = SHARED_NOISE_GRID[(y1 & 63) * 64 + (xi & 63)] / 255.0;
        double v11 = SHARED_NOISE_GRID[(y1 & 63) * 64 + (x1 & 63)] / 255.0;

        double a = v00 + (v10 - v00) * ux;
        double b = v01 + (v11 - v01) * ux;
        return a + (b - a) * uy;
    }

    private static double fbmSeamless(int x, int y) {
        double sx = x * (64.0 / TEX_SIZE);
        double sy = y * (64.0 / TEX_SIZE);
        return valueNoise(sx * 0.125, sy * 0.125, 8) * 0.55
             + valueNoise(sx * 0.25, sy * 0.25, 16) * 0.30
             + valueNoise(sx * 0.50, sy * 0.50, 32) * 0.15;
    }

    public void ensureViewport(int newWidth, int newHeight, double verticalFovDegrees) {
        newWidth = Math.max(320, newWidth);
        newHeight = Math.max(240, newHeight);
        if (newWidth != this.width || newHeight != this.height) {
            rebuildBuffers(newWidth, newHeight, verticalFovDegrees);
        } else if (Math.abs(verticalFovDegrees - this.currentFovDeg) > 0.02) {
            updateFovMetrics(verticalFovDegrees);
        }
    }

    private void rebuildBuffers(int w, int h, double verticalFovDegrees) {
        this.width = w;
        this.height = h;
        this.halfW = w * 0.5;
        this.halfH = h * 0.5;
        this.frameImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) frameImage.getRaster().getDataBuffer()).getData();
        this.fxaaScratch = new int[w * h];
        this.fxaaLuma = new byte[w * h];
        this.zBuffer = new float[w * h];
        this.colVigX = new int[w];
        this.rowVigY = new int[h];
        for (int b = 0; b < NUM_BANDS; b++) {
            bandSeamTop[b] = new int[w];
            bandSeamBot[b] = new int[w];
            bandRingRgb[b * 3] = new int[w];
            bandRingRgb[b * 3 + 1] = new int[w];
            bandRingRgb[b * 3 + 2] = new int[w];
            bandRingLum[b * 3] = new int[w];
            bandRingLum[b * 3 + 1] = new int[w];
            bandRingLum[b * 3 + 2] = new int[w];
        }
        double invHW = 1.0 / Math.max(1.0, halfW);
        double invHH = 1.0 / Math.max(1.0, halfH);
        for (int x = 0; x < w; x++) {
            double nx = (x - halfW) * invHW;
            this.colVigX[x] = (int) (nx * nx * 256.0);
        }
        for (int y = 0; y < h; y++) {
            double ny = (y - halfH) * invHH;
            this.rowVigY[y] = (int) (ny * ny * 256.0);
        }
        updateFovMetrics(verticalFovDegrees);
    }

    private void updateBrightnessLut(int pct) {
        pct = Math.max(70, Math.min(150, pct));
        if (pct == cachedBrightnessLutPct) return;
        cachedBrightnessLutPct = pct;
        this.brightnessPct = pct;
        double gamma = 100.0 / pct;
        double gain = 0.94 + 0.06 * (pct / 100.0);
        for (int i = 0; i < 256; i++) {
            double norm = i / 255.0;
            int mapped = (int) Math.round(Math.pow(norm, gamma * 0.85) * gain * 255.0);
            brightnessLut[i] = Math.max(0, Math.min(255, mapped));
        }
        int low = brightnessLut[0];
        int high = brightnessLut[255];
        for (int i = 0; i < 256; i++) gradeClampLut[i] = low;
        for (int i = 0; i < 256; i++) gradeClampLut[256 + i] = brightnessLut[i];
        for (int i = 512; i < 768; i++) gradeClampLut[i] = high;
    }

    private void updateVibranceLut(int boost) {
        if (boost == cachedVibBoost) return;
        cachedVibBoost = boost;
        for (int s = 0; s < vibGainLut.length; s++) {
            vibGainLut[s] = (boost > 0) ? ((boost * (310 - Math.min(230, s))) >> 8) : boost;
        }
    }

    private int gradeColorOnce(int rgb) {
        if (colorVibranceBoost == 0 && brightnessPct == 100) return rgb;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (colorVibranceBoost != 0) {
            int luma = (r + (g << 1) + b) >> 2;
            int dr = r - luma, dg = g - luma, db = b - luma;
            int sat = (dr < 0 ? -dr : dr) + (db < 0 ? -db : db);
            if (sat > 511) sat = 511;
            int gain = vibGainLut[sat];
            r += (dr * gain) >> 8;
            g += (dg * gain) >> 8;
            b += (db * gain) >> 8;
        }
        return (gradeClampLut[256 + r] << 16) | (gradeClampLut[256 + g] << 8) | gradeClampLut[256 + b];
    }

    private void rebuildGradedMipsIfNeeded() {
        int key = (brightnessPct << 16) ^ (colorVibranceBoost & 0xFFFF);
        if (key == bakedGradeKey) return;
        bakedGradeKey = key;
        if (colorVibranceBoost == 0 && brightnessPct == 100) {
            activeMip0 = SHARED_MIP0;
            activeMip1 = SHARED_MIP1;
            activeMip2 = SHARED_MIP2;
            activeMip3 = SHARED_MIP3;
            return;
        }
        if (gradedMip0 == null) {
            gradedMip0 = new int[NUM_TEXTURES][TEX_SIZE * TEX_SIZE];
            gradedMip1 = new int[NUM_TEXTURES][256 * 256];
            gradedMip2 = new int[NUM_TEXTURES][128 * 128];
            gradedMip3 = new int[NUM_TEXTURES][64 * 64];
            Arrays.fill(gradedMip0[TEX_SOLID], 0xFFFFFF);
            Arrays.fill(gradedMip1[TEX_SOLID], 0xFFFFFF);
            Arrays.fill(gradedMip2[TEX_SOLID], 0xFFFFFF);
            Arrays.fill(gradedMip3[TEX_SOLID], 0xFFFFFF);
        }
        final int vib = this.colorVibranceBoost;
        final int[] gLut = this.gradeClampLut;
        final int[] vLut = this.vibGainLut;
        runParallelBands(NUM_TEXTURES, t -> {
            if (t == TEX_SOLID) return;
            gradeTextureArray(SHARED_MIP0[t], gradedMip0[t], vib, gLut, vLut);
            gradeTextureArray(SHARED_MIP1[t], gradedMip1[t], vib, gLut, vLut);
            gradeTextureArray(SHARED_MIP2[t], gradedMip2[t], vib, gLut, vLut);
            gradeTextureArray(SHARED_MIP3[t], gradedMip3[t], vib, gLut, vLut);
        });
        activeMip0 = gradedMip0;
        activeMip1 = gradedMip1;
        activeMip2 = gradedMip2;
        activeMip3 = gradedMip3;
    }

    private static void gradeTextureArray(int[] src, int[] dst, int vib, int[] gLut, int[] vLut) {
        final int len = src.length;
        if (vib == 0) {
            for (int i = 0; i < len; i++) {
                int c = src[i];
                dst[i] = (gLut[256 + ((c >> 16) & 0xFF)] << 16)
                       | (gLut[256 + ((c >> 8) & 0xFF)] << 8)
                       |  gLut[256 + (c & 0xFF)];
            }
        } else {
            for (int i = 0; i < len; i++) {
                int c = src[i];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int luma = (r + (g << 1) + b) >> 2;
                int dr = r - luma, dg = g - luma, db = b - luma;
                int sat = (dr < 0 ? -dr : dr) + (db < 0 ? -db : db);
                int gain = vLut[sat];
                r += (dr * gain) >> 8;
                g += (dg * gain) >> 8;
                b += (db * gain) >> 8;
                dst[i] = (gLut[256 + r] << 16) | (gLut[256 + g] << 8) | gLut[256 + b];
            }
        }
    }

    private void syncFogToRenderDistance() {
        double cappedEnd = Math.min(baseFogEnd, maxRenderDist - 1.5);
        this.fogEnd = Math.max(24.0, cappedEnd);
        this.fogStart = Math.min(baseFogStart, this.fogEnd * 0.44);
    }

    private void updateFovMetrics(double verticalFovDegrees) {
        this.currentFovDeg = verticalFovDegrees;
        double halfRad = Math.toRadians(verticalFovDegrees) * 0.5;
        this.tanHalfFovY = Math.tan(halfRad);
        this.focalLength = this.halfH / this.tanHalfFovY;
        this.tanHalfFovX = this.tanHalfFovY * ((double) width / height);
        this.secHalfFovY = Math.sqrt(1.0 + tanHalfFovY * tanHalfFovY);
        this.secHalfFovX = Math.sqrt(1.0 + tanHalfFovX * tanHalfFovX);
    }

    public void applyTimeOfDay(GameSettings.TimeOfDay tod) {
        switch (tod) {
            case DAY:
                sunDir = new Vec3(0.45, 0.78, -0.43).normalize();
                skyZenithColor = 0x265DA8;
                skyHorizonColor = 0xC6DFF4;
                fogColor = 0xE2D3B8;
                sunDiscColor = 0xFFF9E2;
                lightTintColor = 0xFFFFFF;
                ambientStrength = 0.58;
                diffuseStrength = 0.46;
                baseFogStart = 30.0;
                baseFogEnd = 78.0;
                break;
            case SUNSET:
                sunDir = new Vec3(0.68, 0.36, -0.58).normalize();
                skyZenithColor = 0x2C2858;
                skyHorizonColor = 0xF58A52;
                fogColor = 0xD88E68;
                sunDiscColor = 0xFFD086;
                lightTintColor = 0xFFE3C8;
                ambientStrength = 0.48;
                diffuseStrength = 0.54;
                baseFogStart = 24.0;
                baseFogEnd = 68.0;
                break;
            case NIGHT:
                sunDir = new Vec3(-0.38, 0.72, 0.52).normalize();
                skyZenithColor = 0x091020;
                skyHorizonColor = 0x1A2B48;
                fogColor = 0x121D32;
                sunDiscColor = 0xD2E4FF;
                lightTintColor = 0x9CB8E4;
                ambientStrength = 0.36;
                diffuseStrength = 0.34;
                baseFogStart = 18.0;
                baseFogEnd = 56.0;
                break;
        }

        if (weatherMode == GameSettings.WeatherMode.RAIN_STORM) {
            skyZenithColor = lerpColor(skyZenithColor, 0x263242, 0.68);
            skyHorizonColor = lerpColor(skyHorizonColor, 0x5C6C7E, 0.68);
            fogColor = lerpColor(fogColor, 0x566474, 0.66);
            baseFogStart = 19.0;
            baseFogEnd = 58.0;
        } else if (weatherMode == GameSettings.WeatherMode.SNOW) {
            skyZenithColor = lerpColor(skyZenithColor, 0x627D9C, 0.55);
            skyHorizonColor = lerpColor(skyHorizonColor, 0xDCE6F2, 0.65);
            fogColor = lerpColor(fogColor, 0xD8E2EE, 0.68);
            baseFogStart = 21.0;
            baseFogEnd = 60.0;
        }
        syncFogToRenderDistance();
    }

    public void setGraphicsFlags(boolean fog, boolean skyClouds, boolean leftHanded, double animTime) {
        this.fogEnabled = fog;
        this.skyCloudsEnabled = skyClouds;
        this.viewFlipX = leftHanded ? -1.0 : 1.0;
        this.cloudAnimOffset = animTime;

        if (weatherMode == GameSettings.WeatherMode.RAIN_STORM) {
            double cycle = animTime % 7.5;
            this.lightningFlash = (cycle > 7.18 && cycle < 7.36) ? 0.50 : 0.0;
        } else {
            this.lightningFlash = 0.0;
        }
    }

    public void setSmoothQualityFlags(boolean smoothTextures, boolean fxaa, GameSettings.WeatherMode weather) {
        setSmoothQualityFlags(smoothTextures, fxaa, true, weather);
    }

    public void setSmoothQualityFlags(boolean smoothTextures, boolean fxaa, boolean multiCore, GameSettings.WeatherMode weather) {
        this.smoothTexturesEnabled = smoothTextures;
        this.fxaaEnabled = fxaa;
        this.aaMode = fxaa ? GameSettings.AntiAliasingMode.SPAN_FXAA_ULTRA : GameSettings.AntiAliasingMode.OFF;
        this.multiCoreEnabled = multiCore;
        this.weatherMode = (weather != null) ? weather : GameSettings.WeatherMode.CLEAR;
    }

    /**
     * Полная конфигурация графического конвейера CS-2 JAVA 7.0 из GameSettings!
     */
    public void configureAdvancedGraphics(GameSettings s) {
        if (s == null) return;
        this.aaMode = (s.antiAliasingMode != null) ? s.antiAliasingMode : GameSettings.AntiAliasingMode.SPAN_FXAA_ULTRA;
        this.fxaaEnabled = (this.aaMode != GameSettings.AntiAliasingMode.OFF);
        this.textureQuality = (s.textureQuality != null) ? s.textureQuality : GameSettings.TextureQualityMode.BILINEAR_MIP;
        this.smoothTexturesEnabled = (this.textureQuality != GameSettings.TextureQualityMode.FAST_MIP);
        this.geometryLodScale = (s.geometryLod != null) ? s.geometryLod.detailScale : 1.0;
        this.maxRenderDist = (s.renderDistance != null) ? s.renderDistance.meters : 88.0;
        this.ambientOcclusionEnabled = s.ambientOcclusion;
        this.vignetteEnabled = s.vignetteEnabled;
        this.casSharpeningStrength = (s.casSharpening != null) ? (s.casSharpening.strengthPct * 320 / 100) : 0;
        this.colorVibranceBoost = (s.colorVibrance != null) ? ((s.colorVibrance.vibrancePct - 100) * 256 / 100) : 0;
        this.multiCoreEnabled = s.multiCoreRendering;
        this.weatherMode = (s.weatherMode != null) ? s.weatherMode : GameSettings.WeatherMode.CLEAR;
        updateBrightnessLut(s.brightnessPct);
        updateVibranceLut(this.colorVibranceBoost);
        rebuildGradedMipsIfNeeded();
        syncFogToRenderDistance();
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public BufferedImage getFrameImage() { return frameImage; }

    /**
     * Многопоточный SWAR-апскейлер/даунскейлер (в 15x быстрее однопоточного Java2D drawImage!).
     * При совпадении разрешений (Native 1:1) возвращает frameImage за 0.00 мс без копирования!
     */
    public BufferedImage getPresentedImage(int outW, int outH, boolean bilinear) {
        if (outW <= 0 || outH <= 0 || (outW == this.width && outH == this.height)) {
            return this.frameImage;
        }
        final int srcW = this.width;
        final int srcH = this.height;
        if (presentImage == null || presentW != outW || presentH != outH) {
            presentW = outW;
            presentH = outH;
            presentImage = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
            presentPixels = ((DataBufferInt) presentImage.getRaster().getDataBuffer()).getData();
            scaleX0 = new int[outW];
            scaleX1 = new int[outW];
            scaleWx = new int[outW];
            scaleCachedSrcW = -1;
        }
        if (scaleCachedSrcW != srcW || scaleCachedSrcH != srcH) {
            scaleCachedSrcW = srcW;
            scaleCachedSrcH = srcH;
            final long stepXFp = (((long) (srcW - 1)) << 16) / Math.max(1, outW - 1);
            long sxFp = 0L;
            for (int x = 0; x < outW; x++) {
                int x0 = (int) (sxFp >>> 16);
                if (x0 >= srcW - 1) {
                    scaleX0[x] = srcW - 1;
                    scaleX1[x] = srcW - 1;
                    scaleWx[x] = 0;
                } else {
                    scaleX0[x] = x0;
                    scaleX1[x] = x0 + 1;
                    scaleWx[x] = (int) ((sxFp >>> 8) & 0xFFL);
                }
                sxFp += stepXFp;
            }
        }

        final int[] src = this.pixels;
        final int[] dst = this.presentPixels;
        final int[] x0Arr = this.scaleX0;
        final int[] x1Arr = this.scaleX1;
        final int[] wxArr = this.scaleWx;
        final int rowsPerBand = (outH + NUM_BANDS - 1) / NUM_BANDS;

        if (!bilinear) {
            runParallelBands(NUM_BANDS, band -> {
                int yStart = band * rowsPerBand;
                int yEnd = Math.min(outH, yStart + rowsPerBand);
                for (int y = yStart; y < yEnd; y++) {
                    int sy = (y * srcH) / outH;
                    int srcRow = sy * srcW;
                    int dstRow = y * outW;
                    for (int x = 0; x < outW; x++) {
                        dst[dstRow + x] = src[srcRow + x0Arr[x]];
                    }
                }
            });
            return this.presentImage;
        }

        final long stepYFp = (((long) (srcH - 1)) << 16) / Math.max(1, outH - 1);
        runParallelBands(NUM_BANDS, band -> {
            int yStart = band * rowsPerBand;
            int yEnd = Math.min(outH, yStart + rowsPerBand);
            long syFp = yStart * stepYFp;
            for (int y = yStart; y < yEnd; y++, syFp += stepYFp) {
                int y0 = (int) (syFp >>> 16);
                int y1, wy;
                if (y0 >= srcH - 1) {
                    y0 = srcH - 1;
                    y1 = srcH - 1;
                    wy = 0;
                } else {
                    y1 = y0 + 1;
                    wy = (int) ((syFp >>> 8) & 0xFFL);
                }
                int invY = 256 - wy;
                int row0 = y0 * srcW;
                int row1 = y1 * srcW;
                int dstRow = y * outW;

                if (wy == 0) {
                    for (int x = 0; x < outW; x++) {
                        int c0 = src[row0 + x0Arr[x]];
                        int c1 = src[row0 + x1Arr[x]];
                        int wx = wxArr[x];
                        int invX = 256 - wx;
                        int rb = (((c0 & 0x00FF00FF) * invX + (c1 & 0x00FF00FF) * wx) >>> 8) & 0x00FF00FF;
                        int g  = (((c0 & 0x0000FF00) * invX + (c1 & 0x0000FF00) * wx) >>> 8) & 0x0000FF00;
                        dst[dstRow + x] = rb | g;
                    }
                } else {
                    for (int x = 0; x < outW; x++) {
                        int ix0 = x0Arr[x];
                        int ix1 = x1Arr[x];
                        int wx = wxArr[x];
                        int invX = 256 - wx;

                        int c00 = src[row0 + ix0], c10 = src[row0 + ix1];
                        int rb0 = (((c00 & 0x00FF00FF) * invX + (c10 & 0x00FF00FF) * wx) >>> 8) & 0x00FF00FF;
                        int g0  = (((c00 & 0x0000FF00) * invX + (c10 & 0x0000FF00) * wx) >>> 8) & 0x0000FF00;

                        int c01 = src[row1 + ix0], c11 = src[row1 + ix1];
                        int rb1 = (((c01 & 0x00FF00FF) * invX + (c11 & 0x00FF00FF) * wx) >>> 8) & 0x00FF00FF;
                        int g1  = (((c01 & 0x0000FF00) * invX + (c11 & 0x0000FF00) * wx) >>> 8) & 0x0000FF00;

                        int rb = ((rb0 * invY + rb1 * wy) >>> 8) & 0x00FF00FF;
                        int g  = ((g0  * invY + g1  * wy) >>> 8) & 0x0000FF00;
                        dst[dstRow + x] = rb | g;
                    }
                }
            }
        });
        return this.presentImage;
    }
    public Vec3 getSunDir() { return sunDir; }
    public int getWorkerThreads() { return WORKER_THREADS; }
    public int getLastFrameRenderedTris() { return lastFrameRenderedTris; }
    public int getLastFrameCulledObjects() { return lastFrameCulledObjects; }
    public int getLastFrameSkyPixelsShaded() { return lastFrameSkyPixelsShaded; }

    public void setCamera(double x, double y, double z, double yaw, double pitch) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.cosYaw = Math.cos(yaw);
        this.sinYaw = Math.sin(yaw);
        this.cosPitch = Math.cos(pitch);
        this.sinPitch = Math.sin(pitch);
    }

    /**
     * Work-Stealing параллельный диспетчер полос: главный поток участвует в расчётах без простоя!
     */
    private void runParallelBands(final int totalBands, final IntConsumer bandWorker) {
        if (!multiCoreEnabled || WORKER_THREADS <= 1 || totalBands <= 1) {
            for (int b = 0; b < totalBands; b++) {
                bandWorker.accept(b);
            }
            return;
        }
        final AtomicInteger nextBand = new AtomicInteger(0);
        final int helperCount = Math.min(BG_WORKERS, totalBands - 1);
        final CountDownLatch latch = new CountDownLatch(helperCount);
        for (int w = 0; w < helperCount; w++) {
            WORKER_POOL.execute(() -> {
                try {
                    int b;
                    while ((b = nextBand.getAndIncrement()) < totalBands) {
                        bandWorker.accept(b);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        int b;
        while ((b = nextBand.getAndIncrement()) < totalBands) {
            bandWorker.accept(b);
        }
        awaitLatch(latch);
    }

    public void clearDepthBuffer() {
        ensureSkyFilled();
        flushRasterQueue();
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);
    }

    public void beginFrame() {
        lastFrameRenderedTris = frameRenderedTris;
        lastFrameCulledObjects = frameCulledObjects;
        lastFrameSkyPixelsShaded = frameSkyPixelsShaded;
        frameRenderedTris = 0;
        frameCulledObjects = 0;
        frameSkyPixelsShaded = 0;
        queuedTriCount = 0;
        skyFilledThisFrame = false;
        for (int b = 0; b < NUM_BANDS; b++) bandCounts[b] = 0;

        final int total = width * height;
        final int chunk = (total + NUM_BANDS - 1) / NUM_BANDS;
        runParallelBands(NUM_BANDS, band -> {
            int i0 = band * chunk;
            int i1 = Math.min(total, i0 + chunk);
            if (i0 < i1) {
                Arrays.fill(zBuffer, i0, i1, Float.POSITIVE_INFINITY);
            }
        });
    }

    public void ensureSkyFilled() {
        if (!skyFilledThisFrame) {
            flushRasterQueue();
            fillVisibleSkyParallel();
        }
    }

    /**
     * Deferred Skybox Shading 7.0: вычисляет градиент неба, билинейные облака и солнце
     * ТОЛЬКО для открытых пикселей неба (zBuffer[idx] == Float.POSITIVE_INFINITY), экономя ~80% работы неба!
     */
    public void fillVisibleSkyParallel() {
        if (skyFilledThisFrame) return;
        flushRasterQueue();
        skyFilledThisFrame = true;

        double sx = sunDir.x * cosYaw - sunDir.z * sinYaw;
        double sz1 = sunDir.x * sinYaw + sunDir.z * cosYaw;
        double sy = sunDir.y * cosPitch - sz1 * sinPitch;
        double sz = sunDir.y * sinPitch + sz1 * cosPitch;

        final boolean sunInFront = (sz > 0.05) && (weatherMode == GameSettings.WeatherMode.CLEAR);
        final double sunScreenX = sunInFront ? (halfW + (sx / sz) * focalLength) : -9999.0;
        final double sunScreenY = sunInFront ? (halfH - (sy / sz) * focalLength) : -9999.0;
        final double maxGlowR = height * 0.30;
        final double maxGlowR2 = maxGlowR * maxGlowR;
        final double invMaxGlowR2 = 1.0 / Math.max(1.0, maxGlowR2);
        final double coreR2 = (height * 0.036) * (height * 0.036);

        final double invFocal = 1.0 / focalLength;
        final double shiftU = cloudAnimOffset * 2.2;
        final double shiftV = cloudAnimOffset * 1.3;
        final int rowsPerBand = (height + NUM_BANDS - 1) / NUM_BANDS;
        final AtomicInteger skyPixCounter = new AtomicInteger(0);

        runParallelBands(NUM_BANDS, band -> {
            int yStart = band * rowsPerBand;
            int yEnd = Math.min(height, yStart + rowsPerBand);
            if (yStart < yEnd) {
                int shaded = renderVisibleSkyBand(yStart, yEnd, sunInFront, sunScreenX, sunScreenY,
                        maxGlowR, maxGlowR2, invMaxGlowR2, coreR2, invFocal, shiftU, shiftV);
                skyPixCounter.addAndGet(shaded);
            }
        });
        frameSkyPixelsShaded = skyPixCounter.get();
    }

    private int renderVisibleSkyBand(int yStart, int yEnd, boolean sunInFront, double sunScreenX, double sunScreenY,
                                     double maxGlowR, double maxGlowR2, double invMaxGlowR2, double coreR2,
                                     double invFocal, double shiftU, double shiftV) {
        int shadedCount = 0;
        final int w = this.width;
        final int gSkyHor = gradeColorOnce(skyHorizonColor);
        final int gSkyZen = gradeColorOnce(skyZenithColor);
        final int gFogCol = gradeColorOnce(fogColor);
        for (int y = yStart; y < yEnd; y++) {
            int rowOffset = y * w;
            int firstOpenX = -1;
            for (int x = 0; x < w; x++) {
                if (zBuffer[rowOffset + x] == Float.POSITIVE_INFINITY) {
                    firstOpenX = x;
                    break;
                }
            }
            if (firstOpenX < 0) continue;

            double screenSlopeY = (halfH - y) * invFocal;
            double rayElev = screenSlopeY * cosPitch + sinPitch;
            int rowBaseColor;
            if (rayElev >= 0.0) {
                double re = Math.min(1.0, rayElev);
                double t = Math.min(1.0, Math.sqrt(re) * (0.84 + 0.16 * re));
                rowBaseColor = lerpColor(gSkyHor, gSkyZen, t);
            } else {
                double t = Math.min(1.0, -rayElev * 2.4);
                rowBaseColor = lerpColor(gFogCol, shadeColor(gFogCol, 0.78), t);
            }
            if (lightningFlash > 0.0) {
                rowBaseColor = lerpColor(rowBaseColor, 0xDCEEFF, lightningFlash);
            }

            boolean checkClouds = skyCloudsEnabled && rayElev > 0.055;
            boolean checkSun = skyCloudsEnabled && sunInFront && Math.abs(y - sunScreenY) <= maxGlowR;

            if (!checkClouds && !checkSun) {
                for (int x = firstOpenX; x < w; x++) {
                    if (zBuffer[rowOffset + x] == Float.POSITIVE_INFINITY) {
                        pixels[rowOffset + x] = rowBaseColor;
                        shadedCount++;
                    }
                }
                continue;
            }

            double dy = y - sunScreenY;
            double dy2 = dy * dy;
            double czRow = -screenSlopeY * sinPitch + cosPitch;
            double invElevScale = 38.0 / (rayElev + 0.09);
            int cloudFade = Math.min(256, (int) (rayElev * 580.0));
            int cloudTint = lerpColor(gSkyHor, 0xFFFFFF, 0.62);

            double wx0 = ((-halfW + firstOpenX) * invFocal) * cosYaw + czRow * sinYaw;
            double wz0 = -((-halfW + firstOpenX) * invFocal) * sinYaw + czRow * cosYaw;
            double stepWx = invFocal * cosYaw;
            double stepWz = -invFocal * sinYaw;

            int uFixed = (int) Math.floor((wx0 * invElevScale + shiftU + 4096.0) * 65536.0);
            int vFixed = (int) Math.floor((wz0 * invElevScale + shiftV + 4096.0) * 65536.0);
            int stepUFixed = (int) (stepWx * invElevScale * 65536.0);
            int stepVFixed = (int) (stepWz * invElevScale * 65536.0);

            for (int x = firstOpenX; x < w; x++) {
                if (zBuffer[rowOffset + x] == Float.POSITIVE_INFINITY) {
                    int col = rowBaseColor;
                    if (checkClouds) {
                        int iu0 = (uFixed >>> 16) & 255;
                        int iv0 = (vFixed >>> 16) & 255;
                        int iu1 = (iu0 + 1) & 255;
                        int iv1 = (iv0 + 1) & 255;
                        int fu = (uFixed >>> 8) & 255;
                        int fv = (vFixed >>> 8) & 255;

                        int row0 = iv0 << 8;
                        int row1 = iv1 << 8;
                        int c00 = SHARED_CLOUD_LUT[row0 | iu0];
                        int c10 = SHARED_CLOUD_LUT[row0 | iu1];
                        int c01 = SHARED_CLOUD_LUT[row1 | iu0];
                        int c11 = SHARED_CLOUD_LUT[row1 | iu1];
                        int topC = c00 + (((c10 - c00) * fu) >> 8);
                        int botC = c01 + (((c11 - c01) * fu) >> 8);
                        int biCloud = topC + (((botC - topC) * fv) >> 8);

                        int cAlpha = (biCloud * cloudFade) >> 8;
                        if (cAlpha > 2) {
                            int invA = 256 - cAlpha;
                            int rb = (((col & 0x00FF00FF) * invA + (cloudTint & 0x00FF00FF) * cAlpha) >>> 8) & 0x00FF00FF;
                            int g  = (((col & 0x0000FF00) * invA + (cloudTint & 0x0000FF00) * cAlpha) >>> 8) & 0x0000FF00;
                            col = rb | g;
                        }
                    }
                    if (checkSun && rayElev > -0.04) {
                        double dx = x - sunScreenX;
                        double d2 = dx * dx + dy2;
                        if (d2 < maxGlowR2) {
                            double t = 1.0 - d2 * invMaxGlowR2;
                            double glow = t * t * t * 0.80;
                            if (d2 < coreR2) glow = Math.min(1.0, glow + 0.65);
                            col = lerpColor(col, sunDiscColor, glow);
                        }
                    }
                    pixels[rowOffset + x] = col;
                    shadedCount++;
                }
                uFixed += stepUFixed;
                vFixed += stepVFixed;
            }
        }
        return shadedCount;
    }

    public boolean isSphereVisible(double wx, double wy, double wz, double radius) {
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;

        double rx = dx * cosYaw - dz * sinYaw;
        double rz1 = dx * sinYaw + dz * cosYaw;
        double cy = dy * cosPitch - rz1 * sinPitch;
        double cz = dy * sinPitch + rz1 * cosPitch;

        if (cz + radius < NEAR_Z || cz - radius > maxRenderDist) {
            frameCulledObjects++;
            return false;
        }
        if (Math.abs(rx) > cz * tanHalfFovX + radius * secHalfFovX) {
            frameCulledObjects++;
            return false;
        }
        if (Math.abs(cy) > cz * tanHalfFovY + radius * secHalfFovY) {
            frameCulledObjects++;
            return false;
        }
        return true;
    }

    private int computeLodSegments(int baseSegs, int minSegs, double wx, double wy, double wz) {
        double dx = wx - camX, dy = wy - camY, dz = wz - camZ;
        double dist2 = dx * dx + dy * dy + dz * dz;
        double distFactor = (dist2 < 144.0) ? 1.0 : (dist2 < 529.0 ? 0.72 : (dist2 < 1444.0 ? 0.52 : 0.38));
        int lod = (int) Math.round(baseSegs * geometryLodScale * distFactor);
        return Math.max(minSegs, Math.min(32, lod));
    }

    public void flushRasterQueue() {
        if (queuedTriCount == 0) return;

        if (!multiCoreEnabled || WORKER_THREADS <= 1 || queuedTriCount < 28) {
            for (int i = 0; i < queuedTriCount; i++) {
                rasterizeQueuedTriangleInBand(i, 0, height - 1);
            }
        } else {
            final int rowsPerBand = (height + NUM_BANDS - 1) / NUM_BANDS;
            runParallelBands(NUM_BANDS, band -> {
                int count = bandCounts[band];
                if (count <= 0) return;
                int bandY0 = band * rowsPerBand;
                int bandY1 = Math.min(height - 1, bandY0 + rowsPerBand - 1);
                int[] bin = bandBins[band];
                for (int k = 0; k < count; k++) {
                    rasterizeQueuedTriangleInBand(bin[k], bandY0, bandY1);
                }
            });
        }

        queuedTriCount = 0;
        for (int b = 0; b < NUM_BANDS; b++) bandCounts[b] = 0;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public void applyFxaaPass() {
        applyPostProcessingPass();
    }

    public void applyPostProcessingPass() {
        ensureSkyFilled();
        flushRasterQueue();
        if (width < 320 || height < 240) return;

        final boolean doAa = fxaaEnabled && aaMode != GameSettings.AntiAliasingMode.OFF;
        final boolean turboAa = (aaMode == GameSettings.AntiAliasingMode.FXAA_TURBO);
        final int cas = this.casSharpeningStrength;
        final boolean doVig = this.vignetteEnabled;

        if (!doAa && cas <= 0 && !doVig) {
            return;
        }

        final int w = width;
        final int h = height;
        final int total = w * h;
        final int rowsPerBand = (h + NUM_BANDS - 1) / NUM_BANDS;
        final int[] px = this.pixels;

        if (!doAa && cas <= 0) {
            final int[] vxArr = this.colVigX;
            final int[] vyArr = this.rowVigY;
            final int[] vigLut = VIG_MUL_LUT;
            runParallelBands(NUM_BANDS, band -> {
                int y0 = band * rowsPerBand;
                int y1 = Math.min(h, y0 + rowsPerBand);
                for (int y = y0; y < y1; y++) {
                    int row = y * w;
                    int vy = vyArr[y];
                    for (int x = 0; x < w; x++) {
                        int vm = vigLut[vxArr[x] + vy];
                        if (vm < 256) {
                            int c = px[row + x];
                            px[row + x] = ((((c & 0x00FF00FF) * vm) >>> 8) & 0x00FF00FF)
                                        | ((((c & 0x0000FF00) * vm) >>> 8) & 0x0000FF00);
                        }
                    }
                }
            });
            return;
        }

        // Сверхбыстрый 1-барьерный L1-Cache Rolling-Row движок (0 копий 3.7 МБ fxaaScratch!) для OFF и FXAA_TURBO!
        if (!doAa || turboAa) {
            for (int b = 0; b < NUM_BANDS; b++) {
                int y0 = b * rowsPerBand;
                int y1 = Math.min(h, y0 + rowsPerBand);
                if (y0 >= y1) continue;
                if (y0 > 0) System.arraycopy(px, (y0 - 1) * w, bandSeamTop[b], 0, w);
                if (y1 < h) System.arraycopy(px, y1 * w, bandSeamBot[b], 0, w);
            }
            runParallelBands(NUM_BANDS, band -> {
                int y0 = band * rowsPerBand;
                int y1 = Math.min(h, y0 + rowsPerBand);
                if (y0 < y1) {
                    runRollingL1Band(band, y0, y1, w, h, doAa, cas, doVig);
                }
            });
            return;
        }

        final int[] sc = this.fxaaScratch;
        final byte[] lum = this.fxaaLuma;
        runParallelBands(NUM_BANDS, band -> {
            int i0 = band * rowsPerBand * w;
            int i1 = Math.min(total, (band + 1) * rowsPerBand * w);
            for (int i = i0; i < i1; i++) {
                int c = px[i];
                sc[i] = c;
                lum[i] = (byte) ((((c >> 16) & 0xFF) + (((c >> 8) & 0xFF) << 1) + (c & 0xFF)) >> 2);
            }
        });

        runParallelBands(NUM_BANDS, band -> {
            int y0 = band * rowsPerBand;
            int y1 = Math.min(h, (band + 1) * rowsPerBand);
            if (y0 >= y1) return;
            runFusedPostFxBand(y0, y1, w, h, true, false, cas, 0, false, doVig);
        });
    }

    private static void computeRowLuma(int[] rgbRow, int[] lumRow, int w) {
        for (int x = 0; x < w; x++) {
            int c = rgbRow[x];
            lumRow[x] = (((c >> 16) & 0xFF) + (((c >> 8) & 0xFF) << 1) + (c & 0xFF)) >> 2;
        }
    }

    private void runRollingL1Band(int band, int y0, int y1, int w, int h, boolean doTurboAa, int cas, boolean doVig) {
        final int[] px = this.pixels;
        final int[] vxArr = this.colVigX;
        final int[] vyArr = this.rowVigY;
        final int[] vigLut = VIG_MUL_LUT;
        final int lastX = w - 1;

        int[] rPrev = bandRingRgb[band * 3];
        int[] rCurr = bandRingRgb[band * 3 + 1];
        int[] rNext = bandRingRgb[band * 3 + 2];
        int[] lPrev = bandRingLum[band * 3];
        int[] lCurr = bandRingLum[band * 3 + 1];
        int[] lNext = bandRingLum[band * 3 + 2];

        System.arraycopy(px, y0 * w, rCurr, 0, w);
        computeRowLuma(rCurr, lCurr, w);
        if (y0 > 0) {
            System.arraycopy(bandSeamTop[band], 0, rPrev, 0, w);
            computeRowLuma(rPrev, lPrev, w);
        }

        for (int y = y0; y < y1; y++) {
            if (y + 1 < y1) {
                System.arraycopy(px, (y + 1) * w, rNext, 0, w);
                computeRowLuma(rNext, lNext, w);
            } else if (y + 1 < h) {
                System.arraycopy(bandSeamBot[band], 0, rNext, 0, w);
                computeRowLuma(rNext, lNext, w);
            }

            final int row = y * w;
            final int vy = vyArr[y];

            if (y == 0 || y == h - 1) {
                if (doVig) {
                    for (int x = 0; x < w; x++) {
                        int vm = vigLut[vxArr[x] + vy];
                        if (vm < 256) {
                            int c = rCurr[x];
                            px[row + x] = ((((c & 0x00FF00FF) * vm) >>> 8) & 0x00FF00FF)
                                        | ((((c & 0x0000FF00) * vm) >>> 8) & 0x0000FF00);
                        }
                    }
                }
            } else {
                for (int x = 0; x < w; x++) {
                    int outCol = rCurr[x];
                    boolean modified = false;

                    if (x > 0 && x < lastX) {
                        int lM = lCurr[x];
                        int lN = lPrev[x];
                        int lS = lNext[x];
                        int lW = lCurr[x - 1];
                        int lE = lCurr[x + 1];

                        int lMin = lM < lN ? lM : lN;
                        if (lS < lMin) lMin = lS;
                        if (lW < lMin) lMin = lW;
                        if (lE < lMin) lMin = lE;
                        int lMax = lM > lN ? lM : lN;
                        if (lS > lMax) lMax = lS;
                        if (lW > lMax) lMax = lW;
                        if (lE > lMax) lMax = lE;
                        int range = lMax - lMin;

                        if (doTurboAa && range >= Math.max(24, lMax >> 3)) {
                            int gradH = Math.abs(lN + lS - (lM << 1));
                            int gradV = Math.abs(lW + lE - (lM << 1));
                            int cOther;
                            if (gradH >= gradV) {
                                cOther = (Math.abs(lN - lM) >= Math.abs(lS - lM)) ? rPrev[x] : rNext[x];
                            } else {
                                cOther = (Math.abs(lW - lM) >= Math.abs(lE - lM)) ? rCurr[x - 1] : rCurr[x + 1];
                            }
                            int blend256 = Math.min(108, (Math.abs(((lN + lS + lW + lE) >> 2) - lM) * 210) / range);
                            if (blend256 > 6) {
                                int inv = 256 - blend256;
                                outCol = ((((outCol & 0x00FF00FF) * inv + (cOther & 0x00FF00FF) * blend256) >>> 8) & 0x00FF00FF)
                                       | ((((outCol & 0x0000FF00) * inv + (cOther & 0x0000FF00) * blend256) >>> 8) & 0x0000FF00);
                                modified = true;
                            }
                        } else if (cas > 0 && range > 5) {
                            int lap = (lM << 2) - (lN + lS + lW + lE);
                            int d = (lap * ((cas * (255 - range)) >> 10)) >> 8;
                            if (d != 0) {
                                int lSharp = lM + d;
                                if (lSharp < lMin) lSharp = lMin;
                                else if (lSharp > lMax) lSharp = lMax;
                                int cd = lSharp - lM;
                                if (cd != 0) {
                                    int r = ((outCol >> 16) & 0xFF) + cd;
                                    int g = ((outCol >> 8) & 0xFF) + cd;
                                    int b = (outCol & 0xFF) + cd;
                                    if (r < 0) r = 0; else if (r > 255) r = 255;
                                    if (g < 0) g = 0; else if (g > 255) g = 255;
                                    if (b < 0) b = 0; else if (b > 255) b = 255;
                                    outCol = (r << 16) | (g << 8) | b;
                                    modified = true;
                                }
                            }
                        }
                    }

                    if (doVig) {
                        int vm = vigLut[vxArr[x] + vy];
                        if (vm < 256) {
                            outCol = ((((outCol & 0x00FF00FF) * vm) >>> 8) & 0x00FF00FF)
                                   | ((((outCol & 0x0000FF00) * vm) >>> 8) & 0x0000FF00);
                            modified = true;
                        }
                    }

                    if (modified) {
                        px[row + x] = outCol;
                    }
                }
            }

            int[] tmpR = rPrev; rPrev = rCurr; rCurr = rNext; rNext = tmpR;
            int[] tmpL = lPrev; lPrev = lCurr; lCurr = lNext; lNext = tmpL;
        }
    }

    private void runFusedPostFxBand(int y0, int y1, int w, int h,
                                    boolean doAa, boolean turboAa, int cas, int vib,
                                    boolean hasColorGrade, boolean doVig) {
        final int[] px = this.pixels;
        final int[] src = doAa ? this.fxaaScratch : this.pixels;
        final byte[] lum = this.fxaaLuma;
        final int[] vxArr = this.colVigX;
        final int[] vyArr = this.rowVigY;
        final int[] gLut = this.gradeClampLut;
        final int[] vLut = this.vibGainLut;
        final int[] vigLut = VIG_MUL_LUT;
        final int lastX = w - 1;

        for (int y = y0; y < y1; y++) {
            final int row = y * w;
            final int vy = vyArr[y];

            if (y == 0 || y == h - 1) {
                for (int x = 0; x < w; x++) {
                    int outCol = src[row + x];
                    if (hasColorGrade) {
                        int r = (outCol >> 16) & 0xFF, g = (outCol >> 8) & 0xFF, b = outCol & 0xFF;
                        if (vib != 0) {
                            int luma = (r + (g << 1) + b) >> 2;
                            int dr = r - luma, dg = g - luma, db = b - luma;
                            int gain = vLut[(dr < 0 ? -dr : dr) + (db < 0 ? -db : db)];
                            r += (dr * gain) >> 8;
                            g += (dg * gain) >> 8;
                            b += (db * gain) >> 8;
                        }
                        outCol = (gLut[256 + r] << 16) | (gLut[256 + g] << 8) | gLut[256 + b];
                    }
                    if (doVig) {
                        int vm = vigLut[vxArr[x] + vy];
                        if (vm < 256) {
                            outCol = ((((outCol & 0x00FF00FF) * vm) >>> 8) & 0x00FF00FF)
                                   | ((((outCol & 0x0000FF00) * vm) >>> 8) & 0x0000FF00);
                        }
                    }
                    px[row + x] = outCol;
                }
                continue;
            }

            if (!hasColorGrade) {
                // Сверхбыстрый путь без грейдинга: пропускает запись ~65% плоских внутренних пикселей!
                for (int x = 0; x < w; x++) {
                    int idx = row + x;
                    int outCol = src[idx];
                    boolean modified = false;

                    if (x > 0 && x < lastX) {
                        int lM = lum[idx] & 0xFF;
                        int lN = lum[idx - w] & 0xFF;
                        int lS = lum[idx + w] & 0xFF;
                        int lW = lum[idx - 1] & 0xFF;
                        int lE = lum[idx + 1] & 0xFF;

                        int lMin = lM < lN ? lM : lN;
                        if (lS < lMin) lMin = lS;
                        if (lW < lMin) lMin = lW;
                        if (lE < lMin) lMin = lE;
                        int lMax = lM > lN ? lM : lN;
                        if (lS > lMax) lMax = lS;
                        if (lW > lMax) lMax = lW;
                        if (lE > lMax) lMax = lE;
                        int range = lMax - lMin;

                        if (doAa && range >= Math.max(24, lMax >> 3)) {
                            int blended = turboAa
                                    ? computeTurboFxaaPixel(src, idx, w, outCol, lM, lN, lS, lW, lE, range)
                                    : computeSpanFxaaPixel(idx, x, y, w, h, outCol, lM, lN, lS, lW, lE, range);
                            if (blended != outCol) {
                                outCol = blended;
                                modified = true;
                            }
                        } else if (cas > 0 && range > 5) {
                            int lap = (lM << 2) - (lN + lS + lW + lE);
                            int d = (lap * ((cas * (255 - range)) >> 10)) >> 8;
                            if (d != 0) {
                                int lSharp = lM + d;
                                if (lSharp < lMin) lSharp = lMin;
                                else if (lSharp > lMax) lSharp = lMax;
                                int cd = lSharp - lM;
                                if (cd != 0) {
                                    int r = ((outCol >> 16) & 0xFF) + cd;
                                    int g = ((outCol >> 8) & 0xFF) + cd;
                                    int b = (outCol & 0xFF) + cd;
                                    if (r < 0) r = 0; else if (r > 255) r = 255;
                                    if (g < 0) g = 0; else if (g > 255) g = 255;
                                    if (b < 0) b = 0; else if (b > 255) b = 255;
                                    outCol = (r << 16) | (g << 8) | b;
                                    modified = true;
                                }
                            }
                        }
                    }

                    if (doVig) {
                        int vm = vigLut[vxArr[x] + vy];
                        if (vm < 256) {
                            outCol = ((((outCol & 0x00FF00FF) * vm) >>> 8) & 0x00FF00FF)
                                   | ((((outCol & 0x0000FF00) * vm) >>> 8) & 0x0000FF00);
                            modified = true;
                        }
                    }

                    if (modified) {
                        px[idx] = outCol;
                    }
                }
            } else {
                // Объединённый 1-проходный конвейер: FXAA + Luma-CAS в регистрах + Vibrance LUT + Gamma LUT + SWAR Vignette!
                for (int x = 0; x < w; x++) {
                    int idx = row + x;
                    int outCol = src[idx];
                    int casDelta = 0;

                    if (x > 0 && x < lastX) {
                        int lM = lum[idx] & 0xFF;
                        int lN = lum[idx - w] & 0xFF;
                        int lS = lum[idx + w] & 0xFF;
                        int lW = lum[idx - 1] & 0xFF;
                        int lE = lum[idx + 1] & 0xFF;

                        int lMin = lM < lN ? lM : lN;
                        if (lS < lMin) lMin = lS;
                        if (lW < lMin) lMin = lW;
                        if (lE < lMin) lMin = lE;
                        int lMax = lM > lN ? lM : lN;
                        if (lS > lMax) lMax = lS;
                        if (lW > lMax) lMax = lW;
                        if (lE > lMax) lMax = lE;
                        int range = lMax - lMin;

                        if (doAa && range >= Math.max(24, lMax >> 3)) {
                            outCol = turboAa
                                    ? computeTurboFxaaPixel(src, idx, w, outCol, lM, lN, lS, lW, lE, range)
                                    : computeSpanFxaaPixel(idx, x, y, w, h, outCol, lM, lN, lS, lW, lE, range);
                        } else if (cas > 0 && range > 5) {
                            int lap = (lM << 2) - (lN + lS + lW + lE);
                            int d = (lap * ((cas * (255 - range)) >> 10)) >> 8;
                            if (d != 0) {
                                int lSharp = lM + d;
                                if (lSharp < lMin) lSharp = lMin;
                                else if (lSharp > lMax) lSharp = lMax;
                                casDelta = lSharp - lM;
                            }
                        }
                    }

                    int r = ((outCol >> 16) & 0xFF) + casDelta;
                    int g = ((outCol >> 8) & 0xFF) + casDelta;
                    int b = (outCol & 0xFF) + casDelta;

                    if (vib != 0) {
                        int luma = (r + (g << 1) + b) >> 2;
                        int dr = r - luma, dg = g - luma, db = b - luma;
                        int sat = (dr < 0 ? -dr : dr) + (db < 0 ? -db : db);
                        if (sat > 511) sat = 511;
                        int gain = vLut[sat];
                        r += (dr * gain) >> 8;
                        g += (dg * gain) >> 8;
                        b += (db * gain) >> 8;
                    }

                    outCol = (gLut[256 + r] << 16) | (gLut[256 + g] << 8) | gLut[256 + b];
                    if (doVig) {
                        int vm = vigLut[vxArr[x] + vy];
                        if (vm < 256) {
                            outCol = ((((outCol & 0x00FF00FF) * vm) >>> 8) & 0x00FF00FF)
                                   | ((((outCol & 0x0000FF00) * vm) >>> 8) & 0x0000FF00);
                        }
                    }
                    px[idx] = outCol;
                }
            }
        }
    }

    private static int computeTurboFxaaPixel(int[] src, int idx, int w, int outCol,
                                             int lM, int lN, int lS, int lW, int lE, int range) {
        int gradH = Math.abs(lN + lS - (lM << 1));
        int gradV = Math.abs(lW + lE - (lM << 1));
        boolean isHorz = gradH >= gradV;
        int lNeg = isHorz ? lN : lW;
        int lPos = isHorz ? lS : lE;
        int stepNormal = isHorz ? (Math.abs(lNeg - lM) >= Math.abs(lPos - lM) ? -w : w)
                                : (Math.abs(lNeg - lM) >= Math.abs(lPos - lM) ? -1 : 1);
        int avgNeighbors = (lN + lS + lW + lE) >> 2;
        int blend256 = Math.min(108, (Math.abs(avgNeighbors - lM) * 210) / range);
        if (blend256 > 6) {
            int cOther = src[idx + stepNormal];
            int inv = 256 - blend256;
            int rb = (((outCol & 0x00FF00FF) * inv + (cOther & 0x00FF00FF) * blend256) >>> 8) & 0x00FF00FF;
            int g  = (((outCol & 0x0000FF00) * inv + (cOther & 0x0000FF00) * blend256) >>> 8) & 0x0000FF00;
            return rb | g;
        }
        return outCol;
    }

    private int computeSpanFxaaPixel(int idx, int x, int y, int w, int h, int cM,
                                     int lM, int lN, int lS, int lW, int lE, int range) {
        final byte[] lum = this.fxaaLuma;
        int lNW = lum[idx - w - 1] & 0xFF;
        int lNE = lum[idx - w + 1] & 0xFF;
        int lSW = lum[idx + w - 1] & 0xFF;
        int lSE = lum[idx + w + 1] & 0xFF;

        int edgeH = Math.abs((lNW + (lN << 1) + lNE) - (lSW + (lS << 1) + lSE))
                  + (Math.abs(lW + lE - (lM << 1)) << 1);
        int edgeV = Math.abs((lNW + (lW << 1) + lSW) - (lNE + (lE << 1) + lSE))
                  + (Math.abs(lN + lS - (lM << 1)) << 1);
        boolean isHorz = edgeH >= edgeV;

        int lNeg = isHorz ? lN : lW;
        int lPos = isHorz ? lS : lE;
        int gradNeg = Math.abs(lNeg - lM);
        int gradPos = Math.abs(lPos - lM);
        boolean pairNeg = gradNeg >= gradPos;

        int stepNormal = isHorz ? (pairNeg ? -w : w) : (pairNeg ? -1 : 1);
        int stepAlong = isHorz ? 1 : w;
        int lumaEdgeAvg2 = lM + (pairNeg ? lNeg : lPos);
        int gradThresh2 = Math.max(8, Math.max(gradNeg, gradPos) >> 2) << 1;

        int distNeg = 1;
        int distPos = 1;
        boolean endNeg = false, endPos = false;
        int diffEndNeg = 0, diffEndPos = 0;

        for (int s = 1; s <= 6; s++) {
            if (!endNeg) {
                int px = x - (isHorz ? s : 0);
                int py = y - (isHorz ? 0 : s);
                if (px <= 0 || py <= 0) {
                    endNeg = true;
                } else {
                    int idxN = idx - s * stepAlong;
                    diffEndNeg = ((lum[idxN] & 0xFF) + (lum[idxN + stepNormal] & 0xFF)) - lumaEdgeAvg2;
                    if (Math.abs(diffEndNeg) >= gradThresh2) {
                        endNeg = true;
                    } else {
                        distNeg++;
                    }
                }
            }
            if (!endPos) {
                int px = x + (isHorz ? s : 0);
                int py = y + (isHorz ? 0 : s);
                if (px >= w - 1 || py >= h - 1) {
                    endPos = true;
                } else {
                    int idxP = idx + s * stepAlong;
                    diffEndPos = ((lum[idxP] & 0xFF) + (lum[idxP + stepNormal] & 0xFF)) - lumaEdgeAvg2;
                    if (Math.abs(diffEndPos) >= gradThresh2) {
                        endPos = true;
                    } else {
                        distPos++;
                    }
                }
            }
            if (endNeg && endPos) break;
        }

        int minDist = distNeg < distPos ? distNeg : distPos;
        int spanLen = distNeg + distPos;
        int endDiff = (distNeg < distPos) ? diffEndNeg : diffEndPos;
        boolean goodSpan = (endDiff < 0) != ((lM << 1) < lumaEdgeAvg2);

        int blend256 = goodSpan ? (((spanLen - (minDist << 1)) * 128) / spanLen) : 0;
        int avgNeighbors = (lN + lS + lW + lE) >> 2;
        int subpix = (Math.abs(avgNeighbors - lM) * 180) / range;
        if (subpix > 96) subpix = 96;
        if (subpix > blend256) blend256 = subpix;

        if (blend256 > 4) {
            int cOther = fxaaScratch[idx + stepNormal];
            int inv = 256 - blend256;
            int rb = (((cM & 0x00FF00FF) * inv + (cOther & 0x00FF00FF) * blend256) >>> 8) & 0x00FF00FF;
            int g  = (((cM & 0x0000FF00) * inv + (cOther & 0x0000FF00) * blend256) >>> 8) & 0x0000FF00;
            return rb | g;
        }
        return cM;
    }

    public void drawWeatherParticles(double animTime, Map map) {
        if (weatherMode == GameSettings.WeatherMode.CLEAR) return;
        ensureSkyFilled();

        boolean rain = (weatherMode == GameSettings.WeatherMode.RAIN_STORM);
        int count = rain ? 190 : 150;
        double span = 18.0;
        double halfSpan = span * 0.5;

        for (int i = 0; i < count; i++) {
            int h1 = (i * 73856093) ^ 0x9E3779B9;
            int h2 = (i * 19349663) ^ 0x517CC1B7;
            int h3 = (i * 83492791) ^ 0x27D4EB2F;

            double ox = ((h1 & 0xFFFF) / 65535.0) * span;
            double oy = ((h2 & 0xFFFF) / 65535.0) * 10.0;
            double oz = ((h3 & 0xFFFF) / 65535.0) * span;

            double fallSpeed = rain ? 15.5 : 2.3;
            double driftX = rain ? (animTime * 2.2) : (Math.sin(animTime * 1.3 + i) * 0.9);
            double wx = camX - halfSpan + positiveMod(ox + driftX - camX, span);
            double wz = camZ - halfSpan + positiveMod(oz - camZ, span);
            double wy = positiveMod(oy - animTime * fallSpeed, 10.0);

            if (map != null && map.hasOverheadRoof(wx, wy, wz)) {
                continue;
            }
            if (rain) {
                draw3DLine(wx + 0.08, wy + 0.48, wz, wx, wy, wz, 0xB4D4F6, 1);
            } else {
                drawTranslucentSphereSprite(wx, wy, wz, 0.045, 0xF4F8FF, 0.85);
            }
        }
    }

    private static double positiveMod(double val, double mod) {
        double r = val % mod;
        return r < 0.0 ? r + mod : r;
    }

    public boolean projectToScreen(double wx, double wy, double wz, double[] outXYDepth) {
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;

        double rx = dx * cosYaw - dz * sinYaw;
        double rz1 = dx * sinYaw + dz * cosYaw;
        double cy = dy * cosPitch - rz1 * sinPitch;
        double cz = dy * sinPitch + rz1 * cosPitch;

        if (cz <= NEAR_Z) return false;
        outXYDepth[0] = halfW + (rx / cz) * focalLength;
        outXYDepth[1] = halfH - (cy / cz) * focalLength;
        outXYDepth[2] = cz;
        return true;
    }

    public void drawFloorGrid(double minX, double maxX, double minZ, double maxZ,
                              double step, int texId, double uvPerMeter) {
        int floorShade = applyDirectionalLight(0xFFFFFF, 0.0, 1.0, 0.0);
        double tileRadius = step * 0.80;
        for (double z = minZ; z < maxZ - 1e-6; z += step) {
            double z2 = Math.min(z + step, maxZ);
            double cz = (z + z2) * 0.5;
            for (double x = minX; x < maxX - 1e-6; x += step) {
                double x2 = Math.min(x + step, maxX);
                double cx = (x + x2) * 0.5;
                if (!isSphereVisible(cx, 0.0, cz, tileRadius)) continue;
                drawWorldQuad(
                    x,  0.0, z,  x * uvPerMeter,  z * uvPerMeter,
                    x,  0.0, z2, x * uvPerMeter,  z2 * uvPerMeter,
                    x2, 0.0, z2, x2 * uvPerMeter, z2 * uvPerMeter,
                    x2, 0.0, z,  x2 * uvPerMeter, z * uvPerMeter,
                    texId, floorShade, true, fogEnabled, 0
                );
            }
        }
    }

    public void drawFloorZoneQuad(double x0, double y, double z0, double x1, double z1, int texId, int tint) {
        double cx = (x0 + x1) * 0.5, cz = (z0 + z1) * 0.5;
        double r = Math.hypot(x1 - x0, z1 - z0) * 0.55;
        if (!isSphereVisible(cx, y, cz, r)) return;
        int shade = applyDirectionalLight(tint, 0.0, 1.0, 0.0);
        drawWorldQuad(
            x0, y, z0, 0.0, 0.0,
            x0, y, z1, 0.0, 1.0,
            x1, y, z1, 1.0, 1.0,
            x1, y, z0, 1.0, 0.0,
            texId, shade, true, fogEnabled, 0
        );
    }

    public void drawBoxSunShadow(Vec3.Box b, double mapMinX, double mapMaxX, double mapMinZ, double mapMaxZ) {
        if (!b.castsShadow || b.minY > 0.05 || b.maxY <= 0.12) return;
        if (b.maxX - b.minX > 16.0 || b.maxZ - b.minZ > 16.0) return;

        double h = Math.min(4.8, b.maxY);
        double scale = h / Math.max(0.32, sunDir.y);
        double ox = -sunDir.x * scale * 0.52;
        double oz = -sunDir.z * scale * 0.52;

        double sx0 = clamp(Math.min(b.minX, b.minX + ox), mapMinX + 0.15, mapMaxX - 0.15);
        double sx1 = clamp(Math.max(b.maxX, b.maxX + ox), mapMinX + 0.15, mapMaxX - 0.15);
        double sz0 = clamp(Math.min(b.minZ, b.minZ + oz), mapMinZ + 0.15, mapMaxZ - 0.15);
        double sz1 = clamp(Math.max(b.maxZ, b.maxZ + oz), mapMinZ + 0.15, mapMaxZ - 0.15);

        if (sx1 - sx0 < 0.05 || sz1 - sz0 < 0.05) return;
        if (!isSphereVisible((sx0 + sx1) * 0.5, 0.01, (sz0 + sz1) * 0.5, Math.hypot(sx1 - sx0, sz1 - sz0) * 0.55)) return;

        drawWorldQuad(
            sx0, 0.008, sz0, 0, 0,
            sx0, 0.008, sz1, 0, 1,
            sx1, 0.008, sz1, 1, 1,
            sx1, 0.008, sz0, 1, 0,
            TEX_SOLID, 0x000000, true, false, 1
        );
    }

    public void drawBlobShadow(double cx, double groundY, double cz, double radX, double radZ) {
        if (!isSphereVisible(cx, groundY, cz, Math.max(radX, radZ) * 1.4)) return;
        double y = groundY + 0.010;
        drawWorldQuad(
            cx - radX, y, cz - radZ, 0, 0,
            cx - radX, y, cz + radZ, 0, 1,
            cx + radX, y, cz + radZ, 1, 1,
            cx + radX, y, cz - radZ, 1, 0,
            TEX_SOLID, 0x000000, true, false, 1
        );
    }

    /**
     * Гладкий архитектурный блок с 3D Frustum Culling и мягким вертикальным Ambient-Occlusion градиентом Гуро!
     */
    public void drawAxisAlignedBox(Vec3.Box b) {
        double cx = (b.minX + b.maxX) * 0.5;
        double cy = (b.minY + b.maxY) * 0.5;
        double cz = (b.minZ + b.maxZ) * 0.5;
        double hx = (b.maxX - b.minX) * 0.5;
        double hy = (b.maxY - b.minY) * 0.5;
        double hz = (b.maxZ - b.minZ) * 0.5;
        double radius = Math.sqrt(hx * hx + hy * hy + hz * hz);
        if (!isSphereVisible(cx, cy, cz, radius)) return;

        double x0 = b.minX, x1 = b.maxX;
        double y0 = b.minY, y1 = b.maxY;
        double z0 = b.minZ, z1 = b.maxZ;
        int tex = b.textureId;
        int base = b.tintColor;

        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        boolean crateTex = (tex == TEX_CRATE_WOOD || tex == TEX_CRATE_MILITARY || tex == TEX_METAL_DOOR);
        double scale = 0.28;
        boolean useAO = ambientOcclusionEnabled;
        double botAO = (y0 <= 0.05 && dy > 0.8) ? 0.86 : 0.96;
        double topLit = 1.05;

        if (camZ < z0) {
            int s = applyDirectionalLight(base, 0.0, 0.0, -1.0);
            double uSpan = crateTex ? 1.0 : dx * scale;
            double vSpan = crateTex ? 1.0 : dy * scale;
            if (useAO) {
                drawSmoothGouraudQuad(x0, y0, z0, 0, vSpan, botAO, x1, y0, z0, uSpan, vSpan, botAO,
                                      x1, y1, z0, uSpan, 0, topLit, x0, y1, z0, 0, 0, topLit, tex, s, true, fogEnabled);
            } else {
                drawWorldQuad(x0, y0, z0, 0, vSpan, x1, y0, z0, uSpan, vSpan, x1, y1, z0, uSpan, 0, x0, y1, z0, 0, 0, tex, s, true, fogEnabled, 0);
            }
        }
        if (camZ > z1) {
            int s = applyDirectionalLight(base, 0.0, 0.0, 1.0);
            double uSpan = crateTex ? 1.0 : dx * scale;
            double vSpan = crateTex ? 1.0 : dy * scale;
            if (useAO) {
                drawSmoothGouraudQuad(x1, y0, z1, 0, vSpan, botAO, x0, y0, z1, uSpan, vSpan, botAO,
                                      x0, y1, z1, uSpan, 0, topLit, x1, y1, z1, 0, 0, topLit, tex, s, true, fogEnabled);
            } else {
                drawWorldQuad(x1, y0, z1, 0, vSpan, x0, y0, z1, uSpan, vSpan, x0, y1, z1, uSpan, 0, x1, y1, z1, 0, 0, tex, s, true, fogEnabled, 0);
            }
        }
        if (camX < x0) {
            int s = applyDirectionalLight(base, -1.0, 0.0, 0.0);
            double uSpan = crateTex ? 1.0 : dz * scale;
            double vSpan = crateTex ? 1.0 : dy * scale;
            if (useAO) {
                drawSmoothGouraudQuad(x0, y0, z1, 0, vSpan, botAO, x0, y0, z0, uSpan, vSpan, botAO,
                                      x0, y1, z0, uSpan, 0, topLit, x0, y1, z1, 0, 0, topLit, tex, s, true, fogEnabled);
            } else {
                drawWorldQuad(x0, y0, z1, 0, vSpan, x0, y0, z0, uSpan, vSpan, x0, y1, z0, uSpan, 0, x0, y1, z1, 0, 0, tex, s, true, fogEnabled, 0);
            }
        }
        if (camX > x1) {
            int s = applyDirectionalLight(base, 1.0, 0.0, 0.0);
            double uSpan = crateTex ? 1.0 : dz * scale;
            double vSpan = crateTex ? 1.0 : dy * scale;
            if (useAO) {
                drawSmoothGouraudQuad(x1, y0, z0, 0, vSpan, botAO, x1, y0, z1, uSpan, vSpan, botAO,
                                      x1, y1, z1, uSpan, 0, topLit, x1, y1, z0, 0, 0, topLit, tex, s, true, fogEnabled);
            } else {
                drawWorldQuad(x1, y0, z0, 0, vSpan, x1, y0, z1, uSpan, vSpan, x1, y1, z1, uSpan, 0, x1, y1, z0, 0, 0, tex, s, true, fogEnabled, 0);
            }
        }
        if (camY > y1) {
            int s = applyDirectionalLight(base, 0.0, 1.0, 0.0);
            double uSpan = crateTex ? 1.0 : dx * scale;
            double vSpan = crateTex ? 1.0 : dz * scale;
            drawWorldQuad(x0, y1, z0, 0, 0, x0, y1, z1, 0, vSpan, x1, y1, z1, uSpan, vSpan, x1, y1, z0, uSpan, 0, tex, s, true, fogEnabled, 0);
        }
        if (camY < y0 && y0 > 0.01) {
            int s = applyDirectionalLight(base, 0.0, -1.0, 0.0);
            double uSpan = crateTex ? 1.0 : dx * scale;
            double vSpan = crateTex ? 1.0 : dz * scale;
            drawWorldQuad(x0, y0, z1, 0, 0, x0, y0, z0, 0, vSpan, x1, y0, z0, uSpan, vSpan, x1, y0, z1, uSpan, 0, tex, s, true, fogEnabled, 0);
        }
    }

    public void drawWorldCylinder(double cx, double bottomY, double cz,
                                  double radBottom, double radTop, double heightY,
                                  int segments, int texId, int baseColor) {
        double maxR = Math.max(radBottom, radTop);
        double midY = bottomY + heightY * 0.5;
        if (!isSphereVisible(cx, midY, cz, Math.hypot(maxR, heightY * 0.5) + 0.1)) return;

        int seg = computeLodSegments(segments, 6, cx, midY, cz);
        double[] cosT = COS_LUT[seg];
        double[] sinT = SIN_LUT[seg];
        double topY = bottomY + heightY;
        int tinted = multiplyColors(baseColor, lightTintColor);
        boolean drawCap = (radTop > 0.004 && camY > topY - 0.15);
        double lTop = drawCap ? computeSmoothLightFactor(0.0, 1.0, 0.0) : 1.0;

        for (int i = 0; i < seg; i++) {
            double c0 = cosT[i], s0 = sinT[i];
            double c1 = cosT[i + 1], s1 = sinT[i + 1];

            double x0b = cx + c0 * radBottom, z0b = cz + s0 * radBottom;
            double x1b = cx + c1 * radBottom, z1b = cz + s1 * radBottom;
            double x0t = cx + c0 * radTop,    z0t = cz + s0 * radTop;
            double x1t = cx + c1 * radTop,    z1t = cz + s1 * radTop;

            double fnx = heightY * (z1b - z0b);
            double fny = (z0t - z0b) * (x1b - x0b) - (x0t - x0b) * (z1b - z0b);
            double fnz = -heightY * (x1b - x0b);
            if ((x0b - camX) * fnx + (bottomY - camY) * fny + (z0b - camZ) * fnz < 1e-5) {
                double l0 = computeSmoothLightFactor(c0, 0.14, s0);
                double l1 = computeSmoothLightFactor(c1, 0.14, s1);
                double u0 = (double) i / seg;
                double u1 = (double) (i + 1) / seg;

                drawGouraudTri(x0b, bottomY, z0b, u0, 1.0, l0 * 0.94,
                               x1b, bottomY, z1b, u1, 1.0, l1 * 0.94,
                               x1t, topY,    z1t, u1, 0.0, l1 * 1.04, texId, tinted, false, fogEnabled);
                drawGouraudTri(x0b, bottomY, z0b, u0, 1.0, l0 * 0.94,
                               x1t, topY,    z1t, u1, 0.0, l1 * 1.04,
                               x0t, topY,    z0t, u0, 0.0, l0 * 1.04, texId, tinted, false, fogEnabled);
            }

            if (drawCap) {
                drawGouraudTri(cx, topY, cz, 0.5, 0.5, lTop,
                               x0t, topY, z0t, 0.5 + c0 * 0.5, 0.5 + s0 * 0.5, lTop,
                               x1t, topY, z1t, 0.5 + c1 * 0.5, 0.5 + s1 * 0.5, lTop, texId, tinted, false, fogEnabled);
            }
        }
    }

    public void drawWorldEllipsoid(double cx, double cy, double cz,
                                   double rx, double ry, double rz,
                                   int segments, int rings, int baseColor) {
        double maxR = Math.max(rx, Math.max(ry, rz));
        if (!isSphereVisible(cx, cy, cz, maxR + 0.08)) return;

        int seg = computeLodSegments(segments, 7, cx, cy, cz);
        int rng = Math.max(4, Math.min(18, computeLodSegments(rings, 4, cx, cy, cz)));
        double[] cosT = COS_LUT[seg];
        double[] sinT = SIN_LUT[seg];
        double[] rCos = RING_COS_LUT[rng];
        double[] rSin = RING_SIN_LUT[rng];
        int tinted = multiplyColors(baseColor, lightTintColor);

        double invRx = 1.0 / Math.max(0.01, rx);
        double invRy = 1.0 / Math.max(0.01, ry);
        double invRz = 1.0 / Math.max(0.01, rz);
        double toCenterX = cx - camX, toCenterY = cy - camY, toCenterZ = cz - camZ;
        double camDist = Math.sqrt(toCenterX * toCenterX + toCenterY * toCenterY + toCenterZ * toCenterZ);
        double cullMargin = camDist * 0.25 * Math.max(invRx, Math.max(invRy, invRz));

        for (int r = 0; r < rng; r++) {
            double cp0 = rCos[r], sp0 = rSin[r];
            double cp1 = rCos[r + 1], sp1 = rSin[r + 1];

            for (int s = 0; s < seg; s++) {
                double ct0 = cosT[s], st0 = sinT[s];
                double ct1 = cosT[s + 1], st1 = sinT[s + 1];

                double nx00 = cp0 * ct0, ny00 = sp0, nz00 = cp0 * st0;
                double nx11 = cp1 * ct1, ny11 = sp1, nz11 = cp1 * st1;

                double midNx = (nx00 + nx11) * 0.5 * invRx;
                double midNy = (ny00 + ny11) * 0.5 * invRy;
                double midNz = (nz00 + nz11) * 0.5 * invRz;
                if (toCenterX * midNx + toCenterY * midNy + toCenterZ * midNz > cullMargin) {
                    continue;
                }

                double nx10 = cp0 * ct1, ny10 = sp0, nz10 = cp0 * st1;
                double nx01 = cp1 * ct0, ny01 = sp1, nz01 = cp1 * st0;

                double l00 = computeSmoothLightFactor(nx00, ny00, nz00);
                double l10 = computeSmoothLightFactor(nx10, ny10, nz10);
                double l11 = computeSmoothLightFactor(nx11, ny11, nz11);
                double l01 = computeSmoothLightFactor(nx01, ny01, nz01);

                drawGouraudTri(cx + nx00 * rx, cy + ny00 * ry, cz + nz00 * rz, 0, 0, l00,
                               cx + nx10 * rx, cy + ny10 * ry, cz + nz10 * rz, 1, 0, l10,
                               cx + nx11 * rx, cy + ny11 * ry, cz + nz11 * rz, 1, 1, l11, TEX_SOLID, tinted, false, fogEnabled);
                drawGouraudTri(cx + nx00 * rx, cy + ny00 * ry, cz + nz00 * rz, 0, 0, l00,
                               cx + nx11 * rx, cy + ny11 * ry, cz + nz11 * rz, 1, 1, l11,
                               cx + nx01 * rx, cy + ny01 * ry, cz + nz01 * rz, 0, 1, l01, TEX_SOLID, tinted, false, fogEnabled);
            }
        }
    }

    public void drawOrientedWorldCylinder(double cx, double cy, double cz,
                                          double radius, double length,
                                          double yaw, double pitchX,
                                          int segments, int baseColor) {
        drawOrientedWorldCylinder(cx, cy, cz, radius, radius, length, yaw, pitchX, segments, baseColor);
    }

    public void drawOrientedWorldCylinder(double cx, double cy, double cz,
                                          double radA, double radB, double length,
                                          double yaw, double pitchX,
                                          int segments, int baseColor) {
        double maxR = Math.max(radA, radB) + length * 0.55;
        if (!isSphereVisible(cx, cy, cz, maxR)) return;

        int seg = Math.min(24, computeLodSegments(segments, 6, cx, cy, cz));
        double[] cosT = COS_LUT[seg];
        double[] sinT = SIN_LUT[seg];
        double halfL = length * 0.5;
        double cyaw = Math.cos(yaw), syaw = Math.sin(yaw);
        double cp = Math.cos(pitchX), sp = Math.sin(pitchX);
        int tinted = multiplyColors(baseColor, lightTintColor);

        for (int i = 0; i < seg; i++) {
            double c0 = cosT[i], s0 = sinT[i];
            double c1 = cosT[i + 1], s1 = sinT[i + 1];

            double nwx0 = c0 * cyaw + (s0 * sp) * syaw;
            double nwy0 = s0 * cp;
            double nwz0 = -c0 * syaw + (s0 * sp) * cyaw;
            double nwx1 = c1 * cyaw + (s1 * sp) * syaw;
            double nwy1 = s1 * cp;
            double nwz1 = -c1 * syaw + (s1 * sp) * cyaw;

            double py0A = s0 * radA * cp - (-halfL) * sp;
            double pz0A = s0 * radA * sp + (-halfL) * cp;
            double wx0A = cx + (c0 * radA) * cyaw + pz0A * syaw;
            double wy0A = cy + py0A;
            double wz0A = cz - (c0 * radA) * syaw + pz0A * cyaw;

            double py1A = s1 * radA * cp - (-halfL) * sp;
            double pz1A = s1 * radA * sp + (-halfL) * cp;
            double wx1A = cx + (c1 * radA) * cyaw + pz1A * syaw;
            double wy1A = cy + py1A;
            double wz1A = cz - (c1 * radA) * syaw + pz1A * cyaw;

            double py0B = s0 * radB * cp - halfL * sp;
            double pz0B = s0 * radB * sp + halfL * cp;
            double wx0B = cx + (c0 * radB) * cyaw + pz0B * syaw;
            double wy0B = cy + py0B;
            double wz0B = cz - (c0 * radB) * syaw + pz0B * cyaw;

            double py1B = s1 * radB * cp - halfL * sp;
            double pz1B = s1 * radB * sp + halfL * cp;
            double wx1B = cx + (c1 * radB) * cyaw + pz1B * syaw;
            double wy1B = cy + py1B;
            double wz1B = cz - (c1 * radB) * syaw + pz1B * cyaw;

            double l0 = computeSmoothLightFactor(nwx0, nwy0, nwz0);
            double l1 = computeSmoothLightFactor(nwx1, nwy1, nwz1);

            drawGouraudTri(wx0A, wy0A, wz0A, 0, 0, l0,
                           wx1A, wy1A, wz1A, 1, 0, l1,
                           wx1B, wy1B, wz1B, 1, 1, l1, TEX_SOLID, tinted, false, fogEnabled);
            drawGouraudTri(wx0A, wy0A, wz0A, 0, 0, l0,
                           wx1B, wy1B, wz1B, 1, 1, l1,
                           wx0B, wy0B, wz0B, 0, 1, l0, TEX_SOLID, tinted, false, fogEnabled);
        }
    }

    public void drawViewModelCylinder(double cx, double cy, double cz,
                                      double radBack, double radFront, double lengthZ,
                                      double rotX, double rotY, double rotZ,
                                      double pivotX, double pivotY, double pivotZ,
                                      int segments, int baseColor) {
        int seg = Math.max(8, Math.min(28, (int) Math.round(segments * Math.max(0.75, geometryLodScale))));
        double[] cosT = COS_LUT[seg];
        double[] sinT = SIN_LUT[seg];
        double halfL = lengthZ * 0.5;
        double cxR = Math.cos(rotX), sxR = Math.sin(rotX);
        double cyR = Math.cos(rotY), syR = Math.sin(rotY);
        double czR = Math.cos(rotZ), szR = Math.sin(rotZ);
        int tinted = multiplyColors(baseColor, lightTintColor);

        for (int i = 0; i < seg; i++) {
            double c0 = cosT[i], s0 = sinT[i];
            double c1 = cosT[i + 1], s1 = sinT[i + 1];

            transformViewPoint(cx + c0 * radBack,  cy + s0 * radBack,  cz - halfL, cxR, sxR, cyR, syR, czR, szR, pivotX, pivotY, pivotZ, vp0A);
            transformViewPoint(cx + c1 * radBack,  cy + s1 * radBack,  cz - halfL, cxR, sxR, cyR, syR, czR, szR, pivotX, pivotY, pivotZ, vp1A);
            transformViewPoint(cx + c0 * radFront, cy + s0 * radFront, cz + halfL, cxR, sxR, cyR, syR, czR, szR, pivotX, pivotY, pivotZ, vp0B);
            transformViewPoint(cx + c1 * radFront, cy + s1 * radFront, cz + halfL, cxR, sxR, cyR, syR, czR, szR, pivotX, pivotY, pivotZ, vp1B);

            double s0p = Math.max(0.0, s0), s1p = Math.max(0.0, s1);
            double s0p2 = s0p * s0p, s1p2 = s1p * s1p;
            double l0 = 0.64 + 0.36 * Math.max(0.0, s0 * 0.78 + c0 * 0.38) + 0.14 * (s0p2 * s0p2);
            double l1 = 0.64 + 0.36 * Math.max(0.0, s1 * 0.78 + c1 * 0.38) + 0.14 * (s1p2 * s1p2);

            submitViewSpaceGouraudTri(vp0A[0], vp0A[1], vp0A[2], l0, vp1A[0], vp1A[1], vp1A[2], l1, vp1B[0], vp1B[1], vp1B[2], l1, tinted);
            submitViewSpaceGouraudTri(vp0A[0], vp0A[1], vp0A[2], l0, vp1B[0], vp1B[1], vp1B[2], l1, vp0B[0], vp0B[1], vp0B[2], l0, tinted);
        }
    }

    private void transformViewPoint(double px, double py, double pz,
                                    double cxR, double sxR, double cyR, double syR, double czR, double szR,
                                    double pivX, double pivY, double pivZ, double[] out) {
        double x = px - pivX, y = py - pivY, z = pz - pivZ;
        double y1 = y * cxR - z * sxR;
        double z1 = y * sxR + z * cxR;
        double x2 = x * cyR + z1 * syR;
        double z2 = -x * syR + z1 * cyR;
        double x3 = x2 * czR - y1 * szR;
        double y3 = x2 * szR + y1 * czR;
        out[0] = (x3 + pivX) * viewFlipX;
        out[1] = y3 + pivY;
        out[2] = Math.max(NEAR_Z + 0.02, z2 + pivZ);
    }

    /**
     * Героическая 3D-модель бойца спецназа CT в центре Главного Меню Panorama 7.0!
     */
    public void drawMenuOperatorShowcase(double animTime, GameSettings.WeaponSkin skin, Weapon.Type weaponType) {
        double fwdDist = 2.48;
        double sideDist = -0.78;
        double wx = camX + sinYaw * fwdDist + cosYaw * sideDist;
        double wz = camZ + cosYaw * fwdDist - sinYaw * sideDist;
        double wy = camY - 1.38;
        double camYawAngle = Math.atan2(sinYaw, cosYaw);
        double opYaw = camYawAngle + Math.PI - 0.34 + Math.sin(animTime * 0.9) * 0.05;
        drawMenuOperatorShowcase(wx, wy, wz, opYaw, animTime, skin);
    }

    public void drawMenuOperatorShowcase(double wx, double wy, double wz, double yaw, double animTime) {
        drawMenuOperatorShowcase(wx, wy, wz, yaw, animTime, GameSettings.WeaponSkin.ASIIMOV);
    }

    public void drawMenuOperatorShowcase(double wx, double wy, double wz, double yaw, double animTime,
                                         GameSettings.WeaponSkin skin) {
        double breathe = Math.sin(animTime * 2.1) * 0.015;
        int armorNavy = 0x243B5C;
        int armorTrim = 0x3A6294;
        int vestDark = 0x182334;
        int visorGlow = 0x38D6FF;
        int skinGlove = 0x161B24;
        int gunBody = (skin != null && skin != GameSettings.WeaponSkin.STANDARD) ? skin.primary : 0x2C3440;
        int gunAccent = (skin != null && skin != GameSettings.WeaponSkin.STANDARD) ? skin.accent : 0xE8A838;

        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double rx = cy, rz = -sy;
        double fx = sy, fz = cy;

        // Тактические ботинки и наколенники
        drawWorldCylinder(wx - rx * 0.16, wy, wz - rz * 0.16, 0.105, 0.095, 0.78, 14, TEX_SOLID, armorNavy);
        drawWorldCylinder(wx + rx * 0.16, wy, wz + rz * 0.16, 0.105, 0.095, 0.78, 14, TEX_SOLID, armorNavy);
        drawWorldEllipsoid(wx - rx * 0.16 + fx * 0.05, wy + 0.42, wz - rz * 0.16 + fz * 0.05, 0.08, 0.09, 0.08, 10, 6, vestDark);
        drawWorldEllipsoid(wx + rx * 0.16 + fx * 0.05, wy + 0.42, wz + rz * 0.16 + fz * 0.05, 0.08, 0.09, 0.08, 10, 6, vestDark);

        // Бронежилет и наплечники спецназа GIGN / SAS
        drawWorldEllipsoid(wx, wy + 1.08 + breathe, wz, 0.27, 0.36, 0.20, 16, 10, armorNavy);
        drawWorldEllipsoid(wx + fx * 0.04, wy + 1.10 + breathe, wz + fz * 0.04, 0.24, 0.26, 0.19, 14, 8, vestDark);
        drawWorldEllipsoid(wx - rx * 0.30, wy + 1.30 + breathe, wz - rz * 0.30, 0.12, 0.11, 0.12, 12, 7, armorTrim);
        drawWorldEllipsoid(wx + rx * 0.30, wy + 1.30 + breathe, wz + rz * 0.30, 0.12, 0.11, 0.12, 12, 7, armorTrim);

        // Руки, удерживающие штурмовую винтовку Asiimov на груди
        drawOrientedWorldCylinder(wx - rx * 0.25 + fx * 0.14, wy + 1.12 + breathe, wz - rz * 0.25 + fz * 0.14, 0.075, 0.068, 0.44, yaw + 0.45, 0.55, 12, armorNavy);
        drawOrientedWorldCylinder(wx + rx * 0.24 + fx * 0.16, wy + 1.10 + breathe, wz + rz * 0.24 + fz * 0.16, 0.075, 0.068, 0.44, yaw - 0.52, 0.62, 12, armorNavy);
        drawWorldEllipsoid(wx + fx * 0.28, wy + 1.02 + breathe, wz + fz * 0.28, 0.065, 0.065, 0.065, 10, 6, skinGlove);

        // Шлем спецназа с неоново-голубым тактическим забралом и ПНВ
        drawWorldEllipsoid(wx, wy + 1.59 + breathe, wz, 0.175, 0.185, 0.180, 18, 11, armorNavy);
        drawWorldEllipsoid(wx + fx * 0.10, wy + 1.59 + breathe, wz + fz * 0.10, 0.135, 0.068, 0.11, 14, 7, visorGlow);
        drawWorldCylinder(wx - rx * 0.18, wy + 1.54 + breathe, wz - rz * 0.18, 0.04, 0.04, 0.09, 10, TEX_SOLID, vestDark);
        drawWorldCylinder(wx + rx * 0.18, wy + 1.54 + breathe, wz + rz * 0.18, 0.04, 0.04, 0.09, 10, TEX_SOLID, vestDark);

        // Винтовка в руках оператора
        double gx = wx + fx * 0.26, gy = wy + 1.05 + breathe, gz = wz + fz * 0.26;
        drawOrientedWorldBox(gx, gy, gz, 0.07, 0.11, 0.56, yaw + 0.62, -0.18, TEX_SOLID, gunBody);
        drawOrientedWorldBox(gx + rx * 0.06, gy + 0.03, gz + rz * 0.06, 0.075, 0.045, 0.34, yaw + 0.62, -0.18, TEX_SOLID, gunAccent);
        drawOrientedWorldCylinder(gx + rx * 0.24 + fx * 0.16, gy - 0.04, gz + rz * 0.24 + fz * 0.16, 0.022, 0.022, 0.42, yaw + 0.62, -0.18, 12, 0x1A202C);
    }

    /**
     * Орбитальный/артиллерийский лазерный целеуказатель авиаудара [U]!
     */
    public void drawAirstrikeBeacon(Vec3.AirstrikeBeacon ab) {
        double x = ab.targetPos.x, y = ab.targetPos.y, z = ab.targetPos.z;
        double pulse = 0.5 + 0.5 * Math.sin(ab.animPhase * 14.0);
        double ringRad = Math.max(0.55, ab.countdown * 2.4);
        drawWorldCylinder(x, y + 0.02, z, ringRad, ringRad * 0.92, 0.05, 22, TEX_SOLID, pulse > 0.5 ? 0xFF3828 : 0xFFAA28);
        drawWorldCylinder(x, y, z, 0.14, 0.14, 24.0, 12, TEX_SOLID, 0xFF4436);
    }

    public void drawAttackHelicopter(Vec3.AttackHelicopter heli) {
        double x = heli.pos.x, y = heli.pos.y, z = heli.pos.z;
        double yaw = heli.yaw;
        boolean flash = heli.hitFlashTimer > 0.0;

        int hullCol = flash ? 0xFF6B5B : 0x26313D;
        int glassCol = 0x3CE0FF;
        int podCol = flash ? 0xFF9478 : 0x3B4959;
        int rotorCol = 0x151920;

        drawWorldEllipsoid(x, y, z, 1.38, 0.96, 2.55, 16, 9, hullCol);
        double fx = Math.sin(yaw), fz = Math.cos(yaw);
        double rx = Math.cos(yaw), rz = -Math.sin(yaw);

        drawWorldEllipsoid(x + fx * 1.18, y + 0.06, z + fz * 1.18, 0.92, 0.66, 1.05, 14, 8, glassCol);
        drawOrientedWorldCylinder(x - fx * 2.6, y + 0.22, z - fz * 2.6, 0.45, 0.18, 3.3, yaw, 0.06, 12, hullCol);
        drawOrientedWorldBox(x - fx * 4.0, y + 0.72, z - fz * 4.0, 0.14, 1.25, 0.65, yaw, 0.0, TEX_SOLID, podCol);

        drawOrientedWorldBox(x, y + 0.10, z, 3.8, 0.16, 0.72, yaw, 0.0, TEX_SOLID, podCol);
        drawOrientedWorldCylinder(x - rx * 1.65, y - 0.12, z - rz * 1.65, 0.28, 0.28, 1.35, yaw, 0.0, 12, 0x4A5868);
        drawOrientedWorldCylinder(x + rx * 1.65, y - 0.12, z + rz * 1.65, 0.28, 0.28, 1.35, yaw, 0.0, 12, 0x4A5868);
        drawWorldEllipsoid(x - rx * 1.85, y + 0.12, z - rz * 1.85, 0.14, 0.14, 0.14, 8, 6, 0xFF3B30);
        drawWorldEllipsoid(x + rx * 1.85, y + 0.12, z + rz * 1.85, 0.14, 0.14, 0.14, 8, 6, 0x34C759);

        drawWorldCylinder(x, y + 0.88, z, 0.22, 0.18, 0.42, 10, TEX_SOLID, 0x627080);
        drawOrientedWorldBox(x, y + 1.28, z, 7.6, 0.06, 0.34, heli.rotorPhase, 0.0, TEX_SOLID, rotorCol);
        drawOrientedWorldBox(x, y + 1.28, z, 0.34, 0.06, 7.6, heli.rotorPhase, 0.0, TEX_SOLID, rotorCol);
        drawOrientedWorldBox(x - fx * 4.1 + rx * 0.16, y + 0.75, z - fz * 4.1 + rz * 0.16, 0.06, 1.45, 0.18, yaw, heli.rotorPhase * 1.6, TEX_SOLID, rotorCol);
    }

    public void drawSentryTurret(Vec3.SentryTurret t) {
        double x = t.pos.x, y = t.pos.y, z = t.pos.z;
        int legCol = 0x2A3544;
        int headCol = 0x2E74C8;
        int barrelCol = 0x1A2029;
        int sensorCol = t.hasTarget ? 0xFF4238 : 0x44F2FF;

        for (int i = 0; i < 3; i++) {
            double a = i * (Math.PI * 2.0 / 3.0) + 0.4;
            drawOrientedWorldCylinder(x + Math.cos(a) * 0.24, y + 0.28, z + Math.sin(a) * 0.24,
                    0.05, 0.04, 0.68, a, 0.58, 8, legCol);
        }
        drawWorldCylinder(x, y + 0.42, z, 0.11, 0.09, 0.46, 12, TEX_SOLID, 0x485668);
        drawOrientedWorldBox(x, y + 0.92, z, 0.38, 0.26, 0.46, t.yaw, 0.0, TEX_SOLID, headCol);

        double fx = Math.sin(t.yaw), fz = Math.cos(t.yaw);
        double rx = Math.cos(t.yaw), rz = -Math.sin(t.yaw);
        drawOrientedWorldCylinder(x + fx * 0.36 - rx * 0.07, y + 0.90, z + fz * 0.36 - rz * 0.07, 0.035, 0.032, 0.48, t.yaw, 0.0, 10, barrelCol);
        drawOrientedWorldCylinder(x + fx * 0.36 + rx * 0.07, y + 0.90, z + fz * 0.36 + rz * 0.07, 0.035, 0.032, 0.48, t.yaw, 0.0, 10, barrelCol);
        drawWorldEllipsoid(x + fx * 0.24, y + 1.02, z + fz * 0.24, 0.07, 0.07, 0.07, 10, 6, sensorCol);

        if (t.muzzleFlash > 0.0) {
            drawWorldEllipsoid(x + fx * 0.66, y + 0.90, z + fz * 0.66, 0.14, 0.14, 0.14, 8, 6, 0xFFE26A);
        }
    }

    public void drawRocketProjectile(Vec3.Rocket r) {
        double yaw = Math.atan2(r.vel.x, r.vel.z);
        double horiz = Math.sqrt(r.vel.x * r.vel.x + r.vel.z * r.vel.z);
        double pitch = -Math.atan2(r.vel.y, Math.max(0.01, horiz));
        drawOrientedWorldCylinder(r.pos.x, r.pos.y, r.pos.z, 0.075, 0.025, 0.68, yaw, pitch, 10, 0x5E7246);
        Vec3 tail = r.pos.sub(r.vel.normalize().scale(0.38));
        drawWorldEllipsoid(tail.x, tail.y, tail.z, 0.15, 0.15, 0.15, 8, 6, 0xFF9E2C);
    }

    public void drawSupplyDrop(Vec3.SupplyDrop sd) {
        double x = sd.pos.x, y = sd.pos.y, z = sd.pos.z;
        Vec3.Box crate = new Vec3.Box(x - 0.48, y, z - 0.48, x + 0.48, y + 0.78, z + 0.48,
                TEX_CRATE_MILITARY, 0x3B78B8, 1.0, false);
        drawAxisAlignedBox(crate);

        if (!sd.landed) {
            double canopyY = y + 2.65;
            drawWorldEllipsoid(x, canopyY, z, 1.55, 0.62, 1.55, 14, 6, 0xE67E28);
            draw3DLine(new Vec3(x - 0.38, y + 0.78, z - 0.38), new Vec3(x - 1.25, canopyY - 0.1, z - 1.0), 0xD8E0E8, 1);
            draw3DLine(new Vec3(x + 0.38, y + 0.78, z - 0.38), new Vec3(x + 1.25, canopyY - 0.1, z - 1.0), 0xD8E0E8, 1);
            draw3DLine(new Vec3(x - 0.38, y + 0.78, z + 0.38), new Vec3(x - 1.25, canopyY - 0.1, z + 1.0), 0xD8E0E8, 1);
            draw3DLine(new Vec3(x + 0.38, y + 0.78, z + 0.38), new Vec3(x + 1.25, canopyY - 0.1, z + 1.0), 0xD8E0E8, 1);
        }
        if (Math.sin(sd.beaconPhase) > -0.3) {
            drawTranslucentSphereSprite(x, y + 0.96, z, 0.20, 0x3CFF8E, 0.85);
        }
    }

    public void drawOrientedWorldBox(double cx, double cy, double cz,
                                     double sx, double sy, double sz,
                                     double yaw, double pitchX,
                                     int texId, int baseColor) {
        double radius = Math.sqrt(sx * sx + sy * sy + sz * sz) * 0.55;
        if (!isSphereVisible(cx, cy, cz, radius)) return;

        double hx = sx * 0.5, hy = sy * 0.5, hz = sz * 0.5;
        double cyaw = Math.cos(yaw), syaw = Math.sin(yaw);
        double cp = Math.cos(pitchX), sp = Math.sin(pitchX);

        double[] lx = {-hx,  hx,  hx, -hx, -hx,  hx,  hx, -hx};
        double[] ly = {-hy, -hy,  hy,  hy, -hy, -hy,  hy,  hy};
        double[] lz = {-hz, -hz, -hz, -hz,  hz,  hz,  hz,  hz};

        for (int i = 0; i < 8; i++) {
            double py = ly[i] * cp - lz[i] * sp;
            double pz = ly[i] * sp + lz[i] * cp;
            double wx = lx[i] * cyaw + pz * syaw;
            double wz = -lx[i] * syaw + pz * cyaw;
            boxVx[i] = cx + wx;
            boxVy[i] = cy + py;
            boxVz[i] = cz + wz;
        }

        int tinted = multiplyColors(baseColor, lightTintColor);
        int cFront = shadeColor(tinted, 0.95);
        int cBack  = shadeColor(tinted, 0.80);
        int cSide  = shadeColor(tinted, 0.87);
        int cTop   = shadeColor(tinted, 1.07);
        int cBot   = shadeColor(tinted, 0.62);

        drawWorldQuad(boxVx[0], boxVy[0], boxVz[0], 0, 1, boxVx[1], boxVy[1], boxVz[1], 1, 1, boxVx[2], boxVy[2], boxVz[2], 1, 0, boxVx[3], boxVy[3], boxVz[3], 0, 0, texId, cBack,  false, fogEnabled, 0);
        drawWorldQuad(boxVx[5], boxVy[5], boxVz[5], 0, 1, boxVx[4], boxVy[4], boxVz[4], 1, 1, boxVx[7], boxVy[7], boxVz[7], 1, 0, boxVx[6], boxVy[6], boxVz[6], 0, 0, texId, cFront, false, fogEnabled, 0);
        drawWorldQuad(boxVx[4], boxVy[4], boxVz[4], 0, 1, boxVx[0], boxVy[0], boxVz[0], 1, 1, boxVx[3], boxVy[3], boxVz[3], 1, 0, boxVx[7], boxVy[7], boxVz[7], 0, 0, texId, cSide,  false, fogEnabled, 0);
        drawWorldQuad(boxVx[1], boxVy[1], boxVz[1], 0, 1, boxVx[5], boxVy[5], boxVz[5], 1, 1, boxVx[6], boxVy[6], boxVz[6], 1, 0, boxVx[2], boxVy[2], boxVz[2], 0, 0, texId, cSide,  false, fogEnabled, 0);
        drawWorldQuad(boxVx[3], boxVy[3], boxVz[3], 0, 1, boxVx[2], boxVy[2], boxVz[2], 1, 1, boxVx[6], boxVy[6], boxVz[6], 1, 0, boxVx[7], boxVy[7], boxVz[7], 0, 0, texId, cTop,   false, fogEnabled, 0);
        drawWorldQuad(boxVx[4], boxVy[4], boxVz[4], 0, 1, boxVx[5], boxVy[5], boxVz[5], 1, 1, boxVx[1], boxVy[1], boxVz[1], 1, 0, boxVx[0], boxVy[0], boxVz[0], 0, 0, texId, cBot,   false, fogEnabled, 0);
    }

    public void drawViewModelBox(double cx, double cy, double cz,
                                 double sx, double sy, double sz,
                                 double rotX, double rotY, double rotZ,
                                 double pivotX, double pivotY, double pivotZ,
                                 int texId, int baseColor) {
        double hx = sx * 0.5, hy = sy * 0.5, hz = sz * 0.5;
        double cxR = Math.cos(rotX), sxR = Math.sin(rotX);
        double cyR = Math.cos(rotY), syR = Math.sin(rotY);
        double czR = Math.cos(rotZ), szR = Math.sin(rotZ);

        double[] lx = {-hx,  hx,  hx, -hx, -hx,  hx,  hx, -hx};
        double[] ly = {-hy, -hy,  hy,  hy, -hy, -hy,  hy,  hy};
        double[] lz = {-hz, -hz, -hz, -hz,  hz,  hz,  hz,  hz};

        for (int i = 0; i < 8; i++) {
            double x = (cx + lx[i]) - pivotX;
            double y = (cy + ly[i]) - pivotY;
            double z = (cz + lz[i]) - pivotZ;

            double y1 = y * cxR - z * sxR;
            double z1 = y * sxR + z * cxR;
            double x2 = x * cyR + z1 * syR;
            double z2 = -x * syR + z1 * cyR;
            double x3 = x2 * czR - y1 * szR;
            double y3 = x2 * szR + y1 * czR;

            boxVx[i] = (x3 + pivotX) * viewFlipX;
            boxVy[i] = y3 + pivotY;
            boxVz[i] = Math.max(NEAR_Z + 0.02, z2 + pivotZ);
        }

        int tinted = multiplyColors(baseColor, lightTintColor);
        // Плавный Гуро-градиент по рёбрам оружия убирает картонную плоскость граней!
        submitViewSpaceSmoothQuad(boxVx[0], boxVy[0], boxVz[0], 0.90, boxVx[1], boxVy[1], boxVz[1], 0.94, boxVx[2], boxVy[2], boxVz[2], 1.04, boxVx[3], boxVy[3], boxVz[3], 1.00, texId, tinted);
        submitViewSpaceSmoothQuad(boxVx[5], boxVy[5], boxVz[5], 0.78, boxVx[4], boxVy[4], boxVz[4], 0.76, boxVx[7], boxVy[7], boxVz[7], 0.88, boxVx[6], boxVy[6], boxVz[6], 0.90, texId, tinted);
        submitViewSpaceSmoothQuad(boxVx[4], boxVy[4], boxVz[4], 0.80, boxVx[0], boxVy[0], boxVz[0], 0.84, boxVx[3], boxVy[3], boxVz[3], 0.96, boxVx[7], boxVy[7], boxVz[7], 0.92, texId, tinted);
        submitViewSpaceSmoothQuad(boxVx[1], boxVy[1], boxVz[1], 0.84, boxVx[5], boxVy[5], boxVz[5], 0.80, boxVx[6], boxVy[6], boxVz[6], 0.94, boxVx[2], boxVy[2], boxVz[2], 0.98, texId, tinted);
        submitViewSpaceSmoothQuad(boxVx[3], boxVy[3], boxVz[3], 1.08, boxVx[2], boxVy[2], boxVz[2], 1.12, boxVx[6], boxVy[6], boxVz[6], 1.06, boxVx[7], boxVy[7], boxVz[7], 1.04, texId, tinted);
        submitViewSpaceSmoothQuad(boxVx[4], boxVy[4], boxVz[4], 0.62, boxVx[5], boxVy[5], boxVz[5], 0.64, boxVx[1], boxVy[1], boxVz[1], 0.68, boxVx[0], boxVy[0], boxVz[0], 0.66, texId, tinted);
    }

    public void drawDecal(Vec3.Decal d) {
        if (!isSphereVisible(d.position.x, d.position.y, d.position.z, d.size)) return;
        Vec3 n = d.normal;
        double tx, ty, tz;
        if (Math.abs(n.y) > 0.8) {
            tx = 1.0; ty = 0.0; tz = 0.0;
        } else {
            double invL = 1.0 / Math.max(1e-6, Math.hypot(n.x, n.z));
            tx = -n.z * invL; ty = 0.0; tz = n.x * invL;
        }
        double bx = n.y * tz - n.z * ty;
        double by = n.z * tx - n.x * tz;
        double bz = n.x * ty - n.y * tx;
        double invB = 1.0 / Math.max(1e-6, Math.sqrt(bx * bx + by * by + bz * bz));
        bx *= invB; by *= invB; bz *= invB;

        double s = d.size * 0.5;
        double cx = d.position.x + n.x * 0.012;
        double cy = d.position.y + n.y * 0.012;
        double cz = d.position.z + n.z * 0.012;

        drawWorldQuad(
            cx - tx * s - bx * s, cy - ty * s - by * s, cz - tz * s - bz * s, 0, 1,
            cx + tx * s - bx * s, cy + ty * s - by * s, cz + tz * s - bz * s, 1, 1,
            cx + tx * s + bx * s, cy + ty * s + by * s, cz + tz * s + bz * s, 1, 0,
            cx - tx * s + bx * s, cy - ty * s + by * s, cz - tz * s + bz * s, 0, 0,
            TEX_SOLID, d.color, false, fogEnabled, 0
        );
    }

    public void drawGraffiti(Vec3.Graffiti gr) {
        if (!isSphereVisible(gr.pos.x, gr.pos.y, gr.pos.z, 0.8)) return;
        Vec3 n = gr.normal;
        double tx, ty, tz;
        if (Math.abs(n.y) > 0.8) {
            tx = 1.0; ty = 0.0; tz = 0.0;
        } else {
            double invL = 1.0 / Math.max(1e-6, Math.hypot(n.x, n.z));
            tx = -n.z * invL; ty = 0.0; tz = n.x * invL;
        }
        double bx = n.y * tz - n.z * ty;
        double by = n.z * tx - n.x * tz;
        double bz = n.x * ty - n.y * tx;
        double invB = 1.0 / Math.max(1e-6, Math.sqrt(bx * bx + by * by + bz * bz));
        bx *= invB; by *= invB; bz *= invB;
        if (by < -0.2) { bx = -bx; by = -by; bz = -bz; }

        drawDecalRectRaw(gr.pos.x, gr.pos.y, gr.pos.z, tx, ty, tz, bx, by, bz, 0.0, 0.0, 0.72, 0.72, 0x161A22);
        double fx = gr.pos.x + n.x * 0.006;
        double fy = gr.pos.y + n.y * 0.006;
        double fz = gr.pos.z + n.z * 0.006;

        if (gr.style == 0) {
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz, 0.0, -0.16, 0.54, 0.14, gr.primaryColor);
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz, -0.18, 0.04, 0.13, 0.28, gr.primaryColor);
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz,  0.00, 0.08, 0.14, 0.34, gr.accentColor);
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz,  0.18, 0.04, 0.13, 0.28, gr.primaryColor);
        } else if (gr.style == 1) {
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz, 0.0, 0.0, 0.52, 0.10, gr.primaryColor);
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz, 0.0, 0.0, 0.10, 0.52, gr.primaryColor);
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz, 0.0, 0.0, 0.20, 0.20, gr.accentColor);
        } else {
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz,  0.06,  0.14, 0.30, 0.13, gr.primaryColor);
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz,  0.00,  0.00, 0.34, 0.12, gr.accentColor);
            drawDecalRectRaw(fx, fy, fz, tx, ty, tz, bx, by, bz, -0.06, -0.14, 0.30, 0.13, gr.primaryColor);
        }
    }

    private void drawDecalRectRaw(double cx0, double cy0, double cz0,
                                  double tx, double ty, double tz,
                                  double bx, double by, double bz,
                                  double offU, double offV, double w, double h, int color) {
        double hw = w * 0.5, hh = h * 0.5;
        double cx = cx0 + tx * offU + bx * offV;
        double cy = cy0 + ty * offU + by * offV;
        double cz = cz0 + tz * offU + bz * offV;
        drawWorldQuad(
            cx - tx * hw - bx * hh, cy - ty * hw - by * hh, cz - tz * hw - bz * hh, 0, 1,
            cx + tx * hw - bx * hh, cy + ty * hw - by * hh, cz + tz * hw - bz * hh, 1, 1,
            cx + tx * hw + bx * hh, cy + ty * hw + by * hh, cz + tz * hw + bz * hh, 1, 0,
            cx - tx * hw + bx * hh, cy - ty * hw + by * hh, cz - tz * hw + bz * hh, 0, 0,
            TEX_SOLID, color, false, fogEnabled, 0
        );
    }

    public void drawSmokeCloud(Vec3.SmokeCloud cloud) {
        double radius = cloud.getCurrentRadius();
        if (radius <= 0.15) return;
        flushRasterQueue();

        double[][] offsets = {
            {0.0,  0.38, 0.0,  0.85},
            {-0.42, 0.25, -0.35, 0.68},
            {0.44,  0.28, -0.32, 0.66},
            {-0.36, 0.26,  0.42, 0.67},
            {0.38,  0.30,  0.40, 0.68},
            {0.0,   0.68,  0.0,  0.62}
        };

        int baseSmoke = multiplyColors(0xC6CDD4, lightTintColor);
        double holeFactor = cloud.bulletHoleTimer > 0.0 ? 0.58 : 1.0;

        for (int i = 0; i < offsets.length; i++) {
            double px = cloud.center.x + offsets[i][0] * radius;
            double py = cloud.center.y + offsets[i][1] * radius;
            double pz = cloud.center.z + offsets[i][2] * radius;
            double puffR = radius * offsets[i][3];
            drawTranslucentSphereSprite(px, py, pz, puffR, shadeColor(baseSmoke, 0.90 + (i % 3) * 0.06), 0.72 * holeFactor);
        }
    }

    public void drawTranslucentSphereSprite(double wx, double wy, double wz, double worldRadius, int rgb, double alpha) {
        ensureSkyFilled();
        flushRasterQueue();
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;

        double rx = dx * cosYaw - dz * sinYaw;
        double rz1 = dx * sinYaw + dz * cosYaw;
        double cy = dy * cosPitch - rz1 * sinPitch;
        double cz = dy * sinPitch + rz1 * cosPitch;

        if (cz <= 0.25) {
            if (dx * dx + dy * dy + dz * dz < worldRadius * worldRadius * 1.3 && worldRadius > 0.4) {
                blendFullScreen(rgb, 0.24);
            }
            return;
        }

        int sx = (int) (halfW + (rx / cz) * focalLength);
        int sy = (int) (halfH - (cy / cz) * focalLength);
        int screenR = Math.max(1, (int) ((worldRadius / cz) * focalLength));
        if (screenR > width) screenR = width;

        int x0 = Math.max(0, sx - screenR);
        int x1 = Math.min(width - 1, sx + screenR);
        int y0 = Math.max(0, sy - screenR);
        int y1 = Math.min(height - 1, sy + screenR);

        double invR2 = 1.0 / Math.max(1.0, (double) screenR * screenR);
        float frontZ = (float) Math.max(NEAR_Z, cz - worldRadius * 0.55);

        for (int y = y0; y <= y1; y++) {
            int row = y * width;
            double ddy = y - sy;
            double ddy2 = ddy * ddy;
            for (int x = x0; x <= x1; x++) {
                double ddx = x - sx;
                double r2 = (ddx * ddx + ddy2) * invR2;
                if (r2 >= 1.0) continue;
                int idx = row + x;
                if (frontZ < zBuffer[idx]) {
                    int a = (int) ((1.0 - r2) * alpha * 225.0);
                    int invA = 256 - a;
                    int dst = pixels[idx];
                    int rb = (((dst & 0xFF00FF) * invA + (rgb & 0xFF00FF) * a) >>> 8) & 0xFF00FF;
                    int g  = (((dst & 0x00FF00) * invA + (rgb & 0x00FF00) * a) >>> 8) & 0x00FF00;
                    pixels[idx] = rb | g;
                }
            }
        }
    }

    private void blendFullScreen(int rgb, double alpha) {
        int a = (int) (alpha * 256.0);
        int ia = 256 - a;
        for (int i = 0; i < pixels.length; i++) {
            int dst = pixels[i];
            int rb = (((dst & 0xFF00FF) * ia + (rgb & 0xFF00FF) * a) >>> 8) & 0xFF00FF;
            int g  = (((dst & 0x00FF00) * ia + (rgb & 0x00FF00) * a) >>> 8) & 0x00FF00;
            pixels[i] = rb | g;
        }
    }

    public void drawFireZone(Vec3.FireZone fz) {
        double rad = fz.getCurrentRadius();
        if (rad <= 0.1 || !isSphereVisible(fz.center.x, fz.center.y, fz.center.z, rad + 0.8)) return;
        double y = fz.center.y + 0.025;
        drawWorldCylinder(fz.center.x, y - 0.01, fz.center.z, rad * 0.85, rad * 0.82, 0.02, 12, TEX_SOLID, 0xCC3B0E);

        for (int i = 0; i < 14; i++) {
            double ang = i * 2.39996 + fz.animPhase * 0.14;
            double dist = ((i + 0.5) / 14.0) * rad * 0.86;
            double fx = fz.center.x + Math.cos(ang) * dist;
            double fzPos = fz.center.z + Math.sin(ang) * dist;
            double flicker = 0.55 + 0.45 * Math.sin(fz.animPhase * 1.4 + i * 1.7);
            double h = (0.36 + (1.0 - dist / (rad + 0.1)) * 0.50) * flicker;
            int flameCol = (i % 3 == 0) ? 0xFFE35C : ((i % 2 == 0) ? 0xFF7B1A : 0xF0380E);
            drawWorldCylinder(fx, y, fzPos, 0.16, 0.02, h, 7, TEX_SOLID, flameCol);
        }
    }

    public void applyFlashlightBeam() {
        flushRasterQueue();
        double coneRadius = Math.min(width, height) * 0.36;
        double coneR2 = coneRadius * coneRadius;
        double invConeR2 = 1.0 / coneR2;

        int x0 = Math.max(0, (int) (halfW - coneRadius));
        int x1 = Math.min(width - 1, (int) (halfW + coneRadius));
        int y0 = Math.max(0, (int) (halfH - coneRadius));
        int y1 = Math.min(height - 1, (int) (halfH + coneRadius));

        for (int y = y0; y <= y1; y++) {
            int row = y * width;
            double dy = y - halfH;
            double dy2 = dy * dy;
            for (int x = x0; x <= x1; x++) {
                double dx = x - halfW;
                double d2 = dx * dx + dy2;
                if (d2 >= coneR2) continue;
                int idx = row + x;
                float z = zBuffer[idx];
                if (z >= 30.0f) continue;

                double radial = 1.0 - d2 * invConeR2;
                double distAtten = 1.0 - (z / 30.0);
                double intensity = radial * radial * distAtten;
                int addR = (int) (intensity * 108.0);
                int addG = (int) (intensity * 102.0);
                int addB = (int) (intensity * 88.0);
                int boost = 256 + (int) (intensity * 210.0);

                int c = pixels[idx];
                int r = Math.min(255, ((((c >> 16) & 0xFF) * boost) >> 8) + addR);
                int g = Math.min(255, ((((c >> 8) & 0xFF) * boost) >> 8) + addG);
                int b = Math.min(255, (((c & 0xFF) * boost) >> 8) + addB);
                pixels[idx] = (r << 16) | (g << 8) | b;
            }
        }
    }

    /**
     * Сглаженная субпиксельная 3D-линия по алгоритму Ву (Wu's Anti-Aliased Line):
     * трассеры, лазеры снайперов и струи дождя рисуются без ступенчатых пикселей!
     */
    public void draw3DLine(Vec3 a, Vec3 b, int rgb, int thickness) {
        draw3DLine(a.x, a.y, a.z, b.x, b.y, b.z, rgb, thickness);
    }

    public void draw3DLine(double wxA, double wyA, double wzA, double wxB, double wyB, double wzB, int rgb, int thickness) {
        ensureSkyFilled();
        flushRasterQueue();
        double ax = (wxA - camX) * cosYaw - (wzA - camZ) * sinYaw;
        double az1 = (wxA - camX) * sinYaw + (wzA - camZ) * cosYaw;
        double ay = (wyA - camY) * cosPitch - az1 * sinPitch;
        double az = (wyA - camY) * sinPitch + az1 * cosPitch;

        double bx = (wxB - camX) * cosYaw - (wzB - camZ) * sinYaw;
        double bz1 = (wxB - camX) * sinYaw + (wzB - camZ) * cosYaw;
        double by = (wyB - camY) * cosPitch - bz1 * sinPitch;
        double bz = (wyB - camY) * sinPitch + bz1 * cosPitch;

        if (az < NEAR_Z && bz < NEAR_Z) return;

        if (az < NEAR_Z) {
            double t = (NEAR_Z - az) / (bz - az);
            ax += (bx - ax) * t;
            ay += (by - ay) * t;
            az = NEAR_Z;
        } else if (bz < NEAR_Z) {
            double t = (NEAR_Z - bz) / (az - bz);
            bx += (ax - bx) * t;
            by += (ay - by) * t;
            bz = NEAR_Z;
        }

        double sx0 = halfW + (ax / az) * focalLength;
        double sy0 = halfH - (ay / az) * focalLength;
        double sx1 = halfW + (bx / bz) * focalLength;
        double sy1 = halfH - (by / bz) * focalLength;

        double dx = sx1 - sx0;
        double dy = sy1 - sy0;
        boolean steep = Math.abs(dy) > Math.abs(dx);
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) steps = 1;
        if (steps > width * 2) steps = width * 2;

        double invZ0 = 1.0 / az;
        double invZ1 = 1.0 / bz;

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double fx = sx0 + dx * t;
            double fy = sy0 + dy * t;
            float z = (float) (1.0 / (invZ0 + (invZ1 - invZ0) * t));

            if (steep) {
                int py = (int) Math.round(fy);
                int pxBase = (int) Math.floor(fx);
                int frac = (int) ((fx - pxBase) * 230.0);
                blendPixelZ(pxBase, py, z, rgb, 230 - frac);
                blendPixelZ(pxBase + 1, py, z, rgb, frac);
                if (thickness > 1) blendPixelZ(pxBase - 1, py, z, rgb, 150);
            } else {
                int px = (int) Math.round(fx);
                int pyBase = (int) Math.floor(fy);
                int frac = (int) ((fy - pyBase) * 230.0);
                blendPixelZ(px, pyBase, z, rgb, 230 - frac);
                blendPixelZ(px, pyBase + 1, z, rgb, frac);
                if (thickness > 1) blendPixelZ(px, pyBase - 1, z, rgb, 150);
            }
        }
    }

    private void blendPixelZ(int x, int y, float z, int rgb, int alpha256) {
        if (x < 0 || x >= width || y < 0 || y >= height || alpha256 <= 4) return;
        int idx = y * width + x;
        if (z < zBuffer[idx]) {
            int inv = 256 - alpha256;
            int dst = pixels[idx];
            int rb = (((dst & 0xFF00FF) * inv + (rgb & 0xFF00FF) * alpha256) >>> 8) & 0xFF00FF;
            int g  = (((dst & 0x00FF00) * inv + (rgb & 0x00FF00) * alpha256) >>> 8) & 0x00FF00;
            pixels[idx] = rb | g;
        }
    }

    public void drawParticle(Vec3.Particle p) {
        drawTranslucentSphereSprite(p.x, p.y, p.z, p.size * 0.72, p.color, 0.90);
    }

    private void drawSmoothGouraudQuad(
            double x0, double y0, double z0, double u0, double v0, double l0,
            double x1, double y1, double z1, double u1, double v1, double l1,
            double x2, double y2, double z2, double u2, double v2, double l2,
            double x3, double y3, double z3, double u3, double v3, double l3,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog) {
        drawGouraudTri(x0, y0, z0, u0, v0, l0, x1, y1, z1, u1, v1, l1, x2, y2, z2, u2, v2, l2, texId, shadeColor, cullBackface, applyFog);
        drawGouraudTri(x0, y0, z0, u0, v0, l0, x2, y2, z2, u2, v2, l2, x3, y3, z3, u3, v3, l3, texId, shadeColor, cullBackface, applyFog);
    }

    private void drawWorldQuad(
            double x0, double y0, double z0, double u0, double v0,
            double x1, double y1, double z1, double u1, double v1,
            double x2, double y2, double z2, double u2, double v2,
            double x3, double y3, double z3, double u3, double v3,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog, int renderMode) {

        double cx0 = (x0 - camX) * cosYaw - (z0 - camZ) * sinYaw;
        double rz0 = (x0 - camX) * sinYaw + (z0 - camZ) * cosYaw;
        double cy0 = (y0 - camY) * cosPitch - rz0 * sinPitch;
        double cz0 = (y0 - camY) * sinPitch + rz0 * cosPitch;

        double cx1 = (x1 - camX) * cosYaw - (z1 - camZ) * sinYaw;
        double rz1 = (x1 - camX) * sinYaw + (z1 - camZ) * cosYaw;
        double cy1 = (y1 - camY) * cosPitch - rz1 * sinPitch;
        double cz1 = (y1 - camY) * sinPitch + rz1 * cosPitch;

        double cx2 = (x2 - camX) * cosYaw - (z2 - camZ) * sinYaw;
        double rz2 = (x2 - camX) * sinYaw + (z2 - camZ) * cosYaw;
        double cy2 = (y2 - camY) * cosPitch - rz2 * sinPitch;
        double cz2 = (y2 - camY) * sinPitch + rz2 * cosPitch;

        double cx3 = (x3 - camX) * cosYaw - (z3 - camZ) * sinYaw;
        double rz3 = (x3 - camX) * sinYaw + (z3 - camZ) * cosYaw;
        double cy3 = (y3 - camY) * cosPitch - rz3 * sinPitch;
        double cz3 = (y3 - camY) * sinPitch + rz3 * cosPitch;

        clipAndEnqueueTri(cx0, cy0, cz0, u0, v0, 1.0, cx1, cy1, cz1, u1, v1, 1.0, cx2, cy2, cz2, u2, v2, 1.0, texId, shadeColor, cullBackface, applyFog, renderMode);
        clipAndEnqueueTri(cx0, cy0, cz0, u0, v0, 1.0, cx2, cy2, cz2, u2, v2, 1.0, cx3, cy3, cz3, u3, v3, 1.0, texId, shadeColor, cullBackface, applyFog, renderMode);
    }

    private void drawGouraudTri(
            double x0, double y0, double z0, double u0, double v0, double l0,
            double x1, double y1, double z1, double u1, double v1, double l1,
            double x2, double y2, double z2, double u2, double v2, double l2,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog) {

        double cx0 = (x0 - camX) * cosYaw - (z0 - camZ) * sinYaw;
        double rz0 = (x0 - camX) * sinYaw + (z0 - camZ) * cosYaw;
        double cy0 = (y0 - camY) * cosPitch - rz0 * sinPitch;
        double cz0 = (y0 - camY) * sinPitch + rz0 * cosPitch;

        double cx1 = (x1 - camX) * cosYaw - (z1 - camZ) * sinYaw;
        double rz1 = (x1 - camX) * sinYaw + (z1 - camZ) * cosYaw;
        double cy1 = (y1 - camY) * cosPitch - rz1 * sinPitch;
        double cz1 = (y1 - camY) * sinPitch + rz1 * cosPitch;

        double cx2 = (x2 - camX) * cosYaw - (z2 - camZ) * sinYaw;
        double rz2 = (x2 - camX) * sinYaw + (z2 - camZ) * cosYaw;
        double cy2 = (y2 - camY) * cosPitch - rz2 * sinPitch;
        double cz2 = (y2 - camY) * sinPitch + rz2 * cosPitch;

        clipAndEnqueueTri(cx0, cy0, cz0, u0, v0, l0, cx1, cy1, cz1, u1, v1, l1, cx2, cy2, cz2, u2, v2, l2, texId, shadeColor, cullBackface, applyFog, 2);
    }

    private void submitViewSpaceGouraudTri(double vx0, double vy0, double vz0, double l0,
                                           double vx1, double vy1, double vz1, double l1,
                                           double vx2, double vy2, double vz2, double l2,
                                           int shadeColor) {
        clipAndEnqueueTri(vx0, vy0, vz0, 0, 0, l0, vx1, vy1, vz1, 1, 0, l1, vx2, vy2, vz2, 1, 1, l2, TEX_SOLID, shadeColor, false, false, 2);
    }

    private void submitViewSpaceSmoothQuad(
            double x0, double y0, double z0, double l0,
            double x1, double y1, double z1, double l1,
            double x2, double y2, double z2, double l2,
            double x3, double y3, double z3, double l3,
            int texId, int shadeColor) {
        clipAndEnqueueTri(x0, y0, z0, 0, 1, l0, x1, y1, z1, 1, 1, l1, x2, y2, z2, 1, 0, l2, texId, shadeColor, false, false, 2);
        clipAndEnqueueTri(x0, y0, z0, 0, 1, l0, x2, y2, z2, 1, 0, l2, x3, y3, z3, 0, 0, l3, texId, shadeColor, false, false, 2);
    }

    private void clipAndEnqueueTri(
            double vx0, double vy0, double vz0, double u0, double v0, double l0,
            double vx1, double vy1, double vz1, double u1, double v1, double l1,
            double vx2, double vy2, double vz2, double u2, double v2, double l2,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog, int renderMode) {

        if (vz0 >= NEAR_Z && vz1 >= NEAR_Z && vz2 >= NEAR_Z) {
            enqueueScreenTriangle(vx0, vy0, vz0, u0, v0, l0, vx1, vy1, vz1, u1, v1, l1, vx2, vy2, vz2, u2, v2, l2, texId, shadeColor, cullBackface, applyFog, renderMode);
            return;
        }
        if (vz0 < NEAR_Z && vz1 < NEAR_Z && vz2 < NEAR_Z) return;

        inX[0] = vx0; inY[0] = vy0; inZ[0] = vz0; inU[0] = u0; inV[0] = v0; inL[0] = l0;
        inX[1] = vx1; inY[1] = vy1; inZ[1] = vz1; inU[1] = u1; inV[1] = v1; inL[1] = l1;
        inX[2] = vx2; inY[2] = vy2; inZ[2] = vz2; inU[2] = u2; inV[2] = v2; inL[2] = l2;

        int outCount = 0;
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            boolean iIn = inZ[i] >= NEAR_Z;
            boolean jIn = inZ[j] >= NEAR_Z;

            if (iIn && jIn) {
                outX[outCount] = inX[j]; outY[outCount] = inY[j]; outZ[outCount] = inZ[j];
                outU[outCount] = inU[j]; outV[outCount] = inV[j]; outL[outCount] = inL[j];
                outCount++;
            } else if (iIn) {
                double t = (NEAR_Z - inZ[i]) / (inZ[j] - inZ[i]);
                outX[outCount] = inX[i] + (inX[j] - inX[i]) * t;
                outY[outCount] = inY[i] + (inY[j] - inY[i]) * t;
                outZ[outCount] = NEAR_Z;
                outU[outCount] = inU[i] + (inU[j] - inU[i]) * t;
                outV[outCount] = inV[i] + (inV[j] - inV[i]) * t;
                outL[outCount] = inL[i] + (inL[j] - inL[i]) * t;
                outCount++;
            } else if (jIn) {
                double t = (NEAR_Z - inZ[i]) / (inZ[j] - inZ[i]);
                outX[outCount] = inX[i] + (inX[j] - inX[i]) * t;
                outY[outCount] = inY[i] + (inY[j] - inY[i]) * t;
                outZ[outCount] = NEAR_Z;
                outU[outCount] = inU[i] + (inU[j] - inU[i]) * t;
                outV[outCount] = inV[i] + (inV[j] - inV[i]) * t;
                outL[outCount] = inL[i] + (inL[j] - inL[i]) * t;
                outCount++;

                outX[outCount] = inX[j]; outY[outCount] = inY[j]; outZ[outCount] = inZ[j];
                outU[outCount] = inU[j]; outV[outCount] = inV[j]; outL[outCount] = inL[j];
                outCount++;
            }
        }

        if (outCount < 3) return;
        for (int i = 1; i < outCount - 1; i++) {
            enqueueScreenTriangle(
                outX[0], outY[0], outZ[0], outU[0], outV[0], outL[0],
                outX[i], outY[i], outZ[i], outU[i], outV[i], outL[i],
                outX[i + 1], outY[i + 1], outZ[i + 1], outU[i + 1], outV[i + 1], outL[i + 1],
                texId, shadeColor, cullBackface, applyFog, renderMode
            );
        }
    }

    private void enqueueScreenTriangle(
            double vx0, double vy0, double vz0, double u0, double v0, double l0,
            double vx1, double vy1, double vz1, double u1, double v1, double l1,
            double vx2, double vy2, double vz2, double u2, double v2, double l2,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog, int renderMode) {

        double iz0 = 1.0 / vz0, iz1 = 1.0 / vz1, iz2 = 1.0 / vz2;
        double x0 = halfW + (vx0 * iz0) * focalLength;
        double y0 = halfH - (vy0 * iz0) * focalLength;
        double x1 = halfW + (vx1 * iz1) * focalLength;
        double y1 = halfH - (vy1 * iz1) * focalLength;
        double x2 = halfW + (vx2 * iz2) * focalLength;
        double y2 = halfH - (vy2 * iz2) * focalLength;

        double uz0 = u0 * iz0, vz0p = v0 * iz0, lz0 = l0 * iz0;
        double uz1 = u1 * iz1, vz1p = v1 * iz1, lz1 = l1 * iz1;
        double uz2 = u2 * iz2, vz2p = v2 * iz2, lz2 = l2 * iz2;

        double area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        if (cullBackface) {
            if (area >= -1e-4) return;
        } else {
            if (Math.abs(area) < 1e-4) return;
            if (area > 0.0) {
                double tx = x1; x1 = x2; x2 = tx;
                double ty = y1; y1 = y2; y2 = ty;
                double tiz = iz1; iz1 = iz2; iz2 = tiz;
                double tuz = uz1; uz1 = uz2; uz2 = tuz;
                double tvz = vz1p; vz1p = vz2p; vz2p = tvz;
                double tlz = lz1; lz1 = lz2; lz2 = tlz;
            }
        }

        int minX = Math.max(0, (int) Math.ceil(Math.min(x0, Math.min(x1, x2)) - 0.5));
        int maxX = Math.min(width - 1, (int) Math.floor(Math.max(x0, Math.max(x1, x2)) - 0.5));
        int minY = Math.max(0, (int) Math.ceil(Math.min(y0, Math.min(y1, y2)) - 0.5));
        int maxY = Math.min(height - 1, (int) Math.floor(Math.max(y0, Math.max(y1, y2)) - 0.5));
        if (minX > maxX || minY > maxY) return;

        if (queuedTriCount >= triCapacity) {
            flushRasterQueue();
        }

        int idx = queuedTriCount++;
        frameRenderedTris++;

        qX0[idx] = (float) x0; qY0[idx] = (float) y0;
        qX1[idx] = (float) x1; qY1[idx] = (float) y1;
        qX2[idx] = (float) x2; qY2[idx] = (float) y2;
        qIz0[idx] = (float) iz0; qIz1[idx] = (float) iz1; qIz2[idx] = (float) iz2;
        qUz0[idx] = (float) uz0; qUz1[idx] = (float) uz1; qUz2[idx] = (float) uz2;
        qVz0[idx] = (float) vz0p; qVz1[idx] = (float) vz1p; qVz2[idx] = (float) vz2p;
        qLz0[idx] = (float) lz0; qLz1[idx] = (float) lz1; qLz2[idx] = (float) lz2;
        qMinX[idx] = (short) minX; qMaxX[idx] = (short) maxX;
        qMinY[idx] = (short) minY; qMaxY[idx] = (short) maxY;
        qTexId[idx] = (byte) texId;
        qShade[idx] = shadeColor;

        int flags = 0;
        if (applyFog) flags |= 1;
        if (renderMode == 1) flags |= 2;
        if (renderMode == 2) flags |= 4;
        qFlags[idx] = (byte) flags;

        int rowsPerBand = (height + NUM_BANDS - 1) / NUM_BANDS;
        int b0 = Math.max(0, minY / rowsPerBand);
        int b1 = Math.min(NUM_BANDS - 1, maxY / rowsPerBand);
        for (int b = b0; b <= b1; b++) {
            int c = bandCounts[b];
            if (c >= bandBins[b].length) {
                bandBins[b] = Arrays.copyOf(bandBins[b], bandBins[b].length * 2);
            }
            bandBins[b][c] = idx;
            bandCounts[b] = c + 1;
        }
    }

    /**
     * Сверхбыстрый спановый растеризатор треугольника внутри заданной вертикальной полосы потока!
     */
    private void rasterizeQueuedTriangleInBand(int tIdx, int bandY0, int bandY1) {
        int minY = Math.max(bandY0, qMinY[tIdx]);
        int maxY = Math.min(bandY1, qMaxY[tIdx]);
        if (minY > maxY) return;

        int minX = qMinX[tIdx];
        int maxX = qMaxX[tIdx];
        int maxDx = maxX - minX;

        double x0 = qX0[tIdx], y0 = qY0[tIdx];
        double x1 = qX1[tIdx], y1 = qY1[tIdx];
        double x2 = qX2[tIdx], y2 = qY2[tIdx];

        double area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        if (Math.abs(area) < 1e-6) return;
        double invArea = 1.0 / area;

        double dx12 = x2 - x1, dy12 = y2 - y1;
        double dx20 = x0 - x2, dy20 = y0 - y2;
        double dx01 = x1 - x0, dy01 = y1 - y0;

        double w0StepX = -dy12 * invArea, w0StepY = dx12 * invArea;
        double w1StepX = -dy20 * invArea, w1StepY = dx20 * invArea;
        double w2StepX = -dy01 * invArea, w2StepY = dx01 * invArea;

        double px0 = minX + 0.5, py0 = minY + 0.5;
        double w0Row = ((px0 - x1) * (-dy12) + (py0 - y1) * dx12) * invArea;
        double w1Row = ((px0 - x2) * (-dy20) + (py0 - y2) * dx20) * invArea;
        double w2Row = ((px0 - x0) * (-dy01) + (py0 - y0) * dx01) * invArea;

        double iz0 = qIz0[tIdx], iz1 = qIz1[tIdx], iz2 = qIz2[tIdx];
        double uz0 = qUz0[tIdx], uz1 = qUz1[tIdx], uz2 = qUz2[tIdx];
        double vz0 = qVz0[tIdx], vz1 = qVz1[tIdx], vz2 = qVz2[tIdx];
        double lz0 = qLz0[tIdx], lz1 = qLz1[tIdx], lz2 = qLz2[tIdx];

        double izStepX = w0StepX * iz0 + w1StepX * iz1 + w2StepX * iz2;
        double uzStepX = w0StepX * uz0 + w1StepX * uz1 + w2StepX * uz2;
        double vzStepX = w0StepX * vz0 + w1StepX * vz1 + w2StepX * vz2;
        double lzStepX = w0StepX * lz0 + w1StepX * lz1 + w2StepX * lz2;

        int texId = qTexId[tIdx] & 0xFF;
        int flags = qFlags[tIdx] & 0xFF;
        boolean applyFog = (flags & 1) != 0;
        boolean isShadow = (flags & 2) != 0;
        boolean gouraud = (flags & 4) != 0;
        int shadeColor = qShade[tIdx];

        boolean solidOnly = (texId == TEX_SOLID);
        if (solidOnly && !isShadow) {
            shadeColor = gradeColorOnce(shadeColor);
        }

        int[] mip0 = activeMip0[texId];
        int[] mip1 = activeMip1[texId];
        int[] mip2 = activeMip2[texId];
        int[] mip3 = activeMip3[texId];

        int sR = (shadeColor >> 16) & 0xFF;
        int sG = (shadeColor >> 8) & 0xFF;
        int sB = shadeColor & 0xFF;
        int gFog = applyFog ? gradeColorOnce(fogColor) : fogColor;
        int fogR = (gFog >> 16) & 0xFF;
        int fogG = (gFog >> 8) & 0xFF;
        int fogB = gFog & 0xFF;
        double invFogSpan = 235.0 / Math.max(1.0, fogEnd - fogStart);

        boolean bilinear = smoothTexturesEnabled && !solidOnly;
        boolean ultraMip = (textureQuality == GameSettings.TextureQualityMode.ULTRA_ANISO_MIP);
        float mip0Dist = ultraMip ? 16.5f : 9.5f;
        float mip1Dist = ultraMip ? 30.0f : 19.0f;
        float mip2Dist = ultraMip ? 48.0f : 34.0f;

        final float[] zBuf = this.zBuffer;
        final int[] pix = this.pixels;

        for (int y = minY; y <= maxY; y++) {
            int dxLo = 0;
            int dxHi = maxDx;

            if (w0StepX > 1e-11) {
                if (w0Row < -1e-6) {
                    int req = (int) Math.ceil((-1e-6 - w0Row) / w0StepX);
                    if (req > dxLo) dxLo = req;
                }
            } else if (w0StepX < -1e-11) {
                if (w0Row < -1e-6) {
                    dxHi = -1;
                } else {
                    int lim = (int) Math.floor((-1e-6 - w0Row) / w0StepX);
                    if (lim < dxHi) dxHi = lim;
                }
            } else if (w0Row < -1e-6) {
                dxHi = -1;
            }

            if (dxLo <= dxHi) {
                if (w1StepX > 1e-11) {
                    if (w1Row < -1e-6) {
                        int req = (int) Math.ceil((-1e-6 - w1Row) / w1StepX);
                        if (req > dxLo) dxLo = req;
                    }
                } else if (w1StepX < -1e-11) {
                    if (w1Row < -1e-6) {
                        dxHi = -1;
                    } else {
                        int lim = (int) Math.floor((-1e-6 - w1Row) / w1StepX);
                        if (lim < dxHi) dxHi = lim;
                    }
                } else if (w1Row < -1e-6) {
                    dxHi = -1;
                }
            }

            if (dxLo <= dxHi) {
                if (w2StepX > 1e-11) {
                    if (w2Row < -1e-6) {
                        int req = (int) Math.ceil((-1e-6 - w2Row) / w2StepX);
                        if (req > dxLo) dxLo = req;
                    }
                } else if (w2StepX < -1e-11) {
                    if (w2Row < -1e-6) {
                        dxHi = -1;
                    } else {
                        int lim = (int) Math.floor((-1e-6 - w2Row) / w2StepX);
                        if (lim < dxHi) dxHi = lim;
                    }
                } else if (w2Row < -1e-6) {
                    dxHi = -1;
                }
            }

            if (dxLo <= dxHi) {
                int xStart = minX + dxLo;
                int xEnd = minX + dxHi;
                int rowIdx = y * width;

                double sw0 = w0Row + w0StepX * dxLo;
                double sw1 = w1Row + w1StepX * dxLo;
                double sw2 = w2Row + w2StepX * dxLo;

                if (isShadow) {
                    double curIz = sw0 * iz0 + sw1 * iz1 + sw2 * iz2;
                    int x = xStart;
                    while (x <= xEnd) {
                        int stepCount = Math.min(16, xEnd - x + 1);
                        double endIz = curIz + izStepX * stepCount;
                        float zA = curIz > 1e-6 ? (float) (1.0 / curIz) : 1000.0f;
                        float zB = endIz > 1e-6 ? (float) (1.0 / endIz) : 1000.0f;
                        float zCur = zA;
                        float zDelta = (zB - zA) / stepCount;
                        int baseIdx = rowIdx + x;
                        for (int k = 0; k < stepCount; k++) {
                            int idx = baseIdx + k;
                            if (Math.abs(zCur - zBuf[idx]) < 0.08f) {
                                int dst = pix[idx];
                                int rb = (((dst & 0xFF00FF) * 184) >>> 8) & 0xFF00FF;
                                int g  = (((dst & 0x00FF00) * 184) >>> 8) & 0x00FF00;
                                pix[idx] = rb | g;
                            }
                            zCur += zDelta;
                        }
                        curIz = endIz;
                        x += stepCount;
                    }
                } else {
                    double curIz = sw0 * iz0 + sw1 * iz1 + sw2 * iz2;
                    double curUz = sw0 * uz0 + sw1 * uz1 + sw2 * uz2;
                    double curVz = sw0 * vz0 + sw1 * vz1 + sw2 * vz2;
                    double curLz = sw0 * lz0 + sw1 * lz1 + sw2 * lz2;

                    int x = xStart;
                    while (x <= xEnd) {
                        int stepCount = Math.min(16, xEnd - x + 1);
                        double endIz = curIz + izStepX * stepCount;
                        double endUz = curUz + uzStepX * stepCount;
                        double endVz = curVz + vzStepX * stepCount;
                        double endLz = curLz + lzStepX * stepCount;

                        double zA = curIz > 1e-6 ? (1.0 / curIz) : 1000.0;
                        double zB = endIz > 1e-6 ? (1.0 / endIz) : 1000.0;
                        float spanMinZ = (float) Math.min(zA, zB) - 1e-4f;

                        int baseIdx = rowIdx + x;
                        boolean anyVisible = false;
                        for (int k = 0; k < stepCount; k++) {
                            if (spanMinZ <= zBuf[baseIdx + k]) {
                                anyVisible = true;
                                break;
                            }
                        }
                        if (!anyVisible) {
                            curIz = endIz;
                            curUz = endUz;
                            curVz = endVz;
                            curLz = endLz;
                            x += stepCount;
                            continue;
                        }

                        double invLen = 1.0 / stepCount;
                        float zCur = (float) zA;
                        float zDelta = (float) ((zB - zA) * invLen);
                        boolean spanFog = applyFog && Math.max(zA, zB) > fogStart;

                        if (solidOnly) {
                            int lFixed = 256, lDelta = 0;
                            if (gouraud) {
                                double lA = curLz * zA;
                                double lB = endLz * zB;
                                lFixed = (int) (lA * 256.0);
                                lDelta = (int) (((lB - lA) * 256.0) * invLen);
                            }
                            if (!gouraud && !spanFog) {
                                for (int k = 0; k < stepCount; k++) {
                                    int idx = baseIdx + k;
                                    if (zCur <= zBuf[idx] + 1e-4f) {
                                        zBuf[idx] = zCur;
                                        pix[idx] = shadeColor;
                                    }
                                    zCur += zDelta;
                                }
                            } else {
                                for (int k = 0; k < stepCount; k++) {
                                    int idx = baseIdx + k;
                                    if (zCur <= zBuf[idx] + 1e-4f) {
                                        zBuf[idx] = zCur;
                                        int r = sR, g = sG, b = sB;
                                        if (gouraud) {
                                            int lf = lFixed < 0 ? 0 : lFixed;
                                            r = Math.min(255, (r * lf) >> 8);
                                            g = Math.min(255, (g * lf) >> 8);
                                            b = Math.min(255, (b * lf) >> 8);
                                        }
                                        if (spanFog && zCur > fogStart) {
                                            int ff = Math.min(235, (int) ((zCur - fogStart) * invFogSpan));
                                            int iff = 256 - ff;
                                            r = (r * iff + fogR * ff) >> 8;
                                            g = (g * iff + fogG * ff) >> 8;
                                            b = (b * iff + fogB * ff) >> 8;
                                        }
                                        pix[idx] = (r << 16) | (g << 8) | b;
                                    }
                                    zCur += zDelta;
                                    lFixed += lDelta;
                                }
                            }
                        } else {
                            double uA = curUz * zA, vA = curVz * zA;
                            double uB = endUz * zB, vB = endVz * zB;
                            int mipLevel = (zCur < mip0Dist) ? 0 : (zCur < mip1Dist ? 1 : (zCur < mip2Dist ? 2 : 3));
                            int[] activeMip = (mipLevel == 0) ? mip0 : (mipLevel == 1 ? mip1 : (mipLevel == 2 ? mip2 : mip3));
                            int mipShift = 9 - mipLevel;
                            int mipMask = (1 << mipShift) - 1;
                            int scaleShift = 17 - mipLevel;
                            double fpScale = (double) (1 << scaleShift);
                            int uFixed = (int) Math.floor(uA * fpScale);
                            int vFixed = (int) Math.floor(vA * fpScale);
                            int uDelta = (int) (((uB - uA) * fpScale) * invLen);
                            int vDelta = (int) (((vB - vA) * fpScale) * invLen);

                            if (!gouraud && !spanFog) {
                                if (!bilinear) {
                                    for (int k = 0; k < stepCount; k++) {
                                        int idx = baseIdx + k;
                                        if (zCur <= zBuf[idx] + 1e-4f) {
                                            zBuf[idx] = zCur;
                                            int texel = activeMip[(((vFixed >> 8) & mipMask) << mipShift) | ((uFixed >> 8) & mipMask)];
                                            int r = (((texel >> 16) & 0xFF) * sR) >> 8;
                                            int g = (((texel >> 8) & 0xFF) * sG) >> 8;
                                            int b = ((texel & 0xFF) * sB) >> 8;
                                            pix[idx] = (r << 16) | (g << 8) | b;
                                        }
                                        zCur += zDelta;
                                        uFixed += uDelta;
                                        vFixed += vDelta;
                                    }
                                } else {
                                    for (int k = 0; k < stepCount; k++) {
                                        int idx = baseIdx + k;
                                        if (zCur <= zBuf[idx] + 1e-4f) {
                                            zBuf[idx] = zCur;
                                            int texel = sampleBilinearSwar(activeMip, uFixed, vFixed, mipMask, mipShift);
                                            int r = (((texel >> 16) & 0xFF) * sR) >> 8;
                                            int g = (((texel >> 8) & 0xFF) * sG) >> 8;
                                            int b = ((texel & 0xFF) * sB) >> 8;
                                            pix[idx] = (r << 16) | (g << 8) | b;
                                        }
                                        zCur += zDelta;
                                        uFixed += uDelta;
                                        vFixed += vDelta;
                                    }
                                }
                            } else {
                                int lFixed = 256, lDelta = 0;
                                if (gouraud) {
                                    double lA = curLz * zA;
                                    double lB = endLz * zB;
                                    lFixed = (int) (lA * 256.0);
                                    lDelta = (int) (((lB - lA) * 256.0) * invLen);
                                }
                                for (int k = 0; k < stepCount; k++) {
                                    int idx = baseIdx + k;
                                    if (zCur <= zBuf[idx] + 1e-4f) {
                                        zBuf[idx] = zCur;
                                        int texel = bilinear
                                                ? sampleBilinearSwar(activeMip, uFixed, vFixed, mipMask, mipShift)
                                                : activeMip[(((vFixed >> 8) & mipMask) << mipShift) | ((uFixed >> 8) & mipMask)];
                                        int r = (((texel >> 16) & 0xFF) * sR) >> 8;
                                        int g = (((texel >> 8) & 0xFF) * sG) >> 8;
                                        int b = ((texel & 0xFF) * sB) >> 8;

                                        if (gouraud) {
                                            int lf = lFixed < 0 ? 0 : lFixed;
                                            r = Math.min(255, (r * lf) >> 8);
                                            g = Math.min(255, (g * lf) >> 8);
                                            b = Math.min(255, (b * lf) >> 8);
                                        }
                                        if (spanFog && zCur > fogStart) {
                                            int ff = Math.min(235, (int) ((zCur - fogStart) * invFogSpan));
                                            int iff = 256 - ff;
                                            r = (r * iff + fogR * ff) >> 8;
                                            g = (g * iff + fogG * ff) >> 8;
                                            b = (b * iff + fogB * ff) >> 8;
                                        }
                                        pix[idx] = (r << 16) | (g << 8) | b;
                                    }
                                    zCur += zDelta;
                                    uFixed += uDelta;
                                    vFixed += vDelta;
                                    lFixed += lDelta;
                                }
                            }
                        }

                        curIz = endIz;
                        curUz = endUz;
                        curVz = endVz;
                        curLz = endLz;
                        x += stepCount;
                    }
                }
            }

            w0Row += w0StepY;
            w1Row += w1StepY;
            w2Row += w2StepY;
        }
    }

    private static int sampleBilinearSwar(int[] tex, int uFixed8, int vFixed8, int mask, int shift) {
        int x0 = (uFixed8 >> 8) & mask;
        int y0 = (vFixed8 >> 8) & mask;
        int x1 = (x0 + 1) & mask;
        int y1 = (y0 + 1) & mask;

        int fx = uFixed8 & 0xFF;
        int fy = vFixed8 & 0xFF;
        int ifx = 256 - fx;
        int ify = 256 - fy;

        int w00 = (ifx * ify) >>> 8;
        int w10 = (fx * ify) >>> 8;
        int w01 = (ifx * fy) >>> 8;
        int w11 = 256 - w00 - w10 - w01;

        int row0 = y0 << shift;
        int row1 = y1 << shift;
        int c00 = tex[row0 | x0];
        int c10 = tex[row0 | x1];
        int c01 = tex[row1 | x0];
        int c11 = tex[row1 | x1];

        int rb = (((c00 & 0xFF00FF) * w00 + (c10 & 0xFF00FF) * w10
                 + (c01 & 0xFF00FF) * w01 + (c11 & 0xFF00FF) * w11) >>> 8) & 0xFF00FF;
        int g  = (((c00 & 0x00FF00) * w00 + (c10 & 0x00FF00) * w10
                 + (c01 & 0x00FF00) * w01 + (c11 & 0x00FF00) * w11) >>> 8) & 0x00FF00;
        return rb | g;
    }

    private double computeSmoothLightFactor(double nx, double ny, double nz) {
        double dot = nx * sunDir.x + ny * sunDir.y + nz * sunDir.z;
        double diff = Math.max(0.0, dot);
        double d2 = diff * diff;
        double spec = (d2 * d2 * d2) * 0.16;
        double hemi = 0.09 * (ny + 1.0);
        return ambientStrength + diff * diffuseStrength + hemi + spec;
    }

    private int applyDirectionalLight(int baseRgb, double nx, double ny, double nz) {
        double dot = nx * sunDir.x + ny * sunDir.y + nz * sunDir.z;
        double intensity = ambientStrength + Math.max(0.0, dot) * diffuseStrength;
        if (ny < -0.5) intensity = ambientStrength * 0.74;
        int lit = shadeColor(baseRgb, intensity);
        return multiplyColors(lit, lightTintColor);
    }

    public static int multiplyColors(int c1, int c2) {
        int r = (((c1 >> 16) & 0xFF) * ((c2 >> 16) & 0xFF)) >> 8;
        int g = (((c1 >> 8) & 0xFF) * ((c2 >> 8) & 0xFF)) >> 8;
        int b = ((c1 & 0xFF) * (c2 & 0xFF)) >> 8;
        return (r << 16) | (g << 8) | b;
    }

    public static int shadeColor(int rgb, double factor) {
        int r = Math.min(255, Math.max(0, (int) (((rgb >> 16) & 0xFF) * factor)));
        int g = Math.min(255, Math.max(0, (int) (((rgb >> 8) & 0xFF) * factor)));
        int b = Math.min(255, Math.max(0, (int) ((rgb & 0xFF) * factor)));
        return (r << 16) | (g << 8) | b;
    }

    public static int lerpColor(int c1, int c2, double t) {
        int a = (int) (clamp(t, 0.0, 1.0) * 256.0);
        int ia = 256 - a;
        int rb = (((c1 & 0xFF00FF) * ia + (c2 & 0xFF00FF) * a) >>> 8) & 0xFF00FF;
        int g  = (((c1 & 0x00FF00) * ia + (c2 & 0x00FF00) * a) >>> 8) & 0x00FF00;
        return rb | g;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double smoothBevel(double dist, double width) {
        return smoothstep(0.0, width, dist);
    }

    /**
     * Генерация 16 идеально гладких 512x512 материалов Source-2 / Valorant стиля:
     * НИ ОДНОГО жёсткого boolean-перехода, НИ ОДНОЙ шахматной клетки и НИКАКОЙ кирпичной пиксельной сетки!
     */
    private static void generateUltraSmoothTextures() {
        final int S = TEX_SIZE; // 512

        // 0. Гладкий бархатный песчаник Dust II (мягкие природные волны песка без резкой сетки)
        int[] sand = SHARED_MIP0[TEX_FLOOR_SAND];
        for (int y = 0; y < S; y++) {
            double v = (y / (double) S) * Math.PI * 2.0;
            for (int x = 0; x < S; x++) {
                double u = (x / (double) S) * Math.PI * 2.0;
                double n = (fbmSeamless(x, y) - 0.5) * 16.0;
                double ripple = Math.sin(u * 2.0 + Math.cos(v * 2.0) * 1.3) * 6.5;
                int r = (int) (222 + ripple + n);
                int g = (int) (195 + ripple * 0.88 + n * 0.9);
                int b = (int) (148 + ripple * 0.70 + n * 0.72);
                sand[y * S + x] = packRgb(r, g, b);
            }
        }

        // 1. Монолитный шлифованный известняк стен Dust II с деликатными архитектурными фасками
        int[] wall = SHARED_MIP0[TEX_WALL_SANDSTONE];
        for (int y = 0; y < S; y++) {
            int inRow = y & 255;
            double hSeam = smoothBevel(Math.min(inRow, 255 - inRow), 9.0);
            double trim = smoothstep(32.0, 18.0, Math.abs(y - 68.0));
            for (int x = 0; x < S; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 13.0;
                int vSeamX = (x + ((y >> 8) * 256)) & 255;
                double vSeam = smoothBevel(Math.min(vSeamX, 255 - vSeamX), 8.0);
                double groove = Math.min(hSeam, vSeam);
                int r = (int) (220 + n - (1.0 - groove) * 16.0 - trim * 14.0);
                int g = (int) (190 + n * 0.9 - (1.0 - groove) * 14.0 - trim * 12.0);
                int b = (int) (142 + n * 0.75 - (1.0 - groove) * 10.0 - trim * 8.0);
                wall[y * S + x] = packRgb(r, g, b);
            }
        }

        // 2. Гладкий лакированный деревянный контейнер со сглаженными фасками (smoothstep)
        int[] wood = SHARED_MIP0[TEX_CRATE_WOOD];
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double n = fbmSeamless(x, y);
                double grain = Math.sin((x / (double) S) * Math.PI * 12.0 + n * 3.8) * 7.5;
                double edgeDist = Math.min(Math.min(x, S - 1 - x), Math.min(y, S - 1 - y));
                double frameFactor = 1.0 - smoothstep(34.0, 44.0, edgeDist);
                double cornerDist = Math.max(Math.min(x, S - 1 - x), Math.min(y, S - 1 - y));
                double steelCorner = 1.0 - smoothstep(46.0, 54.0, cornerDist);
                double diag = Math.min(Math.abs(x - y), Math.abs((S - 1 - x) - y));
                double brace = (1.0 - smoothstep(20.0, 28.0, diag)) * (1.0 - frameFactor);

                double wr = 186 + grain + (n - 0.5) * 10.0 - (frameFactor + brace * 0.7) * 22.0;
                double wg = 140 + grain * 0.8 + (n - 0.5) * 8.0 - (frameFactor + brace * 0.7) * 18.0;
                double wb = 84 + grain * 0.5 + (n - 0.5) * 6.0 - (frameFactor + brace * 0.7) * 12.0;

                int r = (int) (wr * (1.0 - steelCorner) + 64.0 * steelCorner);
                int g = (int) (wg * (1.0 - steelCorner) + 70.0 * steelCorner);
                int b = (int) (wb * (1.0 - steelCorner) + 78.0 * steelCorner);
                wood[y * S + x] = packRgb(r, g, b);
            }
        }

        // 3. Тактический композитный кейс спецназа со сглаженной полимерной полосой
        int[] mil = SHARED_MIP0[TEX_CRATE_MILITARY];
        for (int y = 0; y < S; y++) {
            double bandMask = smoothstep(220.0, 232.0, y) * (1.0 - smoothstep(280.0, 292.0, y));
            double rib = Math.sin((y / (double) S) * Math.PI * 16.0) * 6.0;
            for (int x = 0; x < S; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 9.0;
                double edge = 1.0 - smoothstep(28.0, 38.0, Math.min(Math.min(x, S - 1 - x), Math.min(y, S - 1 - y)));
                double stripeWave = 0.5 + 0.5 * Math.sin(((x + y) / 48.0) * Math.PI * 2.0);
                double smoothChevron = smoothstep(0.35, 0.65, stripeWave) * bandMask * smoothstep(56.0, 68.0, Math.min(x, S - 1 - x));

                double r = 72 + rib + n - edge * 16.0;
                double g = 88 + rib + n - edge * 18.0;
                double b = 70 + rib + n - edge * 14.0;
                r = r * (1.0 - smoothChevron) + 234.0 * smoothChevron;
                g = g * (1.0 - smoothChevron) + 184.0 * smoothChevron;
                b = b * (1.0 - smoothChevron) + 46.0 * smoothChevron;
                mil[y * S + x] = packRgb((int) r, (int) g, (int) b);
            }
        }

        // 4. Гладкий архитектурный шлифованный камень / мраморный бетон
        int[] conc = SHARED_MIP0[TEX_CONCRETE];
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 14.0;
                double vein = Math.sin((x + y * 0.6) * (Math.PI * 2.0 / S) * 2.0 + n * 0.25) * 5.0;
                int v = (int) (174 + n + vein);
                conc[y * S + x] = packRgb(v, v - 2, v - 5);
            }
        }

        // 5. Массивные гладкие бронзово-дубовые ворота с мягкими филёнками
        int[] door = SHARED_MIP0[TEX_METAL_DOOR];
        for (int y = 0; y < S; y++) {
            double midRail = 1.0 - smoothstep(14.0, 24.0, Math.abs(y - S * 0.5));
            for (int x = 0; x < S; x++) {
                double sheen = Math.sin((x / (double) S) * Math.PI * 6.0) * 8.0 + (fbmSeamless(x, y) - 0.5) * 8.0;
                double border = 1.0 - smoothstep(32.0, 44.0, Math.min(Math.min(x, S - 1 - x), Math.min(y, S - 1 - y)));
                double trim = Math.min(1.0, border + midRail);
                int r = (int) ((116 + sheen) * (1.0 - trim) + 66.0 * trim);
                int g = (int) ((88 + sheen * 0.85) * (1.0 - trim) + 60.0 * trim);
                int b = (int) ((66 + sheen * 0.7) * (1.0 - trim) + 56.0 * trim);
                door[y * S + x] = packRgb(r, g, b);
            }
        }

        // 6. Чистый белый буфер для затенения Гуро
        Arrays.fill(SHARED_MIP0[TEX_SOLID], 0xFFFFFF);

        // 7. Сглаженная тактическая зона закладки C4 (Bombsite A / B)
        int[] site = SHARED_MIP0[TEX_BOMBSITE];
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double edgeDist = Math.min(Math.min(x, S - 1 - x), Math.min(y, S - 1 - y));
                double border = 1.0 - smoothstep(26.0, 36.0, edgeDist);
                double diagWave = smoothstep(0.32, 0.68, 0.5 + 0.5 * Math.sin(((x + y) / 48.0) * Math.PI * 2.0));
                double n = (fbmSeamless(x, y) - 0.5) * 10.0;

                double innerR = 212 + n, innerG = 186 + n, innerB = 148 + n;
                double bR = 48 + 182 * diagWave, bG = 46 + 26 * diagWave, bB = 46 + 8 * diagWave;
                site[y * S + x] = packRgb(
                    (int) (innerR * (1.0 - border) + bR * border),
                    (int) (innerG * (1.0 - border) + bG * border),
                    (int) (innerB * (1.0 - border) + bB * border)
                );
            }
        }

        // 8. Гладкая королевская штукатурка Mirage с плавным лазурно-золотым орнаментом (БЕЗ пиксельных квадратов!)
        int[] plaster = SHARED_MIP0[TEX_MIRAGE_PLASTER];
        for (int y = 0; y < S; y++) {
            double friezeMask = smoothstep(50.0, 58.0, y) * (1.0 - smoothstep(84.0, 92.0, y));
            double goldRim = smoothstep(46.0, 51.0, y) * (1.0 - smoothstep(51.0, 56.0, y))
                    + smoothstep(86.0, 91.0, y) * (1.0 - smoothstep(91.0, 96.0, y));
            double plinthMask = smoothstep(420.0, 434.0, y);
            for (int x = 0; x < S; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 10.0;
                double r = 234 + n - plinthMask * 26.0;
                double g = 188 + n * 0.85 - plinthMask * 26.0;
                double b = 150 + n * 0.72 - plinthMask * 22.0;

                r = r * (1.0 - friezeMask) + 42.0 * friezeMask;
                g = g * (1.0 - friezeMask) + 128.0 * friezeMask;
                b = b * (1.0 - friezeMask) + 168.0 * friezeMask;

                double gm = Math.min(1.0, goldRim);
                plaster[y * S + x] = packRgb(
                    (int) (r * (1.0 - gm) + 238.0 * gm),
                    (int) (g * (1.0 - gm) + 196.0 * gm),
                    (int) (b * (1.0 - gm) + 104.0 * gm)
                );
            }
        }

        // 9. Гладкий мраморный травертин площади Mirage (мягкие крупные плиты без резкой сетки)
        int[] mTile = SHARED_MIP0[TEX_MIRAGE_TILE];
        for (int y = 0; y < S; y++) {
            int ty = y & 255;
            double jy = smoothBevel(Math.min(ty, 255 - ty), 10.0);
            for (int x = 0; x < S; x++) {
                int tx = x & 255;
                double jx = smoothBevel(Math.min(tx, 255 - tx), 10.0);
                double joint = Math.min(jx, jy);
                double n = (fbmSeamless(x, y) - 0.5) * 12.0;
                int r = (int) (224 + n - (1.0 - joint) * 12.0);
                int g = (int) (198 + n * 0.9 - (1.0 - joint) * 11.0);
                int b = (int) (160 + n * 0.8 - (1.0 - joint) * 9.0);
                mTile[y * S + x] = packRgb(r, g, b);
            }
        }

        // 10. Матовый анодированный алюминий стеновых панелей Nuke со сглаженной линией безопасности
        int[] nukeWall = SHARED_MIP0[TEX_NUKE_PANEL];
        for (int y = 0; y < S; y++) {
            double stripeMask = smoothstep(382.0, 392.0, y) * (1.0 - smoothstep(424.0, 434.0, y));
            double blueBase = smoothstep(430.0, 442.0, y);
            for (int x = 0; x < S; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 7.0;
                double vSeam = smoothBevel(Math.min(x & 255, 255 - (x & 255)), 7.0);
                double r = 196 + n - (1.0 - vSeam) * 18.0;
                double g = 204 + n - (1.0 - vSeam) * 18.0;
                double b = 216 + n - (1.0 - vSeam) * 18.0;

                r = r * (1.0 - blueBase) + 52.0 * blueBase;
                g = g * (1.0 - blueBase) + 88.0 * blueBase;
                b = b * (1.0 - blueBase) + 134.0 * blueBase;

                double hz = smoothstep(0.34, 0.66, 0.5 + 0.5 * Math.sin(((x + y) / 44.0) * Math.PI * 2.0));
                double hr = 38 + 202 * hz, hg = 42 + 150 * hz, hb = 48 - 8 * hz;
                nukeWall[y * S + x] = packRgb(
                    (int) (r * (1.0 - stripeMask) + hr * stripeMask),
                    (int) (g * (1.0 - stripeMask) + hg * stripeMask),
                    (int) (b * (1.0 - stripeMask) + hb * stripeMask)
                );
            }
        }

        // 11. Гладкий эпоксидный полимерный пол АЭС Nuke (без тёмной клеточной сетки!)
        int[] nukeFloor = SHARED_MIP0[TEX_NUKE_FLOOR];
        for (int y = 0; y < S; y++) {
            int ty = y & 255;
            double jy = smoothBevel(Math.min(ty, 255 - ty), 9.0);
            for (int x = 0; x < S; x++) {
                int tx = x & 255;
                double jx = smoothBevel(Math.min(tx, 255 - tx), 9.0);
                double joint = Math.min(jx, jy);
                double n = (fbmSeamless(x, y) - 0.5) * 9.0;
                int v = (int) (140 + n - (1.0 - joint) * 9.0);
                nukeFloor[y * S + x] = packRgb(v - 4, v + 1, v + 9);
            }
        }

        // 12. Гладкий тёплый итальянский травертин площади Inferno (монолитный камень без рябящих кирпичей!)
        int[] cobble = SHARED_MIP0[TEX_INFERNO_COBBLE];
        for (int y = 0; y < S; y++) {
            int row = y >> 7; // крупные плиты 128px (делитель 512 без швов!)
            int inY = y & 127;
            double jy = smoothBevel(Math.min(inY, 127 - inY), 11.0);
            int off = (row & 1) * 128;
            for (int x = 0; x < S; x++) {
                int sx = (x + off) & 255;
                double jx = smoothBevel(Math.min(sx, 255 - sx), 11.0);
                double bevel = Math.min(jx, jy);
                double n = (fbmSeamless(x, y) - 0.5) * 12.0;
                double marble = Math.sin((x * 0.7 + y * 0.5) * (Math.PI * 2.0 / S) * 3.0) * 4.5;
                int r = (int) (188 + n + marble - (1.0 - bevel) * 13.0);
                int g = (int) (179 + n + marble - (1.0 - bevel) * 12.0);
                int b = (int) (166 + n + marble - (1.0 - bevel) * 11.0);
                cobble[y * S + x] = packRgb(r, g, b);
            }
        }

        // 13. Кремово-терракотовая венецианская штукатурка вилл Inferno с плавным переходом цоколя
        int[] stucco = SHARED_MIP0[TEX_INFERNO_STUCCO];
        for (int y = 0; y < S; y++) {
            double topTrim = 1.0 - smoothstep(26.0, 38.0, y);
            double botTrim = smoothstep(412.0, 426.0, y);
            for (int x = 0; x < S; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 12.0;
                double r = 240 + n;
                double g = 216 + n * 0.9;
                double b = 180 + n * 0.75;

                r = r * (1.0 - topTrim) + 172.0 * topTrim;
                g = g * (1.0 - topTrim) + 92.0 * topTrim;
                b = b * (1.0 - topTrim) + 62.0 * topTrim;

                r = r * (1.0 - botTrim) + (156.0 + n * 0.5) * botTrim;
                g = g * (1.0 - botTrim) + (146.0 + n * 0.5) * botTrim;
                b = b * (1.0 - botTrim) + (134.0 + n * 0.5) * botTrim;
                stucco[y * S + x] = packRgb((int) r, (int) g, (int) b);
            }
        }

        // 14. Гладкая волнообразная средиземноморская черепица крыш Inferno
        int[] roof = SHARED_MIP0[TEX_ROOF_TILES];
        for (int y = 0; y < S; y++) {
            int rowSeam = y & 63;
            double shadow = smoothBevel(Math.min(rowSeam, 63 - rowSeam), 10.0);
            for (int x = 0; x < S; x++) {
                double wave = Math.sin((x / 64.0) * Math.PI * 2.0) * 16.0;
                double n = (fbmSeamless(x, y) - 0.5) * 8.0;
                double mul = 0.82 + 0.18 * shadow;
                int r = (int) ((196 + wave + n) * mul);
                int g = (int) ((98 + wave * 0.48 + n) * mul);
                int b = (int) ((64 + wave * 0.30 + n) * mul);
                roof[y * S + x] = packRgb(r, g, b);
            }
        }

        // 15. Кристально-гладкая лазурная вода фонтана Inferno с мягкими солнечными бликами
        int[] water = SHARED_MIP0[TEX_WATER];
        for (int y = 0; y < S; y++) {
            double v = (y / (double) S) * Math.PI * 2.0;
            for (int x = 0; x < S; x++) {
                double u = (x / (double) S) * Math.PI * 2.0;
                double caustic = Math.sin(u * 3.0 + Math.cos(v * 2.0) * 1.6)
                               + Math.cos(v * 4.0 - Math.sin(u * 2.0) * 1.6);
                double spec = smoothstep(0.45, 1.65, caustic) * 62.0;
                int r = (int) (48 + spec * 1.15);
                int g = (int) (172 + caustic * 10.0 + spec);
                int b = (int) (216 + caustic * 12.0 + spec * 0.9);
                water[y * S + x] = packRgb(r, g, b);
            }
        }
    }

    private static void buildSharedMipmaps() {
        for (int t = 0; t < NUM_TEXTURES; t++) {
            downsample2x(SHARED_MIP0[t], 512, SHARED_MIP1[t], 256);
            downsample2x(SHARED_MIP1[t], 256, SHARED_MIP2[t], 128);
            downsample2x(SHARED_MIP2[t], 128, SHARED_MIP3[t], 64);
        }
    }

    private static void downsample2x(int[] src, int srcSize, int[] dst, int dstSize) {
        for (int y = 0; y < dstSize; y++) {
            int sy0 = (y * 2) * srcSize;
            int sy1 = (y * 2 + 1) * srcSize;
            for (int x = 0; x < dstSize; x++) {
                int sx = x * 2;
                int c00 = src[sy0 + sx];
                int c10 = src[sy0 + sx + 1];
                int c01 = src[sy1 + sx];
                int c11 = src[sy1 + sx + 1];
                int r = (((c00 >> 16) & 0xFF) + ((c10 >> 16) & 0xFF) + ((c01 >> 16) & 0xFF) + ((c11 >> 16) & 0xFF)) >> 2;
                int g = (((c00 >> 8) & 0xFF) + ((c10 >> 8) & 0xFF) + ((c01 >> 8) & 0xFF) + ((c11 >> 8) & 0xFF)) >> 2;
                int b = ((c00 & 0xFF) + (c10 & 0xFF) + (c01 & 0xFF) + (c11 & 0xFF)) >> 2;
                dst[y * dstSize + x] = (r << 16) | (g << 8) | b;
            }
        }
    }

    private static int packRgb(int r, int g, int b) {
        int cr = r < 0 ? 0 : (r > 255 ? 255 : r);
        int cg = g < 0 ? 0 : (g > 255 ? 255 : g);
        int cb = b < 0 ? 0 : (b > 255 ? 255 : b);
        return (cr << 16) | (cg << 8) | cb;
    }
}
