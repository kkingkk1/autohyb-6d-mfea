package main;

import common.AlgorithmConstants;
import common.TuningConfig;
import common.TuningDefaults;
import model.Satellite;
import model.SchedulingPlan;
import model.Task;
import operator.assignment.MaxWindowCountStrategy;
import operator.insertion.InsertionMode;
import utils.AssignTaskOperator;
import utils.DataLoader;
import utils.Initialize;
import utils.PlanUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

public class RunSingleParentConfig {

  private static final int CONFIG_DIMENSION = 1;
  private static final int N_PAR = AlgorithmConstants.HYBCEC_PARENT_ALGORITHM_COUNT;
  private static final int DEFAULT_NUM_TASKS = 500;
  private static final int DEFAULT_NUM_SATELLITES = 5;
  private static final int DEFAULT_N_RUN = 1;
  private static final Path DEFAULT_BASELINE_FILE =
    Paths.get("outputs", "run-single-parent-baseline.properties");
  private static final int SMALL_TASK_THRESHOLD = 500;
  private static final int SMALL_TASK_ASSIGNMENT_STRATEGY = 7;
  private static final int LARGE_TASK_ASSIGNMENT_STRATEGY = 8;
  private static final boolean DEFAULT_ENABLE_DELTA_FAST_REFINE = true;
  private static final boolean DEFAULT_ENABLE_LOCAL_REFINE_HOT_START_BUDGET = true;
  private static final double DEBUG_POPULATION_FACTOR = 2.5;
  private static final double DEBUG_ITERATION_COEFFICIENT =
    TuningConfig.getDouble("iteration.coefficient", TuningDefaults.ITERATION_COEFFICIENT);
  private static final int[] DEFAULT_CONFIG = {
    2
  };
  private static final String DEFAULT_DATA_DIR =
    ScenePathResolver.defaultSceneDir("500-5-0-0-24");
  private static final String LOCAL_REFINE_HOT_START_BUDGET_PROPERTY =
    "taskcoord.localRefineHotStartBudget.enabled";
  private static final String DELTA_FAST_REFINE_ENABLED_PROPERTY =
    "taskcoord.deltaFastRefine.enabled";
  private static final String RUNNER_NAME = "RunSingleParentConfig";
  private static final int DEFAULT_MAX_REASSIGN_ITERATIONS =
    TuningConfig.getInt("eval.maxReassignIterations", TuningDefaults.EVAL_MAX_REASSIGN_ITERATIONS);
  private static final int DEFAULT_KEY_PERTURB_THRESHOLD =
    TuningConfig.getInt("eval.keyPerturbThreshold", TuningDefaults.EVAL_KEY_PERTURB_THRESHOLD);
  private static final double DEFAULT_KEY_PERTURB_STRENGTH =
    TuningConfig.getDouble("eval.keyPerturbStrength", TuningDefaults.EVAL_KEY_PERTURB_STRENGTH);

  private static final class RunArguments {
    private final boolean enableDeltaFastRefine;
    private final boolean enableLocalRefineHotStartBudget;
    private final String dataDir;
    private final int numTasks;
    private final int numSatellites;
    private final int[] config;
    private final int nRun;
    private final Integer nPopOverride;
    private final Integer maxFesOverride;
    private final Double populationFactorOverride;
    private final int assignmentStrategy;

    private RunArguments(
      boolean enableDeltaFastRefine,
      boolean enableLocalRefineHotStartBudget,
      String dataDir,
      int numTasks,
      int numSatellites,
      int[] config,
      int nRun,
      Integer nPopOverride,
      Integer maxFesOverride,
      Double populationFactorOverride,
      int assignmentStrategy) {
      this.enableDeltaFastRefine = enableDeltaFastRefine;
      this.enableLocalRefineHotStartBudget = enableLocalRefineHotStartBudget;
      this.dataDir = dataDir;
      this.numTasks = numTasks;
      this.numSatellites = numSatellites;
      this.config = config;
      this.nRun = nRun;
      this.nPopOverride = nPopOverride;
      this.maxFesOverride = maxFesOverride;
      this.populationFactorOverride = populationFactorOverride;
      this.assignmentStrategy = assignmentStrategy;
    }
  }

  static final class ParsedConfig {
    final int[] config;

    ParsedConfig(int[] config) {
      this.config = config;
    }
  }

  private static final class BaselineOptions {
    private final boolean recordBaseline;
    private final Path baselineFile;
    private final String[] remainingArgs;

    private BaselineOptions(
      boolean recordBaseline,
      Path baselineFile,
      String[] remainingArgs) {
      this.recordBaseline = recordBaseline;
      this.baselineFile = baselineFile;
      this.remainingArgs = remainingArgs;
    }
  }

  static final class ConvergenceOptions {
    final Path convergenceFile;
    final String[] remainingArgs;

    ConvergenceOptions(Path convergenceFile, String[] remainingArgs) {
      this.convergenceFile = convergenceFile;
      this.remainingArgs = remainingArgs;
    }
  }

