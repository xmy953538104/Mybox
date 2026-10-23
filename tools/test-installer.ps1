$ErrorActionPreference = 'Stop'
$testRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testOutput = Join-Path ([IO.Path]::GetTempPath()) ('mybox-installer-classes-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testOutput | Out-Null
try {
    $installerSources = Join-Path $testRepo 'Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/installer'
    & javac -encoding UTF-8 -d $testOutput (Join-Path $installerSources 'AtomicPackageDirectory.java') (Join-Path $installerSources 'InstallSessionState.java') (Join-Path $installerSources 'PackageSetValidator.java') (Join-Path $testRepo 'tests/core/InstallerSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Installer self-check compilation failed' }
    & java -ea -cp $testOutput InstallerSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Installer self-check failed' }
} finally {
    $checkedOutput = [IO.Path]::GetFullPath($testOutput)
    $checkedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $checkedOutput.StartsWith($checkedTemp, [StringComparison]::OrdinalIgnoreCase) -or -not ([IO.Path]::GetFileName($checkedOutput)).StartsWith('mybox-installer-classes-')) { throw 'Unexpected test cleanup path' }
    Remove-Item -LiteralPath $checkedOutput -Recurse -Force
}
