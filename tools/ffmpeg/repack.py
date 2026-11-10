#!/usr/bin/env python3
"""Compute the NEEDED-library closure of Termux's ffmpeg for each Android ABI
and repack it as libffmpeg.zip.so (layout used by MMTube's vendored FFmpeg).

usage: repack.py <rootfs-parent-dir> <jni-output-dir>
  rootfs-parent-dir/<arch>/data/data/com.termux/files/usr/...
Writes <jni-output-dir>/<abi>/libffmpeg.zip.so + libffmpeg.so + libffprobe.so
"""
import collections
import os
import shutil
import stat
import struct
import sys
import zipfile

ABIS = {"aarch64": "arm64-v8a", "arm": "armeabi-v7a", "i686": "x86", "x86_64": "x86_64"}
# Termux ships shims for system libraries that route through its own linker
# namespace helper (libtermux-platform-ns.so). That helper needs system
# libhwui.so, which fails to link inside a normal app process, so both are
# dropped and libavcodec resolves the real system libmediandk.so instead.
EXCLUDE = {
    "libc.so", "libm.so", "libdl.so", "libandroid.so", "liblog.so",
    "libmediandk.so", "libtermux-platform-ns.so",
}


def elf_needed(path):
    data = open(path, "rb").read()
    if data[:4] != b"\x7fELF":
        raise RuntimeError(f"not ELF: {path}")
    is64 = data[4] == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        e_phnum = struct.unpack_from("<H", data, 0x38)[0]
        fmt, fmt2, dyn_size = "<IIQQQQQQ", "<qQ", 16
    else:
        e_phoff = struct.unpack_from("<I", data, 0x1c)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x2a)[0]
        e_phnum = struct.unpack_from("<H", data, 0x2c)[0]
        fmt, fmt2, dyn_size = "<IIIIIIII", "<iI", 8
    needed = []
    for i in range(e_phnum):
        ph = struct.unpack_from(fmt, data, e_phoff + i * e_phentsize)
        if ph[0] != 2:
            continue
        dyn_off = ph[2] if is64 else ph[1]
        entries = []
        j = dyn_off
        while True:
            tag, val = struct.unpack_from(fmt2, data, j)
            if tag == 0:
                break
            entries.append((tag, val))
            j += dyn_size
        strtab = next((v for t, v in entries if t == 5), 0)
        str_off = None
        for k in range(e_phnum):
            ph2 = struct.unpack_from(fmt, data, e_phoff + k * e_phentsize)
            if ph2[0] != 1:
                continue
            if is64:
                vaddr, offset, filesz = ph2[2], ph2[3], ph2[5]
            else:
                vaddr, offset, filesz = ph2[2], ph2[1], ph2[4]
            if vaddr <= strtab < vaddr + filesz:
                str_off = offset + (strtab - vaddr)
                break
        if str_off is None:
            raise RuntimeError(f"strtab unmapped: {path}")
        for tag, val in entries:
            if tag == 1:
                end = data.index(b"\0", str_off + val)
                needed.append(data[str_off + val:end].decode())
    return needed


def elf_soname(path):
    """Return the DT_SONAME of an ELF file, or None."""
    data = open(path, "rb").read()
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        e_phnum = struct.unpack_from("<H", data, 0x38)[0]
        fmt, fmt2, dyn_size = "<IIQQQQQQ", "<qQ", 16
    else:
        e_phoff = struct.unpack_from("<I", data, 0x1c)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x2a)[0]
        e_phnum = struct.unpack_from("<H", data, 0x2c)[0]
        fmt, fmt2, dyn_size = "<IIIIIIII", "<iI", 8
    for i in range(e_phnum):
        ph = struct.unpack_from(fmt, data, e_phoff + i * e_phentsize)
        if ph[0] != 2:
            continue
        dyn_off = ph[2] if is64 else ph[1]
        entries = []
        j = dyn_off
        while True:
            tag, val = struct.unpack_from(fmt2, data, j)
            if tag == 0:
                break
            entries.append((tag, val))
            j += dyn_size
        strtab = next((v for t, v in entries if t == 5), 0)
        str_off = None
        for k in range(e_phnum):
            ph2 = struct.unpack_from(fmt, data, e_phoff + k * e_phentsize)
            if ph2[0] != 1:
                continue
            if is64:
                vaddr, offset, filesz = ph2[2], ph2[3], ph2[5]
            else:
                vaddr, offset, filesz = ph2[2], ph2[1], ph2[4]
            if vaddr <= strtab < vaddr + filesz:
                str_off = offset + (strtab - vaddr)
                break
        if str_off is None:
            return None
        for tag, val in entries:
            if tag == 14:
                end = data.index(b"\0", str_off + val)
                return data[str_off + val:end].decode()
    return None


