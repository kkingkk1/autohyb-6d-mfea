param(
  [string]$SearchOutDir = "outputs\mfea-multiscene-full-20260616",
  [string]$OutputDir = "outputs\best-config-10scene-5seed-20260617",
  [int]$SeedStart = 10,
  [int]$Seeds = 5,
  [ValidateSet("native290002", "native410000")]
  [string]$SeedSource = "native290002"
)

$ErrorActionPreference = "Stop"

function Escape-Csv([string]$value) {
  if ($null -eq $value) { return "" }
  return '"' + ($value -replace '"', '""') + '"'
}

function Parse-BestConfig([string]$logPath) {
  $line = Get-Content -LiteralPath $logPath |
    Where-Object { $_ -match '^Best 6D config:\s*\[(.+)\]' } |
    Select-Object -Last 1
  if (-not $line) {
    throw "Best 6D config not found in $logPath"
  }
  if ($line -notmatch '^Best 6D config:\s*\[(.+)\]') {
    throw "Failed to parse best config line: $line"
  }
  return (($Matches[1] -split ',') | ForEach-Object { $_.Trim() }) -join ','
}

function Parse-Metric([string]$logPath, [string]$pattern) {
  $line = Get-Content -LiteralPath $logPath |
    Where-Object { $_ -match $pattern } |
    Select-Object -Last 1
  if (-not $line) { return $null }
  if ($line -match $pattern) { return [double]$Matches[1] }
  return $null
}

function Mean([double[]]$values) {
  if ($values.Count -eq 0) { return [double]::NaN }
  return (($values | Measure-Object -Average).Average)
}

function StdSample([double[]]$values) {
  if ($values.Count -le 1) { return 0.0 }
  $avg = Mean $values
  $sum = 0.0
  foreach ($v in $values) {
    $d = $v - $avg
    $sum += $d * $d
  }
  return [math]::Sqrt($sum / ($values.Count - 1))
}

$repoRoot = (Resolve-Path ".").Path
$searchLog = Join-Path $repoRoot (Join-Path $SearchOutDir "mfea_multiscene_full.log")
if (-not (Test-Path -LiteralPath $searchLog)) {
  throw "Search log not found: $searchLog"
}

$bestConfig = Parse-BestConfig $searchLog
$seedEnd = $SeedStart + $Seeds - 1
if ($SeedStart -lt 10 -or $seedEnd -gt 14) {
  throw "Native seed IDs must be in 10..14; got $SeedStart..$seedEnd"
}
$outPath = Join-Path $repoRoot $OutputDir
$logsDir = Join-Path $outPath "logs"
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

$commandFile = Join-Path $repoRoot (Join-Path $SearchOutDir "mfea_multiscene_full.command.txt")
if (-not (Test-Path -LiteralPath $commandFile)) {
  throw "Command file not found: $commandFile"
}
$commandText = Get-Content -LiteralPath $commandFile -Raw -Encoding UTF8
if ($commandText -notmatch '^(.*?)\s+-Dtuning\.config\.path=.*?\s+-Dautohyb\.deterministic=true\s+.*?-cp\s+(.+?)\s+main\.MainMFEAMultiScene\s*$') {
  throw "Failed to parse Java executable/classpath from $commandFile"
}
$javaExe = $Matches[1].Trim()
$classPath = $Matches[2].Trim()

$sceneRoot = & (Join-Path $PSScriptRoot "Resolve-SceneRoot.ps1")
$scenes = @(
  @{ Id = "ID1";  Name = "500-5-0-0-24";       Tasks = 500;   Sats = 5 },
  @{ Id = "ID2";  Name = "500-5-1-0-24";       Tasks = 500;   Sats = 5 },
  @{ Id = "ID3";  Name = "1000-10-0-0-12";     Tasks = 1000;  Sats = 10 },
  @{ Id = "ID4";  Name = "1000-10-1-12-24";    Tasks = 1000;  Sats = 10 },
  @{ Id = "ID5";  Name = "1000-20-0-0-12";     Tasks = 1000;  Sats = 20 },
  @{ Id = "ID6";  Name = "2000-20-1-12-24";    Tasks = 2000;  Sats = 20 },
  @{ Id = "ID7";  Name = "4000-40-0-0-6";      Tasks = 4000;  Sats = 40 },
  @{ Id = "ID8";  Name = "6000-60-1-12-18";    Tasks = 6000;  Sats = 60 },
  @{ Id = "ID9";  Name = "8000-80-0-0-12";     Tasks = 8000;  Sats = 80 },
  @{ Id = "ID10"; Name = "10000-100-0-6-12";   Tasks = 10000; Sats = 100 }
)

$detailsPath = Join-Path $outPath "run-details.csv"
$summaryPath = Join-Path $outPath "summary.csv"
$failuresPath = Join-Path $outPath "failures.csv"
$metaPath = Join-Path $outPath "metadata.txt"

"sceneId,sceneName,seedId,config,profitRatio,elapsedSeconds,logPath" |
  Set-Content -LiteralPath $detailsPath -Encoding UTF8
