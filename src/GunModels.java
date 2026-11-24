/**
 * CS-2 JAVA 11.0 — библиотека 3D-моделей оружия (GLB «Flat Guns West/East», CC0).
 *
 * Сопоставляет 26 стволов CS2 с GLB-моделями, задаёт масштаб, перекраску материалов под скины,
 * точки хвата (рукоять, цевьё), дуло, а также процедурные навесы (глушитель, оптика, барабан пулемёта).
 * Начало координат модели — спусковой крючок, ствол смотрит в +Z, верх — +Y.
 */
public final class GunModels {

    public static final class Spec {
        public final String file;
        public final double scale;
        public final boolean suppressor;
        public final boolean scope;
        public final boolean drum;       // коробчатый магазин пулемёта
        public final int tint;           // базовый цвет корпуса (0 = стандарт модели)
        public final int furniture;      // цвет фурнитуры (дерево/полимер), 0 = стандарт

        Spec(String file, double scale, boolean suppressor, boolean scope, boolean drum, int tint, int furniture) {
            this.file = file;
            this.scale = scale;
            this.suppressor = suppressor;
            this.scope = scope;
            this.drum = drum;
            this.tint = tint;
            this.furniture = furniture;
        }
    }

    /** Загруженная модель + вычисленные точки. */
    public static final class Gun {
        public final Spec spec;
        public final MeshModel model;
        public final MeshModel.Pose pose;
        public final int jMag, jBolt, jSlide, jPump, jMuzzle, jScope, jRailBottom;
        public final double[] muzzle = new double[3];     // в координатах модели (до масштаба)
        public final double[] foregrip = new double[3];
        public final double[] scopeMount = new double[3];
        public final double[] magPivot = new double[3];
        public final double minZ, maxZ, minY, maxY;
        final int[] baseColors;

        Gun(Spec spec, MeshModel m) {
            this.spec = spec;
            this.model = m;
            this.pose = new MeshModel.Pose(m);
            pose.compute();
            jMag = m.joint("Magazine");
            jBolt = firstJoint(m, "Bolt", "Charging Handle");
            jSlide = m.joint("Slide");
            jPump = m.joint("Pump");
            jMuzzle = m.joint("Attach_Muzzle");
            jScope = m.joint("Attach_Scope");
            jRailBottom = m.joint("Attach_Rail.Bottom");
            MeshModel.Lod d = m.lods[0];
            double mnz = 1e9, mxz = -1e9, mny = 1e9, mxy = -1e9;
            for (int v = 0; v < d.nv; v++) {
                mnz = Math.min(mnz, d.pos[v * 3 + 2]); mxz = Math.max(mxz, d.pos[v * 3 + 2]);
                mny = Math.min(mny, d.pos[v * 3 + 1]); mxy = Math.max(mxy, d.pos[v * 3 + 1]);
            }
            minZ = mnz; maxZ = mxz; minY = mny; maxY = mxy;
            if (jMuzzle >= 0) { muzzle[0] = m.px(jMuzzle); muzzle[1] = m.py(jMuzzle); muzzle[2] = m.pz(jMuzzle); }
            else {
                // дуло по самой передней точке модели в районе ствола
                double bestZ = -1e9, by = 0;
                for (int v = 0; v < d.nv; v++) if (d.pos[v * 3 + 2] > bestZ) { bestZ = d.pos[v * 3 + 2]; by = d.pos[v * 3 + 1]; }
                muzzle[0] = 0; muzzle[1] = by; muzzle[2] = bestZ;
            }
            if (jPump >= 0) { foregrip[0] = 0; foregrip[1] = m.py(jPump) - 0.01; foregrip[2] = m.pz(jPump) + 0.02; }
            else if (jRailBottom >= 0) { foregrip[0] = 0; foregrip[1] = m.py(jRailBottom) - 0.012; foregrip[2] = m.pz(jRailBottom) - 0.02; }
            else if (jMag >= 0) { foregrip[0] = 0; foregrip[1] = m.py(jMag) - 0.03; foregrip[2] = m.pz(jMag) + 0.03; }
            else { foregrip[0] = 0; foregrip[1] = -0.02; foregrip[2] = 0.18; }
            if (jScope >= 0) { scopeMount[0] = 0; scopeMount[1] = m.py(jScope); scopeMount[2] = m.pz(jScope); }
            else { scopeMount[0] = 0; scopeMount[1] = mxy; scopeMount[2] = 0.02; }
            if (jMag >= 0) { magPivot[0] = m.px(jMag); magPivot[1] = m.py(jMag); magPivot[2] = m.pz(jMag); }
            baseColors = new int[m.matColors.length];
            for (int i = 0; i < baseColors.length; i++) baseColors[i] = liftColor(m.matNames[i], m.matColors[i]);
        }

