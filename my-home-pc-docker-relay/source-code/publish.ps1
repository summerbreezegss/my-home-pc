# No Docker required for this part - just assembles the minimal folder to upload to your NAS.
# Produces ..\docker\ (one level up, alongside this source-code\ folder) containing exactly what
# the container build/run needs: Dockerfile, docker-compose.yml, requirements.txt, app\, and the
# devices.example.yaml/.env.example templates for convenience.
# Never touches docker\devices.yaml or docker\.env - those hold your real secrets and are
# yours to create/maintain (see ..\README.md's "First-time setup").
#
# Re-run this after any code change, then re-upload just the docker\ folder's contents to your
# NAS and rebuild the container there - the same "verify with curl after every change" habit
# from the README applies here too.

$outDir = "$PSScriptRoot\..\docker"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Copy-Item "$PSScriptRoot\Dockerfile" "$outDir\Dockerfile" -Force
Copy-Item "$PSScriptRoot\docker-compose.yml" "$outDir\docker-compose.yml" -Force
Copy-Item "$PSScriptRoot\requirements.txt" "$outDir\requirements.txt" -Force
Copy-Item "$PSScriptRoot\devices.example.yaml" "$outDir\devices.example.yaml" -Force
Copy-Item "$PSScriptRoot\.env.example" "$outDir\.env.example" -Force

# Remove first so a renamed/deleted source file doesn't linger as a stale copy.
Remove-Item "$outDir\app" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item "$PSScriptRoot\app" "$outDir\app" -Recurse -Force
Get-ChildItem "$outDir\app" -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force

Write-Output "Published to $outDir"
Write-Output "Upload that folder's contents to your NAS. If devices.yaml/.env don't exist yet there,"
Write-Output "rename/edit the devices.example.yaml/.env.example templates already sitting alongside them."
