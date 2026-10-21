param([switch]$SkipTests, [string]$Version = '')
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Version) {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Version must be x.y.z' }
    Set-Content -LiteralPath (Join-Path $Root 'shell-front/public/version.json') -Value ('{"version":"' + $Version + '"}') -Encoding ascii
}
$originalLocation = Get-Location
try {
    # 使用独立构建目录，避免 npm ci 与开发服务器/编辑器占用的 node_modules 冲突。
    $buildRoot = $env:DOUMI_BUILD_DIR
    if (-not $buildRoot) { $buildRoot = Join-Path $Root '.build' }
    $frontendBuild = Join-Path $buildRoot 'frontend'
    New-Item -ItemType Directory -Force -Path $frontendBuild | Out-Null
    # Help imports the repository guide; keep the same relative layout in the isolated build.
    Copy-Item -LiteralPath (Join-Path $Root 'docs') -Destination $buildRoot -Recurse -Force
    foreach ($entry in @('src', 'public', 'package.json', 'package-lock.json', 'vite.config.js', 'index.html')) {
        Copy-Item -Path (Join-Path $Root "shell-front/$entry") -Destination $frontendBuild -Recurse -Force
    }
    Set-Location $frontendBuild
    npm ci
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    $staticPath = [IO.Path]::GetFullPath((Join-Path $Root 'shell-end/src/main/resources/static'))
    $expectedStatic = [IO.Path]::GetFullPath((Join-Path $Root 'shell-end/src/main/resources')) + [IO.Path]::DirectorySeparatorChar
    if (-not $staticPath.StartsWith($expectedStatic, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid static directory' }
    if (Test-Path -LiteralPath $staticPath) { Remove-Item -LiteralPath $staticPath -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $staticPath | Out-Null
    Copy-Item -Path (Join-Path $frontendBuild 'dist/*') -Destination $staticPath -Recurse
    Set-Location (Join-Path $Root 'shell-end')
    $backendBuild = Join-Path $Root 'shell-end/target'
    $mavenArgs = @('clean', 'verify')
    if ($SkipTests) { $mavenArgs += '-DskipTests' }
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed' }
    $outputPath = $env:DOUMI_RELEASE_DIR
    if (-not $outputPath) { $outputPath = Join-Path $Root 'release' }
    $outputPath = [IO.Path]::GetFullPath($outputPath)
    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    Copy-Item -LiteralPath (Join-Path $backendBuild 'shell-end-1.0.0-SNAPSHOT.jar') -Destination (Join-Path $outputPath 'doumi-shell.jar') -Force
    foreach ($scriptName in @('run-windows.bat', 'run-linux.sh')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $scriptName) -Destination $outputPath -Force
    }
    # Windows checkouts can use CRLF; Bash launchers must always ship with LF and no BOM.
    $linuxScriptPath = Join-Path $outputPath 'run-linux.sh'
    $linuxScript = [IO.File]::ReadAllText($linuxScriptPath).Replace("`r`n", "`n")
    [IO.File]::WriteAllText($linuxScriptPath, $linuxScript, [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $Root 'shell-end/config/application.yml.example') -Destination (Join-Path $outputPath 'config.example.yml') -Force
    Copy-Item -LiteralPath (Join-Path $Root 'README.md') -Destination $outputPath -Force
    Copy-Item -LiteralPath (Join-Path $Root 'LICENSE') -Destination (Join-Path $outputPath 'LICENSE') -Force
    Copy-Item -LiteralPath (Join-Path $Root 'docs') -Destination $outputPath -Recurse -Force
    Write-Host "Release ready: $outputPath"
} finally { Set-Location $originalLocation }
