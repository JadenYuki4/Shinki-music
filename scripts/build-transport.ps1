$ErrorActionPreference = "Stop"
$framework = Join-Path $env:WINDIR "Microsoft.NET/Framework64/v4.0.30319"
$meta = Join-Path $env:WINDIR "System32/WinMetadata"
$refs = @("Foundation","Media","Storage") | ForEach-Object { "/r:$meta/Windows.$_.winmd" }
foreach ($name in @("System.Runtime","System.Runtime.InteropServices.WindowsRuntime")) {
    $dll = Get-ChildItem "$env:WINDIR/Microsoft.NET/assembly/GAC_MSIL/$name" -Filter "$name.dll" -Recurse | Select-Object -First 1
    $refs += "/r:$($dll.FullName)"
}
$src = (Resolve-Path "$PSScriptRoot/../desktopApp/native/ShinkiTransport.cs").Path
$out = [IO.Path]::GetFullPath("$PSScriptRoot/../desktopApp/resources/windows-x64/ShinkiTransport.exe")
& "$framework/csc.exe" /nologo /target:winexe "/out:$out" $refs $src
exit $LASTEXITCODE

