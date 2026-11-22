import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Программный 3D-движок CS-2 JAVA 5.0 (Source 2 Smooth HD Engine — без пикселизации!):
 * - Нативное HD / Full HD разрешение (1280x720 .. 1920x1080) с 16-пиксельной перспективной интерполяцией спанов
 * - Гладкие процедурные текстуры 256x256 с 3 уровнями Mipmap и билинейной фильтрацией (ноль квадратных пикселей вблизи!)
 * - Гладкое затенение Гуро (Gouraud Shading) для круглых цилиндров, сфер, шлемов, стволов, прицелов, колонн и вертолёта
 * - Многопоточный рендер неба и сглаживание краёв FXAA (убирает «лесенки» на диагональных гранях)
 * - 3D-погода (Ливень с грозой и Снегопад), направленный фонарик, объёмный дым, огонь и солнечные тени
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
    private static final int TEX_SIZE = 256;
    private static final int TEX_MASK = TEX_SIZE - 1;
    private static final int TEX_SHIFT = 8;
    private static final double NEAR_Z = 0.10;

    private int width;
    private int height;
    private double halfW;
    private double halfH;
    private double focalLength;

    private BufferedImage frameImage;
    private int[] pixels;
    private int[] fxaaScratch;
    private float[] zBuffer;
    private byte[] shadowStamp;

    // 3 уровня Mipmap (256x256, 128x128, 64x64) для гладкой фильтрации вблизи и вдали
    private final int[][] texturesMip0 = new int[NUM_TEXTURES][256 * 256];
    private final int[][] texturesMip1 = new int[NUM_TEXTURES][128 * 128];
    private final int[][] texturesMip2 = new int[NUM_TEXTURES][64 * 64];

    // Гладкая решётка шума Перлина/Value для бесшовных непиксельных текстур
    private final float[] smoothNoiseGrid = new float[64 * 64];

    // Параметры камеры
    private double camX, camY, camZ;
    private double cosYaw, sinYaw;
    private double cosPitch, sinPitch;

    // Освещение, атмосфера и флаги качества графики
    private Vec3 sunDir = new Vec3(0.46, 0.76, -0.45).normalize();
    private int skyZenithColor = 0x2563AE;
    private int skyHorizonColor = 0xECD5AA;
    private int sunDiscColor = 0xFFF9E2;
    private int fogColor = 0xDFC79A;
    private int lightTintColor = 0xFFFFFF;
    private double ambientStrength = 0.55;
    private double diffuseStrength = 0.45;
    private double fogStart = 30.0;
    private double fogEnd = 78.0;

    private boolean fogEnabled = true;
    private boolean skyCloudsEnabled = true;
    private boolean leftHandedView = false;
    private boolean smoothTextureFiltering = true;
    private boolean fxaaEnabled = true;
    private GameSettings.WeatherMode weatherMode = GameSettings.WeatherMode.CLEAR;
    private double cloudTime = 0.0;
    private final int[] cloudLut = new int[256 * 256];

    // Буферы отсечения по ближней плоскости (Near-Plane Clipping) с поддержкой Гуро-яркости вершин
    private final double[] clipInX = new double[8];
    private final double[] clipInY = new double[8];
    private final double[] clipInZ = new double[8];
    private final double[] clipInU = new double[8];
    private final double[] clipInV = new double[8];
    private final double[] clipInL = new double[8];

    private final double[] clipOutX = new double[8];
    private final double[] clipOutY = new double[8];
    private final double[] clipOutZ = new double[8];
    private final double[] clipOutU = new double[8];
    private final double[] clipOutV = new double[8];
    private final double[] clipOutL = new double[8];

    private final double[] projX = new double[8];
    private final double[] projY = new double[8];
    private final double[] projInvZ = new double[8];
    private final double[] projUZ = new double[8];
    private final double[] projVZ = new double[8];
    private final double[] projLZ = new double[8];

    public Renderer3D(int width, int height, double verticalFovDegrees) {
        initSmoothNoiseGrid();
        generateSmoothHdTextures();
        buildMipmapPyramids();
        generateCloudLut();
        ensureViewport(width, height, verticalFovDegrees);
        applyTimeOfDay(GameSettings.TimeOfDay.DAY);
    }

    private void initSmoothNoiseGrid() {
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < smoothNoiseGrid.length; i++) {
            seed = (seed * 25214903917L + 11L) & ((1L << 48) - 1);
            smoothNoiseGrid[i] = ((seed >>> 24) & 0xFFFFFF) / (float) 0x1000000;
        }
    }

    private float sampleSmoothNoise(double x, double y, int period) {
        int mask = period - 1;
        double fx = x - Math.floor(x);
        double fy = y - Math.floor(y);
        int x0 = ((int) Math.floor(x)) & mask;
        int y0 = ((int) Math.floor(y)) & mask;
        int x1 = (x0 + 1) & mask;
        int y1 = (y0 + 1) & mask;

        float sx = (float) (fx * fx * (3.0 - 2.0 * fx));
        float sy = (float) (fy * fy * (3.0 - 2.0 * fy));

        float n00 = smoothNoiseGrid[(y0 & 63) * 64 + (x0 & 63)];
        float n10 = smoothNoiseGrid[(y0 & 63) * 64 + (x1 & 63)];
        float n01 = smoothNoiseGrid[(y1 & 63) * 64 + (x0 & 63)];
        float n11 = smoothNoiseGrid[(y1 & 63) * 64 + (x1 & 63)];

        float nx0 = n00 + (n10 - n00) * sx;
        float nx1 = n01 + (n11 - n01) * sx;
        return nx0 + (nx1 - nx0) * sy;
    }

    private double fbmSeamless(int x, int y) {
        double u = x / (double) TEX_SIZE;
        double v = y / (double) TEX_SIZE;
        return sampleSmoothNoise(u * 8.0, v * 8.0, 8) * 0.55
             + sampleSmoothNoise(u * 16.0, v * 16.0, 16) * 0.30
             + sampleSmoothNoise(u * 32.0, v * 32.0, 32) * 0.15;
    }

    private void generateCloudLut() {
        for (int y = 0; y < 256; y++) {
            double v = (y / 256.0) * Math.PI * 2.0;
            for (int x = 0; x < 256; x++) {
                double u = (x / 256.0) * Math.PI * 2.0;
                double cVal = Math.sin(u * 2.0 + Math.cos(v * 2.0))
                            + 0.55 * Math.cos(u * 4.0 - v * 3.0)
                            + 0.25 * Math.sin(u * 6.0 + v * 4.0);
                int alpha = 0;
                if (cVal > 0.42) {
                    alpha = Math.min(118, (int) ((cVal - 0.42) * 92.0));
                }
                cloudLut[(y << 8) | x] = alpha;
            }
        }
    }

    public void ensureViewport(int newWidth, int newHeight, double verticalFovDegrees) {
        if (this.frameImage == null || this.width != newWidth || this.height != newHeight) {
            this.width = newWidth;
            this.height = newHeight;
            this.halfW = newWidth * 0.5;
            this.halfH = newHeight * 0.5;
            this.frameImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            this.pixels = ((DataBufferInt) frameImage.getRaster().getDataBuffer()).getData();
            this.fxaaScratch = new int[newWidth * newHeight];
            this.zBuffer = new float[newWidth * newHeight];
            this.shadowStamp = new byte[newWidth * newHeight];
        }
        double clampedFov = Math.max(8.0, Math.min(100.0, verticalFovDegrees));
        this.focalLength = halfH / Math.tan(Math.toRadians(clampedFov) * 0.5);
    }

    public void applyTimeOfDay(GameSettings.TimeOfDay tod) {
        switch (tod) {
            case SUNSET:
                sunDir = new Vec3(0.66, 0.36, -0.65).normalize();
                skyZenithColor = 0x263262;
                skyHorizonColor = 0xEE8646;
                sunDiscColor = 0xFFD082;
                fogColor = 0xD99668;
                lightTintColor = 0xFFDAB5;
                ambientStrength = 0.47;
                diffuseStrength = 0.55;
                fogStart = 26.0;
                fogEnd = 72.0;
                break;
            case NIGHT:
                sunDir = new Vec3(-0.42, 0.72, 0.55).normalize();
                skyZenithColor = 0x090F1E;
                skyHorizonColor = 0x1A2946;
                sunDiscColor = 0xD8E8FF;
                fogColor = 0x131E35;
                lightTintColor = 0x92B2DC;
                ambientStrength = 0.36;
                diffuseStrength = 0.38;
                fogStart = 20.0;
                fogEnd = 58.0;
                break;
            case DAY:
            default:
                sunDir = new Vec3(0.46, 0.76, -0.45).normalize();
                skyZenithColor = 0x2264B4;
                skyHorizonColor = 0xEEDAB4;
                sunDiscColor = 0xFFF9E4;
                fogColor = 0xE0CBA2;
                lightTintColor = 0xFFFDF8;
                ambientStrength = 0.56;
                diffuseStrength = 0.44;
                fogStart = 30.0;
                fogEnd = 78.0;
                break;
        }

        if (weatherMode == GameSettings.WeatherMode.RAIN_STORM) {
            skyZenithColor = lerpColor(skyZenithColor, 0x1F2A3A, 0.65);
            skyHorizonColor = lerpColor(skyHorizonColor, 0x465568, 0.68);
            fogColor = lerpColor(fogColor, 0x4C5B6E, 0.65);
            lightTintColor = lerpColor(lightTintColor, 0xC6D8EC, 0.45);
            fogStart = 20.0;
            fogEnd = 62.0;
        } else if (weatherMode == GameSettings.WeatherMode.SNOW) {
            skyZenithColor = lerpColor(skyZenithColor, 0x52749A, 0.45);
            skyHorizonColor = lerpColor(skyHorizonColor, 0xDCE6F2, 0.65);
            fogColor = lerpColor(fogColor, 0xD8E3F0, 0.65);
            lightTintColor = lerpColor(lightTintColor, 0xEEF5FF, 0.35);
            fogStart = 22.0;
            fogEnd = 66.0;
        }
    }

    public void setGraphicsFlags(boolean fog, boolean skyClouds, boolean leftHanded, double animTime) {
        this.fogEnabled = fog;
        this.skyCloudsEnabled = skyClouds;
        this.leftHandedView = leftHanded;
        this.cloudTime = animTime;
    }

    public void setSmoothQualityFlags(boolean smoothTextures, boolean fxaa, GameSettings.WeatherMode weather) {
        this.smoothTextureFiltering = smoothTextures;
        this.fxaaEnabled = fxaa;
        this.weatherMode = (weather != null) ? weather : GameSettings.WeatherMode.CLEAR;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public BufferedImage getFrameImage() { return frameImage; }
    public Vec3 getSunDir() { return sunDir; }

    public void setCamera(double x, double y, double z, double yaw, double pitch) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.cosYaw = Math.cos(yaw);
        this.sinYaw = Math.sin(yaw);
        this.cosPitch = Math.cos(pitch);
        this.sinPitch = Math.sin(pitch);
    }

    public void clearDepthBuffer() {
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);
    }

    /**
     * Многопоточная очистка буферов и отрисовка гладкого градиентного неба с облаками, солнцем и вспышками грозы.
     */
    public void beginFrame() {
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);
        Arrays.fill(shadowStamp, (byte) 0);

        final double srx = sunDir.x * cosYaw - sunDir.z * sinYaw;
        final double srz1 = sunDir.x * sinYaw + sunDir.z * cosYaw;
        final double scy = sunDir.y * cosPitch - srz1 * sinPitch;
        final double scz = sunDir.y * sinPitch + srz1 * cosPitch;
        final boolean sunInFront = scz > 0.05 && weatherMode != GameSettings.WeatherMode.RAIN_STORM;
        final double sunScreenX = sunInFront ? (halfW + (srx / scz) * focalLength) : -9999.0;
        final double sunScreenY = sunInFront ? (halfH - (scy / scz) * focalLength) : -9999.0;

        final double maxGlowR = height * 0.36;
        final double maxGlowR2 = maxGlowR * maxGlowR;
        final double invMaxGlowR2 = 1.0 / maxGlowR2;
        final double coreR2 = maxGlowR2 * 0.0072;
        final double invFocal = 1.0 / focalLength;
        final double shiftU = cloudTime * 3.0;
        final double shiftV = cloudTime * 1.5;

        // Редкая атмосферная вспышка молнии в грозу
        double stormPulse = 0.0;
        if (weatherMode == GameSettings.WeatherMode.RAIN_STORM) {
            double cycle = cloudTime % 7.5;
            if (cycle > 7.18 && cycle < 7.32) {
                stormPulse = 0.42;
            }
        }
        final double lightningFlash = stormPulse;

        final int bands = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        final int rowsPerBand = (height + bands - 1) / bands;

        IntStream.range(0, bands).parallel().forEach(band -> {
            int yStart = band * rowsPerBand;
            int yEnd = Math.min(height, yStart + rowsPerBand);
            for (int y = yStart; y < yEnd; y++) {
                double screenSlopeY = (halfH - y) / focalLength;
                double rayElev = screenSlopeY * cosPitch + sinPitch;
                int rowBaseColor;
                if (rayElev >= 0.0) {
                    double t = Math.min(1.0, Math.pow(rayElev, 0.62));
                    rowBaseColor = lerpColor(skyHorizonColor, skyZenithColor, t);
                } else {
                    double t = Math.min(1.0, -rayElev * 2.5);
                    rowBaseColor = lerpColor(fogColor, shadeColor(fogColor, 0.76), t);
                }
                if (lightningFlash > 0.0) {
                    rowBaseColor = lerpColor(rowBaseColor, 0xDCEEFF, lightningFlash);
                }

                int rowOffset = y * width;
                boolean checkClouds = skyCloudsEnabled && rayElev > 0.055;
                boolean checkSun = skyCloudsEnabled && sunInFront && Math.abs(y - sunScreenY) <= maxGlowR;

                if (!checkClouds && !checkSun) {
                    Arrays.fill(pixels, rowOffset, rowOffset + width, rowBaseColor);
                    continue;
                }

                double dy = y - sunScreenY;
                double dy2 = dy * dy;
                double czRow = -screenSlopeY * sinPitch + cosPitch;
                double invElevScale = 40.0 / (rayElev + 0.09);
                int cloudFade = Math.min(256, (int) (rayElev * 600.0));
                int cloudTint = lerpColor(skyHorizonColor, 0xFFFFFF, 0.58);

                double wx = (-halfW * invFocal) * cosYaw + czRow * sinYaw;
                double wz = -(-halfW * invFocal) * sinYaw + czRow * cosYaw;
                double stepWx = invFocal * cosYaw;
                double stepWz = -invFocal * sinYaw;

                for (int x = 0; x < width; x++) {
                    int col = rowBaseColor;

                    if (checkClouds) {
                        int iu = ((int) (wx * invElevScale + shiftU)) & 255;
                        int iv = ((int) (wz * invElevScale + shiftV)) & 255;
                        int cAlpha = (cloudLut[(iv << 8) | iu] * cloudFade) >> 8;
                        if (cAlpha > 2) {
                            int invA = 256 - cAlpha;
                            int r = (((col >> 16) & 0xFF) * invA + ((cloudTint >> 16) & 0xFF) * cAlpha) >> 8;
                            int g = (((col >> 8) & 0xFF) * invA + ((cloudTint >> 8) & 0xFF) * cAlpha) >> 8;
                            int b = ((col & 0xFF) * invA + (cloudTint & 0xFF) * cAlpha) >> 8;
                            col = (r << 16) | (g << 8) | b;
                        }
                    }

                    if (checkSun && rayElev > -0.04) {
                        double dx = x - sunScreenX;
                        double d2 = dx * dx + dy2;
                        if (d2 < maxGlowR2) {
                            double t = 1.0 - d2 * invMaxGlowR2;
                            double glow = t * t * t * 0.78;
                            if (d2 < coreR2) {
                                glow = Math.min(1.0, glow + 0.62);
                            }
                            col = lerpColor(col, sunDiscColor, glow);
                        }
                    }

                    pixels[rowOffset + x] = col;
                    wx += stepWx;
                    wz += stepWz;
                }
            }
        });
    }

    /**
     * Постобработка FXAA-сглаживания контрастных диагональных краёв сцены и оружия (убирает «лесенки»!).
     */
    public void applyFxaaPass() {
        if (!fxaaEnabled || width < 320 || height < 240) return;

        System.arraycopy(pixels, 0, fxaaScratch, 0, pixels.length);
        final int w = width;
        final int h = height;
        final int bands = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        final int rowsPerBand = ((h - 2) + bands - 1) / bands;

        IntStream.range(0, bands).parallel().forEach(band -> {
            int y0 = 1 + band * rowsPerBand;
            int y1 = Math.min(h - 1, y0 + rowsPerBand);
            for (int y = y0; y < y1; y++) {
                int row = y * w;
                int rowUp = row - w;
                int rowDn = row + w;
                for (int x = 1; x < w - 1; x++) {
                    int idx = row + x;
                    int cM = fxaaScratch[idx];
                    int cN = fxaaScratch[rowUp + x];
                    int cS = fxaaScratch[rowDn + x];
                    int cW = fxaaScratch[idx - 1];
                    int cE = fxaaScratch[idx + 1];

                    int lM = fastLuma(cM);
                    int lN = fastLuma(cN);
                    int lS = fastLuma(cS);
                    int lW = fastLuma(cW);
                    int lE = fastLuma(cE);

                    int lMin = Math.min(lM, Math.min(Math.min(lN, lS), Math.min(lW, lE)));
                    int lMax = Math.max(lM, Math.max(Math.max(lN, lS), Math.max(lW, lE)));
                    int range = lMax - lMin;
                    if (range < 34) {
                        continue;
                    }

                    int gradH = Math.abs(lN + lS - 2 * lM);
                    int gradV = Math.abs(lW + lE - 2 * lM);
                    int neighborA;
                    int neighborB;
                    if (gradH >= gradV) {
                        // Горизонтальная грань — сглаживаем вдоль горизонтали с лёгким захватом нормали
                        neighborA = blend50(cW, cE);
                        neighborB = (Math.abs(lN - lM) > Math.abs(lS - lM)) ? cN : cS;
                    } else {
                        // Вертикальная грань — сглаживаем вдоль вертикали
                        neighborA = blend50(cN, cS);
                        neighborB = (Math.abs(lW - lM) > Math.abs(lE - lM)) ? cW : cE;
                    }
                    int edgeMix = blend50(neighborA, neighborB);
                    int r = (((cM >> 16) & 0xFF) * 152 + ((edgeMix >> 16) & 0xFF) * 104) >> 8;
                    int g = (((cM >> 8) & 0xFF) * 152 + ((edgeMix >> 8) & 0xFF) * 104) >> 8;
                    int b = ((cM & 0xFF) * 152 + (edgeMix & 0xFF) * 104) >> 8;
                    pixels[idx] = (r << 16) | (g << 8) | b;
                }
            }
        });
    }

    private static int fastLuma(int rgb) {
        return (((rgb >> 16) & 0xFF) * 77 + ((rgb >> 8) & 0xFF) * 150 + (rgb & 0xFF) * 29) >> 8;
    }

    private static int blend50(int c1, int c2) {
        return ((c1 & 0xFEFEFE) >> 1) + ((c2 & 0xFEFEFE) >> 1);
    }

    /**
     * 3D-погодные эффекты в реальном пространстве вокруг игрока (косые струи ливня или объёмный снегопад)
     * с учётом крыш и навесов зданий (не идут сквозь потолок бункеров и зала АЭС Nuke!).
     */
    public void drawWeatherParticles(double animTime, Map map) {
        if (weatherMode == GameSettings.WeatherMode.CLEAR) return;

        boolean rain = (weatherMode == GameSettings.WeatherMode.RAIN_STORM);
        int count = rain ? 220 : 170;
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
                Vec3 top = new Vec3(wx + 0.08, wy + 0.48, wz);
                Vec3 bot = new Vec3(wx, wy, wz);
                draw3DLine(top, bot, 0xA8C8EB, 1);
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
                              double step, int texId, double uvScale) {
        int shadeColor = applyDirectionalLight(0xFFFFFF, 0.0, 1.0, 0.0);
        for (double z = minZ; z < maxZ - 1e-6; z += step) {
            double z2 = Math.min(maxZ, z + step);
            for (double x = minX; x < maxX - 1e-6; x += step) {
                double x2 = Math.min(maxX, x + step);
                double u0 = x * uvScale;
                double u1 = x2 * uvScale;
                double v0 = z * uvScale;
                double v1 = z2 * uvScale;
                drawWorldQuad(
                    x,  0.0, z,  u0, v1,
                    x2, 0.0, z,  u1, v1,
                    x2, 0.0, z2, u1, v0,
                    x,  0.0, z2, u0, v0,
                    texId, shadeColor, true, fogEnabled, 0
                );
            }
        }
    }

    public void drawFloorZoneQuad(double x0, double y, double z0, double x1, double z1, int texId, int tint) {
        int shade = applyDirectionalLight(tint, 0.0, 1.0, 0.0);
        drawWorldQuad(
            x0, y, z0, 0.0, 1.0,
            x1, y, z0, 1.0, 1.0,
            x1, y, z1, 1.0, 0.0,
            x0, y, z1, 0.0, 0.0,
            texId, shade, true, fogEnabled, 0
        );
    }

    public void drawBoxSunShadow(Vec3.Box b, double mapMinX, double mapMaxX, double mapMinZ, double mapMaxZ) {
        if (!b.castsShadow || b.maxY <= 0.05 || sunDir.y <= 0.15) return;
        if ((b.maxX - b.minX) > 42.0 || (b.maxZ - b.minZ) > 42.0) return;

        double h = Math.min(b.maxY, 4.8);
        double shiftX = -(sunDir.x / sunDir.y) * h;
        double shiftZ = -(sunDir.z / sunDir.y) * h;

        double sx0 = clamp(b.minX + shiftX, mapMinX, mapMaxX);
        double sx1 = clamp(b.maxX + shiftX, mapMinX, mapMaxX);
        double sz0 = clamp(b.minZ + shiftZ, mapMinZ, mapMaxZ);
        double sz1 = clamp(b.maxZ + shiftZ, mapMinZ, mapMaxZ);

        double bx0 = clamp(b.minX, mapMinX, mapMaxX);
        double bx1 = clamp(b.maxX, mapMinX, mapMaxX);
        double bz0 = clamp(b.minZ, mapMinZ, mapMaxZ);
        double bz1 = clamp(b.maxZ, mapMinZ, mapMaxZ);

        double y = 0.0;
        drawWorldQuad(sx0, y, sz0, 0, 1, sx1, y, sz0, 1, 1, sx1, y, sz1, 1, 0, sx0, y, sz1, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
        drawWorldQuad(bx0, y, bz0, 0, 1, bx1, y, bz0, 1, 1, sx1, y, sz0, 1, 0, sx0, y, sz0, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
        drawWorldQuad(bx1, y, bz1, 0, 1, bx0, y, bz1, 1, 1, sx0, y, sz1, 1, 0, sx1, y, sz1, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
        drawWorldQuad(bx0, y, bz1, 0, 1, bx0, y, bz0, 1, 1, sx0, y, sz0, 1, 0, sx0, y, sz1, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
        drawWorldQuad(bx1, y, bz0, 0, 1, bx1, y, bz1, 1, 1, sx1, y, sz1, 1, 0, sx1, y, sz0, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
    }

    public void drawBlobShadow(double cx, double groundY, double cz, double radX, double radZ) {
        double shiftX = sunDir.y > 0.2 ? -(sunDir.x / sunDir.y) * 0.35 : 0.0;
        double shiftZ = sunDir.y > 0.2 ? -(sunDir.z / sunDir.y) * 0.35 : 0.0;
        double x0 = cx - radX + Math.min(0.0, shiftX);
        double x1 = cx + radX + Math.max(0.0, shiftX);
        double z0 = cz - radZ + Math.min(0.0, shiftZ);
        double z1 = cz + radZ + Math.max(0.0, shiftZ);
        drawWorldQuad(x0, groundY, z0, 0, 1, x1, groundY, z0, 1, 1, x1, groundY, z1, 1, 0, x0, groundY, z1, 0, 0,
                TEX_SOLID, 0xFFFFFF, false, false, 1);
    }

    public void drawAxisAlignedBox(Vec3.Box b) {
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ;
        double x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        double s = b.uvScale;
        int tex = b.textureId;
        int base = b.tintColor;

        double wX = (s <= 0.0) ? 1.0 : (x1 - x0) * s;
        double wY = (s <= 0.0) ? 1.0 : (y1 - y0) * s;
        double wZ = (s <= 0.0) ? 1.0 : (z1 - z0) * s;

        if (camZ < z0) {
            int col = applyDirectionalLight(base, 0.0, 0.0, -1.0);
            drawWorldQuad(x0, y0, z0, 0.0, wY, x1, y0, z0, wX, wY, x1, y1, z0, wX, 0.0, x0, y1, z0, 0.0, 0.0, tex, col, true, fogEnabled, 0);
        }
        if (camZ > z1) {
            int col = applyDirectionalLight(base, 0.0, 0.0, 1.0);
            drawWorldQuad(x1, y0, z1, 0.0, wY, x0, y0, z1, wX, wY, x0, y1, z1, wX, 0.0, x1, y1, z1, 0.0, 0.0, tex, col, true, fogEnabled, 0);
        }
        if (camX < x0) {
            int col = applyDirectionalLight(base, -1.0, 0.0, 0.0);
            drawWorldQuad(x0, y0, z1, 0.0, wY, x0, y0, z0, wZ, wY, x0, y1, z0, wZ, 0.0, x0, y1, z1, 0.0, 0.0, tex, col, true, fogEnabled, 0);
        }
        if (camX > x1) {
            int col = applyDirectionalLight(base, 1.0, 0.0, 0.0);
            drawWorldQuad(x1, y0, z0, 0.0, wY, x1, y0, z1, wZ, wY, x1, y1, z1, wZ, 0.0, x1, y1, z0, 0.0, 0.0, tex, col, true, fogEnabled, 0);
        }
        if (camY > y1) {
            int col = applyDirectionalLight(base, 0.0, 1.0, 0.0);
            drawWorldQuad(x0, y1, z0, 0.0, wZ, x1, y1, z0, wX, wZ, x1, y1, z1, wX, 0.0, x0, y1, z1, 0.0, 0.0, tex, col, true, fogEnabled, 0);
        }
        if (y0 > 0.01 && camY < y0) {
            int col = applyDirectionalLight(base, 0.0, -1.0, 0.0);
            drawWorldQuad(x0, y0, z1, 0.0, wZ, x1, y0, z1, wX, wZ, x1, y0, z0, wX, 0.0, x0, y0, z0, 0.0, 0.0, tex, col, true, fogEnabled, 0);
        }
    }

    /**
     * Гладкий вертикальный 3D-цилиндр / усечённый конус с интерполяцией освещения Гуро (колонны, реактор Silo, бочки, стволы пальм!).
     */
    public void drawWorldCylinder(double cx, double bottomY, double cz,
                                  double radBottom, double radTop, double height,
                                  int segments, int texId, int baseColor) {
        int segs = Math.max(6, Math.min(20, segments));
        double topY = bottomY + height;
        int tinted = multiplyColors(baseColor, lightTintColor);
        double step = (Math.PI * 2.0) / segs;

        double prevCos = 1.0, prevSin = 0.0;
        double prevLight = computeSmoothLightFactor(prevCos, 0.12, prevSin);

        for (int i = 1; i <= segs; i++) {
            double ang = (i == segs) ? 0.0 : (i * step);
            double c = Math.cos(ang);
            double s = Math.sin(ang);
            double curLight = computeSmoothLightFactor(c, 0.12, s);

            double x0b = cx + prevCos * radBottom, z0b = cz + prevSin * radBottom;
            double x1b = cx + c * radBottom,       z1b = cz + s * radBottom;
            double x1t = cx + c * radTop,          z1t = cz + s * radTop;
            double x0t = cx + prevCos * radTop,    z0t = cz + prevSin * radTop;

            double u0 = (i - 1) / (double) segs;
            double u1 = i / (double) segs;

            drawWorldQuadGouraud(
                x0b, bottomY, z0b, u0, 1.0, prevLight,
                x1b, bottomY, z1b, u1, 1.0, curLight,
                x1t, topY,    z1t, u1, 0.0, curLight,
                x0t, topY,    z0t, u0, 0.0, prevLight,
                texId, tinted, false, fogEnabled
            );

            if (radTop > 0.01 && camY > topY) {
                double topL = computeSmoothLightFactor(0.0, 1.0, 0.0);
                drawWorldTriGouraud(
                    cx, topY, cz, 0.5, 0.5, topL,
                    x0t, topY, z0t, 0.0, 0.0, topL,
                    x1t, topY, z1t, 1.0, 0.0, topL,
                    texId, tinted, false, fogEnabled
                );
            }

            prevCos = c;
            prevSin = s;
            prevLight = curLight;
        }
    }

    /**
     * Гладкий 3D-эллипсоид / сфера с плавным по-вершинным затенением Гуро (шлемы бойцов, купола, кроны деревьев, нос вертолёта!).
     */
    public void drawWorldEllipsoid(double cx, double cy, double cz,
                                   double rx, double ry, double rz,
                                   int segments, int rings, int baseColor) {
        int segs = Math.max(6, Math.min(16, segments));
        int rngs = Math.max(4, Math.min(10, rings));
        int tinted = multiplyColors(baseColor, lightTintColor);

        for (int r = 0; r < rngs; r++) {
            double phi0 = Math.PI * (r / (double) rngs - 0.5);
            double phi1 = Math.PI * ((r + 1) / (double) rngs - 0.5);
            double sinP0 = Math.sin(phi0), cosP0 = Math.cos(phi0);
            double sinP1 = Math.sin(phi1), cosP1 = Math.cos(phi1);

            for (int s = 0; s < segs; s++) {
                double th0 = (2.0 * Math.PI * s) / segs;
                double th1 = (2.0 * Math.PI * (s + 1)) / segs;
                double cT0 = Math.cos(th0), sT0 = Math.sin(th0);
                double cT1 = Math.cos(th1), sT1 = Math.sin(th1);

                double nx00 = cosP0 * cT0, ny00 = sinP0, nz00 = cosP0 * sT0;
                double nx10 = cosP0 * cT1, ny10 = sinP0, nz10 = cosP0 * sT1;
                double nx11 = cosP1 * cT1, ny11 = sinP1, nz11 = cosP1 * sT1;
                double nx01 = cosP1 * cT0, ny01 = sinP1, nz01 = cosP1 * sT0;

                double l00 = computeSmoothLightFactor(nx00, ny00, nz00);
                double l10 = computeSmoothLightFactor(nx10, ny10, nz10);
                double l11 = computeSmoothLightFactor(nx11, ny11, nz11);
                double l01 = computeSmoothLightFactor(nx01, ny01, nz01);

                drawWorldQuadGouraud(
                    cx + nx00 * rx, cy + ny00 * ry, cz + nz00 * rz, 0, 1, l00,
                    cx + nx10 * rx, cy + ny10 * ry, cz + nz10 * rz, 1, 1, l10,
                    cx + nx11 * rx, cy + ny11 * ry, cz + nz11 * rz, 1, 0, l11,
                    cx + nx01 * rx, cy + ny01 * ry, cz + nz01 * rz, 0, 0, l01,
                    TEX_SOLID, tinted, false, fogEnabled
                );
            }
        }
    }

    /**
     * Гладкий ориентированный в пространстве 3D-цилиндр (конечности солдат, ствол турели, ракеты RPG-7, балка вертолёта).
     */
    public void drawOrientedWorldCylinder(double cx, double cy, double cz,
                                          double radius, double length,
                                          double yaw, double pitchX,
                                          int segments, int baseColor) {
        int segs = Math.max(6, Math.min(14, segments));
        double halfL = length * 0.5;
        double cyaw = Math.cos(yaw), syaw = Math.sin(yaw);
        double cp = Math.cos(pitchX), sp = Math.sin(pitchX);
        int tinted = multiplyColors(baseColor, lightTintColor);
        double step = (Math.PI * 2.0) / segs;

        for (int i = 0; i < segs; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;
            double lx0 = Math.cos(a0) * radius, ly0 = Math.sin(a0) * radius;
            double lx1 = Math.cos(a1) * radius, ly1 = Math.sin(a1) * radius;

            double[] p00 = transformOrientedLocal(cx, cy, cz, lx0, ly0, -halfL, cyaw, syaw, cp, sp);
            double[] p10 = transformOrientedLocal(cx, cy, cz, lx1, ly1, -halfL, cyaw, syaw, cp, sp);
            double[] p11 = transformOrientedLocal(cx, cy, cz, lx1, ly1,  halfL, cyaw, syaw, cp, sp);
            double[] p01 = transformOrientedLocal(cx, cy, cz, lx0, ly0,  halfL, cyaw, syaw, cp, sp);

            double[] n0 = rotateOrientedNormal(Math.cos(a0), Math.sin(a0), 0.0, cyaw, syaw, cp, sp);
            double[] n1 = rotateOrientedNormal(Math.cos(a1), Math.sin(a1), 0.0, cyaw, syaw, cp, sp);
            double l0 = computeSmoothLightFactor(n0[0], n0[1], n0[2]);
            double l1 = computeSmoothLightFactor(n1[0], n1[1], n1[2]);

            drawWorldQuadGouraud(
                p00[0], p00[1], p00[2], 0, 1, l0,
                p10[0], p10[1], p10[2], 1, 1, l1,
                p11[0], p11[1], p11[2], 1, 0, l1,
                p01[0], p01[1], p01[2], 0, 0, l0,
                TEX_SOLID, tinted, false, fogEnabled
            );
        }
    }

    private static double[] transformOrientedLocal(double cx, double cy, double cz,
                                                   double lx, double ly, double lz,
                                                   double cyaw, double syaw, double cp, double sp) {
        double py = ly * cp - lz * sp;
        double pz = ly * sp + lz * cp;
        double wx = lx * cyaw + pz * syaw;
        double wz = -lx * syaw + pz * cyaw;
        return new double[]{cx + wx, cy + py, cz + wz};
    }

    private static double[] rotateOrientedNormal(double nx, double ny, double nz,
                                                 double cyaw, double syaw, double cp, double sp) {
        double py = ny * cp - nz * sp;
        double pz = ny * sp + nz * cp;
        double wx = nx * cyaw + pz * syaw;
        double wz = -nx * syaw + pz * cyaw;
        return new double[]{wx, py, wz};
    }

    /**
     * Гладкий цилиндр от первого лица для круглых оружейных стволов, глушителей, оптики AWP и гранатомёта RPG-7!
     */
    public void drawViewModelCylinder(double cx, double cy, double cz,
                                      double radRear, double radFront, double lengthZ,
                                      double rotX, double rotY, double rotZ,
                                      double pivotX, double pivotY, double pivotZ,
                                      int segments, int baseColor) {
        int segs = Math.max(8, Math.min(14, segments));
        double halfL = lengthZ * 0.5;
        double cxR = Math.cos(rotX), sxR = Math.sin(rotX);
        double cyR = Math.cos(rotY), syR = Math.sin(rotY);
        double czR = Math.cos(rotZ), szR = Math.sin(rotZ);
        double mirrorSign = leftHandedView ? -1.0 : 1.0;
        int tinted = multiplyColors(baseColor, lightTintColor);
        double step = (Math.PI * 2.0) / segs;

        for (int i = 0; i < segs; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;
            double c0 = Math.cos(a0), s0 = Math.sin(a0);
            double c1 = Math.cos(a1), s1 = Math.sin(a1);

            double[] v00 = transformViewModelPoint(cx + c0 * radRear,  cy + s0 * radRear,  cz - halfL, rotX, rotY, rotZ, cxR, sxR, cyR, syR, czR, szR, pivotX, pivotY, pivotZ, mirrorSign);
            double[] v10 = transformViewModelPoint(cx + c1 * radRear,  cy + s1 * radRear,  cz - halfL, rotX, rotY, rotZ, cxR, sxR, cyR, syR, czR, szR, pivotX, pivotY, pivotZ, mirrorSign);
            double[] v11 = transformViewModelPoint(cx + c1 * radFront, cy + s1 * radFront, cz + halfL, rotX, rotY, rotZ, cxR, sxR, cyR, syR, czR, szR, pivotX, pivotY, pivotZ, mirrorSign);
            double[] v01 = transformViewModelPoint(cx + c0 * radFront, cy + s0 * radFront, cz + halfL, rotX, rotY, rotZ, cxR, sxR, cyR, syR, czR, szR, pivotX, pivotY, pivotZ, mirrorSign);

            // Гладкий металлический блик сверху-слева на круглом стволе и оптике
            double l0 = 0.72 + Math.max(0.0, s0 * 0.42 + c0 * 0.14);
            double l1 = 0.72 + Math.max(0.0, s1 * 0.42 + c1 * 0.14);

            drawCameraQuadGouraud(
                v00[0], v00[1], v00[2], 0, 1, l0,
                v10[0], v10[1], v10[2], 1, 1, l1,
                v11[0], v11[1], v11[2], 1, 0, l1,
                v01[0], v01[1], v01[2], 0, 0, l0,
                TEX_SOLID, tinted, false, false
            );
        }
    }

    private static double[] transformViewModelPoint(
            double pxWorld, double pyWorld, double pzWorld,
            double rotX, double rotY, double rotZ,
            double cxR, double sxR, double cyR, double syR, double czR, double szR,
            double pivotX, double pivotY, double pivotZ, double mirrorSign) {
        double px = pxWorld - pivotX;
        double py = pyWorld - pivotY;
        double pz = pzWorld - pivotZ;

        double x1 = px * czR - py * szR;
        double y1 = px * szR + py * czR;
        double z1 = pz;

        double y2 = y1 * cxR - z1 * sxR;
        double z2 = y1 * sxR + z1 * cxR;

        double x3 = x1 * cyR + z2 * syR;
        double z3 = -x1 * syR + z2 * cyR;

        return new double[]{(x3 + pivotX) * mirrorSign, y2 + pivotY, z3 + pivotZ};
    }

    /**
     * Отрисовка масштабного боевого вертолёта-босса в 3D-небе (корпус, стеклянная кабина, пилоны НАР, вращающиеся винты!).
     */
    public void drawAttackHelicopter(Vec3.AttackHelicopter heli) {
        if (heli == null || !heli.isAlive()) return;

        double x = heli.pos.x, y = heli.pos.y, z = heli.pos.z;
        double yaw = heli.yaw;
        double fwdX = Math.sin(yaw), fwdZ = Math.cos(yaw);
        double rightX = Math.cos(yaw), rightZ = -Math.sin(yaw);

        int hullCol = heli.hitFlashTimer > 0.0 ? 0xFF7258 : 0x2B353E;
        int armorCol = heli.hitFlashTimer > 0.0 ? 0xFFA27A : 0x3D4B56;
        int glassCol = 0x5AD2FF;

        // Основной бронированный фюзеляж и обтекаемый нос кабины
        drawOrientedWorldCylinder(x, y, z, 0.92, 3.8, yaw, 0.06, 10, hullCol);
        drawWorldEllipsoid(x + fwdX * 1.85, y - 0.12, z + fwdZ * 1.85, 0.82, 0.68, 0.82, 10, 6, glassCol);
        drawOrientedWorldBox(x, y + 0.52, z - 0.15, 1.15, 0.62, 2.2, yaw, 0.05, TEX_SOLID, armorCol);

        // Боковые крылья с блоками ракет и крупнокалиберная носовая турель
        drawOrientedWorldBox(x, y - 0.08, z + 0.1, 3.6, 0.18, 0.72, yaw, 0.0, TEX_SOLID, armorCol);
        drawOrientedWorldCylinder(x + rightX * 1.55, y - 0.28, z + rightZ * 1.55, 0.26, 1.25, yaw, 0.05, 8, 0x4C5842);
        drawOrientedWorldCylinder(x - rightX * 1.55, y - 0.28, z - rightZ * 1.55, 0.26, 1.25, yaw, 0.05, 8, 0x4C5842);
        drawOrientedWorldCylinder(x + fwdX * 2.1, y - 0.56, z + fwdZ * 2.1, 0.12, 1.15, yaw, 0.24, 8, 0x1A1E24);

        // Хвостовая балка, киль и рулевой винт
        double tailX = x - fwdX * 3.1, tailY = y + 0.28, tailZ = z - fwdZ * 3.1;
        drawOrientedWorldCylinder(tailX, tailY, tailZ, 0.34, 3.2, yaw, -0.06, 8, hullCol);
        double finX = x - fwdX * 4.55, finY = y + 0.78, finZ = z - fwdZ * 4.55;
        drawOrientedWorldBox(finX, finY, finZ, 0.18, 1.25, 0.65, yaw, -0.2, TEX_SOLID, armorCol);
        drawOrientedWorldBox(finX + rightX * 0.22, finY + 0.2, finZ + rightZ * 0.22,
                0.08, 1.35, 0.16, yaw, heli.rotorPhase * 1.7, TEX_SOLID, 0xDCE4EE);

        // Втулка и 4 длинные вращающиеся лопасти главного несущего винта
        drawWorldCylinder(x, y + 0.75, z, 0.22, 0.18, 0.48, 8, TEX_SOLID, 0x1E242C);
        drawOrientedWorldBox(x, y + 1.22, z, 7.6, 0.06, 0.30, heli.rotorPhase, 0.0, TEX_SOLID, 0x181C22);
        drawOrientedWorldBox(x, y + 1.22, z, 0.30, 0.06, 7.6, heli.rotorPhase, 0.0, TEX_SOLID, 0x181C22);

        // Яркие навигационные огни вертолёта (красный/зелёный)
        drawTranslucentSphereSprite(x + rightX * 1.85, y - 0.08, z + rightZ * 1.85, 0.18, 0x38FF7A, 0.85);
        drawTranslucentSphereSprite(x - rightX * 1.85, y - 0.08, z - rightZ * 1.85, 0.18, 0xFF3A30, 0.85);
    }

    /**
     * Отрисовка развёрнутой 3D авто-турели спецназа CT (тренога, сервопривод, сдвоенный ствол и лазерный сенсор).
     */
    public void drawSentryTurret(Vec3.SentryTurret t) {
        double x = t.pos.x, y = t.pos.y, z = t.pos.z;
        drawBlobShadow(x, 0.004, z, 0.52, 0.52);

        // Тренога и центральная стойка
        for (int i = 0; i < 3; i++) {
            double a = i * (Math.PI * 2.0 / 3.0) + 0.4;
            double lx = x + Math.cos(a) * 0.32;
            double lz = z + Math.sin(a) * 0.32;
            drawOrientedWorldCylinder(lx, y + 0.24, lz, 0.055, 0.62, a + Math.PI * 0.5, 0.62, 6, 0x2A3340);
        }
        drawWorldCylinder(x, y + 0.34, z, 0.12, 0.10, 0.46, 8, TEX_SOLID, 0x384658);

        // Поворотная бронированная головка спецназа CT
        double headY = y + 0.86;
        drawOrientedWorldBox(x, headY, z, 0.42, 0.28, 0.50, t.yaw, t.pitch, TEX_SOLID, 0x28568E);
        drawOrientedWorldBox(x, headY + 0.16, z - 0.04, 0.28, 0.12, 0.32, t.yaw, t.pitch, TEX_SOLID, 0x4CA8E8);

        double fwdX = Math.sin(t.yaw), fwdZ = Math.cos(t.yaw);
        drawOrientedWorldCylinder(x + fwdX * 0.38, headY - 0.02, z + fwdZ * 0.38,
                0.055, 0.56, t.yaw, t.pitch, 8, 0x1A2028);

        // Голубой/зелёный индикатор захвата цели + вспышка выстрела
        int ledCol = t.hasTarget ? 0xFF3E38 : 0x36F598;
        drawTranslucentSphereSprite(x + fwdX * 0.22, headY + 0.16, z + fwdZ * 0.22, 0.08, ledCol, 0.90);
        if (t.muzzleFlash > 0.0) {
            drawTranslucentSphereSprite(x + fwdX * 0.68, headY - 0.02, z + fwdZ * 0.68, 0.18, 0x7AE8FF, 0.92);
        }
    }

    /**
     * Отрисовка летящей 3D-ракеты RPG-7 с боевой частью и реактивным факелом двигателя.
     */
    public void drawRocketProjectile(Vec3.Rocket r) {
        drawOrientedWorldCylinder(r.pos.x, r.pos.y, r.pos.z, 0.065, 0.62, r.yaw, -r.pitch, 8, 0x4E5C3E);
        Vec3 dir = r.vel.normalize();
        Vec3 tail = r.pos.sub(dir.scale(0.36));
        drawTranslucentSphereSprite(tail.x, tail.y, tail.z, 0.20, 0xFFB838, 0.92);
    }

    /**
     * Отрисовка десантируемого на парашюте контейнера снабжения (Supply Drop).
     */
    public void drawSupplyDrop(Vec3.SupplyDrop sd) {
        double x = sd.pos.x, y = sd.pos.y, z = sd.pos.z;
        drawBlobShadow(x, 0.004, z, 0.68, 0.68);
        drawOrientedWorldBox(x, y + 0.42, z, 0.86, 0.76, 0.86, sd.beaconPhase * 0.08, 0.0, TEX_CRATE_MILITARY, 0x4E7854);
        drawOrientedWorldBox(x, y + 0.44, z, 0.90, 0.14, 0.90, sd.beaconPhase * 0.08, 0.0, TEX_SOLID, 0xF5B836);

        // Купол парашюта и стропы во время спуска с неба
        if (!sd.landed) {
            double canopyY = y + 2.65;
            drawWorldEllipsoid(x, canopyY, z, 1.55, 0.62, 1.55, 12, 5, 0xE67E28);
            draw3DLine(new Vec3(x - 0.38, y + 0.78, z - 0.38), new Vec3(x - 1.25, canopyY - 0.1, z - 1.0), 0xD8E0E8, 1);
            draw3DLine(new Vec3(x + 0.38, y + 0.78, z - 0.38), new Vec3(x + 1.25, canopyY - 0.1, z - 1.0), 0xD8E0E8, 1);
            draw3DLine(new Vec3(x - 0.38, y + 0.78, z + 0.38), new Vec3(x - 1.25, canopyY - 0.1, z + 1.0), 0xD8E0E8, 1);
            draw3DLine(new Vec3(x + 0.38, y + 0.78, z + 0.38), new Vec3(x + 1.25, canopyY - 0.1, z + 1.0), 0xD8E0E8, 1);
        }

        // Зелёный сигнальный дымок/маяк над контейнером
        if (Math.sin(sd.beaconPhase) > -0.3) {
            drawTranslucentSphereSprite(x, y + 0.96, z, 0.20, 0x3CFF8E, 0.85);
        }
    }

    public void drawOrientedWorldBox(double cx, double cy, double cz,
                                     double sx, double sy, double sz,
                                     double yaw, double pitchX,
                                     int texId, int baseColor) {
        double hx = sx * 0.5, hy = sy * 0.5, hz = sz * 0.5;
        double cyaw = Math.cos(yaw), syaw = Math.sin(yaw);
        double cp = Math.cos(pitchX), sp = Math.sin(pitchX);

        double[] vx = new double[8];
        double[] vy = new double[8];
        double[] vz = new double[8];

        double[] lx = {-hx,  hx,  hx, -hx, -hx,  hx,  hx, -hx};
        double[] ly = {-hy, -hy,  hy,  hy, -hy, -hy,  hy,  hy};
        double[] lz = {-hz, -hz, -hz, -hz,  hz,  hz,  hz,  hz};

        for (int i = 0; i < 8; i++) {
            double py = ly[i] * cp - lz[i] * sp;
            double pz = ly[i] * sp + lz[i] * cp;
            double wx = lx[i] * cyaw + pz * syaw;
            double wz = -lx[i] * syaw + pz * cyaw;
            vx[i] = cx + wx;
            vy[i] = cy + py;
            vz[i] = cz + wz;
        }

        int tinted = multiplyColors(baseColor, lightTintColor);
        int cFront = shadeColor(tinted, 0.94);
        int cBack  = shadeColor(tinted, 0.78);
        int cSide  = shadeColor(tinted, 0.85);
        int cTop   = shadeColor(tinted, 1.06);
        int cBot   = shadeColor(tinted, 0.60);

        drawWorldQuad(vx[0], vy[0], vz[0], 0, 1, vx[1], vy[1], vz[1], 1, 1, vx[2], vy[2], vz[2], 1, 0, vx[3], vy[3], vz[3], 0, 0, texId, cBack,  false, fogEnabled, 0);
        drawWorldQuad(vx[5], vy[5], vz[5], 0, 1, vx[4], vy[4], vz[4], 1, 1, vx[7], vy[7], vz[7], 1, 0, vx[6], vy[6], vz[6], 0, 0, texId, cFront, false, fogEnabled, 0);
        drawWorldQuad(vx[4], vy[4], vz[4], 0, 1, vx[0], vy[0], vz[0], 1, 1, vx[3], vy[3], vz[3], 1, 0, vx[7], vy[7], vz[7], 0, 0, texId, cSide,  false, fogEnabled, 0);
        drawWorldQuad(vx[1], vy[1], vz[1], 0, 1, vx[5], vy[5], vz[5], 1, 1, vx[6], vy[6], vz[6], 1, 0, vx[2], vy[2], vz[2], 0, 0, texId, cSide,  false, fogEnabled, 0);
        drawWorldQuad(vx[3], vy[3], vz[3], 0, 1, vx[2], vy[2], vz[2], 1, 1, vx[6], vy[6], vz[6], 1, 0, vx[7], vy[7], vz[7], 0, 0, texId, cTop,   false, fogEnabled, 0);
        drawWorldQuad(vx[4], vy[4], vz[4], 0, 1, vx[5], vy[5], vz[5], 1, 1, vx[1], vy[1], vz[1], 1, 0, vx[0], vy[0], vz[0], 0, 0, texId, cBot,   false, fogEnabled, 0);
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

        double[] vx = new double[8];
        double[] vy = new double[8];
        double[] vz = new double[8];

        double[] lx = {-hx,  hx,  hx, -hx, -hx,  hx,  hx, -hx};
        double[] ly = {-hy, -hy,  hy,  hy, -hy, -hy,  hy,  hy};
        double[] lz = {-hz, -hz, -hz, -hz,  hz,  hz,  hz,  hz};

        double mirrorSign = leftHandedView ? -1.0 : 1.0;

        for (int i = 0; i < 8; i++) {
            double px = cx + lx[i] - pivotX;
            double py = cy + ly[i] - pivotY;
            double pz = cz + lz[i] - pivotZ;

            double x1 = px * czR - py * szR;
            double y1 = px * szR + py * czR;
            double z1 = pz;

            double y2 = y1 * cxR - z1 * sxR;
            double z2 = y1 * sxR + z1 * cxR;

            double x3 = x1 * cyR + z2 * syR;
            double z3 = -x1 * syR + z2 * cyR;

            vx[i] = (x3 + pivotX) * mirrorSign;
            vy[i] = y2 + pivotY;
            vz[i] = z3 + pivotZ;
        }

        int tinted = multiplyColors(baseColor, lightTintColor);
        int cTop   = shadeColor(tinted, 1.12);
        int cSideL = shadeColor(tinted, 0.94);
        int cSideR = shadeColor(tinted, 0.80);
        int cFront = shadeColor(tinted, 0.88);
        int cBot   = shadeColor(tinted, 0.62);

        drawCameraQuad(vx[0], vy[0], vz[0], 0, 1, vx[1], vy[1], vz[1], 1, 1, vx[2], vy[2], vz[2], 1, 0, vx[3], vy[3], vz[3], 0, 0, texId, cFront, false, false, 0);
        drawCameraQuad(vx[5], vy[5], vz[5], 0, 1, vx[4], vy[4], vz[4], 1, 1, vx[7], vy[7], vz[7], 1, 0, vx[6], vy[6], vz[6], 0, 0, texId, cFront, false, false, 0);
        drawCameraQuad(vx[4], vy[4], vz[4], 0, 1, vx[0], vy[0], vz[0], 1, 1, vx[3], vy[3], vz[3], 1, 0, vx[7], vy[7], vz[7], 0, 0, texId, cSideL, false, false, 0);
        drawCameraQuad(vx[1], vy[1], vz[1], 0, 1, vx[5], vy[5], vz[5], 1, 1, vx[6], vy[6], vz[6], 1, 0, vx[2], vy[2], vz[2], 0, 0, texId, cSideR, false, false, 0);
        drawCameraQuad(vx[3], vy[3], vz[3], 0, 1, vx[2], vy[2], vz[2], 1, 1, vx[6], vy[6], vz[6], 1, 0, vx[7], vy[7], vz[7], 0, 0, texId, cTop,   false, false, 0);
        drawCameraQuad(vx[4], vy[4], vz[4], 0, 1, vx[5], vy[5], vz[5], 1, 1, vx[1], vy[1], vz[1], 1, 0, vx[0], vy[0], vz[0], 0, 0, texId, cBot,   false, false, 0);
    }

    public void drawDecal(Vec3.Decal d) {
        Vec3 n = d.normal;
        Vec3 tangent = (Math.abs(n.y) > 0.8) ? new Vec3(1.0, 0.0, 0.0) : new Vec3(-n.z, 0.0, n.x).normalize();
        Vec3 bitan = n.cross(tangent).normalize();
        double s = d.size * 0.5;
        Vec3 c = d.position.add(n.scale(0.012));

        Vec3 p0 = c.sub(tangent.scale(s)).sub(bitan.scale(s));
        Vec3 p1 = c.add(tangent.scale(s)).sub(bitan.scale(s));
        Vec3 p2 = c.add(tangent.scale(s)).add(bitan.scale(s));
        Vec3 p3 = c.sub(tangent.scale(s)).add(bitan.scale(s));

        drawWorldQuad(
            p0.x, p0.y, p0.z, 0, 1,
            p1.x, p1.y, p1.z, 1, 1,
            p2.x, p2.y, p2.z, 1, 0,
            p3.x, p3.y, p3.z, 0, 0,
            TEX_SOLID, d.color, false, fogEnabled, 0
        );
    }

    public void drawGraffiti(Vec3.Graffiti gr) {
        Vec3 n = gr.normal;
        Vec3 tangent = (Math.abs(n.y) > 0.8) ? new Vec3(1.0, 0.0, 0.0) : new Vec3(-n.z, 0.0, n.x).normalize();
        Vec3 bitan = n.cross(tangent).normalize();
        if (bitan.y < -0.2) bitan = bitan.scale(-1.0);

        drawDecalRect(gr.pos, tangent, bitan, 0.0, 0.0, 0.72, 0.72, 0x161A22);
        Vec3 front = gr.pos.add(n.scale(0.006));

        if (gr.style == 0) {
            drawDecalRect(front, tangent, bitan, 0.0, -0.16, 0.54, 0.14, gr.primaryColor);
            drawDecalRect(front, tangent, bitan, -0.18, 0.04, 0.13, 0.28, gr.primaryColor);
            drawDecalRect(front, tangent, bitan,  0.00, 0.08, 0.14, 0.34, gr.accentColor);
            drawDecalRect(front, tangent, bitan,  0.18, 0.04, 0.13, 0.28, gr.primaryColor);
        } else if (gr.style == 1) {
            drawDecalRect(front, tangent, bitan, 0.0, 0.0, 0.52, 0.10, gr.primaryColor);
            drawDecalRect(front, tangent, bitan, 0.0, 0.0, 0.10, 0.52, gr.primaryColor);
            drawDecalRect(front, tangent, bitan, 0.0, 0.0, 0.20, 0.20, gr.accentColor);
        } else {
            drawDecalRect(front, tangent, bitan,  0.06,  0.14, 0.30, 0.13, gr.primaryColor);
            drawDecalRect(front, tangent, bitan,  0.00,  0.00, 0.34, 0.12, gr.accentColor);
            drawDecalRect(front, tangent, bitan, -0.06, -0.14, 0.30, 0.13, gr.primaryColor);
        }
    }

    private void drawDecalRect(Vec3 center, Vec3 tangent, Vec3 bitan,
                               double offU, double offV, double w, double h, int color) {
        double hw = w * 0.5, hh = h * 0.5;
        Vec3 c = center.add(tangent.scale(offU)).add(bitan.scale(offV));
        Vec3 p0 = c.sub(tangent.scale(hw)).sub(bitan.scale(hh));
        Vec3 p1 = c.add(tangent.scale(hw)).sub(bitan.scale(hh));
        Vec3 p2 = c.add(tangent.scale(hw)).add(bitan.scale(hh));
        Vec3 p3 = c.sub(tangent.scale(hw)).add(bitan.scale(hh));
        drawWorldQuad(
            p0.x, p0.y, p0.z, 0, 1,
            p1.x, p1.y, p1.z, 1, 1,
            p2.x, p2.y, p2.z, 1, 0,
            p3.x, p3.y, p3.z, 0, 0,
            TEX_SOLID, color, false, fogEnabled, 0
        );
    }

    public void drawSmokeCloud(Vec3.SmokeCloud cloud) {
        double radius = cloud.getCurrentRadius();
        if (radius <= 0.15) return;

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

        int sR = (rgb >> 16) & 0xFF;
        int sG = (rgb >> 8) & 0xFF;
        int sB = rgb & 0xFF;
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
                    int dst = pixels[idx];
                    int nr = (((dst >> 16) & 0xFF) * (256 - a) + sR * a) >> 8;
                    int ng = (((dst >> 8) & 0xFF) * (256 - a) + sG * a) >> 8;
                    int nb = ((dst & 0xFF) * (256 - a) + sB * a) >> 8;
                    pixels[idx] = (nr << 16) | (ng << 8) | nb;
                }
            }
        }
    }

    private void blendFullScreen(int rgb, double alpha) {
        int a = (int) (alpha * 256.0);
        int ia = 256 - a;
        int sR = (rgb >> 16) & 0xFF, sG = (rgb >> 8) & 0xFF, sB = rgb & 0xFF;
        for (int i = 0; i < pixels.length; i++) {
            int dst = pixels[i];
            int nr = (((dst >> 16) & 0xFF) * ia + sR * a) >> 8;
            int ng = (((dst >> 8) & 0xFF) * ia + sG * a) >> 8;
            int nb = ((dst & 0xFF) * ia + sB * a) >> 8;
            pixels[i] = (nr << 16) | (ng << 8) | nb;
        }
    }

    public void drawFireZone(Vec3.FireZone fz) {
        double rad = fz.getCurrentRadius();
        if (rad < 0.25) return;

        double y = fz.center.y + 0.018;
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

    public void draw3DLine(Vec3 a, Vec3 b, int rgb, int thickness) {
        double ax = (a.x - camX) * cosYaw - (a.z - camZ) * sinYaw;
        double az1 = (a.x - camX) * sinYaw + (a.z - camZ) * cosYaw;
        double ay = (a.y - camY) * cosPitch - az1 * sinPitch;
        double az = (a.y - camY) * sinPitch + az1 * cosPitch;

        double bx = (b.x - camX) * cosYaw - (b.z - camZ) * sinYaw;
        double bz1 = (b.x - camX) * sinYaw + (b.z - camZ) * cosYaw;
        double by = (b.y - camY) * cosPitch - bz1 * sinPitch;
        double bz = (b.y - camY) * sinPitch + bz1 * cosPitch;

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
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) steps = 1;
        if (steps > width * 2) steps = width * 2;

        double invZ0 = 1.0 / az;
        double invZ1 = 1.0 / bz;

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int px = (int) (sx0 + dx * t);
            int py = (int) (sy0 + dy * t);
            float z = (float) (1.0 / (invZ0 + (invZ1 - invZ0) * t));
            for (int oy = -thickness + 1; oy < thickness; oy++) {
                int yy = py + oy;
                if (yy < 0 || yy >= height) continue;
                for (int ox = -thickness + 1; ox < thickness; ox++) {
                    int xx = px + ox;
                    if (xx < 0 || xx >= width) continue;
                    int idx = yy * width + xx;
                    if (z < zBuffer[idx]) {
                        pixels[idx] = rgb;
                    }
                }
            }
        }
    }

    public void drawParticle(Vec3.Particle p) {
        drawTranslucentSphereSprite(p.x, p.y, p.z, p.size * 0.72, p.color, 0.90);
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

        drawCameraQuad(
            cx0, cy0, cz0, u0, v0,
            cx1, cy1, cz1, u1, v1,
            cx2, cy2, cz2, u2, v2,
            cx3, cy3, cz3, u3, v3,
            texId, shadeColor, cullBackface, applyFog, renderMode
        );
    }

    private void drawWorldQuadGouraud(
            double x0, double y0, double z0, double u0, double v0, double l0,
            double x1, double y1, double z1, double u1, double v1, double l1,
            double x2, double y2, double z2, double u2, double v2, double l2,
            double x3, double y3, double z3, double u3, double v3, double l3,
            int texId, int baseColor, boolean cullBackface, boolean applyFog) {

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

        drawCameraQuadGouraud(
            cx0, cy0, cz0, u0, v0, l0,
            cx1, cy1, cz1, u1, v1, l1,
            cx2, cy2, cz2, u2, v2, l2,
            cx3, cy3, cz3, u3, v3, l3,
            texId, baseColor, cullBackface, applyFog
        );
    }

    private void drawWorldTriGouraud(
            double x0, double y0, double z0, double u0, double v0, double l0,
            double x1, double y1, double z1, double u1, double v1, double l1,
            double x2, double y2, double z2, double u2, double v2, double l2,
            int texId, int baseColor, boolean cullBackface, boolean applyFog) {
        drawWorldQuadGouraud(
            x0, y0, z0, u0, v0, l0,
            x1, y1, z1, u1, v1, l1,
            x2, y2, z2, u2, v2, l2,
            x2, y2, z2, u2, v2, l2,
            texId, baseColor, cullBackface, applyFog
        );
    }

    private void drawCameraQuad(
            double cx0, double cy0, double cz0, double u0, double v0,
            double cx1, double cy1, double cz1, double u1, double v1,
            double cx2, double cy2, double cz2, double u2, double v2,
            double cx3, double cy3, double cz3, double u3, double v3,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog, int renderMode) {

        if (cz0 < NEAR_Z && cz1 < NEAR_Z && cz2 < NEAR_Z && cz3 < NEAR_Z) return;

        clipInX[0] = cx0; clipInY[0] = cy0; clipInZ[0] = cz0; clipInU[0] = u0; clipInV[0] = v0; clipInL[0] = 1.0;
        clipInX[1] = cx1; clipInY[1] = cy1; clipInZ[1] = cz1; clipInU[1] = u1; clipInV[1] = v1; clipInL[1] = 1.0;
        clipInX[2] = cx2; clipInY[2] = cy2; clipInZ[2] = cz2; clipInU[2] = u2; clipInV[2] = v2; clipInL[2] = 1.0;
        clipInX[3] = cx3; clipInY[3] = cy3; clipInZ[3] = cz3; clipInU[3] = u3; clipInV[3] = v3; clipInL[3] = 1.0;

        clipAndRasterizePolygon(4, texId, shadeColor, cullBackface, applyFog, renderMode, false);
    }

    private void drawCameraQuadGouraud(
            double cx0, double cy0, double cz0, double u0, double v0, double l0,
            double cx1, double cy1, double cz1, double u1, double v1, double l1,
            double cx2, double cy2, double cz2, double u2, double v2, double l2,
            double cx3, double cy3, double cz3, double u3, double v3, double l3,
            int texId, int baseColor, boolean cullBackface, boolean applyFog) {

        if (cz0 < NEAR_Z && cz1 < NEAR_Z && cz2 < NEAR_Z && cz3 < NEAR_Z) return;

        clipInX[0] = cx0; clipInY[0] = cy0; clipInZ[0] = cz0; clipInU[0] = u0; clipInV[0] = v0; clipInL[0] = l0;
        clipInX[1] = cx1; clipInY[1] = cy1; clipInZ[1] = cz1; clipInU[1] = u1; clipInV[1] = v1; clipInL[1] = l1;
        clipInX[2] = cx2; clipInY[2] = cy2; clipInZ[2] = cz2; clipInU[2] = u2; clipInV[2] = v2; clipInL[2] = l2;
        clipInX[3] = cx3; clipInY[3] = cy3; clipInZ[3] = cz3; clipInU[3] = u3; clipInV[3] = v3; clipInL[3] = l3;

        clipAndRasterizePolygon(4, texId, baseColor, cullBackface, applyFog, 0, true);
    }

    private void clipAndRasterizePolygon(int vertCount, int texId, int shadeColor,
                                         boolean cullBackface, boolean applyFog, int renderMode, boolean gouraud) {
        int outCount = 0;
        for (int i = 0; i < vertCount; i++) {
            int j = (i + 1) % vertCount;
            double zA = clipInZ[i];
            double zB = clipInZ[j];
            boolean inA = zA >= NEAR_Z;
            boolean inB = zB >= NEAR_Z;

            if (inA) {
                clipOutX[outCount] = clipInX[i];
                clipOutY[outCount] = clipInY[i];
                clipOutZ[outCount] = zA;
                clipOutU[outCount] = clipInU[i];
                clipOutV[outCount] = clipInV[i];
                clipOutL[outCount] = clipInL[i];
                outCount++;
            }
            if (inA != inB) {
                double t = (NEAR_Z - zA) / (zB - zA);
                clipOutX[outCount] = clipInX[i] + (clipInX[j] - clipInX[i]) * t;
                clipOutY[outCount] = clipInY[i] + (clipInY[j] - clipInY[i]) * t;
                clipOutZ[outCount] = NEAR_Z;
                clipOutU[outCount] = clipInU[i] + (clipInU[j] - clipInU[i]) * t;
                clipOutV[outCount] = clipInV[i] + (clipInV[j] - clipInV[i]) * t;
                clipOutL[outCount] = clipInL[i] + (clipInL[j] - clipInL[i]) * t;
                outCount++;
            }
        }

        if (outCount < 3) return;

        for (int i = 0; i < outCount; i++) {
            double invZ = 1.0 / clipOutZ[i];
            projX[i] = halfW + (clipOutX[i] * invZ) * focalLength;
            projY[i] = halfH - (clipOutY[i] * invZ) * focalLength;
            projInvZ[i] = invZ;
            projUZ[i] = clipOutU[i] * invZ;
            projVZ[i] = clipOutV[i] * invZ;
            projLZ[i] = clipOutL[i] * invZ;
        }

        for (int i = 1; i < outCount - 1; i++) {
            rasterizeTriangle(
                projX[0], projY[0], projInvZ[0], projUZ[0], projVZ[0], projLZ[0],
                projX[i], projY[i], projInvZ[i], projUZ[i], projVZ[i], projLZ[i],
                projX[i + 1], projY[i + 1], projInvZ[i + 1], projUZ[i + 1], projVZ[i + 1], projLZ[i + 1],
                texId, shadeColor, cullBackface, applyFog, renderMode, gouraud
            );
        }
    }

    /**
     * Высокоскоростной растеризатор треугольника с 16-пиксельной перспективной интерполяцией,
     * билинейной фильтрацией текстур 256x256 + Mipmap и гладким освещением Гуро!
     */
    private void rasterizeTriangle(
            double x0, double y0, double iz0, double uz0, double vz0, double lz0,
            double x1, double y1, double iz1, double uz1, double vz1, double lz1,
            double x2, double y2, double iz2, double uz2, double vz2, double lz2,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog, int renderMode, boolean gouraud) {

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
                double tvz = vz1; vz1 = vz2; vz2 = tvz;
                double tlz = lz1; lz1 = lz2; lz2 = tlz;
                area = -area;
            }
        }

        int minX = Math.max(0, (int) Math.ceil(Math.min(x0, Math.min(x1, x2)) - 0.5));
        int maxX = Math.min(width - 1, (int) Math.floor(Math.max(x0, Math.max(x1, x2)) - 0.5));
        int minY = Math.max(0, (int) Math.ceil(Math.min(y0, Math.min(y1, y2)) - 0.5));
        int maxY = Math.min(height - 1, (int) Math.floor(Math.max(y0, Math.max(y1, y2)) - 0.5));

        if (minX > maxX || minY > maxY) return;

        double invArea = 1.0 / area;

        double dx12 = x2 - x1, dy12 = y2 - y1;
        double dx20 = x0 - x2, dy20 = y0 - y2;
        double dx01 = x1 - x0, dy01 = y1 - y0;

        double w0StepX = -dy12 * invArea, w0StepY = dx12 * invArea;
        double w1StepX = -dy20 * invArea, w1StepY = dx20 * invArea;
        double w2StepX = -dy01 * invArea, w2StepY = dx01 * invArea;

        double startPx = minX + 0.5;
        double startPy = minY + 0.5;

        double w0Row = ((startPx - x1) * (-dy12) + (startPy - y1) * dx12) * invArea;
        double w1Row = ((startPx - x2) * (-dy20) + (startPy - y2) * dx20) * invArea;
        double w2Row = ((startPx - x0) * (-dy01) + (startPy - y0) * dx01) * invArea;

        // Режим проекционной тени на земле
        if (renderMode == 1) {
            for (int y = minY; y <= maxY; y++) {
                double w0 = w0Row, w1 = w1Row, w2 = w2Row;
                int rowIdx = y * width;
                for (int x = minX; x <= maxX; x++) {
                    if (w0 >= -1e-5 && w1 >= -1e-5 && w2 >= -1e-5) {
                        int idx = rowIdx + x;
                        if (shadowStamp[idx] == 0) {
                            double invZ = w0 * iz0 + w1 * iz1 + w2 * iz2;
                            if (invZ > 1e-6) {
                                float z = (float) (1.0 / invZ);
                                if (Math.abs(z - zBuffer[idx]) < 0.45f) {
                                    shadowStamp[idx] = 1;
                                    int c = pixels[idx];
                                    int r = (((c >> 16) & 0xFF) * 154) >> 8;
                                    int g = (((c >> 8) & 0xFF) * 160) >> 8;
                                    int b = ((c & 0xFF) * 176) >> 8;
                                    pixels[idx] = (r << 16) | (g << 8) | b;
                                }
                            }
                        }
                    }
                    w0 += w0StepX; w1 += w1StepX; w2 += w2StepX;
                }
                w0Row += w0StepY; w1Row += w1StepY; w2Row += w2StepY;
            }
            return;
        }

        final int sR = (shadeColor >> 16) & 0xFF;
        final int sG = (shadeColor >> 8) & 0xFF;
        final int sB = shadeColor & 0xFF;
        final int fogR = (fogColor >> 16) & 0xFF;
        final int fogG = (fogColor >> 8) & 0xFF;
        final int fogB = fogColor & 0xFF;

        final boolean solidOnly = (texId == TEX_SOLID);
        final int[] mip0 = texturesMip0[texId];
        final int[] mip1 = texturesMip1[texId];
        final int[] mip2 = texturesMip2[texId];
        final boolean bilinear = this.smoothTextureFiltering;

        double izStepX = w0StepX * iz0 + w1StepX * iz1 + w2StepX * iz2;
        double uzStepX = w0StepX * uz0 + w1StepX * uz1 + w2StepX * uz2;
        double vzStepX = w0StepX * vz0 + w1StepX * vz1 + w2StepX * vz2;
        double lzStepX = w0StepX * lz0 + w1StepX * lz1 + w2StepX * lz2;

        for (int y = minY; y <= maxY; y++) {
            double w0 = w0Row, w1 = w1Row, w2 = w2Row;
            int spanStart = -1;
            int spanEnd = -1;

            // Находим точные границы горизонтального спана [spanStart .. spanEnd] внутри треугольника
            for (int x = minX; x <= maxX; x++) {
                if (w0 >= -1e-5 && w1 >= -1e-5 && w2 >= -1e-5) {
                    if (spanStart < 0) spanStart = x;
                    spanEnd = x;
                } else if (spanStart >= 0) {
                    break;
                }
                w0 += w0StepX;
                w1 += w1StepX;
                w2 += w2StepX;
            }

            if (spanStart >= 0) {
                int offsetFromMin = spanStart - minX;
                double sw0 = w0Row + w0StepX * offsetFromMin;
                double sw1 = w1Row + w1StepX * offsetFromMin;
                double sw2 = w2Row + w2StepX * offsetFromMin;

                double curIz = sw0 * iz0 + sw1 * iz1 + sw2 * iz2;
                double curUz = sw0 * uz0 + sw1 * uz1 + sw2 * uz2;
                double curVz = sw0 * vz0 + sw1 * vz1 + sw2 * vz2;
                double curLz = sw0 * lz0 + sw1 * lz1 + sw2 * lz2;

                int rowIdx = y * width;
                int x = spanStart;

                // Перспективно-точная интерполяция кусочками по 16 пикселей (скорость Quake + гладкость Source 2!)
                while (x <= spanEnd) {
                    int stepCount = Math.min(16, spanEnd - x + 1);
                    double endIz = curIz + izStepX * stepCount;
                    double endUz = curUz + uzStepX * stepCount;
                    double endVz = curVz + vzStepX * stepCount;
                    double endLz = curLz + lzStepX * stepCount;

                    double zA = curIz > 1e-6 ? (1.0 / curIz) : 1000.0;
                    double zB = endIz > 1e-6 ? (1.0 / endIz) : 1000.0;

                    double uA = curUz * zA, vA = curVz * zA, lA = curLz * zA;
                    double uB = endUz * zB, vB = endVz * zB, lB = endLz * zB;

                    double invLen = 1.0 / stepCount;
                    float zCur = (float) zA;
                    float zDelta = (float) ((zB - zA) * invLen);

                    int lFixed = (int) (lA * 256.0);
                    int lDelta = (int) (((lB - lA) * 256.0) * invLen);

                    if (solidOnly) {
                        for (int k = 0; k < stepCount; k++) {
                            int idx = rowIdx + x + k;
                            if (zCur <= zBuffer[idx] + 1e-4f) {
                                zBuffer[idx] = zCur;
                                int r = sR, g = sG, b = sB;
                                if (gouraud) {
                                    int lf = Math.max(0, lFixed);
                                    r = Math.min(255, (r * lf) >> 8);
                                    g = Math.min(255, (g * lf) >> 8);
                                    b = Math.min(255, (b * lf) >> 8);
                                }
                                if (applyFog && zCur > fogStart) {
                                    int fogFactor = (int) Math.min(235.0, ((zCur - fogStart) / (fogEnd - fogStart)) * 235.0);
                                    r = (r * (256 - fogFactor) + fogR * fogFactor) >> 8;
                                    g = (g * (256 - fogFactor) + fogG * fogFactor) >> 8;
                                    b = (b * (256 - fogFactor) + fogB * fogFactor) >> 8;
                                }
                                pixels[idx] = (r << 16) | (g << 8) | b;
                            }
                            zCur += zDelta;
                            lFixed += lDelta;
                        }
                    } else {
                        // Автовыбор уровня Mipmap по дистанции до камеры (0 = 256x256, 1 = 128x128, 2 = 64x64)
                        int mipLevel = (!bilinear || zCur < 12.0f) ? 0 : (zCur < 26.0f ? 1 : 2);
                        int scaleShift = (mipLevel == 0) ? 16 : (mipLevel == 1 ? 15 : 14);
                        double fpScale = (double) (1 << scaleShift);
                        int uFixed = (int) Math.floor(uA * fpScale);
                        int vFixed = (int) Math.floor(vA * fpScale);
                        int uDelta = (int) (((uB - uA) * fpScale) * invLen);
                        int vDelta = (int) (((vB - vA) * fpScale) * invLen);

                        for (int k = 0; k < stepCount; k++) {
                            int idx = rowIdx + x + k;
                            if (zCur <= zBuffer[idx] + 1e-4f) {
                                zBuffer[idx] = zCur;

                                int texel;
                                if (!bilinear) {
                                    int tx = (uFixed >> 8) & TEX_MASK;
                                    int ty = (vFixed >> 8) & TEX_MASK;
                                    texel = mip0[(ty << TEX_SHIFT) | tx];
                                } else if (mipLevel == 0) {
                                    texel = sampleBilinear(mip0, uFixed, vFixed, 255, 8);
                                } else if (mipLevel == 1) {
                                    texel = sampleBilinear(mip1, uFixed, vFixed, 127, 7);
                                } else {
                                    texel = sampleBilinear(mip2, uFixed, vFixed, 63, 6);
                                }

                                int r = (((texel >> 16) & 0xFF) * sR) >> 8;
                                int g = (((texel >> 8) & 0xFF) * sG) >> 8;
                                int b = ((texel & 0xFF) * sB) >> 8;

                                if (gouraud) {
                                    int lf = Math.max(0, lFixed);
                                    r = Math.min(255, (r * lf) >> 8);
                                    g = Math.min(255, (g * lf) >> 8);
                                    b = Math.min(255, (b * lf) >> 8);
                                }

                                if (applyFog && zCur > fogStart) {
                                    int fogFactor = (int) Math.min(235.0, ((zCur - fogStart) / (fogEnd - fogStart)) * 235.0);
                                    r = (r * (256 - fogFactor) + fogR * fogFactor) >> 8;
                                    g = (g * (256 - fogFactor) + fogG * fogFactor) >> 8;
                                    b = (b * (256 - fogFactor) + fogB * fogFactor) >> 8;
                                }

                                pixels[idx] = (r << 16) | (g << 8) | b;
                            }
                            zCur += zDelta;
                            uFixed += uDelta;
                            vFixed += vDelta;
                            lFixed += lDelta;
                        }
                    }

                    x += stepCount;
                    curIz = endIz;
                    curUz = endUz;
                    curVz = endVz;
                    curLz = endLz;
                }
            }

            w0Row += w0StepY;
            w1Row += w1StepY;
            w2Row += w2StepY;
        }
    }

    /**
     * Быстрая целочисленная билинейная фильтрация 2x2 текселей (8.8 fixed-point):
     * полностью устраняет квадратные пиксели на близких стенах, полу, дверях и ящиках!
     */
    private static int sampleBilinear(int[] tex, int uFixed8, int vFixed8, int mask, int shift) {
        int x0 = (uFixed8 >> 8) & mask;
        int y0 = (vFixed8 >> 8) & mask;
        int x1 = (x0 + 1) & mask;
        int y1 = (y0 + 1) & mask;

        int fx = uFixed8 & 0xFF;
        int fy = vFixed8 & 0xFF;
        int ifx = 256 - fx;
        int ify = 256 - fy;

        int w00 = (ifx * ify) >> 8;
        int w10 = (fx * ify) >> 8;
        int w01 = (ifx * fy) >> 8;
        int w11 = (fx * fy) >> 8;

        int row0 = y0 << shift;
        int row1 = y1 << shift;
        int c00 = tex[row0 | x0];
        int c10 = tex[row0 | x1];
        int c01 = tex[row1 | x0];
        int c11 = tex[row1 | x1];

        int r = (((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10
               + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11) >> 8;
        int g = (((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10
               + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11) >> 8;
        int b = ((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10
               + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11) >> 8;
        return (r << 16) | (g << 8) | b;
    }

    private double computeSmoothLightFactor(double nx, double ny, double nz) {
        double dot = nx * sunDir.x + ny * sunDir.y + nz * sunDir.z;
        double diff = Math.max(0.0, dot);
        double spec = Math.pow(diff, 6.0) * 0.16;
        double hemi = 0.08 * (ny + 1.0);
        return ambientStrength + diff * diffuseStrength + hemi + spec;
    }

    private int applyDirectionalLight(int baseRgb, double nx, double ny, double nz) {
        double dot = nx * sunDir.x + ny * sunDir.y + nz * sunDir.z;
        double intensity = ambientStrength + Math.max(0.0, dot) * diffuseStrength;
        if (ny < -0.5) {
            intensity = ambientStrength * 0.72;
        }
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
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double smoothBevel(double dist, double width) {
        if (dist >= width) return 1.0;
        if (dist <= 0.0) return 0.0;
        double t = dist / width;
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Генерация 16 гладких HD-текстур 256x256 на базе непрерывного многооктавного шума fbmSeamless
     * (никакого пиксельного «шума телевизора» — плавные каменные фаски, волокна дерева и мозаика!).
     */
    private void generateSmoothHdTextures() {
        // 0. Гладкий пустынный песчаник пола Dust II (256x256)
        int[] sand = texturesMip0[TEX_FLOOR_SAND];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 26.0;
                double dunes = Math.sin(x * 0.049 + Math.cos(y * 0.049) * 1.4) * 7.5;
                int tx = x & 127;
                int ty = y & 127;
                double joint = Math.min(smoothBevel(Math.min(tx, 127 - tx), 4.5),
                                        smoothBevel(Math.min(ty, 127 - ty), 4.5));

                int r = (int) (219 + dunes + n - (1.0 - joint) * 24.0);
                int g = (int) (191 + dunes * 0.88 + n * 0.9 - (1.0 - joint) * 21.0);
                int b = (int) (140 + dunes * 0.68 + n * 0.7 - (1.0 - joint) * 16.0);
                sand[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 1. Тёсаный известняк стен Dust II с мягкой объёмной фаской
        int[] wall = texturesMip0[TEX_WALL_SANDSTONE];
        for (int y = 0; y < TEX_SIZE; y++) {
            int row = y / 64;
            int offset = (row & 1) * 64;
            int inRowY = y & 63;
            for (int x = 0; x < TEX_SIZE; x++) {
                int sx = (x + offset) & 127;
                int blockTone = (((row * 37 + ((x + offset) >> 7) * 73) & 15) - 7);
                double n = (fbmSeamless(x, y) - 0.5) * 20.0;
                double edgeDist = Math.min(Math.min(inRowY, 63 - inRowY), Math.min(sx, 127 - sx));
                double groove = smoothBevel(edgeDist, 5.0);
                double highlight = (inRowY >= 4 && inRowY <= 7) ? 8.0 : 0.0;

                int r = (int) (216 + blockTone + n + highlight - (1.0 - groove) * 44.0);
                int g = (int) (184 + blockTone + n * 0.9 + highlight * 0.85 - (1.0 - groove) * 38.0);
                int b = (int) (132 + blockTone * 0.7 + n * 0.7 + highlight * 0.65 - (1.0 - groove) * 28.0);
                wall[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 2. Гладкий деревянный ящик с волокнами древесины и стальными уголками
        int[] wood = texturesMip0[TEX_CRATE_WOOD];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double n = fbmSeamless(x, y);
                double fiber = Math.sin(x * 0.28 + n * 5.0) * 9.0;
                boolean bracket = (x < 28 || x >= 228) && (y < 28 || y >= 228);
                boolean frame = x < 22 || x >= 234 || y < 22 || y >= 234;
                double d1 = Math.abs(x - y);
                double d2 = Math.abs((TEX_SIZE - 1 - x) - y);
                boolean crossBeam = Math.min(d1, d2) < 13.0;
                int plankSeam = y & 31;
                double seamDark = 1.0 - smoothBevel(Math.min(plankSeam, 31 - plankSeam), 2.5);

                int r = (int) (184 + fiber + (n - 0.5) * 14.0 - seamDark * 22.0);
                int g = (int) (138 + fiber * 0.8 + (n - 0.5) * 11.0 - seamDark * 18.0);
                int b = (int) (80 + fiber * 0.5 + (n - 0.5) * 8.0 - seamDark * 12.0);

                if (bracket) {
                    r = 58 + (int) (n * 14); g = 62 + (int) (n * 14); b = 68 + (int) (n * 14);
                } else if (frame || crossBeam) {
                    r -= 24; g -= 20; b -= 14;
                }
                wood[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 3. Военный композитный контейнер с диагональной жёлтой маркировкой
        int[] mil = texturesMip0[TEX_CRATE_MILITARY];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 14.0;
                boolean border = x < 18 || x >= 238 || y < 18 || y >= 238;
                boolean rib = (y >= 48 && y <= 208) && ((y & 31) < 7);
                boolean stripeZone = y >= 114 && y <= 142 && x >= 36 && x <= 220;
                boolean hazardYellow = stripeZone && (((x + y) / 18) & 1) == 0;

                int r = (int) (74 + n), g = (int) (90 + n), b = (int) (68 + n);
                if (hazardYellow) {
                    r = 228; g = 182; b = 46;
                } else if (stripeZone) {
                    r = 38; g = 42; b = 38;
                } else if (border) {
                    r = 54; g = 64; b = 50;
                } else if (rib) {
                    r += 14; g += 16; b += 12;
                }
                mil[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 4. Гладкий архитектурный бетон / камень
        int[] conc = texturesMip0[TEX_CONCRETE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 20.0;
                double joint = Math.min(smoothBevel(Math.min(x & 127, 127 - (x & 127)), 3.5),
                                        smoothBevel(Math.min(y & 127, 127 - (y & 127)), 3.5));
                int v = (int) (164 + n - (1.0 - joint) * 26.0);
                conc[y * TEX_SIZE + x] = packRgb(v, v - 2, v - 6);
            }
        }

        // 5. Массивные двойные двери (Mid Doors) с гладким вертикальным металлом и клёпками
        int[] door = texturesMip0[TEX_METAL_DOOR];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double sheen = Math.sin(x * 0.08) * 9.0 + (fbmSeamless(x, y) - 0.5) * 12.0;
                boolean stiles = x < 24 || x >= 232 || y < 20 || y >= 236 || (y >= 118 && y <= 138);
                int r = (int) (108 + sheen);
                int g = (int) (84 + sheen * 0.85);
                int b = (int) (64 + sheen * 0.7);
                if (stiles) {
                    r = (int) (68 + sheen * 0.5);
                    g = (int) (64 + sheen * 0.5);
                    b = (int) (62 + sheen * 0.5);
                }
                door[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 6. Чистый белый текстурный буфер для гладкого Гуро-рендера
        Arrays.fill(texturesMip0[TEX_SOLID], 0xFFFFFF);

        // 7. Тактическая разметка Bombsite A / B
        int[] site = texturesMip0[TEX_BOMBSITE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean border = x < 18 || x >= 238 || y < 18 || y >= 238;
                boolean chevron = border && (((x + y) / 24) & 1) == 0;
                double n = (fbmSeamless(x, y) - 0.5) * 14.0;
                if (chevron) {
                    site[y * TEX_SIZE + x] = packRgb(224, 68, 48);
                } else if (border) {
                    site[y * TEX_SIZE + x] = packRgb(46, 44, 42);
                } else {
                    site[y * TEX_SIZE + x] = packRgb((int) (206 + n), (int) (176 + n), (int) (128 + n));
                }
            }
        }

        // 8. Марокканская штукатурка Mirage с гладким лазурно-золотым мозаичным фризом
        int[] plaster = texturesMip0[TEX_MIRAGE_PLASTER];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 16.0;
                int r = (int) (232 + n);
                int g = (int) (182 + n * 0.85);
                int b = (int) (144 + n * 0.7);

                if (y >= 26 && y <= 46) {
                    boolean pat = (((x / 12) + ((y - 26) / 10)) & 1) == 0;
                    if (pat) { r = 34; g = 114; b = 158; }
                    else { r = 240; g = 198; b = 96; }
                } else if (y > 210) {
                    r -= 32; g -= 34; b -= 28;
                }
                plaster[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 9. Тёплая дворцовая плитка Mirage
        int[] mTile = texturesMip0[TEX_MIRAGE_TILE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int tx = x & 63;
                int ty = y & 63;
                double joint = Math.min(smoothBevel(Math.min(tx, 63 - tx), 3.2),
                                        smoothBevel(Math.min(ty, 63 - ty), 3.2));
                boolean warm = (((x >> 6) + (y >> 6)) & 1) == 0;
                double n = (fbmSeamless(x, y) - 0.5) * 14.0;
                int r = (int) ((warm ? 222 : 208) + n - (1.0 - joint) * 34.0);
                int g = (int) ((warm ? 194 : 182) + n - (1.0 - joint) * 30.0);
                int b = (int) ((warm ? 154 : 146) + n - (1.0 - joint) * 24.0);
                mTile[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 10. Стальные стеновые панели АЭС Nuke
        int[] nukeWall = texturesMip0[TEX_NUKE_PANEL];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 10.0;
                double seam = Math.min(smoothBevel(Math.min(x & 127, 127 - (x & 127)), 3.0),
                                       smoothBevel(Math.min(y & 255, 255 - (y & 255)), 3.0));
                int r = (int) (188 + n - (1.0 - seam) * 42.0);
                int g = (int) (196 + n - (1.0 - seam) * 42.0);
                int b = (int) (208 + n - (1.0 - seam) * 42.0);

                if (y >= 192 && y <= 212) {
                    boolean hz = (((x + y) / 18) & 1) == 0;
                    r = hz ? 236 : 36;
                    g = hz ? 188 : 40;
                    b = hz ? 38  : 46;
                } else if (y > 212) {
                    r = 56; g = 88; b = 128;
                }
                nukeWall[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 11. Индустриальный бетонный пол Nuke
        int[] nukeFloor = texturesMip0[TEX_NUKE_FLOOR];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int tx = x & 63;
                int ty = y & 63;
                double joint = Math.min(smoothBevel(Math.min(tx, 63 - tx), 2.8),
                                        smoothBevel(Math.min(ty, 63 - ty), 2.8));
                double n = (fbmSeamless(x, y) - 0.5) * 12.0;
                int v = (int) (132 + n - (1.0 - joint) * 24.0);
                nukeFloor[y * TEX_SIZE + x] = packRgb(v - 4, v, v + 8);
            }
        }

        // 12. Европейская каменная брусчатка площади Inferno (256x256)
        int[] cobble = texturesMip0[TEX_INFERNO_COBBLE];
        for (int y = 0; y < TEX_SIZE; y++) {
            int row = y / 32;
            int off = (row & 1) * 24;
            int inY = y & 31;
            for (int x = 0; x < TEX_SIZE; x++) {
                int sx = (x + off) % 48;
                double bevel = Math.min(smoothBevel(Math.min(inY, 31 - inY), 4.2),
                                        smoothBevel(Math.min(sx, 47 - sx), 4.2));
                int stoneTint = (((row * 53 + ((x + off) / 48) * 97) & 15) - 7);
                double n = (fbmSeamless(x, y) - 0.5) * 16.0;
                int r = (int) (168 + stoneTint + n - (1.0 - bevel) * 46.0);
                int g = (int) (160 + stoneTint + n - (1.0 - bevel) * 44.0);
                int b = (int) (150 + stoneTint + n - (1.0 - bevel) * 40.0);
                cobble[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 13. Итальянская кремово-терракотовая штукатурка вилл Inferno с каменным цоколем
        int[] stucco = texturesMip0[TEX_INFERNO_STUCCO];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double n = (fbmSeamless(x, y) - 0.5) * 18.0;
                int r = (int) (238 + n);
                int g = (int) (214 + n * 0.9);
                int b = (int) (176 + n * 0.75);
                if (y < 18) {
                    r = 166; g = 88; b = 58;
                } else if (y > 206) {
                    int bx = x & 63;
                    double j = smoothBevel(Math.min(bx, 63 - bx), 3.0);
                    r = (int) (152 + n * 0.5 - (1.0 - j) * 22.0);
                    g = (int) (142 + n * 0.5 - (1.0 - j) * 20.0);
                    b = (int) (130 + n * 0.5 - (1.0 - j) * 18.0);
                }
                stucco[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 14. Средиземноморская черепица крыш и навесов Inferno
        int[] roof = texturesMip0[TEX_ROOF_TILES];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double wave = Math.sin((x / 32.0) * Math.PI * 2.0) * 18.0;
                int rowSeam = y & 31;
                double shadow = smoothBevel(rowSeam, 5.0);
                double n = (fbmSeamless(x, y) - 0.5) * 12.0;
                int r = (int) ((192 + wave + n) * (0.76 + 0.24 * shadow));
                int g = (int) ((94 + wave * 0.5 + n) * (0.76 + 0.24 * shadow));
                int b = (int) ((62 + wave * 0.3 + n) * (0.76 + 0.24 * shadow));
                roof[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 15. Прозрачно-бирюзовая вода городского фонтана Inferno с солнечными бликами каустики
        int[] water = texturesMip0[TEX_WATER];
        for (int y = 0; y < TEX_SIZE; y++) {
            double v = (y / (double) TEX_SIZE) * Math.PI * 2.0;
            for (int x = 0; x < TEX_SIZE; x++) {
                double u = (x / (double) TEX_SIZE) * Math.PI * 2.0;
                double caustic = Math.sin(u * 4.0 + Math.cos(v * 3.0) * 1.8)
                               + Math.cos(v * 5.0 - Math.sin(u * 3.0) * 1.8);
                double spec = Math.max(0.0, caustic - 0.55) * 48.0;
                int r = (int) (46 + spec * 1.2);
                int g = (int) (164 + caustic * 12.0 + spec);
                int b = (int) (208 + caustic * 14.0 + spec * 0.9);
                water[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }
    }

    private void buildMipmapPyramids() {
        for (int t = 0; t < NUM_TEXTURES; t++) {
            downsample2x(texturesMip0[t], 256, texturesMip1[t], 128);
            downsample2x(texturesMip1[t], 128, texturesMip2[t], 64);
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
