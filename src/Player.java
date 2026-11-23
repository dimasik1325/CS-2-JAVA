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

    // Слоты: 0 = Основное, 1 = Пистолет, 2 = Нож, 3 = Электрошокер Zeus x27
    private final Weapon[] weapons = new Weapon[]{
        new Weapon(Weapon.Type.AK47),
        new Weapon(Weapon.Type.GLOCK),
        new Weapon(Weapon.Type.KNIFE),
        new Weapon(Weapon.Type.ZEUS)
    };
    private int currentWeaponIndex = 0;

    // Экономика, тактические гранаты, Zeus x27, набор сапера и фонарик
    private int money = 2400;
    private int heGrenades = 1;
    private int smokeGrenades = 1;
    private int flashGrenades = 1;
    private int molotovGrenades = 1;
    private int sentryCharges = 1;
    private int airstrikeCharges = 1;
    private boolean hasDefuseKit = false;
    private boolean hasZeus = true;
    private boolean flashlightOn = false;
    private double graffitiCooldown = 0.0;
    private double dashTimer = 0.0;
    private double dashCooldown = 0.0;
    private double dashDirX = 0.0;
    private double dashDirZ = 0.0;

    // Прогресс режима «Гонка вооружений» (Arms Race: 0..7) и звезды MVP
    private int armsRaceTier = 0;
    private int armsRaceTierKills = 0;
    private int mvpStars = 0;

    // Анимации камеры, шагов и эффекты боя
    private double walkBobPhase = 0.0;
    private double footstepAccum = 0.0;
    private boolean footstepReady = false;
    private double swayX = 0.0;
    private double swayY = 0.0;
    private double damageFlash = 0.0;
    private double flashBlindTimer = 0.0;
    private double cameraShake = 0.0;
    private double shakePhase = 0.0;
    private Vec3 lastDamageSource = null;

    // ===== РЕАЛИЗМ 9.0: отдача CS2, торможение при попадании, урон от падения, шлем =====
    private double punchPitch = 0.0, punchYaw = 0.0;      // визуальный «aim punch» от паттерна распыления
    private double kickPitch = 0.0, kickYaw = 0.0;        // мгновенный толчок камеры (затухает)
    private double taggingTimer = 0.0;                    // замедление при получении урона (tagging)
    private double landingSlowTimer = 0.0;                // штраф скорости после приземления
    private double airPeakY = 0.0;                        // высшая точка полёта (для урона от падения)
    private int pendingFallDamage = 0;
    private boolean hasHelmet = true;
    private Weapon.HitGroup lastHitGroup = Weapon.HitGroup.CHEST;
    private double hitIndicatorTimer = 0.0;
    private double bleedTimer = 0.0;
    private int damageTakenThisRound = 0;

    // Статистика матча и текущего раунда (включая карточки фрагов CS2)
    private int kills = 0;
    private int deaths = 0;
    private int headshots = 0;
    private int shotsFired = 0;
    private int shotsHit = 0;
    private int roundKills = 0;
    private final boolean[] roundKillHeadshots = new boolean[16];

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
        this.flashBlindTimer = 0.0;
        this.cameraShake = 0.0;
        this.punchPitch = this.punchYaw = this.kickPitch = this.kickYaw = 0.0;
        this.taggingTimer = 0.0;
        this.landingSlowTimer = 0.0;
        this.airPeakY = spawnPos.y;
        this.pendingFallDamage = 0;
        this.hasHelmet = true;
        this.hitIndicatorTimer = 0.0;
        this.bleedTimer = 0.0;
        this.damageTakenThisRound = 0;
        this.dashTimer = 0.0;
        this.dashCooldown = 0.0;
        this.lastDamageSource = null;
        this.roundKills = 0;

        if (resetLoadoutToDefault || !this.alive) {
            this.weapons[0] = new Weapon(Weapon.Type.AK47);
            this.weapons[1] = new Weapon(Weapon.Type.GLOCK);
            this.weapons[2] = new Weapon(Weapon.Type.KNIFE);
            this.weapons[3] = new Weapon(Weapon.Type.ZEUS);
            this.heGrenades = 1;
            this.smokeGrenades = 1;
            this.flashGrenades = 1;
            this.molotovGrenades = 1;
            this.sentryCharges = 1;
            this.airstrikeCharges = 1;
            this.hasDefuseKit = false;
            this.hasZeus = true;
        } else {
            for (Weapon w : weapons) {
                w.resetAmmo();
            }
            if (heGrenades < 1) heGrenades = 1;
            if (smokeGrenades < 1) smokeGrenades = 1;
            if (flashGrenades < 1) flashGrenades = 1;
            if (sentryCharges < 1) sentryCharges = 1;
            if (airstrikeCharges < 1) airstrikeCharges = 1;
        }

        this.alive = true;
        this.currentWeaponIndex = 0;
    }

    public void applyArmsRaceTierLoadout() {
        int clamped = Math.max(0, Math.min(Weapon.ARMS_RACE_LADDER.length - 1, armsRaceTier));
        Weapon.Type tierType = Weapon.ARMS_RACE_LADDER[clamped];
        this.weapons[2] = new Weapon(Weapon.Type.KNIFE);
        this.weapons[3] = new Weapon(Weapon.Type.ZEUS);
        this.hasZeus = true;
        if (tierType == Weapon.Type.KNIFE) {
            // Финальный 8-й уровень: Золотой Нож!
            this.currentWeaponIndex = 2;
            this.weapons[2].onEquip();
        } else if (Weapon.classOf(tierType) == Weapon.WeaponClass.PISTOL) {
            this.weapons[1] = new Weapon(tierType);
            this.currentWeaponIndex = 1;
            this.weapons[1].onEquip();
        } else {
            this.weapons[0] = new Weapon(tierType);
            this.currentWeaponIndex = 0;
            this.weapons[0].onEquip();
        }
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
        this.weapons[3] = new Weapon(Weapon.Type.ZEUS);
        this.heGrenades = 1;
        this.smokeGrenades = 1;
        this.flashGrenades = 1;
        this.molotovGrenades = 1;
        this.sentryCharges = 1;
        this.airstrikeCharges = 1;
        this.dashTimer = 0.0;
        this.dashCooldown = 0.0;
        this.hasDefuseKit = false;
        this.hasZeus = true;
        this.flashlightOn = false;
        this.armsRaceTier = 0;
        this.armsRaceTierKills = 0;
        this.mvpStars = 0;
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
        if (type == Weapon.Type.ZEUS) {
            this.hasZeus = true;
            this.weapons[3] = new Weapon(Weapon.Type.ZEUS);
            selectWeaponSlot(3);
            return;
        }
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

    /**
     * Отдача CS2 9.0: вместо «вечного» подъёма камеры — мгновенный толчок (view punch), который сам возвращается,
     * а основная отдача задаётся детерминированным паттерном распыления ствола (см. Weapon.getSprayOffset).
     */
    public void applyWeaponRecoilKick(Weapon.Type type) {
        double kick, side = 0.0;
        switch (type) {
            case RPG7:   kick = 0.060; cameraShake = Math.max(cameraShake, 0.34); break;
            case AWP:    kick = 0.052; cameraShake = Math.max(cameraShake, 0.25); break;
            case SSG08:  kick = 0.034; cameraShake = Math.max(cameraShake, 0.10); break;
            case DEAGLE: kick = 0.040; side = 0.010; cameraShake = Math.max(cameraShake, 0.14); break;
            case NOVA: case XM1014: kick = 0.034; cameraShake = Math.max(cameraShake, 0.13); break;
            case NEGEV: case M249: kick = 0.010; side = 0.006; cameraShake = Math.max(cameraShake, 0.06); break;
            case AK47: case GALIL: case SG553: kick = 0.011; side = 0.004; break;
            case M4A4: case M4A1S: case AUG: case FAMAS: kick = 0.008; side = 0.003; break;
            case P250: case FIVESEVEN: case TEC9: kick = 0.016; side = 0.005; break;
            case GLOCK: case USP_S: kick = 0.012; side = 0.004; break;
            case MP9: case MAC10: case UMP45: case P90: kick = 0.006; side = 0.004; break;
            default:     kick = 0.0; break;
        }
        kickPitch = Math.min(0.12, kickPitch + kick);
        kickYaw += side * (Math.random() * 2.0 - 1.0);
    }

    public Weapon.HitGroup getLastHitGroup() { return lastHitGroup; }
    public double getHitIndicatorTimer() { return hitIndicatorTimer; }
    public double getTaggingAmount() { return Math.min(1.0, taggingTimer / 0.45); }
    public boolean hasHelmet() { return hasHelmet; }
    public void setHelmet(boolean h) { hasHelmet = h; }
    public int consumePendingFallDamage() { int d = pendingFallDamage; pendingFallDamage = 0; return d; }
    public double getPunchPitch() { return punchPitch + kickPitch; }
    public double getPunchYaw() { return punchYaw + kickYaw; }
    public int getDamageTakenThisRound() { return damageTakenThisRound; }

    public void addCameraShake(double amount) {
        this.cameraShake = Math.min(1.0, this.cameraShake + amount);
    }

    public void applyFlashBlind(double durationSeconds) {
        this.flashBlindTimer = Math.max(this.flashBlindTimer, durationSeconds);
    }

    public void selectWeaponSlot(int index) {
        if (!alive || index < 0 || index >= weapons.length || index == currentWeaponIndex) {
            return;
        }
        if (index == 3 && !hasZeus) {
            return;
        }
        weapons[currentWeaponIndex].cancelZoom();
        currentWeaponIndex = index;
        weapons[currentWeaponIndex].onEquip();
    }

    public void cycleWeapon(int delta) {
        if (!alive) return;
        int count = hasZeus ? weapons.length : 3;
        int next = (currentWeaponIndex + delta) % count;
        if (next < 0) next += count;
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
        if (flashBlindTimer > 0.0) flashBlindTimer = Math.max(0.0, flashBlindTimer - dt);
        if (graffitiCooldown > 0.0) graffitiCooldown = Math.max(0.0, graffitiCooldown - dt);
        if (dashCooldown > 0.0) dashCooldown = Math.max(0.0, dashCooldown - dt);
        if (dashTimer > 0.0) dashTimer = Math.max(0.0, dashTimer - dt);
        if (cameraShake > 0.0) {
            cameraShake = Math.max(0.0, cameraShake - dt * 2.6);
            shakePhase += dt * 46.0;
        }
        if (taggingTimer > 0.0) taggingTimer = Math.max(0.0, taggingTimer - dt);
        if (landingSlowTimer > 0.0) landingSlowTimer = Math.max(0.0, landingSlowTimer - dt);
        if (hitIndicatorTimer > 0.0) hitIndicatorTimer = Math.max(0.0, hitIndicatorTimer - dt);
        if (bleedTimer > 0.0) bleedTimer = Math.max(0.0, bleedTimer - dt);
        // Визуальный aim punch следует за паттерном распыления оружия (50% как в CS2) и плавно возвращается
        {
            double[] spray = getCurrentWeapon().getCurrentSprayOffset();
            double k = Math.min(1.0, dt * 26.0);
            punchPitch += (spray[1] * 0.5 - punchPitch) * k;
            punchYaw += (spray[0] * 0.5 - punchYaw) * k;
            double kd = Math.exp(-dt * 11.0);
            kickPitch *= kd;
            kickYaw *= kd;
        }
        swayX *= Math.pow(0.001, dt);
        swayY *= Math.pow(0.001, dt);

        if (!alive) {
            currentEyeHeight += (0.32 - currentEyeHeight) * Math.min(1.0, dt * 8.0);
            return;
        }

        this.silentWalking = walkShiftHeld;
        getCurrentWeapon().update(dt);
        if (hasZeus && currentWeaponIndex != 3) {
            weapons[3].update(dt);
        }

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
        // Tagging CS2: попадание пули резко тормозит бойца; жёсткое приземление тоже гасит скорость
        maxSpeed *= 1.0 - 0.55 * getTaggingAmount();
        if (landingSlowTimer > 0.0) maxSpeed *= 0.72;

        double accel = onGround ? 18.0 : 10.5;
        if (dashTimer > 0.0) {
            velocity.x = dashDirX * 14.8;
            velocity.z = dashDirZ * 14.8;
        } else {
            velocity.x += (wishX * maxSpeed - velocity.x) * Math.min(1.0, accel * dt);
            velocity.z += (wishZ * maxSpeed - velocity.z) * Math.min(1.0, accel * dt);
        }

        if (jumpRequested && onGround) {
            velocity.y = JUMP_VELOCITY;
            onGround = false;
            airPeakY = position.y;
        }
        boolean wasOnGround = onGround;

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

        // Урон от падения: безопасная высота ≈ 5.3 м (580 u/s в Source), затем ~10 HP за метр
        if (!onGround) {
            if (wasOnGround) airPeakY = position.y;
            airPeakY = Math.max(airPeakY, position.y);
        } else if (!wasOnGround) {
            double drop = airPeakY - position.y;
            if (drop > 1.6) {
                landingSlowTimer = Math.min(0.45, 0.10 + drop * 0.05);
                cameraShake = Math.min(0.8, cameraShake + Math.min(0.5, drop * 0.05));
                kickPitch -= Math.min(0.05, drop * 0.006);
            }
            if (drop > 5.3) {
                pendingFallDamage = (int) Math.round((drop - 5.3) * 10.5 + 2);
            }
            airPeakY = position.y;
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
        takeDamage(rawDamage, attackerPos, 0.70, Weapon.HitGroup.CHEST, false);
    }

    /** Взрывы/огонь/падение — без зоны попадания и без учёта шлема. */
    public void takeEnvironmentalDamage(int rawDamage, Vec3 sourcePos, boolean bypassArmor) {
        takeDamage(rawDamage, sourcePos, bypassArmor ? 1.0 : 0.55, Weapon.HitGroup.CHEST, true);
    }

    /**
     * Реальная формула брони CS2: при попадании в защищённую зону (торс/руки, голова — только со шлемом)
     * здоровье теряет урон × бронепробитие оружия, а броня — половину поглощённого. Ноги броня не защищает.
     */
    public void takeDamage(int rawDamage, Vec3 attackerPos, double armorPenetration, Weapon.HitGroup hg, boolean environmental) {
        if (!alive || rawDamage <= 0) return;

        int healthLoss = rawDamage;
        boolean armored = armor > 0 && hg != Weapon.HitGroup.LEGS && (hg != Weapon.HitGroup.HEAD || hasHelmet);
        if (armored && armorPenetration < 1.0) {
            double hp = rawDamage * armorPenetration;
            double ap = (rawDamage - hp) * 0.5;
            if (ap > armor) {
                hp = rawDamage - armor * 2.0;
                ap = armor;
            }
            healthLoss = Math.max(1, (int) Math.round(hp));
            armor = Math.max(0, armor - (int) Math.round(ap));
        }

        health = Math.max(0, health - healthLoss);
        damageTakenThisRound += healthLoss;
        lastHitGroup = hg;
        hitIndicatorTimer = 0.9;
        damageFlash = Math.min(1.0, damageFlash + (hg == Weapon.HitGroup.HEAD ? 0.95 : 0.65));
        cameraShake = Math.min(0.65, cameraShake + 0.16);
        if (!environmental) {
            taggingTimer = 0.45;   // замедление от попадания
            // Флинч от пули: без брони камеру дёргает сильно, со шлемом/бронёй — слабее
            double flinch = (armored ? 0.010 : 0.030) * (hg == Weapon.HitGroup.HEAD ? 2.2 : 1.0);
            kickPitch += flinch;
            kickYaw += flinch * (Math.random() - 0.5);
            if (healthLoss >= 20) bleedTimer = 2.0;
        }
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
        if (roundKills >= 0 && roundKills < roundKillHeadshots.length) {
            roundKillHeadshots[roundKills] = headshot;
        }
        roundKills++;
        if (headshot) headshots++;
        addMoney(cashReward);
        return roundKills;
    }

    public boolean isRoundKillHeadshot(int idx) {
        return idx >= 0 && idx < roundKillHeadshots.length && roundKillHeadshots[idx];
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

    public boolean consumeFlashGrenade() {
        if (flashGrenades <= 0) return false;
        flashGrenades--;
        return true;
    }

    public boolean consumeMolotovGrenade() {
        if (molotovGrenades <= 0) return false;
        molotovGrenades--;
        return true;
    }

    public void addHeGrenade() { heGrenades = Math.min(2, heGrenades + 1); }
    public void addSmokeGrenade() { smokeGrenades = Math.min(2, smokeGrenades + 1); }
    public void addFlashGrenade() { flashGrenades = Math.min(2, flashGrenades + 1); }
    public void addMolotovGrenade() { molotovGrenades = Math.min(2, molotovGrenades + 1); }
    public int getSentryCharges() { return sentryCharges; }
    public void addSentryCharge() { sentryCharges = Math.min(2, sentryCharges + 1); }
    public boolean consumeSentryCharge() {
        if (sentryCharges <= 0) return false;
        sentryCharges--;
        return true;
    }
    public void setHasDefuseKit(boolean v) { this.hasDefuseKit = v; }
    public boolean hasDefuseKit() { return hasDefuseKit; }
    public void setHasZeus(boolean v) { this.hasZeus = v; }
    public boolean hasZeus() { return hasZeus; }
    public boolean canSprayGraffiti() { return graffitiCooldown <= 0.0; }
    public void triggerGraffitiCooldown() { this.graffitiCooldown = 1.25; }
    public int getArmsRaceTier() { return armsRaceTier; }
    public void setArmsRaceTier(int t) { this.armsRaceTier = Math.max(0, Math.min(Weapon.ARMS_RACE_LADDER.length - 1, t)); }
    public int getArmsRaceTierKills() { return armsRaceTierKills; }
    public void setArmsRaceTierKills(int k) { this.armsRaceTierKills = k; }
    public int getMvpStars() { return mvpStars; }
    public void addMvpStar() { this.mvpStars++; }
    public void toggleFlashlight() { this.flashlightOn = !this.flashlightOn; }
    public void setFlashlightOn(boolean v) { this.flashlightOn = v; }
    public boolean isFlashlightOn() { return flashlightOn; }
    public double getFlashBlindTimer() { return flashBlindTimer; }

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
        return yaw + punchYaw + kickYaw + cameraShake * Math.cos(shakePhase) * 0.025;
    }

    public double getEffectivePitch() {
        return clamp(pitch + punchPitch + kickPitch + cameraShake * Math.sin(shakePhase * 1.7) * 0.025, -MAX_PITCH, MAX_PITCH);
    }

    /** Направление полёта пули с учётом полного паттерна отдачи ствола (aim punch ×1, view punch ×0.5). */
    public Vec3 getShotDirection(double sprayYaw, double sprayPitch) {
        double y = yaw + sprayYaw + kickYaw;
        double p = clamp(pitch + sprayPitch + kickPitch, -MAX_PITCH, MAX_PITCH);
        double cp = Math.cos(p);
        return new Vec3(Math.sin(y) * cp, Math.sin(p), Math.cos(y) * cp).normalize();
    }

    public double getMaxMoveSpeed() { return BASE_MOVE_SPEED; }
    public boolean isBleeding() { return bleedTimer > 0.0; }

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
    public int getFlashGrenades() { return flashGrenades; }
    public int getMolotovGrenades() { return molotovGrenades; }
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

    public int getAirstrikeCharges() { return airstrikeCharges; }
    public void addAirstrikeCharge(int delta) {
        airstrikeCharges = Math.max(0, Math.min(4, airstrikeCharges + delta));
    }
    public boolean consumeAirstrikeCharge() {
        if (airstrikeCharges <= 0) return false;
        airstrikeCharges--;
        return true;
    }

    public boolean tryTriggerDash(boolean fwd, boolean back, boolean left, boolean right) {
        if (!alive || dashCooldown > 0.0) return false;
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double dx = 0.0, dz = 0.0;
        if (fwd)   { dx += sinY; dz += cosY; }
        if (back)  { dx -= sinY; dz -= cosY; }
        if (right) { dx += cosY; dz -= sinY; }
        if (left)  { dx -= cosY; dz += sinY; }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) {
            dx = sinY;
            dz = cosY;
        } else {
            dx /= len;
            dz /= len;
        }
        this.dashDirX = dx;
        this.dashDirZ = dz;
        this.dashTimer = 0.22;
        this.dashCooldown = 2.6;
        return true;
    }

    public double getDashReadyProgress() {
        return dashCooldown <= 0.0 ? 1.0 : Math.max(0.0, 1.0 - dashCooldown / 2.6);
    }

    public double getDashFovBoostDegrees() {
        return dashTimer > 0.0 ? Math.sin((dashTimer / 0.22) * Math.PI) * 6.5 : 0.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
