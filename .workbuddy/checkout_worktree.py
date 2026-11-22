# -*- coding: utf-8 -*-
"""从 .git 对象库（松散对象 + packfile）检出工作树。不修改 .git，只写文件。"""
import os, sys, zlib, struct, io, time

GIT = r"E:\ai\amusic\.git"
WT  = r"E:\ai\amusic"

# ---------- 松散对象 ----------
def loose_path(sha):
    return os.path.join(GIT, "objects", sha[:2], sha[2:])

def read_loose(sha):
    p = loose_path(sha)
    if not os.path.exists(p):
        return None
    with open(p, "rb") as f:
        data = zlib.decompress(f.read())
    hdr, _, body = data.partition(b"\x00")
    typ = hdr.split(b" ")[0].decode()
    return typ, body

# ---------- packfile ----------
class Pack:
    def __init__(self, pack_path, idx_path):
        self.pack_path = pack_path
        self.idx = {}
        with open(idx_path, "rb") as f:
            data = f.read()
        assert data[:4] == b"\xfftOc", "idx v2 required"
        fan = struct.unpack(">256I", data[8:8+1024])
        n = fan[255]
        off = 8 + 1024
        shas = data[off:off + 20*n]; off += 20*n
        off += 4*n  # crc table
        big_start = off + 4*n
        offsets = struct.unpack(">%dI" % n, data[off:off + 4*n])
        for i in range(n):
            o = offsets[i]
            if o & 0x80000000:
                oi = o & 0x7fffffff
                o = struct.unpack(">Q", data[big_start + 8*oi: big_start + 8*oi + 8])[0]
            self.idx[shas[20*i:20*i+20].hex()] = o
        self._f = open(pack_path, "rb")
        self._cache = {}
        # object count from pack header
        self._f.seek(8)
        self.count = struct.unpack(">I", self._f.read(4))[0]

    def _inflate_at(self, off):
        self._f.seek(off)
        byte = self._f.read(1)[0]
        tnum = (byte >> 4) & 7
        shift = 4
        while byte & 0x80:
            byte = self._f.read(1)[0]
            shift += 7
        start = self._f.tell()
        dec = zlib.decompressobj()
        out = b""
        while True:
            chunk = self._f.read(65536)
            if not chunk: break
            out += dec.decompress(chunk)
            if dec.eof: break
        return tnum, start, out

    def _read_delta(self, base, delta):
        # parse delta header sizes
        i = 0
        src_size = 0; shift = 0
        while True:
            b = delta[i]; i += 1
            src_size |= (b & 0x7f) << shift; shift += 7
            if not b & 0x80: break
        tgt_size = 0; shift = 0
        while True:
            b = delta[i]; i += 1
            tgt_size |= (b & 0x7f) << shift; shift += 7
            if not b & 0x80: break
        out = bytearray()
        while i < len(delta):
            op = delta[i]; i += 1
            if op & 0x80:
                cp_off = 0; cp_size = 0
                for bit, sh in ((0x01,0),(0x02,8),(0x04,16),(0x08,24)):
                    if op & bit:
                        cp_off |= delta[i] << sh; i += 1
                for bit, sh in ((0x10,0),(0x20,8),(0x40,16)):
                    if op & bit:
                        cp_size |= delta[i] << sh; i += 1
                if cp_size == 0: cp_size = 0x10000
                out += base[cp_off:cp_off+cp_size]
            elif op:
                out += delta[i:i+op]; i += op
            else:
                raise ValueError("bad delta op")
        assert len(out) == tgt_size
        return bytes(out)

    def read(self, sha):
        if sha in self._cache:
            return self._cache[sha]
        off = self.idx.get(sha)
        if off is None:
            return None
        result = self._read_at(off)
        self._cache[sha] = result
        return result

    def _read_at(self, off):
        tnum, dstart, data = self._inflate_at(off)
        if tnum in (1,2,3,4):
            return {1:"commit",2:"tree",3:"blob",4:"tag"}[tnum], data
        if tnum == 7:
            self._f.seek(dstart)
            base_sha = self._f.read(20).hex()
            base_typ, base = self.read(base_sha)
            self._f.seek(dstart + 20)
            dec = zlib.decompressobj(); delta = b""
            while True:
                chunk = self._f.read(65536)
                if not chunk: break
                delta += dec.decompress(chunk)
                if dec.eof: break
            return base_typ, self._read_delta(base, delta)
        if tnum == 6:
            self._f.seek(dstart)
            shift = 0; base_off = 0
            while True:
                b = self._f.read(1)[0]; base_off |= (b & 0x7f) << shift; shift += 7
                if not b & 0x80: break
            hdr_end = self._f.tell()
            base_typ, base = self._read_at(dstart - base_off)
            self._f.seek(hdr_end)
            dec = zlib.decompressobj(); delta = b""
            while True:
                chunk = self._f.read(65536)
                if not chunk: break
                delta += dec.decompress(chunk)
                if dec.eof: break
            return base_typ, self._read_delta(base, delta)
        raise ValueError("bad type")