  static final class RunOptions {
    final int runIndexBase;
    final Long runSeedOverride;
    final boolean generationLogEnabled;
    final int logInterval;
    final String[] remainingArgs;

    RunOptions(
      int runIndexBase,
      Long runSeedOverride,
      boolean generationLogEnabled,
      int logInterval,
      String[] remainingArgs) {
      this.runIndexBase = runIndexBase;
      this.runSeedOverride = runSeedOverride;
      this.generationLogEnabled = generationLogEnabled;
      this.logInterval = logInterval;
      this.remainingArgs = remainingArgs;
    }
  }

  private static final class RunResult {
    private final double optimizedProfit;
    private final List<TaskAllocationCoordinator.ConvergenceTrace> convergenceTraces;

    private RunResult(
      double optimizedProfit,
      List<TaskAllocationCoordinator.ConvergenceTrace> convergenceTraces) {
      this.optimizedProfit = optimizedProfit;
      this.convergenceTraces = convergenceTraces;
    }
  }

  private static final class RunConvergenceTrace {
    private final int runIndex;
    private final TaskAllocationCoordinator.ConvergenceTrace trace;

    private RunConvergenceTrace(
      int runIndex,
      TaskAllocationCoordinator.ConvergenceTrace trace) {
      this.runIndex = runIndex;
      this.trace = trace;
    }
  }

  private static final class RunSummary {
    private final String metricLabel;
    private final double primaryProfit;
    private final double bestProfit;
    private final double profitRatio;
    private final long elapsedMillis;
    private final int totalTaskCount;
    private final int assignedTaskCount;
    private final int totalPossibleFitness;
    private final int nPop;
    private final int maxFes;

    private RunSummary(
      String metricLabel,
      double primaryProfit,
      double bestProfit,
      double profitRatio,
      long elapsedMillis,
      int totalTaskCount,
      int assignedTaskCount,
      int totalPossibleFitness,
      int nPop,
      int maxFes) {
      this.metricLabel = metricLabel;
      this.primaryProfit = primaryProfit;
      this.bestProfit = bestProfit;
      this.profitRatio = profitRatio;
      this.elapsedMillis = elapsedMillis;
      this.totalTaskCount = totalTaskCount;
      this.assignedTaskCount = assignedTaskCount;
      this.totalPossibleFitness = totalPossibleFitness;
      this.nPop = nPop;
      this.maxFes = maxFes;
    }
  }

  private static RunArguments parseArgs(String[] args) {
    boolean enableDeltaFastRefine = args.length > 0
      ? parseBooleanSwitch(args[0], "enableDeltaFastRefine")
      : DEFAULT_ENABLE_DELTA_FAST_REFINE;
    boolean enableLocalRefineHotStartBudget = args.length > 1
      ? parseBooleanSwitch(args[1], "enableLocalRefineHotStartBudget")
      : DEFAULT_ENABLE_LOCAL_REFINE_HOT_START_BUDGET;
    String dataDir = args.length > 2 ? args[2] : DEFAULT_DATA_DIR;
    int numTasks = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_NUM_TASKS;
    int numSatellites = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_NUM_SATELLITES;
    ParsedConfig parsedConfig = args.length > 5
      ? parseConfig(args[5])
      : new ParsedConfig(DEFAULT_CONFIG.clone());
    int[] config = parsedConfig.config;
    int nRun = args.length > 6 ? Integer.parseInt(args[6]) : DEFAULT_N_RUN;
    Integer nPopOverride = args.length > 7 ? parseOptionalIntegerArg(args[7]) : null;
    Integer maxFesOverride = args.length > 8 ? parseOptionalIntegerArg(args[8]) : null;
    Double populationFactorOverride = args.length > 9 ? parseOptionalDoubleArg(args[9]) : null;
    int assignmentStrategy = defaultAssignmentStrategy(numTasks);
    validateArgs(
      numTasks,
      numSatellites,
      config,
      nRun,
      nPopOverride,
      maxFesOverride,
      populationFactorOverride
    );
    return new RunArguments(
      enableDeltaFastRefine,
      enableLocalRefineHotStartBudget,
      dataDir,
      numTasks,
      numSatellites,
      config,
      nRun,
      nPopOverride,
      maxFesOverride,
      populationFactorOverride,
      assignmentStrategy
    );
  }

