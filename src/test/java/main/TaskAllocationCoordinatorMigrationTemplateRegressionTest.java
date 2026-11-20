package main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import common.AlgorithmConstants;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import model.Satellite;
import model.SchedulingPlan;
import model.Task;
import operator.assignment.MaxWindowCountStrategy;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;
import utils.PlanUtils;

class TaskAllocationCoordinatorMigrationTemplateRegressionTest {

  @Test
  void migrationTemplateOnlyReordersSharedTasksAndDoesNotTouchAssignment() {
    Task t1 = task(1);
    Task t2 = task(2);
    Task t3 = task(3);
    Task t4 = task(4);

    Satellite sat1 = satellite(1, List.of(t2, t3, t4));
    Satellite sat2 = satellite(2, List.of(t1, t2, t3));
    TaskAllocationCoordinator coordinator = new TaskAllocationCoordinator(
      List.of(t1, t2, t3, t4),
      List.of(sat1, sat2),
      new MaxWindowCountStrategy(),
      3,
      2,
      0.1
    );

    Map<Integer, Integer> assignment = new HashMap<>();
    assignment.put(1, 2);
    assignment.put(2, 1);
    assignment.put(3, 1);
    assignment.put(4, 1);
    coordinator.applyInitialAssignmentForTest(assignment);
    coordinator.captureInitialAssignmentSnapshotForTest();

    List<Task> donorOrder = List.of(t3, t2, t1);
    double[] donorSolution = PlanUtils.encodePlan(new SchedulingPlan(donorOrder), donorOrder);
    coordinator.updateSatelliteStateForTest(2, donorSolution, donorOrder, new SchedulingPlan(donorOrder));

    TaskAllocationCoordinator.HybCecConfig config = createConfig(6, 42L);

    List<Task> currentOrderedTasks = List.of(t2, t3, t4);
    List<double[]> popIni = coordinator.buildPopulationWithInheritanceForTest(
      sat1,
      currentOrderedTasks,
      config,
      42L,
      true
    );

    SchedulingPlan migrated = PlanUtils.decode(popIni.get(3), currentOrderedTasks);
    assertEquals(List.of(3, 2, 4), taskIds(migrated.getTaskSequence()));
    assertEquals(assignment, coordinator.getTaskAssignmentSnapshot());
  }

  @Test
  void migrationTemplateFallsBackToNoOpWhenSharedTasksAreInsufficient() {
    Task t1 = task(1);
    Task t2 = task(2);
    Task t3 = task(3);

    Satellite sat1 = satellite(1, List.of(t1, t3));
    Satellite sat2 = satellite(2, List.of(t1, t2));
    TaskAllocationCoordinator coordinator = new TaskAllocationCoordinator(
      List.of(t1, t2, t3),
      List.of(sat1, sat2),
      new MaxWindowCountStrategy(),
      3,
      2,
      0.1
    );

    List<Task> donorOrder = List.of(t2, t1);
    double[] donorSolution = PlanUtils.encodePlan(new SchedulingPlan(donorOrder), donorOrder);
    coordinator.updateSatelliteStateForTest(2, donorSolution, donorOrder, new SchedulingPlan(donorOrder));

    assertNull(coordinator.buildCrossSatelliteMigrationTemplateForTest(1, List.of(t1, t3)));
  }

  @Test
  void satelliteStateSnapshotIsDetachedFromLaterStateCommits() {
    Task t1 = task(1);
    Task t2 = task(2);
    Satellite sat1 = satellite(1, List.of(t1, t2));
    TaskAllocationCoordinator coordinator = new TaskAllocationCoordinator(
      List.of(t1, t2),
      List.of(sat1),
      new MaxWindowCountStrategy(),
      3,
      2,
      0.1
    );

    List<Task> firstOrder = List.of(t1, t2);
    coordinator.updateSatelliteStateForTest(
      1,
      PlanUtils.encodePlan(new SchedulingPlan(firstOrder), firstOrder),
      firstOrder,
      new SchedulingPlan(firstOrder)
    );
    Map<Integer, SatelliteState> snapshot = coordinator.copySatelliteStatesForTest();

    List<Task> secondOrder = List.of(t2, t1);
    coordinator.updateSatelliteStateForTest(
      1,
      PlanUtils.encodePlan(new SchedulingPlan(secondOrder), secondOrder),
      secondOrder,
      new SchedulingPlan(secondOrder)
    );

    assertEquals(List.of(1, 2), taskIds(snapshot.get(1).orderedTasksSnapshot));
  }

  private Task task(int id) {
    return new Task(id, id, id, 10 - id, 1);
  }

  private Satellite satellite(int id, List<Task> tasks) {
    Satellite satellite = new Satellite(id);
    satellite.setAssignedTasks(tasks);
    return satellite;
  }

  private TaskAllocationCoordinator.HybCecConfig createConfig(int nPop, long seed) {
    return new TaskAllocationCoordinator.HybCecConfig(
      new int[] {AlgorithmConstants.ALGORITHM_INDEX_GA, AlgorithmConstants.ALGORITHM_INDEX_PSO},
      new int[] {AlgorithmConstants.UPDATE_RATIO_CODE_FULL, AlgorithmConstants.UPDATE_RATIO_CODE_FULL},
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
      AlgorithmConstants.OPERATION_ORDER_SEQUENTIAL,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PERMUTATION,
      nPop,
      20,
      InsertionMode.GREEDY,
      seed
    );
  }

  private List<Integer> taskIds(List<Task> tasks) {
    return tasks.stream().map(Task::getId).collect(Collectors.toList());
  }
}
