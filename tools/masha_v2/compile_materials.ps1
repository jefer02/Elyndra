<#
.SYNOPSIS
  Compiles Masha's Filament materials (tools/masha_v2/materials/*.mat) into
  app/src/main/assets/masha/materials/*.filamat and verifies them with matinfo.

.DESCRIPTION
  The matc used MUST be the one from the Filament release that matches the
  runtime (SceneView 2.3.0 -> Filament 1.56.0). A material compiled by another
  matc version aborts at load time ("material version mismatch").

  matc path: $env:MATC, or -Matc, or the default below. matinfo is expected
  next to matc (or $env:MATINFO).

  Verification (fails the script if not met):
    - matinfo reports material Version 56 (Filament 1.56 material format).
    - Every material has SKN (skinning + morphing) vertex variants, because
      Masha_Body/Masha_Eyes are skinned and Masha_Head is skinned + morphed.
    - OpenGL and Vulkan shaders are present.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools/masha_v2/compile_materials.ps1
  $env:MATC = 'D:\filament-v1.56.0\bin\matc.exe'; ./tools/masha_v2/compile_materials.ps1
#>
param(
    [string]$Matc = $env:MATC
)

$ErrorActionPreference = 'Stop'
$expectedVersion = 56

$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$src = Join-Path $PSScriptRoot 'materials'
$out = Join-Path $repo 'app\src\main\assets\masha\materials'

if (-not $Matc) {
    $Matc = Join-Path $env:LOCALAPPDATA 'Temp\claude\C--Users-jefer-OneDrive-Mac-REPOSITORIOS-Elyndra\3f65c38a-0cce-4b8c-a2f1-83bca6b05d90\scratchpad\filament\bin\matc.exe'
}
if (-not (Test-Path $Matc)) {
    throw "matc not found at '$Matc'. Download filament-v1.56.0-windows.tgz from https://github.com/google/filament/releases/tag/v1.56.0 and set `$env:MATC to its bin\matc.exe."
}
$Matinfo = $env:MATINFO
if (-not $Matinfo) { $Matinfo = Join-Path (Split-Path $Matc) 'matinfo.exe' }
if (-not (Test-Path $Matinfo)) { throw "matinfo not found at '$Matinfo' (set `$env:MATINFO)." }

New-Item -ItemType Directory -Force $out | Out-Null

$failed = @()
foreach ($mat in Get-ChildItem $src -Filter *.mat) {
    $target = Join-Path $out ($mat.BaseName + '.filamat')
    Write-Host "matc  $($mat.Name) -> $target"
    & $Matc -p mobile -a opengl -a vulkan -o $target $mat.FullName
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $target)) {
        $failed += $mat.Name
        continue
    }

    $info = (& $Matinfo $target) -join "`n"
    $version = if ($info -match 'Version:\s+(\d+)') { [int]$Matches[1] } else { -1 }
    $hasSkn = $info -match 'vs\s+0x[0-9a-f]+\s+[A-Z|]*SKN'
    $hasGl = $info -match 'GLSL shaders:\s*\n\s+#0'
    $hasVk = $info -match 'Vulkan shaders:\s*\n\s+#0'
    $size = (Get-Item $target).Length
    Write-Host ("        version={0} skinning/morph={1} opengl={2} vulkan={3} size={4:N0} B" -f $version, $hasSkn, $hasGl, $hasVk, $size)
    if ($version -ne $expectedVersion -or -not $hasSkn -or -not $hasGl -or -not $hasVk) {
        $failed += $mat.Name
    }
}

if ($failed.Count -gt 0) {
    throw "Material compilation/verification failed: $($failed -join ', ')"
}
Write-Host 'All materials compiled and verified.'
