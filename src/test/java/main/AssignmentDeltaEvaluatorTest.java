package main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import model.AttitudeRecord;
import model.Satellite;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;

class AssignmentDeltaEvaluatorTest {

  @Test
  void materializePlansReusesAlreadyCalculatedSchedule() {
    Task task = task(1, 7);
    Satellite satellite = satellite(0);
    SchedulingPlan plan = new SchedulingPlan(List.of(task));
    plan.setSchedule(List.of(scheduledTask(task, satellite.getId())));
    plan.setFitness(7.0);
    AtomicInteger materializeCalls = new AtomicInteger();
    AssignmentDeltaEvaluator evaluator = evaluator(List.of(task), List.of(satellite), materializeCalls);

    Map<Integer, SchedulingPlan> plans = Map.of(satellite.getId(), plan);
    Map<Integer, SchedulingPlan> result = evaluator.materializePlans(
      plans,
      Map.of(task.getId(), satellite.getId()),
      InsertionMode.GREEDY
    );

    assertSame(plans, result);
    assertEquals(0, materializeCalls.get());
    assertEquals(1, result.get(satellite.getId()).getSchedule().size());
    assertEquals(7.0, result.get(satellite.getId()).getFitness());
  }

  @Test
  void materializePlansBuildsScheduleWhenFitnessIsMissing() {
    Task task = task(1, 7);
    Satellite satellite = satellite(0);
    SchedulingPlan plan = new SchedulingPlan(List.of(task));
    AtomicInteger materializeCalls = new AtomicInteger();
    AssignmentDeltaEvaluator evaluator = evaluator(List.of(task), List.of(satellite), materializeCalls);

    Map<Integer, SchedulingPlan> result = evaluator.materializePlans(
      Map.of(satellite.getId(), plan),
      Map.of(task.getId(), satellite.getId()),
      InsertionMode.GREEDY
    );

    assertEquals(1, materializeCalls.get());
    assertEquals(1, result.get(satellite.getId()).getSchedule().size());
    assertEquals(7.0, result.get(satellite.getId()).getFitness());
  }

  private AssignmentDeltaEvaluator evaluator(
    List<Task> tasks,
    List<Satellite> satellites,
    AtomicInteger materializeCalls
  ) {
    return new AssignmentDeltaEvaluator(
      tasks,
      satellites,
      new HashMap<>(),
      null,
      new AssignmentDeltaEvaluator.Context() {
        @Override
        public void syncSatelliteTaskViews(Map<Integer, Integer> assignmentSnapshot) {}

        @Override
        public List<Task> getOrderedTasksForSatellite(
          int satelliteId,
          List<Task> preGroupedTasks,
          Map<Integer, Integer> assignmentSnapshot
        ) {
          return Collections.emptyList();
        }

        @Override
        public List<Task> getOrderedTasksForSatellite(int satelliteId, Map<Integer, Integer> assignmentSnapshot) {
          return Collections.emptyList();
        }

        @Override
        public SchedulingPlan scheduleSingleSatellite(
          Satellite satellite,
          List<Task> orderedTasks,
          TaskAllocationCoordinator.HybCecConfig config,
          boolean commitState,
          boolean refreshAnchorOnFallback,
          Map<Integer, SatelliteState> stateStore
        ) {
          return new SchedulingPlan(Collections.emptyList());
        }

        @Override
        public void materializePlanSchedule(SchedulingPlan plan, Satellite satellite, InsertionMode mode) {
          materializeCalls.incrementAndGet();
          if (!plan.getTaskSequence().isEmpty()) {
            plan.setSchedule(List.of(scheduledTask(plan.getTaskSequence().get(0), satellite.getId())));
          }
          plan.setFitness((double) plan.getTotalProfit());
        }

        @Override
        public int getCurrentIteration() {
          return 0;
        }
      }
    );
  }

  private Task task(int id, int priority) {
    Task task = new Task(id, 0.0, 0.0, priority, 1);
    task.initializeTimeWindows(1);
    task.addTimeWindowsForSatellite(0, List.of(timeWindow(id, 0)));
    return task;
  }

  private Satellite satellite(int id) {
    return new Satellite(id);
  }

  private ScheduledTask scheduledTask(Task task, int satelliteId) {
    ScheduledTask scheduledTask = new ScheduledTask(task);
    scheduledTask.setSchedule(timeWindow(task.getId(), satelliteId), attitude(0), attitude(1));
    return scheduledTask;
  }

  private TimeWindow timeWindow(int taskId, int satelliteId) {
    TimeWindow window = new TimeWindow(
      satelliteId,
      taskId,
      2026, 1, 1, 0, 0, 0.0,
      2026, 1, 1, 0, 0, 10.0
    );
    window.addAttitudeRecord(attitude(0));
    window.addAttitudeRecord(attitude(1));
    return window;
  }

  private AttitudeRecord attitude(double second) {
    return new AttitudeRecord(2026, 1, 1, 0, 0, second, 0.0, 0.0);
  }
}
