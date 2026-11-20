package core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import algorithm.alns.ALNS;
import common.AlgorithmConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import model.AttitudeRecord;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;
import utils.PlanUtils;

class HybCecAlnsRegressionTest {

  @Test
  void alnsParentAlgorithmInitializesAndRunsThroughHybCec() throws Exception {
    HybCec hybCec = createHybCec();
    hybCec.setAllTasks(createTasks(3));
    hybCec.setEnableGenerationLog(false);

    assertNotNull(hybCec.getAlnsConfig());

    HybCec.HybResult result = hybCec.run();

    assertNotNull(result.getGbestSolution());
    assertEquals(3, result.getGbestSolution().length);
    assertTrue(Double.isFinite(result.getGbestFit()));
  }

  @Test
  void alnsRunPreservesCompleteTaskPermutationEncoding() {
    List<Task> tasks = createTasks(5);
    List<double[]> population = List.of(
      new double[] {0.1, 0.5, 0.2, 0.4, 0.3},
      new double[] {0.3, 0.2, 0.5, 0.1, 0.4}
    );
    List<Double> fitness = List.of(10.0, 8.0);

    List<double[]> updated = ALNS.run(
      population,
      new int[] {0},
      fitness,
      ALNS.ALNSConfig.defaultConfig(),
      null,
      tasks,
      new Random(7L),
      candidatePopulation -> {
        List<Double> scores = new ArrayList<>(candidatePopulation.size());
        for (double[] candidate : candidatePopulation) {
          SchedulingPlan plan = PlanUtils.decode(candidate, tasks);
          double score = 0.0;
          int rankWeight = tasks.size();
          for (Task task : plan.getTaskSequence()) {
            score += task.getPriority() * rankWeight--;
          }
          scores.add(score);
        }
        return scores;
      }
    );

    assertEquals(1, updated.size());
    SchedulingPlan decoded = PlanUtils.decode(updated.get(0), tasks);
    assertEquals(tasks.size(), decoded.getTaskSequence().size());
    assertEquals(
      tasks.size(),
      decoded.getTaskSequence().stream().map(Task::getId).distinct().count()
    );
  }

  @Test
  void alnsRunWithResultReturnsAlreadyEvaluatedFitness() {
    List<Task> tasks = createTasks(5);
    List<double[]> population = List.of(
      new double[] {0.1, 0.5, 0.2, 0.4, 0.3},
      new double[] {0.3, 0.2, 0.5, 0.1, 0.4}
    );
    List<Double> fitness = List.of(10.0, 8.0);

    ALNS.ALNSResult result = ALNS.runWithResult(
      population,
      new int[] {0},
      fitness,
      ALNS.ALNSConfig.defaultConfig(),
      null,
      tasks,
      new Random(7L),
      candidatePopulation -> {
        List<Double> scores = new ArrayList<>(candidatePopulation.size());
        for (double[] candidate : candidatePopulation) {
          SchedulingPlan plan = PlanUtils.decode(candidate, tasks);
          double score = 0.0;
          int rankWeight = tasks.size();
          for (Task task : plan.getTaskSequence()) {
            score += task.getPriority() * rankWeight--;
          }
          scores.add(score);
        }
        return scores;
      },
      null,
      null,
      ALNS.createDestroyOperators(),
      ALNS.createRepairOperators(ALNS.ALNSConfig.defaultConfig())
    );

    assertEquals(1, result.updatedPopulation().size());
    assertEquals(1, result.evaluatedFitness().size());
    double recomputed = scoreChromosome(result.updatedPopulation().get(0), tasks);
    assertEquals(recomputed, result.evaluatedFitness().get(0));
  }

  @Test
  void alnsRunWithResultReportsActualEvaluationCount() {
    List<Task> tasks = createTasks(5);
    List<double[]> population = List.of(new double[] {0.1, 0.5, 0.2, 0.4, 0.3});
    List<Double> fitness = List.of(10.0);
    AtomicInteger calls = new AtomicInteger();

    ALNS.ALNSResult result = ALNS.runWithResult(
      population,
      new int[] {0},
      fitness,
      ALNS.ALNSConfig.defaultConfig(),
      null,
      tasks,
      new Random(7L),
      candidatePopulation -> {
        calls.addAndGet(candidatePopulation.size());
        List<Double> scores = new ArrayList<>(candidatePopulation.size());
        for (double[] candidate : candidatePopulation) {
          scores.add(scoreChromosome(candidate, tasks));
        }
        return scores;
      },
      null,
      null,
      ALNS.createDestroyOperators(),
      ALNS.createRepairOperators(ALNS.ALNSConfig.defaultConfig())
    );

    assertEquals(calls.get(), result.evaluationCount());
  }

