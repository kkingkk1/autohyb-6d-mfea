package algorithm.de;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import algorithm.workspace.DEWorkspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DEWorkspaceTailVariantsTest {

  @Test
  void tailVariantRunIntoMatchesLegacyRunForSameSeed() {
    List<double[]> population = population();
    int[] selected = {0, 1, 2, 3, 4, 5};
    List<Double> fitness = List.of(1.0, 4.0, 2.0, 3.0, 5.0, 2.5);
    DEConfig config = DEConfig.withTwoF(new double[] {0.6}, new double[] {0.4}, 0.8, 2.0, -2.0);

    assertSameRows(
      DERand2.run(population, selected, config, 0, new Random(11L)),
      DERand2.runInto(population, selected, config, 0, new Random(11L), new DEWorkspace()));

    int bestIndex = 4;
    assertSameRows(
      DEBest2.runWithBest(population, selected, bestIndex, config, 0, new Random(12L)),
      DEBest2.runInto(population, selected, bestIndex, config, 0, new Random(12L), new DEWorkspace()));

    assertSameRows(
      DECurrentToRand.run(population, selected, config, 0, new Random(13L)),
      DECurrentToRand.runInto(population, selected, config, 0, new Random(13L), new DEWorkspace()));

    assertSameRows(
      DERandToBest.run(population, selected, bestIndex, config, 0, new Random(14L)),
      DERandToBest.runInto(population, selected, bestIndex, config, 0, new Random(14L), new DEWorkspace()));
  }

  private static List<double[]> population() {
    List<double[]> population = new ArrayList<>();
    population.add(new double[] {-1.0, -0.5, 0.2});
    population.add(new double[] {0.5, 1.0, -1.2});
    population.add(new double[] {-1.5, 0.25, 1.25});
    population.add(new double[] {1.75, -1.25, 0.75});
    population.add(new double[] {-0.25, 1.25, -1.75});
    population.add(new double[] {1.5, -0.75, 0.5});
    return population;
  }

  private static void assertSameRows(List<double[]> expected, List<double[]> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      assertArrayEquals(expected.get(i), actual.get(i));
    }
  }
}
