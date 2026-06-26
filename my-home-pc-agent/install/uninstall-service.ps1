# Run as Administrator.

$serviceName = "PcAgent"

Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
sc.exe delete $serviceName
Remove-NetFirewallRule -DisplayName "PcAgent Inbound" -ErrorAction SilentlyContinue

Write-Output "PcAgent service and firewall rule removed. Published files at C:\Program Files\PcAgent were left in place."
