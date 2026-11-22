import java.util.List;
import java.util.Random;

/**
 * Тактический ИИ-боец (Террорист T или Союзник Спецназа CT) в CS-2 JAVA 4.0:
 * - Поддержка двух команд: вражеский отряд T и союзный отряд спецназа CT
 * - Выполнение радиокоманд игрока (За мной! / Держать позицию! / Штурм плента!)
 * - Перестрелки «стенка на стенку» (CT против T), учет дымовых гранат CS2, флешек и глушителя
 * - Установка C4 террористами и помощь спецназа CT в разминировании заложенной бомбы
 */
public class Bot {

    public enum Team {
        TERRORIST,
        COUNTER_TERRORIST
    }

    public enum Archetype {
        STANDARD,
        SNIPER,
        JUGGERNAUT
    }

    public enum State {
        PATROL,
        ENGAGE,
        SEARCH
    }

    public enum RadioOrder {
        PATROL_FREE,
        FOLLOW_PLAYER,
        HOLD_POSITION,
        RUSH_SITE
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
        public final Bot hitBot;
        public final int damage;

        public ShotEvent(Vec3 muzzlePos, Vec3 endPos, boolean hitPlayer, Bot hitBot, int damage) {
            this.muzzlePos = muzzlePos;
            this.endPos = endPos;
            this.hitPlayer = hitPlayer;
            this.hitBot = hitBot;
            this.damage = damage;
        }
    }

    private static final double RADIUS = 0.35;
    private static final double HEIGHT = 1.78;
    private static final double EYE_HEIGHT = 1.58;
    private static final double PATROL_SPEED = 3.35;
    private static final double GRAVITY = 20.0;

    private final String name;
    private final Team team;
    private Archetype archetype = Archetype.STANDARD;
    private final Random rng;

    private final Vec3 position = new Vec3();
    private double velocityY = 0.0;
    private double yaw = 0.0;
    private int maxHealth = 100;
    private int health = 100;
    private boolean alive = true;

    private State state = State.PATROL;
    private RadioOrder radioOrder = RadioOrder.PATROL_FREE;
    private final Vec3 holdAnchorPos = new Vec3();
    private int currentWaypointIndex = 0;
    private final Vec3 lastSeenEnemyPos = new Vec3();
    private double searchTimer = 0.0;
    private double stuckTimer = 0.0;
    private final Vec3 lastProgressPos = new Vec3();

    private double reactionTimer = 0.35;
    private double fireCooldown = 0.45;
    private int burstRemaining = 3;
    private double strafeDir = 1.0;
    private double strafeSwitchTimer = 1.4;

    private double walkPhase = 0.0;
    private double hitFlashTimer = 0.0;
    private double muzzleFlashTimer = 0.0;
    private double healthBarTimer = 0.0;
    private double deathAnimProgress = 0.0;
    private double blindTimer = 0.0;
    private double respawnTimer = 0.0;

    // Статистика бойца для таблицы счета Tab
    private int kills = 0;
    private int deaths = 0;

    // Миссия установки взрывчатки C4 (для T-ботов в режиме Операция C4)
    private boolean carryingC4 = false;
    private Vec3 c4TargetSpot = null;
    private String c4SiteName = "A";
    private double c4PlantTimer = 0.0;
    private boolean c4PlantCompleted = false;

    public Bot(String name, Vec3 spawnPos, int initialWaypoint, long seed) {
        this(name, Team.TERRORIST, spawnPos, initialWaypoint, seed);
    }

    public Bot(String name, Team team, Vec3 spawnPos, int initialWaypoint, long seed) {
        this.name = name;
        this.team = team;
        this.rng = new Random(seed);
        respawn(spawnPos, initialWaypoint);
    }

    public void setArchetype(Archetype arch) {
        this.archetype = (arch != null) ? arch : Archetype.STANDARD;
        if (this.archetype == Archetype.JUGGERNAUT) {
            this.maxHealth = 460;
        } else if (this.archetype == Archetype.SNIPER) {
            this.maxHealth = 95;
        } else {
            this.maxHealth = 100;
        }
        this.health = this.maxHealth;
    }

    public Archetype getArchetype() { return archetype; }
    public int getMaxHealth() { return maxHealth; }
    public double getModelScale() {
        return archetype == Archetype.JUGGERNAUT ? 1.26 : 1.0;
    }

