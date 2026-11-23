#!/usr/bin/env python3
"""
Builds native Windows 10/11 x64 executable CS-2-JAVA.exe + CS-2-JAVA.jar + CS-2-JAVA.zip.
Embeds:
  - CS-2-JAVA.jar directly inside CS-2-JAVA.exe (.rdata) for single-file execution
  - Multi-resolution 32-bit ARGB CS2 icon (16, 32, 48, 64 DIB + 256 PNG)
  - Windows 10/11 UAC asInvoker + PerMonitorV2 DPI + CommonControls v6 XML Manifest
  - Win32 VERSIONINFO metadata (10.0.0.0)
"""
import os
import shutil
import struct
import subprocess
import zlib
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD_TMP = "/tmp/cs2_exe_build"


def parse_png_rgba(png_bytes):
    """Minimal pure-Python RGBA PNG decoder for our generated icon PNGs."""
    assert png_bytes[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    w = h = 0
    idat = bytearray()
    while pos < len(png_bytes):
        length = struct.unpack(">I", png_bytes[pos:pos + 4])[0]
        ctype = png_bytes[pos + 4:pos + 8]
        chunk = png_bytes[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            w, h, bit_depth, color_type, _, _, _ = struct.unpack(">IIBBBBB", chunk)
            assert bit_depth == 8 and color_type == 6, f"Unexpected PNG format: depth={bit_depth}, type={color_type}"
        elif ctype == b"IDAT":
            idat.extend(chunk)
        elif ctype == b"IEND":
            break

    raw = zlib.decompress(bytes(idat))
    stride = w * 4
    rows = []
    i = 0
    prev = bytearray(stride)
    for _ in range(h):
        ftype = raw[i]
        i += 1
        cur = bytearray(raw[i:i + stride])
        i += stride
        if ftype == 1:  # Sub
            for x in range(4, stride):
                cur[x] = (cur[x] + cur[x - 4]) & 0xFF
        elif ftype == 2:  # Up
            for x in range(stride):
                cur[x] = (cur[x] + prev[x]) & 0xFF
        elif ftype == 3:  # Average
            for x in range(stride):
                a = cur[x - 4] if x >= 4 else 0
                b = prev[x]
                cur[x] = (cur[x] + ((a + b) >> 1)) & 0xFF
        elif ftype == 4:  # Paeth
            for x in range(stride):
                a = cur[x - 4] if x >= 4 else 0
                b = prev[x]
                c = prev[x - 4] if x >= 4 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[x] = (cur[x] + pr) & 0xFF
        rows.append(bytes(cur))
        prev = cur
    return w, h, rows


def png_to_ico_dib(png_bytes):
    """Converts 32-bit RGBA PNG into bottom-up BGRA DIB + 1-bit AND mask for Win32 RT_ICON."""
    w, h, rows = parse_png_rgba(png_bytes)
    xor_bmp = bytearray()
    and_row_bytes = ((w + 31) // 32) * 4
    and_bmp = bytearray()
    for y in range(h - 1, -1, -1):
        row = rows[y]
        mask_row = bytearray(and_row_bytes)
        for x in range(w):
            r, g, b, a = row[x * 4:x * 4 + 4]
            xor_bmp.extend((b, g, r, a))
            if a < 16:
                mask_row[x >> 3] |= (0x80 >> (x & 7))
        and_bmp.extend(mask_row)

    bih = struct.pack(
        "<IIIHHIIIIII",
        40, w, h * 2, 1, 32, 0,
        len(xor_bmp) + len(and_bmp), 0, 0, 0, 0
    )
    return bih + bytes(xor_bmp) + bytes(and_bmp)


def pad4(b):
    rem = len(b) % 4
    return b if rem == 0 else b + b"\x00" * (4 - rem)


def vs_node(key, value_bytes, is_text, children=()):
    key_utf16 = key.encode("utf-16le") + b"\x00\x00"
    head = bytearray(struct.pack("<HHH", 0, len(value_bytes) // 2 if is_text else len(value_bytes), 1 if is_text else 0))
    head.extend(key_utf16)
    body = bytearray(pad4(bytes(head)))
    if value_bytes:
        body.extend(value_bytes)
    if children:
        body = bytearray(pad4(bytes(body)))
        for ch in children:
            body = bytearray(pad4(bytes(body)))
            body.extend(ch)
    struct.pack_into("<H", body, 0, len(body))
    return bytes(body)


def build_version_info_bytes():
    fixed = struct.pack(
        "<IIIIIIIIIIIII",
        0xFEEF04BD, 0x00010000,
        (8 << 16) | 0, (0 << 16) | 0,
        (8 << 16) | 0, (0 << 16) | 0,
        0x3F, 0x0, 0x00040004, 0x00000001, 0x0, 0, 0
    )
    strings = [
        ("CompanyName", "CS-2 JAVA Studio"),
        ("FileDescription", "CS-2 JAVA 10.0 — Alkash Edition Tactical 3D Shooter"),
        ("FileVersion", "10.0.0.0"),
        ("InternalName", "CS-2-JAVA"),
        ("LegalCopyright", "Copyright (C) 2026 CS-2 JAVA Open Engine"),
        ("OriginalFilename", "CS-2-JAVA.exe"),
        ("ProductName", "CS-2 JAVA 10.0 (Windows 10/11 x64)"),
        ("ProductVersion", "10.0.0.0"),
    ]
    str_nodes = [
        vs_node(k, v.encode("utf-16le") + b"\x00\x00", True)
        for k, v in strings
    ]
    str_table = vs_node("041904B0", b"", True, str_nodes)
    sfi = vs_node("StringFileInfo", b"", True, [str_table])
    trans = vs_node("Translation", struct.pack("<HH", 0x0419, 1200), False)
    vfi = vs_node("VarFileInfo", b"", True, [trans])
    return vs_node("VS_VERSION_INFO", fixed, False, [sfi, vfi])


def res_record(type_id, name_id, data_bytes, lang_id=0x0419):
    hdr = struct.pack(
        "<IIHHHHIHHII",
        len(data_bytes), 32,
        0xFFFF, type_id,
        0xFFFF, name_id,
        0, 0x1030, lang_id, 0, 0
    )
    return hdr + pad4(data_bytes)


def build_ico_and_res(icon_dir, jar_path, out_ico_path, out_res_path):
    sizes = [16, 32, 48, 64, 256]
    icon_payloads = []
    for sz in sizes:
        png = open(os.path.join(icon_dir, f"icon_{sz}.png"), "rb").read()
        payload = png if sz == 256 else png_to_ico_dib(png)
        icon_payloads.append((sz, payload))

    # Write standalone .ico file as well
    ico_dir_hdr = struct.pack("<HHH", 0, 1, len(icon_payloads))
    offset = 6 + 16 * len(icon_payloads)
    ico_entries = bytearray()
    ico_blobs = bytearray()
    for sz, blob in icon_payloads:
        b_sz = 0 if sz == 256 else sz
        ico_entries.extend(struct.pack("<BBBBHHII", b_sz, b_sz, 0, 0, 1, 32, len(blob), offset))
        ico_blobs.extend(blob)
        offset += len(blob)
    with open(out_ico_path, "wb") as f:
        f.write(ico_dir_hdr + ico_entries + ico_blobs)

    # Build Win32 .res (null header + RT_ICONs + RT_GROUP_ICON + RT_VERSION + RT_MANIFEST + RT_RCDATA JAR)
    res = bytearray(struct.pack("<IIHHHHIHHII", 0, 32, 0xFFFF, 0, 0xFFFF, 0, 0, 0, 0, 0, 0))

    grp_entries = bytearray(struct.pack("<HHH", 0, 1, len(icon_payloads)))
    for idx, (sz, blob) in enumerate(icon_payloads, start=1):
        res.extend(res_record(3, idx, blob))  # RT_ICON = 3
        b_sz = 0 if sz == 256 else sz
        grp_entries.extend(struct.pack("<BBBBHHIH", b_sz, b_sz, 0, 0, 1, 32, len(blob), idx))

    res.extend(res_record(14, 1, bytes(grp_entries)))  # RT_GROUP_ICON = 14
    res.extend(res_record(16, 1, build_version_info_bytes()))  # RT_VERSION = 16
    res.extend(res_record(10, 101, open(jar_path, "rb").read(), lang_id=0x0409))  # RT_RCDATA = 10, ID = 101

    manifest_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">'
        '<assemblyIdentity version="10.0.0.0" processorArchitecture="amd64" name="CS2Java.HyperTurbo.Game" type="win32"/>'
        '<description>CS-2 JAVA 10.0 Tactical 3D Shooter</description>'
        '<dependency><dependentAssembly>'
        '<assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0" '
        'processorArchitecture="amd64" publicKeyToken="6595b64144ccf1df" language="*"/>'
        '</dependentAssembly></dependency>'
        '<trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges>'
        '<requestedExecutionLevel level="asInvoker" uiAccess="false"/>'
        '</requestedPrivileges></security></trustInfo>'
        '<compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1"><application>'
        '<supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/>'
        '<supportedOS Id="{1f676c76-80e1-4239-95bb-83d0f6d0da78}"/>'
        '</application></compatibility>'
        '<application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings>'
        '<dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/pm</dpiAware>'
        '<dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2</dpiAwareness>'
        '</windowsSettings></application>'
        '</assembly>'
    ).encode("utf-8")
    res.extend(res_record(24, 1, manifest_xml, lang_id=0x0409))  # RT_MANIFEST = 24

    with open(out_res_path, "wb") as f:
        f.write(res)


def write_embedded_jar_header(jar_path, header_path):
    data = open(jar_path, "rb").read()
    with open(header_path, "w", encoding="ascii") as out:
        out.write("/* Auto-generated from CS-2-JAVA.jar */\n")
        out.write("#pragma once\n#include <stddef.h>\n\n")
        out.write(f"#define EMBEDDED_JAR_LEN {len(data)}u\n")
        out.write("static const unsigned char EMBEDDED_JAR_DATA[] = {\n")
        for i in range(0, len(data), 24):
            chunk = data[i:i + 24]
            out.write("  " + ",".join(str(b) for b in chunk) + ",\n")
        out.write("};\n")


def main():
    os.makedirs(BUILD_TMP, exist_ok=True)
    java_bin = subprocess.check_output(
        ["/tmp/jdk_venv/bin/python", "-c", "import jdk4py; print(jdk4py.JAVA)"], text=True
    ).strip()
    ecj_jar = "/tmp/jdt_test/package/jar/ctxo-jdt-analyzer-17.jar"
    zig_bin = subprocess.check_output(
        ["/tmp/zig_venv/bin/python", "-c",
         "import ziglang, os; print(os.path.join(os.path.dirname(ziglang.__file__), 'zig'))"],
        text=True
    ).strip()

    bin_dir = os.path.join(ROOT, "bin")
    shutil.rmtree(bin_dir, ignore_errors=True)
    os.makedirs(bin_dir, exist_ok=True)

    src_files = sorted(
        os.path.join(ROOT, "src", fn) for fn in os.listdir(os.path.join(ROOT, "src")) if fn.endswith(".java")
    )
    subprocess.check_call([
        java_bin, "-cp", ecj_jar, "org.eclipse.jdt.internal.compiler.batch.Main",
        "-11", "-encoding", "UTF-8", "-nowarn", "-d", bin_dir, *src_files
    ])

    jar_path = os.path.join(ROOT, "CS-2-JAVA.jar")
    with zipfile.ZipFile(jar_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jf:
        manifest = "Manifest-Version: 1.0\r\nMain-Class: Main\r\nCreated-By: CS-2 JAVA 10.0 Panorama\r\n\r\n"
        jf.writestr("META-INF/MANIFEST.MF", manifest)
        for fn in sorted(os.listdir(bin_dir)):
            if fn.endswith(".class"):
                jf.write(os.path.join(bin_dir, fn), arcname=fn)

    icon_dir = os.path.join(BUILD_TMP, "icons")
    os.makedirs(icon_dir, exist_ok=True)
    subprocess.check_call([java_bin, "-Djava.awt.headless=true", "-cp", bin_dir, "Main", "--export-icon", icon_dir])

    ico_path = os.path.join(ROOT, "launcher", "cs2.ico")
    res_path = os.path.join(BUILD_TMP, "cs2_resources.res")
    build_ico_and_res(icon_dir, jar_path, ico_path, res_path)

    exe_path = os.path.join(ROOT, "CS-2-JAVA.exe")
    subprocess.check_call([
        zig_bin, "cc", "-target", "x86_64-windows-gnu",
        "-O2", "-s", "-Wall", "-Wextra",
        "-Wl,--subsystem,windows", "-municode",
        os.path.join(ROOT, "launcher", "cs2_launcher.c"),
        res_path,
        "-luser32", "-lshell32", "-lkernel32",
        "-o", exe_path
    ])

    zip_path = os.path.join(ROOT, "CS-2-JAVA.zip")
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for top in ["CS-2-JAVA.exe", "CS-2-JAVA.jar", "run.bat", "run.sh", "README.md"]:
            zf.write(os.path.join(ROOT, top), arcname=f"CS-2-JAVA/{top}")
        for fn in sorted(os.listdir(os.path.join(ROOT, "src"))):
            if fn.endswith(".java"):
                zf.write(os.path.join(ROOT, "src", fn), arcname=f"CS-2-JAVA/src/{fn}")
        zf.write(os.path.join(ROOT, "launcher", "cs2_launcher.c"), arcname="CS-2-JAVA/launcher/cs2_launcher.c")
        zf.write(ico_path, arcname="CS-2-JAVA/launcher/cs2.ico")

    print(f"SUCCESS! CS-2-JAVA.exe = {os.path.getsize(exe_path):,} bytes")
    print(f"SUCCESS! CS-2-JAVA.jar = {os.path.getsize(jar_path):,} bytes")
    print(f"SUCCESS! CS-2-JAVA.zip = {os.path.getsize(zip_path):,} bytes")


if __name__ == "__main__":
    main()
