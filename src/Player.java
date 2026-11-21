/**
 * FPS-контроллер игрока CS-2 JAVA 2.0:
 * - Движение WASD, тихий шаг Shift, прыжок Space, приседание Ctrl
 * - Экономика CS2 ($), сохранение купленного оружия при выживании в раунде, гранаты HE и Smoke
 * - Реалистичная тряска камеры от взрывов, шаги по песку, инверсия мыши и счетчик серий убийств
 */
public class Player {
    public static final double RADIUS = 0.36;
    public static final double STAND_HEIGHT = 1.80;
    public static final double CROUCH_HEIGHT = 1.10;
    public static final double STAND_EYE_HEIGHT = 1.64;
    public static final double CROUCH_EYE_HEIGHT = 0.96;

    private static final double BASE_MOVE_SPEED = 6.2;
    private static final double CROUCH_SPEED_MULT = 0.54;
    private static final double WALK_SPEED_MULT = 0.58;
    private static final double JUMP_VELOCITY = 7.85;
    private static final double GRAVITY = 20.5;
    private static final double STEP_HEIGHT = 0.34;
    private static final double MAX_PITCH = Math.toRadians(85.0);
    public static final int MAX_MONEY = 16000;

    private final Vec3 position = new Vec3();
    private final Vec3 velocity = new Vec3();

    private double yaw = 0.0;
    private double pitch = 0.0;

    private boolean crouching = false;
    private boolean silentWalking = false;
    private boolean onGround = true;
    private double currentEyeHeight = STAND_EYE_HEIGHT;

    private int health = 100;
    private int armor = 100;
    private boolean alive = true;

    // Слоты: 0 = Основное (AK-47 / M4A1-S / AWP / XM1014), 1 = Пистолет (Glock / Deagle), 2 = Нож
    private final Weapon[] weapons = new Weapon[]{
        new Weapon(Weapon.Type.AK47),
        new Weapon(Weapon.Type.GLOCK),
        new Weapon(Weapon.Type.KNIFE)
    };
    private int currentWeaponIndex = 0;

    // Экономика и тактические гранаты
    private int money = 2400;
    private int heGrenades = 1;
    private int smokeGrenades = 1;

    // Анимации камеры, шагов и эффекты боя
    private double walkBobPhase = 0.0;
    private double footstepAccum = 0.0;
    private boolean footstepReady = false;
    private double swayX = 0.0;
    private double swayY = 0.0;
    private double damageFlash = 0.0;
    private double cameraShake = 0.0;
    private double shakePhase = 0.0;
    private Vec3 lastDamageSource = null;

    // Статистика матча и текущего раунда
    private int kills = 0;
    private int deaths = 0;
    private int headshots = 0;
    private int shotsFired = 0;
    private int shotsHit = 0;
    private int roundKills = 0;

    public Player(Vec3 spawnPos) {
        respawnForRound(spawnPos, true);
    }

    public void respawnForRound(Vec3 spawnPos, boolean resetLoadoutToDefault) {
        this.position.set(spawnPos);
        this.velocity.set(0.0, 0.0, 0.0);
        this.yaw = 0.0;
        this.pitch = 0.0;
        this.crouching = false;
        this.silentWalking = false;
        this.onGround = true;
        this.currentEyeHeight = STAND_EYE_HEIGHT;
        this.health = 100;
        this.armor = 100;
        this.damageFlash = 0.0;
        this.cameraShake = 0.0;
        this.lastDamageSource = null;
        this.roundKills = 0;

        if (resetLoadoutToDefault || !this.alive) {
            this.weapons[0] = new Weapon(Weapon.Type.AK47);
            this.weapons[1] = new Weapon(Weapon.Type.GLOCK);
            this.weapons[2] = new Weapon(Weapon.Type.KNIFE);
            this.heGrenades = 1;
            this.smokeGrenades = 1;
        } else {
            for (Weapon w : weapons) {
                w.resetAmmo();
            }
            if (heGrenades < 1) heGrenades = 1;
            if (smokeGrenades < 1) smokeGrenades = 1;
        }

        this.alive = true;
        this.currentWeaponIndex = 0;
    }

    public void respawn(Vec3 spawnPos) {
        respawnForRound(spawnPos, true);
    }