    public void respawn(Vec3 spawnPos, int initialWaypoint) {
        this.position.set(spawnPos);
        this.lastProgressPos.set(spawnPos);
        this.holdAnchorPos.set(spawnPos);
        this.velocityY = 0.0;
        this.yaw = (team == Team.COUNTER_TERRORIST ? 0.0 : Math.PI) + (rng.nextDouble() - 0.5) * 0.5;
        this.health = this.maxHealth;
        this.alive = true;
        this.state = State.PATROL;
        this.radioOrder = RadioOrder.PATROL_FREE;
        this.currentWaypointIndex = initialWaypoint;
        this.searchTimer = 0.0;
        this.stuckTimer = 0.0;
        this.reactionTimer = 0.35;
        this.fireCooldown = 0.45 + rng.nextDouble() * 0.35;
        this.burstRemaining = 3;
        this.strafeDir = rng.nextBoolean() ? 1.0 : -1.0;
        this.strafeSwitchTimer = 1.0 + rng.nextDouble() * 1.2;
        this.hitFlashTimer = 0.0;
        this.muzzleFlashTimer = 0.0;
        this.healthBarTimer = 0.0;
        this.deathAnimProgress = 0.0;
        this.blindTimer = 0.0;
        this.respawnTimer = 0.0;
        this.carryingC4 = false;
        this.c4TargetSpot = null;
        this.c4PlantTimer = 0.0;
        this.c4PlantCompleted = false;
    }

    public void resetMatchStats() {
        this.kills = 0;
        this.deaths = 0;
    }

    public void recordKill() {
        this.kills++;
    }

    public void setRadioOrder(RadioOrder order) {
        this.radioOrder = order;
        if (order == RadioOrder.HOLD_POSITION) {
            this.holdAnchorPos.set(this.position);
        }
    }

    public RadioOrder getRadioOrder() {
        return radioOrder;
    }

    public void assignBombCarrier(Vec3 siteSpot, String siteName) {
        if (team != Team.TERRORIST) return;
        this.carryingC4 = true;
        this.c4TargetSpot = siteSpot.copy();
        this.c4SiteName = siteName;
        this.c4PlantTimer = 0.0;
        this.c4PlantCompleted = false;
    }

    public void applyFlashBlind(double seconds) {
        if (!alive) return;
        this.blindTimer = Math.max(this.blindTimer, seconds);
        this.healthBarTimer = Math.max(this.healthBarTimer, seconds);
    }

    public boolean consumeC4PlantReady() {
        if (c4PlantCompleted) {
            c4PlantCompleted = false;
            carryingC4 = false;
            return true;
        }
        return false;
    }

    public void notifyPlayerGunshot(Vec3 shotPos, boolean silenced) {
        if (!alive || team == Team.COUNTER_TERRORIST) return;
        double hearRadius = silenced ? 6.5 : 24.0;
        if (position.dist(shotPos) < hearRadius && state == State.PATROL) {
            lastSeenEnemyPos.set(shotPos);
            state = State.SEARCH;
            searchTimer = 5.2;
        }
    }

    public ShotEvent update(double dt, Map map, Player player, List<Bot> allBots,
                            List<Vec3.SmokeCloud> smokes, GameSettings.BotDifficulty difficulty) {
        return update(dt, map, player, allBots, smokes, difficulty, null);
    }

