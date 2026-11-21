/**
 * Игрок (вид от первого лица — FPS контроллер):
 * физика движения WASD, прыжки на Space, приседание на Ctrl, обзор мышью,
 * скользящие 3D-коллизии со стенами и ящиками, инвентарь оружия (1, 2, 3) и статистика K/D.
 */
public class Player {
    public static final double RADIUS = 0.36;
    public static final double STAND_HEIGHT = 1.80;
    public static final double CROUCH_HEIGHT = 1.10;
    public static final double STAND_EYE_HEIGHT = 1.64;
    public static final double CROUCH_EYE_HEIGHT = 0.96;

    private static final double BASE_MOVE_SPEED = 6.2;
    private static final double CROUCH_SPEED_MULT = 0.54;
    private static final double JUMP_VELOCITY = 7.85;
    private static final double GRAVITY = 20.5;
    private static final double STEP_HEIGHT = 0.34;
    private static final double MAX_PITCH = Math.toRadians(85.0);

    private final Vec3 position = new Vec3();
    private final Vec3 velocity = new Vec3();

    private double yaw = 0.0;
    private double pitch = 0.0;

    private boolean crouching = false;
    private boolean onGround = true;
    private double currentEyeHeight = STAND_EYE_HEIGHT;

    private int health = 100;
    private int armor = 100;
    private boolean alive = true;

    // Арсенал: 0 = AK-47 (клавиша 1), 1 = Glock-18 (клавиша 2), 2 = Нож (клавиша 3)
    private final Weapon[] weapons = new Weapon[]{
        new Weapon(Weapon.Type.AK47),
        new Weapon(Weapon.Type.GLOCK),
        new Weapon(Weapon.Type.KNIFE)
    };
    private int currentWeaponIndex = 0;

    // Анимация шага, плавного смещения оружия и вспышки урона
    private double walkBobPhase = 0.0;
    private double swayX = 0.0;
    private double swayY = 0.0;
    private double recoilPitchOffset = 0.0;
    private double damageFlash = 0.0;
    private Vec3 lastDamageSource = null;

    // Статистика матча (K/D, хедшоты, точность)
    private int kills = 0;
    private int deaths = 0;
    private int headshots = 0;
    private int shotsFired = 0;
    private int shotsHit = 0;

    public Player(Vec3 spawnPos) {
        respawn(spawnPos);
    }

    public void respawn(Vec3 spawnPos) {
        this.position.set(spawnPos);
        this.velocity.set(0.0, 0.0, 0.0);
        this.yaw = 0.0;
        this.pitch = 0.0;
        this.crouching = false;
        this.onGround = true;
        this.currentEyeHeight = STAND_EYE_HEIGHT;
        this.health = 100;
        this.armor = 100;
        this.alive = true;
        this.damageFlash = 0.0;
        this.recoilPitchOffset = 0.0;
        this.lastDamageSource = null;

        for (Weapon w : weapons) {
            w.resetAmmo();
        }
        this.currentWeaponIndex = 0;
    }

    public void resetMatchStats() {
        this.kills = 0;
        this.deaths = 0;
        this.headshots = 0;
        this.shotsFired = 0;
        this.shotsHit = 0;
    }

    public void applyMouseLook(double deltaX, double deltaY, double sensitivity) {
        if (!alive) return;
        yaw += deltaX * sensitivity;
        pitch -= deltaY * sensitivity;
        if (pitch > MAX_PITCH) pitch = MAX_PITCH;
        if (pitch < -MAX_PITCH) pitch = -MAX_PITCH;

        swayX = clamp(swayX - deltaX * 0.00032, -0.032, 0.032);
        swayY = clamp(swayY + deltaY * 0.00032, -0.028, 0.028);
    }

    public void applyWeaponRecoilKick(Weapon.Type type) {
        double kick = (type == Weapon.Type.AK47) ? 0.020 : (type == Weapon.Type.GLOCK ? 0.011 : 0.0);
        pitch = clamp(pitch + kick, -MAX_PITCH, MAX_PITCH);
        recoilPitchOffset = Math.min(0.06, recoilPitchOffset + kick * 0.5);
    }

    public void selectWeaponSlot(int index) {
        if (!alive || index < 0 || index >= weapons.length || index == currentWeaponIndex) {
            return;
        }
        currentWeaponIndex = index;
        weapons[currentWeaponIndex].onEquip();
    }

