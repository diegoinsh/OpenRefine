#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""训练数据导入（合成卷宗批次）· OR 侧驱动脚本。

闭环口径见 ruyi-capability-platform `docs/extraction-feedback-loop-design.md` §3.2：
标签只取「当前单元格值 vs pageMap 胜出候选」的快照比对，所以合成数据要先「模拟人工定稿」——
把 benchmark 真值写回 OR 项目单元格，再走 `ui_export` 提交（verify_state=ok），
并在批次上打 `origin` 合成标记（RCP 冻结时按它分留出集）。

流程（全部经 OR 的 HTTP 命令，不直连 RCP 反馈接口）：
  1. 读 benchmark `metrics_detail.json`：逐件取 gt_raw（题名/责任者/文号/成文日期）；
  2. 读 OR 项目 data.txt 建立 (案卷号, 件号) → 行号 映射（离线读，OR 已落盘）；
  3. `apply-extraction-labels` 批量写回真值（label 为空则清格）；
  4. `submit-extraction-feedback`（source=ui_export, origin=<生成器名>）推送 RCP。

用法（PowerShell，py=C:\\ProgramData\\Anaconda3\\envs\\py39ruyi\\python.exe）：
  & $py test\\import_extraction_feedback.py `
      --project 2197882426715 `
      --metrics g:\\workshop\\mock-data-gen\\benchmark\\runs\\20261025-or-qc10\\metrics_detail.json `
      --origin mock-data-gen --base http://127.0.0.1:8090
  仅预览将要写回的格子：加 --dry-run。

登录方式：
  - 缺省走平台狗登录（/api/auth/dog，只读账号，**无管理员权限**）；
  - 需要管理员动作（如排除批次、改训练策略）时显式传账密走 /api/auth/login，
    开发环境默认管理员为 admin/admin：
      --user admin --password admin

注意：
  - `--project` 支持项目 id 或 `.project` 绝对路径（沙箱 data-systest 项目传绝对路径）；
    HTTP 侧仍需项目在 OR 里注册（id 一致时才可写）。
  - 真值按「对齐后的件」取：metrics_detail.pieces_detail[i].pred_piece_no 是 OR 件号，
    其 fields.<field>.gt_raw 为该件真值；status=exact 的格子不写（值已一致，写了也无标签）。
  - 日期真值可能带分隔符（2024-06-28），与 OR 的归一值不同 → 会同时产生 normalized/wrong，
    由 RCP 按 §3.2 结构相似规则自动分流，本脚本不做二次判定。
"""

import argparse
import io
import json
import os
import sys
import zipfile
from pathlib import Path
from urllib import request as urlrequest
from urllib.error import HTTPError

ROOT = Path(os.environ.get("APPDATA", "")) / "JinRefine"
FIELD_TO_COLUMN = {
    "title": "题名",
    "author": "责任者",
    "doc_no": "文号",
    "date": "成文日期",
}


def parse_data_txt(text):
    """解析 3.11 的 data.txt：{sheetId: {"cols": [...], "rows": [[{cell}...]]}}。"""
    sheets = {}
    cur = None
    mode = None
    cols = []
    for line in text.splitlines():
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
                cur["rows"].append(row.get("cells", []))
            except Exception:
                continue
    return sheets


def load_project(project):
    d = Path(project)
    if not d.is_dir():
        d = ROOT / ("%s.project" % project)
    with zipfile.ZipFile(d / "data.zip") as z:
        text = z.read("data.txt").decode("utf-8")
    return d.name[:-len(".project")] if d.name.endswith(".project") else d.name, parse_data_txt(text)


def cell_text(cell):
    if not isinstance(cell, dict):
        return ""
    v = cell.get("v")
    if v is None:
        return ""
    return str(v).strip()


def build_row_index(sheets):
    """(案卷号, 件号) → 卷内行号（多 sheet 合并时按出现顺序累加行号）。"""
    index = {}
    row_no = 0
    for sid, sh in sheets.items():
        if "卷内" not in sid:
            continue
        cols = sh["cols"]
        try:
            i_case = cols.index("案卷号")
            i_piece = cols.index("件号")
        except ValueError:
            continue
        for cells in sh["rows"]:
            case_no = cell_text(cells[i_case]) if i_case < len(cells) else ""
            piece_no = cell_text(cells[i_piece]) if i_piece < len(cells) else ""
            index[(case_no, piece_no.lstrip("0") or piece_no)] = row_no
            row_no += 1
    return index


def build_labels(metrics, row_index):
    """metrics_detail → [{row, fieldKey, labelValue}]，跳过 exact / both_empty。"""
    labels, unresolved, skipped = [], 0, 0
    for vol in metrics.get("volumes") or []:
        case_no = (vol.get("doc_no") or "").strip()
        for piece in vol.get("pieces_detail") or []:
            piece_no = (piece.get("pred_piece_no") or "").strip()
            key = (case_no, piece_no.lstrip("0") or piece_no)
            row = row_index.get(key)
            if row is None:
                unresolved += 1
                continue
            for field, detail in (piece.get("fields") or {}).items():
                column = FIELD_TO_COLUMN.get(field)
                if column is None or not isinstance(detail, dict):
                    continue
                status = detail.get("status")
                if status in ("exact", "both_empty"):
                    skipped += 1
                    continue
                gt = detail.get("gt_raw")
                labels.append({
                    "row": row,
                    "fieldKey": column,
                    "labelValue": (gt if gt is not None and str(gt).strip() else None),
                })
    return labels, unresolved, skipped


class OrClient:
    """最小 OR 客户端：狗登录（或直连）+ CSRF + 两个命令。"""

    def __init__(self, base, user=None, password=None):
        self.base = base.rstrip("/")
        self.token = None
        self.csrf = None
        self._login(user, password)

    def _login(self, user=None, password=None):
        """平台入口先试狗登录（与 run_batch_extraction.ps1 一致）；有账密再走 /api/auth/login。"""
        if user:
            body = json.dumps({"username": user, "password": password}).encode("utf-8")
            data = self._post_json_raw("/api/auth/login", body)
            self.token = ((data.get("data") or {}).get("token")) or None
            if self.token:
                return
        try:
            data = self._post_json_raw("/api/auth/dog", b"")
        except RuntimeError as e:
            if "404" in str(e) or "405" in str(e):      # 直连 OR（:3333）没有狗登录接口
                return
            raise
        self.token = ((data.get("data") or {}).get("token")) or None

    def _headers(self):
        h = {"Content-Type": "application/json; charset=UTF-8"}
        if self.token:
            h["X-Auth-Token"] = self.token
        return h

    def _post_json_raw(self, path, body):
        req = urlrequest.Request(self.base + path, data=body, method="POST",
                                 headers=self._headers())
        try:
            with urlrequest.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except HTTPError as e:
            raise RuntimeError("OR %s 返回 %s: %s" % (path, e.code, e.read().decode("utf-8", "replace")))

    def _post_form(self, path, params):
        from urllib.parse import urlencode
        body = urlencode(params).encode("utf-8")
        headers = self._headers()
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        req = urlrequest.Request(self.base + path, data=body, method="POST",
                                 headers=headers)
        try:
            with urlrequest.urlopen(req, timeout=600) as r:
                return json.loads(r.read().decode("utf-8"))
        except HTTPError as e:
            raise RuntimeError("OR %s 返回 %s: %s" % (path, e.code, e.read().decode("utf-8", "replace")))

    def ensure_csrf(self):
        if self.csrf:
            return self.csrf
        req = urlrequest.Request(self.base + "/command/core/get-csrf-token", headers=self._headers())
        with urlrequest.urlopen(req, timeout=60) as r:
            self.csrf = json.loads(r.read().decode("utf-8")).get("token")
        return self.csrf

    def apply_labels(self, project_id, labels):
        return self._post_form("/command/data-quality/apply-extraction-labels", {
            "project": project_id,
            "labels": json.dumps(labels, ensure_ascii=False),
            "csrf_token": self.ensure_csrf(),
        })

    def submit_feedback(self, project_id, origin, source="ui_export"):
        return self._post_form("/command/data-quality/submit-extraction-feedback", {
            "project": project_id,
            "source": source,
            "origin": origin,
            "push": "1",
            "csrf_token": self.ensure_csrf(),
        })


def main(argv=None):
    ap = argparse.ArgumentParser(description="benchmark 真值 → OR 单元格 → RCP 反馈样本")
    ap.add_argument("--project", required=True, help="OR 项目 id 或 .project 目录路径")
    ap.add_argument("--metrics", required=True, help="benchmark metrics_detail.json")
    ap.add_argument("--base", default="http://127.0.0.1:8090",
                    help="OR 入口（缺省经平台 8090 代理；直连 OR 填 :3333）")
    ap.add_argument("--origin", default="mock-data-gen", help="合成批次标记（写入 RCP batch.origin）")
    ap.add_argument("--source", default="ui_export", help="反馈来源（合成数据固定 ui_export）")
    ap.add_argument("--user", help="平台用户名（加密狗不可用时）")
    ap.add_argument("--password", help="平台密码")
    ap.add_argument("--limit", type=int, help="最多写回多少个格子（调试用）")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不写不改不提交")
    a = ap.parse_args(argv)

    project_id, sheets = load_project(a.project)
    row_index = build_row_index(sheets)
    if not row_index:
        print("[FAIL] 项目里没有可识别的卷内表（缺 案卷号/件号 列）", file=sys.stderr)
        return 2

    metrics = json.load(io.open(a.metrics, encoding="utf-8"))
    labels, unresolved, skipped = build_labels(metrics, row_index)
    if a.limit:
        labels = labels[:a.limit]

    print("[plan] project=%s 行映射=%d 待写回=%d（exact 跳过=%d，未对齐件=%d）"
          % (project_id, len(row_index), len(labels), skipped, unresolved))
    if a.dry_run:
        for x in labels[:20]:
            print("  row=%(row)s %(fieldKey)s → %(labelValue)r" % x)
        if len(labels) > 20:
            print("  ... 其余 %d 条" % (len(labels) - 20))
        return 0
    if not labels:
        print("[skip] 没有需要写回的格子（真值已全部一致）")
        return 0

    client = OrClient(a.base, a.user, a.password)
    applied = client.apply_labels(project_id, labels)
    print("[apply] code=%s applied=%s skipped=%s saved=%s"
          % (applied.get("code"), applied.get("applied"), applied.get("skipped"), applied.get("saved")))
    if applied.get("code") != "ok":
        print("[FAIL] 写回失败：%s" % applied, file=sys.stderr)
        return 3

    out = client.submit_feedback(project_id, a.origin, a.source)
    print("[submit] %s" % json.dumps(out, ensure_ascii=False)[:2000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
