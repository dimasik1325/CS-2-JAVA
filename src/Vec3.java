import java.util.List;

/**
 * Базовые 3D-структуры данных: вектор Vec3, AABB-блок (Box), результат рейкаста (RayHit),
 * трассер (Tracer), частица (Particle), декаль пробоины (Decal), 3D-гильза (ShellCasing),
 * граната (Grenade), дымовая завеса (SmokeCloud) и выпадающий трофей (Pickup).
 */
public class Vec3 {
    public double x;
    public double y;
    public double z;

    public Vec3() {
        this(0.0, 0.0, 0.0);
    }

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 set(double nx, double ny, double nz) {
        this.x = nx;
        this.y = ny;
        this.z = nz;
        return this;
    }

    public Vec3 set(Vec3 other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        return this;
    }

    public Vec3 copy() {
        return new Vec3(x, y, z);
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x
        );
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double dist(Vec3 o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double distXZ(Vec3 o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public Vec3 normalize() {
        double len = length();
        if (len < 1e-9) {
            return new Vec3(0.0, 0.0, 0.0);
        }
        return new Vec3(x / len, y / len, z / len);
    }

    /**
     * Выровненный по осям 3D-параллелепипед (AABB) для стен, ящиков, укрытий и декора.
     */
    public static class Box {
        public final double minX, minY, minZ;
        public final double maxX, maxY, maxZ;
        public final int textureId;
        public final int tintColor;
        public final double uvScale;
        public final boolean collidable;
        public final boolean castsShadow;

        public Box(double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ,
                   int textureId, int tintColor, double uvScale, boolean collidable) {
            this(minX, minY, minZ, maxX, maxY, maxZ, textureId, tintColor, uvScale, collidable, collidable);
        }

        public Box(double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ,
                   int textureId, int tintColor, double uvScale,
                   boolean collidable, boolean castsShadow) {
            this.minX = Math.min(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxY = Math.max(minY, maxY);
            this.maxZ = Math.max(minZ, maxZ);
            this.textureId = textureId;
            this.tintColor = tintColor;
            this.uvScale = uvScale;
            this.collidable = collidable;
            this.castsShadow = castsShadow;
        }

        public boolean intersectsAABB(double aMinX, double aMinY, double aMinZ,
                                      double aMaxX, double aMaxY, double aMaxZ) {
            return aMaxX > minX && aMinX < maxX
                && aMaxY > minY && aMinY < maxY
                && aMaxZ > minZ && aMinZ < maxZ;
        }

        public double intersectRay(Vec3 origin, Vec3 dir, double maxDist, Vec3 outNormal) {
            double tMin = 0.0;
            double tMax = maxDist;
            double nx = 0.0, ny = 0.0, nz = 0.0;

            if (Math.abs(dir.x) < 1e-9) {
                if (origin.x < minX || origin.x > maxX) return -1.0;
            } else {
                double inv = 1.0 / dir.x;
                double t1 = (minX - origin.x) * inv;
                double t2 = (maxX - origin.x) * inv;
                double sign = -1.0;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; sign = 1.0; }
                if (t1 > tMin) { tMin = t1; nx = sign; ny = 0.0; nz = 0.0; }
                if (t2 < tMax) tMax = t2;
                if (tMin > tMax) return -1.0;
            }

            if (Math.abs(dir.y) < 1e-9) {
                if (origin.y < minY || origin.y > maxY) return -1.0;
            } else {
                double inv = 1.0 / dir.y;
                double t1 = (minY - origin.y) * inv;
                double t2 = (maxY - origin.y) * inv;
                double sign = -1.0;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; sign = 1.0; }
                if (t1 > tMin) { tMin = t1; nx = 0.0; ny = sign; nz = 0.0; }
                if (t2 < tMax) tMax = t2;
                if (tMin > tMax) return -1.0;
            }

            if (Math.abs(dir.z) < 1e-9) {
                if (origin.z < minZ || origin.z > maxZ) return -1.0;
            } else {
                double inv = 1.0 / dir.z;
                double t1 = (minZ - origin.z) * inv;
                double t2 = (maxZ - origin.z) * inv;
                double sign = -1.0;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; sign = 1.0; }
                if (t1 > tMin) { tMin = t1; nx = 0.0; ny = 0.0; nz = sign; }
                if (t2 < tMax) tMax = t2;
                if (tMin > tMax) return -1.0;
            }

            if (tMin < 0.0 || tMin > maxDist) {
                return -1.0;
            }
            if (outNormal != null) {
                outNormal.set(nx, ny, nz);
            }
            return tMin;
        }
    }

    public static class RayHit {
        public final double distance;
        public final Vec3 point;
        public final Vec3 normal;
        public final Box box;

