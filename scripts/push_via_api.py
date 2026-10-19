#!/usr/bin/env python3
"""当 github.com 的 git 端口不可达时，用 GitHub Git Data API 把本地提交推上去。

用法（Windows Git Bash）:
    export GH_TOKEN=github_pat_xxx
    python scripts/push_via_api.py --tag v0.9.0alpha6 --title "0.9.0alpha6 — ..." \
        --notes docs/release-notes.md --apk path/to/app.apk

特点：
- 从 git 对象库读原始字节创建 blob（不受 .gitattributes eol 转换影响）
- 提交信息取 commit 对象里的原始 message（含结尾换行），保证生成的 commit sha 与本地一致
- 推完会校验远端 main 是否等于本地 HEAD
"""
import argparse, base64, json, os, subprocess, sys, time, urllib.error, urllib.request

def sh(repo, *args):
    return subprocess.check_output(args, cwd=repo).decode("utf-8", "replace")

def api(token, method, url, payload=None, raw=None, content_type="application/json", retries=3):
    data = None
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "courtgrab-push",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if raw is not None:
        data = raw
        headers["Content-Type"] = content_type
    elif payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:500]
            if e.code in (500, 502, 503, 504) and attempt + 1 < retries:
                time.sleep(3); continue
            raise SystemExit(f"HTTP {e.code} {method} {url}\n{detail}")
        except Exception as e:
            if attempt + 1 < retries:
                time.sleep(3); continue
            raise SystemExit(f"{method} {url} failed: {e}")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="Wyc330/ZJU-Badminton")
    ap.add_argument("--dir", default=".")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--notes", required=True, help="release notes markdown 文件")
    ap.add_argument("--apk", default="")
    ap.add_argument("--tag-message", default="")
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN", "").strip()
    if not token:
        tokfile = os.path.join(args.dir, "_samples", "tok.txt")
        if os.path.exists(tokfile):
            token = open(tokfile, encoding="utf-8").read().strip()
    if not token:
        raise SystemExit("缺少 GH_TOKEN（或 _samples/tok.txt）")

    repo_dir = os.path.abspath(args.dir)
    api_base = f"https://api.github.com/repos/{args.repo}"

    print("=== 远端 main ===")
    remote = api(token, "GET", f"{api_base}/git/ref/heads/main")["object"]["sha"]
    local = sh(repo_dir, "git", "rev-parse", "HEAD").strip()
    parent = sh(repo_dir, "git", "rev-parse", "HEAD^").strip()
    print(f"remote={remote[:7]} local={local[:7]} parent={parent[:7]}")
    if remote == local:
        print("远端已是最新，跳过提交创建")
    else:
        if remote != parent:
            raise SystemExit(f"远端 main 不是本地 HEAD 的父提交，拒绝推送（remote={remote[:7]} parent={parent[:7]}）")

        raw_commit = subprocess.check_output(["git", "cat-file", "commit", local], cwd=repo_dir)
        body = raw_commit.split(b"\n\n", 1)[1]
        message = body.decode("utf-8")
        meta = sh(repo_dir, "git", "log", "-1", "--format=%an|%ae|%aI|%cn|%ce|%cI").strip().split("|")
        an, ae, ad, cn, ce, cd = meta
        tree_sha = sh(repo_dir, "git", "rev-parse", "HEAD^{tree}").strip()

        print("=== 创建 blob ===")
        entries, n = [], 0
        for line in sh(repo_dir, "git", "ls-tree", "-r", "HEAD").splitlines():
            if not line.strip():
                continue
            head, path = line.split("\t", 1)
            mode, _typ, blob_sha = head.split()
            data = subprocess.check_output(["git", "cat-file", "blob", blob_sha], cwd=repo_dir)
            created = api(token, "POST", f"{api_base}/git/blobs", {
                "content": base64.b64encode(data).decode(), "encoding": "base64"})
            if created["sha"] != blob_sha:
                raise SystemExit(f"blob 不一致：{path}")
            entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob_sha})
            n += 1
        print(f"blob {n} 个")

        print("=== 创建 tree ===")
        tree = api(token, "POST", f"{api_base}/git/trees", {"tree": entries})
        if tree["sha"] != tree_sha:
            raise SystemExit("tree sha 不一致")

        print("=== 创建 commit ===")
        commit = api(token, "POST", f"{api_base}/git/commits", {
            "message": message, "tree": tree["sha"], "parents": [remote],
            "author": {"name": an, "email": ae, "date": ad},
            "committer": {"name": cn, "email": ce, "date": cd},
        })
        if commit["sha"] != local:
            raise SystemExit(f"commit sha 不一致：api={commit['sha']} local={local}")
        api(token, "PATCH", f"{api_base}/git/refs/heads/main", {"sha": local, "force": True})
        print("main 已更新 →", local[:7])

    print("=== tag ===")
    try:
        api(token, "GET", f"{api_base}/git/ref/tags/{args.tag}")
        print("tag 已存在")
    except SystemExit:
        meta = sh(repo_dir, "git", "log", "-1", "--format=%cn|%ce|%cI").strip().split("|")
        cn, ce, cd = meta
        tag_obj = api(token, "POST", f"{api_base}/git/tags", {
            "tag": args.tag,
            "message": args.tag_message or args.title,
            "object": local, "type": "commit",
            "tagger": {"name": cn, "email": ce, "date": cd},
        })
        api(token, "POST", f"{api_base}/git/refs", {"ref": f"refs/tags/{args.tag}", "sha": tag_obj["sha"]})
        print("tag 创建完成")

    print("=== release ===")
    notes = open(args.notes, encoding="utf-8").read()
    release = api(token, "POST", f"{api_base}/releases", {
        "tag_name": args.tag, "name": args.title, "body": notes,
        "prerelease": True, "draft": False,
    })
    print("release:", release["html_url"])
    if args.apk and os.path.exists(args.apk):
        upload = release["upload_url"].split("{")[0]
        fname = os.path.basename(args.apk)
        with open(args.apk, "rb") as f:
            asset = api(token, "POST", f"{upload}?name={fname}",
                        raw=f.read(), content_type="application/octet-stream")
        print("asset:", asset["name"], asset["size"])

    print("=== 校验 ===")
    final = api(token, "GET", f"{api_base}/git/ref/heads/main")["object"]["sha"]
    print("remote main =", final, "| match =", final == local)

if __name__ == "__main__":
    main()
