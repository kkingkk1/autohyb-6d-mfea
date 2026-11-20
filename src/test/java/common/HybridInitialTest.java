package common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HybridInitialTest {

  @Test
  void seededParentsStayWithinShrunkParentPool() {
    int[][] seeded = HybridInitial.hybridInitial(
      AlgorithmConstants.HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT,
      AlgorithmConstants.UPDATE_RATIO_CODE_COUNT,
      AlgorithmConstants.OPERATION_ORDER_COUNT,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_COUNT,
      4,
      true);

    for (int i = 0; i < 4; i++) {
      assertTrue(seeded[i][0] >= 0 && seeded[i][0] < AlgorithmConstants.HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT);
      assertTrue(seeded[i][1] >= 0 && seeded[i][1] < AlgorithmConstants.HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT);
      assertNotEquals(seeded[i][0], seeded[i][1]);
    }
  }

  @Test
  void archivedEliteConfigsTakePriorityOverDefaultSeeds() {
    int[][] archivedSeeds = {
      {1, 2, 6, 5, 4, 3},
      {2, 3, 4, 3, 2, 1}
    };

    int[][] seeded = HybridInitial.hybridInitial(
      AlgorithmConstants.HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT,
      AlgorithmConstants.UPDATE_RATIO_CODE_COUNT,
      AlgorithmConstants.OPERATION_ORDER_COUNT,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_COUNT,
      4,
      true,
      archivedSeeds
    );

    assertArrayEquals(AlgorithmConstants.normalizeHybridConfig(archivedSeeds[0]), seeded[0]);
    assertArrayEquals(AlgorithmConstants.normalizeHybridConfig(archivedSeeds[1]), seeded[1]);
  }
}
