import java.util.ArrayList;
import java.util.List;

/**
 * Мульти-картовая 3D-арена CS-2 JAVA 5.0 (Source 2 Smooth Architecture):
 * Поддерживает 4 уникальные тактические арены с гладкими цилиндрическими колоннами, куполами, деревьями и фонтаном:
 * 1) INFERNO — европейский старый город с каменной брусчаткой, виллами с черепицей, Фонтаном, Колокольней и Кипарисами;
 * 2) DUST_II — песчаные стены, центральные Двойные двери Мида, гладкий купол Минарета, Пленты A и B;
 * 3) MIRAGE  — марокканский дворец с круглой колоннадой, Окном снайпера на Миду, Тикет-боксом и Пальмами;
 * 4) NUKE    — ядерный комплекс с круглыми желтыми реакторами Силоса A, градирней АЭС и бочками B.
 */
public class Map {
    public static final double MIN_X = -24.0;
    public static final double MAX_X = 24.0;
    public static final double MIN_Z = -24.0;
    public static final double MAX_Z = 24.0;

    private static class SmoothCylinderProp {
        final double cx, bottomY, cz, radBottom, radTop, height;
        final int segments, texId, color;

        SmoothCylinderProp(double cx, double bottomY, double cz,
                           double radBottom, double radTop, double height,
                           int segments, int texId, int color) {
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

    private static class SmoothEllipsoidProp {
        final double cx, cy, cz, rx, ry, rz;
        final int segments, rings, color;

        SmoothEllipsoidProp(double cx, double cy, double cz,
                            double rx, double ry, double rz,
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

    private GameSettings.MapTheme currentTheme = GameSettings.MapTheme.INFERNO;
    private final List<Vec3.Box> boxes = new ArrayList<>();
    private final List<SmoothCylinderProp> smoothCylinders = new ArrayList<>();
    private final List<SmoothEllipsoidProp> smoothEllipsoids = new ArrayList<>();
    private final List<Vec3> botSpawns = new ArrayList<>();
    private final List<Vec3> allySpawns = new ArrayList<>();
    private final List<Vec3> patrolWaypoints = new ArrayList<>();
    private Vec3 playerSpawn = new Vec3(0.0, 0.0, -19.5);
    private Vec3 bombsiteASpot = new Vec3(17.2, 0.56, 14.6);
    private Vec3 bombsiteBSpot = new Vec3(-16.2, 0.0, 13.6);
    private double siteAFloorY = 0.56;
    private int floorTexId = Renderer3D.TEX_FLOOR_SAND;

    public Map() {
        rebuildForTheme(GameSettings.MapTheme.INFERNO);
    }

    public GameSettings.MapTheme getCurrentTheme() { return currentTheme; }
    public List<Vec3.Box> getBoxes() { return boxes; }
    public Vec3 getPlayerSpawn() { return playerSpawn.copy(); }
    public List<Vec3> getBotSpawns() { return botSpawns; }
    public List<Vec3> getAllySpawns() { return allySpawns; }
    public List<Vec3> getPatrolWaypoints() { return patrolWaypoints; }
    public Vec3 getBombsiteASpot() { return bombsiteASpot.copy(); }
    public Vec3 getBombsiteBSpot() { return bombsiteBSpot.copy(); }

    public void rebuildForTheme(GameSettings.MapTheme theme) {
        this.currentTheme = (theme != null) ? theme : GameSettings.MapTheme.INFERNO;
        boxes.clear();
        smoothCylinders.clear();
        smoothEllipsoids.clear();
        botSpawns.clear();
        allySpawns.clear();
        patrolWaypoints.clear();

        switch (this.currentTheme) {
            case INFERNO:
                buildInfernoArena();
                break;
            case MIRAGE:
                buildMirageArena();
                break;
            case NUKE:
                buildNukeArena();
                break;
            case DUST_II:
            default:
                buildDust2Arena();
                break;
        }
        setupCommonAllySpawns();
    }

    private void setupCommonAllySpawns() {
        allySpawns.add(new Vec3(-3.2, 0.0, -19.2));
        allySpawns.add(new Vec3(3.2,  0.0, -19.2));
        allySpawns.add(new Vec3(-6.4, 0.0, -18.4));
        allySpawns.add(new Vec3(6.4,  0.0, -18.4));
    }

    private void addInvisibleCollider(double x0, double y0, double z0, double x1, double y1, double z1, boolean shadow) {
        // collidable=true, textureId=-1 означает невидимый физический хитбокс для гладких цилиндров/колонн
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1, -1, 0xFFFFFF, 0.0, true, shadow));
    }

    private void addWallTex(double x0, double y0, double z0, double x1, double y1, double z1, int texId) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1, texId, 0xFFFFFF, 0.32, true, true));
    }

    private void addWall(double x0, double y0, double z0, double x1, double y1, double z1) {
        addWallTex(x0, y0, z0, x1, y1, z1, Renderer3D.TEX_WALL_SANDSTONE);
    }

    private void addConcrete(double x0, double y0, double z0, double x1, double y1, double z1, int tint) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1,
                Renderer3D.TEX_CONCRETE, tint, 0.42, true, true));
    }

    private void addWoodCrate(double cx, double y0, double cz, double sizeXZ, double height) {
        double h = sizeXZ * 0.5;
        boxes.add(new Vec3.Box(cx - h, y0, cz - h, cx + h, y0 + height, cz + h,
                Renderer3D.TEX_CRATE_WOOD, 0xFFFFFF, 0.0, true, true));
    }

    private void addMilitaryCrate(double x0, double y0, double z0, double x1, double y1, double z1) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1,
                Renderer3D.TEX_CRATE_MILITARY, 0xFFFFFF, 0.0, true, true));
    }

    private void addDecor(double x0, double y0, double z0, double x1, double y1, double z1, int tex, int tint, boolean shadow) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1, tex, tint, 0.28, false, shadow));
    }

    private void addBarrel(double cx, double y0, double cz, int tint) {
        addInvisibleCollider(cx - 0.42, y0, cz - 0.42, cx + 0.42, y0 + 1.24, cz + 0.42, true);
        smoothCylinders.add(new SmoothCylinderProp(cx, y0, cz, 0.43, 0.43, 1.24, 12, Renderer3D.TEX_SOLID, tint));
        smoothCylinders.add(new SmoothCylinderProp(cx, y0 + 0.36, cz, 0.45, 0.45, 0.08, 12, Renderer3D.TEX_SOLID, 0x2B3038));
        smoothCylinders.add(new SmoothCylinderProp(cx, y0 + 0.82, cz, 0.45, 0.45, 0.08, 12, Renderer3D.TEX_SOLID, 0x2B3038));
    }

    private void addPalmTree(double cx, double cz) {
        smoothCylinders.add(new SmoothCylinderProp(cx, 0.0, cz, 0.28, 0.19, 5.7, 10, Renderer3D.TEX_SOLID, 0x76502E));
        smoothEllipsoids.add(new SmoothEllipsoidProp(cx, 5.9, cz, 1.85, 0.55, 1.85, 10, 5, 0x35964C));
        smoothEllipsoids.add(new SmoothEllipsoidProp(cx + 0.55, 5.65, cz - 0.4, 1.35, 0.42, 0.95, 8, 4, 0x2D8040));
        smoothEllipsoids.add(new SmoothEllipsoidProp(cx - 0.55, 5.65, cz + 0.4, 1.35, 0.42, 0.95, 8, 4, 0x42AD5B));
    }

    private void addCypressTree(double cx, double cz, double height) {
        smoothCylinders.add(new SmoothCylinderProp(cx, 0.0, cz, 0.22, 0.16, height * 0.28, 8, Renderer3D.TEX_SOLID, 0x5E4026));
        smoothEllipsoids.add(new SmoothEllipsoidProp(cx, height * 0.52, cz, 0.88, height * 0.34, 0.88, 10, 6, 0x286B38));
        smoothEllipsoids.add(new SmoothEllipsoidProp(cx, height * 0.82, cz, 0.56, height * 0.26, 0.56, 10, 5, 0x338244));
    }

    // =========================================================================
    //  КАРТА 0 (НОВАЯ!): INFERNO — ЕВРОПЕЙСКИЙ СТАРЫЙ ГОРОД С ФОНТАНОМ И КОЛОКОЛЬНЕЙ
    // =========================================================================
    private void buildInfernoArena() {
        floorTexId = Renderer3D.TEX_INFERNO_COBBLE;
        playerSpawn = new Vec3(0.0, 0.0, -19.5);
        bombsiteASpot = new Vec3(17.2, 0.0, 14.8);
        bombsiteBSpot = new Vec3(-16.5, 0.0, 14.2);
        siteAFloorY = 0.0;

        double wallH = 5.6;
        double t = 1.2;
        int stucco = Renderer3D.TEX_INFERNO_STUCCO;
        int roof = Renderer3D.TEX_ROOF_TILES;

        // Внешние фасады итальянских вилл с черепичными карнизами
        addWallTex(MIN_X - t, 0.0, MIN_Z - t, MAX_X + t, wallH, MIN_Z, stucco);
        addWallTex(MIN_X - t, 0.0, MAX_Z,     MAX_X + t, wallH, MAX_Z + t, stucco);
        addWallTex(MIN_X - t, 0.0, MIN_Z,     MIN_X,     wallH, MAX_Z, stucco);
        addWallTex(MAX_X,     0.0, MIN_Z,     MAX_X + t, wallH, MAX_Z, stucco);

        addDecor(MIN_X - t, wallH, MAX_Z - 0.3, MAX_X + t, wallH + 0.75, MAX_Z + t, roof, 0xFFFFFF, false);
        addDecor(MIN_X - t, wallH, MIN_Z - t, MAX_X + t, wallH + 0.75, MIN_Z + 0.3, roof, 0xFFFFFF, false);
        addDecor(MIN_X - t, wallH, MIN_Z, MIN_X + 0.3, wallH + 0.75, MAX_Z, roof, 0xFFFFFF, false);
        addDecor(MAX_X - 0.3, wallH, MIN_Z, MAX_X + t, wallH + 0.75, MAX_Z, roof, 0xFFFFFF, false);

        // Высокая итальянская Колокольня (Bell Tower) над площадью!
        addDecor(3.5, wallH, MAX_Z + 0.8, 8.5, wallH + 8.6, MAX_Z + 5.8, stucco, 0xFFF3E2, false);
        smoothCylinders.add(new SmoothCylinderProp(6.0, wallH + 8.6, MAX_Z + 3.3, 3.1, 0.15, 3.4, 8, roof, 0xD86848));
        smoothEllipsoids.add(new SmoothEllipsoidProp(6.0, wallH + 7.2, MAX_Z + 3.3, 0.65, 0.75, 0.65, 10, 5, 0xE6B83A));

        // Кипарисы по периметру старинных улочек
        addCypressTree(-10.5, -15.5, 6.8);
        addCypressTree(10.8,  -15.2, 6.6);
        addCypressTree(-21.5,   3.5, 7.2);
        addCypressTree(21.5,    8.5, 7.0);

        // ЗНАМЕНИТЫЙ КАМЕННЫЙ ФОНТАН НА ЦЕНТРАЛЬНОЙ ПЛОЩАДИ / B-SITE INFERNO!
        double fx = 0.0, fz = -5.5;
        addInvisibleCollider(fx - 2.1, 0.0, fz - 2.1, fx + 2.1, 1.05, fz + 2.1, true);
        smoothCylinders.add(new SmoothCylinderProp(fx, 0.0,  fz, 2.35, 2.45, 0.82, 16, Renderer3D.TEX_CONCRETE, 0xDDD4C6));
        smoothCylinders.add(new SmoothCylinderProp(fx, 0.02, fz, 2.05, 2.05, 0.76, 16, Renderer3D.TEX_WATER, 0xFFFFFF));
        smoothCylinders.add(new SmoothCylinderProp(fx, 0.75, fz, 0.46, 0.32, 1.55, 12, Renderer3D.TEX_CONCRETE, 0xEAE2D6));
        smoothCylinders.add(new SmoothCylinderProp(fx, 1.65, fz, 0.95, 1.08, 0.24, 14, Renderer3D.TEX_CONCRETE, 0xEAE2D6));
        smoothEllipsoids.add(new SmoothEllipsoidProp(fx, 2.48, fz, 0.36, 0.42, 0.36, 10, 5, 0x8CE8FF));

        // Центральная арка Мида с деревянными воротами и черепичным навесом
        addWallTex(-10.8, 0.0, 0.6, -2.8, 4.6, 2.0, stucco);
        addWallTex(2.8,   0.0, 0.6, 10.8, 4.6, 2.0, stucco);
        addConcrete(-3.0, 3.4, 0.4, 3.0, 4.7, 2.2, 0xDDD2C2);
        addDecor(-11.0, 4.6, 0.2, 11.0, 5.2, 2.4, roof, 0xFFFFFF, true);
        boxes.add(new Vec3.Box(-2.8, 0.0, 1.15, -0.9, 3.4, 1.48,
                Renderer3D.TEX_METAL_DOOR, 0xC89B6E, 0.0, true, true));
        boxes.add(new Vec3.Box(0.9,  0.0, 1.15,  2.8, 3.4, 1.48,
                Renderer3D.TEX_METAL_DOOR, 0xC89B6E, 0.0, true, true));

        // Аллея «Банан» (Banana) слева с каменными колоннами, дровами и винными бочками
        for (int i = 0; i < 3; i++) {
            double bz = -10.5 + i * 5.6;
            addInvisibleCollider(-14.2, 0.0, bz - 0.5, -13.1, 4.2, bz + 0.5, true);
            smoothCylinders.add(new SmoothCylinderProp(-13.65, 0.0, bz, 0.52, 0.46, 4.2, 12, Renderer3D.TEX_CONCRETE, 0xE6DCCB));
        }
        addDecor(-24.0, 4.2, -11.5, -13.0, 4.75, 1.5, roof, 0xFFFFFF, true);
        addWoodCrate(-18.2, 0.0, -7.8, 1.65, 1.50);
        addBarrel(-16.2, 0.0, -2.4, 0x7A482A);
        addBarrel(-15.2, 0.0, -2.1, 0x7A482A);

        // Плент B (Старинная площадь с гробами/ящиками и каменным укрытием)
        addWallTex(-18.6, 0.0, 6.8, -12.6, 3.7, 8.2, stucco);
        addMilitaryCrate(-20.8, 0.0, 14.8, -17.6, 1.65, 16.8);
        addWoodCrate(-19.2, 1.65, 15.8, 1.50, 1.42);
        addWoodCrate(-14.2, 0.0, 17.6, 1.70, 1.62);
        addBarrel(-17.4, 0.0, 12.2, 0x8C3D2B);

        // Балкон Апартаментов (Apartments) справа и Плент A с круглой ротондой
        addWallTex(13.8, 0.0, -9.5, 18.8, 3.5, -6.8, stucco);
        addDecor(13.4, 3.5, -9.9, 19.2, 4.1, -6.4, roof, 0xFFFFFF, true);
        addWoodCrate(15.2, 0.0, -2.5, 1.65, 1.55);

        // Колонны Библиотеки / Pit на Пленте A
        for (int i = 0; i < 2; i++) {
            double px = 14.2 + i * 5.8;
            addInvisibleCollider(px - 0.5, 0.0, 10.5, px + 0.5, 4.4, 11.5, true);
            smoothCylinders.add(new SmoothCylinderProp(px, 0.0, 11.0, 0.54, 0.48, 4.4, 12, Renderer3D.TEX_CONCRETE, 0xEAE0D0));
        }
        addDecor(13.4, 4.4, 10.2, 20.8, 5.0, 11.8, roof, 0xFFFFFF, true);

        addWoodCrate(18.2, 0.0, 16.8, 1.75, 1.70);
        addWoodCrate(20.0, 0.0, 16.8, 1.75, 1.70);
        addWoodCrate(19.1, 1.70, 16.8, 1.55, 1.48);
        addMilitaryCrate(-3.8, 0.0, 8.8, -0.6, 1.42, 10.6);
        addWoodCrate(5.2, 0.0, 7.6, 1.65, 1.55);

        populateStandardSpawnsAndWaypoints(0.0);
    }

    // =========================================================================
    //  КАРТА 1: DUST II (ПЕСЧАНАЯ КЛАССИКА С ГЛАДКИМ МИНАРЕТОМ)
    // =========================================================================
    private void buildDust2Arena() {
        floorTexId = Renderer3D.TEX_FLOOR_SAND;
        playerSpawn = new Vec3(0.0, 0.0, -19.5);
        bombsiteASpot = new Vec3(17.2, 0.56, 14.6);
        bombsiteBSpot = new Vec3(-16.2, 0.0, 13.6);
        siteAFloorY = 0.56;

        double wallH = 5.4;
        double t = 1.2;

        addWall(MIN_X - t, 0.0, MIN_Z - t, MAX_X + t, wallH, MIN_Z);
        addWall(MIN_X - t, 0.0, MAX_Z,     MAX_X + t, wallH, MAX_Z + t);
        addWall(MIN_X - t, 0.0, MIN_Z,     MIN_X,     wallH, MAX_Z);
        addWall(MAX_X,     0.0, MIN_Z,     MAX_X + t, wallH, MAX_Z);

        addDecor(MIN_X - t, wallH, MAX_Z, MAX_X + t, wallH + 0.65, MAX_Z + t, Renderer3D.TEX_CONCRETE, 0xF0DFC0, false);
        addDecor(MIN_X - t, wallH, MIN_Z - t, MAX_X + t, wallH + 0.55, MIN_Z, Renderer3D.TEX_CONCRETE, 0xE8D4B2, false);
        addDecor(-17.5, wallH, MAX_Z + 0.3, -11.5, wallH + 4.5, MAX_Z + 4.8, Renderer3D.TEX_WALL_SANDSTONE, 0xF2DEC0, false);
        addDecor(10.5,  wallH, MAX_Z + 0.3,  17.5, wallH + 5.2, MAX_Z + 5.5, Renderer3D.TEX_WALL_SANDSTONE, 0xF5E4C8, false);

        // Гладкий круглый Минарет и золотой купол на горизонте Dust II!
        smoothCylinders.add(new SmoothCylinderProp(-0.4, wallH, MAX_Z + 4.1, 1.55, 1.25, 7.6, 14, Renderer3D.TEX_CONCRETE, 0xF4E4C8));
        smoothEllipsoids.add(new SmoothEllipsoidProp(-0.4, wallH + 8.2, MAX_Z + 4.1, 1.45, 1.65, 1.45, 14, 7, 0xE5BE68));
        smoothEllipsoids.add(new SmoothEllipsoidProp(14.0, wallH + 5.4, MAX_Z + 3.0, 2.5, 1.8, 2.5, 14, 6, 0xDFBA78));

        addDecor(-26.8, wallH, -7.5, -24.1, wallH + 4.0, 1.5, Renderer3D.TEX_WALL_SANDSTONE, 0xEED8B4, false);
        addDecor(24.1,  wallH,  2.5,  27.5, wallH + 4.4, 12.5, Renderer3D.TEX_WALL_SANDSTONE, 0xEED8B4, false);

        // Центральная стена Мида с аркой и простреливаемыми Двойными дверями
        addWall(-11.5, 0.0, -0.7, -2.5, 4.6, 0.7);
        addWall(2.5,   0.0, -0.7, 11.5, 4.6, 0.7);
        addConcrete(-2.7, 3.3, -0.85, 2.7, 4.85, 0.85, 0xF6E6CA);
        addDecor(-11.6, 4.6, -0.82, 11.6, 5.05, 0.82, Renderer3D.TEX_CONCRETE, 0xEAD6B3, true);

        boxes.add(new Vec3.Box(-2.5, 0.0, -0.34, -0.82, 3.3, 0.12,
                Renderer3D.TEX_METAL_DOOR, 0xFFFFFF, 0.0, true, true));
        boxes.add(new Vec3.Box(0.82, 0.0, -0.12,  2.5,  3.3, 0.34,
                Renderer3D.TEX_METAL_DOOR, 0xFFFFFF, 0.0, true, true));

        addConcrete(-12.4, 0.0, -1.15, -11.3, 4.95, 1.15, 0xEAD8B8);
        addConcrete(11.3,  0.0, -1.15,  12.4, 4.95, 1.15, 0xEAD8B8);
        for (int b = -2; b <= 2; b += 2) {
            addDecor(-24.0, 4.45, b * 1.1 - 0.18, -12.3, 4.72, b * 1.1 + 0.18, Renderer3D.TEX_SOLID, 0x6E4726, true);
        }
        smoothCylinders.add(new SmoothCylinderProp(-8.6, 0.0, -9.6, 0.18, 0.15, 6.2, 8, Renderer3D.TEX_SOLID, 0x5C3D22));

        addConcrete(-4.6, 0.0, -12.6, -1.3, 1.12, -11.6, 0xE5D2B0);
        addConcrete(1.8,  0.0, -14.8,  5.0, 1.12, -13.8, 0xE5D2B0);
        addBarrel(-5.2, 0.0, -12.1, 0x3B668C);

        addWoodCrate(3.3,  0.0, -3.5, 1.35, 0.78);
        addWoodCrate(4.85, 0.0, -3.5, 1.65, 1.52);

        addWoodCrate(15.6,  0.0,  -11.5, 1.7, 1.65);
        addWoodCrate(17.35, 0.0,  -11.5, 1.6, 1.65);
        addWoodCrate(16.4,  1.65, -11.5, 1.5, 1.45);

        addMilitaryCrate(-18.5, 0.0, -10.8, -15.6, 1.35, -9.1);
        addWall(-20.2, 0.0, -3.5, -17.4, 3.6, -1.8);

        // Плент A на платформе
        boxes.add(new Vec3.Box(13.0, 0.0, 10.5, 23.8, 0.56, 23.8,
                Renderer3D.TEX_CONCRETE, 0xE8D5B5, 0.42, true, false));
        boxes.add(new Vec3.Box(14.5, 0.0, 9.3,  20.5, 0.28, 10.5,
                Renderer3D.TEX_CONCRETE, 0xDFC9A3, 0.42, true, false));
        boxes.add(new Vec3.Box(11.8, 0.0, 13.5, 13.0, 0.28, 19.0,
                Renderer3D.TEX_CONCRETE, 0xDFC9A3, 0.42, true, false));

        addWoodCrate(18.2, 0.56, 17.2, 1.75, 1.70);
        addWoodCrate(20.0, 0.56, 17.2, 1.75, 1.70);
        addWoodCrate(19.1, 2.26, 17.2, 1.55, 1.50);
        addBarrel(21.4, 0.56, 16.8, 0x346288);
        addConcrete(13.2, 0.56, 10.6, 14.3, 1.66, 13.2, 0xDEC8A4);
        addDecor(23.92, 2.2, 16.2, 23.99, 3.5, 18.2, Renderer3D.TEX_BOMBSITE, 0xFFBBBB, false);

        addWall(18.4, 0.0, 1.8, 20.0, 3.4, 4.6);
        addWoodCrate(14.4, 0.0, -3.8, 1.55, 1.55);

        // Плент B
        addWall(-17.8, 0.0, 7.2, -11.8, 3.7, 8.6);
        addMilitaryCrate(-21.2, 0.0, 14.8, -18.2, 1.65, 16.8);
        addWoodCrate(-19.7, 1.65, 15.8, 1.5, 1.45);
        addWoodCrate(-14.2, 0.0, 17.8, 1.70, 1.65);
        addBarrel(-17.6, 0.0, 16.2, 0x8F4332);
        addConcrete(-17.2, 0.0, 2.2, -14.2, 1.12, 3.2, 0xE5D2B0);
        addDecor(-23.99, 2.2, 14.8, -23.92, 3.5, 16.8, Renderer3D.TEX_BOMBSITE, 0xFFBBBB, false);

        addConcrete(-4.8, 0.0, 7.2, -1.6, 1.15, 8.3, 0xE5D2B0);
        addWoodCrate(3.8, 0.0, 6.4, 1.65, 1.60);
        addMilitaryCrate(-2.2, 0.0, 16.8, 1.4, 1.50, 18.6);
        addWall(6.4, 0.0, 12.2, 8.2, 3.8, 15.4);

        populateStandardSpawnsAndWaypoints(0.56);
    }

    // =========================================================================
    //  КАРТА 2: MIRAGE (МАРОККАНСКИЙ ДВОРЕЦ С КРУГЛЫМИ КОЛОННАМИ И КУПОЛАМИ)
    // =========================================================================
    private void buildMirageArena() {
        floorTexId = Renderer3D.TEX_MIRAGE_TILE;
        playerSpawn = new Vec3(0.0, 0.0, -19.5);
        bombsiteASpot = new Vec3(17.4, 0.0, 15.2);
        bombsiteBSpot = new Vec3(-16.8, 0.0, 14.2);
        siteAFloorY = 0.0;

        double wallH = 5.5;
        double t = 1.2;
        int wTex = Renderer3D.TEX_MIRAGE_PLASTER;

        addWallTex(MIN_X - t, 0.0, MIN_Z - t, MAX_X + t, wallH, MIN_Z, wTex);
        addWallTex(MIN_X - t, 0.0, MAX_Z,     MAX_X + t, wallH, MAX_Z + t, wTex);
        addWallTex(MIN_X - t, 0.0, MIN_Z,     MIN_X,     wallH, MAX_Z, wTex);
        addWallTex(MAX_X,     0.0, MIN_Z,     MAX_X + t, wallH, MAX_Z, wTex);

        // Лазурный сферический купол Дворца (Palace) и пальмы с гладкими стволами!
        addDecor(12.0, wallH, MAX_Z + 0.4, 21.5, wallH + 4.6, MAX_Z + 6.0, wTex, 0xFFE6CC, false);
        smoothEllipsoids.add(new SmoothEllipsoidProp(16.8, wallH + 5.2, MAX_Z + 3.2, 2.8, 2.2, 2.8, 14, 7, 0x2B88AE));
        addDecor(-20.0, wallH, MAX_Z + 0.4, -12.0, wallH + 4.4, MAX_Z + 5.2, wTex, 0xFFE2C4, false);
        smoothEllipsoids.add(new SmoothEllipsoidProp(-16.0, wallH + 4.8, MAX_Z + 2.8, 2.2, 1.7, 2.2, 12, 6, 0xD8A24A));

        addPalmTree(-9.5, -13.5);
        addPalmTree(10.2, -13.2);
        addPalmTree(-8.8, 16.5);

        addWallTex(-9.8, 0.0, -1.2, -3.4, 4.4, 1.2, wTex);
        addWallTex(3.4,  0.0, -1.2,  9.8, 4.4, 1.2, wTex);
        addConcrete(-3.4, 0.0, -0.8, 3.4, 1.05, 0.8, 0xDFC4A6);
        addConcrete(-3.6, 2.85, -1.0, 3.6, 4.5, 1.0, 0xE8CEB0);
        boxes.add(new Vec3.Box(-3.3, 1.05, -0.25, -1.3, 2.85, 0.25,
                Renderer3D.TEX_CRATE_WOOD, 0xFFFFFF, 0.0, true, true));

        addWoodCrate(5.6, 0.0, 5.2, 1.65, 1.55);
        addWoodCrate(7.3, 0.0, 5.2, 1.55, 1.45);
        addMilitaryCrate(-6.2, 0.0, -7.8, -3.2, 1.25, -6.0);
        addWoodCrate(3.4, 0.0, -10.8, 1.60, 1.45);

        // Гладкая круглая колоннада Дворца (Palace Pillars)!
        for (int i = 0; i < 3; i++) {
            double pz = 5.5 + i * 5.2;
            addInvisibleCollider(12.2, 0.0, pz - 0.62, 13.5, 4.4, pz + 0.62, true);
            smoothCylinders.add(new SmoothCylinderProp(12.85, 0.0, pz, 0.62, 0.54, 4.4, 14, wTex, 0xFFE6CE));
        }
        addDecor(12.0, 4.4, 4.5, 24.0, 4.95, 17.0, Renderer3D.TEX_CONCRETE, 0xE4C8A8, true);

        addWoodCrate(18.0, 0.0, 17.0, 1.75, 1.70);
        addWoodCrate(19.8, 0.0, 17.0, 1.75, 1.70);
        addWoodCrate(19.8, 0.0, 15.2, 1.70, 1.65);
        addWoodCrate(18.9, 1.70, 17.0, 1.55, 1.50);
        addConcrete(16.8, 0.0, -8.5, 20.6, 2.9, -5.8, 0x3D7A9E);
        addWoodCrate(15.2, 0.0, -7.2, 1.55, 1.40);

        addWallTex(-19.2, 0.0, 4.8, -13.2, 3.8, 6.2, wTex);
        addMilitaryCrate(-20.8, 0.0, 15.0, -17.6, 1.65, 17.0);
        addWoodCrate(-14.5, 0.0, 17.5, 1.70, 1.65);
        addBarrel(-18.2, 0.0, 12.2, 0x2B7A78);
        addWallTex(-18.5, 0.0, -7.5, -15.2, 3.5, -5.8, wTex);

        populateStandardSpawnsAndWaypoints(0.0);
    }

    // =========================================================================
    //  КАРТА 3: NUKE (КРУГЛЫЙ ЖЁЛТЫЙ РЕАКТОР SILO И ГРАДИРНЯ АЭС)
    // =========================================================================
    private void buildNukeArena() {
        floorTexId = Renderer3D.TEX_NUKE_FLOOR;
        playerSpawn = new Vec3(0.0, 0.0, -19.5);
        bombsiteASpot = new Vec3(17.2, 0.0, 14.8);
        bombsiteBSpot = new Vec3(-16.5, 0.0, 14.0);
        siteAFloorY = 0.0;

        double wallH = 5.6;
        double t = 1.2;
        int nTex = Renderer3D.TEX_NUKE_PANEL;

        addWallTex(MIN_X - t, 0.0, MIN_Z - t, MAX_X + t, wallH, MIN_Z, nTex);
        addWallTex(MIN_X - t, 0.0, MAX_Z,     MAX_X + t, wallH, MAX_Z + t, nTex);
        addWallTex(MIN_X - t, 0.0, MIN_Z,     MIN_X,     wallH, MAX_Z, nTex);
        addWallTex(MAX_X,     0.0, MIN_Z,     MAX_X + t, wallH, MAX_Z, nTex);

        // Круглая гиперболическая Градирня АЭС на горизонте Nuke!
        smoothCylinders.add(new SmoothCylinderProp(0.0, wallH, MAX_Z + 5.2, 5.2, 3.5, 6.4, 16, Renderer3D.TEX_CONCRETE, 0xC2CCD8));
        smoothCylinders.add(new SmoothCylinderProp(0.0, wallH + 6.4, MAX_Z + 5.2, 3.5, 4.1, 3.4, 16, Renderer3D.TEX_CONCRETE, 0xCFD8E4));
        smoothCylinders.add(new SmoothCylinderProp(0.0, wallH + 9.4, MAX_Z + 5.2, 4.05, 4.18, 0.65, 16, Renderer3D.TEX_SOLID, 0xDC4034));

        addWallTex(-11.2, 0.0, -0.8, -2.6, 4.5, 0.8, nTex);
        addWallTex(2.6,   0.0, -0.8, 11.2, 4.5, 0.8, nTex);
        addConcrete(-2.8, 3.3, -1.0, 2.8, 4.7, 1.0, 0x9AA6B4);
        boxes.add(new Vec3.Box(-2.6, 0.0, -0.25, -0.85, 3.3, 0.25,
                Renderer3D.TEX_METAL_DOOR, 0xB8D0E8, 0.0, true, true));
        boxes.add(new Vec3.Box(0.85, 0.0, -0.25,  2.6,  3.3, 0.25,
                Renderer3D.TEX_METAL_DOOR, 0xB8D0E8, 0.0, true, true));

        for (int z = -14; z <= 14; z += 14) {
            addDecor(-24.0, 5.0, z - 0.35, 24.0, 5.45, z + 0.35, Renderer3D.TEX_SOLID, 0x394452, true);
        }

        // Знаменитый круглый желтый ядерный реактор "Silo" на Пленте A с купольной крышкой!
        addInvisibleCollider(17.9, 0.0, 16.6, 21.3, 4.6, 20.0, true);
        smoothCylinders.add(new SmoothCylinderProp(19.6, 0.0, 18.3, 1.85, 1.85, 4.2, 16, Renderer3D.TEX_SOLID, 0xEEBC2A));
        smoothCylinders.add(new SmoothCylinderProp(19.6, 2.0, 18.3, 1.92, 1.92, 0.38, 16, Renderer3D.TEX_SOLID, 0x262E38));
        smoothEllipsoids.add(new SmoothEllipsoidProp(19.6, 4.2, 18.3, 1.85, 0.68, 1.85, 16, 6, 0xF5CA3C));

        addMilitaryCrate(13.6, 0.0, 11.5, 16.2, 1.55, 13.4);
        addWoodCrate(15.2, 0.0, 3.2, 1.65, 1.55);
        addBarrel(21.8, 0.0, 13.5, 0x2874B8);

        addWallTex(3.2, 0.0, -9.8, 7.6, 3.2, -6.6, nTex);
        addMilitaryCrate(-6.2, 0.0, -11.5, -2.8, 1.45, -9.5);
        addWoodCrate(-3.8, 0.0, -4.2, 1.55, 1.45);
        addMilitaryCrate(14.8, 0.0, -11.2, 18.2, 1.65, -9.0);

        addWallTex(-18.6, 0.0, 5.8, -12.4, 3.7, 7.2, nTex);
        addMilitaryCrate(-21.0, 0.0, 15.2, -17.8, 1.70, 17.4);
        addBarrel(-14.6, 0.0, 16.8, 0x38B858);
        addBarrel(-13.5, 0.0, 16.4, 0x38B858);
        addBarrel(-14.1, 0.0, 17.8, 0xE6B422);
        addWoodCrate(-16.8, 0.0, -6.8, 1.65, 1.55);
        addConcrete(-4.5, 0.0, 8.2, -1.2, 1.25, 9.5, 0x9AA6B4);
        addWoodCrate(4.2, 0.0, 7.5, 1.65, 1.55);

        populateStandardSpawnsAndWaypoints(0.0);
    }

    private void populateStandardSpawnsAndWaypoints(double siteAY) {
        botSpawns.add(new Vec3(0.0,   0.0,    20.8));
        botSpawns.add(new Vec3(-16.5, 0.0,    20.2));
        botSpawns.add(new Vec3(16.2,  siteAY, 20.5));
        botSpawns.add(new Vec3(-8.2,  0.0,    12.5));
        botSpawns.add(new Vec3(15.5,  0.0,     5.5));
        botSpawns.add(new Vec3(-16.5, 0.0,     5.2));
        botSpawns.add(new Vec3(5.8,   0.0,    18.8));
        botSpawns.add(new Vec3(-3.5,  0.0,    12.2));

        patrolWaypoints.add(new Vec3(0.0,   0.0,     4.2));
        patrolWaypoints.add(new Vec3(0.0,   0.0,    -3.2));
        patrolWaypoints.add(new Vec3(-8.5,  0.0,     4.5));
        patrolWaypoints.add(new Vec3(-15.5, 0.0,    -0.5));
        patrolWaypoints.add(new Vec3(-15.5, 0.0,    -7.5));
        patrolWaypoints.add(new Vec3(-15.8, 0.0,    12.5));
        patrolWaypoints.add(new Vec3(-8.5,  0.0,    18.5));
        patrolWaypoints.add(new Vec3(8.5,   0.0,     4.5));
        patrolWaypoints.add(new Vec3(15.5,  0.0,    -0.5));
        patrolWaypoints.add(new Vec3(15.0,  0.0,    -7.5));
        patrolWaypoints.add(new Vec3(16.2,  0.0,     7.5));
        patrolWaypoints.add(new Vec3(16.5,  siteAY, 14.2));
        patrolWaypoints.add(new Vec3(3.5,   0.0,    13.0));
        patrolWaypoints.add(new Vec3(-6.5,  0.0,    -7.2));
    }

    public boolean isBoxPenetrable(Vec3.Box box) {
        if (box == null) return false;
        return box.textureId == Renderer3D.TEX_CRATE_WOOD
            || box.textureId == Renderer3D.TEX_METAL_DOOR;
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

        for (int i = 0; i < boxes.size(); i++) {
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

        for (int i = 0; i < boxes.size(); i++) {
            Vec3.Box b = boxes.get(i);
            if (!b.collidable) continue;
            double t = b.intersectRay(from, dir, dist - 0.05, null);
            if (t >= 0.0 && t < dist - 0.05) {
                return false;
            }
        }

        if (smokes != null) {
            for (int i = 0; i < smokes.size(); i++) {
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

        for (int i = 0; i < boxes.size(); i++) {
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

        for (int i = 0; i < boxes.size(); i++) {
            Vec3.Box b = boxes.get(i);
            if (!b.collidable) continue;
            if (maxX > b.minX && minX < b.maxX && maxZ > b.minZ && minZ < b.maxZ) {
                if (b.maxY <= currentFeetY + 0.08 && b.maxY > supportY) {
                    supportY = b.maxY;
                }
            }
        }
        return supportY;
    }

    public void renderFloorOnly(Renderer3D renderer) {
        renderer.drawFloorGrid(MIN_X, MAX_X, MIN_Z, MAX_Z, 7.0, floorTexId, 0.26);
        renderer.drawFloorZoneQuad(-21.6, 0.005, 12.2, -14.8, 18.8, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);
        renderer.drawFloorZoneQuad(14.8, siteAFloorY + 0.005, 13.5, 22.0, 19.8, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);
    }

    public void renderShadowsOnly(Renderer3D renderer, GameSettings.ShadowMode shadowMode, List<Bot> bots) {
        if (shadowMode == GameSettings.ShadowMode.SUN_PROJECTED) {
            for (int i = 0; i < boxes.size(); i++) {
                renderer.drawBoxSunShadow(boxes.get(i), MIN_X, MAX_X, MIN_Z, MAX_Z);
            }
        } else if (shadowMode == GameSettings.ShadowMode.BLOBS) {
            for (int i = 0; i < boxes.size(); i++) {
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
            for (int i = 0; i < bots.size(); i++) {
                Bot bot = bots.get(i);
                if (bot.getPosition().y <= 0.03) {
                    double scale = bot.getModelScale();
                    double r = (bot.isAlive() ? 0.42 : 0.65) * scale;
                    renderer.drawBlobShadow(bot.getPosition().x, 0.0, bot.getPosition().z, r, r);
                }
            }
        }
    }

    public void renderFloorAndShadows(Renderer3D renderer, GameSettings.ShadowMode shadowMode, List<Bot> bots) {
        renderFloorOnly(renderer);
        renderShadowsOnly(renderer, shadowMode, bots);
    }

    public void renderStructures(Renderer3D renderer) {
        for (int i = 0; i < boxes.size(); i++) {
            Vec3.Box b = boxes.get(i);
            if (b.textureId >= 0) {
                renderer.drawAxisAlignedBox(b);
            }
        }
        for (int i = 0; i < smoothCylinders.size(); i++) {
            SmoothCylinderProp c = smoothCylinders.get(i);
            renderer.drawWorldCylinder(c.cx, c.bottomY, c.cz, c.radBottom, c.radTop, c.height, c.segments, c.texId, c.color);
        }
        for (int i = 0; i < smoothEllipsoids.size(); i++) {
            SmoothEllipsoidProp e = smoothEllipsoids.get(i);
            renderer.drawWorldEllipsoid(e.cx, e.cy, e.cz, e.rx, e.ry, e.rz, e.segments, e.rings, e.color);
        }
    }

    /**
     * Проверяет, находится ли точка под крышей/навесом арены (чтобы дождь и снег не шли сквозь потолок АЭС Nuke!).
     */
    public boolean hasOverheadRoof(double x, double y, double z) {
        for (int i = 0; i < boxes.size(); i++) {
            Vec3.Box b = boxes.get(i);
            if (b.minY >= 2.8 && y < b.maxY
                    && x >= b.minX && x <= b.maxX
                    && z >= b.minZ && z <= b.maxZ) {
                return true;
            }
        }
        return false;
    }
}