    public ShotEvent update(double dt, Map map, Player player, List<Bot> allBots,
                            List<Vec3.SmokeCloud> smokes, GameSettings.BotDifficulty difficulty,
                            Vec3.BombC4 plantedC4) {
        if (hitFlashTimer > 0.0) hitFlashTimer = Math.max(0.0, hitFlashTimer - dt);
        if (muzzleFlashTimer > 0.0) muzzleFlashTimer = Math.max(0.0, muzzleFlashTimer - dt);
        if (healthBarTimer > 0.0) healthBarTimer = Math.max(0.0, healthBarTimer - dt);
        if (blindTimer > 0.0) blindTimer = Math.max(0.0, blindTimer - dt);

        if (!alive) {
            if (deathAnimProgress < 1.0) {
                deathAnimProgress = Math.min(1.0, deathAnimProgress + dt * 2.35);
            }
            if (respawnTimer > 0.0) {
                respawnTimer = Math.max(0.0, respawnTimer - dt);
            }
            applyVerticalPhysics(dt, map);
            return null;
        }

        if (blindTimer > 0.15) {
            c4PlantTimer = 0.0;
            yaw += Math.sin(blindTimer * 7.0) * dt * 2.4;
            double blindMoveX = Math.cos(yaw) * strafeDir * 1.9;
            double blindMoveZ = -Math.sin(yaw) * strafeDir * 1.9;
            tryMoveBotAxis(map, blindMoveX * dt, 0.0);
            tryMoveBotAxis(map, 0.0, blindMoveZ * dt);
            applyVerticalPhysics(dt, map);
            walkPhase += dt * 3.8;
            return null;
        }

        // Поиск ближайшего видимого противника (для T: игрок + союзники CT; для CT: враги T)
        boolean targetIsPlayer = false;
        Bot targetBot = null;
        Vec3 targetFeetPos = null;
        Vec3 targetAimPos = null;
        double bestTargetDist = Double.MAX_VALUE;

        if (team == Team.TERRORIST && player != null && canSeePlayer(map, player, smokes)) {
            double d = position.dist(player.getPosition());
            bestTargetDist = d;
            targetIsPlayer = true;
            targetFeetPos = player.getPosition();
            targetAimPos = player.getCenterTargetPosition();
        }

        for (int i = 0; i < allBots.size(); i++) {
            Bot other = allBots.get(i);
            if (other == this || !other.alive || other.team == this.team) continue;
            if (canSeeBot(map, other, smokes)) {
                double d = position.dist(other.position);
                if (d < bestTargetDist) {
                    bestTargetDist = d;
                    targetIsPlayer = false;
                    targetBot = other;
                    targetFeetPos = other.position;
                    targetAimPos = new Vec3(other.position.x, other.position.y + 1.24, other.position.z);
                }
            }
        }

        boolean seesEnemy = (targetFeetPos != null);
        if (seesEnemy) {
            lastSeenEnemyPos.set(targetFeetPos);
            if (state != State.ENGAGE) {
                state = State.ENGAGE;
                reactionTimer = difficulty.reactionDelay + rng.nextDouble() * 0.14;
            }
            searchTimer = 5.5;
        } else if (state == State.ENGAGE) {
            state = State.SEARCH;
        }

        ShotEvent shotEvent = null;
        double moveX = 0.0;
        double moveZ = 0.0;
        double combatSpeed = difficulty.combatSpeed;

        if (state == State.ENGAGE && seesEnemy) {
            double dx = targetFeetPos.x - position.x;
            double dz = targetFeetPos.z - position.z;
            double targetYaw = Math.atan2(dx, dz);
            yaw = turnSmoothly(yaw, targetYaw, dt * 7.8);

            double distXZ = Math.sqrt(dx * dx + dz * dz);
            double dirX = distXZ > 1e-4 ? dx / distXZ : 0.0;
            double dirZ = distXZ > 1e-4 ? dz / distXZ : 1.0;

            strafeSwitchTimer -= dt;
            if (strafeSwitchTimer <= 0.0) {
                strafeDir = -strafeDir;
                strafeSwitchTimer = 0.9 + rng.nextDouble() * 1.4;
            }

            double rightX = dirZ;
            double rightZ = -dirX;

            if (distXZ > 13.5 && radioOrder != RadioOrder.HOLD_POSITION) {
                moveX = (dirX * 0.88 + rightX * strafeDir * 0.34) * combatSpeed;
                moveZ = (dirZ * 0.88 + rightZ * strafeDir * 0.34) * combatSpeed;
            } else if (distXZ < 4.2) {
                moveX = (-dirX * 0.65 + rightX * strafeDir * 0.75) * combatSpeed;
                moveZ = (-dirZ * 0.65 + rightZ * strafeDir * 0.75) * combatSpeed;
            } else {
                moveX = rightX * strafeDir * (combatSpeed * 0.80);
                moveZ = rightZ * strafeDir * (combatSpeed * 0.80);
            }

            if (reactionTimer > 0.0) {
                reactionTimer -= dt;
            } else {
                fireCooldown -= dt;
                if (fireCooldown <= 0.0) {
                    shotEvent = fireAtTarget(map, player, targetIsPlayer, targetBot, targetAimPos, difficulty);
                    burstRemaining--;
                    if (burstRemaining <= 0) {
                        if (archetype == Archetype.JUGGERNAUT) {
                            burstRemaining = 6 + rng.nextInt(5);
                            fireCooldown = 0.42 + rng.nextDouble() * 0.25;
                        } else if (archetype == Archetype.SNIPER) {
                            burstRemaining = 1;
                            fireCooldown = 1.05 + rng.nextDouble() * 0.35;
                        } else {
                            burstRemaining = 2 + rng.nextInt(3);
                            fireCooldown = 0.60 + rng.nextDouble() * 0.34;
                        }
                    } else {
                        fireCooldown = (archetype == Archetype.JUGGERNAUT ? 0.11 : 0.16) + rng.nextDouble() * 0.04;
                    }
                }
            }
        } else if (team == Team.COUNTER_TERRORIST && plantedC4 != null) {
            // Союзник CT спешит к заложенной бомбе C4, чтобы помочь обезвредить её!
            double dx = plantedC4.pos.x - position.x;
            double dz = plantedC4.pos.z - position.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 1.85) {
                double targetYaw = Math.atan2(dx, dz);
                yaw = turnSmoothly(yaw, targetYaw, dt * 6.2);
                moveX = (dx / dist) * (PATROL_SPEED * 1.25);
                moveZ = (dz / dist) * (PATROL_SPEED * 1.25);
            } else {
                yaw = turnSmoothly(yaw, Math.atan2(dx, dz), dt * 5.0);
            }
        } else if (team == Team.TERRORIST && carryingC4 && c4TargetSpot != null) {
            double dx = c4TargetSpot.x - position.x;
            double dz = c4TargetSpot.z - position.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 1.75) {
                c4PlantTimer += dt;
                yaw += dt * 1.2;
                if (c4PlantTimer >= 2.6) {
                    c4PlantCompleted = true;
                }
            } else {
                c4PlantTimer = 0.0;
                double targetYaw = Math.atan2(dx, dz);
                yaw = turnSmoothly(yaw, targetYaw, dt * 5.6);
                moveX = (dx / dist) * (PATROL_SPEED * 1.18);
                moveZ = (dz / dist) * (PATROL_SPEED * 1.18);
            }
        } else if (team == Team.COUNTER_TERRORIST && radioOrder == RadioOrder.FOLLOW_PLAYER && player != null && player.isAlive()) {
            double dx = player.getPosition().x - position.x;
            double dz = player.getPosition().z - position.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 3.6) {
                double targetYaw = Math.atan2(dx, dz);
                yaw = turnSmoothly(yaw, targetYaw, dt * 6.0);
                moveX = (dx / dist) * (PATROL_SPEED * 1.32);
                moveZ = (dz / dist) * (PATROL_SPEED * 1.32);
            } else {
                yaw = turnSmoothly(yaw, player.getYaw(), dt * 3.5);
            }
        } else if (team == Team.COUNTER_TERRORIST && radioOrder == RadioOrder.HOLD_POSITION) {
            double dx = holdAnchorPos.x - position.x;
            double dz = holdAnchorPos.z - position.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 1.8) {
                double targetYaw = Math.atan2(dx, dz);
                yaw = turnSmoothly(yaw, targetYaw, dt * 5.0);
                moveX = (dx / dist) * PATROL_SPEED;
                moveZ = (dz / dist) * PATROL_SPEED;
            } else {
                yaw += Math.sin(walkPhase * 0.5) * dt * 0.6;
            }
        } else if (team == Team.COUNTER_TERRORIST && radioOrder == RadioOrder.RUSH_SITE) {
            Vec3 rushGoal = (currentWaypointIndex % 2 == 0) ? map.getBombsiteASpot() : map.getBombsiteBSpot();
            double dx = rushGoal.x - position.x;
            double dz = rushGoal.z - position.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 2.2) {
                currentWaypointIndex++;
            } else {
                double targetYaw = Math.atan2(dx, dz);
                yaw = turnSmoothly(yaw, targetYaw, dt * 5.8);
                moveX = (dx / dist) * (PATROL_SPEED * 1.28);
                moveZ = (dz / dist) * (PATROL_SPEED * 1.28);
            }
        } else if (state == State.SEARCH) {
            searchTimer -= dt;
            double dx = lastSeenEnemyPos.x - position.x;
            double dz = lastSeenEnemyPos.z - position.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (searchTimer <= 0.0 || dist < 1.35) {
                state = State.PATROL;
                pickNextWaypoint(map.getPatrolWaypoints());
            } else {
                double targetYaw = Math.atan2(dx, dz);
                yaw = turnSmoothly(yaw, targetYaw, dt * 5.2);
                moveX = (dx / dist) * (combatSpeed * 0.88);
                moveZ = (dz / dist) * (combatSpeed * 0.88);
            }
        } else {
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

        stuckTimer += dt;
        if (stuckTimer >= 1.1) {
            if (speedSq > 1.0 && position.distXZ(lastProgressPos) < 0.22) {
                strafeDir = -strafeDir;
                state = State.PATROL;
                pickNextWaypoint(map.getPatrolWaypoints());
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

    private boolean canSeePlayer(Map map, Player player, List<Vec3.SmokeCloud> smokes) {
        if (!player.isAlive()) return false;
        Vec3 eye = getEyePosition();
        Vec3 targetEye = player.getEyePosition();
        double dist = eye.dist(targetEye);
        if (dist > 42.0) return false;

        double closeHearDist = player.isSilentWalking() ? 3.2 : 7.5;
        if (dist > closeHearDist && state == State.PATROL) {
            Vec3 toPlayer = targetEye.sub(eye).normalize();
            Vec3 forward = new Vec3(Math.sin(yaw), 0.0, Math.cos(yaw));
            if (forward.dot(new Vec3(toPlayer.x, 0.0, toPlayer.z).normalize()) < -0.18) {
                return false;
            }
        }

        if (map.hasLineOfSight(eye, targetEye, smokes)) return true;
        return map.hasLineOfSight(eye, player.getCenterTargetPosition(), smokes);
    }

    private boolean canSeeBot(Map map, Bot other, List<Vec3.SmokeCloud> smokes) {
        if (!other.alive) return false;
        Vec3 eye = getEyePosition();
        Vec3 targetEye = other.getEyePosition();
        double dist = eye.dist(targetEye);
        if (dist > 42.0) return false;

        if (dist > 8.0 && state == State.PATROL) {
            Vec3 toOther = targetEye.sub(eye).normalize();
            Vec3 forward = new Vec3(Math.sin(yaw), 0.0, Math.cos(yaw));
            if (forward.dot(new Vec3(toOther.x, 0.0, toOther.z).normalize()) < -0.20) {
                return false;
            }
        }
        return map.hasLineOfSight(eye, targetEye, smokes);
    }

    private ShotEvent fireAtTarget(Map map, Player player, boolean targetIsPlayer, Bot targetBot,
                                   Vec3 aimTarget, GameSettings.BotDifficulty difficulty) {
        muzzleFlashTimer = 0.075;
        Vec3 muzzle = getMuzzleWorldPosition();
        Vec3 baseDir = aimTarget.sub(muzzle).normalize();

        double spread = difficulty.aimSpread;
        if (targetIsPlayer && player != null) {
            if (player.isCrouching()) spread += 0.016;
            if (player.getHorizontalSpeed() > 3.5) spread += 0.018;
        }

        Vec3 shotDir = new Vec3(
            baseDir.x + rng.nextGaussian() * spread,
            baseDir.y + rng.nextGaussian() * (spread * 0.78),
            baseDir.z + rng.nextGaussian() * spread
        ).normalize();

        Vec3.RayHit wallHit = map.raycast(muzzle, shotDir, 72.0);
        double maxDist = (wallHit != null) ? wallHit.distance : 72.0;
        Vec3 endPos = (wallHit != null) ? wallHit.point : muzzle.add(shotDir.scale(72.0));

        if (targetIsPlayer && player != null && player.isAlive()) {
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
                int dmg = difficulty.baseDamage + rng.nextInt(5);
                return new ShotEvent(muzzle, hitPt, true, null, dmg);
            }
        } else if (targetBot != null && targetBot.alive) {
            HitResult bHit = targetBot.intersectShotRay(muzzle, shotDir, maxDist);
            if (bHit != null) {
                int dmg = (bHit.headshot ? difficulty.baseDamage * 2 : difficulty.baseDamage) + rng.nextInt(7);
                return new ShotEvent(muzzle, bHit.point, false, targetBot, dmg);
            }
        }

        return new ShotEvent(muzzle, endPos, false, null, 0);
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
        for (double step = 0.12; step <= 0.34; step += 0.10) {
            if (!map.collides(nx, position.y + step, nz, RADIUS, HEIGHT)) {
                position.x = nx;
                position.z = nz;
                position.y += step;
                return true;
            }
        }
        if (state == State.ENGAGE) strafeDir = -strafeDir;
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

    public HitResult intersectShotRay(Vec3 origin, Vec3 dir, double maxDist) {
        if (!alive) return null;

        double s = getModelScale();
        Vec3.Box headBox = new Vec3.Box(
            position.x - 0.22 * s, position.y + 1.38 * s, position.z - 0.22 * s,
            position.x + 0.22 * s, position.y + 1.84 * s, position.z + 0.22 * s,
            0, 0, 0, true
        );
        Vec3.Box bodyBox = new Vec3.Box(
            position.x - 0.34 * s, position.y + 0.02, position.z - 0.34 * s,
            position.x + 0.34 * s, position.y + 1.40 * s, position.z + 0.34 * s,
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
            lastSeenEnemyPos.set(attackerPos);
            if (state == State.PATROL) {
                state = State.SEARCH;
                searchTimer = 6.0;
            }
        }

        if (health <= 0) {
            alive = false;
            deaths++;
            deathAnimProgress = 0.0;
            return true;
        }
        return false;
    }

    public void render(Renderer3D r) {
        double s = getModelScale();
        if (!r.isSphereVisible(position.x, position.y + 0.90 * s, position.z, 1.55 * s)) {
            return;
        }
        boolean flashing = hitFlashTimer > 0.0 && alive;
        boolean ct = (team == Team.COUNTER_TERRORIST);
        boolean jug = (archetype == Archetype.JUGGERNAUT);
        boolean sniper = (archetype == Archetype.SNIPER);

        int pantsColor = flashing ? 0xFF5555 : (jug ? 0x1F242C : (ct ? 0x223650 : 0x3D4652));
        int bootsColor = flashing ? 0xFF4444 : 0x181C22;
        int shirtColor = flashing ? 0xFF7070 : (jug ? 0x2D3540 : (ct ? 0x2C5E96 : (sniper ? 0x6E3834 : 0xBA8D5A)));
        int vestColor  = flashing ? 0xFF3B3B : (jug ? 0x161A20 : (ct ? 0x18283C : 0x2D3529));
        int packColor  = flashing ? 0xFF4A4A : (ct ? 0x20344E : 0x4C3D2D);
        int visorColor = flashing ? 0xFF9090 : (jug ? 0xFF3628 : (ct ? 0x5CE0FF : (sniper ? 0xFF543E : 0xD2A079)));
        int helmetCol  = flashing ? 0xFF4848 : (jug ? 0x222832 : (ct ? 0x21426B : (sniper ? 0xA82428 : 0x2C2624)));
        int gunColor   = 0x22262E;
        int gunAccent  = jug ? 0x44603C : (ct ? 0x365C82 : 0x8B4A22);

        if (!alive) {
            double fallAngle = deathAnimProgress * (Math.PI * 0.48);
            double sinF = Math.sin(fallAngle);
            double cosF = Math.cos(fallAngle);
            double baseY = position.y + 0.16 * sinF;

            drawPartFallen(r, 0.0, 0.42 * s, -0.14 * s, 0.18 * s, 0.72 * s, 0.19 * s, fallAngle, sinF, cosF, baseY, pantsColor);
            drawPartFallen(r, 0.0, 0.42 * s,  0.14 * s, 0.18 * s, 0.72 * s, 0.19 * s, fallAngle, sinF, cosF, baseY, pantsColor);
            drawPartFallen(r, 0.0, 1.04 * s,  0.00,     0.30 * s, 0.62 * s, 0.46 * s, fallAngle, sinF, cosF, baseY, vestColor);
            r.drawWorldEllipsoid(position.x - Math.sin(yaw) * 1.48 * s * sinF, baseY + Math.max(0.14, 1.54 * s * cosF),
                    position.z - Math.cos(yaw) * 1.48 * s * sinF, 0.19 * s, 0.19 * s, 0.19 * s, 10, 5, helmetCol);
            double gx = position.x + Math.cos(yaw) * 0.45 * s;
            double gz = position.z - Math.sin(yaw) * 0.45 * s;
            r.drawOrientedWorldCylinder(gx, position.y + 0.06, gz, 0.045, 0.68, yaw + 0.5, 0.0, 8, gunColor);
            return;
        }

        double legSwing = Math.sin(walkPhase) * 0.38;
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double rightX = cosY;
        double rightZ = -sinY;
        double bx = position.x, by = position.y, bz = position.z;

        // Гладкие цилиндрические ноги и закруглённые ботинки
        r.drawOrientedWorldCylinder(bx - rightX * 0.14 * s, by + 0.42 * s, bz - rightZ * 0.14 * s,
                0.095 * s, 0.72 * s, yaw, Math.PI * 0.5 + legSwing, 8, pantsColor);
        r.drawOrientedWorldCylinder(bx + rightX * 0.14 * s, by + 0.42 * s, bz + rightZ * 0.14 * s,
                0.095 * s, 0.72 * s, yaw, Math.PI * 0.5 - legSwing, 8, pantsColor);
        r.drawWorldEllipsoid(bx - rightX * 0.14 * s, by + 0.08 * s, bz - rightZ * 0.14 * s, 0.10 * s, 0.08 * s, 0.14 * s, 8, 4, bootsColor);
        r.drawWorldEllipsoid(bx + rightX * 0.14 * s, by + 0.08 * s, bz + rightZ * 0.14 * s, 0.10 * s, 0.08 * s, 0.14 * s, 8, 4, bootsColor);

        // Торс, бронежилет и круглые наплечники (у Джаггернаута — массивная титановая броня!)
        r.drawOrientedWorldBox(bx, by + 1.06 * s, bz, 0.44 * s, 0.62 * s, 0.27 * s, yaw, 0.0, Renderer3D.TEX_SOLID, shirtColor);
        r.drawOrientedWorldBox(bx, by + 1.08 * s, bz, 0.47 * s, 0.48 * s, 0.31 * s, yaw, 0.0, Renderer3D.TEX_SOLID, vestColor);
        r.drawWorldEllipsoid(bx - rightX * 0.28 * s, by + 1.30 * s, bz - rightZ * 0.28 * s,
                (jug ? 0.18 : 0.12) * s, (jug ? 0.16 : 0.11) * s, (jug ? 0.18 : 0.12) * s, 9, 5, jug ? vestColor : shirtColor);
        r.drawWorldEllipsoid(bx + rightX * 0.28 * s, by + 1.30 * s, bz + rightZ * 0.28 * s,
                (jug ? 0.18 : 0.12) * s, (jug ? 0.16 : 0.11) * s, (jug ? 0.18 : 0.12) * s, 9, 5, jug ? vestColor : shirtColor);

        r.drawOrientedWorldBox(bx - sinY * 0.20 * s, by + 1.10 * s, bz - cosY * 0.20 * s, 0.30 * s, 0.38 * s, 0.14 * s, yaw, 0.0, Renderer3D.TEX_SOLID, packColor);
        if (carryingC4) {
            r.drawOrientedWorldBox(bx - sinY * 0.28 * s, by + 1.12 * s, bz - cosY * 0.28 * s, 0.24, 0.18, 0.10, yaw, 0.0, Renderer3D.TEX_SOLID, 0xC2A35E);
            r.drawTranslucentSphereSprite(bx - sinY * 0.34 * s, by + 1.16 * s, bz - cosY * 0.34 * s, 0.06, 0xFF3322, 0.90);
        }

        // Гладкие цилиндрические руки
        if (blindTimer > 0.15) {
            r.drawOrientedWorldCylinder(bx - rightX * 0.18 * s + sinY * 0.22 * s, by + 1.54 * s, bz - rightZ * 0.18 * s + cosY * 0.22 * s,
                    0.075 * s, 0.36 * s, yaw + 1.2, 0.20, 8, shirtColor);
        } else {
            r.drawOrientedWorldCylinder(bx - rightX * 0.27 * s + sinY * 0.15 * s, by + 1.15 * s, bz - rightZ * 0.27 * s + cosY * 0.15 * s,
                    0.075 * s, 0.44 * s, yaw + 0.18, 0.10, 8, shirtColor);
        }
        r.drawOrientedWorldCylinder(bx + rightX * 0.26 * s + sinY * 0.16 * s, by + 1.14 * s, bz + rightZ * 0.26 * s + cosY * 0.16 * s,
                0.075 * s, 0.45 * s, yaw - 0.12, 0.10, 8, shirtColor);

        // ГЛАДКАЯ КРУГЛАЯ ГОЛОВА И ТАКТИЧЕСКИЙ ШЛЕМ С ГУРО-ЗАТЕНЕНИЕМ (НИКАКИХ КУБИЧЕСКИХ ГОЛОВ!)
        r.drawWorldEllipsoid(bx, by + 1.56 * s, bz, 0.175 * s, 0.195 * s, 0.175 * s, 12, 6, helmetCol);
        r.drawWorldEllipsoid(bx, by + 1.62 * s, bz, 0.195 * s, 0.145 * s, 0.195 * s, 12, 5, helmetCol);
        r.drawOrientedWorldBox(bx + sinY * 0.155 * s, by + 1.57 * s, bz + cosY * 0.155 * s,
                0.23 * s, 0.085 * s, 0.06 * s, yaw, 0.0, Renderer3D.TEX_SOLID, visorColor);

        // Оружие в руках с круглым стволом
        double gunCenterX = bx + rightX * 0.15 * s + sinY * 0.36 * s;
        double gunCenterY = by + 1.15 * s;
        double gunCenterZ = bz + rightZ * 0.15 * s + cosY * 0.36 * s;
        r.drawOrientedWorldBox(gunCenterX - sinY * 0.10 * s, gunCenterY, gunCenterZ - cosY * 0.10 * s,
                0.07 * s, 0.12 * s, 0.36 * s, yaw, 0.0, Renderer3D.TEX_SOLID, gunAccent);
        r.drawOrientedWorldCylinder(gunCenterX + sinY * 0.22 * s, gunCenterY + 0.02 * s, gunCenterZ + cosY * 0.22 * s,
                (jug ? 0.045 : 0.028) * s, (jug ? 0.62 : 0.48) * s, yaw, 0.0, 8, gunColor);

        // Лазерный целеуказатель элитного снайпера!
        if (sniper && state == State.ENGAGE) {
            Vec3 muz = getMuzzleWorldPosition();
            Vec3 laserEnd = muz.add(new Vec3(sinY * 8.0, -0.05, cosY * 8.0));
            r.draw3DLine(muz, laserEnd, 0xFF2E38, 1);
        }

        if (muzzleFlashTimer > 0.0) {
            Vec3 muz = getMuzzleWorldPosition();
            r.drawTranslucentSphereSprite(muz.x, muz.y, muz.z, 0.18 * s, ct ? 0x70E8FF : 0xFFCA3A, 0.92);
        }
    }

    private void drawPartFallen(Renderer3D r, double sideOffset, double heightOffset, double zOffset,
                                double sx, double sy, double sz,
                                double fallAngle, double sinF, double cosF, double baseY, int color) {
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double rightX = cosY, rightZ = -sinY;
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
        if (Math.abs(diff) <= maxDelta) return target;
        return current + Math.copySign(maxDelta, diff);
    }

    public Vec3 getPosition() { return position; }
    public Vec3 getEyePosition() { return new Vec3(position.x, position.y + EYE_HEIGHT, position.z); }
    public Vec3 getMuzzleWorldPosition() {
        double sinY = Math.sin(yaw), cosY = Math.cos(yaw);
        return new Vec3(position.x + cosY * 0.15 + sinY * 0.72, position.y + 1.16, position.z - sinY * 0.15 + cosY * 0.72);
    }

    public String getName() { return name; }
    public Team getTeam() { return team; }
    public boolean isAlly() { return team == Team.COUNTER_TERRORIST; }
    public int getHealth() { return health; }
    public boolean isAlive() { return alive; }
    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public State getState() { return state; }
    public boolean shouldShowHealthBar() { return alive && (archetype == Archetype.JUGGERNAUT || health < maxHealth || healthBarTimer > 0.0 || blindTimer > 0.1); }
    public boolean isFiringRecently() { return muzzleFlashTimer > 0.0; }
    public boolean isBlinded() { return alive && blindTimer > 0.15; }
    public boolean isCarryingC4() { return alive && carryingC4; }
    public String getC4SiteName() { return c4SiteName; }
    public Vec3 getC4TargetSpot() { return c4TargetSpot; }
    public void setRespawnTimer(double t) { this.respawnTimer = t; }
    public double getRespawnTimer() { return respawnTimer; }
}
