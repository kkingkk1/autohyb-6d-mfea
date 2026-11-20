package main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import common.TuningConfig;
import common.TuningDefaults;

public class TargetSatelliteRanker {
  private static final int ORDINARY_TOP_K = TuningConfig.getInt(
    "ranker.ordinaryTopK",
    TuningDefaults.RANKER_ORDINARY_TOP_K
  );
  private static final int PRIORITIZED_TOP_K = TuningConfig.getInt(
    "ranker.prioritizedTopK",
    TuningDefaults.RANKER_PRIORITIZED_TOP_K
  );
  private static final double HIGH_VALUE_PRIORITY_THRESHOLD = 0.80;

  // ---- cheap rank top-level weights ----
  private static final double CHEAP_PAIR_WEIGHT_HIGH_VALUE = TuningConfig.getDouble("ranker.cheapPairWeightHighValue", TuningDefaults.RANKER_CHEAP_PAIR_WEIGHT_HIGH_VALUE);
  private static final double CHEAP_PAIR_WEIGHT_NORMAL = TuningConfig.getDouble("ranker.cheapPairWeightNormal", TuningDefaults.RANKER_CHEAP_PAIR_WEIGHT_NORMAL);
  // cheap rank - pair sub-scores
  private static final double CHEAP_PAIR_WINDOW_COUNT_WEIGHT = TuningConfig.getDouble("ranker.cheapPairWindowCountWeight", TuningDefaults.RANKER_CHEAP_PAIR_WINDOW_COUNT_WEIGHT);
  private static final double CHEAP_PAIR_WINDOW_LENGTH_WEIGHT = TuningConfig.getDouble("ranker.cheapPairWindowLengthWeight", TuningDefaults.RANKER_CHEAP_PAIR_WINDOW_LENGTH_WEIGHT);
  private static final double CHEAP_PAIR_EARLIEST_START_WEIGHT = TuningConfig.getDouble("ranker.cheapPairEarliestStartWeight", TuningDefaults.RANKER_CHEAP_PAIR_EARLIEST_START_WEIGHT);
  private static final double CHEAP_PAIR_TASK_SAT_KEY_WEIGHT = TuningConfig.getDouble("ranker.cheapPairTaskSatKeyWeight", TuningDefaults.RANKER_CHEAP_PAIR_TASK_SAT_KEY_WEIGHT);
  private static final double CHEAP_PAIR_WINDOW_COMPAT_WEIGHT = TuningConfig.getDouble("ranker.cheapPairWindowCompatWeight", TuningDefaults.RANKER_CHEAP_PAIR_WINDOW_COMPAT_WEIGHT);
  // cheap rank - task sub-scores
  private static final double CHEAP_TASK_PRIORITY_WEIGHT = TuningConfig.getDouble("ranker.cheapTaskPriorityWeight", TuningDefaults.RANKER_CHEAP_TASK_PRIORITY_WEIGHT);
  private static final double CHEAP_TASK_TIGHTNESS_WEIGHT = TuningConfig.getDouble("ranker.cheapTaskTightnessWeight", TuningDefaults.RANKER_CHEAP_TASK_TIGHTNESS_WEIGHT);
  private static final double CHEAP_TASK_UNSCHED_PRESSURE_WEIGHT = TuningConfig.getDouble("ranker.cheapTaskUnschedPressureWeight", TuningDefaults.RANKER_CHEAP_TASK_UNSCHED_PRESSURE_WEIGHT);

  // ---- extractPairFeatures internal combination weights ----
  private static final double FEAT_WINDOW_COMPAT_COUNT_WEIGHT = TuningConfig.getDouble("ranker.featWindowCompatCountWeight", TuningDefaults.RANKER_FEAT_WINDOW_COMPAT_COUNT_WEIGHT);
  private static final double FEAT_WINDOW_COMPAT_LENGTH_WEIGHT = TuningConfig.getDouble("ranker.featWindowCompatLengthWeight", TuningDefaults.RANKER_FEAT_WINDOW_COMPAT_LENGTH_WEIGHT);

  public List<Integer> rank(RankRequest request) {
    if (request == null || request.candidates == null || request.candidates.isEmpty()) {
      return Collections.emptyList();
    }
    TaskFeatures taskFeatures = extractTaskFeatures(request);
    List<RankedCandidate> ranked = cheapRank(request, taskFeatures);
    int topK = resolveTopK(request, ranked.size());
    return buildTopSatelliteIds(ranked, topK);
  }

