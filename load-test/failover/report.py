#!/usr/bin/env python3
"""probe.py가 남긴 결과(probe.csv, events.csv, verify.json)를 읽어서 한 장짜리 보고서(report.html, report.md)를 만든다.

report.html은 외부 파일 없이 혼자 열리는 파일이라 그대로 공유하거나 스크린샷으로 남기면 된다.
  - 요약 표: 프로브별 성공률, 실패 구간, 복구 시간
  - 타임라인: 프로브마다 한 줄, 초록 = 성공, 빨강 = 실패. 세로선은 장애를 일으킨 시각과 복구 시각
  - 응답 시간: 점 하나가 요청 하나
사용: report.py results/<폴더>
"""
import csv
import html
import json
import os
import sys
from datetime import datetime

ORDER = ["read", "db_read", "login", "reissue", "addr_create", "addr_delete"]
LABEL = {
    "read": "read · 카테고리 조회 (Redis 캐시, 우회 가능)",
    "db_read": "db_read · 배송지 조회 (DB만)",
    "login": "login · 로그인 (Redis + DB)",
    "reissue": "reissue · 토큰 재발급 (Redis)",
    "addr_create": "addr_create · 배송지 등록 (락 + DB 쓰기)",
    "addr_delete": "addr_delete · 배송지 삭제 (락 + DB 쓰기)",
}
OK_COLOR, FAIL_COLOR, EVENT_COLOR = "#2e9e5b", "#d64545", "#444444"


def parse_ts(value):
    return datetime.fromisoformat(value).timestamp()


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))
    return ordered[index]


