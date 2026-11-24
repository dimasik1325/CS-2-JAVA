/**
 * CS-2 JAVA 11.0 — живые 3D-бойцы из GLB-моделей Quaternius (CC0) со скелетной анимацией.
 *
 * Процедурная анимация: шаг/бег с работой коленей и стоп, покачивание таза, поворот корпуса,
 * наклон за прицелом, отдача, вздрагивание от попаданий, ослепление флешкой, перезарядка
 * и «рэгдолл»-падение при смерти. Руки ставятся на рукоять и цевьё настоящей GLB-модели оружия
 * аналитическим IK, пальцы сжимаются в хват.
 */
public final class CharacterRig {

    public enum Variant {
        CT_SWAT("char_swat"), T_SWAT("char_swat"), CT_SOLDIER("char_soldier"), T_PUNK("char_punk"), JUGGERNAUT("char_swat");
        public final String file;
        Variant(String f) { file = f; }
    }

    /** Параметры кадра анимации. */
    public static final class Params {
        public double time;          // глобальное время, с
        public double walkPhase;     // фаза шага (рад)
        public double moveSpeed;     // 0 — стоит, 1 — бег
        public double aimPitch;      // наклон прицеливания (рад, + вверх)
        public double aimBlend = 1;  // 0 — оружие опущено (патруль), 1 — у плеча
        public double recoil;        // 0..1
        public double flinch;        // 0..1 — реакция на попадание
        public double blind;         // 0..1 — ослеплён
        public double reload;        // 0..1 — фаза перезарядки, <0 — нет
        public double death = -1;    // <0 жив, 0..1 падение
        public int deathStyle;       // 0 — назад, 1 — вперёд, 2 — на бок
        public boolean flash;        // подсветка попадания
    }

    private static final int[] EMPTY = new int[0];

    public final Variant variant;
    public final MeshModel model;
    public final MeshModel.Pose pose;
    private final int jRoot, jHips, jAbd, jTorso, jChest, jNeck, jHead;
    private final int jShL, jUaL, jLaL, jHandL, jShR, jUaR, jLaR, jHandR;
    private final int jUlL, jLlL, jFootL, jUlR, jLlR, jFootR;
    private final int[] fingersL, fingersR, thumbsL, thumbsR;
    private int[] idxR, wrapR;   // правая: указательный (спуск) и обхватывающие пальцы
    private final int[] baseColors;
    private final int[] colors;
    // 11.0: тактические перчатки от первого лица — материал кожи кистей перекрашивается в цвет перчаток
    private int skinMatIndex = -1;
    private final int[] fpArmColors;
    // 11.3: тёплая кожаная гамма перчаток — руки читаются руками (сине-серые 0x2A2F38/0x39424E
    // визуально сливались с фурнитурой и выглядели «сломанными синими руками»)
    private static final int GLOVE_CT = 0x4A3A28, GLOVE_T = 0x4C3B27, GLOVE_JUGG = 0x332B22;
    private static final int PLATE_CT = 0x5A4A36, PLATE_T = 0x634E30, PLATE_JUGG = 0x3D3428;
    private static final int STRAP_COL = 0xB08F5C;
    private final double[] tmp = new double[3];
    private final double[] tmp2 = new double[3];
    private final double[] bone = new double[12];
    private final double[] xf = new double[12];
    private final double[] gunXf = new double[12];
    private int[] gunColors = new int[16];

    /** Последняя мировая позиция дула (после render). */
    public final double[] muzzleWorld = new double[3];
    public boolean muzzleValid = false;

    public static CharacterRig create(Variant v) {
        MeshModel m = MeshModel.get(v.file);
        return m == null ? null : new CharacterRig(v, m);
    }

    private CharacterRig(Variant v, MeshModel m) {
        variant = v;
        model = m;
        pose = new MeshModel.Pose(m);
        jRoot = m.joint("Root"); jHips = m.joint("Hips"); jAbd = m.joint("Abdomen"); jTorso = m.joint("Torso");
        jChest = m.joint("Chest"); jNeck = m.joint("Neck"); jHead = m.joint("Head");
        jShL = m.joint("Shoulder.L"); jUaL = m.joint("UpperArm.L"); jLaL = m.joint("LowerArm.L"); jHandL = m.joint("Hand.L");
        jShR = m.joint("Shoulder.R"); jUaR = m.joint("UpperArm.R"); jLaR = m.joint("LowerArm.R"); jHandR = m.joint("Hand.R");
        jUlL = m.joint("UpperLeg.L"); jLlL = m.joint("LowerLeg.L"); jFootL = m.joint("Foot.L");
        jUlR = m.joint("UpperLeg.R"); jLlR = m.joint("LowerLeg.R"); jFootR = m.joint("Foot.R");
        fingersL = collect(m, ".L", false); fingersR = collect(m, ".R", false);
        thumbsL = collect(m, ".L", true); thumbsR = collect(m, ".R", true);
        baseColors = new int[m.matColors.length];
        for (int i = 0; i < baseColors.length; i++) baseColors[i] = teamColor(v, m.matNames[i], m.matColors[i]);
        colors = baseColors.clone();
        // 11.0: перчатки — ищем материал кожи и красим его в цвет перчаток (только для FP-рук)
        for (int i = 0; i < m.matNames.length; i++) {
            if ("Skin".equalsIgnoreCase(m.matNames[i])) { skinMatIndex = i; break; }
        }
        fpArmColors = colors.clone();
        if (skinMatIndex >= 0) {
            boolean ct = (v == Variant.CT_SWAT || v == Variant.CT_SOLDIER);
            int glove = (v == Variant.JUGGERNAUT) ? GLOVE_JUGG : (ct ? GLOVE_CT : GLOVE_T);
            fpArmColors[skinMatIndex] = glove;
            // 11.3: перчатка в GLB = материал Swat_Black (был 0x2A2D33 — тёмно-синий!),
            // красим в тёплый кожаный цвет; рукав «Swat» — тёплый серо-оливковый
            for (int i = 0; i < m.matNames.length; i++) {
                if ("Swat_Black".equalsIgnoreCase(m.matNames[i])) fpArmColors[i] = glove;
                                else if ("Swat".equalsIgnoreCase(m.matNames[i])) fpArmColors[i] = 0x6B5B3E;
            }
        }
    }