    public void cycleWeapon(int delta) {
        if (!alive) return;
        int next = (currentWeaponIndex + delta) % weapons.length;
        if (next < 0) next += weapons.length;
        selectWeaponSlot(next);
    }

    public void update(double dt, Map map,
                       boolean moveForward, boolean moveBackward,
                       boolean moveLeft, boolean moveRight,
                       boolean jumpRequested, boolean crouchHeld) {
        if (damageFlash > 0.0) {
            damageFlash = Math.max(0.0, damageFlash - dt * 2.4);
        }
        swayX *= Math.pow(0.001, dt);
        swayY *= Math.pow(0.001, dt);
        if (recoilPitchOffset > 0.0) {
            double recover = Math.min(recoilPitchOffset, dt * 0.14);
            recoilPitchOffset -= recover;
        }

        if (!alive) {
            currentEyeHeight += (0.32 - currentEyeHeight) * Math.min(1.0, dt * 8.0);
            return;
        }

        getCurrentWeapon().update(dt);

        // Приседание на Ctrl с проверкой потолка перед вставанием
        if (crouchHeld) {
            crouching = true;
        } else if (crouching) {
            if (!map.collides(position.x, position.y, position.z, RADIUS, STAND_HEIGHT)) {
                crouching = false;
            }
        }

        double targetEye = crouching ? CROUCH_EYE_HEIGHT : STAND_EYE_HEIGHT;
        currentEyeHeight += (targetEye - currentEyeHeight) * Math.min(1.0, dt * 13.0);
        double bodyHeight = getCurrentBodyHeight();

        // Векторы движения в горизонтальной плоскости XZ
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double wishX = 0.0;
        double wishZ = 0.0;

        if (moveForward)  { wishX += sinY; wishZ += cosY; }
        if (moveBackward) { wishX -= sinY; wishZ -= cosY; }
        if (moveRight)    { wishX += cosY; wishZ -= sinY; }
        if (moveLeft)     { wishX -= cosY; wishZ += sinY; }

        double wishLen = Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (wishLen > 1e-6) {
            wishX /= wishLen;
            wishZ /= wishLen;
        }

        double maxSpeed = BASE_MOVE_SPEED * getCurrentWeapon().getMoveSpeedMultiplier();
        if (crouching) {
            maxSpeed *= CROUCH_SPEED_MULT;
        }

        double accel = onGround ? 18.0 : 10.5;
        double targetVx = wishX * maxSpeed;
        double targetVz = wishZ * maxSpeed;

        velocity.x += (targetVx - velocity.x) * Math.min(1.0, accel * dt);
        velocity.z += (targetVz - velocity.z) * Math.min(1.0, accel * dt);

        // Прыжок на Space
        if (jumpRequested && onGround) {
            velocity.y = JUMP_VELOCITY;
            onGround = false;
        }

        // Гравитация
        velocity.y -= GRAVITY * dt;
        if (velocity.y < -28.0) {
            velocity.y = -28.0;
        }

        // Раздельное скольжение по осям X и Z с поддержкой подъема на ступеньки (STEP_HEIGHT)
        moveHorizontalAxis(map, velocity.x * dt, 0.0, bodyHeight);
        moveHorizontalAxis(map, 0.0, velocity.z * dt, bodyHeight);

        // Движение по вертикали Y (пол, крыши ящиков, потолки)
        double nextY = position.y + velocity.y * dt;
        if (velocity.y <= 0.0) {
            double floorY = map.getSupportingFloorY(position.x, position.z, RADIUS, position.y);
            if (nextY <= floorY) {
                position.y = floorY;
                velocity.y = 0.0;
                onGround = true;
            } else if (!map.collides(position.x, nextY, position.z, RADIUS, bodyHeight)) {
                position.y = nextY;
                onGround = false;
            } else {
                velocity.y = 0.0;
                onGround = true;
            }
        } else {
            if (!map.collides(position.x, nextY, position.z, RADIUS, bodyHeight)) {
                position.y = nextY;
                onGround = false;
            } else {
                velocity.y = 0.0;
            }
        }

        // Анимация покачивания оружия при ходьбе
        double horizSpeed = getHorizontalSpeed();
        if (onGround && horizSpeed > 0.25) {
            walkBobPhase += dt * horizSpeed * 1.65;
        } else {
            walkBobPhase += dt * 1.1;
        }
    }

