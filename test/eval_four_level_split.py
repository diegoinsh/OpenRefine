# -*- coding: utf-8 -*-
"""四级分件集成评测：mock 4L 卷 → AIMP 页级抽取 → 卷级分件 → 与 gt materials 对照。

链路与 OpenRefine 批量抽取一致：
  1. 逐页 POST /extract/upload（同步，法院门类，页号/总页数/禁用缓存）；
  2. 用封面的 party_roles + 卷内条目页的 title/party/版面信号 POST /extract/split-pieces；
  3. 把返回件区间换算回卷内页号，与 gt 每件的 materials[] 比较切点。

用法：
    python test/eval_four_level_split.py --gt <gt.json> --images <卷图片目录> [--limit N]
    python test/eval_four_level_split.py --gt ... --images ... --reuse   # 复用已落盘页结果

产物：test-output/four-level/<档号>.json（逐页结果 + 两侧件区间 + 指标）
"""
import argparse
import json
import os
import sys
import time
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / 'test-output' / 'four-level'

KEYS = 'title,responsible_party,dangshiren'
SUB = '人民法院诉讼档案'
CATEGORY = '专业档案'


# ---------------------------------------------------------------- HTTP
def _post_multipart(url, file_path, fields, timeout=600):
    boundary = '----fourlevel' + uuid.uuid4().hex
    body = bytearray()
    for name, value in fields.items():
        body.extend(('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n' % (boundary, name)).encode())
        body.extend(str(value).encode('utf-8'))
        body.extend(b'\r\n')
    name = os.path.basename(file_path)
    body.extend(('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\n'
                 'Content-Type: application/octet-stream\r\n\r\n' % (boundary, name)).encode('utf-8'))
    body.extend(Path(file_path).read_bytes())
    body.extend(('\r\n--%s--\r\n' % boundary).encode())
    req = urllib.request.Request(url, data=bytes(body), method='POST')
    req.add_header('Content-Type', 'multipart/form-data; boundary=%s' % boundary)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8'))


def _post_json(url, payload, timeout=300):
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(url, data=data, method='POST')
    req.add_header('Content-Type', 'application/json; charset=utf-8')
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8'))


# ---------------------------------------------------------------- 抽取
def _image_of(images_dir, page_no):
    """卷内页号 → 图片路径；条目页统一是 _P%03d。"""
    matches = sorted(Path(images_dir).glob('*_P%03d.jpg' % page_no))
    return matches[0] if matches else None


def _cover_image(images_dir):
    matches = sorted(Path(images_dir).glob('*_00-卷宗封面.jpg'))
    return matches[0] if matches else None


CUSTOM_ELEMENTS = json.dumps([
    {"name": "当事人", "key": "dangshiren", "action": "include",
     "description": "提取本页出现的当事人姓名或名称，只取姓名或名称本身，不带诉讼地位称谓。"},
], ensure_ascii=False)


def extract_page(aimp, image, page_no, total_pages, extra_keys=None, force=False):
    fields = {
        'key_list': KEYS + (',' + extra_keys if extra_keys else ''),
        'sync': 'true',
        'custom_elements': CUSTOM_ELEMENTS,
        'options': json.dumps({
            'current_page': page_no,
            'total_pages': total_pages,
            'disable_cache': True,
            'force_extract': bool(force),
            'four_level_split': True,
            'archive_category': CATEGORY,
            'archive_sub_category': SUB,
        }, ensure_ascii=False),
    }
    data = _post_multipart(aimp.rstrip('/') + '/extract/upload', image, fields)
    if not data.get('success'):
        raise RuntimeError('抽取失败 页%d：%s' % (page_no, data.get('message')))
    payload = data.get('data') or {}
    results = payload.get('results') or data.get('results') or {}
    infos = {}
    for key, info in results.items():
        if isinstance(info, dict):
            infos[key] = {'value': info.get('value', ''), 'confidence': info.get('confidence')}
        else:
            infos[key] = {'value': info, 'confidence': None}
    title = (infos.get('title') or {}).get('value') or ''
    party = (infos.get('responsible_party') or {}).get('value') or ''
    conf = (infos.get('title') or {}).get('confidence')
    layout = payload.get('layout_titles') or {}
    page_status = payload.get('page_status') or {}
    return {
        'value': title,
        'pageTitle': title,
        'titleConfidence': conf,
        'party': party,
        'holder': (infos.get('dangshiren') or {}).get('value') or '',
        'partyRoles': infos.get('party_roles', {}).get('value'),
        'titleRegions': layout.get('title_region_count', 0),
        'pageType': (page_status.get('page_type') or (payload.get('document_status') or {}).get('page_type') or ''),
        'skipped': str(payload.get('processing_mode') or '').startswith('single_page_skipped'),
    }


