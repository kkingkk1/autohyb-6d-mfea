package main;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import model.Satellite;
import model.SchedulingPlan;
import model.Task;
import operator.insertion.InsertionMode;

final class AssignmentDeltaEvaluator {
  static final int MOVE_LAZY_EVAL_MIN_TASKS = 4000;

  interface Context {
    void syncSatelliteTaskViews(Map<Integer, Integer> assignmentSnapshot);

    List<Task> getOrderedTasksForSatellite(
      int satelliteId,
      List<Task> preGroupedTasks,
      Map<Integer, Integer> assignmentSnapshot
    );

    List<Task> getOrderedTasksForSatellite(int satelliteId, Map<Integer, Integer> assignmentSnapshot);

    SchedulingPlan scheduleSingleSatellite(
      Satellite satellite,
      List<Task> orderedTasks,
      TaskAllocationCoordinator.HybCecConfig config,
      boolean commitState,
      boolean refreshAnchorOnFallback,
      Map<Integer, SatelliteState> stateStore
    ) throws Exception;

    void materializePlanSchedule(SchedulingPlan plan, Satellite satellite, InsertionMode mode);

    int getCurrentIteration();
  }

  private final List<Task> allTasks;
  private final List<Satellite> satellites;
  private final Map<Integer, SatelliteState> satelliteStates;
  private final DeltaFastRefiner deltaFastRefiner;
  private final Context context;

  AssignmentDeltaEvaluator(
    List<Task> allTasks,
    List<Satellite> satellites,
    Map<Integer, SatelliteState> satelliteStates,
    DeltaFastRefiner deltaFastRefiner,
    Context context
  ) {
    this.allTasks = allTasks;
    this.satellites = satellites;
    this.satelliteStates = satelliteStates;
    this.deltaFastRefiner = deltaFastRefiner;
    this.context = context;
  }

  Map<Integer, SchedulingPlan> buildDeltaPlans(
    Map<Integer, SchedulingPlan> basePlans,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, Integer> trialAssignment,
    Set<Integer> affectedSatelliteIds,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    Map<Integer, SchedulingPlan> recomputedAffectedPlans = evaluateAffectedSatellites(
      baseAssignment,
      trialAssignment,
      affectedSatelliteIds,
      config
    );
    return mergeDeltaPlans(basePlans, baseAssignment, trialAssignment, affectedSatelliteIds, recomputedAffectedPlans);
  }

  Map<Integer, SchedulingPlan> buildDeltaPlansLazy(
    Map<Integer, SchedulingPlan> basePlans,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, Integer> trialAssignment,
    Set<Integer> affectedSatelliteIds,
    int targetSatelliteId,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    Map<Integer, SchedulingPlan> recomputedAffectedPlans = evaluateAffectedSatellitesLazy(
      baseAssignment,
      trialAssignment,
      affectedSatelliteIds,
      targetSatelliteId,
      basePlans,
      config
    );
    if (recomputedAffectedPlans.size() < affectedSatelliteIds.size()) {
      Map<Integer, SchedulingPlan> mergedPlans = copyPlans(basePlans);
      for (Map.Entry<Integer, SchedulingPlan> entry : recomputedAffectedPlans.entrySet()) {
        mergedPlans.put(entry.getKey(), entry.getValue());
      }
      return mergedPlans;
    }
    return mergeDeltaPlans(basePlans, baseAssignment, trialAssignment, affectedSatelliteIds, recomputedAffectedPlans);
  }

