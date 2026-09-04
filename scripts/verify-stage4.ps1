$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$listeners = @()
try {
    for ($i = 0; $i -lt 4; $i++) {
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
$project = 'retry-storm-stage4-check-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$baseUri = 'http://localhost:' + $env:SERVER_PORT
$appProcess = $null

function Start-LabApp($jarPath, $run) {
    $process = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jarPath) -PassThru -WindowStyle Hidden -RedirectStandardOutput "build/stage4-$run.stdout.log" -RedirectStandardError "build/stage4-$run.stderr.log"
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

try {
    ./gradlew.bat --no-daemon bootJar
    if ($LASTEXITCODE -ne 0) { throw 'bootJar 빌드 실패' }
    docker compose -p $project up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Compose 기동 실패' }
    $jar = Get-ChildItem 'build/libs/*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    $appProcess = Start-LabApp $jar.FullName 'before-restart'
    $body = @{ payload = '재시작 내구성 합성 검증'; failuresBeforeSuccess = 3 } | ConvertTo-Json
    $published = Invoke-RestMethod -Method Post "$baseUri/api/v1/messages" -ContentType 'application/json' -Body $body
    $id = $published.messageId
    $entry = $null
    for ($i = 0; $i -lt 100; $i++) {
        try {
            $entry = Invoke-RestMethod "$baseUri/api/v1/dlq/$id"
            if ($entry.state -eq 'PENDING') { break }
        } catch {
        }
        Start-Sleep -Milliseconds 100
    }
    if ($null -eq $entry -or $entry.state -ne 'PENDING' -or $entry.originalAttempts -ne 3) {
        throw '최종 실패가 DLQ에 정확히 저장되지 않았습니다.'
    }
    Stop-Process -Id $appProcess.Id
    Wait-Process -Id $appProcess.Id -ErrorAction SilentlyContinue
    $appProcess = Start-LabApp $jar.FullName 'after-restart'
    $restored = Invoke-RestMethod "$baseUri/api/v1/dlq/$id"
    if ($restored.state -ne 'PENDING' -or $restored.version -ne $entry.version) {
        throw '재시작 후 DLQ 상태가 보존되지 않았습니다.'
    }
    $replayBody = @{ expectedVersion = $restored.version; failuresBeforeSuccess = 0 } | ConvertTo-Json
    $replayed = Invoke-RestMethod -Method Post "$baseUri/api/v1/dlq/$id/reprocess" -ContentType 'application/json' -Body $replayBody
    if ($replayed.state -ne 'SUCCEEDED' -or $replayed.replayAttempts -ne 1 -or $replayed.reprocessCount -ne 1) {
        throw '재처리가 예상대로 완료되지 않았습니다.'
    }
    $conflictStatus = 0
    try {
        Invoke-RestMethod -Method Post "$baseUri/api/v1/dlq/$id/reprocess" -ContentType 'application/json' -Body $replayBody | Out-Null
    } catch {
        $conflictStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($conflictStatus -ne 409) { throw '이전 버전의 중복 재처리가 거부되지 않았습니다.' }
    Write-Output 'HEALTH=UP'
    Write-Output 'DLQ_BEFORE_RESTART=PENDING'
    Write-Output 'DLQ_AFTER_RESTART=PENDING'
    Write-Output 'ORIGINAL_ATTEMPTS=3'
    Write-Output ('REPLAY_STATE=' + $replayed.state)
    Write-Output ('REPLAY_ATTEMPTS=' + $replayed.replayAttempts)
    Write-Output ('REPROCESS_COUNT=' + $replayed.reprocessCount)
    Write-Output ('FINAL_VERSION=' + $replayed.version)
    Write-Output ('STALE_VERSION_HTTP=' + $conflictStatus)
} finally {
    if ($null -ne $appProcess -and -not $appProcess.HasExited) {
        Stop-Process -Id $appProcess.Id
    }
    docker compose -p $project down -v
    if ($LASTEXITCODE -ne 0) { throw "검증용 Compose 정리 실패: $project" }
}
