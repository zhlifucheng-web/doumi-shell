param([Parameter(Mandatory)][string]$MsiPath)
$ErrorActionPreference = 'Stop'
$MsiPath = (Resolve-Path -LiteralPath $MsiPath).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($MsiPath, 0)
function Read-Rows([string]$Query, [int]$Columns) {
    $view = $database.OpenView($Query)
    [void]$view.Execute()
    try {
        while ($row = $view.Fetch()) {
            $values = @(for ($i=1; $i -le $Columns; $i++) { $row.StringData($i) })
            ,$values
        }
    } finally { [void]$view.Close() }
}
$controls = @(Read-Rows 'SELECT `Control`, `Property` FROM `Control` WHERE `Dialog_` = ''ShortcutPromptDlg''' 2)
foreach ($pair in @(@('InstallDesktopShortcut','JP_INSTALL_DESKTOP_SHORTCUT'), @('InstallStartMenuShortcut','JP_INSTALL_STARTMENU_SHORTCUT'))) {
    if (-not ($controls | Where-Object { $_[0] -eq $pair[0] -and $_[1] -eq $pair[1] })) { throw "Missing shortcut checkbox: $($pair[0])" }
    $components = @(Read-Rows "SELECT ``Component`` FROM ``Component`` WHERE ``Condition`` = '$($pair[1])'" 1)
    if (-not $components.Count) { throw "Shortcut creation must follow checkbox: $($pair[1])" }
}
$checkbox = @(Read-Rows 'SELECT `Control` FROM `Control` WHERE `Dialog_` = ''ExitDialog'' AND `Property` = ''WIXUI_EXITDIALOGOPTIONALCHECKBOX''' 1)
if ($checkbox.Count -ne 1) { throw 'Missing finish checkbox control' }
$label = @(Read-Rows 'SELECT `Target` FROM `CustomAction` WHERE `Source` = ''WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT''' 1)
if ($label.Count -ne 1 -or $label[0][0] -ne 'Open Doumi Shell in browser now') { throw 'Missing finish checkbox label' }
$finish = @(Read-Rows 'SELECT `Condition` FROM `ControlEvent` WHERE `Dialog_` = ''ExitDialog'' AND `Control_` = ''Finish'' AND `Event` = ''DoAction'' AND `Argument` = ''DoumiLaunchAfterInstall''' 1)
if ($finish.Count -ne 1) { throw 'Missing successful finish launch event' }
$condition = [string]$finish[0][0]
$action = @(Read-Rows 'SELECT `Type`, `Source`, `Target` FROM `CustomAction` WHERE `Action` = ''DoumiLaunchAfterInstall''' 3)
if ($action.Count -ne 1 -or $action[0][1] -ne 'WixCA' -or $action[0][2] -ne 'WixShellExec' -or [int]$action[0][0] -ne 65) { throw 'Launch must use the interactive user shell without blocking installation' }
foreach ($table in @('InstallUISequence','InstallExecuteSequence')) {
    if (@(Read-Rows "SELECT ``Action`` FROM ``$table`` WHERE ``Action`` = 'DoumiLaunchAfterInstall'" 1).Count) { throw "Launch must not be scheduled in $table" }
}
$target = @(Read-Rows 'SELECT `Value` FROM `Property` WHERE `Property` = ''WixShellExecTarget''' 1)
if ($target[0][0] -ne '[INSTALLDIR]Doumi Shell.exe') { throw 'Incorrect launch target' }
# Evaluate installer conditions only. Never install or execute the launch action.
$session = $installer.OpenPackage($MsiPath, 1)
$session.Property('Installed') = ''
$session.Property('REMOVE') = ''
$session.Property('WIXUI_EXITDIALOGOPTIONALCHECKBOX') = '1'
if ($session.EvaluateCondition($condition) -ne 1) { throw 'Checked first install/upgrade must allow launch' }
$session.Property('WIXUI_EXITDIALOGOPTIONALCHECKBOX') = ''
if ($session.EvaluateCondition($condition) -ne 0) { throw 'Unchecked finish must not launch' }
$session.Property('WIXUI_EXITDIALOGOPTIONALCHECKBOX') = '1'
$session.Property('Installed') = '1'
if ($session.EvaluateCondition($condition) -ne 0) { throw 'Maintenance must not launch' }
$session.Property('Installed') = ''
$session.Property('REMOVE') = 'ALL'
if ($session.EvaluateCondition($condition) -ne 0) { throw 'Removal must not launch' }
Write-Output 'PASS: shortcut choices, finish checkbox conditions, user-shell target, and absence of automatic launch in install sequences (no installation performed).'
$uninstall = @(Read-Rows 'SELECT `Target`, `Arguments` FROM `Shortcut` WHERE `Shortcut` = ''DoumiUninstallLink''' 2)
if ($uninstall.Count -ne 1 -or $uninstall[0][0] -ne '[SystemFolder]msiexec.exe' -or $uninstall[0][1] -ne '/x [ProductCode]') { throw 'Missing current-product uninstall shortcut' }
$feature = @(Read-Rows 'SELECT `Feature_` FROM `FeatureComponents` WHERE `Component_` = ''DoumiUninstallShortcut''' 1)
if ($feature.Count -ne 1) { throw 'Uninstall shortcut must be installed with the product' }
Write-Output 'PASS: uninstall shortcut targets the current MSI product (no uninstall performed).'
