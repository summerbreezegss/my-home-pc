# No admin rights needed - just builds the distributable folder.
# Produces ..\Installer\"Install PcAgent.exe" (one level up, alongside this source-code\ folder) -
# the only thing meant to be run directly from there.
# It bundles the agent (Installer\PcAgent\) and the uninstaller (Installer\Uninstaller\) and copies
# both into the install directory itself, so "uninstall" only shows up once something is installed.
#
# That Installer\ folder is the only thing end users need - share just that folder with them.
# (everything in this source-code\ folder is only needed if you're editing this project.)

$outDir = "$PSScriptRoot\..\Installer"

dotnet publish "$PSScriptRoot\PcAgent\PcAgent.csproj" -c Release -o "$outDir\PcAgent"
dotnet publish "$PSScriptRoot\PcAgentSetup\Uninstaller\Uninstaller.csproj" -c Release -o "$outDir\Uninstaller"
dotnet publish "$PSScriptRoot\PcAgentSetup\PcAgentSetup.csproj" -c Release -o $outDir

Write-Output ""
Write-Output "Published to $outDir"
Write-Output "Share just the 'Installer' folder with end users - double-click 'Install PcAgent.exe' in it."
