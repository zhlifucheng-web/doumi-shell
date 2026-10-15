$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolsDirectory = Join-Path $Root '.build-tools'
New-Item -ItemType Directory -Force -Path $toolsDirectory | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-VerifiedArchive([string]$Url, [string]$Filename, [string]$ExpectedHash, [string]$Destination) {
    $archive = Join-Path $toolsDirectory $Filename
    if (-not (Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $ExpectedHash) {
        Write-Host "Downloading $Filename"
        Invoke-WebRequest -Uri $Url -OutFile $archive -UseBasicParsing
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $ExpectedHash) { throw "Checksum mismatch: $Filename" }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $toolsDirectory $Destination) -Force
}

Get-VerifiedArchive `
    'https://download.visualstudio.microsoft.com/download/pr/a9672056-0a4d-425d-8488-f481ee56319c/ac8d1216f7d03a27c018bc960f947721/microsoft-jdk-17.0.20.1-windows-x64.zip' `
    'microsoft-jdk17.zip' '3d9006956fc8af5601cd24ffc4f468bef48279c7ebd8171b9bdf90d0aabfbf1f' 'ms-jdk'
Get-VerifiedArchive `
    'https://api.nuget.org/v3-flatcontainer/wix/3.14.1/wix.3.14.1.nupkg' `
    'wix3.zip' '15d50463c73dce31fbea5440ac33af47e92d54d4188166d207e9e39577b8fe0f' 'wix3'
Write-Host 'Build tools ready. Run ./scripts/package-msi.ps1'
