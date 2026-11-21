import java.util.ArrayList;
import java.util.List;

/**
 * Детализированная 3D-арена в стилистике Dust II (CS-2 JAVA 2.0):
 * - Песчаные стены, центральные "Двойные двери" на Миду, Плент A на платформе, Плент B
 * - Разметка плентов на земле, деревянные балки, столбы, бочки, мешки с песком и башни на горизонте
 * - Солнечные тени от объектов, коллизии AABB и проверка перекрытия обзора дымовыми гранатами
 */
public class Map {
    public static final double MIN_X = -24.0;
    public static final double MAX_X = 24.0;
    public static final double MIN_Z = -24.0;
    public static final double MAX_Z = 24.0;

    private final List<Vec3.Box> boxes = new ArrayList<>();
    private final List<Vec3> botSpawns = new ArrayList<>();
    private final List<Vec3> patrolWaypoints = new ArrayList<>();
    private final Vec3 playerSpawn = new Vec3(0.0, 0.0, -19.5);

    public Map() {
        buildDust2Arena();
        setupSpawnsAndWaypoints();
    }

    public List<Vec3.Box> getBoxes() { return boxes; }
    public Vec3 getPlayerSpawn() { return playerSpawn.copy(); }
    public List<Vec3> getBotSpawns() { return botSpawns; }
    public List<Vec3> getPatrolWaypoints() { return patrolWaypoints; }
    public Vec3 getBombsiteASpot() { return new Vec3(17.2, 0.56, 14.6); }
    public Vec3 getBombsiteBSpot() { return new Vec3(-16.2, 0.0, 13.6); }

