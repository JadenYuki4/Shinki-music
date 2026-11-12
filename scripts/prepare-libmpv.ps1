# SPDX-License-Identifier: GPL-3.0-or-later
# Reproduce the Windows x64 native dependency without installing Limusic.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$cache = New-Item -ItemType Directory -Force (Join-Path $root '.tmp/libmpv-build')
$target = New-Item -ItemType Directory -Force (Join-Path $root 'desktopApp/resources/windows-x64')
$archive = Join-Path $cache.FullName 'limusic.msi'
$dll = Join-Path $target.FullName 'libmpv-2.dll'
$dllHash = '5C876D79E070529128331591B48F87846FB30557F19C11280DF9C6EE9B6DBAFA'
if ((Test-Path -LiteralPath $dll) -and (Get-FileHash $dll).Hash -eq $dllHash) { return }
Invoke-WebRequest 'https://github.com/SimoHypers/limusic/releases/download/v1.3.0/limusic_1.3.0_x64_en-US.msi' -OutFile $archive
if ((Get-FileHash $archive).Hash -ne '3B6B37B6CF1DEE6FB7D83EFF2F63E070ABE704DAC433CB15C456D297F642A29E') {
    throw 'Limusic package SHA-256 mismatch'
}
$unpacked = Join-Path $cache.FullName 'image'
$arguments = '/a "{0}" /qn TARGETDIR="{1}"' -f $archive, $unpacked
$process = Start-Process msiexec.exe -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "MSI extraction failed ($($process.ExitCode))" }
$source = Join-Path $unpacked 'PFiles/limusic/libmpv-2.dll'
if ((Get-FileHash $source).Hash -ne $dllHash) { throw 'libmpv SHA-256 mismatch' }
Copy-Item -LiteralPath $source -Destination $dll