  static ParsedConfig parseConfig(String configCsv) {
    String[] parts = configCsv.split(",");
    if (parts.length != CONFIG_DIMENSION) {
      throw new IllegalArgumentException(
        "single-algorithm configCsv must contain exactly 1 integer, got: "
          + configCsv
      );
    }
    int[] rawConfig = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      rawConfig[i] = Integer.parseInt(parts[i].trim());
    }
    return new ParsedConfig(new int[] {rawConfig[0]});
  }

  static TaskAllocationCoordinator.HybCecConfig buildSingleAlgorithmConfig(
    int[] config,
    int nPop,
    int maxFes,
    InsertionMode insertionMode,
    long seed) {
    return new TaskAllocationCoordinator.HybCecConfig(
      new int[] {config[0], config[0]},
      new int[] {
        AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
        AlgorithmConstants.UPDATE_RATIO_CODE_FULL
      },
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
      AlgorithmConstants.OPERATION_ORDER_SEQUENTIAL,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_ELITIST_BEST_N,
      nPop,
      maxFes,
      insertionMode,
      seed,
      true
    );
  }

  private static int defaultAssignmentStrategy(int numTasks) {
    return numTasks <= SMALL_TASK_THRESHOLD
      ? SMALL_TASK_ASSIGNMENT_STRATEGY
      : LARGE_TASK_ASSIGNMENT_STRATEGY;
  }

  private static boolean parseBooleanSwitch(String rawValue, String label) {
    if ("true".equalsIgnoreCase(rawValue)) {
      return true;
    }
    if ("false".equalsIgnoreCase(rawValue)) {
      return false;
    }
    throw new IllegalArgumentException(label + " must be true or false, got: " + rawValue);
  }

  private static Integer parseOptionalIntegerArg(String rawValue) {
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return null;
    }
    return Integer.parseInt(rawValue.trim());
  }

  private static Double parseOptionalDoubleArg(String rawValue) {
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return null;
    }
    return Double.parseDouble(rawValue.trim());
  }

  private static void validateArgs(
    int numTasks,
    int numSatellites,
    int[] config,
    int nRun,
    Integer nPopOverride,
    Integer maxFesOverride,
    Double populationFactorOverride) {
    if (numTasks <= 0) {
      throw new IllegalArgumentException("numTasks must be positive, got: " + numTasks);
    }
    if (numSatellites <= 0) {
      throw new IllegalArgumentException("numSatellites must be positive, got: " + numSatellites);
    }
    if (nRun <= 0) {
      throw new IllegalArgumentException("nRun must be positive, got: " + nRun);
    }
    if (nPopOverride != null && nPopOverride <= 0) {
      throw new IllegalArgumentException("nPop must be positive when provided, got: " + nPopOverride);
    }
    if (maxFesOverride != null && maxFesOverride <= 0) {
      throw new IllegalArgumentException(
        "maxFes must be positive when provided, got: " + maxFesOverride
      );
    }
    if (populationFactorOverride != null
      && (!Double.isFinite(populationFactorOverride) || populationFactorOverride <= 0.0)) {
      throw new IllegalArgumentException(
        "populationFactorOverride must be a positive finite value, got: "
          + populationFactorOverride
      );
    }
    if (config == null || config.length != CONFIG_DIMENSION) {
      throw new IllegalArgumentException(
        "single-algorithm config must contain exactly " + CONFIG_DIMENSION + " integer"
      );
    }
    validateRange("config[0]", config[0], 0, N_PAR - 1);
  }

  private static void validateRange(String label, int value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(label + " must be in [" + min + ", " + max + "], got: " + value);
    }
  }

  private static String[] extractConfigArg(String[] args) {
    List<String> remaining = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if ("--config".equals(args[i]) && i + 1 < args.length) {
        System.setProperty(TuningConfig.CONFIG_PATH_PROPERTY, args[i + 1]);
        i++;
      } else {
        remaining.add(args[i]);
      }
    }
    TuningConfig.reload();
    return remaining.toArray(new String[0]);
  }

  private static BaselineOptions extractBaselineOptions(String[] args) {
    List<String> remaining = new ArrayList<>();
    boolean recordBaseline = false;
    Path baselineFile = DEFAULT_BASELINE_FILE;
    for (int i = 0; i < args.length; i++) {
      if ("--record-baseline".equals(args[i])) {
        recordBaseline = true;
      } else if ("--baseline-file".equals(args[i]) && i + 1 < args.length) {
        baselineFile = Paths.get(args[i + 1]);
        i++;
      } else {
        remaining.add(args[i]);
      }
    }
    return new BaselineOptions(
      recordBaseline,
      baselineFile,
      remaining.toArray(new String[0])
    );
  }

  static ConvergenceOptions extractConvergenceOptions(String[] args) {
    List<String> remaining = new ArrayList<>();
    Path convergenceFile = null;
    for (int i = 0; i < args.length; i++) {
      if ("--convergence-file".equals(args[i]) && i + 1 < args.length) {
        convergenceFile = Paths.get(args[i + 1]);
        i++;
      } else {
        remaining.add(args[i]);
      }
    }
    return new ConvergenceOptions(convergenceFile, remaining.toArray(new String[0]));
  }

  static RunOptions extractRunOptions(String[] args) {
    List<String> remaining = new ArrayList<>();
    int runIndexBase = configuredRunIndexBase();
    Long runSeedOverride = null;
    boolean generationLogEnabled = Boolean.getBoolean(RunSingleHybridConfig.GENERATION_LOG_PROPERTY)
      || Boolean.getBoolean(RunSingleHybridConfig.GENERATION_LOG_ENABLED_PROPERTY);
    int logInterval = Integer.getInteger(RunSingleHybridConfig.LOG_INTERVAL_PROPERTY, 1);
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if ("--run-index-base".equals(arg) && i + 1 < args.length) {
        runIndexBase = Integer.parseInt(args[i + 1]);
        i++;
      } else if (arg.startsWith("--run-index-base=")) {
        runIndexBase = Integer.parseInt(arg.substring("--run-index-base=".length()));
      } else if ("--run-seed".equals(arg) && i + 1 < args.length) {
        runSeedOverride = parseRunSeed(args[i + 1]);
        i++;
      } else if (arg.startsWith("--run-seed=")) {
        runSeedOverride = parseRunSeed(arg.substring("--run-seed=".length()));
      } else if ("--generation-log".equals(arg)) {
        generationLogEnabled = true;
      } else if (arg.startsWith("--log-interval=")) {
        logInterval = parseLogInterval(arg.substring("--log-interval=".length()));
      } else {
        remaining.add(arg);
      }
    }
    if (runIndexBase < 0) {
      throw new IllegalArgumentException("runIndexBase must be non-negative, got: " + runIndexBase);
    }
    if (logInterval <= 0) {
      throw new IllegalArgumentException("logInterval must be positive, got: " + logInterval);
    }
    System.setProperty(
      RunSingleHybridConfig.GENERATION_LOG_ENABLED_PROPERTY,
      Boolean.toString(generationLogEnabled));
    System.setProperty(
      RunSingleHybridConfig.GENERATION_LOG_PROPERTY,
      Boolean.toString(generationLogEnabled));
    System.setProperty(RunSingleHybridConfig.LOG_INTERVAL_PROPERTY, Integer.toString(logInterval));
    return new RunOptions(
      runIndexBase,
      runSeedOverride,
      generationLogEnabled,
      logInterval,
      remaining.toArray(new String[0]));
  }

  private static int parseLogInterval(String rawValue) {
    int value = Integer.parseInt(rawValue.trim());
    if (value <= 0) {
      throw new IllegalArgumentException("--log-interval must be positive, got: " + rawValue);
    }
    return value;
  }

  private static long parseRunSeed(String rawValue) {
    String trimmedValue = rawValue == null ? "" : rawValue.trim();
    if (trimmedValue.isEmpty()) {
      throw new IllegalArgumentException("--run-seed must be a long, got: " + rawValue);
    }
    try {
      return Long.parseLong(trimmedValue);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--run-seed must be a long, got: " + rawValue, e);
    }
  }

  static long resolveExplicitRunSeed(long firstRunSeed, int runOffset) {
    if (runOffset < 0) {
      throw new IllegalArgumentException("runOffset must be non-negative, got: " + runOffset);
    }
    try {
      return Math.addExact(firstRunSeed, runOffset);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
        "Run seed overflow: firstRunSeed=" + firstRunSeed + ", runOffset=" + runOffset,
        e
      );
    }
  }

  private static int configuredRunIndexBase() {
    String configuredValue = System.getProperty(EvaluationHyb.RUN_INDEX_BASE_PROPERTY);
    if (configuredValue == null || configuredValue.trim().isEmpty()) {
      return 0;
    }
    try {
      long parsedValue = Long.parseLong(configuredValue.trim());
      if (parsedValue < 0L || parsedValue > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(
          EvaluationHyb.RUN_INDEX_BASE_PROPERTY
            + " must be in [0, " + Integer.MAX_VALUE + "], got: " + configuredValue
        );
      }
      return (int) parsedValue;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
        EvaluationHyb.RUN_INDEX_BASE_PROPERTY + " must be a non-negative integer, got: " + configuredValue,
        e
      );
    }
  }

  private static long stableSeed(int[] config, int nPop, int maxFes) {
    long seed = AlgorithmConstants.FIXED_RANDOM_SEED_LONG;
    seed = 31L * seed + CONFIG_DIMENSION;
    seed = 31L * seed + nPop;
    seed = 31L * seed + maxFes;
    for (int value : config) {
      seed = 31L * seed + value;
    }
    return seed;
  }

  static long resolveRunSeed(int[] config, int nPop, int maxFes, int runIndex) {
    long defaultSeedBase = Math.addExact(
      stableSeed(config, nPop, maxFes),
      EvaluationHyb.RUN_SEED_OFFSET
    );
    return EvaluationHyb.resolveConfiguredRunSeed(defaultSeedBase, runIndex);
  }

  private static java.util.Map<Integer, Integer> buildInitialAssignment(List<Satellite> allSatellites) {
    java.util.Map<Integer, Integer> initialAssignment = new HashMap<>();
    for (Satellite satellite : allSatellites) {
      for (Task task : satellite.getAssignedTasks()) {
        initialAssignment.put(task.getId(), satellite.getId());
      }
    }
    return initialAssignment;
  }

  private static List<Satellite> copySatellites(List<Satellite> sourceSatellites) {
    List<Satellite> copiedSatellites = new ArrayList<>(sourceSatellites.size());
    for (Satellite satellite : sourceSatellites) {
      Satellite copied = new Satellite(satellite.getId());
      copied.setTimeWindows(satellite.getTimeWindows());
      copied.setAssignedTasks(satellite.getAssignedTasks());
      copiedSatellites.add(copied);
    }
    return copiedSatellites;
  }

  private static double evaluatePlansWithInsertionMode(
    List<Satellite> satellites,
    java.util.Map<Integer, SchedulingPlan> plans,
    InsertionMode insertionMode) {
    double totalProfit = 0.0;
    for (Satellite satellite : satellites) {
      SchedulingPlan plan = plans.get(satellite.getId());
      if (plan == null) {
        continue;
      }
      totalProfit += PlanUtils.evaluatePlan(plan.copy(), satellite, insertionMode);
    }
    return totalProfit;
  }

  private static int calculateTotalTaskPriority(List<Task> allTasks) {
    int totalPriority = 0;
    for (Task task : allTasks) {
      totalPriority += task.getPriority();
    }
    return totalPriority;
  }

  private static int countAssignedTasks(List<Satellite> allSatellites) {
    int assignedTaskCount = 0;
    for (Satellite satellite : allSatellites) {
      List<Task> assignedTasks = satellite.getAssignedTasks();
      if (assignedTasks != null) {
        assignedTaskCount += assignedTasks.size();
      }
    }
    return assignedTaskCount;
  }

  private static double calculatePercentage(double numerator, double denominator) {
    if (denominator <= 0.0) {
      return 0.0;
    }
    return numerator * 100.0 / denominator;
  }

  static String configToCsv(int[] config) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < config.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(config[i]);
    }
    return builder.toString();
  }

  private static Properties createBaselineProperties(
    String dataDir,
    int numTasks,
    int numSatellites,
    int[] config,
    int nRun,
    int nPop,
    int maxFes,
    String metricLabel,
    double metricValue,
    double bestProfit,
    double profitRatio,
    long elapsedMillis,
    int totalTaskCount,
    int assignedTaskCount,
    int totalPossibleFitness) {
    Properties properties = new Properties();
    properties.setProperty("runner", RUNNER_NAME);
    properties.setProperty("dataDir", dataDir);
    properties.setProperty("numTasks", String.valueOf(numTasks));
    properties.setProperty("numSatellites", String.valueOf(numSatellites));
    properties.setProperty("configCsv", configToCsv(config));
    properties.setProperty("nRun", String.valueOf(nRun));
    properties.setProperty("nPop", String.valueOf(nPop));
    properties.setProperty("maxFes", String.valueOf(maxFes));
    properties.setProperty("metricLabel", metricLabel);
    properties.setProperty("metricValue", String.valueOf(metricValue));
    properties.setProperty("bestProfit", String.valueOf(bestProfit));
    properties.setProperty("profitRatio", String.valueOf(profitRatio));
    properties.setProperty("elapsedMillis", String.valueOf(elapsedMillis));
    properties.setProperty("totalTaskCount", String.valueOf(totalTaskCount));
    properties.setProperty("assignedTaskCount", String.valueOf(assignedTaskCount));
    properties.setProperty("totalPossibleFitness", String.valueOf(totalPossibleFitness));
    properties.setProperty("timestamp", java.time.Instant.now().toString());
    return properties;
  }

  private static boolean baselineContextMatches(
    Properties properties,
    String dataDir,
    int numTasks,
    int numSatellites,
    int[] config,
    int nRun,
    int nPop,
    int maxFes) {
    return RUNNER_NAME.equals(properties.getProperty("runner"))
      && dataDir.equals(properties.getProperty("dataDir"))
      && String.valueOf(numTasks).equals(properties.getProperty("numTasks"))
      && String.valueOf(numSatellites).equals(properties.getProperty("numSatellites"))
      && configToCsv(config).equals(properties.getProperty("configCsv"))
      && String.valueOf(nRun).equals(properties.getProperty("nRun"))
      && String.valueOf(nPop).equals(properties.getProperty("nPop"))
      && String.valueOf(maxFes).equals(properties.getProperty("maxFes"));
  }

  private static Properties loadBaselineProperties(Path baselineFile) throws IOException {
    Properties properties = new Properties();
    try (InputStream inputStream = Files.newInputStream(baselineFile)) {
      properties.load(inputStream);
    }
    return properties;
  }

  private static void printBaselineComparison(
    RunArguments arguments,
    RunSummary runSummary,
    Path baselineFile) throws IOException {
    if (!Files.exists(baselineFile)) {
      System.out.printf("[Baseline] No baseline file found at %s%n", baselineFile.toAbsolutePath());
      return;
    }

    Properties properties = loadBaselineProperties(baselineFile);
    if (!baselineContextMatches(
      properties,
      arguments.dataDir,
      arguments.numTasks,
      arguments.numSatellites,
      arguments.config,
      arguments.nRun,
      runSummary.nPop,
      runSummary.maxFes)) {
      return;
    }

    double baselineMetricValue = Double.parseDouble(properties.getProperty("metricValue"));
    double baselineProfitRatio = Double.parseDouble(properties.getProperty("profitRatio"));
    long baselineElapsedMillis = Long.parseLong(properties.getProperty("elapsedMillis"));
    double profitDelta = runSummary.primaryProfit - baselineMetricValue;
    double ratioDelta = runSummary.profitRatio - baselineProfitRatio;
    long elapsedDeltaMillis = runSummary.elapsedMillis - baselineElapsedMillis;

    System.out.printf(
      "[Baseline] %s delta: %+,.2f | ratio delta: %+,.2f%% | elapsed delta: %+,.2f s%n",
      runSummary.metricLabel,
      profitDelta,
      ratioDelta,
      elapsedDeltaMillis / 1000.0
    );
  }

  private static void writeBaselineProperties(
    RunArguments arguments,
    RunSummary runSummary,
    Path baselineFile) throws IOException {
    Path parent = baselineFile.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Properties properties = createBaselineProperties(
      arguments.dataDir,
      arguments.numTasks,
      arguments.numSatellites,
      arguments.config,
      arguments.nRun,
      runSummary.nPop,
      runSummary.maxFes,
      runSummary.metricLabel,
      runSummary.primaryProfit,
      runSummary.bestProfit,
      runSummary.profitRatio,
      runSummary.elapsedMillis,
      runSummary.totalTaskCount,
      runSummary.assignedTaskCount,
      runSummary.totalPossibleFitness
    );
    try (OutputStream outputStream = Files.newOutputStream(baselineFile)) {
      properties.store(outputStream, "RunSingleParentConfig performance baseline");
    }
    System.out.printf("[Baseline] Recorded baseline to %s%n", baselineFile.toAbsolutePath());
  }

  private static RunResult runSingleParentOnce(
    List<Task> allTasks,
    List<Satellite> templateSatellites,
    java.util.Map<Integer, Integer> initialAssignment,
    int[] config,
    int nPop,
    int maxFes,
    long runSeed,
    InsertionMode insertionMode) throws Exception {
    List<Satellite> runSatellites = copySatellites(templateSatellites);
    TaskAllocationCoordinator coordinator = new TaskAllocationCoordinator(
      allTasks,
      runSatellites,
      new MaxWindowCountStrategy(),
      DEFAULT_MAX_REASSIGN_ITERATIONS,
      DEFAULT_KEY_PERTURB_THRESHOLD,
      DEFAULT_KEY_PERTURB_STRENGTH
    );
    TaskAllocationCoordinator.HybCecConfig hybCecConfig = buildSingleAlgorithmConfig(
      config,
      nPop,
      maxFes,
      insertionMode,
      runSeed
    );
    java.util.Map<Integer, SchedulingPlan> optimizedPlans = coordinator.scheduleWithReassignment(
      hybCecConfig,
      runSatellites,
      initialAssignment.isEmpty() ? null : new HashMap<>(initialAssignment)
    );
    double optimizedProfit = evaluatePlansWithInsertionMode(runSatellites, optimizedPlans, insertionMode);
    return new RunResult(optimizedProfit, coordinator.getConvergenceTracesSnapshot());
  }

  private static void writeConvergenceFile(
    Path outputFile,
    List<RunConvergenceTrace> traces,
    int[] config,
    int nPop,
    int maxFes) throws IOException {
    if (outputFile == null) {
      return;
    }
    Path parent = outputFile.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    List<String> lines = new ArrayList<>();
    lines.add("configCsv,nPop,maxFes,runIndex,traceIndex,satelliteId,ensembleIndex,traceMaxFes,gen,evals,bestFitness");
    String configCsv = csvEscape(configToCsv(config));
    for (RunConvergenceTrace runTrace : traces) {
      TaskAllocationCoordinator.ConvergenceTrace trace = runTrace.trace;
      int maxGen = countRecordedGenerations(trace.bestFitnessByGeneration);
      for (int gen = 0; gen < maxGen; gen++) {
        double bestFitness = trace.bestFitnessByGeneration.get(gen);
        int evals = gen < trace.evalsByGeneration.size()
          ? trace.evalsByGeneration.get(gen)
          : -1;
        lines.add(String.format(
          java.util.Locale.ROOT,
          "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f",
          configCsv,
          nPop,
          maxFes,
          runTrace.runIndex,
          trace.traceIndex,
          trace.satelliteId,
          trace.ensembleIndex,
          trace.maxFes,
          gen,
          evals,
          bestFitness
        ));
      }
    }
    Files.write(outputFile, lines);
    System.out.printf("[Convergence] Wrote %d trace rows to %s%n",
      Math.max(0, lines.size() - 1),
      outputFile.toAbsolutePath());
  }

  private static String csvEscape(String value) {
    if (value == null) {
      return "";
    }
    if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
      return value;
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static int countRecordedGenerations(List<Double> bestFitnessByGeneration) {
    int lastRecordedIndex = -1;
    for (int i = 0; i < bestFitnessByGeneration.size(); i++) {
      double value = bestFitnessByGeneration.get(i);
      if (Double.isFinite(value) && value != 0.0) {
        lastRecordedIndex = i;
      }
    }
    return lastRecordedIndex + 1;
  }

  private static RunSummary buildRunSummary(
    RunArguments arguments,
    double meanProfit,
    double bestProfit,
    int assignedTaskCount,
    int totalPossibleFitness,
    int nPop,
    int maxFes,
    long elapsedMillis) {
    if (arguments.nRun > 1) {
      return new RunSummary(
        "meanProfit",
        meanProfit,
        bestProfit,
        calculatePercentage(meanProfit, totalPossibleFitness),
        elapsedMillis,
        arguments.numTasks,
        assignedTaskCount,
        totalPossibleFitness,
        nPop,
        maxFes
      );
    }

    return new RunSummary(
      "optimizedProfit",
      bestProfit,
      bestProfit,
      calculatePercentage(bestProfit, totalPossibleFitness),
      elapsedMillis,
      arguments.numTasks,
      assignedTaskCount,
      totalPossibleFitness,
      nPop,
      maxFes
    );
  }

  private static void printCompactSummary(double optimizedProfit, double totalPossibleFitness, long elapsedMillis) {
    System.out.printf(
      "Optimized profit ratio: %.2f%%%n",
      calculatePercentage(optimizedProfit, totalPossibleFitness)
    );
    System.out.printf("Elapsed: %.2f s%n", elapsedMillis / 1000.0);
  }

  private static void printAverageSummary(double meanProfit, double bestProfit, long wallClockMillis, int nRun) {
    double avgMillis = (double) wallClockMillis / nRun;
    System.out.println("============= Single-Parent Multi-Run Summary =============");
    System.out.printf("Avg profit: %.2f%n", meanProfit);
    System.out.printf("Best profit: %.2f%n", bestProfit);
    System.out.printf("Wall clock: %.2f s%n", wallClockMillis / 1000.0);
    System.out.printf("Avg elapsed (wall / nRun): %.2f s%n", avgMillis / 1000.0);
    System.out.println("===========================================================");
  }

  private static void printRunDetail(int runIndex, long runSeed, double optimizedProfit, long elapsedMillis) {
    System.out.printf(
      "[Run] index=%d seed=%d profit=%.2f elapsed=%.2f s%n",
      runIndex,
      runSeed,
      optimizedProfit,
      elapsedMillis / 1000.0
    );
  }

  private static void printUsage() {
    System.out.println("Usage: java -cp target/classes main.RunSingleParentConfig [--config <path>] [--record-baseline] [--baseline-file <path>] [--convergence-file <path>] [--run-index-base N] [--run-seed S] [--generation-log] [--log-interval=N] [enableDeltaFastRefine] [enableLocalRefineHotStartBudget] [dataDir] [numTasks] [numSatellites] [configCsv] [nRun] [nPop] [maxFes] [populationFactor]");
    System.out.println("  configCsv format: algorithmIndex");
    System.out.println("  the dynamic reassignment layer calls SingleAlgorithmOptimizer directly with pairwise greedy replacement");
    System.out.println("  --run-seed S: use S for runOffset=0 and S+runOffset for later runs; this overrides calculated Parent seeds");
    System.out.println("  seed pairing: pass Hybrid's final perRun seed through --run-seed S in the 1D entry; run i uses S+i");
    System.out.println("  --generation-log prints per-generation best fitness; --log-interval=N controls the sampling interval");
    System.out.println("  --convergence-file writes per-run, per-satellite single-algorithm best-fitness traces as CSV");
    System.out.println("Example: java -cp target/classes main.RunSingleParentConfig --generation-log --log-interval=10 true true \"" + DEFAULT_DATA_DIR + "\" 500 5 \"1\" 1 270 16200");
  }

  public static void main(String[] args) {
    if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
      printUsage();
      return;
    }

    args = extractConfigArg(args);
    BaselineOptions baselineOptions = extractBaselineOptions(args);
    ConvergenceOptions convergenceOptions = extractConvergenceOptions(baselineOptions.remainingArgs);
    RunOptions runOptions = extractRunOptions(convergenceOptions.remainingArgs);
    args = runOptions.remainingArgs;

    try {
      RunArguments arguments = parseArgs(args);
      System.setProperty(
        DELTA_FAST_REFINE_ENABLED_PROPERTY,
        Boolean.toString(arguments.enableDeltaFastRefine)
      );
      System.setProperty(
        LOCAL_REFINE_HOT_START_BUDGET_PROPERTY,
        Boolean.toString(arguments.enableLocalRefineHotStartBudget)
      );

      DataLoader.DataSet dataSet = DataLoader.loadAllData(
        arguments.dataDir,
        arguments.numTasks,
        arguments.numSatellites
      );
      List<Task> allTasks = dataSet.taskList;
      List<Satellite> templateSatellites = dataSet.satelliteList;
      int computedDefaultAssignmentStrategy = defaultAssignmentStrategy(arguments.numTasks);

      System.out.printf(
        "[RunSingleParentConfig] numTasks=%d, assignmentStrategy=%d, computedDefaultAssignmentStrategy=%d, configCsv=%s, algorithmIndex=%d, mode=standalone-greedy, generationLog=%s, logInterval=%d, sourceVersion=2026-07-24-single-algorithm-optimizer%n",
        arguments.numTasks,
        arguments.assignmentStrategy,
        computedDefaultAssignmentStrategy,
        configToCsv(arguments.config),
        arguments.config[0],
        runOptions.generationLogEnabled,
        runOptions.logInterval
      );

      List<List<Integer>> initialAssignmentBySatellite = arguments.assignmentStrategy >= 0
        ? Initialize.initializeWithStrategy(allTasks, templateSatellites, arguments.assignmentStrategy)
        : Initialize.initialize(allTasks, templateSatellites);
      AssignTaskOperator.assignTaskToSatellites(
        initialAssignmentBySatellite,
        allTasks,
        templateSatellites
      );
      InsertionMode insertionMode = InsertionMode.GREEDY;
      int assignedTaskCount = countAssignedTasks(templateSatellites);
      int totalPossibleFitness = calculateTotalTaskPriority(allTasks);
      int maxAssignedTaskCount = templateSatellites.stream()
        .mapToInt(satellite -> satellite.getAssignedTasks().size())
        .max()
        .orElse(0);
      double populationFactor = arguments.populationFactorOverride != null
        ? arguments.populationFactorOverride
        : DEBUG_POPULATION_FACTOR;
      int autoNPop = Math.max(1, (int) Math.round(populationFactor * Math.max(1, maxAssignedTaskCount)));
      int autoMaxFes = (int) Math.round(
        populationFactor * Math.max(1, maxAssignedTaskCount) * DEBUG_ITERATION_COEFFICIENT
      );
      int nPop = arguments.nPopOverride != null ? arguments.nPopOverride : autoNPop;
      int maxFes = arguments.maxFesOverride != null ? arguments.maxFesOverride : autoMaxFes;

      System.out.println(RunSingleHybridConfig.buildBudgetSummary(
        populationFactor,
        DEBUG_ITERATION_COEFFICIENT,
        maxAssignedTaskCount,
        autoNPop,
        autoMaxFes,
        arguments.nPopOverride,
        arguments.maxFesOverride,
        nPop,
        maxFes
      ));

      java.util.Map<Integer, Integer> initialAssignment = buildInitialAssignment(templateSatellites);
      long wallClockStartNanos = System.nanoTime();
      double profitSum = 0.0;
      double bestProfit = Double.NEGATIVE_INFINITY;
      List<RunConvergenceTrace> convergenceTraces = new ArrayList<>();
      for (int runOffset = 0; runOffset < arguments.nRun; runOffset++) {
        int runIndex = runOptions.runIndexBase + runOffset;
        long runSeed = runOptions.runSeedOverride == null
          ? resolveRunSeed(arguments.config, nPop, maxFes, runIndex)
          : resolveExplicitRunSeed(runOptions.runSeedOverride, runOffset);
        long runStartNanos = System.nanoTime();
        RunResult runResult = runSingleParentOnce(
          allTasks,
          templateSatellites,
          initialAssignment,
          arguments.config,
          nPop,
          maxFes,
          runSeed,
          insertionMode
        );
        long runElapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
          System.nanoTime() - runStartNanos
        );
        printRunDetail(runIndex, runSeed, runResult.optimizedProfit, runElapsedMillis);
        profitSum += runResult.optimizedProfit;
        bestProfit = Math.max(bestProfit, runResult.optimizedProfit);
        for (TaskAllocationCoordinator.ConvergenceTrace trace : runResult.convergenceTraces) {
          convergenceTraces.add(new RunConvergenceTrace(runIndex, trace));
        }
      }
      long totalElapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
        System.nanoTime() - wallClockStartNanos
      );
      double meanProfit = profitSum / arguments.nRun;

      RunSummary runSummary = buildRunSummary(
        arguments,
        meanProfit,
        bestProfit,
        assignedTaskCount,
        totalPossibleFitness,
        nPop,
        maxFes,
        totalElapsedMillis
      );

      if (arguments.nRun > 1) {
        printAverageSummary(meanProfit, bestProfit, totalElapsedMillis, arguments.nRun);
      } else {
        printCompactSummary(bestProfit, totalPossibleFitness, totalElapsedMillis);
      }

      writeConvergenceFile(
        convergenceOptions.convergenceFile,
        convergenceTraces,
        arguments.config,
        nPop,
        maxFes
      );
      printBaselineComparison(arguments, runSummary, baselineOptions.baselineFile);
      if (baselineOptions.recordBaseline) {
        writeBaselineProperties(arguments, runSummary, baselineOptions.baselineFile);
      }
    } catch (Exception e) {
      System.err.println("===================================================");
      System.err.println("FATAL ERROR: Single parent config execution failed");
      System.err.println("===================================================");
      System.err.println("Error type: " + e.getClass().getSimpleName());
      System.err.println("Error message: " + e.getMessage());
      System.err.println();
      e.printStackTrace();
      System.err.println("===================================================");
      System.exit(1);
    }
  }
}
