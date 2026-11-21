import java.util.ArrayList;
import java.util.List;

/**
 * Мульти-картовая 3D-арена CS-2 JAVA 4.0:
 * Поддерживает 3 уникальные тактические карты Counter-Strike 2:
 * 1) DUST_II — песчаные стены, центральные Двойные двери Мида, минарет, Пленты A и B;
 * 2) MIRAGE  — марокканский дворец с колоннадой, Окно снайпера на Миду, Тикет-бокс и Пальмы;
 * 3) NUKE    — индустриальный ядерный комплекс с парными желтыми реакторами Силоса A и бочками B.
 * Также реализует AABB-коллизии, прострел деревянных преград (Wallbang) и перекрытие обзора дымом.
 */
public class Map {
    public static final double MIN_X = -24.0;
    public static final double MAX_X = 24.0;
    public static final double MIN_Z = -24.0;
    public static final double MAX_Z = 24.0;

    private GameSettings.MapTheme currentTheme = GameSettings.MapTheme.DUST_II;
    private final List<Vec3.Box> boxes = new ArrayList<>();
    private final List<Vec3> botSpawns = new ArrayList<>();
    private final List<Vec3> allySpawns = new ArrayList<>();
    private final List<Vec3> patrolWaypoints = new ArrayList<>();
    private Vec3 playerSpawn = new Vec3(0.0, 0.0, -19.5);
    private Vec3 bombsiteASpot = new Vec3(17.2, 0.56, 14.6);
    private Vec3 bombsiteBSpot = new Vec3(-16.2, 0.0, 13.6);
    private double siteAFloorY = 0.56;
    private int floorTexId = Renderer3D.TEX_FLOOR_SAND;

