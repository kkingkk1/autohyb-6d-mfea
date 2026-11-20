package operator.selection;
import common.AlgorithmConstants;
import common.RandomProvider;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class Tournament {


  // ==========================================================
  // List-based methods
  // ==========================================================

  public static List<Integer> select(List<Double> popFit, int kTournament, Random rand) {
    validateParameters(popFit, kTournament);

    List<Integer> indiceParents = new ArrayList<>(2);
    indiceParents.add(selectOne(popFit, kTournament, rand));
    indiceParents.add(selectOne(popFit, kTournament, rand));

    return indiceParents;
  }

  public static int selectOne(List<Double> popFit, int kTournament, Random rand) {
    validateParameters(popFit, kTournament);

    int nPop = popFit.size();
    int effectiveK = Math.min(kTournament, nPop);

    // Sample without replacement using HashSet
    Set<Integer> candidates = sampleWithoutReplacement(nPop, effectiveK, rand);

    // Find the best individual among candidates
    int bestInd = -1;
    double bestFit = Double.NEGATIVE_INFINITY;

    for (int idx : candidates) {
      double fit = popFit.get(idx);
      if (fit > bestFit) {
        bestFit = fit;
        bestInd = idx;
      }
    }

    return bestInd;
  }

  public static List<Integer> selectMultiple(
    List<Double> popFit,
    int kTournament,
    int numParents,
    Random rand) {

    validateParameters(popFit, kTournament);
    if (numParents <= 0) {
      throw new IllegalArgumentException("Number of parents must be positive, got: " + numParents);
    }

    List<Integer> result = new ArrayList<>(numParents);
    for (int i = 0; i < numParents; i++) {
      result.add(selectOne(popFit, kTournament, rand));
    }
    return result;
  }

  // ==========================================================
  // Helper methods
  // ==========================================================

  private static void validateParameters(List<Double> popFit, int kTournament) {
    if (popFit == null) {
      throw new IllegalArgumentException("Population fitness cannot be null");
    }
    if (popFit.isEmpty()) {
      throw new IllegalArgumentException("Population fitness cannot be empty");
    }
    if (kTournament <= 0) {
      throw new IllegalArgumentException("Tournament size must be positive, got: " + kTournament);
    }
  }

  private static Set<Integer> sampleWithoutReplacement(int nPop, int k, Random rand) {
    Set<Integer> selected = new HashSet<>(k);

    if (k >= nPop / 2) {
      // When k is large, use Fisher-Yates style approach
      List<Integer> indices = new ArrayList<>(nPop);
      for (int i = 0; i < nPop; i++) {
        indices.add(i);
      }
      for (int i = 0; i < k; i++) {
        int j = i + rand.nextInt(nPop - i);
        selected.add(indices.get(j));
        indices.set(j, indices.get(i));
      }
    } else {
      // When k is small, use rejection sampling
      while (selected.size() < k) {
        selected.add(rand.nextInt(nPop));
      }
    }

    return selected;
  }
}