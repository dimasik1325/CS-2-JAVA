import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;

/**
 * Программный 3D-рендерер с Z-буфером, отсечением по ближней плоскости (Near-Plane Clipping),
 * перспективно-корректным текстурированием, направленным солнечным освещением и дымкой (Fog).
 * Не требует OpenGL/LWJGL и стабильно работает на любой стандартной JDK.
 */
public class Renderer3D {
    public static final int TEX_FLOOR_SAND = 0;
    public static final int TEX_WALL_SANDSTONE = 1;
    public static final int TEX_CRATE_WOOD = 2;
    public static final int TEX_CRATE_MILITARY = 3;
    public static final int TEX_CONCRETE = 4;
    public static final int TEX_METAL_DOOR = 5;
    public static final int TEX_SOLID = 6;

    private static final int TEX_SIZE = 64;
    private static final int TEX_MASK = TEX_SIZE - 1;
    private static final double NEAR_Z = 0.10;
    private static final double FOG_START = 26.0;
    private static final double FOG_END = 68.0;
    private static final int FOG_COLOR = 0xDFC79A;

    private final int width;
    private final int height;
    private final double halfW;
    private final double halfH;
    private final double focalLength;

    private final BufferedImage frameImage;
    private final int[] pixels;
    private final float[] zBuffer;
    private final int[][] textures;

    // Параметры текущей камеры
    private double camX, camY, camZ;
    private double cosYaw, sinYaw;
    private double cosPitch, sinPitch;

    // Направление солнца (нормализованное)
    private final Vec3 sunDir = new Vec3(0.46, 0.76, -0.45).normalize();

    // Предварительно выделенные буферы для отсечения полигонов без аллокаций в кадре
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
        this.width = width;
        this.height = height;
        this.halfW = width * 0.5;
        this.halfH = height * 0.5;
        this.focalLength = halfH / Math.tan(Math.toRadians(verticalFovDegrees) * 0.5);