"sceneId,sceneName,tasks,satellites,n,config,meanProfitRatio,stdProfitRatio,minProfitRatio,maxProfitRatio,meanElapsedSeconds,totalElapsedSeconds" |
  Set-Content -LiteralPath $summaryPath -Encoding UTF8
"sceneId,sceneName,seedId,exitCode,logPath,errPath" |
  Set-Content -LiteralPath $failuresPath -Encoding UTF8

@(
  "bestConfig=$bestConfig",
  "seedStart=$SeedStart",
  "seedEnd=$seedEnd",
  "seeds=$Seeds",
  "seedSource=$SeedSource",
  "seedContract=seedId 10..14 maps to perRun 0..4 via the selected native scene seed table",
  "javaExe=$javaExe",
  "classPath=$classPath",
  "searchLog=$searchLog",
  "started=$(Get-Date -Format o)"
) | Set-Content -LiteralPath $metaPath -Encoding UTF8

foreach ($scene in $scenes) {
  $sceneDir = Join-Path $sceneRoot $scene.Name
  if (-not (Test-Path -LiteralPath $sceneDir)) {
    throw "Missing scene directory: $sceneDir"
  }

  $ratios = New-Object System.Collections.Generic.List[double]
  $elapsed = New-Object System.Collections.Generic.List[double]

  for ($seedOffset = 0; $seedOffset -lt $Seeds; $seedOffset++) {
    $seedId = $SeedStart + $seedOffset
    $perRun = $seedId - 10
    $logPath = Join-Path $logsDir ("{0}_seed{1}.log" -f $scene.Id, $seedId)
    $errPath = Join-Path $logsDir ("{0}_seed{1}.err.log" -f $scene.Id, $seedId)
    $argsList = @(
      "-Dtuning.config.path=tuning.properties",
      "-Dautohyb.deterministic=true",
      "-Devaluation.seedTable.source=$SeedSource",
      "-Devaluation.perRunStart=$perRun",
      "-Devaluation.runSeedLog=true",
      "-cp", $classPath,
      "main.RunSingleHybridConfig",
      "--quiet",
      "true",
      "true",
      $sceneDir,
      "$($scene.Tasks)",
      "$($scene.Sats)",
      $bestConfig,
      "1"
    )

    Write-Host ("[{0}] {1} {2} seed={3} config={4}" -f
      (Get-Date -Format "HH:mm:ss"), $scene.Id, $scene.Name, $seedId, $bestConfig)

    & $javaExe @argsList 1> $logPath 2> $errPath
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
      Add-Content -LiteralPath $failuresPath -Encoding UTF8 -Value (
        "{0},{1},{2},{3},{4},{5}" -f
        $scene.Id,
        $scene.Name,
        $seedId,
        $exitCode,
        (Escape-Csv $logPath),
        (Escape-Csv $errPath)
      )
      continue
    }

    $ratio = Parse-Metric $logPath '^Optimized profit ratio:\s*([0-9.]+)%'
    $seconds = Parse-Metric $logPath '^Elapsed:\s*([0-9.]+)\s*s'
    if ($null -eq $ratio -or $null -eq $seconds) {
      Add-Content -LiteralPath $failuresPath -Encoding UTF8 -Value (
        "{0},{1},{2},{3},{4},{5}" -f
        $scene.Id,
        $scene.Name,
        $seedId,
        "parse",
        (Escape-Csv $logPath),
        (Escape-Csv $errPath)
      )
      continue
    }

    $ratios.Add($ratio)
    $elapsed.Add($seconds)
    Add-Content -LiteralPath $detailsPath -Encoding UTF8 -Value (
      "{0},{1},{2},{3},{4:F2},{5:F2},{6}" -f
      $scene.Id,
      $scene.Name,
      $seedId,
      (Escape-Csv $bestConfig),
      $ratio,
      $seconds,
      (Escape-Csv $logPath)
    )
  }

  $ratioArray = [double[]]$ratios.ToArray()
  $elapsedArray = [double[]]$elapsed.ToArray()
  if ($ratioArray.Count -eq 0) {
    continue
  }
  Add-Content -LiteralPath $summaryPath -Encoding UTF8 -Value (
    "{0},{1},{2},{3},{4},{5},{6:F4},{7:F4},{8:F2},{9:F2},{10:F2},{11:F2}" -f
    $scene.Id,
    $scene.Name,
    $scene.Tasks,
    $scene.Sats,
    $ratioArray.Count,
    (Escape-Csv $bestConfig),
    (Mean $ratioArray),
    (StdSample $ratioArray),
    (($ratioArray | Measure-Object -Minimum).Minimum),
    (($ratioArray | Measure-Object -Maximum).Maximum),
    (Mean $elapsedArray),
    (($elapsedArray | Measure-Object -Sum).Sum)
  )
}

Add-Content -LiteralPath $metaPath -Encoding UTF8 -Value ("finished=$(Get-Date -Format o)")
Write-Host "Done."
Write-Host "Summary: $summaryPath"
Write-Host "Details: $detailsPath"
