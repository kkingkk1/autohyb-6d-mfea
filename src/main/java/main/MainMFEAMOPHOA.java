package main;

import operator.mutation.MutationHyb;
import common.AlgorithmConstants;
import common.EliteConfigArchive;
import common.HybridInitial;
import common.RandomProvider;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import model.Satellite;
import model.Task;
import utils.AssignTaskOperator;
import utils.DataLoader;
import utils.Initialize;

public class MainMFEAMOPHOA {

  // Thread pool configuration constants
  private static final int MIN_EVAL_THREADS = 2;
  private static final int MAX_EVAL_THREADS = 6;
  private static final int MIN_HYB_THREADS = 2;

  // Timeout constants
  private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;
  private static final long POPULATION_EVALUATION_TIMEOUT_MINUTES =
      Long.getLong("autohyb.outer.evalTimeoutMinutes", 30);
  private static final String FIXED_INSERTION_MODE = "GREEDY";
  private static final String OUTER_CACHE_ENABLED_PROPERTY = "autohyb.outer.cache.enabled";
  private static final String OUTER_RACING_ENABLED_PROPERTY = "autohyb.outer.racing.enabled";
  private static final double OUTER_RACING_PROMOTION_RATIO = 0.5;
  private static final String OUTER_NPOP_HYB_PROPERTY = "autohyb.outer.nPopHyb";
  private static final String OUTER_GMAX_PROPERTY = "autohyb.outer.gMax";
  private static final String OUTER_NRUN_PROPERTY = "autohyb.outer.nRun";
  private static final String OUTER_MAX_FES_PROPERTY = "autohyb.outer.maxFes";

  private static final class MainArguments {
    private final String dataDir;
    private final int numTasks;
    private final int numSatellites;
    private final boolean useEliteConfigs;

    private MainArguments(String dataDir, int numTasks, int numSatellites, boolean useEliteConfigs) {
      this.dataDir = dataDir;
      this.numTasks = numTasks;
      this.numSatellites = numSatellites;
      this.useEliteConfigs = useEliteConfigs;
    }
  }

