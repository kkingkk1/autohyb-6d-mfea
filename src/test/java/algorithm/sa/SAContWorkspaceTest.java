package algorithm.sa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import algorithm.workspace.SAWorkspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SAContWorkspaceTest {

  @Test
  void defaultConfigUsesTunedReferenceParameters() {
    SACont.SAConfig config = SACont.SAConfig.defaultConfig();

    assertEquals(5, config.genSA);
    assertEquals(0.99, config.freezeRate, 1.0e-12);
    assertEquals(0.5, config.temperature, 1.0e-12);
    assertEquals(SACont.SA_TEMPERATURE_SCHEDULE_LOGARITHMIC, config.temperatureSchedule);
    assertEquals(0.95, config.temperatureAlpha, 1.0e-12);
    assertEquals(3.0, config.temperatureLogOffset, 1.0e-12);
    assertFalse(config.adaptiveSigma);
  }

  @Test
  void defaultConfigReadsSystemProperties() {
    try {
      System.setProperty(SACont.SA_FAST_GENERATIONS_PROPERTY, "7");
      System.setProperty(SACont.SA_FREEZE_RATE_PROPERTY, "0.99");
      System.setProperty(SACont.SA_TEMPERATURE_PROPERTY, "0.5");
      System.setProperty(SACont.SA_TEMPERATURE_SCHEDULE_PROPERTY, "LOGARITHMIC");
      System.setProperty(SACont.SA_TEMPERATURE_ALPHA_PROPERTY, "0.98");
      System.setProperty(SACont.SA_TEMPERATURE_LOG_OFFSET_PROPERTY, "3.0");
      System.setProperty(SACont.SA_ADAPTIVE_SIGMA_PROPERTY, "true");

      SACont.SAConfig config = SACont.SAConfig.defaultConfig();

      assertEquals(7, config.genSA);
      assertEquals(0.99, config.freezeRate, 1.0e-12);
      assertEquals(0.5, config.temperature, 1.0e-12);
      assertEquals(SACont.SA_TEMPERATURE_SCHEDULE_LOGARITHMIC, config.temperatureSchedule);
      assertEquals(0.98, config.temperatureAlpha, 1.0e-12);
      assertEquals(3.0, config.temperatureLogOffset, 1.0e-12);
      assertTrue(config.adaptiveSigma);
    } finally {
      System.clearProperty(SACont.SA_FAST_GENERATIONS_PROPERTY);
      System.clearProperty(SACont.SA_FREEZE_RATE_PROPERTY);
      System.clearProperty(SACont.SA_TEMPERATURE_PROPERTY);
      System.clearProperty(SACont.SA_TEMPERATURE_SCHEDULE_PROPERTY);
      System.clearProperty(SACont.SA_TEMPERATURE_ALPHA_PROPERTY);
      System.clearProperty(SACont.SA_TEMPERATURE_LOG_OFFSET_PROPERTY);
      System.clearProperty(SACont.SA_ADAPTIVE_SIGMA_PROPERTY);
    }
  }

  @Test
  void workspacePathAllowsBestFitnessUpdates() throws Exception {
    List<double[]> population = new ArrayList<>();
    population.add(new double[] {0.0, 0.0});
    population.add(new double[] {1.0, 1.0});
    List<Double> fitness = new ArrayList<>(List.of(0.0, 2.0));
    List<double[]> sigma = new ArrayList<>();
    sigma.add(new double[] {0.1, 0.1});
    sigma.add(new double[] {0.1, 0.1});

    SACont.SAConfig config = new SACont.SAConfig(
      2,
      0.97,
      0.5,
      SACont.SA_TEMPERATURE_SCHEDULE_CONSTANT,
      SACont.SA_DEFAULT_TEMPERATURE_ALPHA,
      SACont.SA_DEFAULT_TEMPERATURE_LOG_OFFSET,
      10.0,
      -10.0,
      true);

    SACont.SAResult result = SACont.run(
      population,
      fitness,
      sigma,
      new int[] {0, 1},
      config,
      null,
      List.of(),
      new Random(3L),
      candidates -> {
        List<Double> scores = new ArrayList<>(candidates.size());
        for (double[] candidate : candidates) {
          scores.add(candidate[0] + candidate[1] + 1.0);
        }
        return scores;
      },
      new SAWorkspace());

    assertEquals(2, result.sBest.size());
    assertEquals(2, result.sigma.size());
    assertTrue(result.fBest.get(0) >= 0.0);
  }
}