    private void moveHorizontalAxis(Map map, double dx, double dz, double bodyHeight) {
        if (Math.abs(dx) < 1e-7 && Math.abs(dz) < 1e-7) return;

        double targetX = position.x + dx;
        double targetZ = position.z + dz;

        if (!map.collides(targetX, position.y, targetZ, RADIUS, bodyHeight)) {
            position.x = targetX;
            position.z = targetZ;
            return;
        }

        // Попытка плавного подъема на ступеньку/платформу высотой до STEP_HEIGHT
        if (onGround) {
            for (double step = 0.10; step <= STEP_HEIGHT + 0.01; step += 0.08) {
                if (!map.collides(targetX, position.y + step, targetZ, RADIUS, bodyHeight)) {
                    position.x = targetX;
                    position.z = targetZ;
                    position.y += step;
                    return;
                }
            }
        }

        if (Math.abs(dx) > 0.0) velocity.x = 0.0;
        if (Math.abs(dz) > 0.0) velocity.z = 0.0;
    }

    public void takeDamage(int rawDamage, Vec3 attackerPos) {
        if (!alive || rawDamage <= 0) return;

        int healthLoss = rawDamage;
        if (armor > 0) {
            int absorbed = Math.min(armor, (int) Math.round(rawDamage * 0.35));
            armor -= absorbed;
            healthLoss = Math.max(1, rawDamage - absorbed);
        }

        health = Math.max(0, health - healthLoss);
        damageFlash = Math.min(1.0, damageFlash + 0.65);
        if (attackerPos != null) {
            lastDamageSource = attackerPos.copy();
        }

        if (health <= 0) {
            alive = false;
            deaths++;
        }
    }

    public void recordKill(boolean headshot) {
        kills++;
        if (headshot) {
            headshots++;
        }
    }

    public void recordShot(boolean hit) {
        shotsFired++;
        if (hit) {
            shotsHit++;
        }
    }

    public double getKdRatio() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    public double getAccuracyPercent() {
        if (shotsFired == 0) return 0.0;
        return (shotsHit * 100.0) / shotsFired;
    }

    public Vec3 getPosition() {
        return position;
    }

    public Vec3 getEyePosition() {
        double bobOffset = onGround ? Math.sin(walkBobPhase * 2.0) * 0.018 * getWalkSpeedNormalized() : 0.0;
        return new Vec3(position.x, position.y + currentEyeHeight + bobOffset, position.z);
    }

    public Vec3 getCenterTargetPosition() {
        return new Vec3(position.x, position.y + currentEyeHeight * 0.84, position.z);
    }

    public Vec3 getLookDirection() {
        double cp = Math.cos(pitch);
        return new Vec3(Math.sin(yaw) * cp, Math.sin(pitch), Math.cos(yaw) * cp).normalize();
    }

    public Vec3 getRightVector() {
        return new Vec3(Math.cos(yaw), 0.0, -Math.sin(yaw));
    }

    public double getHorizontalSpeed() {
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    public double getWalkSpeedNormalized() {
        return onGround ? Math.min(1.0, getHorizontalSpeed() / BASE_MOVE_SPEED) : 0.0;
    }

    public double getCurrentBodyHeight() {
        return crouching ? CROUCH_HEIGHT : STAND_HEIGHT;
    }

    public double getYaw() { return yaw; }
    public double getPitch() { return pitch; }
    public void setAngles(double yaw, double pitch) {
        this.yaw = yaw;
        this.pitch = clamp(pitch, -MAX_PITCH, MAX_PITCH);
    }

    public boolean isCrouching() { return crouching; }
    public boolean isOnGround() { return onGround; }
    public boolean isAlive() { return alive; }
    public int getHealth() { return health; }
    public int getArmor() { return armor; }
    public Weapon getCurrentWeapon() { return weapons[currentWeaponIndex]; }
    public Weapon[] getWeapons() { return weapons; }
    public int getCurrentWeaponIndex() { return currentWeaponIndex; }
    public double getWalkBobPhase() { return walkBobPhase; }
    public double getSwayX() { return swayX; }
    public double getSwayY() { return swayY; }
    public double getDamageFlash() { return damageFlash; }
    public Vec3 getLastDamageSource() { return lastDamageSource; }

    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public int getHeadshots() { return headshots; }
    public int getShotsFired() { return shotsFired; }
    public int getShotsHit() { return shotsHit; }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