PACKS = []
def load_packs():
    pdir = os.path.join(GIT, "objects", "pack")
    if not os.path.isdir(pdir): return
    for name in os.listdir(pdir):
        if name.endswith(".idx"):
            pack = os.path.join(pdir, name[:-4] + ".pack")
            if os.path.exists(pack):
                PACKS.append(Pack(pack, os.path.join(pdir, name)))

def read_object(sha):
    r = read_loose(sha)
    if r: return r
    for p in PACKS:
        r = p.read(sha)
        if r: return r
    raise KeyError("object not found: %s" % sha)

# ---------- tree walk ----------
def parse_tree(body):
    i = 0
    while i < len(body):
        j = body.index(b"\x00", i)
        mode_name = body[i:j]
        mode, _, name = mode_name.partition(b" ")
        sha = body[j+1:j+21].hex()
        yield mode.decode(), name.decode("utf-8", "replace"), sha
        i = j + 21

def checkout_tree(tree_sha, dest):
    typ, body = read_object(tree_sha)
    assert typ == "tree"
    files = dirs = 0
    for mode, name, sha in parse_tree(body):
        if name in (".git",):
            continue
        target = os.path.join(dest, name)
        if mode == "40000" or mode == "040000":
            os.makedirs(target, exist_ok=True)
            f2, d2 = checkout_tree(sha, target)
            files += f2; dirs += d2 + 1
        elif mode in ("100644", "100755"):
            t, b = read_object(sha)
            assert t == "blob"
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "wb") as fo:
                fo.write(b)
            if mode == "100755":
                os.chmod(target, 0o755) if hasattr(os, "chmod") else None
            files += 1
        elif mode == "120000":
            t, b = read_object(sha)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            try:
                if os.path.lexists(target): os.remove(target)
                os.symlink(b.decode("utf-8", "replace"), target)
            except OSError:
                with open(target, "wb") as fo: fo.write(b)
            files += 1
        elif mode == "160000":
            os.makedirs(target, exist_ok=True)  # gitlink: leave empty
            dirs += 1
        else:
            print("skip mode", mode, name)
    return files, dirs

def commit_info(sha):
    typ, body = read_object(sha)
    head = body.partition(b"\n\n")[0].decode("utf-8", "replace")
    return head

if __name__ == "__main__":
    t0 = time.time()
    load_packs()
    print("packs:", len(PACKS))
    main_sha = open(os.path.join(GIT, "refs", "heads", "main")).read().strip()
    info = commit_info(main_sha)
    print("main commit:", main_sha)
    print(info)
    # find tree
    typ, body = read_object(main_sha)
    tree = body.partition(b"\n")[0].split(b" ")[1].decode()
    files, dirs = checkout_tree(tree, WT)
    print("checkout done: %d files, %d dirs in %.1fs" % (files, dirs, time.time() - t0))
