<#
.SYNOPSIS
    Sets up a Windows PC to build and run the Job Assistant app locally.

.DESCRIPTION
    Safe to run more than once: every step checks first and skips what is already done.
      1. Installs Git and JDK 21 (Temurin) with winget if they are missing.
      2. Installs the Android command-line tools into the SDK folder (an existing Android Studio
         SDK is reused), checked against Google's published SHA-1.
      3. Installs the SDK packages this project builds against and accepts their licences.
      4. Clones the repository (or uses the one this script is in) and writes local.properties.
      5. Creates an API 36 emulator, builds the dev app, starts the emulator, installs and opens it.

    Run from PowerShell (no admin needed, except winget may ask once):
        Set-ExecutionPolicy -Scope Process Bypass
        .\scripts\setup\setup-windows.ps1

    Or, without a clone yet, download this file and run it from the folder that should hold the
    clone. Re-run after it finishes if winget installed Git or Java (new PATH entries need a new
    window). Keep the package list in step with gradle/libs.versions.toml (compileSdk 37,
    targetSdk 36) and the Android CI workflow.

.PARAMETER SkipEmulator
    Build and install only; use when a phone is connected with USB debugging on.
#>
param(
    [switch]$SkipEmulator,
    [string]$CloneDir = (Join-Path (Get-Location) 'job-assistant')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with the progress bar

# Versions and packages. Keep in step with the build (see .DESCRIPTION).
$RepoUrl = 'https://github.com/coderArchitech/job-assistant.git'
$CmdlineToolsZip = 'commandlinetools-win-16111833_latest.zip'
$CmdlineToolsSha1 = '57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d'
$SystemImage = 'system-images;android-36;google_apis;x86_64'
$SdkPackages = @(
    'platform-tools',
    'platforms;android-37.0',
    'platforms;android-36',
    'build-tools;36.0.0',
    'emulator',
    $SystemImage
)
$AvdName = 'JobAssistant_API36'
$AppId = 'com.coderarchitech.jobassistant.dev'

function Write-Step([string]$Message) { Write-Host "`n==> $Message" -ForegroundColor Cyan }

function Test-Command([string]$Name) { [bool](Get-Command $Name -ErrorAction SilentlyContinue) }

function Install-WithWinget([string]$Id, [string]$What) {
    if (-not (Test-Command 'winget')) {
        throw "$What is missing and winget is not available. Install $What yourself, then re-run."
    }
    winget install --id $Id --exact --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) { throw "winget could not install $What (exit $LASTEXITCODE)." }
}

# Runs a native program and returns its output (stdout and stderr) as text. Windows PowerShell 5.1
# turns a native program's stderr into errors, which $ErrorActionPreference = 'Stop' makes fatal,
# so the preference is relaxed for the call only.
function Invoke-Native([string]$Exe, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $previous }
}

function Update-PathFromRegistry {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machine;$user"
}

# 1. Git and JDK 21 ------------------------------------------------------------------------------
Write-Step 'Checking Git'
if (-not (Test-Command 'git')) {
    Install-WithWinget 'Git.Git' 'Git'
    Update-PathFromRegistry
}
Invoke-Native 'git' @('--version')

Write-Step 'Checking JDK 21'
# Gradle uses JAVA_HOME before PATH, so JAVA_HOME itself must point at a JDK 21.
function Get-JavaMajor([string]$JavaHome) {
    $exe = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path $exe)) { return 0 }
    $line = Invoke-Native $exe @('-version') | Select-Object -First 1
    if ("$line" -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}
function Find-Jdk21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'C:\Program Files\Android\Android Studio\jbr'   # Android Studio's bundled JDK
    $candidates += (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue).FullName
    $candidates += (Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue).FullName
    $candidates += (Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue).FullName
    foreach ($c in $candidates) { if ($c -and (Get-JavaMajor $c) -eq 21) { return $c } }
    return $null
}
$jdk = Find-Jdk21
if (-not $jdk) {
    Install-WithWinget 'EclipseAdoptium.Temurin.21.JDK' 'JDK 21'
    $jdk = Find-Jdk21
}
if (-not $jdk) { throw 'JDK 21 was not found after installing it. Open a new PowerShell window and re-run.' }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
Write-Host "JAVA_HOME: $jdk"

# 2. Android SDK command-line tools --------------------------------------------------------------
Write-Step 'Checking the Android SDK'
$Sdk = $env:ANDROID_HOME
if (-not $Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }   # Android Studio's default
New-Item -ItemType Directory -Force -Path $Sdk | Out-Null
$env:ANDROID_HOME = $Sdk
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $Sdk, 'User')
Write-Host "SDK folder: $Sdk"

