package algorithm.aco;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import algorithm.workspace.ACOWorkspace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import utils.FitnessUtils;

class ACOGaussGenerateTest {

  @Test
  void defaultConfigUsesFinalMhParameters() {
    ACOGaussGenerate.ACOConfig config = ACOGaussGenerate.ACOConfig.defaultConfig();

    assertEquals(0.8, config.nSample, 1.0e-12);
    assertEquals(0.35, config.intenseFactor, 1.0e-12);
    assertEquals(0.5, config.deviateDistanceRatio, 1.0e-12);
    assertEquals(0.001, config.minSigmaRatio, 1.0e-12);
    assertTrue(!config.legacySigma);
  }

  @Test
  void fastSigmaMatchesPairwiseDistanceFormula() {
    double[][] elite = {
      {-1.0, 0.25, 0.25},
      {0.5, -0.75, 1.25},
      {-1.5, 1.50, -0.25},
      {1.75, -1.25, 0.75},
      {0.5, 0.00, -1.75}
    };

    double[][] sigma = ACOGaussGenerate.calculateSigmaForTest(
      elite, 0.5, 4.0, 0.001, false, elite.length, elite[0].length, new ACOWorkspace());
    double[][] expected = bruteForceSigma(elite, 0.5, 4.0, 0.001);

    assertEquals(expected.length, sigma.length);
    for (int row = 0; row < expected.length; row++) {
      assertArrayEquals(expected[row], sigma[row], 1.0e-12);
    }
  }

  @Test
  void fastSigmaMatchesPairwiseFormulaOnRandomTiedValues() {
    Random random = new Random(123L);
    for (int trial = 0; trial < 20; trial++) {
      double[][] elite = new double[24][7];
      for (int row = 0; row < elite.length; row++) {
        for (int dim = 0; dim < elite[row].length; dim++) {
          double raw = -2.0 + 4.0 * random.nextDouble();
          elite[row][dim] = dim % 3 == 0 ? Math.rint(raw * 4.0) / 4.0 : raw;
        }
      }
      double[][] sigma = ACOGaussGenerate.calculateSigmaForTest(
        elite, 0.5, 4.0, 0.001, false, elite.length, elite[0].length, new ACOWorkspace());
      double[][] expected = bruteForceSigma(elite, 0.5, 4.0, 0.001);
      for (int row = 0; row < expected.length; row++) {
        assertArrayEquals(expected[row], sigma[row], 1.0e-12);
      }
    }
  }

  @Test
  void primitiveFitnessSortUsesDescendingFitnessAndIndexTieOrder() {
    List<Double> fitness = List.of(3.0, 6.0, 6.0, 2.0, 4.0, 4.0, 6.0, 1.0);
    int[] order = new int[fitness.size()];

    ACOGaussGenerate.sortFitnessIndexDescForTest(fitness, order, fitness.size());

    assertArrayEquals(new int[] {1, 2, 6, 4, 5, 0, 3, 7}, order);
  }

