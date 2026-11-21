/**
 * Расширенный арсенал оружия CS-2 JAVA 3.0 (8 видов оружия + поддержка скинов CS2):
 * Слот 1 (Основное): AK-47, M4A1-S (глушитель), AWP (прицел ПКМ), Дробовик XM1014, ПП MP9
 * Слот 2 (Пистолеты): Glock-18, Desert Eagle (крупнокалиберный хром)
 * Слот 3 (Ближний бой): Боевой нож (быстрый удар ЛКМ, выпад ПКМ, осмотр на F, скины Fade/Asiimov)
 */
public class Weapon {

    public enum Type {
        AK47,
        M4A1S,
        AWP,
        XM1014,
        MP9,
        GLOCK,
        DEAGLE,
        ZEUS,
        KNIFE
    }

    public static final Type[] ARMS_RACE_LADDER = {
        Type.MP9, Type.XM1014, Type.M4A1S, Type.AK47,
        Type.AWP, Type.DEAGLE, Type.GLOCK, Type.KNIFE
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
                this.displayName = "Боевой нож";
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
        recoilKick = Math.max(0.0, recoilKick - dt * (type == Type.AWP ? 3.5 : 6.5));

        if (type == Type.ZEUS && magAmmo == 0 && !reloading) {
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
            muzzleFlashTimer = silenced ? 0.025 : 0.058;
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

        double forwardDist = (type == Type.AWP || type == Type.M4A1S) ? 0.86 : (slotNumber == 1 ? 0.72 : 0.55);
        double sideDist = adsActive ? 0.06 : 0.21;
        return eye.add(forward.scale(forwardDist)).add(right.scale(sideDist)).add(camUp.scale(-0.14));
    }

    public void renderViewModel(Renderer3D renderer, double walkBobPhase, double walkSpeedNorm,
                                double swayX, double swayY) {
        renderViewModel(renderer, walkBobPhase, walkSpeedNorm, swayX, swayY, GameSettings.WeaponSkin.STANDARD);
    }

    /**
     * Рендер 3D-модели оружия от первого лица с выбранным скином CS2.
     */
    public void renderViewModel(Renderer3D renderer, double walkBobPhase, double walkSpeedNorm,
                                double swayX, double swayY, GameSettings.WeaponSkin skin) {
        if (sniperScope && scopeZoomLevel > 0) {
            return;
        }

        double bobX = Math.sin(walkBobPhase) * 0.014 * walkSpeedNorm;
        double bobY = Math.abs(Math.cos(walkBobPhase)) * (-0.013) * walkSpeedNorm;
        double equipDrop = (1.0 - equipProgress) * 0.25;

        double rotX = -recoilKick * (type == Type.DEAGLE || type == Type.AWP ? 0.22 : 0.13);
        double rotY = -0.07;
        double rotZ = 0.0;
        double reloadDropY = 0.0;

        if (inspectTimer > 0.0) {
            double p = 1.0 - (inspectTimer / INSPECT_DURATION);
            double s1 = Math.sin(p * Math.PI);
            double s2 = Math.sin(p * Math.PI * 2.0);
            rotZ = -0.58 * s1 + 0.22 * s2;
            rotY = -0.07 + 0.48 * s1;
            rotX += 0.16 * s1;
        }

        if (reloading && reloadDuration > 0.0) {
            double progress = 1.0 - (reloadTimer / reloadDuration);
            double wave = Math.sin(progress * Math.PI);
            rotZ = 0.54 * wave;
            rotX = 0.28 * wave;
            reloadDropY = -0.10 * wave;
        }

        double adsShiftX = adsActive ? -0.15 : 0.0;
        double adsShiftY = adsActive ? 0.058 : 0.0;
        double adsShiftZ = adsActive ? -0.06 : 0.0;

        double ox = 0.23 + adsShiftX + bobX + swayX;
        double oy = -0.22 + adsShiftY + bobY + swayY - equipDrop + reloadDropY;
        double oz = 0.48 + adsShiftZ - recoilKick * 0.06;

        dispatchWeaponModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, true);
    }

