param(
  [string]$SearchOutDir = "outputs\mfea-native290002-training-20260806-5seeds-2026080501-2026080505-earlystop20-5-javaw",
  [string]$OutputDir = "",
  [ValidateRange(1, 100)]
  [int]$TopK = 3
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

function Test-CompletedRun([string]$runDirectory) {
  $marker = Join-Path $runDirectory "run-complete.txt"
  return (Test-Path -LiteralPath $marker -PathType Leaf) -and
    $null -ne (Select-String -LiteralPath $marker -Pattern '^status=completed$' -ErrorAction SilentlyContinue)
}

function Normalize-Config([string]$configText) {
  $values = @($configText -split ',' | ForEach-Object { $_.Trim() })
  if ($values.Count -ne 6 -or @($values | Where-Object { $_ -notmatch '^\d+$' }).Count -ne 0) {
    throw "Invalid 6D configuration in generation log: [$configText]"
  }
  return ($values | ForEach-Object { [int]$_ }) -join ','
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$outputsRoot = Join-Path $repoRoot "outputs"
$searchRoot = Resolve-RepoPath $repoRoot $SearchOutDir
Assert-ChildOf $searchRoot $outputsRoot "SearchOutDir"
if (-not (Test-Path -LiteralPath $searchRoot -PathType Container)) {
  throw "Search output directory not found: $searchRoot"
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
  $outputRoot = Join-Path $searchRoot "candidate-selection"
} else {
  $outputRoot = Resolve-RepoPath $repoRoot $OutputDir
}
Assert-ChildOf $outputRoot $outputsRoot "OutputDir"

$generationPattern = [regex]'^\[MultiScene\]\[Gen (?<generation>\d+)\/(?<generationMax>\d+)\] bestFitness=(?<bestFitness>-?\d+(?:\.\d+)?), genBest=(?<generationBest>-?\d+(?:\.\d+)?), bestConfig=\[(?<config>[^\]]+)\], time=(?<seconds>\d+(?:\.\d+)?)s$'
$runLabels = 1..5 | ForEach-Object { "seed202608050$_" }
$history = New-Object System.Collections.Generic.List[object]

foreach ($label in $runLabels) {
  $runDirectory = Join-Path $searchRoot $label
  if (-not (Test-CompletedRun $runDirectory)) {
    throw "Search run is not completed: $label"
  }
  $logPath = Join-Path $runDirectory "mfea.stdout.log"
  if (-not (Test-Path -LiteralPath $logPath -PathType Leaf)) {
    throw "Generation log not found: $logPath"
  }

  $outerSeed = [long]$label.Substring("seed".Length)
  foreach ($line in Get-Content -LiteralPath $logPath) {
    if (-not $line.StartsWith("[MultiScene][Gen ", [System.StringComparison]::Ordinal)) {
      continue
    }
    $match = $generationPattern.Match($line)
    if (-not $match.Success) {
      throw "Failed to parse generation history in ${logPath}: $line"
    }
    $history.Add([pscustomobject]@{
      outerSeed = $outerSeed
      generation = [int]$match.Groups['generation'].Value
      generationMax = [int]$match.Groups['generationMax'].Value
      bestFitness = [double]$match.Groups['bestFitness'].Value
      generationBest = [double]$match.Groups['generationBest'].Value
      bestConfig = Normalize-Config $match.Groups['config'].Value
      generationSeconds = [double]$match.Groups['seconds'].Value
      sourceLog = $logPath
    })
  }
}

if ($history.Count -eq 0) {
  throw "No generation history records found under: $searchRoot"
}

$rankedCandidates = foreach ($group in ($history | Group-Object bestConfig)) {
  $records = @($group.Group | Sort-Object @{ Expression = { $_.bestFitness }; Descending = $true }, outerSeed, generation)
  $best = $records[0]
  [pscustomobject]@{
    bestConfig = $group.Name
    bestFitness = $best.bestFitness
    firstOuterSeed = $best.outerSeed
    firstGeneration = $best.generation
    occurrenceCount = $records.Count
    outerSeedCount = @($records.outerSeed | Sort-Object -Unique).Count
    sourceLog = $best.sourceLog
  }
}
$rankedCandidates = @($rankedCandidates | Sort-Object @{ Expression = { $_.bestFitness }; Descending = $true }, firstOuterSeed, firstGeneration, bestConfig)
if ($rankedCandidates.Count -lt $TopK) {
  throw "Only $($rankedCandidates.Count) distinct generation-best configurations are available; TopK=$TopK"
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$historyPath = Join-Path $outputRoot "generation-best-history.csv"
$allCandidatesPath = Join-Path $outputRoot "distinct-candidates.csv"
$topCandidatesPath = Join-Path $outputRoot "top$TopK-candidates.csv"
$metadataPath = Join-Path $outputRoot "metadata.txt"

@($history | Sort-Object outerSeed, generation) |
  Export-Csv -LiteralPath $historyPath -NoTypeInformation -Encoding UTF8

$allRows = for ($index = 0; $index -lt $rankedCandidates.Count; $index++) {
  $candidate = $rankedCandidates[$index]
  [pscustomobject]@{
    rank = $index + 1
    bestConfig = $candidate.bestConfig
    bestFitness = $candidate.bestFitness
    firstOuterSeed = $candidate.firstOuterSeed
    firstGeneration = $candidate.firstGeneration
    occurrenceCount = $candidate.occurrenceCount
    outerSeedCount = $candidate.outerSeedCount
    sourceLog = $candidate.sourceLog
  }
}
@($allRows) | Export-Csv -LiteralPath $allCandidatesPath -NoTypeInformation -Encoding UTF8
@($allRows | Select-Object -First $TopK) |
  Export-Csv -LiteralPath $topCandidatesPath -NoTypeInformation -Encoding UTF8

@(
  "searchRoot=$searchRoot",
  "completedOuterSeeds=$($runLabels -join ',')",
  "historyRecords=$($history.Count)",
  "distinctCandidates=$($rankedCandidates.Count)",
  "topK=$TopK",
  "selectionRule=deduplicate per-generation bestConfig records and rank each configuration by its maximum logged bestFitness across all five outer seeds",
  "historyPath=$historyPath",
  "allCandidatesPath=$allCandidatesPath",
  "topCandidatesPath=$topCandidatesPath",
  "generated=$(Get-Date -Format o)"
) | Set-Content -LiteralPath $metadataPath -Encoding UTF8

Write-Host "Generation history: $historyPath"
Write-Host "Top $TopK candidates: $topCandidatesPath"
