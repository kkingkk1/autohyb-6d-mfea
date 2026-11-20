package main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import model.SchedulingPlan;
import org.junit.jupiter.api.Test;

class EvaluationHybTest {

  @Test
  void buildOptimizedPerRunResultUsesOptimizedResultDirectly() {
    SchedulingPlan optimizedPlan = new SchedulingPlan(List.of());
    optimizedPlan.setFitness(42.0);
    Map<Integer, SchedulingPlan> optimizedPlans = Map.of(2, optimizedPlan);
    Map<Integer, Integer> initialAssignment = Map.of(100, 1, 101, 1);
    Map<Integer, Integer> optimizedAssignment = Map.of(100, 2);

    EvaluationHyb.PerRunResult result = EvaluationHyb.buildOptimizedPerRunResult(
      3,
      initialAssignment,
      42.0,
      1,
      optimizedPlans,
      optimizedAssignment,
      List.of("initial"),
      List.of(1),
      List.of(0),
      List.of(42.0),
      25L
    );

    assertEquals(42.0, result.totalProfit);
    assertEquals(1, result.metrics.scheduledTaskCount);
    assertEquals(2, result.metrics.assignedTaskCount);
    assertEquals(optimizedAssignment, result.assignmentSnapshot);
    assertEquals(42.0, result.plansBySatellite.get(2).getFitness());
    assertNotSame(optimizedPlan, result.plansBySatellite.get(2));
  }

  @Test
  void runSeedLogIncludesSourceTableScenePerRunAndSeed() throws Exception {
    String previousSource = System.getProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY);
    String previousPath = System.getProperty(SceneSeedTable.TABLE_PATH_PROPERTY);
    String previousLog = System.getProperty(EvaluationHyb.RUN_SEED_LOG_PROPERTY);
    PrintStream previousOut = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, SceneSeedTable.NATIVE_410000_SOURCE);
      System.clearProperty(SceneSeedTable.TABLE_PATH_PROPERTY);
      System.setProperty(EvaluationHyb.RUN_SEED_LOG_PROPERTY, "true");
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

      Method resolveRunSeed = EvaluationHyb.class.getDeclaredMethod(
        "resolveRunSeed",
        String.class,
        int.class
      );
      resolveRunSeed.setAccessible(true);
      long seed = (long) resolveRunSeed.invoke(null, "500-5-0-0-24", 1);

      System.out.flush();
      long expectedSeed = 1103879922877097674L;
      assertEquals(expectedSeed, seed);
      assertEquals(
        "[RunSeed] source=native410000 table=scene-seeds-native410000.properties "
          + "scene=500-5-0-0-24 perRun=1 seedId=11 seed=" + expectedSeed
          + System.lineSeparator(),
        captured.toString(StandardCharsets.UTF_8)
      );
    } finally {
      System.setOut(previousOut);
      restoreProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, previousSource);
      restoreProperty(SceneSeedTable.TABLE_PATH_PROPERTY, previousPath);
      restoreProperty(EvaluationHyb.RUN_SEED_LOG_PROPERTY, previousLog);
    }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }
}