def extract_volume(aimp, gt, images_dir, limit=None, reuse=False):
    doc_no = gt.get('doc_no') or Path(images_dir).name
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cache = OUT_DIR / ('pages-%s.json' % doc_no)
    if reuse and cache.is_file():
        return json.loads(cache.read_text(encoding='utf-8'))

    page_nos = set()
    for piece in gt.get('pieces') or []:
        for m in piece.get('materials') or []:
            page_nos.add(int(m['start']))
            page_nos.add(int(m['end']))
    total = max(page_nos) if page_nos else 0
    ordered = sorted(page_nos)
    if limit:
        ordered = ordered[:limit]

    cover = _cover_image(images_dir)
    cover_result = None
    if cover is not None:
        print('抽取封面（party_roles）...', flush=True)
        cover_result = extract_page(aimp, str(cover), 0, total)

    pages = []
    t0 = time.time()
    for i, page_no in enumerate(ordered, 1):
        image = _image_of(images_dir, page_no)
        if image is None:
            raise RuntimeError('第 %d 页图片不存在：%s' % (page_no, images_dir))
        result = extract_page(aimp, str(image), page_no, total)
        result['page'] = page_no
        pages.append(result)
        print('[%3d/%3d] 页%02d %6.1fs  题名=%s  责任者=%s  标题块=%s  置信度=%s' % (
            i, len(ordered), page_no, time.time() - t0,
            result['pageTitle'] or '—', result['party'] or '—',
            result['titleRegions'], result['titleConfidence']), flush=True)

    payload = {'doc_no': doc_no, 'total_pages': total, 'cover': cover_result, 'pages': pages}
    retry_material_home_pages(aimp, payload, images_dir, total)
    cache.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
    return payload


def retry_material_home_pages(aimp, payload, images_dir, total):
    """与 OpenRefine BatchExtractionManager.retryMaterialHomePages 同口径：
    被判跳过、题名空、但版面有标题块的条目页 → force_extract 补抽一次。"""
    for result in payload['pages']:
        if not (result.get('skipped') and not (result.get('pageTitle') or '').strip()
                and (result.get('titleRegions') or 0) > 0):
            continue
        image = _image_of(images_dir, result['page'])
        if image is None:
            continue
        print('补抽材料首页 页%d（跳页 + 无题名 + 标题块=%d）...' % (
            result['page'], result['titleRegions']), flush=True)
        retried = extract_page(aimp, str(image), result['page'], total, force=True)
        retried['page'] = result['page']
        result.update(retried)


# ---------------------------------------------------------------- 分件
def _party_roles_of(cover):
    """封面页的 party_roles 可能是 JSON 字符串（AIMP 规则解析产出）。"""
    if not cover:
        return None
    raw = cover.get('partyRoles')
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            data = json.loads(raw)
            return data if isinstance(data, dict) else None
        except ValueError:
            return None
    return None


CERT_TITLE_WORDS = ('身份证', '户口簿', '户口本', '常住人口', '营业执照',
                    '法定代表人身份证明', '执业证', '组织机构代码证')


def material_party(page, certificate_fallback):
    """与 OpenRefine partyForPiece 同口径：证件页责任者为空时用「当事人」（证面姓名）。"""
    party = (page.get('party') or '').strip()
    if party or not certificate_fallback:
        return party
    title = page.get('pageTitle') or ''
    if any(word in title for word in CERT_TITLE_WORDS):
        return (page.get('holder') or '').strip()
    return party


def split_pieces(aimp, extracted, enabled):
    pages = []
    for i, p in enumerate(extracted['pages'], 1):
        pages.append({
            'page': i,
            'title': p['pageTitle'] or '',
            'party': material_party(p, enabled),
            'title_confidence': p['titleConfidence'],
            'title_regions': p['titleRegions'],
            'skipped': p['skipped'],
            'page_type': p['pageType'],
        })
    payload = {
        'pages': pages,
        'total_pages': len(pages),
        'archive_category': CATEGORY,
        'archive_sub_category': SUB,
        'four_level_split': enabled,
    }
    roles = _party_roles_of(extracted.get('cover'))
    if roles:
        payload['party_roles'] = json.dumps(roles, ensure_ascii=False)
    return _post_json(aimp.rstrip('/') + '/extract/split-pieces', payload)


# ---------------------------------------------------------------- 评测
def _gt_page_range(gt):
    return sorted(int(m['start']) for piece in gt.get('pieces') or []
                  for m in piece.get('materials') or [])


def _range_of(piece):
    return int(piece['start']), int(piece['end'])


