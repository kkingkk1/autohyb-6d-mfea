package core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import common.AlgorithmConstants;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;

class HybCecOperationOrderRegressionTest {

  @Test
  void sequentialOperationDoesNotUseAdaptiveCreditRouting() {
    HybCec hybCec = createHybCec(AlgorithmConstants.OPERATION_ORDER_SEQUENTIAL);

    hybCec.updateEnableParentsForTest();

    assertEquals(List.of(true, true), hybCec.getState().getEnableParents());
  }

  @Test
  void adaptiveCreditRoutesToParent1WhenCreditRatioIsOne() {
    HybCec hybCec = createHybCec(AlgorithmConstants.OPERATION_ORDER_ADAPTIVE_CREDIT);

    hybCec.setAdaptiveCreditRatioForTest(1.0);
    hybCec.updateEnableParentsForTest();

    assertEquals(List.of(true, false), hybCec.getState().getEnableParents());
  }

  @Test
  void adaptiveCreditRoutesToParent2WhenCreditRatioIsZero() {
    HybCec hybCec = createHybCec(AlgorithmConstants.OPERATION_ORDER_ADAPTIVE_CREDIT);

    hybCec.setAdaptiveCreditRatioForTest(0.0);
    hybCec.updateEnableParentsForTest();

    assertEquals(List.of(false, true), hybCec.getState().getEnableParents());
  }

  @Test
  void adaptiveCreditOrderUpdatesRoutingRatio() throws Exception {
    HybCec hybCec = createHybCec(AlgorithmConstants.OPERATION_ORDER_ADAPTIVE_CREDIT);
    hybCec.getState().setEnableParents(List.of(true, false));
    hybCec.getState().setGbestFit(10.0);
    hybCec.getState().addIterationEvalCostParent1(1);

    invokeRecordAdaptiveCredit(hybCec, 0.0);

    assertEquals(1.0, hybCec.getState().getCreditRatioParent1(), 1.0e-6);
  }

  @Test
  void periodicSwitchUsesIterationBudgetPhases() {
    HybCec hybCec = createHybCec(AlgorithmConstants.OPERATION_ORDER_PERIODIC_SWITCH);

    hybCec.getState().setItera(0);
    hybCec.updateEnableParentsForTest();
    assertEquals(List.of(true, false), hybCec.getState().getEnableParents());

    hybCec.getState().setItera(1);
    hybCec.updateEnableParentsForTest();
    assertEquals(List.of(false, true), hybCec.getState().getEnableParents());
  }

  @Test
  void processNoImprovementSwitchUsesIterationBudgetSinceLastProcessProgress() {
    HybCec hybCec = createHybCec(AlgorithmConstants.OPERATION_ORDER_PROCESS_NO_IMPROVEMENT_SWITCH);

    hybCec.getState().setItera(0);
    hybCec.setLastProcessImproveIteraForTest(0);
    hybCec.updateEnableParentsForTest();
    assertEquals(List.of(true, false), hybCec.getState().getEnableParents());

    hybCec.getState().setItera(1);
    hybCec.updateEnableParentsForTest();
    assertEquals(List.of(false, true), hybCec.getState().getEnableParents());
  }

  @Test
  void diversitySwitchRoutesToParent2WhenCachedDiversityIsLow() {
    HybCec hybCec = createHybCec(AlgorithmConstants.OPERATION_ORDER_DIVERSITY_SWITCH);

    hybCec.getState().setItera(0);
    hybCec.setLastNormalizedDiversityForTest(0.0);
    hybCec.updateEnableParentsForTest();

    assertEquals(List.of(false, true), hybCec.getState().getEnableParents());
  }

  @Test
  void normalizedPopulationDiversityUsesContinuousPopulationSpread() {
    HybCec hybCec = createHybCec(AlgorithmConstants.OPERATION_ORDER_DIVERSITY_SWITCH);

    double diversity = hybCec.computeNormalizedPopulationDiversityForTest();

    assertEquals(0.001138550085106622, diversity, 1.0e-12);
  }

  private HybCec createHybCec(int operationOrder) {
    List<double[]> popIni = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      popIni.add(new double[] {i + 0.1, i + 0.2});
    }

    return new HybCec(
      AlgorithmConstants.ALGORITHM_INDEX_GA,
      AlgorithmConstants.ALGORITHM_INDEX_PSO,
      2,
      6,
      new int[] {
        AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
        AlgorithmConstants.UPDATE_RATIO_CODE_MIXED_SUBSET
      },
      AlgorithmConstants.UPDATE_RATIO_CODE_FULL,
      operationOrder,
      AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PERMUTATION,
      20,
      null,
      popIni,
      new Random(42L),
      InsertionMode.GREEDY
    );
  }

  private void invokeRecordAdaptiveCredit(HybCec hybCec, double gbestBeforeGeneration) throws Exception {
    Method method = HybCec.class.getDeclaredMethod("recordAdaptiveCredit", double.class);
    method.setAccessible(true);
    method.invoke(hybCec, gbestBeforeGeneration);
  }
}
