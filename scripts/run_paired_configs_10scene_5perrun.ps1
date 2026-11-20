param(
  [string]$SearchOutDir = "outputs\mfea-multiscene-full-20260616",
  [string]$OutputDir = "outputs\paired-native-perrun0-4-10scene-20260724-r01",
  [long]$ReferenceSeedIdStart = 10,
  [ValidateSet("native290002", "native410000")]
  [string]$SeedSource = "native290002",
  [int]$PerRunStart = 0,
  [int]$PerRuns = 5,
  [string]$ExperimentId = "paired-native-perrun0-4-10scene-20260724-r01",
  [string]$RuntimeClassesDir = "target\classes",
  [string]$ReuseOutputDir = "",
  [switch]$IncludeParents,
  [switch]$PreflightOnly,
  [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

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

function Get-TextSha256([string]$text) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
}

function Get-SourceHashes([string]$repoRoot, [string[]]$relativePaths) {
  $hashes = [ordered]@{}
  foreach ($relativePath in $relativePaths) {
    $absolutePath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
      throw "Source file not found: $absolutePath"
    }
    $hashes[$relativePath] = (Get-FileHash -LiteralPath $absolutePath -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  return $hashes
}

function Get-LastRegexMatch([string]$text, [string]$pattern) {
  $matches = [System.Text.RegularExpressions.Regex]::Matches(
    $text,
    $pattern,
    [System.Text.RegularExpressions.RegexOptions]::Multiline
  )
  if ($matches.Count -eq 0) {
    return $null
  }
  return $matches[$matches.Count - 1]
}

function Get-CpuPercent {
  try {
    $sample = Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
    if ($null -ne $sample) {
      return [double]$sample.PercentProcessorTime
    }
  } catch {
    return [double]::NaN
  }
  return [double]::NaN
}

function Append-CsvRow([object]$row, [string]$path) {
  if (Test-Path -LiteralPath $path) {
    $row | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8 -Append
  } else {
    $row | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8
  }
}

function Invoke-JavaProcess(
  [string]$javaExe,
  [string[]]$argsList,
  [string]$workingDirectory,
  [string]$logPath,
  [string]$errPath) {
  $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $javaExe
  $startInfo.WorkingDirectory = $workingDirectory
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  foreach ($arg in $argsList) {
    [void]$startInfo.ArgumentList.Add([string]$arg)
  }

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

  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.WaitForExit()
  $finished = Get-Date
  $stdout = $stdoutTask.GetAwaiter().GetResult()
  $stderr = $stderrTask.GetAwaiter().GetResult()
  [System.IO.File]::WriteAllText($logPath, $stdout, [System.Text.UTF8Encoding]::new($false))
  [System.IO.File]::WriteAllText($errPath, $stderr, [System.Text.UTF8Encoding]::new($false))
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
  if (-not (Test-Path -LiteralPath $detailsPath)) {
    return
  }
  $rows = @(Import-Csv -LiteralPath $detailsPath)
  $summary = foreach ($group in ($rows | Group-Object sceneId, configKey)) {
    $first = $group.Group[0]
    [double[]]$ratios = @($group.Group | ForEach-Object { [double]$_.profitRatio })
    [double[]]$elapsed = @($group.Group | ForEach-Object { [double]$_.elapsedSeconds })
    [pscustomobject]@{
      sceneId = $first.sceneId
      sceneName = $first.sceneName
      configKey = $first.configKey
      entrypoint = $first.entrypoint
      config = $first.config
      n = $group.Count
      meanProfitRatio = "{0:F4}" -f (Mean $ratios)
      stdProfitRatio = "{0:F4}" -f (StdSample $ratios)
      minProfitRatio = "{0:F2}" -f (($ratios | Measure-Object -Minimum).Minimum)
      maxProfitRatio = "{0:F2}" -f (($ratios | Measure-Object -Maximum).Maximum)
      meanElapsedSeconds = "{0:F2}" -f (Mean $elapsed)
      totalElapsedSeconds = "{0:F2}" -f (($elapsed | Measure-Object -Sum).Sum)
    }
  }
  @($summary) | Sort-Object sceneId, configKey |
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

if ($PerRunStart -lt 0) {
  throw "PerRunStart must be non-negative, got: $PerRunStart"
}
if ($ReferenceSeedIdStart -ne 10) {
  throw "ReferenceSeedIdStart must be 10 for the selected scene seed table, got: $ReferenceSeedIdStart"
}
if ($PerRuns -le 0) {
  throw "PerRuns must be positive, got: $PerRuns"
}
$perRunEndDecimal = [decimal]$PerRunStart + [decimal]$PerRuns - 1
if ($perRunEndDecimal -gt [int]::MaxValue) {
  throw "perRun end exceeds Int32: $perRunEndDecimal"
}
$perRunEnd = [int]$perRunEndDecimal
if ($PerRunStart -ne 0 -or $PerRuns -ne 5) {
  throw "Native seed contract requires perRun 0..4, got: $PerRunStart..$perRunEnd"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$outPath = Resolve-WorkspacePath $repoRoot $OutputDir
Assert-OutputPath $repoRoot $outPath
$logsDir = Join-Path $outPath "logs"
$commandsDir = Join-Path $outPath "commands"
New-Item -ItemType Directory -Force -Path $logsDir, $commandsDir | Out-Null

$searchCommandFile = Join-Path $repoRoot (Join-Path $SearchOutDir "mfea_multiscene_full.command.txt")
$classPathEntries = @()
if (Test-Path -LiteralPath $searchCommandFile -PathType Leaf) {
  $commandText = Get-Content -LiteralPath $searchCommandFile -Raw
  if ($commandText -notmatch '^(.*?)\s+-Dtuning\.config\.path=.*?\s+-Dautohyb\.deterministic=true\s+.*?-cp\s+(.+?)\s+main\.MainMFEAMultiScene\s*$') {
    throw "Failed to parse Java executable/classpath from $searchCommandFile"
  }
  $javaExe = $Matches[1].Trim()
  $classPathEntries = @($Matches[2].Trim() -split ';')
} else {
  $javaExe = (Get-Command java -CommandType Application -ErrorAction Stop).Source
  $commonsMath = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) ".m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar"
  if (-not (Test-Path -LiteralPath $commonsMath -PathType Leaf)) {
    throw "Fallback dependency not found: $commonsMath"
  }
  $classPathEntries = @((Join-Path $repoRoot "target\classes"), $commonsMath)
  Write-Warning "Search command file missing; using current Java runtime and target\\classes fallback."
}
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
  throw "Java executable not found: $javaExe"
}
if ($RuntimeClassesDir) {
  $runtimeClassesPath = Resolve-WorkspacePath $repoRoot $RuntimeClassesDir
  if (-not (Test-Path -LiteralPath $runtimeClassesPath -PathType Container)) {
    throw "RuntimeClassesDir not found: $runtimeClassesPath"
  }
  $classPathEntries[0] = $runtimeClassesPath
}
$classPath = $classPathEntries -join ';'
foreach ($classPathEntry in ($classPath -split ';')) {
  $resolvedEntry = Resolve-WorkspacePath $repoRoot $classPathEntry
  if (-not (Test-Path -LiteralPath $resolvedEntry)) {
    throw "Classpath entry not found: $resolvedEntry"
  }
}

$sceneRoot = & (Join-Path $PSScriptRoot "Resolve-SceneRoot.ps1")
$scenes = @(
  [ordered]@{ Id = "ID1"; Name = "500-5-0-0-24"; Tasks = 500; Sats = 5 },
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
$configs = @(
  [ordered]@{ Key = "hybrid-410000"; Kind = "hybrid"; Entrypoint = "main.RunSingleHybridConfig"; Config = "4,1,0,0,0,0" },
  [ordered]@{ Key = "hybrid-290002"; Kind = "hybrid"; Entrypoint = "main.RunSingleHybridConfig"; Config = "2,9,0,0,0,2" }
)
if ($IncludeParents) {
  $configs += @(
    [ordered]@{ Key = "parent-4"; Kind = "parent"; Entrypoint = "main.RunSingleParentConfig"; Config = "4" },
    [ordered]@{ Key = "parent-1"; Kind = "parent"; Entrypoint = "main.RunSingleParentConfig"; Config = "1" }
  )
}

$sceneManifest = foreach ($scene in $scenes) {
  $sceneDir = Join-Path $sceneRoot $scene.Name
  if (-not (Test-Path -LiteralPath $sceneDir -PathType Container)) {
    throw "Missing scene directory: $sceneDir"
  }
  foreach ($file in (Get-ChildItem -LiteralPath $sceneDir -Recurse -File | Sort-Object FullName)) {
    [pscustomobject]@{
      sceneId = $scene.Id
      sceneName = $scene.Name
      relativePath = $file.FullName.Substring($sceneDir.Length).TrimStart('\')
      length = $file.Length
      sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
  }
}
$sceneManifestJson = @($sceneManifest) | ConvertTo-Json -Depth 4 -Compress
$sceneManifestHash = Get-TextSha256 $sceneManifestJson
$sceneManifestPath = Join-Path $outPath "scene-manifest.csv"
@($sceneManifest) | Export-Csv -LiteralPath $sceneManifestPath -NoTypeInformation -Encoding UTF8

$sourceHashPaths = @(
  "src\main\java\main\EvaluationHyb.java",
  "src\main\java\main\RunSingleHybridConfig.java",
  "src\main\java\main\SceneSeedTable.java",
  "scene-seeds-native290002.properties",
  "scene-seeds-native410000.properties",
  "tuning.properties",
  "pom.xml",
  "scripts\run_paired_configs_10scene_5perrun.ps1"
)
if ($IncludeParents) {
  $sourceHashPaths += @(
    "src\main\java\main\RunSingleParentConfig.java",
    "src\main\java\main\SingleAlgorithmOptimizer.java"
  )
}
$sourceHashes = Get-SourceHashes $repoRoot $sourceHashPaths
$runtimeClassPaths = @(
  "main\EvaluationHyb.class",
  "main\RunSingleHybridConfig.class",
  "main\SceneSeedTable.class"
)
if ($IncludeParents) {
  $runtimeClassPaths += @(
    "main\RunSingleParentConfig.class",
    "main\SingleAlgorithmOptimizer.class"
  )
}
$runtimeClassHashes = [ordered]@{}
foreach ($relativeClassPath in $runtimeClassPaths) {
  $classFilePath = Join-Path $classPathEntries[0] $relativeClassPath
  if (-not (Test-Path -LiteralPath $classFilePath -PathType Leaf)) {
    throw "Runtime class not found: $classFilePath"
  }
  $runtimeClassHashes[$relativeClassPath] = (Get-FileHash -LiteralPath $classFilePath -Algorithm SHA256).Hash.ToLowerInvariant()
}
$gitHead = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Failed to resolve Git HEAD" }
$logicalProcessors = [int](Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors

$expectedRunCount = @($scenes).Count * $PerRuns * @($configs).Count
if ($ValidateOnly) {
  Write-Host "Validation passed."
  Write-Host "Expected runs: $expectedRunCount"
  Write-Host "Configs: $($configs.Key -join ', ')"
  Write-Host "Seed source: $SeedSource"
  Write-Host "perRun: $PerRunStart..$perRunEnd"
  Write-Host "Reference seed IDs: $($ReferenceSeedIdStart + $PerRunStart)..$($ReferenceSeedIdStart + $perRunEnd)"
  Write-Host "Seed reference probes: skipped in ValidateOnly mode"
  Write-Host "Priority: Normal; logical processors: $logicalProcessors"
  Write-Host "Output: $outPath"
  exit 0
}

$referenceConfig = $configs | Where-Object { $_.Key -eq "hybrid-410000" } | Select-Object -First 1
if ($null -eq $referenceConfig) {
  throw "Reference config hybrid-410000 is missing"
}
$probeContext = [ordered]@{
  sourceHashes = $sourceHashes
  sceneManifestSha256 = $sceneManifestHash
  referenceConfigKey = $referenceConfig.Key
  referenceConfig = $referenceConfig.Config
  referenceSeedIdStart = $ReferenceSeedIdStart
  seedSource = $SeedSource
  perRunStart = $PerRunStart
  perRunEnd = $perRunEnd
  seedMode = "scene-fixed-table"
}
$probeContextHash = Get-TextSha256 ($probeContext | ConvertTo-Json -Depth 8 -Compress)
$seedReferencePath = Join-Path $outPath "seed-reference.csv"
$reusePath = $null
$reuseContract = $null
if ($ReuseOutputDir) {
  $reusePath = Resolve-WorkspacePath $repoRoot $ReuseOutputDir
  $reuseContractPath = Join-Path $reusePath "experiment-contract.json"
  if (-not (Test-Path -LiteralPath $reuseContractPath -PathType Leaf)) {
    throw "Reuse experiment contract not found: $reuseContractPath"
  }
  $reuseContract = Get-Content -Raw -LiteralPath $reuseContractPath | ConvertFrom-Json
  $reuseSeedSource = if ($reuseContract.PSObject.Properties.Name -contains "seedSource") {
    [string]$reuseContract.seedSource
  } else {
    ""
  }
  if ($reuseContract.sceneManifestSha256 -ne $sceneManifestHash -or $reuseContract.referenceConfigKey -ne $referenceConfig.Key -or $reuseContract.referenceConfig -ne $referenceConfig.Config -or ([long]$reuseContract.referenceSeedIdStart) -ne $ReferenceSeedIdStart -or ([int]$reuseContract.perRunStart) -ne $PerRunStart -or $reuseSeedSource -ne $SeedSource) {
    throw "Reuse experiment scene or seed contract does not match: $reuseContractPath"
  }
  foreach ($relativePath in ($sourceHashPaths | Where-Object { $_ -ne "scripts\run_paired_configs_10scene_5perrun.ps1" })) {
    $reuseHashProperty = $reuseContract.sourceHashes.PSObject.Properties[$relativePath]
    if ($null -eq $reuseHashProperty -or $reuseHashProperty.Value -ne $sourceHashes[$relativePath]) {
      throw "Reuse experiment source hash mismatch: $relativePath"
    }
  }
  foreach ($relativeClassPath in $runtimeClassHashes.Keys) {
    $reuseClassHashProperty = $reuseContract.runtimeClassHashes.PSObject.Properties[$relativeClassPath]
    if ($null -eq $reuseClassHashProperty -or $reuseClassHashProperty.Value -ne $runtimeClassHashes[$relativeClassPath]) {
      throw "Reuse experiment runtime class hash mismatch: $relativeClassPath"
    }
  }
  if (-not (Test-Path -LiteralPath $seedReferencePath)) {
    $reuseSeedReferencePath = Join-Path $reusePath "seed-reference.csv"
    if (-not (Test-Path -LiteralPath $reuseSeedReferencePath -PathType Leaf)) {
      throw "Reuse seed reference not found: $reuseSeedReferencePath"
    }
    $reusedSeedRows = @(Import-Csv -LiteralPath $reuseSeedReferencePath)
    foreach ($row in $reusedSeedRows) {
      $row.probeContextSha256 = $probeContextHash
    }
    $reusedSeedRows | Export-Csv -LiteralPath $seedReferencePath -NoTypeInformation -Encoding UTF8
  }
}
$seedReferenceRows = @()
if (Test-Path -LiteralPath $seedReferencePath) {
  $seedReferenceRows = @(Import-Csv -LiteralPath $seedReferencePath)
  $duplicateScenes = @($seedReferenceRows | Group-Object sceneId | Where-Object { $_.Count -ne 1 })
  if ($duplicateScenes.Count -gt 0) {
    throw "Seed reference contains duplicate scene rows: $($duplicateScenes.Name -join ', ')"
  }
  foreach ($row in $seedReferenceRows) {
    if ($row.probeContextSha256 -ne $probeContextHash) {
      throw "Seed reference context changed; use a new OutputDir. Existing: $seedReferencePath"
    }
  }
}

$nativeSeedStartIndex = 0L
$nativeAnchorIndex = [long]($nativeSeedStartIndex + $perRunEnd)
$nativeAnchorSeedId = [long]($ReferenceSeedIdStart + $perRunEnd)
$probeLogsDir = Join-Path $outPath "seed-probes\logs"
$probeCommandsDir = Join-Path $outPath "seed-probes\commands"
New-Item -ItemType Directory -Force -Path $probeLogsDir, $probeCommandsDir | Out-Null

foreach ($scene in $scenes) {
  $existingReference = $seedReferenceRows | Where-Object { $_.sceneId -eq $scene.Id } | Select-Object -First 1
  if ($null -ne $existingReference) {
    Write-Host "[SEED-PROBE SKIP] $($scene.Id) anchorSeedId=$nativeAnchorSeedId actualSeed=$($existingReference.actualSeed)"
    continue
  }

  $sceneDir = Join-Path $sceneRoot $scene.Name
  $probeKey = "{0}_native-anchor-perrun{1}_{2}" -f $scene.Id, $perRunEnd, $referenceConfig.Key
  $attempt = 1
  do {
    $logPath = Join-Path $probeLogsDir ("{0}.attempt{1}.log" -f $probeKey, $attempt)
    $errPath = Join-Path $probeLogsDir ("{0}.attempt{1}.err.log" -f $probeKey, $attempt)
    $attempt++
  } while ((Test-Path -LiteralPath $logPath) -or (Test-Path -LiteralPath $errPath))
  $attempt--

  $argsList = @(
    "-Dtuning.config.path=tuning.properties",
    "-Dautohyb.deterministic=true",
    "-Dautohyb.experimentId=$ExperimentId-seed-probe",
    "-Devaluation.seedTable.source=$SeedSource",
    "-Devaluation.perRunStart=$perRunEnd",
    "-Devaluation.runSeedLog=true",
    "-cp", $classPath,
    $referenceConfig.Entrypoint,
    "--quiet",
    "true",
    "true",
    $sceneDir,
    "$($scene.Tasks)",
    "$($scene.Sats)",
    $referenceConfig.Config,
    "1"
  )
  $commandRecordPath = Join-Path $probeCommandsDir ("{0}.attempt{1}.json" -f $probeKey, $attempt)
  [ordered]@{
    probeKey = $probeKey
    javaExe = $javaExe
    workingDirectory = $repoRoot
    arguments = $argsList
    referenceSeedId = $nativeAnchorSeedId
    expectedRunIndex = $nativeAnchorIndex
    seedMode = "scene-fixed-table"
    priority = "Normal"
    logicalProcessors = $logicalProcessors
    started = Get-Date -Format o
  } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $commandRecordPath -Encoding UTF8

  Write-Host ("[{0}] SEED-PROBE {1} nativeAnchor=perRun+{2} config={3} priority=Normal" -f
    (Get-Date -Format "HH:mm:ss"), $scene.Id, $perRunEnd, $referenceConfig.Config)
  $processResult = Invoke-JavaProcess $javaExe $argsList $repoRoot $logPath $errPath
  $started = $processResult.Started
  $finished = $processResult.Finished
  $exitCode = $processResult.ExitCode
  $actualPriority = $processResult.Priority
  $logText = if (Test-Path -LiteralPath $logPath) { Get-Content -LiteralPath $logPath -Raw } else { "" }
  $seedMatch = Get-LastRegexMatch $logText '^\[RunSeed\] scene=([^\s]+) perRun=(\d+) seedId=(\d+) seed=(-?\d+)\r?$'
  $ratioMatch = Get-LastRegexMatch $logText '^Optimized profit ratio:\s*([0-9.]+)%\r?$'
  $elapsedMatch = Get-LastRegexMatch $logText '^Elapsed:\s*([0-9.]+)\s*s\r?$'
  if ($exitCode -ne 0) {
    throw "Seed probe failed: $probeKey, exit=$exitCode, log=$logPath, err=$errPath"
  }
  if ($null -eq $seedMatch -or $null -eq $ratioMatch -or $null -eq $elapsedMatch) {
    throw "Seed probe output parse failed: $probeKey, log=$logPath"
  }
  $actualScene = $seedMatch.Groups[1].Value
  $actualPerRun = [int]$seedMatch.Groups[2].Value
  $actualSeedId = [long]$seedMatch.Groups[3].Value
  $actualSeed = [long]$seedMatch.Groups[4].Value
  if ($actualScene -ne $scene.Name -or $actualPerRun -ne $perRunEnd -or $actualSeedId -ne $nativeAnchorSeedId) {
    throw "Scene seed preflight mismatch: $probeKey, expected scene=$($scene.Name), perRun=$perRunEnd, seedId=$nativeAnchorSeedId; actual scene=$actualScene, perRun=$actualPerRun, seedId=$actualSeedId."
  }
  $sceneRunSeedBaseDecimal = [decimal]$actualSeed - [decimal]$perRunEnd
  if ($sceneRunSeedBaseDecimal -lt [long]::MinValue -or $sceneRunSeedBaseDecimal -gt [long]::MaxValue) {
    throw "Scene seed base exceeds Int64: scene=$($scene.Id), actualSeed=$actualSeed, perRun=$perRunEnd"
  }
  $sceneRunSeedBase = [long]$sceneRunSeedBaseDecimal
  $seedReferenceRow = [pscustomobject]@{
    sceneId = $scene.Id
    sceneName = $scene.Name
    referenceConfigKey = $referenceConfig.Key
    referenceConfig = $referenceConfig.Config
    referenceSeedId = $nativeAnchorSeedId
    referencePerRun = $perRunEnd
    anchorPerRun = $perRunEnd
    seedMode = "scene-fixed-table"
    actualSeed = $actualSeed
    runSeedBase = $sceneRunSeedBase
    profitRatio = [double]$ratioMatch.Groups[1].Value
    elapsedSeconds = [double]$elapsedMatch.Groups[1].Value
    priority = $actualPriority
    logicalProcessors = $logicalProcessors
    probeContextSha256 = $probeContextHash
    started = $started.ToString("o")
    finished = $finished.ToString("o")
    logPath = $logPath
    errPath = $errPath
    commandPath = $commandRecordPath
  }
  Append-CsvRow $seedReferenceRow $seedReferencePath
  $seedReferenceRows += $seedReferenceRow
}

$seedReferenceRows = @(Import-Csv -LiteralPath $seedReferencePath)
if ($seedReferenceRows.Count -ne @($scenes).Count) {
  throw "Seed reference incomplete: expected=$(@($scenes).Count), actual=$($seedReferenceRows.Count)"
}
$sceneRunSeedBases = [ordered]@{}
foreach ($scene in $scenes) {
  $seedRow = $seedReferenceRows | Where-Object { $_.sceneId -eq $scene.Id } | Select-Object -First 1
  if ($null -eq $seedRow) {
    throw "Seed reference missing scene: $($scene.Id)"
  }
  $sceneRunSeedBases[$scene.Id] = [long]$seedRow.runSeedBase
}
$nativeRunIndexOffset = [long]($nativeAnchorIndex - $nativeSeedStartIndex)

$expectedRuns = foreach ($scene in $scenes) {
  $sceneRunSeedBase = [long]$sceneRunSeedBases[$scene.Id]
  for ($perRun = $PerRunStart; $perRun -le $perRunEnd; $perRun++) {
    $effectiveRunIndex = [long]$perRun
    $runIndexBase = $effectiveRunIndex
    $effectiveSeedDecimal = [decimal]$sceneRunSeedBase + [decimal]$effectiveRunIndex
    if ($effectiveSeedDecimal -lt [long]::MinValue -or $effectiveSeedDecimal -gt [long]::MaxValue) {
      throw "Effective seed exceeds Int64: scene=$($scene.Id), seedBase=$sceneRunSeedBase, effectiveIndex=$effectiveRunIndex"
    }
    $effectiveSeed = [long]$effectiveSeedDecimal
    foreach ($config in $configs) {
      [pscustomobject]@{
        runKey = "{0}_perrun{1}_{2}" -f $scene.Id, $perRun, $config.Key
        sceneId = $scene.Id
        sceneName = $scene.Name
        tasks = $scene.Tasks
        satellites = $scene.Sats
        perRun = $perRun
        referenceSeedId = [long]($ReferenceSeedIdStart + $perRun)
        effectiveRunIndex = $effectiveRunIndex
        runIndexBase = $runIndexBase
        runSeedBase = $sceneRunSeedBase
        effectiveSeed = $effectiveSeed
        configKey = $config.Key
        kind = $config.Kind
        entrypoint = $config.Entrypoint
        config = $config.Config
        seedMode = if ($config.Kind -eq "hybrid") { "scene-fixed-table" } else { "paired-native-reference" }
      }
    }
  }
}
$expectedPath = Join-Path $outPath "expected-runs.csv"
@($expectedRuns) | Export-Csv -LiteralPath $expectedPath -NoTypeInformation -Encoding UTF8

$contract = [ordered]@{
  schemaVersion = 3
  experimentId = $ExperimentId
  gitHead = $gitHead
  sourceHashes = $sourceHashes
  runtimeClassesDir = $classPathEntries[0]
  runtimeClassHashes = $runtimeClassHashes
  sceneRoot = $sceneRoot
  sceneManifestSha256 = $sceneManifestHash
  runSeedMode = "source-native-perrun-0-4"
  seedSource = $SeedSource
  referenceConfigKey = $referenceConfig.Key
  referenceConfig = $referenceConfig.Config
  referenceSeedIdStart = $ReferenceSeedIdStart
  nativeSeedStartIndex = $nativeSeedStartIndex
  nativeAnchorPerRun = $perRunEnd
  nativeAnchorIndex = $nativeAnchorIndex
  nativeRunIndexOffset = $nativeRunIndexOffset
  seedReferencePath = $seedReferencePath
  seedReferenceSha256 = (Get-FileHash -LiteralPath $seedReferencePath -Algorithm SHA256).Hash.ToLowerInvariant()
  sceneRunSeedBases = $sceneRunSeedBases
  perRunStart = $PerRunStart
  perRunEnd = $perRunEnd
  perRuns = $PerRuns
  expectedRuns = @($expectedRuns).Count
  configKeys = @($configs | ForEach-Object { $_.Key })
  javaExe = $javaExe
  classPath = $classPath
  processPriority = "Normal"
  logicalProcessors = $logicalProcessors
  deterministic = $true
  reuseOutputDir = $reusePath
}
$contractJson = $contract | ConvertTo-Json -Depth 8
$contractPath = Join-Path $outPath "experiment-contract.json"
if (Test-Path -LiteralPath $contractPath) {
  $existingContract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json
  $existingCanonicalJson = $existingContract | ConvertTo-Json -Depth 8 -Compress
  $currentCanonicalJson = $contract | ConvertTo-Json -Depth 8 -Compress
  if ($existingCanonicalJson -ne $currentCanonicalJson) {
    throw "Experiment contract changed; use a new OutputDir. Existing: $contractPath"
  }
} else {
  $contractJson | Set-Content -LiteralPath $contractPath -Encoding UTF8
}

$detailsStem = if ($PreflightOnly) { "seed-preflight-details" } else { "run-details" }
$summaryName = if ($PreflightOnly) { "seed-preflight-summary.csv" } else { "summary.csv" }
$statusName = if ($PreflightOnly) { "seed-preflight-status.csv" } else { "run-status.csv" }
$progressName = if ($PreflightOnly) { "seed-preflight-progress.json" } else { "progress.json" }
$detailsPath = Join-Path $outPath ("{0}.csv" -f $detailsStem)
$summaryPath = Join-Path $outPath $summaryName
$statusPath = Join-Path $outPath $statusName
$progressPath = Join-Path $outPath $progressName
$reuseManifestPath = Join-Path $outPath "reused-runs.csv"
$runsToExecute = if ($PreflightOnly) {
  @($expectedRuns | Where-Object {
    $_.sceneId -eq "ID1" -and ($_.perRun -eq $PerRunStart -or $_.perRun -eq $perRunEnd)
  })
} else {
  @($expectedRuns)
}
$plannedRunCount = @($runsToExecute).Count
$completedKeys = @{}
if ($PreflightOnly -and $null -ne $reusePath) {
  throw "PreflightOnly cannot reuse prior run details."
}
if (-not $PreflightOnly -and $null -ne $reusePath) {
  $reuseDetailsPath = Join-Path $reusePath "run-details.csv"
  if (-not (Test-Path -LiteralPath $reuseDetailsPath -PathType Leaf)) {
    throw "Reuse run details not found: $reuseDetailsPath"
  }
  $expectedByKey = @{}
  foreach ($expectedRun in $expectedRuns) {
    $expectedByKey[$expectedRun.runKey] = $expectedRun
  }
  foreach ($row in (Import-Csv -LiteralPath $reuseDetailsPath)) {
    if (-not $expectedByKey.ContainsKey($row.runKey)) {
      continue
    }
    $expectedRun = $expectedByKey[$row.runKey]
    if ($row.configKey -ne $expectedRun.configKey -or $row.config -ne $expectedRun.config -or ([long]$row.effectiveSeed) -ne ([long]$expectedRun.effectiveSeed)) {
      throw "Reuse run does not match expected contract: $($row.runKey)"
    }
    if (-not $completedKeys.ContainsKey($row.runKey)) {
      Append-CsvRow $row $detailsPath
      Append-CsvRow ([pscustomobject]@{
        runKey = $row.runKey
        sourceOutputDir = $reusePath
        sourceDetailsSha256 = (Get-FileHash -LiteralPath $reuseDetailsPath -Algorithm SHA256).Hash.ToLowerInvariant()
      }) $reuseManifestPath
      $completedKeys[$row.runKey] = $true
    }
  }
}
if (Test-Path -LiteralPath $detailsPath) {
  foreach ($row in (Import-Csv -LiteralPath $detailsPath)) {
    $completedKeys[$row.runKey] = $true
  }
}
Write-Progress $progressPath "validated" $plannedRunCount $completedKeys.Count 0 ""

$failureAttempts = 0
$consecutiveFailures = 0
$lastRunKey = ""
Write-Progress $progressPath "running" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey

foreach ($run in $runsToExecute) {
  $lastRunKey = $run.runKey
  if ($completedKeys.ContainsKey($run.runKey)) {
    Write-Host "[SKIP] $($run.runKey)"
    continue
  }

  $sceneDir = Join-Path $sceneRoot $run.sceneName
  $attempt = 1
  do {
    $logPath = Join-Path $logsDir ("{0}.attempt{1}.log" -f $run.runKey, $attempt)
    $errPath = Join-Path $logsDir ("{0}.attempt{1}.err.log" -f $run.runKey, $attempt)
    $attempt++
  } while ((Test-Path -LiteralPath $logPath) -or (Test-Path -LiteralPath $errPath))
  $attempt--

  $argsList = @(
    "-Dtuning.config.path=tuning.properties",
    "-Dautohyb.deterministic=true",
    "-Dautohyb.experimentId=$ExperimentId",
    "-Devaluation.seedTable.source=$SeedSource"
  )
  if ($run.kind -eq "hybrid") {
    $argsList += "-Devaluation.perRunStart=$($run.perRun)"
    $argsList += "-Devaluation.runSeedLog=true"
  } else {
    $argsList += "-Devaluation.runSeedLog=true"
  }
  $argsList += @("-cp", $classPath, $run.entrypoint)
  if ($run.kind -eq "hybrid") {
    $argsList += "--quiet"
  } else {
    $argsList += "--run-index-base=$($run.effectiveRunIndex)"
    $argsList += "--run-seed=$($run.effectiveSeed)"
  }
  $argsList += @(
    "true",
    "true",
    $sceneDir,
    "$($run.tasks)",
    "$($run.satellites)",
    $run.config,
    "1"
  )

  $commandRecordPath = Join-Path $commandsDir ("{0}.attempt{1}.json" -f $run.runKey, $attempt)
  [ordered]@{
    runKey = $run.runKey
    javaExe = $javaExe
    workingDirectory = $repoRoot
    arguments = $argsList
    referenceSeedId = [long]$run.referenceSeedId
    effectiveRunIndex = [long]$run.effectiveRunIndex
    runIndexBase = [long]$run.runIndexBase
    runSeedBase = [long]$run.runSeedBase
    expectedSeed = [long]$run.effectiveSeed
    seedMode = $run.seedMode
    priority = "Normal"
    logicalProcessors = $logicalProcessors
    started = Get-Date -Format o
  } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $commandRecordPath -Encoding UTF8

  Write-Host ("[{0}] {1} index={2} seed={3} config={4} priority=Normal" -f
    (Get-Date -Format "HH:mm:ss"), $run.runKey, $run.effectiveRunIndex, $run.effectiveSeed, $run.config)
  $cpuBefore = Get-CpuPercent
  $processResult = Invoke-JavaProcess $javaExe $argsList $repoRoot $logPath $errPath
  $started = $processResult.Started
  $finished = $processResult.Finished
  $exitCode = $processResult.ExitCode
  $processCpuSeconds = $processResult.ProcessCpuSeconds
  $actualPriority = $processResult.Priority
  $cpuAfter = Get-CpuPercent

  $logText = if (Test-Path -LiteralPath $logPath) { Get-Content -LiteralPath $logPath -Raw } else { "" }
  $seedPattern = if ($run.kind -eq "hybrid") {
    '^\[RunSeed\] scene=([^\s]+) perRun=(\d+) seedId=(\d+) seed=(-?\d+)\r?$'
  } else {
    '^\[Run\] index=(\d+) seed=(-?\d+) profit=([-+0-9.eE]+) elapsed=([0-9.]+) s\r?$'
  }
  $seedMatch = Get-LastRegexMatch $logText $seedPattern
  $ratioMatch = Get-LastRegexMatch $logText '^Optimized profit ratio:\s*([0-9.]+)%\r?$'
  $elapsedMatch = Get-LastRegexMatch $logText '^Elapsed:\s*([0-9.]+)\s*s\r?$'
  $parseStatus = "ok"
  $actualSeed = $null
  $optimizedProfit = $null
  if ($exitCode -ne 0) {
    $parseStatus = "exit-$exitCode"
  } elseif ($null -eq $seedMatch -or $null -eq $ratioMatch -or $null -eq $elapsedMatch) {
    $parseStatus = "parse"
  } else {
    if ($run.kind -eq "hybrid") {
      $actualScene = $seedMatch.Groups[1].Value
      $actualIndex = [int]$seedMatch.Groups[2].Value
      $actualSeedId = [long]$seedMatch.Groups[3].Value
      $actualSeed = [long]$seedMatch.Groups[4].Value
      if ($actualScene -ne $run.sceneName -or $actualIndex -ne [int]$run.perRun -or $actualSeedId -ne [long]$run.referenceSeedId -or $actualSeed -ne [long]$run.effectiveSeed) {
        $parseStatus = "seed-mismatch"
      }
    } else {
      $actualIndex = [int]$seedMatch.Groups[1].Value
      $actualSeed = [long]$seedMatch.Groups[2].Value
      if ($run.kind -eq "parent") {
        $optimizedProfit = [double]$seedMatch.Groups[3].Value
      }
      if ($actualIndex -ne [int]$run.effectiveRunIndex -or $actualSeed -ne [long]$run.effectiveSeed) {
        $parseStatus = "seed-mismatch"
      }
    }
  }

  $statusRow = [pscustomobject]@{
    runKey = $run.runKey
    attempt = $attempt
    status = $parseStatus
    exitCode = $exitCode
    expectedSeed = $run.effectiveSeed
    actualSeed = $actualSeed
    priority = $actualPriority
    logicalProcessors = $logicalProcessors
    systemCpuBeforePercent = $cpuBefore
    systemCpuAfterPercent = $cpuAfter
    processCpuSeconds = $processCpuSeconds
    started = $started.ToString("o")
    finished = $finished.ToString("o")
    logPath = $logPath
    errPath = $errPath
    commandPath = $commandRecordPath
  }
  Append-CsvRow $statusRow $statusPath

  if ($parseStatus -ne "ok") {
    $failureAttempts++
    $consecutiveFailures++
    Write-Progress $progressPath "running" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
    Write-Warning "Run failed: $($run.runKey), status=$parseStatus, log=$logPath, err=$errPath"
    if ($consecutiveFailures -ge 3) {
      Write-Progress $progressPath "stopped-after-failures" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
      throw "Stopped after 3 consecutive failures. Last run: $($run.runKey)"
    }
    continue
  }

  $detailsRow = [pscustomobject]@{
    runKey = $run.runKey
    sceneId = $run.sceneId
    sceneName = $run.sceneName
    tasks = $run.tasks
    satellites = $run.satellites
    perRun = $run.perRun
    referenceSeedId = $run.referenceSeedId
    effectiveRunIndex = $run.effectiveRunIndex
    runIndexBase = $run.runIndexBase
    runSeedBase = $run.runSeedBase
    effectiveSeed = $run.effectiveSeed
    configKey = $run.configKey
    entrypoint = $run.entrypoint
    config = $run.config
    seedMode = $run.seedMode
    optimizedProfit = $optimizedProfit
    profitRatio = [double]$ratioMatch.Groups[1].Value
    elapsedSeconds = [double]$elapsedMatch.Groups[1].Value
    priority = $actualPriority
    logicalProcessors = $logicalProcessors
    logPath = $logPath
    errPath = $errPath
    commandPath = $commandRecordPath
  }
  Append-CsvRow $detailsRow $detailsPath
  $completedKeys[$run.runKey] = $true
  $consecutiveFailures = 0
  Write-Summary $detailsPath $summaryPath
  Write-Progress $progressPath "running" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
}

Write-Summary $detailsPath $summaryPath
Write-Progress $progressPath "completed" $plannedRunCount $completedKeys.Count $failureAttempts $lastRunKey
Write-Host "Done."
Write-Host "Details: $detailsPath"
Write-Host "Summary: $summaryPath"
