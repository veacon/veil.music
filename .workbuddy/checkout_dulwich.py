# -*- coding: utf-8 -*-
"""用 dulwich 从 E:\ai\amusic\.git 检出 main 工作树（只写文件，不动 .git）。"""
import os
from dulwich.repo import Repo

REPO = r"E:\ai\amusic"
r = Repo(REPO)
head_sha = r.head().decode()
commit = r[head_sha.encode() if isinstance(head_sha, str) else head_sha]
print("HEAD:", head_sha)
msg = commit.message.decode("utf-8", "replace").splitlines()[0] if commit.message else ""
import datetime
print("date:", datetime.datetime.fromtimestamp(commit.author_time).isoformat())
print("msg :", msg[:120])

files = dirs = 0
def walk(tree_sha, dest):
    global files, dirs
    tree = r[tree_sha]
    for entry in tree.items():
        name = entry.path.decode("utf-8", "replace")
        if name == ".git":
            continue
        target = os.path.join(dest, name)
        if entry.mode in (0o40000, 0o040000):
            os.makedirs(target, exist_ok=True)
            walk(entry.sha, target)
            dirs += 1
        elif entry.mode in (0o100644, 0o100755, 0o120000):
            obj = r[entry.sha]
            data = obj.data
            os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
            if os.path.lexists(target) and os.path.islink(target):
                os.remove(target)
            with open(target, "wb") as fo:
                fo.write(data)
            files += 1
        elif entry.mode == 0o160000:
            os.makedirs(target, exist_ok=True)
            dirs += 1
            print("gitlink (skip):", name)
        else:
            print("unknown mode %o: %s" % (entry.mode, name))

walk(commit.tree, REPO)
print("done: %d files, %d dirs" % (files, dirs))
