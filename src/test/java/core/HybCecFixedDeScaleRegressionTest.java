package core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import common.AlgorithmConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;

class HybCecFixedDeScaleRegressionTest {

  @Test
  void neutralRatioMutationDoesNotChangeDeConfig() {
    HybCec hybCec = createHybCec(AlgorithmConstants.ALGORITHM_INDEX_DE_RAND_1);

    double baselineF = hybCec.getDeConfig().getF1(0);
    hybCec.getState().setNeutralRatio(1.0);

    assertEquals(baselineF, hybCec.getDeConfig().getF1(0), 1.0e-9);
  }

  private HybCec createHybCec(int parent1) {
    List<double[]> popIni = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      popIni.add(new double[] {i + 0.1, i + 0.2});
    }

    return new HybCec(
      parent1,
      AlgorithmConstants.ALGORITHM_INDEX_GA,
      2,
      6,
      new int[] {
        AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
        AlgorithmConstants.UPDATE_RATIO_CODE_MIXED_SUBSET
      },
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
      AlgorithmConstants.OPERATION_ORDER_SEQUENTIAL,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PERMUTATION,
      20,
      null,
      popIni,
      new Random(42L),
      InsertionMode.GREEDY
    );
  }
}
