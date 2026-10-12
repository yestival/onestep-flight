param([switch]$WithEmulator)
$ErrorActionPreference = 'Stop'
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME','ANDROID_HOME','ANDROID_USER_HOME','ANDROID_EMULATOR_HOME','ANDROID_PREFS_ROOT','ANDROID_SDK_HOME','ANDROID_AVD_HOME')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
$toolRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../build/toolchain'))
function AssertToolPath($value) {
    $resolved = [IO.Path]::GetFullPath($value)
    if (!$resolved.StartsWith($toolRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Tool path escapes build/toolchain: $resolved" }
}
New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
function Download($url, $destination) {
    & curl.exe --fail --location --retry 3 --output $destination $url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
}
$jdk = Join-Path $toolRoot 'jdk'
if (!(Test-Path (Join-Path $jdk 'bin/java.exe'))) {
    $archive = Join-Path $toolRoot 'jdk21.zip'
    Download 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' $archive
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $toolRoot 'jdk-unpack') -Force
    $source = (Get-ChildItem (Join-Path $toolRoot 'jdk-unpack') -Directory | Select-Object -First 1).FullName
    AssertToolPath $source
    AssertToolPath $jdk
    Move-Item -LiteralPath $source -Destination $jdk
}
$sdk = Join-Path $toolRoot 'sdk'
$manager = Join-Path $sdk 'cmdline-tools/latest/bin/sdkmanager.bat'
if (!(Test-Path $manager)) {
    $archive = Join-Path $toolRoot 'commandline-tools.zip'
    Download 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' $archive
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a') { throw 'SDK archive checksum mismatch' }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $toolRoot 'sdk-unpack') -Force
    New-Item -ItemType Directory -Path (Join-Path $sdk 'cmdline-tools') -Force | Out-Null
    AssertToolPath (Join-Path $toolRoot 'sdk-unpack/cmdline-tools')
    AssertToolPath (Join-Path $sdk 'cmdline-tools/latest')
    Move-Item -LiteralPath (Join-Path $toolRoot 'sdk-unpack/cmdline-tools') -Destination (Join-Path $sdk 'cmdline-tools/latest')
}
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_PREFS_ROOT = $env:ANDROID_USER_HOME
$env:ANDROID_SDK_HOME = $toolRoot
$env:ANDROID_AVD_HOME = Join-Path $toolRoot 'avd'
1..100 | ForEach-Object { 'y' } | & $manager "--sdk_root=$sdk" --licenses
if ($LASTEXITCODE -ne 0) { throw 'SDK license setup failed' }
& $manager "--sdk_root=$sdk" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
if ($WithEmulator) {
    & $manager "--sdk_root=$sdk" 'emulator' 'system-images;android-36;google_apis;x86_64'
    if ($LASTEXITCODE -ne 0) { throw 'Emulator installation failed' }
    New-Item -ItemType Directory -Path $env:ANDROID_AVD_HOME -Force | Out-Null
    if (!(Test-Path (Join-Path $env:ANDROID_AVD_HOME 'OneStep_API_36.avd/config.ini'))) {
        'no' | & (Join-Path $sdk 'cmdline-tools/latest/bin/avdmanager.bat') create avd --name OneStep_API_36 --package 'system-images;android-36;google_apis;x86_64' --device pixel_6
        if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed' }
        $avdConfig = Join-Path $env:ANDROID_AVD_HOME 'OneStep_API_36.avd/config.ini'
        $lines = [IO.File]::ReadAllLines($avdConfig) -replace '^hw.lcd.width=.*$', 'hw.lcd.width=720' -replace '^hw.lcd.height=.*$', 'hw.lcd.height=1600' -replace '^hw.lcd.density=.*$', 'hw.lcd.density=280'
        [IO.File]::WriteAllLines($avdConfig, $lines, [Text.UTF8Encoding]::new($false))
    }
    if (!(Test-Path (Join-Path $env:ANDROID_AVD_HOME 'OneStep_API_36.avd/config.ini'))) { throw 'AVD configuration missing' }
    & (Join-Path $sdk 'emulator/emulator.exe') -accel-check
}
Write-Output "ANDROID_JAVA_HOME=$jdk"
Write-Output "ANDROID_HOME=$sdk"
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