    /**
     * Вращающийся 3D-предпросмотр оружия для экрана «Оружейная скинов CS2» в Главном меню.
     */
    public void renderShowcaseModel(Renderer3D renderer, double spinYaw, GameSettings.WeaponSkin skin) {
        // Центрируем модель в правой витрине (справа от боковой панели меню) под эффектным углом CS2 Inspect
        double angleY = 1.28 + spinYaw;
        double ox = 0.20;
        double oy = 0.05;
        double oz = (type == Type.AWP) ? 0.92 : (slotNumber == 1 ? 0.72 : 0.52);
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
            case XM1014: renderShotgunModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case MP9:    renderMp9Model(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case DEAGLE: renderDeagleModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case GLOCK:  renderGlockModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case ZEUS:   renderZeusModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case KNIFE:  renderKnifeModel(renderer, ox, oy, oz, 0, 0, 0, skin, drawHands); break;
        }
    }

    private void renderAk47Model(Renderer3D r, double ox, double oy, double oz,
                                 double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int wood = custom ? skin.accent : 0x9B5326;
        int darkWood = custom ? skin.trim : 0x7B3E1B;
        int steel = custom ? skin.primary : 0x32373E;
        int darkSteel = 0x20242A;
        int boltMetal = 0x767E87;
        int magColor = custom ? skin.accent : 0x3C251B;
        int sleeve = 0x2C3E54, glove = 0x1E2228;

        if (drawHands) {
            r.drawViewModelBox(ox - 0.04, oy - 0.10, oz - 0.10, 0.075, 0.075, 0.26, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(ox - 0.01, oy - 0.05, oz + 0.06, 0.062, 0.055, 0.09, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, glove);
        }

        r.drawViewModelBox(ox, oy - 0.025, oz - 0.18, 0.046, 0.082, 0.20, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkWood);
        r.drawViewModelBox(ox, oy - 0.085, oz - 0.04, 0.040, 0.095, 0.048, rotX + 0.22, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkWood);
        r.drawViewModelBox(ox, oy + 0.012, oz + 0.01, 0.052, 0.068, 0.24, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, steel);
        if (custom) {
            r.drawViewModelBox(ox, oy + 0.036, oz + 0.02, 0.054, 0.014, 0.20, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, skin.accent);
        }
        r.drawViewModelBox(ox + 0.029, oy + 0.022, oz + 0.03 - recoilKick * 0.03, 0.018, 0.016, 0.045, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, boltMetal);

        if (!reloading || reloadTimer > reloadDuration * 0.65 || reloadTimer < reloadDuration * 0.28) {
            r.drawViewModelBox(ox, oy - 0.095, oz + 0.085, 0.038, 0.145, 0.058, rotX - 0.28, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, magColor);
        }

        r.drawViewModelBox(ox, oy + 0.006, oz + 0.20, 0.048, 0.058, 0.16, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, wood);
        r.drawViewModelBox(ox, oy + 0.038, oz + 0.19, 0.038, 0.026, 0.14, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkWood);
        r.drawViewModelBox(ox, oy + 0.014, oz + 0.39, 0.020, 0.020, 0.26, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox, oy + 0.034, oz + 0.33, 0.016, 0.018, 0.12, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, steel);
        r.drawViewModelBox(ox, oy + 0.044, oz + 0.48, 0.014, 0.045, 0.024, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox, oy + 0.052, oz + 0.10, 0.028, 0.016, 0.032, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.016, oz + 0.55, 0.14);
    }

    private void renderM4a1sModel(Renderer3D r, double ox, double oy, double oz,
                                  double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int bodyBlack = custom ? skin.trim : 0x262B33;
        int railGray = custom ? skin.primary : 0x3A414C;
        int suppressor = custom ? skin.accent : 0x1A1E24;
        int accentBlue = custom ? skin.accent : 0x365C82;
        int sleeve = 0x2C3E54, glove = 0x1E2228;

        if (drawHands) {
            r.drawViewModelBox(ox - 0.04, oy - 0.10, oz - 0.10, 0.075, 0.075, 0.26, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(ox - 0.01, oy - 0.05, oz + 0.08, 0.062, 0.055, 0.09, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, glove);
        }

        r.drawViewModelBox(ox, oy - 0.012, oz - 0.18, 0.042, 0.086, 0.19, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyBlack);
        r.drawViewModelBox(ox, oy - 0.085, oz - 0.03, 0.038, 0.095, 0.045, rotX + 0.20, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyBlack);
        r.drawViewModelBox(ox, oy + 0.015, oz + 0.03, 0.050, 0.070, 0.24, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, railGray);
        r.drawViewModelBox(ox, oy + 0.062, oz + 0.02, 0.032, 0.024, 0.16, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, accentBlue);

        if (!reloading || reloadTimer > reloadDuration * 0.62 || reloadTimer < reloadDuration * 0.28) {
            r.drawViewModelBox(ox, oy - 0.090, oz + 0.07, 0.034, 0.130, 0.052, rotX - 0.10, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, accentBlue);
        }

        r.drawViewModelBox(ox, oy + 0.016, oz + 0.23, 0.046, 0.054, 0.19, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, railGray);
        r.drawViewModelBox(ox, oy + 0.016, oz + 0.47, 0.034, 0.034, 0.30, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, suppressor);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.016, oz + 0.63, 0.07);
    }

    private void renderAwpModel(Renderer3D r, double ox, double oy, double oz,
                                double rotX, double rotY, double rotZ,
                                GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int awpBody = custom ? skin.primary : 0x3D5438;
        int awpStripe = custom ? skin.accent : 0x2F422B;
        int darkSteel = 0x20252B;
        int scopeMetal = custom ? skin.trim : 0x181C22;
        int lensCyan = 0x4FC3F7;
        int sleeve = 0x2C3E54, glove = 0x1E2228;

        if (drawHands) {
            r.drawViewModelBox(ox - 0.04, oy - 0.10, oz - 0.10, 0.075, 0.075, 0.26, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(ox - 0.01, oy - 0.05, oz + 0.10, 0.064, 0.054, 0.09, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, glove);
        }

        r.drawViewModelBox(ox, oy - 0.020, oz - 0.16, 0.050, 0.092, 0.24, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, awpBody);
        r.drawViewModelBox(ox, oy + 0.005, oz + 0.10, 0.052, 0.072, 0.34, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, awpBody);
        r.drawViewModelBox(ox, oy + 0.022, oz + 0.11, 0.055, 0.018, 0.28, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, awpStripe);

        r.drawViewModelBox(ox, oy + 0.062, oz + 0.03, 0.024, 0.032, 0.03, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox, oy + 0.062, oz + 0.15, 0.024, 0.032, 0.03, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox, oy + 0.085, oz + 0.09, 0.042, 0.042, 0.26, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, scopeMetal);
        r.drawViewModelBox(ox, oy + 0.085, oz - 0.042, 0.036, 0.036, 0.01, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, lensCyan);

        r.drawViewModelBox(ox, oy + 0.022, oz + 0.46, 0.022, 0.022, 0.42, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox, oy + 0.022, oz + 0.68, 0.032, 0.028, 0.05, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, scopeMetal);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.022, oz + 0.72, 0.19);
    }

    private void renderShotgunModel(Renderer3D r, double ox, double oy, double oz,
                                    double rotX, double rotY, double rotZ,
                                    GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int polyDark = custom ? skin.trim : 0x25282E;
        int receiverSilver = custom ? skin.primary : 0x626A75;
        int forendCol = custom ? skin.accent : 0x25282E;
        int barrelSteel = 0x2E333B;
        int sleeve = 0x2C3E54, glove = 0x1E2228;

        if (drawHands) {
            r.drawViewModelBox(ox - 0.04, oy - 0.10, oz - 0.10, 0.075, 0.075, 0.26, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(ox - 0.01, oy - 0.04, oz + 0.20, 0.064, 0.055, 0.09, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, glove);
        }

        r.drawViewModelBox(ox, oy - 0.025, oz - 0.16, 0.048, 0.085, 0.20, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, polyDark);
        r.drawViewModelBox(ox, oy + 0.015, oz + 0.04, 0.054, 0.070, 0.24, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, receiverSilver);
        r.drawViewModelBox(ox, oy - 0.012, oz + 0.24, 0.052, 0.048, 0.18, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, forendCol);

        r.drawViewModelBox(ox, oy + 0.032, oz + 0.36, 0.024, 0.024, 0.42, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, barrelSteel);
        r.drawViewModelBox(ox, oy + 0.005, oz + 0.34, 0.022, 0.022, 0.36, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, polyDark);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.032, oz + 0.58, 0.18);
    }

    private void renderMp9Model(Renderer3D r, double ox, double oy, double oz,
                                double rotX, double rotY, double rotZ,
                                GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int bodyCol = custom ? skin.primary : 0x353D47;
        int accentCol = custom ? skin.accent : 0x4E5B6B;
        int darkCol = custom ? skin.trim : 0x1F242B;
        int sleeve = 0x2C3E54, glove = 0x1E2228;

        if (drawHands) {
            r.drawViewModelBox(ox - 0.04, oy - 0.10, oz - 0.10, 0.074, 0.074, 0.24, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(ox - 0.01, oy - 0.06, oz + 0.06, 0.062, 0.055, 0.09, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, glove);
        }

        // Складной рамочный приклад и компактная ствольная коробка MP9
        r.drawViewModelBox(ox, oy + 0.012, oz - 0.15, 0.028, 0.052, 0.18, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkCol);
        r.drawViewModelBox(ox, oy + 0.022, oz + 0.04, 0.052, 0.068, 0.25, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, bodyCol);
        r.drawViewModelBox(ox, oy + 0.060, oz + 0.04, 0.036, 0.016, 0.22, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, accentCol);
        // Пистолетная рукоятка с прямым длинным магазином + передняя тактическая рукоятка
        r.drawViewModelBox(ox, oy - 0.075, oz + 0.01, 0.038, 0.125, 0.046, rotX + 0.10, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkCol);
        if (!reloading || reloadTimer > reloadDuration * 0.62 || reloadTimer < reloadDuration * 0.28) {
            r.drawViewModelBox(ox, oy - 0.135, oz + 0.02, 0.028, 0.16, 0.034, rotX + 0.06, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, accentCol);
        }
        r.drawViewModelBox(ox, oy - 0.048, oz + 0.13, 0.030, 0.085, 0.032, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkCol);
        r.drawViewModelBox(ox, oy + 0.022, oz + 0.22, 0.024, 0.024, 0.14, rotX, rotY, rotZ, ox, oy, oz, Renderer3D.TEX_SOLID, darkCol);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, ox, oy + 0.022, oz + 0.31, 0.11);
    }

    private void renderDeagleModel(Renderer3D r, double ox, double oy, double oz,
                                   double rotX, double rotY, double rotZ,
                                   GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int chrome = custom ? skin.primary : 0xC8D2DC;
        int darkChrome = custom ? skin.accent : 0x8F9AA6;
        int gripBlack = custom ? skin.trim : 0x1D2026;
        int sleeve = 0x2C3E54, glove = 0x1E2228;

        double gx = ox - 0.02, gy = oy + 0.01, gz = oz - 0.04;
        double slideBack = recoilKick * 0.058;

        if (drawHands) {
            r.drawViewModelBox(gx - 0.03, gy - 0.11, gz - 0.12, 0.072, 0.070, 0.25, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(gx, gy - 0.06, gz - 0.01, 0.066, 0.070, 0.08, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, glove);
        }
        r.drawViewModelBox(gx, gy - 0.058, gz + 0.00, 0.042, 0.112, 0.054, rotX + 0.20, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, gripBlack);
        r.drawViewModelBox(gx, gy + 0.006, gz + 0.09, 0.044, 0.032, 0.23, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, darkChrome);
        r.drawViewModelBox(gx, gy + 0.038, gz + 0.09 - slideBack, 0.048, 0.048, 0.24, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, chrome);
        r.drawViewModelBox(gx, gy + 0.065, gz - 0.02 - slideBack, 0.030, 0.012, 0.015, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, gripBlack);
        r.drawViewModelBox(gx, gy + 0.065, gz + 0.19, 0.012, 0.014, 0.015, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, gripBlack);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, gx, gy + 0.038, gz + 0.24, 0.15);
    }

    private void renderGlockModel(Renderer3D r, double ox, double oy, double oz,
                                  double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int frameBlack = custom ? skin.trim : 0x1E2126;
        int slideSteel = custom ? skin.primary : 0x434A54;
        int barrelDark = custom ? skin.accent : 0x262A30;
        int sightWhite = 0xEDF3F8;
        int sleeve = 0x2C3E54, glove = 0x1E2228;

        double gx = ox - 0.02, gy = oy + 0.01, gz = oz - 0.05;
        double slideBack = recoilKick * 0.048;

        if (drawHands) {
            r.drawViewModelBox(gx - 0.03, gy - 0.11, gz - 0.12, 0.072, 0.070, 0.25, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(gx, gy - 0.06, gz - 0.01, 0.064, 0.068, 0.075, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, glove);
        }
        r.drawViewModelBox(gx, gy - 0.055, gz + 0.00, 0.038, 0.105, 0.048, rotX + 0.20, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, frameBlack);
        r.drawViewModelBox(gx, gy + 0.002, gz + 0.065, 0.040, 0.030, 0.19, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, frameBlack);
        r.drawViewModelBox(gx, gy + 0.030, gz + 0.065 - slideBack, 0.042, 0.038, 0.195, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, slideSteel);
        r.drawViewModelBox(gx, gy + 0.026, gz + 0.14, 0.020, 0.020, 0.09, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, barrelDark);
        r.drawViewModelBox(gx, gy + 0.053, gz - 0.02 - slideBack, 0.026, 0.010, 0.012, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, sightWhite);
        r.drawViewModelBox(gx, gy + 0.053, gz + 0.15 - slideBack, 0.009, 0.011, 0.012, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, sightWhite);

        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(r, gx, gy + 0.028, gz + 0.21, 0.10);
    }

    private void renderZeusModel(Renderer3D r, double ox, double oy, double oz,
                                 double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int bodyDark = custom ? skin.trim : 0x1E2228;
        int hazardYellow = custom ? skin.accent : 0xF2BE22;
        int steel = custom ? skin.primary : 0x8A96A4;
        int chargeLed = (magAmmo > 0) ? 0x2AF2FF : 0xFF4436;
        int sleeve = 0x2C3E54, glove = 0x1E2228;

        double zx = ox - 0.02, zy = oy + 0.01, zz = oz - 0.04;
        if (drawHands) {
            r.drawViewModelBox(zx - 0.03, zy - 0.11, zz - 0.12, 0.072, 0.070, 0.25, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(zx, zy - 0.06, zz - 0.01, 0.064, 0.068, 0.075, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, glove);
        }

        // Эргономичная рукоятка + черно-желтый корпус электрошокера Zeus x27
        r.drawViewModelBox(zx, zy - 0.055, zz + 0.00, 0.040, 0.105, 0.052, rotX + 0.22, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, bodyDark);
        r.drawViewModelBox(zx, zy + 0.018, zz + 0.065, 0.052, 0.064, 0.19, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, bodyDark);
        r.drawViewModelBox(zx, zy + 0.024, zz + 0.085, 0.056, 0.026, 0.13, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, hazardYellow);
        // Верхний цифровой индикатор заряда батареи
        r.drawViewModelBox(zx, zy + 0.054, zz + 0.045, 0.028, 0.010, 0.075, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, chargeLed);
        // Два высоковольтных электрода спереди
        r.drawViewModelBox(zx - 0.014, zy + 0.026, zz + 0.18, 0.009, 0.012, 0.055, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, steel);
        r.drawViewModelBox(zx + 0.014, zy + 0.026, zz + 0.18, 0.009, 0.012, 0.055, rotX, rotY, rotZ, zx, zy, zz, Renderer3D.TEX_SOLID, steel);

        if (muzzleFlashTimer > 0.0 && drawHands) {
            r.drawViewModelBox(zx, zy + 0.026, zz + 0.21, 0.09, 0.09, 0.04, 0.0, 0.0, 0.4, zx, zy, zz, Renderer3D.TEX_SOLID, 0x48F5FF);
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
            inspectSpin = Math.sin(p * Math.PI * 2.0) * 1.4;
        }

        double kx = ox - 0.02 - swing * (heavySlash ? 0.11 : 0.19);
        double ky = oy - 0.01 + swing * 0.06;
        double kz = oz - 0.09 + swing * (heavySlash ? 0.22 : 0.13);

        double rotX = drawHands ? (-0.35 + swing * 0.58 + inspectSpin * 0.6) : overrideRotX;
        double rotY = drawHands ? (-0.25 - swing * (heavySlash ? 0.35 : 0.85) + inspectSpin * 0.35) : overrideRotY;
        double rotZ = drawHands ? (0.18 + swing * 0.45) : overrideRotZ;

        boolean custom = (skin != GameSettings.WeaponSkin.STANDARD);
        int sleeve = 0x2C3E54, glove = 0x1E2228;
        int handle = custom ? skin.trim : 0x23272E;
        int guard = 0xD4AF37;
        int bladeSteel = custom ? skin.primary : 0xB8C4D0;
        int bladeEdge = custom ? skin.accent : 0xEDF4FA;

        if (drawHands) {
            r.drawViewModelBox(kx + 0.02, ky - 0.09, kz - 0.10, 0.072, 0.070, 0.22, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, sleeve);
            r.drawViewModelBox(kx, ky - 0.03, kz + 0.00, 0.062, 0.058, 0.075, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, glove);
        }
        r.drawViewModelBox(kx, ky + 0.00, kz + 0.01, 0.030, 0.036, 0.11, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, handle);
        r.drawViewModelBox(kx, ky + 0.005, kz + 0.07, 0.048, 0.062, 0.016, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, guard);
        r.drawViewModelBox(kx, ky + 0.012, kz + 0.170, 0.011, 0.034, 0.185, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, bladeSteel);
        r.drawViewModelBox(kx, ky - 0.005, kz + 0.172, 0.007, 0.011, 0.190, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, bladeEdge);
        r.drawViewModelBox(kx, ky + 0.006, kz + 0.276, 0.008, 0.021, 0.042, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, bladeEdge);
        r.drawViewModelBox(kx, ky + 0.002, kz + 0.304, 0.006, 0.011, 0.026, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, bladeEdge);
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
        return type == Type.AWP || type == Type.AK47 || type == Type.M4A1S
            || type == Type.DEAGLE || type == Type.MP9 || type == Type.GLOCK;
    }

    public double getWallbangDamageFactor() {
        switch (type) {
            case AWP:    return 0.78;
            case AK47:   return 0.60;
            case DEAGLE: return 0.62;
            case M4A1S:  return 0.54;
            default:     return 0.38;
        }
    }
}
