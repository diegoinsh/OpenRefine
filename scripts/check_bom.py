#!/usr/bin/env python3
"""双 BOM 检查 / 自动收敛为单 BOM。

- 单 BOM：中文 .ps1/.bat/.cmd 的正常形态（Windows PowerShell 5.1 读无 BOM 的 UTF-8 会按
  GBK 解析，中文被截断）。
- 双 BOM：文件头连续两个 EF BB BF。PowerShell 会把首行当表达式，报
  “意外属性 CmdletBinding”这类解析错误，脚本直接跑不起来。
"""
import argparse
import subprocess
import sys
from pathlib import Path

BOM = b"\xef\xbb\xbf"
EXTS = {".ps1", ".bat", ".cmd", ".py", ".sh"}
NEED_BOM = {".ps1", ".bat", ".cmd"}


def double_bom(data: bytes) -> bool:
    return data.startswith(BOM + BOM)


def to_single(data: bytes) -> bytes:
    while double_bom(data):
        data = BOM + data[len(BOM) * 2:]
    return data


def check_path(path: Path, fix: bool) -> bool:
    try:
        data = path.read_bytes()
    except OSError as exc:
        print(f"[SKIP] {path}: {exc}")
        return True
    if double_bom(data):
        if fix:
            path.write_bytes(to_single(data))
            print(f"[FIXED] {path}（已收敛为单 BOM）")
            return True
        print(f"[DOUBLE-BOM] {path}")
        return False
    if path.suffix.lower() in NEED_BOM and data and not data.startswith(BOM):
        print(f"[WARN] {path} 无 BOM（Windows PowerShell 5.1 可能按 GBK 解析）")
    return True


SKIP_DIRS = {".git", "node_modules", "__pycache__", "target", "build", "dist",
             "images", "generated-archs", "data-systest"}


def iter_repo(root: Path):
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in EXTS:
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        yield path


def staged_files():
    out = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACM"],
        capture_output=True, text=True)
    return [line for line in out.stdout.splitlines() if line.strip()]


def check_staged(fix: bool) -> bool:
    ok = True
    for rel in staged_files():
        if Path(rel).suffix.lower() not in EXTS:
            continue
        blob = subprocess.run(["git", "show", ":" + rel], capture_output=True).stdout
        if not double_bom(blob):
            continue
        if not fix:
            print(f"[DOUBLE-BOM] {rel}")
            ok = False
            continue
        path = Path(rel)
        work = path.read_bytes() if path.exists() else None
        if work is not None and work == blob:
            path.write_bytes(to_single(work))
            subprocess.run(["git", "add", rel], check=False)
            print(f"[FIXED] {rel}（已收敛为单 BOM 并重新暂存）")
        else:
            print(f"[WARN] {rel} 暂存内容含双 BOM，但工作区与暂存不一致，未自动改写")
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description="双 BOM 检查工具")
    parser.add_argument("--staged", action="store_true", help="检查 git 暂存区（pre-commit 用）")
    parser.add_argument("--file", action="append", default=[], help="检查指定文件，可重复")
    parser.add_argument("--repo", help="扫描整个仓库目录")
    parser.add_argument("--fix", action="store_true", help="发现双 BOM 时收敛为单 BOM")
    args = parser.parse_args()

    if args.staged:
        return 0 if check_staged(args.fix) else 1

    ok = True
    for item in args.file:
        ok = check_path(Path(item), args.fix) and ok
    if args.repo:
        for path in iter_repo(Path(args.repo)):
            ok = check_path(path, args.fix) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