    /** Цепочка суставов одного пальца (Index.R, Middle.L, ...). */
    private static int[] collectNamed(MeshModel m, String prefix) {
        int[] out = new int[8];
        int c = 0;
        for (int j = 0; j < m.jointCount(); j++) {
            String n = m.jointNames[j];
            if (n.startsWith(prefix) && !n.endsWith("_end")) out[c++] = j;
        }
        return java.util.Arrays.copyOf(out, c);
    }

    private static int[] collect(MeshModel m, String side, boolean thumb) {
        int[] out = new int[16];
        int c = 0;
        for (int j = 0; j < m.jointCount(); j++) {
            String n = m.jointNames[j];
            if (!n.endsWith(side) || n.endsWith("_end")) continue;
            boolean isThumb = n.startsWith("Thumb");
            boolean isFinger = n.startsWith("Index") || n.startsWith("Middle") || n.startsWith("Ring") || n.startsWith("Pinky");
            if ((thumb && isThumb) || (!thumb && isFinger)) out[c++] = j;
        }
        return java.util.Arrays.copyOf(out, c);
    }

    static int teamColor(Variant v, String mat, int rgb) {
        String n = mat.toLowerCase();
        switch (v) {
            case CT_SWAT:
                if (n.equals("swat")) return 0x46566C;
                if (n.contains("black")) return 0x2A2D33;
                if (n.contains("visor")) return 0x18222E;
                break;
            case T_SWAT:
                if (n.equals("swat")) return 0x86724F;
                if (n.contains("black")) return 0x3E3B2B;
                if (n.contains("visor")) return 0x121212;
                break;
            case JUGGERNAUT:
                if (n.equals("swat")) return 0x2C3138;
                if (n.contains("black")) return 0x1A1D22;
                if (n.contains("visor")) return 0xD8301E;
                break;
            case CT_SOLDIER:
                if (n.equals("swat")) return 0x46566C;
                if (n.equals("black")) return 0x26292E;
                if (n.equals("grey")) return 0x3C424A;
                if (n.contains("hair")) return 0x4A3422;
                if (n.equals("brown")) return 0x3A2C22;
                break;
            case T_PUNK:
                if (n.equals("white")) return 0xB8B2A8;
                if (n.contains("lightblue")) return 0x3E5872;
                if (n.equals("black")) return 0x24262A;
                break;
            default:
                break;
        }
        // поднимаем слишком тёмные PBR-цвета
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int mx = Math.max(r, Math.max(g, b));
        if (mx < 40) { r += 22; g += 22; b += 22; }
        return (r << 16) | (g << 8) | b;
    }

    // ===================== ПОЗА =====================

    /**
     * Строит позу и возвращает матрицу оружия (модель персонажа -> модель оружия, 3x4, с учётом масштаба ствола)
     * в gunXf (если gun != null).
     */
    public void buildPose(Params p, GunModels.Gun gun) {
        pose.reset();
        boolean dead = p.death >= 0;
        double spd = Math.max(0, Math.min(1.2, p.moveSpeed));
        double ph = p.walkPhase;
        double breathe = Math.sin(p.time * 1.9) * 0.012;

        // ---- Ноги: шаг/бег ----
        if (!dead) {
            double amp = 0.52 * spd;
            for (int side = 0; side < 2; side++) {
                double lp = ph + (side == 0 ? 0 : Math.PI);
                double swing = Math.sin(lp) * amp;           // + вперёд
                double knee = (0.10 + Math.max(0, Math.cos(lp)) * 0.85) * spd + 0.06;
                int ul = side == 0 ? jUlL : jUlR, ll = side == 0 ? jLlL : jLlR, ft = side == 0 ? jFootL : jFootR;
                pose.rotateX(ul, -swing - 0.04);
                pose.rotateX(ll, knee);
                pose.rotateX(ft, -knee * 0.35 + Math.max(0, -Math.sin(lp)) * 0.25 * spd);
            }
            // таз: подпрыгивание и покачивание
            double bob = -Math.abs(Math.sin(ph)) * 0.035 * spd - 0.02 * spd;
            pose.translate(jHips, 0, bob, 0);
            pose.rotateY(jHips, Math.sin(ph) * 0.10 * spd);
            pose.rotateY(jAbd, -Math.sin(ph) * 0.08 * spd);
        }

        // ---- Корпус: стойка стрелка ----
        double aim = Math.max(0, Math.min(1, p.aimBlend));
        double pitch = p.aimPitch * aim;
        pose.rotateX(jAbd, 0.05 + 0.08 * spd + breathe);
        pose.rotateY(jTorso, 0.30 * aim + 0.12);   // корпус развёрнут правым плечом назад
        pose.rotateX(jTorso, -pitch * 0.35 - p.recoil * 0.06);
        pose.rotateX(jChest, -pitch * 0.35 + p.flinch * 0.35);
        pose.rotateZ(jChest, p.flinch * 0.12);
        pose.rotateY(jNeck, -0.30 * aim - 0.10);   // голова смотрит вперёд по линии прицела
        pose.rotateX(jHead, -pitch * 0.3 + p.blind * 0.5);

        // ---- Смерть: процедурный рэгдолл ----
        if (dead) {
            applyDeath(p);
        }
        pose.compute();

        if (gun == null || dead) {
            // руки вдоль тела (или раскинуты при смерти)
            if (!dead) {
                pose.rotateZ(jUaR, -1.25); pose.rotateZ(jUaL, 1.25);
                pose.rotateX(jLaR, -0.25); pose.rotateX(jLaL, -0.25);
                pose.compute();
            }
            return;
        }

        // ---- Оружие у плеча ----
        boolean pistol = gun.isPistolSized() && gun.spec.file.contains("pistol");
        double gs = gun.spec.scale;
        // точка удержания в системе груди (поза покоя), опущенный ствол при патруле
        double hx, hy, hz;
        if (pistol) { hx = 0.04; hy = 1.33; hz = 0.42; }
        else { hx = 0.06; hy = 1.33; hz = 0.22; }
        double lowered = (1 - aim) * 0.55;
        double down = -lowered;
        if (p.blind > 0.2) down = -0.9;
        pose.transformPoint(jChest, hx, hy - lowered * 0.12, hz, tmp);
        pose.boneMatrix(jChest, bone);
        // ориентация оружия: поворот груди * компенсация разворота корпуса * наклон вниз/отдача
        double[] base = new double[]{bone[0], bone[1], bone[2], bone[4], bone[5], bone[6], bone[8], bone[9], bone[10]};
        double[] yawFix = MeshModel.axisAngle(0, 1, 0, -(0.30 * aim + 0.12) + (pistol ? 0.0 : -0.04));
        double[] pitchR = MeshModel.axisAngle(1, 0, 0, -down - p.recoil * 0.10 + p.flinch * -0.2);
        double[] g = mul(base, mul(yawFix, pitchR));
        for (int r = 0; r < 3; r++) {
            gunXf[r * 4] = g[r * 3] * gs; gunXf[r * 4 + 1] = g[r * 3 + 1] * gs; gunXf[r * 4 + 2] = g[r * 3 + 2] * gs;
        }
        gunXf[3] = tmp[0]; gunXf[7] = tmp[1]; gunXf[11] = tmp[2];

        armsToGun(p, gun, pistol);
    }

