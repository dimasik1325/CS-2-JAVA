/**
 * Расширенный арсенал оружия CS-2 JAVA 5.0 (11 видов 3D-оружия с гладкими круглыми стволами, оптикой и скинами):
 * Слот 1 (Основное и Тяжёлое): AK-47, M4A1-S, AWP, Пулемёт M249 (100 патр.), Гранатомёт RPG-7 (3D-ракеты!), XM1014, MP9
 * Слот 2 (Пистолеты): Glock-18, Desert Eagle
 * Слот 3 (Ближний бой): Боевой нож
 * Слот 4 (Спецсредство): Электрошокер Zeus x27
 */
public class Weapon {

    public enum Type {
        AK47,
        M4A1S,
        AWP,
        M249,
        RPG7,
        XM1014,
        MP9,
        GLOCK,
        DEAGLE,
        ZEUS,
        KNIFE
    }

    public static final Type[] ARMS_RACE_LADDER = {
        Type.MP9, Type.XM1014, Type.M249, Type.M4A1S, Type.AK47,
        Type.AWP, Type.RPG7, Type.DEAGLE, Type.KNIFE
    };

    private final Type type;
    private final String displayName;
    private final int slotNumber;
    private final int price;
    private final int killReward;
    private final boolean automatic;
    private final boolean melee;
    private final boolean silenced;
    private final boolean sniperScope;
    private final int pelletsPerShot;

    private final int maxMagAmmo;
    private final int initialReserveAmmo;
    private final double fireInterval;
    private final double reloadDuration;
    private final int bodyDamage;
    private final int headDamage;
    private final double maxRange;
    private final double baseSpread;
    private final double recoilPerShot;
    private final double moveSpeedMultiplier;

    private int magAmmo;
    private int reserveAmmo;
    private double fireCooldown;
    private boolean reloading;
    private double reloadTimer;

    private int scopeZoomLevel = 0; // 0 = нет, 1 = зум x2.8, 2 = зум x6 (для AWP)
    private boolean adsActive = false;
    private double inspectTimer = 0.0;
    private static final double INSPECT_DURATION = 2.1;

    private double recoilBloom;
    private double recoilKick;
    private double muzzleFlashTimer;
    private double equipProgress;
    private double slashTimer;
    private boolean heavySlash;

    public Weapon(Type type) {
        this.type = type;
        switch (type) {
            case AK47:
                this.displayName = "AK-47";
                this.slotNumber = 1;
                this.price = 2700;
                this.killReward = 300;
                this.automatic = true;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 30;
                this.initialReserveAmmo = 90;
                this.fireInterval = 0.10;
                this.reloadDuration = 2.15;
                this.bodyDamage = 36;
                this.headDamage = 116;
                this.maxRange = 115.0;
                this.baseSpread = 0.0075;
                this.recoilPerShot = 0.0135;
                this.moveSpeedMultiplier = 0.92;
                break;

            case M4A1S:
                this.displayName = "M4A1-S";
                this.slotNumber = 1;
                this.price = 2900;
                this.killReward = 300;
                this.automatic = true;
                this.melee = false;
                this.silenced = true;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 20;
                this.initialReserveAmmo = 80;
                this.fireInterval = 0.095;
                this.reloadDuration = 1.95;
                this.bodyDamage = 33;
                this.headDamage = 104;
                this.maxRange = 115.0;
                this.baseSpread = 0.0042;
                this.recoilPerShot = 0.0085;
                this.moveSpeedMultiplier = 0.94;
                break;

            case AWP:
                this.displayName = "AWP";
                this.slotNumber = 1;
                this.price = 4750;
                this.killReward = 100;
                this.automatic = false;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = true;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 5;
                this.initialReserveAmmo = 30;
                this.fireInterval = 1.15;
                this.reloadDuration = 2.85;
                this.bodyDamage = 148;
                this.headDamage = 440;
                this.maxRange = 160.0;
                this.baseSpread = 0.038;
                this.recoilPerShot = 0.035;
                this.moveSpeedMultiplier = 0.82;
                break;

            case M249:
                this.displayName = "M249 Para";
                this.slotNumber = 1;
                this.price = 5200;
                this.killReward = 300;
                this.automatic = true;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 100;
                this.initialReserveAmmo = 200;
                this.fireInterval = 0.078;
                this.reloadDuration = 3.60;
                this.bodyDamage = 35;
                this.headDamage = 108;
                this.maxRange = 125.0;
                this.baseSpread = 0.0085;
                this.recoilPerShot = 0.0095;
                this.moveSpeedMultiplier = 0.84;
                break;

            case RPG7:
                this.displayName = "RPG-7";
                this.slotNumber = 1;
                this.price = 4500;
                this.killReward = 300;
                this.automatic = false;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 1;
                this.initialReserveAmmo = 5;
                this.fireInterval = 1.10;
                this.reloadDuration = 2.45;
                this.bodyDamage = 260;
                this.headDamage = 320;
                this.maxRange = 160.0;
                this.baseSpread = 0.0025;
                this.recoilPerShot = 0.032;
                this.moveSpeedMultiplier = 0.85;
                break;

            case XM1014:
                this.displayName = "XM1014";
                this.slotNumber = 1;
                this.price = 2000;
                this.killReward = 900;
                this.automatic = true;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = false;
                this.pelletsPerShot = 8;
                this.maxMagAmmo = 7;
                this.initialReserveAmmo = 32;
                this.fireInterval = 0.34;
                this.reloadDuration = 2.10;
                this.bodyDamage = 20;
                this.headDamage = 52;
                this.maxRange = 46.0;
                this.baseSpread = 0.052;
                this.recoilPerShot = 0.018;
                this.moveSpeedMultiplier = 0.93;
                break;

            case MP9:
                this.displayName = "MP9";
                this.slotNumber = 1;
                this.price = 1250;
                this.killReward = 600;
                this.automatic = true;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 30;
                this.initialReserveAmmo = 120;
                this.fireInterval = 0.068;
                this.reloadDuration = 1.65;
                this.bodyDamage = 25;
                this.headDamage = 78;
                this.maxRange = 78.0;
                this.baseSpread = 0.0085;
                this.recoilPerShot = 0.0080;
                this.moveSpeedMultiplier = 1.04;
                break;

            case DEAGLE:
                this.displayName = "Desert Eagle";
                this.slotNumber = 2;
                this.price = 700;
                this.killReward = 300;
                this.automatic = false;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 7;
                this.initialReserveAmmo = 35;
                this.fireInterval = 0.27;
                this.reloadDuration = 1.85;
                this.bodyDamage = 58;
                this.headDamage = 186;
                this.maxRange = 98.0;
                this.baseSpread = 0.0055;
                this.recoilPerShot = 0.026;
                this.moveSpeedMultiplier = 0.97;
                break;

            case GLOCK:
                this.displayName = "Glock-18";
                this.slotNumber = 2;
                this.price = 200;
                this.killReward = 300;
                this.automatic = false;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 20;
                this.initialReserveAmmo = 120;
                this.fireInterval = 0.14;
                this.reloadDuration = 1.45;
                this.bodyDamage = 26;
                this.headDamage = 82;
                this.maxRange = 85.0;
                this.baseSpread = 0.006;
                this.recoilPerShot = 0.009;
                this.moveSpeedMultiplier = 1.00;
                break;

            case ZEUS:
                this.displayName = "Zeus x27";
                this.slotNumber = 4;
                this.price = 200;
                this.killReward = 300;
                this.automatic = false;
                this.melee = false;
                this.silenced = false;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 1;
                this.initialReserveAmmo = 0;
                this.fireInterval = 0.85;
                this.reloadDuration = 10.0;
                this.bodyDamage = 195;
                this.headDamage = 240;
                this.maxRange = 5.2;
                this.baseSpread = 0.002;
                this.recoilPerShot = 0.004;
                this.moveSpeedMultiplier = 1.08;
                break;

            case KNIFE:
            default:
                this.displayName = "Керамбит";
                this.slotNumber = 3;
                this.price = 0;
                this.killReward = 1500;
                this.automatic = true;
                this.melee = true;
                this.silenced = true;
                this.sniperScope = false;
                this.pelletsPerShot = 1;
                this.maxMagAmmo = 0;
                this.initialReserveAmmo = 0;
                this.fireInterval = 0.38;
                this.reloadDuration = 0.0;
                this.bodyDamage = 55;
                this.headDamage = 95;
                this.maxRange = 2.68;
                this.baseSpread = 0.0;
                this.recoilPerShot = 0.0;
                this.moveSpeedMultiplier = 1.15;
                break;
        }
        resetAmmo();
    }

