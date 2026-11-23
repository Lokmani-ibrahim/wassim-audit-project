$ErrorActionPreference = 'Stop'
$mobileRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $mobileRoot
$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$jdkPath = $env:NEOS_ANDROID_JAVA_HOME
if (-not $jdkPath) {
    $candidate = Get-ChildItem -LiteralPath 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' | Select-Object -First 1
    if ($candidate) { $jdkPath = $candidate.FullName }
}
if (-not $jdkPath -or -not (Test-Path -LiteralPath (Join-Path $jdkPath 'bin\java.exe'))) {
    throw 'Set NEOS_ANDROID_JAVA_HOME to the local JDK 17 installation.'
}
# Process-local only. Maven and global Java settings are untouched.
$env:JAVA_HOME = $jdkPath
$env:ANDROID_HOME = $sdkPath
$env:PATH = "$jdkPath\bin;$sdkPath\platform-tools;$env:PATH"
if (-not $env:ANDROID_SERIAL) { $env:ANDROID_SERIAL = 'emulator-5554' }
& "$sdkPath\platform-tools\adb.exe" reverse tcp:8081 tcp:8081
& "$sdkPath\platform-tools\adb.exe" reverse tcp:8080 tcp:8080
& "$sdkPath\platform-tools\adb.exe" reverse tcp:8082 tcp:8082
# Node resolves "localhost" to ::1 first, but adb reverse dials 127.0.0.1: make Metro bind IPv4.
if (-not $env:NODE_OPTIONS) { $env:NODE_OPTIONS = '--dns-result-order=ipv4first' }
# 8081 belongs to Keycloak. Metro must use a different port.
npx expo run:android --port 8082
exit $LASTEXITCODE
