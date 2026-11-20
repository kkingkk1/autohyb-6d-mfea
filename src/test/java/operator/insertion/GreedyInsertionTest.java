package operator.insertion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;
import java.util.List;
import model.AttitudeRecord;
import model.ScheduledTask;
import model.Task;
import model.TimeWindow;
import operator.insertion.InsertionStrategy.InsertionResult;
import org.junit.jupiter.api.Test;

class GreedyInsertionTest {

  @Test
  void emptyScheduleUsesFirstFeasibleWindowAtIndexZero() {
    Task task = new Task(1, 0.0, 0.0, 10, 2);
    TimeWindow tooShort = timeWindow(1, 1, 0, 2);
    TimeWindow feasible = timeWindow(1, 1, 10, 15);
    task.addTimeWindowsForSatellite(0, List.of(tooShort, feasible));

    InsertionResult result = new GreedyInsertion().findBestPosition(
      task,
      0,
      Collections.emptyList()
    );

    assertNotNull(result);
    assertEquals(0, result.getInsertIndex());
    ScheduledTask scheduledTask = result.getTask();
    assertSame(feasible, scheduledTask.getTimeWindow());
    assertSame(feasible.getAttitudeRecord(1), scheduledTask.getStartRecord());
    assertSame(feasible.getAttitudeRecord(3), scheduledTask.getEndRecord());
  }

  @Test
  void nonUniformAttitudeTimesStillFindFirstFeasibleStart() {
    Task previous = new Task(1, 0.0, 0.0, 1, 5);
    TimeWindow previousWindow = irregularTimeWindow(0, 1, 0.0, 1.0);
    ScheduledTask scheduledPrevious = new ScheduledTask(previous);
    scheduledPrevious.setSchedule(
      previousWindow,
      previousWindow.getAttitudeRecord(0),
      previousWindow.getAttitudeRecord(1)
    );

    Task task = new Task(2, 0.0, 0.0, 10, 2);
    TimeWindow window = irregularTimeWindow(
      0, 2,
      1.4, 4.2, 8.9, 12.5, 13.0, 14.0, 15.0
    );
    task.addTimeWindowsForSatellite(0, List.of(window));

    InsertionResult result = new GreedyInsertion().findBestPosition(
      task,
      0,
      List.of(scheduledPrevious)
    );

    assertNotNull(result);
    ScheduledTask scheduledTask = result.getTask();
    assertSame(window.getAttitudeRecord(4), scheduledTask.getStartRecord());
    assertSame(window.getAttitudeRecord(6), scheduledTask.getEndRecord());
  }

  private static TimeWindow timeWindow(int satelliteId, int taskId, int startSecond, int endSecond) {
    TimeWindow window = new TimeWindow(
      satelliteId,
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
    return window;
  }

  private static TimeWindow irregularTimeWindow(int satelliteId, int taskId, double... seconds) {
    TimeWindow window = new TimeWindow(
      satelliteId,
      taskId,
      2026,
      1,
      1,
      0,
      0,
      seconds[0],
      2026,
      1,
      1,
      0,
      0,
      seconds[seconds.length - 1]
    );
    for (double second : seconds) {
      window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, second, 0.0, 0.0));
    }
    return window;
  }
}
