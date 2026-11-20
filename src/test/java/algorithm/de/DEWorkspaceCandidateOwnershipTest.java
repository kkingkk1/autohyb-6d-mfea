package algorithm.de;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import algorithm.workspace.DEWorkspace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DEWorkspaceCandidateOwnershipTest {

  @Test
  void ownedCandidateCopiesRemainStableAcrossWorkspaceReuse() {
    List<double[]> population = population();
    int[] selected = {0, 1, 2, 3, 4, 5};
    DEConfig config = DEConfig.withTwoF(new double[] {0.6}, new double[] {0.4}, 0.8, 10.0, -10.0);
    DEWorkspace workspace = new DEWorkspace();

    List<double[]> borrowed = DERand2.runInto(population, selected, config, 0, new Random(21L), workspace);
    List<double[]> handedOff = ownedCopies(borrowed);
    double[][] expected = handedOff.stream()
      .map(double[]::clone)
      .toArray(double[][]::new);

    for (int i = 0; i < handedOff.size(); i++) {
      assertNotSame(borrowed.get(i), handedOff.get(i));
    }

    for (int repeat = 0; repeat < 3; repeat++) {
      DERand2.runInto(population, selected, config, 0, new Random(100L + repeat), workspace);
    }

    for (int i = 0; i < handedOff.size(); i++) {
      assertArrayEquals(expected[i], handedOff.get(i));
    }
    for (int i = 0; i < handedOff.size(); i++) {
      for (int j = i + 1; j < handedOff.size(); j++) {
        assertNotSame(handedOff.get(i), handedOff.get(j));
      }
    }
  }

  private static List<double[]> ownedCopies(List<double[]> borrowed) {
    List<double[]> owned = new ArrayList<>(borrowed.size());
    for (double[] row : borrowed) {
      owned.add(Arrays.copyOf(row, row.length));
    }
    return owned;
  }

  private static List<double[]> population() {
    List<double[]> population = new ArrayList<>();
    population.add(new double[] {-1.0, -0.5, 0.2, 0.7});
    population.add(new double[] {0.5, 1.0, -1.2, -0.3});
    population.add(new double[] {-1.5, 0.25, 1.25, 0.1});
    population.add(new double[] {1.75, -1.25, 0.75, -0.9});
    population.add(new double[] {-0.25, 1.25, -1.75, 0.4});
    population.add(new double[] {1.5, -0.75, 0.5, -0.6});
    return population;
  }
}