  private static void shutdownExecutor(ExecutorService executor, String name) {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException ie) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
      System.err.println("[WARN] Interrupted while shutting down executor " + name);
    } catch (Exception e) {
      System.err.println("[WARN] Failed to shutdown executor " + name + ": " + e.getMessage());
    }
  }

  // ==========================================================
  // Helper methods
  // ==========================================================

  /**
   * Sort indices by fitness values in descending order.
   */
  private static void sortIndicesDescending(int[] indices, double[] fitness) {
    int[] scratch = new int[indices.length];
    mergeSortIndicesDescending(indices, scratch, fitness, 0, indices.length);
  }

  private static void mergeSortIndicesDescending(int[] indices, int[] scratch, double[] fitness, int left, int right) {
    int length = right - left;
    if (length <= 1) {
      return;
    }
    int mid = left + (length >>> 1);
    mergeSortIndicesDescending(indices, scratch, fitness, left, mid);
    mergeSortIndicesDescending(indices, scratch, fitness, mid, right);
    int i = left;
    int j = mid;
    int k = left;
    while (i < mid && j < right) {
      int leftIndex = indices[i];
      int rightIndex = indices[j];
      if (compareFitnessIndex(leftIndex, rightIndex, fitness) <= 0) {
        scratch[k++] = leftIndex;
        i++;
      } else {
        scratch[k++] = rightIndex;
        j++;
      }
    }
    while (i < mid) {
      scratch[k++] = indices[i++];
    }
    while (j < right) {
      scratch[k++] = indices[j++];
    }
    System.arraycopy(scratch, left, indices, left, length);
  }

  private static int compareFitnessIndex(int leftIndex, int rightIndex, double[] fitness) {
    int byFitness = Double.compare(fitness[rightIndex], fitness[leftIndex]);
    if (byFitness != 0) {
      return byFitness;
    }
    return Integer.compare(leftIndex, rightIndex);
  }

  private static void sortIndicesAscendingByScore(int[] indices, double[] score) {
    int[] scratch = new int[indices.length];
    mergeSortIndicesAscending(indices, scratch, score, 0, indices.length);
  }

  private static void mergeSortIndicesAscending(int[] indices, int[] scratch, double[] score, int left, int right) {
    int length = right - left;
    if (length <= 1) {
      return;
    }
    int mid = left + (length >>> 1);
    mergeSortIndicesAscending(indices, scratch, score, left, mid);
    mergeSortIndicesAscending(indices, scratch, score, mid, right);
    int i = left;
    int j = mid;
    int k = left;
    while (i < mid && j < right) {
      int leftIndex = indices[i];
      int rightIndex = indices[j];
      int byScore = Double.compare(score[leftIndex], score[rightIndex]);
      if (byScore < 0 || (byScore == 0 && leftIndex <= rightIndex)) {
        scratch[k++] = leftIndex;
        i++;
      } else {
        scratch[k++] = rightIndex;
        j++;
      }
    }
    while (i < mid) {
      scratch[k++] = indices[i++];
    }
    while (j < right) {
      scratch[k++] = indices[j++];
    }
    System.arraycopy(scratch, left, indices, left, length);
  }

  private static double lowerConfidenceBound(EvaluationHyb.EvaluationResult result) {
    double mean = result.meanProfit;
    double variance = 0.0;
    int count = 0;
    for (double value : result.runProfits) {
      if (Double.isFinite(value)) {
        double delta = value - mean;
        variance += delta * delta;
        count++;
      }
    }
    double std = count > 1 ? Math.sqrt(variance / (count - 1)) : 0.0;
    return mean - std;
  }

  /**
   * Generate random mutation points using Fisher-Yates shuffle algorithm.
   *
   * @param n Total number of positions
   * @return Array of randomly selected mutation point indices
   */
  private static int[] generateMutationPoints(int n, Random rand) {
    int numMutation = rand.nextInt(n) + 1;
    int[] mutationPoints = new int[numMutation];
    int[] indices = new int[n];
    for (int k = 0; k < n; k++) {
      indices[k] = k;
    }
    for (int k = 0; k < numMutation; k++) {
      int j = k + rand.nextInt(n - k);
      int temp = indices[k];
      indices[k] = indices[j];
      indices[j] = temp;
      mutationPoints[k] = indices[k];
    }
    return mutationPoints;
  }

  /**
   * Shuffle an array of integers in-place using Fisher-Yates algorithm.
   *
   * @param array Array to shuffle
   */
  private static void shuffleArray(int[] array, Random rand) {
    for (int i = array.length - 1; i > 0; i--) {
      int j = rand.nextInt(i + 1);
      int temp = array[i];
      array[i] = array[j];
      array[j] = temp;
    }
  }

  /**
   * Validate algorithm parameters.
   *
   * @param numTasks Number of tasks
   * @param numSatellites Number of satellites
   * @param nPopHyb MFEA population size
   * @param gMax Maximum generations
   * @param nRun Number of independent runs
   * @param crossRate Crossover rate
   * @throws IllegalArgumentException if any parameter is invalid
   */
  private static void validateParameters(int numTasks, int numSatellites, int nPopHyb,
    int gMax, int nRun, double crossRate) {
    if (numTasks <= 0) {
      throw new IllegalArgumentException("numTasks must be positive, got: " + numTasks);
    }
    if (numSatellites <= 0) {
      throw new IllegalArgumentException("numSatellites must be positive, got: " + numSatellites);
    }
    if (nPopHyb <= 0) {
      throw new IllegalArgumentException("nPopHyb must be positive, got: " + nPopHyb);
    }
    if (gMax <= 0) {
      throw new IllegalArgumentException("gMax must be positive, got: " + gMax);
    }
    if (nRun <= 0) {
      throw new IllegalArgumentException("nRun must be positive, got: " + nRun);
    }
    if (crossRate < 0.0 || crossRate > 1.0) {
      throw new IllegalArgumentException("crossRate must be in [0, 1], got: " + crossRate);
    }
  }

  /**
   * Result of global MFEA optimization.
   * Encapsulates the final output of a single global 6D configuration search.
   */
  private static class GlobalOptimizationResult {
    final int[] bestGlobalSolution;
    final double initialGlobalFitness;
    final double bestGlobalFitness;
    final Map<Integer, model.SchedulingPlan> bestPlansBySatellite;
    final double totalTaskProfit;
    final double totalAssignedProfit;

    GlobalOptimizationResult(
      int[] bestGlobalSolution,
      double initialGlobalFitness,
      double bestGlobalFitness,
      Map<Integer, model.SchedulingPlan> bestPlansBySatellite,
      double totalTaskProfit,
      double totalAssignedProfit) {
      this.bestGlobalSolution = bestGlobalSolution;
      this.initialGlobalFitness = initialGlobalFitness;
      this.bestGlobalFitness = bestGlobalFitness;
      this.bestPlansBySatellite = bestPlansBySatellite;
      this.totalTaskProfit = totalTaskProfit;
      this.totalAssignedProfit = totalAssignedProfit;
    }
  }

  private static final class CacheKey {
    private final int[] config;
    private final int nPop;
    private final int maxFes;
    private final int nRun;
    private final String insertionMode;
    private final int tuningHash;

    private CacheKey(
      int[] config,
      int nPop,
      int maxFes,
      int nRun,
      String insertionMode,
      int tuningHash) {
      this.config = config.clone();
      this.nPop = nPop;
      this.maxFes = maxFes;
      this.nRun = nRun;
      this.insertionMode = insertionMode;
      this.tuningHash = tuningHash;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof CacheKey)) {
        return false;
      }
      CacheKey that = (CacheKey) other;
      return nPop == that.nPop
        && maxFes == that.maxFes
        && nRun == that.nRun
        && tuningHash == that.tuningHash
        && Arrays.equals(config, that.config)
        && Objects.equals(insertionMode, that.insertionMode);
    }

    @Override
    public int hashCode() {
      int result = Arrays.hashCode(config);
      result = 31 * result + nPop;
      result = 31 * result + maxFes;
      result = 31 * result + nRun;
      result = 31 * result + Objects.hashCode(insertionMode);
      result = 31 * result + tuningHash;
      return result;
    }
  }

  private static final class EvaluationCache {
    private final boolean enabled;
    private final String sceneDir;
    private final ConcurrentHashMap<CacheKey, EvaluationHyb.EvaluationResult> entries =
      new ConcurrentHashMap<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();

    private EvaluationCache(boolean enabled, String sceneDir) {
      this.enabled = enabled;
      this.sceneDir = sceneDir;
    }

    private EvaluationHyb.EvaluationResult evaluate(
      int[] config,
      int d,
      int nPop,
      int maxFes,
      int nRun,
      List<Satellite> allSatellites,
      List<Task> allTasks,
      ExecutorService hybExecutor) throws Exception {
      if (!enabled) {
        return EvaluationHyb.evaluationHyb(
          config, d, nPop, maxFes, nRun, allSatellites, allTasks, sceneDir, hybExecutor);
      }
      CacheKey key = new CacheKey(
        config,
        nPop,
        maxFes,
        nRun,
        RunSingleHybridConfig.resolveInsertionMode().name(),
        tuningHash());
      EvaluationHyb.EvaluationResult cached = entries.get(key);
      if (cached != null) {
        hits.incrementAndGet();
        return cached;
      }
      misses.incrementAndGet();
      EvaluationHyb.EvaluationResult result = EvaluationHyb.evaluationHyb(
        config, d, nPop, maxFes, nRun, allSatellites, allTasks, sceneDir, hybExecutor);
      EvaluationHyb.EvaluationResult previous = entries.putIfAbsent(key, result);
      return previous != null ? previous : result;
    }

    private void printSummary() {
      if (!enabled) {
        return;
      }
      int hitCount = hits.get();
      int missCount = misses.get();
      int total = hitCount + missCount;
      double hitRate = total == 0 ? 0.0 : hitCount * 100.0 / total;
      System.out.printf("[OuterCache] hits=%d, misses=%d, hitRate=%.2f%%, size=%d%n",
        hitCount, missCount, hitRate, entries.size());
    }
  }

  private static int tuningHash() {
    String tuningPath = System.getProperty(common.TuningConfig.CONFIG_PATH_PROPERTY, "");
    return Objects.hash(tuningPath);
  }

  private static MainArguments parseMainArguments(String[] args) {
    List<String> positional = new ArrayList<>();
    for (String arg : args) {
      if ("--quiet".equals(arg)) {
        System.setProperty(RunSingleHybridConfig.QUIET_PROPERTY, Boolean.TRUE.toString());
      } else if (arg.startsWith("--log-interval=")) {
        int interval = Integer.parseInt(arg.substring("--log-interval=".length()).trim());
        if (interval <= 0) {
          throw new IllegalArgumentException("--log-interval must be positive, got: " + interval);
        }
        System.setProperty(RunSingleHybridConfig.LOG_INTERVAL_PROPERTY, Integer.toString(interval));
      } else {
        positional.add(arg);
      }
    }
    String dataDir = positional.size() > 0 ? positional.get(0)
        : ScenePathResolver.defaultSceneDir("500-5-0-0-24");
    int numTasks = positional.size() > 1 ? Integer.parseInt(positional.get(1)) : 200;
    int numSatellites = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : 3;
    boolean useEliteConfigs = positional.size() > 3 ? Boolean.parseBoolean(positional.get(3)) : false;
    return new MainArguments(dataDir, numTasks, numSatellites, useEliteConfigs);
  }

  private static boolean shouldPrintGenerationLog(int generation, int maxGeneration) {
    if (Boolean.getBoolean(RunSingleHybridConfig.QUIET_PROPERTY)) {
      return false;
    }
    int interval = Integer.getInteger(RunSingleHybridConfig.LOG_INTERVAL_PROPERTY, 1);
    return generation == 1 || generation == maxGeneration || generation % Math.max(1, interval) == 0;
  }

  /**
   * Evaluate a global 6D population in parallel using CompletableFuture.
   *
   * @param population Global hybrid configuration population
   * @param d Configuration dimension
   * @param nPop Inner HybCec population size
   * @param maxFes Maximum HybCec evaluations
   * @param nRun Number of independent global evaluation runs
   * @param allSatellites All satellites participating in the global schedule
   * @param allTasks All tasks
   * @param hybExecutor Executor for hybrid algorithm
   * @param evalExecutor Executor for evaluation orchestration
   * @param contextLabel Label for error messages and progress logs
   * @return Mean total-profit fitness for each global configuration individual
   * @throws Exception if evaluation fails or times out
   */
  private static double[] evaluatePopulationParallel(
    int[][] population,
    int d,
    int nPop,
    int maxFes,
    int nRun,
    List<Satellite> allSatellites,
    List<Task> allTasks,
    ExecutorService hybExecutor,
    ExecutorService evalExecutor,
    String contextLabel,
    EvaluationCache evaluationCache
  ) throws Exception {
    if (Boolean.getBoolean(OUTER_RACING_ENABLED_PROPERTY) && nRun > 1) {
      return evaluatePopulationProgressiveRacing(
        population,
        d,
        nPop,
        maxFes,
        nRun,
        allSatellites,
        allTasks,
        hybExecutor,
        evalExecutor,
        contextLabel,
        evaluationCache
      );
    }
    int popSize = population.length;
    double[] fitness = new double[popSize];

    @SuppressWarnings("unchecked")
    java.util.concurrent.CompletableFuture<Double>[] futures = new java.util.concurrent.CompletableFuture[popSize];

    for (int i = 0; i < popSize; i++) {
      final int index = i;
      futures[index] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try {
            EvaluationHyb.EvaluationResult result;
            try (ParallelPolicy.Scope ignored = ParallelPolicy.enterOuterEvaluation()) {
              result = evaluationCache.evaluate(
                population[index],
                d,
                nPop,
                maxFes,
                nRun,
                allSatellites,
                allTasks,
                hybExecutor
              );
            }
            return result.meanProfit;
          } catch (Exception e) {
            throw new RuntimeException("Evaluation failed for " + contextLabel + " individual " + index, e);
          }
        }, evalExecutor);
    }

    try {
      java.util.concurrent.CompletableFuture.allOf(futures).get(POPULATION_EVALUATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new RuntimeException(contextLabel + " evaluation timeout (exceeded " + POPULATION_EVALUATION_TIMEOUT_MINUTES + " minutes)", e);
    }

    for (int i = 0; i < popSize; i++) {
      fitness[i] = futures[i].join();
    }

    return fitness;
  }

  private static double[] evaluatePopulationProgressiveRacing(
    int[][] population,
    int d,
    int nPop,
    int maxFes,
    int nRun,
    List<Satellite> allSatellites,
    List<Task> allTasks,
    ExecutorService hybExecutor,
    ExecutorService evalExecutor,
    String contextLabel,
    EvaluationCache evaluationCache
  ) throws Exception {
    int popSize = population.length;
    double[] fitness = new double[popSize];
    double[] lcbScore = new double[popSize];
    int[] indices = new int[popSize];

    @SuppressWarnings("unchecked")
    java.util.concurrent.CompletableFuture<EvaluationHyb.EvaluationResult>[] firstPassFutures =
      new java.util.concurrent.CompletableFuture[popSize];
    for (int i = 0; i < popSize; i++) {
      final int index = i;
      firstPassFutures[index] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try (ParallelPolicy.Scope ignored = ParallelPolicy.enterOuterEvaluation()) {
            return evaluationCache.evaluate(
              population[index],
              d,
              nPop,
              maxFes,
              1,
              allSatellites,
              allTasks,
              hybExecutor
            );
          } catch (Exception e) {
            throw new RuntimeException("Racing first pass failed for " + contextLabel
              + " individual " + index, e);
          }
        }, evalExecutor);
    }
    try {
      java.util.concurrent.CompletableFuture.allOf(firstPassFutures)
        .get(POPULATION_EVALUATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new RuntimeException(contextLabel + " racing first pass timeout (exceeded "
        + POPULATION_EVALUATION_TIMEOUT_MINUTES + " minutes)", e);
    }
    for (int i = 0; i < popSize; i++) {
      EvaluationHyb.EvaluationResult firstPass = firstPassFutures[i].join();
      fitness[i] = lowerConfidenceBound(firstPass);
      lcbScore[i] = -fitness[i];
      indices[i] = i;
    }

    sortIndicesAscendingByScore(indices, lcbScore);
    int promoteCount = Math.max(1, (int) Math.ceil(popSize * OUTER_RACING_PROMOTION_RATIO));
    @SuppressWarnings("unchecked")
    java.util.concurrent.CompletableFuture<EvaluationHyb.EvaluationResult>[] promotedFutures =
      new java.util.concurrent.CompletableFuture[promoteCount];
    for (int rank = 0; rank < promoteCount; rank++) {
      final int index = indices[rank];
      promotedFutures[rank] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try (ParallelPolicy.Scope ignored = ParallelPolicy.enterOuterEvaluation()) {
            return evaluationCache.evaluate(
              population[index],
              d,
              nPop,
              maxFes,
              nRun,
              allSatellites,
              allTasks,
              hybExecutor
            );
          } catch (Exception e) {
            throw new RuntimeException("Racing promoted pass failed for " + contextLabel
              + " individual " + index, e);
          }
        }, evalExecutor);
    }
    try {
      java.util.concurrent.CompletableFuture.allOf(promotedFutures)
        .get(POPULATION_EVALUATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new RuntimeException(contextLabel + " racing promoted pass timeout (exceeded "
        + POPULATION_EVALUATION_TIMEOUT_MINUTES + " minutes)", e);
    }
    for (int rank = 0; rank < promoteCount; rank++) {
      int index = indices[rank];
      fitness[index] = lowerConfidenceBound(promotedFutures[rank].join());
    }

    if (!Boolean.getBoolean(RunSingleHybridConfig.QUIET_PROPERTY)) {
      System.out.printf("[OuterRacing] %s firstPass=%d, promoted=%d, nRun=%d%n",
        contextLabel, popSize, promoteCount, nRun);
    }
    return fitness;
  }

  private static GlobalOptimizationResult runGlobalMFEA(
    String sceneDir,
    int numSatellites,
    List<Satellite> allSatellites,
    List<Task> allTasks,
    int populationFactor,
    int maxFes,
    int nPar,
    int nOL,
    int nOO,
    int nSS,
    int n,
    int nPopHyb,
    int gMax,
    int nRun,
    double crossRate,
    ExecutorService hybExecutor,
    ExecutorService evalExecutor,
    boolean useEliteConfigs,
    EliteConfigArchive.ArchiveContext archiveContext
  ) throws Exception {
    int maxAssignedTaskCount = allSatellites.stream()
    .mapToInt(satellite -> satellite.getAssignedTasks().size())
    .max()
    .orElse(0);
    int nPop = Math.max(1, populationFactor * Math.max(1, maxAssignedTaskCount));

    int[] bestGlobalSolution = new int[n];
    double bestGlobalFitness = Double.NEGATIVE_INFINITY;

    RandomProvider.resetThreadCounter();

    int[][] popIni = HybridInitial.hybridInitial(
      nPar,
      nOL,
      nOO,
      nSS,
      nPopHyb,
      useEliteConfigs,
      archiveContext
    );
    int[][] pop = popIni;
    EvaluationCache evaluationCache = new EvaluationCache(
      Boolean.getBoolean(OUTER_CACHE_ENABLED_PROPERTY), sceneDir);

    System.out.println("[PARALLEL] Evaluating initial global configuration population (" + nPopHyb + " individuals)");
    double[] popFit = evaluatePopulationParallel(
      popIni,
      n,
      nPop,
      maxFes,
      nRun,
      allSatellites,
      allTasks,
      hybExecutor,
      evalExecutor,
      "initial global configuration population",
      evaluationCache
    );

    int bestIdx0 = 0;
    for (int i = 1; i < nPopHyb; i++) {
      if (popFit[i] > popFit[bestIdx0]) {
        bestIdx0 = i;
      }
    }
    bestGlobalFitness = popFit[bestIdx0];
    bestGlobalSolution = pop[bestIdx0].clone();
    double initialGlobalFitness = bestGlobalFitness;

    int[][] newPop = new int[nPopHyb][n];
    int[] parentIndices = new int[nPopHyb];
    int mergeSize = 2 * nPopHyb;
    int[][] popMerge = new int[mergeSize][];
    double[] popFitMerge = new double[mergeSize];
    int[] sortIndices = new int[mergeSize];
    Random outerRand = RandomProvider.newWithSeed(AlgorithmConstants.FIXED_RANDOM_SEED_LONG);

    System.out.println("\n========================================================");
    System.out.println("Start global 6D MFEA optimization");
    System.out.println("========================================================\n");

    for (int g = 0; g < gMax; g++) {
      long genStartTime = System.currentTimeMillis();

      for (int i = 0; i < nPopHyb; i++) {
        parentIndices[i] = i;
      }
      shuffleArray(parentIndices, outerRand);

      for (int i = 0; i < nPopHyb; i += 2) {
        int parentIndex1 = parentIndices[i];
        int[] son1;

        if (i + 1 < nPopHyb) {
          int parentIndex2 = parentIndices[i + 1];
          int[] son2;

          if (outerRand.nextDouble() < crossRate) {
            int[] parent1 = pop[parentIndex1];
            int[] parent2 = pop[parentIndex2];
            int crossPoint = outerRand.nextInt(n - 1) + 1;

            son1 = new int[n];
            son2 = new int[n];

            System.arraycopy(parent1, 0, son1, 0, crossPoint);
            System.arraycopy(parent2, crossPoint, son1, crossPoint, n - crossPoint);
            System.arraycopy(parent2, 0, son2, 0, crossPoint);
            System.arraycopy(parent1, crossPoint, son2, crossPoint, n - crossPoint);

            if (son1[0] == son1[1]) {
              son1[1] = (son1[0] + 1 + outerRand.nextInt(nPar - 1)) % nPar;
            }

            if (son2[0] == son2[1]) {
              son2[1] = (son2[0] + 1 + outerRand.nextInt(nPar - 1)) % nPar;
            }
          } else {
            int[] mutationPoints = generateMutationPoints(n, outerRand);
            son1 = MutationHyb.mutationHyb(
              pop[parentIndex1], mutationPoints, nPar, nOL, nOO, nSS
            );
            son2 = MutationHyb.mutationHyb(
              pop[parentIndex2], mutationPoints, nPar, nOL, nOO, nSS
            );
          }
          newPop[i] = son1;
          newPop[i + 1] = son2;
        } else {
          int[] mutationPoints = generateMutationPoints(n, outerRand);
          son1 = MutationHyb.mutationHyb(
            pop[parentIndex1], mutationPoints, nPar, nOL, nOO, nSS
          );
          newPop[i] = son1;
        }
      }

      double[] popFitNew = evaluatePopulationParallel(
        newPop,
        n,
        nPop,
        maxFes,
        nRun,
        allSatellites,
        allTasks,
        hybExecutor,
        evalExecutor,
        "global generation " + (g + 1),
        evaluationCache
      );

      for (int i = 0; i < nPopHyb; i++) {
        popMerge[i] = pop[i];
        popMerge[i + nPopHyb] = newPop[i];
      }

      System.arraycopy(popFit, 0, popFitMerge, 0, nPopHyb);
      System.arraycopy(popFitNew, 0, popFitMerge, nPopHyb, nPopHyb);

      for (int i = 0; i < mergeSize; i++) {
        sortIndices[i] = i;
      }
      sortIndicesDescending(sortIndices, popFitMerge);

      for (int i = 0; i < nPopHyb; i++) {
        pop[i] = popMerge[sortIndices[i]];
        popFit[i] = popFitMerge[sortIndices[i]];
      }

      if (popFit[0] > bestGlobalFitness) {
        bestGlobalFitness = popFit[0];
        bestGlobalSolution = pop[0].clone();
      }

      double genTime = (System.currentTimeMillis() - genStartTime) / 1000.0;
      if (shouldPrintGenerationLog(g + 1, gMax)) {
        System.out.printf(
          "[Global 6D][Generation %d/%d] bestTotalProfit=%.4f, generationBest=%.4f, bestConfig=%s, time=%.1fs%n",
          g + 1,
          gMax,
          bestGlobalFitness,
          popFit[0],
          Arrays.toString(bestGlobalSolution),
          genTime
        );
      }
    }

    System.out.println("\n========================================================");
    System.out.println("Global 6D MFEA optimization completed");
    System.out.println("========================================================\n");
    evaluationCache.printSummary();

    EvaluationHyb.EvaluationResult bestEvaluation = evaluationCache.evaluate(
      bestGlobalSolution,
      n,
      nPop,
      maxFes,
      nRun,
      allSatellites,
      allTasks,
      hybExecutor
    );

    if (archiveContext != null) {
      EliteConfigArchive.saveBestConfig(archiveContext, bestGlobalSolution);
    }

    double totalTaskProfit = allTasks.stream().mapToInt(Task::getPriority).sum();
    double totalAssignedProfit = allSatellites.stream()
    .flatMap(satellite -> satellite.getAssignedTasks().stream())
    .mapToDouble(Task::getPriority)
    .sum();

    return new GlobalOptimizationResult(
      bestGlobalSolution,
      initialGlobalFitness,
      bestEvaluation.bestRunProfit,
      bestEvaluation.bestPlansBySatellite,
      totalTaskProfit,
      totalAssignedProfit
    );
  }

  static double sumSatellitePlanProfit(
    Map<Integer, model.SchedulingPlan> plansBySatellite,
    List<Satellite> allSatellites) {
    double summedSatelliteProfit = 0.0;
    for (Satellite satellite : allSatellites) {
      model.SchedulingPlan plan = plansBySatellite.get(satellite.getId());
      if (plan == null) {
        continue;
      }
      summedSatelliteProfit += plan.isFitnessCalculated() ? plan.getFitness() : plan.getTotalProfit();
    }
    return summedSatelliteProfit;
  }

  static boolean isProfitConsistent(double globalFitness, double summedSatelliteProfit, double tolerance) {
    return Math.abs(globalFitness - summedSatelliteProfit) <= tolerance;
  }

  public static void main(String[] args) {

    // ======================================================
    // I. Program initialization
    // ======================================================

    MainArguments mainArguments = parseMainArguments(args);
    String dataDir = mainArguments.dataDir;
    int numTasks = mainArguments.numTasks;
    int numSatellites = mainArguments.numSatellites;
    boolean useEliteConfigs = mainArguments.useEliteConfigs;

    int totalCores = Runtime.getRuntime().availableProcessors();
    int evalThreads = ParallelPolicy.evalThreadsForOuter(totalCores, MIN_EVAL_THREADS, MAX_EVAL_THREADS);
    int hybThreads = ParallelPolicy.hybThreadsForOuter(totalCores, evalThreads, MIN_HYB_THREADS);
    System.out.println("[THREADS] evalExecutor=" + evalThreads + ", hybExecutor=" + hybThreads + ", cores=" + totalCores);

    ExecutorService hybExecutor = Executors.newFixedThreadPool(hybThreads);
    ExecutorService evalExecutor = Executors.newFixedThreadPool(evalThreads);

    try {
      DataLoader.DataSet dataSet = DataLoader.loadAllData(dataDir, numTasks, numSatellites);
      List<Task> allTasks = dataSet.taskList;
      List<Satellite> allSatellites = dataSet.satelliteList;
      List<List<Integer>> initialAssignment = Initialize.initialize(allTasks, allSatellites);
      AssignTaskOperator.assignTaskToSatellites(initialAssignment, allTasks, allSatellites);

      int populationFactor = AlgorithmConstants.DEFAULT_POPULATION_Factor;
      double iterationCoefficient = AlgorithmConstants.DEFAULT_ITERATION_COEFFICIENT;
      int maxD = allSatellites.stream().mapToInt(s -> s.getAssignedTasks().size()).max().orElse(0);
      int defaultMaxFes = (int) Math.round(populationFactor * maxD * iterationCoefficient);
      int maxFes = Integer.getInteger(OUTER_MAX_FES_PROPERTY, defaultMaxFes);
      int nPar = AlgorithmConstants.HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT;
      int nOL = AlgorithmConstants.UPDATE_RATIO_CODE_COUNT;
      int nOO = AlgorithmConstants.OPERATION_ORDER_COUNT;
      int nSS = AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_COUNT;
      int n = AlgorithmConstants.HYBRID_CONFIG_DIMENSION;
      int nPopHyb = Integer.getInteger(OUTER_NPOP_HYB_PROPERTY, 12);
      int gMax = Integer.getInteger(OUTER_GMAX_PROPERTY, 5);
      int nRun = Integer.getInteger(OUTER_NRUN_PROPERTY, 2);
      double crossRate = 0.3;
      EliteConfigArchive.ArchiveContext archiveContext = new EliteConfigArchive.ArchiveContext(
        dataDir,
        numTasks,
        numSatellites,
        populationFactor,
        maxFes,
        FIXED_INSERTION_MODE
      );

      validateParameters(numTasks, numSatellites, nPopHyb, gMax, nRun, crossRate);

      GlobalOptimizationResult result = runGlobalMFEA(
        dataDir,
        numSatellites,
        allSatellites,
        allTasks,
        populationFactor,
        maxFes,
        nPar,
        nOL,
        nOO,
        nSS,
        n,
        nPopHyb,
        gMax,
        nRun,
        crossRate,
        hybExecutor,
        evalExecutor,
        useEliteConfigs,
        archiveContext
      );

      System.out.println("\n========================================================");
      System.out.println("GLOBAL OPTIMIZATION SUMMARY");
      System.out.println("========================================================");
    System.out.printf("Global best 6D config: %s%n", Arrays.toString(result.bestGlobalSolution));
      for (Satellite satellite : allSatellites) {
        model.SchedulingPlan plan = result.bestPlansBySatellite.get(satellite.getId());
        double satelliteFitness = 0.0;
        int scheduledTaskCount = 0;
        if (plan != null) {
          satelliteFitness = plan.isFitnessCalculated() ? plan.getFitness() : plan.getTotalProfit();
          scheduledTaskCount = plan.getSchedule().size();
        }
        System.out.printf("Satellite %d plan: profit=%.4f, scheduledTasks=%d%n",
          satellite.getId(), satelliteFitness, scheduledTaskCount);
      }
      double summedSatelliteProfit = sumSatellitePlanProfit(result.bestPlansBySatellite, allSatellites);
      double consistencyDelta = Math.abs(result.bestGlobalFitness - summedSatelliteProfit);
      boolean profitConsistent = isProfitConsistent(result.bestGlobalFitness, summedSatelliteProfit, 1e-9);
      System.out.println("--------------------------------------------------------");
      System.out.printf("Total Task Profit (all tasks): %.4f%n", result.totalTaskProfit);
      System.out.printf("Total Assigned Task Profit: %.4f (%.2f%% of all tasks)%n",
        result.totalAssignedProfit, (result.totalAssignedProfit / result.totalTaskProfit) * 100);
      System.out.printf("Global Initial Fitness: %.4f (%.2f%% of all tasks)%n",
        result.initialGlobalFitness, (result.initialGlobalFitness / result.totalTaskProfit) * 100);
      System.out.printf("Global Final Fitness: %.4f (%.2f%% of all tasks)%n",
        result.bestGlobalFitness, (result.bestGlobalFitness / result.totalTaskProfit) * 100);
      System.out.printf("Summed Satellite Plan Profit: %.4f%n", summedSatelliteProfit);
      System.out.printf("Profit Consistency Check: %s (delta=%.12f)%n",
        profitConsistent ? "PASS" : "FAIL", consistencyDelta);
      if (!profitConsistent) {
        System.err.printf("[WARN] Global final fitness and summed satellite plan profit diverge: global=%.12f, summed=%.12f, delta=%.12f%n",
          result.bestGlobalFitness, summedSatelliteProfit, consistencyDelta);
      }
      System.out.printf("Fitness Improvement: %.4f (%.2f%% -> %.2f%%)%n",
        result.bestGlobalFitness - result.initialGlobalFitness,
        (result.initialGlobalFitness / result.totalTaskProfit) * 100,
        (result.bestGlobalFitness / result.totalTaskProfit) * 100);
      System.out.println("========================================================");
    } catch (Exception e) {
      System.err.println("===================================================");
      System.err.println("FATAL ERROR: Algorithm execution failed");
      System.err.println("===================================================");
      System.err.println("Error type: " + e.getClass().getSimpleName());
      System.err.println("Error message: " + e.getMessage());
      System.err.println("\nStack trace:");
      e.printStackTrace();
      System.err.println("===================================================");
      System.exit(1);
    } finally {
      shutdownExecutor(evalExecutor, "evalExecutor");
      shutdownExecutor(hybExecutor, "hybExecutor");
    }
  }
}
