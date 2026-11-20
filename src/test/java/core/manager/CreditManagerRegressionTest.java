package core.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreditManagerRegressionTest {

  @Test
  void rewardPerCostDominatesAdaptiveRatio() {
    CreditManager manager = new CreditManager(3);

    manager.recordGeneration(10.0, 10, 3.0, 1);

    assertEquals(0.25, manager.getParent1Ratio(), 1.0e-6);
    assertTrue(manager.getParent2Credit() > manager.getParent1Credit());
  }

  @Test
  void slidingWindowDropsStaleGenerations() {
    CreditManager manager = new CreditManager(2);

    manager.recordGeneration(8.0, 1, 0.0, 1);
    double firstRatio = manager.getParent1Ratio();

    manager.recordGeneration(8.0, 1, 0.0, 1);
    manager.recordGeneration(0.0, 1, 9.0, 1);

    assertTrue(firstRatio > manager.getParent1Ratio());
    assertTrue(manager.getParent1Ratio() < 0.5);
  }
}