    /** IK рук к рукояти и цевью оружия, матрица которого уже лежит в gunXf (координаты модели персонажа). */
    void armsToGun(Params p, GunModels.Gun gun, boolean pistol) {
        boolean fp = fpArmScale > 0.0;
        if (idxR == null) {
            idxR = collectNamed(model, "Index.R");
            int[] mid = collectNamed(model, "Middle.R"), rng = collectNamed(model, "Ring.R"), pnk = collectNamed(model, "Pinky.R");
            java.util.ArrayList<Integer> all = new java.util.ArrayList<>();
            for (int[] arr : new int[][]{mid, rng, pnk}) for (int j : arr) all.add(j);
            wrapR = new int[all.size()];
            for (int i = 0; i < wrapR.length; i++) wrapR[i] = all.get(i);
        }
        if (procHold) {
            // 11.1: процедурное оружие (нож/Zeus/RPG7) — хват по заданным точкам рукоятей
            gunPoint(0.006, procRearY, procRearZ, tmp);
            pose.solveTwoBoneIK(jUaR, jLaR, jHandR, tmp[0], tmp[1], tmp[2], 0.7, -1.0, -0.35);
            if (procTwoHand) {
                gunPoint(0.0, procForeY, procForeZ, tmp);
                pose.solveTwoBoneIK(jUaL, jLaL, jHandL, tmp[0], tmp[1], tmp[2], -0.8, -1.0, -0.4);
            }
            if (fp) {
                double hs = 1.0 / fpArmScale;
                pose.scl[jHandR] = hs;
                if (procTwoHand) pose.scl[jHandL] = hs;
            }
            double[] gr = {0, 1, -1, 0, 1, 0, 1, 0, 0};          // правая: пальцы +Z, ладонь -X
            double[] glR = {0, 1, 0, 0, 0, 1, 1, 0, 0};          // левая (винтовочная опора)
            setHandFrame(jHandR, jLaR, gr);
            if (procTwoHand) setHandFrame(jHandL, jLaL, glR);
            curlPalm(wrapR, 0.92);
            curlPalm(idxR, 0.22);
            curlPalm(thumbsR, 0.50);
            if (procTwoHand) {
                curlPalm(fingersL, 0.95);
                curlPalm(thumbsL, 0.30);
            }
            pose.compute();
            return;
        }
        // запястье правой руки: у нижнего торца рукояти (кисть тянется вверх вдоль рукояти)
        gunPoint(0.006, fp ? (pistol ? -0.115 : -0.112) : -0.060, fp ? (pistol ? -0.070 : -0.078) : -0.075, tmp);
        pose.solveTwoBoneIK(jUaR, jLaR, jHandR, tmp[0], tmp[1], tmp[2], 0.7, -1.0, -0.35);
        double[] fg = gun.foregrip;
        double reload = p.reload;
        if (reload >= 0 && gun.jMag >= 0) {
            // левая рука уходит к магазину и обратно (11.1: компактнее — без растяжки руки поперёк экрана)
            double w = Math.sin(Math.min(1, reload) * Math.PI);
            gunPoint(-0.03 - 0.07 * w, gun.magPivot[1] - 0.05 - 0.11 * w, gun.magPivot[2] - 0.04, tmp);
        } else if (pistol) {
            gunPoint(fp ? -0.032 : -0.028, fp ? -0.105 : -0.100, fp ? -0.080 : -0.040, tmp);
        } else {
            // левая кисть ПОД цевьём; у снайперок — ближе к телу (устойчивая поддержка сошек)
            boolean snip = gun.spec.file.contains("sniper");
            double back = fp ? (snip ? 0.300 : 0.145) : (snip ? 0.180 : 0.055);
            gunPoint(fg[0] - 0.028, fg[1] - (fp ? 0.050 : 0.045), fg[2] - back, tmp);
        }
        pose.solveTwoBoneIK(jUaL, jLaL, jHandL, tmp[0], tmp[1], tmp[2], -0.8, -1.0, -0.4);
        if (idxR == null) {
            idxR = collectNamed(model, "Index.R");
            wrapR = new int[]{};
            int[] mid = collectNamed(model, "Middle.R"), rng = collectNamed(model, "Ring.R"), pnk = collectNamed(model, "Pinky.R");
            java.util.ArrayList<Integer> all = new java.util.ArrayList<>();
            for (int[] arr : new int[][]{mid, rng, pnk}) for (int j : arr) all.add(j);
            wrapR = new int[all.size()];
            for (int i = 0; i < wrapR.length; i++) wrapR[i] = all.get(i);
        }
        // в FP руки вытянуты масштабом — кисти возвращаем к натуральному размеру
        if (fp) {
            double hs = 1.0 / fpArmScale;
            pose.scl[jHandR] = hs;
            pose.scl[jHandL] = hs;
        }
        boolean reloading = reload >= 0;
        if (fp) {
            // точная ориентация кистей матрицами: пальцы вдоль оружия, ладонь к рукояти/цевью
            // G: колонки = куда смотрят пальцы(X), тыльная сторона кисти(Y), ладонь(Z) в системе оружия
            double[] gr = {0, 1, -1, 0, 1, 0, 1, 0, 0};          // правая: пальцы +Z, ладонь -X
            double[] glR = {0, 1, 0, 0, 0, 1, 1, 0, 0};          // левая (винтовка): пальцы +Z, ладонь +Y
            double[] glP = {0, 0, 1, 0, -1, 0, 1, 0, 0};         // левая (пистолет): пальцы +Z, ладонь +X
            setHandFrame(jHandR, jLaR, gr);
            setHandFrame(jHandL, jLaL, pistol ? glP : glR);
            // реальный хват: три пальца обхватывают рукоять, указательный — на спуске
            curlPalm(wrapR, reloading ? 0.32 : 0.95);
            curlPalm(idxR, pistol ? 0.16 : 0.20);
            curlPalm(thumbsR, 0.55);
            // левая: жёсткий обхват цевья/рукояти снизу (при перезарядке раскрывается)
            curlPalm(fingersL, reloading ? 0.18 : 0.98);
            curlPalm(thumbsL, 0.30);
        } else {
            // третье лицо: ладонь правой к рукояти, указательный на спуске
            pose.rotateX(jHandR, -1.35);
            pose.rotateX(jHandL, pistol ? -1.55 : -2.4);
            pose.rotateZ(jHandL, pistol ? 0.9 : 0.25);
            curl(wrapR, -1, reloading ? 0.5 : 0.85);
            curl(idxR, -1, 0.20);
            curl(fingersL, 1, reloading ? 0.35 : (pistol ? 1.0 : 0.85));
            curl(thumbsR, -1, 0.35);
            curl(thumbsL, 1, 0.40);
        }
        pose.compute();
    }

