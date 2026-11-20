param(
  [string]$SearchOutDir = "outputs\mfea-multiscene-full-20260616",
  [string]$OutputDir = "outputs\best-config-10scene-5seed-20260617",
  [int]$SeedStart = 10,
  [int]$Seeds = 5,
  [ValidateSet("native290002", "native410000")]
  [string]$SeedSource = "native290002"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path ".").Path
$outPath = Join-Path $repoRoot $OutputDir
New-Item -ItemType Directory -Force -Path $outPath | Out-Null

$pidPath = Join-Path $outPath "best_config_10scene_5seed.pid.txt"
$runnerLog = Join-Path $outPath "runner.log"
$runnerErr = Join-Path $outPath "runner.err.log"
$commandPath = Join-Path $outPath "runner.command.txt"

if (Test-Path -LiteralPath $pidPath) {
  $oldPidText = (Get-Content -LiteralPath $pidPath -Raw).Trim()
  if ($oldPidText) {
    $oldProc = Get-Process -Id ([int]$oldPidText) -ErrorAction SilentlyContinue
    if ($oldProc) {
      Write-Host "Validation already running. PID=$oldPidText"
      exit 0
    }
  }
}

$scriptPath = Join-Path $repoRoot "scripts\run_best_config_10scene_5seed.ps1"
if (-not (Test-Path -LiteralPath $scriptPath)) {
  throw "Validation script not found: $scriptPath"
}

$argList = @(
  "-NoProfile",
  "-ExecutionPolicy",
  "Bypass",
  "-File",
  "`"$scriptPath`"",
  "-SearchOutDir",
  "`"$SearchOutDir`"",
  "-OutputDir",
  "`"$OutputDir`"",
  "-SeedStart",
  "$SeedStart",
  "-Seeds",
  "$Seeds",
  "-SeedSource",
  "$SeedSource"
)

("powershell.exe " + ($argList -join " ")) |
  Set-Content -LiteralPath $commandPath -Encoding UTF8

$proc = Start-Process -FilePath "powershell.exe" `
  -ArgumentList $argList `
  -WorkingDirectory $repoRoot `
  -RedirectStandardOutput $runnerLog `
  -RedirectStandardError $runnerErr `
  -WindowStyle Hidden `
  -PassThru

$proc.Id | Set-Content -LiteralPath $pidPath -Encoding ASCII
Write-Host "Started validation. PID=$($proc.Id)"
Write-Host "OutputDir=$outPath"
