param(
  [Parameter(Mandatory = $true)]
  [string]$CandidateCsv,
  [Parameter(Mandatory = $true)]
  [string]$OutputDir,
  [ValidateSet("train", "all10")]
  [string]$SceneSet = "train",
  [ValidateSet("native290002")]
  [string]$SeedSource = "native290002",
  [ValidateRange(1, 20)]
  [int]$SeedCount = 5,
  [switch]$PreflightOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-RepoPath([string]$repoRoot, [string]$path) {
  if ([System.IO.Path]::IsPathRooted($path)) {
    return [System.IO.Path]::GetFullPath($path)
  }
  return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $path))
}

function Assert-ChildOf([string]$path, [string]$parent, [string]$label) {
  $normalizedParent = [System.IO.Path]::GetFullPath($parent).TrimEnd('\\')
  $normalizedPath = [System.IO.Path]::GetFullPath($path)
  $prefix = $normalizedParent + [System.IO.Path]::DirectorySeparatorChar
  if (-not $normalizedPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "$label must be under $normalizedParent, got: $normalizedPath"
  }
}

function Normalize-Config([string]$configText) {
  $values = @($configText -split ',' | ForEach-Object { $_.Trim() })
  if ($values.Count -ne 6 -or @($values | Where-Object { $_ -notmatch '^\d+$' }).Count -ne 0) {
    throw "Invalid 6D configuration: $configText"
  }
  return ($values | ForEach-Object { [int]$_ }) -join ','
}

function Mean([double[]]$values) {
  if ($values.Count -eq 0) { throw "Cannot calculate a mean of zero values" }
  return [double](($values | Measure-Object -Average).Average)
}

function StdSample([double[]]$values) {
  if ($values.Count -le 1) { return 0.0 }
  $mean = Mean $values
  $sum = 0.0
  foreach ($value in $values) {
    $delta = $value - $mean
    $sum += $delta * $delta
  }
  return [math]::Sqrt($sum / ($values.Count - 1))
}

function Get-LastRegexMatch([string]$text, [string]$pattern) {
  $matches = [regex]::Matches($text, $pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
  if ($matches.Count -eq 0) { return $null }
  return $matches[$matches.Count - 1]
}

function Add-DetailRow([pscustomobject]$row) {
  if ($script:detailsInitialized) {
    $row | Export-Csv -LiteralPath $script:detailsPath -NoTypeInformation -Append -Encoding UTF8
  } else {
    $row | Export-Csv -LiteralPath $script:detailsPath -NoTypeInformation -Encoding UTF8
    $script:detailsInitialized = $true
  }
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$outputsRoot = Join-Path $repoRoot "outputs"
$candidatePath = Resolve-RepoPath $repoRoot $CandidateCsv
$outputRoot = Resolve-RepoPath $repoRoot $OutputDir
Assert-ChildOf $candidatePath $outputsRoot "CandidateCsv"
Assert-ChildOf $outputRoot $outputsRoot "OutputDir"
if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) {
  throw "Candidate CSV not found: $candidatePath"
}

$candidateRows = @(Import-Csv -LiteralPath $candidatePath)
if ($candidateRows.Count -eq 0) {
  throw "Candidate CSV is empty: $candidatePath"
}
$candidates = New-Object System.Collections.Generic.List[object]
$seenConfigs = @{}
foreach ($row in $candidateRows) {
  if (-not ($row.PSObject.Properties.Name -contains "bestConfig")) {
    throw "Candidate CSV must contain a bestConfig column: $candidatePath"
  }
  $config = Normalize-Config ([string]$row.bestConfig)
  if ($seenConfigs.ContainsKey($config)) {
    continue
  }
  $seenConfigs[$config] = $true
  $candidateKey = "config-" + ($config -replace ',', '-')
  $candidates.Add([pscustomobject]@{
    candidateKey = $candidateKey
    bestConfig = $config
  })
}
if ($candidates.Count -eq 0) {
  throw "Candidate CSV contains no usable configurations: $candidatePath"
}

$allScenes = @(
  @{ Id = "ID1"; Name = "500-5-0-0-24"; Tasks = 500; Sats = 5 },
  @{ Id = "ID2"; Name = "500-5-1-0-24"; Tasks = 500; Sats = 5 },
  @{ Id = "ID3"; Name = "1000-10-0-0-12"; Tasks = 1000; Sats = 10 },
  @{ Id = "ID4"; Name = "1000-10-1-12-24"; Tasks = 1000; Sats = 10 },
  @{ Id = "ID5"; Name = "1000-20-0-0-12"; Tasks = 1000; Sats = 20 },
  @{ Id = "ID6"; Name = "2000-20-1-12-24"; Tasks = 2000; Sats = 20 },
  @{ Id = "ID7"; Name = "4000-40-0-0-6"; Tasks = 4000; Sats = 40 },
  @{ Id = "ID8"; Name = "6000-60-1-12-18"; Tasks = 6000; Sats = 60 },
  @{ Id = "ID9"; Name = "8000-80-0-0-12"; Tasks = 8000; Sats = 80 },
  @{ Id = "ID10"; Name = "10000-100-0-6-12"; Tasks = 10000; Sats = 100 }
)
$trainScenes = @(
  @{ Id = "train-500-5"; Name = "500-5-0-0-24-train"; Tasks = 500; Sats = 5 },
  @{ Id = "train-1000-10"; Name = "1000-10-0-0-12-train"; Tasks = 1000; Sats = 10 },
  @{ Id = "train-1000-20"; Name = "1000-20-0-0-12-train"; Tasks = 1000; Sats = 20 },
  @{ Id = "train-4000-40"; Name = "4000-40-0-0-6-train"; Tasks = 4000; Sats = 40 }
)
$scenes = if ($SceneSet -eq "train") { $trainScenes } else { $allScenes }

$sceneRoot = & (Join-Path $PSScriptRoot "Resolve-SceneRoot.ps1")
$javaExe = (Get-Command java -CommandType Application -ErrorAction Stop).Source
$classesDir = Join-Path $repoRoot "target\classes"
$commonsMath = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) ".m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar"
if (-not (Test-Path -LiteralPath $classesDir -PathType Container)) {
  throw "Compiled classes not found: $classesDir"
}
if (-not (Test-Path -LiteralPath $commonsMath -PathType Leaf)) {
  throw "commons-math dependency not found: $commonsMath"
}
$classPath = "$classesDir;$commonsMath"

