param([Parameter(Mandatory)][string]$MsiPath)
$ErrorActionPreference = 'Stop'
$MsiPath = (Resolve-Path -LiteralPath $MsiPath).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($MsiPath, 0)
foreach ($table in @('InstallUISequence', 'InstallExecuteSequence')) {
    $sequence = @{}
    $view = $database.OpenView("SELECT ``Action``, ``Sequence`` FROM ``$table``")
    $view.Execute()
    while ($row = $view.Fetch()) { $sequence[$row.StringData(1)] = $row.IntegerData(2) }
    $view.Close()
    if ($sequence.FindRelatedProducts -ge $sequence.AppSearch -or $sequence.AppSearch -ge $sequence.DoumiRestoreUserInstallDir -or $sequence.DoumiRestoreUserInstallDir -ge $sequence.CostInitialize) {
        throw "Directory discovery is ordered incorrectly in $table"
    }
    if ($table -eq 'InstallExecuteSequence' -and $sequence.DoumiRestoreMachineInstallDir -ge $sequence.RemoveExistingProducts) {
        throw 'The old directory must be read before the previous product is removed.'
    }
}
# OpenPackage + immediate AppSearch/type-51 actions only: no installation,
# registration, file replacement, uninstall, or execute sequence is performed.
$code = '{' + [Guid]::NewGuid().ToString().ToUpperInvariant() + '}'
$keyPath = "Software\Microsoft\Windows\CurrentVersion\Uninstall\$code"
$original = Join-Path ([IO.Path]::GetTempPath()) ('Doumi upgrade fixture ' + [Guid]::NewGuid().ToString() + '\Old install\')
$fixture = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey($keyPath)
try {
    $fixture.SetValue('InstallLocation', $original, [Microsoft.Win32.RegistryValueKind]::String)
    $fixture.Close()
    $session = $installer.OpenPackage($MsiPath, 1)
    $session.Property('JP_UPGRADABLE_FOUND') = $code
    if ($session.DoAction('AppSearch') -ne 1) { throw 'AppSearch failed' }
    if ($session.Property('DOUMI_PREVIOUS_USER_DIR') -ne $original) { throw 'Original directory was not discovered' }
    if ($session.DoAction('DoumiRestoreUserInstallDir') -ne 1) { throw 'Directory restoration failed' }
    if ($session.Property('INSTALLDIR') -ne $original) { throw 'INSTALLDIR does not match the original directory' }
    Write-Output 'PASS: UI/execute sequencing, original directory lookup, and INSTALLDIR restoration (no installation performed).'
} finally {
    $fixture.Close()
    [Microsoft.Win32.Registry]::CurrentUser.DeleteSubKey($keyPath, $false)
}
