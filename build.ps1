$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $bundledJdk = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath "$bundledJdk\bin\java.exe") { $env:JAVA_HOME = $bundledJdk }
}
if (-not (Test-Path -LiteralPath "$PSScriptRoot\local.properties")) {
    $sdkPath = "$env:LOCALAPPDATA\Android\Sdk".Replace('\', '/').Replace(':', '\:')
    Set-Content -LiteralPath "$PSScriptRoot\local.properties" -Value "sdk.dir=$sdkPath" -Encoding ascii
}
if (-not (Test-Path -LiteralPath "$env:USERPROFILE\.android-auto-music-restart-signing\signing.properties")) {
    throw '개인 서명 키 설정이 없습니다. README의 서명 키 위치를 확인하세요.'
}
& .\gradlew.bat :app:assembleRelease :app:assembleReleaseAndroidTest :app:testReleaseUnitTest :app:lintRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "빌드/검증 실패 ($LASTEXITCODE)" }
