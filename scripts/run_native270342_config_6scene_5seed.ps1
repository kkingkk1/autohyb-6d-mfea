param(
  [string]$OutputDir = "outputs\native270342-config-2-7-0-3-4-2-id1-3-5-7-9-10-seeds10-14-20260805-retry2",
  [string]$RuntimeClassesDir = "target\classes",
  [int]$MaxAttempts = 1
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ExperimentId = "native270342-config-2-7-0-3-4-2-id1-3-5-7-9-10-seeds10-14-20260805-retry2"
$SeedSource = "native270342"
$Config = "2,7,0,3,4,2"
$SeedCount = 5
$SeedStart = 10

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
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $stream = [System.IO.File]::OpenRead($path)
  try {
    return ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
  } finally {
    $stream.Dispose()
    $sha.Dispose()
  }
}

function Append-CsvRow([object]$row, [string]$path) {
  if (Test-Path -LiteralPath $path -PathType Leaf) {
    $row | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8 -Append
  } else {
    $row | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8
  }
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
  [string]$pidPath) {
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

function Write-Summary([string]$detailsPath, [string]$summaryPath) {
  if (-not (Test-Path -LiteralPath $detailsPath -PathType Leaf)) {
    return
  }
  $rows = @(Import-Csv -LiteralPath $detailsPath)
  $summary = foreach ($group in ($rows | Group-Object sceneId)) {
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
  @($summary) | Sort-Object sceneId |
    Export-Csv -LiteralPath $summaryPath -NoTypeInformation -Encoding UTF8
}

function Write-Progress(
  [string]$path,
  [string]$status,
  [int]$expected,
  [int]$completed,
  [int]$failureAttempts,
  [string]$lastRunKey) {
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

if ($MaxAttempts -lt 1 -or $MaxAttempts -gt 3) {
  throw "MaxAttempts must be in 1..3, got: $MaxAttempts"
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
  [ordered]@{ Id = "ID1"; Name = "500-5-0-0-24"; Tasks = 500; Sats = 5 },
  [ordered]@{ Id = "ID3"; Name = "1000-10-0-0-12"; Tasks = 1000; Sats = 10 },
  [ordered]@{ Id = "ID5"; Name = "1000-20-0-0-12"; Tasks = 1000; Sats = 20 },
  [ordered]@{ Id = "ID7"; Name = "4000-40-0-0-6"; Tasks = 4000; Sats = 40 },
  [ordered]@{ Id = "ID9"; Name = "8000-80-0-0-12"; Tasks = 8000; Sats = 80 },
  [ordered]@{ Id = "ID10"; Name = "10000-100-0-6-12"; Tasks = 10000; Sats = 100 }
)

foreach ($scene in $scenes) {
  $sceneDir = Join-Path $sceneRoot $scene.Name
  if (-not (Test-Path -LiteralPath $sceneDir -PathType Container)) {
    throw "Missing scene directory: $sceneDir"
  }
}

$seedTablePath = Join-Path $repoRoot "scene-seeds-native270342.properties"
if (-not (Test-Path -LiteralPath $seedTablePath -PathType Leaf)) {
  throw "Seed table not found: $seedTablePath"
}

$javaCommand = Get-Command javaw.exe -CommandType Application -ErrorAction SilentlyContinue
if ($null -eq $javaCommand) {
  $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction Stop
}
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

$detailsPath = Join-Path $outPath "run-details.csv"
$statusPath = Join-Path $outPath "run-status.csv"
$summaryPath = Join-Path $outPath "summary.csv"
$progressPath = Join-Path $outPath "progress.json"
$metadataPath = Join-Path $outPath "metadata.txt"
$contractPath = Join-Path $outPath "experiment-contract.json"
$completePath = Join-Path $outPath "batch-complete.txt"

$expectedRuns = @($scenes | ForEach-Object {
  $scene = $_
  0..($SeedCount - 1) | ForEach-Object {
    $perRun = $_
    [pscustomobject]@{
      runKey = "{0}_perrun{1}_seed{2}" -f $scene.Id, $perRun, ($SeedStart + $perRun)
      sceneId = $scene.Id
      sceneName = $scene.Name
      tasks = $scene.Tasks
      satellites = $scene.Sats
      perRun = $perRun
      seedId = $SeedStart + $perRun
      expectedSeed = Get-ExpectedSeed $seedTablePath $scene.Name $perRun
    }
  }
})
$plannedRunCount = $expectedRuns.Count

if (-not (Test-Path -LiteralPath $detailsPath -PathType Leaf)) {
  "runKey,sceneId,sceneName,tasks,satellites,perRun,seedId,expectedSeed,actualSeed,config,profit,profitRatio,elapsedSeconds,nPop,maxFes,priority,processCpuSeconds,started,finished,stdoutPath,stderrPath,metricsPath" |
    Set-Content -LiteralPath $detailsPath -Encoding UTF8
}

$contract = [ordered]@{
  schemaVersion = 1
  experimentId = $ExperimentId
  config = $Config
  seedSource = $SeedSource
  seedMapping = "native290002 seed IDs 12,17,18,19,14 -> native270342 perRun 0,1,2,3,4"
  perRunStart = 0
  seedCount = $SeedCount
  scenes = @($scenes)
  sceneRoot = $sceneRoot
  javaExe = $javaExe
  runtimeClassesDir = $runtimeClassesPath
  classPath = $classPath
  processPriority = "Normal"
  deterministic = $true
  sourceHashes = [ordered]@{
    sceneSeedTable = Get-FileSha256 $seedTablePath
    sceneSeedTableJava = Get-FileSha256 (Join-Path $repoRoot "src\main\java\main\SceneSeedTable.java")
    runSingleHybridConfig = Get-FileSha256 (Join-Path $repoRoot "src\main\java\main\RunSingleHybridConfig.java")
    runner = Get-FileSha256 (Join-Path $repoRoot "scripts\run_native270342_config_6scene_5seed.ps1")
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

@(
  "experimentId=$ExperimentId",
  "config=$Config",
  "seedSource=$SeedSource",
  "seedMapping=native290002 IDs 12,17,18,19,14 -> native270342 perRun 0,1,2,3,4",
  "scenes=$(@($scenes | ForEach-Object { $_.Id }) -join ',')",
  "seedIds=$SeedStart..$($SeedStart + $SeedCount - 1)",
  "nRunPerProcess=1",
  "budget=automatic RunSingleHybridConfig budget",
  "javaExe=$javaExe",
  "priority=Normal",
  "deterministic=true",
  "started=$(Get-Date -Format o)"
) | Set-Content -LiteralPath $metadataPath -Encoding UTF8

$completedKeys = @{}
foreach ($row in @(Import-Csv -LiteralPath $detailsPath)) {
  if ($null -ne $row.runKey -and $row.runKey.Trim()) {
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

  $scene = $scenes | Where-Object { $_.Id -eq $run.sceneId } | Select-Object -First 1
  $sceneDir = Join-Path $sceneRoot $scene.Name
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
    "-Dautohyb.experimentId=$ExperimentId",
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
    "$($scene.Tasks)",
    "$($scene.Sats)",
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
    expectedSeedId = $run.seedId
    expectedSeed = $run.expectedSeed
    priority = "Normal"
    started = Get-Date -Format o
  } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $commandPath -Encoding UTF8

  $attempt = 0
  $completed = $false
  while (-not $completed -and $attempt -lt $MaxAttempts) {
    $attempt++
    Write-Host ("[{0}] {1} scene={2} perRun={3} seedId={4} expectedSeed={5} priority=Normal attempt={6}" -f
      (Get-Date -Format "HH:mm:ss"), $run.runKey, $run.sceneName, $run.perRun, $run.seedId, $run.expectedSeed, $attempt)
    $processResult = Invoke-JavaProcess $javaExe $argsList $repoRoot $stdoutPath $stderrPath $pidPath
    $logText = if (Test-Path -LiteralPath $stdoutPath -PathType Leaf) { Get-Content -LiteralPath $stdoutPath -Raw -Encoding UTF8 } else { "" }
    $seedMatch = [regex]::Match($logText, '(?m)^\[RunSeed\] source=([^\s]+) table=([^\s]+) scene=([^\s]+) perRun=(\d+) seedId=(\d+) seed=(-?\d+)\r?$')
    $ratioMatch = [regex]::Match($logText, '(?m)^Optimized profit ratio:\s*([0-9.]+)%\r?$')
    $elapsedMatch = [regex]::Match($logText, '(?m)^Elapsed:\s*([0-9.]+)\s*s\r?$')
    $parseStatus = "ok"
    if ($processResult.ExitCode -ne 0) {
      $parseStatus = "exit-$($processResult.ExitCode)"
    } elseif (-not $seedMatch.Success -or -not $ratioMatch.Success -or -not $elapsedMatch.Success -or -not (Test-Path -LiteralPath $metricsPath -PathType Leaf)) {
      $parseStatus = "parse"
    } else {
      $actualSource = $seedMatch.Groups[1].Value
      $actualTable = $seedMatch.Groups[2].Value
      $actualScene = $seedMatch.Groups[3].Value
      $actualPerRun = [int]$seedMatch.Groups[4].Value
      $actualSeedId = [int]$seedMatch.Groups[5].Value
      $actualSeed = [long]$seedMatch.Groups[6].Value
      if ($actualSource -ne $SeedSource -or $actualTable -ne "scene-seeds-native270342.properties" -or
          $actualScene -ne $run.sceneName -or $actualPerRun -ne [int]$run.perRun -or
          $actualSeedId -ne [int]$run.seedId -or $actualSeed -ne [long]$run.expectedSeed) {
        $parseStatus = "seed-mismatch"
      }
    }

    $statusRow = [pscustomobject]@{
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
    }
    Append-CsvRow $statusRow $statusPath

    if ($parseStatus -eq "ok") {
      $profit = [double](Get-PropertyValue $metricsPath "metricValue")
      $profitRatio = [double](Get-PropertyValue $metricsPath "profitRatio")
      $elapsedSeconds = [double](Get-PropertyValue $metricsPath "elapsedMillis") / 1000.0
      $nPop = [int](Get-PropertyValue $metricsPath "nPop")
      $maxFes = [int](Get-PropertyValue $metricsPath "maxFes")
      Append-CsvRow ([pscustomobject]@{
        runKey = $run.runKey
        sceneId = $run.sceneId
        sceneName = $run.sceneName
        tasks = $run.tasks
        satellites = $run.satellites
        perRun = $run.perRun
        seedId = $run.seedId
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
      }) $detailsPath
      $completedKeys[$run.runKey] = $true
      Write-Summary $detailsPath $summaryPath
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

Write-Summary $detailsPath $summaryPath
if ($completedKeys.Count -ne $plannedRunCount) {
  throw "Batch incomplete: expected=$plannedRunCount, completed=$($completedKeys.Count)"
}
@(
  "experimentId=$ExperimentId",
  "config=$Config",
  "seedSource=$SeedSource",
  "seedMapping=native290002 IDs 12,17,18,19,14 -> native270342 perRun 0,1,2,3,4",
  "scenes=$(@($scenes | ForEach-Object { $_.Id }) -join ',')",
  "seedIds=$SeedStart..$($SeedStart + $SeedCount - 1)",
  "completedRuns=$($completedKeys.Count)",
  "finished=$(Get-Date -Format o)",
  "status=completed"
) | Set-Content -LiteralPath $completePath -Encoding UTF8
Write-Progress $progressPath "completed" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
Add-Content -LiteralPath $metadataPath -Value ("finished=$(Get-Date -Format o)") -Encoding UTF8
Add-Content -LiteralPath $metadataPath -Value "status=completed" -Encoding UTF8
Write-Host "Done."
Write-Host "Details: $detailsPath"
Write-Host "Summary: $summaryPath"