    public void resetAmmo() {
        this.magAmmo = maxMagAmmo;
        this.reserveAmmo = initialReserveAmmo;
        this.fireCooldown = 0.0;
        this.reloading = false;
        this.reloadTimer = 0.0;
        this.recoilBloom = 0.0;
        this.recoilKick = 0.0;
        this.muzzleFlashTimer = 0.0;
        this.equipProgress = 1.0;
        this.slashTimer = 0.0;
        this.scopeZoomLevel = 0;
        this.adsActive = false;
        this.inspectTimer = 0.0;
    }

    public void refillReserveAmmo() {
        this.magAmmo = maxMagAmmo;
        this.reserveAmmo = initialReserveAmmo;
    }

    public void onEquip() {
        this.reloading = false;
        this.reloadTimer = 0.0;
        this.equipProgress = 0.0;
        this.scopeZoomLevel = 0;
        this.adsActive = false;
        this.inspectTimer = 0.0;
        this.fireCooldown = Math.max(this.fireCooldown, 0.16);
    }

    public void triggerInspect() {
        if (!reloading && scopeZoomLevel == 0) {
            inspectTimer = INSPECT_DURATION;
        }
    }

    public void toggleSecondaryAction() {
        if (reloading || melee) return;
        inspectTimer = 0.0;
        if (sniperScope) {
            scopeZoomLevel = (scopeZoomLevel + 1) % 3;
        } else {
            adsActive = !adsActive;
        }
    }

    public void cancelZoom() {
        scopeZoomLevel = 0;
        adsActive = false;
    }

    public double getEffectiveFovFactor() {
        if (sniperScope) {
            if (scopeZoomLevel == 1) return 0.34;
            if (scopeZoomLevel == 2) return 0.15;
        } else if (adsActive) {
            return 0.78;
        }
        return 1.0;
    }

    public void update(double dt) {
        if (fireCooldown > 0.0) fireCooldown = Math.max(0.0, fireCooldown - dt);
        if (muzzleFlashTimer > 0.0) muzzleFlashTimer = Math.max(0.0, muzzleFlashTimer - dt);
        if (slashTimer > 0.0) slashTimer = Math.max(0.0, slashTimer - dt);
        if (inspectTimer > 0.0) inspectTimer = Math.max(0.0, inspectTimer - dt);
        if (equipProgress < 1.0) equipProgress = Math.min(1.0, equipProgress + dt * 4.8);

        recoilBloom = Math.max(0.0, recoilBloom - dt * 0.058);
        recoilKick = Math.max(0.0, recoilKick - dt * (type == Type.AWP || type == Type.RPG7 ? 3.5 : 6.5));

        if (type == Type.ZEUS && magAmmo == 0 && !reloading) {
            reloading = true;
            reloadTimer = reloadDuration;
        } else if (type == Type.RPG7 && magAmmo == 0 && reserveAmmo > 0 && !reloading && fireCooldown <= 0.2) {
            reloading = true;
            reloadTimer = reloadDuration;
        }

        if (reloading) {
            reloadTimer -= dt;
            if (reloadTimer <= 0.0) {
                if (type == Type.ZEUS) {
                    magAmmo = 1;
                } else {
                    int needed = maxMagAmmo - magAmmo;
                    int transfer = Math.min(needed, reserveAmmo);
                    magAmmo += transfer;
                    reserveAmmo -= transfer;
                }
                reloading = false;
                reloadTimer = 0.0;
            }
        }
    }

    public boolean startReload() {
        if (melee || reloading || magAmmo >= maxMagAmmo || reserveAmmo <= 0) {
            return false;
        }
        cancelZoom();
        inspectTimer = 0.0;
        reloading = true;
        reloadTimer = reloadDuration;
        return true;
    }

    public boolean canFire() {
        if (reloading || fireCooldown > 0.0 || equipProgress < 0.75) {
            return false;
        }
        return melee || magAmmo > 0;
    }

    public void onFire(boolean altHeavyMelee) {
        inspectTimer = 0.0;
        if (melee) {
            heavySlash = altHeavyMelee;
            fireCooldown = altHeavyMelee ? 0.68 : fireInterval;
            slashTimer = altHeavyMelee ? 0.36 : 0.26;
            recoilKick = altHeavyMelee ? 0.85 : 0.52;
        } else {
            fireCooldown = fireInterval;
            magAmmo = Math.max(0, magAmmo - 1);
            recoilBloom = Math.min(0.075, recoilBloom + recoilPerShot);
            recoilKick = 1.0;
            muzzleFlashTimer = silenced ? 0.025 : 0.062;
            if (sniperScope && magAmmo > 0) {
                scopeZoomLevel = 0;
            }
        }
    }

    public double computeCurrentSpread(boolean moving, boolean silentWalking, boolean airborne, boolean crouching) {
        if (melee) return 0.0;
        double spread = baseSpread;
        if (sniperScope && scopeZoomLevel > 0) {
            spread = (scopeZoomLevel == 2) ? 0.0006 : 0.0014;
        } else if (adsActive) {
            spread *= 0.62;
        }
        spread += recoilBloom;

        if (moving) {
            double movePen = (type == Type.AWP) ? 0.065
                    : (type == Type.MP9 || type == Type.XM1014 ? 0.011 : 0.020);
            if (silentWalking) movePen *= 0.35;
            spread += movePen;
        }
        if (airborne) spread += 0.062;
        if (crouching) spread *= 0.52;
        return spread;
    }

    public Vec3 getMuzzleWorldPosition(Player player, boolean leftHanded) {
        Vec3 eye = player.getEyePosition();
        Vec3 forward = player.getLookDirection();
        Vec3 right = player.getRightVector().scale(leftHanded ? -1.0 : 1.0);
        double cp = Math.cos(player.getPitch());
        double sp = Math.sin(player.getPitch());
        double sy = Math.sin(player.getYaw());
        double cy = Math.cos(player.getYaw());
        Vec3 camUp = new Vec3(-sy * sp, cp, -cy * sp);

        double forwardDist = (type == Type.AWP || type == Type.M4A1S || type == Type.RPG7) ? 0.86 : (slotNumber == 1 ? 0.74 : 0.55);
        double sideDist = adsActive ? 0.06 : 0.21;
        return eye.add(forward.scale(forwardDist)).add(right.scale(sideDist)).add(camUp.scale(-0.14));
    }

    public void renderViewModel(Renderer3D renderer, double walkBobPhase, double walkSpeedNorm,
                                double swayX, double swayY) {
        renderViewModel(renderer, walkBobPhase, walkSpeedNorm, swayX, swayY, GameSettings.WeaponSkin.STANDARD);
    }

