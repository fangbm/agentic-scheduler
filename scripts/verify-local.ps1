param(
    [string[]]$GradleArgs = @('build', '--no-daemon')
)

$taskRoot = Split-Path $PSScriptRoot -Parent
$taskSdkRoot = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($taskSdkRoot)) { $taskSdkRoot = $env:ANDROID_SDK_ROOT }

if ([string]::IsNullOrWhiteSpace($taskSdkRoot)) {
    $taskLocalProperties = Join-Path $taskRoot 'local.properties'
    if (Test-Path $taskLocalProperties) {
        $taskSdkLine = Get-Content $taskLocalProperties | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
        if ($taskSdkLine) { $taskSdkRoot = $taskSdkLine.Substring('sdk.dir='.Length).Replace('\\', '\') }
    }
}

$taskJavaHome = $env:JAVA_HOME
$taskJava = if ($taskJavaHome) { Join-Path $taskJavaHome 'bin\java.exe' } else { $null }
if (-not $taskJava -or -not (Test-Path $taskJava)) {
    $taskJava = (Get-Command java -ErrorAction SilentlyContinue).Source
}

$taskJavaVersion = if ($taskJava) { (& $taskJava -version 2>&1 | Select-Object -First 1) } else { '' }
$taskJavaMajor = if ($taskJavaVersion -match 'version "(\d+)') { [int]$Matches[1] } else { 0 }
$taskPlatform = if ($taskSdkRoot) { Join-Path $taskSdkRoot 'platforms\android-37.0\android.jar' } else { $null }
$taskBuildTools = if ($taskSdkRoot) { Join-Path $taskSdkRoot 'build-tools\37.0.0' } else { $null }

$taskHasPlatform = $taskPlatform -and (Test-Path $taskPlatform)
$taskHasBuildTools = $taskBuildTools -and (Test-Path $taskBuildTools)

if ($taskJavaMajor -lt 17 -or -not $taskHasPlatform -or -not $taskHasBuildTools) {
    Write-Output 'VERIFY_FALLBACK=CI'
    if ($taskJavaMajor -lt 17) { Write-Output 'Missing JDK 17.' }
    if (-not $taskHasPlatform) { Write-Output 'Missing Android platform android-37.0.' }
    if (-not $taskHasBuildTools) { Write-Output 'Missing Android build-tools 37.0.0.' }
    exit 0
}

$env:JAVA_HOME = Split-Path (Split-Path $taskJava -Parent) -Parent
$env:ANDROID_HOME = $taskSdkRoot
$env:ANDROID_SDK_ROOT = $taskSdkRoot
& (Join-Path $taskRoot 'gradlew.bat') @GradleArgs
exit $LASTEXITCODE
