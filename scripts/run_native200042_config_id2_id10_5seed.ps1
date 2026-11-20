param(
  [string]$OutputDir = "outputs\native200042-config-2-0-0-0-4-2-ID2-10-sourceIDs13-17-21-24-25-20260818",
  [string]$RuntimeClassesDir = "target\classes",
  [ValidateRange(1, 3)]
  [int]$MaxAttempts = 1,
  [switch]$PreflightOnly,
  [switch]$RunFullAfterPreflight
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($RunFullAfterPreflight) {
  $preflightOutputDir = "$OutputDir-preflight"
  & $PSCommandPath -OutputDir $preflightOutputDir -RuntimeClassesDir $RuntimeClassesDir `
    -MaxAttempts $MaxAttempts -PreflightOnly
  if (-not $?) {
    throw "native200042 seed preflight failed: $preflightOutputDir"
  }
  & $PSCommandPath -OutputDir $OutputDir -RuntimeClassesDir $RuntimeClassesDir -MaxAttempts $MaxAttempts
  if (-not $?) {
    throw "native200042 full batch failed: $OutputDir"
  }
  return
}

$Config = "2,0,0,0,4,2"
$SeedSource = "native200042"
[int[]]$SourceSeedIds = @(13, 17, 21, 24, 25)
$LogicalSeedStart = 10

function Resolve-WorkspacePath([string]$repoRoot, [string]$path) {
  if ([System.IO.Path]::IsPathRooted($path)) {
    return [System.IO.Path]::GetFullPath($path)
  }
  return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $path))
}

function Assert-OutputPath([string]$repoRoot, [string]$path) {
  $outputsRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "outputs")).TrimEnd('\')
  $fullPath = [System.IO.Path]::GetFullPath($path)
  $prefix = $outputsRoot + [System.IO.Path]::DirectorySeparatorChar
  if (-not $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDir must be a child of $outputsRoot, got: $fullPath"
  }
}

function Get-FileSha256([string]$path) {
  return (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
}

function Get-PropertyValue([string]$path, [string]$key) {
  $prefix = "$key="
  $line = Get-Content -LiteralPath $path -Encoding UTF8 |
    Where-Object { $_.StartsWith($prefix, [System.StringComparison]::Ordinal) } |
    Select-Object -Last 1
  if ($null -eq $line) {
    throw "Property '$key' not found in $path"
  }
  return $line.Substring($prefix.Length)
}

function Get-ExpectedSeed([string]$tablePath, [string]$sceneName, [int]$perRun) {
  $prefix = "seed.$sceneName.$perRun="
  $line = Get-Content -LiteralPath $tablePath -Encoding UTF8 |
    Where-Object { $_.StartsWith($prefix, [System.StringComparison]::Ordinal) } |
    Select-Object -Last 1
  if ($null -eq $line) {
    throw "Seed table row not found: $prefix"
  }
  return [long]$line.Substring($prefix.Length).Trim()
}

function Quote-ProcessArgument([string]$value) {
  if ($value -notmatch '[\s"]') {
    return $value
  }
  return '"' + ($value -replace '"', '\\"') + '"'
}

function Invoke-JavaProcess(
  [string]$javaExe,
  [string[]]$argsList,
  [string]$workingDirectory,
  [string]$stdoutPath,
  [string]$stderrPath,
  [string]$pidPath
) {
  $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $javaExe
  $startInfo.WorkingDirectory = $workingDirectory
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.Arguments = (($argsList | ForEach-Object {
    Quote-ProcessArgument ([string]$_)
  }) -join ' ')

  $process = [System.Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  $started = Get-Date
  if (-not $process.Start()) {
    throw "Failed to start Java process: $javaExe"
  }
  try {
    $process.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::Normal
    $actualPriority = $process.PriorityClass.ToString()
  } catch {
    try { $process.Kill() } catch {}
    throw "Failed to set Java PID $($process.Id) to Normal priority: $($_.Exception.Message)"
  }
  $process.Id | Set-Content -LiteralPath $pidPath -Encoding ASCII

  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.WaitForExit()
  $finished = Get-Date
  $stdout = $stdoutTask.GetAwaiter().GetResult()
  $stderr = $stderrTask.GetAwaiter().GetResult()
  [System.IO.File]::WriteAllText($stdoutPath, $stdout, [System.Text.UTF8Encoding]::new($false))
  [System.IO.File]::WriteAllText($stderrPath, $stderr, [System.Text.UTF8Encoding]::new($false))
  try { $processCpuSeconds = $process.TotalProcessorTime.TotalSeconds } catch { $processCpuSeconds = [double]::NaN }
  $exitCode = $process.ExitCode
  $process.Dispose()
  return [pscustomobject]@{
    ExitCode = $exitCode
    Priority = $actualPriority
    Started = $started
    Finished = $finished
    ProcessCpuSeconds = $processCpuSeconds
  }
}

function Append-CsvRow([object]$row, [string]$path) {
  if (Test-Path -LiteralPath $path -PathType Leaf) {
    $row | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8 -Append
  } else {
    $row | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8
  }
}

function Mean([double[]]$values) {
  if ($values.Count -eq 0) { return [double]::NaN }
  return [double](($values | Measure-Object -Average).Average)
}

function StdSample([double[]]$values) {
  if ($values.Count -le 1) { return 0.0 }
  $average = Mean $values
  $sum = 0.0
  foreach ($value in $values) {
    $delta = $value - $average
    $sum += $delta * $delta
  }
  return [math]::Sqrt($sum / ($values.Count - 1))
}

function Write-Progress(
  [string]$path,
  [string]$status,
  [int]$expected,
  [int]$completed,
  [int]$failureAttempts,
  [string]$lastRunKey
) {
  [ordered]@{
    status = $status
    expectedRuns = $expected
    completedRuns = $completed
    remainingRuns = $expected - $completed
    failureAttempts = $failureAttempts
    lastRunKey = $lastRunKey
    updated = Get-Date -Format o
  } | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
}

function Write-Summaries(
  [object[]]$details,
  [string]$detailsPath,
  [string]$perSeedPath,
  [string]$sceneSummaryPath,
  [string]$summaryJsonPath,
  [string]$experimentId,
  [int]$plannedRunCount
) {
  $orderedDetails = @($details | Sort-Object sceneId, perRun)
  $orderedDetails | Export-Csv -LiteralPath $detailsPath -NoTypeInformation -Encoding UTF8
  $orderedDetails |
    Select-Object sceneId, sceneName, perRun, logicalSeedId, sourceNative290002SeedId,
      expectedSeed, actualSeed, profit, profitRatio, elapsedSeconds, nPop, maxFes, status |
    Export-Csv -LiteralPath $perSeedPath -NoTypeInformation -Encoding UTF8

  $sceneSummary = foreach ($group in ($orderedDetails | Group-Object sceneId)) {
    $first = $group.Group[0]
    [double[]]$profits = @($group.Group | ForEach-Object { [double]$_.profit })
    [double[]]$ratios = @($group.Group | ForEach-Object { [double]$_.profitRatio })
    [double[]]$elapsed = @($group.Group | ForEach-Object { [double]$_.elapsedSeconds })
    [pscustomobject]@{
      sceneId = $first.sceneId
      sceneName = $first.sceneName
      tasks = $first.tasks
      satellites = $first.satellites
      config = $first.config
      n = $group.Count
      meanProfit = "{0:F8}" -f (Mean $profits)
      stdProfit = "{0:F8}" -f (StdSample $profits)
      meanProfitRatio = "{0:F8}" -f (Mean $ratios)
      stdProfitRatio = "{0:F8}" -f (StdSample $ratios)
      minProfitRatio = "{0:F8}" -f (($ratios | Measure-Object -Minimum).Minimum)
      maxProfitRatio = "{0:F8}" -f (($ratios | Measure-Object -Maximum).Maximum)
      meanElapsedSeconds = "{0:F2}" -f (Mean $elapsed)
      totalElapsedSeconds = "{0:F2}" -f (($elapsed | Measure-Object -Sum).Sum)
    }
  }
  @($sceneSummary | Sort-Object { [int](($_.sceneId -replace '\D', '')) }) |
    Export-Csv -LiteralPath $sceneSummaryPath -NoTypeInformation -Encoding UTF8

  [ordered]@{
    experimentId = $experimentId
    completedRuns = $orderedDetails.Count
    plannedRuns = $plannedRunCount
    aggregateMeanProfitRatio = if ($orderedDetails.Count -eq 0) { $null } else {
      [double](Mean @($orderedDetails | ForEach-Object { [double]$_.profitRatio }))
    }
    generated = Get-Date -Format o
  } | ConvertTo-Json | Set-Content -LiteralPath $summaryJsonPath -Encoding UTF8
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$outPath = Resolve-WorkspacePath $repoRoot $OutputDir
Assert-OutputPath $repoRoot $outPath
$logsDir = Join-Path $outPath "logs"
$commandsDir = Join-Path $outPath "commands"
New-Item -ItemType Directory -Force -Path $logsDir, $commandsDir | Out-Null

$sceneRootOutput = & (Join-Path $PSScriptRoot "Resolve-SceneRoot.ps1")
$sceneRoot = ($sceneRootOutput | Select-Object -Last 1).ToString().Trim()
if ([string]::IsNullOrWhiteSpace($sceneRoot) -or -not (Test-Path -LiteralPath $sceneRoot -PathType Container)) {
  throw "Scene root not found: $sceneRoot"
}

$scenes = @(
  [ordered]@{ Id = "ID2"; Name = "500-5-1-0-24"; Tasks = 500; Sats = 5 },
  [ordered]@{ Id = "ID3"; Name = "1000-10-0-0-12"; Tasks = 1000; Sats = 10 },
  [ordered]@{ Id = "ID4"; Name = "1000-10-1-12-24"; Tasks = 1000; Sats = 10 },
  [ordered]@{ Id = "ID5"; Name = "1000-20-0-0-12"; Tasks = 1000; Sats = 20 },
  [ordered]@{ Id = "ID6"; Name = "2000-20-1-12-24"; Tasks = 2000; Sats = 20 },
  [ordered]@{ Id = "ID7"; Name = "4000-40-0-0-6"; Tasks = 4000; Sats = 40 },
  [ordered]@{ Id = "ID8"; Name = "6000-60-1-12-18"; Tasks = 6000; Sats = 60 },
  [ordered]@{ Id = "ID9"; Name = "8000-80-0-0-12"; Tasks = 8000; Sats = 80 },
  [ordered]@{ Id = "ID10"; Name = "10000-100-0-6-12"; Tasks = 10000; Sats = 100 }
)
foreach ($scene in $scenes) {
  $sceneDir = Join-Path $sceneRoot $scene.Name
  if (-not (Test-Path -LiteralPath $sceneDir -PathType Container)) {
    throw "Missing scene directory: $sceneDir"
  }
}

$seedTablePath = Join-Path $repoRoot "scene-seeds-native200042.properties"
$native290002TablePath = Join-Path $repoRoot "scene-seeds-native290002.properties"
foreach ($tablePath in @($seedTablePath, $native290002TablePath)) {
  if (-not (Test-Path -LiteralPath $tablePath -PathType Leaf)) {
    throw "Seed table not found: $tablePath"
  }
}

$javaCommand = Get-Command java.exe -CommandType Application -ErrorAction Stop
$javaExe = $javaCommand.Source
$runtimeClassesPath = Resolve-WorkspacePath $repoRoot $RuntimeClassesDir
if (-not (Test-Path -LiteralPath $runtimeClassesPath -PathType Container)) {
  throw "RuntimeClassesDir not found: $runtimeClassesPath"
}
$commonsMath = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) ".m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar"
if (-not (Test-Path -LiteralPath $commonsMath -PathType Leaf)) {
  throw "Commons Math dependency not found: $commonsMath"
}
$classPath = $runtimeClassesPath + ";" + $commonsMath

$mode = if ($PreflightOnly) { "preflight" } else { "full" }
$experimentId = "native200042-config-2-0-0-0-4-2-ID2-10-sourceIDs13-17-21-24-25-20260818-$mode"
$detailsPath = Join-Path $outPath "run-details.csv"
$statusPath = Join-Path $outPath "run-status.csv"
$perSeedPath = Join-Path $outPath "per-seed-profit.csv"
$sceneSummaryPath = Join-Path $outPath "scene-summary.csv"
$summaryJsonPath = Join-Path $outPath "summary.json"
$progressPath = Join-Path $outPath "progress.json"
$metadataPath = Join-Path $outPath "metadata.txt"
$seedReferencePath = Join-Path $outPath "seed-reference.csv"
$contractPath = Join-Path $outPath "experiment-contract.json"
$completePath = Join-Path $outPath "run-complete.txt"

$allExpectedRuns = @(
  foreach ($scene in $scenes) {
    for ($perRun = 0; $perRun -lt $SourceSeedIds.Count; $perRun++) {
      $sourceNative290002SeedId = $SourceSeedIds[$perRun]
      $expectedSeed = Get-ExpectedSeed $seedTablePath $scene.Name $perRun
      $sourceTableSeed = Get-ExpectedSeed $native290002TablePath $scene.Name ($sourceNative290002SeedId - 10)
      if ($expectedSeed -ne $sourceTableSeed) {
        throw "native200042 mapping mismatch for $($scene.Name) perRun=${perRun}: expected=$expectedSeed source=$sourceTableSeed"
      }
      [pscustomobject]@{
        runKey = "{0}_perrun{1}_seed{2}_source{3}" -f $scene.Id, $perRun, ($LogicalSeedStart + $perRun), $sourceNative290002SeedId
        sceneId = $scene.Id
        sceneName = $scene.Name
        tasks = $scene.Tasks
        satellites = $scene.Sats
        perRun = $perRun
        logicalSeedId = $LogicalSeedStart + $perRun
        sourceNative290002SeedId = $sourceNative290002SeedId
        expectedSeed = $expectedSeed
      }
    }
  }
)
$expectedRuns = if ($PreflightOnly) {
  @($allExpectedRuns | Where-Object {
    ($_.sceneId -eq "ID2" -and $_.perRun -eq 0) -or
    ($_.sceneId -eq "ID10" -and $_.perRun -eq 4)
  })
} else {
  $allExpectedRuns
}
$plannedRunCount = $expectedRuns.Count

$contract = [ordered]@{
  schemaVersion = 1
  experimentId = $experimentId
  mode = $mode
  config = $Config
  seedSource = $SeedSource
  seedMapping = "native290002 seed IDs 13,17,21,24,25 -> native200042 perRun 0,1,2,3,4"
  logicalSeedIds = @($LogicalSeedStart..($LogicalSeedStart + $SourceSeedIds.Count - 1))
  sourceNative290002SeedIds = @($SourceSeedIds)
  plannedRunCount = $plannedRunCount
  scenes = @($expectedRuns | Select-Object sceneId, sceneName, tasks, satellites -Unique)
  sceneRoot = $sceneRoot
  javaExe = $javaExe
  runtimeClassesDir = $runtimeClassesPath
  classPath = $classPath
  processPriority = "Normal"
  deterministic = $true
  sourceHashes = [ordered]@{
    native200042SeedTable = Get-FileSha256 $seedTablePath
    native290002SeedTable = Get-FileSha256 $native290002TablePath
    sceneSeedTableJava = Get-FileSha256 (Join-Path $repoRoot "src\main\java\main\SceneSeedTable.java")
    runSingleHybridConfig = Get-FileSha256 (Join-Path $repoRoot "src\main\java\main\RunSingleHybridConfig.java")
    runner = Get-FileSha256 $PSCommandPath
  }
}
$contractJson = $contract | ConvertTo-Json -Depth 8
if (Test-Path -LiteralPath $contractPath -PathType Leaf) {
  $existingContract = Get-Content -LiteralPath $contractPath -Raw -Encoding UTF8 | ConvertFrom-Json
  if (($existingContract | ConvertTo-Json -Depth 8 -Compress) -ne ($contract | ConvertTo-Json -Depth 8 -Compress)) {
    throw "Experiment contract changed; use a new OutputDir: $contractPath"
  }
} else {
  $contractJson | Set-Content -LiteralPath $contractPath -Encoding UTF8
}

$expectedRuns |
  Select-Object sceneId, sceneName, perRun, logicalSeedId, sourceNative290002SeedId, expectedSeed |
  Export-Csv -LiteralPath $seedReferencePath -NoTypeInformation -Encoding UTF8

if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
  @(
    "experimentId=$experimentId",
    "config=$Config",
    "seedSource=$SeedSource",
    "seedMapping=native290002 IDs 13,17,21,24,25 -> native200042 perRun 0,1,2,3,4",
    "mode=$mode",
    "scenes=$(@($expectedRuns | Select-Object -ExpandProperty sceneId -Unique) -join ',')",
    "logicalSeedIds=$(@($LogicalSeedStart..($LogicalSeedStart + $SourceSeedIds.Count - 1)) -join ',')",
    "sourceNative290002SeedIds=$($SourceSeedIds -join ',')",
    "nRunPerProcess=1",
    "budget=automatic RunSingleHybridConfig budget",
    "javaExe=$javaExe",
    "priority=Normal",
    "deterministic=true",
    "started=$(Get-Date -Format o)"
  ) | Set-Content -LiteralPath $metadataPath -Encoding UTF8
}

$details = @()
if (Test-Path -LiteralPath $detailsPath -PathType Leaf) {
  $details = @(Import-Csv -LiteralPath $detailsPath)
}
$completedKeys = @{}
foreach ($row in $details) {
  if ($row.status -eq "completed" -and -not [string]::IsNullOrWhiteSpace($row.runKey)) {
    $completedKeys[$row.runKey] = $true
  }
}
$failureAttempts = 0
$lastRunKey = ""
Write-Progress $progressPath "running" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey

foreach ($run in $expectedRuns) {
  $lastRunKey = $run.runKey
  if ($completedKeys.ContainsKey($run.runKey)) {
    Write-Host "[SKIP] $($run.runKey)"
    continue
  }

  $sceneDir = Join-Path $sceneRoot $run.sceneName
  $runDir = Join-Path $logsDir $run.runKey
  New-Item -ItemType Directory -Force -Path $runDir | Out-Null
  $stdoutPath = Join-Path $runDir "run.stdout.log"
  $stderrPath = Join-Path $runDir "run.stderr.log"
  $metricsPath = Join-Path $runDir "metrics.properties"
  $pidPath = Join-Path $runDir "run.pid.txt"
  $commandPath = Join-Path $commandsDir "$($run.runKey).json"
  $argsList = @(
    "-Dtuning.config.path=tuning.properties",
    "-Dautohyb.sceneRoot=$sceneRoot",
    "-Dautohyb.deterministic=true",
    "-Dautohyb.experimentId=$experimentId",
    "-Devaluation.seedTable.source=$SeedSource",
    "-Devaluation.perRunStart=$($run.perRun)",
    "-Devaluation.runSeedLog=true",
    "-cp", $classPath,
    "main.RunSingleHybridConfig",
    "--quiet",
    "--record-baseline",
    "--baseline-file", $metricsPath,
    "true",
    "true",
    $sceneDir,
    "$($run.tasks)",
    "$($run.satellites)",
    $Config,
    "1"
  )
  [ordered]@{
    runKey = $run.runKey
    javaExe = $javaExe
    workingDirectory = $repoRoot
    arguments = $argsList
    expectedScene = $run.sceneName
    expectedPerRun = $run.perRun
    expectedLogicalSeedId = $run.logicalSeedId
    sourceNative290002SeedId = $run.sourceNative290002SeedId
    expectedSeed = $run.expectedSeed
    priority = "Normal"
    started = Get-Date -Format o
  } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $commandPath -Encoding UTF8

  $attempt = 0
  $completed = $false
  while (-not $completed -and $attempt -lt $MaxAttempts) {
    $attempt++
    Write-Host ("[{0}] {1} scene={2} perRun={3} logicalSeedId={4} sourceNative290002SeedId={5} expectedSeed={6} priority=Normal attempt={7}" -f
      (Get-Date -Format "HH:mm:ss"), $run.runKey, $run.sceneName, $run.perRun, $run.logicalSeedId,
      $run.sourceNative290002SeedId, $run.expectedSeed, $attempt)
    $processResult = Invoke-JavaProcess $javaExe $argsList $repoRoot $stdoutPath $stderrPath $pidPath
    $logText = if (Test-Path -LiteralPath $stdoutPath -PathType Leaf) {
      Get-Content -LiteralPath $stdoutPath -Raw -Encoding UTF8
    } else {
      ""
    }
    $seedMatch = [regex]::Match($logText, '(?m)^\[RunSeed\] source=([^\s]+) table=([^\s]+) scene=([^\s]+) perRun=(\d+) seedId=(\d+) seed=(-?\d+)\r?$')
    $ratioMatch = [regex]::Match($logText, '(?m)^Optimized profit ratio:\s*([0-9.]+)%\r?$')
    $elapsedMatch = [regex]::Match($logText, '(?m)^Elapsed:\s*([0-9.]+)\s*s\r?$')
    $parseStatus = "ok"
    if ($processResult.ExitCode -ne 0) {
      $parseStatus = "exit-$($processResult.ExitCode)"
    } elseif (-not $seedMatch.Success -or -not $ratioMatch.Success -or -not $elapsedMatch.Success -or
      -not (Test-Path -LiteralPath $metricsPath -PathType Leaf)) {
      $parseStatus = "parse"
    } else {
      $actualSource = $seedMatch.Groups[1].Value
      $actualTable = $seedMatch.Groups[2].Value
      $actualScene = $seedMatch.Groups[3].Value
      $actualPerRun = [int]$seedMatch.Groups[4].Value
      $actualLogicalSeedId = [int]$seedMatch.Groups[5].Value
      $actualSeed = [long]$seedMatch.Groups[6].Value
      if ($actualSource -ne $SeedSource -or $actualTable -ne "scene-seeds-native200042.properties" -or
        $actualScene -ne $run.sceneName -or $actualPerRun -ne [int]$run.perRun -or
        $actualLogicalSeedId -ne [int]$run.logicalSeedId -or $actualSeed -ne [long]$run.expectedSeed) {
        $parseStatus = "seed-mismatch"
      }
    }

    Append-CsvRow ([pscustomobject]@{
      runKey = $run.runKey
      attempt = $attempt
      status = $parseStatus
      exitCode = $processResult.ExitCode
      expectedSeed = $run.expectedSeed
      actualSeed = if ($seedMatch.Success) { $seedMatch.Groups[6].Value } else { "" }
      priority = $processResult.Priority
      processCpuSeconds = $processResult.ProcessCpuSeconds
      started = $processResult.Started.ToString("o")
      finished = $processResult.Finished.ToString("o")
      stdoutPath = $stdoutPath
      stderrPath = $stderrPath
      metricsPath = $metricsPath
    }) $statusPath

    if ($parseStatus -eq "ok") {
      $profit = [double](Get-PropertyValue $metricsPath "metricValue")
      $profitRatio = [double](Get-PropertyValue $metricsPath "profitRatio")
      $elapsedSeconds = [double](Get-PropertyValue $metricsPath "elapsedMillis") / 1000.0
      $nPop = [int](Get-PropertyValue $metricsPath "nPop")
      $maxFes = [int](Get-PropertyValue $metricsPath "maxFes")
      $details += [pscustomobject]@{
        runKey = $run.runKey
        sceneId = $run.sceneId
        sceneName = $run.sceneName
        tasks = $run.tasks
        satellites = $run.satellites
        perRun = $run.perRun
        logicalSeedId = $run.logicalSeedId
        sourceNative290002SeedId = $run.sourceNative290002SeedId
        expectedSeed = $run.expectedSeed
        actualSeed = $seedMatch.Groups[6].Value
        config = $Config
        profit = $profit
        profitRatio = $profitRatio
        elapsedSeconds = $elapsedSeconds
        nPop = $nPop
        maxFes = $maxFes
        priority = $processResult.Priority
        processCpuSeconds = $processResult.ProcessCpuSeconds
        started = $processResult.Started.ToString("o")
        finished = $processResult.Finished.ToString("o")
        stdoutPath = $stdoutPath
        stderrPath = $stderrPath
        metricsPath = $metricsPath
        status = "completed"
      }
      $completedKeys[$run.runKey] = $true
      Write-Summaries $details $detailsPath $perSeedPath $sceneSummaryPath $summaryJsonPath $experimentId $plannedRunCount
      Write-Progress $progressPath "running" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
      Write-Host ("[DONE] {0} profit={1} ratio={2}% elapsed={3}s nPop={4} maxFes={5}" -f
        $run.runKey, $profit, $profitRatio, $elapsedSeconds, $nPop, $maxFes)
      $completed = $true
    } else {
      $failureAttempts++
      Write-Progress $progressPath "running" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
      Write-Warning "Run failed: $($run.runKey), status=$parseStatus, stdout=$stdoutPath, stderr=$stderrPath"
      if ($attempt -ge $MaxAttempts) {
        Write-Progress $progressPath "stopped-after-failure" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
        throw "Run failed after $attempt attempt(s): $($run.runKey), status=$parseStatus"
      }
    }
  }
}

Write-Summaries $details $detailsPath $perSeedPath $sceneSummaryPath $summaryJsonPath $experimentId $plannedRunCount
if ($completedKeys.Count -ne $plannedRunCount) {
  throw "Batch incomplete: expected=$plannedRunCount, completed=$($completedKeys.Count)"
}
@(
  "experimentId=$experimentId",
  "config=$Config",
  "seedSource=$SeedSource",
  "seedMapping=native290002 IDs 13,17,21,24,25 -> native200042 perRun 0,1,2,3,4",
  "mode=$mode",
  "completedRuns=$($completedKeys.Count)",
  "status=completed",
  "finished=$(Get-Date -Format o)"
) | Set-Content -LiteralPath $completePath -Encoding UTF8
Write-Progress $progressPath "completed" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
Add-Content -LiteralPath $metadataPath -Value ("finished=$(Get-Date -Format o)") -Encoding UTF8
Add-Content -LiteralPath $metadataPath -Value "status=completed" -Encoding UTF8
Write-Host "Done."
Write-Host "Details: $detailsPath"
Write-Host "Per-seed profits: $perSeedPath"
Write-Host "Scene summary: $sceneSummaryPath"
