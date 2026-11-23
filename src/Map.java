import java.util.ArrayList;
import java.util.List;

/**
 * Полномасштабная многоуровневая 3D-архитектура CS-2 JAVA 8.0 (68 × 68 метров = 4 624 м² — более чем в 2 раза больше!).
 * Каждая из 4 карт имеет полностью уникальную геометрию, вертикальность (проходимые лестницы, 2-е этажи,
 * деревянные балконы, крытые тоннели, мостки Heaven, Окно Снайпера, Королевский Дворец), собственные Пленты A/B,
 * маршруты тактического ИИ и реальные названия позиций (Callouts) под радаром!
 */
public class Map {
    public static final double MIN_X = -34.0;
    public static final double MAX_X =  34.0;
    public static final double MIN_Z = -34.0;
    public static final double MAX_Z =  34.0;

    private static final class SmoothCylinderProp {
        final double cx, bottomY, cz, radBottom, radTop, height;
        final int segments, texId, color;

        SmoothCylinderProp(double cx, double bottomY, double cz, double radBottom, double radTop,
                           double height, int segments, int texId, int color) {
            this.cx = cx;
            this.bottomY = bottomY;
            this.cz = cz;
            this.radBottom = radBottom;
            this.radTop = radTop;
            this.height = height;
            this.segments = segments;
            this.texId = texId;
            this.color = color;
        }
    }

    private static final class SmoothEllipsoidProp {
        final double cx, cy, cz, rx, ry, rz;
        final int segments, rings, color;