  TrialMoveResult evaluateTrialMove(
    Task task,
    Integer sourceSatelliteId,
    int targetSatelliteId,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    Map<Integer, Integer> trialAssignment = AssignmentOverlayMap.over(baseAssignment)
      .withAssigned(task.getId(), targetSatelliteId);

    if (sourceSatelliteId != null && allTasks.size() >= MOVE_LAZY_EVAL_MIN_TASKS) {
      Set<Integer> targetOnly = Collections.singleton(targetSatelliteId);
      Map<Integer, SchedulingPlan> targetRecomputed = evaluateAffectedSatellites(
        baseAssignment,
        trialAssignment,
        targetOnly,
        config
      );
      double targetDelta = getPlanProfit(targetRecomputed.get(targetSatelliteId))
        - getPlanProfit(basePlans.get(targetSatelliteId));
      if (targetDelta <= 0.0) {
        Map<Integer, SchedulingPlan> earlyPlans = copyPlans(basePlans);
        earlyPlans.put(targetSatelliteId, targetRecomputed.get(targetSatelliteId));
        double earlyProfit = baseProfit + targetDelta;
        return new TrialMoveResult(trialAssignment, earlyPlans, earlyProfit, earlyProfit - baseProfit);
      }
      Set<Integer> sourceOnly = Collections.singleton(sourceSatelliteId);
      Map<Integer, SchedulingPlan> sourceRecomputed = evaluateAffectedSatellites(
        baseAssignment,
        trialAssignment,
        sourceOnly,
        config
      );
      Map<Integer, SchedulingPlan> trialPlans = copyPlans(basePlans);
      trialPlans.put(targetSatelliteId, targetRecomputed.get(targetSatelliteId));
      trialPlans.put(sourceSatelliteId, sourceRecomputed.get(sourceSatelliteId));
      double sourceDelta = getPlanProfit(sourceRecomputed.get(sourceSatelliteId))
        - getPlanProfit(basePlans.get(sourceSatelliteId));
      double trialProfit = baseProfit + targetDelta + sourceDelta;
      return new TrialMoveResult(trialAssignment, trialPlans, trialProfit, trialProfit - baseProfit);
    }

    Set<Integer> affectedSatelliteIds = new LinkedHashSet<>();
    if (sourceSatelliteId != null) {
      affectedSatelliteIds.add(sourceSatelliteId);
    }
    affectedSatelliteIds.add(targetSatelliteId);
    Map<Integer, SchedulingPlan> trialPlans = buildDeltaPlans(
      basePlans,
      baseAssignment,
      trialAssignment,
      affectedSatelliteIds,
      config
    );
    double trialProfit = calculateDeltaProfit(basePlans, trialPlans, affectedSatelliteIds, baseProfit);
    return new TrialMoveResult(
      trialAssignment,
      trialPlans,
      trialProfit,
      trialProfit - baseProfit
    );
  }

  Map<Integer, SchedulingPlan> evaluateAffectedSatellites(
    Map<Integer, Integer> baseAssignmentSnapshot,
    Map<Integer, Integer> assignmentSnapshot,
    Set<Integer> affectedSatelliteIds,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    context.syncSatelliteTaskViews(assignmentSnapshot);
    Map<Integer, SchedulingPlan> recomputedPlans = new HashMap<>();
    for (Satellite sat : satellites) {
      if (!affectedSatelliteIds.contains(sat.getId())) {
        continue;
      }
      List<Task> previousOrderedTasks = context.getOrderedTasksForSatellite(sat.getId(), baseAssignmentSnapshot);
      List<Task> orderedTasks = context.getOrderedTasksForSatellite(sat.getId(), assignmentSnapshot);
      SchedulingPlan plan = deltaFastRefiner.refine(
        sat,
        previousOrderedTasks,
        orderedTasks,
        assignmentSnapshot,
        config.insertionMode,
        satelliteStates,
        context.getCurrentIteration()
      );
      if (plan == null) {
        plan = context.scheduleSingleSatellite(sat, orderedTasks, config, false, false, satelliteStates);
      }
      recomputedPlans.put(sat.getId(), plan);
    }
    return materializePlans(recomputedPlans, affectedSatelliteIds, config.insertionMode);
  }