    public void resetMatchStats() {
        this.kills = 0;
        this.deaths = 0;
        this.headshots = 0;
        this.shotsFired = 0;
        this.shotsHit = 0;
        this.roundKills = 0;
        this.money = 2400;
        this.weapons[0] = new Weapon(Weapon.Type.AK47);
        this.weapons[1] = new Weapon(Weapon.Type.GLOCK);
        this.weapons[2] = new Weapon(Weapon.Type.KNIFE);
        this.heGrenades = 1;
        this.smokeGrenades = 1;
    }

    public void addMoney(int delta) {
        this.money = Math.max(0, Math.min(MAX_MONEY, this.money + delta));
    }

    public boolean spendMoney(int cost) {
        if (money < cost) return false;
        money -= cost;
        return true;
    }

    public void equipPurchasedWeapon(Weapon.Type type) {
        Weapon nw = new Weapon(type);
        int slot = nw.getSlotNumber() - 1;
        if (slot >= 0 && slot < weapons.length) {
            weapons[slot] = nw;
            currentWeaponIndex = slot;
            weapons[slot].onEquip();
        }
    }

    public void applyMouseLook(double deltaX, double deltaY, double sensitivity, boolean invertY) {
        if (!alive) return;
        double zoomScale = getCurrentWeapon().getEffectiveFovFactor();
        double effSens = sensitivity * Math.max(0.25, zoomScale);
        yaw += deltaX * effSens;
        double dy = invertY ? -deltaY : deltaY;
        pitch -= dy * effSens;
        if (pitch > MAX_PITCH) pitch = MAX_PITCH;
        if (pitch < -MAX_PITCH) pitch = -MAX_PITCH;

        swayX = clamp(swayX - deltaX * 0.00032, -0.034, 0.034);
        swayY = clamp(swayY + dy * 0.00032, -0.030, 0.030);
    }

    public void applyMouseLook(double deltaX, double deltaY, double sensitivity) {
        applyMouseLook(deltaX, deltaY, sensitivity, false);
    }

    public void applyWeaponRecoilKick(Weapon.Type type) {
        double kick;
        switch (type) {
            case AWP:    kick = 0.045; cameraShake = Math.max(cameraShake, 0.25); break;
            case DEAGLE: kick = 0.032; cameraShake = Math.max(cameraShake, 0.14); break;
            case XM1014: kick = 0.028; cameraShake = Math.max(cameraShake, 0.12); break;
            case AK47:   kick = 0.019; break;
            case M4A1S:  kick = 0.012; break;
            case GLOCK:  kick = 0.010; break;
            default:     kick = 0.0; break;
        }
        pitch = clamp(pitch + kick, -MAX_PITCH, MAX_PITCH);
    }

    public void addCameraShake(double amount) {
        this.cameraShake = Math.min(1.0, this.cameraShake + amount);
    }

    public void selectWeaponSlot(int index) {
        if (!alive || index < 0 || index >= weapons.length || index == currentWeaponIndex) {
            return;
        }
        weapons[currentWeaponIndex].cancelZoom();
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
        update(dt, map, moveForward, moveBackward, moveLeft, moveRight, jumpRequested, crouchHeld, false);
    }

