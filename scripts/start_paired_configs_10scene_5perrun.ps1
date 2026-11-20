param(
  [string]$SearchOutDir = "outputs\mfea-multiscene-full-20260616",
  [string]$OutputDir = "outputs\paired-hybrid-10scene-5perrun-seedid10-14-20260724",
  [long]$ReferenceSeedIdStart = 10,
  [ValidateSet("native290002", "native410000")]
  [string]$SeedSource = "native290002",
  [int]$PerRunStart = 0,
  [int]$PerRuns = 5,
  [string]$ExperimentId = "paired-hybrid-10scene-5perrun-seedid10-14-20260724",
  [string]$RuntimeClassesDir = "",
  [string]$ReuseOutputDir = "outputs\paired-configs-10scene-5perrun-seedid10-14-20260724",
  [switch]$IncludeParents
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$outPath = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
  [System.IO.Path]::GetFullPath($OutputDir)
} else {
  [System.IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDir))
}
$outputsRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "outputs")).TrimEnd('\')
if (-not $outPath.StartsWith($outputsRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "OutputDir must be a child of $outputsRoot, got: $outPath"
}
New-Item -ItemType Directory -Force -Path $outPath | Out-Null

$runnerPath = Join-Path $repoRoot "scripts\run_paired_configs_10scene_5perrun.ps1"
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
  throw "Runner not found: $runnerPath"
}
$pidPath = Join-Path $outPath "runner.pid.txt"
if (Test-Path -LiteralPath $pidPath) {
  $oldPidText = (Get-Content -LiteralPath $pidPath -Raw).Trim()
  if ($oldPidText) {
    $oldProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$oldPidText" -ErrorAction SilentlyContinue
    if ($null -ne $oldProcess -and $oldProcess.CommandLine -match 'run_paired_configs_10scene_5perrun\.ps1') {
      Write-Host "Runner already active. PID=$oldPidText"
      exit 0
    }
  }
}
$javaProcesses = @(Get-CimInstance Win32_Process | Where-Object {
  $_.Name -eq "java.exe" -and $_.CommandLine -like "*-Dautohyb.experimentId=$ExperimentId*"
})
if ($javaProcesses.Count -gt 0) {
  Write-Host "Experiment Java process already active. PID=$($javaProcesses[0].ProcessId)"
  exit 0
}

$shellCommand = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if ($null -eq $shellCommand) {
  $shellCommand = Get-Command powershell.exe -ErrorAction Stop
}
$shellExe = $shellCommand.Source
$argList = @(
  "-NoProfile",
  "-ExecutionPolicy",
  "Bypass",
  "-File",
  "`"$runnerPath`"",
  "-SearchOutDir",
  "`"$SearchOutDir`"",
  "-OutputDir",
  "`"$OutputDir`"",
  "-ReferenceSeedIdStart",
  "$ReferenceSeedIdStart",
  "-SeedSource",
  "$SeedSource",
  "-PerRunStart",
  "$PerRunStart",
  "-PerRuns",
  "$PerRuns",
  "-ExperimentId",
  "`"$ExperimentId`""
)
if ($RuntimeClassesDir) {
  $argList += @(
    "-RuntimeClassesDir",
    "`"$RuntimeClassesDir`""
  )
}
if ($ReuseOutputDir) {
  $argList += @(
    "-ReuseOutputDir",
    "`"$ReuseOutputDir`""
  )
}
if ($IncludeParents) {
  $argList += "-IncludeParents"
}

$attempt = 1
do {
  $runnerLog = Join-Path $outPath ("runner.attempt{0}.log" -f $attempt)
  $runnerErr = Join-Path $outPath ("runner.attempt{0}.err.log" -f $attempt)
  $commandPath = Join-Path $outPath ("runner.attempt{0}.command.json" -f $attempt)
  $attempt++
} while ((Test-Path -LiteralPath $runnerLog) -or (Test-Path -LiteralPath $runnerErr))

[ordered]@{
  shellExe = $shellExe
  workingDirectory = $repoRoot
  arguments = $argList
  priority = "Normal"
  started = Get-Date -Format o
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $commandPath -Encoding UTF8

$process = Start-Process -FilePath $shellExe `
  -ArgumentList $argList `
  -WorkingDirectory $repoRoot `
  -RedirectStandardOutput $runnerLog `
  -RedirectStandardError $runnerErr `
  -WindowStyle Hidden `
  -PassThru
if (-not $process.HasExited) {
  try {
    $process.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::Normal
  } catch {
    try { $process.Kill() } catch {}
    throw "Failed to set runner PID $($process.Id) to Normal priority: $($_.Exception.Message)"
  }
}
$process.Id | Set-Content -LiteralPath $pidPath -Encoding ASCII
Write-Host "Started paired full-speed experiment. PID=$($process.Id)"
Write-Host "Priority=Normal"
Write-Host "OutputDir=$outPath"
