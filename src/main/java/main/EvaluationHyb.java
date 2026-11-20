package main;

import common.AlgorithmConstants;
import common.RandomProvider;
import common.TuningConfig;
import common.TuningDefaults;
import model.Satellite;
import model.SchedulingPlan;
import model.Task;
import utils.PlanUtils;
import utils.AssignTaskOperator;
import utils.DataLoader;
import utils.Initialize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;

public class EvaluationHyb {

    private static final String SCENE_DIR_PROPERTY = "evaluation.sceneDir";
    static final String RUN_INDEX_BASE_PROPERTY = "evaluation.runIndexBase";
    static final String RUN_SEED_BASE_PROPERTY = "evaluation.runSeedBase";
    static final String RUN_SEED_LOG_PROPERTY = "evaluation.runSeedLog";
    static final String PER_RUN_START_PROPERTY = "evaluation.perRunStart";
    static final long RUN_SEED_OFFSET = 8L;

    /**
     * A1 switch: re-evaluate the final best solution using optimal insertion mode.
     * Optimal mode tries all feasible insertion points, picks the one with minimum transition time, can schedule more tasks.
     */
    static boolean ENABLE_OPTIMAL_REEVAL = false;
    /**
     * Maximum time to wait for each run to complete before timing out.
     */
    private static final long RUN_TIMEOUT_MINUTES = 30;
    private static final int DEFAULT_MAX_REASSIGN_ITERATIONS = TuningConfig.getInt("eval.maxReassignIterations", TuningDefaults.EVAL_MAX_REASSIGN_ITERATIONS);
    private static final int DEFAULT_KEY_PERTURB_THRESHOLD = TuningConfig.getInt("eval.keyPerturbThreshold", TuningDefaults.EVAL_KEY_PERTURB_THRESHOLD);
    private static final double DEFAULT_KEY_PERTURB_STRENGTH = TuningConfig.getDouble("eval.keyPerturbStrength", TuningDefaults.EVAL_KEY_PERTURB_STRENGTH);

    private static void printRunStage(String message) {
        // Silent: suppress intermediate logs in tuning mode
    }

    private static long resolveRunSeed(String sceneDir, int perRun) {
        long runSeed = SceneSeedTable.resolve(sceneDir, perRun);
        if (Boolean.getBoolean(RUN_SEED_LOG_PROPERTY)) {
            System.out.printf(
                "[RunSeed] source=%s table=%s scene=%s perRun=%d seedId=%d seed=%d%n",
                SceneSeedTable.selectedSource(),
                SceneSeedTable.selectedTableName(),
                SceneSeedTable.normalizeSceneKey(sceneDir),
                perRun,
                SceneSeedTable.seedIdForPerRun(perRun),
                runSeed
            );
        }
        return runSeed;
    }