        SmoothEllipsoidProp(double cx, double cy, double cz, double rx, double ry, double rz,
                            int segments, int rings, int color) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
            this.segments = segments;
            this.rings = rings;
            this.color = color;
        }
    }

    private static final class FloorZone {
        final double x0, y, z0, x1, z1;
        final int texId, tint;

        FloorZone(double x0, double y, double z0, double x1, double z1, int texId, int tint) {
            this.x0 = Math.min(x0, x1);
            this.y = y;
            this.z0 = Math.min(z0, z1);
            this.x1 = Math.max(x0, x1);
            this.z1 = Math.max(z0, z1);
            this.texId = texId;
            this.tint = tint;
        }
    }

    private static final class CalloutZone {
        final double minX, maxX, minZ, maxZ, minY;
        final String name;

        CalloutZone(double minX, double maxX, double minZ, double maxZ, double minY, String name) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.minY = minY;
            this.name = name;
        }
    }

    private final List<Vec3.Box> boxes = new ArrayList<>();
    private final List<Vec3.Box> overheadRoofs = new ArrayList<>();
    private final List<SmoothCylinderProp> smoothCylinders = new ArrayList<>();
    private final List<SmoothEllipsoidProp> smoothEllipsoids = new ArrayList<>();
    private final List<FloorZone> floorZones = new ArrayList<>();
    private final List<CalloutZone> calloutZones = new ArrayList<>();

    private final List<Vec3> botSpawns = new ArrayList<>();
    private final List<Vec3> allySpawns = new ArrayList<>();
    private final List<Vec3> patrolWaypoints = new ArrayList<>();

    private Vec3 playerSpawn = new Vec3(6.0, 0.0, -29.0);
    private Vec3 bombsiteASpot = new Vec3(21.5, 0.0, 20.5);
    private Vec3 bombsiteBSpot = new Vec3(-22.5, 0.0, 22.0);

    private GameSettings.MapTheme currentTheme = GameSettings.MapTheme.INFERNO;
    private int floorTexId = Renderer3D.TEX_INFERNO_COBBLE;

    public Map() {
        rebuildForTheme(GameSettings.MapTheme.INFERNO);
    }

    public GameSettings.MapTheme getCurrentTheme() {
        return currentTheme;
    }

    public void rebuildForTheme(GameSettings.MapTheme theme) {
        this.currentTheme = theme;
        boxes.clear();
        overheadRoofs.clear();
        smoothCylinders.clear();
        smoothEllipsoids.clear();
        floorZones.clear();
        calloutZones.clear();
        botSpawns.clear();
        allySpawns.clear();
        patrolWaypoints.clear();

        switch (theme) {
            case DUST_II:
                buildDustII();
                break;
            case MIRAGE:
                buildMirage();
                break;
            case NUKE:
                buildNuke();
                break;
            case INFERNO:
            default:
                buildInferno();
                break;
        }
    }

    // =========================================================================
    // АРХИТЕКТУРНЫЕ ПРИМИТИВЫ И ДЕТАЛИЗИРОВАННЫЕ ОБЪЕКТЫ ОКРУЖЕНИЯ
    // =========================================================================

    private void addWall(double x0, double y0, double z0, double x1, double y1, double z1, int texId, int tint) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1, texId, tint, 0.28, true, true));
    }

    private void addRoof(double x0, double y0, double z0, double x1, double y1, double z1, int texId, int tint) {
        Vec3.Box roof = new Vec3.Box(x0, y0, z0, x1, y1, z1, texId, tint, 0.28, true, false);
        boxes.add(roof);
        overheadRoofs.add(roof);
    }

    private void addDecor(double x0, double y0, double z0, double x1, double y1, double z1, int texId, int tint) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1, texId, tint, 0.28, false, false));
    }

    private void addInvisibleCollider(double x0, double y0, double z0, double x1, double y1, double z1, boolean shadow) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1, -1, 0xFFFFFF, 1.0, true, shadow));
    }

    private void addDoorPanel(double x0, double y0, double z0, double x1, double y1, double z1, int tint) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1, Renderer3D.TEX_METAL_DOOR, tint, 1.0, true, true));
    }

    private void addFloorZone(double x0, double y, double z0, double x1, double z1, int texId, int tint) {
        floorZones.add(new FloorZone(x0, y, z0, x1, z1, texId, tint));
    }

    private void addCallout(double minX, double maxX, double minZ, double maxZ, double minY, String name) {
        calloutZones.add(new CalloutZone(minX, maxX, minZ, maxZ, minY, name));
    }

    private void addPerimeterWalls(int wallTex, int wallTint, int trimTex, int trimTint, double wallH) {
        double t = 1.25;
        addWall(MIN_X - t, 0.0, MIN_Z - t, MAX_X + t, wallH, MIN_Z, wallTex, wallTint);
        addWall(MIN_X - t, 0.0, MAX_Z,     MAX_X + t, wallH, MAX_Z + t, wallTex, wallTint);
        addWall(MIN_X - t, 0.0, MIN_Z,     MIN_X,     wallH, MAX_Z, wallTex, wallTint);
        addWall(MAX_X,     0.0, MIN_Z,     MAX_X + t, wallH, MAX_Z, wallTex, wallTint);

        // Каменный цоколь и верхний архитектурный карниз по всему периметру
        addDecor(MIN_X - 0.1, 0.0, MIN_Z, MAX_X + 0.1, 0.44, MIN_Z + 0.16, trimTex, trimTint);
        addDecor(MIN_X - 0.1, 0.0, MAX_Z - 0.16, MAX_X + 0.1, 0.44, MAX_Z, trimTex, trimTint);
        addDecor(MIN_X, 0.0, MIN_Z, MIN_X + 0.16, 0.44, MAX_Z, trimTex, trimTint);
        addDecor(MAX_X - 0.16, 0.0, MIN_Z, MAX_X, 0.44, MAX_Z, trimTex, trimTint);

        addDecor(MIN_X - 0.25, wallH, MIN_Z - 0.25, MAX_X + 0.25, wallH + 0.48, MIN_Z + 0.30, trimTex, trimTint);
        addDecor(MIN_X - 0.25, wallH, MAX_Z - 0.30, MAX_X + 0.25, wallH + 0.48, MAX_Z + 0.25, trimTex, trimTint);
        addDecor(MIN_X - 0.25, wallH, MIN_Z, MIN_X + 0.30, wallH + 0.48, MAX_Z, trimTex, trimTint);
        addDecor(MAX_X - 0.30, wallH, MIN_Z, MAX_X + 0.25, wallH + 0.48, MAX_Z, trimTex, trimTint);
    }

    /**
     * Проходимая лестница вдоль оси Z (высота ступени <= 0.26 м — плавный подъём для игрока и всех ботов!).
     */
    private void addStaircaseZ(double minX, double maxX, double startZ, double endZ,
                               double baseY, double topY, int texId, int tint) {
        int steps = Math.max(2, (int) Math.ceil(Math.abs(topY - baseY) / 0.255));
        double dz = (endZ - startZ) / steps;
        double dy = (topY - baseY) / steps;
        for (int i = 0; i < steps; i++) {
            double z0 = startZ + i * dz;
            double z1 = startZ + (i + 1) * dz;
            double yTop = baseY + (i + 1) * dy;
            boxes.add(new Vec3.Box(minX, baseY, Math.min(z0, z1), maxX, yTop, Math.max(z0, z1),
                texId, tint, 0.28, true, false));
        }
    }

    /**
     * Проходимая лестница вдоль оси X.
     */
    private void addStaircaseX(double startX, double endX, double minZ, double maxZ,
                               double baseY, double topY, int texId, int tint) {
        int steps = Math.max(2, (int) Math.ceil(Math.abs(topY - baseY) / 0.255));
        double dx = (endX - startX) / steps;
        double dy = (topY - baseY) / steps;
        for (int i = 0; i < steps; i++) {
            double x0 = startX + i * dx;
            double x1 = startX + (i + 1) * dx;
            double yTop = baseY + (i + 1) * dy;
            boxes.add(new Vec3.Box(Math.min(x0, x1), baseY, minZ, Math.max(x0, x1), yTop, maxZ,
                texId, tint, 0.28, true, false));
        }
    }

    private void addCrateStack(double cx, double baseY, double cz, double size,
                               boolean military, boolean doubleStack) {
        double h = size * 0.5;
        int tex = military ? Renderer3D.TEX_CRATE_MILITARY : Renderer3D.TEX_CRATE_WOOD;
        int tint = military ? 0xD8E2D4 : 0xF2DEC2;
        boxes.add(new Vec3.Box(cx - h, baseY, cz - h, cx + h, baseY + size, cz + h, tex, tint, 1.0, true, true));
        if (doubleStack) {
            double s2 = size * 0.78;
            double h2 = s2 * 0.5;
            boxes.add(new Vec3.Box(cx - h2 + 0.14, baseY + size, cz - h2 - 0.10,
                cx + h2 + 0.14, baseY + size + s2, cz + h2 - 0.10, tex, 0xEAD4B4, 1.0, true, false));
        }
    }

    private void addBarrelCluster(double x, double baseY, double z, int col1, int col2) {
        addInvisibleCollider(x - 0.42, baseY, z - 0.42, x + 0.92, baseY + 1.18, z + 0.48, true);
        smoothCylinders.add(new SmoothCylinderProp(x, baseY, z, 0.36, 0.36, 1.16, 14, Renderer3D.TEX_SOLID, col1));
        smoothCylinders.add(new SmoothCylinderProp(x, baseY + 0.36, z, 0.375, 0.375, 0.06, 14, Renderer3D.TEX_SOLID, 0x222830));
        smoothCylinders.add(new SmoothCylinderProp(x, baseY + 0.76, z, 0.375, 0.375, 0.06, 14, Renderer3D.TEX_SOLID, 0x222830));

        smoothCylinders.add(new SmoothCylinderProp(x + 0.56, baseY, z + 0.12, 0.35, 0.35, 1.14, 14, Renderer3D.TEX_SOLID, col2));
        smoothCylinders.add(new SmoothCylinderProp(x + 0.56, baseY + 0.38, z + 0.12, 0.365, 0.365, 0.06, 14, Renderer3D.TEX_SOLID, 0x222830));
    }

    private void addVehicleAlongZ(double cx, double cz, int bodyColor, boolean van) {
        double halfW = van ? 1.08 : 0.96;
        double halfL = van ? 2.38 : 2.12;
        double hoodH = van ? 1.14 : 0.92;
        double roofH = van ? 2.08 : 1.54;

        // Основной кузов автомобиля / бронефургона
        addWall(cx - halfW, 0.26, cz - halfL, cx + halfW, hoodH, cz + halfL, Renderer3D.TEX_SOLID, bodyColor);
        double cabMinZ = van ? cz - halfL + 0.16 : cz - halfL * 0.52;
        double cabMaxZ = van ? cz + halfL - 0.52 : cz + halfL * 0.44;
        addWall(cx - halfW + 0.08, hoodH, cabMinZ, cx + halfW - 0.08, roofH, cabMaxZ, Renderer3D.TEX_SOLID, bodyColor);
        // Тонированное остекление кабины
        addDecor(cx - halfW + 0.04, hoodH + 0.12, cabMinZ + 0.14, cx + halfW - 0.04, roofH - 0.12, cabMaxZ - 0.14, Renderer3D.TEX_SOLID, 0x283A4C);
        // Бамперы, решётка радиатора, фары и стоп-сигналы
        addDecor(cx - halfW - 0.04, 0.22, cz + halfL - 0.12, cx + halfW + 0.04, 0.46, cz + halfL + 0.08, Renderer3D.TEX_SOLID, 0x22262C);
        addDecor(cx - halfW - 0.04, 0.22, cz - halfL - 0.08, cx + halfW + 0.04, 0.46, cz - halfL + 0.12, Renderer3D.TEX_SOLID, 0x22262C);
        addDecor(cx - halfW + 0.14, 0.58, cz + halfL - 0.02, cx - halfW + 0.44, 0.76, cz + halfL + 0.05, Renderer3D.TEX_SOLID, 0xFFF4CE);
        addDecor(cx + halfW - 0.44, 0.58, cz + halfL - 0.02, cx + halfW - 0.14, 0.76, cz + halfL + 0.05, Renderer3D.TEX_SOLID, 0xFFF4CE);
        addDecor(cx - halfW + 0.14, 0.58, cz - halfL - 0.05, cx - halfW + 0.44, 0.74, cz - halfL + 0.02, Renderer3D.TEX_SOLID, 0xDC2E28);
        addDecor(cx + halfW - 0.44, 0.58, cz - halfL - 0.05, cx + halfW - 0.14, 0.74, cz - halfL + 0.02, Renderer3D.TEX_SOLID, 0xDC2E28);

        // 4 круглых 3D-колеса с металлическими дисками
        double[][] wheels = {
            {cx - halfW - 0.03, cz - halfL + 0.68},
            {cx + halfW + 0.03, cz - halfL + 0.68},
            {cx - halfW - 0.03, cz + halfL - 0.68},
            {cx + halfW + 0.03, cz + halfL - 0.68}
        };
        for (double[] w : wheels) {
            smoothEllipsoids.add(new SmoothEllipsoidProp(w[0], 0.36, w[1], 0.18, 0.36, 0.36, 10, 6, 0x1C2024));
            smoothEllipsoids.add(new SmoothEllipsoidProp(w[0] + (w[0] < cx ? -0.05 : 0.05), 0.36, w[1], 0.08, 0.20, 0.20, 8, 5, 0xAEB8C4));
        }
    }

    private void addSandbagBarricade(double cx, double baseY, double cz, boolean alongX, int rows) {
        for (int row = 0; row < rows; row++) {
            for (int i = -1; i <= 1; i++) {
                double ox = alongX ? i * 0.74 + (row % 2) * 0.16 : 0.0;
                double oz = alongX ? 0.0 : i * 0.74 + (row % 2) * 0.16;
                smoothEllipsoids.add(new SmoothEllipsoidProp(cx + ox, baseY + 0.20 + row * 0.29, cz + oz,
                    alongX ? 0.42 : 0.30, 0.17, alongX ? 0.30 : 0.42, 10, 5, 0xB8A47C));
            }
        }
        if (alongX) {
            addInvisibleCollider(cx - 1.20, baseY, cz - 0.34, cx + 1.20, baseY + rows * 0.31, cz + 0.34, true);
        } else {
            addInvisibleCollider(cx - 0.34, baseY, cz - 1.20, cx + 0.34, baseY + rows * 0.31, cz + 1.20, true);
        }
    }

    private void addCypressTree(double x, double z, double trunkH, double r) {
        addInvisibleCollider(x - 0.28, 0.0, z - 0.28, x + 0.28, 2.2, z + 0.28, true);
        smoothCylinders.add(new SmoothCylinderProp(x, 0.0, z, 0.24, 0.18, trunkH, 10, Renderer3D.TEX_SOLID, 0x5E422B));
        smoothEllipsoids.add(new SmoothEllipsoidProp(x, trunkH + r * 0.75, z, r * 0.58, r * 1.50, r * 0.58, 12, 7, 0x2E6A2E));
        smoothEllipsoids.add(new SmoothEllipsoidProp(x, trunkH + r * 1.85, z, r * 0.38, r * 1.15, r * 0.38, 10, 6, 0x3D8038));
    }

    private void addPalmTree(double x, double z, double h) {
        addInvisibleCollider(x - 0.26, 0.0, z - 0.26, x + 0.26, 2.2, z + 0.26, true);
        smoothCylinders.add(new SmoothCylinderProp(x, 0.0, z, 0.29, 0.22, h * 0.55, 10, Renderer3D.TEX_SOLID, 0x7E5E3E));
        smoothCylinders.add(new SmoothCylinderProp(x + 0.06, h * 0.53, z + 0.04, 0.22, 0.16, h * 0.48, 10, Renderer3D.TEX_SOLID, 0x8A6846));
        double[][] fronds = {{1.45, 0.0}, {-1.45, 0.0}, {0.0, 1.45}, {0.0, -1.45}, {1.05, 1.05}, {-1.05, 1.05}, {1.05, -1.05}, {-1.05, -1.05}};
        for (double[] f : fronds) {
            smoothEllipsoids.add(new SmoothEllipsoidProp(x + f[0], h + 0.05, z + f[1], 1.12, 0.22, 0.50, 10, 5, 0x427E36));
        }
    }

    private void addStreetLamp(double x, double z, double h) {
        smoothCylinders.add(new SmoothCylinderProp(x, 0.0, z, 0.11, 0.07, h, 8, Renderer3D.TEX_SOLID, 0x2C323B));
        addDecor(x - 0.10, h - 0.08, z - 0.55, x + 0.10, h + 0.05, z + 0.12, Renderer3D.TEX_SOLID, 0x323842);
        smoothEllipsoids.add(new SmoothEllipsoidProp(x, h - 0.18, z - 0.48, 0.20, 0.16, 0.20, 8, 5, 0xFFEAA8));
    }

    // =========================================================================
    // 1. DE_INFERNO — СТАРАЯ ИТАЛИЯ (68×68 м: Узкий Банан, Фонтан B + 19м Колокольня, 2-эт. Апартаменты!)
    // =========================================================================
    private void buildInferno() {
        floorTexId = Renderer3D.TEX_INFERNO_COBBLE;
        bombsiteASpot = new Vec3(21.5, 0.0, 20.5);
        bombsiteBSpot = new Vec3(-22.5, 0.0, 22.0);
        playerSpawn   = new Vec3(6.0, 0.0, -29.0);

        int stucco = Renderer3D.TEX_INFERNO_STUCCO;
        int roof   = Renderer3D.TEX_ROOF_TILES;
        int stone  = Renderer3D.TEX_WALL_SANDSTONE;

        addPerimeterWalls(stucco, 0xF4DEC6, roof, 0xB84A34, 6.8);

        // Терракотовые площади на Плентах A и B + отметка BOMBSITE
        addFloorZone(-31.5, 0.004, 13.5, -11.5, 32.5, Renderer3D.TEX_MIRAGE_TILE, 0xDEC4AE);
        addFloorZone(13.5,  0.004, 12.5, 32.5,  32.5, Renderer3D.TEX_MIRAGE_TILE, 0xE2CCB4);
        addFloorZone(bombsiteBSpot.x - 4.4, 0.008, bombsiteBSpot.z - 4.2, bombsiteBSpot.x + 4.4, bombsiteBSpot.z + 4.2, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);
        addFloorZone(bombsiteASpot.x - 4.5, 0.008, bombsiteASpot.z - 4.2, bombsiteASpot.x + 4.5, bombsiteASpot.z + 4.2, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);

        // --- ЮЖНАЯ БАЗА СПЕЦНАЗА (CT SPAWN): Бронефургон GIGN + Каменный Колодец + Арка ---
        addVehicleAlongZ(13.0, -28.0, 0x2B405C, true);
        addInvisibleCollider(0.4, 0.0, -29.6, 2.6, 1.08, -27.4, true);
        smoothCylinders.add(new SmoothCylinderProp(1.5, 0.0, -28.5, 1.18, 1.18, 1.04, 16, Renderer3D.TEX_CONCRETE, 0xA89E92));
        smoothCylinders.add(new SmoothCylinderProp(1.5, 0.0, -28.5, 0.90, 0.90, 1.07, 16, Renderer3D.TEX_WATER, 0x4EC8E8));

        addWall(-11.5, 0.0, -24.0, -4.5, 5.4, -18.0, stucco, 0xF0D2B4);
        addDecor(-11.8, 5.4, -24.3, -4.2, 6.0, -17.7, roof, 0xBC4832);
        addRoof(-16.8, 3.8, -22.0, -11.5, 5.2, -19.2, stone, 0xE2CBB0);

        // --- ЗАПАДНЫЙ КОРИДОР: ЗНАМЕНИТЫЙ УЗКИЙ «БАНАН» (X = -28..-17.8, Z = -18..+13.5) ---
        addWall(-34.0, 0.0, -18.0, -28.2, 6.2, 13.5, stucco, 0xE8BE9A);
        addDecor(-28.5, 5.8, -18.0, -27.1, 6.4, 13.5, roof, 0xBA4630);
        // Длинная стена, отделяющая Банан от Мида!
        addWall(-17.8, 0.0, -14.5, -12.2, 6.0, 12.8, stucco, 0xF2D8BC);
        addDecor(-18.2, 5.8, -14.8, -11.8, 6.4, 13.1, roof, 0xBC4832);

        // Поленница дров (Woodpile / Logs), Синий Седан (Banana Car), полустенка и мешки с песком!
        addWall(-28.0, 0.0, -10.8, -25.8, 1.48, -5.6, Renderer3D.TEX_CRATE_WOOD, 0xC8945E);
        addWall(-28.0, 1.48, -10.3, -26.4, 2.15, -6.2, Renderer3D.TEX_CRATE_WOOD, 0xB88450);
        addVehicleAlongZ(-20.2, 3.8, 0x3A6898, false); // Машина на Банане!
        addWall(-22.5, 0.0, -2.8, -18.0, 1.30, -1.6, Renderer3D.TEX_INFERNO_COBBLE, 0xB6ACA0); // Каменная полустенка!
        addSandbagBarricade(-24.8, 0.0, 8.8, true, 3); // Мешки с песком!
        addRoof(-28.2, 3.9, 11.5, -17.8, 5.6, 13.4, stone, 0xE4CEB2); // Каменная арка входа на B!
        addStreetLamp(-27.4, -2.0, 4.3);
        addStreetLamp(-18.4, 9.5, 4.3);

        // --- СЕВЕРО-ЗАПАД: ПЛЕНТ B + 3-ЯРУСНЫЙ ФОНТАН С ВОДОЙ + РУИНЫ И 19-МЕТРОВАЯ КОЛОКОЛЬНЯ! ---
        addInvisibleCollider(-24.3, 0.0, 20.2, -20.7, 1.15, 23.8, true);
        smoothCylinders.add(new SmoothCylinderProp(-22.5, 0.0, 22.0, 2.50, 2.55, 0.72, 20, Renderer3D.TEX_CONCRETE, 0xDDD6CC));
        smoothCylinders.add(new SmoothCylinderProp(-22.5, 0.0, 22.0, 2.12, 2.12, 0.75, 20, Renderer3D.TEX_WATER, 0x44D2F2));
        smoothCylinders.add(new SmoothCylinderProp(-22.5, 0.72, 22.0, 0.56, 0.44, 1.30, 14, Renderer3D.TEX_CONCRETE, 0xE6DFD6));
        smoothCylinders.add(new SmoothCylinderProp(-22.5, 1.90, 22.0, 1.18, 1.24, 0.26, 16, Renderer3D.TEX_CONCRETE, 0xECE6DE));
        smoothEllipsoids.add(new SmoothEllipsoidProp(-22.5, 2.48, 22.0, 0.34, 0.48, 0.34, 12, 6, 0x9EEFFF));

        addCrateStack(-28.8, 0.0, 28.2, 1.85, false, true); // Гробы (Coffins)
        addCrateStack(-15.8, 0.0, 26.5, 1.75, true, true);  // Апельсины (First/Second Oranges)
        addWall(-33.8, 0.0, 22.8, -30.4, 4.2, 33.8, stucco, 0xCFA888); // Тёмный угол (Dark)
        addWall(-12.2, 0.0, 17.5, -8.8, 5.2, 28.5, stucco, 0xEAD0B4);  // Проход КТ / Стройка

        // 19-метровая Итальянская Колокольня (Bell Tower) над Плентом B!
        addWall(-32.8, 0.0, 14.0, -28.4, 14.8, 18.4, stucco, 0xF2D4B4);
        addDecor(-33.2, 9.4, 13.6, -28.0, 9.9, 18.8, stone, 0xD8C4AA);
        addDecor(-33.3, 14.8, 13.5, -27.9, 15.5, 18.9, roof, 0xBC4832);
        addDecor(-32.6, 15.5, 13.8, -31.8, 18.0, 14.6, stone, 0xEAD4B8);
        addDecor(-29.2, 15.5, 13.8, -28.4, 18.0, 14.6, stone, 0xEAD4B8);
        addDecor(-32.6, 15.5, 17.6, -31.8, 18.0, 18.4, stone, 0xEAD4B8);
        addDecor(-29.2, 15.5, 17.6, -28.4, 18.0, 18.4, stone, 0xEAD4B8);
        smoothEllipsoids.add(new SmoothEllipsoidProp(-30.6, 16.8, 16.1, 0.68, 0.82, 0.68, 12, 6, 0xE8BA44));
        addDecor(-33.4, 18.0, 13.4, -27.8, 18.9, 19.0, roof, 0xB4422C);

        addCypressTree(-31.2, -24.5, 2.8, 1.55);
        addCypressTree(-25.0, 31.5, 3.0, 1.65);
        addCypressTree(-15.0, 31.8, 2.6, 1.45);

        // --- ЦЕНТР: ГЛАВНЫЙ МИД, ВТОРОЙ МИД, НАВЕСНОЙ МОСТ И ДОМ АРКИ ---
        addWall(-7.5, 0.0, -18.5, -1.5, 5.8, -4.5, stucco, 0xEED0B0);
        addDecor(-7.8, 5.8, -18.8, -1.2, 6.3, -4.2, roof, 0xBC4832);
        addDecor(-1.5, 3.7, -11.5, 8.5, 5.2, -8.5, stone, 0xE4CEB2); // Навесной Мост над Мидом!

        addWall(-2.5, 0.0, 3.8, 8.8, 6.0, 14.2, stucco, 0xF4DAC0);   // Центральный дом (Арка CT <-> Бойлер)
        addDecor(-2.8, 6.0, 3.5, 9.1, 6.5, 14.5, roof, 0xBC4832);
        addBarrelCluster(3.2, 0.0, -2.4, 0x7E4E2C, 0x6E4224);
        addStreetLamp(-1.2, 1.8, 4.3);
        addStreetLamp(10.5, 2.5, 4.3);

        // --- ВОСТОК: ПРОХОДНЫЕ 2-ЭТАЖНЫЕ АПАРТАМЕНТЫ (Y = 2.08 м) + ДЕРЕВЯННЫЙ БАЛКОН НАД ПЛЕНТОМ А! ---
        addWall(16.5, 0.0, -14.0, 33.4, 2.08, 8.5, stucco, 0xE8C8A8);
        // Полноценная каменная лестница на 2-й этаж Апартаментов (Z = -22.2 -> -14.0, Y = 0 -> 2.08 м)!
        addStaircaseZ(21.0, 26.2, -22.2, -14.0, 0.0, 2.08, Renderer3D.TEX_INFERNO_COBBLE, 0xC8BEB2);
        addWall(20.6, 0.0, -22.2, 21.0, 2.85, -14.0, Renderer3D.TEX_CRATE_WOOD, 0x8E5E38);
        addWall(26.2, 0.0, -22.2, 26.6, 2.85, -14.0, Renderer3D.TEX_CRATE_WOOD, 0x8E5E38);

        // Стены и окна 2-го этажа Апартаментов + настоящая крыша (защищает от дождя!)
        addWall(32.2, 2.08, -14.0, 33.8, 5.8, 8.5, stucco, 0xF0D2B4);
        addWall(16.5, 2.08, -14.0, 20.8, 5.8, -12.8, stucco, 0xF0D2B4);
        addWall(26.4, 2.08, -14.0, 32.4, 5.8, -12.8, stucco, 0xF0D2B4);
        addWall(16.5, 2.08, -12.8, 17.6, 3.04, 8.5, stone, 0xE4CCB0); // Подоконник окон на Шорт А!
        addWall(16.5, 4.48, -12.8, 17.6, 5.8, 8.5, stone, 0xE4CCB0);
        addWall(16.5, 3.04, -3.6, 17.6, 4.48, -1.2, stone, 0xE4CCB0);
        addCrateStack(29.2, 2.08, -3.5, 1.35, false, false);
        addRoof(16.2, 5.8, -14.2, 33.8, 6.4, 8.8, roof, 0xBC4832);

        // Деревянный Балкон Апартаментов над Плентом А и Ямой (Y = 2.08 м)!
        addWall(18.5, 1.78, 8.5, 27.5, 2.08, 12.8, Renderer3D.TEX_CRATE_WOOD, 0x9C683E);
        addWall(18.5, 2.08, 8.5, 18.9, 3.02, 12.8, Renderer3D.TEX_CRATE_WOOD, 0x7E502E);
        addWall(27.1, 2.08, 8.5, 27.5, 3.02, 12.8, Renderer3D.TEX_CRATE_WOOD, 0x7E502E);
        addWall(18.5, 2.08, 12.4, 22.0, 3.02, 12.8, Renderer3D.TEX_CRATE_WOOD, 0x7E502E);
        addCrateStack(25.2, 0.0, 13.8, 1.42, false, false); // Телега под балконом!

        // --- СЕВЕРО-ВОСТОК: ПЛЕНТ А + БИБЛИОТЕКА + БОЛЬШАЯ ЯМА (PIT) ---
        addCrateStack(20.5, 0.0, 20.2, 1.85, true, true);
        addCrateStack(24.0, 0.0, 22.6, 1.65, false, false);
        addWall(16.8, 0.0, 17.8, 18.2, 3.8, 19.2, stone, 0xEAD8BE);

        addWall(28.5, 0.0, 16.0, 29.4, 1.28, 26.5, Renderer3D.TEX_INFERNO_COBBLE, 0xB8AEA2); // Парапет Ямы!
        addSandbagBarricade(31.2, 0.0, 28.5, true, 3);
        addWall(5.5, 0.0, 27.5, 15.5, 5.6, 33.5, stucco, 0xEED2B6); // Библиотека!
        addDecor(5.2, 5.6, 27.2, 15.8, 6.2, 33.8, roof, 0xBC4832);
        addCypressTree(18.5, 31.2, 2.9, 1.60);
        addCypressTree(31.5, -24.0, 2.7, 1.55);

        // Callouts
        addCallout(16.0, 34.0, -14.5, 13.0, 1.4, "АПАРТАМЕНТЫ • 2 ЭТАЖ");
        addCallout(-34.0, -16.5, -18.0, 13.5, -1.0, "УЛИЦА БАНАН");
        addCallout(-34.0, -10.0, 13.5, 34.0, -1.0, "ПЛЕНТ B • ФОНТАН");
        addCallout(13.0, 28.0, 13.0, 30.0, -1.0, "ПЛЕНТ A • ПЛОЩАДЬ");
        addCallout(28.0, 34.0, 14.0, 34.0, -1.0, "БОЛЬШАЯ ЯМА (PIT)");
        addCallout(4.0, 16.5, 24.0, 34.0, -1.0, "БИБЛИОТЕКА");
        addCallout(-11.0, 14.0, -16.0, 14.0, -1.0, "ГЛАВНЫЙ МИД");
        addCallout(-16.0, 16.0, -34.0, -18.0, -1.0, "БАЗА CT • ИТАЛИЯ");
        addCallout(15.0, 34.0, -34.0, -14.5, -1.0, "ВТОРОЙ МИД • СТУПЕНИ");

        // Spawns & Waypoints
        allySpawns.add(new Vec3(4.0, 0.0, -29.0));
        allySpawns.add(new Vec3(8.5, 0.0, -29.0));
        allySpawns.add(new Vec3(-2.5, 0.0, -28.5));
        allySpawns.add(new Vec3(10.0, 0.0, -25.0));
        allySpawns.add(new Vec3(-14.0, 0.0, -26.5));
        allySpawns.add(new Vec3(18.0, 0.0, -27.5));

        botSpawns.add(new Vec3(-23.0, 0.0, 29.0));
        botSpawns.add(new Vec3(-17.5, 0.0, 28.5));
        botSpawns.add(new Vec3(-5.5, 0.0, 29.0));
        botSpawns.add(new Vec3(2.0, 0.0, 28.0));
        botSpawns.add(new Vec3(20.0, 0.0, 28.5));
        botSpawns.add(new Vec3(26.5, 0.0, 25.0));
        botSpawns.add(new Vec3(12.5, 0.0, 21.0));
        botSpawns.add(new Vec3(-22.5, 0.0, 16.0));
        botSpawns.add(new Vec3(3.0, 0.0, 18.0));
        botSpawns.add(new Vec3(23.5, 2.08, 4.0));

        patrolWaypoints.add(new Vec3(-23.0, 0.0, -12.0));
        patrolWaypoints.add(new Vec3(-23.0, 0.0, 1.5));
        patrolWaypoints.add(new Vec3(-22.5, 0.0, 17.5));
        patrolWaypoints.add(new Vec3(-15.5, 0.0, 23.0));
        patrolWaypoints.add(new Vec3(-2.0, 0.0, 21.5));
        patrolWaypoints.add(new Vec3(3.5, 0.0, -6.0));
        patrolWaypoints.add(new Vec3(12.5, 0.0, 5.0));
        patrolWaypoints.add(new Vec3(21.5, 0.0, 17.5));
        patrolWaypoints.add(new Vec3(27.0, 0.0, 22.0));
        patrolWaypoints.add(new Vec3(12.0, 0.0, -22.0));
        patrolWaypoints.add(new Vec3(23.5, 2.08, -5.0));
        patrolWaypoints.add(new Vec3(-14.0, 0.0, -22.0));
    }

    // =========================================================================
    // 2. DE_DUST2 — МАРОККАНСКАЯ КРЕПОСТЬ (68×68 м: Длина А, Коробка, Парапет Short, Крытые Тоннели B!)
    // =========================================================================
    private void buildDustII() {
        floorTexId = Renderer3D.TEX_FLOOR_SAND;
        bombsiteASpot = new Vec3(23.5, 1.56, 24.0); // На высоком Плато А!
        bombsiteBSpot = new Vec3(-24.5, 0.0, 23.5);
        playerSpawn   = new Vec3(2.0, 0.0, 28.5);   // Каньон CT-Mid!

        int sand = Renderer3D.TEX_WALL_SANDSTONE;
        addPerimeterWalls(sand, 0xF2DCBA, Renderer3D.TEX_CONCRETE, 0xC8AE86, 6.8);

        // Каменная дорога на Длине А, тёмный каменный пол Тоннеля B и декали Плентов A/B
        addFloorZone(16.8, 0.004, -24.0, 33.2, 18.5, Renderer3D.TEX_INFERNO_COBBLE, 0xDECBA8);
        addFloorZone(-33.5, 0.004, -15.5, -11.5, 11.2, Renderer3D.TEX_CONCRETE, 0xACA08E);
        addFloorZone(bombsiteBSpot.x - 4.2, 0.008, bombsiteBSpot.z - 4.2, bombsiteBSpot.x + 4.2, bombsiteBSpot.z + 4.2, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);
        addFloorZone(bombsiteASpot.x - 4.2, 1.566, bombsiteASpot.z - 4.2, bombsiteASpot.x + 4.2, bombsiteASpot.z + 4.2, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);

        // --- ВОСТОК: ДЛИНА А (LONG A) + КРЫТОЕ ЗДАНИЕ «КОРОБКА» + ПЛАТО ПЛЕНТА A (Y = 1.56 м) ---
        addWall(12.5, 0.0, -18.0, 16.8, 6.2, 16.5, sand, 0xECD2AA);

        // Крытое здание «КОРОБКА» (Long Doors House, X = 16.8..30.4, Z = -22.5..-11.0) с простреливаемыми воротами!
        addWall(16.8, 0.0, -22.5, 20.5, 4.8, -21.2, sand, 0xE6CA9E);
        addWall(24.8, 0.0, -22.5, 30.4, 4.8, -21.2, sand, 0xE6CA9E);
        addDoorPanel(20.5, 0.0, -22.1, 22.0, 3.6, -21.6, 0xC89458);
        addDoorPanel(23.5, 0.0, -21.9, 24.8, 3.6, -21.4, 0xC89458);
        addWall(29.0, 0.0, -22.5, 30.4, 4.8, -11.0, sand, 0xE6CA9E);
        addWall(16.8, 0.0, -12.2, 21.2, 4.8, -11.0, sand, 0xE6CA9E);
        addWall(25.6, 0.0, -12.2, 30.4, 4.8, -11.0, sand, 0xE6CA9E);
        addRoof(16.8, 4.6, -22.6, 30.4, 5.2, -10.9, sand, 0xD4B68C);

        // Синий контейнер (Blue Dumpster), машина Длины (Long Car) и укрытие Ямы (Long Pit)!
        addWall(17.2, 0.0, -8.2, 20.4, 1.72, -5.8, Renderer3D.TEX_METAL_DOOR, 0x4A88C4);
        addVehicleAlongZ(30.8, 6.5, 0x9E8668, false);
        addWall(27.2, 0.0, -9.5, 28.2, 1.30, -2.5, sand, 0xDEC298);

        // ВЫСОКОЕ ПЛАТО ПЛЕНТА A (Y = 1.56 м) + Широкая Рампа с Длины А и подъём с CT!
        addWall(16.5, 0.0, 18.5, 33.8, 1.56, 33.8, sand, 0xEED6B0);
        addStaircaseZ(23.5, 31.8, 11.5, 18.5, 0.0, 1.56, sand, 0xE4CA9E);
        addStaircaseX(10.5, 16.5, 25.5, 30.5, 0.0, 1.56, sand, 0xE4CA9E);
        addCrateStack(22.8, 1.56, 23.5, 1.75, true, true);
        addCrateStack(26.6, 1.56, 25.8, 1.55, false, false);
        addWall(28.5, 1.56, 31.8, 33.6, 4.6, 33.6, sand, 0xF6DFBA); // Позиция GOOSE!

        // --- ЦЕНТР: МИД + ДВЕРИ МИДА (MID DOORS) + ЯЩИК XBOX + ПАРАПЕТ «ЗИГЗАГ» (SHORT A, Y = 1.56 м)! ---
        addWall(-11.5, 0.0, 13.8, -3.2, 5.2, 15.4, sand, 0xEAD0A8);
        addWall(2.8,   0.0, 13.8, 9.5,  5.2, 15.4, sand, 0xEAD0A8);
        addRoof(-3.2,  3.6, 13.8, 2.8,  5.4, 15.4, sand, 0xDEC298);
        // Двойные простреливаемые ворота Мида со снайперской щелью!
        addDoorPanel(-3.2, 0.0, 14.2, -0.78, 3.55, 14.7, 0xC8965A);
        addDoorPanel(0.78, 0.0, 14.5, 2.8,   3.55, 15.0, 0xC8965A);

        // Высокий Парапет Short A / Catwalk (Y = 1.56 м) + Ящик XBOX + Лестница с Мида!
        addWall(5.5,  0.0, -2.0, 12.5, 1.56, 22.5, sand, 0xEAD2AA);
        addWall(12.5, 0.0, 16.5, 16.5, 1.56, 22.5, sand, 0xEAD2AA);
        addCrateStack(3.8, 0.0, -3.2, 1.35, false, false); // Знаменитый XBOX!
        addStaircaseZ(6.2, 11.2, -7.8, -2.0, 0.0, 1.56, sand, 0xDEC49C);
        addWall(5.5, 1.56, 4.5, 6.2, 2.55, 12.0, sand, 0xF2DAB4);

        addPalmTree(-4.8, -22.5, 5.4);
        addPalmTree(11.5, -27.5, 5.8);

        // --- ЗАПАД: КРЫТЫЕ КАМЕННЫЕ ТОННЕЛИ B (ТЁМКА, ПОТОЛОК Y = 4.2 м) + БАСТИОН ПЛЕНТА B С ОКНОМ! ---
        addWall(-13.2, 0.0, -15.5, -11.2, 5.2, 5.2, sand, 0xD4BA92);
        addWall(-21.5, 0.0, -15.5, -13.2, 5.2, -13.8, sand, 0xD4BA92);
        addWall(-33.8, 0.0, -15.5, -27.5, 5.2, -13.8, sand, 0xD4BA92);
        addWall(-20.2, 0.0, 9.2, -11.2, 5.2, 11.2, sand, 0xD4BA92);
        addWall(-33.8, 0.0, 9.2, -25.8, 5.2, 11.2, sand, 0xD4BA92);
        addRoof(-33.8, 4.1, -15.5, -11.2, 5.2, 11.2, Renderer3D.TEX_CONCRETE, 0xB8A68A);
        // Несущие резные колонны внутри Верхнего Тоннеля B!
        addWall(-24.8, 0.0, -7.2, -22.8, 4.1, -5.2, sand, 0xC2A882);
        addWall(-24.8, 0.0, 1.2,  -22.8, 4.1, 3.2,  sand, 0xC2A882);
        addCrateStack(-30.5, 0.0, -2.5, 1.65, false, true);

        // Стена Плента B с Воротами B (Z = 18..21.6) и Прыжковым Проломом «ОКНО B» (Z = 24.4..27.8)!
        addWall(-13.2, 0.0, 11.2, -11.5, 4.8, 18.0, sand, 0xEAD0A8);
        addWall(-13.2, 0.0, 21.6, -11.5, 4.8, 24.4, sand, 0xEAD0A8);
        addWall(-13.2, 0.0, 24.4, -11.5, 1.04, 27.8, sand, 0xDEC29A); // Подоконник Окна B!
        addWall(-13.2, 2.95, 24.4, -11.5, 4.8, 27.8, sand, 0xDEC29A);
        addWall(-13.2, 0.0, 27.8, -11.5, 4.8, 33.8, sand, 0xEAD0A8);
        addCrateStack(-10.3, 0.0, 26.0, 0.92, false, false);
        addCrateStack(-14.3, 0.0, 26.0, 0.92, false, false);

        addCrateStack(-24.8, 0.0, 23.8, 1.85, true, true);
        addCrateStack(-30.5, 0.0, 29.2, 1.75, false, true);
        addVehicleAlongZ(-16.8, 14.8, 0xB8A07C, false);

        // Высокий восточный Минарет с золотым куполом за Плентом B!
        smoothCylinders.add(new SmoothCylinderProp(-31.2, 0.0, 31.2, 1.50, 1.35, 14.5, 16, sand, 0xF2DCBA));
        smoothEllipsoids.add(new SmoothEllipsoidProp(-31.2, 15.2, 31.2, 1.58, 1.45, 1.58, 14, 7, 0xE8B844));
        addPalmTree(-18.5, 31.2, 5.6);

        // Callouts
        addCallout(15.5, 34.0, 17.5, 34.0, 0.8, "ПЛЕНТ A • ВЫСОКОЕ ПЛАТО");
        addCallout(16.5, 31.0, -23.0, -10.5, -1.0, "КОРОБКА • ВОРОТА ДЛИНЫ");
        addCallout(16.0, 34.0, -10.5, 17.5, -1.0, "ДЛИНА А (LONG A)");
        addCallout(5.0, 16.5, -3.0, 23.0, 0.8, "ПАРАПЕТ • ЗИГЗАГ (SHORT A)");
        addCallout(-34.0, -11.0, -16.0, 11.2, -1.0, "ТЁМКА • ТОННЕЛЬ B");
        addCallout(-34.0, -11.5, 11.2, 34.0, -1.0, "ПЛЕНТ B • БАСТИОН");
        addCallout(-11.5, 11.5, 14.8, 34.0, -1.0, "КАНЬОН CT-MID");
        addCallout(-11.0, 6.0, -18.0, 14.8, -1.0, "ЦЕНТР • ДВЕРИ МИДА");
        addCallout(-34.0, 34.0, -34.0, -18.0, -1.0, "ПУСТЫНЯ • БАЗА T");

        // Spawns & Waypoints
        allySpawns.add(new Vec3(0.0, 0.0, 28.0));
        allySpawns.add(new Vec3(5.5, 0.0, 28.0));
        allySpawns.add(new Vec3(-5.5, 0.0, 27.5));
        allySpawns.add(new Vec3(9.5, 0.0, 24.5));
        allySpawns.add(new Vec3(-8.5, 0.0, 21.5));
        allySpawns.add(new Vec3(20.5, 1.56, 27.5));

        botSpawns.add(new Vec3(-24.5, 0.0, -27.5));
        botSpawns.add(new Vec3(-14.0, 0.0, -28.5));
        botSpawns.add(new Vec3(0.0, 0.0, -29.0));
        botSpawns.add(new Vec3(12.0, 0.0, -28.5));
        botSpawns.add(new Vec3(23.0, 0.0, -28.0));
        botSpawns.add(new Vec3(28.0, 0.0, -26.0));
        botSpawns.add(new Vec3(-24.0, 0.0, -8.0));
        botSpawns.add(new Vec3(23.5, 0.0, -5.0));
        botSpawns.add(new Vec3(0.0, 0.0, -8.0));
        botSpawns.add(new Vec3(8.8, 1.56, 8.0));

        patrolWaypoints.add(new Vec3(23.0, 0.0, -25.0));
        patrolWaypoints.add(new Vec3(23.5, 0.0, -6.0));
        patrolWaypoints.add(new Vec3(26.5, 0.0, 9.0));
        patrolWaypoints.add(new Vec3(23.5, 1.56, 23.0));
        patrolWaypoints.add(new Vec3(8.8, 1.56, 12.0));
        patrolWaypoints.add(new Vec3(8.5, 0.0, -10.0));
        patrolWaypoints.add(new Vec3(0.0, 0.0, 6.0));
        patrolWaypoints.add(new Vec3(0.0, 0.0, 21.0));
        patrolWaypoints.add(new Vec3(-8.5, 0.0, 19.8));
        patrolWaypoints.add(new Vec3(-23.0, 0.0, 16.0));
        patrolWaypoints.add(new Vec3(-24.0, 0.0, -2.0));
        patrolWaypoints.add(new Vec3(-24.5, 0.0, -20.0));
    }

    // =========================================================================
    // 3. DE_MIRAGE — КОРОЛЕВСКИЙ ДВОРЕЦ МАРРАКЕША (68×68 м: Окно Снайпера, Колонный Зал Дворца, Коннектор, Маркет!)
    // =========================================================================
    private void buildMirage() {
        floorTexId = Renderer3D.TEX_MIRAGE_TILE;
        bombsiteASpot = new Vec3(20.5, 0.0, 18.5);
        bombsiteBSpot = new Vec3(-23.0, 0.0, 20.5);
        playerSpawn   = new Vec3(8.0, 0.0, 29.5);

        int plaster = Renderer3D.TEX_MIRAGE_PLASTER;
        int tile    = Renderer3D.TEX_MIRAGE_TILE;

        addPerimeterWalls(plaster, 0xF5CBB0, Renderer3D.TEX_ROOF_TILES, 0xB86248, 6.8);

        addFloorZone(-8.5, 0.004, -24.0, 8.5, 11.5, Renderer3D.TEX_FLOOR_SAND, 0xEAD4B6);
        addFloorZone(bombsiteBSpot.x - 4.4, 0.008, bombsiteBSpot.z - 4.2, bombsiteBSpot.x + 4.4, bombsiteBSpot.z + 4.2, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);
        addFloorZone(bombsiteASpot.x - 4.5, 0.008, bombsiteASpot.z - 4.2, bombsiteASpot.x + 4.5, bombsiteASpot.z + 4.2, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);

        // --- ЦЕНТР: ОТКРЫТЫЙ МИД + ОКНО СНАЙПЕРА НА ВЫСОТЕ (Y = 1.82 м) + КОННЕКТОР! ---
        addCrateStack(3.5, 0.0, -16.8, 1.55, false, true); // Top-Mid Cart!
        addWall(-8.5, 0.0, -8.5, -5.8, 2.25, -3.5, plaster, 0xF0C2A4);

        // Приподнятая комната «ОКНО СНАЙПЕРА» (Sniper's Nest / Window, Y = 1.82 м)!
        addWall(-7.5, 0.0, 10.5, 2.5, 1.82, 18.5, plaster, 0xE8BA9C);
        addStaircaseZ(-2.8, 1.8, 24.5, 18.5, 0.0, 1.82, tile, 0xE2CCB6);
        addWall(-7.5, 1.82, 10.5, -6.2, 5.2, 18.5, plaster, 0xF4C8AC);
        addWall(1.4,  1.82, 10.5, 2.5,  5.2, 18.5, plaster, 0xF4C8AC);
        addWall(-6.2, 1.82, 10.5, 1.4,  2.74, 11.4, plaster, 0xE6B89A); // Снайперский подоконник!
        addDecor(-6.2, 4.30, 10.2, 1.4, 5.2, 11.5, Renderer3D.TEX_SOLID, 0x2E74B8); // Синий марокканский навес!
        addRoof(-7.6, 5.1, 10.4, 2.6, 5.6, 18.6, plaster, 0xD8A688);

        // Крытый арочный Коннектор (Connector)!
        addWall(2.5, 0.0, 1.8, 3.6, 4.8, 10.5, plaster, 0xF0C4A8);
        addWall(9.6, 0.0, -4.5, 11.2, 5.2, 8.8, plaster, 0xF0C4A8);
        addRoof(2.5, 4.4, 2.0, 11.2, 5.2, 10.5, plaster, 0xDCA88A);

        // --- ЮГО-ВОСТОК: ПРОХОДНОЙ МРАМОРНЫЙ ЗАЛ ДВОРЦА (PALACE, Y = 1.82 м) С ЗОЛОТЫМ КУПОЛОМ! ---
        addWall(16.5, 0.0, -16.0, 33.8, 1.82, 8.5, tile, 0xEFE0CE);
        addStaircaseZ(22.0, 28.5, -23.0, -16.0, 0.0, 1.82, tile, 0xEAD8C4);
        addWall(16.5, 1.82, -16.0, 17.8, 5.8, 2.5, plaster, 0xF4CAAE);
        addWall(32.4, 1.82, -16.0, 33.8, 5.8, 8.5, plaster, 0xF4CAAE);
        // 4 резные круглые мраморные колонны внутри Дворца!
        double[][] palacePillars = {{21.5, -9.5}, {28.0, -9.5}, {21.5, -1.5}, {28.0, -1.5}};
        for (double[] pp : palacePillars) {
            addInvisibleCollider(pp[0] - 0.52, 1.82, pp[1] - 0.52, pp[0] + 0.52, 5.8, pp[1] + 0.52, false);
            smoothCylinders.add(new SmoothCylinderProp(pp[0], 1.82, pp[1], 0.58, 0.52, 3.98, 14, Renderer3D.TEX_CONCRETE, 0xF6ECE0));
        }
        addRoof(16.4, 5.8, -16.2, 33.8, 6.4, 8.6, plaster, 0xE2AE90);
        smoothEllipsoids.add(new SmoothEllipsoidProp(25.0, 7.2, -4.0, 4.8, 2.6, 4.8, 16, 8, 0xEAB844));
        // Деревянный балкон выхода из Дворца над Плентом А!
        addWall(19.5, 1.52, 8.5, 27.5, 1.82, 12.2, Renderer3D.TEX_CRATE_WOOD, 0x9C683E);

        // ПЛЕНТ А: Ступени (Stairs), Тройной ящик (Triple), Firebox и Синяя Билетная Касса (Ticket Booth)!
        addCrateStack(20.2, 0.0, 18.2, 1.85, true, true);
        addCrateStack(26.8, 0.0, 21.5, 1.75, false, true);
        addWall(11.2, 0.0, 10.5, 15.2, 1.56, 14.8, plaster, 0xECC0A4); // Позиция СТУПЕНИ (Stairs)!
        addStaircaseX(7.5, 11.2, 11.2, 14.2, 0.0, 1.56, tile, 0xE4CEB8);
        addWall(17.5, 0.0, 27.5, 21.8, 2.85, 30.8, Renderer3D.TEX_METAL_DOOR, 0x3E7EC2); // Ticket Booth!
        addCrateStack(16.1, 0.0, 28.8, 1.15, false, false);

        // --- СЕВЕРО-ЗАПАД: ГАЛЕРЕЯ АПАРТАМЕНТОВ B (Y = 1.56 м) + БЕЛЫЙ ФУРГОН (VAN) + ПЛЕНТ B + МАРКЕТ! ---
        addWall(-33.8, 0.0, -14.0, -25.8, 1.56, 12.5, plaster, 0xE8BA9C);
        addStaircaseZ(-31.8, -26.8, -20.5, -14.0, 0.0, 1.56, tile, 0xE2CCB6);
        addWall(-26.8, 1.56, -14.0, -25.6, 4.8, 4.5, plaster, 0xF2C6AA);
        addRoof(-33.8, 4.8, -14.0, -25.4, 5.4, 12.5, Renderer3D.TEX_ROOF_TILES, 0xB85E44);

        addVehicleAlongZ(-27.5, 15.2, 0xE6ECF2, true); // Белый Фургон (B Van)!
        addCrateStack(-22.8, 0.0, 20.5, 1.80, true, true);
        addWall(-19.5, 0.0, 13.8, -16.8, 4.8, 16.5, plaster, 0xF0C4A6); // Колонна Шорта B!

        // Крытое здание МАРКЕТА (Market / Kitchen) с окном и дверью на Плент B!
        addWall(-16.8, 0.0, 23.5, -15.4, 4.6, 26.2, plaster, 0xF6D2BA);
        addWall(-16.8, 0.0, 26.2, -15.4, 1.02, 29.5, plaster, 0xE8C0A4); // Окно Маркета!
        addWall(-16.8, 2.85, 26.2, -15.4, 4.6, 29.5, plaster, 0xE8C0A4);
        addWall(-16.8, 0.0, 32.0, -8.2, 4.6, 33.6, plaster, 0xF6D2BA);
        addWall(-9.5, 0.0, 23.5, -8.2, 4.6, 27.5, plaster, 0xF6D2BA);
        addRoof(-16.9, 4.5, 23.4, -8.1, 5.1, 33.6, plaster, 0xDCA88C);

        addPalmTree(-14.5, -26.5, 5.5);
        addPalmTree(14.5, 31.2, 5.2);

        // Callouts
        addCallout(16.0, 34.0, -16.5, 12.5, 1.2, "КОРОЛЕВСКИЙ ДВОРЕЦ");
        addCallout(-8.0, 3.0, 10.0, 19.0, 1.2, "ОКНО СНАЙПЕРА (WINDOW)");
        addCallout(-34.0, -25.0, -14.5, 13.0, 1.0, "АПАРТАМЕНТЫ B");
        addCallout(11.0, 34.0, 10.0, 34.0, -1.0, "ПЛЕНТ A • КАССА И СТУПЕНИ");
        addCallout(-34.0, -16.8, 12.5, 34.0, -1.0, "ПЛЕНТ B • ФУРГОН");
        addCallout(-16.8, -7.5, 22.5, 34.0, -1.0, "МАРКЕТ (КУХНЯ)");
        addCallout(2.5, 11.2, 1.5, 11.5, -1.0, "КОННЕКТОР");
        addCallout(-12.0, 11.0, -22.0, 10.0, -1.0, "ЦЕНТР • МИД МИРАЖА");
        addCallout(-12.0, 18.0, 20.0, 34.0, -1.0, "БАЗА СПЕЦНАЗА CT");

        // Spawns & Waypoints
        allySpawns.add(new Vec3(5.0, 0.0, 29.0));
        allySpawns.add(new Vec3(11.5, 0.0, 29.0));
        allySpawns.add(new Vec3(-2.0, 0.0, 28.5));
        allySpawns.add(new Vec3(-6.0, 0.0, 29.5));
        allySpawns.add(new Vec3(15.0, 0.0, 25.5));
        allySpawns.add(new Vec3(-2.0, 1.82, 15.0));

        botSpawns.add(new Vec3(0.0, 0.0, -29.0));
        botSpawns.add(new Vec3(8.5, 0.0, -29.0));
        botSpawns.add(new Vec3(18.0, 0.0, -28.5));
        botSpawns.add(new Vec3(25.0, 0.0, -27.5));
        botSpawns.add(new Vec3(-10.0, 0.0, -28.5));
        botSpawns.add(new Vec3(-22.0, 0.0, -27.5));
        botSpawns.add(new Vec3(-29.0, 0.0, -25.0));
        botSpawns.add(new Vec3(13.5, 0.0, -6.0));
        botSpawns.add(new Vec3(24.5, 1.82, -4.0));
        botSpawns.add(new Vec3(-14.0, 0.0, 4.0));

        patrolWaypoints.add(new Vec3(0.0, 0.0, -12.0));
        patrolWaypoints.add(new Vec3(-1.5, 0.0, 4.0));
        patrolWaypoints.add(new Vec3(6.2, 0.0, 6.5));
        patrolWaypoints.add(new Vec3(13.8, 0.0, 4.0));
        patrolWaypoints.add(new Vec3(20.5, 0.0, 18.0));
        patrolWaypoints.add(new Vec3(14.5, 0.0, 25.0));
        patrolWaypoints.add(new Vec3(24.8, 1.82, 2.0));
        patrolWaypoints.add(new Vec3(-12.5, 0.0, 8.5));
        patrolWaypoints.add(new Vec3(-22.5, 0.0, 19.5));
        patrolWaypoints.add(new Vec3(-12.5, 0.0, 29.8));
        patrolWaypoints.add(new Vec3(-29.0, 1.56, -2.0));
        patrolWaypoints.add(new Vec3(13.5, 0.0, -22.0));
    }

    // =========================================================================
    // 4. DE_NUKE — СЕКРЕТНЫЙ ЯДЕРНЫЙ КОМПЛЕКС (68×68 м: Улица с Красной и Силосом, Зал Реактора А + Мостки HEAVEN!)
    // =========================================================================
    private void buildNuke() {
        floorTexId = Renderer3D.TEX_NUKE_FLOOR;
        bombsiteASpot = new Vec3(11.5, 0.0, 4.5);
        bombsiteBSpot = new Vec3(-7.5, 0.0, 24.5);
        playerSpawn   = new Vec3(24.0, 0.0, 27.5);

        int panel = Renderer3D.TEX_NUKE_PANEL;
        int conc  = Renderer3D.TEX_CONCRETE;

        addPerimeterWalls(panel, 0xD2DCE8, conc, 0xE6B428, 7.6);

        // Тёмный асфальт Улицы (Outside) и зоны Плентов A/B
        addFloorZone(-33.5, 0.004, -33.0, -14.8, 33.0, conc, 0x7A8490);
        addFloorZone(bombsiteASpot.x - 4.8, 0.008, bombsiteASpot.z - 4.4, bombsiteASpot.x + 4.8, bombsiteASpot.z + 4.4, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);
        addFloorZone(bombsiteBSpot.x - 4.4, 0.008, bombsiteBSpot.z - 4.2, bombsiteBSpot.x + 4.4, bombsiteBSpot.z + 4.2, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);

        // Гиперболическая градирня АЭС за северным периметром!
        smoothCylinders.add(new SmoothCylinderProp(-24.0, 7.6, MAX_Z + 6.5, 6.2, 4.1, 7.5, 18, conc, 0xC4CED8));
        smoothCylinders.add(new SmoothCylinderProp(-24.0, 15.1, MAX_Z + 6.5, 4.1, 4.8, 3.8, 18, conc, 0xD2DCE6));
        smoothCylinders.add(new SmoothCylinderProp(-24.0, 18.6, MAX_Z + 6.5, 4.75, 4.88, 0.7, 18, Renderer3D.TEX_SOLID, 0xDC3E32));

        // --- ЗАПАД: ОТКРЫТАЯ УЛИЦА (OUTSIDE) + КРАСНЫЙ МОРСКОЙ КОНТЕЙНЕР + ГИГАНТСКИЙ СИЛОС (MARSHMALLOW, Y = 2.60 м)! ---
        addWall(-27.5, 0.0, -6.5, -23.5, 3.15, 4.5, Renderer3D.TEX_METAL_DOOR, 0xC8342E); // КРАСНЫЙ КОНТЕЙНЕР!
        addWall(-31.5, 0.0, 13.5, -27.8, 3.05, 22.5, Renderer3D.TEX_METAL_DOOR, 0x2E6EB4); // Синий контейнер у Ангара!
        addVehicleAlongZ(-21.5, 25.5, 0x38485A, true);
        addBarrelCluster(-20.2, 0.0, 9.5, 0xE6B422, 0x2E8B57);

        // ГИГАНТСКИЙ КРУГЛЫЙ СИЛОС «ЗЕФИРКА» (MARSHMALLOW) с внешней стальной лестницей на отметку Y = 2.60 м!
        addInvisibleCollider(-21.2, 0.0, -19.2, -15.8, 2.60, -13.8, true);
        smoothCylinders.add(new SmoothCylinderProp(-18.5, 0.0, -16.5, 3.25, 3.25, 2.60, 20, conc, 0xDCE4EC));
        smoothCylinders.add(new SmoothCylinderProp(-18.5, 2.25, -16.5, 3.34, 3.34, 0.32, 20, Renderer3D.TEX_SOLID, 0xE8B428));
        addStaircaseZ(-22.8, -19.8, -26.8, -19.2, 0.0, 2.60, conc, 0x9AA6B4);

        // --- ЦЕНТР И ВОСТОК: КРЫТЫЙ РЕАКТОРНЫЙ ЗАЛ ПЛЕНТА A + БУДКА HUT + МОСТКИ 2-ГО ЯРУСА «ДЕВЯТКА / HEAVEN» (Y = 2.60 м)! ---
        addWall(-15.0, 0.0, -12.5, -13.4, 7.2, 1.5, panel, 0xCED8E4);
        addWall(-15.0, 0.0, 7.2,   -13.4, 7.2, 16.5, panel, 0xCED8E4);
        addWall(-15.0, 4.2, 1.5,   -13.4, 7.2, 7.2, conc, 0xE2AE28); // Вход Mini с улицы!

        addWall(-13.4, 0.0, -13.2, -1.5, 7.2, -11.6, panel, 0xD2DCE8);
        addWall(9.2,   0.0, -13.2, 24.5, 7.2, -11.6, panel, 0xD2DCE8);
        // Знаменитая синяя БУДКА «HUT» (выход Т в Реакторный Зал А, по крыше Hut можно ходить!)
        addWall(-1.5, 0.0, -11.6, -0.4, 3.35, -5.8, Renderer3D.TEX_METAL_DOOR, 0x3874B8);
        addWall(4.2,  0.0, -11.6, 5.2,  3.35, -8.2, Renderer3D.TEX_METAL_DOOR, 0x3874B8);
        addWall(-1.6, 3.15, -11.7, 5.3, 3.55, -5.7, Renderer3D.TEX_METAL_DOOR, 0x2E629E);

        // ДВА ГИГАНТСКИХ ЖЁЛТЫХ ЯДЕРНЫХ РЕАКТОРА В ЦЕНТРЕ ПЛЕНТА A!
        double[] siloX = {8.2, 15.2};
        for (double sx : siloX) {
            addInvisibleCollider(sx - 1.75, 0.0, 4.5 - 1.75, sx + 1.75, 5.8, 4.5 + 1.75, true);
            smoothCylinders.add(new SmoothCylinderProp(sx, 0.0, 4.5, 2.05, 2.05, 5.2, 18, Renderer3D.TEX_SOLID, 0xEEBC2A));
            smoothCylinders.add(new SmoothCylinderProp(sx, 2.4, 4.5, 2.14, 2.14, 0.38, 18, Renderer3D.TEX_SOLID, 0x262E38));
            smoothEllipsoids.add(new SmoothEllipsoidProp(sx, 5.2, 4.5, 2.05, 0.72, 2.05, 18, 7, 0xF6CA3E));
        }

        // НАВЕСНЫЕ СТАЛЬНЫЕ МОСТКИ 2-ГО ЯРУСА «ДЕВЯТКА / HEAVEN» (Y = 2.60 м) С ПОЛНОЦЕННОЙ ЛЕСТНИЦЕЙ CT!
        addWall(19.5, 2.30, -8.5, 25.8, 2.60, 14.5, conc, 0x8E9BA8);
        addWall(4.5,  2.30, 12.2, 25.8, 2.60, 15.8, conc, 0x8E9BA8);
        addStaircaseZ(21.2, 25.4, 25.2, 15.8, 0.0, 2.60, conc, 0xA2B0BE);
        addWall(19.3, 2.60, -8.5, 19.7, 3.48, 2.0, Renderer3D.TEX_SOLID, 0xE6B428);
        addWall(6.5,  2.60, 12.0, 14.5, 3.48, 12.4, Renderer3D.TEX_SOLID, 0xE6B428);

        // Потолок Реакторного Зала А (защищает от осадков!) + жёлтая мостовая кран-балка под потолком!
        addRoof(-14.5, 7.2, -12.5, 26.2, 7.8, 16.5, conc, 0x6E7A88);
        addDecor(-13.5, 6.3, 3.8, 25.0, 7.05, 5.2, Renderer3D.TEX_SOLID, 0xE8B428);

        // --- СЕВЕР: ЗАЛ ОХЛАЖДЕНИЯ И ПЛЕНТ B С БАССЕЙНОМ ЧЕРЕНКОВА И СТЕРЖНЕМ РЕАКТОРА! ---
        addFloorZone(-13.8, 0.012, 28.4, -1.5, 32.8, Renderer3D.TEX_WATER, 0x3CE0FF); // Светящийся бассейн Черенкова!
        addWall(-14.0, 0.0, 28.0, -1.3, 0.72, 28.4, conc, 0xE6B428);

        addInvisibleCollider(-9.0, 0.0, 23.0, -6.0, 4.8, 26.0, true);
        smoothCylinders.add(new SmoothCylinderProp(-7.5, 0.0, 24.5, 2.15, 2.15, 0.85, 16, conc, 0x6C7886));
        smoothCylinders.add(new SmoothCylinderProp(-7.5, 0.85, 24.5, 1.60, 1.60, 4.2, 16, Renderer3D.TEX_WATER, 0x48DCFF));
        addWall(0.5, 0.0, 21.5, 2.2, 2.15, 27.5, Renderer3D.TEX_METAL_DOOR, 0x526274);
        addCrateStack(-12.2, 0.0, 21.2, 1.65, true, true);

        // Массивные стальные трубы охлаждения на восточной Рампе
        smoothCylinders.add(new SmoothCylinderProp(31.8, 0.0, -8.0, 0.85, 0.85, 6.8, 12, conc, 0xBAC4CE));
        smoothCylinders.add(new SmoothCylinderProp(31.8, 0.0, -4.2, 0.85, 0.85, 6.8, 12, conc, 0xBAC4CE));

        // Callouts
        addCallout(4.0, 26.5, -9.0, 16.5, 1.8, "ДЕВЯТКА • МОСТКИ HEAVEN");
        addCallout(-23.0, -15.0, -20.5, -13.0, 1.8, "ВЕРШИНА СИЛОСА (ЗЕФИРКА)");
        addCallout(-34.0, -15.0, -34.0, 34.0, -1.0, "УЛИЦА • КРАСНАЯ");
        addCallout(-14.5, 26.0, -12.0, 16.5, -1.0, "ЗАЛ РЕАКТОРА • ПЛЕНТ A");
        addCallout(-15.0, 10.5, 16.5, 34.0, -1.0, "ОХЛАЖДЕНИЕ • ПЛЕНТ B");
        addCallout(-14.5, 25.0, -34.0, -12.0, -1.0, "ВЕСТИБЮЛЬ T • БУДКА HUT");
        addCallout(10.5, 34.0, 16.5, 34.0, -1.0, "ВЕРТОЛЁТНАЯ БАЗА CT");
        addCallout(25.5, 34.0, -16.0, 16.5, -1.0, "ВОСТОЧНАЯ РАМПА");

        // Spawns & Waypoints
        allySpawns.add(new Vec3(20.0, 0.0, 28.0));
        allySpawns.add(new Vec3(26.5, 0.0, 28.0));
        allySpawns.add(new Vec3(14.5, 0.0, 26.5));
        allySpawns.add(new Vec3(28.5, 0.0, 20.0));
        allySpawns.add(new Vec3(12.0, 2.60, 14.0));
        allySpawns.add(new Vec3(-18.0, 0.0, 29.0));

        botSpawns.add(new Vec3(-6.0, 0.0, -29.0));
        botSpawns.add(new Vec3(2.0, 0.0, -29.0));
        botSpawns.add(new Vec3(12.0, 0.0, -28.5));
        botSpawns.add(new Vec3(-18.0, 0.0, -29.0));
        botSpawns.add(new Vec3(-28.0, 0.0, -28.0));
        botSpawns.add(new Vec3(26.0, 0.0, -26.5));
        botSpawns.add(new Vec3(-20.0, 0.0, -6.0));
        botSpawns.add(new Vec3(2.0, 0.0, -8.5));
        botSpawns.add(new Vec3(28.5, 0.0, -2.0));
        botSpawns.add(new Vec3(-18.5, 2.60, -16.5));

        patrolWaypoints.add(new Vec3(-20.5, 0.0, -8.0));
        patrolWaypoints.add(new Vec3(-20.5, 0.0, 12.0));
        patrolWaypoints.add(new Vec3(-11.0, 0.0, 4.5));
        patrolWaypoints.add(new Vec3(2.0, 0.0, -3.5));
        patrolWaypoints.add(new Vec3(11.5, 0.0, 9.5));
        patrolWaypoints.add(new Vec3(22.5, 2.60, 4.0));
        patrolWaypoints.add(new Vec3(23.2, 0.0, 26.5));
        patrolWaypoints.add(new Vec3(-3.5, 0.0, 23.5));
        patrolWaypoints.add(new Vec3(-18.5, 0.0, 20.5));
        patrolWaypoints.add(new Vec3(28.0, 0.0, 4.0));
        patrolWaypoints.add(new Vec3(7.0, 0.0, -16.0));
        patrolWaypoints.add(new Vec3(-29.5, 0.0, 4.0));
    }

    // =========================================================================
    // МЕТОДЫ ГЕОМЕТРИИ, КОЛЛИЗИЙ, ЛУЧЕЙ И ВЫСОКОСКОРОСТНОГО РЕНДЕРА
    // =========================================================================

    public String getCalloutName(double x, double y, double z) {
        for (int i = 0, n = calloutZones.size(); i < n; i++) {
            CalloutZone cz = calloutZones.get(i);
            if (x >= cz.minX && x <= cz.maxX && z >= cz.minZ && z <= cz.maxZ && y >= cz.minY) {
                return cz.name;
            }
        }
        return currentTheme.codeName;
    }

    public Vec3 getPlayerSpawn() {
        return playerSpawn;
    }

    public List<Vec3> getBotSpawns() {
        return botSpawns;
    }

    public List<Vec3> getAllySpawns() {
        return allySpawns;
    }

    public List<Vec3> getPatrolWaypoints() {
        return patrolWaypoints;
    }

    public List<Vec3.Box> getBoxes() {
        return boxes;
    }

    public Vec3 getBombsiteASpot() {
        return bombsiteASpot;
    }

    public Vec3 getBombsiteBSpot() {
        return bombsiteBSpot;
    }

    public boolean isBoxPenetrable(Vec3.Box box) {
        return getMaterialPenetrability(box) > 0.0;
    }

    /**
     * Пробиваемость материала (CS2 9.0): дерево легко, жесть/двери средне, штукатурка и плитка хуже,
     * песчаник/бетон/булыжник — только тонкие участки мощным калибром. Пол карты и вода не пробиваются.
     */
    public double getMaterialPenetrability(Vec3.Box box) {
        if (box == null) return 0.0;
        switch (box.textureId) {
            case Renderer3D.TEX_CRATE_WOOD:      return 1.60;
            case Renderer3D.TEX_METAL_DOOR:      return 1.10;
            case Renderer3D.TEX_CRATE_MILITARY:  return 0.80;
            case Renderer3D.TEX_SOLID:           return 0.70;
            case Renderer3D.TEX_MIRAGE_PLASTER:  return 0.55;
            case Renderer3D.TEX_NUKE_PANEL:      return 0.60;
            case Renderer3D.TEX_ROOF_TILES:      return 0.50;
            case Renderer3D.TEX_INFERNO_STUCCO:  return 0.45;
            case Renderer3D.TEX_WALL_SANDSTONE:  return 0.35;
            case Renderer3D.TEX_CONCRETE:        return 0.30;
            case Renderer3D.TEX_INFERNO_COBBLE:  return 0.25;
            default:                             return 0.0;
        }
    }

    public String getMaterialName(Vec3.Box box) {
        if (box == null) return "ГРУНТ";
        switch (box.textureId) {
            case Renderer3D.TEX_CRATE_WOOD:      return "ДЕРЕВО";
            case Renderer3D.TEX_METAL_DOOR:      return "ЖЕСТЬ";
            case Renderer3D.TEX_CRATE_MILITARY:  return "ЯЩИК";
            case Renderer3D.TEX_MIRAGE_PLASTER:  return "ШТУКАТУРКА";
            case Renderer3D.TEX_NUKE_PANEL:      return "ПАНЕЛЬ";
            case Renderer3D.TEX_CONCRETE:        return "БЕТОН";
            default:                             return "КАМЕНЬ";
        }
    }

    /** Толщина препятствия вдоль луча от точки входа (длина пути пули внутри AABB). */
    public double getBoxThicknessAlongRay(Vec3.Box b, Vec3 entry, Vec3 dir) {
        double tExit = Double.MAX_VALUE;
        double[] o = {entry.x, entry.y, entry.z}, d = {dir.x, dir.y, dir.z};
        double[] mn = {b.minX, b.minY, b.minZ}, mx = {b.maxX, b.maxY, b.maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) continue;
            double t1 = (mn[i] - o[i]) / d[i], t2 = (mx[i] - o[i]) / d[i];
            double far = Math.max(t1, t2);
            if (far < tExit) tExit = far;
        }
        return (tExit == Double.MAX_VALUE) ? 99.0 : Math.max(0.0, tExit);
    }

    public Vec3.RayHit raycast(Vec3 origin, Vec3 dir, double maxDist) {
        return raycastIgnoringBox(origin, dir, maxDist, null);
    }

    public Vec3.RayHit raycastIgnoringBox(Vec3 origin, Vec3 dir, double maxDist, Vec3.Box ignoredBox) {
        double bestT = maxDist;
        Vec3 bestNormal = null;
        Vec3.Box bestBox = null;
        Vec3 tmpNormal = new Vec3();

        if (dir.y < -1e-6 && origin.y > 0.0) {
            double tFloor = -origin.y / dir.y;
            if (tFloor >= 0.0 && tFloor < bestT) {
                double hx = origin.x + dir.x * tFloor;
                double hz = origin.z + dir.z * tFloor;
                if (hx >= MIN_X && hx <= MAX_X && hz >= MIN_Z && hz <= MAX_Z) {
                    bestT = tFloor;
                    bestNormal = new Vec3(0.0, 1.0, 0.0);
                }
            }
        }

        for (int i = 0, n = boxes.size(); i < n; i++) {
            Vec3.Box b = boxes.get(i);
            if (!b.collidable || b == ignoredBox) continue;
            double t = b.intersectRay(origin, dir, bestT, tmpNormal);
            if (t >= 0.0 && t < bestT) {
                bestT = t;
                bestNormal = tmpNormal.copy();
                bestBox = b;
            }
        }

        if (bestNormal == null) return null;
        return new Vec3.RayHit(bestT, origin.add(dir.scale(bestT)), bestNormal, bestBox);
    }

    public boolean hasLineOfSight(Vec3 from, Vec3 to) {
        return hasLineOfSight(from, to, null);
    }

    public boolean hasLineOfSight(Vec3 from, Vec3 to, List<Vec3.SmokeCloud> smokes) {
        Vec3 diff = to.sub(from);
        double dist = diff.length();
        if (dist < 1e-4) return true;
        Vec3 dir = diff.scale(1.0 / dist);

        for (int i = 0, n = boxes.size(); i < n; i++) {
            Vec3.Box b = boxes.get(i);
            if (!b.collidable) continue;
            double t = b.intersectRay(from, dir, dist - 0.05, null);
            if (t >= 0.0 && t < dist - 0.05) {
                return false;
            }
        }

        if (smokes != null) {
            for (int i = 0, n = smokes.size(); i < n; i++) {
                if (smokes.get(i).blocksSegment(from, to)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean collides(double px, double py, double pz, double radius, double height) {
        if (px - radius < MIN_X || px + radius > MAX_X
                || pz - radius < MIN_Z || pz + radius > MAX_Z
                || py < -0.001) {
            return true;
        }
        double minX = px - radius, maxX = px + radius;
        double minY = py,          maxY = py + height;
        double minZ = pz - radius, maxZ = pz + radius;

        for (int i = 0, n = boxes.size(); i < n; i++) {
            Vec3.Box b = boxes.get(i);
            if (b.collidable && b.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }
        }
        return false;
    }

    public double getSupportingFloorY(double px, double pz, double radius, double currentFeetY) {
        double supportY = 0.0;
        double minX = px - radius, maxX = px + radius;
        double minZ = pz - radius, maxZ = pz + radius;

        for (int i = 0, n = boxes.size(); i < n; i++) {
            Vec3.Box b = boxes.get(i);
            if (!b.collidable) continue;
            if (maxX > b.minX && minX < b.maxX && maxZ > b.minZ && minZ < b.maxZ) {
                if (b.maxY <= currentFeetY + 0.14 && b.maxY > supportY) {
                    supportY = b.maxY;
                }
            }
        }
        return supportY;
    }

    public void renderFloorOnly(Renderer3D renderer) {
        // Сначала рисуем зональные покрытия и метки BOMBSITE (Early-Z позволит drawFloorGrid пропустить их!)
        for (int i = 0, n = floorZones.size(); i < n; i++) {
            FloorZone fz = floorZones.get(i);
            renderer.drawFloorZoneQuad(fz.x0, fz.y, fz.z0, fz.x1, fz.z1, fz.texId, fz.tint);
        }
        renderer.drawFloorGrid(MIN_X, MAX_X, MIN_Z, MAX_Z, 8.5, floorTexId, 0.26);
    }

    public void renderShadowsOnly(Renderer3D renderer, GameSettings.ShadowMode shadowMode, List<Bot> bots) {
        if (shadowMode == GameSettings.ShadowMode.SUN_PROJECTED) {
            for (int i = 0, n = boxes.size(); i < n; i++) {
                renderer.drawBoxSunShadow(boxes.get(i), MIN_X, MAX_X, MIN_Z, MAX_Z);
            }
        } else if (shadowMode == GameSettings.ShadowMode.BLOBS) {
            for (int i = 0, n = boxes.size(); i < n; i++) {
                Vec3.Box b = boxes.get(i);
                if (b.castsShadow && b.minY <= 0.02 && (b.maxX - b.minX) < 10.0 && (b.maxZ - b.minZ) < 10.0) {
                    renderer.drawBlobShadow(
                        (b.minX + b.maxX) * 0.5, 0.0, (b.minZ + b.maxZ) * 0.5,
                        (b.maxX - b.minX) * 0.56, (b.maxZ - b.minZ) * 0.56
                    );
                }
            }
        }

        if (shadowMode != GameSettings.ShadowMode.OFF && bots != null) {
            for (int i = 0, n = bots.size(); i < n; i++) {
                Bot bot = bots.get(i);
                double floorY = bot.getPosition().y;
                if (floorY <= 2.8) {
                    double scale = bot.getModelScale();
                    double r = (bot.isAlive() ? 0.42 : 0.65) * scale;
                    renderer.drawBlobShadow(bot.getPosition().x, floorY, bot.getPosition().z, r, r);
                }
            }
        }
    }

    public void renderFloorAndShadows(Renderer3D renderer, GameSettings.ShadowMode shadowMode, List<Bot> bots) {
        renderFloorOnly(renderer);
        renderShadowsOnly(renderer, shadowMode, bots);
    }

    public void renderStructures(Renderer3D renderer) {
        for (int i = 0, n = boxes.size(); i < n; i++) {
            Vec3.Box b = boxes.get(i);
            if (b.textureId >= 0) {
                renderer.drawAxisAlignedBox(b);
            }
        }
        for (int i = 0, n = smoothCylinders.size(); i < n; i++) {
            SmoothCylinderProp c = smoothCylinders.get(i);
            renderer.drawWorldCylinder(c.cx, c.bottomY, c.cz, c.radBottom, c.radTop, c.height, c.segments, c.texId, c.color);
        }
        for (int i = 0, n = smoothEllipsoids.size(); i < n; i++) {
            SmoothEllipsoidProp e = smoothEllipsoids.get(i);
            renderer.drawWorldEllipsoid(e.cx, e.cy, e.cz, e.rx, e.ry, e.rz, e.segments, e.rings, e.color);
        }
    }

    public boolean hasOverheadRoof(double x, double y, double z) {
        for (int i = 0, n = overheadRoofs.size(); i < n; i++) {
            Vec3.Box b = overheadRoofs.get(i);
            if (y < b.maxY && x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ) {
                return true;
            }
        }
        return false;
    }
}
