import concurrent.futures
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import socket
import subprocess
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
HIDDEN = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def command(args, env, timeout=180):
    return subprocess.run(args, cwd=ROOT, env=env, check=True, capture_output=True,
                          encoding="utf-8", errors="replace", timeout=timeout,
                          creationflags=HIDDEN).stdout.strip()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("리다이렉트는 허용하지 않습니다.")


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def request(url, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with HTTP.open(req, timeout=10) as response:
        return json.load(response)


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def messages(base, failures, workers, timeout):
    def publish(item):
        index, count = item
        response = request(base + "/api/v1/messages",
                           {"payload": "로컬 비교 실험 합성 메시지", "failuresBeforeSuccess": count})
        return {"inputIndex": index, "failuresBeforeSuccess": count, "messageId": response["messageId"]}

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        inputs = list(pool.map(publish, enumerate(failures)))
        pending = {item["messageId"]: item for item in inputs}
        finished = []
        deadline = time.monotonic() + timeout
        while pending and time.monotonic() < deadline:
            ids = list(pending)
            responses = pool.map(lambda key: request(base + "/api/v1/messages/" + key), ids)
            for key, response in zip(ids, responses):
                if response["state"] in ("SUCCEEDED", "FAILED", "PERSISTENCE_FAILED"):
                    finished.append(dict(pending.pop(key), **{
                        name: response[name] for name in
                        ("state", "attemptCount", "attemptTimestamps", "publishedAt", "completedAt")
                    }))
            if pending:
                time.sleep(0.2)
        if pending:
            raise RuntimeError("메시지 종료 확인 시간이 초과됐습니다.")
    for item in finished:
        expected = "FAILED" if item["failuresBeforeSuccess"] >= 3 else "SUCCEEDED"
        if item["state"] != expected or item["attemptCount"] != min(item["failuresBeforeSuccess"] + 1, 3):
            raise RuntimeError("종료 상태 또는 시도 예산 불일치")
        if not item["completedAt"]:
            raise RuntimeError("서버 종료 시각이 없습니다.")
    return sorted(finished, key=lambda item: item["inputIndex"])


def main():
    plan = json.loads((ROOT / "experiments/plan.json").read_text(encoding="utf-8"))
    if not (1 <= plan["repetitions"] <= 5 and 4 <= plan["messagesPerRun"] <= 500
            and 1 <= plan["publishWorkers"] <= 32 and 1 <= plan["consumers"] <= 16
            and 1 <= plan["timeoutSeconds"] <= 300):
        raise ValueError("로컬 실험 예산 범위를 벗어났습니다.")
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("SPRING_", "LAB_", "POSTGRES_", "APP_DB_", "RABBITMQ_", "PROMETHEUS_"))}
    sockets = [socket.socket() for _ in range(5)]
    for sock in sockets:
        sock.bind(("127.0.0.1", 0))
    ports = [sock.getsockname()[1] for sock in sockets]
    for sock in sockets:
        sock.close()
    run_id = dt.datetime.now(dt.timezone.utc).strftime("stage6-%Y%m%dT%H%M%SZ")
    output = ROOT / "results" / run_id
    output.mkdir(parents=True, exist_ok=False)
    logs = ROOT / "build" / run_id
    logs.mkdir(parents=True, exist_ok=False)
    env.update({
        "POSTGRES_DB": "retry_storm", "POSTGRES_MIGRATION_USER": "retry_migrator",
        "POSTGRES_MIGRATION_PASSWORD": secrets.token_hex(24), "APP_DB_USER": "retry_app",
        "APP_DB_PASSWORD": secrets.token_hex(24), "POSTGRES_HOST": "localhost",
        "POSTGRES_PORT": str(ports[0]), "RABBITMQ_HOST": "localhost", "RABBITMQ_USER": "retry_app",
        "RABBITMQ_PASSWORD": secrets.token_hex(24), "RABBITMQ_AMQP_PORT": str(ports[1]),
        "RABBITMQ_MANAGEMENT_PORT": str(ports[2]), "SERVER_PORT": str(ports[3]),
        "PROMETHEUS_PORT": str(ports[4]), "LAB_RETRY_MAX_ATTEMPTS": "3",
        "SPRING_RABBITMQ_LISTENER_SIMPLE_CONCURRENCY": str(plan["consumers"]),
        "SPRING_RABBITMQ_LISTENER_SIMPLE_MAX_CONCURRENCY": str(plan["consumers"]),
        "SPRING_RABBITMQ_LISTENER_SIMPLE_PREFETCH": str(plan["prefetch"]),
        "GRADLE_USER_HOME": str(ROOT / ".gradle-user-home"),
        "JAVA_TOOL_OPTIONS": "-Djava.io.tmpdir=" + str(ROOT / ".tmp"),
    })
    (ROOT / ".tmp").mkdir(exist_ok=True)
    targets = logs / "targets.json"
    save(targets, [{"targets": ["host.docker.internal:" + str(ports[3])]}])
    env["PROMETHEUS_TARGETS_FILE"] = str(targets)
    project = "retry-storm-stage6-" + secrets.token_hex(4)
    compose = ["docker", "compose", "-p", project, "-f", "compose.yaml",
               "-f", "compose.monitoring.yaml", "-f", "compose.experiment.yaml"]
    base = "http://localhost:" + str(ports[3])
    prom = "http://localhost:" + str(ports[4])
    process = None
    manifest = {
        "runId": run_id, "status": "RUNNING", "plan": plan,
        "planSha256": hashlib.sha256((ROOT / "experiments/plan.json").read_bytes()).hexdigest(),
        "collectorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "sourceCommit": command(["git", "rev-parse", "HEAD"], env),
        "workingTreeChangesIncluded": bool(command(["git", "status", "--porcelain", "--untracked-files=no"], env)),
        "os": platform.platform(), "python": platform.python_version(),
        "docker": command(["docker", "version", "--format", "{{.Server.Version}}"], env),
        "compose": command(["docker", "compose", "version", "--short"], env),
        "trials": [],
    }
    manifest["java"] = subprocess.run(["java", "-version"], env=env, capture_output=True,
                                      text=True, check=True, creationflags=HIDDEN).stderr.strip()
    manifest["dockerResources"] = json.loads(command(
        ["docker", "info", "--format", '{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}}}'], env))
    if os.name == "nt":
        hardware = command(["powershell", "-NoProfile", "-Command",
                            "@{cpu=(Get-CimInstance Win32_Processor).Name; ramBytes=(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory} | ConvertTo-Json"], env)
        manifest["hardware"] = json.loads(hardware)
    save(output / "manifest.json", manifest)

    def sql(query):
        return command(compose + ["exec", "-T", "postgres", "psql", "-U", "retry_migrator",
                                   "-d", "retry_storm", "-At", "-c", query], env)

    def db_calls():
        return int(sql("SELECT COALESCE(sum(calls),0) FROM pg_stat_statements(false) "
                       "WHERE userid=(SELECT oid FROM pg_roles WHERE rolname='retry_app') "
                       "AND dbid=(SELECT oid FROM pg_database WHERE datname=current_database())"))

    try:
        build = ["cmd.exe", "/d", "/c", str(ROOT / "gradlew.bat")] if os.name == "nt" else ["./gradlew"]
        command(build + ["--no-daemon", "bootJar"], env, 300)
        jar = ROOT / "build/libs/retry-storm-control-lab-0.0.1-SNAPSHOT.jar"
        manifest["jarSha256"] = hashlib.sha256(jar.read_bytes()).hexdigest()
        command(compose + ["up", "-d", "--wait"], env)
        sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
        manifest["postgres"] = sql("SHOW server_version")
        manifest["images"] = {"rabbitmq": "4.1.4-management-alpine", "prometheus": "v3.5.0"}
        failure_input = [plan["failurePattern"][i % len(plan["failurePattern"])]
                         for i in range(plan["messagesPerRun"])]
        manifest["inputSha256"] = hashlib.sha256(json.dumps(failure_input).encode()).hexdigest()
        save(output / "manifest.json", manifest)
        for repetition in range(plan["repetitions"]):
            policies = plan["policies"][repetition:] + plan["policies"][:repetition]
            for policy in policies:
                trial_id = str(repetition + 1) + "-" + policy["name"]
                print("실험 시작: " + trial_id, flush=True)
                env.update({
                    "LAB_RETRY_MODE": policy["mode"], "LAB_RETRY_FIXED_DELAY": policy["fixedDelay"],
                    "LAB_RETRY_INITIAL_DELAY": policy["initialDelay"], "LAB_RETRY_MULTIPLIER": str(policy["multiplier"]),
                    "LAB_RETRY_MAX_DELAY": policy["maxDelay"], "LAB_RETRY_JITTER_RATIO": str(policy["jitterRatio"]),
                })
                with (logs / (trial_id + ".log")).open("w", encoding="utf-8") as log:
                    process = subprocess.Popen(["java", "-jar", str(jar)], cwd=ROOT, env=env,
                                               stdout=log, stderr=log, creationflags=HIDDEN)
                    ready = False
                    for _ in range(90):
                        if process.poll() is not None:
                            raise RuntimeError("앱이 준비 전에 종료됐습니다.")
                        try:
                            if request(base + "/actuator/health")["status"] == "UP":
                                ready = True
                                break
                        except Exception:
                            pass
                        time.sleep(1)
                    if not ready:
                        raise RuntimeError("앱 기동 시간 초과")
                    messages(base, [2] * plan["warmupMessages"], plan["publishWorkers"], plan["timeoutSeconds"])
                    observation_start = time.monotonic()
                    before = db_calls()
                    started = time.time()
                    records = messages(base, failure_input, plan["publishWorkers"], plan["timeoutSeconds"])
                    ended = time.time()
                    after = db_calls()
                    window = time.monotonic() - observation_start
                    trial = {"trialId": trial_id, "repetition": repetition + 1, "policy": policy["name"],
                             "startedEpochSeconds": started, "endedEpochSeconds": ended, "messages": records,
                             "dbCallsBefore": before, "dbCallsAfter": after, "dbObservationSeconds": window}
                    save(output / (trial_id + ".json"), trial)
                    time.sleep(2)
                    trial["telemetry"] = {}
                    for metric, query in {
                        "processCpu": 'process_cpu_usage{job="retry-lab"}',
                        "queueDepth": 'sum(rabbitmq_queue_messages{queue="retry.lab.work.v4"})',
                    }.items():
                        params = urllib.parse.urlencode({"query": query, "start": started, "end": ended, "step": 1})
                        response = request(prom + "/api/v1/query_range?" + params)
                        if response["status"] != "success" or len(response["data"]["result"]) != 1:
                            raise RuntimeError("Prometheus 시계열 수집 실패")
                        trial["telemetry"][metric] = response["data"]["result"][0]["values"]
                    trial["dlq"] = []
                    trial["replays"] = []
                    for record in records:
                        if record["state"] == "FAILED":
                            url = base + "/api/v1/dlq/" + record["messageId"]
                            entry = request(url)
                            trial["dlq"].append(entry)
                            replay = request(url + "/reprocess", {"expectedVersion": entry["version"], "failuresBeforeSuccess": 0})
                            trial["replays"].append(replay)
                            if replay["state"] != "SUCCEEDED" or replay["replayAttempts"] != 1:
                                raise RuntimeError("DLQ 재처리 실패")
                    save(output / (trial_id + ".json"), trial)
                    manifest["trials"].append(trial_id)
                    save(output / "manifest.json", manifest)
                    stop(process)
                    process = None
                print("실험 완료: " + trial_id, flush=True)
        manifest["status"] = "COMPLETED"
    except Exception as error:
        manifest["status"] = "FAILED"
        manifest["errorType"] = type(error).__name__
        (logs / "failure.log").write_text(getattr(error, "stderr", None) or str(error), encoding="utf-8")
        raise
    finally:
        stop(process)
        try:
            command(compose + ["down", "-v"], env)
            manifest["cleanup"] = "COMPLETED"
        except Exception:
            manifest["cleanup"] = "FAILED"
            manifest["status"] = "FAILED"
            raise
        finally:
            save(output / "manifest.json", manifest)
            print("결과 위치: " + str(output), flush=True)


if __name__ == "__main__":
    main()
