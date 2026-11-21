/**
 * Система вооружения CS-2 JAVA:
 * 1 — Винтовка AK-47 (автоматический огонь, высокий урон, отдача)
 * 2 — Пистолет Glock-18 (полуавтоматический, точный, быстрая перезарядка)
 * 3 — Боевой нож (ближний бой, повышенная скорость бега)
 * Включает полноценный 3D-рендеринг оружия в руках от первого лица.
 */
public class Weapon {

    public enum Type {
        AK47,
        GLOCK,
        KNIFE
    }

    private final Type type;
    private final String displayName;
    private final int slotNumber;
    private final boolean automatic;
    private final boolean melee;

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

    // Визуальные параметры анимации оружия в руках
    private double recoilBloom;
    private double recoilKick;
    private double muzzleFlashTimer;
    private double equipProgress;
    private double slashTimer;

    public Weapon(Type type) {
        this.type = type;
        switch (type) {
            case AK47:
                this.displayName = "AK-47";
                this.slotNumber = 1;
                this.automatic = true;
                this.melee = false;
                this.maxMagAmmo = 30;
                this.initialReserveAmmo = 90;
                this.fireInterval = 0.10;
                this.reloadDuration = 2.15;
                this.bodyDamage = 36;
                this.headDamage = 116;
                this.maxRange = 110.0;
                this.baseSpread = 0.008;
                this.recoilPerShot = 0.014;
                this.moveSpeedMultiplier = 0.92;
                break;
            case GLOCK:
                this.displayName = "Glock-18";
                this.slotNumber = 2;
                this.automatic = false;
                this.melee = false;
                this.maxMagAmmo = 20;
                this.initialReserveAmmo = 120;
                this.fireInterval = 0.145;
                this.reloadDuration = 1.45;
                this.bodyDamage = 26;
                this.headDamage = 82;
                this.maxRange = 85.0;
                this.baseSpread = 0.006;
                this.recoilPerShot = 0.009;
                this.moveSpeedMultiplier = 1.00;
                break;
            case KNIFE:
            default:
                this.displayName = "Боевой нож";
                this.slotNumber = 3;
                this.automatic = true;
                this.melee = true;
                this.maxMagAmmo = 0;
                this.initialReserveAmmo = 0;
                this.fireInterval = 0.40;
                this.reloadDuration = 0.0;
                this.bodyDamage = 55;
                this.headDamage = 95;
                this.maxRange = 2.65;
                this.baseSpread = 0.0;
                this.recoilPerShot = 0.0;
                this.moveSpeedMultiplier = 1.14;
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
    }

    public void onEquip() {
        this.reloading = false;
        this.reloadTimer = 0.0;
        this.equipProgress = 0.0;
        this.fireCooldown = Math.max(this.fireCooldown, 0.16);
    }

    public void update(double dt) {
        if (fireCooldown > 0.0) {
            fireCooldown = Math.max(0.0, fireCooldown - dt);
        }
        if (muzzleFlashTimer > 0.0) {
            muzzleFlashTimer = Math.max(0.0, muzzleFlashTimer - dt);
        }
        if (slashTimer > 0.0) {
            slashTimer = Math.max(0.0, slashTimer - dt);
        }
        if (equipProgress < 1.0) {
            equipProgress = Math.min(1.0, equipProgress + dt * 4.8);
        }

        recoilBloom = Math.max(0.0, recoilBloom - dt * 0.055);
        recoilKick = Math.max(0.0, recoilKick - dt * 6.5);

        if (reloading) {
            reloadTimer -= dt;
            if (reloadTimer <= 0.0) {
                int needed = maxMagAmmo - magAmmo;
                int transfer = Math.min(needed, reserveAmmo);
                magAmmo += transfer;
                reserveAmmo -= transfer;
                reloading = false;
                reloadTimer = 0.0;
            }
        }
    }

    public boolean startReload() {
        if (melee || reloading || magAmmo >= maxMagAmmo || reserveAmmo <= 0) {
            return false;
        }
        reloading = true;
        reloadTimer = reloadDuration;
        return true;
    }

    public boolean canFire() {
        if (reloading || fireCooldown > 0.0 || equipProgress < 0.75) {
            return false;
        }
        if (melee) {
            return true;
        }
        return magAmmo > 0;
    }

    public void onFire() {
        fireCooldown = fireInterval;
        if (melee) {
            slashTimer = 0.28;
            recoilKick = 0.55;
        } else {
            magAmmo = Math.max(0, magAmmo - 1);
            recoilBloom = Math.min(0.065, recoilBloom + recoilPerShot);
            recoilKick = 1.0;
            muzzleFlashTimer = 0.055;
        }
    }

    public double computeCurrentSpread(boolean moving, boolean airborne, boolean crouching) {
        if (melee) return 0.0;
        double spread = baseSpread + recoilBloom;
        if (moving) spread += (type == Type.AK47 ? 0.022 : 0.010);
        if (airborne) spread += 0.055;
        if (crouching) spread *= 0.52;
        return spread;
    }

    /**
     * Возвращает мировую точку дульного среза оружия для красивого старта 3D-трассера.
     */
    public Vec3 getMuzzleWorldPosition(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 forward = player.getLookDirection();
        Vec3 right = player.getRightVector();
        double cp = Math.cos(player.getPitch());
        double sp = Math.sin(player.getPitch());
        double sy = Math.sin(player.getYaw());
        double cy = Math.cos(player.getYaw());
        Vec3 camUp = new Vec3(-sy * sp, cp, -cy * sp);

        double forwardDist = (type == Type.AK47) ? 0.76 : 0.55;
        return eye
                .add(forward.scale(forwardDist))
                .add(right.scale(0.21))
                .add(camUp.scale(-0.15));
    }

    /**
     * Отрисовка детализированной 3D-модели оружия в руках игрока + вспышки выстрела.
     */
    public void renderViewModel(Renderer3D renderer, double walkBobPhase, double walkSpeedNorm,
                                double swayX, double swayY) {
        double bobX = Math.sin(walkBobPhase) * 0.014 * walkSpeedNorm;
        double bobY = Math.abs(Math.cos(walkBobPhase)) * (-0.013) * walkSpeedNorm;
        double equipDrop = (1.0 - equipProgress) * 0.24;

        double rotX = -recoilKick * 0.13;
        double rotY = -0.07;
        double rotZ = 0.0;
        double reloadDropY = 0.0;

        if (reloading && reloadDuration > 0.0) {
            double progress = 1.0 - (reloadTimer / reloadDuration);
            double wave = Math.sin(progress * Math.PI);
            rotZ = 0.52 * wave;
            rotX = 0.28 * wave;
            reloadDropY = -0.10 * wave;
        }

        double ox = 0.23 + bobX + swayX;
        double oy = -0.22 + bobY + swayY - equipDrop + reloadDropY;
        double oz = 0.48 - recoilKick * 0.055;

        if (type == Type.AK47) {
            renderAk47Model(renderer, ox, oy, oz, rotX, rotY, rotZ);
        } else if (type == Type.GLOCK) {
            renderGlockModel(renderer, ox, oy, oz, rotX, rotY, rotZ);
        } else {
            renderKnifeModel(renderer, ox, oy, oz);
        }
    }

    private void renderAk47Model(Renderer3D r, double ox, double oy, double oz,
                                 double rotX, double rotY, double rotZ) {
        int wood = 0x9B5326;
        int darkWood = 0x7B3E1B;
        int steel = 0x32373E;
        int darkSteel = 0x20242A;
        int boltMetal = 0x767E87;
        int magColor = 0x3C251B;
        int sleeve = 0x2C3E54;
        int glove = 0x1E2228;

        double px = ox, py = oy, pz = oz;

        // Рукава и тактические перчатки спецназа CT
        r.drawViewModelBox(ox - 0.04, oy - 0.10, oz - 0.10, 0.075, 0.075, 0.26, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, sleeve);
        r.drawViewModelBox(ox - 0.01, oy - 0.05, oz + 0.06, 0.062, 0.055, 0.09, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, glove);

        // Деревянный приклад AK-47
        r.drawViewModelBox(ox, oy - 0.025, oz - 0.18, 0.046, 0.082, 0.20, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, darkWood);
        // Пистолетная рукоятка
        r.drawViewModelBox(ox, oy - 0.085, oz - 0.04, 0.040, 0.095, 0.048, rotX + 0.22, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, darkWood);

        // Стальная ствольная коробка и крышка
        r.drawViewModelBox(ox, oy + 0.012, oz + 0.01, 0.052, 0.068, 0.24, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, steel);
        r.drawViewModelBox(ox + 0.029, oy + 0.022, oz + 0.03 - recoilKick * 0.03, 0.018, 0.016, 0.045, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, boltMetal);

        // Изогнутый магазин AK-47 ("банан")
        if (!reloading || reloadTimer > reloadDuration * 0.65 || reloadTimer < reloadDuration * 0.28) {
            r.drawViewModelBox(ox, oy - 0.095, oz + 0.085, 0.038, 0.145, 0.058, rotX - 0.28, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, magColor);
        }

        // Деревянное цевье и накладка газовой трубки
        r.drawViewModelBox(ox, oy + 0.006, oz + 0.20, 0.048, 0.058, 0.16, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, wood);
        r.drawViewModelBox(ox, oy + 0.038, oz + 0.19, 0.038, 0.026, 0.14, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, darkWood);

        // Длинный ствол, газоотвод и характерная мушка AK-47
        r.drawViewModelBox(ox, oy + 0.014, oz + 0.39, 0.020, 0.020, 0.26, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, darkSteel);
        r.drawViewModelBox(ox, oy + 0.034, oz + 0.33, 0.016, 0.018, 0.12, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, steel);
        r.drawViewModelBox(ox, oy + 0.044, oz + 0.48, 0.014, 0.045, 0.024, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, darkSteel);
        // Целик
        r.drawViewModelBox(ox, oy + 0.052, oz + 0.10, 0.028, 0.016, 0.032, rotX, rotY, rotZ, px, py, pz, Renderer3D.TEX_SOLID, darkSteel);

        if (muzzleFlashTimer > 0.0) {
            renderMuzzleFlash3D(r, ox, oy + 0.016, oz + 0.55, 0.14);
        }
    }

    private void renderGlockModel(Renderer3D r, double ox, double oy, double oz,
                                  double rotX, double rotY, double rotZ) {
        int frameBlack = 0x1E2126;
        int slideSteel = 0x434A54;
        int barrelDark = 0x262A30;
        int sightWhite = 0xEDF3F8;
        int sleeve = 0x2C3E54;
        int glove = 0x1E2228;

        double gx = ox - 0.02;
        double gy = oy + 0.01;
        double gz = oz - 0.05;
        double slideBack = recoilKick * 0.048;

        // Руки и перчатки в двуручном хвате
        r.drawViewModelBox(gx - 0.03, gy - 0.11, gz - 0.12, 0.072, 0.070, 0.25, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, sleeve);
        r.drawViewModelBox(gx, gy - 0.06, gz - 0.01, 0.064, 0.068, 0.075, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, glove);

        // Полимерная рамка и рукоятка Glock-18
        r.drawViewModelBox(gx, gy - 0.055, gz + 0.00, 0.038, 0.105, 0.048, rotX + 0.20, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, frameBlack);
        r.drawViewModelBox(gx, gy + 0.002, gz + 0.065, 0.040, 0.030, 0.19, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, frameBlack);

        // Металлический кожух-затвор (откатывается назад при выстреле)
        r.drawViewModelBox(gx, gy + 0.030, gz + 0.065 - slideBack, 0.042, 0.038, 0.195, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, slideSteel);
        // Ствол
        r.drawViewModelBox(gx, gy + 0.026, gz + 0.14, 0.020, 0.020, 0.09, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, barrelDark);

        // Прицельные приспособления Glock с белыми точками
        r.drawViewModelBox(gx, gy + 0.053, gz - 0.02 - slideBack, 0.026, 0.010, 0.012, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, sightWhite);
        r.drawViewModelBox(gx, gy + 0.053, gz + 0.15 - slideBack, 0.009, 0.011, 0.012, rotX, rotY, rotZ, gx, gy, gz, Renderer3D.TEX_SOLID, sightWhite);

        if (muzzleFlashTimer > 0.0) {
            renderMuzzleFlash3D(r, gx, gy + 0.028, gz + 0.21, 0.10);
        }
    }

    private void renderKnifeModel(Renderer3D r, double ox, double oy, double oz) {
        double slashPhase = (slashTimer > 0.0) ? (1.0 - slashTimer / 0.28) : 0.0;
        double swing = Math.sin(slashPhase * Math.PI);

        double kx = ox - 0.02 - swing * 0.19;
        double ky = oy - 0.01 + swing * 0.06;
        double kz = oz - 0.09 + swing * 0.13;

        double rotX = -0.35 + swing * 0.55;
        double rotY = -0.25 - swing * 0.85;
        double rotZ = 0.18 + swing * 0.45;

        int sleeve = 0x2C3E54;
        int glove = 0x1E2228;
        int handle = 0x23272E;
        int guard = 0x4A525D;
        int bladeSteel = 0xB8C4D0;
        int bladeEdge = 0xEDF4FA;

        r.drawViewModelBox(kx + 0.02, ky - 0.09, kz - 0.10, 0.072, 0.070, 0.22, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, sleeve);
        r.drawViewModelBox(kx, ky - 0.03, kz + 0.00, 0.062, 0.058, 0.075, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, glove);

        // Рукоять и гарда боевого ножа
        r.drawViewModelBox(kx, ky + 0.00, kz + 0.01, 0.030, 0.036, 0.11, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, handle);
        r.drawViewModelBox(kx, ky + 0.005, kz + 0.07, 0.048, 0.062, 0.016, rotX, rotY, rotZ, kx, ky, kz, Renderer3D.TEX_SOLID, guard);

        // Клинок и светлая режущая кромка (монолитное лезвие с острием)
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
    public boolean isAutomatic() { return automatic; }
    public boolean isMelee() { return melee; }
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
    public double getRecoilBloom() { return recoilBloom; }
    public boolean hasMuzzleFlash() { return muzzleFlashTimer > 0.0; }
}
