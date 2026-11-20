package main;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import common.RandomProvider;
import common.TuningConfig;
import common.TuningDefaults;
import model.AttitudeRecord;
import model.Satellite;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import utils.PlanUtils;

class DeltaFastRefinerTest {
  private final Satellite satellite = new Satellite(0);

  @AfterEach
  void restoreSharedRandomAndStatistics() {
    RandomProvider.reset();
    DeltaFastRefiner.resetAdjacentSwapStats();
  }

  @ParameterizedTest
  @EnumSource(InsertionMode.class)
  void snapshotRangesMatchExistingSetEntry(InsertionMode mode) {
    List<Task> tasks = fixture(23);
    tasks.add(3, null);
    tasks.add(tasks.get(0));
    for (int[] range : List.of(new int[]{-3, 5}, new int[]{0, tasks.size() - 1},
      new int[]{tasks.size(), tasks.size() + 2}, new int[]{3, 2})) {
      Set<Integer> positions = new TreeSet<>();
      for (int i = range[0]; i <= range[1]; i++) positions.add(i);
      RandomProvider.reset(91);
      PlanUtils.ScheduleSnapshotResult expected =
        PlanUtils.generateScheduleWithSnapshots(tasks, satellite, mode, positions);
      long expectedNextRandom = RandomProvider.get().nextLong();
      RandomProvider.reset(91);
      PlanUtils.ScheduleSnapshotResult actual =
        PlanUtils.generateScheduleWithSnapshotRange(tasks, satellite, mode, range[0], range[1]);
      assertEquals(expectedNextRandom, RandomProvider.get().nextLong());
      assertScheduleEquals(expected.finalSchedule, actual.finalSchedule);
      assertEquals(expected.scheduleSnapshots.keySet(), actual.scheduleSnapshots.keySet());
      for (int position : expected.scheduleSnapshots.keySet()) {
        assertScheduleEquals(expected.scheduleSnapshots.get(position), actual.scheduleSnapshots.get(position));
        assertArrayEquals(expected.scheduledIdSnapshots.get(position), actual.scheduledIdSnapshots.get(position));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(InsertionMode.class)
  void inPlaceReplayAndRollbackMatchCopyingPath(InsertionMode mode) {
    List<Task> tasks = fixture(37);
    tasks.add(4, null);
    tasks.add(tasks.get(0));
    PlanUtils.ScheduleSnapshotResult snapshots = PlanUtils.generateScheduleWithSnapshotRange(
      tasks, satellite, mode, 0, tasks.size() - 1);
    PlanUtils.SnapshotReplayScratch scratch = new PlanUtils.SnapshotReplayScratch();
    for (int pos = 0; pos < tasks.size() - 1; pos++) {
      List<Task> trial = new ArrayList<>(tasks);
      Collections.swap(trial, pos, pos + 1);
      List<ScheduledTask> prefix = pos == 0 ? new ArrayList<>() : snapshots.scheduleSnapshots.get(pos - 1);
      List<ScheduledTask> unchangedPrefix = new ArrayList<>(prefix);
      int[] ids = pos == 0 ? null : snapshots.scheduledIdSnapshots.get(pos - 1);
      RandomProvider.reset(172);
      List<ScheduledTask> expected = PlanUtils.generateScheduleFromSnapshot(
        trial, satellite, mode, prefix, ids, pos);
      long expectedNextRandom = RandomProvider.get().nextLong();
      RandomProvider.reset(172);
      SchedulingPlan acceptedCopy = new SchedulingPlan(trial);
      try {
        PlanUtils.generateScheduleFromSnapshotInPlace(
          trial, satellite, mode, prefix, ids, pos, pos + 2, scratch);
        List<ScheduledTask> actual = PlanUtils.continueScheduleFromSnapshotInPlace(
          trial, satellite, mode, prefix, pos + 2, scratch);
        assertSame(prefix, actual);
        assertScheduleEquals(expected, actual);
        assertEquals(expectedNextRandom, RandomProvider.get().nextLong());
        acceptedCopy.setSchedule(actual);
      } finally {
        scratch.rollback(prefix);
      }
      assertEquals(unchangedPrefix, prefix);
      assertScheduleEquals(expected, acceptedCopy.getSchedule());
    }
  }

  @Test
  void rollbackRestoresPrefixAfterPartialReplayThrows() {
    List<Task> tasks = fixture(9);
    Task failure = new Task(999, 0, 0, 1, 1) {
      @Override
      public List<TimeWindow> getTimeWindowsForSatellite(int satelliteId) {
        throw new IllegalStateException("fixture failure");
      }
    };
    List<Task> trial = List.of(tasks.get(0), failure);
    List<ScheduledTask> prefix = new ArrayList<>();
    PlanUtils.SnapshotReplayScratch scratch = new PlanUtils.SnapshotReplayScratch();
    try {
      assertThrows(IllegalStateException.class, () -> PlanUtils.generateScheduleFromSnapshotInPlace(
        trial, satellite, InsertionMode.GREEDY, prefix, null, 0, trial.size(), scratch));
      assertEquals(1, prefix.size());
    } finally {
      scratch.rollback(prefix);
    }
    assertTrue(prefix.isEmpty());
    try {
      PlanUtils.generateScheduleFromSnapshotInPlace(
        tasks, satellite, InsertionMode.GREEDY, prefix, null, 0, tasks.size(), scratch);
      assertTrue(prefix.size() > 0);
    } finally {
      scratch.rollback(prefix);
    }
    assertTrue(prefix.isEmpty());
  }

  @ParameterizedTest
  @EnumSource(InsertionMode.class)
  void refinementMatchesOriginalAdjacentSearchAndRandomConsumption(InsertionMode mode) {
    long acceptedTotal = 0;
    for (int seed = 0; seed < 12; seed++) {
      List<Task> current = fixture(seed);
      List<Task> base = new ArrayList<>(current.subList(0, current.size() - 1));
      List<Task> added = List.of(current.get(current.size() - 1));
      boolean large = seed % 2 == 1;
      List<Task> allTasks = new ArrayList<>(current);
      if (large) {
        for (int id = allTasks.size(); id < 4000; id++) {
          allTasks.add(new Task(id + 10_000, 0, 0, 1, 1));
        }
      }
      int iteration = seed % 3 == 0 ? 2 : 8;
      int positions = large ? (iteration < 5 ? 4 : 3)
        : TuningConfig.getInt("coord.deltaFastRefineMaxCandidatePositions",
          TuningDefaults.COORD_DELTA_FAST_REFINE_MAX_CANDIDATE_POSITIONS);
      RandomProvider.reset(44);
      SchedulingPlan baseline = PlanUtils.buildPlanByLimitedInsertion(
        base, added, satellite, mode, positions, true);
      ReferenceResult expected = originalAdjacentSearch(baseline, added, allTasks.size(), mode);
      long expectedNextRandom = RandomProvider.get().nextLong();
      Map<Integer, Integer> assignment = new HashMap<>();
      for (Task task : current) assignment.put(task.getId(), satellite.getId());
      SatelliteState state = new SatelliteState(new double[base.size()], base, new SchedulingPlan(base));
      DeltaFastRefiner refiner = new DeltaFastRefiner(allTasks, new DeltaFastRefiner.Context() {
        public double computeFastRefineRepairScore(Task task, int satelliteId) {
          return task.getPriority();
        }
        public void materializePlanSchedule(SchedulingPlan plan, Satellite sat, InsertionMode insertionMode) {
          PlanUtils.evaluatePlan(plan, sat, insertionMode);
        }
      });
      DeltaFastRefiner.resetAdjacentSwapStats();
      RandomProvider.reset(44);
      SchedulingPlan actual = refiner.refine(
        satellite, base, current, assignment, mode, Map.of(satellite.getId(), state), iteration);
      assertEquals(expectedNextRandom, RandomProvider.get().nextLong(), "RNG: " + mode + "/" + seed);
      assertEquals(expected.plan.getTaskSequence(), actual.getTaskSequence(), "sequence: " + mode + "/" + seed);
      assertEquals(expected.plan.getFitness(), actual.getFitness());
      assertScheduleEquals(expected.plan.getSchedule(), actual.getSchedule());
      DeltaFastRefiner.AdjacentSwapStats.Snapshot stats = DeltaFastRefiner.adjacentSwapStatsSnapshot();
      assertEquals(expected.attempted, stats.attemptedPositions);
      assertEquals(expected.accepted, stats.acceptedSwaps);
      assertEquals(expected.gain, stats.profitGain);
      acceptedTotal += stats.acceptedSwaps;
    }
    if (mode == InsertionMode.GREEDY) assertTrue(acceptedTotal > 0, "fixture must exercise accepted swaps");
  }

  // Kept independently from the optimized replay loop: original copying path and set snapshots.
  private ReferenceResult originalAdjacentSearch(
    SchedulingPlan baseline, List<Task> added, int allTaskCount, InsertionMode mode
  ) {
    List<Task> sequence = new ArrayList<>(baseline.getTaskSequence());
    int first = sequence.indexOf(added.get(0));
    int radius = TuningConfig.getInt("coord.fastRefineSwapRadius", TuningDefaults.COORD_FAST_REFINE_SWAP_RADIUS);
    int forward = TuningConfig.getInt("coord.fastRefineSwapForwardRadius",
      TuningDefaults.COORD_FAST_REFINE_SWAP_FORWARD_RADIUS);
    int start = Math.max(0, first - radius);
    int end = allTaskCount >= 4000 ? Math.min(sequence.size() - 1, first + forward) : sequence.size() - 1;
    Set<Integer> positions = new TreeSet<>();
    for (int pos = start; pos < end; pos++) if (pos > 0) positions.add(pos - 1);
    PlanUtils.ScheduleSnapshotResult snapshots =
      PlanUtils.generateScheduleWithSnapshots(sequence, satellite, mode, positions);
    SchedulingPlan best = new SchedulingPlan(sequence);
    best.setSchedule(snapshots.finalSchedule);
    best.setFitness((double) PlanUtils.calculateProfit(snapshots.finalSchedule));
    int accepted = 0;
    double gain = 0;
    for (int pos = start; pos < end; pos++) {
      Collections.swap(sequence, pos, pos + 1);
      List<ScheduledTask> prefix = pos > 0 ? snapshots.scheduleSnapshots.get(pos - 1) : List.of();
      int[] ids = pos > 0 ? snapshots.scheduledIdSnapshots.get(pos - 1) : new int[0];
      List<ScheduledTask> schedule = PlanUtils.generateScheduleFromSnapshot(
        sequence, satellite, mode, prefix, ids, pos);
      double fitness = PlanUtils.calculateProfit(schedule);
      if (fitness > best.getFitness()) {
        gain += fitness - best.getFitness();
        accepted++;
        best = new SchedulingPlan(sequence);
        best.setSchedule(schedule);
        best.setFitness(fitness);
        snapshots = PlanUtils.generateScheduleWithSnapshots(sequence, satellite, mode, positions);
      } else {
        Collections.swap(sequence, pos, pos + 1);
      }
    }
    return new ReferenceResult(best, Math.max(0, end - start), accepted, gain);
  }

  private record ReferenceResult(SchedulingPlan plan, int attempted, int accepted, double gain) {}

  private List<Task> fixture(int seed) {
    Random random = new Random(seed);
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < 28; i++) {
      int id = i == 0 ? -7 : i == 1 ? 1_000_005 : i;
      Task task = new Task(id, 0, 0, 1 + random.nextInt(9), 2 + random.nextInt(4));
      List<TimeWindow> windows = new ArrayList<>();
      for (int w = 0; w < 2; w++) {
        int start = random.nextInt(80);
        int end = start + 15 + random.nextInt(30);
        TimeWindow window = new TimeWindow(0, id,
          2026, 1, 1, 0, start / 60, start % 60,
          2026, 1, 1, 0, end / 60, end % 60);
        double roll = random.nextDouble() * 25;
        for (int second = start; second <= end; second++) {
          window.addAttitudeRecord(new AttitudeRecord(
            2026, 1, 1, 0, second / 60, second % 60, roll, 0));
        }
        windows.add(window);
      }
      task.addTimeWindowsForSatellite(0, windows);
      tasks.add(task);
    }
    return tasks;
  }

  private static void assertScheduleEquals(List<ScheduledTask> expected, List<ScheduledTask> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      ScheduledTask a = expected.get(i);
      ScheduledTask b = actual.get(i);
      assertSame(a.getTask(), b.getTask());
      assertEquals(a.getSatelliteId(), b.getSatelliteId());
      assertSame(a.getTimeWindow(), b.getTimeWindow());
      assertSame(a.getStartRecord(), b.getStartRecord());
      assertSame(a.getEndRecord(), b.getEndRecord());
    }
  }
}
