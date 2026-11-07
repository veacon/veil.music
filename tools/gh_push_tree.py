"""通过 GitHub Git Data API 把当前 HEAD 的整棵树推到 veil.music（git 协议被代理挡住时的兜底）。

用法：GH_TOKEN=xxx python tools/gh_push_tree.py
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

TOKEN = os.environ["GH_TOKEN"]
REPO = "veacon/veil.music"
BRANCH = "main"
PARENT = os.environ.get("PARENT_SHA", "")
MESSAGE = os.environ.get(
    "COMMIT_MESSAGE",
    "feat(2.0): Veil Music 2.0 —— 品牌重构、全屏留白修复、音源弹窗重做\n\n"
    "旧代码（com.veil.music / v1.1.2）归档在 legacy-1.1.2 分支与同名 tag。",
)
API = "https://api.github.com"


def call(method, path, payload=None, tries=4):
    data = json.dumps(payload).encode() if payload is not None else None
    last = None
    for attempt in range(tries):
        req = urllib.request.Request(API + path, data=data, method=method)
        req.add_header("Authorization", "token " + TOKEN)
        req.add_header("Accept", "application/vnd.github+json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:300]
            last = "HTTP %s %s" % (e.code, body)
            if e.code in (401, 403, 404, 422):
                raise SystemExit("API 拒绝：" + last)
        except Exception as e:  # 网络抖动
            last = repr(e)
        time.sleep(2 + attempt * 2)
    raise SystemExit("请求失败：" + str(last))


def ls_tree():
    out = subprocess.check_output(["git", "ls-tree", "-r", "-z", "HEAD"])
    entries = []
    for chunk in out.split(b"\0"):
        if not chunk:
            continue
        meta, path = chunk.split(b"\t", 1)
        mode, typ, sha = meta.decode().split()
        entries.append((mode, typ, sha, path.decode("utf-8")))
    return entries


def blob_bytes(sha):
    return subprocess.check_output(["git", "cat-file", "blob", sha])


def main():
    entries = ls_tree()
    unique = {}
    for mode, typ, sha, path in entries:
        unique.setdefault(sha, path)
    print("文件 %d 个（唯一 blob %d 个）" % (len(entries), len(unique)))

    def upload(item):
        sha, path = item
        body = base64.b64encode(blob_bytes(sha)).decode()
        res = call("POST", "/repos/%s/git/blobs" % REPO, {"content": body, "encoding": "base64"})
        return sha, res["sha"]

    done = 0
    with ThreadPoolExecutor(max_workers=6) as pool:
        for sha, new_sha in pool.map(upload, list(unique.items())):
            if new_sha != sha:
                raise SystemExit("blob sha 不一致：" + sha)
            done += 1
            if done % 50 == 0 or done == len(unique):
                print("  已上传 %d/%d" % (done, len(unique)))

    tree = [{"path": p, "mode": m, "type": "blob", "sha": s} for m, _, s, p in entries]
    tree_res = call("POST", "/repos/%s/git/trees" % REPO, {"tree": tree})
    print("tree:", tree_res["sha"])

    commit_body = {"message": MESSAGE, "tree": tree_res["sha"]}
    if PARENT:
        commit_body["parents"] = [PARENT]
    commit = call("POST", "/repos/%s/git/commits" % REPO, commit_body)
    print("commit:", commit["sha"])

    call("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH),
         {"sha": commit["sha"], "force": True})
    print("main ->", commit["sha"])


if __name__ == "__main__":
    main()