  private List<RankedCandidate> cheapRank(RankRequest request, TaskFeatures taskFeatures) {
    double maxWindowCount = 1.0;
    double maxWindowLength = 1.0;
    double minEarliestStart = Double.POSITIVE_INFINITY;
    for (SatelliteCandidateContext candidate : request.candidates) {
      maxWindowCount = Math.max(maxWindowCount, candidate.windowCount);
      maxWindowLength = Math.max(maxWindowLength, candidate.totalWindowLength);
      if (Double.isFinite(candidate.earliestStart)) {
        minEarliestStart = Math.min(minEarliestStart, candidate.earliestStart);
      }
    }
    if (!Double.isFinite(minEarliestStart)) {
      minEarliestStart = 0.0;
    }
    List<RankedCandidate> ranked = new ArrayList<>(request.candidates.size());
    for (SatelliteCandidateContext candidate : request.candidates) {
      PairFeatures pairFeatures = extractPairFeatures(candidate, maxWindowCount, maxWindowLength, minEarliestStart);
      double score = computeCheapScore(taskFeatures, pairFeatures);
      ranked.add(new RankedCandidate(candidate, pairFeatures, score, 0));
    }
    ranked.sort((left, right) -> compareByScore(right.cheapScore, left.cheapScore, left.satelliteId, right.satelliteId));
    return assignRanks(ranked);
  }

  private int resolveTopK(RankRequest request, int candidateCount) {
    if (candidateCount <= 0) {
      return 0;
    }
    int configuredTopK = request.prioritizedTask ? PRIORITIZED_TOP_K : ORDINARY_TOP_K;
    return Math.min(candidateCount, Math.max(1, configuredTopK));
  }

  private List<Integer> buildTopSatelliteIds(List<RankedCandidate> rankedCandidates, int topK) {
    List<Integer> topSatelliteIds = new ArrayList<>();
    for (int i = 0; i < Math.min(topK, rankedCandidates.size()); i++) {
      topSatelliteIds.add(rankedCandidates.get(i).satelliteId);
    }
    return topSatelliteIds;
  }

  private int compareByScore(double rightScore, double leftScore, int leftSatelliteId, int rightSatelliteId) {
    int scoreCompare = Double.compare(rightScore, leftScore);
    if (scoreCompare != 0) {
      return scoreCompare;
    }
    return Integer.compare(leftSatelliteId, rightSatelliteId);
  }

  private List<RankedCandidate> assignRanks(List<RankedCandidate> ranked) {
    List<RankedCandidate> withRanks = new ArrayList<>(ranked.size());
    for (int index = 0; index < ranked.size(); index++) {
      RankedCandidate candidate = ranked.get(index);
      withRanks.add(new RankedCandidate(
        candidate.context,
        candidate.pairFeatures,
        candidate.cheapScore,
        index + 1
      ));
    }
    return withRanks;
  }

  private double computeCheapScore(TaskFeatures taskFeatures, PairFeatures pairFeatures) {
    double pairWeight = taskFeatures.highValue ? CHEAP_PAIR_WEIGHT_HIGH_VALUE : CHEAP_PAIR_WEIGHT_NORMAL;
    double taskWeight = 1.0 - pairWeight;
    double cheapPairFit = CHEAP_PAIR_WINDOW_COUNT_WEIGHT * pairFeatures.windowCountScore
      + CHEAP_PAIR_WINDOW_LENGTH_WEIGHT * pairFeatures.windowLengthScore
      + CHEAP_PAIR_EARLIEST_START_WEIGHT * pairFeatures.earliestStartScore
      + CHEAP_PAIR_TASK_SAT_KEY_WEIGHT * pairFeatures.taskSatelliteKeyScore
      + CHEAP_PAIR_WINDOW_COMPAT_WEIGHT * pairFeatures.windowCompatibility;
    double taskConditioning = CHEAP_TASK_PRIORITY_WEIGHT * taskFeatures.priorityScore
      + CHEAP_TASK_TIGHTNESS_WEIGHT * taskFeatures.tightnessScore
      + CHEAP_TASK_UNSCHED_PRESSURE_WEIGHT * taskFeatures.unscheduledPressure;
    return pairWeight * cheapPairFit + taskWeight * taskConditioning;
  }

