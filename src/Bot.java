import java.util.List;
import java.util.Random;

/**
 * Противник под управлением ИИ (Бот):
 * - Патрулирует вейпоинты арены Dust 2
 * - Обнаруживает игрока по прямой видимости (LOS) и звуку выстрелов
 * - Сближается, маневрирует стрейфами и ведет ответный огонь очередями
 * - Имеет раздельные хитбоксы головы (Headshot) и корпуса, индикацию попадания и 3D-анимацию смерти
 */
public class Bot {

    public enum State {
        PATROL,
        ENGAGE,
        SEARCH
    }

    public static class HitResult {
        public final double distance;
        public final Vec3 point;
        public final boolean headshot;

        public HitResult(double distance, Vec3 point, boolean headshot) {
            this.distance = distance;
            this.point = point;
            this.headshot = headshot;
        }
    }

    public static class ShotEvent {
        public final Vec3 muzzlePos;
        public final Vec3 endPos;
        public final boolean hitPlayer;
        public final int damage;

        public ShotEvent(Vec3 muzzlePos, Vec3 endPos, boolean hitPlayer, int damage) {
            this.muzzlePos = muzzlePos;
            this.endPos = endPos;
            this.hitPlayer = hitPlayer;
            this.damage = damage;
        }
    }

    private static final double RADIUS = 0.35;
    private static final double HEIGHT = 1.78;
    private static final double EYE_HEIGHT = 1.58;
    private static final double PATROL_SPEED = 3.25;
    private static final double COMBAT_SPEED = 4.25;
    private static final double GRAVITY = 20.0;

    private final String name;
    private final Random rng;

    private final Vec3 position = new Vec3();
    private double velocityY = 0.0;
    private double yaw = 0.0;
    private int health = 100;
    private boolean alive = true;

    private State state = State.PATROL;
    private int currentWaypointIndex = 0;
    private final Vec3 lastSeenPlayerPos = new Vec3();
    private double searchTimer = 0.0;
    private double stuckTimer = 0.0;
    private final Vec3 lastProgressPos = new Vec3();

    // Боевые таймеры
    private double reactionTimer = 0.35;
    private double fireCooldown = 0.45;
    private int burstRemaining = 3;
    private double strafeDir = 1.0;
    private double strafeSwitchTimer = 1.4;

    // Визуальная анимация и индикация урона/смерти
    private double walkPhase = 0.0;
    private double hitFlashTimer = 0.0;
    private double muzzleFlashTimer = 0.0;
    private double healthBarTimer = 0.0;
    private double deathAnimProgress = 0.0;

    public Bot(String name, Vec3 spawnPos, int initialWaypoint, long seed) {
        this.name = name;
        this.rng = new Random(seed);
        respawn(spawnPos, initialWaypoint);
    }

    public void respawn(Vec3 spawnPos, int initialWaypoint) {
        this.position.set(spawnPos);
        this.lastProgressPos.set(spawnPos);
        this.velocityY = 0.0;
        this.yaw = Math.PI + (rng.nextDouble() - 0.5) * 0.6;
        this.health = 100;
        this.alive = true;
        this.state = State.PATROL;
        this.currentWaypointIndex = initialWaypoint;
        this.searchTimer = 0.0;
        this.stuckTimer = 0.0;
        this.reactionTimer = 0.38;
        this.fireCooldown = 0.45 + rng.nextDouble() * 0.35;
        this.burstRemaining = 3;
        this.strafeDir = rng.nextBoolean() ? 1.0 : -1.0;
        this.strafeSwitchTimer = 1.0 + rng.nextDouble() * 1.2;
        this.hitFlashTimer = 0.0;
        this.muzzleFlashTimer = 0.0;
        this.healthBarTimer = 0.0;
        this.deathAnimProgress = 0.0;
    }

    /**
     * Оповещает бота о громком выстреле игрока поблизости.
     */
    public void notifyPlayerGunshot(Vec3 shotPos) {
        if (!alive) return;
        double dist = position.dist(shotPos);
        if (dist < 23.0 && state == State.PATROL) {
            lastSeenPlayerPos.set(shotPos);
            state = State.SEARCH;
            searchTimer = 5.0;
        }
    }