    public void renderViewModel(Renderer3D renderer, double walkBobPhase, double walkSpeedNorm,
                                double swayX, double swayY, GameSettings.WeaponSkin skin) {
        if (sniperScope && scopeZoomLevel > 0) {
            return;
        }

        double bobX = Math.sin(walkBobPhase) * 0.013 * walkSpeedNorm;
        double bobY = Math.abs(Math.cos(walkBobPhase)) * (-0.012) * walkSpeedNorm;
        double equipDrop = (1.0 - equipProgress) * 0.24;

        double rotX = -recoilKick * (type == Type.DEAGLE || type == Type.AWP || type == Type.RPG7 ? 0.23 : 0.13);
        double rotY = adsActive ? -0.015 : -0.065;
        double rotZ = Math.max(-0.08, Math.min(0.08, swayX * 1.6));
        double reloadDropY = 0.0;

        if (inspectTimer > 0.0) {
            double p = 1.0 - (inspectTimer / INSPECT_DURATION);
            double s1 = Math.sin(p * Math.PI);
            double s2 = Math.sin(p * Math.PI * 2.0);
            rotZ = -0.62 * s1 + 0.24 * s2;
            rotY = -0.065 + 0.52 * s1;
            rotX += 0.17 * s1;
        }

        if (reloading && reloadDuration > 0.0) {
            double progress = 1.0 - (reloadTimer / reloadDuration);
            double wave = Math.sin(progress * Math.PI);
            rotZ = 0.50 * wave;
            rotX = 0.25 * wave;
            reloadDropY = -0.085 * wave;
        }

        double adsShiftX = adsActive ? -0.155 : 0.0;
        double adsShiftY = adsActive ? 0.056 : 0.0;
        double adsShiftZ = adsActive ? -0.055 : 0.0;

        double ox = 0.225 + adsShiftX + bobX + swayX;
        double oy = -0.215 + adsShiftY + bobY + swayY - equipDrop + reloadDropY;
        double oz = 0.48 + adsShiftZ - recoilKick * 0.058;

        dispatchWeaponModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, true);
    }

    public void renderShowcaseModel(Renderer3D renderer, double spinYaw, GameSettings.WeaponSkin skin) {
        double angleY = 1.28 + spinYaw;
        double ox = 0.20;
        double oy = 0.05;
        double oz = (type == Type.AWP || type == Type.M249 || type == Type.RPG7) ? 0.92 : (slotNumber == 1 ? 0.72 : 0.52);
        double tiltX = 0.10 * Math.sin(spinYaw * 0.8);
        if (type == Type.KNIFE) {
            renderKnifeModel(renderer, ox, oy - 0.01, oz - 0.08, tiltX, angleY, 0.26, skin, false);
        } else {
            dispatchWeaponModel(renderer, ox, oy, oz, tiltX, angleY, 0.08, skin, false);
        }
    }

    private void dispatchWeaponModel(Renderer3D renderer, double ox, double oy, double oz,
                                     double rotX, double rotY, double rotZ,
                                     GameSettings.WeaponSkin skin, boolean drawHands) {
        switch (type) {
            case AK47:   renderAk47Model(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case M4A1S:  renderM4a1sModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case AWP:    renderAwpModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case M249:   renderM249Model(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case RPG7:   renderRpg7Model(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case XM1014: renderShotgunModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case MP9:    renderMp9Model(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case DEAGLE: renderDeagleModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case GLOCK:  renderGlockModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case ZEUS:   renderZeusModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case KNIFE:  renderKnifeModel(renderer, ox, oy, oz, 0, 0, 0, skin, drawHands); break;
        }
    }

    /**
     * Реалистичный двуручный хват спецназа CS2 (Specialist Tactical Gloves):
     * - Правая рука в рукаве рипстоп + карбоновая защита костяшек + большой и указательный палец на спуске!
     * - Левая рука с тактическими цифровыми часами на запястье поддерживает цевьё (или меняет магазин при перезарядке)!
     */
    private void drawTacticalHandsAndGloves(Renderer3D r, double ox, double oy, double oz,
                                            double gripZ, double supportZ, boolean twoHandPistol,
                                            double rotX, double rotY, double rotZ, GameSettings.WeaponSkin skin) {
        int sleeveFabric = 0x243447; // Тёмно-синяя боевая рубаха спецназа SAS
        int cuffStrap    = 0x18202A; // Неопреновая манжета с липучкой
        int gloveBody    = 0x1D222B; // Кевлар-кожаная тактическая перчатка Oakley / Specialist
        int knucklePlate = (skin != GameSettings.WeaponSkin.STANDARD) ? skin.accent : 0x384454;
        int fingerLeather= 0x151920;

        // 1. ПРАВАЯ РУКА (на пистолетной рукояти и спусковом крючке)
        r.drawViewModelCylinder(ox + 0.032, oy - 0.135, oz + gripZ - 0.16, 0.044, 0.037, 0.25,
                rotX + 0.18, rotY - 0.12, rotZ, ox, oy, oz, 12, sleeveFabric);
        r.drawViewModelCylinder(ox + 0.014, oy - 0.092, oz + gripZ - 0.045, 0.038, 0.035, 0.042,
                rotX + 0.16, rotY - 0.08, rotZ, ox, oy, oz, 10, cuffStrap);
        // Ладонь + карбоновый щиток костяшек + пальцы вокруг рукояти
        r.drawViewModelBox(ox + 0.012, oy - 0.068, oz + gripZ + 0.005, 0.042, 0.056, 0.068,
                rotX + 0.18, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, gloveBody);
        r.drawViewModelBox(ox + 0.026, oy - 0.054, oz + gripZ + 0.008, 0.018, 0.028, 0.052,
                rotX + 0.18, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, knucklePlate);
        // Большой палец сверху и указательный палец на спусковой скобе!
        r.drawViewModelCylinder(ox - 0.010, oy - 0.036, oz + gripZ + 0.018, 0.011, 0.009, 0.046,
                rotX - 0.10, rotY + 0.35, rotZ, ox, oy, oz, 8, fingerLeather);
        r.drawViewModelCylinder(ox + 0.014, oy - 0.040, oz + gripZ + 0.048, 0.009, 0.008, 0.044,
                rotX, rotY - 0.18, rotZ, ox, oy, oz, 8, fingerLeather);

        // 2. ЛЕВАЯ ПОДДЕРЖИВАЮЩАЯ РУКА + ТАКТИЧЕСКИЕ ЧАСЫ С ПОДСВЕТКОЙ!
        if (supportZ > -0.5) {
            double reloadPullZ = 0.0;
            double reloadPullY = 0.0;
            if (reloading && reloadDuration > 0.0 && !twoHandPistol) {
                double p = 1.0 - (reloadTimer / reloadDuration);
                double magWave = Math.sin(p * Math.PI);
                reloadPullZ = -0.11 * magWave;
                reloadPullY = -0.075 * magWave;
            }
            double sz = oz + supportZ + reloadPullZ;
            double sy = oy + reloadPullY;

            // Предплечье левой руки, уходящее влево-вниз к корпусу игрока
            r.drawViewModelCylinder(ox - 0.135, sy - 0.105, sz - 0.13, 0.043, 0.036, 0.28,
                    rotX + 0.22, rotY + 0.44, rotZ, ox, oy, oz, 12, sleeveFabric);
            // Тактические цифровые часы спецназа на левом запястье (корпус + светящийся бирюзовый циферблат!)
            r.drawViewModelCylinder(ox - 0.058, sy - 0.052, sz - 0.025, 0.037, 0.036, 0.034,
                    rotX + 0.18, rotY + 0.36, rotZ, ox, oy, oz, 10, cuffStrap);
            r.drawViewModelBox(ox - 0.052, sy - 0.022, sz - 0.022, 0.030, 0.014, 0.028,
                    rotX + 0.18, rotY + 0.36, rotZ + 0.32, ox, oy, oz, Renderer3D.TEX_SOLID, 0x161B22);
            r.drawViewModelBox(ox - 0.050, sy - 0.015, sz - 0.022, 0.022, 0.006, 0.020,
                    rotX + 0.18, rotY + 0.36, rotZ + 0.32, ox, oy, oz, Renderer3D.TEX_SOLID, 0x3CF2D8);

            // Кисть левой перчатки, обхватывающая цевьё снизу и слева (C-Clamp хват!)
            double handY = twoHandPistol ? (sy - 0.064) : (sy - 0.020);
            r.drawViewModelBox(ox - 0.018, handY, sz + 0.018, 0.046, 0.042, 0.072,
                    rotX, rotY + 0.12, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, gloveBody);
            r.drawViewModelBox(ox - 0.034, handY + 0.004, sz + 0.018, 0.016, 0.028, 0.054,
                    rotX, rotY + 0.12, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, knucklePlate);
            if (!twoHandPistol) {
                // Большой палец левой руки лежит поверх цевья!
                r.drawViewModelCylinder(ox - 0.016, handY + 0.030, sz + 0.026, 0.010, 0.009, 0.048,
                        rotX, rotY - 0.12, rotZ, ox, oy, oz, 8, fingerLeather);
            }
        }
    }

    private void renderAk47Model(Renderer3D r, double ox, double oy, double oz,
                                 double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int wood      = custom ? skin.accent  : 0x9C5226;
        int darkWood  = custom ? skin.trim    : 0x733818;
        int steel     = custom ? skin.primary : 0x323840;
        int darkSteel = 0x1C2026;
        int boltMetal = 0x9CA6B2;
        int magColor  = custom ? skin.accent  : 0xA84E20; // Классический бакелитовый рыжий магазин 7.62!

        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.03, 0.19, false, rotX, rotY, rotZ, skin);

        // Деревянный приклад со стальным затыльником и пистолетная рукоять + спусковая скоба
        r.drawViewModelBox(ox, oy - 0.028, oz - 0.185, 0.042, 0.082, 0.21, rotX - 0.06, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkWood);
        r.drawViewModelBox(ox, oy - 0.034, oz - 0.292, 0.044, 0.086, 0.016, rotX - 0.06, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox, oy - 0.086, oz - 0.042, 0.034, 0.098, 0.044, rotX + 0.24, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkWood);
        r.drawViewModelBox(ox, oy - 0.044, oz + 0.005, 0.016, 0.028, 0.048, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);

        // Штампованная ствольная коробка + ребристая крышка + переводчик огня + затворная рама!
        r.drawViewModelBox(ox, oy + 0.010, oz + 0.015, 0.048, 0.054, 0.245, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, steel);
        r.drawViewModelCylinder(ox, oy + 0.038, oz + 0.005, 0.023, 0.021, 0.225, rotX, rotY, rotZ, ox, oy, oz, 10, custom ? skin.primary : 0x282E36);
        if (custom) {
            r.drawViewModelBox(ox, oy + 0.022, oz + 0.02, 0.051, 0.016, 0.20, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, skin.accent);
        }
        // Подвижная рукоять затвора (отскакивает назад при каждом выстреле!)
        r.drawViewModelCylinder(ox + 0.028, oy + 0.026, oz + 0.035 - recoilKick * 0.045, 0.008, 0.008, 0.042, rotX, rotY + 1.4, rotZ, ox, oy, oz, 8, boltMetal);
        // Целик с прицельной планкой (виден прямо по центру при прицеливании ПКМ!)
        r.drawViewModelBox(ox, oy + 0.056, oz + 0.115, 0.024, 0.016, 0.044, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);

        // Изогнутый рожковый магазин 7.62×39 из 2 сегментов (реалистичный изгиб «банана» + анимация смены!)
        double magPull = 0.0;
        if (reloading && reloadDuration > 0.0) {
            double p = 1.0 - (reloadTimer / reloadDuration);
            magPull = Math.sin(p * Math.PI) * 0.14;
        }
        r.drawViewModelBox(ox, oy - 0.068 - magPull, oz + 0.078, 0.033, 0.088, 0.054, rotX - 0.18, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, magColor);
        r.drawViewModelBox(ox, oy - 0.138 - magPull, oz + 0.104, 0.032, 0.086, 0.052, rotX - 0.42, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, magColor);

        // Деревянное цевьё и ствольная накладка с вентиляционными окнами
        r.drawViewModelBox(ox, oy + 0.004, oz + 0.215, 0.044, 0.046, 0.165, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, wood);
        r.drawViewModelCylinder(ox, oy + 0.036, oz + 0.205, 0.019, 0.018, 0.145, rotX, rotY, rotZ, ox, oy, oz, 10, darkWood);

        // Ствол, газоотводная камора под углом, шомпол под стволом, круглая мушка с кольцом и косой компенсатор!
        r.drawViewModelCylinder(ox, oy + 0.015, oz + 0.415, 0.0105, 0.010, 0.29, rotX, rotY, rotZ, ox, oy, oz, 12, darkSteel);
        r.drawViewModelCylinder(ox, oy + 0.002, oz + 0.405, 0.004, 0.004, 0.24, rotX, rotY, rotZ, ox, oy, oz, 6, boltMetal); // Шомпол!
        r.drawViewModelCylinder(ox, oy + 0.034, oz + 0.325, 0.010, 0.009, 0.11, rotX, rotY, rotZ, ox, oy, oz, 10, steel);
        r.drawViewModelBox(ox, oy + 0.026, oz + 0.375, 0.016, 0.032, 0.026, rotX + 0.45, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel); // Газоотвод!
        // Знаменитая мушка АК-47 с защитным кольцом
        r.drawViewModelBox(ox, oy + 0.038, oz + 0.505, 0.012, 0.038, 0.022, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelCylinder(ox, oy + 0.052, oz + 0.505, 0.013, 0.013, 0.016, rotX, rotY, rotZ, ox, oy, oz, 10, darkSteel);
        r.drawViewModelCylinder(ox, oy + 0.015, oz + 0.562, 0.012, 0.011, 0.032, rotX, rotY, rotZ, ox, oy, oz, 10, darkSteel);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.016, oz + 0.59, 0.145);
    }

    private void renderM4a1sModel(Renderer3D r, double ox, double oy, double oz,
                                  double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int bodyBlack  = custom ? skin.trim    : 0x222830;
        int railGray   = custom ? skin.primary : 0x3A434F;
        int suppressor = custom ? skin.accent  : 0x181C22;
        int magGray    = custom ? skin.accent  : 0x36404A;
        int peqTan     = 0x6E604A;

        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.025, 0.21, false, rotX, rotY, rotZ, skin);

        // Телескопический приклад SOPMOD + буферная труба + пистолетная рукоять и спусковая скоба
        r.drawViewModelCylinder(ox, oy + 0.010, oz - 0.165, 0.020, 0.019, 0.19, rotX, rotY, rotZ, ox, oy, oz, 10, bodyBlack);
        r.drawViewModelBox(ox, oy - 0.018, oz - 0.205, 0.044, 0.088, 0.12, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyBlack);
        r.drawViewModelBox(ox, oy - 0.084, oz - 0.028, 0.034, 0.096, 0.042, rotX + 0.22, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyBlack);
        r.drawViewModelBox(ox, oy - 0.042, oz + 0.016, 0.016, 0.026, 0.046, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyBlack);

        // Верхний и нижний кованый ресивер M4A1 + шторка выброса гильз + досылатель затвора
        r.drawViewModelBox(ox, oy + 0.014, oz + 0.032, 0.046, 0.066, 0.235, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, railGray);
        r.drawViewModelBox(ox + 0.024, oy + 0.022, oz + 0.055, 0.006, 0.018, 0.068, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x161A20);
        r.drawViewModelBox(ox, oy + 0.050, oz + 0.11, 0.032, 0.010, 0.36, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyBlack); // Планка Пикатинни!

        // Голографический коллиматорный прицел EOTech с защитным кожухом и светящейся красной маркой!
        r.drawViewModelBox(ox, oy + 0.072, oz + 0.028, 0.042, 0.036, 0.086, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyBlack);
        r.drawViewModelBox(ox, oy + 0.075, oz + 0.028, 0.030, 0.024, 0.012, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x24485E);
        r.drawViewModelBox(ox, oy + 0.076, oz + 0.020, 0.006, 0.006, 0.008, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0xFF342E);

        // Изогнутый магазин STANAG 5.56
        double magPull = 0.0;
        if (reloading && reloadDuration > 0.0) {
            magPull = Math.sin((1.0 - reloadTimer / reloadDuration) * Math.PI) * 0.13;
        }
        r.drawViewModelBox(ox, oy - 0.072 - magPull, oz + 0.068, 0.030, 0.095, 0.052, rotX - 0.08, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, magGray);
        r.drawViewModelBox(ox, oy - 0.132 - magPull, oz + 0.078, 0.029, 0.065, 0.050, rotX - 0.20, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, magGray);

        // Перфорированное тактическое цевьё M-LOK + лазерный блок AN/PEQ-15 сбоку + двухступенчатый глушитель Knights!
        r.drawViewModelCylinder(ox, oy + 0.018, oz + 0.235, 0.025, 0.024, 0.195, rotX, rotY, rotZ, ox, oy, oz, 12, railGray);
        r.drawViewModelBox(ox + 0.026, oy + 0.024, oz + 0.255, 0.022, 0.026, 0.078, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, custom ? skin.accent : peqTan);
        r.drawViewModelCylinder(ox, oy + 0.018, oz + 0.355, 0.014, 0.014, 0.06, rotX, rotY, rotZ, ox, oy, oz, 12, bodyBlack);
        r.drawViewModelCylinder(ox, oy + 0.018, oz + 0.505, 0.0195, 0.0195, 0.27, rotX, rotY, rotZ, ox, oy, oz, 16, suppressor);
        r.drawViewModelCylinder(ox, oy + 0.018, oz + 0.415, 0.021, 0.021, 0.032, rotX, rotY, rotZ, ox, oy, oz, 14, bodyBlack); // Накатка глушителя!

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.018, oz + 0.65, 0.075);
    }

    private void renderAwpModel(Renderer3D r, double ox, double oy, double oz,
                                double rotX, double rotY, double rotZ,
                                GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int awpBody    = custom ? skin.primary : 0x3A5436; // Классический британский зелёный полимер Accuracy International!
        int awpStripe  = custom ? skin.accent  : 0x2B3F28;
        int darkSteel  = 0x1E232A;
        int scopeMetal = custom ? skin.trim    : 0x151920;
        int lensEmerald= 0x3CE8B5;

        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.02, 0.22, false, rotX, rotY, rotZ, skin);

        // Ложа с вырезом под большой палец, регулируемой щекой приклада и резиновым затыльником
        r.drawViewModelBox(ox, oy - 0.022, oz - 0.165, 0.046, 0.088, 0.24, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, awpBody);
        r.drawViewModelBox(ox, oy + 0.028, oz - 0.155, 0.038, 0.024, 0.12, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, scopeMetal); // Щека приклада!
        r.drawViewModelBox(ox, oy + 0.004, oz + 0.105, 0.048, 0.068, 0.34, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, awpBody);
        r.drawViewModelBox(ox, oy + 0.020, oz + 0.115, 0.051, 0.018, 0.28, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, awpStripe);
        // Стальной коробчатый магазин .338 Lapua Magnum
        r.drawViewModelBox(ox, oy - 0.056, oz + 0.065, 0.034, 0.068, 0.088, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);

        // Продольно-скользящий затвор с шариком рукояти (поднимается при перезарядке затвора после выстрела!)
        double boltLift = (fireCooldown > 0.45) ? Math.sin(((fireCooldown - 0.45) / 0.70) * Math.PI) * 0.55 : 0.0;
        r.drawViewModelCylinder(ox + 0.032, oy + 0.024 + boltLift * 0.02, oz + 0.01 - boltLift * 0.04, 0.006, 0.012, 0.042, rotX, rotY + 1.35, rotZ + boltLift, ox, oy, oz, 8, darkSteel);

        // Кольца крепления + телескопический прицел Schmidt & Bender 8×50 с барабанчиками поправок и изумрудной линзой!
        r.drawViewModelBox(ox, oy + 0.056, oz + 0.03, 0.024, 0.030, 0.026, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox, oy + 0.056, oz + 0.165, 0.024, 0.030, 0.026, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelCylinder(ox, oy + 0.086, oz + 0.095, 0.019, 0.019, 0.22, rotX, rotY, rotZ, ox, oy, oz, 14, scopeMetal);
        r.drawViewModelCylinder(ox, oy + 0.086, oz + 0.235, 0.019, 0.028, 0.075, rotX, rotY, rotZ, ox, oy, oz, 14, scopeMetal);
        r.drawViewModelCylinder(ox, oy + 0.086, oz - 0.025, 0.022, 0.019, 0.045, rotX, rotY, rotZ, ox, oy, oz, 14, scopeMetal);
        r.drawViewModelCylinder(ox, oy + 0.086, oz - 0.048, 0.018, 0.018, 0.008, rotX, rotY, rotZ, ox, oy, oz, 12, lensEmerald);
        // Барабанчики вертикальных и боковых поправок на оптике
        r.drawViewModelBox(ox, oy + 0.110, oz + 0.095, 0.018, 0.018, 0.018, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox + 0.024, oy + 0.086, oz + 0.095, 0.016, 0.018, 0.018, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);

        // Сложенные снайперские сошки под цевьём + длинный каннелированный ствол + двухкамерный ДТК!
        r.drawViewModelCylinder(ox - 0.020, oy - 0.024, oz + 0.34, 0.007, 0.007, 0.16, rotX, rotY, rotZ, ox, oy, oz, 8, darkSteel);
        r.drawViewModelCylinder(ox + 0.020, oy - 0.024, oz + 0.34, 0.007, 0.007, 0.16, rotX, rotY, rotZ, ox, oy, oz, 8, darkSteel);
        r.drawViewModelCylinder(ox, oy + 0.022, oz + 0.465, 0.0125, 0.011, 0.43, rotX, rotY, rotZ, ox, oy, oz, 12, darkSteel);
        r.drawViewModelBox(ox, oy + 0.022, oz + 0.695, 0.028, 0.024, 0.058, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, scopeMetal);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.022, oz + 0.735, 0.195);
    }

    private void renderM249Model(Renderer3D r, double ox, double oy, double oz,
                                 double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int bodyGunmetal = custom ? skin.primary : 0x2D343E;
        int heatShield   = custom ? skin.accent  : 0x434D5B;
        int ammoBoxGreen = custom ? skin.trim    : 0x334E36;
        int brassCase    = 0xE6B83E;
        int copperTip    = 0xC86D3B;

        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.02, 0.24, false, rotX, rotY, rotZ, skin);

        // Складной рамочный приклад Para, пистолетная рукоять, массивная ствольная коробка и крышка лентоприёмника
        r.drawViewModelBox(ox, oy - 0.016, oz - 0.175, 0.048, 0.086, 0.19, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x1A1E24);
        r.drawViewModelBox(ox, oy - 0.082, oz - 0.025, 0.036, 0.096, 0.044, rotX + 0.20, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x1A1E24);
        r.drawViewModelBox(ox, oy + 0.018, oz + 0.06, 0.064, 0.080, 0.30, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyGunmetal);
        r.drawViewModelBox(ox, oy + 0.064, oz + 0.045, 0.054, 0.018, 0.23, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, heatShield);
        r.drawViewModelBox(ox, oy + 0.080, oz - 0.035, 0.028, 0.018, 0.024, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x161A20); // Целик!

        // Зелёный короб на 100 патронов + детализированная пулемётная лента с отдельными латунными патронами 5.56!
        if (!reloading || reloadTimer < reloadDuration * 0.42) {
            r.drawViewModelBox(ox - 0.024, oy - 0.086, oz + 0.065, 0.098, 0.108, 0.118, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, ammoBoxGreen);
            double beltJiggle = (muzzleFlashTimer > 0.0) ? 0.008 : 0.0;
            for (int b = 0; b < 5; b++) {
                double by = oy - 0.026 + b * 0.014 + beltJiggle;
                double bx = ox - 0.052 + b * 0.005;
                r.drawViewModelCylinder(bx, by, oz + 0.052 + (b % 2) * 0.004, 0.0045, 0.0042, 0.038, rotX, rotY + 1.52, rotZ, ox, oy, oz, 6, brassCase);
                r.drawViewModelCylinder(bx + 0.018, by, oz + 0.052, 0.0040, 0.0018, 0.014, rotX, rotY + 1.52, rotZ, ox, oy, oz, 6, copperTip);
            }
        }

        // Перфорированный кожух, рукоятка переноски ствола, газовый регулятор и пламегаситель!
        r.drawViewModelBox(ox, oy + 0.014, oz + 0.285, 0.056, 0.062, 0.20, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, heatShield);
        r.drawViewModelCylinder(ox + 0.034, oy + 0.062, oz + 0.20, 0.008, 0.008, 0.075, rotX, rotY, rotZ, ox, oy, oz, 8, 0x1A1E24); // Рукоять смены ствола!
        r.drawViewModelCylinder(ox, oy + 0.030, oz + 0.465, 0.014, 0.013, 0.35, rotX, rotY, rotZ, ox, oy, oz, 12, 0x1A1E24);
        r.drawViewModelCylinder(ox, oy + 0.002, oz + 0.425, 0.011, 0.011, 0.25, rotX, rotY, rotZ, ox, oy, oz, 10, bodyGunmetal);
        r.drawViewModelBox(ox, oy + 0.054, oz + 0.56, 0.018, 0.032, 0.020, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x1A1E24);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.030, oz + 0.655, 0.185);
    }

    private void renderRpg7Model(Renderer3D r, double ox, double oy, double oz,
                                 double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int tubeSteel    = custom ? skin.primary : 0x384036;
        int woodWrap     = custom ? skin.accent  : 0x8A4822;
        int warheadOlive = custom ? skin.trim    : 0x4B6038;

        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, 0.04, 0.22, false, rotX, rotY, rotZ, skin);

        // Деревянные накладки теплоизоляции, стальная пусковая труба, раструб и ДВЕ рукояти РПГ-7!
        r.drawViewModelCylinder(ox, oy + 0.025, oz - 0.12, 0.034, 0.027, 0.28, rotX, rotY, rotZ, ox, oy, oz, 14, woodWrap);
        r.drawViewModelCylinder(ox, oy + 0.025, oz + 0.18, 0.026, 0.027, 0.36, rotX, rotY, rotZ, ox, oy, oz, 14, tubeSteel);
        r.drawViewModelCylinder(ox, oy + 0.025, oz + 0.37, 0.027, 0.038, 0.055, rotX, rotY, rotZ, ox, oy, oz, 14, 0x202620);
        r.drawViewModelBox(ox, oy - 0.062, oz + 0.05, 0.032, 0.105, 0.042, rotX + 0.18, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, woodWrap);
        r.drawViewModelBox(ox, oy - 0.058, oz + 0.21, 0.030, 0.095, 0.038, rotX + 0.12, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x24282E);
        // Оптический прицел ПГО-7В слева и откидная рамочная мушка!
        r.drawViewModelBox(ox - 0.032, oy + 0.068, oz + 0.11, 0.026, 0.042, 0.078, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x242A30);
        r.drawViewModelBox(ox, oy + 0.072, oz + 0.30, 0.018, 0.046, 0.022, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x1E2226);

        // Наддульная кумулятивная граната ПГ-7В с пьезовзрывателем!
        if (magAmmo > 0 && !reloading) {
            r.drawViewModelCylinder(ox, oy + 0.025, oz + 0.455, 0.022, 0.054, 0.135, rotX, rotY, rotZ, ox, oy, oz, 14, warheadOlive);
            r.drawViewModelCylinder(ox, oy + 0.025, oz + 0.575, 0.054, 0.011, 0.145, rotX, rotY, rotZ, ox, oy, oz, 14, warheadOlive);
            r.drawViewModelCylinder(ox, oy + 0.025, oz + 0.660, 0.011, 0.006, 0.042, rotX, rotY, rotZ, ox, oy, oz, 10, 0xCFD6DE);
        }

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.025, oz + 0.44, 0.24);
    }

    private void renderShotgunModel(Renderer3D r, double ox, double oy, double oz,
                                    double rotX, double rotY, double rotZ,
                                    GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int polyDark       = custom ? skin.trim    : 0x22262C;
        int receiverSilver = custom ? skin.primary : 0x747F8C;
        int forendCol      = custom ? skin.accent  : 0x262B32;
        int barrelSteel    = 0x282E36;
        int shellRed       = 0xD22E2E;

        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.03, 0.22, false, rotX, rotY, rotZ, skin);

        // Тактический приклад Benelli M4, анодированный ресивер с планкой Пикатинни и патронташ на 3 красных патрона 12 калибра!
        r.drawViewModelBox(ox, oy - 0.025, oz - 0.165, 0.044, 0.084, 0.20, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, polyDark);
        r.drawViewModelBox(ox, oy - 0.080, oz - 0.035, 0.034, 0.090, 0.042, rotX + 0.22, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, polyDark);
        r.drawViewModelBox(ox, oy + 0.015, oz + 0.04, 0.050, 0.068, 0.245, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, receiverSilver);
        r.drawViewModelBox(ox, oy + 0.054, oz + 0.03, 0.026, 0.012, 0.18, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, polyDark);
        // 3 красных дробовых патрона с латунным донцем на боку ствольной коробки!
        for (int s = 0; s < 3; s++) {
            double sz = oz + 0.005 + s * 0.042;
            r.drawViewModelCylinder(ox - 0.029, oy + 0.018, sz, 0.008, 0.008, 0.032, rotX + 1.57, rotY, rotZ, ox, oy, oz, 8, shellRed);
            r.drawViewModelCylinder(ox - 0.029, oy + 0.036, sz, 0.0085, 0.0085, 0.008, rotX + 1.57, rotY, rotZ, ox, oy, oz, 8, 0xE2B640);
        }

        // Ребристое цевьё + ствол, трубчатый магазин и мушка Ghost Ring!
        r.drawViewModelCylinder(ox, oy - 0.006, oz + 0.235, 0.026, 0.024, 0.185, rotX, rotY, rotZ, ox, oy, oz, 10, forendCol);
        r.drawViewModelCylinder(ox, oy + 0.032, oz + 0.37, 0.013, 0.013, 0.43, rotX, rotY, rotZ, ox, oy, oz, 12, barrelSteel);
        r.drawViewModelCylinder(ox, oy + 0.004, oz + 0.35, 0.012, 0.012, 0.37, rotX, rotY, rotZ, ox, oy, oz, 12, polyDark);
        r.drawViewModelBox(ox, oy + 0.052, oz + 0.555, 0.014, 0.022, 0.018, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, polyDark);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.032, oz + 0.59, 0.185);
    }

    private void renderMp9Model(Renderer3D r, double ox, double oy, double oz,
                                double rotX, double rotY, double rotZ,
                                GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int bodyCol   = custom ? skin.primary : 0x343C46;
        int accentCol = custom ? skin.accent  : 0x4E5B6B;
        int darkCol   = custom ? skin.trim    : 0x1C2026;

        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, 0.00, 0.12, false, rotX, rotY, rotZ, skin);

        // Рамочный приклад B&T MP9, полимерный корпус, микро-коллиматор Aimpoint, передняя рукоять и длинный магазин!
        r.drawViewModelBox(ox, oy + 0.014, oz - 0.145, 0.022, 0.048, 0.18, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkCol);
        r.drawViewModelBox(ox, oy + 0.022, oz + 0.045, 0.048, 0.066, 0.255, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyCol);
        r.drawViewModelBox(ox, oy + 0.058, oz + 0.045, 0.032, 0.014, 0.23, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, accentCol);
        // Компактный коллиматор Micro T-2 сверху с зелёной точкой!
        r.drawViewModelCylinder(ox, oy + 0.078, oz + 0.035, 0.016, 0.016, 0.058, rotX, rotY, rotZ, ox, oy, oz, 10, darkCol);
        r.drawViewModelBox(ox, oy + 0.078, oz + 0.010, 0.005, 0.005, 0.006, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, 0x48FF88);

        r.drawViewModelBox(ox, oy - 0.072, oz + 0.01, 0.035, 0.122, 0.044, rotX + 0.10, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkCol);
        if (!reloading || reloadTimer > reloadDuration * 0.62 || reloadTimer < reloadDuration * 0.28) {
            r.drawViewModelBox(ox, oy - 0.142, oz + 0.02, 0.025, 0.165, 0.032, rotX + 0.06, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, custom ? accentCol : 0x625842);
        }
        r.drawViewModelCylinder(ox, oy - 0.048, oz + 0.135, 0.015, 0.014, 0.085, rotX + 1.45, rotY, rotZ, ox, oy, oz, 8, darkCol);
        r.drawViewModelCylinder(ox, oy + 0.022, oz + 0.235, 0.015, 0.014, 0.155, rotX, rotY, rotZ, ox, oy, oz, 12, darkCol);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.022, oz + 0.32, 0.11);
    }

    private void renderDeagleModel(Renderer3D r, double ox, double oy, double oz,
                                   double rotX, double rotY, double rotZ,
                                   GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int chrome     = custom ? skin.primary : 0xD2DCE6;
        int darkChrome = custom ? skin.accent  : 0x8E9AA8;
        int gripBlack  = custom ? skin.trim    : 0x1A1D22;

        double gx = ox - 0.02, gy = oy + 0.01, gz = oz - 0.035;
        double slideBack = recoilKick * 0.062;

        if (drawHands) drawTacticalHandsAndGloves(r, gx, gy, gz, 0.00, 0.01, true, rotX, rotY, rotZ, skin);

        // Прорезиненная рукоять .50 AE, спусковая скоба, курок, массивный гранёный затвор и тритиевые прицельные точки!
        r.drawViewModelBox(gx, gy - 0.058, gz + 0.00, 0.039, 0.114, 0.052, rotX + 0.20, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, gripBlack);
        r.drawViewModelBox(gx, gy - 0.018, gz + 0.052, 0.016, 0.026, 0.046, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, darkChrome);
        r.drawViewModelBox(gx, gy + 0.006, gz + 0.092, 0.040, 0.030, 0.235, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, darkChrome);
        // Неподвижный треугольный кожух ствола спереди + откатывающийся затвор сзади!
        r.drawViewModelBox(gx, gy + 0.038, gz + 0.155, 0.044, 0.046, 0.115, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, chrome);
        r.drawViewModelBox(gx, gy + 0.038, gz + 0.032 - slideBack, 0.045, 0.045, 0.135, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, chrome);
        r.drawViewModelCylinder(gx, gy + 0.036, gz + 0.12, 0.012, 0.012, 0.16, rotX, rotY, rotZ, gx, gy, gz, 10, 0x242830);
        // Светящиеся зелёные тритиевые точки целика и мушки!
        r.drawViewModelBox(gx - 0.009, gy + 0.064, gz - 0.028 - slideBack, 0.006, 0.008, 0.010, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, 0x48FF88);
        r.drawViewModelBox(gx + 0.009, gy + 0.064, gz - 0.028 - slideBack, 0.006, 0.008, 0.010, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, 0x48FF88);
        r.drawViewModelBox(gx, gy + 0.064, gz + 0.202, 0.007, 0.010, 0.014, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, 0x48FF88);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, gx, gy + 0.038, gz + 0.245, 0.155);
    }

    private void renderGlockModel(Renderer3D r, double ox, double oy, double oz,
                                  double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int frameBlack = custom ? skin.trim    : 0x1B1E24;
        int slideSteel = custom ? skin.primary : 0x424A55;
        int barrelDark = custom ? skin.accent  : 0x242830;

        double gx = ox - 0.02, gy = oy + 0.01, gz = oz - 0.045;
        double slideBack = recoilKick * 0.048;

        if (drawHands) drawTacticalHandsAndGloves(r, gx, gy, gz, 0.00, 0.01, true, rotX, rotY, rotZ, skin);

        // Полимерная рамка Glock-18 + спусковая скоба + тактический подствольный фонарь SureFire + затвор с насечками!
        r.drawViewModelBox(gx, gy - 0.055, gz + 0.00, 0.035, 0.106, 0.046, rotX + 0.20, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, frameBlack);
        r.drawViewModelBox(gx, gy - 0.016, gz + 0.048, 0.014, 0.024, 0.042, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, frameBlack);
        r.drawViewModelBox(gx, gy + 0.002, gz + 0.068, 0.036, 0.026, 0.192, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, frameBlack);
        r.drawViewModelCylinder(gx, gy - 0.014, gz + 0.128, 0.012, 0.013, 0.072, rotX, rotY, rotZ, gx, gy, gz, 10, 0x28303A); // Подствольный фонарь!
        r.drawViewModelBox(gx, gy + 0.030, gz + 0.065 - slideBack, 0.038, 0.035, 0.196, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, slideSteel);
        r.drawViewModelCylinder(gx, gy + 0.028, gz + 0.148, 0.0095, 0.0095, 0.095, rotX, rotY, rotZ, gx, gy, gz, 10, barrelDark);
        r.drawViewModelBox(gx, gy + 0.051, gz - 0.022 - slideBack, 0.022, 0.009, 0.010, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, 0xEDF4FA);
        r.drawViewModelBox(gx, gy + 0.051, gz + 0.152 - slideBack, 0.007, 0.010, 0.012, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, 0x48FF88);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, gx, gy + 0.028, gz + 0.215, 0.105);
    }

    private void renderZeusModel(Renderer3D r, double ox, double oy, double oz,
                                 double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int bodyDark     = custom ? skin.trim    : 0x1C2026;
        int hazardYellow = custom ? skin.accent  : 0xF2BE22;
        int steel        = custom ? skin.primary : 0x8A96A4;
        int chargeLed    = (magAmmo > 0) ? 0x2AF2FF : 0xFF4436;

        double zx = ox - 0.02, zy = oy + 0.01, zz = oz - 0.04;
        if (drawHands) drawTacticalHandsAndGloves(r, zx, zy, zz, 0.00, 0.01, true, rotX, rotY, rotZ, skin);

        r.drawViewModelBox(zx, zy - 0.055, zz + 0.00, 0.038, 0.106, 0.050, rotX + 0.22, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, bodyDark);
        r.drawViewModelBox(zx, zy + 0.018, zz + 0.065, 0.050, 0.062, 0.19, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, bodyDark);
        r.drawViewModelBox(zx, zy + 0.024, zz + 0.088, 0.054, 0.026, 0.135, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, hazardYellow);
        r.drawViewModelBox(zx, zy + 0.053, zz + 0.045, 0.026, 0.010, 0.075, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, chargeLed);
        r.drawViewModelCylinder(zx - 0.014, zy + 0.026, zz + 0.18, 0.005, 0.004, 0.055, rotX, rotY, rotZ, zx, zy, zz, 8, steel);
        r.drawViewModelCylinder(zx + 0.014, zy + 0.026, zz + 0.18, 0.005, 0.004, 0.055, rotX, rotY, rotZ, zx, zy, zz, 8, steel);

        if (muzzleFlashTimer > 0.0 && drawHands) {
            r.drawViewModelBox(zx, zy + 0.026, zz + 0.21, 0.095, 0.095, 0.04, 0.0, 0.0, 0.4, zx, zy, zz, Renderer3D.TEX_SOLID, 0x48F5FF);
        }
    }

    private void renderKnifeModel(Renderer3D r, double ox, double oy, double oz,
                                  double overrideRotX, double overrideRotY, double overrideRotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        double dur = heavySlash ? 0.36 : 0.26;
        double slashPhase = (slashTimer > 0.0) ? (1.0 - slashTimer / dur) : 0.0;
        double swing = Math.sin(slashPhase * Math.PI);

        double inspectSpin = 0.0;
        if (inspectTimer > 0.0) {
            double p = 1.0 - inspectTimer / INSPECT_DURATION;
            inspectSpin = p * Math.PI * 4.0; // Двойной оборот Керамбита на указательном пальце [F]!
        }

        double kx = ox - 0.02 - swing * (heavySlash ? 0.11 : 0.19);
        double ky = oy - 0.01 + swing * 0.06;
        double kz = oz - 0.08 + swing * (heavySlash ? 0.22 : 0.13);

        double rotX = drawHands ? (-0.30 + swing * 0.62 + inspectSpin) : overrideRotX;
        double rotY = drawHands ? (-0.24 - swing * (heavySlash ? 0.35 : 0.85)) : overrideRotY;
        double rotZ = drawHands ? (0.16 + swing * 0.45) : overrideRotZ;

        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int handle     = custom ? skin.trim    : 0x1A1E26;
        int ringGold   = 0xF2C648;
        int bladeSteel = custom ? skin.primary : 0xD6E0EC;
        int bladeEdge  = custom ? skin.accent  : 0xFF427C;

        if (drawHands) {
            drawTacticalHandsAndGloves(r, kx + 0.02, ky + 0.03, kz, -0.02, -1.0, false, -0.26 + swing * 0.42, rotY, rotZ, skin);
            // Левая рука в тактической стойке ближнего боя с часами слева!
            r.drawViewModelCylinder(ox - 0.25, oy - 0.14, oz - 0.05, 0.042, 0.035, 0.24, 0.35, 0.55, 0.15, ox, oy, oz, 10, 0x243447);
            r.drawViewModelBox(ox - 0.17, oy - 0.07, oz + 0.04, 0.044, 0.042, 0.064, 0.20, 0.35, 0.10, ox, oy, oz, Renderer3D.TEX_SOLID, 0x1D222B);
        }

        // Кольцо Керамбита + текстурированная G10 рукоять с заклёпками + хищный изогнутый клинок с долом!
        r.drawViewModelCylinder(kx, ky - 0.024, kz - 0.034, 0.023, 0.023, 0.013, rotX + 1.57, rotY, rotZ, kx, ky, kz, 14, ringGold);
        r.drawViewModelCylinder(kx, ky - 0.004, kz + 0.026, 0.015, 0.019, 0.108, rotX - 0.12, rotY, rotZ, kx, ky, kz, 12, handle);
        r.drawViewModelBox(kx, ky + 0.002, kz + 0.026, 0.034, 0.008, 0.075, rotX - 0.12, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, ringGold);
        r.drawViewModelBox(kx, ky + 0.014, kz + 0.105, 0.009, 0.034, 0.078, rotX + 0.10, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, bladeSteel);
        r.drawViewModelBox(kx, ky + 0.028, kz + 0.168, 0.008, 0.029, 0.072, rotX + 0.34, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, bladeSteel);
        r.drawViewModelBox(kx, ky + 0.052, kz + 0.218, 0.007, 0.023, 0.064, rotX + 0.62, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, bladeEdge);
        r.drawViewModelBox(kx, ky + 0.082, kz + 0.252, 0.005, 0.013, 0.046, rotX + 0.90, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, bladeEdge);
    }

    private void renderMuzzleFlash3D(Renderer3D r, double fx, double fy, double fz, double span) {
        r.drawViewModelBox(fx, fy, fz, span, span * 0.24, 0.02, 0.0, 0.0, 0.35, fx, fy, fz, Renderer3D.TEX_SOLID, 0xFFBE3B);
        r.drawViewModelBox(fx, fy, fz, span * 0.24, span, 0.02, 0.0, 0.0, 0.35, fx, fy, fz, Renderer3D.TEX_SOLID, 0xFFE680);
        r.drawViewModelBox(fx, fy, fz + 0.01, span * 0.42, span * 0.42, 0.04, 0.0, 0.0, 0.0, fx, fy, fz, Renderer3D.TEX_SOLID, 0xFFFBE6);
    }

    public Type getType() { return type; }
    public String getDisplayName() { return displayName; }
    public int getSlotNumber() { return slotNumber; }
    public int getPrice() { return price; }
    public int getKillReward() { return killReward; }
    public boolean isAutomatic() { return automatic; }
    public boolean isMelee() { return melee; }
    public boolean isSilenced() { return silenced; }
    public boolean hasSniperScope() { return sniperScope; }
    public int getScopeZoomLevel() { return scopeZoomLevel; }
    public boolean isAdsActive() { return adsActive; }
    public int getPelletsPerShot() { return pelletsPerShot; }
    public int getMagAmmo() { return magAmmo; }
    public int getMaxMagAmmo() { return maxMagAmmo; }
    public int getReserveAmmo() { return reserveAmmo; }
    public boolean isReloading() { return reloading; }
    public double getReloadProgress() {
        if (!reloading || reloadDuration <= 0.0) return 0.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - reloadTimer / reloadDuration));
    }
    public int getBodyDamage() { return bodyDamage; }
    public int getHeadDamage() { return headDamage; }
    public double getMaxRange() { return maxRange; }
    public double getMoveSpeedMultiplier() { return moveSpeedMultiplier; }
    public boolean hasMuzzleFlash() { return muzzleFlashTimer > 0.0; }

    public boolean canWallbang() {
        return type == Type.AWP || type == Type.M249 || type == Type.AK47
            || type == Type.M4A1S || type == Type.DEAGLE || type == Type.MP9 || type == Type.GLOCK;
    }

    public double getWallbangDamageFactor() {
        switch (type) {
            case AWP:    return 0.78;
            case M249:   return 0.68;
            case AK47:   return 0.60;
            case DEAGLE: return 0.62;
            case M4A1S:  return 0.54;
            default:     return 0.38;
        }
    }
}