        this.frameImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) frameImage.getRaster().getDataBuffer()).getData();
        this.zBuffer = new float[width * height];
        this.textures = new int[7][TEX_SIZE * TEX_SIZE];
        generateProceduralTextures();
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public BufferedImage getFrameImage() { return frameImage; }

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
     * Очищает буфер глубины и рисует процедурное пустынное небо Dust 2 с солнцем.
     */
    public void beginFrame() {
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        // Проекция солнца в пространство камеры для мягкого ореола в небе
        double srx = sunDir.x * cosYaw - sunDir.z * sinYaw;
        double srz1 = sunDir.x * sinYaw + sunDir.z * cosYaw;
        double scy = sunDir.y * cosPitch - srz1 * sinPitch;
        double scz = sunDir.y * sinPitch + srz1 * cosPitch;
        boolean sunInFront = scz > 0.05;
        double sunScreenX = sunInFront ? (halfW + (srx / scz) * focalLength) : -9999.0;
        double sunScreenY = sunInFront ? (halfH - (scy / scz) * focalLength) : -9999.0;

        for (int y = 0; y < height; y++) {
            double screenSlopeY = (halfH - y) / focalLength;
            // Примерная высота луча над горизонтом с учетом наклона камеры pitch
            double rayElev = screenSlopeY * cosPitch + sinPitch;
            int rowBaseColor;
            if (rayElev >= 0.0) {
                double t = Math.min(1.0, Math.pow(rayElev, 0.62));
                rowBaseColor = lerpColor(0xEAD4A8, 0x2F6FB8, t);
            } else {
                double t = Math.min(1.0, -rayElev * 2.5);
                rowBaseColor = lerpColor(0xDEC392, 0xB89762, t);
            }

            int rowOffset = y * width;
            if (!sunInFront || Math.abs(y - sunScreenY) > height * 0.36) {
                Arrays.fill(pixels, rowOffset, rowOffset + width, rowBaseColor);
            } else {
                double dy = (y - sunScreenY);
                double dy2 = dy * dy;
                double maxGlowR = height * 0.34;
                double maxGlowR2 = maxGlowR * maxGlowR;
                for (int x = 0; x < width; x++) {
                    double dx = x - sunScreenX;
                    double d2 = dx * dx + dy2;
                    if (d2 < maxGlowR2 && rayElev > -0.04) {
                        double norm = Math.sqrt(d2) / maxGlowR;
                        double glow = Math.pow(1.0 - norm, 3.2) * 0.78;
                        if (norm < 0.09) {
                            glow = Math.min(1.0, glow + 0.55);
                        }
                        pixels[rowOffset + x] = lerpColor(rowBaseColor, 0xFFF9E2, Math.min(1.0, glow));
                    } else {
                        pixels[rowOffset + x] = rowBaseColor;
                    }
                }
            }
        }
    }

    /**
     * Проецирует 3D-точку мира в экранные координаты [screenX, screenY, depthZ] (в масштабе буфера).
     * Возвращает true, если точка находится перед камерой.
     */
    public boolean projectToScreen(double wx, double wy, double wz, double[] outXYDepth) {
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;

        double rx = dx * cosYaw - dz * sinYaw;
        double rz1 = dx * sinYaw + dz * cosYaw;
        double cy = dy * cosPitch - rz1 * sinPitch;
        double cz = dy * sinPitch + rz1 * cosPitch;

        if (cz <= NEAR_Z) {
            return false;
        }
        outXYDepth[0] = halfW + (rx / cz) * focalLength;
        outXYDepth[1] = halfH - (cy / cz) * focalLength;
        outXYDepth[2] = cz;
        return true;
    }

    /**
     * Рисует горизонтальный пол арены, разбитый на тайлы для идеальной перспективы вблизи.
     */
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
                    texId, shadeColor, true, true
                );
            }
        }
    }

    /**
     * Рисует выровненный по осям 3D-блок мира (стену, ящик, платформу, колонну).
     */
    public void drawAxisAlignedBox(Vec3.Box b) {
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ;
        double x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        double s = b.uvScale;
        int tex = b.textureId;
        int base = b.tintColor;

        double wX = (x1 - x0) * s;
        double wY = (y1 - y0) * s;
        double wZ = (z1 - z0) * s;

        // Для ящиков используем 1 полный тайл на грань, если uvScale <= 0
        if (s <= 0.0) {
            wX = 1.0;
            wY = 1.0;
            wZ = 1.0;
        }

        // Южная грань (-Z)
        if (camZ < z0) {
            int col = applyDirectionalLight(base, 0.0, 0.0, -1.0);
            drawWorldQuad(
                x0, y0, z0, 0.0, wY,
                x1, y0, z0, wX,  wY,
                x1, y1, z0, wX,  0.0,
                x0, y1, z0, 0.0, 0.0,
                tex, col, true, true
            );
        }
        // Северная грань (+Z)
        if (camZ > z1) {
            int col = applyDirectionalLight(base, 0.0, 0.0, 1.0);
            drawWorldQuad(
                x1, y0, z1, 0.0, wY,
                x0, y0, z1, wX,  wY,
                x0, y1, z1, wX,  0.0,
                x1, y1, z1, 0.0, 0.0,
                tex, col, true, true
            );
        }
        // Западная грань (-X)
        if (camX < x0) {
            int col = applyDirectionalLight(base, -1.0, 0.0, 0.0);
            drawWorldQuad(
                x0, y0, z1, 0.0, wY,
                x0, y0, z0, wZ,  wY,
                x0, y1, z0, wZ,  0.0,
                x0, y1, z1, 0.0, 0.0,
                tex, col, true, true
            );
        }
        // Восточная грань (+X)
        if (camX > x1) {
            int col = applyDirectionalLight(base, 1.0, 0.0, 0.0);
            drawWorldQuad(
                x1, y0, z0, 0.0, wY,
                x1, y0, z1, wZ,  wY,
                x1, y1, z1, wZ,  0.0,
                x1, y1, z0, 0.0, 0.0,
                tex, col, true, true
            );
        }
        // Верхняя грань (+Y)
        if (camY > y1) {
            int col = applyDirectionalLight(base, 0.0, 1.0, 0.0);
            drawWorldQuad(
                x0, y1, z0, 0.0, wZ,
                x1, y1, z0, wX,  wZ,
                x1, y1, z1, wX,  0.0,
                x0, y1, z1, 0.0, 0.0,
                tex, col, true, true
            );
        }
        // Нижняя грань (-Y), только если блок висит над полом
        if (y0 > 0.01 && camY < y0) {
            int col = applyDirectionalLight(base, 0.0, -1.0, 0.0);
            drawWorldQuad(
                x0, y0, z1, 0.0, wZ,
                x1, y0, z1, wX,  wZ,
                x1, y0, z0, wX,  0.0,
                x0, y0, z0, 0.0, 0.0,
                tex, col, true, true
            );
        }
    }

    /**
     * Рисует произвольно повернутый 3D-параллелепипед в мировых координатах
     * (используется для частей тела ботов, их оружия и анимации падения).
     */
    public void drawOrientedWorldBox(double cx, double cy, double cz,
                                     double sx, double sy, double sz,
                                     double yaw, double pitchX,
                                     int texId, int baseColor) {
        double hx = sx * 0.5;
        double hy = sy * 0.5;
        double hz = sz * 0.5;

        double cyaw = Math.cos(yaw), syaw = Math.sin(yaw);
        double cp = Math.cos(pitchX), sp = Math.sin(pitchX);

        double[] vx = new double[8];
        double[] vy = new double[8];
        double[] vz = new double[8];

        double[] lx = {-hx,  hx,  hx, -hx, -hx,  hx,  hx, -hx};
        double[] ly = {-hy, -hy,  hy,  hy, -hy, -hy,  hy,  hy};
        double[] lz = {-hz, -hz, -hz, -hz,  hz,  hz,  hz,  hz};

        for (int i = 0; i < 8; i++) {
            // Локальный наклон вокруг X (pitchX), затем поворот вокруг Y (yaw)
            double py = ly[i] * cp - lz[i] * sp;
            double pz = ly[i] * sp + lz[i] * cp;

            double wx = lx[i] * cyaw + pz * syaw;
            double wz = -lx[i] * syaw + pz * cyaw;

            vx[i] = cx + wx;
            vy[i] = cy + py;
            vz[i] = cz + wz;
        }

        int cFront = shadeColor(baseColor, 0.92);
        int cBack  = shadeColor(baseColor, 0.78);
        int cSide  = shadeColor(baseColor, 0.84);
        int cTop   = shadeColor(baseColor, 1.05);
        int cBot   = shadeColor(baseColor, 0.62);

        // 6 граней (Z-буфер обеспечивает точное перекрытие при любом угле поворота)
        drawWorldQuad(vx[0], vy[0], vz[0], 0, 1, vx[1], vy[1], vz[1], 1, 1, vx[2], vy[2], vz[2], 1, 0, vx[3], vy[3], vz[3], 0, 0, texId, cBack,  false, true);
        drawWorldQuad(vx[5], vy[5], vz[5], 0, 1, vx[4], vy[4], vz[4], 1, 1, vx[7], vy[7], vz[7], 1, 0, vx[6], vy[6], vz[6], 0, 0, texId, cFront, false, true);
        drawWorldQuad(vx[4], vy[4], vz[4], 0, 1, vx[0], vy[0], vz[0], 1, 1, vx[3], vy[3], vz[3], 1, 0, vx[7], vy[7], vz[7], 0, 0, texId, cSide,  false, true);
        drawWorldQuad(vx[1], vy[1], vz[1], 0, 1, vx[5], vy[5], vz[5], 1, 1, vx[6], vy[6], vz[6], 1, 0, vx[2], vy[2], vz[2], 0, 0, texId, cSide,  false, true);
        drawWorldQuad(vx[3], vy[3], vz[3], 0, 1, vx[2], vy[2], vz[2], 1, 1, vx[6], vy[6], vz[6], 1, 0, vx[7], vy[7], vz[7], 0, 0, texId, cTop,   false, true);
        drawWorldQuad(vx[4], vy[4], vz[4], 0, 1, vx[5], vy[5], vz[5], 1, 1, vx[1], vy[1], vz[1], 1, 0, vx[0], vy[0], vz[0], 0, 0, texId, cBot,   false, true);
    }

    /**
     * Рисует 3D-блок непосредственно в локальном пространстве камеры (для модели оружия в руках!).
     * Координаты камеры: +X вправо, +Y вверх, +Z вперед вглубь экрана.
     */
    public void drawViewModelBox(double cx, double cy, double cz,
                                 double sx, double sy, double sz,
                                 double rotX, double rotY, double rotZ,
                                 double pivotX, double pivotY, double pivotZ,
                                 int texId, int baseColor) {
        double hx = sx * 0.5;
        double hy = sy * 0.5;
        double hz = sz * 0.5;

        double cxR = Math.cos(rotX), sxR = Math.sin(rotX);
        double cyR = Math.cos(rotY), syR = Math.sin(rotY);
        double czR = Math.cos(rotZ), szR = Math.sin(rotZ);

        double[] vx = new double[8];
        double[] vy = new double[8];
        double[] vz = new double[8];

        double[] lx = {-hx,  hx,  hx, -hx, -hx,  hx,  hx, -hx};
        double[] ly = {-hy, -hy,  hy,  hy, -hy, -hy,  hy,  hy};
        double[] lz = {-hz, -hz, -hz, -hz,  hz,  hz,  hz,  hz};

        for (int i = 0; i < 8; i++) {
            double px = cx + lx[i] - pivotX;
            double py = cy + ly[i] - pivotY;
            double pz = cz + lz[i] - pivotZ;

            // Вращение Z (крен при перезарядке)
            double x1 = px * czR - py * szR;
            double y1 = px * szR + py * czR;
            double z1 = pz;

            // Вращение X (подброс отдачи)
            double y2 = y1 * cxR - z1 * sxR;
            double z2 = y1 * sxR + z1 * cxR;

            // Вращение Y (взмах ножа / покачивание)
            double x3 = x1 * cyR + z2 * syR;
            double z3 = -x1 * syR + z2 * cyR;

            vx[i] = x3 + pivotX;
            vy[i] = y2 + pivotY;
            vz[i] = z3 + pivotZ;
        }

        int cTop   = shadeColor(baseColor, 1.12);
        int cSideL = shadeColor(baseColor, 0.94);
        int cSideR = shadeColor(baseColor, 0.80);
        int cFront = shadeColor(baseColor, 0.88);
        int cBot   = shadeColor(baseColor, 0.62);

        drawCameraQuad(vx[0], vy[0], vz[0], 0, 1, vx[1], vy[1], vz[1], 1, 1, vx[2], vy[2], vz[2], 1, 0, vx[3], vy[3], vz[3], 0, 0, texId, cFront, false, false);
        drawCameraQuad(vx[5], vy[5], vz[5], 0, 1, vx[4], vy[4], vz[4], 1, 1, vx[7], vy[7], vz[7], 1, 0, vx[6], vy[6], vz[6], 0, 0, texId, cFront, false, false);
        drawCameraQuad(vx[4], vy[4], vz[4], 0, 1, vx[0], vy[0], vz[0], 1, 1, vx[3], vy[3], vz[3], 1, 0, vx[7], vy[7], vz[7], 0, 0, texId, cSideL, false, false);
        drawCameraQuad(vx[1], vy[1], vz[1], 0, 1, vx[5], vy[5], vz[5], 1, 1, vx[6], vy[6], vz[6], 1, 0, vx[2], vy[2], vz[2], 0, 0, texId, cSideR, false, false);
        drawCameraQuad(vx[3], vy[3], vz[3], 0, 1, vx[2], vy[2], vz[2], 1, 1, vx[6], vy[6], vz[6], 1, 0, vx[7], vy[7], vz[7], 0, 0, texId, cTop,   false, false);
        drawCameraQuad(vx[4], vy[4], vz[4], 0, 1, vx[5], vy[5], vz[5], 1, 1, vx[1], vy[1], vz[1], 1, 0, vx[0], vy[0], vz[0], 0, 0, texId, cBot,   false, false);
    }

    /**
     * Рисует 3D-линию с проверкой Z-буфера (для трассеров пуль).
     */
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

    /**
     * Рисует 3D-частицу (искру, пыль, осколок) с учетом глубины Z-буфера.
     */
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
        if (halfRad > 18) halfRad = 18;

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

        double cx3 = (x3 - camX) * cosYaw - (z3 - camZ) * sinYaw;
        double rz3 = (x3 - camX) * sinYaw + (z3 - camZ) * cosYaw;
        double cy3 = (y3 - camY) * cosPitch - rz3 * sinPitch;
        double cz3 = (y3 - camY) * sinPitch + rz3 * cosPitch;

        drawCameraQuad(
            cx0, cy0, cz0, u0, v0,
            cx1, cy1, cz1, u1, v1,
            cx2, cy2, cz2, u2, v2,
            cx3, cy3, cz3, u3, v3,
            texId, shadeColor, cullBackface, applyFog
        );
    }

    private void drawCameraQuad(
            double cx0, double cy0, double cz0, double u0, double v0,
            double cx1, double cy1, double cz1, double u1, double v1,
            double cx2, double cy2, double cz2, double u2, double v2,
            double cx3, double cy3, double cz3, double u3, double v3,
            int texId, int shadeColor, boolean cullBackface, boolean applyFog) {

        if (cz0 < NEAR_Z && cz1 < NEAR_Z && cz2 < NEAR_Z && cz3 < NEAR_Z) {
            return;
        }

        clipInX[0] = cx0; clipInY[0] = cy0; clipInZ[0] = cz0; clipInU[0] = u0; clipInV[0] = v0;
        clipInX[1] = cx1; clipInY[1] = cy1; clipInZ[1] = cz1; clipInU[1] = u1; clipInV[1] = v1;
        clipInX[2] = cx2; clipInY[2] = cy2; clipInZ[2] = cz2; clipInU[2] = u2; clipInV[2] = v2;
        clipInX[3] = cx3; clipInY[3] = cy3; clipInZ[3] = cz3; clipInU[3] = u3; clipInV[3] = v3;

        // Отсечение Сазерленда-Ходжмана по ближней плоскости cz >= NEAR_Z
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

        if (outCount < 3) {
            return;
        }

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
                textures[texId], shadeColor, cullBackface, applyFog
            );
        }
    }

    /**
     * Высокоскоростная барицентрическая/сканлайн растеризация треугольника
     * с перспективно-корректными UV и проверкой Z-буфера.
     */
    private void rasterizeTriangle(
            double x0, double y0, double iz0, double uz0, double vz0,
            double x1, double y1, double iz1, double uz1, double vz1,
            double x2, double y2, double iz2, double uz2, double vz2,
            int[] tex, int shadeColor, boolean cullBackface, boolean applyFog) {

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

        if (minX > maxX || minY > maxY) {
            return;
        }

        double invArea = 1.0 / area;

        // Градиенты ребер для центра пикселя (px + 0.5, py + 0.5)
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

        int sR = (shadeColor >> 16) & 0xFF;
        int sG = (shadeColor >> 8) & 0xFF;
        int sB = shadeColor & 0xFF;

        int fogR = (FOG_COLOR >> 16) & 0xFF;
        int fogG = (FOG_COLOR >> 8) & 0xFF;
        int fogB = FOG_COLOR & 0xFF;

        for (int y = minY; y <= maxY; y++) {
            double w0 = w0Row;
            double w1 = w1Row;
            double w2 = w2Row;
            int rowIdx = y * width;

            for (int x = minX; x <= maxX; x++) {
                if (w0 >= -1e-5 && w1 >= -1e-5 && w2 >= -1e-5) {
                    double invZ = w0 * iz0 + w1 * iz1 + w2 * iz2;
                    if (invZ > 1e-6) {
                        float z = (float) (1.0 / invZ);
                        int idx = rowIdx + x;
                        if (z < zBuffer[idx]) {
                            zBuffer[idx] = z;

                            double u = (w0 * uz0 + w1 * uz1 + w2 * uz2) * z;
                            double v = (w0 * vz0 + w1 * vz1 + w2 * vz2) * z;

                            int tx = ((int) Math.floor(u * TEX_SIZE)) & TEX_MASK;
                            int ty = ((int) Math.floor(v * TEX_SIZE)) & TEX_MASK;
                            int texel = tex[(ty << 6) | tx];

                            int r = (((texel >> 16) & 0xFF) * sR) >> 8;
                            int g = (((texel >> 8) & 0xFF) * sG) >> 8;
                            int b = ((texel & 0xFF) * sB) >> 8;

                            if (applyFog && z > FOG_START) {
                                int fogFactor = (int) Math.min(220.0, ((z - FOG_START) / (FOG_END - FOG_START)) * 220.0);
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
        double intensity = 0.56 + Math.max(0.0, dot) * 0.44;
        if (ny < -0.5) {
            intensity = 0.42;
        }
        return shadeColor(baseRgb, intensity);
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

    /**
     * Генерация процедурных текстур в стилистике Dust 2 (песок, песчаник, ящики, ворота, бетон).
     */
    private void generateProceduralTextures() {
        Random rng = new Random(20261121L);

        // 0. Песчаная брусчатка пола Dust 2
        int[] sand = textures[TEX_FLOOR_SAND];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int grain = rng.nextInt(16) - 8;
                double wave = Math.sin(x * 0.19) * 5.0 + Math.cos(y * 0.19) * 5.0;
                boolean tileEdge = (x % 32 == 0) || (y % 32 == 0);
                int baseR = 216 + grain + (int) wave;
                int baseG = 188 + grain + (int) (wave * 0.85);
                int baseB = 136 + grain + (int) (wave * 0.65);
                if (tileEdge) {
                    baseR -= 20; baseG -= 18; baseB -= 14;
                }
                sand[y * TEX_SIZE + x] = packRgb(baseR, baseG, baseB);
            }
        }

        // 1. Каменные блоки песчаника (стены Dust 2)
        int[] wall = textures[TEX_WALL_SANDSTONE];
        for (int y = 0; y < TEX_SIZE; y++) {
            int row = y / 16;
            int offset = (row % 2) * 16;
            boolean hMortar = (y % 16 == 0) || (y % 16 == 15);
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean vMortar = ((x + offset) % 32 == 0);
                int brickSeed = (row * 17 + ((x + offset) / 32) * 31) & 15;
                int grain = rng.nextInt(14) - 7 + (brickSeed - 7);
                int r = 214 + grain;
                int g = 182 + grain;
                int b = 128 + grain;
                if (hMortar || vMortar) {
                    r -= 38; g -= 34; b -= 28;
                } else if (y > 54) {
                    // Легкое запыление у основания блока
                    r -= 14; g -= 12; b -= 10;
                }
                wall[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 2. Деревянный ящик Dust 2
        int[] wood = textures[TEX_CRATE_WOOD];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean outerFrame = x < 6 || x >= 58 || y < 6 || y >= 58;
                boolean diag = Math.abs(x - y) <= 3 || Math.abs((TEX_SIZE - 1 - x) - y) <= 3;
                boolean plankSeam = (!outerFrame && !diag) && (y % 12 == 0);
                int grain = ((int) (Math.sin(x * 0.45 + y * 0.08) * 6.0)) + (rng.nextInt(10) - 5);

                int r = 178 + grain;
                int g = 134 + grain;
                int b = 78 + grain / 2;

                if (outerFrame || diag) {
                    r = 146 + grain;
                    g = 106 + grain;
                    b = 58 + grain / 2;
                }
                if (plankSeam || x == 0 || y == 0 || x == 63 || y == 63) {
                    r -= 36; g -= 30; b -= 22;
                }
                // Заклепки по углам
                if ((x == 3 || x == 60) && (y == 3 || y == 60)) {
                    r = 62; g = 60; b = 58;
                }
                wood[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 3. Военный ящик с боеприпасами (оливковый со стальными ребрами и желтой полосой)
        int[] mil = textures[TEX_CRATE_MILITARY];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean border = x < 5 || x >= 59 || y < 5 || y >= 59;
                boolean rib = (y >= 18 && y <= 20) || (y >= 43 && y <= 45);
                boolean stencil = (y >= 28 && y <= 34) && (x >= 14 && x <= 50) && ((x + y) % 6 < 3);
                int grain = rng.nextInt(10) - 5;
                int r = 92 + grain;
                int g = 104 + grain;
                int b = 70 + grain;
                if (border || rib) {
                    r = 66 + grain; g = 74 + grain; b = 56 + grain;
                } else if (stencil) {
                    r = 208; g = 176; b = 68;
                }
                mil[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 4. Светлый бетон / оштукатуренные колонны и платформы
        int[] conc = textures[TEX_CONCRETE];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int grain = rng.nextInt(12) - 6;
                boolean groove = (y == 6 || y == 57);
                int r = 205 + grain;
                int g = 192 + grain;
                int b = 166 + grain;
                if (groove) {
                    r -= 26; g -= 24; b -= 20;
                }
                conc[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 5. Металлические ворота ("Двойные двери" на миду Dust 2)
        int[] door = textures[TEX_METAL_DOOR];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                boolean frame = x < 5 || x >= 59 || y < 5 || y >= 59 || (y >= 30 && y <= 33);
                int grain = rng.nextInt(12) - 6;
                int r = 106 + grain;
                int g = 88 + grain;
                int b = 72 + grain;
                if (frame) {
                    r = 74 + grain; g = 64 + grain; b = 56 + grain;
                }
                if (x % 16 == 8 && (y == 3 || y == 60)) {
                    r = 148; g = 136; b = 120;
                }
                door[y * TEX_SIZE + x] = packRgb(r, g, b);
            }
        }

        // 6. Мягко-текстурированный нейтральный материал для персонажей и 3D-оружия
        int[] solid = textures[TEX_SOLID];
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int v = 247 + (rng.nextInt(9) - 4);
                solid[y * TEX_SIZE + x] = packRgb(v, v, v);
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