  Map<Integer, SchedulingPlan> evaluateAffectedSatellitesLazy(
    Map<Integer, Integer> baseAssignmentSnapshot,
    Map<Integer, Integer> assignmentSnapshot,
    Set<Integer> affectedSatelliteIds,
    int targetSatelliteId,
    Map<Integer, SchedulingPlan> basePlans,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    if (allTasks.size() < MOVE_LAZY_EVAL_MIN_TASKS || affectedSatelliteIds.size() <= 1) {
      return evaluateAffectedSatellites(baseAssignmentSnapshot, assignmentSnapshot, affectedSatelliteIds, config);
    }
    context.syncSatelliteTaskViews(assignmentSnapshot);
    Map<Integer, SchedulingPlan> recomputedPlans = new HashMap<>();
    for (Satellite sat : satellites) {
      if (sat.getId() != targetSatelliteId) {
        continue;
      }
      List<Task> previousOrderedTasks = context.getOrderedTasksForSatellite(sat.getId(), baseAssignmentSnapshot);
      List<Task> orderedTasks = context.getOrderedTasksForSatellite(sat.getId(), assignmentSnapshot);
      SchedulingPlan plan = deltaFastRefiner.refine(
        sat,
        previousOrderedTasks,
        orderedTasks,
        assignmentSnapshot,
        config.insertionMode,
        satelliteStates,
        context.getCurrentIteration()
      );
      if (plan == null) {
        plan = context.scheduleSingleSatellite(sat, orderedTasks, config, false, false, satelliteStates);
      }
      recomputedPlans.put(sat.getId(), plan);
      break;
    }

    SchedulingPlan targetPlan = recomputedPlans.get(targetSatelliteId);
    SchedulingPlan materializedTargetPlan = null;
    if (targetPlan != null) {
      materializedTargetPlan = targetPlan.copy();
      Satellite targetSat = getSatelliteById(targetSatelliteId);
      if (targetSat != null) {
        context.materializePlanSchedule(materializedTargetPlan, targetSat, config.insertionMode);
      }
    }
    double targetDelta = getPlanProfit(materializedTargetPlan)
      - getPlanProfit(basePlans.get(targetSatelliteId));
    if (targetDelta <= 0.0) {
      Map<Integer, SchedulingPlan> earlyResult = new HashMap<>();
      earlyResult.put(targetSatelliteId, materializedTargetPlan);
      return earlyResult;
    }

    Set<Integer> remainingIds = new HashSet<>(affectedSatelliteIds);
    remainingIds.remove(targetSatelliteId);
    for (Satellite sat : satellites) {
      if (!remainingIds.contains(sat.getId())) {
        continue;
      }
      List<Task> previousOrderedTasks = context.getOrderedTasksForSatellite(sat.getId(), baseAssignmentSnapshot);
      List<Task> orderedTasks = context.getOrderedTasksForSatellite(sat.getId(), assignmentSnapshot);
      SchedulingPlan plan = deltaFastRefiner.refine(
        sat,
        previousOrderedTasks,
        orderedTasks,
        assignmentSnapshot,
        config.insertionMode,
        satelliteStates,
        context.getCurrentIteration()
      );
      if (plan == null) {
        plan = context.scheduleSingleSatellite(sat, orderedTasks, config, false, false, satelliteStates);
      }
      recomputedPlans.put(sat.getId(), plan);
    }
    Map<Integer, SchedulingPlan> materializedPlans = materializePlans(
      recomputedPlans,
      remainingIds,
      config.insertionMode
    );
    materializedPlans.put(targetSatelliteId, materializedTargetPlan);
    return materializedPlans;
  }

  Map<Integer, SchedulingPlan> mergeDeltaPlans(
    Map<Integer, SchedulingPlan> basePlans,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, Integer> trialAssignment,
    Set<Integer> affectedSatelliteIds,
    Map<Integer, SchedulingPlan> recomputedAffectedPlans
  ) {
    validatePlanCoverage(basePlans, "basePlans");
    validateAffectedAssignmentDelta(baseAssignment, trialAssignment, affectedSatelliteIds);
    for (Integer satelliteId : affectedSatelliteIds) {
      if (!recomputedAffectedPlans.containsKey(satelliteId)) {
        throw new IllegalStateException(
          String.format("Missing recomputed plan for affected satellite %d", satelliteId)
        );
      }
    }
    Map<Integer, SchedulingPlan> mergedPlans = copyPlans(basePlans);
    for (Integer satelliteId : affectedSatelliteIds) {
      SchedulingPlan recomputedPlan = recomputedAffectedPlans.get(satelliteId);
      mergedPlans.put(satelliteId, recomputedPlan);
    }
    return mergedPlans;
  }

  Map<Integer, SchedulingPlan> materializePlans(
    Map<Integer, SchedulingPlan> plans,
    Map<Integer, Integer> assignmentSnapshot,
    InsertionMode mode
  ) {
    if (areAllSatellitePlansMaterialized(plans)) {
      return plans;
    }
    Map<Integer, SchedulingPlan> materializedPlans = newPlanMap(satellites.size());
    for (int i = 0; i < satellites.size(); i++) {
      Satellite sat = satellites.get(i);
      SchedulingPlan plan = plans.get(sat.getId());
      if (plan == null) {
        materializedPlans.put(sat.getId(), null);
        continue;
      }
      materializedPlans.put(sat.getId(), materializePlan(plan, sat, mode));
    }
    return materializedPlans;
  }

