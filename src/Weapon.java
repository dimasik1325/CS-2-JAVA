/**
 * Арсенал CS-2 JAVA 9.0 — 26 видов 3D-оружия с реальными ТТХ CS2 (урон, RPM, бронепробитие, падение урона, паттерны отдачи):
 * Слот 1: AK-47, M4A4, M4A1-S, FAMAS, Galil AR, SG 553, AUG, AWP, SSG 08, MAC-10, MP9, UMP-45, P90, Nova, XM1014, Negev, M249, RPG-7
 * Слот 2 (Пистолеты): Glock-18, USP-S, P250, Five-SeveN, Tec-9, Desert Eagle
 * Слот 3 (Ближний бой): Боевой нож
 * Слот 4 (Спецсредство): Электрошокер Zeus x27
 */
public class Weapon {

    public enum Type {
        AK47, M4A1S, AWP, M249, RPG7, XM1014, MP9, GLOCK, DEAGLE, ZEUS, KNIFE,
        // ===== НОВЫЙ АРСЕНАЛ CS-2 JAVA 9.0 (+15 стволов) =====
        M4A4, FAMAS, GALIL, SG553, AUG, SSG08,
        MAC10, UMP45, P90,
        NOVA, NEGEV,
        USP_S, P250, FIVESEVEN, TEC9
    }

    /** Класс оружия — для 3D-модели от 3-го лица, поведения ботов, магазина и звука. */
    public enum WeaponClass { PISTOL, SMG, RIFLE, SNIPER, SHOTGUN, MACHINE_GUN, LAUNCHER, MELEE, TASER }

    /** Зоны попадания CS2 с реальными множителями урона (голова ×4, грудь ×1, живот ×1.25, ноги ×0.75). */
    public enum HitGroup {
        HEAD(4.0, "ГОЛОВА"), CHEST(1.0, "ГРУДЬ"), STOMACH(1.25, "ЖИВОТ"), LEGS(0.75, "НОГИ");
        public final double multiplier;
        public final String label;
        HitGroup(double m, String label) { this.multiplier = m; this.label = label; }
    }

    /** Команда, которой оружие доступно в оригинальной CS2 (для бейджа в Магазине и закупки ботов). */
    public static final int TEAM_ANY = 0, TEAM_CT = 1, TEAM_T = 2;

    public static final Type[] ARMS_RACE_LADDER = {
        Type.MAC10, Type.MP9, Type.UMP45, Type.P90, Type.NOVA, Type.XM1014, Type.NEGEV,
        Type.GALIL, Type.FAMAS, Type.AK47, Type.M4A4, Type.M4A1S, Type.SG553, Type.AUG,
        Type.SSG08, Type.AWP, Type.RPG7, Type.FIVESEVEN, Type.DEAGLE, Type.KNIFE
    };

    /** Табличные ТТХ ствола (реальные значения CS2: урон, RPM, бронепробитие, падение урона, скорость в юнитах). */
    private static final class Spec {
        String name, shortName; int slot, price, reward; boolean auto, silenced, scope;
        double adsZoom = 0.78; int pellets = 1, mag, reserve; double rpm, reload; int damage;
        double armorPen, rangeMod, penetration; int speedUnits; double range, spread, bloom;
        double climb, sideAmp, sideFreq, sidePhase; int climbShots; boolean shellReload;
        WeaponClass cls; int team = TEAM_ANY;
    }

    private static Spec spec(Type t) {
        Spec s = new Spec();
        switch (t) {
            // --------------------------- ШТУРМОВЫЕ ВИНТОВКИ ---------------------------
            case AK47:   rifle(s, "AK-47", "AK-47", 2700, 300, 30, 90, 600, 2.45, 36, 0.775, 0.98, 215, 0.0062, 0.0115, 9, 0.050, 0.45, 0.0); s.team = TEAM_T; break;
            case M4A4:   rifle(s, "M4A4", "M4A4", 3100, 300, 30, 90, 666, 3.05, 33, 0.70, 0.97, 225, 0.0048, 0.0095, 9, 0.040, 0.42, 2.0); s.team = TEAM_CT; break;
            case M4A1S:  rifle(s, "M4A1-S", "M4A1-S", 2900, 300, 20, 80, 600, 3.05, 38, 0.70, 0.99, 225, 0.0040, 0.0085, 8, 0.032, 0.40, 1.2); s.silenced = true; s.team = TEAM_CT; break;
            case FAMAS:  rifle(s, "FAMAS", "FAMAS", 2050, 300, 25, 90, 666, 3.30, 30, 0.70, 0.96, 220, 0.0058, 0.0090, 8, 0.038, 0.48, 0.5); s.team = TEAM_CT; break;
            case GALIL:  rifle(s, "Galil AR", "Galil", 1800, 300, 35, 90, 666, 3.00, 30, 0.775, 0.98, 215, 0.0062, 0.0095, 9, 0.044, 0.44, 2.6); s.team = TEAM_T; break;
            case SG553:  rifle(s, "SG 553", "SG 553", 3000, 300, 30, 90, 545, 2.80, 30, 1.00, 0.98, 210, 0.0052, 0.0100, 9, 0.036, 0.40, 0.8); s.adsZoom = 0.52; s.team = TEAM_T; break;
            case AUG:    rifle(s, "AUG", "AUG", 3300, 300, 30, 90, 600, 3.80, 28, 0.90, 0.98, 220, 0.0048, 0.0090, 9, 0.034, 0.38, 1.7); s.adsZoom = 0.52; s.team = TEAM_CT; break;
            // --------------------------- СНАЙПЕРСКИЕ ВИНТОВКИ -------------------------
            case AWP:    sniper(s, "AWP", "AWP", 4750, 100, 5, 30, 41, 3.70, 115, 0.975, 0.99, 200, 0.040); break;
            case SSG08:  sniper(s, "SSG 08", "Scout", 1700, 300, 10, 90, 48, 3.70, 88, 0.85, 0.98, 230, 0.026); break;
            // --------------------------- ПИСТОЛЕТЫ-ПУЛЕМЁТЫ ---------------------------
            case MAC10:  smg(s, "MAC-10", "MAC-10", 1050, 600, 30, 100, 800, 2.60, 29, 0.575, 0.80, 240, 0.0095, 0.0080, 8, 0.046, 0.55, 0.3); s.team = TEAM_T; break;
            case MP9:    smg(s, "MP9", "MP9", 1250, 600, 30, 120, 857, 2.10, 26, 0.60, 0.87, 240, 0.0085, 0.0075, 8, 0.040, 0.50, 1.0); s.team = TEAM_CT; break;
            case UMP45:  smg(s, "UMP-45", "UMP-45", 1200, 600, 25, 100, 666, 3.50, 35, 0.65, 0.75, 230, 0.0090, 0.0080, 8, 0.034, 0.40, 2.2); break;
            case P90:    smg(s, "P90", "P90", 2350, 300, 50, 100, 857, 3.30, 26, 0.69, 0.86, 230, 0.0088, 0.0060, 10, 0.036, 0.35, 0.4); break;
            // --------------------------- ТЯЖЁЛОЕ ОРУЖИЕ -------------------------------
            case NOVA:   shotgun(s, "Nova", "Nova", 1050, 900, 8, 32, 68, 0.52, 26, 9, 0.50, 220, 0.058, false); break;
            case XM1014: shotgun(s, "XM1014", "XM1014", 2000, 900, 7, 32, 171, 0.46, 20, 6, 0.80, 215, 0.050, true); break;
            case NEGEV:  mg(s, "Negev", "Negev", 1700, 300, 150, 300, 800, 5.70, 35, 0.71, 0.90, 150, 0.0110, 0.0070, 12, 0.060, 0.30, 0.9); break;
            case M249:   mg(s, "M249 Para", "M249", 5200, 300, 100, 200, 750, 5.70, 32, 0.80, 0.97, 195, 0.0095, 0.0075, 12, 0.050, 0.32, 1.4); break;
            case RPG7:
                s.name = "RPG-7"; s.shortName = "RPG-7"; s.slot = 1; s.price = 4500; s.reward = 300; s.mag = 1; s.reserve = 5;
                s.rpm = 60.0 / 1.10; s.reload = 2.45; s.damage = 260; s.armorPen = 1.0; s.rangeMod = 1.0; s.penetration = 0.0;
                s.speedUnits = 200; s.range = 160.0; s.spread = 0.0025; s.bloom = 0.032; s.cls = WeaponClass.LAUNCHER; break;
            // --------------------------- ПИСТОЛЕТЫ ------------------------------------
            case GLOCK:     pistol(s, "Glock-18", "Glock", 200, 300, 20, 120, 400, 2.27, 30, 0.47, 0.85, 1.0, 0.0060, 0.0090); s.team = TEAM_T; break;
            case USP_S:     pistol(s, "USP-S", "USP-S", 200, 300, 12, 24, 352, 2.20, 35, 0.505, 0.91, 1.0, 0.0045, 0.0100); s.silenced = true; s.team = TEAM_CT; break;
            case P250:      pistol(s, "P250", "P250", 300, 300, 13, 26, 400, 2.20, 38, 0.64, 0.90, 1.0, 0.0058, 0.0120); break;
            case FIVESEVEN: pistol(s, "Five-SeveN", "Five-7", 500, 300, 20, 100, 400, 2.20, 32, 0.911, 0.81, 1.0, 0.0058, 0.0100); s.team = TEAM_CT; break;
            case TEC9:      pistol(s, "Tec-9", "Tec-9", 500, 300, 18, 90, 500, 2.50, 33, 0.903, 0.83, 1.0, 0.0072, 0.0120); s.team = TEAM_T; break;
            case DEAGLE:    pistol(s, "Desert Eagle", "Deagle", 700, 300, 7, 35, 267, 2.20, 53, 0.932, 0.81, 2.0, 0.0050, 0.0300); s.speedUnits = 230; break;
            // --------------------------- СПЕЦСРЕДСТВА ---------------------------------
            case ZEUS:
                s.name = "Zeus x27"; s.shortName = "Zeus"; s.slot = 4; s.price = 200; s.reward = 0; s.mag = 1; s.reserve = 0;
                s.rpm = 60.0 / 0.85; s.reload = 10.0; s.damage = 500; s.armorPen = 1.0; s.rangeMod = 1.0; s.penetration = 0.0;
                s.speedUnits = 220; s.range = 5.2; s.spread = 0.002; s.bloom = 0.004; s.cls = WeaponClass.TASER; break;
            case KNIFE:
            default:
                s.name = "Керамбит"; s.shortName = "Нож"; s.slot = 3; s.price = 0; s.reward = 1500; s.auto = true; s.silenced = true;
                s.mag = 0; s.reserve = 0; s.rpm = 60.0 / 0.38; s.reload = 0.0; s.damage = 55; s.armorPen = 0.85; s.rangeMod = 1.0;
                s.penetration = 0.0; s.speedUnits = 250; s.range = 2.68; s.spread = 0.0; s.bloom = 0.0; s.cls = WeaponClass.MELEE; break;
        }
        return s;
    }

