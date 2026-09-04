$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$listeners = @()
try {
    for ($i = 0; $i -lt 5; $i++) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $listeners += $listener
    }
    $ports = @($listeners | ForEach-Object { $_.LocalEndpoint.Port })
} finally {
    $listeners | ForEach-Object { $_.Stop() }
}

$env:POSTGRES_DB = 'retry_storm'
$env:POSTGRES_MIGRATION_USER = 'retry_migrator'
$env:POSTGRES_MIGRATION_PASSWORD = 'local-' + [guid]::NewGuid().ToString('N')
$env:APP_DB_USER = 'retry_app'
$env:APP_DB_PASSWORD = 'local-' + [guid]::NewGuid().ToString('N')
$env:POSTGRES_HOST = 'localhost'
$env:POSTGRES_PORT = [string]$ports[0]
$env:RABBITMQ_HOST = 'localhost'
$env:RABBITMQ_USER = 'retry_app'
$env:RABBITMQ_PASSWORD = 'local-' + [guid]::NewGuid().ToString('N')
$env:RABBITMQ_AMQP_PORT = [string]$ports[1]
$env:RABBITMQ_MANAGEMENT_PORT = [string]$ports[2]
$env:SERVER_PORT = [string]$ports[3]
$env:LAB_RETRY_MODE = 'FIXED'
$env:LAB_RETRY_MAX_ATTEMPTS = '3'
$env:LAB_RETRY_FIXED_DELAY = '50ms'
New-Item -ItemType Directory -Force -Path '.gradle-user-home', '.tmp' | Out-Null
$env:GRADLE_USER_HOME = (Resolve-Path '.gradle-user-home').Path
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + (Resolve-Path '.tmp').Path
$project = 'retry-storm-stage5-check-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$baseUri = 'http://localhost:' + $env:SERVER_PORT
$appProcess = $null

function Start-LabApp($jarPath, $run) {
    $process = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jarPath) -PassThru -WindowStyle Hidden -RedirectStandardOutput "build/stage5-$run.stdout.log" -RedirectStandardError "build/stage5-$run.stderr.log"
    for ($i = 0; $i -lt 90; $i++) {
        try {
            $health = Invoke-RestMethod "$baseUri/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { return $process }
        } catch {
        }
        if ($process.HasExited) { throw '애플리케이션이 준비되기 전에 종료됐습니다.' }
        Start-Sleep -Seconds 1
    }
    if (-not $process.HasExited) { Stop-Process -Id $process.Id }
    throw '애플리케이션 health 확인 시간이 초과됐습니다.'
}


$env:PROMETHEUS_PORT = [string]$ports[4]
$resultDir = Join-Path (Get-Location) ('build/' + $project)
New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
$env:PROMETHEUS_TARGETS_FILE = Join-Path $resultDir 'targets.json'
$env:LOAD_RESULTS_DIR = $resultDir
$env:LOAD_BASE_URL = 'http://host.docker.internal:' + $env:SERVER_PORT
$env:LOAD_ITERATIONS = '12'
$env:LOAD_VUS = '2'
$env:LOAD_MAX_DURATION = '2m'
$env:LOAD_POLL_TIMEOUT_MS = '30000'
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($env:PROMETHEUS_TARGETS_FILE,
    ('[{"targets":["host.docker.internal:' + $env:SERVER_PORT + '"]}]'), $utf8)
$composeArgs = @('-p', $project, '-f', 'compose.yaml', '-f', 'compose.monitoring.yaml')
$promUri = 'http://localhost:' + $env:PROMETHEUS_PORT

function Query-Prometheus($query) {
    $response = Invoke-RestMethod ($promUri + '/api/v1/query?query=' + [uri]::EscapeDataString($query)) -TimeoutSec 5
    if ($response.status -ne 'success' -or $response.data.result.Count -ne 1) {
        throw ('지표가 없거나 여러 개입니다: ' + $query)
    }
    return [double]$response.data.result[0].value[1]
}