    /**
     * Обновляет ИИ бота, передвижение и стрельбу. Возвращает событие выстрела, если бот выстрелил в этом кадре.
     */
    public ShotEvent update(double dt, Map map, Player player, List<Bot> allBots) {
        if (hitFlashTimer > 0.0) hitFlashTimer = Math.max(0.0, hitFlashTimer - dt);
        if (muzzleFlashTimer > 0.0) muzzleFlashTimer = Math.max(0.0, muzzleFlashTimer - dt);
        if (healthBarTimer > 0.0) healthBarTimer = Math.max(0.0, healthBarTimer - dt);

        if (!alive) {
            if (deathAnimProgress < 1.0) {
                deathAnimProgress = Math.min(1.0, deathAnimProgress + dt * 2.35);
            }
            applyVerticalPhysics(dt, map);
            return null;
        }

        boolean seesPlayer = canSeePlayer(map, player);
        if (seesPlayer) {
            lastSeenPlayerPos.set(player.getPosition());
            if (state != State.ENGAGE) {
                state = State.ENGAGE;
                reactionTimer = 0.30 + rng.nextDouble() * 0.18;
            }
            searchTimer = 5.5;
        } else if (state == State.ENGAGE) {
            state = State.SEARCH;
        }

        ShotEvent shotEvent = null;
        double moveX = 0.0;
        double moveZ = 0.0;

        if (state == State.ENGAGE && player.isAlive()) {
            double dx = player.getPosition().x - position.x;
            double dz = player.getPosition().z - position.z;
            double targetYaw = Math.atan2(dx, dz);
            yaw = turnSmoothly(yaw, targetYaw, dt * 7.2);

            double distXZ = Math.sqrt(dx * dx + dz * dz);
            double dirX = distXZ > 1e-4 ? dx / distXZ : 0.0;
            double dirZ = distXZ > 1e-4 ? dz / distXZ : 1.0;

            strafeSwitchTimer -= dt;
            if (strafeSwitchTimer <= 0.0) {
                strafeDir = -strafeDir;
                strafeSwitchTimer = 1.0 + rng.nextDouble() * 1.4;
            }

            double rightX = dirZ;
            double rightZ = -dirX;

            if (distXZ > 13.5) {
                moveX = (dirX * 0.88 + rightX * strafeDir * 0.32) * COMBAT_SPEED;
                moveZ = (dirZ * 0.88 + rightZ * strafeDir * 0.32) * COMBAT_SPEED;
            } else if (distXZ < 4.2) {
                moveX = (-dirX * 0.65 + rightX * strafeDir * 0.75) * COMBAT_SPEED;
                moveZ = (-dirZ * 0.65 + rightZ * strafeDir * 0.75) * COMBAT_SPEED;
            } else {
                moveX = rightX * strafeDir * (COMBAT_SPEED * 0.78);
                moveZ = rightZ * strafeDir * (COMBAT_SPEED * 0.78);
            }

            if (reactionTimer > 0.0) {
                reactionTimer -= dt;
            } else {
                fireCooldown -= dt;
                if (fireCooldown <= 0.0 && seesPlayer) {
                    shotEvent = fireAtPlayer(map, player);
                    burstRemaining--;
                    if (burstRemaining <= 0) {
                        burstRemaining = 2 + rng.nextInt(3);
                        fireCooldown = 0.68 + rng.nextDouble() * 0.38;
                    } else {
                        fireCooldown = 0.17 + rng.nextDouble() * 0.05;
                    }
                }
            }
        } else if (state == State.SEARCH) {
            searchTimer -= dt;
            double dx = lastSeenPlayerPos.x - position.x;
            double dz = lastSeenPlayerPos.z - position.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (searchTimer <= 0.0 || dist < 1.35) {
                state = State.PATROL;
                pickNextWaypoint(map.getPatrolWaypoints());
            } else {
                double targetYaw = Math.atan2(dx, dz);
                yaw = turnSmoothly(yaw, targetYaw, dt * 5.2);
                moveX = (dx / dist) * (COMBAT_SPEED * 0.88);
                moveZ = (dz / dist) * (COMBAT_SPEED * 0.88);
            }
        } else {
            // Состояние PATROL
            List<Vec3> wps = map.getPatrolWaypoints();
            if (!wps.isEmpty()) {
                Vec3 wp = wps.get(currentWaypointIndex % wps.size());
                double dx = wp.x - position.x;
                double dz = wp.z - position.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < 1.35) {
                    pickNextWaypoint(wps);
                } else {
                    double targetYaw = Math.atan2(dx, dz);
                    yaw = turnSmoothly(yaw, targetYaw, dt * 4.5);
                    moveX = (dx / dist) * PATROL_SPEED;
                    moveZ = (dz / dist) * PATROL_SPEED;
                }
            }
        }