  @Test
  void alnsRunWithResultPrefersSequenceEvaluatorWhenAvailable() {
    List<Task> tasks = createTasks(5);
    List<double[]> population = List.of(new double[] {0.1, 0.5, 0.2, 0.4, 0.3});
    AtomicInteger sequenceCalls = new AtomicInteger();
    AtomicInteger chromosomeCalls = new AtomicInteger();

    ALNS.ALNSResult result = ALNS.runWithResult(
      population,
      new int[] {0},
      List.of(10.0),
      ALNS.ALNSConfig.defaultConfig(),
      null,
      tasks,
      new Random(7L),
      null,
      chromosome -> {
        chromosomeCalls.incrementAndGet();
        return scoreChromosome(chromosome, tasks);
      },
      sequence -> {
        sequenceCalls.incrementAndGet();
        return scoreSequence(sequence, tasks.size());
      },
      ALNS.createDestroyOperators(),
      ALNS.createRepairOperators(ALNS.ALNSConfig.defaultConfig())
    );

    assertTrue(sequenceCalls.get() > 0);
    assertEquals(0, chromosomeCalls.get());
    assertEquals(sequenceCalls.get(), result.evaluationCount());
  }

  @Test
  void evaluateTaskSequenceMatchesPlanEvaluation() {
    List<Task> tasks = createTasks(5);
    SchedulingPlan plan = new SchedulingPlan(tasks);
    double direct = PlanUtils.evaluateTaskSequence(tasks, nullSafeSatellite(), InsertionMode.GREEDY);
    double viaPlan = PlanUtils.evaluatePlan(plan, nullSafeSatellite(), InsertionMode.GREEDY);

    assertEquals(viaPlan, direct);
  }

  @Test
  void evaluateTaskSequenceMatchesPlanEvaluationWithScheduledWindows() {
    List<Task> tasks = createWindowedTasks(5);
    SchedulingPlan plan = new SchedulingPlan(tasks);
    double direct = PlanUtils.evaluateTaskSequence(tasks, nullSafeSatellite(), InsertionMode.GREEDY);
    double viaPlan = PlanUtils.evaluatePlan(plan, nullSafeSatellite(), InsertionMode.GREEDY);

    assertEquals(viaPlan, direct);
    assertTrue(direct > 0.0);
  }

  @Test
  void evaluateUniqueTaskSequenceMatchesPlanEvaluationWithScheduledWindows() {
    List<Task> tasks = createWindowedTasks(5);
    SchedulingPlan plan = new SchedulingPlan(tasks);
    double direct = PlanUtils.evaluateUniqueTaskSequence(tasks, nullSafeSatellite(), InsertionMode.GREEDY, null);
    double viaPlan = PlanUtils.evaluatePlan(plan, nullSafeSatellite(), InsertionMode.GREEDY);

    assertEquals(viaPlan, direct);
    assertTrue(direct > 0.0);
  }

  @Test
  void alnsRunWithResultDoesNotCountEvaluationWhenNoReferenceTasks() {
    List<double[]> population = List.of(new double[] {0.1, 0.5, 0.2});
    AtomicInteger calls = new AtomicInteger();

    ALNS.ALNSResult result = ALNS.runWithResult(
      population,
      new int[] {0},
      List.of(10.0),
      ALNS.ALNSConfig.defaultConfig(),
      null,
      List.of(),
      new Random(7L),
      candidatePopulation -> {
        calls.addAndGet(candidatePopulation.size());
        return List.of(0.0);
      },
      null,
      null,
      ALNS.createDestroyOperators(),
      ALNS.createRepairOperators(ALNS.ALNSConfig.defaultConfig())
    );

    assertEquals(0, calls.get());
    assertEquals(0, result.evaluationCount());
    assertEquals(10.0, result.evaluatedFitness().get(0));
  }

