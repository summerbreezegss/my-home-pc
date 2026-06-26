# Run as Administrator.
# Publishes PcAgent as a self-contained single-file exe and registers it as a Windows Service.

$publishDir = "C:\Program Files\PcAgent"
$serviceName = "PcAgent"

dotnet publish "$PSScriptRoot\..\PcAgent\PcAgent.csproj" -c Release -o $publishDir

if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
    Write-Output "Service '$serviceName' already exists. Stopping and removing it first..."
    Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
    sc.exe delete $serviceName
    Start-Sleep -Seconds 2
}

sc.exe create $serviceName binPath= "`"$publishDir\PcAgent.exe`"" start= auto
sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/30000/restart/60000
sc.exe description $serviceName "MyHomePC agent: accepts authenticated shutdown/restart commands from the home relay."

New-NetFirewallRule -DisplayName "PcAgent Inbound" -Direction Inbound -Protocol TCP `
    -LocalPort 8585 -Action Allow -Profile Private -ErrorAction SilentlyContinue

Write-Output ""
Write-Output "PcAgent published to $publishDir."
Write-Output "IMPORTANT: edit $publishDir\appsettings.json and set AGENT_SHARED_SECRET to match"
Write-Output "the relay's AGENT_SHARED_SECRET value, then run: Start-Service $serviceName"
Write-Output "(the service has NOT been started yet - start it only after setting the real secret)"
Write-Output "(editing the secret again later just needs the file saved - no restart needed, it reloads automatically)"
