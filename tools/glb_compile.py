#!/usr/bin/env python3
"""
CS-2 JAVA 11.0 — компилятор GLB-моделей (glTF 2.0 binary) в компактный формат .cmdl
для софтверного рендера игры.

Что делает:
  * читает .glb (узлы, скин, inverse bind matrices, материалы baseColorFactor);
  * запекает модель в позу покоя (rest pose) в метрах, Y — вверх;
  * нормализует ориентацию (оружие: ствол по +Z, верх по +Y);
  * упрощает сетку (quadric decimation, pyfqmr) по материалам до нужного числа треугольников (LOD0/LOD1),
    переносит на новые вершины нормали, кости и веса от ближайших исходных вершин;
  * сохраняет скелет (имя, родитель, точка вращения сустава) и цвета материалов в sRGB.

Формат .cmdl (big-endian, читается DataInputStream в MeshModel.java):
  "CMDL" int version(1)
  int nMat   { UTF name, int rgb }
  int nJoint { UTF name, int parent, float px,py,pz }
  int nLod   { int nVert; float[nVert*3] pos; byte[nVert*3] normal*127; byte[nVert*4] joints; byte[nVert*4] weights*255;
               int nTri; ushort[nTri*3] idx; byte[nTri] mat }   (весь файл сжат GZIP)

Модели: Quaternius «Ultimate Modular Men/Women» (CC0), «Flat Guns West/East» (CC0, OpenGameArt).
"""
import json, struct, sys, os, math
import numpy as np

try:
    import pyfqmr
except ImportError:  # упрощение необязательно
    pyfqmr = None

CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def load_glb(path):
    d = open(path, "rb").read()
    assert d[:4] == b"glTF", path
    off = 12
    j = None
    binc = b""
    while off < len(d):
        ln, typ = struct.unpack("<II", d[off:off + 8])
        chunk = d[off + 8: off + 8 + ln]
        if typ == 0x4E4F534A:
            j = json.loads(chunk)
        elif typ == 0x004E4942:
            binc = chunk
        off += 8 + ln
    return j, binc


def accessor(j, binc, idx):
    a = j["accessors"][idx]
    bv = j["bufferViews"][a["bufferView"]]
    dt = CT[a["componentType"]]
    n = NC[a["type"]]
    cnt = a["count"]
    start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = bv.get("byteStride", 0)
    isz = np.dtype(dt).itemsize * n
    if stride and stride != isz:
        raw = np.frombuffer(binc, dtype=np.uint8, count=stride * (cnt - 1) + isz, offset=start)
        rows = np.lib.stride_tricks.as_strided(raw, shape=(cnt, isz), strides=(stride, 1))
        arr = np.frombuffer(rows.copy().tobytes(), dtype=dt).reshape(cnt, n)
    else:
        arr = np.frombuffer(binc, dtype=dt, count=cnt * n, offset=start).reshape(cnt, n)
    arr = arr.astype(np.float64) if dt == np.float32 else arr.copy()
    if a.get("normalized") and dt != np.float32:
        arr = arr.astype(np.float64) / float(np.iinfo(dt).max)
    return arr


def quat_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0],
        [0, 0, 0, 1]])


def node_local(n):
    if "matrix" in n:
        return np.array(n["matrix"], dtype=np.float64).reshape(4, 4).T
    t = np.eye(4)
    t[:3, 3] = n.get("translation", [0, 0, 0])
    r = quat_mat(n.get("rotation", [0, 0, 0, 1]))
    s = np.diag(list(n.get("scale", [1, 1, 1])) + [1])
    return t @ r @ s