$SdkManager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $SdkManager)) {
    Write-Step 'Downloading the Android command-line tools'
    $zip = Join-Path $env:TEMP $CmdlineToolsZip
    Invoke-WebRequest "https://dl.google.com/android/repository/$CmdlineToolsZip" -OutFile $zip
    $sha1 = (Get-FileHash $zip -Algorithm SHA1).Hash.ToLowerInvariant()
    if ($sha1 -ne $CmdlineToolsSha1) {
        Remove-Item $zip
        throw "Checksum mismatch for $CmdlineToolsZip (got $sha1). Not installing it."
    }
    $unpack = Join-Path $env:TEMP 'jobassistant-cmdline-tools'
    Remove-Item $unpack -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive $zip -DestinationPath $unpack
    $target = Join-Path $Sdk 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    Move-Item (Join-Path $unpack 'cmdline-tools') $target
    Remove-Item $zip, $unpack -Recurse -Force
}

# 3. SDK packages --------------------------------------------------------------------------------
Write-Step 'Accepting SDK licences'
# sdkmanager reads one "y" per licence prompt.
('y' + "`n") * 20 | & $SdkManager --licenses --sdk_root=$Sdk | Out-Null

Write-Step "Installing SDK packages (first run downloads a few GB)"
& $SdkManager --sdk_root=$Sdk @SdkPackages
if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed (exit $LASTEXITCODE)." }
$env:Path = "$Sdk\platform-tools;$Sdk\emulator;$env:Path"

# 4. Repository and local.properties -------------------------------------------------------------
Write-Step 'Getting the code'
$scriptPath = $MyInvocation.MyCommand.Path   # empty when the script is piped, not run from a file
$repoFromScript = if ($scriptPath) { Join-Path (Split-Path -Parent $scriptPath) '..\..' } else { $null }
if ($repoFromScript -and (Test-Path (Join-Path $repoFromScript 'gradlew.bat'))) {
    $Repo = (Resolve-Path $repoFromScript).Path
} elseif (Test-Path (Join-Path $CloneDir 'gradlew.bat')) {
    $Repo = $CloneDir
} else {
    git clone $RepoUrl $CloneDir
    if ($LASTEXITCODE -ne 0) { throw 'git clone failed.' }
    $Repo = $CloneDir
}
Write-Host "Repository: $Repo"

# local.properties is untracked; Java properties need backslashes and the drive colon escaped.
$escaped = $Sdk.Replace('\', '\\').Replace(':', '\:')
Set-Content -Path (Join-Path $Repo 'local.properties') -Value "sdk.dir=$escaped" -Encoding ASCII

# 5. Emulator, build, install, launch ------------------------------------------------------------
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
if (-not $SkipEmulator) {
    Write-Step "Creating the emulator $AvdName"
    $AvdManager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
    $existing = & $AvdManager list avd -c
    if ($existing -notcontains $AvdName) {
        'no' | & $AvdManager create avd --name $AvdName --package $SystemImage --device 'pixel_8'
        if ($LASTEXITCODE -ne 0) { throw 'avdmanager could not create the emulator.' }
    }
}

Write-Step 'Building the dev app (the first build downloads Gradle and dependencies)'
Push-Location $Repo
try {
    & .\gradlew.bat assembleDevDebug
    if ($LASTEXITCODE -ne 0) { throw 'The build failed; see the Gradle output above.' }

    if (-not $SkipEmulator) {
        $running = (& $Adb devices) -match 'emulator-\d+\s+device'
        if (-not $running) {
            Write-Step 'Starting the emulator'
            $emulatorExe = Join-Path $Sdk 'emulator\emulator.exe'
            $check = (Invoke-Native $emulatorExe @('-accel-check')) -join "`n"
            if ($check -notmatch 'is installed and usable') {
                Write-Warning ("Emulator acceleration is not available. Turn on 'Windows Hypervisor " +
                    "Platform' (Windows Features) or install the Android Emulator hypervisor driver, " +
                    "reboot, and re-run. Details:`n$check")
            }
            Start-Process $emulatorExe -ArgumentList @('-avd', $AvdName, '-no-snapshot-save')
            & $Adb wait-for-device
            do { Start-Sleep -Seconds 3 } until ((Invoke-Native $Adb @('shell', 'getprop', 'sys.boot_completed')) -match '^1$')
        }
    }

    Write-Step 'Installing and opening the app'
    & .\gradlew.bat installDevDebug
    if ($LASTEXITCODE -ne 0) { throw 'Install failed. Is an emulator running or a phone connected?' }
    & $Adb shell monkey -p $AppId -c android.intent.category.LAUNCHER 1 | Out-Null
} finally {
    Pop-Location
}

Write-Host "`nDone. The dev app ($AppId) is installed and open." -ForegroundColor Green
Write-Host 'Next time: start the emulator from Android Studio or with'
Write-Host "  emulator -avd $AvdName"
Write-Host 'then run .\gradlew.bat installDevDebug from the repository.'
