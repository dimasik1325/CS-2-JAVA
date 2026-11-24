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
        public final Weapon.HitGroup group;

        public HitResult(double distance, Vec3 point, boolean headshot) {
            this(distance, point, headshot ? Weapon.HitGroup.HEAD : Weapon.HitGroup.CHEST);
        }

        public HitResult(double distance, Vec3 point, Weapon.HitGroup group) {
            this.distance = distance;
            this.point = point;
            this.group = group;
            this.headshot = (group == Weapon.HitGroup.HEAD);
        }
    }

    public static class ShotEvent {
        public final Vec3 muzzlePos;
        public final Vec3 endPos;
        public final boolean hitPlayer;
        public final Bot hitBot;
        public final int damage;
        public Weapon.Type weapon = Weapon.Type.AK47;
        public Weapon.HitGroup hitGroup = Weapon.HitGroup.CHEST;
        public double armorPenetration = 0.775;
        public boolean silenced = false;
        public Vec3 extraTracerEnd = null;   // для дробовиков — разлёт второй дробины

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
    // РЕАЛИЗМ 9.0: личное оружие бота, кевлар+шлем, магазин и перезарядка, замедление от попаданий
    private Weapon.Type weaponType = Weapon.Type.AK47;
    private Weapon.Type forcedWeapon = null;
    private int armor = 100;
    private boolean helmet = true;
    private int botMag = 30;
    private double botReloadTimer = 0.0;
    private double taggedTimer = 0.0;
    private Weapon.HitGroup lastHitGroup = Weapon.HitGroup.CHEST;
    private int sprayShot = 0;

    private State state = State.PATROL;
    private RadioOrder radioOrder = RadioOrder.PATROL_FREE;
    private final Vec3 holdAnchorPos = new Vec3();
    private int currentWaypointIndex = 0;
    private final Vec3 lastSeenEnemyPos = new Vec3();
    private double searchTimer = 0.0;
    private double stuckTimer = 0.0;
    private final Vec3 lastProgressPos = new Vec3();
    // 11.0 SMART BOTS: обход стен («усики»-рейкасты + скольжение и следование вдоль препятствия)
    private final double[] steerOut = new double[2];
    private double avoidSideTimer = 0.0;
    private int avoidSide = 1;               // -1 влево, +1 вправо
    private final Vec3 navStep = new Vec3();
    private double navRepathTimer = 0.0;
    private double wallFollowTimer = 0.0;    // идём вдоль стены, не пересчитывая навигацию
    private final Vec3 wallFollowDir = new Vec3(0, 0, 1);  // текущее направление скольжения вдоль стены
    private double escapeTimer = 0.0;        // откат назад из «кармана»
    private final Vec3 lastMvDir = new Vec3(0, 0, 1);      // последнее направление движения

    private double reactionTimer = 0.35;
    private double fireCooldown = 0.45;
    private int burstRemaining = 3;
    private double strafeDir = 1.0;
    private double strafeSwitchTimer = 1.4;

    private double walkPhase = 0.0;
    // 11.0: GLB-модель бойца со скелетной анимацией
    public static volatile boolean useGlbModels = true;
    private CharacterRig rig;
    private CharacterRig.Variant rigVariant;
    private double animSpeed = 0.0;
    private double reloadDurationAnim = 2.5;
    private final CharacterRig.Params rigParams = new CharacterRig.Params();
    private double lastAnimTime = 0.0;
    private final Vec3 cachedMuzzle = new Vec3();
    private boolean cachedMuzzleValid = false;
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
        rollWeapon();
    }

    public Archetype getArchetype() { return archetype; }

    /** Реалистичная закупка бота по стороне конфликта и роли (как в эко-системе CS2). */
    private void rollWeapon() {
        if (forcedWeapon != null) {
            weaponType = forcedWeapon;
        } else if (archetype == Archetype.JUGGERNAUT) {
            weaponType = rng.nextBoolean() ? Weapon.Type.NEGEV : Weapon.Type.M249;
        } else if (archetype == Archetype.SNIPER) {
            weaponType = rng.nextDouble() < 0.62 ? Weapon.Type.AWP : Weapon.Type.SSG08;
        } else {
            double r = rng.nextDouble();
            if (team == Team.TERRORIST) {
                weaponType = r < 0.42 ? Weapon.Type.AK47 : r < 0.58 ? Weapon.Type.GALIL : r < 0.68 ? Weapon.Type.SG553
                        : r < 0.78 ? Weapon.Type.MAC10 : r < 0.84 ? Weapon.Type.UMP45 : r < 0.89 ? Weapon.Type.P90
                        : r < 0.93 ? Weapon.Type.NOVA : r < 0.97 ? Weapon.Type.TEC9 : Weapon.Type.DEAGLE;
            } else {
                weaponType = r < 0.30 ? Weapon.Type.M4A4 : r < 0.52 ? Weapon.Type.M4A1S : r < 0.64 ? Weapon.Type.FAMAS
                        : r < 0.72 ? Weapon.Type.AUG : r < 0.81 ? Weapon.Type.MP9 : r < 0.87 ? Weapon.Type.UMP45
                        : r < 0.92 ? Weapon.Type.P90 : r < 0.96 ? Weapon.Type.FIVESEVEN : Weapon.Type.USP_S;
            }
        }
        botMag = Weapon.prototype(weaponType).getMaxMagAmmo();
        botReloadTimer = 0.0;
        sprayShot = 0;
    }

    /** Принудительное оружие (Гонка вооружений / пистолетный раунд). null — обычная закупка. */
    public void setForcedWeapon(Weapon.Type t) {
        this.forcedWeapon = t;
        rollWeapon();
    }

    public Weapon.Type getWeaponType() { return weaponType; }
    public int getArmor() { return armor; }
    public boolean hasHelmet() { return helmet; }
    public Weapon.HitGroup getLastHitGroup() { return lastHitGroup; }
    public boolean isReloadingWeapon() { return botReloadTimer > 0.0; }
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
        this.armor = (archetype == Archetype.JUGGERNAUT) ? 200 : 100;
        this.helmet = true;
        this.taggedTimer = 0.0;
        rollWeapon();
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
        if (taggedTimer > 0.0) taggedTimer = Math.max(0.0, taggedTimer - dt);
        if (botReloadTimer > 0.0) {
            botReloadTimer -= dt;
            if (botReloadTimer <= 0.0) {
                botReloadTimer = 0.0;
                botMag = Weapon.prototype(weaponType).getMaxMagAmmo();
            }
        }

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
            // Боты целятся в торс; элита чуть выше — но не в шею (11.0 BALANCE)
            double aimFrac = 0.55 + 0.045 * difficulty.ordinal();
            Vec3 pp = player.getPosition();
            targetAimPos = new Vec3(pp.x, pp.y + player.getCurrentBodyHeight() * aimFrac, pp.z);
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
        double combatSpeed = difficulty.combatSpeed * Weapon.prototype(weaponType).getMoveSpeedMultiplier() / 0.86;
        if (taggedTimer > 0.0) combatSpeed *= 0.5; // tagging: попадание тормозит бота
        boolean engaged = (state == State.ENGAGE && seesEnemy);
        Vec3 navGoalForSteering = null;   // долгая цель для «следования вдоль стены»

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
                if (botReloadTimer > 0.0) {
                    // Бот перезаряжается — реальное окно для пика!
                } else if (fireCooldown <= 0.0) {
                    Weapon proto = Weapon.prototype(weaponType);
                    shotEvent = fireAtTarget(map, player, targetIsPlayer, targetBot, targetAimPos, difficulty);
                    burstRemaining--;
                    sprayShot++;
                    botMag--;
                    if (botMag <= 0) {
                        botReloadTimer = proto.getBaseReloadDuration() * (proto.isShellByShellReload() ? 4.0 : 1.0);
                        reloadDurationAnim = Math.max(0.3, botReloadTimer);
                        burstRemaining = 0;
                    }
                    double interval = proto.getFireInterval();
                    if (burstRemaining <= 0) {
                        sprayShot = 0;
                        switch (proto.getWeaponClass()) {
                            case MACHINE_GUN: burstRemaining = 6 + rng.nextInt(6); fireCooldown = 0.58 + rng.nextDouble() * 0.32; break;
                            case SNIPER:      burstRemaining = 1; fireCooldown = Math.max(interval, 1.30) + rng.nextDouble() * 0.45; break;
                            case SHOTGUN:     burstRemaining = 1; fireCooldown = interval + 0.30 + rng.nextDouble() * 0.30; break;
                            case PISTOL:      burstRemaining = 1 + rng.nextInt(2); fireCooldown = interval + 0.38 + rng.nextDouble() * 0.30; break;
                            case SMG:         burstRemaining = 3 + rng.nextInt(5); fireCooldown = 0.55 + rng.nextDouble() * 0.35; break;
                            default:          burstRemaining = 2 + rng.nextInt(3); fireCooldown = 0.72 + rng.nextDouble() * 0.42; break;
                        }
                    } else {
                        fireCooldown = interval * (1.05 + rng.nextDouble() * 0.25);
                    }
                }
            }
        } else if (team == Team.COUNTER_TERRORIST && plantedC4 != null) {
            // Союзник CT спешит к заложенной бомбе C4, чтобы помочь обезвредить её!
            navGoalForSteering = plantedC4.pos;
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
            navGoalForSteering = c4TargetSpot;
            double dx = c4TargetSpot.x - position.x;
            double dz = c4TargetSpot.z - position.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 1.85) {
                c4PlantTimer += dt;
                yaw += dt * 1.2;
                if (c4PlantTimer >= 2.6) {
                    c4PlantCompleted = true;
                }
            } else {
                c4PlantTimer = 0.0;
                Vec3 navGoal = resolveNavStepTowards(map, c4TargetSpot);
                double ndx = navGoal.x - position.x;
                double ndz = navGoal.z - position.z;
                double ndist = Math.max(0.01, Math.hypot(ndx, ndz));
                double targetYaw = Math.atan2(ndx, ndz);
                yaw = turnSmoothly(yaw, targetYaw, dt * 5.6);
                moveX = (ndx / ndist) * (PATROL_SPEED * 1.18);
                moveZ = (ndz / ndist) * (PATROL_SPEED * 1.18);
            }
        } else if (team == Team.COUNTER_TERRORIST && radioOrder == RadioOrder.FOLLOW_PLAYER && player != null && player.isAlive()) {
            navGoalForSteering = player.getPosition();
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
            navGoalForSteering = holdAnchorPos;
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
            navGoalForSteering = rushGoal;
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
                Vec3 nav = cachedNavStep(map, lastSeenEnemyPos, dt);
                navGoalForSteering = nav;
                double ndx = nav.x - position.x;
                double ndz = nav.z - position.z;
                double ndist = Math.max(0.01, Math.hypot(ndx, ndz));
                moveX = (ndx / ndist) * (combatSpeed * 0.88);
                moveZ = (ndz / ndist) * (combatSpeed * 0.88);
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
                    Vec3 nav = cachedNavStep(map, wp, dt);
                    navGoalForSteering = nav;
                    double ndx = nav.x - position.x;
                    double ndz = nav.z - position.z;
                    double ndist = Math.max(0.01, Math.hypot(ndx, ndz));
                    moveX = (ndx / ndist) * PATROL_SPEED;
                    moveZ = (ndz / ndist) * PATROL_SPEED;
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
        // 11.2 FIX: союзники не лезут в камеру — персональное пространство вокруг игрока
        if (player != null && player.isAlive()) {
            double ox = position.x - player.getPosition().x;
            double oz = position.z - player.getPosition().z;
            double d2 = ox * ox + oz * oz;
            double minSep = 1.35;
            if (d2 > 1e-6 && d2 < minSep * minSep) {
                double d = Math.sqrt(d2);
                double push = (minSep - d) * 5.0;
                moveX += (ox / d) * push;
                moveZ += (oz / d) * push;
            }
        }

        // 11.0 SMART BOTS: обход стен — «усики»-рейкасты поворачивают движение вдоль препятствия
        double esp = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (escapeTimer > 0.0) {
            // откат назад из «кармана»: идём в противоположную сторону от последнего направления
            escapeTimer -= dt;
            if (esp > 0.4) {
                moveX = -lastMvDir.x * esp;
                moveZ = -lastMvDir.z * esp;
            }
        } else {
            steerOut[0] = moveX; steerOut[1] = moveZ;
            steerAroundWalls(map, steerOut, dt, engaged ? null : navGoalForSteering);
            moveX = steerOut[0];
            moveZ = steerOut[1];
            esp = Math.sqrt(moveX * moveX + moveZ * moveZ);
            if (esp > 0.4) lastMvDir.set(moveX / esp, 0.0, moveZ / esp);
        }

        boolean movedX = tryMoveBotAxis(map, moveX * dt, 0.0);
        boolean movedZ = tryMoveBotAxis(map, 0.0, moveZ * dt);
        applyVerticalPhysics(dt, map);

        double speedSq = moveX * moveX + moveZ * moveZ;
        if (speedSq > 0.25 && (movedX || movedZ)) {
            walkPhase += dt * Math.sqrt(speedSq) * 2.1;
        }
        double targetAnim = (speedSq > 0.25 && (movedX || movedZ)) ? Math.min(1.0, Math.sqrt(speedSq) / 4.6) : 0.0;
        animSpeed += (targetAnim - animSpeed) * Math.min(1.0, dt * 8.0);

        stuckTimer += dt;
        if (stuckTimer >= 1.1) {
            if (speedSq > 1.0 && position.distXZ(lastProgressPos) < 0.22) {
                strafeDir = -strafeDir;
                escapeTimer = 0.9;   // 11.0 SMART BOTS: сначала откат из кармана, потом новая цель
                if (state == State.ENGAGE) {
                    // не выходим из боя: меняем сторону маневра вдоль укрытия
                    avoidSide = -avoidSide;
                    avoidSideTimer = 0.8;
                } else {
                    state = State.PATROL;
                    pickNextWaypoint(map.getPatrolWaypoints());
                }
            }
            lastProgressPos.set(position);
            stuckTimer = 0.0;
        }

        return shotEvent;
    }

    /** Кэшированный шаг навигации: промежуточная точка с прямой видимостью (пересчёт 4 раза в секунду). */
    private Vec3 cachedNavStep(Map map, Vec3 goal, double dt) {
        navRepathTimer -= dt;
        if (navRepathTimer <= 0.0) {
            navRepathTimer = 0.25;
            navStep.set(resolveNavStepTowards(map, goal));
        }
        return navStep;
    }

    /**
     * 11.0 SMART BOTS: обход стен. «Усики»-рейкасты по ширине корпуса (3 параллельных луча вперёд,
     * 2 диагональных ±40°) на высоте 0.36 м — видят и низкие ящики, и углы зданий, которые узкий
     * луч «прошивает» мимо. При контакте — поворот в свободную сторону, скольжение вдоль стены
     * и «следование вдоль стены» с commitment'ом, пока к цели не откроется прямая видимость.
     */
    private void steerAroundWalls(Map map, double[] mv, double dt, Vec3 navGoal) {
        if (avoidSideTimer > 0.0) avoidSideTimer -= dt;
        double mx = mv[0], mz = mv[1];
        double sp = Math.sqrt(mx * mx + mz * mz);
        if (sp < 0.4) return;
        double ux = mx / sp, uz = mz / sp;
        double wY = position.y + 0.36;   // выше макс. шага (0.34), ниже пояса — видит ящики
        double ox = uz * 0.28, oz = -ux * 0.28;   // поперечник корпуса (право)

        // ---- РЕЖИМ СЛЕДОВАНИЯ ВДОЛЬ СТЕНЫ (с commitment'ом, чтобы не «хлопать» у угла) ----
        while (wallFollowTimer > 0.0) {
            wallFollowTimer -= dt;
            Vec3.RayHit fHit = raycastWide(map, wY, ox, oz, ux, uz, 1.7);
            if (fHit == null) {
                // стена кончилась: выходим из follow — дальше идём по цели,
                // у следующей стены follow перезапустится (тангенс теперь по свободе хода)
                break;
            }
            Vec3 n = fHit.normal;
            double nl = Math.sqrt(n.x * n.x + n.z * n.z);
            if (nl < 1e-4) break;
            double tx = -n.z / nl, tz = n.x / nl;   // касательная к стене
            // свобода хода вдоль каждой из двух касательных — выбираем более свободную
            Vec3.RayHit hA = raycastWide(map, wY, ox, oz, tx, tz, 1.8);
            Vec3.RayHit hB = raycastWide(map, wY, ox, oz, -tx, -tz, 1.8);
            double cA = hA != null ? hA.distance : 99.0;
            double cB = hB != null ? hB.distance : 99.0;
            if (cB > cA + 0.30) { tx = -tx; tz = -tz; }
            else if (cA > cB + 0.30) {
                // оставляем как есть
            } else if (navGoal != null) {
                double gx = navGoal.x - position.x, gz = navGoal.z - position.z;
                double gl = Math.sqrt(gx * gx + gz * gz);
                if (gl > 1e-4) {
                    double dot = tx * (gx / gl) + tz * (gz / gl);
                    if (dot < -0.05) { tx = -tx; tz = -tz; }
                }
            } else if (tx * ux + tz * uz < 0.0) { tx = -tx; tz = -tz; }
            // скользим вдоль стены с лёгким сносом от неё
            double fx = tx + (n.x / nl) * 0.30, fz = tz + (n.z / nl) * 0.30;
            double fl = Math.sqrt(fx * fx + fz * fz);
            if (fl < 1e-4) break;
            wallFollowDir.set(fx / fl, 0.0, fz / fl);
            mv[0] = wallFollowDir.x * sp;
            mv[1] = wallFollowDir.z * sp;
            return;
        }
        wallFollowTimer = 0.0;

        Vec3.RayHit fHit = raycastWide(map, wY, ox, oz, ux, uz, 2.4);
        if (fHit == null && avoidSideTimer <= 0.0) return;   // путь чист — не рулим
        double fD = fHit != null ? fHit.distance : 99.0;
        double ca40 = 0.766, sa40 = 0.643;
        double lx = ux * ca40 + uz * sa40, lz = -ux * sa40 + uz * ca40;   // влево 40°
        double rx = ux * ca40 - uz * sa40, rz = ux * sa40 + uz * ca40;    // вправо 40°
        // диагональные лучи смещаем к соответствующему краю корпуса
        Vec3.RayHit lHit = map.raycast(new Vec3(position.x - ox, wY, position.z - oz), new Vec3(lx, 0.0, lz), 2.9);
        Vec3.RayHit rHit = map.raycast(new Vec3(position.x + ox, wY, position.z + oz), new Vec3(rx, 0.0, rz), 2.9);
        double lD = lHit != null ? lHit.distance : 99.0;
        double rD = rHit != null ? rHit.distance : 99.0;
        if (fD > 2.1 && lD > 2.4 && rD > 2.4) return;

        // выбор стороны: по запасу просвета, с гистерезисом чтобы не дрожать у угла
        int side = (avoidSideTimer > 0.0) ? avoidSide : (lD > rD ? -1 : 1);
        avoidSide = side;
        avoidSideTimer = 0.55;

        double turn = (fD < 1.15 || (lD < 0.9 && rD < 0.9)) ? 0.95 : 0.48;  // 55° или 28°
        double ca = Math.cos(turn), sa = Math.sin(turn);
        double nx, nz;
        if (side < 0) { nx = ux * ca - uz * sa; nz = ux * sa + uz * ca; }
        else          { nx = ux * ca + uz * sa; nz = -ux * sa + uz * ca; }

        // скольжение вдоль стены: убираем компоненту в нормаль
        if (fHit != null && fHit.normal != null && fD < 1.4) {
            Vec3 n = fHit.normal;
            double dn = nx * n.x + nz * n.z;
            if (dn < 0.0) { nx -= n.x * dn; nz -= n.z * dn; }
        }
        double nl2 = Math.sqrt(nx * nx + nz * nz);
        if (nl2 > 1e-6) {
            mv[0] = (nx / nl2) * sp;
            mv[1] = (nz / nl2) * sp;
        }
        // стена вплотную и цель не видна — включаем следование вдоль стены
        if (navGoal != null && fD < 1.5
                && !map.hasLineOfSight(new Vec3(position.x, wY, position.z),
                                       new Vec3(navGoal.x, wY, navGoal.z))) {
            wallFollowTimer = 1.6;
            if (fHit != null && fHit.normal != null) {
                double nl3 = Math.sqrt(fHit.normal.x * fHit.normal.x + fHit.normal.z * fHit.normal.z);
                if (nl3 > 1e-4) {
                    double tx = -fHit.normal.z / nl3, tz = fHit.normal.x / nl3;
                    if (tx * nx + tz * nz < 0.0) { tx = -tx; tz = -tz; }
                    wallFollowDir.set(tx, 0.0, tz);
                }
            } else {
                wallFollowDir.set(nx / nl2, 0.0, nz / nl2);
            }
        }
    }

    /** Три параллельных луча по ширине корпуса (центр ± 0.28 м) — ближайшее препятствие. */
    private Vec3.RayHit raycastWide(Map map, double wY, double ox, double oz, double ux, double uz, double dist) {
        Vec3.RayHit c = map.raycast(new Vec3(position.x, wY, position.z), new Vec3(ux, 0.0, uz), dist);
        Vec3.RayHit l = map.raycast(new Vec3(position.x - ox, wY, position.z - oz), new Vec3(ux, 0.0, uz), dist);
        Vec3.RayHit r = map.raycast(new Vec3(position.x + ox, wY, position.z + oz), new Vec3(ux, 0.0, uz), dist);
        Vec3.RayHit best = c;
        if (l != null && (best == null || l.distance < best.distance)) best = l;
        if (r != null && (best == null || r.distance < best.distance)) best = r;
        return best;
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

    private Vec3 resolveNavStepTowards(Map map, Vec3 goal) {
        List<Vec3> wps = map.getPatrolWaypoints();
        double goalDistNow = position.distXZ(goal);
        // 11.0 SMART BOTS: навигация по ПРОХОДИМОСТИ, а не по видимости — линия до кандидата
        // проверяется «капсулой» корпуса, чтобы боты не шли в карманы между стенами
        if (walkableRun(map, goal) >= position.distXZ(goal)) {
            return goal;
        }
        Vec3 bestFull = null;
        double bestFullScore = Double.MAX_VALUE;
        Vec3 bestPartial = null;
        double bestRun = -1.0, bestPartialGoal = Double.MAX_VALUE;
        for (int i = 0, n = wps.size(); i < n; i++) {
            Vec3 wp = wps.get(i);
            double dToMe = position.distXZ(wp);
            if (dToMe < 1.5) continue;
            double wpToGoal = wp.distXZ(goal);
            // точка должна реально приближать к цели (строгий прогресс)
            if (wpToGoal >= goalDistNow - 1.2) continue;
            double run = walkableRun(map, wp);
            if (run >= dToMe) {
                double score = wpToGoal + dToMe * 0.45;
                if (score < bestFullScore) { bestFullScore = score; bestFull = wp; }
            } else if (run > bestRun + 0.01
                    || (run > bestRun - 0.01 && wpToGoal < bestPartialGoal)) {
                bestRun = run;
                bestPartialGoal = wpToGoal;
                bestPartial = wp;
            }
        }
        if (bestFull != null) return bestFull;
        if (bestPartial != null) return bestPartial;
        return goal;
    }

    /** Сколько метров по прямой до цели можно пройти корпусом до первого столкновения. */
    private double walkableRun(Map map, Vec3 to) {
        double dx = to.x - position.x, dz = to.z - position.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return 1e9;
        double stepLen = 0.30;
        int steps = (int) Math.ceil(len / stepLen);
        for (int i = 1; i <= steps; i++) {
            double t = Math.min(1.0, i * stepLen / len);
            double px = position.x + dx * t;
            double pz = position.z + dz * t;
            if (map.collides(px, position.y, pz, RADIUS * 0.95, HEIGHT)) return i * stepLen;
        }
        return 1e9;
    }

    private ShotEvent fireAtTarget(Map map, Player player, boolean targetIsPlayer, Bot targetBot,
                                   Vec3 aimTarget, GameSettings.BotDifficulty difficulty) {
        Weapon proto = Weapon.prototype(weaponType);
        Weapon.WeaponClass wc = proto.getWeaponClass();
        muzzleFlashTimer = proto.isSilenced() ? 0.03 : 0.075;
        Vec3 muzzle = getMuzzleWorldPosition();
        Vec3 baseDir = aimTarget.sub(muzzle).normalize();

        double spread = difficulty.aimSpread;
        switch (wc) {
            case SNIPER:      spread *= 0.55; break;
            case MACHINE_GUN: spread *= 1.30; break;
            case SMG:         spread *= 1.15; break;
            case PISTOL:      spread *= 1.08; break;
            case SHOTGUN:     spread = spread * 0.6 + proto.computeCurrentSpread(0.0, false, false); break;
            default: break;
        }
        // Боты тоже подвержены отдаче: чем длиннее очередь, тем выше и шире уходят пули
        double[] spray = proto.getSprayOffset(Math.min(sprayShot, 12));
        if (targetIsPlayer && player != null) {
            if (player.isCrouching()) spread += 0.012;
            if (player.getHorizontalSpeed() > 3.5) spread += 0.016;
        }
        if (taggedTimer > 0.0) spread += 0.020; // попадание по боту сбивает его прицел

        double distToTarget = aimTarget.sub(muzzle).length();
        double dmgScale = difficulty.baseDamage / 22.0;
        int pellets = proto.getPelletsPerShot();
        int totalDamage = 0;
        Vec3 firstEnd = null, secondEnd = null;
        boolean hitP = false;
        Bot hitB = null;
        Weapon.HitGroup bestGroup = Weapon.HitGroup.CHEST;

        for (int pi = 0; pi < pellets; pi++) {
            double ps = (pellets > 1) ? spread * 1.0 : spread;
            Vec3 shotDir = new Vec3(
                baseDir.x + rng.nextGaussian() * ps + Math.cos(yaw) * spray[0] * 0.7,
                baseDir.y + rng.nextGaussian() * (ps * 0.78) + spray[1] * 0.6,
                baseDir.z + rng.nextGaussian() * ps - Math.sin(yaw) * spray[0] * 0.7
            ).normalize();

            double range = proto.getMaxRange();
            Vec3.RayHit wallHit = map.raycast(muzzle, shotDir, range);
            double maxDist = (wallHit != null) ? wallHit.distance : range;
            Vec3 endPos = (wallHit != null) ? wallHit.point : muzzle.add(shotDir.scale(range));
            Weapon.HitGroup group = null;
            double hitDist = 0.0;

            if (targetIsPlayer && player != null && player.isAlive()) {
                Vec3 pPos = player.getPosition();
                double pH = player.getCurrentBodyHeight();
                Vec3.Box playerBox = new Vec3.Box(
                    pPos.x - Player.RADIUS * 0.8, pPos.y, pPos.z - Player.RADIUS * 0.8,
                    pPos.x + Player.RADIUS * 0.8, pPos.y + pH, pPos.z + Player.RADIUS * 0.8,
                    0, 0, 0, true
                );
                double tPlayer = playerBox.intersectRay(muzzle, shotDir, maxDist, null);
                if (tPlayer >= 0.0 && tPlayer < maxDist) {
                    endPos = muzzle.add(shotDir.scale(tPlayer));
                    double rel = (endPos.y - pPos.y) / pH;
                    group = rel > 0.86 ? Weapon.HitGroup.HEAD : rel > 0.60 ? Weapon.HitGroup.CHEST
                            : rel > 0.45 ? Weapon.HitGroup.STOMACH : Weapon.HitGroup.LEGS;
                    hitP = true;
                    hitDist = tPlayer;
                }
            } else if (targetBot != null && targetBot.alive) {
                HitResult bHit = targetBot.intersectShotRay(muzzle, shotDir, maxDist);
                if (bHit != null) {
                    endPos = bHit.point;
                    group = bHit.group;
                    hitB = targetBot;
                    hitDist = bHit.distance;
                }
            }
            if (group != null) {
                double raw = proto.computeRawHitDamage(group, hitDist) * dmgScale;
                totalDamage += (int) Math.round(raw * (0.92 + rng.nextDouble() * 0.16));
                if (group.multiplier > bestGroup.multiplier || totalDamage == 0) bestGroup = group;
            }
            if (firstEnd == null) firstEnd = endPos; else if (secondEnd == null) secondEnd = endPos;
        }
        if (distToTarget > proto.getMaxRange()) totalDamage = 0;

        ShotEvent ev;
        if (hitP && totalDamage > 0) ev = new ShotEvent(muzzle, firstEnd, true, null, totalDamage);
        else if (hitB != null && totalDamage > 0) ev = new ShotEvent(muzzle, firstEnd, false, hitB, totalDamage);
        else ev = new ShotEvent(muzzle, firstEnd, false, null, 0);
        ev.weapon = weaponType;
        ev.hitGroup = bestGroup;
        ev.armorPenetration = proto.getArmorPenetration();
        ev.silenced = proto.isSilenced();
        ev.extraTracerEnd = secondEnd;
        return ev;
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

        // Анатомические хитбоксы CS2: голова (×4), грудь+руки (×1), живот (×1.25), ноги (×0.75)
        double s = getModelScale();
        double px = position.x, py = position.y, pz = position.z;
        Vec3.Box headBox = new Vec3.Box(px - 0.17 * s, py + 1.41 * s, pz - 0.17 * s, px + 0.17 * s, py + 1.79 * s, pz + 0.17 * s, 0, 0, 0, true);
        Vec3.Box chestBox = new Vec3.Box(px - 0.31 * s, py + 1.05 * s, pz - 0.31 * s, px + 0.31 * s, py + 1.41 * s, pz + 0.31 * s, 0, 0, 0, true);
        Vec3.Box stomachBox = new Vec3.Box(px - 0.27 * s, py + 0.82 * s, pz - 0.27 * s, px + 0.27 * s, py + 1.05 * s, pz + 0.27 * s, 0, 0, 0, true);
        Vec3.Box legsBox = new Vec3.Box(px - 0.24 * s, py + 0.02, pz - 0.24 * s, px + 0.24 * s, py + 0.82 * s, pz + 0.24 * s, 0, 0, 0, true);

        double best = Double.MAX_VALUE;
        Weapon.HitGroup bestG = null;
        double t;
        if ((t = headBox.intersectRay(origin, dir, maxDist, null)) >= 0.0 && t < best - 0.04) { best = t; bestG = Weapon.HitGroup.HEAD; }
        if ((t = chestBox.intersectRay(origin, dir, maxDist, null)) >= 0.0 && t < best - 0.04) { best = t; bestG = Weapon.HitGroup.CHEST; }
        if ((t = stomachBox.intersectRay(origin, dir, maxDist, null)) >= 0.0 && t < best) { best = t; bestG = Weapon.HitGroup.STOMACH; }
        if ((t = legsBox.intersectRay(origin, dir, maxDist, null)) >= 0.0 && t < best) { best = t; bestG = Weapon.HitGroup.LEGS; }
        if (bestG == null) return null;
        return new HitResult(best, origin.add(dir.scale(best)), bestG);
    }

    /** Реальная броня CS2 у ботов: урон × бронепробитие, броня теряет половину поглощённого; ноги не защищены. */
    public boolean takeDamage(int rawDamage, Vec3 attackerPos, double armorPen, Weapon.HitGroup hg) {
        if (!alive || rawDamage <= 0) return false;
        int dmg = rawDamage;
        lastHitGroup = hg;
        boolean armored = armor > 0 && hg != Weapon.HitGroup.LEGS && (hg != Weapon.HitGroup.HEAD || helmet);
        if (armored && armorPen < 1.0) {
            double hp = rawDamage * armorPen;
            double ap = (rawDamage - hp) * 0.5;
            if (ap > armor) { hp = rawDamage - armor * 2.0; ap = armor; }
            dmg = Math.max(1, (int) Math.round(hp));
            armor = Math.max(0, armor - (int) Math.round(ap));
        }
        taggedTimer = 0.40;
        return takeDamage(dmg, attackerPos);
    }

    public int previewDamage(int rawDamage, double armorPen, Weapon.HitGroup hg) {
        boolean armored = armor > 0 && hg != Weapon.HitGroup.LEGS && (hg != Weapon.HitGroup.HEAD || helmet);
        if (!armored || armorPen >= 1.0) return rawDamage;
        double hp = rawDamage * armorPen, ap = (rawDamage - hp) * 0.5;
        if (ap > armor) hp = rawDamage - armor * 2.0;
        return Math.max(1, (int) Math.round(hp));
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

    private CharacterRig.Variant pickVariant() {
        if (archetype == Archetype.JUGGERNAUT) return CharacterRig.Variant.JUGGERNAUT;
        int h = Math.abs(name.hashCode());
        if (team == Team.COUNTER_TERRORIST) return (h % 3 == 1) ? CharacterRig.Variant.CT_SOLDIER : CharacterRig.Variant.CT_SWAT;
        return (h % 4 == 2) ? CharacterRig.Variant.T_PUNK : CharacterRig.Variant.T_SWAT;
    }

    /** 11.0: отрисовка настоящей GLB-модели с процедурной скелетной анимацией. */
    private boolean renderGlb(Renderer3D r) {
        cachedMuzzleValid = false;
        CharacterRig.Variant v = pickVariant();
        if (rig == null || rigVariant != v) {
            rig = CharacterRig.create(v);
            rigVariant = v;
            if (rig == null) return false;
        }
        CharacterRig.Params p = rigParams;
        p.time = (System.nanoTime() % 1_000_000_000_000L) / 1e9 + Math.abs(name.hashCode() % 100);
        p.walkPhase = walkPhase * 1.6;
        p.moveSpeed = alive ? (blindTimer > 0.15 ? 0.45 : animSpeed) : 0;
        p.aimBlend = (state == State.ENGAGE || muzzleFlashTimer > 0.0) ? 1.0 : (state == State.SEARCH ? 0.7 : 0.25);
        p.aimPitch = 0.0;
        p.recoil = muzzleFlashTimer > 0.0 ? Math.min(1.0, muzzleFlashTimer / 0.075) : 0.0;
        p.flinch = hitFlashTimer > 0.0 ? Math.sin(Math.min(1.0, hitFlashTimer / 0.16) * Math.PI) : 0.0;
        p.blind = blindTimer > 0.15 ? 1.0 : 0.0;
        p.reload = botReloadTimer > 0.0 ? 1.0 - botReloadTimer / reloadDurationAnim : -1.0;
        p.death = alive ? -1.0 : deathAnimProgress;
        p.deathStyle = Math.abs(name.hashCode() / 7) % 3;
        p.flash = hitFlashTimer > 0.0 && alive;
        GunModels.Gun gun = GunModels.get(weaponType);
        if (gun == null && alive) return false; // нож/РПГ/Zeus — старая модель
        double s = getModelScale();
        boolean visible = rig.render(r, position.x, position.y, position.z, yaw, s, p, gun,
                GameSettings.WeaponSkin.STANDARD, r.botLodBias());
        if (!visible) return true;
        if (rig.muzzleValid) {
            cachedMuzzle.set(rig.muzzleWorld[0], rig.muzzleWorld[1], rig.muzzleWorld[2]);
            cachedMuzzleValid = true;
        }
        if (!alive) return true;
        boolean ct = (team == Team.COUNTER_TERRORIST);
        boolean suppressed = (weaponType == Weapon.Type.M4A1S || weaponType == Weapon.Type.USP_S);
        if (carryingC4) {
            double sinY = Math.sin(yaw), cosY = Math.cos(yaw);
            r.drawOrientedWorldBox(position.x - sinY * 0.22 * s, position.y + 1.13 * s, position.z - cosY * 0.22 * s, 0.22, 0.16, 0.09, yaw, 0.0, Renderer3D.TEX_SOLID, 0xC2A35E);
            r.drawTranslucentSphereSprite(position.x - sinY * 0.28 * s, position.y + 1.17 * s, position.z - cosY * 0.28 * s, 0.055, 0xFF3322, 0.90);
        }
        if (Weapon.classOf(weaponType) == Weapon.WeaponClass.SNIPER && state == State.ENGAGE) {
            Vec3 muz = getMuzzleWorldPosition();
            double sinY = Math.sin(yaw), cosY = Math.cos(yaw);
            r.draw3DLine(muz, muz.add(new Vec3(sinY * 9.5, -0.05, cosY * 9.5)), 0xFF2E38, 1);
        }
        if (muzzleFlashTimer > 0.0) {
            Vec3 muz = getMuzzleWorldPosition();
            r.drawTranslucentSphereSprite(muz.x, muz.y, muz.z, (suppressed ? 0.07 : 0.19) * s, ct ? 0x88ECFF : 0xFFCA3A, 0.92);
        }
        return true;
    }

    public void render(Renderer3D r) {
        // 11.2 FIX: бот вплотную к камере (союзник «влез в лицо») не рисуется —
        // его плечо/руки занимали пол-экрана и убивали FPS огромным overdraw
        if (r.distanceToCamera(position.x, position.y + 1.0, position.z) < 1.7) return;
        if (useGlbModels && renderGlb(r)) return;
        double s = getModelScale();
        if (!r.isSphereVisible(position.x, position.y + 0.92 * s, position.z, 1.62 * s)) {
            return;
        }
        boolean flashing = hitFlashTimer > 0.0 && alive;
        boolean ct = (team == Team.COUNTER_TERRORIST);
        boolean jug = (archetype == Archetype.JUGGERNAUT);
        boolean sniper = (archetype == Archetype.SNIPER);

        // Реалистичные армейские палитры спецназа SAS/GIGN (CT) и оперативников Phoenix/Elite Crew (T)
        int pantsColor = flashing ? 0xFF5555 : (jug ? 0x1D222A : (ct ? 0x253548 : (sniper ? 0x463E36 : 0x3A433C)));
        int bootsColor = flashing ? 0xFF4444 : 0x16191E;
        int shirtColor = flashing ? 0xFF7070 : (jug ? 0x2A323D : (ct ? 0x2D4866 : (sniper ? 0x6A3A32 : 0x8E7454)));
        int vestColor  = flashing ? 0xFF3B3B : (jug ? 0x181D24 : (ct ? 0x444E42 : 0x2E342A));
        int pouchColor = flashing ? 0xFF4A4A : (ct ? 0x2A312A : 0x4A3A28);
        int skinTone   = flashing ? 0xFFAAAA : 0xC89676;
        int helmetCol  = flashing ? 0xFF4848 : (jug ? 0x242C34 : (ct ? 0x465246 : (sniper ? 0x9C2C2C : 0x242222)));
        int goggleCol  = flashing ? 0xFF9090 : (jug ? 0xFF3B28 : (ct ? 0x1E3246 : 0x181C22));
        int gunMetal   = 0x20262E;
        int gunBody    = jug ? 0x364434 : (ct ? 0x2B3440 : (sniper ? 0x364C34 : 0x8C4C26));

        if (!alive) {
            double fallAngle = deathAnimProgress * (Math.PI * 0.48);
            double sinF = Math.sin(fallAngle);
            double cosF = Math.cos(fallAngle);
            double baseY = position.y + 0.14 * sinF;

            drawPartFallen(r, 0.0, 0.42 * s, -0.15 * s, 0.17 * s, 0.74 * s, 0.18 * s, fallAngle, sinF, cosF, baseY, pantsColor);
            drawPartFallen(r, 0.0, 0.42 * s,  0.15 * s, 0.17 * s, 0.74 * s, 0.18 * s, fallAngle, sinF, cosF, baseY, pantsColor);
            drawPartFallen(r, 0.0, 1.05 * s,  0.00,     0.28 * s, 0.60 * s, 0.44 * s, fallAngle, sinF, cosF, baseY, vestColor);
            r.drawWorldEllipsoid(position.x - Math.sin(yaw) * 1.48 * s * sinF, baseY + Math.max(0.14, 1.55 * s * cosF),
                    position.z - Math.cos(yaw) * 1.48 * s * sinF, 0.15 * s, 0.16 * s, 0.15 * s, 10, 6, helmetCol);
            double gx = position.x + Math.cos(yaw) * 0.46 * s;
            double gz = position.z - Math.sin(yaw) * 0.46 * s;
            r.drawOrientedWorldBox(gx, position.y + 0.05, gz, 0.06, 0.10, 0.56, yaw + 0.55, 0.0, Renderer3D.TEX_SOLID, gunBody);
            r.drawOrientedWorldCylinder(gx + Math.sin(yaw + 0.55) * 0.36, position.y + 0.06, gz + Math.cos(yaw + 0.55) * 0.36, 0.022, 0.38, yaw + 0.55, 0.0, 8, gunMetal);
            return;
        }

        double stride = Math.sin(walkPhase) * 0.34;
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double rightX = cosY;
        double rightZ = -sinY;
        double bx = position.x, by = position.y, bz = position.z;

        // 1. Анатомические двусоставные ноги (бедро -> тактический наколенник -> голень -> армейский ботинок с подошвой)!
        for (int side = -1; side <= 1; side += 2) {
            double sw = (side < 0) ? stride : -stride;
            double kneeBend = Math.max(0.0, -sw) * 0.45;
            double hipX = bx + rightX * (side * 0.145 * s);
            double hipZ = bz + rightZ * (side * 0.145 * s);

            double thighFwd = Math.sin(sw) * 0.11 * s;
            r.drawOrientedWorldCylinder(hipX + sinY * thighFwd, by + 0.63 * s, hipZ + cosY * thighFwd,
                    0.098 * s, 0.084 * s, 0.40 * s, yaw, Math.PI * 0.5 + sw * 0.85, 9, pantsColor);

            double kneeX = hipX + sinY * (thighFwd * 1.7);
            double kneeZ = hipZ + cosY * (thighFwd * 1.7);
            r.drawWorldEllipsoid(kneeX + sinY * 0.05 * s, by + 0.44 * s, kneeZ + cosY * 0.05 * s,
                    0.066 * s, 0.076 * s, 0.058 * s, 9, 5, bootsColor);

            double shinPitch = Math.PI * 0.5 + sw * 0.65 - kneeBend;
            double ankleFwd = thighFwd * 1.9 - kneeBend * 0.18 * s;
            r.drawOrientedWorldCylinder(hipX + sinY * ankleFwd, by + 0.24 * s, hipZ + cosY * ankleFwd,
                    0.082 * s, 0.072 * s, 0.36 * s, yaw, shinPitch, 9, pantsColor);

            r.drawOrientedWorldBox(hipX + sinY * (ankleFwd + 0.05 * s), by + 0.06 * s, hipZ + cosY * (ankleFwd + 0.05 * s),
                    0.125 * s, 0.11 * s, 0.25 * s, yaw, 0.0, Renderer3D.TEX_SOLID, bootsColor);
        }

        // Тактический ремень и набедренная кобура на правом бедре
        r.drawWorldCylinder(bx, by + 0.81 * s, bz, 0.215 * s, 0.215 * s, 0.08 * s, 12, Renderer3D.TEX_SOLID, pouchColor);
        r.drawOrientedWorldBox(bx + rightX * 0.235 * s, by + 0.65 * s, bz + rightZ * 0.235 * s,
                0.055 * s, 0.17 * s, 0.11 * s, yaw, 0.0, Renderer3D.TEX_SOLID, bootsColor);

        // 2. Торс, бронежилет-плитоносец JPC / разгрузка Chicom с 3 подсумками магазинов и рюкзаком!
        r.drawWorldEllipsoid(bx, by + 1.10 * s, bz, 0.225 * s, 0.31 * s, 0.155 * s, 12, 7, shirtColor);
        r.drawOrientedWorldBox(bx + sinY * 0.095 * s, by + 1.11 * s, bz + cosY * 0.095 * s,
                (jug ? 0.38 : 0.30) * s, 0.33 * s, 0.09 * s, yaw, -0.04, Renderer3D.TEX_SOLID, vestColor);
        r.drawOrientedWorldBox(bx - sinY * 0.095 * s, by + 1.12 * s, bz - cosY * 0.095 * s,
                0.29 * s, 0.33 * s, 0.08 * s, yaw, 0.04, Renderer3D.TEX_SOLID, vestColor);

        // 3 объёмных подсумка магазинов на груди + рюкзак
        for (int m = -1; m <= 1; m++) {
            double mx = bx + sinY * 0.152 * s + rightX * (m * 0.082 * s);
            double mz = bz + cosY * 0.152 * s + rightZ * (m * 0.082 * s);
            r.drawOrientedWorldBox(mx, by + 1.02 * s, mz, 0.062 * s, 0.135 * s, 0.048 * s, yaw, -0.04, Renderer3D.TEX_SOLID, pouchColor);
        }
        r.drawOrientedWorldBox(bx - sinY * 0.19 * s, by + 1.12 * s, bz - cosY * 0.19 * s,
                0.26 * s, 0.34 * s, 0.12 * s, yaw, 0.0, Renderer3D.TEX_SOLID, pouchColor);

        // Наплечники (у Джаггернаута — тяжёлые титановые щитки и броневоротник!)
        r.drawWorldEllipsoid(bx - rightX * 0.265 * s, by + 1.28 * s, bz - rightZ * 0.265 * s,
                (jug ? 0.16 : 0.095) * s, (jug ? 0.14 : 0.10) * s, (jug ? 0.16 : 0.095) * s, 9, 5, jug ? vestColor : shirtColor);
        r.drawWorldEllipsoid(bx + rightX * 0.265 * s, by + 1.28 * s, bz + rightZ * 0.265 * s,
                (jug ? 0.16 : 0.095) * s, (jug ? 0.14 : 0.10) * s, (jug ? 0.16 : 0.095) * s, 9, 5, jug ? vestColor : shirtColor);
        if (jug) {
            r.drawWorldCylinder(bx, by + 1.33 * s, bz, 0.22 * s, 0.20 * s, 0.11 * s, 12, Renderer3D.TEX_SOLID, vestColor);
        }

        if (carryingC4) {
            r.drawOrientedWorldBox(bx - sinY * 0.27 * s, by + 1.13 * s, bz - cosY * 0.27 * s, 0.22, 0.16, 0.09, yaw, 0.0, Renderer3D.TEX_SOLID, 0xC2A35E);
            r.drawTranslucentSphereSprite(bx - sinY * 0.33 * s, by + 1.17 * s, bz - cosY * 0.33 * s, 0.055, 0xFF3322, 0.90);
        }

        // 3. Двуручный стрелковый хват (плечо -> предплечье -> перчатка на цевье и рукояти!)
        if (blindTimer > 0.15) {
            r.drawOrientedWorldCylinder(bx - rightX * 0.18 * s + sinY * 0.20 * s, by + 1.50 * s, bz - rightZ * 0.18 * s + cosY * 0.20 * s,
                    0.068 * s, 0.34 * s, yaw + 1.15, 0.22, 8, shirtColor);
        } else {
            // Левая поддерживающая рука тянется вперёд к цевью винтовки!
            r.drawOrientedWorldCylinder(bx - rightX * 0.24 * s + sinY * 0.10 * s, by + 1.21 * s, bz - rightZ * 0.24 * s + cosY * 0.10 * s,
                    0.066 * s, 0.058 * s, 0.28 * s, yaw + 0.42, 0.35, 8, shirtColor);
            r.drawOrientedWorldCylinder(bx - rightX * 0.06 * s + sinY * 0.31 * s, by + 1.16 * s, bz - rightZ * 0.06 * s + cosY * 0.31 * s,
                    0.055 * s, 0.048 * s, 0.28 * s, yaw + 0.62, -0.08, 8, shirtColor);
            r.drawWorldEllipsoid(bx + rightX * 0.08 * s + sinY * 0.43 * s, by + 1.16 * s, bz + rightZ * 0.08 * s + cosY * 0.43 * s,
                    0.046 * s, 0.044 * s, 0.050 * s, 8, 4, bootsColor);
        }
        // Правая стреляющая рука удерживает пистолетную рукоять у плеча
        r.drawOrientedWorldCylinder(bx + rightX * 0.24 * s + sinY * 0.06 * s, by + 1.20 * s, bz + rightZ * 0.24 * s + cosY * 0.06 * s,
                0.066 * s, 0.058 * s, 0.26 * s, yaw - 0.16, 0.42, 8, shirtColor);
        r.drawOrientedWorldCylinder(bx + rightX * 0.17 * s + sinY * 0.20 * s, by + 1.14 * s, bz + rightZ * 0.17 * s + cosY * 0.20 * s,
                0.054 * s, 0.046 * s, 0.22 * s, yaw - 0.10, -0.15, 8, shirtColor);

        // 4. Реалистичная анатомическая голова по фракциям (Ops-Core FAST с ПНВ у CT, балаклава/арафатка у T, «Алтын» у Босса)!
        r.drawWorldCylinder(bx, by + 1.36 * s, bz, 0.095 * s, 0.088 * s, 0.09 * s, 10, Renderer3D.TEX_SOLID, bootsColor);
        double hy = by + 1.55 * s;
        if (jug) {
            // Тяжёлый титановый шлем «Алтын» с опущенным стальным забралом и узкой бронещелью!
            r.drawWorldEllipsoid(bx, hy + 0.02 * s, bz, 0.165 * s, 0.175 * s, 0.170 * s, 12, 7, helmetCol);
            r.drawOrientedWorldBox(bx + sinY * 0.135 * s, hy + 0.01 * s, bz + cosY * 0.135 * s,
                    0.24 * s, 0.15 * s, 0.06 * s, yaw, 0.0, Renderer3D.TEX_SOLID, 0x36404C);
            r.drawOrientedWorldBox(bx + sinY * 0.162 * s, hy + 0.025 * s, bz + cosY * 0.162 * s,
                    0.18 * s, 0.032 * s, 0.025 * s, yaw, 0.0, Renderer3D.TEX_SOLID, goggleCol);
        } else if (ct) {
            // Боец спецназа CT: тёмная балаклава, переносица, очки Oakley, шлем Ops-Core FAST, наушники и сдвоенный ПНВ!
            r.drawWorldEllipsoid(bx, hy, bz, 0.128 * s, 0.150 * s, 0.134 * s, 12, 7, bootsColor);
            r.drawWorldEllipsoid(bx + sinY * 0.106 * s, hy + 0.012 * s, bz + cosY * 0.106 * s, 0.084 * s, 0.030 * s, 0.042 * s, 8, 4, skinTone);
            r.drawOrientedWorldBox(bx + sinY * 0.120 * s, hy + 0.018 * s, bz + cosY * 0.120 * s,
                    0.175 * s, 0.048 * s, 0.050 * s, yaw, 0.0, Renderer3D.TEX_SOLID, goggleCol);
            r.drawWorldEllipsoid(bx - sinY * 0.01 * s, hy + 0.062 * s, bz - cosY * 0.01 * s,
                    0.146 * s, 0.118 * s, 0.154 * s, 12, 7, helmetCol);
            // Наушники ComTac + бинокулярный ПНВ на лбу шлема
            r.drawWorldEllipsoid(bx - rightX * 0.138 * s, hy - 0.005 * s, bz - rightZ * 0.138 * s, 0.034 * s, 0.058 * s, 0.048 * s, 8, 5, pouchColor);
            r.drawWorldEllipsoid(bx + rightX * 0.138 * s, hy - 0.005 * s, bz + rightZ * 0.138 * s, 0.034 * s, 0.058 * s, 0.048 * s, 8, 5, pouchColor);
            r.drawOrientedWorldCylinder(bx + sinY * 0.155 * s - rightX * 0.038 * s, hy + 0.095 * s, bz + cosY * 0.155 * s - rightZ * 0.038 * s,
                    0.022 * s, 0.024 * s, 0.085 * s, yaw, -0.22, 8, bootsColor);
            r.drawOrientedWorldCylinder(bx + sinY * 0.155 * s + rightX * 0.038 * s, hy + 0.095 * s, bz + cosY * 0.155 * s + rightZ * 0.038 * s,
                    0.022 * s, 0.024 * s, 0.085 * s, yaw, -0.22, 8, bootsColor);
        } else {
            // Террорист (Phoenix балаклава с прорезью глаз или куфия/очки у Снайпера!)
            r.drawWorldEllipsoid(bx, hy, bz, 0.130 * s, 0.154 * s, 0.136 * s, 12, 7, helmetCol);
            if (sniper) {
                r.drawWorldEllipsoid(bx - sinY * 0.015 * s, hy + 0.035 * s, bz - cosY * 0.015 * s, 0.148 * s, 0.138 * s, 0.152 * s, 12, 6, helmetCol);
                r.drawWorldEllipsoid(bx + sinY * 0.105 * s, hy - 0.005 * s, bz + cosY * 0.105 * s, 0.088 * s, 0.058 * s, 0.046 * s, 8, 5, skinTone);
                r.drawOrientedWorldBox(bx + sinY * 0.122 * s, hy + 0.018 * s, bz + cosY * 0.122 * s,
                        0.175 * s, 0.044 * s, 0.045 * s, yaw, 0.0, Renderer3D.TEX_SOLID, goggleCol);
            } else {
                // Вырез для глаз в вязаной балаклаве Phoenix Connexion
                r.drawWorldEllipsoid(bx + sinY * 0.108 * s, hy + 0.016 * s, bz + cosY * 0.108 * s, 0.088 * s, 0.034 * s, 0.045 * s, 9, 4, skinTone);
                r.drawWorldEllipsoid(bx + sinY * 0.122 * s - rightX * 0.038 * s, hy + 0.018 * s, bz + cosY * 0.122 * s - rightZ * 0.038 * s, 0.016 * s, 0.014 * s, 0.014 * s, 6, 4, 0x1A1E24);
                r.drawWorldEllipsoid(bx + sinY * 0.122 * s + rightX * 0.038 * s, hy + 0.018 * s, bz + cosY * 0.122 * s + rightZ * 0.038 * s, 0.016 * s, 0.014 * s, 0.014 * s, 6, 4, 0x1A1E24);
            }
        }

        // 5. Персональное 3D-оружие бота 9.0: силуэт зависит от реального ствола (винтовка, булл-пап, ПП, дробовик, пулемёт, пистолет)
        Weapon.WeaponClass wcl = Weapon.classOf(weaponType);
        boolean pistolHeld = (wcl == Weapon.WeaponClass.PISTOL);
        boolean bullpup = (weaponType == Weapon.Type.AUG || weaponType == Weapon.Type.FAMAS || weaponType == Weapon.Type.P90);
        boolean scoped = (wcl == Weapon.WeaponClass.SNIPER || weaponType == Weapon.Type.AUG || weaponType == Weapon.Type.SG553);
        boolean suppressed = (weaponType == Weapon.Type.M4A1S || weaponType == Weapon.Type.USP_S);
        int wBody;
        switch (weaponType) {
            case AK47: wBody = 0x8C4C26; break;
            case GALIL: wBody = 0x5E4630; break;
            case SG553: wBody = 0x8A5228; break;
            case AUG: wBody = 0x55603E; break;
            case NEGEV: case M249: wBody = 0x3D4A34; break;
            case FIVESEVEN: wBody = 0x5A5446; break;
            case P250: wBody = 0x8E979F; break;
            case AWP: wBody = 0x364C34; break;
            default: wBody = 0x2B3138; break;
        }
        if (flashing) wBody = 0xFF6060;
        double gunFwd = pistolHeld ? 0.46 : 0.34;
        double gunCenterX = bx + rightX * (pistolHeld ? 0.05 : 0.135) * s + sinY * gunFwd * s;
        double gunCenterY = by + (pistolHeld ? 1.22 : 1.19) * s;
        double gunCenterZ = bz + rightZ * (pistolHeld ? 0.05 : 0.135) * s + cosY * gunFwd * s;
        double recLen, stockLen, handLen, barrelLen, barrelR;
        switch (wcl) {
            case PISTOL:      recLen = 0.17; stockLen = 0.0;  handLen = 0.0;  barrelLen = suppressed ? 0.16 : 0.03; barrelR = suppressed ? 0.018 : 0.010; break;
            case SMG:         recLen = (weaponType == Weapon.Type.P90) ? 0.40 : 0.24; stockLen = (weaponType == Weapon.Type.P90) ? 0.0 : 0.14; handLen = 0.10; barrelLen = 0.10; barrelR = 0.014; break;
            case SNIPER:      recLen = 0.30; stockLen = 0.24; handLen = 0.26; barrelLen = (weaponType == Weapon.Type.SSG08) ? 0.52 : 0.62; barrelR = 0.016; break;
            case SHOTGUN:     recLen = 0.26; stockLen = 0.22; handLen = 0.20; barrelLen = 0.46; barrelR = 0.022; break;
            case MACHINE_GUN: recLen = 0.34; stockLen = 0.20; handLen = 0.24; barrelLen = 0.50; barrelR = 0.030; break;
            default:          recLen = bullpup ? 0.44 : 0.32; stockLen = bullpup ? 0.0 : 0.20; handLen = bullpup ? 0.10 : 0.22; barrelLen = suppressed ? 0.48 : 0.40; barrelR = suppressed ? 0.026 : 0.018; break;
        }
        if (stockLen > 0.0) {
            r.drawOrientedWorldBox(gunCenterX - sinY * (recLen * 0.5 + stockLen * 0.4) * s, gunCenterY - 0.015 * s, gunCenterZ - cosY * (recLen * 0.5 + stockLen * 0.4) * s,
                    0.044 * s, 0.090 * s, stockLen * s, yaw, 0.0, Renderer3D.TEX_SOLID, weaponType == Weapon.Type.AK47 ? wBody : gunMetal);
        }
        r.drawOrientedWorldBox(gunCenterX, gunCenterY, gunCenterZ,
                (pistolHeld ? 0.040 : 0.058) * s, (pistolHeld ? 0.060 : (weaponType == Weapon.Type.P90 ? 0.12 : 0.098)) * s, recLen * s,
                yaw, 0.0, Renderer3D.TEX_SOLID, bullpup || pistolHeld ? wBody : gunMetal);
        if (pistolHeld) {
            r.drawOrientedWorldBox(gunCenterX - sinY * 0.05 * s, gunCenterY - 0.07 * s, gunCenterZ - cosY * 0.05 * s,
                    0.034 * s, 0.10 * s, 0.045 * s, yaw, 0.22, Renderer3D.TEX_SOLID, gunMetal);
        }
        if (handLen > 0.0) {
            double hOff = recLen * 0.5 + handLen * 0.45;
            r.drawOrientedWorldBox(gunCenterX + sinY * hOff * s, gunCenterY + 0.005 * s, gunCenterZ + cosY * hOff * s,
                    0.050 * s, 0.066 * s, handLen * s, yaw, 0.0, Renderer3D.TEX_SOLID, wBody);
        }
        // Магазины: изогнутый рожок, прямой, короб пулемёта, верхний P90, трубчатый у дробовика
        if (wcl == Weapon.WeaponClass.MACHINE_GUN) {
            r.drawOrientedWorldBox(gunCenterX + sinY * 0.04 * s, gunCenterY - 0.10 * s, gunCenterZ + cosY * 0.04 * s,
                    0.13 * s, 0.14 * s, 0.13 * s, yaw, 0.0, Renderer3D.TEX_SOLID, 0x3D5236);
        } else if (weaponType == Weapon.Type.P90) {
            r.drawOrientedWorldBox(gunCenterX, gunCenterY + 0.075 * s, gunCenterZ, 0.044 * s, 0.024 * s, 0.30 * s, yaw, 0.0, Renderer3D.TEX_SOLID, 0x6E6A5C);
        } else if (wcl == Weapon.WeaponClass.SHOTGUN) {
            r.drawOrientedWorldCylinder(gunCenterX + sinY * 0.30 * s, gunCenterY - 0.030 * s, gunCenterZ + cosY * 0.30 * s,
                    0.016 * s, 0.34 * s, yaw, 0.0, 8, gunMetal);
        } else if (!pistolHeld && wcl != Weapon.WeaponClass.SNIPER) {
            double magZ = bullpup ? -0.10 : 0.06;
            boolean curved = (weaponType == Weapon.Type.AK47 || weaponType == Weapon.Type.GALIL);
            r.drawOrientedWorldBox(gunCenterX + sinY * magZ * s, gunCenterY - 0.11 * s, gunCenterZ + cosY * magZ * s,
                    0.038 * s, 0.16 * s, 0.062 * s, yaw, curved ? 0.32 : 0.10, Renderer3D.TEX_SOLID,
                    weaponType == Weapon.Type.AK47 ? 0x9E5224 : (weaponType == Weapon.Type.AUG ? 0x8FA2A8 : 0x2E343C));
        } else if (wcl == Weapon.WeaponClass.SNIPER) {
            r.drawOrientedWorldBox(gunCenterX + sinY * 0.04 * s, gunCenterY - 0.07 * s, gunCenterZ + cosY * 0.04 * s,
                    0.036 * s, 0.06 * s, 0.07 * s, yaw, 0.0, Renderer3D.TEX_SOLID, gunMetal);
        }
        // Оптика / коллиматор
        if (scoped) {
            r.drawOrientedWorldCylinder(gunCenterX + sinY * 0.02 * s, gunCenterY + 0.082 * s, gunCenterZ + cosY * 0.02 * s,
                    0.026 * s, 0.030 * s, (wcl == Weapon.WeaponClass.SNIPER ? 0.30 : 0.18) * s, yaw, 0.0, 9, gunMetal);
        } else if (ct && !pistolHeld) {
            r.drawOrientedWorldBox(gunCenterX - sinY * 0.02 * s, gunCenterY + 0.070 * s, gunCenterZ - cosY * 0.02 * s,
                    0.044 * s, 0.048 * s, 0.095 * s, yaw, 0.0, Renderer3D.TEX_SOLID, gunMetal);
        }
        // Ствол / глушитель
        double barrelStart = recLen * 0.5 + handLen * 0.9;
        r.drawOrientedWorldCylinder(gunCenterX + sinY * (barrelStart + barrelLen * 0.5) * s, gunCenterY + 0.018 * s, gunCenterZ + cosY * (barrelStart + barrelLen * 0.5) * s,
                barrelR * s, barrelLen * s, yaw, 0.0, 9, gunMetal);

        // Лазерный целеуказатель элитного снайпера!
        if (wcl == Weapon.WeaponClass.SNIPER && state == State.ENGAGE) {
            Vec3 muz = getMuzzleWorldPosition();
            Vec3 laserEnd = muz.add(new Vec3(sinY * 9.5, -0.05, cosY * 9.5));
            r.draw3DLine(muz, laserEnd, 0xFF2E38, 1);
        }

        if (muzzleFlashTimer > 0.0) {
            Vec3 muz = getMuzzleWorldPosition();
            r.drawTranslucentSphereSprite(muz.x, muz.y, muz.z, (suppressed ? 0.07 : 0.19) * s, ct ? 0x88ECFF : 0xFFCA3A, 0.92);
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
        if (useGlbModels && cachedMuzzleValid && alive && rig != null) {
            return new Vec3(cachedMuzzle.x, cachedMuzzle.y, cachedMuzzle.z);
        }
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