        // Отталкивание ботов друг от друга, чтобы не слипались в проходах
        for (int i = 0; i < allBots.size(); i++) {
            Bot other = allBots.get(i);
            if (other == this || !other.alive) continue;
            double ox = position.x - other.position.x;
            double oz = position.z - other.position.z;
            double d2 = ox * ox + oz * oz;
            double minSep = 0.95;
            if (d2 > 1e-6 && d2 < minSep * minSep) {
                double d = Math.sqrt(d2);
                double push = (minSep - d) * 4.5;
                moveX += (ox / d) * push;
                moveZ += (oz / d) * push;
            }
        }

        boolean movedX = tryMoveBotAxis(map, moveX * dt, 0.0);
        boolean movedZ = tryMoveBotAxis(map, 0.0, moveZ * dt);
        applyVerticalPhysics(dt, map);

        double speedSq = moveX * moveX + moveZ * moveZ;
        if (speedSq > 0.25 && (movedX || movedZ)) {
            walkPhase += dt * Math.sqrt(speedSq) * 2.1;
        }

        // Анти-застревание при патрулировании/поиске
        stuckTimer += dt;
        if (stuckTimer >= 1.1) {
            if (speedSq > 1.0 && position.distXZ(lastProgressPos) < 0.22) {
                strafeDir = -strafeDir;
                if (state == State.PATROL) {
                    pickNextWaypoint(map.getPatrolWaypoints());
                } else if (state == State.SEARCH) {
                    state = State.PATROL;
                    pickNextWaypoint(map.getPatrolWaypoints());
                }
            }
            lastProgressPos.set(position);
            stuckTimer = 0.0;
        }

