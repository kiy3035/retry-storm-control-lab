import csv
import importlib.util
import json
from pathlib import Path
import sys

SPEC = importlib.util.spec_from_file_location("analysis", Path(__file__).with_name("analyze-experiment.py"))
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)
ROOT = Path(__file__).resolve().parents[1]


def verify(folder):
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    stored = json.loads((folder / "summary.json").read_text(encoding="utf-8"))
    if manifest["status"] != "COMPLETED" or manifest["cleanup"] != "COMPLETED":
        raise ValueError("완료되지 않은 실험입니다.")
    rows, buckets = [], {}
    for name in manifest["trials"]:
        raw = json.loads((folder / (name + ".json")).read_text(encoding="utf-8"))
        row, bucket = analysis.summarize(raw)
        rows.append(row)
        buckets[name] = bucket
    if rows != stored["runs"]:
        raise ValueError("원본과 요약 값이 다릅니다.")
    for policy, summary in stored["policies"].items():
        members = [row for row in rows if row["policy"] == policy]
        for key, value in summary.items():
            if analysis.stats([row[key] for row in members]) != value:
                raise ValueError("반복 집계가 일치하지 않습니다.")
    if buckets != json.loads((folder / "retry-buckets.json").read_text(encoding="utf-8")):
        raise ValueError("원본 시각과 bucket이 일치하지 않습니다.")
    with (folder / "summary.csv").open(encoding="utf-8", newline="") as source:
        csv_rows = list(csv.DictReader(source))
    if csv_rows != [{key: "" if value is None else str(value) for key, value in row.items()} for row in rows]:
        raise ValueError("CSV와 JSON이 일치하지 않습니다.")
    table = (folder / "comparison-table.md").read_text(encoding="utf-8").strip()
    for doc in ("experiment-report.md", "blog-draft.md"):
        if table not in (ROOT / "docs" / doc).read_text(encoding="utf-8"):
            raise ValueError("문서의 표가 분석 결과와 다릅니다: " + doc)
    print("원본→요약→bucket→CSV→보고서·블로그 일치 확인 완료")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