  @Test
  void alnsOperatorStatePersistsThroughHybCecStateAcrossStrategyInstances() throws Exception {
    HybCec hybCec = createHybCec();
    hybCec.setAllTasks(createTasks(3));
    hybCec.setEnableGenerationLog(false);

    List<ALNS.OperatorState> destroyOperators = hybCec.getState().getAlnsDestroyOperators(hybCec.getAlnsConfig());
    List<ALNS.OperatorState> repairOperators = hybCec.getState().getAlnsRepairOperators(hybCec.getAlnsConfig());

    hybCec.run();

    assertSame(destroyOperators, hybCec.getState().getAlnsDestroyOperators(hybCec.getAlnsConfig()));
    assertSame(repairOperators, hybCec.getState().getAlnsRepairOperators(hybCec.getAlnsConfig()));
  }

  @Test
  void alnsOperatorStateUsesConfiguredInitialWeights() {
    HybCec hybCec = createHybCec();
    ALNS.ALNSConfig config = new ALNS.ALNSConfig(
      3,
      3,
      1,
      0.15,
      30.0,
      20.0,
      10.0,
      0.5,
      0.032,
      100.0,
      0.99975,
      -AlgorithmConstants.DEFAULT_BOUND,
      AlgorithmConstants.DEFAULT_BOUND,
      InsertionMode.GREEDY,
      false,
      new double[] {0.2, 0.6, 0.2},
      new double[] {0.5, 0.3, 0.2});

    List<ALNS.OperatorState> destroyOperators = hybCec.getState().getAlnsDestroyOperators(config);
    List<ALNS.OperatorState> repairOperators = hybCec.getState().getAlnsRepairOperators(config);

    assertEquals(0.2, destroyOperators.get(0).getWeight(), 1.0e-12);
    assertEquals(0.6, destroyOperators.get(1).getWeight(), 1.0e-12);
    assertEquals(0.5, repairOperators.get(0).getWeight(), 1.0e-12);
    assertEquals(0.2, repairOperators.get(2).getWeight(), 1.0e-12);
  }

  private HybCec createHybCec() {
    List<double[]> popIni = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      popIni.add(new double[] {i + 0.1, i + 0.2, i + 0.3});
    }

    return new HybCec(
      AlgorithmConstants.ALGORITHM_INDEX_ALNS,
      AlgorithmConstants.ALGORITHM_INDEX_GA,
      3,
      6,
      new int[] {
        AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
        AlgorithmConstants.UPDATE_RATIO_CODE_MIXED_SUBSET
      },
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
      AlgorithmConstants.OPERATION_ORDER_SEQUENTIAL,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PERMUTATION,
      30,
      null,
      popIni,
      new Random(42L),
      population -> {
        List<Double> scores = new ArrayList<>(population.size());
        for (double[] individual : population) {
          double score = 0.0;
          for (double gene : individual) {
            score += gene;
          }
          scores.add(score);
        }
        return scores;
      }
    );
  }

  private List<Task> createTasks(int count) {
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      tasks.add(new Task(i, 0.0, 0.0, i + 1, 10));
    }
    return tasks;
  }

  private List<Task> createWindowedTasks(int count) {
    List<Task> tasks = createTasks(count);
    for (int i = 0; i < count; i++) {
      Task task = tasks.get(i);
      task.setImagingDuration(1);
      task.initializeTimeWindows(1);
      TimeWindow window = new TimeWindow(
        0, task.getId(),
        2026, 1, 1, 0, i, 0.0,
        2026, 1, 1, 0, i, 10.0
      );
      for (int second = 0; second <= 5; second++) {
        window.addAttitudeRecord(new AttitudeRecord(
          2026, 1, 1, 0, i, second,
          0.0, 0.0
        ));
      }
      task.addTimeWindowsForSatellite(0, List.of(window));
    }
    return tasks;
  }

  private double scoreChromosome(double[] chromosome, List<Task> tasks) {
    SchedulingPlan plan = PlanUtils.decode(chromosome, tasks);
    return scoreSequence(plan.getTaskSequence(), tasks.size());
  }

  private double scoreSequence(List<Task> sequence, int taskCount) {
    double score = 0.0;
    int rankWeight = taskCount;
    for (Task task : sequence) {
      score += task.getPriority() * rankWeight--;
    }
    return score;
  }

  private model.Satellite nullSafeSatellite() {
    model.Satellite satellite = new model.Satellite();
    satellite.setId(0);
    return satellite;
  }

}
