package main;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MainMFEAMultiSceneTest {

  @Test
  void earlyStopWaitsForMinimumGeneration() {
    assertFalse(MainMFEAMultiScene.shouldEarlyStop(true, 9, 10, 10, 10));
    assertTrue(MainMFEAMultiScene.shouldEarlyStop(true, 10, 10, 10, 10));
  }

  @Test
  void earlyStopRequiresFullPatienceWindow() {
    assertFalse(MainMFEAMultiScene.shouldEarlyStop(true, 20, 10, 9, 10));
    assertTrue(MainMFEAMultiScene.shouldEarlyStop(true, 20, 10, 10, 10));
  }

  @Test
  void disabledEarlyStopNeverTriggers() {
    assertFalse(MainMFEAMultiScene.shouldEarlyStop(false, 20, 10, 100, 10));
  }
}
