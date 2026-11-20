package main;

import operator.mutation.MutationHyb;
import common.AlgorithmConstants;
import common.HybridInitial;
import common.RandomProvider;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import model.Satellite;
import model.Task;
import utils.AssignTaskOperator;
import utils.DataLoader;
import utils.Initialize;

/**
 * Multi-scene MFEA: searches for a single 6D config that maximizes
 * aggregated normalized profit across multiple scenes.
 *
 * fitness = sum_i(meanProfit_i / totalProfit_i)
 */
public class MainMFEAMultiScene {

  private static final int MIN_EVAL_THREADS = 2;
  private static final int MAX_EVAL_THREADS = 6;
  private static final int MIN_HYB_THREADS = 2;
  private static final double HYBCEC_POPULATION_FACTOR = 2.5;
  private static final double HYBCEC_ITERATION_COEFFICIENT = 100.0;
  private static final long POPULATION_EVALUATION_TIMEOUT_MINUTES =
      Long.getLong("autohyb.outer.evalTimeoutMinutes", 60);
  private static final String OUTER_SEARCH_SEED_PROPERTY = "autohyb.outer.seed";
  private static final String OUTER_STOP_CONFIG_PROPERTY = "autohyb.outer.stopConfig";
  private static final String OUTER_EARLY_STOP_ENABLED_PROPERTY =
      "autohyb.outer.earlyStop.enabled";
  private static final String OUTER_EARLY_STOP_MIN_GENERATIONS_PROPERTY =
      "autohyb.outer.earlyStop.minGenerations";
  private static final String OUTER_EARLY_STOP_PATIENCE_PROPERTY =
      "autohyb.outer.earlyStop.patience";
  private static final String OUTER_EARLY_STOP_MIN_DELTA_PROPERTY =
      "autohyb.outer.earlyStop.minDelta";

  private static final String SCENE_BASE_DIR = ScenePathResolver.defaultSceneRoot();