    public Map() {
        rebuildForTheme(GameSettings.MapTheme.DUST_II);
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
        this.currentTheme = (theme != null) ? theme : GameSettings.MapTheme.DUST_II;
        boxes.clear();
        botSpawns.clear();
        allySpawns.clear();
        patrolWaypoints.clear();

        switch (this.currentTheme) {
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
        boxes.add(new Vec3.Box(cx - 0.42, y0, cz - 0.42, cx + 0.42, y0 + 1.24, cz + 0.42,
                Renderer3D.TEX_SOLID, tint, 0.0, true, true));
        boxes.add(new Vec3.Box(cx - 0.45, y0 + 0.38, cz - 0.45, cx + 0.45, y0 + 0.46, cz + 0.45,
                Renderer3D.TEX_SOLID, 0x2F343A, 0.0, false, false));
        boxes.add(new Vec3.Box(cx - 0.45, y0 + 0.82, cz - 0.45, cx + 0.45, y0 + 0.90, cz + 0.45,
                Renderer3D.TEX_SOLID, 0x2F343A, 0.0, false, false));
    }

    private void addPalmTree(double cx, double cz) {
        addDecor(cx - 0.24, 0.0, cz - 0.24, cx + 0.24, 5.8, cz + 0.24, Renderer3D.TEX_SOLID, 0x6B4828, true);
        addDecor(cx - 1.7, 5.5, cz - 0.45, cx + 1.7, 6.0, cz + 0.45, Renderer3D.TEX_SOLID, 0x2E8544, false);
        addDecor(cx - 0.45, 5.5, cz - 1.7, cx + 0.45, 6.0, cz + 1.7, Renderer3D.TEX_SOLID, 0x35964C, false);
        addDecor(cx - 1.15, 5.8, cz - 1.15, cx + 1.15, 6.3, cz + 1.15, Renderer3D.TEX_SOLID, 0x3EA656, false);
    }

    // =========================================================================
    //  КАРТА 1: DUST II (ПЕСЧАНАЯ КЛАССИКА)
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
        addDecor(-2.0, wallH, MAX_Z + 2.5, 1.2, wallH + 7.8, MAX_Z + 5.7, Renderer3D.TEX_CONCRETE, 0xF4E4C8, false);
        addDecor(-1.4, wallH + 7.8, MAX_Z + 3.1, 0.6, wallH + 9.6, MAX_Z + 5.1, Renderer3D.TEX_SOLID, 0xD8B47C, false);
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
        addDecor(-8.8, 0.0, -9.8, -8.45, 6.2, -9.45, Renderer3D.TEX_SOLID, 0x5C3D22, true);
        addDecor(-9.3, 5.7, -9.72, -7.95, 5.84, -9.52, Renderer3D.TEX_SOLID, 0x4A311C, false);

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
    //  КАРТА 2: MIRAGE (МАРОККАНСКИЙ ДВОРЕЦ И СНАЙПЕРСКОЕ ОКНО)
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

        // Внешние терракотовые стены Mirage с мозаичным фризом
        addWallTex(MIN_X - t, 0.0, MIN_Z - t, MAX_X + t, wallH, MIN_Z, wTex);
        addWallTex(MIN_X - t, 0.0, MAX_Z,     MAX_X + t, wallH, MAX_Z + t, wTex);
        addWallTex(MIN_X - t, 0.0, MIN_Z,     MIN_X,     wallH, MAX_Z, wTex);
        addWallTex(MAX_X,     0.0, MIN_Z,     MAX_X + t, wallH, MAX_Z, wTex);

        // Купола Дворца (Palace) и пальмы на фоне неба
        addDecor(12.0, wallH, MAX_Z + 0.4, 21.5, wallH + 4.8, MAX_Z + 6.0, wTex, 0xFFE6CC, false);
        addDecor(14.5, wallH + 4.8, MAX_Z + 1.8, 19.0, wallH + 7.2, MAX_Z + 5.0, Renderer3D.TEX_SOLID, 0x2B82A6, false);
        addDecor(-20.0, wallH, MAX_Z + 0.4, -12.0, wallH + 4.4, MAX_Z + 5.2, wTex, 0xFFE2C4, false);
        addPalmTree(-9.5, -13.5);
        addPalmTree(10.2, -13.2);
        addPalmTree(-8.8, 16.5);

        // Центральный комплекс «Окно снайпера» (Window / Connector) на Миду
        addWallTex(-9.8, 0.0, -1.2, -3.4, 4.4, 1.2, wTex);
        addWallTex(3.4,  0.0, -1.2,  9.8, 4.4, 1.2, wTex);
        // Подоконник и верхняя арка снайперского Окна (через него идет перестрелка на Миду!)
        addConcrete(-3.4, 0.0, -0.8, 3.4, 1.05, 0.8, 0xDFC4A6);
        addConcrete(-3.6, 2.85, -1.0, 3.6, 4.5, 1.0, 0xE8CEB0);
        // Деревянная ставня в половине окна (можно простреливать Wallbang!)
        boxes.add(new Vec3.Box(-3.3, 1.05, -0.25, -1.3, 2.85, 0.25,
                Renderer3D.TEX_CRATE_WOOD, 0xFFFFFF, 0.0, true, true));

        // Коробки "Top Mid Boxes" и синяя тележка
        addWoodCrate(5.6, 0.0, 5.2, 1.65, 1.55);
        addWoodCrate(7.3, 0.0, 5.2, 1.55, 1.45);
        addMilitaryCrate(-6.2, 0.0, -7.8, -3.2, 1.25, -6.0);
        addWoodCrate(3.4, 0.0, -10.8, 1.60, 1.45);

        // Колоннада Дворца (Palace Pillars) и Плент A ("Triple Box" + "Ticket Booth")
        for (int i = 0; i < 3; i++) {
            double pz = 5.5 + i * 5.2;
            addWallTex(12.2, 0.0, pz - 0.65, 13.5, 4.4, pz + 0.65, wTex);
        }
        addDecor(12.0, 4.4, 4.5, 24.0, 4.95, 17.0, Renderer3D.TEX_CONCRETE, 0xE4C8A8, true);

        // Знаменитый "Triple Box" и "Firebox" на Пленте A Mirage
        addWoodCrate(18.0, 0.0, 17.0, 1.75, 1.70);
        addWoodCrate(19.8, 0.0, 17.0, 1.75, 1.70);
        addWoodCrate(19.8, 0.0, 15.2, 1.70, 1.65);
        addWoodCrate(18.9, 1.70, 17.0, 1.55, 1.50);
        // Будка "Ticket Booth" на CT-входе в A
        addConcrete(16.8, 0.0, -8.5, 20.6, 2.9, -5.8, 0x3D7A9E);
        addWoodCrate(15.2, 0.0, -7.2, 1.55, 1.40);

        // Плент B (Апартаменты и Рынок / Market)
        addWallTex(-19.2, 0.0, 4.8, -13.2, 3.8, 6.2, wTex);
        addMilitaryCrate(-20.8, 0.0, 15.0, -17.6, 1.65, 17.0);
        addWoodCrate(-14.5, 0.0, 17.5, 1.70, 1.65);
        addBarrel(-18.2, 0.0, 12.2, 0x2B7A78);
        addWallTex(-18.5, 0.0, -7.5, -15.2, 3.5, -5.8, wTex);

        populateStandardSpawnsAndWaypoints(0.0);
    }

    // =========================================================================
    //  КАРТА 3: NUKE (ЯДЕРНЫЙ НАУЧНЫЙ КОМПЛЕКС И РЕАКТОРЫ)
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

        // Градирня атомной станции на горизонте Nuke
        addDecor(-6.5, wallH, MAX_Z + 1.5, 6.5, wallH + 6.5, MAX_Z + 8.5, Renderer3D.TEX_CONCRETE, 0xB8C2CE, false);
        addDecor(-5.0, wallH + 6.5, MAX_Z + 2.5, 5.0, wallH + 10.2, MAX_Z + 7.5, Renderer3D.TEX_SOLID, 0xCBD4DF, false);
        addDecor(-5.2, wallH + 9.4, MAX_Z + 2.3, 5.2, wallH + 10.2, MAX_Z + 7.7, Renderer3D.TEX_SOLID, 0xD94136, false);