def build(root, arch):
    usr = os.path.join(root, arch, "data/data/com.termux/files/usr")
    libdir, bindir = os.path.join(usr, "lib"), os.path.join(usr, "bin")
    seed = [os.path.join(bindir, b) for b in ("ffmpeg", "ffprobe")]
    needed = collections.deque()
    for s in seed:
        needed.extend(elf_needed(s))
    included = {}

    def add(name):
        if name in EXCLUDE:
            return False
        cand = os.path.join(libdir, name)
        if not (os.path.isfile(cand) or os.path.islink(cand)):
            print(f"    MISSING: {name}")
            return False
        real = os.path.realpath(cand)
        if real not in included:
            included[real] = {"file": real, "aliases": {name}}
            needed.extend(elf_needed(real))
            return True
        return False

    while needed:
        add(needed.popleft())
    changed = True
    while changed:
        changed = False
        for info in list(included.values()):
            for dep in elf_needed(info["file"]):
                if add(dep):
                    changed = True
    return usr, included


def write_zip(usr, included, out_path):
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    libdir = os.path.join(usr, "lib")
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for d in ["usr/", "usr/lib/"]:
            zi = zipfile.ZipInfo(d)
            zi.external_attr = 0o40755 << 16
            zf.writestr(zi, "")
        subdirs = set()
        for info in included.values():
            rel = os.path.relpath(info["file"], usr)
            parts = rel.split(os.sep)[:-1]
            for i in range(len(parts)):
                subdirs.add("usr/" + "/".join(parts[: i + 1]) + "/")
        for d in sorted(subdirs):
            if d not in ("usr/", "usr/lib/"):
                zi = zipfile.ZipInfo(d)
                zi.external_attr = 0o40755 << 16
                zf.writestr(zi, "")
        for real, info in sorted(included.items()):
            base = os.path.basename(real)
            aliases = set(info["aliases"])
            aliases.add(base)
            soname = elf_soname(real)
            if soname:
                aliases.add(soname)
            with open(real, "rb") as fh:
                data = fh.read()
            for name in sorted(aliases):
                if name == base:
                    zi = zipfile.ZipInfo("usr/lib/" + base)
                    zi.external_attr = 0o100644 << 16
                    zf.writestr(zi, data)
                else:
                    zi = zipfile.ZipInfo("usr/lib/" + name)
                    zi.external_attr = (0o120777 << 16) | 0o777
                    zi.create_system = 3
                    zf.writestr(zi, base)


def main():
    root, out = sys.argv[1], sys.argv[2]
    for arch, abi in ABIS.items():
        usr, included = build(root, arch)
        out_dir = os.path.join(out, abi)
        os.makedirs(out_dir, exist_ok=True)
        write_zip(usr, included, os.path.join(out_dir, "libffmpeg.zip.so"))
        for name in ("ffmpeg", "ffprobe"):
            shutil.copy2(
                os.path.join(usr, "bin", name),
                os.path.join(out_dir, f"lib{name}.so"),
            )
        print(f"{abi}: {len(included)} libs, "
              f"{os.path.getsize(os.path.join(out_dir, 'libffmpeg.zip.so')) / 1024 / 1024:.1f} MB zip")


if __name__ == "__main__":
    main()
