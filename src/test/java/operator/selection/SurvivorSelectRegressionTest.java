package operator.selection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import common.AlgorithmConstants;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SurvivorSelectRegressionTest {

  @Test
  void strategyCodeThreeUsesRankBasedSelection() {
    List<double[]> offspringPop = List.of(
      new double[] {10.0},
      new double[] {20.0},
      new double[] {30.0}
    );
    List<double[]> parentPop = List.of(
      new double[] {40.0},
      new double[] {50.0},
      new double[] {60.0}
    );
    List<Double> offspringFit = List.of(1.0, 2.0, 3.0);
    List<Double> parentFit = List.of(4.0, 5.0, 6.0);

    SurvivorSelect.SurvivorSelectResult viaDispatch = SurvivorSelect.survivorSelect(
      offspringPop,
      parentPop,
      offspringFit,
      parentFit,
      3,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_RANK_BASED,
      new Random(7L)
    );
    SurvivorSelect.SurvivorSelectResult direct = SurvivorSelect.survivorSelectRankBased(
      offspringPop,
      parentPop,
      offspringFit,
      parentFit,
      3,
      new Random(7L)
    );

    assertIterableEquals(direct.popFit, viaDispatch.popFit);
    for (int i = 0; i < direct.pop.size(); i++) {
      assertArrayEquals(direct.pop.get(i), viaDispatch.pop.get(i), 1.0e-9);
    }
  }

  @Test
  void newSurvivorNamesKeepOldAliasesStable() {
    assertEquals(
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_ELITIST_BEST_N,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PERMUTATION);
    assertEquals(
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_TOURNAMENT,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_CHAMPION);
    assertEquals(
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PAIRWISE_GREEDY,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_SERIAL_NUMBER);
    assertEquals(
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_RANK_WEIGHTED,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_RANK_BASED);
  }

  @Test
  void elitistBestNCanReorderSubsetUnlikePairwiseGreedy() {
    List<double[]> offspringPop = List.of(
      new double[] {10.0},
      new double[] {20.0}
    );
    List<double[]> parentPop = List.of(
      new double[] {30.0},
      new double[] {40.0}
    );
    List<Double> offspringFit = List.of(3.0, 1.0);
    List<Double> parentFit = List.of(2.0, 4.0);

    SurvivorSelect.SurvivorSelectResult elitist = SurvivorSelect.survivorSelect(
      offspringPop,
      parentPop,
      offspringFit,
      parentFit,
      2,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_ELITIST_BEST_N,
      new Random(7L)
    );
    SurvivorSelect.SurvivorSelectResult pairwise = SurvivorSelect.survivorSelect(
      offspringPop,
      parentPop,
      offspringFit,
      parentFit,
      2,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PAIRWISE_GREEDY,
      new Random(7L)
    );

    assertIterableEquals(List.of(4.0, 3.0), elitist.popFit);
    assertIterableEquals(List.of(3.0, 4.0), pairwise.popFit);
  }

  @Test
  void removedNewPopOnlyStrategyNowThrowsForUnsupportedCode() {
    assertThrows(IllegalArgumentException.class, () -> SurvivorSelect.survivorSelect(
      List.of(new double[] {1.0}),
      List.of(new double[] {2.0}),
      List.of(1.0),
      List.of(2.0),
      1,
      4,
      new Random(1L)
    ));
  }
}
