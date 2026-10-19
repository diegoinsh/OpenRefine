"""逐件逐字段比对两次抽取结果（OpenRefine 3.11 项目存储）。

用法：
    python test/compare_extraction_projects.py <baselineId> <currentId> [--csv out.csv] [--limit N]

读取 %APPDATA%\\JinRefine\\<id>.project 下的 data.zip：data.txt（3.11 多 sheet 行存储）。
对齐口径：
  - 「卷内」sheet：按「案卷号 + 件号」对齐，逐件比对题名/责任者/文号/成文日期/提取状态等；
  - 「卷」sheet：按「案卷号」对齐，比对卷级要素（密级/开放状态/案由/当事人/审级/结案方式/保管期限）。
"""
import argparse
import csv
import json
import os
import sys
import zipfile
from pathlib import Path

ROOT = Path(os.environ["APPDATA"]) / "JinRefine"


def cell_text(cell):
    if not isinstance(cell, dict):
        return str(cell).strip() if cell is not None else ""
    v = cell.get("v")
    if v is None:
        return ""
    if isinstance(v, list):
        return " | ".join(str(x) for x in v)
    return str(v).strip()


def parse_data_txt(text):
    """解析 3.11 的 data.txt：返回 {sheetId: {"cols": [...], "rows": [[...]]}}。"""
    sheets = {}
    cur = None
    mode = None
    cols = []
    for raw in text.splitlines():
        line = raw.rstrip("\n")
        if line.startswith("sheet:"):
            sid = line[len("sheet:"):].rstrip("=")
            cur = {"cols": [], "rows": []}
            sheets[sid] = cur
            cols = []
            mode = "header"
        elif line.startswith("columnModel="):
            mode = "columns"
        elif line.startswith("columnGroupCount="):
            mode = "before_rows"
        elif line == "/e/":
            mode = "rows"
        elif mode == "columns" and line.startswith("{"):
            try:
                cols.append(json.loads(line)["name"])
            except Exception:
                pass
            cur["cols"] = cols
        elif mode == "rows" and line.startswith("{"):
            try:
                row = json.loads(line)
                cur["rows"].append([cell_text(c) for c in row.get("cells", [])])
            except Exception:
                continue
    return sheets


def load_project(pid):
    d = ROOT / ("%s.project" % pid)
    meta = json.loads((d / "metadata.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(d / "data.zip") as z:
        text = z.read("data.txt").decode("utf-8")
    return {"id": pid, "name": meta.get("name", ""), "sheets": parse_data_txt(text)}


def pick_sheet(sheets, want_inner):
    for sid, sh in sheets.items():
        if want_inner and "卷内" in sid:
            return sid, sh
        if not want_inner and "卷内" not in sid:
            return sid, sh
    return None, None


def compare_sheet(tag, base_sheet, cur_sheet, key_cols, ignore_cols, diffs, limit_note):
    cols_b = base_sheet["cols"]
    cols_c = cur_sheet["cols"]
    cols = cols_b if len(cols_b) >= len(cols_c) else cols_c
    idx_b = {c: i for i, c in enumerate(cols_b)}
    idx_c = {c: i for i, c in enumerate(cols_c)}
    keys = [c for c in key_cols if c in idx_b and c in idx_c]

    def key_of(row, idx):
        return "|".join(row[idx[c]] if idx[c] < len(row) else "" for c in keys)

    map_b = {}
    for r in base_sheet["rows"]:
        map_b.setdefault(key_of(r, idx_b), []).append(r)
    map_c = {}
    for r in cur_sheet["rows"]:
        map_c.setdefault(key_of(r, idx_c), []).append(r)

    stats = {c: {"same": 0, "diff": 0, "base_only": 0, "cur_only": 0} for c in cols}
    base_only_keys = sorted(set(map_b) - set(map_c))
    cur_only_keys = sorted(set(map_c) - set(map_b))
    matched = 0

    for k in sorted(set(map_b) & set(map_c)):
        rb_list, rc_list = map_b[k], map_c[k]
        for i in range(min(len(rb_list), len(rc_list))):
            rb, rc = rb_list[i], rc_list[i]
            matched += 1
            for c in cols:
                if c in ignore_cols:
                    continue
                b = rb[idx_b[c]] if c in idx_b and idx_b[c] < len(rb) else ""
                v = rc[idx_c[c]] if c in idx_c and idx_c[c] < len(rc) else ""
                st = stats[c]
                if b == v:
                    st["same"] += 1
                elif b and v:
                    st["diff"] += 1
                    diffs.append((tag, k, c, b, v))
                elif b:
                    st["base_only"] += 1
                    diffs.append((tag, k, c, b, "(空)"))
                else:
                    st["cur_only"] += 1
                    diffs.append((tag, k, c, "(空)", v))

    print("\n=== %s ===  列=%s" % (tag, cols))
    print("  基线件数=%d  本次件数=%d  对齐成功=%d" % (len(base_sheet["rows"]), len(cur_sheet["rows"]), matched))
    print("  仅基线有键=%s" % (base_only_keys[:10] or "无"))
    print("  仅本次有键=%s" % (cur_only_keys[:10] or "无"))
    print("  %-12s %6s %6s %8s %8s %8s" % ("column", "same", "diff", "baseOnly", "curOnly", "一致率"))
    for c in cols:
        if c in ignore_cols:
            continue
        st = stats[c]
        total = sum(st.values())
        rate = (st["same"] / total * 100) if total else 0
        print("  %-12s %6d %6d %8d %8d %7.1f%%" % (c, st["same"], st["diff"], st["base_only"], st["cur_only"], rate))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("baseline")
    ap.add_argument("current")
    ap.add_argument("--csv", default="")
    ap.add_argument("--limit", type=int, default=40)
    ap.add_argument("--key", default="案卷号,件号",
                    help="件级对齐键（逗号分隔；分件边界有变化时可用 案卷号,题名）")
    args = ap.parse_args()

    base = load_project(args.baseline)
    cur = load_project(args.current)
    print("baseline: %s  id=%s  sheets=%s" % (base["name"], base["id"], list(base["sheets"])))
    print("current : %s  id=%s  sheets=%s" % (cur["name"], cur["id"], list(cur["sheets"])))

    diffs = []
    sid_b, sh_b = pick_sheet(base["sheets"], True)
    sid_c, sh_c = pick_sheet(cur["sheets"], True)
    if sh_b and sh_c:
        compare_sheet("卷内（件级）", sh_b, sh_c, [k.strip() for k in args.key.split(",")],
                      {"文件夹路径", "备注"}, diffs, args.limit)

    sid_b2, sh_b2 = pick_sheet(base["sheets"], False)
    sid_c2, sh_c2 = pick_sheet(cur["sheets"], False)
    if sh_b2 and sh_c2:
        compare_sheet("卷（卷级要素）", sh_b2, sh_c2, ["案卷号"], set(), diffs, args.limit)

    print("\n差异明细（前 %d 条，共 %d）：" % (args.limit, len(diffs)))
    for tag, key, col, b, c in diffs[:args.limit]:
        print("  [%s] %-22s %-10s base=%r  cur=%r" % (tag, key, col, b[:80], c[:80]))

    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8-sig") as fh:
            w = csv.writer(fh)
            w.writerow(["sheet", "件键", "字段", "改造前", "改造后"])
            for item in diffs:
                w.writerow(item)
        print("\nCSV: %s" % args.csv)
    return 0


if __name__ == "__main__":
    sys.exit(main())
