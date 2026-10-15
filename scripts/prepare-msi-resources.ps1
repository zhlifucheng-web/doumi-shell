param([Parameter(Mandatory)][string]$JdkHome, [Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$bytes = [IO.File]::ReadAllBytes((Join-Path $JdkHome 'jmods/jdk.jpackage.jmod'))
# JMOD has a four-byte header before the ZIP payload.
$stream = [IO.MemoryStream]::new()
$stream.Write($bytes, 4, $bytes.Length - 4)
$stream.Position = 0
$zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
try {
    $entry = $zip.GetEntry('classes/jdk/jpackage/internal/resources/main.wxs')
    if (-not $entry) { throw 'The JDK does not contain the expected jpackage WiX template.' }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $template = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$marker = '<InstallExecuteSequence>'
if (-not $template.Contains($marker)) { throw 'Unsupported jpackage WiX template.' }
$upgrade = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'msi/upgrade.xml') -Raw
$finish = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'msi/finish.xml') -Raw
$uninstall = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'msi/uninstall.xml') -Raw
$template = $template.Replace('<ComponentGroupRef Id="Files"/>', '<ComponentGroupRef Id="Files"/><ComponentRef Id="DoumiUninstallShortcut"/>')
$actions = @'
      <Custom Action="DoumiRestoreUserInstallDir" After="AppSearch">NOT Installed AND JP_UPGRADABLE_FOUND AND DOUMI_PREVIOUS_USER_DIR</Custom>
      <Custom Action="DoumiRestoreMachineInstallDir" After="DoumiRestoreUserInstallDir">NOT Installed AND JP_UPGRADABLE_FOUND AND NOT DOUMI_PREVIOUS_USER_DIR AND DOUMI_PREVIOUS_MACHINE_DIR</Custom>
'@
$template = $template.Replace($marker, "$upgrade`n$finish`n$uninstall`n$marker`n$actions")
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
[IO.File]::WriteAllText((Join-Path $Destination 'main.wxs'), $template, [Text.UTF8Encoding]::new($false))