def evaluate(gt, extracted, pieces):
    """把 pred 件区间（条目页序号 1..N）换算成卷内页号，与 gt materials 起点比对。"""
    ordered = [p['page'] for p in extracted['pages']]
    index_to_page = {i + 1: page for i, page in enumerate(ordered)}

    def to_pages(seq):
        out = []
        for p in seq:
            s = index_to_page.get(int(p['start']), int(p['start']))
            e = index_to_page.get(int(p['end']), int(p['end']))
            out.append({'start': s, 'end': e, 'title': p.get('title', '')})
        return out

    pred = to_pages(pieces)
    gt_starts = set()
    gt_materials = []
    for piece in gt.get('pieces') or []:
        for m in piece.get('materials') or []:
            gt_starts.add(int(m['start']))
            gt_materials.append({
                'start': int(m['start']), 'end': int(m['end']),
                'breed': m.get('breed', ''), 'piece': piece.get('piece_no'),
                'piece_title': piece.get('title'),
            })
    pred_starts = {p['start'] for p in pred}
    # 切点 = 件/材料起点（卷内第 1 页不参与，它两侧都必然起一件）
    first = min([m['start'] for m in gt_materials] + [p['start'] for p in pred]) if gt_materials else 1
    gt_cut = gt_starts - {first}
    pred_cut = pred_starts - {first}
    hit = gt_cut & pred_cut
    precision = len(hit) / len(pred_cut) if pred_cut else 1.0
    recall = len(hit) / len(gt_cut) if gt_cut else 1.0
    f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)

    # 逐件对照：gt 每个件被切成几份、多切/少切在哪
    detail = []
    for piece in gt.get('pieces') or []:
        mats = piece.get('materials') or []
        if not mats:
            continue
        lo, hi = int(mats[0]['start']), int(mats[-1]['end'])
        inside = [p for p in pred if lo <= p['start'] <= hi]
        detail.append({
            'piece_no': piece.get('piece_no'),
            'piece_title': piece.get('title'),
            'gt_materials': len(mats),
            'pred_pieces': len(inside),
            'ok': len(inside) == len(mats),
            'pred': [{'start': p['start'], 'end': p['end'], 'title': p['title']} for p in inside],
            'gt': [{'start': int(m['start']), 'end': int(m['end']), 'breed': m.get('breed')}
                   for m in mats],
        })
    return {
        'piece_count': len(pred),
        'gt_material_count': len(gt_materials),
        'boundary_precision': round(precision, 4),
        'boundary_recall': round(recall, 4),
        'boundary_f1': round(f1, 4),
        'missing_cuts': sorted(gt_cut - pred_cut),
        'extra_cuts': sorted(pred_cut - gt_cut),
        'pieces': detail,
        'pred': pred,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--gt', required=True)
    ap.add_argument('--images', required=True)
    ap.add_argument('--aimp', default='http://127.0.0.1:7998')
    ap.add_argument('--limit', type=int, default=None, help='只抽前 N 页（冒烟）')
    ap.add_argument('--reuse', action='store_true', help='复用已落盘的页级结果')
    args = ap.parse_args()

    gt = json.loads(Path(args.gt).read_text(encoding='utf-8'))
    extracted = extract_volume(args.aimp, gt, args.images, args.limit, args.reuse)

    baseline = split_pieces(args.aimp, extracted, False)
    four = split_pieces(args.aimp, extracted, True)
    if not baseline.get('success') or not four.get('success'):
        raise SystemExit('分件失败：baseline=%s four=%s' % (
            baseline.get('reason'), four.get('reason')))

    report = {
        'doc_no': extracted['doc_no'],
        'pages': len(extracted['pages']),
        'baseline': {'piece_count': len(baseline.get('pieces') or [])},
        'four_level': evaluate(gt, extracted, four.get('pieces') or []),
        'baseline_metrics': evaluate(gt, extracted, baseline.get('pieces') or []),
        'baseline_pieces': baseline.get('pieces'),
        'four_pieces': four.get('pieces'),
    }
    out = OUT_DIR / ('report-%s.json' % extracted['doc_no'])
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    fl = report['four_level']
    print('\n=== 四级分件评测 %s ===' % extracted['doc_no'])
    print('件数：基线 %d → 四级 %d（gt 材料数 %d）' % (
        report['baseline']['piece_count'], fl['piece_count'], fl['gt_material_count']))
    print('切点 P=%.3f R=%.3f F1=%.3f' % (
        fl['boundary_precision'], fl['boundary_recall'], fl['boundary_f1']))
    print('漏切页: %s' % (fl['missing_cuts'] or '无'))
    print('多切页: %s' % (fl['extra_cuts'] or '无'))
    bad = [d for d in fl['pieces'] if not d['ok']]
    if bad:
        print('件级不一致 %d 件：' % len(bad))
        for d in bad:
            print('  %s %s：gt %d 份 / pred %d 件  pred=%s' % (
                d['piece_no'], d['piece_title'], d['gt_materials'], d['pred_pieces'],
                [(p['start'], p['end'], p['title']) for p in d['pred']]))
    else:
        print('件级全部一致')
    print('报告：%s' % out)
    return 0 if not bad else 1


if __name__ == '__main__':
    sys.exit(main())
