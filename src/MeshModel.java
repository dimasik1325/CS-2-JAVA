import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * CS-2 JAVA 11.0 — настоящие 3D-модели из GLB (glTF 2.0), скомпилированные tools/glb_compile.py в .cmdl.
 *
 * Персонажи: Quaternius «Ultimate Modular Men/Women» (CC0) — 49 костей, скиннинг до 4 костей на вершину.
 * Оружие: «Flat Guns West/East» (CC0) — подвижные магазин, затвор, помпа, спуск и точки крепления (дуло, прицел).
 *
 * Анимация процедурная: у каждой кости — поворот в системе координат позы покоя вокруг её сустава
 * (S_j = S_parent · T(p_j) · R_j · T(-p_j) · T(offset_j)), плюс аналитический двухзвенный IK для рук.
 * Координаты модели: X — вправо, Y — вверх, Z — вперёд (как в мире игры), метры.
 */
public final class MeshModel {
    public static boolean DEBUG_IK = false;

    public static final class Lod {
        public int nv, nt;
        public float[] pos;     // 3 * nv
        public float[] nrm;     // 3 * nv
        public byte[] jnt;      // 4 * nv
        public float[] wgt;     // 4 * nv
        public boolean[] single;// вершина целиком на одной кости — быстрый путь
        public int[] idx;       // 3 * nt
        public byte[] mat;      // nt
        public float radius;    // радиус ограничивающей сферы от (0, cy, 0)
        public float centerY;
    }

    public final String name;
    public final String[] matNames;
    public final int[] matColors;
    public final String[] jointNames;
    public final int[] jointParent;
    public final float[] pivot;     // 3 * nj
    public final int[] order;       // топологический порядок костей (родитель раньше ребёнка)
    public final Lod[] lods;
    private final HashMap<String, Integer> jointIndex = new HashMap<>();
    private final HashMap<String, Integer> matIndex = new HashMap<>();

    private MeshModel(String name, String[] mn, int[] mc, String[] jn, int[] jp, float[] piv, Lod[] lods) {
        this.name = name;
        this.matNames = mn;
        this.matColors = mc;
        this.jointNames = jn;
        this.jointParent = jp;
        this.pivot = piv;
        this.lods = lods;
        for (int i = 0; i < jn.length; i++) jointIndex.put(jn[i], i);
        for (int i = 0; i < mn.length; i++) matIndex.put(mn[i], i);
        int n = jn.length;
        order = new int[n];
        boolean[] done = new boolean[n];
        int k = 0;
        while (k < n) {
            int before = k;
            for (int i = 0; i < n; i++) {
                if (done[i]) continue;
                int p = jp[i];
                if (p < 0 || done[p]) { order[k++] = i; done[i] = true; }
            }
            if (k == before) { // цикл (не должно быть) — добиваем как есть
                for (int i = 0; i < n; i++) if (!done[i]) { order[k++] = i; done[i] = true; }
            }
        }
    }

    public int joint(String n) { Integer i = jointIndex.get(n); return i == null ? -1 : i; }
    public int material(String n) { Integer i = matIndex.get(n); return i == null ? -1 : i; }
    public int jointCount() { return jointNames.length; }
    public double px(int j) { return pivot[j * 3]; }
    public double py(int j) { return pivot[j * 3 + 1]; }
    public double pz(int j) { return pivot[j * 3 + 2]; }

    /** Индекс материала по подстроке имени (без учёта регистра) или -1. */
    public int materialLike(String part) {
        String p = part.toLowerCase();
        for (int i = 0; i < matNames.length; i++) if (matNames[i].toLowerCase().contains(p)) return i;
        return -1;
    }

    // ===================== ЗАГРУЗКА =====================

