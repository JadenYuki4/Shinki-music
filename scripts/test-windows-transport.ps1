$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$sessionType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
function Await-WinRT($operation, $type) {
    $method = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq "AsTask" -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -like 'IAsyncOperation?1' } | Select-Object -First 1
    $task = $method.MakeGenericMethod($type).Invoke($null,@($operation))
    if (!$task.Wait(5000)) { throw "Windows media API timed out" }
    $task.Result
}
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = (Resolve-Path "$PSScriptRoot/../desktopApp/resources/windows-x64/ShinkiTransport.exe").Path
$start.UseShellExecute = $false
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.CreateNoWindow = $true
$proc = [Diagnostics.Process]::Start($start)
try {
    $ready = $proc.StandardOutput.ReadLineAsync()
    if (!$ready.Wait(5000) -or $ready.Result -ne "Ready") { throw "Media session failed to start" }
    $title = "Shinki QA " + [guid]::NewGuid()
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($title))
    $proc.StandardInput.WriteLine("Playing`t$encoded`t")
    $manager = Await-WinRT ($sessionType::RequestAsync()) $sessionType
    $propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
    for ($i=0; $i -lt 20 -and !$found; $i++) {
        foreach ($session in $manager.GetSessions()) {
            $props = Await-WinRT ($session.TryGetMediaPropertiesAsync()) $propsType
            if ($props.Title -eq $title) { $found = $session; break }
        }
        Start-Sleep -Milliseconds 200
    }
    if (!$found) { throw "Shinki session absent from Windows" }
    foreach ($pair in @("Pause:TryPauseAsync","Play:TryPlayAsync","Next:TrySkipNextAsync","Previous:TrySkipPreviousAsync")) {
        $name,$method = $pair.Split(":")
        if (!(Await-WinRT ($found.$method()) ([bool]))) { throw "$name rejected" }
        $read = $proc.StandardOutput.ReadLineAsync()
        if (!$read.Wait(5000) -or $read.Result -ne $name) { throw "$name not delivered" }
        if ($name -eq "Pause") { $proc.StandardInput.WriteLine("Paused`t$encoded`t"); Start-Sleep -Milliseconds 200 }
        Write-Output "$name OK"
    }
} finally { $proc.StandardInput.Close(); if (!$proc.WaitForExit(3000)) { $proc.Kill() } }


