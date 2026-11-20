param(
  [string]$ConfiguredRoot = $env:AUTOHYB_SCENE_ROOT
)

$ErrorActionPreference = "Stop"

if ($ConfiguredRoot) {
  $resolvedConfiguredRoot = (Resolve-Path -LiteralPath $ConfiguredRoot).Path
  Write-Output $resolvedConfiguredRoot
  return
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
$candidates = @(
  (Join-Path $projectRoot "Scene"),
  (Join-Path $workspaceRoot "Scene")
)

foreach ($candidate in $candidates) {
  if (Test-Path -LiteralPath $candidate -PathType Container) {
    Write-Output (Resolve-Path -LiteralPath $candidate).Path
    return
  }
}

throw "Scene root not found. Set AUTOHYB_SCENE_ROOT or place Scene in/next to the project."
