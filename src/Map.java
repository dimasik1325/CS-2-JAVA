import java.util.ArrayList;
import java.util.List;

/**
 * 3D-карта арены в стилистике Dust 2:
 * песчаные стены, центральная арка с "Двойными дверями" на Миду,
 * подъем на плент A, коридор B, деревянные и военные ящики, низкие укрытия для приседания,
 * а также система коллизий и трассировки лучей (Raycasting).
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

    public List<Vec3.Box> getBoxes() {
        return boxes;
    }

    public Vec3 getPlayerSpawn() {
        return playerSpawn.copy();
    }

    public List<Vec3> getBotSpawns() {
        return botSpawns;
    }

    public List<Vec3> getPatrolWaypoints() {
        return patrolWaypoints;
    }

    private void addWall(double x0, double y0, double z0, double x1, double y1, double z1) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1,
                Renderer3D.TEX_WALL_SANDSTONE, 0xFFFFFF, 0.35, true));
    }

    private void addConcrete(double x0, double y0, double z0, double x1, double y1, double z1, int tint) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1,
                Renderer3D.TEX_CONCRETE, tint, 0.45, true));
    }

    private void addWoodCrate(double cx, double y0, double cz, double sizeXZ, double height) {
        double h = sizeXZ * 0.5;
        boxes.add(new Vec3.Box(cx - h, y0, cz - h, cx + h, y0 + height, cz + h,
                Renderer3D.TEX_CRATE_WOOD, 0xFFFFFF, 0.0, true));
    }

    private void addMilitaryCrate(double x0, double y0, double z0, double x1, double y1, double z1) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1,
                Renderer3D.TEX_CRATE_MILITARY, 0xFFFFFF, 0.0, true));
    }

    private void addDecorSkyline(double x0, double y0, double z0, double x1, double y1, double z1, int tex, int tint) {
        boxes.add(new Vec3.Box(x0, y0, z0, x1, y1, z1, tex, tint, 0.30, false));
    }

    private void buildDust2Arena() {
        double wallH = 5.4;
        double t = 1.2;

        // 1. Внешний периметр крепостных стен Dust 2
        addWall(MIN_X - t, 0.0, MIN_Z - t, MAX_X + t, wallH, MIN_Z); // Юг (за спиной CT)
        addWall(MIN_X - t, 0.0, MAX_Z,     MAX_X + t, wallH, MAX_Z + t); // Север (база T)
        addWall(MIN_X - t, 0.0, MIN_Z,     MIN_X,     wallH, MAX_Z); // Запад (линия B)
        addWall(MAX_X,     0.0, MIN_Z,     MAX_X + t, wallH, MAX_Z); // Восток (Длина A)

        // Карниз/парапет поверх внешних стен и декоративные башни на горизонте
        addDecorSkyline(MIN_X - t, wallH, MAX_Z, MAX_X + t, wallH + 0.65, MAX_Z + t, Renderer3D.TEX_CONCRETE, 0xF0DFC0);
        addDecorSkyline(-16.0, wallH, MAX_Z + 0.2, -11.5, wallH + 4.2, MAX_Z + 4.5, Renderer3D.TEX_WALL_SANDSTONE, 0xF2DEC0);
        addDecorSkyline(11.0,  wallH, MAX_Z + 0.2,  16.5, wallH + 5.0, MAX_Z + 5.2, Renderer3D.TEX_WALL_SANDSTONE, 0xF5E4C8);
        addDecorSkyline(-26.5, wallH, -6.0, -24.1, wallH + 3.6, 1.0, Renderer3D.TEX_WALL_SANDSTONE, 0xEED8B4);
        addDecorSkyline(24.1,  wallH,  4.0,  27.2, wallH + 4.0, 11.5, Renderer3D.TEX_WALL_SANDSTONE, 0xEED8B4);

        // 2. Центральная стена Мида с аркой и знаменитыми "Двойными дверями" (Double Doors)
        addWall(-11.5, 0.0, -0.7, -2.5, 4.6, 0.7);
        addWall(2.5,   0.0, -0.7, 11.5, 4.6, 0.7);
        // Верхняя перемычка арки над двойными дверями
        addConcrete(-2.6, 3.3, -0.8, 2.6, 4.8, 0.8, 0xF6E6CA);

        // Створки металлических двойных дверей (оставляют снайперскую щель по центру X: -0.82 .. +0.82)
        boxes.add(new Vec3.Box(-2.5, 0.0, -0.32, -0.82, 3.3, 0.12,
                Renderer3D.TEX_METAL_DOOR, 0xFFFFFF, 0.0, true));
        boxes.add(new Vec3.Box(0.82, 0.0, -0.12,  2.5,  3.3, 0.32,
                Renderer3D.TEX_METAL_DOOR, 0xFFFFFF, 0.0, true));

        // Колонны обрамления проходов на Запад (Туннель B) и Восток (Длина A)
        addConcrete(-12.3, 0.0, -1.1, -11.3, 4.9, 1.1, 0xEAD8B8);
        addConcrete(11.3,  0.0, -1.1,  12.3, 4.9, 1.1, 0xEAD8B8);

        // 3. Южная половина (подходы CT, укрытия и "Икс-бокс" у мида)
        // Низкое бетонное укрытие для приседания (высота 1.12 м: стоя видно поверх, сидя на Ctrl — полное укрытие!)
        addConcrete(-4.6, 0.0, -12.6, -1.3, 1.12, -11.6, 0xE5D2B0);
        addConcrete(1.8,  0.0, -14.8,  5.0, 1.12, -13.8, 0xE5D2B0);

        // Каскад ящиков "Xbox" справа от мида (можно запрыгнуть через ступеньку Space!)
        addWoodCrate(3.3, 0.0, -3.5, 1.35, 0.78);  // Нижний ящик-ступенька
        addWoodCrate(4.85, 0.0, -3.5, 1.65, 1.52); // Высокий ящик обзора Мида

        // Ящики прикрытия на выходе из CT к Длине A и коридору B
        addWoodCrate(15.6, 0.0, -11.5, 1.7, 1.65);
        addWoodCrate(17.35, 0.0, -11.5, 1.6, 1.65);
        addWoodCrate(16.4, 1.65, -11.5, 1.5, 1.45); // Двойной ящик по высоте

        addMilitaryCrate(-18.5, 0.0, -10.8, -15.6, 1.35, -9.1);
        addWall(-20.2, 0.0, -3.5, -17.4, 3.6, -1.8); // Колонна в коридоре B

        // 4. Восточный фланг и Плент A (Северо-Восток) с возвышенной платформой и ступенями
        addConcrete(13.0, 0.0, 10.5, 23.8, 0.56, 23.8, 0xE8D5B5); // Платформа плента A
        // Ступени подъема на платформу A с юга и запада (плавный шаг без прыжка или с прыжком)
        addConcrete(14.5, 0.0, 9.3,  20.5, 0.28, 10.5, 0xDFC9A3);
        addConcrete(11.8, 0.0, 13.5, 13.0, 0.28, 19.0, 0xDFC9A3);

        // Знаменитые ящики плента A на платформе
        addWoodCrate(18.2, 0.56, 17.2, 1.75, 1.70);
        addWoodCrate(20.0, 0.56, 17.2, 1.75, 1.70);
        addWoodCrate(19.1, 2.26, 17.2, 1.55, 1.50);
        // Низкий парапет-укрытие на краю платформы A
        addConcrete(13.2, 0.56, 10.6, 14.3, 1.66, 13.2, 0xDEC8A4);

        // Боковая стена-укрытие на Длине A ("Яма / Угол Длины")
        addWall(18.4, 0.0, 1.8, 20.0, 3.4, 4.6);
        addWoodCrate(14.4, 0.0, -3.8, 1.55, 1.55);

        // 5. Западный фланг и Плент B (Северо-Запад)
        addWall(-17.8, 0.0, 7.2, -11.8, 3.7, 8.6); // Стена отделения плента B
        addMilitaryCrate(-21.2, 0.0, 14.8, -18.2, 1.65, 16.8);
        addWoodCrate(-19.7, 1.65, 15.8, 1.5, 1.45);
        addWoodCrate(-14.2, 0.0, 17.8, 1.70, 1.65);
        // Низкое укрытие на B для перестрелок из-за укрытия
        addConcrete(-17.2, 0.0, 2.2, -14.2, 1.12, 3.2, 0xE5D2B0);

        // 6. Северный двор за Двойными дверями (Перетяжка T-спавна / Мид-Север)
        addConcrete(-4.8, 0.0, 7.2, -1.6, 1.15, 8.3, 0xE5D2B0);
        addWoodCrate(3.8, 0.0, 6.4, 1.65, 1.60);
        addMilitaryCrate(-2.2, 0.0, 16.8, 1.4, 1.50, 18.6);
        addWall(6.4, 0.0, 12.2, 8.2, 3.8, 15.4);
    }

    private void setupSpawnsAndWaypoints() {
        // Стартовые позиции ботов (T-сторона, плент A, плент B, север мида, длина A)
        botSpawns.add(new Vec3(0.0,   0.0, 20.8));
        botSpawns.add(new Vec3(-16.5, 0.0, 20.2));
        botSpawns.add(new Vec3(16.2,  0.56, 20.5));
        botSpawns.add(new Vec3(-8.2,  0.0, 12.5));
        botSpawns.add(new Vec3(15.5,  0.0, 5.5));
        botSpawns.add(new Vec3(-16.5, 0.0, 5.2));
        botSpawns.add(new Vec3(5.8,   0.0, 18.8));

        // Маршрутные точки патрулирования по всей карте
        patrolWaypoints.add(new Vec3(0.0,   0.0,  4.2));   // Север у Двойных дверей
        patrolWaypoints.add(new Vec3(0.0,   0.0, -4.5));   // Юг у Двойных дверей
        patrolWaypoints.add(new Vec3(-8.5,  0.0,  4.5));   // Переход к B
        patrolWaypoints.add(new Vec3(-15.5, 0.0, -0.5));   // Проход B
        patrolWaypoints.add(new Vec3(-15.5, 0.0, -7.5));   // Южный фланг B
        patrolWaypoints.add(new Vec3(-15.8, 0.0, 12.5));   // Вход на плент B
        patrolWaypoints.add(new Vec3(-8.5,  0.0, 18.5));   // Северный коридор T
        patrolWaypoints.add(new Vec3(8.5,   0.0,  4.5));   // Зигзаг / Парапет
        patrolWaypoints.add(new Vec3(15.5,  0.0, -0.5));   // Ворота Длины A
        patrolWaypoints.add(new Vec3(15.0,  0.0, -7.5));   // Южная Длина A
        patrolWaypoints.add(new Vec3(16.2,  0.0,  7.5));   // Подступ к платформе A
        patrolWaypoints.add(new Vec3(16.5,  0.56, 14.2));  // Платформа A
        patrolWaypoints.add(new Vec3(3.5,   0.0, 13.0));   // Центр базы T
        patrolWaypoints.add(new Vec3(-6.5,  0.0, -7.2));   // Центр двора CT
    }

    /**
     * Трассировка луча в геометрии карты (блоки + пол y=0).
     */
    public Vec3.RayHit raycast(Vec3 origin, Vec3 dir, double maxDist) {
        double bestT = maxDist;
        Vec3 bestNormal = null;
        Vec3.Box bestBox = null;
        Vec3 tmpNormal = new Vec3();

        // Проверка пересечения с полом y = 0
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

        // Проверка всех коллизионных блоков
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

        if (bestNormal == null) {
            return null;
        }
        Vec3 hitPoint = origin.add(dir.scale(bestT));
        return new Vec3.RayHit(bestT, hitPoint, bestNormal, bestBox);
    }

    /**
     * Проверяет прямую видимость между двумя 3D-точками (не перекрыта ли стенами/ящиками).
     */
    public boolean hasLineOfSight(Vec3 from, Vec3 to) {
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
        return true;
    }

    /**
     * Проверяет, пересекает ли цилиндр/AABB сущности с заданной позицией стоп (px, py, pz),
     * радиусом radius и высотой height какой-либо твердый блок карты.
     */
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

    /**
     * Возвращает высоту поверхности (пол 0.0 или крыша ящика/платформы) прямо под ногами сущности.
     */
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
     * Отрисовка всей 3D-геометрии карты.
     */
    public void render(Renderer3D renderer) {
        renderer.drawFloorGrid(MIN_X, MAX_X, MIN_Z, MAX_Z, 4.0, Renderer3D.TEX_FLOOR_SAND, 0.42);
        for (int i = 0; i < boxes.size(); i++) {
            renderer.drawAxisAlignedBox(boxes.get(i));
        }
    }
}