        // Центральный шлюз безопасности с металлическими створками (простреливаются!)
        addWallTex(-11.2, 0.0, -0.8, -2.6, 4.5, 0.8, nTex);
        addWallTex(2.6,   0.0, -0.8, 11.2, 4.5, 0.8, nTex);
        addConcrete(-2.8, 3.3, -1.0, 2.8, 4.7, 1.0, 0x9AA6B4);
        boxes.add(new Vec3.Box(-2.6, 0.0, -0.25, -0.85, 3.3, 0.25,
                Renderer3D.TEX_METAL_DOOR, 0xB8D0E8, 0.0, true, true));
        boxes.add(new Vec3.Box(0.85, 0.0, -0.25,  2.6,  3.3, 0.25,
                Renderer3D.TEX_METAL_DOOR, 0xB8D0E8, 0.0, true, true));

        // Потолочные стальные фермы ангара
        for (int z = -14; z <= 14; z += 14) {
            addDecor(-24.0, 5.0, z - 0.35, 24.0, 5.45, z + 0.35, Renderer3D.TEX_SOLID, 0x394452, true);
        }

        // Знаменитые желтые ядерные реакторы "Silo" на Пленте A!
        boxes.add(new Vec3.Box(17.8, 0.0, 16.5, 21.4, 4.4, 20.1,
                Renderer3D.TEX_SOLID, 0xE8B828, 0.15, true, true));
        addDecor(17.65, 2.0, 16.35, 21.55, 2.45, 20.25, Renderer3D.TEX_SOLID, 0x262E38, false);
        addMilitaryCrate(13.6, 0.0, 11.5, 16.2, 1.55, 13.4);
        addWoodCrate(15.2, 0.0, 3.2, 1.65, 1.55);
        addBarrel(21.8, 0.0, 13.5, 0x2874B8);

        // Диспетчерская будка "Hut / Control" и контейнеры Юга
        addWallTex(3.2, 0.0, -9.8, 7.6, 3.2, -6.6, nTex);
        addMilitaryCrate(-6.2, 0.0, -11.5, -2.8, 1.45, -9.5);
        addWoodCrate(-3.8, 0.0, -4.2, 1.55, 1.45);
        addMilitaryCrate(14.8, 0.0, -11.2, 18.2, 1.65, -9.0);

        // Плент B: Хранилище радиоактивных бочек и защитный экран
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
        patrolWaypoints.add(new Vec3(0.0,   0.0,    -4.5));
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

    /**
     * Проверяет, можно ли прострелить данное препятствие навылет (деревянный ящик или дверь).
     */
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

    public void renderFloorAndShadows(Renderer3D renderer, GameSettings.ShadowMode shadowMode, List<Bot> bots) {
        renderer.drawFloorGrid(MIN_X, MAX_X, MIN_Z, MAX_Z, 4.0, floorTexId, 0.42);
        renderer.drawFloorZoneQuad(-21.6, 0.004, 12.2, -14.8, 18.8, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);

        if (shadowMode == GameSettings.ShadowMode.SUN_PROJECTED) {
            for (int i = 0; i < boxes.size(); i++) {
                renderer.drawBoxSunShadow(boxes.get(i), MIN_X, MAX_X, MIN_Z, MAX_Z);
            }
        } else if (shadowMode == GameSettings.ShadowMode.BLOBS) {
            for (int i = 0; i < boxes.size(); i++) {
                Vec3.Box b = boxes.get(i);
                if (b.castsShadow && b.minY <= 0.02 && (b.maxX - b.minX) < 10.0 && (b.maxZ - b.minZ) < 10.0) {
                    renderer.drawBlobShadow(
                        (b.minX + b.maxX) * 0.5, 0.0, (b.minZ + b.minZ + (b.maxZ - b.minZ)) * 0.5,
                        (b.maxX - b.minX) * 0.56, (b.maxZ - b.minZ) * 0.56
                    );
                }
            }
        }

        if (shadowMode != GameSettings.ShadowMode.OFF && bots != null) {
            for (int i = 0; i < bots.size(); i++) {
                Bot bot = bots.get(i);
                if (bot.getPosition().y <= 0.03) {
                    double r = bot.isAlive() ? 0.42 : 0.65;
                    renderer.drawBlobShadow(bot.getPosition().x, 0.0, bot.getPosition().z, r, r);
                }
            }
        }
    }

    public void renderStructures(Renderer3D renderer) {
        for (int i = 0; i < boxes.size(); i++) {
            renderer.drawAxisAlignedBox(boxes.get(i));
        }
        renderer.drawFloorZoneQuad(14.8, siteAFloorY + 0.004, 13.5, 22.0, 19.8, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);
    }
}