  @Test
  void primitiveFitnessSortMatchesExistingStableUtility() {
    Random random = new Random(456L);
    for (int trial = 0; trial < 30; trial++) {
      List<Double> fitness = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        double raw = Math.rint((-5.0 + random.nextDouble() * 10.0) * 2.0) / 2.0;
        fitness.add(raw);
      }

      int[] order = new int[fitness.size()];
      ACOGaussGenerate.sortFitnessIndexDescForTest(fitness, order, fitness.size());
      List<FitnessUtils.IndexFitnessPair> expected = FitnessUtils.sortIndexFitnessDesc(fitness);

      for (int i = 0; i < order.length; i++) {
        assertEquals(expected.get(i).getIndex(), order[i]);
      }
    }
  }

  @Test
  void runUnsafeMatchesValidatedRunForSameSeed() {
    List<double[]> population = samplePopulation();
    List<Double> fitness = List.of(1.0, 4.0, 2.0, 6.0, 3.0, 5.0);
    int[] selected = {0, 2, 3, 5};
    ACOGaussGenerate.ACOConfig config = new ACOGaussGenerate.ACOConfig(
      0.75, 0.35, 0.5, 2.0, -2.0, 0.001);

    List<double[]> validated = ACOGaussGenerate.run(
      population, selected, fitness, config, new Random(42L));
    List<double[]> unsafe = ACOGaussGenerate.runUnsafe(
      population, selected, fitness, config, new Random(42L), new ACOWorkspace());

    assertEquals(validated.size(), unsafe.size());
    for (int i = 0; i < validated.size(); i++) {
      assertArrayEquals(validated.get(i), unsafe.get(i));
    }
  }

  @Test
  void rankAwareRunMatchesInternalSortForSameSeed() {
    List<double[]> population = samplePopulation();
    List<Double> fitness = List.of(1.0, 4.0, 2.0, 6.0, 3.0, 5.0);
    int[] selected = {0, 2, 3, 5};
    int[] rank = {3, 5, 1, 4, 2, 0};
    ACOGaussGenerate.ACOConfig config = new ACOGaussGenerate.ACOConfig(
      0.75, 0.35, 0.5, 2.0, -2.0, 0.001);

    List<double[]> sorted = ACOGaussGenerate.runUnsafe(
      population, selected, fitness, config, new Random(17L), new ACOWorkspace());
    List<double[]> rankAware = ACOGaussGenerate.runUnsafe(
      population, selected, fitness, config, new Random(17L), new ACOWorkspace(), rank);

    assertEquals(sorted.size(), rankAware.size());
    for (int i = 0; i < sorted.size(); i++) {
      assertArrayEquals(sorted.get(i), rankAware.get(i));
    }
  }

  @Test
  void workspaceReusesDistributionWhenEliteReferencesStayUnchanged() {
    List<double[]> population = samplePopulation();
    List<Double> fitness = List.of(1.0, 4.0, 2.0, 6.0, 3.0, 5.0);
    int[] rank = {3, 5, 1, 4, 2, 0};
    ACOGaussGenerate.ACOConfig config = new ACOGaussGenerate.ACOConfig(
      0.5, 0.35, 0.5, 2.0, -2.0, 0.001);
    ACOWorkspace workspace = new ACOWorkspace();

    ACOGaussGenerate.runUnsafe(
      population, new int[] {0, 2}, fitness, config, new Random(7L), workspace, rank);
    long buildsAfterFirst = workspace.getDistributionBuildCountForTest();
    ACOGaussGenerate.runUnsafe(
      population, new int[] {4, 5}, fitness, config, new Random(11L), workspace, rank);

    assertEquals(1L, buildsAfterFirst);
    assertEquals(buildsAfterFirst, workspace.getDistributionBuildCountForTest());
  }

  @Test
  void workspaceReuseKeepsSamplingBoundedAcrossPopulationSizes() {
    ACOWorkspace workspace = new ACOWorkspace();
    ACOGaussGenerate.ACOConfig config = new ACOGaussGenerate.ACOConfig(
      0.8, 0.5, 1.0, 2.0, -2.0, 0.001);

    ACOGaussGenerate.runUnsafe(
      samplePopulation(),
      new int[] {0, 1, 2, 3, 4, 5},
      List.of(1.0, 4.0, 2.0, 6.0, 3.0, 5.0),
      config,
      new Random(7L),
      workspace);

    List<double[]> shrunk = samplePopulation().subList(0, 4);
    List<double[]> offspring = ACOGaussGenerate.runUnsafe(
      shrunk,
      new int[] {0, 2, 3},
      List.of(1.0, 4.0, 2.0, 6.0),
      config,
      new Random(11L),
      workspace);

    assertEquals(3, offspring.size());
    for (double[] child : offspring) {
      assertEquals(3, child.length);
      for (double value : child) {
        assertTrue(value >= -2.0 && value <= 2.0);
      }
    }
  }

  private static List<double[]> samplePopulation() {
    return new ArrayList<>(Arrays.asList(
      new double[] {-1.0, -0.5, 0.0},
      new double[] {0.5, 1.0, 1.5},
      new double[] {-1.5, 0.25, 1.25},
      new double[] {1.75, -1.25, 0.75},
      new double[] {-0.25, 1.25, -1.75},
      new double[] {1.5, -0.75, 0.5}
    ));
  }

  private static double[][] bruteForceSigma(
    double[][] elite,
    double deviateDistanceRatio,
    double searchRange,
    double minSigmaRatio) {
    int nPop = elite.length;
    int dimension = elite[0].length;
    double[][] sigma = new double[nPop][dimension];
    double minSigma = Math.max(0.0, searchRange) * minSigmaRatio;
    for (int row = 0; row < nPop; row++) {
      for (int dim = 0; dim < dimension; dim++) {
        double distanceSum = 0.0;
        for (int other = 0; other < nPop; other++) {
          if (other != row) {
            distanceSum += Math.abs(elite[row][dim] - elite[other][dim]);
          }
        }
        sigma[row][dim] = Math.max(
          minSigma,
          deviateDistanceRatio * distanceSum / (nPop - 1));
      }
    }
    return sigma;
  }
}