try {
    ./gradlew.bat --no-daemon bootJar
    if ($LASTEXITCODE -ne 0) { throw 'bootJar 빌드 실패' }
    docker compose @composeArgs up -d --wait
    if ($LASTEXITCODE -ne 0) {
        $logs = docker compose @composeArgs logs --no-color 2>&1 | Out-String
        [IO.File]::WriteAllText((Join-Path $resultDir 'infrastructure.log'), $logs, $utf8)
        throw '관측 환경 기동 실패: 결과 디렉터리의 infrastructure.log를 확인하세요.'
    }
    $jar = Get-ChildItem 'build/libs/*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    $appProcess = Start-LabApp $jar.FullName 'observability'
    docker compose @composeArgs run --rm k6
    if ($LASTEXITCODE -ne 0) { throw 'k6 검증 임계값 실패' }
    $summary = Get-Content (Join-Path $resultDir 'summary.json') -Raw | ConvertFrom-Json
    if ($summary.metrics.lab_terminal.values.count -ne 12 -or $summary.metrics.lab_poll_timeouts.values.count -ne 0) {
        throw 'k6 종료 건수 또는 타임아웃 검증 실패'
    }
    $entries = Invoke-RestMethod "$baseUri/api/v1/dlq"
    if ($entries.Count -ne 3) { throw '예상 DLQ 3건과 다릅니다.' }
    $replayBody = @{ expectedVersion = $entries[0].version; failuresBeforeSuccess = 0 } | ConvertTo-Json
    $replayed = Invoke-RestMethod -Method Post "$baseUri/api/v1/dlq/$($entries[0].messageId)/reprocess" -ContentType 'application/json' -Body $replayBody
    if ($replayed.state -ne 'SUCCEEDED') { throw '재처리 검증 실패' }
    $verified = $false
    for ($i = 0; $i -lt 30; $i++) {
        try {
            $up = Query-Prometheus 'sum(up{job=~"retry-lab|rabbitmq"})'
            $attempts = Query-Prometheus 'sum(lab_processing_attempts_total{path="CONSUME"})'
            $retries = Query-Prometheus 'sum(lab_retries_total{path="CONSUME"})'
            $stored = Query-Prometheus 'sum(lab_dlq_store_total{outcome="INSERTED"})'
            $replayCount = Query-Prometheus 'sum(lab_processing_duration_seconds_count{path="REPLAY",outcome="SUCCEEDED"})'
            $queueDepth = Query-Prometheus 'sum(rabbitmq_queue_messages{queue="retry.lab.work.v4"})'
            if ($up -eq 2 -and $attempts -eq 27 -and $retries -eq 15 -and $stored -eq 3 -and $replayCount -eq 1 -and $queueDepth -eq 0) {
                $verified = $true
                break
            }
        } catch {
        }
        Start-Sleep -Seconds 1
    }
    if (-not $verified) { throw 'Prometheus 수집 지표가 예상값과 일치하지 않습니다.' }
    $evidence = [ordered]@{
        checkedAt = (Get-Date -Format o)
        sourceCommit = (git rev-parse HEAD)
        scope = '5단계 기능 smoke; 전략 비교 실험 아님'
        mode = 'FIXED'
        fixedDelayMs = 50
        health = 'UP'
        prometheusTargetsUp = $up
        k6Iterations = 12
        terminalMessages = $summary.metrics.lab_terminal.values.count
        pollTimeouts = $summary.metrics.lab_poll_timeouts.values.count
        consumeAttempts = $attempts
        consumeRetries = $retries
        dlqInserted = $stored
        replaySucceeded = $replayCount
        workQueueDepthAfterDrain = $queueDepth
    }
    $json = $evidence | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $resultDir 'verification.json'), $json, $utf8)
    Write-Output $json
    Write-Output ('RESULT_DIRECTORY=' + $resultDir)
} finally {
    if ($null -ne $appProcess -and -not $appProcess.HasExited) {
        Stop-Process -Id $appProcess.Id
    }
    docker compose @composeArgs down -v
    if ($LASTEXITCODE -ne 0) { throw "검증용 Compose 정리 실패: $project" }
}