    private void curl(int[] joints, int sign, double ang) {
        for (int j : joints) pose.rotateZ(j, sign * ang);
    }

    /** Сгиб пальцев к ладони (после точной ориентации кисти ладонь = локальный +Z). */
    private void curlPalm(int[] joints, double ang) {
        for (int j : joints) pose.rotateY(j, -ang);
    }

    private static double[] mul33rm(double[] a, double[] b) {
        double[] o = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
        return o;
    }

    private static void transpose3(double[] m, double[] out) {
        out[0] = m[0]; out[1] = m[3]; out[2] = m[6];
        out[3] = m[1]; out[4] = m[4]; out[5] = m[7];
        out[6] = m[2]; out[7] = m[5]; out[8] = m[8];
    }

    /** Ориентировать кисть matrix-точн: локальный поворот = A_parent^T · R_gun · G. */
    private void setHandFrame(int hand, int lower, double[] G) {
        if (hand < 0 || lower < 0) return;
        double[] rg = {gunXf[0], gunXf[1], gunXf[2], gunXf[4], gunXf[5], gunXf[6], gunXf[8], gunXf[9], gunXf[10]};
        double[] M = mul33rm(rg, G);
        double[] A = new double[12];
        pose.boneMatrix(lower, A);
        double[] At = new double[9];
        transpose3(new double[]{A[0], A[1], A[2], A[4], A[5], A[6], A[8], A[9], A[10]}, At);
        double[] L = mul33rm(At, M);
        pose.setRotation(hand, L);
    }

    private void gunPoint(double x, double y, double z, double[] out) {
        out[0] = gunXf[0] * x + gunXf[1] * y + gunXf[2] * z + gunXf[3];
        out[1] = gunXf[4] * x + gunXf[5] * y + gunXf[6] * z + gunXf[7];
        out[2] = gunXf[8] * x + gunXf[9] * y + gunXf[10] * z + gunXf[11];
        if (fpArmScale > 0.0) {
            // цели IK заданы в системе масштабированных рук: предварительно компенсируем
            out[0] = out[0] / fpArmScale;
            out[1] = (out[1] - FP_PIVOT_Y) / fpArmScale + FP_PIVOT_Y;
            out[2] = out[2] / fpArmScale;
        }
    }