  private static long resolveOuterSearchSeed() {
    String raw = System.getProperty(OUTER_SEARCH_SEED_PROPERTY);
    if (raw == null || raw.trim().isEmpty()) {
      return AlgorithmConstants.FIXED_RANDOM_SEED_LONG;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          OUTER_SEARCH_SEED_PROPERTY + " must be a long, got: " + raw, e);
    }
  }

  private static int[] resolveStopConfig(int n, int nPar, int nOL, int nOO, int nSS) {
    String raw = System.getProperty(OUTER_STOP_CONFIG_PROPERTY);
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }

    String[] tokens = raw.split(",", -1);
    if (tokens.length != n) {
      throw new IllegalArgumentException(
          OUTER_STOP_CONFIG_PROPERTY + " must contain " + n + " comma-separated genes, got: " + raw);
    }

    int[] config = new int[n];
    int[] limits = {nPar, nPar, nOL, nOL, nOO, nSS};
    try {
      for (int i = 0; i < n; i++) {
        config[i] = Integer.parseInt(tokens[i].trim());
        if (config[i] < 0 || config[i] >= limits[i]) {
          throw new IllegalArgumentException(
              OUTER_STOP_CONFIG_PROPERTY + " gene " + i + " is out of range: " + config[i]);
        }
      }
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          OUTER_STOP_CONFIG_PROPERTY + " contains a non-integer gene: " + raw, e);
    }

    if (config[0] == config[1]) {
      throw new IllegalArgumentException(
          OUTER_STOP_CONFIG_PROPERTY + " must use two different parent algorithms: " + raw);
    }
    return config;
  }

  private static boolean resolveBooleanProperty(String property, boolean defaultValue) {
    String raw = System.getProperty(property);
    if (raw == null || raw.trim().isEmpty()) {
      return defaultValue;
    }
    if ("true".equalsIgnoreCase(raw.trim())) {
      return true;
    }
    if ("false".equalsIgnoreCase(raw.trim())) {
      return false;
    }
    throw new IllegalArgumentException(property + " must be true or false, got: " + raw);
  }

  private static int resolveIntProperty(String property, int defaultValue, int minInclusive) {
    String raw = System.getProperty(property);
    int value = defaultValue;
    if (raw != null && !raw.trim().isEmpty()) {
      try {
        value = Integer.parseInt(raw.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(property + " must be an integer, got: " + raw, e);
      }
    }
    if (value < minInclusive) {
      throw new IllegalArgumentException(
          property + " must be >= " + minInclusive + ", got: " + value);
    }
    return value;
  }

  private static double resolveDoubleProperty(String property, double defaultValue,
      double minInclusive) {
    String raw = System.getProperty(property);
    double value = defaultValue;
    if (raw != null && !raw.trim().isEmpty()) {
      try {
        value = Double.parseDouble(raw.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(property + " must be a number, got: " + raw, e);
      }
    }
    if (!Double.isFinite(value) || value < minInclusive) {
      throw new IllegalArgumentException(
          property + " must be finite and >= " + minInclusive + ", got: " + value);
    }
    return value;
  }

  static boolean shouldEarlyStop(boolean enabled, int generation, int minGenerations,
      int noImprovementGenerations, int patience) {
    return enabled
        && generation >= minGenerations
        && noImprovementGenerations >= patience;
  }

  static class SceneConfig {
    final String sceneDir;
    final int numTasks;
    final int numSatellites;
    final double totalProfit;

    SceneConfig(String sceneDir, int numTasks, int numSatellites, double totalProfit) {
      this.sceneDir = sceneDir;
      this.numTasks = numTasks;
      this.numSatellites = numSatellites;
      this.totalProfit = totalProfit;
    }
  }
  static class LoadedScene {
    final SceneConfig config;
    final List<Satellite> satellites;
    final List<Task> tasks;
    final int nPop;
    final int maxFes;

    LoadedScene(SceneConfig config, List<Satellite> satellites, List<Task> tasks,
                int nPop, int maxFes) {
      this.config = config;
      this.satellites = satellites;
      this.tasks = tasks;
      this.nPop = nPop;
      this.maxFes = maxFes;
    }
  }

  private static LoadedScene loadScene(SceneConfig sc, double fesBudgetRatio) throws Exception {
    DataLoader.DataSet dataSet = DataLoader.loadAllData(sc.sceneDir, sc.numTasks, sc.numSatellites);
    List<Task> tasks = dataSet.taskList;
    List<Satellite> satellites = dataSet.satelliteList;
    List<List<Integer>> assignment = Initialize.initialize(tasks, satellites);
    AssignTaskOperator.assignTaskToSatellites(assignment, tasks, satellites);

    int maxD = satellites.stream().mapToInt(s -> s.getAssignedTasks().size()).max().orElse(0);
    int nPop = Math.max(1, (int) Math.round(
        HYBCEC_POPULATION_FACTOR * Math.max(1, maxD)));
    int maxFes = Math.max(1, (int) Math.round(
        HYBCEC_POPULATION_FACTOR * Math.max(1, maxD)
            * HYBCEC_ITERATION_COEFFICIENT * fesBudgetRatio));
    double totalProfit = tasks.stream().mapToDouble(Task::getPriority).sum();
    if (!(totalProfit > 0.0)) {
      throw new IllegalArgumentException("Scene has no positive task priority total: " + sc.sceneDir);
    }
    SceneConfig loadedConfig = new SceneConfig(
        sc.sceneDir, sc.numTasks, sc.numSatellites, totalProfit);
    return new LoadedScene(loadedConfig, satellites, tasks, nPop, maxFes);
  }

  private static double evaluateConfigOnScene(int[] config, LoadedScene scene,
      int nRun, ExecutorService hybExecutor) throws Exception {
    int d = AlgorithmConstants.HYBRID_CONFIG_DIMENSION;
    try (ParallelPolicy.Scope ignored = ParallelPolicy.enterOuterEvaluation()) {
      EvaluationHyb.EvaluationResult result = EvaluationHyb.evaluationHyb(
          config, d, scene.nPop, scene.maxFes, nRun,
          scene.satellites, scene.tasks, scene.config.sceneDir, hybExecutor);
      return result.meanProfit;
    }
  }

  private static double computeMultiSceneFitness(int[] config, List<LoadedScene> scenes,
      int nRun, ExecutorService hybExecutor) throws Exception {
    double fitness = 0.0;
    for (LoadedScene scene : scenes) {
      double profit = evaluateConfigOnScene(config, scene, nRun, hybExecutor);
      fitness += profit / scene.config.totalProfit;
    }
    return fitness;
  }
  private static double[] evaluatePopulationMultiScene(
      int[][] population, List<LoadedScene> scenes, int nRun,
      ExecutorService hybExecutor, ExecutorService evalExecutor,
      String contextLabel) {

    int popSize = population.length;
    double[] fitness = new double[popSize];

    @SuppressWarnings("unchecked")
    java.util.concurrent.CompletableFuture<Double>[] futures =
        new java.util.concurrent.CompletableFuture[popSize];

    for (int i = 0; i < popSize; i++) {
      final int index = i;
      futures[index] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try {
          return computeMultiSceneFitness(population[index], scenes, nRun, hybExecutor);
        } catch (Exception e) {
          throw new RuntimeException("Evaluation failed for " + contextLabel
              + " individual " + index, e);
        }
      }, evalExecutor);
    }

    try {
      java.util.concurrent.CompletableFuture.allOf(futures)
          .get(POPULATION_EVALUATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new RuntimeException(contextLabel + " evaluation timeout (exceeded "
          + POPULATION_EVALUATION_TIMEOUT_MINUTES + " minutes)", e);
    } catch (Exception e) {
      throw new RuntimeException(contextLabel + " evaluation failed", e);
    }

    for (int i = 0; i < popSize; i++) {
      fitness[i] = futures[i].join();
    }
    return fitness;
  }
  public static void main(String[] args) {
    int nPopHyb = Integer.getInteger("autohyb.outer.nPopHyb", 35);
    int gMax = Integer.getInteger("autohyb.outer.gMax", 20);
    int nRun = Integer.getInteger("autohyb.outer.nRun", 3);
    double fesBudgetRatio = Double.parseDouble(
        System.getProperty("autohyb.outer.fesBudgetRatio", "0.6"));
    long outerSearchSeed = resolveOuterSearchSeed();
    boolean earlyStopEnabled = resolveBooleanProperty(
        OUTER_EARLY_STOP_ENABLED_PROPERTY, true);
    int earlyStopMinGenerations = resolveIntProperty(
        OUTER_EARLY_STOP_MIN_GENERATIONS_PROPERTY, 10, 0);
    int earlyStopPatience = resolveIntProperty(
        OUTER_EARLY_STOP_PATIENCE_PROPERTY, 10, 1);
    double earlyStopMinDelta = resolveDoubleProperty(
        OUTER_EARLY_STOP_MIN_DELTA_PROPERTY, 0.001, 0.0);
    double crossRate = 0.3;

    int nPar = AlgorithmConstants.HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT;
    int nOL = AlgorithmConstants.UPDATE_RATIO_CODE_COUNT;
    int nOO = AlgorithmConstants.OPERATION_ORDER_COUNT;
    int nSS = AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_COUNT;
    int n = AlgorithmConstants.HYBRID_CONFIG_DIMENSION;
    int[] stopConfig = resolveStopConfig(n, nPar, nOL, nOO, nSS);

    // Keep the original four training scales, using the validated -train copies.
    List<SceneConfig> sceneConfigs = Arrays.asList(
        new SceneConfig(SCENE_BASE_DIR + "\\500-5-0-0-24-train", 500, 5, 0.0),
        new SceneConfig(SCENE_BASE_DIR + "\\1000-10-0-0-12-train", 1000, 10, 0.0),
        new SceneConfig(SCENE_BASE_DIR + "\\1000-20-0-0-12-train", 1000, 20, 0.0),
        new SceneConfig(SCENE_BASE_DIR + "\\4000-40-0-0-6-train", 4000, 40, 0.0)
    );

    int totalCores = Runtime.getRuntime().availableProcessors();
    int evalThreads = Math.max(MIN_EVAL_THREADS, Math.min(MAX_EVAL_THREADS, totalCores / 4));
    int hybThreads = Math.max(MIN_HYB_THREADS, totalCores - evalThreads);
    System.out.printf("[THREADS] evalExecutor=%d, hybExecutor=%d, cores=%d%n",
        evalThreads, hybThreads, totalCores);
    System.out.printf(
        "[CONFIG] nPopHyb=%d, gMax=%d, nRun=%d, populationFactor=%.4f, iterationCoefficient=%.4f, fesBudgetRatio=%.2f%n",
        nPopHyb, gMax, nRun, HYBCEC_POPULATION_FACTOR,
        HYBCEC_ITERATION_COEFFICIENT, fesBudgetRatio);
    System.out.printf("[SEED] outerSearchSeed=%d (property=%s)%n",
        outerSearchSeed, OUTER_SEARCH_SEED_PROPERTY);
    if (stopConfig != null) {
      System.out.printf("[STOP] targetConfig=%s (property=%s)%n",
          Arrays.toString(stopConfig), OUTER_STOP_CONFIG_PROPERTY);
    }
    System.out.printf(
        "[EARLY_STOP] enabled=%s, minGenerations=%d, patience=%d, minDelta=%.6f%n",
        earlyStopEnabled, earlyStopMinGenerations, earlyStopPatience, earlyStopMinDelta);
    System.out.printf("[SCENES] %d scenes, fitness=sum(profit/totalProfit)%n",
        sceneConfigs.size());

    ExecutorService hybExecutor = Executors.newFixedThreadPool(hybThreads);
    ExecutorService evalExecutor = Executors.newFixedThreadPool(evalThreads);

    try {
      System.out.println("[LOADING] Loading all scenes...");
      List<LoadedScene> scenes = new ArrayList<>();
      for (SceneConfig sc : sceneConfigs) {
        LoadedScene loaded = loadScene(sc, fesBudgetRatio);
        scenes.add(loaded);
        System.out.printf("  Scene %s: nPop=%d, maxFes=%d%n",
            sc.sceneDir.substring(sc.sceneDir.lastIndexOf('\\') + 1),
            loaded.nPop, loaded.maxFes);
      }

      RandomProvider.reset(outerSearchSeed);
      RandomProvider.resetThreadCounter();
      int[][] pop = HybridInitial.hybridInitial(nPar, nOL, nOO, nSS, nPopHyb, false, null);

      System.out.printf("%n[EVAL] Evaluating initial population (%d individuals x %d scenes)%n",
          nPopHyb, scenes.size());
      double[] popFit = evaluatePopulationMultiScene(
          pop, scenes, nRun, hybExecutor, evalExecutor, "initial population");

      int bestIdx = 0;
      for (int i = 1; i < nPopHyb; i++) {
        if (popFit[i] > popFit[bestIdx]) bestIdx = i;
      }
      double bestFitness = popFit[bestIdx];
      int[] bestSolution = pop[bestIdx].clone();
      boolean targetReached = stopConfig != null && Arrays.equals(bestSolution, stopConfig);
      boolean earlyStopped = false;
      int noImprovementGenerations = 0;
      int lastImprovementGeneration = 0;
      int generationsExecuted = 0;
      if (targetReached) {
        System.out.printf("[STOP] targetConfig reached in initial population: %s%n",
            Arrays.toString(stopConfig));
      }

      System.out.printf("%n========================================%n");
      System.out.printf("Start multi-scene 6D MFEA optimization%n");
      System.out.printf("========================================%n%n");

      int[][] newPop = new int[nPopHyb][n];
      int[] parentIndices = new int[nPopHyb];
      int mergeSize = 2 * nPopHyb;
      int[][] popMerge = new int[mergeSize][];
      double[] popFitMerge = new double[mergeSize];
      int[] sortIndices = new int[mergeSize];
      Random outerRand = RandomProvider.newWithSeed(outerSearchSeed);
      for (int g = 0; g < gMax && !targetReached && !earlyStopped; g++) {
        long genStart = System.currentTimeMillis();

        for (int i = 0; i < nPopHyb; i++) parentIndices[i] = i;
        shuffleArray(parentIndices, outerRand);

        for (int i = 0; i < nPopHyb; i += 2) {
          int p1 = parentIndices[i];
          if (i + 1 < nPopHyb) {
            int p2 = parentIndices[i + 1];
            if (outerRand.nextDouble() < crossRate) {
              int cp = outerRand.nextInt(n - 1) + 1;
              int[] s1 = new int[n], s2 = new int[n];
              System.arraycopy(pop[p1], 0, s1, 0, cp);
              System.arraycopy(pop[p2], cp, s1, cp, n - cp);
              System.arraycopy(pop[p2], 0, s2, 0, cp);
              System.arraycopy(pop[p1], cp, s2, cp, n - cp);
              if (s1[0] == s1[1]) s1[1] = (s1[0] + 1 + outerRand.nextInt(nPar - 1)) % nPar;
              if (s2[0] == s2[1]) s2[1] = (s2[0] + 1 + outerRand.nextInt(nPar - 1)) % nPar;
              newPop[i] = s1;
              newPop[i + 1] = s2;
            } else {
              newPop[i] = MutationHyb.mutationHyb(pop[p1], generateMutationPoints(n, outerRand), nPar, nOL, nOO, nSS);
              newPop[i + 1] = MutationHyb.mutationHyb(pop[p2], generateMutationPoints(n, outerRand), nPar, nOL, nOO, nSS);
            }
          } else {
            newPop[i] = MutationHyb.mutationHyb(pop[p1], generateMutationPoints(n, outerRand), nPar, nOL, nOO, nSS);
          }
        }

        double[] popFitNew = evaluatePopulationMultiScene(
            newPop, scenes, nRun, hybExecutor, evalExecutor, "generation " + (g + 1));

        for (int i = 0; i < nPopHyb; i++) {
          popMerge[i] = pop[i];
          popMerge[i + nPopHyb] = newPop[i];
        }
        System.arraycopy(popFit, 0, popFitMerge, 0, nPopHyb);
        System.arraycopy(popFitNew, 0, popFitMerge, nPopHyb, nPopHyb);

        for (int i = 0; i < mergeSize; i++) sortIndices[i] = i;
        sortIndicesDescending(sortIndices, popFitMerge);

        for (int i = 0; i < nPopHyb; i++) {
          pop[i] = popMerge[sortIndices[i]];
          popFit[i] = popFitMerge[sortIndices[i]];
        }

        double previousBestFitness = bestFitness;
        if (popFit[0] > bestFitness) {
          bestFitness = popFit[0];
          bestSolution = pop[0].clone();
        }
        double improvement = bestFitness - previousBestFitness;
        if (improvement > earlyStopMinDelta) {
          noImprovementGenerations = 0;
          lastImprovementGeneration = g + 1;
        } else {
          noImprovementGenerations++;
        }
        generationsExecuted = g + 1;

        double genTime = (System.currentTimeMillis() - genStart) / 1000.0;
        System.out.printf(
            "[MultiScene][Gen %d/%d] bestFitness=%.6f, genBest=%.6f, bestConfig=%s, time=%.1fs%n",
            g + 1, gMax, bestFitness, popFit[0], Arrays.toString(bestSolution), genTime);
        if (stopConfig != null && Arrays.equals(bestSolution, stopConfig)) {
          targetReached = true;
          System.out.printf("[STOP] targetConfig reached at generation %d: %s%n",
              g + 1, Arrays.toString(stopConfig));
        } else if (shouldEarlyStop(earlyStopEnabled, g + 1, earlyStopMinGenerations,
            noImprovementGenerations, earlyStopPatience)) {
          earlyStopped = true;
          System.out.printf(
              "[EARLY_STOP] no improvement above %.6f for %d generations at generation %d; "
                  + "lastImprovementGen=%d%n",
              earlyStopMinDelta, noImprovementGenerations, g + 1, lastImprovementGeneration);
        }
      }

      String completionStatus = targetReached
          ? "stopped at target configuration"
          : earlyStopped ? "stopped by early stopping" : "completed";
      System.out.printf("%n========================================%n");
      System.out.printf("Multi-scene MFEA optimization %s%n", completionStatus);
      System.out.printf("========================================%n%n");
      System.out.printf("Best 6D config: %s%n", Arrays.toString(bestSolution));
      System.out.printf("Best aggregated fitness: %.6f%n", bestFitness);
      System.out.printf(
          "[SUMMARY] generationsExecuted=%d/%d, lastImprovementGen=%d, noImprovementGenerations=%d%n",
          generationsExecuted, gMax, lastImprovementGeneration, noImprovementGenerations);
      System.out.printf("  (higher = better normalized profit across all scenes)%n%n");

    } catch (Exception e) {
      System.out.println("\n===================================================");
      System.out.println("FATAL ERROR: " + e.getClass().getSimpleName());
      System.out.println("===================================================");
      System.out.println("Error message: " + e.getMessage());
      e.printStackTrace();
    } finally {
      hybExecutor.shutdownNow();
      evalExecutor.shutdownNow();
    }
  }

  private static int[] generateMutationPoints(int n, Random rand) {
    if (n <= 0) {
      throw new IllegalArgumentException("Mutation dimension must be positive: " + n);
    }
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

  private static void shuffleArray(int[] array, Random rand) {
    for (int i = array.length - 1; i > 0; i--) {
      int j = rand.nextInt(i + 1);
      int temp = array[i];
      array[i] = array[j];
      array[j] = temp;
    }
  }

  private static void sortIndicesDescending(int[] indices, double[] values) {
    Integer[] boxed = new Integer[indices.length];
    for (int i = 0; i < indices.length; i++) boxed[i] = indices[i];
    Arrays.sort(boxed, (a, b) -> Double.compare(values[b], values[a]));
    for (int i = 0; i < indices.length; i++) indices[i] = boxed[i];
  }
}