    private static void rifle(Spec s, String n, String sn, int price, int reward, int mag, int res, double rpm, double reload,
                              int dmg, double ap, double rm, int speed, double spread, double climb, int climbShots,
                              double sideAmp, double sideFreq, double phase) {
        s.name = n; s.shortName = sn; s.slot = 1; s.price = price; s.reward = reward; s.auto = true; s.mag = mag; s.reserve = res;
        s.rpm = rpm; s.reload = reload; s.damage = dmg; s.armorPen = ap; s.rangeMod = rm; s.penetration = 2.0; s.speedUnits = speed;
        s.range = 150.0; s.spread = spread; s.bloom = climb * 0.30; s.climb = climb; s.climbShots = climbShots;
        s.sideAmp = sideAmp; s.sideFreq = sideFreq; s.sidePhase = phase; s.cls = WeaponClass.RIFLE;
    }

    private static void sniper(Spec s, String n, String sn, int price, int reward, int mag, int res, double rpm, double reload,
                               int dmg, double ap, double rm, int speed, double unscopedSpread) {
        s.name = n; s.shortName = sn; s.slot = 1; s.price = price; s.reward = reward; s.scope = true; s.mag = mag; s.reserve = res;
        s.rpm = rpm; s.reload = reload; s.damage = dmg; s.armorPen = ap; s.rangeMod = rm; s.penetration = 2.5; s.speedUnits = speed;
        s.range = 200.0; s.spread = unscopedSpread; s.bloom = 0.035; s.climb = 0.030; s.climbShots = 1; s.cls = WeaponClass.SNIPER;
    }

    private static void smg(Spec s, String n, String sn, int price, int reward, int mag, int res, double rpm, double reload,
                            int dmg, double ap, double rm, int speed, double spread, double climb, int climbShots,
                            double sideAmp, double sideFreq, double phase) {
        rifle(s, n, sn, price, reward, mag, res, rpm, reload, dmg, ap, rm, speed, spread, climb, climbShots, sideAmp, sideFreq, phase);
        s.penetration = 1.0; s.range = 95.0; s.cls = WeaponClass.SMG;
    }

    private static void mg(Spec s, String n, String sn, int price, int reward, int mag, int res, double rpm, double reload,
                           int dmg, double ap, double rm, int speed, double spread, double climb, int climbShots,
                           double sideAmp, double sideFreq, double phase) {
        rifle(s, n, sn, price, reward, mag, res, rpm, reload, dmg, ap, rm, speed, spread, climb, climbShots, sideAmp, sideFreq, phase);
        s.penetration = 2.0; s.range = 140.0; s.cls = WeaponClass.MACHINE_GUN;
    }

    private static void shotgun(Spec s, String n, String sn, int price, int reward, int mag, int res, double rpm,
                                double perShell, int dmg, int pellets, double ap, int speed, double spread, boolean auto) {
        s.name = n; s.shortName = sn; s.slot = 1; s.price = price; s.reward = reward; s.auto = auto; s.mag = mag; s.reserve = res;
        s.rpm = rpm; s.reload = perShell; s.damage = dmg; s.pellets = pellets; s.armorPen = ap; s.rangeMod = 0.70; s.penetration = 1.0;
        s.speedUnits = speed; s.range = 42.0; s.spread = spread; s.bloom = 0.010; s.climb = 0.028; s.climbShots = 1;
        s.shellReload = true; s.cls = WeaponClass.SHOTGUN;
    }

    private static void pistol(Spec s, String n, String sn, int price, int reward, int mag, int res, double rpm, double reload,
                               int dmg, double ap, double rm, double pen, double spread, double bloom) {
        s.name = n; s.shortName = sn; s.slot = 2; s.price = price; s.reward = reward; s.mag = mag; s.reserve = res;
        s.rpm = rpm; s.reload = reload; s.damage = dmg; s.armorPen = ap; s.rangeMod = rm; s.penetration = pen; s.speedUnits = 240;
        s.range = 110.0; s.spread = spread; s.bloom = bloom; s.climb = bloom * 0.9; s.climbShots = 4;
        s.sideAmp = 0.010; s.sideFreq = 0.9; s.cls = WeaponClass.PISTOL;
    }

    private final Type type;
    private final String displayName;
    private final String shortName;
    private final int slotNumber;
    private final int price;
    private final int killReward;
    private final boolean automatic;
    private final boolean melee;
    private final boolean silenced;
    private final boolean sniperScope;
    private final int pelletsPerShot;
    private final WeaponClass weaponClass;
    private final int teamAffinity;