    private void applyDeath(Params p) {
        double t = Math.max(0, Math.min(1, p.death));
        // ease-in падение с отскоком
        double e = t < 0.82 ? (t / 0.82) * (t / 0.82) : 1 + Math.sin((t - 0.82) / 0.18 * Math.PI) * 0.04;
        double knees = Math.min(1, t * 2.2) * (1 - 0.75 * e);
        pose.rotateX(jUlL, -0.55 * knees); pose.rotateX(jUlR, -0.35 * knees);
        pose.rotateX(jLlL, 0.9 * knees); pose.rotateX(jLlR, 0.6 * knees);
        // руки: из положения «у оружия» безвольно раскидываются
        double armDown = 1.15 - 0.75 * e;
        pose.rotateZ(jUaR, -armDown); pose.rotateZ(jUaL, armDown);
        pose.rotateX(jUaR, -0.9 * (1 - e) - 0.2 * e); pose.rotateX(jUaL, -0.7 * (1 - e) + 0.3 * e);
        pose.rotateX(jLaR, -0.6 * (1 - e)); pose.rotateX(jLaL, -0.5 * (1 - e));
        pose.rotateX(jHead, (p.deathStyle == 1 ? 0.4 : -0.5) * e);
        pose.rotateZ(jNeck, 0.3 * e);
        double ang = Math.PI * 0.5 * e;
        double lift = 0.13 * e;
        switch (p.deathStyle) {
            case 1: // вперёд, лицом вниз
                pose.rotate(jRoot, 1, 0, 0, ang);
                pose.translate(jRoot, 0, lift, -0.25 * e);
                break;
            case 2: // на бок
                pose.rotate(jRoot, 0, 0, 1, ang);
                pose.translate(jRoot, 0.2 * e, lift, 0);
                pose.rotateX(jChest, 0.3 * e);
                break;
            default: // назад, на спину
                pose.rotate(jRoot, 1, 0, 0, -ang);
                pose.translate(jRoot, 0, lift, 0.25 * e);
                pose.rotateX(jChest, -0.25 * e);
                break;
        }
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++)
            for (int k = 0; k < 3; k++)
                r[i * 3 + k] = a[i * 3] * b[k] + a[i * 3 + 1] * b[3 + k] + a[i * 3 + 2] * b[6 + k];
        return r;
    }

    // ===================== ОТРИСОВКА =====================

    /**
     * Рисует бойца в мире. Модель смотрит вдоль (sin yaw, 0, cos yaw), правая рука — (cos yaw, 0, -sin yaw).
     * @return false, если боец вне экрана
     */
    public boolean render(Renderer3D r, double wx, double wy, double wz, double yaw, double scale,
                          Params p, GunModels.Gun gun, GameSettings.WeaponSkin skin, int lodBias) {
        muzzleValid = false;
        double rad = 1.3 * scale;
        if (!r.isMeshVisible(wx, wy + 0.9 * scale, wz, rad)) return false;
        double dist = r.distanceToCamera(wx, wy + 1.0, wz);
        // 11.0 PERF: LOD0 только вплотную (4.5м), дальше LOD1 — 3x меньше трис без потери читаемости
        int lod = dist < 4.5 ? 0 : (dist < 15 ? 1 : 2);
        lod = Math.min(model.lods.length - 1, lod + lodBias);
        buildPose(p, p.death >= 0 ? null : gun);

        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        // столбцы: X -> (cos,0,-sin), Y -> (0,1,0), Z -> (sin,0,cos)
        xf[0] = cy * scale; xf[1] = 0; xf[2] = sy * scale; xf[3] = wx;
        xf[4] = 0; xf[5] = scale; xf[6] = 0; xf[7] = wy;
        xf[8] = -sy * scale; xf[9] = 0; xf[10] = cy * scale; xf[11] = wz;

        int[] cols = colors;
        for (int i = 0; i < cols.length; i++) cols[i] = p.flash ? Renderer3D.lerpColor(baseColors[i], 0xFF3030, 0.55) : baseColors[i];
        r.drawMesh(model, lod, pose.skin, xf, cols, false, null, 1.0);

        if (gun != null && p.death < 0) {
            double[] w = compose(xf, gunXf);
            gunColors = gun.colors(skin, gunColors);
            animateGun(gun, p);
            r.drawMesh(gun.model, lod == 0 ? 0 : 1, gun.pose.skin, w, gunColors, false, null, 1.05);
            drawAttachments(r, gun, w, false, skin);
            double[] m = gun.muzzle;
            muzzleWorld[0] = w[0] * m[0] + w[1] * m[1] + w[2] * (m[2] + extraMuzzle(gun)) + w[3];
            muzzleWorld[1] = w[4] * m[0] + w[5] * m[1] + w[6] * (m[2] + extraMuzzle(gun)) + w[7];
            muzzleWorld[2] = w[8] * m[0] + w[9] * m[1] + w[10] * (m[2] + extraMuzzle(gun)) + w[11];
            muzzleValid = true;
        }
        return true;
    }

    static double extraMuzzle(GunModels.Gun gun) {
        return gun.spec.suppressor ? (gun.isPistolSized() ? 0.13 : 0.17) : 0.0;
    }

    /** Движение затвора/помпы/магазина GLB-оружия. */
    static void animateGun(GunModels.Gun gun, Params p) {
        MeshModel.Pose gp = gun.pose;
        gp.reset();
        double kick = Math.max(0, Math.min(1, p.recoil));
        if (gun.jSlide >= 0) gp.translate(gun.jSlide, 0, 0, -0.022 * kick);
        if (gun.jBolt >= 0) gp.translate(gun.jBolt, 0, 0, -0.035 * kick);
        if (gun.jPump >= 0) gp.translate(gun.jPump, 0, 0, -0.06 * kick);
        if (p.reload >= 0 && gun.jMag >= 0) {
            double w = Math.sin(Math.min(1, p.reload) * Math.PI);
            gp.translate(gun.jMag, 0, -0.16 * w, -0.02 * w);
        }
        gp.compute();
    }

    private static final int[] SUPP_COL = {0x1E2227};
    private static final int[] SCOPE_COL = {0x22272E};
    private static final int[] LENS_COL = {0x3C8AC8};
    private static final int[] DRUM_COL = {0x3D4A36};

    /** Глушитель, оптика, короб пулемёта — по точкам крепления модели. */
    static void drawAttachments(Renderer3D r, GunModels.Gun gun, double[] w, boolean viewSpace, GameSettings.WeaponSkin skin) {
        MeshModel cyl = MeshModel.get("__cyl");
        MeshModel box = MeshModel.get("__box");
        if (cyl == null || box == null) return;
        double[] m = gun.muzzle;
        if (gun.spec.suppressor) {
            boolean pistol = gun.isPistolSized();
            double rad = pistol ? 0.016 : 0.021, len = pistol ? 0.13 : 0.17;
            r.drawMesh(cyl, 0, null, compose(w, partXf(m[0], m[1], m[2] - 0.004, rad, rad, len)), SUPP_COL, viewSpace, null, 1.0);
        }
        if (gun.spec.scope) {
            double[] s = gun.scopeMount;
            boolean sniper = gun.spec.file.contains("sniper");
            double len = sniper ? 0.30 : 0.17, rad = sniper ? 0.024 : 0.019;
            double y = s[1] + rad + 0.022;
            r.drawMesh(cyl, 0, null, compose(w, partXf(s[0], y, s[2] - len * 0.45, rad, rad, len)), SCOPE_COL, viewSpace, null, 1.0);
            r.drawMesh(cyl, 0, null, compose(w, partXf(s[0], y, s[2] - len * 0.45 - 0.012, rad * 1.25, rad * 1.25, 0.04)), SCOPE_COL, viewSpace, null, 1.0);
            r.drawMesh(cyl, 0, null, compose(w, partXf(s[0], y, s[2] + len * 0.55 - 0.03, rad * 1.3, rad * 1.3, 0.04)), SCOPE_COL, viewSpace, null, 1.0);
            r.drawMesh(cyl, 0, null, compose(w, partXf(s[0], y, s[2] + len * 0.55 + 0.0105, rad * 1.05, rad * 1.05, 0.002)), LENS_COL, viewSpace, null, 1.4);
            r.drawMesh(box, 0, null, compose(w, boxXf(s[0], s[1] + 0.012, s[2] - len * 0.15, 0.016, 0.026, 0.02)), SCOPE_COL, viewSpace, null, 1.0);
            r.drawMesh(box, 0, null, compose(w, boxXf(s[0], s[1] + 0.012, s[2] + len * 0.3, 0.016, 0.026, 0.02)), SCOPE_COL, viewSpace, null, 1.0);
        }
        if (gun.spec.drum && gun.jMag >= 0) {
            double[] mp = gun.magPivot;
            r.drawMesh(box, 0, null, compose(w, boxXf(mp[0] - 0.03, mp[1] - 0.07, mp[2] + 0.01, 0.075, 0.11, 0.12)), DRUM_COL, viewSpace, null, 1.0);
        }
    }

    /** Цилиндр вдоль +Z: начало (x,y,z), радиусы rx, ry, длина len. */
    static double[] partXf(double x, double y, double z, double rx, double ry, double len) {
        return new double[]{rx, 0, 0, x, 0, ry, 0, y, 0, 0, len, z};
    }

    static double[] boxXf(double x, double y, double z, double sx, double sy, double sz) {
        return new double[]{sx, 0, 0, x, 0, sy, 0, y, 0, 0, sz, z};
    }

    /** a ∘ b для аффинных 3x4. */
    static double[] compose(double[] a, double[] b) {
        double[] r = new double[12];
        for (int row = 0; row < 3; row++) {
            double a0 = a[row * 4], a1 = a[row * 4 + 1], a2 = a[row * 4 + 2], a3 = a[row * 4 + 3];
            r[row * 4]     = a0 * b[0] + a1 * b[4] + a2 * b[8];
            r[row * 4 + 1] = a0 * b[1] + a1 * b[5] + a2 * b[9];
            r[row * 4 + 2] = a0 * b[2] + a1 * b[6] + a2 * b[10];
            r[row * 4 + 3] = a0 * b[3] + a1 * b[7] + a2 * b[11] + a3;
        }
        return r;
    }

    public double[] lastGunTransform() { return gunXf; }

    // ===================== ВИД ОТ ПЕРВОГО ЛИЦА =====================

    private int[] armTris;
    private MeshModel armMesh;   // подмножество: скиннинг только вершин рук
    private MeshModel armMeshR;  // 11.1: только правая рука (нож — одноручный хват)
    private static final double EYE_Y = 1.64, EYE_Z = 0.02;
    /** Трюк FPS-ригов: руки длиннее пропорций тела (масштаб относительно точки у груди). */
    private static final double FP_ARM_SCALE = 1.32, FP_PIVOT_Y = 0.90;
    private double fpArmScale = 0.0;

    /**
     * Руки бойца (из той же GLB-модели) + оружие в пространстве камеры.
     * @param gunView матрица оружия в пространстве камеры (x вправо, y вверх, z вперёд), с масштабом
     */
    public void renderFirstPerson(Renderer3D r, GunModels.Gun gun, double[] gunView, Params p,
                                  GameSettings.WeaponSkin skin, boolean drawArms) {
        buildFirstPersonPose(gun, gunView, p, skin, drawArms);
        // руки под оружием: затвор и ствол не перекрываются ладонями
        if (drawArms) {
            // руки длиннее пропорций: масштаб относительно (0, FP_PIVOT_Y, 0) минус положение глаз
            double sc = FP_ARM_SCALE, ty = FP_PIVOT_Y * (1 - sc) - EYE_Y;
            double[] armXf = {sc, 0, 0, 0, 0, sc, 0, ty, 0, 0, sc, -EYE_Z};
            r.drawMesh(armMesh, 0, pose.skin, armXf, fpArmColors, true, null, 1.08);
            // 11.0: тактические перчатки — костяшная пластина и манжета на запястье
            drawFpGloves(r, armXf, sc);
        }
        r.drawMesh(gun.model, 0, gun.pose.skin, gunView, gunColors, true, null, 1.12);
        drawAttachments(r, gun, gunView, true, skin);
    }

    /** Отладка: та же поза FP, но в мировых координатах персонажа (камера смотрит со стороны). */
    public void debugFirstPersonWorld(Renderer3D r, GunModels.Gun gun, double[] gunView, Params p,
                                      GameSettings.WeaponSkin skin) {
        buildFirstPersonPose(gun, gunView, p, skin, true);
        double sc = FP_ARM_SCALE, ty = FP_PIVOT_Y * (1 - sc);
        double[] armXf = {sc, 0, 0, 0, 0, sc, 0, ty, 0, 0, sc, 0};
        r.drawMesh(armMesh, 0, pose.skin, armXf, colors, false, null, 1.08);
        r.drawMesh(gun.model, 0, gun.pose.skin, gunXf, gunColors, false, null, 1.12);
        drawAttachments(r, gun, gunXf, false, skin);
    }

    /** Отладка: печатает пивоты суставов текущей позы. */
    public void debugJoints() {
        double[] bm = new double[12];
        int[] js = {jTorso, jChest, jShR, jUaR, jLaR, jHandR, jShL, jUaL, jLaL, jHandL};
        String[] nm = {"Torso", "Chest", "ShR", "UaR", "LaR", "HandR", "ShL", "UaL", "LaL", "HandL"};
        for (int i = 0; i < js.length; i++) {
            pose.boneMatrix(js[i], bm);
            System.out.printf("%s: %.3f %.3f %.3f%n", nm[i], bm[3], bm[7], bm[11]);
        }
    }

    /** Отладка: печатает цели IK и позиции суставов рук для FP-позы. */
    public void debugArms2() { MeshModel.DEBUG_IK = true; }
    public void debugArms(GunModels.Gun gun, double[] gunView, Params p, GameSettings.WeaponSkin skin) {
        buildFirstPersonPose(gun, gunView, p, skin, true);
        double[] o = new double[3];
        String[] names = {"UaR", "LaR", "HandR", "UaL", "LaL", "HandL"};
        int[] js = {jUaR, jLaR, jHandR, jUaL, jLaL, jHandL};
        double[] bm = new double[12];
        for (int i = 0; i < js.length; i++) {
            pose.boneMatrix(js[i], bm);
            System.out.printf("%s: %.3f %.3f %.3f%n", names[i], bm[3], bm[7], bm[11]);
        }
        gunPoint(0.012, -0.060, -0.075, o);
        System.out.printf("gripT: %.3f %.3f %.3f%n", o[0], o[1], o[2]);
        double[] fg = gun.foregrip;
        gunPoint(fg[0] - 0.030, fg[1] - 0.040, fg[2] - 0.055, o);
        System.out.printf("foreT: %.3f %.3f %.3f%n", o[0], o[1], o[2]);
        System.out.printf("gunO: %.3f %.3f %.3f scale %.2f%n", gunView[3], gunView[7], gunView[11], gun.spec.scale);
    }

    private void buildFirstPersonPose(GunModels.Gun gun, double[] gunView, Params p,
                                      GameSettings.WeaponSkin skin, boolean drawArms) {
        if (gun != null) {
            animateGun(gun, p);
            gunColors = gun.colors(skin, gunColors);
        }
        if (drawArms) {
            fpArmScale = FP_ARM_SCALE;
            if (armTris == null) {
                armTris = model.trianglesOfJoints(0, j -> {
                    String n = model.jointNames[j];
                    return n.contains("Arm") || n.startsWith("Hand") || n.startsWith("Index") || n.startsWith("Middle")
                            || n.startsWith("Ring") || n.startsWith("Pinky") || n.startsWith("Thumb");
                });
                armMesh = model.extractSubset(0, armTris);
            }
            if (armMeshR == null) {
                int[] armTrisR = model.trianglesOfJoints(0, j -> {
                    String n = model.jointNames[j];
                    return (n.contains("Arm") || n.startsWith("Hand") || n.startsWith("Index") || n.startsWith("Middle")
                            || n.startsWith("Ring") || n.startsWith("Pinky") || n.startsWith("Thumb")) && n.endsWith(".R");
                });
                armMeshR = model.extractSubset(0, armTrisR);
            }
            // стойка стрелка: плечи развёрнуты, корпус чуть наклонён
            pose.reset();
            pose.rotateX(jAbd, 0.06);
            pose.rotateY(jTorso, 0.22);
            // плечи заметно ниже глаз — рукава входят в кадр только снизу у кистей
            pose.translate(jShR, 0.05, -0.24, 0.03);
            pose.translate(jShL, -0.05, -0.24, 0.03);
            pose.compute();
            // матрица оружия в координатах модели: модель = вид + глаз
            System.arraycopy(gunView, 0, gunXf, 0, 12);
            gunXf[7] += EYE_Y;
            gunXf[11] += EYE_Z;
            boolean pistol = (gun != null) && gun.spec.file.contains("pistol");
            armsToGun(p, gun, pistol);
            fpArmScale = 0.0;
        }
    }

    // 11.1: процедурное оружие (нож/Zeus/RPG7) — руки через GLB-риг
    private boolean procHold = false, procTwoHand = true;
    private double procRearZ = 0.0, procRearY = -0.112, procForeZ = 0.0, procForeY = -0.118;

    /**
     * 11.1: GLB-руки (перчатки) для процедурного оружия. holdView — матрица хвата в пространстве
     * камеры (локальные координаты: Z вдоль ствола/клинка), rearZ/rearY — точка правой кисти,
     * foreZ/foreY — левой (twoHanded=false — одноручный хват ножа).
     */
    public void renderFirstPersonHold(Renderer3D r, double[] holdView, Params p,
                                      GameSettings.WeaponSkin skin, boolean twoHanded,
                                      double rearZ, double rearY, double foreZ, double foreY) {
        procHold = true; procTwoHand = twoHanded;
        procRearZ = rearZ; procRearY = rearY; procForeZ = foreZ; procForeY = foreY;
        try {
            buildFirstPersonPose(null, holdView, p, skin, true);
            // buildFirstPersonPose сбрасывает fpArmScale — рисуем безусловно (drawArms=true)
            double sc = FP_ARM_SCALE, ty = FP_PIVOT_Y * (1 - sc) - EYE_Y;
            double[] armXf = {sc, 0, 0, 0, 0, sc, 0, ty, 0, 0, sc, -EYE_Z};
            r.drawMesh(twoHanded ? armMesh : armMeshR, 0, pose.skin, armXf, fpArmColors, true, null, 1.08);
            drawFpGloves(r, armXf, sc, twoHanded);
        } finally {
            procHold = false;
        }
    }

    /** Точка оружия (координаты модели оружия) -> пространство камеры по матрице gunView. */
    public static void gunViewPoint(double[] gunView, double x, double y, double z, double[] out) {
        out[0] = gunView[0] * x + gunView[1] * y + gunView[2] * z + gunView[3];
        out[1] = gunView[4] * x + gunView[5] * y + gunView[6] * z + gunView[7];
        out[2] = gunView[8] * x + gunView[9] * y + gunView[10] * z + gunView[11];
    }

    // ===================== 11.0: ТАКТИЧЕСКИЕ ПЕРЧАТКИ FP =====================

    /** Позиция сустава в пространстве камеры (через матрицу кости и armXf). */
    private double[] jointView(int j, double[] armXf, double[] out) {
        pose.boneMatrix(j, bone);
        double bx = bone[3], by = bone[7], bz = bone[11];
        out[0] = armXf[0] * bx + armXf[1] * by + armXf[2] * bz + armXf[3];
        out[1] = armXf[4] * bx + armXf[5] * by + armXf[6] * bz + armXf[7];
        out[2] = armXf[8] * bx + armXf[9] * by + armXf[10] * bz + armXf[11];
        return out;
    }

    /**
     * 11.0: тактические перчатки от первого лица. Кожа кистей уже перекрашена (fpArmColors);
     * сверху добавляются объёмные элементы: костяшная пластина на тыльной стороне кисти,
     * манжета на запястье (цилиндр вдоль предплечья) и светлый ремешок.
     */
    private void drawFpGloves(Renderer3D r, double[] armXf, double sc) {
        drawFpGloves(r, armXf, sc, true);
    }

    private void drawFpGloves(Renderer3D r, double[] armXf, double sc, boolean twoHanded) {
        if (jHandR < 0 || jLaR < 0) return;
        double[] gv = gunXf;
        // оси оружия в пространстве камеры (столбцы поворота gunXf), нормализованные
        double[] ax = norm3(gv[0], gv[4], gv[8]);    // X оружия (вправо)
        double[] ay = norm3(gv[1], gv[5], gv[9]);    // Y оружия (вверх)
        double[] az = norm3(gv[2], gv[6], gv[10]);   // Z оружия (вперёд)
        boolean ct = (variant == Variant.CT_SWAT || variant == Variant.CT_SOLDIER);
        int plateCol = (variant == Variant.JUGGERNAUT) ? PLATE_JUGG : (ct ? PLATE_CT : PLATE_T);

        double[] tmp = new double[3];
        double[] tmpE = new double[3];

        // ---- правая рука: костяшки смотрят вправо-вверх (ладонь обхватывает рукоять слева)
        double[] wR = jointView(jHandR, armXf, new double[3]);
        double[] eR = jointView(jLaR, armXf, new double[3]);
        // костяшная пластина: от запястья вперёд по пальцам и наружу от оружия
        double px = wR[0] + az[0] * 0.045 * sc + ax[0] * 0.024 * sc + ay[0] * 0.010 * sc;
        double py = wR[1] + az[1] * 0.045 * sc + ax[1] * 0.024 * sc + ay[1] * 0.010 * sc;
        double pz = wR[2] + az[2] * 0.045 * sc + ax[2] * 0.024 * sc + ay[2] * 0.010 * sc;
        r.drawViewModelBox(px, py, pz, 0.017 * sc, 0.050 * sc, 0.040 * sc,
                0.0, -0.10, 0.0, px, py, pz, Renderer3D.TEX_SOLID, plateCol);
        // манжета: цилиндр вдоль предплечья (запястье -> локоть)
        tmp[0] = wR[0] - eR[0]; tmp[1] = wR[1] - eR[1]; tmp[2] = wR[2] - eR[2];
        double fl = Math.sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2]);
        if (fl > 1e-4) {
            double dx = tmp[0] / fl, dy = tmp[1] / fl, dz = tmp[2] / fl;
            double cx = wR[0] - dx * 0.042 * sc, cy = wR[1] - dy * 0.042 * sc, cz = wR[2] - dz * 0.042 * sc;
            double rotX = -Math.asin(Math.max(-1, Math.min(1, dy)));
            double rotY = Math.atan2(dx, dz);
            r.drawViewModelCylinder(cx, cy, cz, 0.031 * sc, 0.032 * sc, 0.098 * sc,
                    rotX, rotY, 0.0, cx, cy, cz, 12, 0xFFFF00);
            r.drawViewModelCylinder(cx - dx * 0.034 * sc, cy - dy * 0.034 * sc, cz - dz * 0.034 * sc,
                    0.033 * sc, 0.033 * sc, 0.020 * sc, rotX, rotY, 0.0, cx, cy, cz, 12, STRAP_COL);
        }

        // ---- левая рука: тыльная сторона смотрит вниз (кисть держит цевьё снизу)
        if (twoHanded && jHandL >= 0 && jLaL >= 0) {
            double[] wL = jointView(jHandL, armXf, new double[3]);
            double[] eL = jointView(jLaL, armXf, tmpE);
            double qx = wL[0] + az[0] * 0.040 * sc - ay[0] * 0.024 * sc;
            double qy = wL[1] + az[1] * 0.040 * sc - ay[1] * 0.024 * sc;
            double qz = wL[2] + az[2] * 0.040 * sc - ay[2] * 0.024 * sc;
            r.drawViewModelBox(qx, qy, qz, 0.050 * sc, 0.017 * sc, 0.038 * sc,
                    0.0, -0.10, 0.0, qx, qy, qz, Renderer3D.TEX_SOLID, plateCol);
            tmp[0] = wL[0] - eL[0]; tmp[1] = wL[1] - eL[1]; tmp[2] = wL[2] - eL[2];
            double fl2 = Math.sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2]);
            if (fl2 > 1e-4) {
                double dx = tmp[0] / fl2, dy = tmp[1] / fl2, dz = tmp[2] / fl2;
                double cx = wL[0] - dx * 0.042 * sc, cy = wL[1] - dy * 0.042 * sc, cz = wL[2] - dz * 0.042 * sc;
                double rotX = -Math.asin(Math.max(-1, Math.min(1, dy)));
                double rotY = Math.atan2(dx, dz);
                r.drawViewModelCylinder(cx, cy, cz, 0.030 * sc, 0.031 * sc, 0.098 * sc,
                        rotX, rotY, 0.0, cx, cy, cz, 12, 0xFFFF00);
                r.drawViewModelCylinder(cx - dx * 0.034 * sc, cy - dy * 0.034 * sc, cz - dz * 0.034 * sc,
                        0.032 * sc, 0.032 * sc, 0.020 * sc, rotX, rotY, 0.0, cx, cy, cz, 12, STRAP_COL);
            }
        }
    }

    private static double[] norm3(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-9) return new double[]{0, 0, 1};
        return new double[]{x / l, y / l, z / l};
    }
}
