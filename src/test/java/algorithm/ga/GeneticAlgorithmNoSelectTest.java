package algorithm.ga;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import algorithm.workspace.GAWorkspace;
import org.junit.jupiter.api.Test;

class GeneticAlgorithmNoSelectTest {

  @Test
  void runProducesBoundedOffspringWithoutMutatingInputPopulation() {
    List<double[]> population = new ArrayList<>();
    population.add(new double[] {-1.0, -0.5, 0.0});
    population.add(new double[] {0.5, 1.0, 1.5});
    population.add(new double[] {-1.5, 0.25, 1.25});
    population.add(new double[] {1.75, -1.25, 0.75});

    double[][] original = population.stream()
      .map(double[]::clone)
      .toArray(double[][]::new);

    GeneticAlgorithmNoSelect.GAConfig config = new GeneticAlgorithmNoSelect.GAConfig(
      1.0,
      1.0,
      2,
      0.2,
      2.0,
      -2.0,
      0
    );

    List<double[]> offspring = GeneticAlgorithmNoSelect.run(
      population,
      new int[] {0, 1, 2, 3},
      Arrays.asList(1.0, 4.0, 2.0, 3.0),
      config,
      0,
      new Random(42L)
    );

    assertEquals(4, offspring.size());
    for (int i = 0; i < population.size(); i++) {
      assertArrayEquals(original[i], population.get(i));
    }
    for (double[] child : offspring) {
      assertNotSame(population.get(0), child);
      assertEquals(3, child.length);
      for (double value : child) {
        assertTrue(value >= -2.0 && value <= 2.0);
      }
    }
  }

  @Test
  void runIntoMatchesLegacyRunForSameSeed() {
    List<double[]> population = new ArrayList<>();
    population.add(new double[] {-1.0, -0.5, 0.0, 0.5});
    population.add(new double[] {0.5, 1.0, 1.5, -1.5});
    population.add(new double[] {-1.5, 0.25, 1.25, -0.75});
    population.add(new double[] {1.75, -1.25, 0.75, 0.25});
    population.add(new double[] {-0.25, 1.25, -1.75, 1.5});

    GeneticAlgorithmNoSelect.GAConfig config = new GeneticAlgorithmNoSelect.GAConfig(
      0.8,
      0.7,
      3,
      0.2,
      2.0,
      -2.0,
      1,
      GeneticAlgorithmNoSelect.GAConfig.ElitismScope.SELECT_LOCAL,
      0.75
    );
    int[] selectIndices = {0, 2, 3, 4};
    double[] fitness = {1.0, 4.0, 2.0, 3.0, 5.0};

    List<double[]> legacy = GeneticAlgorithmNoSelect.run(
      population, selectIndices, fitness, config, new Random(123L));
    List<double[]> workspace = GeneticAlgorithmNoSelect.runInto(
      population, selectIndices, fitness, config, new Random(123L), new GAWorkspace());

    assertEquals(legacy.size(), workspace.size());
    for (int i = 0; i < legacy.size(); i++) {
      assertArrayEquals(legacy.get(i), workspace.get(i));
    }
  }

  @Test
  void adaptiveMutationScheduleDecaysAfterConfiguredStartRatio() {
    GeneticAlgorithmNoSelect.GAConfig config = new GeneticAlgorithmNoSelect.GAConfig(
      0.8,
      0.15,
      3,
      0.2,
      2.0,
      -2.0,
      1,
      GeneticAlgorithmNoSelect.GAConfig.ElitismScope.SELECT_LOCAL,
      0.1,
      true,
      0.10,
      0.1,
      0.05,
      0.5,
      101
    );

    assertEquals(0.15, config.effectiveProbMutation(0), 1.0e-12);
    assertEquals(0.2, config.effectiveSigmaScale(50), 1.0e-12);
    assertEquals(0.075, config.effectiveMaxMutationDimRatio(75), 1.0e-12);
    assertEquals(0.10, config.effectiveProbMutation(100), 1.0e-12);
    assertEquals(0.1, config.effectiveSigmaScale(100), 1.0e-12);
    assertEquals(0.05, config.effectiveMaxMutationDimRatio(100), 1.0e-12);
  }

  @Test
  void fixedMutationScheduleIgnoresLateValues() {
    GeneticAlgorithmNoSelect.GAConfig config = new GeneticAlgorithmNoSelect.GAConfig(
      0.8,
      0.15,
      3,
      0.2,
      2.0,
      -2.0,
      1,
      GeneticAlgorithmNoSelect.GAConfig.ElitismScope.SELECT_LOCAL,
      0.1,
      false,
      0.10,
      0.1,
      0.05,
      0.5,
      101
    );

    assertEquals(0.15, config.effectiveProbMutation(100), 1.0e-12);
    assertEquals(0.2, config.effectiveSigmaScale(100), 1.0e-12);
    assertEquals(0.1, config.effectiveMaxMutationDimRatio(100), 1.0e-12);
  }

  @Test
  void runIntoSupportsReusedWorkspaceAfterPopulationShrink() {
    GAWorkspace workspace = new GAWorkspace();
    GeneticAlgorithmNoSelect.GAConfig config = GeneticAlgorithmNoSelect.GAConfig.defaultConfig();

    List<double[]> largePopulation = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      largePopulation.add(new double[] {i, i + 0.5});
    }
    GeneticAlgorithmNoSelect.runInto(
      largePopulation,
      new int[] {0, 1, 2, 3, 4, 5},
      Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
      config,
      0,
      new Random(7L),
      workspace);

    List<double[]> shrunkPopulation = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      shrunkPopulation.add(new double[] {i, -i});
    }

    List<double[]> offspring = GeneticAlgorithmNoSelect.runInto(
      shrunkPopulation,
      new int[] {0, 1, 2, 3},
      Arrays.asList(1.0, 2.0, 3.0, 4.0),
      config,
      1,
      new Random(11L),
      workspace);

    assertEquals(4, offspring.size());
  }
}
