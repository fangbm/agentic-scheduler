param(
    [Parameter(Mandatory)][string]$Python,
    [string]$OpenSsl = 'C:\Program Files\Git\usr\bin\openssl.exe',
    [string]$Avd = 'temvio-d90205-acceptance',
    [string]$PostgresContainer = 'temvio-d9-02-05-postgres'
)
$ErrorActionPreference = 'Stop'
if (-not $env:SYNC_TEST_DATABASE_URL) { throw 'Disposable PostgreSQL configuration is required.' }
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
$root = Join-Path $repo "build\d90205-platform\run-$([guid]::NewGuid())"
New-Item -ItemType Directory -Force -Path $root | Out-Null
$env:D9_PLATFORM_DIRECTORY = $root
$env:SYNC_DATABASE_URL = $env:SYNC_TEST_DATABASE_URL
$env:SYNC_DATABASE_USER = $env:SYNC_TEST_DATABASE_USER
$env:SYNC_DATABASE_PASSWORD = $env:SYNC_TEST_DATABASE_PASSWORD
$env:SYNC_PORT = '18444'
$env:SYNC_BIND_HOST = '127.0.0.1'
$env:SYNC_ADMIN_TOKEN = 'd90205-disposable-admin'
[IO.File]::WriteAllText((Join-Path $root 'account.txt'), "d90205-platform-$([guid]::NewGuid())")
[IO.File]::WriteAllText((Join-Path $root 'space.txt'), "d90205-platform-$([guid]::NewGuid())")
& $OpenSsl req -x509 -newkey rsa:2048 -nodes -days 1 -keyout "$root\key.pem" -out "$root\cert.pem" -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' 2> "$root\certificate-generation.log"
if ($LASTEXITCODE) { throw 'Acceptance certificate generation failed.' }
function Gradle([string[]]$Tasks) {
    & "$env:JAVA_HOME\bin\java.exe" -classpath "$repo\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain --no-daemon --no-configuration-cache --max-workers=1 @Tasks
    if ($LASTEXITCODE) { throw "Gradle acceptance failed ($LASTEXITCODE)." }
}
function Start-Relay {
    $process = Start-Process "$env:JAVA_HOME\bin\java.exe" -ArgumentList @('-classpath', "$repo\server\sync\build\install\sync\lib\*", 'dev.agenticscheduler.server.sync.ApplicationKt') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root\server.log" -RedirectStandardError "$root\server-error.log"
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try { Invoke-RestMethod 'http://127.0.0.1:18444/health' | Out-Null; return $process } catch { Start-Sleep -Seconds 1 }
    }
    Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
    throw 'Acceptance relay did not become healthy.'
}
function Phase([string]$Name) {
    $env:D9_PLATFORM_PHASE = $Name
    Gradle -Tasks @(':shared:application:desktopTest', '--tests', '*D9PlatformRelayAcceptanceTest')
    Copy-Item -LiteralPath "$repo\shared\application\build\test-results\desktopTest\TEST-dev.agenticscheduler.application.sync.D9PlatformRelayAcceptanceTest.xml" -Destination "$root\$Name.xml"
}
$relay = $null; $proxy = $null; $emulator = $null
try {
    foreach ($port in @(18444,18445)) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Acceptance port $port is already occupied." } }
    Gradle -Tasks @(':server:sync:installDist')
    $relay = Start-Relay
    $proxy = Start-Process $Python -ArgumentList @("$repo\test-support\d9-02-05\tls_proxy.py", "$root\cert.pem", "$root\key.pem") -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root\proxy.log" -RedirectStandardError "$root\proxy-error.log"
    Start-Sleep -Seconds 2
    Phase 'seed'
    Stop-Process -Id $relay.Id
    $relay.WaitForExit()
    $relay = Start-Relay
    Phase 'resume'
    $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
    $emulator = Start-Process "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList @('-avd', $Avd, '-no-window', '-no-audio', '-no-snapshot-save', '-gpu', 'swiftshader_indirect', '-port', '5580') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root\emulator.log" -RedirectStandardError "$root\emulator-error.log"
    $ready = $false
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        if ($emulator.HasExited) { throw 'Dedicated acceptance emulator exited before boot; inspect emulator-error.log.' }
        if ((& $adb -s emulator-5580 shell getprop sys.boot_completed 2>$null) -eq '1') { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Dedicated Android acceptance emulator did not boot.' }
    & $adb -s emulator-5580 reverse tcp:18445 tcp:18445
    $certificate = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$root\cert.pem"))
    $account = [IO.File]::ReadAllText("$root\account.txt")
    $space = [IO.File]::ReadAllText("$root\space.txt")
    Remove-Item Env:\D9_PLATFORM_PHASE
    Gradle -Tasks @(':apps:android:assembleDebug', ':apps:android:assembleDebugAndroidTest')
    & $adb -s emulator-5580 install -r "$repo\apps\android\build\outputs\apk\debug\android-debug.apk"
    if ($LASTEXITCODE) { throw 'Acceptance target APK installation failed.' }
    & $adb -s emulator-5580 install -r "$repo\apps\android\build\outputs\apk\androidTest\debug\android-debug-androidTest.apk"
    if ($LASTEXITCODE) { throw 'Acceptance test APK installation failed.' }
    # This script owns a dedicated disposable emulator; reset only its acceptance app before enrollment.
    & $adb -s emulator-5580 shell pm clear dev.agenticscheduler.android
    foreach ($androidPhase in @('seed','resume')) {
        & $adb -s emulator-5580 shell am force-stop dev.agenticscheduler.android
        $result = & $adb -s emulator-5580 shell am instrument -w -e class dev.agenticscheduler.android.D9PlatformRelayInstrumentedTest -e d9AcceptancePhase $androidPhase -e d9AcceptanceCertificate $certificate -e d9AcceptanceAccount $account -e d9AcceptanceSpace $space dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner
        $result | Tee-Object -FilePath "$root\android-$androidPhase.txt"
        if ($LASTEXITCODE -or (($result -join "`n") -notmatch 'OK \(1 test\)')) { throw "Android $androidPhase instrumentation failed." }
    }
    $uiResult = & $adb -s emulator-5580 shell am instrument -w -e class dev.agenticscheduler.android.AgentConversationSyncControlsInstrumentedTest dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner
    $uiResult | Tee-Object -FilePath "$root\android-consent-ui.txt"
    if ($LASTEXITCODE -or (($uiResult -join "`n") -notmatch 'OK \([1-9][0-9]* tests?\)')) { throw 'Android consent UI instrumentation failed.' }
    Phase 'verify'
    $tables = & docker exec $PostgresContainer psql -U $env:SYNC_TEST_DATABASE_USER -d agentic_d90205 -At -c "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'"
    if ($LASTEXITCODE) { throw 'Opaque relay table scan failed.' }
    foreach ($table in $tables) {
        if ($table -notmatch '^[a-z_]+$') { throw 'Unexpected acceptance table identifier.' }
        $rows = & docker exec $PostgresContainer psql -U $env:SYNC_TEST_DATABASE_USER -d agentic_d90205 -At -c "SELECT to_jsonb(r)::text FROM $table r"
        if ($LASTEXITCODE) { throw 'Opaque relay scan query failed.' }
        foreach ($canary in @('D90205-PLATFORM-TITLE-CANARY','D90205-DESKTOP-MESSAGE-CANARY','D90205-DESKTOP-ASSISTANT-CANARY','D90205-ANDROID-MESSAGE-CANARY','CwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSo')) {
            if (($rows -join "`n").Contains($canary)) { throw "Opaque relay canary leaked in $table." }
        }
    }
    Write-Output 'D9 enrolled Windows Desktop Android round trip and public-table canary scan PASS.'
} finally {
    foreach ($process in @($relay,$proxy,$emulator)) { if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue } }
    Remove-Item Env:\D9_PLATFORM_PHASE -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$root\key.pem" -ErrorAction SilentlyContinue
}
