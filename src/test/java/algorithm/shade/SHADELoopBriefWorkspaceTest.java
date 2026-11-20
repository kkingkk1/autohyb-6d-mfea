package algorithm.shade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import algorithm.workspace.SHADEWorkspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SHADELoopBriefWorkspaceTest {

  @Test
  void runIntoMatchesLegacyRunForFullBinomialPath() {
    List<double[]> population = new ArrayList<>();
    population.add(new double[] {-1.0, -0.5, 0.2});
    population.add(new double[] {0.5, 1.0, -1.2});
    population.add(new double[] {-1.5, 0.25, 1.25});
    population.add(new double[] {1.75, -1.25, 0.75});
    population.add(new double[] {-0.25, 1.25, -1.75});
    population.add(new double[] {1.5, -0.75, 0.5});
    List<Double> fitness = List.of(1.0, 4.0, 2.0, 3.0, 5.0, 2.5);
    int[] selected = IntStream.range(0, population.size()).toArray();

    SHADELoopBrief.SHADEConfig legacyConfig = SHADELoopBrief.SHADEConfig.forPopulationSize(population.size());
    legacyConfig.probEigenCrossover = 0.0;
    SHADELoopBrief.SHADEConfig workspaceConfig = SHADELoopBrief.SHADEConfig.forPopulationSize(population.size());
    workspaceConfig.probEigenCrossover = 0.0;

    SHADELoopBrief.SHADEResult legacy = SHADELoopBrief.run(
      population,
      fitness,
      selected,
      0,
      0,
      100,
      legacyConfig,
      new ArrayList<>(),
      new ArrayList<>(),
      new ArrayList<>(),
      new ArrayList<>(),
      new Random(7L)
    );

    SHADELoopBrief.SHADEResult workspace = SHADELoopBrief.runInto(
      population,
      fitness,
      selected,
      0,
      0,
      100,
      workspaceConfig,
      new ArrayList<>(),
      new ArrayList<>(),
      new ArrayList<>(),
      new ArrayList<>(),
      new Random(7L),
      new SHADEWorkspace()
    );

    assertEquals(legacy.shadeGG, workspace.shadeGG);
    assertEquals(legacy.flag1Shade, workspace.flag1Shade);
    assertEquals(legacy.flag2Shade, workspace.flag2Shade);
    assertArrayEquals(legacy.shadeSF, workspace.shadeSF);
    assertArrayEquals(legacy.shadeCR, workspace.shadeCR);
    assertArrayEquals(legacy.shadeFreq, workspace.shadeFreq);
    assertEquals(legacy.newPop.size(), workspace.newPop.size());
    for (int i = 0; i < legacy.newPop.size(); i++) {
      assertArrayEquals(legacy.newPop.get(i), workspace.newPop.get(i));
    }
  }

  @Test
  void runIntoMatchesLegacyRunForPartialBinomialPath() {
    List<double[]> population = new ArrayList<>();
    population.add(new double[] {-1.0, -0.5, 0.2});
    population.add(new double[] {0.5, 1.0, -1.2});
    population.add(new double[] {-1.5, 0.25, 1.25});
    population.add(new double[] {1.75, -1.25, 0.75});
    population.add(new double[] {-0.25, 1.25, -1.75});
    population.add(new double[] {1.5, -0.75, 0.5});
    List<Double> fitness = List.of(1.0, 4.0, 2.0, 3.0, 5.0, 2.5);
    int[] selected = new int[] {1, 3, 4};

    SHADELoopBrief.SHADEConfig legacyConfig = SHADELoopBrief.SHADEConfig.forPopulationSize(population.size());
    legacyConfig.probEigenCrossover = 0.0;
    SHADELoopBrief.SHADEConfig workspaceConfig = SHADELoopBrief.SHADEConfig.forPopulationSize(population.size());
    workspaceConfig.probEigenCrossover = 0.0;

    SHADELoopBrief.SHADEResult legacy = SHADELoopBrief.run(
      population,
      fitness,
      selected,
      2,
      10,
      100,
      legacyConfig,
      new ArrayList<>(),
      new ArrayList<>(),
      new ArrayList<>(),
      new ArrayList<>(),
      new Random(17L)
    );

    SHADELoopBrief.SHADEResult workspace = SHADELoopBrief.runInto(
      population,
      fitness,
      selected,
      2,
      10,
      100,
      workspaceConfig,
      new ArrayList<>(),
      new ArrayList<>(),
      new ArrayList<>(),
      new ArrayList<>(),
      new Random(17L),
      new SHADEWorkspace()
    );

    assertEquals(legacy.shadeGG, workspace.shadeGG);
    assertEquals(legacy.flag1Shade, workspace.flag1Shade);
    assertEquals(legacy.flag2Shade, workspace.flag2Shade);
    assertArrayEquals(legacy.shadeSF, workspace.shadeSF);
    assertArrayEquals(legacy.shadeCR, workspace.shadeCR);
    assertArrayEquals(legacy.shadeFreq, workspace.shadeFreq);
    assertEquals(legacy.newPop.size(), workspace.newPop.size());
    for (int i = 0; i < legacy.newPop.size(); i++) {
      assertArrayEquals(legacy.newPop.get(i), workspace.newPop.get(i));
    }
  }
}