def load(folder):
    rows = []
    with open(os.path.join(folder, "probe.csv"), encoding="utf-8") as file:
        for row in csv.DictReader(file):
            rows.append({"ts": parse_ts(row["ts"]), "t": float(row["t"]), "probe": row["probe"], "ok": row["ok"] == "1",
                         "status": int(row["status"]), "ms": float(row["ms"]), "err": row["err"]})
    events = []
    path = os.path.join(folder, "events.csv")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as file:
            for line in file:
                line = line.strip()
                if not line:
                    continue
                ts, name, *rest = line.split(",", 2)
                events.append({"ts": parse_ts(ts), "name": name, "detail": rest[0] if rest else ""})
    verify = {}
    path = os.path.join(folder, "verify.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as file:
            verify = json.load(file)
    return rows, events, verify


def failure_windows(samples, gap=5.0):
    """연속된 실패를 하나의 구간으로 묶는다. 요청이 매달린 시간(ms)까지 실패 구간에 넣고, 성공이 gap초 넘게 끼어야 다른 구간으로 본다."""
    windows = []
    for sample in samples:
        if sample["ok"]:
            continue
        start, end = sample["t"], sample["t"] + sample["ms"] / 1000
        if windows and start - windows[-1]["end"] <= gap:
            windows[-1]["end"] = max(windows[-1]["end"], end)
            windows[-1]["count"] += 1
        else:
            windows.append({"start": start, "end": end, "count": 1})
    return windows


def summarize(rows, events):
    base_ts = rows[0]["ts"] - rows[0]["t"]  # t=0의 실제 시각
    inject = next((e for e in events if e["name"] == "inject"), None)
    inject_t = inject["ts"] - base_ts if inject else None
    summary = []
    for probe in [p for p in ORDER if any(r["probe"] == p for r in rows)]:
        samples = [r for r in rows if r["probe"] == probe]
        failed = [s for s in samples if not s["ok"]]
        latencies = [s["ms"] for s in samples if s["ok"]]
        windows = failure_windows(samples)
        # 장애를 일으킨 뒤 마지막으로 실패한 요청이 있으면, 그 이후 첫 성공까지를 복구 시간으로 본다
        recovery = None
        if inject_t is not None and windows:
            after = [w for w in windows if w["end"] >= inject_t]
            if after:
                last = after[-1]["end"]
                nxt = next((s for s in samples if s["ok"] and s["t"] + s["ms"] / 1000 > last), None)
                recovery = (nxt["t"] + nxt["ms"] / 1000 - inject_t) if nxt else None
        summary.append({
            "probe": probe, "total": len(samples), "failed": len(failed),
            "availability": 100.0 * (len(samples) - len(failed)) / len(samples) if samples else 0.0,
            "p50": percentile(latencies, 50), "p95": percentile(latencies, 95), "max": max(latencies) if latencies else None,
            "windows": windows,
            "outage": sum(w["end"] - w["start"] for w in windows) if windows else 0.0,
            "recovery": recovery,
            "statuses": sorted({(s["status"], s["err"]) for s in failed}),
        })
    return summary, base_ts, inject_t


def timeline_svg(rows, events, base_ts, summary):
    width, left, right = 1040, 330, 20
    row_h, gap = 26, 8
    probes = [s["probe"] for s in summary]
    max_t = max(r["t"] + r["ms"] / 1000 for r in rows) or 1
    plot_w = width - left - right

    def x(t):
        return left + plot_w * (t / max_t)

    parts = []
    height = 40 + len(probes) * (row_h + gap) + 190
    for i, probe in enumerate(probes):
        y = 30 + i * (row_h + gap)
        parts.append(f'<text x="{left - 10}" y="{y + row_h * 0.68}" text-anchor="end" font-size="11.5" fill="#222">{html.escape(LABEL.get(probe, probe))}</text>')
        parts.append(f'<rect x="{left}" y="{y}" width="{plot_w}" height="{row_h}" fill="#f0f0f0"/>')
        # 호출 주기가 긴 프로브(로그인, 재발급)는 칸을 주기만큼 넓게 그려서 비어 보이지 않게 한다
        ts_list = [r["t"] for r in rows if r["probe"] == probe]
        gaps = sorted(b - a for a, b in zip(ts_list, ts_list[1:]) if b - a < 30) or [1.0]
        spacing = gaps[len(gaps) // 2]
        sample_w = max(1.5, plot_w / max(1, max_t) * spacing * 0.92)
        for sample in (r for r in rows if r["probe"] == probe):
            color = OK_COLOR if sample["ok"] else FAIL_COLOR
            # 실패한 요청은 매달린 시간만큼 넓게 그려서, 시간 초과로 오래 걸린 구간이 빨갛게 보이게 한다
            w = sample_w if sample["ok"] else max(sample_w, plot_w * (sample["ms"] / 1000) / max_t)
            parts.append(f'<rect x="{x(sample["t"]):.1f}" y="{y}" width="{w:.1f}" height="{row_h}" fill="{color}"/>')
    axis_y = 30 + len(probes) * (row_h + gap)
    step = 10 if max_t <= 200 else 30 if max_t <= 900 else 60
    t = 0
    while t <= max_t:
        parts.append(f'<line x1="{x(t):.1f}" y1="20" x2="{x(t):.1f}" y2="{axis_y}" stroke="#ddd"/>')
        parts.append(f'<text x="{x(t):.1f}" y="{axis_y + 14}" text-anchor="middle" font-size="11" fill="#666">{int(t)}s</text>')
        t += step
    # 가까운 이벤트(예: db_available와 after_start)의 이름이 겹치지 않게, 겹치면 한 줄 위로 올려서 쓴다
    label_parts, level_ends = [], []
    for event in events:
        et = event["ts"] - base_ts
        if 0 <= et <= max_t:
            parts.append(f'<line x1="{x(et):.1f}" y1="20" x2="{x(et):.1f}" y2="{axis_y}" stroke="{EVENT_COLOR}" stroke-width="2" stroke-dasharray="5,3"/>')
            start_x = x(et) + 4
            level = next((i for i, end in enumerate(level_ends) if start_x >= end), len(level_ends))
            if level == len(level_ends):
                level_ends.append(0)
            level_ends[level] = start_x + len(event["name"]) * 6.5 + 8
            label_parts.append(f'<text x="{start_x:.1f}" y="{12 + level * 13}" font-size="11" fill="{EVENT_COLOR}">{html.escape(event["name"])}</text>')
    shift = 13 * max(0, len(level_ends) - 1)
    # 응답 시간 산점도
    top = axis_y + 40
    chart_h = 130
    max_ms = max((r["ms"] for r in rows if r["ok"]), default=1) or 1
    cap = max(100.0, min(max_ms, 5000.0))
    parts.append(f'<text x="{left - 10}" y="{top + 12}" text-anchor="end" font-size="12" fill="#222">응답 시간(ms, 성공 요청)</text>')
    parts.append(f'<rect x="{left}" y="{top}" width="{plot_w}" height="{chart_h}" fill="#fafafa" stroke="#ddd"/>')
    parts.append(f'<text x="{left + 4}" y="{top + 12}" font-size="10" fill="#888">{int(cap)}ms</text>')
    palette = ["#4c78a8", "#f58518", "#54a24b", "#b279a2", "#e45756", "#72b7b2"]
    for i, probe in enumerate(probes):
        color = palette[i % len(palette)]
        for sample in (r for r in rows if r["probe"] == probe and r["ok"]):
            ms = min(sample["ms"], cap)
            cy = top + chart_h - chart_h * (ms / cap)
            parts.append(f'<circle cx="{x(sample["t"]):.1f}" cy="{cy:.1f}" r="1.8" fill="{color}" opacity="0.8"/>')
        step_x = min(130, (plot_w - 20) / max(1, len(probes)))
        parts.append(f'<rect x="{left + 10 + i * step_x:.0f}" y="{top + chart_h + 8}" width="10" height="10" fill="{color}"/>')
        parts.append(f'<text x="{left + 24 + i * step_x:.0f}" y="{top + chart_h + 17}" font-size="11" fill="#222">{html.escape(probe)}</text>')
    return (f'<svg viewBox="0 0 {width} {height + shift}" width="100%" xmlns="http://www.w3.org/2000/svg" font-family="-apple-system, Segoe UI, sans-serif">'
            f'{"".join(label_parts)}<g transform="translate(0,{shift})">{"".join(parts)}</g></svg>')


def fmt(value, unit="", digits=1):
    return "-" if value is None else f"{value:.{digits}f}{unit}"


def build(folder):
    rows, events, verify = load(folder)
    if not rows:
        raise SystemExit("probe.csv가 비어 있다")
    summary, base_ts, inject_t = summarize(rows, events)
    title = os.path.basename(os.path.abspath(folder))
    duration = max(r["t"] + r["ms"] / 1000 for r in rows)

    lines = [f"# 장애 대응 테스트 결과: {title}", "",
             f"- 측정 시간: {duration:.0f}초, 요청 {len(rows)}건", ""]
    if events:
        lines += ["## 이벤트", "", "| 경과(초) | 이벤트 | 내용 |", "|---|---|---|"]
        for event in events:
            lines.append(f"| {event['ts'] - base_ts:.1f} | {event['name']} | {event['detail']} |")
        lines.append("")
    lines += ["## 프로브별 결과", "",
              "| 프로브 | 요청 | 실패 | 성공률 | 실패 구간(초) | 장애 후 복구까지 | 응답 p50 | p95 | 최대 |", "|---|---|---|---|---|---|---|---|---|"]
    for s in summary:
        windows = ", ".join(f"{w['start']:.0f}~{w['end']:.0f}" for w in s["windows"]) or "-"
        lines.append(f"| {s['probe']} | {s['total']} | {s['failed']} | {s['availability']:.1f}% | {windows} | {fmt(s['recovery'], '초')} | "
                     f"{fmt(s['p50'], 'ms', 0)} | {fmt(s['p95'], 'ms', 0)} | {fmt(s['max'], 'ms', 0)} |")
    lines.append("")
    failures = [(s["probe"], s["statuses"]) for s in summary if s["statuses"]]
    if failures:
        lines += ["## 실패 응답", ""] + [f"- {p}: " + ", ".join(f"status {st} {err}".strip() for st, err in sts) for p, sts in failures] + [""]
    if verify:
        lines += ["## 쓰기 유실 확인", ""]
        if verify.get("checked"):
            lines += [f"- 성공 응답(201)을 받은 배송지 {verify['created_acked']}건, 삭제 성공(204) {verify['deleted_acked']}건",
                      f"- **유실(성공 응답을 받았는데 서버에 없음): {len(verify['lost'])}건** {verify['lost'] or ''}",
                      f"- 삭제 성공을 받았는데 남아 있음: {len(verify['resurrected'])}건 {verify['resurrected'] or ''}"]
        else:
            lines.append("- 종료 시점에 서버 조회를 하지 못해 확인하지 못했다")
        lines.append(f"- 재발급이 401로 끝난 횟수(리프레시 토큰이 서버에서 사라진 횟수): {verify.get('session_lost', 0)}")
        lines.append("")
    markdown = "\n".join(lines)

    # HTML
    def td(value):
        return f"<td>{html.escape(str(value))}</td>"

    table = ["<table><tr><th>프로브</th><th>요청</th><th>실패</th><th>성공률</th><th>실패 구간(초)</th><th>장애 후 복구까지</th><th>p50</th><th>p95</th><th>최대</th></tr>"]
    for s in summary:
        windows = ", ".join(f"{w['start']:.0f}~{w['end']:.0f}" for w in s["windows"]) or "-"
        cls = ' class="bad"' if s["failed"] else ""
        table.append(f"<tr{cls}>" + "".join(td(v) for v in [
            s["probe"], s["total"], s["failed"], f"{s['availability']:.1f}%", windows, fmt(s["recovery"], "초"),
            fmt(s["p50"], "ms", 0), fmt(s["p95"], "ms", 0), fmt(s["max"], "ms", 0)]) + "</tr>")
    table.append("</table>")
    event_list = "".join(f"<li><b>{e['ts'] - base_ts:.1f}초</b> {html.escape(e['name'])} {html.escape(e['detail'])}</li>" for e in events)
    verify_html = ""
    if verify.get("checked"):
        verify_html = (f"<h2>쓰기 유실 확인</h2><p>성공 응답을 받은 배송지 {verify['created_acked']}건 중 서버에 없는 것: "
                       f"<b>{len(verify['lost'])}건</b>. 삭제 성공을 받았는데 남아 있는 것: <b>{len(verify['resurrected'])}건</b>. "
                       f"리프레시 토큰이 사라진 횟수(재발급 401): <b>{verify.get('session_lost', 0)}</b></p>")
    page = f"""<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>{html.escape(title)}</title>
<style>body{{font-family:-apple-system,Segoe UI,sans-serif;margin:24px;color:#222;max-width:1040px}}
table{{border-collapse:collapse;width:100%;font-size:13px}}th,td{{border:1px solid #ddd;padding:6px 8px;text-align:right}}
th{{background:#f5f5f5}}td:first-child,th:first-child{{text-align:left}}tr.bad td{{background:#fff4f4}}
.legend span{{display:inline-block;width:12px;height:12px;margin:0 4px 0 12px;vertical-align:middle}}</style></head><body>
<h1>장애 대응 테스트 결과</h1><p>{html.escape(title)} · {duration:.0f}초 · 요청 {len(rows)}건</p>
<h2>타임라인</h2><p class="legend"><span style="background:{OK_COLOR}"></span>성공<span style="background:{FAIL_COLOR}"></span>실패
<span style="background:#f0f0f0"></span>요청 안 함<span style="background:#fff;border-top:2px dashed {EVENT_COLOR};height:0"></span>이벤트(장애 발생, 복구)</p>
{timeline_svg(rows, events, base_ts, summary)}
<h2>이벤트</h2><ul>{event_list or '<li>기록된 이벤트 없음</li>'}</ul>
<h2>프로브별 결과</h2>{''.join(table)}{verify_html}
</body></html>"""
    return markdown, page


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    folder = sys.argv[1]
    markdown, page = build(folder)
    with open(os.path.join(folder, "report.md"), "w", encoding="utf-8") as file:
        file.write(markdown)
    with open(os.path.join(folder, "report.html"), "w", encoding="utf-8") as file:
        file.write(page)
    # 마크다운 문서에 그림으로 넣을 수 있게 타임라인만 따로 저장한다. 어두운 배경에서도 읽히도록 흰 배경을 깐다
    rows, events, _ = load(folder)
    summary, base_ts, _ = summarize(rows, events)
    svg = timeline_svg(rows, events, base_ts, summary)
    svg = svg.replace(">", '><rect width="100%" height="100%" fill="#fff"/>', 1)
    with open(os.path.join(folder, "timeline.svg"), "w", encoding="utf-8") as file:
        file.write(svg)
    print(markdown)
    print(f"\n보고서: {os.path.join(folder, 'report.html')}, {os.path.join(folder, 'timeline.svg')}")


if __name__ == "__main__":
    main()