        /** Цвета материалов с учётом скина и базового тона ствола. */
        public int[] colors(GameSettings.WeaponSkin skin, int[] out) {
            if (out == null || out.length < baseColors.length) out = new int[baseColors.length];
            boolean custom = skin != null && skin != GameSettings.WeaponSkin.STANDARD;
            for (int i = 0; i < baseColors.length; i++) {
                String n = model.matNames[i].toLowerCase();
                int c = baseColors[i];
                if (custom) {
                    if (n.contains("grey_dark") && !n.contains("darker")) c = skin.primary;
                    else if (n.contains("grey_darker")) c = Renderer3D.shadeColor(skin.primary, 0.78);
                    else if (n.contains("brown") || n.contains("red")) c = skin.accent;
                    else if (n.contains("grey_light") || n.contains("white")) c = skin.accent;
                    else if (n.contains("black_lighter")) c = skin.trim;
                    // 11.2: чёрная фурнитура — тёмный тон СКИНА (не синяя «перчатка» 0x1C2026)
                    else if (n.contains("black")) c = Renderer3D.shadeColor(skin.primary, 0.42);
                } else {
                    if (spec.tint != 0 && (n.contains("grey_dark") || n.contains("grey_darker")))
                        c = n.contains("darker") ? Renderer3D.shadeColor(spec.tint, 0.8) : spec.tint;
                    if (spec.furniture != 0 && (n.contains("brown") || n.contains("red") || n.contains("black")))
                        c = spec.furniture;
                }
                out[i] = c;
            }
            return out;
        }

        public boolean isPistolSized() { return maxZ - minZ < 0.32; }
    }

    private static int firstJoint(MeshModel m, String... names) {
        for (String n : names) { int j = m.joint(n); if (j >= 0) return j; }
        return -1;
    }

    /** Тёмные PBR-цвета Flat Guns поднимаются до читаемых в софтверном рендере. */
    static int liftColor(String name, int rgb) {
        String n = name.toLowerCase();
        if (n.contains("black_lighter")) return 0x463B2C;
        // 11.2: чёрная фурнитура (рукоять/магазин/приклад) — тёплое оружейное дерево-полимер.
        // Раньше был 0x1C2026 (синий отсвет) — в кадре читалось как «рука в перчатке»!
        if (n.contains("black")) return 0x3A2E20;
        if (n.contains("grey_darker")) return 0x46403A;
        if (n.contains("grey_dark")) return 0x5A544C;
        if (n.contains("grey_light")) return 0x847C6E;
        if (n.contains("white")) return 0xC4C9CF;
        if (n.contains("brass") || n.contains("yellow")) return 0xC8962E;
        if (n.contains("brown")) return 0x8A4A26;
        if (n.contains("red")) return 0x9A2A2A;
        // иначе — осветлённый оригинал
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        r = Math.min(255, r + 30); g = Math.min(255, g + 30); b = Math.min(255, b + 30);
        return (r << 16) | (g << 8) | b;
    }

