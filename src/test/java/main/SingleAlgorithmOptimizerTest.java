package main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import common.AlgorithmConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import model.AttitudeRecord;
import model.Satellite;
import model.Task;
import model.TimeWindow;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;

class SingleAlgorithmOptimizerTest {

  @Test
  void gaRunKeepsBestFitnessMonotonic() throws Exception {
    List<Task> tasks = createWindowedTasks(3);
    Satellite satellite = new Satellite(0);
    satellite.setAssignedTasks(tasks);
    List<double[]> initialPopulation = createInitialPopulation();

    SingleAlgorithmOptimizer.Result result = SingleAlgorithmOptimizer.optimize(
      AlgorithmConstants.ALGORITHM_INDEX_GA,
      tasks.size(),
      initialPopulation.size(),
      12,
      satellite,
      tasks,
      initialPopulation,
      new Random(42L),
      InsertionMode.GREEDY
    );

    assertNotNull(result.getBestSolution());
    assertEquals(tasks.size(), result.getBestSolution().length);
    assertEquals(
      result.getBestFitnessByGeneration().size(),
      result.getEvalsByGeneration().size()
    );
    List<Double> history = result.getBestFitnessByGeneration();
    for (int i = 1; i < history.size(); i++) {
      assertTrue(history.get(i) >= history.get(i - 1));
    }
    assertTrue(result.getEvalsByGeneration().get(result.getEvalsByGeneration().size() - 1) >= 12);
  }

  @Test
  void lShadeRunsWithPartialFinalBatch() throws Exception {
    List<Task> tasks = createWindowedTasks(3);
    Satellite satellite = new Satellite(0);
    satellite.setAssignedTasks(tasks);
    List<double[]> initialPopulation = createInitialPopulation();

    SingleAlgorithmOptimizer.Result result = SingleAlgorithmOptimizer.optimize(
      AlgorithmConstants.ALGORITHM_INDEX_LSHADE,
      tasks.size(),
      initialPopulation.size(),
      6,
      satellite,
      tasks,
      initialPopulation,
      new Random(42L),
      InsertionMode.GREEDY
    );

    assertNotNull(result.getBestSolution());
    assertEquals(6, result.getEvalsByGeneration().get(result.getEvalsByGeneration().size() - 1));
  }

  private List<double[]> createInitialPopulation() {
    return List.of(
      new double[] {0.1, 0.2, 0.3},
      new double[] {0.3, 0.2, 0.1},
      new double[] {0.2, 0.1, 0.3},
      new double[] {0.2, 0.3, 0.1}
    );
  }

  private List<Task> createWindowedTasks(int count) {
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Task task = new Task(i, 0.0, 0.0, i + 1, 10);
      task.setImagingDuration(1);
      task.initializeTimeWindows(1);
      TimeWindow window = new TimeWindow(
        0,
        task.getId(),
        2026,
        1,
        1,
        0,
        i,
        0.0,
        2026,
        1,
        1,
        0,
        i,
        10.0
      );
      for (int second = 0; second <= 5; second++) {
        window.addAttitudeRecord(new AttitudeRecord(
          2026,
          1,
          1,
          0,
          i,
          second,
          0.0,
          0.0
        ));
      }
      task.addTimeWindowsForSatellite(0, List.of(window));
      tasks.add(task);
    }
    return tasks;
  }
}
