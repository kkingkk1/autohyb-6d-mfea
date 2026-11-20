package utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import model.AttitudeRecord;
import model.Satellite;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;

class DecodeFastVsLegacyTest {

  @Test
  void decodeMatchesLegacyIntegerSortForRandomVectors() {
    Random random = new Random(123456789L);
    for (int trial = 0; trial < 10_000; trial++) {
      int n = 1 + random.nextInt(128);
      double[] vec = new double[n];
      List<Task> tasks = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        vec[i] = sampleValue(random, i);
        tasks.add(new Task(i, 0.0, 0.0, 1, 1));
      }

      SchedulingPlan fast = PlanUtils.decode(vec, tasks);
      List<Integer> legacyOrder = legacyOrder(vec);
      int[] directOrder = PlanUtils.decodeTaskIdOrderForTesting(vec, tasks, new DecodeWorkspace());

      for (int i = 0; i < n; i++) {
        assertEquals(legacyOrder.get(i).intValue(), fast.getTaskSequence().get(i).getId());
      }
      assertArrayEquals(toIntArray(legacyOrder), directOrder);
    }
  }

  @Test
  void decodeMatchesLegacyIntegerSortForNanAndSignedZero() {
    double[] vec = {
      1.0,
      Double.NaN,
      -0.0,
      0.0,
      Double.NaN,
      -2.0,
      1.0
    };
    List<Task> tasks = new ArrayList<>(vec.length);
    for (int i = 0; i < vec.length; i++) {
      tasks.add(new Task(i, 0.0, 0.0, 1, 1));
    }

    SchedulingPlan fast = PlanUtils.decode(vec, tasks);
    List<Integer> legacyOrder = legacyOrder(vec);
    int[] directOrder = PlanUtils.decodeTaskIdOrderForTesting(vec, tasks, new DecodeWorkspace());

    for (int i = 0; i < vec.length; i++) {
      assertEquals(legacyOrder.get(i).intValue(), fast.getTaskSequence().get(i).getId());
    }
    assertArrayEquals(toIntArray(legacyOrder), directOrder);
  }

  @Test
  void workspaceSchedulePoolKeepsRepeatedDirectEvaluationEquivalentToPlanEvaluation() {
    Satellite satellite = new Satellite(0);
    Task first = schedulableTask(1, 10, 0, 20);
    Task second = schedulableTask(2, 20, 40, 60);
    Task third = schedulableTask(3, 30, 80, 100);
    List<Task> tasks = List.of(first, second, third);
    satellite.setAssignedTasks(tasks);

    DecodeWorkspace workspace = new DecodeWorkspace();
    double[] firstVector = {3.0, 2.0, 1.0};
    double[] secondVector = {1.0, 3.0, 2.0};

    double firstDirect = PlanUtils.evaluateVector(
      firstVector,
      tasks,
      satellite,
      InsertionMode.GREEDY,
      workspace
    );
    double secondDirect = PlanUtils.evaluateVector(
      secondVector,
      tasks,
      satellite,
      InsertionMode.GREEDY,
      workspace
    );

    assertEquals(
      PlanUtils.evaluatePlan(PlanUtils.decode(firstVector, tasks), satellite, InsertionMode.GREEDY),
      firstDirect
    );
    assertEquals(
      PlanUtils.evaluatePlan(PlanUtils.decode(secondVector, tasks), satellite, InsertionMode.GREEDY),
      secondDirect
    );
  }

  private static double sampleValue(Random random, int index) {
    int bucket = random.nextInt(20);
    if (bucket == 0) {
      return 0.0;
    }
    if (bucket == 1) {
      return -0.0;
    }
    if (bucket < 6) {
      return index % 7;
    }
    return random.nextDouble() * 2.0 - 1.0;
  }

  private static List<Integer> legacyOrder(double[] vec) {
    Integer[] order = new Integer[vec.length];
    for (int i = 0; i < vec.length; i++) {
      order[i] = i;
    }
    Arrays.sort(order, Comparator.comparingDouble((Integer i) -> vec[i]).reversed());
    return Arrays.asList(order);
  }

  private static int[] toIntArray(List<Integer> values) {
    int[] result = new int[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i);
    }
    return result;
  }

  private static Task schedulableTask(
    int taskId,
    int priority,
    int startSecond,
    int endSecond
  ) {
    Task task = new Task(taskId, 0.0, 0.0, priority, 2);
    TimeWindow window = new TimeWindow(
      0,
      taskId,
      2026,
      1,
      1,
      0,
      0,
      startSecond,
      2026,
      1,
      1,
      0,
      0,
      endSecond
    );
    for (int second = startSecond; second <= endSecond; second++) {
      window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, second, 0.0, 0.0));
    }
    task.addTimeWindowsForSatellite(0, List.of(window));
    return task;
  }
}