$plannedRuns = New-Object System.Collections.Generic.List[object]
foreach ($candidate in $candidates) {
  foreach ($scene in $scenes) {
    $sceneDir = Join-Path $sceneRoot $scene.Name
    if (-not (Test-Path -LiteralPath $sceneDir -PathType Container)) {
      throw "Missing scene directory: $sceneDir"
    }
    for ($perRun = 0; $perRun -lt $SeedCount; $perRun++) {
      $seedId = 10 + $perRun
      $runKey = "{0}|{1}|{2}" -f $candidate.candidateKey, $scene.Id, $perRun
      $plannedRuns.Add([pscustomobject]@{
        runKey = $runKey
        candidateKey = $candidate.candidateKey
        bestConfig = $candidate.bestConfig
        sceneId = $scene.Id
        sceneName = $scene.Name
        sceneDir = $sceneDir
        tasks = $scene.Tasks
        satellites = $scene.Sats
        perRun = $perRun
        seedId = $seedId
      })
    }
  }
}

if ($PreflightOnly) {
  [pscustomobject]@{
    candidateCsv = $candidatePath
    outputDir = $outputRoot
    sceneSet = $SceneSet
    candidateCount = $candidates.Count
    sceneCount = $scenes.Count
    seedCount = $SeedCount
    plannedRuns = $plannedRuns.Count
    seedContract = "native290002 perRun=0..$($SeedCount - 1), seedId=10..$($SeedCount + 9)"
  } | ConvertTo-Json -Depth 3
  return
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$logsDir = Join-Path $outputRoot "logs"
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$script:detailsPath = Join-Path $outputRoot "run-details.csv"
$script:detailsInitialized = Test-Path -LiteralPath $script:detailsPath -PathType Leaf
$plannedPath = Join-Path $outputRoot "planned-runs.csv"
$metadataPath = Join-Path $outputRoot "metadata.txt"
$sceneSummaryPath = Join-Path $outputRoot "scene-summary.csv"
$candidateSummaryPath = Join-Path $outputRoot "candidate-summary.csv"
$winnerPath = Join-Path $outputRoot "winner.csv"
$completePath = Join-Path $outputRoot "run-complete.txt"
$candidateHash = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash

if (Test-Path -LiteralPath $metadataPath -PathType Leaf) {
  $metadata = @{}
  foreach ($line in Get-Content -LiteralPath $metadataPath) {
    if ($line -match '^([^=]+)=(.*)$') {
      $metadata[$Matches[1]] = $Matches[2]
    }
  }
  if ($metadata['candidateCsvSha256'] -ne $candidateHash -or $metadata['sceneSet'] -ne $SceneSet -or
      $metadata['seedSource'] -ne $SeedSource -or [int]$metadata['seedCount'] -ne $SeedCount) {
    throw "Existing output metadata does not match the requested candidate, scene, or seed contract: $outputRoot"
  }
} else {
  @(
    "candidateCsv=$candidatePath",
    "candidateCsvSha256=$candidateHash",
    "sceneSet=$SceneSet",
    "seedSource=$SeedSource",
    "seedCount=$SeedCount",
    "seedContract=scene-fixed table perRun=0..$($SeedCount - 1) seedId=10..$($SeedCount + 9)",
    "selectionRule=highest macro mean of per-scene mean optimized profit ratios; ties use lower across-scene sample standard deviation",
    "javaExe=$javaExe",
    "classPath=$classPath",
    "priority=Normal",
    "started=$(Get-Date -Format o)"
  ) | Set-Content -LiteralPath $metadataPath -Encoding UTF8
}
$plannedRuns | Export-Csv -LiteralPath $plannedPath -NoTypeInformation -Encoding UTF8

$completedKeys = @{}
if ($script:detailsInitialized) {
  foreach ($row in @(Import-Csv -LiteralPath $script:detailsPath)) {
    if ($row.status -eq "completed") {
      $completedKeys[$row.runKey] = $true
    }
  }
}

try {
  (Get-Process -Id $PID -ErrorAction Stop).PriorityClass = [System.Diagnostics.ProcessPriorityClass]::Normal
} catch {
  throw "Unable to set the candidate evaluation runner to Normal priority: $($_.Exception.Message)"
}

foreach ($run in $plannedRuns) {
  if ($completedKeys.ContainsKey($run.runKey)) {
    continue
  }

  $logPath = Join-Path $logsDir ("{0}_{1}_perRun{2}.log" -f $run.candidateKey, $run.sceneId, $run.perRun)
  $errPath = Join-Path $logsDir ("{0}_{1}_perRun{2}.err.log" -f $run.candidateKey, $run.sceneId, $run.perRun)
  $record = [ordered]@{
    runKey = $run.runKey
    status = "failed"
    candidateKey = $run.candidateKey
    bestConfig = $run.bestConfig
    sceneId = $run.sceneId
    sceneName = $run.sceneName
    perRun = $run.perRun
    seedId = $run.seedId
    actualSeed = ""
    profitRatio = ""
    elapsedSeconds = ""
    logPath = $logPath
    errPath = $errPath
    error = ""
  }
  try {
    $arguments = @(
      "-Dtuning.config.path=tuning.properties",
      "-Dautohyb.deterministic=true",
      "-Devaluation.seedTable.source=$SeedSource",
      "-Devaluation.perRunStart=$($run.perRun)",
      "-Devaluation.runSeedLog=true",
      "-cp", $classPath,
      "main.RunSingleHybridConfig",
      "--quiet",
      "true",
      "true",
      $run.sceneDir,
      "$($run.tasks)",
      "$($run.satellites)",
      $run.bestConfig,
      "1"
    )
    Write-Host ("[{0}] {1} {2} perRun={3} config={4}" -f (Get-Date -Format "HH:mm:ss"), $run.candidateKey, $run.sceneId, $run.perRun, $run.bestConfig)
    & $javaExe @arguments 1> $logPath 2> $errPath
    if ($LASTEXITCODE -ne 0) {
      throw "Java exited with code $LASTEXITCODE"
    }
    $logText = Get-Content -LiteralPath $logPath -Raw
    $ratioMatch = Get-LastRegexMatch $logText '^Optimized profit ratio:\s*([0-9.]+)%\s*$'
    $elapsedMatch = Get-LastRegexMatch $logText '^Elapsed:\s*([0-9.]+)\s*s\s*$'
    $seedMatch = Get-LastRegexMatch $logText '^\[RunSeed\] source=(\S+) table=(\S+) scene=(\S+) perRun=(\d+) seedId=(\d+) seed=(\d+)\s*$'
    if ($null -eq $ratioMatch -or $null -eq $elapsedMatch -or $null -eq $seedMatch) {
      throw "Missing compact result or fixed-seed evidence in $logPath"
    }
    if ($seedMatch.Groups[1].Value -ne $SeedSource -or $seedMatch.Groups[3].Value -ne $run.sceneName -or
        [int]$seedMatch.Groups[4].Value -ne $run.perRun -or [int]$seedMatch.Groups[5].Value -ne $run.seedId) {
      throw "Fixed-seed evidence does not match the planned run: $($run.runKey)"
    }
    $record.status = "completed"
    $record.actualSeed = [long]$seedMatch.Groups[6].Value
    $record.profitRatio = [double]$ratioMatch.Groups[1].Value
    $record.elapsedSeconds = [double]$elapsedMatch.Groups[1].Value
  } catch {
    $record.error = $_.Exception.Message
    Add-DetailRow ([pscustomobject]$record)
    throw "Run failed and was recorded: $($run.runKey): $($record.error)"
  }
  Add-DetailRow ([pscustomobject]$record)
  $completedKeys[$run.runKey] = $true
}

$details = @(Import-Csv -LiteralPath $script:detailsPath | Where-Object { $_.status -eq "completed" })
foreach ($run in $plannedRuns) {
  if (@($details | Where-Object { $_.runKey -eq $run.runKey }).Count -ne 1) {
    throw "Expected exactly one completed result for $($run.runKey)"
  }
}

$sceneSummary = foreach ($group in ($details | Group-Object candidateKey, sceneId)) {
  $rows = @($group.Group)
  $first = $rows[0]
  $ratios = [double[]]@($rows | ForEach-Object { [double]$_.profitRatio })
  $elapsed = [double[]]@($rows | ForEach-Object { [double]$_.elapsedSeconds })
  [pscustomobject]@{
    candidateKey = $first.candidateKey
    bestConfig = $first.bestConfig
    sceneId = $first.sceneId
    sceneName = $first.sceneName
    n = $ratios.Count
    meanProfitRatio = Mean $ratios
    stdProfitRatio = StdSample $ratios
    minProfitRatio = ($ratios | Measure-Object -Minimum).Minimum
    maxProfitRatio = ($ratios | Measure-Object -Maximum).Maximum
    meanElapsedSeconds = Mean $elapsed
  }
}
@($sceneSummary | Sort-Object candidateKey, sceneId) |
  Export-Csv -LiteralPath $sceneSummaryPath -NoTypeInformation -Encoding UTF8

$candidateSummary = foreach ($group in ($sceneSummary | Group-Object candidateKey)) {
  $rows = @($group.Group)
  if ($rows.Count -ne $scenes.Count) {
    throw "Candidate $($group.Name) has $($rows.Count) completed scenes, expected $($scenes.Count)"
  }
  $first = $rows[0]
  $sceneMeans = [double[]]@($rows | ForEach-Object { [double]$_.meanProfitRatio })
  [pscustomobject]@{
    candidateKey = $first.candidateKey
    bestConfig = $first.bestConfig
    completedScenes = $rows.Count
    completedRuns = $rows.n | Measure-Object -Sum | Select-Object -ExpandProperty Sum
    macroMeanProfitRatio = Mean $sceneMeans
    macroStdAcrossScenes = StdSample $sceneMeans
  }
}
$candidateSummary = @($candidateSummary | Sort-Object @{ Expression = { $_.macroMeanProfitRatio }; Descending = $true }, @{ Expression = { $_.macroStdAcrossScenes }; Descending = $false }, bestConfig)
$rankedSummary = for ($index = 0; $index -lt $candidateSummary.Count; $index++) {
  $row = $candidateSummary[$index]
  [pscustomobject]@{
    rank = $index + 1
    candidateKey = $row.candidateKey
    bestConfig = $row.bestConfig
    completedScenes = $row.completedScenes
    completedRuns = $row.completedRuns
    macroMeanProfitRatio = $row.macroMeanProfitRatio
    macroStdAcrossScenes = $row.macroStdAcrossScenes
  }
}
@($rankedSummary) | Export-Csv -LiteralPath $candidateSummaryPath -NoTypeInformation -Encoding UTF8
@($rankedSummary | Select-Object -First 1) | Export-Csv -LiteralPath $winnerPath -NoTypeInformation -Encoding UTF8

@(
  "finished=$(Get-Date -Format o)",
  "completedRuns=$($plannedRuns.Count)",
  "winner=$($rankedSummary[0].bestConfig)",
  "winnerMacroMeanProfitRatio=$($rankedSummary[0].macroMeanProfitRatio)",
  "status=completed"
) | Add-Content -LiteralPath $metadataPath -Encoding UTF8
"status=completed" | Set-Content -LiteralPath $completePath -Encoding UTF8

Write-Host "Candidate summary: $candidateSummaryPath"
Write-Host "Winner: $winnerPath"
