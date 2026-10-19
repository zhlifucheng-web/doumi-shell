param(
    [string]$JdkHome = $env:DOUMI_BUILD_JDK,
    [string]$WixHome = $env:DOUMI_WIX_HOME,
    [string]$Version = '',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Version) { $Version = (Get-Content -Raw (Join-Path $Root 'shell-front/public/version.json') | ConvertFrom-Json).version }
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Version must be x.y.z' }
$buildDirectory = $env:DOUMI_BUILD_DIR
if (-not $buildDirectory) { $buildDirectory = Join-Path $Root '.build' }
New-Item -ItemType Directory -Force -Path $buildDirectory | Out-Null
if (-not $JdkHome) {
    $candidates = @($env:JAVA_HOME)
    foreach ($folder in @('.build-tools/jdk', '.build-tools/ms-jdk')) {
        if (Test-Path (Join-Path $Root $folder)) { $candidates += (Get-ChildItem (Join-Path $Root $folder) -Directory).FullName }
    }
    $JdkHome = $candidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin/jpackage.exe')) } | Select-Object -First 1
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin/jpackage.exe'))) {
    throw 'A Windows x64 JDK 17 with jpackage is required on the build machine. Pass -JdkHome or set DOUMI_BUILD_JDK.'
}
if (-not $WixHome) {
    $localWix = Join-Path $Root '.build-tools/wix3/tools'
    if (Test-Path (Join-Path $localWix 'candle.exe')) { $WixHome = $localWix }
    elseif (Get-Command candle.exe -ErrorAction SilentlyContinue) { $WixHome = Split-Path (Get-Command candle.exe).Source }
}
if (-not $WixHome -or -not (Test-Path (Join-Path $WixHome 'light.exe'))) {
    throw 'WiX Toolset 3.14 binaries are required on the build machine. Pass -WixHome or set DOUMI_WIX_HOME.'
}
$previousJava = $env:JAVA_HOME
$previousPath = $env:PATH
try {
    $env:JAVA_HOME = $JdkHome
    $env:PATH = (Join-Path $JdkHome 'bin') + ';' + $WixHome + ';' + $previousPath
    if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'package-release.ps1') -Version $Version }
    $jarPath = Join-Path $Root 'release/doumi-shell.jar'
    if (-not (Test-Path -LiteralPath $jarPath)) { throw 'release/doumi-shell.jar does not exist' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jarArchive = [IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $versionEntry = $jarArchive.GetEntry('BOOT-INF/classes/static/version.json')
        if (-not $versionEntry) { throw 'JAR has no version.json; rebuild before packaging' }
        $versionReader = [IO.StreamReader]::new($versionEntry.Open())
        try { $jarVersion = ($versionReader.ReadToEnd() | ConvertFrom-Json).version } finally { $versionReader.Dispose() }
        if ($jarVersion -ne $Version) { throw "JAR version $jarVersion differs from MSI version $Version; rebuild before packaging" }
    } finally { $jarArchive.Dispose() }
    $inputDirectory = Join-Path $buildDirectory 'doumi-msi-input'
    New-Item -ItemType Directory -Force -Path $inputDirectory | Out-Null
    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $inputDirectory 'doumi-shell.jar') -Force
    Copy-Item -LiteralPath (Join-Path $Root 'README.md') -Destination $inputDirectory -Force
    Copy-Item -LiteralPath (Join-Path $Root 'LICENSE') -Destination $inputDirectory -Force
    Copy-Item -LiteralPath (Join-Path $Root 'docs') -Destination $inputDirectory -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'doumi-shell-windows.cmd') `
        -Destination (Join-Path $inputDirectory 'Start Doumi Shell (Java).cmd') -Force
    $resourceDirectory = Join-Path $buildDirectory 'jpackage-resources'
    & (Join-Path $PSScriptRoot 'prepare-msi-resources.ps1') -JdkHome $JdkHome -Destination $resourceDirectory
    $jpackageArgs = @(
        '--type', 'msi', '--name', 'Doumi Shell', '--app-version', $Version,
        '--install-dir', 'DoumiShell',
        '--vendor', 'Doumi', '--description', 'Doumi Shell SSH Console',
        '--input', $inputDirectory, '--main-jar', 'doumi-shell.jar',
        '--resource-dir', $resourceDirectory,
        '--main-class', 'org.springframework.boot.loader.JarLauncher',
        '--dest', (Join-Path $Root 'release'),
        '--java-options', '-Ddoumi.desktop=true', '--java-options', '-Djava.awt.headless=false',
        '--java-options', '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--java-options', '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
        '--java-options', '-Dfile.encoding=UTF-8',
        '--add-modules', 'java.se,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.zipfs,jdk.localedata',
        '--win-per-user-install', '--win-dir-chooser', '--win-shortcut', '--win-menu', '--win-shortcut-prompt',
        '--win-menu-group', 'Doumi Shell', '--win-upgrade-uuid', '86a987b4-1f52-4dcb-ae9c-87d53ee3ca21'
    )
    & (Join-Path $JdkHome 'bin/jpackage.exe') @jpackageArgs
    if ($LASTEXITCODE -ne 0) { throw 'MSI packaging failed' }
    $msiPath = Join-Path $Root "release/Doumi Shell-$Version.msi"
    if (-not (Test-Path -LiteralPath $msiPath)) { throw 'MSI output missing' }
    $hash = (Get-FileHash -LiteralPath $msiPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$msiPath.sha256" -Value "$hash  Doumi Shell-$Version.msi" -Encoding ascii
    Write-Host "MSI ready: $msiPath"
} finally {
    $env:JAVA_HOME = $previousJava
    $env:PATH = $previousPath
}