  private TaskFeatures extractTaskFeatures(RankRequest request) {
    double priorityScore = clamp01(request.priorityScore);
    double tightnessScore = clamp01(request.tightnessScore);
    double unscheduledPressure = clamp01(request.unscheduledPressure);
    boolean highValue = priorityScore >= HIGH_VALUE_PRIORITY_THRESHOLD || request.prioritizedTask;
    return new TaskFeatures(priorityScore, tightnessScore, unscheduledPressure, highValue);
  }

  private PairFeatures extractPairFeatures(
    SatelliteCandidateContext candidate,
    double maxWindowCount,
    double maxWindowLength,
    double minEarliestStart
  ) {
    double windowCountScore = clamp01(safeDivide(candidate.windowCount, maxWindowCount));
    double windowLengthScore = clamp01(safeDivide(candidate.totalWindowLength, maxWindowLength));
    double earliestStartScore = Double.isFinite(candidate.earliestStart)
      ? clamp01(1.0 / (1.0 + Math.max(0.0, candidate.earliestStart - minEarliestStart)))
      : 0.0;
    double windowCompatibility = clamp01(FEAT_WINDOW_COMPAT_COUNT_WEIGHT * windowCountScore + FEAT_WINDOW_COMPAT_LENGTH_WEIGHT * windowLengthScore);
    return new PairFeatures(
      windowCountScore,
      windowLengthScore,
      earliestStartScore,
      clamp01(candidate.taskSatelliteKey),
      windowCompatibility
    );
  }

  private double safeDivide(double numerator, double denominator) {
    if (Math.abs(denominator) <= 1.0e-12) {
      return 0.0;
    }
    return numerator / denominator;
  }

  private double clamp01(double value) {
    if (Double.isNaN(value)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, value));
  }

  public static class RankRequest {
    final List<SatelliteCandidateContext> candidates;
    final boolean prioritizedTask;
    final double priorityScore;
    final double tightnessScore;
    final double unscheduledPressure;

    public RankRequest(
      List<SatelliteCandidateContext> candidates,
      boolean prioritizedTask,
      double priorityScore,
      double tightnessScore,
      double unscheduledPressure
    ) {
      this.candidates = candidates;
      this.prioritizedTask = prioritizedTask;
      this.priorityScore = priorityScore;
      this.tightnessScore = tightnessScore;
      this.unscheduledPressure = unscheduledPressure;
    }
  }

  public static class SatelliteCandidateContext {
    final int satelliteId;
    final int windowCount;
    final double totalWindowLength;
    final double earliestStart;
    final double taskSatelliteKey;

    public SatelliteCandidateContext(
      int satelliteId,
      int windowCount,
      double totalWindowLength,
      double earliestStart,
      double taskSatelliteKey
    ) {
      this.satelliteId = satelliteId;
      this.windowCount = windowCount;
      this.totalWindowLength = totalWindowLength;
      this.earliestStart = earliestStart;
      this.taskSatelliteKey = taskSatelliteKey;
    }
  }

  public static class RankedCandidate {
    final int satelliteId;
    final SatelliteCandidateContext context;
    final PairFeatures pairFeatures;
    final double cheapScore;
    final int cheapRank;

    RankedCandidate(
      SatelliteCandidateContext context,
      PairFeatures pairFeatures,
      double cheapScore,
      int cheapRank
    ) {
      this.context = context;
      this.satelliteId = context.satelliteId;
      this.pairFeatures = pairFeatures;
      this.cheapScore = cheapScore;
      this.cheapRank = cheapRank;
    }
  }

  static class TaskFeatures {
    final double priorityScore;
    final double tightnessScore;
    final double unscheduledPressure;
    final boolean highValue;

    TaskFeatures(double priorityScore, double tightnessScore, double unscheduledPressure, boolean highValue) {
      this.priorityScore = priorityScore;
      this.tightnessScore = tightnessScore;
      this.unscheduledPressure = unscheduledPressure;
      this.highValue = highValue;
    }
  }

  static class PairFeatures {
    final double windowCountScore;
    final double windowLengthScore;
    final double earliestStartScore;
    final double taskSatelliteKeyScore;
    final double windowCompatibility;

    PairFeatures(
      double windowCountScore,
      double windowLengthScore,
      double earliestStartScore,
      double taskSatelliteKeyScore,
      double windowCompatibility
    ) {
      this.windowCountScore = windowCountScore;
      this.windowLengthScore = windowLengthScore;
      this.earliestStartScore = earliestStartScore;
      this.taskSatelliteKeyScore = taskSatelliteKeyScore;
      this.windowCompatibility = windowCompatibility;
    }
  }
}
