import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;

/**
 * Программный 3D-движок CS-2 JAVA 2.0:
 * - Масштабируемое разрешение рендера (480p .. 960p) и динамический FOV (прицел AWP / ADS)
 * - Детализированные процедурные текстуры 128x128 с запеченным Ambient Occlusion на швах
 * - 3 режима времени суток (Полдень, Золотой закат, Ночная операция) и плывущие облака
 * - Реалистичные солнечные проекционные тени на песке без двойного затенения и Z-fighting
 * - Рендер декалей пуль, 3D-гильз, объемного дыма и поддержка левой/правой руки (cl_righthand)
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

    private static final int TEX_SIZE = 128;
    private static final int TEX_MASK = TEX_SIZE - 1;
    private static final int TEX_SHIFT = 7;
    private static final double NEAR_Z = 0.10;

    private int width;
    private int height;
    private double halfW;
    private double halfH;
    private double focalLength;

    private BufferedImage frameImage;
    private int[] pixels;
    private float[] zBuffer;
    private byte[] shadowStamp;
    private final int[][] textures;

    // Параметры камеры
    private double camX, camY, camZ;
    private double cosYaw, sinYaw;
    private double cosPitch, sinPitch;

    // Освещение, атмосфера и флаги графики
    private Vec3 sunDir = new Vec3(0.46, 0.76, -0.45).normalize();
    private int skyZenithColor = 0x2563AE;
    private int skyHorizonColor = 0xECD5AA;
    private int sunDiscColor = 0xFFF9E2;
    private int fogColor = 0xDFC79A;
    private int lightTintColor = 0xFFFFFF;
    private double ambientStrength = 0.54;
    private double diffuseStrength = 0.46;
    private double fogStart = 28.0;
    private double fogEnd = 74.0;

    private boolean fogEnabled = true;
    private boolean skyCloudsEnabled = true;
    private boolean leftHandedView = false;
    private double cloudTime = 0.0;
    private final int[] cloudLut = new int[256 * 256];

    // Предварительно выделенные буферы отсечения Сазерленда-Ходжмана
    private final double[] clipInX = new double[8];
    private final double[] clipInY = new double[8];
    private final double[] clipInZ = new double[8];
    private final double[] clipInU = new double[8];
    private final double[] clipInV = new double[8];

    private final double[] clipOutX = new double[8];
    private final double[] clipOutY = new double[8];
    private final double[] clipOutZ = new double[8];
    private final double[] clipOutU = new double[8];
    private final double[] clipOutV = new double[8];

    private final double[] projX = new double[8];
    private final double[] projY = new double[8];
    private final double[] projInvZ = new double[8];
    private final double[] projUZ = new double[8];
    private final double[] projVZ = new double[8];

    public Renderer3D(int width, int height, double verticalFovDegrees) {
        this.textures = new int[8][TEX_SIZE * TEX_SIZE];
        generateRealisticTextures();
        generateCloudLut();
        ensureViewport(width, height, verticalFovDegrees);
        applyTimeOfDay(GameSettings.TimeOfDay.DAY);
    }

    private void generateCloudLut() {
        for (int y = 0; y < 256; y++) {
            double v = (y / 256.0) * Math.PI * 2.0;
            for (int x = 0; x < 256; x++) {
                double u = (x / 256.0) * Math.PI * 2.0;
                double cVal = Math.sin(u * 2.0 + Math.cos(v * 2.0))
                            + 0.55 * Math.cos(u * 4.0 - v * 3.0)
                            + 0.25 * Math.sin(u * 7.0 + v * 5.0);
                int alpha = 0;
                if (cVal > 0.45) {
                    alpha = Math.min(115, (int) ((cVal - 0.45) * 95.0));
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
                ambientStrength = 0.46;
                diffuseStrength = 0.56;
                fogStart = 24.0;
                fogEnd = 68.0;
                break;
            case NIGHT:
                sunDir = new Vec3(-0.42, 0.72, 0.55).normalize();
                skyZenithColor = 0x090F1E;
                skyHorizonColor = 0x1A2946;
                sunDiscColor = 0xD8E8FF;
                fogColor = 0x131E35;
                lightTintColor = 0x90AED8;
                ambientStrength = 0.34;
                diffuseStrength = 0.36;
                fogStart = 18.0;
                fogEnd = 56.0;
                break;
            case DAY:
            default:
                sunDir = new Vec3(0.46, 0.76, -0.45).normalize();
                skyZenithColor = 0x2462AD;
                skyHorizonColor = 0xECD6AC;
                sunDiscColor = 0xFFF9E2;
                fogColor = 0xDFC79A;
                lightTintColor = 0xFFFDF8;
                ambientStrength = 0.55;
                diffuseStrength = 0.45;
                fogStart = 28.0;
                fogEnd = 74.0;
                break;
        }
    }

    public void setGraphicsFlags(boolean fog, boolean skyClouds, boolean leftHanded, double animTime) {
        this.fogEnabled = fog;
        this.skyCloudsEnabled = skyClouds;
        this.leftHandedView = leftHanded;
        this.cloudTime = animTime;
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
     * Очищает Z-буфер, маску теней и рисует атмосферное небо с облаками и светилом.
     */
    public void beginFrame() {
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);
        Arrays.fill(shadowStamp, (byte) 0);

        double srx = sunDir.x * cosYaw - sunDir.z * sinYaw;
        double srz1 = sunDir.x * sinYaw + sunDir.z * cosYaw;
        double scy = sunDir.y * cosPitch - srz1 * sinPitch;
        double scz = sunDir.y * sinPitch + srz1 * cosPitch;
        boolean sunInFront = scz > 0.05;
        double sunScreenX = sunInFront ? (halfW + (srx / scz) * focalLength) : -9999.0;
        double sunScreenY = sunInFront ? (halfH - (scy / scz) * focalLength) : -9999.0;

        double maxGlowR = height * 0.36;
        double maxGlowR2 = maxGlowR * maxGlowR;

        for (int y = 0; y < height; y++) {
            double screenSlopeY = (halfH - y) / focalLength;
            double rayElev = screenSlopeY * cosPitch + sinPitch;
            int rowBaseColor;
            if (rayElev >= 0.0) {
                double t = Math.min(1.0, Math.pow(rayElev, 0.60));
                rowBaseColor = lerpColor(skyHorizonColor, skyZenithColor, t);
            } else {
                double t = Math.min(1.0, -rayElev * 2.5);
                rowBaseColor = lerpColor(fogColor, shadeColor(fogColor, 0.74), t);
            }

            int rowOffset = y * width;
            boolean checkClouds = skyCloudsEnabled && rayElev > 0.06;
            boolean checkSun = skyCloudsEnabled && sunInFront && Math.abs(y - sunScreenY) <= maxGlowR;

            if (!checkClouds && !checkSun) {
                Arrays.fill(pixels, rowOffset, rowOffset + width, rowBaseColor);
                continue;
            }

            double dy = y - sunScreenY;
            double dy2 = dy * dy;
            double invFocal = 1.0 / focalLength;
            double czRow = -screenSlopeY * sinPitch + cosPitch;
            double invElevScale = 42.0 / (rayElev + 0.09);
            int cloudFade = Math.min(256, (int) (rayElev * 620.0));
            int cloudTint = lerpColor(skyHorizonColor, 0xFFFFFF, 0.58);
            double shiftU = cloudTime * 3.2;
            double shiftV = cloudTime * 1.6;
            double invMaxGlowR2 = 1.0 / maxGlowR2;
            double coreR2 = maxGlowR2 * 0.0072;

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

    /**
     * Рисует плоскую разметочную зону на полу/платформе (например, плент A / плент B).
     */
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

    /**
     * Рисует солнечную тень от 3D-блока на полу y = 0 без двойного затемнения.
     */
    public void drawBoxSunShadow(Vec3.Box b, double mapMinX, double mapMaxX, double mapMinZ, double mapMaxZ) {
        if (!b.castsShadow || b.maxY <= 0.05 || sunDir.y <= 0.15) return;
        // Внешние стены периметра слишком длинные — пропускаем, оставляя внутренние стены, колонны и ящики
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
        // Сдвинутая крыша тени + боковые шлейфы от основания блока к крыше
        drawWorldQuad(sx0, y, sz0, 0, 1, sx1, y, sz0, 1, 1, sx1, y, sz1, 1, 0, sx0, y, sz1, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
        drawWorldQuad(bx0, y, bz0, 0, 1, bx1, y, bz0, 1, 1, sx1, y, sz0, 1, 0, sx0, y, sz0, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
        drawWorldQuad(bx1, y, bz1, 0, 1, bx0, y, bz1, 1, 1, sx0, y, sz1, 1, 0, sx1, y, sz1, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
        drawWorldQuad(bx0, y, bz1, 0, 1, bx0, y, bz0, 1, 1, sx0, y, sz0, 1, 0, sx0, y, sz1, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
        drawWorldQuad(bx1, y, bz0, 0, 1, bx1, y, bz1, 1, 1, sx1, y, sz1, 1, 0, sx1, y, sz0, 0, 0, TEX_SOLID, 0xFFFFFF, false, false, 1);
    }

    /**
     * Мягкая контактная тень под ногами персонажа или ящика на любой высоте groundY.
     */
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

    /**
     * Рисует декаль пробоины от пули на поверхности стены/ящика/пола.
     */
    public void drawDecal(Vec3.Decal d) {
        Vec3 n = d.normal;
        Vec3 tangent;
        if (Math.abs(n.y) > 0.8) {
            tangent = new Vec3(1.0, 0.0, 0.0);
        } else {
            tangent = new Vec3(-n.z, 0.0, n.x).normalize();
        }
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

    /**
     * Рисует объемное полупрозрачное облако дыма CS2 с проверкой Z-буфера.
     */
    public void drawSmokeCloud(Vec3.SmokeCloud cloud) {
        double radius = cloud.getCurrentRadius();
        if (radius <= 0.15) return;

        // Несколько объемных клубов внутри радиуса облака
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

    private void drawTranslucentSphereSprite(double wx, double wy, double wz, double worldRadius, int rgb, double alpha) {
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;

        double rx = dx * cosYaw - dz * sinYaw;
        double rz1 = dx * sinYaw + dz * cosYaw;
        double cy = dy * cosPitch - rz1 * sinPitch;
        double cz = dy * sinPitch + rz1 * cosPitch;

        if (cz <= 0.25) {
            if (dx * dx + dy * dy + dz * dz < worldRadius * worldRadius * 1.3) {
                // Игрок прямо внутри облака дыма — мягкая дымовая пелена
                blendFullScreen(rgb, 0.26);
            }
            return;
        }

        int sx = (int) (halfW + (rx / cz) * focalLength);
        int sy = (int) (halfH - (cy / cz) * focalLength);
        int screenR = (int) ((worldRadius / cz) * focalLength);
        if (screenR < 2) return;
        if (screenR > width) screenR = width;

        int x0 = Math.max(0, sx - screenR);
        int x1 = Math.min(width - 1, sx + screenR);
        int y0 = Math.max(0, sy - screenR);
        int y1 = Math.min(height - 1, sy + screenR);

        int sR = (rgb >> 16) & 0xFF;
        int sG = (rgb >> 8) & 0xFF;
        int sB = rgb & 0xFF;
        double invR2 = 1.0 / ((double) screenR * screenR);
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
                    int a = (int) ((1.0 - r2) * alpha * 220.0);
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

    /**
     * Отрисовка огненной зоны Молотова CS2 (горящее пятно на земле + пляшущие языки 3D-пламени).
     */
    public void drawFireZone(Vec3.FireZone fz) {
        double rad = fz.getCurrentRadius();
        if (rad < 0.25) return;

        double y = fz.center.y + 0.015;
        double rIn = rad * 0.78;
        drawWorldQuad(
            fz.center.x - rIn, y, fz.center.z + rIn, 0, 0,
            fz.center.x + rIn, y, fz.center.z + rIn, 1, 0,
            fz.center.x + rIn, y, fz.center.z - rIn, 1, 1,
            fz.center.x - rIn, y, fz.center.z - rIn, 0, 1,
            TEX_SOLID, 0xCC3B0E, false, true, 0
        );

        for (int i = 0; i < 16; i++) {
            double ang = i * 2.39996 + fz.animPhase * 0.14;
            double dist = ((i + 0.5) / 16.0) * rad * 0.88;
            double fx = fz.center.x + Math.cos(ang) * dist;
            double fzPos = fz.center.z + Math.sin(ang) * dist;
            double flicker = 0.55 + 0.45 * Math.sin(fz.animPhase * 1.4 + i * 1.7);
            double h = (0.34 + (1.0 - dist / (rad + 0.1)) * 0.48) * flicker;
            int flameCol = (i % 3 == 0) ? 0xFFE35C : ((i % 2 == 0) ? 0xFF7B1A : 0xF0380E);
            drawOrientedWorldBox(fx, y + h * 0.5, fzPos, 0.22, h, 0.22,
                    ang + fz.animPhase * 0.4, 0.0, TEX_SOLID, flameCol);
        }
    }

    /**
     * Направленный конус тактического фонарика игрока (клавиша L) — освещает стены, пол и ботов в 3D.
     */
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
                if (z >= 29.0f) continue;

                double radial = 1.0 - d2 * invConeR2;
                double distAtten = 1.0 - (z / 29.0);
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
        double dx = p.x - camX;
        double dy = p.y - camY;
        double dz = p.z - camZ;

        double rx = dx * cosYaw - dz * sinYaw;
        double rz1 = dx * sinYaw + dz * cosYaw;
        double cy = dy * cosPitch - rz1 * sinPitch;
        double cz = dy * sinPitch + rz1 * cosPitch;
        if (cz <= NEAR_Z) return;

        int sx = (int) (halfW + (rx / cz) * focalLength);
        int sy = (int) (halfH - (cy / cz) * focalLength);
        int halfRad = Math.max(1, (int) ((p.size / cz) * focalLength * 0.5));
        if (halfRad > 22) halfRad = 22;

        int x0 = Math.max(0, sx - halfRad);
        int x1 = Math.min(width - 1, sx + halfRad);
        int y0 = Math.max(0, sy - halfRad);
        int y1 = Math.min(height - 1, sy + halfRad);

        float fz = (float) cz;
        for (int y = y0; y <= y1; y++) {
            int row = y * width;
            for (int x = x0; x <= x1; x++) {
                int idx = row + x;
                if (fz < zBuffer[idx]) {
                    zBuffer[idx] = fz;
                    pixels[idx] = p.color;
                }
            }
        }
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

    private void drawCameraQuad(
            double cx0, double cy0, double cz0, double u0, double v0,
            double cx1, double cy1, double cz1, double u1, double v1,
            double cx2, double cy2, double cz2, double u2, double v2,
            double cx3, double cy3, double cz3, double u3, double v3,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog, int renderMode) {

        if (cz0 < NEAR_Z && cz1 < NEAR_Z && cz2 < NEAR_Z && cz3 < NEAR_Z) return;

        clipInX[0] = cx0; clipInY[0] = cy0; clipInZ[0] = cz0; clipInU[0] = u0; clipInV[0] = v0;
        clipInX[1] = cx1; clipInY[1] = cy1; clipInZ[1] = cz1; clipInU[1] = u1; clipInV[1] = v1;
        clipInX[2] = cx2; clipInY[2] = cy2; clipInZ[2] = cz2; clipInU[2] = u2; clipInV[2] = v2;
        clipInX[3] = cx3; clipInY[3] = cy3; clipInZ[3] = cz3; clipInU[3] = u3; clipInV[3] = v3;

        int outCount = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            double zA = clipInZ[i];
            double zB = clipInZ[j];
            boolean inA = zA >= NEAR_Z;
            boolean inB = zB >= NEAR_Z;

            if (inA) {
                clipOutX[outCount] = clipInX[i];
                clipOutY[outCount] = clipInY[i];
                clipOutZ[outCount] = clipInZ[i];
                clipOutU[outCount] = clipInU[i];
                clipOutV[outCount] = clipInV[i];
                outCount++;
            }
            if (inA != inB) {
                double t = (NEAR_Z - zA) / (zB - zA);
                clipOutX[outCount] = clipInX[i] + (clipInX[j] - clipInX[i]) * t;
                clipOutY[outCount] = clipInY[i] + (clipInY[j] - clipInY[i]) * t;
                clipOutZ[outCount] = NEAR_Z;
                clipOutU[outCount] = clipInU[i] + (clipInU[j] - clipInU[i]) * t;
                clipOutV[outCount] = clipInV[i] + (clipInV[j] - clipInV[i]) * t;
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
        }

        for (int i = 1; i < outCount - 1; i++) {
            rasterizeTriangle(
                projX[0], projY[0], projInvZ[0], projUZ[0], projVZ[0],
                projX[i], projY[i], projInvZ[i], projUZ[i], projVZ[i],
                projX[i + 1], projY[i + 1], projInvZ[i + 1], projUZ[i + 1], projVZ[i + 1],
                textures[texId], shadeColor, cullBackface, applyFog, renderMode
            );
        }
    }

    /**
     * renderMode: 0 = стандартный текстурированный полигон с Z-буфером,
     *             1 = проекционная тень на земле с защитой от двойного затемнения (shadowStamp).
     */
    private void rasterizeTriangle(
            double x0, double y0, double iz0, double uz0, double vz0,
            double x1, double y1, double iz1, double uz1, double vz1,
            double x2, double y2, double iz2, double uz2, double vz2,
            int[] tex, int shadeColor, boolean cullBackface, boolean applyFog, int renderMode) {

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
                                    int r = (((c >> 16) & 0xFF) * 148) >> 8;
                                    int g = (((c >> 8) & 0xFF) * 154) >> 8;
                                    int b = ((c & 0xFF) * 170) >> 8;
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

        int sR = (shadeColor >> 16) & 0xFF;
        int sG = (shadeColor >> 8) & 0xFF;
        int sB = shadeColor & 0xFF;

        int fogR = (fogColor >> 16) & 0xFF;
        int fogG = (fogColor >> 8) & 0xFF;
        int fogB = fogColor & 0xFF;

        for (int y = minY; y <= maxY; y++) {
            double w0 = w0Row, w1 = w1Row, w2 = w2Row;
            int rowIdx = y * width;

            for (int x = minX; x <= maxX; x++) {
                if (w0 >= -1e-5 && w1 >= -1e-5 && w2 >= -1e-5) {
                    double invZ = w0 * iz0 + w1 * iz1 + w2 * iz2;
                    if (invZ > 1e-6) {
                        float z = (float) (1.0 / invZ);
                        int idx = rowIdx + x;
                        if (z <= zBuffer[idx] + 1e-4f) {
                            zBuffer[idx] = z;

                            double u = (w0 * uz0 + w1 * uz1 + w2 * uz2) * z;
                            double v = (w0 * vz0 + w1 * vz1 + w2 * vz2) * z;

                            int tx = ((int) Math.floor(u * TEX_SIZE)) & TEX_MASK;
                            int ty = ((int) Math.floor(v * TEX_SIZE)) & TEX_MASK;
                            int texel = tex[(ty << TEX_SHIFT) | tx];

                            int r = (((texel >> 16) & 0xFF) * sR) >> 8;
                            int g = (((texel >> 8) & 0xFF) * sG) >> 8;
                            int b = ((texel & 0xFF) * sB) >> 8;

                            if (applyFog && z > fogStart) {
                                int fogFactor = (int) Math.min(235.0, ((z - fogStart) / (fogEnd - fogStart)) * 235.0);
                                r = (r * (256 - fogFactor) + fogR * fogFactor) >> 8;
                                g = (g * (256 - fogFactor) + fogG * fogFactor) >> 8;
                                b = (b * (256 - fogFactor) + fogB * fogFactor) >> 8;
                            }

                            pixels[idx] = (r << 16) | (g << 8) | b;
                        }
                    }
                }
                w0 += w0StepX;
                w1 += w1StepX;
                w2 += w2StepX;
            }

            w0Row += w0StepY;
            w1Row += w1StepY;
            w2Row += w2StepY;
        }
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

    /**
     * Генерация реалистичных текстур высокого разрешения 128x128 с микрорельефом,
     * фасками каменной кладки и Ambient Occlusion по краям.
     */
    private void generateRealisticTextures() {
        Random rng = new Random(20261121L);

        // 0. Реалистичный песчаник и пустынная брусчатка пола Dust II (128x128)
        int[] sand = textures[TEX_FLOOR_SAND];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double dunes = Math.sin(x * 0.11 + Math.cos(y * 0.08) * 1.6) * 7.0
                             + Math.cos(y * 0.14 - x * 0.05) * 5.0;
                int fineNoise = rng.nextInt(15) - 7;
                boolean pebble = rng.nextInt(48) == 0;

                int tileX = x % 64;
                int tileY = y % 64;
                boolean mortar = tileX <= 1 || tileY <= 1;
                boolean highlight = (tileX == 2 || tileY == 2);

                int r = 216 + (int) dunes + fineNoise;
                int g = 188 + (int) (dunes * 0.88) + fineNoise;
                int b = 136 + (int) (dunes * 0.68) + fineNoise;

                if (mortar) {
                    r -= 28; g -= 25; b -= 18;
                } else if (highlight) {
                    r += 10; g += 9; b += 6;
                }
                if (pebble) {
                    r -= 24; g -= 22; b -= 18;
                }
                sand[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 1. Кладка стен из тесаного известняка/песчаника с объемной фаской и трещинами
        int[] wall = textures[TEX_WALL_SANDSTONE];
        for (int y = 0; y < TEX_SIZE; y++) {
            int row = y / 32;
            int offset = (row & 1) * 32;
            int inRowY = y % 32;
            for (int x = 0; x < TEX_SIZE; x++) {
                int sx = (x + offset) % 64;
                int brickHash = ((row * 37 + ((x + offset) / 64) * 73) & 31) - 15;
                double weather = Math.sin(x * 0.09 + y * 0.05) * 6.0;
                int grain = rng.nextInt(15) - 7;

                int r = 214 + brickHash / 2 + (int) weather + grain;
                int g = 182 + brickHash / 2 + (int) (weather * 0.9) + grain;
                int b = 128 + brickHash / 3 + (int) (weather * 0.7) + grain;

                // Глубокий шов раствора + световая и теневая фаска камня
                if (inRowY <= 2 || sx <= 2) {
                    r -= 48; g -= 42; b -= 32;
                } else if (inRowY == 3 || sx == 3) {
                    r += 15; g += 13; b += 10;
                } else if (inRowY >= 30 || sx >= 62) {
                    r -= 20; g -= 18; b -= 14;
                }

                // Мягкое запыление/AO у нижнего края текстуры
                if (y > 108) {
                    int ao = (y - 108) * 1;
                    r -= ao; g -= ao; b -= ao;
                }
                wall[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 2. Детализированный деревянный ящик со скобами, волокнами древесины и AO
        int[] wood = textures[TEX_CRATE_WOOD];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean frame = x < 12 || x >= 116 || y < 12 || y >= 116;
                boolean diag = Math.abs(x - y) <= 6 || Math.abs((TEX_SIZE - 1 - x) - y) <= 6;
                boolean bracket = (x < 16 || x >= 112) && (y < 16 || y >= 112);

                double fiber = Math.sin(x * 0.55 + Math.sin(y * 0.14) * 2.2) * 8.0;
                int grain = rng.nextInt(11) - 5;

                int r = 182 + (int) fiber + grain;
                int g = 136 + (int) (fiber * 0.8) + grain;
                int b = 78  + (int) (fiber * 0.5) + grain / 2;

                if (bracket) {
                    r = 62 + grain; g = 64 + grain; b = 68 + grain;
                } else if (frame || diag) {
                    r = 148 + (int) (fiber * 0.6) + grain;
                    g = 108 + (int) (fiber * 0.5) + grain;
                    b = 58  + grain / 2;
                    if (x == 12 || y == 12 || x == 115 || y == 115) {
                        r -= 28; g -= 24; b -= 18;
                    }
                } else if (y % 22 == 0) {
                    r -= 40; g -= 32; b -= 22;
                }

                // Контактный AO по периметру грани ящика
                int edgeDist = Math.min(Math.min(x, 127 - x), Math.min(y, 127 - y));
                if (edgeDist < 5) {
                    int ao = (5 - edgeDist) * 7;
                    r -= ao; g -= ao; b -= ao;
                }
                wood[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 3. Армейский оружейный контейнер (оливковый металл, замки, трафарет)
        int[] mil = textures[TEX_CRATE_MILITARY];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean border = x < 10 || x >= 118 || y < 10 || y >= 118;
                boolean rib = (y >= 34 && y <= 38) || (y >= 88 && y <= 92);
                boolean hazard = (y >= 56 && y <= 68) && (x >= 24 && x <= 104) && (((x + y) / 6) % 2 == 0);
                int grain = rng.nextInt(11) - 5;

                int r = 88 + grain;
                int g = 102 + grain;
                int b = 68 + grain;
                if (border || rib) {
                    r = 58 + grain; g = 66 + grain; b = 52 + grain;
                } else if (hazard) {
                    r = 216 + grain; g = 178 + grain; b = 56;
                }
                mil[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 4. Оштукатуренный бетон и каменный карниз
        int[] conc = textures[TEX_CONCRETE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                double mottle = Math.sin(x * 0.12) * 4.0 + Math.cos(y * 0.15) * 4.0;
                int grain = rng.nextInt(13) - 6;
                boolean trim = (y >= 8 && y <= 11) || (y >= 116 && y <= 119);
                int r = 208 + (int) mottle + grain;
                int g = 194 + (int) mottle + grain;
                int b = 166 + (int) mottle + grain;
                if (trim) { r -= 30; g -= 28; b -= 24; }
                conc[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 5. Металлические "Двойные двери" Мида с заклепками и потертостями
        int[] door = textures[TEX_METAL_DOOR];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean frame = x < 10 || x >= 118 || y < 10 || y >= 118 || (y >= 60 && y <= 67);
                boolean rivet = (x == 5 || x == 122 || y == 5 || y == 122) && ((x + y) % 16 < 4);
                double wear = Math.sin(y * 0.22 + x * 0.05) * 6.0;
                int grain = rng.nextInt(14) - 7;

                int r = 108 + (int) wear + grain;
                int g = 88  + (int) wear + grain;
                int b = 70  + (int) wear + grain;
                if (rivet) {
                    r = 162; g = 148; b = 132;
                } else if (frame) {
                    r = 68 + grain; g = 58 + grain; b = 50 + grain;
                }
                door[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 6. Матовая поверхность для 3D-оружия и экипировки персонажей
        int[] solid = textures[TEX_SOLID];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int v = 247 + (rng.nextInt(9) - 4);
                solid[y * TEX_SIZE + x] = packRgb(v, v, v);
            }
        }

        // 7. Разметка зоны Плента (красно-оранжевый пунктирный периметр на камне)
        int[] site = textures[TEX_BOMBSITE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int base = sand[y * TEX_SIZE + x];
                boolean border = (x < 9 || x >= 119 || y < 9 || y >= 119) && (((x + y) / 10) % 2 == 0);
                boolean cross = (Math.abs(x - 64) <= 4 && Math.abs(y - 64) <= 22)
                             || (Math.abs(y - 64) <= 4 && Math.abs(x - 64) <= 22);
                if (border || cross) {
                    site[y * TEX_SIZE + x] = lerpColor(base, 0xD63B2F, 0.72);
                } else {
                    site[y * TEX_SIZE + x] = base;
                }
            }
        }
    }

    private static int packRgb(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (r << 16) | (g << 8) | b;
    }
}