    private static int resolvePerRunStart() {
        String raw = System.getProperty(PER_RUN_START_PROPERTY, "0").trim();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                PER_RUN_START_PROPERTY + " must be an integer in [0,"
                    + (SceneSeedTable.SEED_COUNT - 1) + "], got: " + raw,
                e
            );
        }
    }

    static long resolveConfiguredRunSeed(long defaultSeedBase, long runIndex) {
        String configuredSeedBase = System.getProperty(RUN_SEED_BASE_PROPERTY);
        long seedBase = defaultSeedBase;
        if (configuredSeedBase != null) {
            try {
                seedBase = Long.parseLong(configuredSeedBase.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    RUN_SEED_BASE_PROPERTY + " must be a long, got: " + configuredSeedBase,
                    e
                );
            }
        }
        try {
            return Math.addExact(seedBase, runIndex);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                "Run seed overflow: seedBase=" + seedBase + ", runIndex=" + runIndex,
                e
            );
        }
    }

    public static class RunMetrics {
        public final int runIndex;
        public final double totalProfit;
        public final int scheduledTaskCount;
        public final int assignedTaskCount;
        public final long elapsedMillis;
        public final List<String> strategyHistory;
        public final List<Integer> unscheduledTaskCountHistory;
        public final List<Integer> migrationCountHistory;
        public final List<Double> totalProfitHistory;

        public RunMetrics(
            int runIndex,
            double totalProfit,
            int scheduledTaskCount,
            int assignedTaskCount,
            long elapsedMillis,
            List<String> strategyHistory,
            List<Integer> unscheduledTaskCountHistory,
            List<Integer> migrationCountHistory,
            List<Double> totalProfitHistory) {
            this.runIndex = runIndex;
            this.totalProfit = totalProfit;
            this.scheduledTaskCount = scheduledTaskCount;
            this.assignedTaskCount = assignedTaskCount;
            this.elapsedMillis = elapsedMillis;
            this.strategyHistory = strategyHistory;
            this.unscheduledTaskCountHistory = unscheduledTaskCountHistory;
            this.migrationCountHistory = migrationCountHistory;
            this.totalProfitHistory = totalProfitHistory;
        }
    }

    static class PerRunResult {
        final int runIndex;
        final double totalProfit;
        final long elapsedMillis;
        final java.util.Map<Integer, SchedulingPlan> plansBySatellite;
        final java.util.Map<Integer, Integer> assignmentSnapshot;
        final RunMetrics metrics;

        private PerRunResult(
            int runIndex,
            double totalProfit,
            long elapsedMillis,
            java.util.Map<Integer, SchedulingPlan> plansBySatellite,
            java.util.Map<Integer, Integer> assignmentSnapshot,
            RunMetrics metrics) {
            this.runIndex = runIndex;
            this.totalProfit = totalProfit;
            this.elapsedMillis = elapsedMillis;
            this.plansBySatellite = plansBySatellite;
            this.assignmentSnapshot = assignmentSnapshot;
            this.metrics = metrics;
        }
    }

    // ==========================================================
    // Result class
    // ==========================================================

    public static class EvaluationResult {
        public final double meanProfit;
        public final double[] runProfits;
        public final double bestRunProfit;
        public final int bestRunIndex;
        public final java.util.Map<Integer, SchedulingPlan> bestPlansBySatellite;
        public final java.util.Map<Integer, Integer> bestTaskAssignment;
        public final java.util.List<RunMetrics> runMetrics;
        public final double[][] gbestSolution;
        public final double[] gbestProfit;
        public final java.util.List<java.util.List<Double>> bestFitRecord;
        public final int[] genUsed;

        public EvaluationResult(
            double meanProfit,
            double[] runProfits,
            double bestRunProfit,
            int bestRunIndex,
            java.util.Map<Integer, SchedulingPlan> bestPlansBySatellite,
            java.util.Map<Integer, Integer> bestTaskAssignment,
            java.util.List<RunMetrics> runMetrics,
            double[][] gbestSolution,
            double[] gbestProfit,
            java.util.List<java.util.List<Double>> bestFitRecord,
            int[] genUsed) {
            this.meanProfit = meanProfit;
            this.runProfits = runProfits;
            this.bestRunProfit = bestRunProfit;
            this.bestRunIndex = bestRunIndex;
            this.bestPlansBySatellite = bestPlansBySatellite;
            this.bestTaskAssignment = bestTaskAssignment;
            this.runMetrics = runMetrics;
            this.gbestSolution = gbestSolution;
            this.gbestProfit = gbestProfit;
            this.bestFitRecord = bestFitRecord;
            this.genUsed = genUsed;
        }
    }

    // ==========================================================
    // Main evaluation method
    // ==========================================================

    /**
     * Evaluates a hybrid algorithm configuration across multiple independent runs.
     *
     * @param x Encoded 6D hybrid configuration array
     * @param d Dimension parameter, retained for caller compatibility
     * @param nPop Initial population size for HybCec
     * @param maxFes Maximum number of evaluations for HybCec
     * @param nRun Number of independent runs
     * @param allSatellites All satellites participating in the global evaluation
     * @param allTasks List of all tasks to be scheduled
     * @deprecated The scene is required by the fixed seed contract. Use the
     *             overload that includes {@code sceneDir}, or set
     *             {@code -Devaluation.sceneDir=<scene-directory>} for legacy
     *             callers.
     * @param executor Thread pool executor for parallel execution
     * @return Global evaluation result containing run profits and the best multi-satellite plans
     * @throws Exception if any run fails or executor issues occur
     */
    @Deprecated
    public static EvaluationResult evaluationHyb(
        int[] x,
        int d,
        int nPop,
        int maxFes,
        int nRun,
        List<Satellite> allSatellites,
        List<Task> allTasks,
        ExecutorService executor) throws Exception {
        String sceneDir = System.getProperty(SCENE_DIR_PROPERTY);
        if (sceneDir == null || sceneDir.isBlank()) {
            throw new IllegalArgumentException(
                "sceneDir is required; use the evaluationHyb overload with sceneDir "
                    + "or set -D" + SCENE_DIR_PROPERTY + "=<scene-directory>"
            );
        }
        return evaluationHybInternal(
            x,
            d,
            nPop,
            maxFes,
            nRun,
            allSatellites,
            allTasks,
            sceneDir,
            executor
        );
    }

    public static EvaluationResult evaluationHyb(
        int[] x,
        int d,
        int nPop,
        int maxFes,
        int nRun,
        List<Satellite> allSatellites,
        List<Task> allTasks,
        String sceneDir,
        ExecutorService executor) throws Exception {
        return evaluationHybInternal(
            x,
            d,
            nPop,
            maxFes,
            nRun,
            allSatellites,
            allTasks,
            sceneDir,
            executor
        );
    }

    public static void warmup(
        int[] x,
        int d,
        int nPop,
        int maxFes,
        int warmupRuns,
        String dataDir,
        int numTasks,
        int numSatellites,
        int assignmentStrategy,
        ExecutorService executor) throws Exception {
        if (warmupRuns <= 0) {
            return;
        }
        DataLoader.DataSet dataSet = DataLoader.loadAllData(dataDir, numTasks, numSatellites);
        List<Task> tasks = dataSet.taskList;
        List<Satellite> satellites = dataSet.satelliteList;
        List<List<Integer>> initialAssignment = assignmentStrategy >= 0
            ? Initialize.initializeWithStrategy(tasks, satellites, assignmentStrategy)
            : Initialize.initialize(tasks, satellites);
        AssignTaskOperator.assignTaskToSatellites(initialAssignment, tasks, satellites);
        evaluationHybInternal(
            x,
            d,
            nPop,
            maxFes,
            warmupRuns,
            satellites,
            tasks,
            dataDir,
            executor
        );
    }

    private static EvaluationResult evaluationHybInternal(
        int[] x,
        int d,
        int nPop,
        int maxFes,
        int nRun,
        List<Satellite> allSatellites,
        List<Task> allTasks,
        String sceneDir,
        ExecutorService executor) throws Exception {
        if (sceneDir == null || sceneDir.isBlank()) {
            throw new IllegalArgumentException(
                "sceneDir is required for fixed scene/perRun seed resolution"
            );
        }
        int perRunStart = resolvePerRunStart();
        if (perRunStart < 0 || perRunStart >= SceneSeedTable.SEED_COUNT) {
            throw new IllegalArgumentException(
                PER_RUN_START_PROPERTY + " must be in [0," + (SceneSeedTable.SEED_COUNT - 1)
                    + "], got: " + perRunStart
            );
        }
        if (nRun <= 0 || nRun > SceneSeedTable.SEED_COUNT - perRunStart) {
            throw new IllegalArgumentException(
                "nRun must be in [1," + (SceneSeedTable.SEED_COUNT - perRunStart)
                    + "] for perRunStart=" + perRunStart + ", got: " + nRun
            );
        }
        final int[] config = AlgorithmConstants.normalizeHybridConfig(x);
        double randOL = AlgorithmConstants.HYBCEC_RAND_OL;
        int maxGen = Math.max(1, maxFes - nPop);
        int configDimension = config.length;

        int[] parentsIndex = {config[0], config[1]};
        int[] oL = {config[2], config[3]};
        int oO = config[4];
        int strSurvive = config[5];

        operator.insertion.InsertionMode insertionMode = RunSingleHybridConfig.resolveInsertionMode();

        int[] genUsed = new int[nRun];
        double[][] gbestSolution = new double[nRun][configDimension];
        double[] runProfits = new double[nRun];
        java.util.Arrays.fill(runProfits, Double.NEGATIVE_INFINITY);
        java.util.List<java.util.List<Double>> bestFitRecord = new ArrayList<>(nRun);
        for (int i = 0; i < nRun; i++) {
            bestFitRecord.add(new ArrayList<>(maxGen));
            for (int j = 0; j < configDimension; j++) {
                gbestSolution[i][j] = config[j];
            }
        }

        if (executor == null) {
            throw new IllegalArgumentException(
                "Executor cannot be null. Please provide a valid ExecutorService for parallel execution.");
        }
        if (allSatellites == null || allSatellites.isEmpty()) {
            throw new IllegalArgumentException("allSatellites cannot be null or empty");
        }
        if (allTasks == null) {
            throw new IllegalArgumentException("allTasks cannot be null");
        }

        java.util.Map<Integer, Integer> initialAssignment = buildInitialAssignment(allSatellites);

        java.util.List<Future<PerRunResult>> futures = new ArrayList<>();

        for (int runOffset = 0; runOffset < nRun; runOffset++) {
            final int perRun = perRunStart + runOffset;
            final int runIndex = perRun;
            Callable<PerRunResult> task = () -> {
                long runStartNanos = System.nanoTime();
                try {
                    long runSeed = resolveRunSeed(sceneDir, perRun);
                    Random rand = RandomProvider.newWithSeed(runSeed);
                    int oLRand = (rand.nextDouble() < randOL) ? oL[0] : oL[1];

                    java.util.List<Satellite> runSatellites = copySatellites(allSatellites);
                    TaskAllocationCoordinator coordinator = new TaskAllocationCoordinator(
                        allTasks,
                        runSatellites,
                        new operator.assignment.MaxWindowCountStrategy(),
                        DEFAULT_MAX_REASSIGN_ITERATIONS,
                        DEFAULT_KEY_PERTURB_THRESHOLD,
                        DEFAULT_KEY_PERTURB_STRENGTH
                    );

                    TaskAllocationCoordinator.HybCecConfig hybCecConfig = new TaskAllocationCoordinator.HybCecConfig(
                        parentsIndex,
                        oL,
                        oLRand,
                        oO,
                        strSurvive,
                        nPop,
                        maxFes,
                        insertionMode,
                        runSeed
                    );

                    java.util.Map<Integer, SchedulingPlan> optimizedPlans = coordinator.scheduleWithReassignment(
                        hybCecConfig,
                        runSatellites,
                        initialAssignment.isEmpty() ? null : new java.util.HashMap<>(initialAssignment)
                    );

                    double optimizedProfit = ENABLE_OPTIMAL_REEVAL
                        ? reevaluateTotalProfit(optimizedPlans, runSatellites)
                        : sumPlanFitness(optimizedPlans);
                    int optimizedScheduledTaskCount = countScheduledTasks(optimizedPlans, allTasks);
                    java.util.Map<Integer, Integer> optimizedAssignmentSnapshot = coordinator.getBestTaskAssignmentSnapshot();
                    if (optimizedAssignmentSnapshot.isEmpty()) {
                        optimizedAssignmentSnapshot = coordinator.getTaskAssignmentSnapshot();
                    }

                    PerRunResult result = buildOptimizedPerRunResult(
                        runIndex,
                        initialAssignment,
                        optimizedProfit,
                        optimizedScheduledTaskCount,
                        optimizedPlans,
                        optimizedAssignmentSnapshot,
                        coordinator.getStrategyHistorySnapshot(),
                        coordinator.getUnscheduledTaskCountHistorySnapshot(),
                        coordinator.getMigrationCountHistorySnapshot(),
                        coordinator.getTotalProfitHistorySnapshot(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - runStartNanos)
                    );
                    printRunStage(String.format(
                        "run=%d done, elapsed=%d ms, fitness=%.6f, scheduled=%d, assigned=%d",
                        runIndex,
                        result.elapsedMillis,
                        result.totalProfit,
                        result.metrics.scheduledTaskCount,
                        initialAssignment.size()));

                    return result;

                } catch (Exception e) {
                    System.err.printf("Run %d failed with exception: %s%n", runIndex + 1, e.getMessage());
                    e.printStackTrace();
                    throw new RuntimeException("Run " + (runIndex + 1) + " failed during execution", e);
                }
            };

            try {
                futures.add(executor.submit(task));
            } catch (RejectedExecutionException e) {
                System.err.printf("Run %d rejected by executor: %s%n", runIndex + 1, e.getMessage());
                throw new RuntimeException(
                    "Failed to submit run " + (runIndex + 1)
                        + " to executor. Executor may be shutdown or thread pool is full.", e);
            }
        }

        java.util.List<RunMetrics> runMetrics = new ArrayList<>(java.util.Collections.nCopies(nRun, (RunMetrics) null));
        java.util.Map<Integer, SchedulingPlan> bestPlansBySatellite = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, Integer> bestTaskAssignment = new java.util.HashMap<>();
        double bestRunProfit = Double.NEGATIVE_INFINITY;
        int bestRunIndex = -1;

        for (int i = 0; i < nRun; i++) {
            Future<PerRunResult> future = futures.get(i);
            try {
                PerRunResult result = future.get(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                runProfits[i] = result.totalProfit;
                genUsed[i] = 0;
                bestFitRecord.get(i).add(result.totalProfit);
                runMetrics.set(i, result.metrics);

                if (result.totalProfit > bestRunProfit) {
                    bestRunProfit = result.totalProfit;
                    bestRunIndex = result.runIndex;
                    bestPlansBySatellite = copyPlans(result.plansBySatellite);
                    bestTaskAssignment = new java.util.HashMap<>(result.assignmentSnapshot);
                }
            } catch (TimeoutException e) {
                System.err.printf("Run %d timed out after %d minutes, cancelling task%n", i + 1, RUN_TIMEOUT_MINUTES);
                future.cancel(true);
                e.printStackTrace();
                throw new RuntimeException(
                    "Run " + (i + 1) + " timed out after " + RUN_TIMEOUT_MINUTES + " minutes", e);
            } catch (Exception e) {
                System.err.printf("Run %d failed: %s%n", i + 1, e.getMessage());
                future.cancel(true);
                e.printStackTrace();
                throw new RuntimeException("Run " + (i + 1) + " failed", e);
            }
        }

        double meanProfit = 0.0;
        int validRuns = 0;
        for (double profit : runProfits) {
            if (!Double.isInfinite(profit) && profit > Double.NEGATIVE_INFINITY) {
                meanProfit += profit;
                validRuns++;
            }
        }
        if (validRuns > 0) {
            meanProfit /= validRuns;
        } else {
            meanProfit = Double.NEGATIVE_INFINITY;
        }

        return new EvaluationResult(
            meanProfit,
            runProfits,
            bestRunProfit,
            bestRunIndex,
            bestPlansBySatellite,
            bestTaskAssignment,
            runMetrics,
            gbestSolution,
            runProfits,
            bestFitRecord,
            genUsed
        );
    }

    static PerRunResult buildOptimizedPerRunResult(
        int runIndex,
        java.util.Map<Integer, Integer> initialAssignmentSnapshot,
        double optimizedProfit,
        int optimizedScheduledTaskCount,
        java.util.Map<Integer, SchedulingPlan> optimizedPlans,
        java.util.Map<Integer, Integer> optimizedAssignmentSnapshot,
        java.util.List<String> strategyHistory,
        java.util.List<Integer> unscheduledTaskCountHistory,
        java.util.List<Integer> migrationCountHistory,
        java.util.List<Double> totalProfitHistory,
        long elapsedMillis) {
        double finalProfit = optimizedProfit;
        int finalScheduledTaskCount = optimizedScheduledTaskCount;
        java.util.Map<Integer, SchedulingPlan> finalPlans = copyPlans(optimizedPlans);
        java.util.Map<Integer, Integer> finalAssignment = new java.util.HashMap<>(optimizedAssignmentSnapshot);
        RunMetrics metrics = new RunMetrics(
            runIndex,
            finalProfit,
            finalScheduledTaskCount,
            initialAssignmentSnapshot.size(),
            elapsedMillis,
            strategyHistory,
            unscheduledTaskCountHistory,
            migrationCountHistory,
            totalProfitHistory
        );
        return new PerRunResult(
            runIndex,
            finalProfit,
            elapsedMillis,
            finalPlans,
            finalAssignment,
            metrics
        );
    }

    private static java.util.Map<Integer, Integer> buildInitialAssignment(List<Satellite> allSatellites) {
        java.util.Map<Integer, Integer> initialAssignment = new java.util.HashMap<>();
        for (Satellite satellite : allSatellites) {
            for (Task task : satellite.getAssignedTasks()) {
                initialAssignment.put(task.getId(), satellite.getId());
            }
        }
        return initialAssignment;
    }

    private static java.util.List<Satellite> copySatellites(List<Satellite> sourceSatellites) {
        java.util.List<Satellite> copiedSatellites = new ArrayList<>(sourceSatellites.size());
        for (Satellite satellite : sourceSatellites) {
            copiedSatellites.add(copySatellite(satellite));
        }
        return copiedSatellites;
    }

    private static Satellite copySatellite(Satellite source) {
        Satellite satellite = new Satellite(source.getId());
        satellite.setTimeWindows(source.getTimeWindows());
        satellite.setAssignedTasks(source.getAssignedTasks());
        return satellite;
    }

    private static java.util.Map<Integer, SchedulingPlan> copyPlans(java.util.Map<Integer, SchedulingPlan> plans) {
        java.util.Map<Integer, SchedulingPlan> copiedPlans = new java.util.LinkedHashMap<>();
        java.util.List<Integer> satelliteIds = new ArrayList<>(plans.keySet());
        java.util.Collections.sort(satelliteIds);
        for (Integer satelliteId : satelliteIds) {
            copiedPlans.put(satelliteId, plans.get(satelliteId).copy());
        }
        return copiedPlans;
    }

    static double sumPlanFitness(java.util.Map<Integer, SchedulingPlan> plans) {
        double totalProfit = 0.0;
        for (SchedulingPlan plan : plans.values()) {
            totalProfit += plan.isFitnessCalculated() ? plan.getFitness() : plan.getTotalProfit();
        }
        return totalProfit;
    }

    static boolean isEvaluationConsistent(EvaluationResult result) {
        if (result == null || result.bestPlansBySatellite == null) {
            return false;
        }
        return Math.abs(result.bestRunProfit - sumPlanFitness(result.bestPlansBySatellite)) <= 1e-9;
    }

    private static int countScheduledTasks(java.util.Map<Integer, SchedulingPlan> plans, java.util.List<Task> allTasks) {
        if (allTasks == null || allTasks.isEmpty()) {
            return 0;
        }
        Set<Integer> validTaskIds = new LinkedHashSet<>();
        for (Task task : allTasks) {
            if (task != null) {
                validTaskIds.add(task.getId());
            }
        }
        Set<Integer> scheduledTaskIds = new LinkedHashSet<>();
        for (SchedulingPlan plan : plans.values()) {
            if (plan == null || plan.getSchedule() == null) {
                continue;
            }
            for (model.ScheduledTask scheduledTask : plan.getSchedule()) {
                if (scheduledTask == null || scheduledTask.getTask() == null) {
                    continue;
                }
                int taskId = scheduledTask.getTask().getId();
                if (validTaskIds.contains(taskId)) {
                    scheduledTaskIds.add(taskId);
                }
            }
        }
        return scheduledTaskIds.size();
    }

    private static double reevaluateTotalProfit(
        java.util.Map<Integer, SchedulingPlan> plans,
        java.util.List<Satellite> satellites) {
        java.util.Map<Integer, Satellite> satelliteMap = new java.util.HashMap<>();
        for (Satellite satellite : satellites) {
            satelliteMap.put(satellite.getId(), satellite);
        }
        double totalProfit = 0.0;
        for (java.util.Map.Entry<Integer, SchedulingPlan> entry : plans.entrySet()) {
            Satellite satellite = satelliteMap.get(entry.getKey());
            if (satellite == null) {
                continue;
            }
            totalProfit += PlanUtils.evaluatePlan(
                entry.getValue(),
                satellite,
                operator.insertion.InsertionMode.fromUseOptimal(true)
            );
        }
        return totalProfit;
    }
}