    private void addWall(double x0, double y0, double z0, double x1, double y1, double z1) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1,
                Renderer3D.TEX_WALL_SANDSTONE, 0xFFFFFF, 0.32, true, true));
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

    private void buildDust2Arena() {
        double wallH = 5.4;
        double t = 1.2;

        // 1. Внешний периметр стен Dust II + верхний каменный карниз
        addWall(MIN_X - t, 0.0, MIN_Z - t, MAX_X + t, wallH, MIN_Z);
        addWall(MIN_X - t, 0.0, MAX_Z,     MAX_X + t, wallH, MAX_Z + t);
        addWall(MIN_X - t, 0.0, MIN_Z,     MIN_X,     wallH, MAX_Z);
        addWall(MAX_X,     0.0, MIN_Z,     MAX_X + t, wallH, MAX_Z);

        // Архитектурный силуэт города вокруг арены (башни, купольные минареты, здания)
        addDecor(MIN_X - t, wallH, MAX_Z, MAX_X + t, wallH + 0.65, MAX_Z + t, Renderer3D.TEX_CONCRETE, 0xF0DFC0, false);
        addDecor(MIN_X - t, wallH, MIN_Z - t, MAX_X + t, wallH + 0.55, MIN_Z, Renderer3D.TEX_CONCRETE, 0xE8D4B2, false);
        addDecor(-17.5, wallH, MAX_Z + 0.3, -11.5, wallH + 4.5, MAX_Z + 4.8, Renderer3D.TEX_WALL_SANDSTONE, 0xF2DEC0, false);
        addDecor(10.5,  wallH, MAX_Z + 0.3,  17.5, wallH + 5.2, MAX_Z + 5.5, Renderer3D.TEX_WALL_SANDSTONE, 0xF5E4C8, false);
        // Высокий минарет Dust II на фоне неба
        addDecor(-2.0, wallH, MAX_Z + 2.5, 1.2, wallH + 7.8, MAX_Z + 5.7, Renderer3D.TEX_CONCRETE, 0xF4E4C8, false);
        addDecor(-1.4, wallH + 7.8, MAX_Z + 3.1, 0.6, wallH + 9.6, MAX_Z + 5.1, Renderer3D.TEX_SOLID, 0xD8B47C, false);
        addDecor(-26.8, wallH, -7.5, -24.1, wallH + 4.0, 1.5, Renderer3D.TEX_WALL_SANDSTONE, 0xEED8B4, false);
        addDecor(24.1,  wallH,  2.5,  27.5, wallH + 4.4, 12.5, Renderer3D.TEX_WALL_SANDSTONE, 0xEED8B4, false);

        // 2. Центральная стена Мида с аркой и "Двойными дверями"
        addWall(-11.5, 0.0, -0.7, -2.5, 4.6, 0.7);
        addWall(2.5,   0.0, -0.7, 11.5, 4.6, 0.7);
        addConcrete(-2.7, 3.3, -0.85, 2.7, 4.85, 0.85, 0xF6E6CA);
        addDecor(-11.6, 4.6, -0.82, 11.6, 5.05, 0.82, Renderer3D.TEX_CONCRETE, 0xEAD6B3, true);

        // Металлические створки Двойных дверей
        boxes.add(new Vec3.Box(-2.5, 0.0, -0.34, -0.82, 3.3, 0.12,
                Renderer3D.TEX_METAL_DOOR, 0xFFFFFF, 0.0, true, true));
        boxes.add(new Vec3.Box(0.82, 0.0, -0.12,  2.5,  3.3, 0.34,
                Renderer3D.TEX_METAL_DOOR, 0xFFFFFF, 0.0, true, true));

        // Пилоны и деревянные перекладины над проходами B и Длины A (атмосфера Ближнего Востока!)
        addConcrete(-12.4, 0.0, -1.15, -11.3, 4.95, 1.15, 0xEAD8B8);
        addConcrete(11.3,  0.0, -1.15,  12.4, 4.95, 1.15, 0xEAD8B8);
        for (int b = -2; b <= 2; b += 2) {
            addDecor(-24.0, 4.45, b * 1.1 - 0.18, -12.3, 4.72, b * 1.1 + 0.18, Renderer3D.TEX_SOLID, 0x6E4726, true);
        }
        // Деревянный телеграфный столб во дворе
        addDecor(-8.8, 0.0, -9.8, -8.45, 6.2, -9.45, Renderer3D.TEX_SOLID, 0x5C3D22, true);
        addDecor(-9.3, 5.7, -9.72, -7.95, 5.84, -9.52, Renderer3D.TEX_SOLID, 0x4A311C, false);

        // 3. Южный сектор CT: укрытия для приседания и каскад "Икс-бокс"
        addConcrete(-4.6, 0.0, -12.6, -1.3, 1.12, -11.6, 0xE5D2B0);
        addConcrete(1.8,  0.0, -14.8,  5.0, 1.12, -13.8, 0xE5D2B0);
        addBarrel(-5.2, 0.0, -12.1, 0x3B668C);

        // Ящики "Xbox" справа от мида
        addWoodCrate(3.3,  0.0, -3.5, 1.35, 0.78);
        addWoodCrate(4.85, 0.0, -3.5, 1.65, 1.52);

        // Двойной стек ящиков у выхода на Длину A
        addWoodCrate(15.6,  0.0,  -11.5, 1.7, 1.65);
        addWoodCrate(17.35, 0.0,  -11.5, 1.6, 1.65);
        addWoodCrate(16.4,  1.65, -11.5, 1.5, 1.45);

        addMilitaryCrate(-18.5, 0.0, -10.8, -15.6, 1.35, -9.1);
        addWall(-20.2, 0.0, -3.5, -17.4, 3.6, -1.8);

        // 4. Восточный фланг и Плент A (возвышенная платформа + ступени)
        boxes.add(new Vec3.Box(13.0, 0.0, 10.5, 23.8, 0.56, 23.8,
                Renderer3D.TEX_CONCRETE, 0xE8D5B5, 0.42, true, false));
        boxes.add(new Vec3.Box(14.5, 0.0, 9.3,  20.5, 0.28, 10.5,
                Renderer3D.TEX_CONCRETE, 0xDFC9A3, 0.42, true, false));
        boxes.add(new Vec3.Box(11.8, 0.0, 13.5, 13.0, 0.28, 19.0,
                Renderer3D.TEX_CONCRETE, 0xDFC9A3, 0.42, true, false));

        // Знаменитые ящики плента A + синяя бочка
        addWoodCrate(18.2, 0.56, 17.2, 1.75, 1.70);
        addWoodCrate(20.0, 0.56, 17.2, 1.75, 1.70);
        addWoodCrate(19.1, 2.26, 17.2, 1.55, 1.50);
        addBarrel(21.4, 0.56, 16.8, 0x346288);
        addConcrete(13.2, 0.56, 10.6, 14.3, 1.66, 13.2, 0xDEC8A4);

        // Красный указатель "A" на стене Плента A
        addDecor(23.92, 2.2, 16.2, 23.99, 3.5, 18.2, Renderer3D.TEX_BOMBSITE, 0xFFBBBB, false);

        addWall(18.4, 0.0, 1.8, 20.0, 3.4, 4.6);
        addWoodCrate(14.4, 0.0, -3.8, 1.55, 1.55);

        // 5. Западный фланг и Плент B
        addWall(-17.8, 0.0, 7.2, -11.8, 3.7, 8.6);
        addMilitaryCrate(-21.2, 0.0, 14.8, -18.2, 1.65, 16.8);
        addWoodCrate(-19.7, 1.65, 15.8, 1.5, 1.45);
        addWoodCrate(-14.2, 0.0, 17.8, 1.70, 1.65);
        addBarrel(-17.6, 0.0, 16.2, 0x8F4332);
        addConcrete(-17.2, 0.0, 2.2, -14.2, 1.12, 3.2, 0xE5D2B0);
        addDecor(-23.99, 2.2, 14.8, -23.92, 3.5, 16.8, Renderer3D.TEX_BOMBSITE, 0xFFBBBB, false);

        // 6. Северный сектор T-спавна
        addConcrete(-4.8, 0.0, 7.2, -1.6, 1.15, 8.3, 0xE5D2B0);
        addWoodCrate(3.8, 0.0, 6.4, 1.65, 1.60);
        addMilitaryCrate(-2.2, 0.0, 16.8, 1.4, 1.50, 18.6);
        addWall(6.4, 0.0, 12.2, 8.2, 3.8, 15.4);
    }

    private void setupSpawnsAndWaypoints() {
        botSpawns.add(new Vec3(0.0,   0.0,  20.8));
        botSpawns.add(new Vec3(-16.5, 0.0,  20.2));
        botSpawns.add(new Vec3(16.2,  0.56, 20.5));
        botSpawns.add(new Vec3(-8.2,  0.0,  12.5));
        botSpawns.add(new Vec3(15.5,  0.0,   5.5));
        botSpawns.add(new Vec3(-16.5, 0.0,   5.2));
        botSpawns.add(new Vec3(5.8,   0.0,  18.8));
        botSpawns.add(new Vec3(-3.5,  0.0,  12.2));

        patrolWaypoints.add(new Vec3(0.0,   0.0,   4.2));
        patrolWaypoints.add(new Vec3(0.0,   0.0,  -4.5));
        patrolWaypoints.add(new Vec3(-8.5,  0.0,   4.5));
        patrolWaypoints.add(new Vec3(-15.5, 0.0,  -0.5));
        patrolWaypoints.add(new Vec3(-15.5, 0.0,  -7.5));
        patrolWaypoints.add(new Vec3(-15.8, 0.0,  12.5));
        patrolWaypoints.add(new Vec3(-8.5,  0.0,  18.5));
        patrolWaypoints.add(new Vec3(8.5,   0.0,   4.5));
        patrolWaypoints.add(new Vec3(15.5,  0.0,  -0.5));
        patrolWaypoints.add(new Vec3(15.0,  0.0,  -7.5));
        patrolWaypoints.add(new Vec3(16.2,  0.0,   7.5));
        patrolWaypoints.add(new Vec3(16.5,  0.56, 14.2));
        patrolWaypoints.add(new Vec3(3.5,   0.0,  13.0));
        patrolWaypoints.add(new Vec3(-6.5,  0.0,  -7.2));
    }

    public Vec3.RayHit raycast(Vec3 origin, Vec3 dir, double maxDist) {
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
            if (!b.collidable) continue;
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

    /**
     * Шаг 1 рендера карты: пол, разметка плентов и проекционные тени на песке.
     */
    public void renderFloorAndShadows(Renderer3D renderer, GameSettings.ShadowMode shadowMode, List<Bot> bots) {
        renderer.drawFloorGrid(MIN_X, MAX_X, MIN_Z, MAX_Z, 4.0, Renderer3D.TEX_FLOOR_SAND, 0.42);
        // Зона закладки на пленте B (на уровне пола)
        renderer.drawFloorZoneQuad(-21.6, 0.004, 12.2, -16.4, 18.8, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);

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
                    double r = bot.isAlive() ? 0.42 : 0.65;
                    renderer.drawBlobShadow(bot.getPosition().x, 0.0, bot.getPosition().z, r, r);
                }
            }
        }
    }

    /**
     * Шаг 2 рендера карты: все 3D-блоки стен, платформ, ящиков, ворот и декора.
     */
    public void renderStructures(Renderer3D renderer) {
        for (int i = 0; i < boxes.size(); i++) {
            renderer.drawAxisAlignedBox(boxes.get(i));
        }
        // Зона разметки Плента A поверх платформы (y = 0.564)
        renderer.drawFloorZoneQuad(15.6, 0.564, 14.5, 22.4, 20.2, Renderer3D.TEX_BOMBSITE, 0xFFFFFF);
    }
}
