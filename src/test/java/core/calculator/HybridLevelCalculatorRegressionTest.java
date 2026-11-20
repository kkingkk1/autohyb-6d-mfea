package core.calculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import common.AlgorithmConstants;
import core.state.HybCecState;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HybridLevelCalculatorRegressionTest {

  @Test
  void crossLevelHistoricalBestTargetsActualGbestRowInsteadOfFirstRow() {
    HybCecState state = new HybCecState();
    state.setNPop(4);
    state.setPop(List.of(
      new double[] {0.1, 0.2},
      new double[] {0.3, 0.4},
      new double[] {0.9, 0.8},
      new double[] {0.5, 0.6}
    ));
    state.setPopFit(List.of(1.0, 2.0, 9.0, 3.0));
    state.setGbestSolution(new double[] {0.9, 0.8});
    state.setOLReal(new int[] {
      AlgorithmConstants.OPERATION_LEVEL_INDIVIDUAL,
      AlgorithmConstants.OPERATION_LEVEL_POPULATION
    });
    state.setOOReal(AlgorithmConstants.OPERATION_ORDER_REAL_SERIAL);

    int[] oL = {
      AlgorithmConstants.UPDATE_RATIO_CODE_LOCAL_SEARCH,
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL
    };
    HybridLevelCalculator calculator = new HybridLevelCalculator(
      state,
      2,
      oL,
      100,
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
      AlgorithmConstants.OPERATION_ORDER_SEQUENTIAL,
      new Random(42L)
    );

    int updatedCount = calculator.determineHybridLevel1();

    assertEquals(1, updatedCount);
    assertFalse(state.getIndMask1().get(0));
    assertTrue(state.getIndMask1().get(2));
    assertTrue(state.getUnitMask1().get(2)[0]);
    assertTrue(state.getUnitMask1().get(2)[1]);
  }

  @Test
  void worstRepairTargetsCurrentWorstFitnessRow() {
    HybCecState state = new HybCecState();
    state.setNPop(4);
    state.setPop(List.of(
      new double[] {0.1, 0.2},
      new double[] {0.3, 0.4},
      new double[] {0.9, 0.8},
      new double[] {0.5, 0.6}
    ));
    state.setPopFit(List.of(1.0, 2.0, 9.0, 3.0));
    state.setGbestSolution(new double[] {0.9, 0.8});
    state.setOLReal(new int[] {
      AlgorithmConstants.OPERATION_LEVEL_INDIVIDUAL,
      AlgorithmConstants.OPERATION_LEVEL_POPULATION
    });
    state.setOOReal(AlgorithmConstants.OPERATION_ORDER_REAL_SERIAL);

    int[] oL = {
      AlgorithmConstants.UPDATE_RATIO_CODE_WORST_REPAIR,
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL
    };
    HybridLevelCalculator calculator = new HybridLevelCalculator(
      state,
      2,
      oL,
      100,
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
      AlgorithmConstants.OPERATION_ORDER_SEQUENTIAL,
      new Random(42L)
    );

    int updatedCount = calculator.determineHybridLevel1();

    assertEquals(1, updatedCount);
    assertTrue(state.getIndMask1().get(0));
    assertFalse(state.getIndMask1().get(2));
    assertTrue(state.getUnitMask1().get(0)[0]);
    assertTrue(state.getUnitMask1().get(0)[1]);
  }
}