    private final int maxMagAmmo;
    private final int initialReserveAmmo;
    private final double fireInterval;
    private final double baseReloadDuration;
    private double reloadDuration;
    private final int baseDamage;
    private final int bodyDamage;
    private final int headDamage;
    private final double armorPenetration;
    private final double rangeModifier;
    private final double penetrationPower;
    private final double maxRange;
    private final double baseSpread;
    private final double recoilPerShot;
    private final double moveSpeedMultiplier;
    private final double adsZoomFactor;
    private final boolean shellByShellReload;
    private final double sprayClimb, spraySideAmp, spraySideFreq, spraySidePhase;
    private final int sprayClimbShots;

    private int magAmmo;
    private int reserveAmmo;
    private double fireCooldown;
    private boolean reloading;
    private double reloadTimer;
    private double sprayIndex = 0.0;
    private int lastShotSprayIndex = 0;

    private int scopeZoomLevel = 0; // 0 = нет, 1 = зум x2.8, 2 = зум x6 (для AWP / SSG 08)
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
        Spec s = spec(type);
        this.displayName = s.name;
        this.shortName = s.shortName;
        this.slotNumber = s.slot;
        this.price = s.price;
        this.killReward = s.reward;
        this.automatic = s.auto;
        this.melee = (s.cls == WeaponClass.MELEE);
        this.silenced = s.silenced;
        this.sniperScope = s.scope;
        this.pelletsPerShot = s.pellets;
        this.weaponClass = s.cls;
        this.teamAffinity = s.team;
        this.maxMagAmmo = s.mag;
        this.initialReserveAmmo = s.reserve;
        this.fireInterval = 60.0 / Math.max(1.0, s.rpm);
        this.baseReloadDuration = s.reload;
        this.reloadDuration = s.reload;
        this.baseDamage = s.damage;
        this.armorPenetration = s.armorPen;
        this.rangeModifier = s.rangeMod;
        this.penetrationPower = s.penetration;
        this.maxRange = s.range;
        this.baseSpread = s.spread;
        this.recoilPerShot = s.bloom;
        this.moveSpeedMultiplier = s.speedUnits / 250.0;
        this.adsZoomFactor = s.adsZoom;
        this.shellByShellReload = s.shellReload;
        this.sprayClimb = s.climb;
        this.sprayClimbShots = Math.max(1, s.climbShots);
        this.spraySideAmp = s.sideAmp;
        this.spraySideFreq = s.sideFreq;
        this.spraySidePhase = s.sidePhase;
        if (type == Type.KNIFE) {
            this.bodyDamage = 55; this.headDamage = 95;
        } else if (type == Type.ZEUS) {
            this.bodyDamage = 500; this.headDamage = 500;
        } else if (type == Type.RPG7) {
            this.bodyDamage = 260; this.headDamage = 320;
        } else {
            this.bodyDamage = s.damage;
            this.headDamage = (int) Math.round(s.damage * HitGroup.HEAD.multiplier);
        }
        resetAmmo();
    }

    public void resetAmmo() {
        this.magAmmo = maxMagAmmo;
        this.reserveAmmo = initialReserveAmmo;
        this.fireCooldown = 0.0;
        this.reloading = false;
        this.reloadTimer = 0.0;
        this.reloadDuration = baseReloadDuration;
        this.recoilBloom = 0.0;
        this.recoilKick = 0.0;
        this.muzzleFlashTimer = 0.0;
        this.equipProgress = 1.0;
        this.slashTimer = 0.0;
        this.scopeZoomLevel = 0;
        this.adsActive = false;
        this.inspectTimer = 0.0;
        this.sprayIndex = 0.0;
        this.lastShotSprayIndex = 0;
    }

    public void refillReserveAmmo() {
        this.magAmmo = maxMagAmmo;
        this.reserveAmmo = initialReserveAmmo;
    }

    /** Подобранное с земли оружие сохраняет реальный остаток патронов (как в CS2). */
    public void setAmmoState(int mag, int reserve) {
        this.magAmmo = Math.max(0, Math.min(maxMagAmmo, mag));
        this.reserveAmmo = Math.max(0, reserve);
    }

    public void onEquip() {
        this.reloading = false;
        this.reloadTimer = 0.0;
        this.equipProgress = 0.0;
        this.scopeZoomLevel = 0;
        this.adsActive = false;
        this.inspectTimer = 0.0;
        this.sprayIndex = 0.0;
        // Реальное время доставания: пистолеты быстрее, тяжёлое оружие — медленнее
        double deploy = (weaponClass == WeaponClass.PISTOL || melee) ? 0.16
                : (weaponClass == WeaponClass.MACHINE_GUN || weaponClass == WeaponClass.SNIPER ? 0.34 : 0.24);
        this.fireCooldown = Math.max(this.fireCooldown, deploy);
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
            if (scopeZoomLevel == 1) return type == Type.SSG08 ? 0.40 : 0.34;
            if (scopeZoomLevel == 2) return type == Type.SSG08 ? 0.18 : 0.15;
        } else if (adsActive) {
            return adsZoomFactor;
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
        recoilKick = Math.max(0.0, recoilKick - dt * (weaponClass == WeaponClass.SNIPER || type == Type.RPG7 || type == Type.NOVA ? 3.5 : 6.5));

        // Восстановление паттерна отдачи CS2: после паузы прицел плавно возвращается к первому выстрелу
        if (fireCooldown <= 0.0 && sprayIndex > 0.0) {
            double recoverRate = (weaponClass == WeaponClass.PISTOL) ? 16.0 : 22.0;
            sprayIndex = Math.max(0.0, sprayIndex - dt * recoverRate);
        }

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
                    reloading = false;
                } else if (shellByShellReload) {
                    // Реалистичная поштучная зарядка дробовика: по одному патрону 12 калибра в трубчатый магазин
                    if (reserveAmmo > 0 && magAmmo < maxMagAmmo) {
                        magAmmo++;
                        reserveAmmo--;
                    }
                    if (magAmmo < maxMagAmmo && reserveAmmo > 0) {
                        reloadDuration = baseReloadDuration;
                        reloadTimer = reloadDuration;
                        return;
                    }
                    reloading = false;
                } else {
                    int needed = maxMagAmmo - magAmmo;
                    int transfer = Math.min(needed, reserveAmmo);
                    magAmmo += transfer;
                    reserveAmmo -= transfer;
                    reloading = false;
                }
                reloadTimer = 0.0;
                reloadDuration = baseReloadDuration;
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
        if (shellByShellReload) {
            // Подготовка к зарядке (перехват дробовика) + первый патрон
            reloadDuration = baseReloadDuration + 0.38;
        } else if (magAmmo == 0) {
            // Перезарядка с пустым магазином дольше: нужно дослать патрон затворной задержкой/рукоятью затвора
            reloadDuration = baseReloadDuration;
        } else {
            // Тактическая перезарядка (патрон в патроннике) — быстрее на ~18%
            reloadDuration = baseReloadDuration * 0.82;
        }
        reloadTimer = reloadDuration;
        return true;
    }

    public boolean canFire() {
        if (fireCooldown > 0.0 || equipProgress < 0.75) {
            return false;
        }
        if (reloading) {
            // Дробовик можно перебить выстрелом прямо во время поштучной зарядки (как в CS2)
            if (shellByShellReload && magAmmo > 0) {
                reloading = false;
                reloadTimer = 0.0;
                reloadDuration = baseReloadDuration;
                return true;
            }
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
            // Прицельный режим AUG / SG 553 снижает скорострельность (реальная механика CS2)
            fireCooldown = fireInterval * ((adsActive && adsZoomFactor < 0.7) ? 1.28 : 1.0);
            magAmmo = Math.max(0, magAmmo - 1);
            recoilBloom = Math.min(0.075, recoilBloom + recoilPerShot);
            recoilKick = 1.0;
            muzzleFlashTimer = silenced ? 0.025 : 0.062;
            lastShotSprayIndex = (int) Math.floor(sprayIndex + 1e-6);
            sprayIndex = Math.min(48.0, Math.floor(sprayIndex + 1e-6) + 1.0);
            if (sniperScope && magAmmo > 0) {
                scopeZoomLevel = 0;
            }
        }
    }

    /**
     * Детерминированный паттерн распыления CS2 для выстрела №n (0 = первый, идеально точный).
     * Возвращает {смещение по рысканью (рад), смещение вверх (рад)}: сначала подъём «вверх», затем «змейка» влево-вправо.
     */
    public double[] getSprayOffset(double n) {
        if (melee || n <= 0.0) return new double[]{0.0, 0.0};
        double climbN = Math.min(n, sprayClimbShots);
        double pitch = sprayClimb * climbN * (1.0 - 0.025 * climbN) + sprayClimb * 0.12 * Math.max(0.0, n - sprayClimbShots);
        double sideStart = sprayClimbShots * 0.65;
        double yaw;
        if (n <= sideStart) {
            yaw = sprayClimb * 0.12 * n * Math.sin(spraySidePhase + 0.7);
        } else {
            double k = n - sideStart;
            double ramp = Math.min(1.0, k / 3.0);
            yaw = sprayClimb * 0.12 * sideStart * Math.sin(spraySidePhase + 0.7)
                    + spraySideAmp * ramp * Math.sin(k * spraySideFreq + spraySidePhase);
        }
        return new double[]{yaw, pitch};
    }

    public double[] getCurrentSprayOffset() { return getSprayOffset(sprayIndex); }
    public double[] getLastShotSprayOffset() { return getSprayOffset(lastShotSprayIndex); }
    public double getSprayIndex() { return sprayIndex; }

    /**
     * Реалистичная неточность CS2: первый выстрел точен, движение быстрее 34% от макс. скорости резко сбивает прицел
     * (требуется «контр-стрейф»), прыжок — огромный разброс, приседание — +48% точности.
     */
    public double computeCurrentSpread(double speedFraction, boolean airborne, boolean crouching) {
        if (melee) return 0.0;
        double spread = baseSpread;
        if (sniperScope && scopeZoomLevel > 0) {
            spread = (scopeZoomLevel == 2) ? 0.0006 : 0.0014;
            if (type == Type.SSG08) spread *= 0.8;
        } else if (adsActive) {
            spread *= (adsZoomFactor < 0.7) ? 0.42 : 0.62;
        }
        spread += recoilBloom;

        double moveThreshold = 0.34;
        if (speedFraction > moveThreshold) {
            double t = Math.min(1.0, (speedFraction - moveThreshold) / (1.0 - moveThreshold));
            double movePen;
            switch (weaponClass) {
                case SNIPER:      movePen = (type == Type.SSG08) ? 0.030 : 0.075; break;
                case MACHINE_GUN: movePen = 0.034; break;
                case RIFLE:       movePen = 0.026; break;
                case SHOTGUN:     movePen = 0.010; break;
                case SMG:         movePen = 0.011; break;
                case PISTOL:      movePen = 0.009; break;
                default:          movePen = 0.015; break;
            }
            spread += movePen * t;
        }
        if (airborne) spread += (type == Type.SSG08 && scopeZoomLevel > 0) ? 0.004 : 0.062;
        if (crouching) spread *= 0.52;
        return spread;
    }

    /** Совместимость со старым API (булевы флаги движения). */
    public double computeCurrentSpread(boolean moving, boolean silentWalking, boolean airborne, boolean crouching) {
        return computeCurrentSpread(moving ? (silentWalking ? 0.5 : 1.0) : 0.0, airborne, crouching);
    }

    /** Урон пули по зоне попадания с учётом реального падения урона на дистанции CS2 (rangeModifier ^ (юниты/500)). */
    public double computeRawHitDamage(HitGroup hg, double distanceMeters) {
        if (type == Type.ZEUS) return 500.0;
        if (type == Type.RPG7) return hg == HitGroup.HEAD ? 320 : 260;
        double units = distanceMeters * 39.37;
        double rangeFactor = Math.pow(rangeModifier, units / 500.0);
        return baseDamage * hg.multiplier * rangeFactor;
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

        double forwardDist;
        switch (weaponClass) {
            case SNIPER: case MACHINE_GUN: case LAUNCHER: forwardDist = 0.86; break;
            case RIFLE:   forwardDist = (type == Type.M4A1S) ? 0.86 : ((type == Type.AUG || type == Type.FAMAS) ? 0.64 : 0.76); break;
            case SHOTGUN: forwardDist = 0.78; break;
            case SMG:     forwardDist = (type == Type.P90 || type == Type.MAC10) ? 0.52 : 0.62; break;
            default:      forwardDist = (type == Type.USP_S) ? 0.66 : 0.55; break;
        }
        double sideDist = adsActive ? 0.06 : 0.21;
        return eye.add(forward.scale(forwardDist)).add(right.scale(sideDist)).add(camUp.scale(-0.14));
    }

    public void renderViewModel(Renderer3D renderer, double walkBobPhase, double walkSpeedNorm,
                                double swayX, double swayY) {
        renderViewModel(renderer, walkBobPhase, walkSpeedNorm, swayX, swayY, GameSettings.WeaponSkin.STANDARD);
    }

    public void renderViewModel(Renderer3D renderer, double walkBobPhase, double walkSpeedNorm,
                                double swayX, double swayY, GameSettings.WeaponSkin skin) {
        if ((sniperScope && scopeZoomLevel > 0) || (adsActive && adsZoomFactor < 0.7)) {
            return;
        }

        double bobX = Math.sin(walkBobPhase) * 0.013 * walkSpeedNorm;
        double bobY = Math.abs(Math.cos(walkBobPhase)) * (-0.012) * walkSpeedNorm;
        double equipDrop = (1.0 - equipProgress) * 0.24;

        double rotX = -recoilKick * (type == Type.DEAGLE || type == Type.AWP || type == Type.RPG7 || type == Type.SSG08 || type == Type.NOVA ? 0.23 : (weaponClass == WeaponClass.PISTOL ? 0.17 : 0.13));
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
        double oz = (type == Type.AWP || type == Type.M249 || type == Type.RPG7 || type == Type.NEGEV || type == Type.SSG08) ? 0.92 : (slotNumber == 1 ? 0.72 : 0.52);
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
        if (Bot.useGlbModels && renderGlbModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands)) return;
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
            default:     renderNewWeaponModel(renderer, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
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

    // ===================== 11.0: GLB-ОРУЖИЕ И РУКИ ОТ 1-ГО ЛИЦА =====================
    private static CharacterRig fpRig;
    private static final CharacterRig.Params FP_PARAMS = new CharacterRig.Params();
    private final double[] glbView = new double[12];
    private final double[] glbTmp = new double[3];

    private boolean renderGlbModel(Renderer3D r, double ox, double oy, double oz,
                                   double rotX, double rotY, double rotZ,
                                   GameSettings.WeaponSkin skin, boolean drawHands) {
        GunModels.Gun gun = GunModels.get(type);
        if (gun == null) return false;
        if (drawHands && fpRig == null) {
            fpRig = CharacterRig.create(CharacterRig.Variant.CT_SWAT);
            if (fpRig == null) return false;
        }
        double cx = Math.cos(rotX), sx = Math.sin(rotX);
        double cy = Math.cos(rotY), sy = Math.sin(rotY);
        double cz = Math.cos(rotZ), sz = Math.sin(rotZ);
        // R = Rz * Ry * Rx (как в transformViewPoint)
        double[] R = {
                cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
                sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
                -sy,     cy * sx,                cy * cx};
        boolean pistol = gun.spec.file.contains("pistol");
        double gs = gun.spec.scale;
        // база вьюмодели 11.0 (выверена по GLB-рукам): спуск оружия в точке хвата
        double bx = ox + (pistol ? -0.075 : -0.055);
        double by = oy + (pistol ? 0.115 : 0.100);
        double bz = oz + (pistol ? 0.075 : 0.020);
        double offX = 0.0, offY = 0.0, offZ = 0.0;
        if (!drawHands) { // витрина: центр модели в точке вращения
            offY = -(gun.minY + gun.maxY) * 0.5 * gs;
            offZ = -(gun.minZ + gun.maxZ) * 0.5 * gs;
        }
        for (int i = 0; i < 3; i++) {
            glbView[i * 4] = R[i * 3] * gs; glbView[i * 4 + 1] = R[i * 3 + 1] * gs; glbView[i * 4 + 2] = R[i * 3 + 2] * gs;
        }
        glbView[3] = bx + R[0] * offX + R[1] * offY + R[2] * offZ;
        glbView[7] = by + R[3] * offX + R[4] * offY + R[5] * offZ;
        glbView[11] = bz + R[6] * offX + R[7] * offY + R[8] * offZ;

        CharacterRig.Params p = FP_PARAMS;
        p.recoil = recoilKick;
        p.reload = (reloading && reloadDuration > 0.0) ? 1.0 - reloadTimer / reloadDuration : -1.0;
        p.death = -1;
        p.flash = false;
        if (drawHands) {
            fpRig.renderFirstPerson(r, gun, glbView, p, skin, true);
        } else {
            p.recoil = 0; p.reload = -1;
            CharacterRig.animateGun(gun, p);
            int[] cols = gun.colors(skin, null);
            r.drawMesh(gun.model, 0, gun.pose.skin, glbView, cols, true, null, 1.15);
            CharacterRig.drawAttachments(r, gun, glbView, true, skin);
        }
        if (muzzleFlashTimer > 0.0 && drawHands) {
            double[] m = gun.muzzle;
            CharacterRig.gunViewPoint(glbView, m[0], m[1], m[2] + CharacterRig.extraMuzzle(gun) + 0.01, glbTmp);
            double span = silenced ? 0.06 : (weaponClass == WeaponClass.SNIPER || weaponClass == WeaponClass.MACHINE_GUN ? 0.19
                    : (weaponClass == WeaponClass.PISTOL ? 0.11 : 0.15));
            renderMuzzleFlash3D(r, glbTmp[0], glbTmp[1], glbTmp[2], span);
        }
        return true;
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
    public int getBaseDamage() { return baseDamage; }
    public double getArmorPenetration() { return armorPenetration; }
    public double getRangeModifier() { return rangeModifier; }
    public double getPenetrationPower() { return penetrationPower; }
    public double getMaxRange() { return maxRange; }
    public double getMoveSpeedMultiplier() { return moveSpeedMultiplier; }
    public int getMoveSpeedUnits() { return (int) Math.round(moveSpeedMultiplier * 250.0); }
    public double getFireInterval() { return fireInterval; }
    public int getRoundsPerMinute() { return (int) Math.round(60.0 / fireInterval); }
    public double getBaseReloadDuration() { return baseReloadDuration; }
    public boolean hasMuzzleFlash() { return muzzleFlashTimer > 0.0; }
    public String getShortName() { return shortName; }
    public WeaponClass getWeaponClass() { return weaponClass; }
    public int getTeamAffinity() { return teamAffinity; }
    public boolean isShellByShellReload() { return shellByShellReload; }
    public double getRecoilKick() { return recoilKick; }

    /** Статические сведения о типе без создания экземпляра (для магазина, ботов, лент убийств). */
    private static final java.util.EnumMap<Type, Weapon> PROTOTYPES = new java.util.EnumMap<>(Type.class);
    public static synchronized Weapon prototype(Type t) {
        return PROTOTYPES.computeIfAbsent(t, Weapon::new);
    }
    public static String nameOf(Type t) { return prototype(t).getDisplayName(); }
    public static WeaponClass classOf(Type t) { return prototype(t).getWeaponClass(); }

    /** Прострел стен: любое огнестрельное оружие с силой пробития > 0 (как в CS2). */
    public boolean canWallbang() {
        return !melee && type != Type.ZEUS && type != Type.RPG7 && penetrationPower > 0.0;
    }

    /** Доля урона, сохраняемая после пробития препятствия (зависит от калибра). */
    public double getWallbangDamageFactor() {
        switch (type) {
            case AWP:    return 0.80;
            case SSG08:  return 0.70;
            case NEGEV: case M249: return 0.66;
            case AK47: case GALIL: case SG553: return 0.62;
            case DEAGLE: return 0.62;
            case M4A4: case M4A1S: case AUG: case FAMAS: return 0.56;
            case FIVESEVEN: case TEC9: return 0.52;
            case P90:    return 0.48;
            default:     return weaponClass == WeaponClass.SHOTGUN ? 0.30 : 0.40;
        }
    }

    // =====================================================================================
    //  НОВЫЕ 3D-МОДЕЛИ CS-2 JAVA 9.0 — компактный контекст рисования относительно точки хвата
    // =====================================================================================
    private Renderer3D mr;
    private double mx, my, mz, mrx, mry, mrz;

    private void ctx(Renderer3D r, double ox, double oy, double oz, double rx, double ry, double rz) {
        mr = r; mx = ox; my = oy; mz = oz; mrx = rx; mry = ry; mrz = rz;
    }

    private void bx(double dx, double dy, double dz, double sx, double sy, double sz, double drx, int col) {
        mr.drawViewModelBox(mx + dx, my + dy, mz + dz, sx, sy, sz, mrx + drx, mry, mrz, mx, my, mz, Renderer3D.TEX_SOLID, col);
    }

    private void cy(double dx, double dy, double dz, double r0, double r1, double len, double drx, int segs, int col) {
        mr.drawViewModelCylinder(mx + dx, my + dy, mz + dz, r0, r1, len, mrx + drx, mry, mrz, mx, my, mz, segs, col);
    }

    private void cyY(double dx, double dy, double dz, double r0, double r1, double len, double drx, double dry, int segs, int col) {
        mr.drawViewModelCylinder(mx + dx, my + dy, mz + dz, r0, r1, len, mrx + drx, mry + dry, mrz, mx, my, mz, segs, col);
    }

    private double magPull() {
        if (reloading && reloadDuration > 0.0 && !shellByShellReload) {
            double p = 1.0 - (reloadTimer / reloadDuration);
            return Math.sin(p * Math.PI) * 0.14;
        }
        return 0.0;
    }

    private void flash(double dx, double dy, double dz, double span, boolean drawHands) {
        if (muzzleFlashTimer > 0.0 && drawHands) renderMuzzleFlash3D(mr, mx + dx, my + dy, mz + dz, span);
    }

    private void scope(double dy, double dz, double len, double rad, int body, int ringCol) {
        cy(0, dy, dz, rad, rad, len, 0, 12, body);
        cy(0, dy, dz + len * 0.55, rad * 1.28, rad * 1.18, len * 0.22, 0, 12, body);   // объектив
        cy(0, dy, dz - len * 0.52, rad * 1.12, rad * 1.12, len * 0.16, 0, 12, body);   // окуляр
        bx(0, dy, dz + len * 0.66, rad * 1.6, rad * 1.6, 0.004, 0, 0x3C78B8);           // голубоватый блик линзы
        bx(0, dy - rad - 0.008, dz - len * 0.22, rad * 1.1, 0.016, 0.018, 0, ringCol);  // кольца крепления
        bx(0, dy - rad - 0.008, dz + len * 0.22, rad * 1.1, 0.016, 0.018, 0, ringCol);
        cyY(rad + 0.004, dy, dz, 0.008, 0.008, 0.016, 0, 1.5708, 8, ringCol);           // барабан поправок
    }

    private void renderM4a4Model(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int black = c ? skin.primary : 0x24282E, dark = c ? skin.trim : 0x16191E, rail = c ? skin.accent : 0x3A414B, tan = 0x8C7A58;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.03, 0.22, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, -0.016, -0.215, 0.040, 0.066, 0.13, 0, black);          // телескопический приклад
        cy(0, 0.004, -0.115, 0.015, 0.015, 0.11, 0, 10, dark);        // буферная трубка
        bx(0, -0.084, -0.040, 0.032, 0.094, 0.042, 0.26, dark);       // пистолетная рукоять
        bx(0, -0.044, 0.004, 0.014, 0.026, 0.050, 0, dark);           // спусковая скоба
        bx(0, -0.014, 0.030, 0.044, 0.040, 0.215, 0, black);          // нижний ресивер
        bx(0, 0.022, 0.020, 0.046, 0.036, 0.225, 0, black);           // верхний ресивер
        bx(0, 0.046, 0.030, 0.022, 0.010, 0.205, 0, rail);            // планка Пикатинни
        bx(0, 0.060, -0.050, 0.018, 0.024, 0.030, 0, dark);           // откидной целик
        cyY(0.026, 0.020, -0.030 - recoilKick * 0.035, 0.007, 0.007, 0.030, 0, 1.45, 8, 0x8E98A4); // рукоять заряжания
        bx(0, -0.090 - mp, 0.078, 0.030, 0.112, 0.052, -0.12, c ? skin.accent : tan); // магазин STANAG (койот)
        cy(0, 0.018, 0.240, 0.027, 0.027, 0.19, 0, 12, rail);         // квад-цевьё
        bx(0, 0.046, 0.240, 0.020, 0.010, 0.18, 0, dark);
        bx(0, 0.062, 0.322, 0.012, 0.040, 0.016, 0, dark);            // мушка
        cy(0, 0.018, 0.425, 0.0095, 0.0095, 0.22, 0, 12, dark);       // ствол
        cy(0, 0.018, 0.548, 0.0135, 0.0130, 0.042, 0, 10, dark);      // пламегаситель «птичья клетка»
        flash(0, 0.018, 0.58, 0.15, drawHands);
    }

    private void renderFamasModel(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int body = c ? skin.primary : 0x2B3036, dark = c ? skin.trim : 0x171A1F, accent = c ? skin.accent : 0x3E4650;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, 0.06, 0.26, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, 0.004, -0.040, 0.052, 0.076, 0.40, 0, body);            // булл-пап корпус «Клерон»
        bx(0, -0.032, -0.215, 0.050, 0.060, 0.06, 0, dark);           // затыльник
        bx(0, -0.088, 0.075, 0.032, 0.092, 0.040, 0.24, dark);        // рукоять со спуском впереди магазина
        bx(0, -0.050, 0.110, 0.014, 0.026, 0.060, 0, dark);           // длинная скоба
        bx(0, -0.090 - mp, -0.075, 0.030, 0.100, 0.052, -0.04, accent); // магазин за рукоятью
        // Знаменитая длинная ручка для переноски с прицельными приспособлениями
        bx(0, 0.088, 0.000, 0.016, 0.016, 0.32, 0, dark);
        bx(0, 0.062, -0.150, 0.016, 0.040, 0.020, 0, dark);
        bx(0, 0.062, 0.150, 0.016, 0.040, 0.020, 0, dark);
        cyY(0, 0.050, -0.005 - recoilKick * 0.04, 0.007, 0.007, 0.030, 0, 0.0, 8, 0x8E98A4);
        bx(0, 0.010, 0.215, 0.046, 0.054, 0.10, 0, body);             // нос цевья
        cy(0, 0.010, 0.330, 0.010, 0.010, 0.15, 0, 12, dark);         // ствол
        cy(0, 0.010, 0.410, 0.014, 0.013, 0.030, 0, 10, dark);        // дульный тормоз-гранатомёт
        cy(0.016, -0.018, 0.250, 0.005, 0.005, 0.13, 0.05, 6, dark);  // сложенные сошки
        cy(-0.016, -0.018, 0.250, 0.005, 0.005, 0.13, 0.05, 6, dark);
        flash(0, 0.010, 0.44, 0.13, drawHands);
    }

    private void renderGalilModel(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int steel = c ? skin.primary : 0x363B42, dark = c ? skin.trim : 0x1A1D22, wood = c ? skin.accent : 0x6A4A2E;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.03, 0.20, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        // Складной трубчатый приклад
        cy(0, 0.020, -0.200, 0.008, 0.008, 0.17, 0, 8, dark);
        cy(0, -0.030, -0.200, 0.008, 0.008, 0.17, 0, 8, dark);
        bx(0, -0.004, -0.292, 0.016, 0.070, 0.018, 0, dark);
        bx(0, -0.086, -0.042, 0.034, 0.096, 0.044, 0.24, dark);
        bx(0, 0.008, 0.015, 0.048, 0.056, 0.245, 0, steel);           // фрезерованная коробка (ветвь АК)
        cy(0, 0.036, 0.005, 0.022, 0.021, 0.22, 0, 10, steel);
        bx(0, 0.070, -0.070, 0.020, 0.040, 0.018, 0, dark);           // высокий диоптрический целик
        cyY(-0.028, 0.026, 0.040 - recoilKick * 0.045, 0.008, 0.008, 0.040, 0, -1.4, 8, 0x9CA6B2); // поднятая рукоять затвора
        bx(0, -0.070 - mp, 0.078, 0.032, 0.086, 0.052, -0.16, dark);  // изогнутый 35-зарядный магазин
        bx(0, -0.142 - mp, 0.100, 0.031, 0.084, 0.050, -0.36, dark);
        bx(0, 0.004, 0.220, 0.046, 0.052, 0.165, 0, wood);            // деревянное цевьё
        cy(0, 0.036, 0.215, 0.018, 0.018, 0.14, 0, 10, dark);
        cy(0, 0.014, 0.420, 0.0105, 0.010, 0.28, 0, 12, dark);
        bx(0, 0.040, 0.500, 0.012, 0.044, 0.020, 0, dark);            // мушка
        cy(0, 0.014, 0.565, 0.013, 0.012, 0.036, 0, 10, dark);
        flash(0, 0.014, 0.59, 0.15, drawHands);
    }

    private void renderSg553Model(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int body = c ? skin.primary : 0x2C3138, dark = c ? skin.trim : 0x15181C, orange = c ? skin.accent : 0x9C5A2A;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.03, 0.20, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, -0.010, -0.210, 0.040, 0.078, 0.15, 0, body);           // скелетный приклад
        bx(0, -0.010, -0.210, 0.042, 0.040, 0.10, 0, 0x0E1013);
        bx(0, -0.086, -0.042, 0.034, 0.096, 0.044, 0.24, dark);
        bx(0, 0.006, 0.020, 0.050, 0.060, 0.25, 0, body);
        bx(0, -0.080 - mp, 0.080, 0.030, 0.104, 0.052, -0.20, 0xB88A4E); // полупрозрачный магазин
        bx(0, 0.002, 0.225, 0.050, 0.054, 0.17, 0, orange);           // фирменное рыжее цевьё SIG
        scope(0.074, 0.030, 0.17, 0.020, dark, body);                 // оптика 4x (прицельный режим ПКМ)
        cy(0, 0.016, 0.415, 0.010, 0.0095, 0.24, 0, 12, dark);
        cy(0, 0.016, 0.540, 0.014, 0.013, 0.044, 0, 10, dark);
        flash(0, 0.016, 0.57, 0.14, drawHands);
    }

    private void renderAugModel(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int olive = c ? skin.primary : 0x55603E, dark = c ? skin.trim : 0x1A1D20, steel = c ? skin.accent : 0x3A4048;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, 0.07, 0.24, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, -0.004, -0.090, 0.058, 0.084, 0.30, 0, olive);          // обтекаемый корпус булл-пап Steyr
        bx(0, -0.020, -0.235, 0.054, 0.066, 0.04, 0, dark);
        bx(0, -0.072, 0.085, 0.040, 0.050, 0.11, 0, olive);           // огромная спусковая скоба-«ручка»
        bx(0, -0.094, 0.068, 0.030, 0.088, 0.036, 0.20, olive);
        bx(0, -0.092 - mp, -0.050, 0.030, 0.104, 0.050, -0.06, 0x8FA2A8); // прозрачный магазин
        bx(0, 0.006, 0.120, 0.044, 0.050, 0.12, 0, steel);
        bx(0, -0.060, 0.205, 0.026, 0.080, 0.030, 0.10, olive);       // передняя складная рукоять
        scope(0.078, 0.030, 0.19, 0.021, dark, dark);                 // встроенный прицел Swarovski 1.5x
        cy(0, 0.012, 0.330, 0.0105, 0.010, 0.22, 0, 12, dark);
        cy(0, 0.012, 0.445, 0.014, 0.013, 0.040, 0, 10, dark);
        flash(0, 0.012, 0.47, 0.13, drawHands);
    }

    private void renderSsg08Model(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int stock = c ? skin.primary : 0x3A4350, dark = c ? skin.trim : 0x16191D, steel = c ? skin.accent : 0x2A2F36;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.03, 0.22, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, -0.024, -0.230, 0.038, 0.084, 0.20, -0.04, stock);      // лёгкое полимерное ложе Scout
        bx(0, -0.030, -0.335, 0.040, 0.090, 0.014, -0.04, 0x0E1013);  // резиновый затыльник
        bx(0, -0.076, -0.050, 0.032, 0.090, 0.042, 0.28, stock);
        bx(0, -0.006, 0.120, 0.040, 0.050, 0.34, 0, stock);           // цевьё
        bx(0, 0.022, 0.000, 0.040, 0.034, 0.17, 0, steel);            // ствольная коробка
        cyY(0.034, 0.026, -0.040 - recoilKick * 0.06, 0.007, 0.007, 0.045, 0, 1.45, 8, 0xA6B0BC); // рукоять продольно-скользящего затвора
        cyY(0.056, 0.026, -0.040 - recoilKick * 0.06, 0.012, 0.012, 0.010, 0, 1.45, 8, 0x14171B);
        bx(0, -0.056 - mp * 0.5, 0.040, 0.026, 0.040, 0.050, 0, dark); // отъёмный 10-зарядный магазин
        scope(0.074, 0.010, 0.25, 0.022, dark, steel);                // оптика 10x
        cy(0, 0.020, 0.440, 0.0085, 0.0075, 0.42, 0, 12, dark);       // тонкий длинный ствол
        cy(0, 0.020, 0.660, 0.012, 0.012, 0.030, 0, 10, dark);
        flash(0, 0.020, 0.69, 0.17, drawHands);
    }

    private void renderMac10Model(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int body = c ? skin.primary : 0x2E3238, dark = c ? skin.trim : 0x17191D, accent = c ? skin.accent : 0x44494F;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, 0.00, 0.13, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, 0.012, 0.030, 0.050, 0.078, 0.200, 0, body);            // штампованная коробка Ingram
        bx(0, 0.054, 0.030, 0.030, 0.010, 0.17, 0, accent);
        bx(0, 0.052, 0.040 - recoilKick * 0.05, 0.012, 0.014, 0.022, 0, 0xA0A8B2); // рукоять затвора сверху
        bx(0, -0.082, 0.010, 0.036, 0.120, 0.044, 0.06, dark);        // рукоять = шахта магазина
        if (!reloading || reloadTimer > reloadDuration * 0.62 || reloadTimer < reloadDuration * 0.28) {
            bx(0, -0.160 - mp * 0.4, 0.016, 0.028, 0.080, 0.036, 0.06, accent);
        }
        bx(0, -0.040, 0.062, 0.014, 0.026, 0.050, 0, dark);
        // Проволочный приклад и петля для ремня
        cy(0.020, 0.012, -0.120, 0.005, 0.005, 0.11, 0, 6, dark);
        cy(-0.020, 0.012, -0.120, 0.005, 0.005, 0.11, 0, 6, dark);
        bx(0, 0.000, -0.178, 0.046, 0.060, 0.010, 0, dark);
        cy(0, 0.012, 0.165, 0.012, 0.012, 0.075, 0, 12, dark);        // короткий резьбовой ствол
        flash(0, 0.012, 0.21, 0.10, drawHands);
    }

    private void renderUmp45Model(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int body = c ? skin.primary : 0x26292E, dark = c ? skin.trim : 0x141619, accent = c ? skin.accent : 0x3A3F46;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.01, 0.17, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, 0.010, -0.180, 0.034, 0.060, 0.14, 0, dark);            // складной скелетный приклад
        bx(0, 0.010, -0.180, 0.036, 0.026, 0.09, 0, 0x0B0D10);
        bx(0, -0.080, -0.030, 0.034, 0.096, 0.044, 0.22, body);
        bx(0, 0.008, 0.050, 0.050, 0.070, 0.28, 0, body);             // полимерный корпус H&K
        bx(0, 0.050, 0.050, 0.026, 0.012, 0.25, 0, accent);           // планка
        bx(0, 0.064, -0.040, 0.016, 0.022, 0.024, 0, dark);           // барабанный целик
        bx(0, 0.064, 0.170, 0.014, 0.026, 0.020, 0, dark);
        cyY(-0.030, 0.026, 0.150 - recoilKick * 0.04, 0.007, 0.007, 0.040, 0, -1.4, 8, 0x8E98A4);
        bx(0, -0.090 - mp, 0.060, 0.030, 0.110, 0.046, -0.10, dark);  // магазин .45 ACP
        cy(0, 0.010, 0.275, 0.011, 0.011, 0.12, 0, 12, dark);
        flash(0, 0.010, 0.35, 0.12, drawHands);
    }

    private void renderP90Model(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int body = c ? skin.primary : 0x2A2E33, dark = c ? skin.trim : 0x15171A, accent = c ? skin.accent : 0x3D434A;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, 0.05, 0.17, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, -0.010, -0.040, 0.058, 0.096, 0.34, 0, body);           // эргономичный булл-пап FN
        bx(0, -0.080, 0.050, 0.056, 0.020, 0.14, 0, dark);            // отверстие под большой палец
        bx(0, -0.070, 0.002, 0.030, 0.050, 0.030, 0, 0x0A0C0E);
        // Знаменитый горизонтальный 50-зарядный магазин сверху (полупрозрачный — видно патроны!)
        bx(0, 0.052 + mp * 0.4, -0.010, 0.044, 0.020, 0.27, 0, 0x6E6A5C);
        bx(0, 0.052 + mp * 0.4, -0.010, 0.030, 0.012, 0.24, 0, 0xB08A3A);
        bx(0, 0.084, 0.060, 0.026, 0.030, 0.080, 0, accent);          // коллиматор Ring Sight
        bx(0, 0.084, 0.060, 0.016, 0.018, 0.082, 0, 0x101214);
        bx(0, 0.010, 0.165, 0.046, 0.060, 0.07, 0, accent);
        cy(0, 0.012, 0.230, 0.010, 0.010, 0.07, 0, 12, dark);
        flash(0, 0.012, 0.27, 0.11, drawHands);
    }

    private void renderNovaModel(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int body = c ? skin.primary : 0x24272C, dark = c ? skin.trim : 0x121417, steel = c ? skin.accent : 0x3C424A;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        // Помповое цевьё уходит назад после каждого выстрела (передёргивание!)
        double pump = Math.max(0.0, Math.sin(Math.min(1.0, recoilKick) * Math.PI)) * 0.075;
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.03, 0.26 - pump, false, rotX, rotY, rotZ, skin);
        bx(0, -0.024, -0.210, 0.042, 0.090, 0.20, -0.06, body);       // приклад Benelli Nova (монолит)
        bx(0, -0.084, -0.050, 0.034, 0.090, 0.044, 0.30, body);
        bx(0, 0.010, 0.040, 0.048, 0.066, 0.20, 0, body);             // ствольная коробка
        bx(0, 0.048, 0.040, 0.016, 0.010, 0.18, 0, steel);
        cy(0, -0.010, 0.300, 0.016, 0.016, 0.34, 0, 12, dark);        // подствольный трубчатый магазин
        bx(0, -0.012, 0.250 - pump, 0.046, 0.044, 0.13, 0, steel);    // помповое цевьё с рёбрами
        bx(0, -0.012, 0.215 - pump, 0.048, 0.046, 0.012, 0, dark);
        bx(0, -0.012, 0.285 - pump, 0.048, 0.046, 0.012, 0, dark);
        cy(0, 0.024, 0.340, 0.0145, 0.0140, 0.44, 0, 14, dark);       // ствол 12 калибра
        bx(0, 0.042, 0.555, 0.010, 0.012, 0.012, 0, 0xE8C040);        // латунная мушка-бусина
        flash(0, 0.024, 0.59, 0.20, drawHands);
    }

    private void renderNegevModel(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                  GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int body = c ? skin.primary : 0x2F343B, dark = c ? skin.trim : 0x17191D, box = c ? skin.accent : 0x4C5440;
        ctx(r, ox, oy, oz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, ox, oy, oz, -0.03, 0.24, false, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, -0.014, -0.220, 0.044, 0.070, 0.16, 0, dark);           // складной приклад
        bx(0, -0.088, -0.040, 0.034, 0.096, 0.044, 0.24, dark);
        bx(0, 0.016, 0.030, 0.060, 0.074, 0.27, 0, body);             // массивная ствольная коробка IWI
        bx(0, 0.060, 0.000, 0.050, 0.016, 0.14, 0, dark);             // крышка ленточного приёмника
        bx(0.0, 0.096, 0.050, 0.012, 0.012, 0.11, 0, dark);           // ручка для переноски
        bx(0.0, 0.074, 0.010, 0.012, 0.034, 0.012, 0, dark);
        bx(0.0, 0.074, 0.090, 0.012, 0.034, 0.012, 0, dark);
        bx(0, -0.090 - mp, 0.060, 0.060, 0.100, 0.100, 0, box);       // короб на 150 патронов
        bx(0.034, -0.028, 0.050, 0.020, 0.030, 0.050, 0, 0xB08A3A);   // латунная лента в окне подачи
        cy(0, 0.016, 0.270, 0.026, 0.024, 0.18, 0, 12, dark);         // перфорированный кожух
        cy(0, 0.016, 0.460, 0.0125, 0.012, 0.25, 0, 12, dark);        // сменный ствол
        cy(0.018, -0.022, 0.330, 0.0055, 0.0055, 0.16, 0.06, 6, dark);// сошки
        cy(-0.018, -0.022, 0.330, 0.0055, 0.0055, 0.16, 0.06, 6, dark);
        cy(0, 0.016, 0.600, 0.016, 0.015, 0.040, 0, 10, dark);
        flash(0, 0.016, 0.63, 0.19, drawHands);
    }

    private void pistolBase(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                            GameSettings.WeaponSkin skin, boolean drawHands, int frame, int slide, double slideLen, double slideH) {
        double gx = ox - 0.02, gy = oy + 0.01, gz = oz - 0.045;
        ctx(r, gx, gy, gz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, gx, gy, gz, 0.00, 0.01, true, rotX, rotY, rotZ, skin);
        double slideBack = recoilKick * 0.050;
        bx(0, -0.055, 0.000, 0.035, 0.106, 0.046, 0.20, frame);
        bx(0, -0.016, 0.048, 0.014, 0.024, 0.042, 0, frame);
        bx(0, 0.002, 0.068, 0.036, 0.026, slideLen - 0.004, 0, frame);
        bx(0, 0.030, 0.065 - slideBack, 0.038, slideH, slideLen, 0, slide);
        bx(0, 0.030 + slideH * 0.5 + 0.004, -0.022 - slideBack, 0.022, 0.009, 0.010, 0, 0xEDF4FA);
        bx(0, 0.030 + slideH * 0.5 + 0.004, 0.060 + slideLen * 0.45 - slideBack, 0.007, 0.010, 0.012, 0, 0x48FF88);
    }

    private void renderUspModel(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        pistolBase(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands, c ? skin.trim : 0x1C1F24, c ? skin.primary : 0x2E3339, 0.19, 0.036);
        cy(0, 0.028, 0.190, 0.0095, 0.0095, 0.05, 0, 10, 0x22262C);
        cy(0, 0.028, 0.275, 0.0175, 0.0175, 0.14, 0, 14, c ? skin.accent : 0x15181C); // фирменный глушитель USP-S
        cy(0, 0.028, 0.348, 0.0150, 0.0150, 0.008, 0, 14, 0x2A2F36);
        flash(0, 0.028, 0.36, 0.05, drawHands);
    }

    private void renderP250Model(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        pistolBase(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands, c ? skin.trim : 0x22252A, c ? skin.primary : 0x8E979F, 0.172, 0.034);
        bx(0, 0.030, 0.150 - recoilKick * 0.05, 0.039, 0.020, 0.030, 0, c ? skin.accent : 0x5E666E); // двухтонный срез затвора
        cy(0, 0.028, 0.160, 0.009, 0.009, 0.03, 0, 10, 0x22262C);
        flash(0, 0.028, 0.19, 0.10, drawHands);
    }

    private void renderFiveSevenModel(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                      GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        pistolBase(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands, c ? skin.trim : 0x5A5446, c ? skin.primary : 0x2B2F34, 0.20, 0.030);
        bx(0, -0.010, 0.120, 0.034, 0.016, 0.060, 0, c ? skin.accent : 0x4A4538); // планка под фонарь
        cy(0, 0.026, 0.170, 0.0085, 0.0085, 0.03, 0, 10, 0x22262C);
        flash(0, 0.026, 0.20, 0.11, drawHands);
    }

    private void renderTec9Model(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                 GameSettings.WeaponSkin skin, boolean drawHands) {
        boolean c = skin != GameSettings.WeaponSkin.STANDARD;
        int body = c ? skin.primary : 0x2A2D31, dark = c ? skin.trim : 0x141618, accent = c ? skin.accent : 0x3E434A;
        double gx = ox - 0.02, gy = oy + 0.01, gz = oz - 0.045;
        ctx(r, gx, gy, gz, rotX, rotY, rotZ);
        if (drawHands) drawTacticalHandsAndGloves(r, gx, gy, gz, 0.00, 0.01, true, rotX, rotY, rotZ, skin);
        double mp = magPull();
        bx(0, -0.055, 0.000, 0.034, 0.100, 0.044, 0.18, dark);
        bx(0, -0.016, 0.046, 0.014, 0.024, 0.040, 0, dark);
        bx(0, 0.022, 0.080, 0.044, 0.050, 0.22, 0, body);             // штампованная коробка Intratec
        bx(0, 0.050, 0.060 - recoilKick * 0.05, 0.010, 0.012, 0.022, 0, 0xA0A8B2);
        bx(0, -0.060 - mp, 0.095, 0.026, 0.110, 0.034, 0.0, accent);  // магазин ПЕРЕД спусковой скобой!
        cy(0, 0.022, 0.215, 0.016, 0.016, 0.07, 0, 12, accent);        // перфорированный кожух ствола
        cy(0, 0.022, 0.260, 0.009, 0.009, 0.03, 0, 10, dark);
        flash(0, 0.022, 0.28, 0.11, drawHands);
    }

    private void renderNewWeaponModel(Renderer3D r, double ox, double oy, double oz, double rotX, double rotY, double rotZ,
                                      GameSettings.WeaponSkin skin, boolean drawHands) {
        switch (type) {
            case M4A4:      renderM4a4Model(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case FAMAS:     renderFamasModel(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case GALIL:     renderGalilModel(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case SG553:     renderSg553Model(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case AUG:       renderAugModel(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case SSG08:     renderSsg08Model(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case MAC10:     renderMac10Model(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case UMP45:     renderUmp45Model(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case P90:       renderP90Model(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case NOVA:      renderNovaModel(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case NEGEV:     renderNegevModel(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case USP_S:     renderUspModel(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case P250:      renderP250Model(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case FIVESEVEN: renderFiveSevenModel(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            case TEC9:      renderTec9Model(r, ox, oy, oz, rotX, rotY, rotZ, skin, drawHands); break;
            default: break;
        }
    }
}