        return shotEvent;
    }

    private void pickNextWaypoint(List<Vec3> waypoints) {
        if (waypoints.isEmpty()) return;
        int bestIdx = rng.nextInt(waypoints.size());
        double bestScore = Double.MAX_VALUE;
        for (int attempt = 0; attempt < 4; attempt++) {
            int candidate = rng.nextInt(waypoints.size());
            if (candidate == currentWaypointIndex) continue;
            double d = position.distXZ(waypoints.get(candidate));
            if (d > 4.0 && d < bestScore) {
                bestScore = d;
                bestIdx = candidate;
            }
        }
        currentWaypointIndex = bestIdx;
    }

    private boolean canSeePlayer(Map map, Player player) {
        if (!player.isAlive()) return false;
        Vec3 eye = getEyePosition();
        Vec3 targetEye = player.getEyePosition();
        double dist = eye.dist(targetEye);
        if (dist > 41.0) return false;

        // Проверка угла обзора бота (вблизи чувствует на 360 градусов)
        if (dist > 7.5 && state == State.PATROL) {
            Vec3 toPlayer = targetEye.sub(eye).normalize();
            Vec3 forward = new Vec3(Math.sin(yaw), 0.0, Math.cos(yaw));
            if (forward.dot(new Vec3(toPlayer.x, 0.0, toPlayer.z).normalize()) < -0.18) {
                return false;
            }
        }

        if (map.hasLineOfSight(eye, targetEye)) {
            return true;
        }
        return map.hasLineOfSight(eye, player.getCenterTargetPosition());
    }

    private ShotEvent fireAtPlayer(Map map, Player player) {
        muzzleFlashTimer = 0.075;
        Vec3 muzzle = getMuzzleWorldPosition();
        Vec3 aimTarget = player.getCenterTargetPosition();
        Vec3 baseDir = aimTarget.sub(muzzle).normalize();

        // Реалистичный разброс стрельбы бота (увеличивается на дистанции и если игрок сидит/двигается)
        double spread = 0.048;
        if (player.isCrouching()) spread += 0.018;
        if (player.getHorizontalSpeed() > 3.5) spread += 0.020;

        Vec3 shotDir = new Vec3(
            baseDir.x + (rng.nextGaussian()) * spread,
            baseDir.y + (rng.nextGaussian()) * (spread * 0.78),
            baseDir.z + (rng.nextGaussian()) * spread
        ).normalize();

        Vec3.RayHit wallHit = map.raycast(muzzle, shotDir, 70.0);
        double maxDist = (wallHit != null) ? wallHit.distance : 70.0;
        Vec3 endPos = (wallHit != null) ? wallHit.point : muzzle.add(shotDir.scale(70.0));

        // Проверка пересечения луча выстрела бота с AABB игрока
        Vec3 pPos = player.getPosition();
        double pH = player.getCurrentBodyHeight();
        Vec3.Box playerBox = new Vec3.Box(
            pPos.x - Player.RADIUS, pPos.y, pPos.z - Player.RADIUS,
            pPos.x + Player.RADIUS, pPos.y + pH, pPos.z + Player.RADIUS,
            0, 0, 0, true
        );
        double tPlayer = playerBox.intersectRay(muzzle, shotDir, maxDist, null);
        if (tPlayer >= 0.0 && tPlayer < maxDist) {
            Vec3 hitPt = muzzle.add(shotDir.scale(tPlayer));
            int dmg = 11 + rng.nextInt(6);
            return new ShotEvent(muzzle, hitPt, true, dmg);
        }

        return new ShotEvent(muzzle, endPos, false, 0);
    }

    private boolean tryMoveBotAxis(Map map, double dx, double dz) {
        if (Math.abs(dx) < 1e-7 && Math.abs(dz) < 1e-7) return false;
        double nx = position.x + dx;
        double nz = position.z + dz;

        if (!map.collides(nx, position.y, nz, RADIUS, HEIGHT)) {
            position.x = nx;
            position.z = nz;
            return true;
        }
        // Подъем на ступеньки платформ (Плент A)
        for (double step = 0.12; step <= 0.34; step += 0.10) {
            if (!map.collides(nx, position.y + step, nz, RADIUS, HEIGHT)) {
                position.x = nx;
                position.z = nz;
                position.y += step;
                return true;
            }
        }
        if (state == State.ENGAGE) {
            strafeDir = -strafeDir;
        }
        return false;
    }

    private void applyVerticalPhysics(double dt, Map map) {
        velocityY -= GRAVITY * dt;
        double nextY = position.y + velocityY * dt;
        double floorY = map.getSupportingFloorY(position.x, position.z, RADIUS, position.y);
        if (nextY <= floorY) {
            position.y = floorY;
            velocityY = 0.0;
        } else if (!map.collides(position.x, nextY, position.z, RADIUS, HEIGHT)) {
            position.y = nextY;
        } else {
            velocityY = 0.0;
        }
    }

    /**
     * Проверяет попадание луча выстрела игрока в хитбоксы головы и тела бота.
     */
    public HitResult intersectShotRay(Vec3 origin, Vec3 dir, double maxDist) {
        if (!alive) return null;

        Vec3.Box headBox = new Vec3.Box(
            position.x - 0.21, position.y + 1.38, position.z - 0.21,
            position.x + 0.21, position.y + 1.82, position.z + 0.21,
            0, 0, 0, true
        );
        Vec3.Box bodyBox = new Vec3.Box(
            position.x - 0.33, position.y + 0.02, position.z - 0.33,
            position.x + 0.33, position.y + 1.40, position.z + 0.33,
            0, 0, 0, true
        );

        double tHead = headBox.intersectRay(origin, dir, maxDist, null);
        double tBody = bodyBox.intersectRay(origin, dir, maxDist, null);

        if (tHead >= 0.0 && (tBody < 0.0 || tHead <= tBody + 0.05)) {
            return new HitResult(tHead, origin.add(dir.scale(tHead)), true);
        }
        if (tBody >= 0.0) {
            return new HitResult(tBody, origin.add(dir.scale(tBody)), false);
        }
        return null;
    }

    public boolean takeDamage(int damage, Vec3 attackerPos) {
        if (!alive || damage <= 0) return false;
        health = Math.max(0, health - damage);
        hitFlashTimer = 0.16;
        healthBarTimer = 4.5;

        if (attackerPos != null) {
            lastSeenPlayerPos.set(attackerPos);
            if (state == State.PATROL) {
                state = State.SEARCH;
                searchTimer = 6.0;
            }
        }

        if (health <= 0) {
            alive = false;
            deathAnimProgress = 0.0;
            return true;
        }
        return false;
    }

    /**
     * Отрисовка составной 3D-модели бота (ноги, торс в бронежилете, руки, голова в балаклаве, автомат и вспышка).
     */
    public void render(Renderer3D r) {
        boolean flashing = hitFlashTimer > 0.0 && alive;

        int pantsColor = flashing ? 0xFF5555 : 0x3D4652;
        int bootsColor = flashing ? 0xFF4444 : 0x1E2126;
        int shirtColor = flashing ? 0xFF7070 : 0xB88B58;
        int vestColor  = flashing ? 0xFF3B3B : 0x2F362B;
        int skinColor  = flashing ? 0xFF9090 : 0xD2A079;
        int maskColor  = flashing ? 0xFF4848 : 0x292423;
        int bandColor  = flashing ? 0xFFAAAA : 0x9E2A25;
        int gunColor   = 0x24282E;
        int gunWood    = 0x8B4A22;

        if (!alive) {
            // 3D-анимация падения убитого противника на песок
            double fallAngle = deathAnimProgress * (Math.PI * 0.48);
            double sinF = Math.sin(fallAngle);
            double cosF = Math.cos(fallAngle);
            double baseY = position.y + 0.16 * sinF;

            drawPartFallen(r, 0.0, 0.42, -0.14, 0.18, 0.72, 0.19, fallAngle, sinF, cosF, baseY, pantsColor);
            drawPartFallen(r, 0.0, 0.42,  0.14, 0.18, 0.72, 0.19, fallAngle, sinF, cosF, baseY, pantsColor);
            drawPartFallen(r, 0.0, 1.04,  0.00, 0.30, 0.62, 0.46, fallAngle, sinF, cosF, baseY, vestColor);
            drawPartFallen(r, 0.0, 1.54,  0.00, 0.30, 0.32, 0.30, fallAngle, sinF, cosF, baseY, maskColor);
            // Упавший рядом автомат
            double gx = position.x + Math.cos(yaw) * 0.45;
            double gz = position.z - Math.sin(yaw) * 0.45;
            r.drawOrientedWorldBox(gx, position.y + 0.06, gz, 0.08, 0.10, 0.68, yaw + 0.5, 0.0, Renderer3D.TEX_SOLID, gunColor);
            return;
        }

        double legSwing = Math.sin(walkPhase) * 0.38;
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double rightX = cosY;
        double rightZ = -sinY;

        double bx = position.x;
        double by = position.y;
        double bz = position.z;

        // Левая и правая ноги с анимацией шага
        r.drawOrientedWorldBox(bx - rightX * 0.14, by + 0.38, bz - rightZ * 0.14,
                0.18, 0.74, 0.20, yaw, legSwing, Renderer3D.TEX_SOLID, pantsColor);
        r.drawOrientedWorldBox(bx + rightX * 0.14, by + 0.38, bz + rightZ * 0.14,
                0.18, 0.74, 0.20, yaw, -legSwing, Renderer3D.TEX_SOLID, pantsColor);

        // Ботинки
        r.drawOrientedWorldBox(bx - rightX * 0.14, by + 0.08, bz - rightZ * 0.14,
                0.19, 0.16, 0.26, yaw, legSwing * 0.5, Renderer3D.TEX_SOLID, bootsColor);
        r.drawOrientedWorldBox(bx + rightX * 0.14, by + 0.08, bz + rightZ * 0.14,
                0.19, 0.16, 0.26, yaw, -legSwing * 0.5, Renderer3D.TEX_SOLID, bootsColor);

        // Торс и тактический жилет
        r.drawOrientedWorldBox(bx, by + 1.06, bz, 0.46, 0.64, 0.28, yaw, 0.0, Renderer3D.TEX_SOLID, shirtColor);
        r.drawOrientedWorldBox(bx, by + 1.08, bz, 0.48, 0.48, 0.31, yaw, 0.0, Renderer3D.TEX_SOLID, vestColor);

        // Плечи и руки, удерживающие винтовку
        r.drawOrientedWorldBox(bx - rightX * 0.29 + sinY * 0.14, by + 1.16, bz - rightZ * 0.29 + cosY * 0.14,
                0.15, 0.22, 0.42, yaw + 0.18, 0.0, Renderer3D.TEX_SOLID, shirtColor);
        r.drawOrientedWorldBox(bx + rightX * 0.27 + sinY * 0.15, by + 1.14, bz + rightZ * 0.27 + cosY * 0.15,
                0.15, 0.22, 0.44, yaw - 0.12, 0.0, Renderer3D.TEX_SOLID, shirtColor);

        // Голова, балаклава и красная повязка противника (хорошо читаемый силуэт на фоне песка!)
        r.drawOrientedWorldBox(bx, by + 1.57, bz, 0.31, 0.34, 0.31, yaw, 0.0, Renderer3D.TEX_SOLID, maskColor);
        r.drawOrientedWorldBox(bx + sinY * 0.14, by + 1.58, bz + cosY * 0.14, 0.24, 0.10, 0.06, yaw, 0.0, Renderer3D.TEX_SOLID, skinColor);
        r.drawOrientedWorldBox(bx, by + 1.67, bz, 0.33, 0.08, 0.33, yaw, 0.0, Renderer3D.TEX_SOLID, bandColor);

        // Винтовка AK в руках бота
        double gunCenterX = bx + rightX * 0.15 + sinY * 0.36;
        double gunCenterY = by + 1.15;
        double gunCenterZ = bz + rightZ * 0.15 + cosY * 0.36;
        r.drawOrientedWorldBox(gunCenterX, gunCenterY, gunCenterZ,
                0.07, 0.11, 0.68, yaw, 0.0, Renderer3D.TEX_SOLID, gunColor);
        r.drawOrientedWorldBox(gunCenterX - sinY * 0.16, gunCenterY - 0.02, gunCenterZ - cosY * 0.16,
                0.08, 0.10, 0.24, yaw, 0.0, Renderer3D.TEX_SOLID, gunWood);

        // Вспышка выстрела на стволе бота
        if (muzzleFlashTimer > 0.0) {
            Vec3 muz = getMuzzleWorldPosition();
            r.drawOrientedWorldBox(muz.x, muz.y, muz.z, 0.24, 0.24, 0.24, yaw, 0.4, Renderer3D.TEX_SOLID, 0xFFCA3A);
        }
    }

    private void drawPartFallen(Renderer3D r, double sideOffset, double heightOffset, double zOffset,
                                double sx, double sy, double sz,
                                double fallAngle, double sinF, double cosF, double baseY, int color) {
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double rightX = cosY;
        double rightZ = -sinY;

        // Падение назад вдоль направления -forward
        double localY = heightOffset * cosF;
        double backDist = -heightOffset * sinF;

        double wx = position.x + rightX * (sideOffset + zOffset) + sinY * backDist;
        double wy = baseY + Math.max(0.08, localY);
        double wz = position.z + rightZ * (sideOffset + zOffset) + cosY * backDist;

        r.drawOrientedWorldBox(wx, wy, wz, sz, sy, sx, yaw, -fallAngle, Renderer3D.TEX_SOLID, color);
    }

    private static double turnSmoothly(double current, double target, double maxDelta) {
        double diff = target - current;
        while (diff > Math.PI) diff -= Math.PI * 2.0;
        while (diff < -Math.PI) diff += Math.PI * 2.0;
        if (Math.abs(diff) <= maxDelta) {
            return target;
        }
        return current + Math.copySign(maxDelta, diff);
    }

    public Vec3 getPosition() { return position; }
    public Vec3 getEyePosition() { return new Vec3(position.x, position.y + EYE_HEIGHT, position.z); }
    public Vec3 getMuzzleWorldPosition() {
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        return new Vec3(
            position.x + cosY * 0.15 + sinY * 0.72,
            position.y + 1.16,
            position.z - sinY * 0.15 + cosY * 0.72
        );
    }

    public String getName() { return name; }
    public int getHealth() { return health; }
    public boolean isAlive() { return alive; }
    public State getState() { return state; }
    public boolean shouldShowHealthBar() { return alive && (health < 100 || healthBarTimer > 0.0); }
    public boolean isFiringRecently() { return muzzleFlashTimer > 0.0; }
}