    private static final ConcurrentHashMap<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    /** Модель по имени файла без расширения (например "char_swat"); null, если ресурс недоступен. */
    public static MeshModel get(String name) {
        Object o = CACHE.get(name);
        if (o == null) {
            synchronized (CACHE) {
                o = CACHE.get(name);
                if (o == null) {
                    MeshModel m = null;
                    try { m = name.equals("__cyl") ? makeCylinder(14) : (name.equals("__box") ? makeBox() : load(name)); } catch (Exception e) {
                        System.err.println("[MeshModel] не удалось загрузить " + name + ": " + e);
                    }
                    o = (m == null) ? MISSING : m;
                    CACHE.put(name, o);
                }
            }
        }
        return o == MISSING ? null : (MeshModel) o;
    }

    private static InputStream open(String name) throws IOException {
        InputStream in = MeshModel.class.getResourceAsStream("/models/" + name + ".cmdl");
        if (in != null) return in;
        String[] dirs = {"res/models", "../res/models", "models"};
        for (String d : dirs) {
            File f = new File(d, name + ".cmdl");
            if (f.isFile()) return new FileInputStream(f);
        }
        return null;
    }

    private static MeshModel load(String name) throws IOException {
        InputStream raw = open(name);
        if (raw == null) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 16))) {
            byte[] mg = new byte[4];
            in.readFully(mg);
            if (mg[0] != 'C' || mg[1] != 'M' || mg[2] != 'D' || mg[3] != 'L') throw new IOException("bad magic");
            in.readInt();
            int nm = in.readInt();
            String[] mn = new String[nm];
            int[] mc = new int[nm];
            for (int i = 0; i < nm; i++) { mn[i] = in.readUTF(); mc[i] = in.readInt(); }
            int nj = in.readInt();
            String[] jn = new String[nj];
            int[] jp = new int[nj];
            float[] piv = new float[nj * 3];
            for (int i = 0; i < nj; i++) {
                jn[i] = in.readUTF();
                jp[i] = in.readInt();
                piv[i * 3] = in.readFloat(); piv[i * 3 + 1] = in.readFloat(); piv[i * 3 + 2] = in.readFloat();
            }
            int nl = in.readInt();
            Lod[] lods = new Lod[nl];
            for (int l = 0; l < nl; l++) {
                Lod d = new Lod();
                d.nv = in.readInt();
                d.pos = new float[d.nv * 3];
                for (int i = 0; i < d.nv * 3; i++) d.pos[i] = in.readFloat();
                d.nrm = new float[d.nv * 3];
                for (int v = 0; v < d.nv; v++) {
                    float x = in.readByte(), y = in.readByte(), z = in.readByte();
                    float len = (float) Math.sqrt(x * x + y * y + z * z);
                    if (len < 1e-3f) { x = 0; y = 1; z = 0; len = 1; }
                    d.nrm[v * 3] = x / len; d.nrm[v * 3 + 1] = y / len; d.nrm[v * 3 + 2] = z / len;
                }
                d.jnt = new byte[d.nv * 4];
                in.readFully(d.jnt);
                byte[] w = new byte[d.nv * 4];
                in.readFully(w);
                d.wgt = new float[d.nv * 4];
                d.single = new boolean[d.nv];
                for (int v = 0; v < d.nv; v++) {
                    float s = 0;
                    for (int k = 0; k < 4; k++) s += (w[v * 4 + k] & 0xFF);
                    if (s <= 0) { s = 255; w[v * 4] = (byte) 255; }
                    int nonZero = 0;
                    for (int k = 0; k < 4; k++) {
                        d.wgt[v * 4 + k] = (w[v * 4 + k] & 0xFF) / s;
                        if ((w[v * 4 + k] & 0xFF) > 2) nonZero++;
                    }
                    d.single[v] = nonZero <= 1 && d.wgt[v * 4] > 0.97f;
                }
                d.nt = in.readInt();
                d.idx = new int[d.nt * 3];
                for (int i = 0; i < d.nt * 3; i++) d.idx[i] = in.readUnsignedShort();
                d.mat = new byte[d.nt];
                in.readFully(d.mat);
                float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int v = 0; v < d.nv; v++) { minY = Math.min(minY, d.pos[v * 3 + 1]); maxY = Math.max(maxY, d.pos[v * 3 + 1]); }
                d.centerY = (minY + maxY) * 0.5f;
                float r2 = 0;
                for (int v = 0; v < d.nv; v++) {
                    float x = d.pos[v * 3], y = d.pos[v * 3 + 1] - d.centerY, z = d.pos[v * 3 + 2];
                    r2 = Math.max(r2, x * x + y * y + z * z);
                }
                d.radius = (float) Math.sqrt(r2);
                lods[l] = d;
            }
            return new MeshModel(name, mn, mc, jn, jp, piv, lods);
        }
    }

    // ===================== ПРОЦЕДУРНЫЕ ПРИМИТИВЫ =====================

    private static Lod newLod(int nv, int nt) {
        Lod d = new Lod();
        d.nv = nv; d.nt = nt;
        d.pos = new float[nv * 3]; d.nrm = new float[nv * 3];
        d.jnt = new byte[nv * 4]; d.wgt = new float[nv * 4]; d.single = new boolean[nv];
        for (int v = 0; v < nv; v++) { d.wgt[v * 4] = 1f; d.single[v] = true; }
        d.idx = new int[nt * 3]; d.mat = new byte[nt];
        return d;
    }

    private static MeshModel wrap(String name, Lod d) {
        d.centerY = 0; d.radius = 1.5f;
        return new MeshModel(name, new String[]{"solid"}, new int[]{0x808080}, new String[]{"root"}, new int[]{-1},
                new float[3], new Lod[]{d});
    }

    /** Цилиндр радиуса 1 вдоль +Z от 0 до 1 с торцами (треугольники обращены наружу в системе мира игры). */
    static MeshModel makeCylinder(int seg) {
        Lod d = newLod(seg * 2 + (seg + 1) * 2, seg * 2 + seg * 2);
        int v = 0, t = 0;
        for (int i = 0; i < seg; i++) {
            double a = Math.PI * 2 * i / seg;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            for (int e = 0; e < 2; e++) {
                d.pos[v * 3] = c; d.pos[v * 3 + 1] = sn; d.pos[v * 3 + 2] = e;
                d.nrm[v * 3] = c; d.nrm[v * 3 + 1] = sn; d.nrm[v * 3 + 2] = 0;
                v++;
            }
        }
        for (int i = 0; i < seg; i++) {
            int a0 = i * 2, a1 = a0 + 1, b0 = ((i + 1) % seg) * 2, b1 = b0 + 1;
            tri(d, t++, a0, b0, b1); tri(d, t++, a0, b1, a1);
        }
        for (int e = 0; e < 2; e++) {
            int center = v;
            d.pos[v * 3 + 2] = e; d.nrm[v * 3 + 2] = e == 0 ? -1 : 1; v++;
            int first = v;
            for (int i = 0; i < seg; i++) {
                double a = Math.PI * 2 * i / seg;
                d.pos[v * 3] = (float) Math.cos(a); d.pos[v * 3 + 1] = (float) Math.sin(a); d.pos[v * 3 + 2] = e;
                d.nrm[v * 3 + 2] = e == 0 ? -1 : 1;
                v++;
            }
            for (int i = 0; i < seg; i++) {
                int p0 = first + i, p1 = first + (i + 1) % seg;
                if (e == 0) tri(d, t++, center, p1, p0); else tri(d, t++, center, p0, p1);
            }
        }
        d.nv = v; d.nt = t;
        return wrap("__cyl", d);
    }

    /** Куб со стороной 1 с центром в начале координат. */
    static MeshModel makeBox() {
        Lod d = newLod(24, 12);
        float[][] nr = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        int v = 0, t = 0;
        for (float[] n : nr) {
            // два касательных вектора
            float[] u = Math.abs(n[1]) > 0.5f ? new float[]{1,0,0} : new float[]{0,1,0};
            float[] w = {n[1]*u[2]-n[2]*u[1], n[2]*u[0]-n[0]*u[2], n[0]*u[1]-n[1]*u[0]};
            u = new float[]{w[1]*n[2]-w[2]*n[1], w[2]*n[0]-w[0]*n[2], w[0]*n[1]-w[1]*n[0]};
            int base = v;
            float[][] cs = {{-1,-1},{1,-1},{1,1},{-1,1}};
            for (float[] c : cs) {
                for (int k = 0; k < 3; k++) {
                    d.pos[v * 3 + k] = 0.5f * (n[k] + c[0] * u[k] + c[1] * w[k]);
                    d.nrm[v * 3 + k] = n[k];
                }
                v++;
            }
            tri(d, t++, base, base + 1, base + 2); tri(d, t++, base, base + 2, base + 3);
        }
        return wrap("__box", d);
    }

    private static void tri(Lod d, int t, int a, int b, int c) {
        d.idx[t * 3] = a; d.idx[t * 3 + 1] = b; d.idx[t * 3 + 2] = c;
    }

    /** Треугольники, все вершины которых принадлежат указанным костям (например, только руки для вида от 1-го лица). */
    /** Выделение подмножества треугольников в отдельный меш (скиннинг только нужных вершин). */
    public MeshModel extractSubset(int lod, int[] tris) {
        Lod d = lods[lod];
        int[] remap = new int[d.nv];
        java.util.Arrays.fill(remap, -1);
        int[] idx = new int[tris.length * 3];
        byte[] mat = new byte[tris.length];
        int c = 0;
        for (int t = 0; t < tris.length; t++) {
            int ti = tris[t];
            mat[t] = d.mat[ti];
            for (int k = 0; k < 3; k++) {
                int v = d.idx[ti * 3 + k];
                if (remap[v] < 0) remap[v] = c++;
                idx[t * 3 + k] = remap[v];
            }
        }
        Lod o = new Lod();
        o.nv = c; o.nt = tris.length;
        o.pos = new float[c * 3]; o.nrm = new float[c * 3];
        o.jnt = new byte[c * 4]; o.wgt = new float[c * 4];
        o.single = new boolean[c];
        o.idx = idx; o.mat = mat;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int v = 0; v < d.nv; v++) {
            int r = remap[v];
            if (r < 0) continue;
            System.arraycopy(d.pos, v * 3, o.pos, r * 3, 3);
            System.arraycopy(d.nrm, v * 3, o.nrm, r * 3, 3);
            System.arraycopy(d.jnt, v * 4, o.jnt, r * 4, 4);
            System.arraycopy(d.wgt, v * 4, o.wgt, r * 4, 4);
            o.single[r] = d.single[v];
            float y = d.pos[v * 3 + 1];
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        o.centerY = (minY + maxY) * 0.5f;
        double rad = 0;
        for (int v = 0; v < c; v++) {
            double dx = o.pos[v * 3], dy = o.pos[v * 3 + 1] - o.centerY, dz = o.pos[v * 3 + 2];
            rad = Math.max(rad, dx * dx + dy * dy + dz * dz);
        }
        o.radius = (float) Math.sqrt(rad);
        return new MeshModel(name + "_sub", matNames, matColors, jointNames, jointParent, pivot, new Lod[]{o});
    }

    public int[] trianglesOfJoints(int lod, java.util.function.IntPredicate jointFilter) {
        Lod d = lods[lod];
        int[] tmp = new int[d.nt];
        int c = 0;
        for (int t = 0; t < d.nt; t++) {
            boolean ok = true;
            for (int k = 0; k < 3 && ok; k++) {
                int v = d.idx[t * 3 + k];
                int best = 0; float bw = -1;
                for (int q = 0; q < 4; q++) if (d.wgt[v * 4 + q] > bw) { bw = d.wgt[v * 4 + q]; best = d.jnt[v * 4 + q] & 0xFF; }
                ok = jointFilter.test(best);
            }
            if (ok) tmp[c++] = t;
        }
        return java.util.Arrays.copyOf(tmp, c);
    }

    // ===================== ПОЗА И СКИННИНГ =====================

    /** Поза скелета: повороты/сдвиги костей и итоговые матрицы скиннинга 3x4 (row-major). */
    public static final class Pose {
        public final MeshModel model;
        public final double[] rot;     // 9 на кость
        public final double[] off;     // 3 на кость (сдвиг после поворота, в осях покоя)
        public final double[] scl;     // посуставной масштаб (1 = без изменений)
        public final float[] skin;     // 12 на кость
        private final double[] sk;     // double-копия для вычислений

        public Pose(MeshModel m) {
            model = m;
            int n = m.jointCount();
            rot = new double[n * 9];
            off = new double[n * 3];
            scl = new double[n];
            skin = new float[n * 12];
            sk = new double[n * 12];
            reset();
        }

        public void reset() {
            int n = model.jointCount();
            for (int j = 0; j < n; j++) {
                int b = j * 9;
                for (int k = 0; k < 9; k++) rot[b + k] = (k % 4 == 0) ? 1 : 0;
                off[j * 3] = off[j * 3 + 1] = off[j * 3 + 2] = 0;
                scl[j] = 1.0;
            }
        }

        /** Домножить поворот кости j (слева) на поворот вокруг оси (ax,ay,az) на угол ang. */
        public void rotate(int j, double ax, double ay, double az, double ang) {
            if (j < 0 || Math.abs(ang) < 1e-9) return;
            double[] r = axisAngle(ax, ay, az, ang);
            mul3(r, 0, rot, j * 9, rot, j * 9);
        }

        public void rotateX(int j, double a) { rotate(j, 1, 0, 0, a); }
        public void rotateY(int j, double a) { rotate(j, 0, 1, 0, a); }
        public void rotateZ(int j, double a) { rotate(j, 0, 0, 1, a); }

        public void setRotation(int j, double[] r9) {
            if (j < 0) return;
            System.arraycopy(r9, 0, rot, j * 9, 9);
        }

        public void translate(int j, double x, double y, double z) {
            if (j < 0) return;
            off[j * 3] += x; off[j * 3 + 1] += y; off[j * 3 + 2] += z;
        }

        /** Пересчитать матрицы скиннинга для всех костей. */
        public void compute() {
            MeshModel m = model;
            for (int oi = 0; oi < m.order.length; oi++) {
                int j = m.order[oi];
                double[] r = rot;
                int rb = j * 9;
                double px = m.pivot[j * 3], py = m.pivot[j * 3 + 1], pz = m.pivot[j * 3 + 2];
                double ox = off[j * 3], oy = off[j * 3 + 1], oz = off[j * 3 + 2];
                // L = T(p) R S(k) T(-p) T(off):  A = k*R,  t = p - k*R p + k*R off
                double k = scl[j];
                double m0 = r[rb] * k, m1 = r[rb + 1] * k, m2 = r[rb + 2] * k;
                double m3 = r[rb + 3] * k, m4 = r[rb + 4] * k, m5 = r[rb + 5] * k;
                double m6 = r[rb + 6] * k, m7 = r[rb + 7] * k, m8 = r[rb + 8] * k;
                double qx = ox - px, qy = oy - py, qz = oz - pz;
                double tx = px + m0 * qx + m1 * qy + m2 * qz;
                double ty = py + m3 * qx + m4 * qy + m5 * qz;
                double tz = pz + m6 * qx + m7 * qy + m8 * qz;
                int sb = j * 12;
                int p = m.jointParent[j];
                if (p < 0) {
                    sk[sb] = m0; sk[sb + 1] = m1; sk[sb + 2] = m2; sk[sb + 3] = tx;
                    sk[sb + 4] = m3; sk[sb + 5] = m4; sk[sb + 6] = m5; sk[sb + 7] = ty;
                    sk[sb + 8] = m6; sk[sb + 9] = m7; sk[sb + 10] = m8; sk[sb + 11] = tz;
                } else {
                    int pb = p * 12;
                    for (int row = 0; row < 3; row++) {
                        double a0 = sk[pb + row * 4], a1 = sk[pb + row * 4 + 1], a2 = sk[pb + row * 4 + 2], a3 = sk[pb + row * 4 + 3];
                        sk[sb + row * 4]     = a0 * m0 + a1 * m3 + a2 * m6;
                        sk[sb + row * 4 + 1] = a0 * m1 + a1 * m4 + a2 * m7;
                        sk[sb + row * 4 + 2] = a0 * m2 + a1 * m5 + a2 * m8;
                        sk[sb + row * 4 + 3] = a0 * tx + a1 * ty + a2 * tz + a3;
                    }
                }
            }
            for (int i = 0; i < sk.length; i++) skin[i] = (float) sk[i];
        }

        /** Позиция сустава j в текущей позе (координаты модели). */
        public void jointPos(int j, double[] out) {
            int b = j * 12;
            double x = model.pivot[j * 3], y = model.pivot[j * 3 + 1], z = model.pivot[j * 3 + 2];
            out[0] = sk[b] * x + sk[b + 1] * y + sk[b + 2] * z + sk[b + 3];
            out[1] = sk[b + 4] * x + sk[b + 5] * y + sk[b + 6] * z + sk[b + 7];
            out[2] = sk[b + 8] * x + sk[b + 9] * y + sk[b + 10] * z + sk[b + 11];
        }

        /** Преобразовать точку покоя (rx,ry,rz) матрицей кости j. */
        public void transformPoint(int j, double rx, double ry, double rz, double[] out) {
            int b = j * 12;
            out[0] = sk[b] * rx + sk[b + 1] * ry + sk[b + 2] * rz + sk[b + 3];
            out[1] = sk[b + 4] * rx + sk[b + 5] * ry + sk[b + 6] * rz + sk[b + 7];
            out[2] = sk[b + 8] * rx + sk[b + 9] * ry + sk[b + 10] * rz + sk[b + 11];
        }

        /** Матрица скиннинга кости (12 double, 3x4 row-major). */
        public double[] boneMatrix(int j, double[] out) {
            System.arraycopy(sk, j * 12, out, 0, 12);
            return out;
        }

        /**
         * Аналитический двухзвенный IK (плечо → локоть → кисть) к цели (tx,ty,tz) в координатах модели.
         * pole — желаемое направление локтя (координаты модели). Требует актуальных матриц (compute()).
         * После вызова нужно снова вызвать compute().
         */
        public void solveTwoBoneIK(int upper, int lower, int hand, double tx, double ty, double tz,
                                   double poleX, double poleY, double poleZ) {
            if (upper < 0 || lower < 0 || hand < 0) return;
            MeshModel m = model;
            int parent = m.jointParent[upper];
            double[] A = new double[12];
            if (parent >= 0) boneMatrix(parent, A); else { A[0] = A[5] = A[10] = 1; }
            // цель и полюс в системе покоя родителя: x = A^-1 (T - t)  (A — поворот, ортонормированный)
            double dx = tx - A[3], dy = ty - A[7], dz = tz - A[11];
            double lx = A[0] * dx + A[4] * dy + A[8] * dz;
            double ly = A[1] * dx + A[5] * dy + A[9] * dz;
            double lz = A[2] * dx + A[6] * dy + A[10] * dz;
            double plx = A[0] * poleX + A[4] * poleY + A[8] * poleZ;
            double ply = A[1] * poleX + A[5] * poleY + A[9] * poleZ;
            double plz = A[2] * poleX + A[6] * poleY + A[10] * poleZ;

            double ux = m.px(upper), uy = m.py(upper), uz = m.pz(upper);
            double ex = m.px(lower), ey = m.py(lower), ez = m.pz(lower);
            double hx = m.px(hand), hy = m.py(hand), hz = m.pz(hand);
            double a = len(ex - ux, ey - uy, ez - uz);
            double b = len(hx - ex, hy - ey, hz - ez);
            double tdx = lx - ux, tdy = ly - uy, tdz = lz - uz;
            double dist = len(tdx, tdy, tdz);
            if (dist < 1e-6) return;
            double ndx = tdx / dist, ndy = tdy / dist, ndz = tdz / dist;
            dist = Math.max(Math.abs(a - b) + 1e-4, Math.min(a + b - 1e-4, dist));
            if (DEBUG_IK) System.out.printf("IK u%d: a=%.3f b=%.3f dist=%.3f %s%n", upper, a, b, len(tdx,tdy,tdz), dist >= a+b-1e-4 ? "CLAMP" : "ok");
            double cosA = (a * a + dist * dist - b * b) / (2 * a * dist);
            cosA = Math.max(-1, Math.min(1, cosA));
            double sinA = Math.sqrt(1 - cosA * cosA);
            double pd = plx * ndx + ply * ndy + plz * ndz;
            double qx = plx - ndx * pd, qy = ply - ndy * pd, qz = plz - ndz * pd;
            double ql = len(qx, qy, qz);
            if (ql < 1e-6) { qx = 0; qy = -1; qz = 0; ql = 1; }
            qx /= ql; qy /= ql; qz /= ql;
            double nex = ux + (ndx * cosA + qx * sinA) * a;
            double ney = uy + (ndy * cosA + qy * sinA) * a;
            double nez = uz + (ndz * cosA + qz * sinA) * a;
            double nhx = ux + ndx * dist, nhy = uy + ndy * dist, nhz = uz + ndz * dist;

            double[] ru = minRotation(ex - ux, ey - uy, ez - uz, nex - ux, ney - uy, nez - uz);
            setRotation(upper, ru);
            // желаемое направление предплечья в системе плеча: R_u^T (H' - E')
            double fx = nhx - nex, fy = nhy - ney, fz = nhz - nez;
            double gx = ru[0] * fx + ru[3] * fy + ru[6] * fz;
            double gy = ru[1] * fx + ru[4] * fy + ru[7] * fz;
            double gz = ru[2] * fx + ru[5] * fy + ru[8] * fz;
            double[] rl = minRotation(hx - ex, hy - ey, hz - ez, gx, gy, gz);
            setRotation(lower, rl);
        }
    }

    // ===================== МАТЕМАТИКА =====================

    static double len(double x, double y, double z) { return Math.sqrt(x * x + y * y + z * z); }

    public static double[] axisAngle(double ax, double ay, double az, double ang) {
        double l = len(ax, ay, az);
        if (l < 1e-12) return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        ax /= l; ay /= l; az /= l;
        double c = Math.cos(ang), s = Math.sin(ang), t = 1 - c;
        return new double[]{
                t * ax * ax + c,      t * ax * ay - s * az, t * ax * az + s * ay,
                t * ax * ay + s * az, t * ay * ay + c,      t * ay * az - s * ax,
                t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c};
    }

    /** Минимальный поворот, переводящий направление a в направление b. */
    public static double[] minRotation(double ax, double ay, double az, double bx, double by, double bz) {
        double la = len(ax, ay, az), lb = len(bx, by, bz);
        if (la < 1e-9 || lb < 1e-9) return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        ax /= la; ay /= la; az /= la; bx /= lb; by /= lb; bz /= lb;
        double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
        double d = ax * bx + ay * by + az * bz;
        double s = len(cx, cy, cz);
        if (s < 1e-9) {
            if (d > 0) return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            // противоположные направления — поворот на 180° вокруг любой перпендикулярной оси
            double px = Math.abs(ax) < 0.9 ? 1 : 0, py = Math.abs(ax) < 0.9 ? 0 : 1;
            double qx = ay * 0 - az * py, qy = az * px - ax * 0, qz = ax * py - ay * px;
            return axisAngle(qx, qy, qz, Math.PI);
        }
        return axisAngle(cx, cy, cz, Math.atan2(s, d));
    }

    /** out = a * b (3x3, row-major, допускается out == b). */
    static void mul3(double[] a, int ao, double[] b, int bo, double[] out, int oo) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++)
            for (int k = 0; k < 3; k++)
                r[i * 3 + k] = a[ao + i * 3] * b[bo + k] + a[ao + i * 3 + 1] * b[bo + 3 + k] + a[ao + i * 3 + 2] * b[bo + 6 + k];
        System.arraycopy(r, 0, out, oo, 9);
    }
}