  private boolean areAllSatellitePlansMaterialized(Map<Integer, SchedulingPlan> plans) {
    for (int i = 0; i < satellites.size(); i++) {
      Satellite sat = satellites.get(i);
      if (!plans.containsKey(sat.getId())) {
        return false;
      }
      SchedulingPlan plan = plans.get(sat.getId());
      if (plan == null) {
        continue;
      }
      if (!isMaterializedWithPureProfit(plan)) {
        return false;
      }
    }
    return true;
  }

  Map<Integer, SchedulingPlan> materializePlans(
    Map<Integer, SchedulingPlan> plans,
    Set<Integer> satelliteIds,
    InsertionMode mode
  ) {
    Map<Integer, SchedulingPlan> materializedPlans = newPlanMap(satelliteIds.size());
    for (int i = 0; i < satellites.size(); i++) {
      Satellite sat = satellites.get(i);
      if (!satelliteIds.contains(sat.getId())) {
        continue;
      }
      SchedulingPlan plan = plans.get(sat.getId());
      if (plan == null) {
        materializedPlans.put(sat.getId(), null);
        continue;
      }
      materializedPlans.put(sat.getId(), materializePlan(plan, sat, mode));
    }
    return materializedPlans;
  }

  private SchedulingPlan materializePlan(SchedulingPlan plan, Satellite satellite, InsertionMode mode) {
    SchedulingPlan normalizedPlan = plan.copy();
    if (isMaterializedWithPureProfit(normalizedPlan)) {
      normalizedPlan.setFitness((double) normalizedPlan.getTotalProfit());
      return normalizedPlan;
    }
    context.materializePlanSchedule(normalizedPlan, satellite, mode);
    return normalizedPlan;
  }

  private static boolean isMaterializedWithPureProfit(SchedulingPlan plan) {
    return plan.isFitnessCalculated()
      && Double.compare(plan.getFitness(), (double) plan.getTotalProfit()) == 0;
  }

  private static Map<Integer, SchedulingPlan> newPlanMap(int expectedSize) {
    int capacity = Math.max(16, (int) (expectedSize / 0.75f) + 1);
    return new HashMap<>(capacity);
  }

  void validatePlanCoverage(Map<Integer, SchedulingPlan> plans, String label) {
    for (Satellite sat : satellites) {
      if (!plans.containsKey(sat.getId())) {
        throw new IllegalStateException(
          String.format("%s is missing plan for satellite %d", label, sat.getId())
        );
      }
    }
  }

  void validateAffectedAssignmentDelta(
    Map<Integer, Integer> baseAssignment,
    Map<Integer, Integer> trialAssignment,
    Set<Integer> affectedSatelliteIds
  ) {
    for (Task task : allTasks) {
      Integer baseSatelliteId = baseAssignment.get(task.getId());
      Integer trialSatelliteId = trialAssignment.get(task.getId());
      boolean changed = !java.util.Objects.equals(baseSatelliteId, trialSatelliteId);
      if (!changed) {
        continue;
      }
      if (baseSatelliteId != null && !affectedSatelliteIds.contains(baseSatelliteId)) {
        throw new IllegalStateException(
          String.format(
            "Assignment changed outside affected satellites: taskId=%d, satelliteId=%d",
            task.getId(),
            baseSatelliteId
          )
        );
      }
      if (trialSatelliteId != null && !affectedSatelliteIds.contains(trialSatelliteId)) {
        throw new IllegalStateException(
          String.format(
            "Assignment changed outside affected satellites: taskId=%d, satelliteId=%d",
            task.getId(),
            trialSatelliteId
          )
        );
      }
    }
  }

  double calculateDeltaProfit(
    Map<Integer, SchedulingPlan> basePlans,
    Map<Integer, SchedulingPlan> trialPlans,
    Set<Integer> affectedSatelliteIds,
    double baseProfit
  ) {
    double delta = 0.0;
    for (int satId : affectedSatelliteIds) {
      delta -= getPlanProfit(basePlans.get(satId));
      delta += getPlanProfit(trialPlans.get(satId));
    }
    return baseProfit + delta;
  }

  static double getPlanProfit(SchedulingPlan plan) {
    if (plan == null) {
      return 0.0;
    }
    return plan.isFitnessCalculated() ? plan.getFitness() : plan.getTotalProfit();
  }

  static Map<Integer, SchedulingPlan> copyPlans(Map<Integer, SchedulingPlan> plans) {
    return new HashMap<>(plans);
  }

  private Satellite getSatelliteById(int satelliteId) {
    for (Satellite satellite : satellites) {
      if (satellite.getId() == satelliteId) {
        return satellite;
      }
    }
    return null;
  }
}
