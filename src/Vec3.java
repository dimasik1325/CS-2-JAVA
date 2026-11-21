/**
 * Базовые 3D-структуры данных: вектор Vec3, 3D-коробка коллизий (Box),
 * результат рейкаста (RayHit), трассер пули (Tracer) и 3D-частица (Particle).
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
     * Выровненный по осям 3D-параллелепипед (AABB) для стен, ящиков, укрытий и платформ.
     */
    public static class Box {
        public final double minX, minY, minZ;
        public final double maxX, maxY, maxZ;
        public final int textureId;
        public final int tintColor;
        public final double uvScale;
        public final boolean collidable;

        public Box(double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ,
                   int textureId, int tintColor, double uvScale, boolean collidable) {
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
        }

        public boolean intersectsAABB(double aMinX, double aMinY, double aMinZ,
                                      double aMaxX, double aMaxY, double aMaxZ) {
            return aMaxX > minX && aMinX < maxX
                && aMaxY > minY && aMinY < maxY
                && aMaxZ > minZ && aMinZ < maxZ;
        }

        /**
         * Проверка пересечения луча с AABB методом слэбов (Slab method).
         * Возвращает расстояние t >= 0 до точки входа или -1 при отсутствии попадания.
         */
        public double intersectRay(Vec3 origin, Vec3 dir, double maxDist, Vec3 outNormal) {
            double tMin = 0.0;
            double tMax = maxDist;
            double nx = 0.0, ny = 0.0, nz = 0.0;

            // Ось X
            if (Math.abs(dir.x) < 1e-9) {
                if (origin.x < minX || origin.x > maxX) return -1.0;
            } else {
                double inv = 1.0 / dir.x;
                double t1 = (minX - origin.x) * inv;
                double t2 = (maxX - origin.x) * inv;
                double sign = -1.0;
                if (t1 > t2) {
                    double tmp = t1; t1 = t2; t2 = tmp;
                    sign = 1.0;
                }
                if (t1 > tMin) {
                    tMin = t1;
                    nx = sign; ny = 0.0; nz = 0.0;
                }
                if (t2 < tMax) tMax = t2;
                if (tMin > tMax) return -1.0;
            }

            // Ось Y
            if (Math.abs(dir.y) < 1e-9) {
                if (origin.y < minY || origin.y > maxY) return -1.0;
            } else {
                double inv = 1.0 / dir.y;
                double t1 = (minY - origin.y) * inv;
                double t2 = (maxY - origin.y) * inv;
                double sign = -1.0;
                if (t1 > t2) {
                    double tmp = t1; t1 = t2; t2 = tmp;
                    sign = 1.0;
                }
                if (t1 > tMin) {
                    tMin = t1;
                    nx = 0.0; ny = sign; nz = 0.0;
                }
                if (t2 < tMax) tMax = t2;
                if (tMin > tMax) return -1.0;
            }

            // Ось Z
            if (Math.abs(dir.z) < 1e-9) {
                if (origin.z < minZ || origin.z > maxZ) return -1.0;
            } else {
                double inv = 1.0 / dir.z;
                double t1 = (minZ - origin.z) * inv;
                double t2 = (maxZ - origin.z) * inv;
                double sign = -1.0;
                if (t1 > t2) {
                    double tmp = t1; t1 = t2; t2 = tmp;
                    sign = 1.0;
                }
                if (t1 > tMin) {
                    tMin = t1;
                    nx = 0.0; ny = 0.0; nz = sign;
                }
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

    /**
     * Результат попадания луча в геометрию карты.
     */
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

    /**
     * Визуальный 3D-трассер выстрела.
     */
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

    /**
     * 3D-частица попадания (пыль от стен/ящиков, искры, попадание по врагу).
     */
    public static class Particle {
        public double x, y, z;
        public double vx, vy, vz;
        public int color;
        public double size;
        public double life;
        public double maxLife;

        public Particle(double x, double y, double z,
                        double vx, double vy, double vz,
                        int color, double size, double maxLife) {
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
        }

        public boolean update(double dt) {
            life -= dt;
            if (life <= 0.0) return false;
            x += vx * dt;
            y += vy * dt;
            z += vz * dt;
            vy -= 12.0 * dt;
            if (y < 0.03) {
                y = 0.03;
                vy = -vy * 0.25;
                vx *= 0.65;
                vz *= 0.65;
            }
            return true;
        }
    }
}