def lin2srgb(c):
    c = max(0.0, min(1.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def compile_model(path, out, fix=np.eye(4), lod_targets=(None, 1500), origin_joint=None, keep_joints=None,
                  scale_to_height=None, min_lod1=200):
    j, binc = load_glb(path)
    nodes = j["nodes"]
    parent = {}
    for i, n in enumerate(nodes):
        for c in n.get("children", []):
            parent[c] = i
    G = [None] * len(nodes)

    def glob(i):
        if G[i] is None:
            l = node_local(nodes[i])
            G[i] = (glob(parent[i]) @ l) if i in parent else l
        return G[i]

    for i in range(len(nodes)):
        glob(i)

    mats = []
    for m in j.get("materials", []):
        bc = m.get("pbrMetallicRoughness", {}).get("baseColorFactor", [0.8, 0.8, 0.8, 1])
        r, g, b = [int(round(lin2srgb(c) * 255)) for c in bc[:3]]
        mats.append((m.get("name", "mat"), (r << 16) | (g << 8) | b))
    if not mats:
        mats.append(("default", 0xB0B0B0))

    skin = j["skins"][0] if j.get("skins") else None
    joint_nodes = list(skin["joints"]) if skin else []
    jindex = {nd: k for k, nd in enumerate(joint_nodes)}

    # ---- вершины в позе покоя ----
    allP, allN, allJ, allW, allF, allM = [], [], [], [], [], []
    base = 0
    for ni, n in enumerate(nodes):
        if "mesh" not in n:
            continue
        mesh = j["meshes"][n["mesh"]]
        sk = j["skins"][n["skin"]] if "skin" in n else None
        if sk is not None:
            ibm = accessor(j, binc, sk["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1)
            jm = np.array([glob(nd) @ ibm[k] for k, nd in enumerate(sk["joints"])])
            remap = np.array([jindex.get(nd, 0) for nd in sk["joints"]])
        for pr in mesh["primitives"]:
            at = pr["attributes"]
            P = accessor(j, binc, at["POSITION"])
            N = accessor(j, binc, at["NORMAL"]) if "NORMAL" in at else np.zeros_like(P)
            cnt = len(P)
            if "indices" in pr:
                F = accessor(j, binc, pr["indices"]).reshape(-1, 3).astype(np.int64)
            else:
                F = np.arange(cnt).reshape(-1, 3)
            if sk is not None and "JOINTS_0" in at:
                J = accessor(j, binc, at["JOINTS_0"]).astype(np.int64)
                W = accessor(j, binc, at["WEIGHTS_0"]).astype(np.float64)
                if W.max() > 1.5:
                    W = W / 255.0
                W = W / np.maximum(1e-9, W.sum(1, keepdims=True))
                Ph = np.c_[P, np.ones(cnt)]
                M = np.einsum("vk,vkab->vab", W, jm[J])
                P2 = np.einsum("vab,vb->va", M, Ph)[:, :3]
                N2 = np.einsum("vab,vb->va", M[:, :3, :3], N)
                J2 = remap[J]
            else:
                Gm = glob(ni)
                P2 = (np.c_[P, np.ones(cnt)] @ Gm.T)[:, :3]
                N2 = N @ Gm[:3, :3].T
                J2 = np.zeros((cnt, 4), dtype=np.int64)
                W = np.zeros((cnt, 4)); W[:, 0] = 1
                if ni in jindex:
                    J2[:, 0] = jindex[ni]
            allP.append(P2); allN.append(N2); allJ.append(J2); allW.append(W)
            allF.append(F + base)
            allM.append(np.full(len(F), pr.get("material", 0)))
            base += cnt
    P = np.vstack(allP); N = np.vstack(allN); J = np.vstack(allJ); W = np.vstack(allW)
    F = np.vstack(allF); M = np.concatenate(allM)

    pivots = np.array([glob(nd)[:3, 3] for nd in joint_nodes]) if joint_nodes else np.zeros((0, 3))
    # ---- нормализация: fix-матрица, масштаб и начало координат ----
    def apply(Xp):
        return (np.c_[Xp, np.ones(len(Xp))] @ fix.T)[:, :3]
    P = apply(P)
    N = N @ fix[:3, :3].T
    pivots = apply(pivots) if len(pivots) else pivots
    if scale_to_height:
        h = P[:, 1].max() - P[:, 1].min()
        s = scale_to_height / h
        P *= s; pivots = pivots * s
        P[:, 1] -= 0  # ноги остаются на y=0
    if origin_joint is not None:
        k = [nodes[nd].get("name") for nd in joint_nodes].index(origin_joint)
        o = pivots[k].copy()
        P -= o; pivots = pivots - o
    N /= np.maximum(1e-9, np.linalg.norm(N, axis=1, keepdims=True))
    if np.linalg.det(fix[:3, :3]) < 0:   # зеркалирование (glTF правосторонний -> мир игры левосторонний)
        F = F[:, [0, 2, 1]]

    jparents = []
    for nd in joint_nodes:
        p = parent.get(nd)
        while p is not None and p not in jindex:
            p = parent.get(p)
        jparents.append(jindex[p] if p is not None else -1)
    jnames = [nodes[nd].get("name", "j") for nd in joint_nodes]

    # ---- LOD ----
    lods = []
    for tgt in lod_targets:
        if tgt is None or tgt >= len(F) or pyfqmr is None:
            lods.append(clean(P, N, J, W, F, M))
            continue
        lods.append(decimate(P, N, J, W, F, M, tgt, min_lod1))

    import gzip
    with gzip.open(out, "wb", compresslevel=9) as f:
        f.write(b"CMDL"); f.write(struct.pack(">i", 1))
        f.write(struct.pack(">i", len(mats)))
        for nm, rgb in mats:
            wutf(f, nm); f.write(struct.pack(">i", rgb))
        f.write(struct.pack(">i", len(jnames)))
        for k, nm in enumerate(jnames):
            wutf(f, nm); f.write(struct.pack(">i", jparents[k])); f.write(struct.pack(">3f", *pivots[k]))
        f.write(struct.pack(">i", len(lods)))
        for (lp, ln, lj, lw, lf, lm) in lods:
            f.write(struct.pack(">i", len(lp)))
            wq = np.clip(np.round(lw * 255), 0, 255).astype(np.uint8)
            f.write(lp.astype(">f4").tobytes())
            f.write(np.clip(np.round(ln * 127), -127, 127).astype(np.int8).tobytes())
            f.write((lj & 0xFF).astype(np.uint8).tobytes())
            f.write(wq.tobytes())
            f.write(struct.pack(">i", len(lf)))
            f.write(lf.astype(">u2").tobytes())
            f.write(lm.astype(np.uint8).tobytes())
    info = dict(file=os.path.basename(out), mats=len(mats), joints=len(jnames),
                lods=[len(l[4]) for l in lods],
                bbox=[np.round(P.min(0), 3).tolist(), np.round(P.max(0), 3).tolist()])
    return info, P, F, M, mats, jnames, pivots


def clean(P, N, J, W, F, M):
    used = np.unique(F)
    remap = -np.ones(len(P), dtype=np.int64); remap[used] = np.arange(len(used))
    return P[used], N[used], J[used], W[used], remap[F], M


def decimate(P, N, J, W, F, M, target, min_tris):
    total = len(F)
    outP, outN, outJ, outW, outF, outM = [], [], [], [], [], []
    base = 0
    for mat in np.unique(M):
        sel = F[M == mat]
        used = np.unique(sel)
        # сварка вершин по позиции (плоские low-poly модели хранят каждую грань отдельно)
        key = np.round(P[used] / 1e-5).astype(np.int64)
        _, first, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
        inv = inv.reshape(-1)
        remap = -np.ones(len(P), dtype=np.int64); remap[used] = inv
        lp = P[used][first]; lf = remap[sel]
        lf = lf[(lf[:, 0] != lf[:, 1]) & (lf[:, 1] != lf[:, 2]) & (lf[:, 0] != lf[:, 2])]
        used = used[first]
        want = max(12, int(round(target * len(sel) / total)))
        if len(sel) > want and len(sel) > 24:
            s = pyfqmr.Simplify()
            s.setMesh(lp.astype(np.float64), lf.astype(np.int32))
            s.simplify_mesh(target_count=want, aggressiveness=6, preserve_border=True, verbose=False)
            vp, vf, _ = s.getMesh()
        else:
            vp, vf = lp, lf
        # перенос атрибутов от ближайшей исходной вершины
        src = used
        idx = nearest(P[src], vp)
        orig = src[idx]
        outP.append(vp); outN.append(N[orig]); outJ.append(J[orig]); outW.append(W[orig])
        outF.append(np.asarray(vf, dtype=np.int64) + base); outM.append(np.full(len(vf), mat))
        base += len(vp)
    return (np.vstack(outP), np.vstack(outN), np.vstack(outJ), np.vstack(outW), np.vstack(outF), np.concatenate(outM))


def nearest(A, B):
    res = np.empty(len(B), dtype=np.int64)
    for i in range(0, len(B), 512):
        d = ((B[i:i + 512, None, :] - A[None, :, :]) ** 2).sum(-1)
        res[i:i + 512] = d.argmin(1)
    return res


def wutf(f, s):
    b = s.encode("utf-8")
    f.write(struct.pack(">H", len(b))); f.write(b)


def preview(P, F, M, mats, out, view="side", size=360):
    from PIL import Image, ImageDraw
    if view == "side":       # смотрим с +X: экран x = z, экран y = -y
        X, Y, Z = P[:, 2], -P[:, 1], P[:, 0]
    elif view == "front":    # смотрим с +Z
        X, Y, Z = -P[:, 0], -P[:, 1], P[:, 2]
    else:
        X, Y, Z = P[:, 0] * 0.7 + P[:, 2] * 0.7, -P[:, 1], P[:, 2] * 0.7 - P[:, 0] * 0.7
    mn = np.array([X.min(), Y.min()]); mx = np.array([X.max(), Y.max()])
    sc = (size - 20) / max(mx - mn)
    img = Image.new("RGB", (size, size), (40, 44, 52))
    d = ImageDraw.Draw(img)
    v = np.c_[X, Y]
    tri = v[F]
    depth = Z[F].mean(1)
    e1 = P[F[:, 1]] - P[F[:, 0]]; e2 = P[F[:, 2]] - P[F[:, 0]]
    nrm = np.cross(e1, e2); nrm /= np.maximum(1e-9, np.linalg.norm(nrm, axis=1, keepdims=True))
    L = np.array([0.4, 0.7, 0.6]); L /= np.linalg.norm(L)
    for t in np.argsort(depth):
        c = mats[M[t]][1]
        lum = 0.45 + 0.55 * abs(nrm[t] @ L)
        col = tuple(int(min(255, ((c >> s) & 255) * lum)) for s in (16, 8, 0))
        pts = [tuple(((p - mn) * sc + 10).tolist()) for p in tri[t]]
        d.polygon(pts, fill=col)
    img.save(out)


# glTF: правосторонняя система, Y вверх. Мир CS-2 JAVA: X вправо, Y вверх, Z вперёд (левосторонняя).
MIRROR_X = np.diag([-1.0, 1.0, 1.0, 1.0])     # персонажи смотрят в +Z, правая рука уходит в +X
GUN_FIX = np.diag([1.0, 1.0, -1.0, 1.0])      # ствол оружия glTF (-Z) -> +Z игры

if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else "assets/glb"
    dst = sys.argv[2] if len(sys.argv) > 2 else "res/models"
    prev = sys.argv[3] if len(sys.argv) > 3 else None
    os.makedirs(dst, exist_ok=True)
    jobs = []
    # Персонажи: высота 1.80 м, лицом к +Z
    for nm in ["Swat", "Soldier", "Punk"]:
        jobs.append((nm + ".glb", "char_" + nm.lower() + ".cmdl", dict(fix=MIRROR_X, lod_targets=(None, 2400, 900), scale_to_height=1.80)))
    for fn in sorted(os.listdir(src)):
        if fn.endswith("_East.glb") or fn.endswith("_West.glb"):
            jobs.append((fn, "gun_" + fn[:-4].lower() + ".cmdl",
                         dict(fix=GUN_FIX, lod_targets=(None, 380), origin_joint="Trigger")))
    for fn, outn, kw in jobs:
        p = os.path.join(src, fn)
        if not os.path.exists(p):
            print("skip", fn); continue
        info, P, F, M, mats, jn, piv = compile_model(p, os.path.join(dst, outn), **kw)
        print(info)
        if prev:
            os.makedirs(prev, exist_ok=True)
            preview(P, F, M, mats, os.path.join(prev, outn + ".png"), "side" if outn.startswith("gun") else "front")
