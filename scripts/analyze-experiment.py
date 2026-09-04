import argparse
import csv
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import statistics


def instant(value):
    result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("시각에 시간대가 없습니다.")
    return result


def stats(values):
    if not values or any(not math.isfinite(x) for x in values):
        raise ValueError("통계 표본이 없거나 유한하지 않습니다.")
    ordered = sorted(values)
    def percentile(q):
        return ordered[math.ceil(len(ordered) * q) - 1]
    return {"count": len(values), "mean": statistics.mean(values), "median": statistics.median(values),
            "min": ordered[0], "max": ordered[-1], "p95": percentile(0.95), "p99": percentile(0.99)}


def summarize(trial):
    records = trial["messages"]
    if not records or len({r["messageId"] for r in records}) != len(records):
        raise ValueError("메시지가 없거나 ID가 중복됐습니다.")
    retries, durations, started, completed = [], [], [], []
    for record in records:
        times = [instant(value) for value in record["attemptTimestamps"]]
        if len(times) != record["attemptCount"] or len(times) != min(record["failuresBeforeSuccess"] + 1, 3):
            raise ValueError("시도 예산 또는 원본 시각 개수 불일치")
        expected = "FAILED" if record["failuresBeforeSuccess"] >= 3 else "SUCCEEDED"
        if record["state"] != expected:
            raise ValueError("종료 상태 불일치")
        publication, completion = instant(record["publishedAt"]), instant(record["completedAt"])
        if times != sorted(times) or not publication <= times[0] <= times[-1] <= completion:
            raise ValueError("서버 시각의 순서가 올바르지 않습니다.")
        durations.append((completion - publication).total_seconds() * 1000)
        started.append(times[0])
        completed.append(completion)
        retries.extend(times[1:])
    origin, end = min(started), max(completed)
    bucket_sets = {}
    for width in (100, 1000):
        count = int((end - origin).total_seconds() * 1000 // width) + 1
        buckets = [0] * count
        for value in retries:
            delta = value - origin
            microseconds = (delta.days * 86400 + delta.seconds) * 1000000 + delta.microseconds
            buckets[microseconds // (width * 1000)] += 1
        bucket_sets[str(width)] = buckets
    failed_ids = {r["messageId"] for r in records if r["state"] == "FAILED"}
    if {r["messageId"] for r in trial["dlq"]} != failed_ids or len(trial["dlq"]) != len(failed_ids):
        raise ValueError("실패 메시지와 DLQ 원본 불일치")
    if any(r["state"] != "PENDING" or r["originalAttempts"] != 3
           or r["failureCode"] != "RETRY_EXHAUSTED" for r in trial["dlq"]):
        raise ValueError("DLQ 저장 상태 불일치")
    if {r["messageId"] for r in trial["replays"]} != failed_ids or len(trial["replays"]) != len(failed_ids):
        raise ValueError("재처리 결과 누락 또는 중복")
    if any(r["state"] != "SUCCEEDED" or r["replayAttempts"] != 1 for r in trial["replays"]):
        raise ValueError("재처리 성공 조건 불일치")
    if trial["dbCallsAfter"] < trial["dbCallsBefore"] or trial["dbObservationSeconds"] <= 0:
        raise ValueError("DB 관측 구간 불일치")
    telemetry = {key: stats([float(point[1]) for point in samples])
                 for key, samples in trial["telemetry"].items()}
    row = {
        "trialId": trial["trialId"], "policy": trial["policy"], "repetition": trial["repetition"],
        "messages": len(records), "attempts": sum(r["attemptCount"] for r in records), "retries": len(retries),
        "succeeded": len(records) - len(failed_ids), "dlqStored": len(trial["dlq"]),
        "dlqRate": len(failed_ids) / len(records), "dlqStorageSuccessRate": 1.0 if failed_ids else None,
        "replaySuccessRate": 1.0 if failed_ids else None,
        "completionMedianMs": stats(durations)["median"], "completionP95Ms": stats(durations)["p95"],
        "completionP99Ms": stats(durations)["p99"], "completionMeanMs": stats(durations)["mean"],
        "completionMinMs": min(durations), "completionMaxMs": max(durations),
        "retry1sMax": max(bucket_sets["1000"]), "retry1sP95": stats(bucket_sets["1000"])["p95"],
        "retry100msMax": max(bucket_sets["100"]),
        "processCpuMean": telemetry["processCpu"]["mean"], "processCpuMax": telemetry["processCpu"]["max"],
        "queueDepthMax": telemetry["queueDepth"]["max"],
        "appDbCalls": trial["dbCallsAfter"] - trial["dbCallsBefore"],
        "appDbCallsPerSecond": (trial["dbCallsAfter"] - trial["dbCallsBefore"]) / trial["dbObservationSeconds"],
    }
    return row, {"origin": origin.isoformat(), "widthMsToCounts": bucket_sets}


def analyze(folder):
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    plan = manifest["plan"]
    if manifest["status"] != "COMPLETED" or manifest["cleanup"] != "COMPLETED":
        raise ValueError("실험 또는 리소스 정리가 완료되지 않았습니다.")
    expected = {(r + 1, p["name"]) for r in range(plan["repetitions"]) for p in plan["policies"]}
    summaries, bucket_results, actual = [], {}, set()
    for name in manifest["trials"]:
        trial = json.loads((folder / (name + ".json")).read_text(encoding="utf-8"))
        identity = (trial["repetition"], trial["policy"])
        if identity in actual:
            raise ValueError("중복 반복 실험")
        actual.add(identity)
        if len(trial["messages"]) != plan["messagesPerRun"]:
            raise ValueError("입력 건수 불일치")
        for index, record in enumerate(trial["messages"]):
            if record["inputIndex"] != index or record["failuresBeforeSuccess"] != plan["failurePattern"][index % len(plan["failurePattern"])]:
                raise ValueError("동일 입력 조건 불일치")
        summary, buckets = summarize(trial)
        summaries.append(summary)
        bucket_results[name] = buckets
    if expected != actual:
        raise ValueError("반복 실험 누락")
    groups = {}
    for policy in plan["policies"]:
        rows = [row for row in summaries if row["policy"] == policy["name"]]
        groups[policy["name"]] = {
            key: stats([row[key] for row in rows]) for key in rows[0]
            if key not in ("trialId", "policy", "repetition") and rows[0][key] is not None
        }
    result = {
        "analyzerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "percentile": "nearest-rank; median은 중앙 두 값의 평균",
        "runs": summaries,
        "policies": groups,
    }
    (folder / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (folder / "retry-buckets.json").write_text(json.dumps(bucket_results, indent=2) + "\n", encoding="utf-8")
    with (folder / "summary.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(summaries[0]))
        writer.writeheader()
        writer.writerows(summaries)
    lines = ["| 정책 | 반복 수 | 완료시간 중앙값(ms) | 완료시간 p95(ms) | 1초 재시도 최대 | 100ms 재시도 최대 |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for name, group in groups.items():
        lines.append(f"| {name} | {group['messages']['count']} | {group['completionMedianMs']['median']:.3f} | "
                     f"{group['completionP95Ms']['median']:.3f} | {group['retry1sMax']['median']:.0f} | "
                     f"{group['retry100msMax']['median']:.0f} |")
    table = "\n".join(lines) + "\n"
    (folder / "comparison-table.md").write_text(table, encoding="utf-8")
    return table


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("result_directory", type=Path)
    args = parser.parse_args()
    print(analyze(args.result_directory))