        public RayHit(double distance, Vec3 point, Vec3 normal, Box box) {
            this.distance = distance;
            this.point = point;
            this.normal = normal;
            this.box = box;
        }
    }

    public static class Tracer {
        public final Vec3 start;
        public final Vec3 end;
        public final int color;
        public double life;
        public final double maxLife;

        public Tracer(Vec3 start, Vec3 end, int color, double maxLife) {
            this.start = start;
            this.end = end;
            this.color = color;
            this.life = maxLife;
            this.maxLife = maxLife;
        }
    }

    public static class Particle {
        public double x, y, z;
        public double vx, vy, vz;
        public int color;
        public double size;
        public double life;
        public final double maxLife;
        public final double gravityScale;

        public Particle(double x, double y, double z,
                        double vx, double vy, double vz,
                        int color, double size, double maxLife) {
            this(x, y, z, vx, vy, vz, color, size, maxLife, 1.0);
        }

        public Particle(double x, double y, double z,
                        double vx, double vy, double vz,
                        int color, double size, double maxLife, double gravityScale) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.color = color;
            this.size = size;
            this.life = maxLife;
            this.maxLife = maxLife;
            this.gravityScale = gravityScale;
        }

        public boolean update(double dt) {
            life -= dt;
            if (life <= 0.0) return false;
            x += vx * dt;
            y += vy * dt;
            z += vz * dt;
            vy -= 12.5 * gravityScale * dt;
            if (gravityScale < 0.0) {
                vx *= (1.0 - Math.min(0.9, dt * 1.8));
                vz *= (1.0 - Math.min(0.9, dt * 1.8));
            }
            if (y < 0.03) {
                y = 0.03;
                vy = -vy * 0.28;
                vx *= 0.65;
                vz *= 0.65;
            }
            return true;
        }
    }

    /**
     * Декаль пулевого отверстия на стенах, ящиках или полу.
     */
    public static class Decal {
        public final Vec3 position;
        public final Vec3 normal;
        public final double size;
        public final int color;
        public double life;

        public Decal(Vec3 position, Vec3 normal, double size, int color, double life) {
            this.position = position;
            this.normal = normal;
            this.size = size;
            this.color = color;
            this.life = life;
        }
    }

    /**
     * Вылетающая латунная 3D-гильза с физикой отскока от пола.
     */
    public static class ShellCasing {
        public double x, y, z;
        public double vx, vy, vz;
        public double yaw, pitch;
        public double spinYaw, spinPitch;
        public final int color;
        public double life;
        public boolean bouncedSoundPlayed = false;

        public ShellCasing(double x, double y, double z,
                           double vx, double vy, double vz,
                           double yaw, int color) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.yaw = yaw;
            this.pitch = 0.0;
            this.spinYaw = (vx * 3.1) + 9.0;
            this.spinPitch = 14.0;
            this.color = color;
            this.life = 4.5;
        }

        public boolean update(double dt) {
            life -= dt;
            if (life <= 0.0) return false;
            x += vx * dt;
            y += vy * dt;
            z += vz * dt;
            vy -= 18.5 * dt;
            yaw += spinYaw * dt;
            pitch += spinPitch * dt;
            if (y <= 0.025) {
                y = 0.025;
                vy = -vy * 0.42;
                vx *= 0.58;
                vz *= 0.58;
                spinYaw *= 0.5;
                spinPitch *= 0.45;
                if (Math.abs(vy) < 0.45) {
                    vy = 0.0;
                    spinPitch = 0.0;
                    pitch = 0.0;
                }
            }
            return true;
        }
    }

    /**
     * Брошенная 3D-граната: осколочная (HE), дымовая (SMOKE), светошумовая (FLASH) или зажигательная (MOLOTOV).
     */
    public static class Grenade {
        public enum Kind { HE, SMOKE, FLASH, MOLOTOV }

        public final Kind kind;
        public final Vec3 pos;
        public final Vec3 vel;
        public double fuseTimer;
        public double yaw;

        public Grenade(Kind kind, Vec3 startPos, Vec3 startVel, double fuseSeconds) {
            this.kind = kind;
            this.pos = startPos.copy();
            this.vel = startVel.copy();
            this.fuseTimer = fuseSeconds;
            this.yaw = 0.0;
        }

        public boolean update(double dt, List<Box> boxes, double minX, double maxX, double minZ, double maxZ) {
            fuseTimer -= dt;
            if (fuseTimer <= 0.0) {
                return true; // Срабатывание взрыва / дыма / вспышки!
            }
            vel.y -= 17.5 * dt;
            yaw += dt * 7.0;

            double r = 0.12;
            // Ось X
            double nx = pos.x + vel.x * dt;
            if (nx - r < minX || nx + r > maxX || hitsAny(boxes, nx, pos.y, pos.z, r)) {
                vel.x = -vel.x * 0.52;
            } else {
                pos.x = nx;
            }

            // Ось Z
            double nz = pos.z + vel.z * dt;
            if (nz - r < minZ || nz + r > maxZ || hitsAny(boxes, pos.x, pos.y, nz, r)) {
                vel.z = -vel.z * 0.52;
            } else {
                pos.z = nz;
            }

            // Ось Y
            double ny = pos.y + vel.y * dt;
            if (ny <= r) {
                pos.y = r;
                if (kind == Kind.MOLOTOV) {
                    return true; // Коктейль Молотова разбивается сразу при ударе о землю!
                }
                vel.y = -vel.y * 0.42;
                vel.x *= 0.78;
                vel.z *= 0.78;
            } else if (hitsAny(boxes, pos.x, ny, pos.z, r)) {
                if (kind == Kind.MOLOTOV && vel.y < 0.0) {
                    return true;
                }
                vel.y = -vel.y * 0.42;
                vel.x *= 0.80;
                vel.z *= 0.80;
            } else {
                pos.y = ny;
            }
            return false;
        }

        private static boolean hitsAny(List<Box> boxes, double x, double y, double z, double r) {
            for (int i = 0; i < boxes.size(); i++) {
                Box b = boxes.get(i);
                if (b.collidable && b.intersectsAABB(x - r, y - r, z - r, x + r, y + r, z + r)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Объемное облако дыма CS2: перекрывает обзор ботам и слегка рассеивается от пуль!
     */
    public static class SmokeCloud {
        public final Vec3 center;
        public final double maxRadius;
        public double life;
        public final double maxLife;
        public double bulletHoleTimer = 0.0;

        public SmokeCloud(Vec3 center, double maxRadius, double duration) {
            this.center = center.copy();
            this.maxRadius = maxRadius;
            this.life = duration;
            this.maxLife = duration;
        }

        public double getCurrentRadius() {
            double age = maxLife - life;
            double grow = Math.min(1.0, age / 0.55);
            double fade = Math.min(1.0, life / 1.4);
            return maxRadius * grow * fade;
        }

        public boolean update(double dt) {
            life -= dt;
            if (bulletHoleTimer > 0.0) {
                bulletHoleTimer = Math.max(0.0, bulletHoleTimer - dt);
            }
            return life > 0.0;
        }

        public boolean blocksSegment(Vec3 a, Vec3 b) {
            if (bulletHoleTimer > 0.08) return false;
            double rad = getCurrentRadius() * 0.88;
            if (rad < 0.4) return false;
            Vec3 ab = b.sub(a);
            double abLenSq = ab.lengthSq();
            if (abLenSq < 1e-6) {
                return a.dist(center) <= rad;
            }
            double t = Math.max(0.0, Math.min(1.0, center.sub(a).dot(ab) / abLenSq));
            Vec3 closest = a.add(ab.scale(t));
            return closest.dist(center) <= rad;
        }
    }

    /**
     * Выпадающий с противников тактический набор (аптечка / броня + боеприпасы).
     */
    public static class Pickup {
        public enum Type { MEDKIT, AMMO_PACK }

        public final Type type;
        public final Vec3 pos;
        public double life = 18.0;
        public double bobPhase = 0.0;

        public Pickup(Type type, Vec3 pos) {
            this.type = type;
            this.pos = pos.copy();
        }

        public boolean update(double dt) {
            life -= dt;
            bobPhase += dt * 3.2;
            return life > 0.0;
        }
    }

    /**
     * Огненная зона от коктейля Молотова CS2: горит на земле, наносит урон и тушится дымовой гранатой!
     */
    public static class FireZone {
        public final Vec3 center;
        public final double maxRadius;
        public double life;
        public final double maxLife;
        public double tickTimer = 0.0;
        public double animPhase = 0.0;

        public FireZone(Vec3 center, double maxRadius, double duration) {
            this.center = new Vec3(center.x, Math.max(0.02, center.y), center.z);
            this.maxRadius = maxRadius;
            this.life = duration;
            this.maxLife = duration;
        }

        public double getCurrentRadius() {
            double age = maxLife - life;
            double grow = Math.min(1.0, 0.35 + age * 1.6);
            double fade = Math.min(1.0, life / 1.1);
            return maxRadius * grow * fade;
        }

        public boolean update(double dt) {
            life -= dt;
            animPhase += dt * 9.0;
            return life > 0.0;
        }
    }

    /**
     * Установленная взрывчатка C4 на Пленте A или Пленте B с таймером, маячком и прогрессом обезвреживания.
     */
    public static class BombC4 {
        public final Vec3 pos;
        public final String siteName;
        public double countdown = 40.0;
        public double beepTimer = 0.0;
        public double blinkPhase = 0.0;
        public double defuseProgress = 0.0;
        public double defuseRequired = 5.0;
        public boolean beingDefused = false;
        public boolean defused = false;
        public boolean exploded = false;

        public BombC4(Vec3 pos, String siteName) {
            this.pos = pos.copy();
            this.siteName = siteName;
        }
    }

    /**
     * Яркое 3D-граффити CS2 на стене, ящике или полу (наносится баллончиком по клавише Y).
     */
    public static class Graffiti {
        public final Vec3 pos;
        public final Vec3 normal;
        public final int primaryColor;
        public final int accentColor;
        public final int style; // 0 = Корона, 1 = Прицел, 2 = Молния
        public double life = 45.0;

        public Graffiti(Vec3 pos, Vec3 normal, int primaryColor, int accentColor, int style) {
            this.pos = pos.copy().add(normal.scale(0.025));
            this.normal = normal.copy();
            this.primaryColor = primaryColor;
            this.accentColor = accentColor;
            this.style = style;
        }

        public boolean update(double dt) {
            life -= dt;
            return life > 0.0;
        }
    }

    /**
     * Физическая 3D-ракета гранатомёта RPG-7 с реактивным шлейфом дыма и пламени.
     */
    public static class Rocket {
        public final Vec3 pos;
        public final Vec3 vel;
        public final double yaw;
        public final double pitch;
        public double life = 4.5;
        public double smokeEmitTimer = 0.0;

        public Rocket(Vec3 startPos, Vec3 dir, double speed) {
            this.pos = startPos.copy();
            Vec3 n = dir.normalize();
            this.vel = n.scale(speed);
            this.yaw = Math.atan2(n.x, n.z);
            this.pitch = Math.asin(Math.max(-1.0, Math.min(1.0, n.y)));
        }
    }

    /**
     * Развёртываемая 3D авто-турель спецназа CT (клавиша J) с поворотной головкой, лазером и автоматическим огнём.
     */
    public static class SentryTurret {
        public final Vec3 pos;
        public double yaw;
        public double pitch = 0.0;
        public int health = 220;
        public final int maxHealth = 220;
        public double life = 55.0;
        public double fireCooldown = 0.0;
        public double muzzleFlash = 0.0;
        public double scanPhase = 0.0;
        public boolean hasTarget = false;

        public SentryTurret(Vec3 pos, double initialYaw) {
            this.pos = pos.copy();
            this.yaw = initialYaw;
        }

        public boolean isAlive() {
            return health > 0 && life > 0.0;
        }
    }

    /**
     * Боевой ударный вертолёт-босс в небе над ареной (режим «Осада: Волны и Боссы»):
     * вращающиеся лопасти несущего и рулевого винтов, прожектор, пулемётные очереди и сбитие из RPG-7 / M249 / AWP!
     */
    public static class AttackHelicopter {
        public final Vec3 pos;
        public double orbitAngle = 0.0;
        public double yaw = 0.0;
        public double rotorPhase = 0.0;
        public int health;
        public final int maxHealth;
        public double fireCooldown = 2.0;
        public int burstShotsLeft = 0;
        public double burstShotTimer = 0.0;
        public double hitFlashTimer = 0.0;

        public AttackHelicopter(int maxHealth) {
            this.maxHealth = maxHealth;
            this.health = maxHealth;
            this.pos = new Vec3(0.0, 12.2, 0.0);
        }

        public boolean isAlive() {
            return health > 0;
        }

        public boolean takeDamage(int dmg) {
            if (health <= 0) return false;
            health = Math.max(0, health - dmg);
            hitFlashTimer = 0.14;
            return health == 0;
        }
    }

    /**
     * Воздушный сброс снабжения на парашюте (Supply Drop) с боеприпасами, бронёй, турелью и бонусом $.
     */
    public static class SupplyDrop {
        public final Vec3 pos;
        public double beaconPhase = 0.0;
        public double life = 40.0;
        public boolean landed = false;

        public SupplyDrop(double x, double startY, double z) {
            this.pos = new Vec3(x, startY, z);
        }

        public boolean update(double dt) {
            life -= dt;
            beaconPhase += dt * 5.5;
            if (!landed) {
                pos.y -= dt * 3.4;
                if (pos.y <= 0.0) {
                    pos.y = 0.0;
                    landed = true;
                }
            }
            return life > 0.0;
        }
    }

    /**
     * Лазерный маяк орбитального / артиллерийского авиаудара спецназа [U] (спускает залп из 3 ракет с неба!).
     */
    public static class AirstrikeBeacon {
        public final Vec3 targetPos;
        public double countdown = 1.45;
        public int shellsLaunched = 0;
        public double nextShellTimer = 0.0;
        public double animPhase = 0.0;

        public AirstrikeBeacon(Vec3 targetPos) {
            this.targetPos = targetPos.copy();
        }
    }
}