    public void update(double dt, Map map,
                       boolean moveForward, boolean moveBackward,
                       boolean moveLeft, boolean moveRight,
                       boolean jumpRequested, boolean crouchHeld, boolean walkShiftHeld) {
        if (damageFlash > 0.0) damageFlash = Math.max(0.0, damageFlash - dt * 2.4);
        if (cameraShake > 0.0) {
            cameraShake = Math.max(0.0, cameraShake - dt * 2.6);
            shakePhase += dt * 46.0;
        }
        swayX *= Math.pow(0.001, dt);
        swayY *= Math.pow(0.001, dt);

        if (!alive) {
            currentEyeHeight += (0.32 - currentEyeHeight) * Math.min(1.0, dt * 8.0);
            return;
        }

        this.silentWalking = walkShiftHeld;
        getCurrentWeapon().update(dt);

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

        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double wishX = 0.0, wishZ = 0.0;

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
        if (getCurrentWeapon().getScopeZoomLevel() > 0) {
            maxSpeed *= 0.62;
        }
        if (crouching) {
            maxSpeed *= CROUCH_SPEED_MULT;
        } else if (silentWalking) {
            maxSpeed *= WALK_SPEED_MULT;
        }

        double accel = onGround ? 18.0 : 10.5;
        velocity.x += (wishX * maxSpeed - velocity.x) * Math.min(1.0, accel * dt);
        velocity.z += (wishZ * maxSpeed - velocity.z) * Math.min(1.0, accel * dt);

        if (jumpRequested && onGround) {
            velocity.y = JUMP_VELOCITY;
            onGround = false;
        }

        velocity.y -= GRAVITY * dt;
        if (velocity.y < -28.0) velocity.y = -28.0;

        moveHorizontalAxis(map, velocity.x * dt, 0.0, bodyHeight);
        moveHorizontalAxis(map, 0.0, velocity.z * dt, bodyHeight);

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

        double horizSpeed = getHorizontalSpeed();
        if (onGround && horizSpeed > 0.35) {
            walkBobPhase += dt * horizSpeed * 1.65;
            if (!silentWalking && !crouching && horizSpeed > 2.2) {
                footstepAccum += dt * horizSpeed;
                if (footstepAccum >= 2.35) {
                    footstepAccum = 0.0;
                    footstepReady = true;
                }
            }
        } else {
            walkBobPhase += dt * 1.05;
        }
    }

    public boolean consumeFootstepTrigger() {
        if (footstepReady) {
            footstepReady = false;
            return true;
        }
        return false;
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
        cameraShake = Math.min(0.65, cameraShake + 0.16);
        if (attackerPos != null) {
            lastDamageSource = attackerPos.copy();
        }

        if (health <= 0) {
            alive = false;
            deaths++;
        }
    }

    public void healAndRestoreArmor(int hpAdd, int apAdd) {
        if (!alive) return;
        health = Math.min(100, health + hpAdd);
        armor = Math.min(100, armor + apAdd);
    }

    public int recordKill(boolean headshot, int cashReward) {
        kills++;
        roundKills++;
        if (headshot) headshots++;
        addMoney(cashReward);
        return roundKills;
    }

    public void recordKill(boolean headshot) {
        recordKill(headshot, 300);
    }

    public void recordShot(boolean hit) {
        shotsFired++;
        if (hit) shotsHit++;
    }

    public boolean consumeHeGrenade() {
        if (heGrenades <= 0) return false;
        heGrenades--;
        return true;
    }

    public boolean consumeSmokeGrenade() {
        if (smokeGrenades <= 0) return false;
        smokeGrenades--;
        return true;
    }

    public void addHeGrenade() { heGrenades = Math.min(2, heGrenades + 1); }
    public void addSmokeGrenade() { smokeGrenades = Math.min(2, smokeGrenades + 1); }

    public double getKdRatio() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    public double getAccuracyPercent() {
        return shotsFired == 0 ? 0.0 : (shotsHit * 100.0) / shotsFired;
    }

    public Vec3 getPosition() { return position; }

    public Vec3 getEyePosition() {
        double bobOffset = onGround ? Math.sin(walkBobPhase * 2.0) * 0.018 * getWalkSpeedNormalized() : 0.0;
        double shakeY = cameraShake * Math.sin(shakePhase * 1.3) * 0.045;
        return new Vec3(position.x, position.y + currentEyeHeight + bobOffset + shakeY, position.z);
    }

    public double getEffectiveYaw() {
        return yaw + cameraShake * Math.cos(shakePhase) * 0.025;
    }

    public double getEffectivePitch() {
        return clamp(pitch + cameraShake * Math.sin(shakePhase * 1.7) * 0.025, -MAX_PITCH, MAX_PITCH);
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
    public boolean isSilentWalking() { return silentWalking; }
    public boolean isOnGround() { return onGround; }
    public boolean isAlive() { return alive; }
    public int getHealth() { return health; }
    public int getArmor() { return armor; }
    public int getMoney() { return money; }
    public int getHeGrenades() { return heGrenades; }
    public int getSmokeGrenades() { return smokeGrenades; }
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
    public int getRoundKills() { return roundKills; }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