    public static Spec specOf(Weapon.Type t) {
        switch (t) {
            case AK47:      return new Spec("gun_rifle_assault_east", 1.18, false, false, false, 0, 0x8C4A22);
            case M4A4:      return new Spec("gun_rifle_assault_west", 1.14, false, false, false, 0, 0);
            case M4A1S:     return new Spec("gun_rifle_assault_west", 1.10, true, false, false, 0x434A44, 0);
            case GALIL:     return new Spec("gun_rifle_battle_east", 1.08, false, false, false, 0x4C4A3E, 0x6A5232);
            case FAMAS:     return new Spec("gun_shotgun_auto_east", 1.02, false, false, false, 0x5A6066, 0x3A3E44);
            case SG553:     return new Spec("gun_rifle_battle_west", 1.04, false, true, false, 0x6A5A40, 0);
            case AUG:       return new Spec("gun_rifle_battle_west", 0.98, false, true, false, 0x5E6A48, 0x48523A);
            case AWP:       return new Spec("gun_sniper_material_west", 1.00, false, true, false, 0x3E5A3A, 0x2E3A2A);
            case SSG08:     return new Spec("gun_sniper_rifle_west", 1.02, false, true, false, 0x50585E, 0);
            case MAC10:     return new Spec("gun_smg_compact_east", 1.00, false, false, false, 0x4A4E54, 0);
            case MP9:       return new Spec("gun_smg_compact_west", 1.04, false, false, false, 0, 0);
            case UMP45:     return new Spec("gun_smg_full_west", 1.06, false, false, false, 0x3E4246, 0);
            case P90:       return new Spec("gun_smg_full_east", 1.04, false, false, false, 0x5C5A50, 0x3E3C36);
            case NOVA:      return new Spec("gun_shotgun_pump_west", 1.40, false, false, false, 0, 0x7A4424);
            case XM1014:    return new Spec("gun_shotgun_pump_east", 1.20, false, false, false, 0x3A3E44, 0);
            case M249:      return new Spec("gun_shotgun_auto_west", 1.12, false, false, true, 0x3D4A34, 0);
            case NEGEV:     return new Spec("gun_rifle_battle_east", 1.22, false, false, true, 0x4A503E, 0x30342A);
            case GLOCK:     return new Spec("gun_pistol_compact_west", 1.12, false, false, false, 0x3A3E44, 0);
            case USP_S:     return new Spec("gun_pistol_full_west", 1.02, true, false, false, 0x2E3238, 0);
            case P250:      return new Spec("gun_pistol_compact_east", 1.10, false, false, false, 0x8E979F, 0x2A2E34);
            case FIVESEVEN: return new Spec("gun_pistol_full_east", 1.04, false, false, false, 0x6E6A5A, 0x2E3034);
            case TEC9:      return new Spec("gun_smg_compact_east", 0.84, false, false, false, 0x6A6A58, 0);
            case DEAGLE:    return new Spec("gun_pistol_full_east", 1.30, false, false, false, 0x9CA4AC, 0x2A2A2A);
            default:        return null; // нож, Zeus, РПГ — процедурные модели
        }
    }

    private static final java.util.EnumMap<Weapon.Type, Object> GUNS = new java.util.EnumMap<>(Weapon.Type.class);
    private static final Object NONE = new Object();
    private static volatile boolean enabled = true;

    public static void setEnabled(boolean e) { enabled = e; }
    public static boolean isEnabled() { return enabled; }

    /** Модель оружия (кэш) или null, если для типа нет GLB или модели отключены. */
    public static Gun get(Weapon.Type t) {
        if (!enabled || t == null) return null;
        synchronized (GUNS) {
            Object o = GUNS.get(t);
            if (o == null) {
                Spec s = specOf(t);
                MeshModel m = s == null ? null : MeshModel.get(s.file);
                o = (m == null) ? NONE : new Gun(s, m);
                GUNS.put(t, o);
            }
            return o == NONE ? null : (Gun) o;
        }
    }

    // ===================== ПРОЦЕДУРНЫЕ НАВЕСЫ =====================

    private static MeshModel.Lod cylinderLod;

    /** Единичный цилиндр (радиус 1, длина 1 вдоль +Z от 0 до 1) — для глушителей, оптики и барабанов. */
    public static synchronized MeshModel cylinder() {
        MeshModel m = MeshModel.get("__cyl");
        return m;
    }

    private GunModels() {}
}
