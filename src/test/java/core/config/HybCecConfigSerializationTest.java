package core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import algorithm.abc.ABC;
import algorithm.aco.ACOGaussGenerate;
import algorithm.alns.ALNS;
import algorithm.de.DEConfig;
import algorithm.eda.EDAGaussSam;
import algorithm.ga.GeneticAlgorithmNoSelect;
import algorithm.pso.PSO;
import algorithm.sa.SACont;
import common.BoundaryHandler;
import java.nio.file.Path;
import java.util.Properties;
import operator.insertion.InsertionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybCecConfigSerializationTest {

  @TempDir
  Path tempDir;

  @Test
  void phaseDOptionalConfigRoundTripsThroughProperties() throws Exception {
    HybCecConfig config = new HybCecConfig();
    config.setParent1(0);
    config.setParent2(1);
    config.setD(3);
    config.setNPop(4);
    config.setOL(new int[] {0, 0});
    config.setOLRand(0);
    config.setOO(0);
    config.setStrSurvive(0);
    config.setMaxItera(10);
    config.setPsoConfig(new PSO.PSOConfig(
      new double[] {1.7, 1.3},
      new double[] {0.9, 0.8},
      80.0,
      -80.0,
      750.0,
      -750.0,
      BoundaryHandler.BoundaryMode.SPECULAR_REFLECTION,
      0.2,
      true,
      0.08,
      PSO.PSOConfig.Topology.GLOBAL_BEST,
      2.2,
      0.8,
      0.8,
      2.2));
    config.setGaConfig(new GeneticAlgorithmNoSelect.GAConfig(
      0.8,
      0.2,
      2,
      0.15,
      750.0,
      -750.0,
      1,
      GeneticAlgorithmNoSelect.GAConfig.ElitismScope.SELECT_LOCAL,
      0.25,
      true,
      0.12,
      0.08,
      0.10,
      0.6,
      99));
    config.setEdaConfig(new EDAGaussSam.EDAConfig(
      0.3,
      1.0,
      750.0,
      -750.0,
      0.002,
      1.8,
      7));
    config.setAcoConfig(new ACOGaussGenerate.ACOConfig(
      0.8,
      0.35,
      0.5,
      750.0,
      -750.0,
      0.003));
    config.setAbcConfig(new ABC.ABCConfig(
      100,
      750.0,
      -750.0,
      2.0,
      1.0));
    config.setDeConfig(new DEConfig(
      new double[] {0.1, 0.05},
      new double[] {0.2, 0.1},
      0.3,
      750.0,
      -750.0,
      DEConfig.BoundaryPolicy.REFLECT));
    config.setSaConfig(new SACont.SAConfig(
      5,
      0.97,
      0.5,
      SACont.SA_TEMPERATURE_SCHEDULE_LOGARITHMIC,
      0.95,
      3.0,
      750.0,
      -750.0,
      true));
    config.setAlnsConfig(new ALNS.ALNSConfig(
      3,
      3,
      1,
      0.15,
      30.0,
      20.0,
      10.0,
      0.5,
      0.05,
      100.0,
      0.99975,
      -750.0,
      750.0,
      InsertionMode.GREEDY,
      true));

    Path file = tempDir.resolve("hybcec.properties");
    config.saveToPropertiesFile(file.toString());

    HybCecConfig loaded = HybCecConfig.loadFromPropertiesFile(file.toString());

    assertEquals(DEConfig.BoundaryPolicy.REFLECT, loaded.getDeConfig().boundaryPolicy);
    assertEquals(0.003, loaded.getAcoConfig().minSigmaRatio, 1.0e-12);
    assertEquals(100, loaded.getAbcConfig().numNoImp);
    assertEquals(2.0, loaded.getAbcConfig().phiScale, 1.0e-12);
    assertEquals(1.0, loaded.getAbcConfig().phiOffset, 1.0e-12);
    assertEquals(1.8, loaded.getEdaConfig().stagnationBoostRatio, 1.0e-12);
    assertEquals(7, loaded.getEdaConfig().stagnationGenerations);
    assertEquals(
      GeneticAlgorithmNoSelect.GAConfig.ElitismScope.SELECT_LOCAL,
      loaded.getGaConfig().elitismScope);
    assertEquals(0.25, loaded.getGaConfig().maxMutationDimRatio, 1.0e-12);
    assertTrue(loaded.getGaConfig().adaptiveMutation);
    assertEquals(0.12, loaded.getGaConfig().probMutationLate, 1.0e-12);
    assertEquals(0.08, loaded.getGaConfig().sigmaScaleLate, 1.0e-12);
    assertEquals(0.10, loaded.getGaConfig().maxMutationDimRatioLate, 1.0e-12);
    assertEquals(0.6, loaded.getGaConfig().mutationDecayStartRatio, 1.0e-12);
    assertEquals(99, loaded.getGaConfig().maxGeneration);
    assertTrue(loaded.getPsoConfig().enableTVAC);
    assertEquals(2.2, loaded.getPsoConfig().tvacC1Start, 1.0e-12);
    assertEquals(0.8, loaded.getPsoConfig().tvacC1End, 1.0e-12);
    assertEquals(0.8, loaded.getPsoConfig().tvacC2Start, 1.0e-12);
    assertEquals(2.2, loaded.getPsoConfig().tvacC2End, 1.0e-12);
    assertEquals(0.08, loaded.getPsoConfig().vMaxRatio, 1.0e-12);
    assertTrue(loaded.getSaConfig().adaptiveSigma);
    assertEquals(
      SACont.SA_TEMPERATURE_SCHEDULE_LOGARITHMIC,
      loaded.getSaConfig().temperatureSchedule);
    assertEquals(3.0, loaded.getSaConfig().temperatureLogOffset, 1.0e-12);
    assertTrue(loaded.getAlnsConfig().enableRegret2Repair);
  }

  @Test
  void missingOptionalPhaseDKeysUseDefaultSafeValues() throws Exception {
    Properties props = new Properties();
    props.setProperty("parent1", "0");
    props.setProperty("parent2", "1");
    props.setProperty("D", "3");
    props.setProperty("nPop", "4");
    props.setProperty("oL", "0,0");
    props.setProperty("oLRand", "0");
    props.setProperty("oO", "0");
    props.setProperty("strSurvive", "0");
    props.setProperty("maxItera", "10");
    props.setProperty("flagOO", "1,1,1,1,1,1,1,1");
    props.setProperty("pso.cognitiveCoefficient", "2.0,0.5");
    props.setProperty("pso.inertiaWeight", "0.9,0.4");
    props.setProperty("pso.vMaxLimit", "80.0");
    props.setProperty("pso.vMinLimit", "-80.0");
    props.setProperty("pso.upperBound", "750.0");
    props.setProperty("pso.lowerBound", "-750.0");
    props.setProperty("de.differentialWeight1", "0.1");
    props.setProperty("de.crossoverRate", "0.3");
    props.setProperty("de.upperBound", "750.0");
    props.setProperty("de.lowerBound", "-750.0");
    props.setProperty("aco.nSample", "0.8");
    props.setProperty("aco.intenseFactor", "0.35");
    props.setProperty("aco.deviateDistanceRatio", "0.5");
    props.setProperty("aco.upperBound", "750.0");
    props.setProperty("aco.lowerBound", "-750.0");
    props.setProperty("abc.numNoImp", "100");
    props.setProperty("abc.upperBound", "750.0");
    props.setProperty("abc.lowerBound", "-750.0");
    props.setProperty("sa.genSA", "5");
    props.setProperty("sa.freezeRate", "0.97");
    props.setProperty("sa.upperBound", "750.0");
    props.setProperty("sa.lowerBound", "-750.0");

    Path file = tempDir.resolve("legacy.properties");
    try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
      props.store(out, "legacy");
    }

    HybCecConfig loaded = HybCecConfig.loadFromPropertiesFile(file.toString());

    assertEquals(DEConfig.BoundaryPolicy.RANDOM_REINIT, loaded.getDeConfig().boundaryPolicy);
    assertEquals(
      ACOGaussGenerate.ACO_DEFAULT_MIN_SIGMA_RATIO,
      loaded.getAcoConfig().minSigmaRatio,
      1.0e-12);
    assertFalse(loaded.getAcoConfig().legacySigma);
    assertEquals(ABC.ABC_PHI_SCALE, loaded.getAbcConfig().phiScale, 1.0e-12);
    assertEquals(ABC.ABC_PHI_OFFSET, loaded.getAbcConfig().phiOffset, 1.0e-12);
    assertTrue(loaded.getPsoConfig().enableTVAC);
    assertEquals(PSO.PSO_TVAC_C1_START, loaded.getPsoConfig().tvacC1Start, 1.0e-12);
    assertEquals(PSO.PSO_TVAC_C1_END, loaded.getPsoConfig().tvacC1End, 1.0e-12);
    assertEquals(PSO.PSO_TVAC_C2_START, loaded.getPsoConfig().tvacC2Start, 1.0e-12);
    assertEquals(PSO.PSO_TVAC_C2_END, loaded.getPsoConfig().tvacC2End, 1.0e-12);
    assertEquals(PSO.PSO_BOUNDARY_MODE, loaded.getPsoConfig().boundaryMode);
    assertEquals(
      PSO.PSO_BOUNDARY_REPAIR_RATIO,
      loaded.getPsoConfig().boundaryRepairRatio,
      1.0e-12);
    assertFalse(loaded.getSaConfig().adaptiveSigma);
    assertEquals(
      SACont.SA_DEFAULT_TEMPERATURE_SCHEDULE,
      loaded.getSaConfig().temperatureSchedule);
    assertEquals(
      SACont.SA_DEFAULT_TEMPERATURE_LOG_OFFSET,
      loaded.getSaConfig().temperatureLogOffset,
      1.0e-12);
  }
}
