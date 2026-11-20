package operator.mutation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import common.AlgorithmConstants;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MutationHybTest {

  @Test
  void mutationAtSurvivorSlotOnlyTouchesSurvivorStrategy() {
    int[] baseConfig = {4, 1, 0, 0, 0, 0};
    long seed = 31L;

    int[] mutated = MutationHyb.mutationHyb(
      baseConfig,
      new int[] {5},
      AlgorithmConstants.HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT,
      AlgorithmConstants.UPDATE_RATIO_CODE_COUNT,
      AlgorithmConstants.OPERATION_ORDER_COUNT,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_COUNT,
      new Random(seed)
    );

    int expectedSurvivorStrategy = new Random(seed).nextInt(AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_COUNT);

    assertEquals(expectedSurvivorStrategy, mutated[5]);
    assertArrayEquals(new int[] {4, 1, 0, 0, 0, 0}, baseConfig);
    assertArrayEquals(new int[] {4, 1, 0, 0, 0, mutated[5]}, mutated);
  }
}
