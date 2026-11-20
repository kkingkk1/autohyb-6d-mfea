package main;

import common.AlgorithmConstants;
import common.EliteConfigArchive;
import common.TuningConfig;
import common.TuningDefaults;
import model.Satellite;
import model.SchedulingPlan;
import model.Task;
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
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RunSingleHybridConfig {

    static final String INSERTION_MODE_PROPERTY = "hybcec.insertionMode";
    static final String QUIET_PROPERTY = "autohyb.quiet";
    static final String LOG_INTERVAL_PROPERTY = "autohyb.logInterval";
    static final String GENERATION_LOG_ENABLED_PROPERTY = "autohyb.generationLog.enabled";
    static final String GENERATION_LOG_PROPERTY = "autohyb.generationLog";
    static final String DETERMINISTIC_PROPERTY = "autohyb.deterministic";
    private static final int MIN_HYB_THREADS = 2;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final int CONFIG_DIMENSION = AlgorithmConstants.HYBRID_CONFIG_DIMENSION;
    private static final int N_PAR = AlgorithmConstants.HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT;
    private static final int N_OL = AlgorithmConstants.UPDATE_RATIO_CODE_COUNT;
    private static final int N_OO = AlgorithmConstants.OPERATION_ORDER_COUNT;
    private static final int N_SS = AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_COUNT;
    private static final int DEFAULT_NUM_TASKS = 500;
    private static final int DEFAULT_NUM_SATELLITES = 5;
    private static final int DEFAULT_N_RUN = 1;
    private static final Path DEFAULT_BASELINE_FILE =
        Paths.get("outputs", "run-single-baseline.properties");
    private static final int SMALL_TASK_THRESHOLD = 500;
    private static final boolean DEFAULT_ENABLE_DELTA_FAST_REFINE = true;
    private static final boolean DEFAULT_ENABLE_LOCAL_REFINE_HOT_START_BUDGET = true;
    /** Manual direct-run seed-table row for VSCode runs; valid values are 0..19. */
    private static final int MANUAL_PER_RUN_START = 10;
    /** Manual direct-run seed-table source for VSCode runs. */
    //private static final String MANUAL_SEED_TABLE_SOURCE = "native290002";
    private static final String MANUAL_SEED_TABLE_SOURCE = "native290002";
    /** Print fixed seed metadata for direct VSCode runs unless explicitly disabled. */
    private static final boolean MANUAL_RUN_SEED_LOG = true;
    private static final double DEBUG_POPULATION_FACTOR = 2.5;
    /** Current 6D baseline vector; the latest ID1 benchmark anchor has best ratio 99.600145%. */
    private static final int[] DEFAULT_CONFIG = {2, 0, 0, 0, 4, 2};
    private static final int SMALL_TASK_ASSIGNMENT_STRATEGY = 7;
    private static final int LARGE_TASK_ASSIGNMENT_STRATEGY = 8;
    private static final String DEFAULT_DATA_DIR =
        ScenePathResolver.defaultSceneDir("500-5-0-0-24");
        
    private static final String LOCAL_REFINE_HOT_START_BUDGET_PROPERTY = "taskcoord.localRefineHotStartBudget.enabled";
    private static final String DELTA_FAST_REFINE_ENABLED_PROPERTY = "taskcoord.deltaFastRefine.enabled";
    private static final int WARMUP_MAX_FES = 1000;

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

    private static final class ParsedConfig {
        private final int[] config;

        private ParsedConfig(int[] config) {
            this.config = config;
        }
    }

    private static final class BaselineOptions {
        private final boolean recordBaseline;
        private final boolean recordEliteArchive;
        private final Path baselineFile;
        private final String[] remainingArgs;

        private BaselineOptions(
            boolean recordBaseline,
            boolean recordEliteArchive,
            Path baselineFile,
            String[] remainingArgs) {
            this.recordBaseline = recordBaseline;
            this.recordEliteArchive = recordEliteArchive;
            this.baselineFile = baselineFile;
            this.remainingArgs = remainingArgs;
        }
    }

    private static final class ProfitSummary {
        private final double optimizedProfit;

        private ProfitSummary(double optimizedProfit) {
            this.optimizedProfit = optimizedProfit;
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
        int computedDefaultAssignmentStrategy = defaultAssignmentStrategy(numTasks);
        int assignmentStrategy = computedDefaultAssignmentStrategy;
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

    private static ParsedConfig parseConfig(String configCsv) {
        String[] parts = configCsv.split(",");
        if (parts.length != CONFIG_DIMENSION) {
            throw new IllegalArgumentException(
                "configCsv must contain " + CONFIG_DIMENSION
                    + " integers, got: "
                    + configCsv
            );
        }
        int[] rawConfig = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            rawConfig[i] = Integer.parseInt(parts[i].trim());
        }
        return new ParsedConfig(AlgorithmConstants.normalizeHybridConfig(rawConfig));
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
            throw new IllegalArgumentException("maxFes must be positive when provided, got: " + maxFesOverride);
        }
        if (populationFactorOverride != null
            && (!Double.isFinite(populationFactorOverride) || populationFactorOverride <= 0.0)) {
            throw new IllegalArgumentException(
                "populationFactorOverride must be a positive finite value, got: " + populationFactorOverride
            );
        }
        if (config == null || config.length != CONFIG_DIMENSION) {
            throw new IllegalArgumentException("config must contain exactly " + CONFIG_DIMENSION + " integers");
        }
        if (config[0] == config[1]) {
            throw new IllegalArgumentException("config[0] and config[1] must be different parent algorithms");
        }
        validateRange("config[0]", config[0], 0, N_PAR - 1);
        validateRange("config[1]", config[1], 0, N_PAR - 1);
        validateRange("config[2]", config[2], 0, N_OL - 1);
        validateRange("config[3]", config[3], 0, N_OL - 1);
        validateRange("config[4]", config[4], 0, N_OO - 1);
        validateRange("config[5]", config[5], 0, N_SS - 1);
    }

    private static void validateRange(String label, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + " must be in [" + min + ", " + max + "], got: " + value);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp target/classes main.RunSingleHybridConfig [--config <path>] [--quiet] [--log-interval=N] [--warmup-runs=N] [--record-baseline] [--record-elite-archive] [--baseline-file <path>] [enableDeltaFastRefine] [enableLocalRefineHotStartBudget] [dataDir] [numTasks] [numSatellites] [configCsv] [nRun] [nPop] [maxFes] [populationFactor]");
        System.out.println("  --config <path>  Load .properties config file to override hardcoded params (optional, any position)");
        System.out.println("  --record-baseline  Write current result as performance baseline");
        System.out.println("  --record-elite-archive  Append current config into the instance-scoped elite archive");
        System.out.println("  --baseline-file <path>  Override baseline file path (default: outputs/run-single-baseline.properties)");
        System.out.println("  --quiet  Suppress warmup status logs; compact final metrics remain printed");
        System.out.println("  --log-interval=N  Print generation logs every N generations (default: 1)");
        System.out.println("  --warmup-runs=N  Run N short warmup evaluations before measured timing (default: 0)");
        System.out.println("  -Devaluation.perRunStart=N  Select fixed scene seed table index N (default: 0; valid: 0..19)");
        System.out.println("  -Devaluation.seedTable.source=native290002|native270342|native200042|native410000  Select built-in scene seed table (default: native290002)");
        System.out.println("  -Devaluation.seedTable.path=<path>  Override the selected scene seed table path");
        System.out.println("  -Devaluation.runSeedLog=true|false  Print fixed seed source/table and run metadata (default: true for direct runs)");
        System.out.println("VSCode direct run: edit DEFAULT_ENABLE_DELTA_FAST_REFINE / DEFAULT_ENABLE_LOCAL_REFINE_HOT_START_BUDGET / MANUAL_PER_RUN_START / MANUAL_SEED_TABLE_SOURCE / MANUAL_RUN_SEED_LOG in source, then run without args.");
        System.out.println("Example: java -cp target/classes main.RunSingleHybridConfig --config params.properties true true \"" + DEFAULT_DATA_DIR + "\" 200 3 \"0,1,0,1,0,0\" 1 8 40");
        System.out.println("Example record baseline: java -cp target/classes main.RunSingleHybridConfig --record-baseline");
        System.out.println("Example record elite archive: java -cp target/classes main.RunSingleHybridConfig --record-elite-archive");
        System.out.println("Example configCsv: 9,1,0,1,4,0");
        System.out.println("Example populationFactor only: pass empty nPop/maxFes placeholders, e.g. ... 3 \"\" \"\" 2.25");
        System.out.println("If arguments are omitted, built-in defaults are used.");
    }

    /**
     * Extract --config <path> from args, set System Property, return remaining args.
     */
    private static String[] extractConfigArg(String[] args) {
        java.util.List<String> remaining = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                TuningConfig.reload(Paths.get(args[i + 1]));
                i++; // skip path
            } else {
                remaining.add(args[i]);
            }
        }
        return remaining.toArray(new String[0]);
    }

    private static double iterationCoefficient() {
        return TuningConfig.getDouble("iteration.coefficient", TuningDefaults.ITERATION_COEFFICIENT);
    }

    private static BaselineOptions extractBaselineOptions(String[] args) {
        java.util.List<String> remaining = new java.util.ArrayList<>();
        boolean recordBaseline = false;
        boolean recordEliteArchive = false;
        Path baselineFile = DEFAULT_BASELINE_FILE;
        for (int i = 0; i < args.length; i++) {
            if ("--record-baseline".equals(args[i])) {
                recordBaseline = true;
            } else if ("--record-elite-archive".equals(args[i])) {
                recordEliteArchive = true;
            } else if ("--baseline-file".equals(args[i]) && i + 1 < args.length) {
                baselineFile = Paths.get(args[i + 1]);
                i++;
            } else {
                remaining.add(args[i]);
            }
        }
        return new BaselineOptions(
            recordBaseline,
            recordEliteArchive,
            baselineFile,
            remaining.toArray(new String[0])
        );
    }

    private static LoggingOptions extractLoggingOptions(String[] args) {
        java.util.List<String> remaining = new java.util.ArrayList<>();
        boolean quiet = Boolean.getBoolean(QUIET_PROPERTY);
        int logInterval = Integer.getInteger(LOG_INTERVAL_PROPERTY, 1);
        int warmupRuns = 0;
        for (String arg : args) {
            if ("--quiet".equals(arg)) {
                quiet = true;
            } else if (arg.startsWith("--log-interval=")) {
                logInterval = parseLogInterval(arg.substring("--log-interval=".length()));
            } else if (arg.startsWith("--warmup-runs=")) {
                warmupRuns = parseWarmupRuns(arg.substring("--warmup-runs=".length()));
            } else {
                remaining.add(arg);
            }
        }
        System.setProperty(QUIET_PROPERTY, Boolean.toString(quiet));
        System.setProperty(LOG_INTERVAL_PROPERTY, Integer.toString(logInterval));
        return new LoggingOptions(quiet, logInterval, warmupRuns, remaining.toArray(new String[0]));
    }

    private static int parseLogInterval(String rawValue) {
        int value = Integer.parseInt(rawValue.trim());
        if (value <= 0) {
            throw new IllegalArgumentException("--log-interval must be positive, got: " + rawValue);
        }
        return value;
    }

    private static int parseWarmupRuns(String rawValue) {
        int value = Integer.parseInt(rawValue.trim());
        if (value < 0) {
            throw new IllegalArgumentException("--warmup-runs must be non-negative, got: " + rawValue);
        }
        return value;
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

    static InsertionMode resolveInsertionMode() {
        String value = System.getProperty(INSERTION_MODE_PROPERTY, InsertionMode.GREEDY.name()).trim();
        if (value.isEmpty()) {
            return InsertionMode.GREEDY;
        }
        try {
            return InsertionMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid " + INSERTION_MODE_PROPERTY + ": " + value
                    + ". Valid values: " + java.util.Arrays.toString(InsertionMode.values()),
                e
            );
        }
    }

    private static final class LoggingOptions {
        private final boolean quiet;
        private final int logInterval;
        private final int warmupRuns;
        private final String[] remainingArgs;

        private LoggingOptions(boolean quiet, int logInterval, int warmupRuns, String[] remainingArgs) {
            this.quiet = quiet;
            this.logInterval = logInterval;
            this.warmupRuns = warmupRuns;
            this.remainingArgs = remainingArgs;
        }
    }

    private static void runWarmupIfRequested(
        LoggingOptions loggingOptions,
        RunArguments arguments,
        int nPop,
        ExecutorService evaluationExecutor
    ) throws Exception {
        if (loggingOptions.warmupRuns <= 0) {
            return;
        }
        if (!loggingOptions.quiet) {
            System.out.printf("[Warmup] runs=%d, maxFes=%d%n", loggingOptions.warmupRuns, WARMUP_MAX_FES);
        }
        EvaluationHyb.warmup(
            arguments.config,
            CONFIG_DIMENSION,
            Math.max(1, Math.min(nPop, WARMUP_MAX_FES)),
            WARMUP_MAX_FES,
            loggingOptions.warmupRuns,
            arguments.dataDir,
            arguments.numTasks,
            arguments.numSatellites,
            arguments.assignmentStrategy,
            evaluationExecutor
        );
        DeltaFastRefiner.resetAdjacentSwapStats();
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

    private static ProfitSummary buildProfitSummary(
        List<Satellite> allSatellites,
        java.util.Map<Integer, SchedulingPlan> optimizedPlans,
        InsertionMode insertionMode) {
        double optimizedProfit = evaluatePlansWithInsertionMode(allSatellites, optimizedPlans, insertionMode);
        return new ProfitSummary(optimizedProfit);
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

    static Properties createBaselineProperties(
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

    static boolean baselineContextMatches(
        Properties properties,
        String dataDir,
        int numTasks,
        int numSatellites,
        int[] config,
        int nRun,
        int nPop,
        int maxFes) {
        return dataDir.equals(properties.getProperty("dataDir"))
            && String.valueOf(numTasks).equals(properties.getProperty("numTasks"))
            && String.valueOf(numSatellites).equals(properties.getProperty("numSatellites"))
            && configToCsv(config).equals(properties.getProperty("configCsv"))
            && String.valueOf(nRun).equals(properties.getProperty("nRun"))
            && String.valueOf(nPop).equals(properties.getProperty("nPop"))
            && String.valueOf(maxFes).equals(properties.getProperty("maxFes"));
    }

    private static RunSummary buildRunSummary(
        RunArguments arguments,
        EvaluationHyb.EvaluationResult result,
        List<Satellite> allSatellites,
        InsertionMode insertionMode,
        int assignedTaskCount,
        int totalPossibleFitness,
        int nPop,
        int maxFes,
        long elapsedMillis) {
        if (arguments.nRun > 1) {
            return new RunSummary(
                "meanProfit",
                result.meanProfit,
                result.bestRunProfit,
                calculatePercentage(result.meanProfit, totalPossibleFitness),
                elapsedMillis,
                arguments.numTasks,
                assignedTaskCount,
                totalPossibleFitness,
                nPop,
                maxFes
            );
        }

        ProfitSummary profitSummary = buildProfitSummary(
            allSatellites,
            result.bestPlansBySatellite,
            insertionMode
        );
        return new RunSummary(
            "optimizedProfit",
            profitSummary.optimizedProfit,
            profitSummary.optimizedProfit,
            calculatePercentage(profitSummary.optimizedProfit, totalPossibleFitness),
            elapsedMillis,
            arguments.numTasks,
            assignedTaskCount,
            totalPossibleFitness,
            nPop,
            maxFes
        );
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
            properties.store(outputStream, "RunSingleHybridConfig performance baseline");
        }
        System.out.printf("[Baseline] Recorded baseline to %s%n", baselineFile.toAbsolutePath());
    }

    private static void printCompactSummary(RunArguments arguments, RunSummary runSummary) {
        System.out.println("Hybrid config: " + configToCsv(arguments.config));
        System.out.printf("Optimized profit ratio: %.2f%%%n", runSummary.profitRatio);
        System.out.printf("Elapsed: %.2f s%n", runSummary.elapsedMillis / 1000.0);
    }

    static String buildBudgetSummary(
        double populationFactor,
        double iterationCoefficient,
        int maxAssignedTaskCount,
        int autoNPop,
        int autoMaxFes,
        Integer nPopOverride,
        Integer maxFesOverride,
        int nPop,
        int maxFes) {
        String nPopSource = nPopOverride != null ? "override" : "auto";
        String maxFesSource = maxFesOverride != null ? "override" : "auto";
        return String.format(
            "[Budget] populationFactor=%.4f, iterationCoefficient=%.4f, maxAssignedTaskCount=%d, autoNPop=%d, autoMaxFes=%d, effectiveNPop=%d (%s), effectiveMaxFes=%d (%s)",
            populationFactor,
            iterationCoefficient,
            maxAssignedTaskCount,
            autoNPop,
            autoMaxFes,
            nPop,
            nPopSource,
            maxFes,
            maxFesSource
        );
    }

    public static void main(String[] args) {
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printUsage();
            return;
        }

        // --config <path> support: extract config file path from args, remaining args parsed as before
        args = extractConfigArg(args);
        LoggingOptions loggingOptions = extractLoggingOptions(args);
        args = loggingOptions.remainingArgs;
        BaselineOptions baselineOptions = extractBaselineOptions(args);
        args = baselineOptions.remainingArgs;

        ExecutorService hybExecutor = null;
        try {
            RunArguments arguments = parseArgs(args);
            enableDeterministicReplayIfUnset();
            applyManualPerRunStartIfUnset();
            applyManualSeedTableSourceIfUnset();
            applyManualRunSeedLogIfUnset();
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
            List<Satellite> allSatellites = dataSet.satelliteList;

            List<List<Integer>> initialAssignment = arguments.assignmentStrategy >= 0
                ? Initialize.initializeWithStrategy(allTasks, allSatellites, arguments.assignmentStrategy)
                : Initialize.initialize(allTasks, allSatellites);
            AssignTaskOperator.assignTaskToSatellites(initialAssignment, allTasks, allSatellites);
            InsertionMode insertionMode = resolveInsertionMode();
            int assignedTaskCount = countAssignedTasks(allSatellites);
            int totalPossibleFitness = calculateTotalTaskPriority(allTasks);

            int maxAssignedTaskCount = allSatellites.stream()
                .mapToInt(s -> s.getAssignedTasks().size())
                .max()
                .orElse(0);
            double populationFactor = arguments.populationFactorOverride != null
                ? arguments.populationFactorOverride
                : DEBUG_POPULATION_FACTOR;
            int autoNPop = Math.max(1, (int) Math.round(populationFactor * Math.max(1, maxAssignedTaskCount)));
            double iterationCoefficient = iterationCoefficient();
            int autoMaxFes = (int) Math.round(populationFactor * Math.max(1, maxAssignedTaskCount) * iterationCoefficient);
            int nPop = arguments.nPopOverride != null ? arguments.nPopOverride : autoNPop;
            int maxFes = arguments.maxFesOverride != null ? arguments.maxFesOverride : autoMaxFes;

            int totalCores = Runtime.getRuntime().availableProcessors();
            int hybThreads = ParallelPolicy.hybThreadsForSingleRun(totalCores, MIN_HYB_THREADS);
            if (System.getProperty(TaskAllocationCoordinator.SATELLITE_POOL_SIZE_PROPERTY) != null) {
                int threadBudget = Math.max(1, (int) Math.floor(totalCores * 1.5));
                int maxHybThreads = Math.max(MIN_HYB_THREADS, threadBudget - 1);
                hybThreads = Math.min(hybThreads, maxHybThreads);
            }
            hybExecutor = Executors.newFixedThreadPool(hybThreads);
            final ExecutorService evaluationExecutor = hybExecutor;

            runWarmupIfRequested(loggingOptions, arguments, nPop, evaluationExecutor);
            DeltaFastRefiner.resetAdjacentSwapStats();
            long evaluationStartNanos = System.nanoTime();
            EvaluationHyb.EvaluationResult result = EvaluationHyb.evaluationHyb(
                arguments.config,
                CONFIG_DIMENSION,
                nPop,
                maxFes,
                arguments.nRun,
                allSatellites,
                allTasks,
                arguments.dataDir,
                evaluationExecutor
            );
            EliteConfigArchive.ArchiveContext archiveContext = new EliteConfigArchive.ArchiveContext(
                arguments.dataDir,
                arguments.numTasks,
                arguments.numSatellites,
                populationFactor,
                maxFes,
                insertionMode.name()
            );

            long totalElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - evaluationStartNanos);
            RunSummary runSummary = buildRunSummary(
                arguments,
                result,
                allSatellites,
                insertionMode,
                assignedTaskCount,
                totalPossibleFitness,
                nPop,
                maxFes,
                totalElapsedMillis
            );

            printCompactSummary(arguments, runSummary);

            if (baselineOptions.recordEliteArchive) {
                EliteConfigArchive.saveCandidateConfig(archiveContext, arguments.config);
            }

            if (baselineOptions.recordBaseline) {
                printBaselineComparison(arguments, runSummary, baselineOptions.baselineFile);
            }
            if (baselineOptions.recordBaseline) {
                writeBaselineProperties(arguments, runSummary, baselineOptions.baselineFile);
            }
        } catch (Exception e) {
            System.err.println("===================================================");
            System.err.println("FATAL ERROR: Single hybrid config execution failed");
            System.err.println("===================================================");
            System.err.println("Error type: " + e.getClass().getSimpleName());
            System.err.println("Error message: " + e.getMessage());
            System.err.println("\nStack trace:");
            e.printStackTrace();
            System.err.println("===================================================");
            System.exit(1);
        } finally {
            shutdownExecutor(hybExecutor, "hybExecutor");
        }
    }

    private static void enableDeterministicReplayIfUnset() {
        if (System.getProperty(DETERMINISTIC_PROPERTY) == null) {
            System.setProperty(DETERMINISTIC_PROPERTY, Boolean.TRUE.toString());
        }
        if (Boolean.getBoolean(DETERMINISTIC_PROPERTY)) {
            common.RandomProvider.reset();
            common.RandomProvider.resetThreadCounter();
        }
    }

    static void applyManualPerRunStartIfUnset() {
        if (System.getProperty(EvaluationHyb.PER_RUN_START_PROPERTY) == null) {
            System.setProperty(
                EvaluationHyb.PER_RUN_START_PROPERTY,
                Integer.toString(MANUAL_PER_RUN_START)
            );
        }
    }

    static void applyManualSeedTableSourceIfUnset() {
        if (System.getProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY) == null) {
            System.setProperty(
                SceneSeedTable.TABLE_SOURCE_PROPERTY,
                MANUAL_SEED_TABLE_SOURCE
            );
        }
    }

    static void applyManualRunSeedLogIfUnset() {
        if (System.getProperty(EvaluationHyb.RUN_SEED_LOG_PROPERTY) == null) {
            System.setProperty(
                EvaluationHyb.RUN_SEED_LOG_PROPERTY,
                Boolean.toString(MANUAL_RUN_SEED_LOG)
            );
        }
    }

}
