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
import algorithm.shade.SHADELoopBrief;
import common.TuningConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HybCecConfigManagerTest {

  @AfterEach
  void resetTuningProperties() {
    System.clearProperty("ga.adaptiveMutation");
    System.clearProperty("ga.probMutationLate");
    System.clearProperty("ga.sigmaScaleLate");
    System.clearProperty("ga.maxMutationDimRatioLate");
    System.clearProperty("ga.mutationDecayStartRatio");
    System.clearProperty("eda.kElite");
    System.clearProperty("eda.sigmaScale");
    System.clearProperty("eda.minSigmaRatio");
    System.clearProperty("eda.stagnationBoostRatio");
    System.clearProperty("eda.stagnationGenerations");
    System.clearProperty(SHADELoopBrief.SHADE_ARCHIVE_RATE_FAST_PROPERTY);
    System.clearProperty(SHADELoopBrief.SHADE_MEMORY_SIZE_PROPERTY);
    System.clearProperty(SHADELoopBrief.SHADE_PBEST_RATE_PROPERTY);
    System.clearProperty(SHADELoopBrief.SHADE_LEARN_PERIOD_PROPERTY);
    System.clearProperty(SHADELoopBrief.SHADE_CR_GAUSSIAN_STD_PROPERTY);
    System.clearProperty(SHADELoopBrief.SHADE_CAUCHY_SCALE_PROPERTY);
    System.clearProperty("pso.cognitiveCoefficient");
    System.clearProperty("pso.inertiaWeight");
    System.clearProperty("pso.inertiaStart");
    System.clearProperty("pso.inertiaEnd");
    System.clearProperty("pso.vMaxLimit");
    System.clearProperty("pso.vMinLimit");
    System.clearProperty("pso.boundaryMode");
    System.clearProperty("pso.boundaryRepairRatio");
    System.clearProperty("pso.enableTVAC");
    System.clearProperty("pso.tvacC1Start");
    System.clearProperty("pso.tvacC1End");
    System.clearProperty("pso.tvacC2Start");
    System.clearProperty("pso.tvacC2End");
    System.clearProperty("pso.vMaxRatio");
    System.clearProperty("pso.topology");
    System.clearProperty("abc.numNoImp");
    System.clearProperty("abc.upperBound");
    System.clearProperty("abc.lowerBound");
    System.clearProperty("abc.phiScale");
    System.clearProperty("abc.phiOffset");
    System.clearProperty("aco.nSample");
    System.clearProperty("aco.intenseFactor");
    System.clearProperty("aco.deviateDistanceRatio");
    System.clearProperty("aco.minSigmaRatio");
    System.clearProperty("aco.legacySigma");
    System.clearProperty(SACont.SA_FAST_GENERATIONS_PROPERTY);
    System.clearProperty(SACont.SA_FREEZE_RATE_PROPERTY);
    System.clearProperty(SACont.SA_TEMPERATURE_PROPERTY);
    System.clearProperty(SACont.SA_TEMPERATURE_SCHEDULE_PROPERTY);
    System.clearProperty(SACont.SA_TEMPERATURE_ALPHA_PROPERTY);
    System.clearProperty(SACont.SA_TEMPERATURE_LOG_OFFSET_PROPERTY);
    System.clearProperty(SACont.SA_ADAPTIVE_SIGMA_PROPERTY);
    System.clearProperty("de.fSchedule");
    System.clearProperty("de.differentialWeight1");
    System.clearProperty("de.differentialWeight2");
    System.clearProperty("de.fStatic");
    System.clearProperty("de.fMax");
    System.clearProperty("de.fMin");
    System.clearProperty("de.sigmoidP0");
    System.clearProperty("de.sigmoidK");
    System.clearProperty("de.crossoverRate");
    System.clearProperty("de.boundaryPolicy");
    System.clearProperty("alns.innerIterations");
    System.clearProperty("alns.weightUpdateInterval");
    System.clearProperty("alns.minDestroyCount");
    System.clearProperty("alns.maxDestroyRatio");
    System.clearProperty("alns.sigmaGlobal");
    System.clearProperty("alns.sigmaLocal");
    System.clearProperty("alns.sigmaBad");
    System.clearProperty("alns.reactionFactor");
    System.clearProperty("alns.initialTemperatureRatio");
    System.clearProperty("alns.maxInitialTemperature");
    System.clearProperty("alns.coolingRate");
    System.clearProperty("alns.insertionMode");
    System.clearProperty("alns.enableRegret2Repair");
    System.clearProperty("alns.destroyWeights");
    System.clearProperty("alns.repairWeights");
    TuningConfig.reload();
  }

  @Test
  void gaAdaptiveMutationUsesGenerationScaleNotEvaluationBudgetScale() {
    System.setProperty("ga.adaptiveMutation", "true");
    System.setProperty("ga.probMutationLate", "0.10");
    System.setProperty("ga.sigmaScaleLate", "0.10");
    System.setProperty("ga.maxMutationDimRatioLate", "0.05");
    System.setProperty("ga.mutationDecayStartRatio", "0.5");
    TuningConfig.reload();

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.GA.getIndex()] = 1;
    manager.initialize(flagPar, 27000, 270);

    GeneticAlgorithmNoSelect.GAConfig config = manager.getGaConfig();
    assertTrue(config.adaptiveMutation);
    assertEquals(100, config.maxGeneration);
    assertEquals(0.15, config.effectiveProbMutation(49), 1.0e-12);
    assertEquals(0.10, config.effectiveSigmaScale(99), 1.0e-12);
    assertEquals(0.05, config.effectiveMaxMutationDimRatio(99), 1.0e-12);
  }

  @Test
  void edaConfigKeepsTunedDefaultWhenActivated() {
    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.EDA.getIndex()] = 1;
    manager.initialize(flagPar, 16200, 270);

    EDAGaussSam.EDAConfig config = manager.getEdaConfig();
    assertEquals(0.12, config.kElite, 1.0e-12);
    assertEquals(1.00, config.sigmaScale, 1.0e-12);
    assertEquals(0.002, config.minSigmaRatio, 1.0e-12);
    assertEquals(1.0, config.stagnationBoostRatio, 1.0e-12);
    assertEquals(0, config.stagnationGenerations);
  }

  @Test
  void edaConfigUsesTuningPropertiesWhenActivated() {
    System.setProperty("eda.kElite", "0.25");
    System.setProperty("eda.sigmaScale", "1.50");
    System.setProperty("eda.minSigmaRatio", "0.003");
    System.setProperty("eda.stagnationBoostRatio", "1.20");
    System.setProperty("eda.stagnationGenerations", "5");
    TuningConfig.reload();

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.EDA.getIndex()] = 1;
    manager.initialize(flagPar, 16200, 270);

    EDAGaussSam.EDAConfig config = manager.getEdaConfig();
    assertEquals(0.25, config.kElite, 1.0e-12);
    assertEquals(1.50, config.sigmaScale, 1.0e-12);
    assertEquals(0.003, config.minSigmaRatio, 1.0e-12);
    assertEquals(1.20, config.stagnationBoostRatio, 1.0e-12);
    assertEquals(5, config.stagnationGenerations);
  }

  @Test
  void abcConfigKeepsFinalDefaultWhenActivated() {
    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.ABC.getIndex()] = 1;
    manager.initialize(flagPar, 16200, 270);

    ABC.ABCConfig config = manager.getAbcConfig();
    assertEquals(100, config.numNoImp);
    assertEquals(2.0, config.phiScale, 1.0e-12);
    assertEquals(1.0, config.phiOffset, 1.0e-12);
  }

  @Test
  void abcConfigUsesTuningConfigOverrides() {
    System.setProperty("abc.numNoImp", "50");
    System.setProperty("abc.phiScale", "1.0");
    System.setProperty("abc.phiOffset", "0.5");
    TuningConfig.reload();

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.ABC.getIndex()] = 1;
    manager.initialize(flagPar, 16200, 270);

    ABC.ABCConfig config = manager.getAbcConfig();
    assertEquals(50, config.numNoImp);
    assertEquals(1.0, config.phiScale, 1.0e-12);
    assertEquals(0.5, config.phiOffset, 1.0e-12);
  }

  @Test
  void acoConfigKeepsFinalDefaultWhenActivated() {
    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.ACO.getIndex()] = 1;
    manager.initialize(flagPar, 13500, 270);

    ACOGaussGenerate.ACOConfig config = manager.getAcoConfig();
    assertEquals(0.8, config.nSample, 1.0e-12);
    assertEquals(0.35, config.intenseFactor, 1.0e-12);
    assertEquals(0.5, config.deviateDistanceRatio, 1.0e-12);
    assertEquals(0.001, config.minSigmaRatio, 1.0e-12);
    assertFalse(config.legacySigma);
  }

  @Test
  void acoConfigUsesTuningPropertiesWhenActivated() {
    System.setProperty("aco.nSample", "0.6");
    System.setProperty("aco.intenseFactor", "0.8");
    System.setProperty("aco.deviateDistanceRatio", "1.5");
    System.setProperty("aco.minSigmaRatio", "0.003");
    System.setProperty("aco.legacySigma", "true");
    TuningConfig.reload();

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.ACO.getIndex()] = 1;
    manager.initialize(flagPar, 13500, 270);

    ACOGaussGenerate.ACOConfig config = manager.getAcoConfig();
    assertEquals(0.6, config.nSample, 1.0e-12);
    assertEquals(0.8, config.intenseFactor, 1.0e-12);
    assertEquals(1.5, config.deviateDistanceRatio, 1.0e-12);
    assertEquals(0.003, config.minSigmaRatio, 1.0e-12);
    assertTrue(config.legacySigma);
  }

  @Test
  void saConfigKeepsTunedDefaultWhenActivated() {
    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.SA.getIndex()] = 1;
    manager.initialize(flagPar, 27000, 270);

    SACont.SAConfig config = manager.getSaConfig();
    assertEquals(5, config.genSA);
    assertEquals(0.99, config.freezeRate, 1.0e-12);
    assertEquals(0.5, config.temperature, 1.0e-12);
    assertEquals(SACont.SA_TEMPERATURE_SCHEDULE_LOGARITHMIC, config.temperatureSchedule);
    assertEquals(0.95, config.temperatureAlpha, 1.0e-12);
    assertEquals(3.0, config.temperatureLogOffset, 1.0e-12);
    assertFalse(config.adaptiveSigma);
  }

  @Test
  void alnsConfigKeepsTunedDefaultWhenActivated() {
    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.ALNS.getIndex()] = 1;
    manager.initialize(flagPar, 300, 30);

    ALNS.ALNSConfig config = manager.getAlnsConfig();
    assertEquals(0.032, config.initialTemperatureRatio, 1.0e-12);
  }

  @Test
  void alnsConfigUsesTuningPropertiesWhenActivated() {
    System.setProperty("alns.innerIterations", "2");
    System.setProperty("alns.weightUpdateInterval", "4");
    System.setProperty("alns.minDestroyCount", "2");
    System.setProperty("alns.maxDestroyRatio", "0.25");
    System.setProperty("alns.sigmaGlobal", "40.0");
    System.setProperty("alns.sigmaLocal", "15.0");
    System.setProperty("alns.sigmaBad", "5.0");
    System.setProperty("alns.reactionFactor", "0.3");
    System.setProperty("alns.initialTemperatureRatio", "0.03");
    System.setProperty("alns.maxInitialTemperature", "80.0");
    System.setProperty("alns.coolingRate", "0.995");
    System.setProperty("alns.enableRegret2Repair", "true");
    System.setProperty("alns.destroyWeights", "0.2,0.6,0.2");
    System.setProperty("alns.repairWeights", "0.5,0.2,0.2,0.1");
    TuningConfig.reload();

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.ALNS.getIndex()] = 1;
    manager.initialize(flagPar, 300, 30);

    ALNS.ALNSConfig config = manager.getAlnsConfig();
    assertEquals(2, config.innerIterations);
    assertEquals(4, config.weightUpdateInterval);
    assertEquals(2, config.minDestroyCount);
    assertEquals(0.25, config.maxDestroyRatio, 1.0e-12);
    assertEquals(40.0, config.sigmaGlobal, 1.0e-12);
    assertEquals(15.0, config.sigmaLocal, 1.0e-12);
    assertEquals(5.0, config.sigmaBad, 1.0e-12);
    assertEquals(0.3, config.reactionFactor, 1.0e-12);
    assertEquals(0.03, config.initialTemperatureRatio, 1.0e-12);
    assertEquals(80.0, config.maxInitialTemperature, 1.0e-12);
    assertEquals(0.995, config.coolingRate, 1.0e-12);
    assertTrue(config.enableRegret2Repair);
    assertEquals(0.2, config.destroyWeights[0], 1.0e-12);
    assertEquals(0.6, config.destroyWeights[1], 1.0e-12);
    assertEquals(0.5, config.repairWeights[0], 1.0e-12);
    assertEquals(0.1, config.repairWeights[3], 1.0e-12);
  }

  @Test
  void shadeConfigUsesSystemPropertiesForHyperparameterSweep() {
    System.setProperty(SHADELoopBrief.SHADE_ARCHIVE_RATE_FAST_PROPERTY, "2.0");
    System.setProperty(SHADELoopBrief.SHADE_MEMORY_SIZE_PROPERTY, "20");
    System.setProperty(SHADELoopBrief.SHADE_PBEST_RATE_PROPERTY, "0.2");
    System.setProperty(SHADELoopBrief.SHADE_LEARN_PERIOD_PROPERTY, "12");
    System.setProperty(SHADELoopBrief.SHADE_CR_GAUSSIAN_STD_PROPERTY, "0.05");
    System.setProperty(SHADELoopBrief.SHADE_CAUCHY_SCALE_PROPERTY, "0.2");

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.L_SHADE.getIndex()] = 1;
    manager.initialize(flagPar, 16200, 270);

    SHADELoopBrief.SHADEConfig config = manager.getShadeConfig();
    assertEquals(20, config.memorySize);
    assertEquals(0.2, config.pbestRate, 1.0e-12);
    assertEquals(12, config.learnPeriod);
    assertEquals(2.0, config.archiveRate, 1.0e-12);
    assertEquals(540, config.archiveMaxVolume);
    assertEquals(0.05, config.crGaussianStd, 1.0e-12);
    assertEquals(0.2, config.cauchyScale, 1.0e-12);
  }

  @Test
  void psoConfigEnablesTVACByDefaultWhenActivated() {
    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.PSO.getIndex()] = 1;
    manager.initialize(flagPar, 12, 4);

    assertTrue(manager.getPsoConfig().enableTVAC);
    assertEquals(PSO.PSO_TVAC_C1_START, manager.getPsoConfig().tvacC1Start, 1.0e-12);
    assertEquals(PSO.PSO_TVAC_C1_END, manager.getPsoConfig().tvacC1End, 1.0e-12);
    assertEquals(PSO.PSO_TVAC_C2_START, manager.getPsoConfig().tvacC2Start, 1.0e-12);
    assertEquals(PSO.PSO_TVAC_C2_END, manager.getPsoConfig().tvacC2End, 1.0e-12);
    assertEquals(PSO.PSO_BOUNDARY_MODE, manager.getPsoConfig().boundaryMode);
    assertEquals(
      PSO.PSO_BOUNDARY_REPAIR_RATIO,
      manager.getPsoConfig().boundaryRepairRatio,
      1.0e-12);
  }

  @Test
  void psoConfigCanDisableTVACWithTuningProperty() {
    System.setProperty("pso.enableTVAC", "false");
    TuningConfig.reload();

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.PSO.getIndex()] = 1;
    manager.initialize(flagPar, 12, 4);

    assertFalse(manager.getPsoConfig().enableTVAC);
  }

  @Test
  void psoConfigUsesTuningPropertiesWhenActivated() {
    System.setProperty("pso.cognitiveCoefficient", "1.5,1.0");
    System.setProperty("pso.inertiaWeight", "0.72");
    System.setProperty("pso.vMaxLimit", "40.0");
    System.setProperty("pso.vMinLimit", "-35.0");
    System.setProperty("pso.boundaryMode", "SPECULAR_REFLECTION");
    System.setProperty("pso.boundaryRepairRatio", "0.10");
    System.setProperty("pso.enableTVAC", "true");
    System.setProperty("pso.tvacC1Start", "2.2");
    System.setProperty("pso.tvacC1End", "0.8");
    System.setProperty("pso.tvacC2Start", "0.8");
    System.setProperty("pso.tvacC2End", "2.2");
    System.setProperty("pso.vMaxRatio", "0.12");
    TuningConfig.reload();

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.PSO.getIndex()] = 1;
    manager.initialize(flagPar, 12, 4);

    PSO.PSOConfig config = manager.getPsoConfig();
    assertEquals(1.5, config.cognitiveCoefficient[0], 1.0e-12);
    assertEquals(1.0, config.cognitiveCoefficient[1], 1.0e-12);
    assertEquals(12, config.inertiaWeight.length);
    assertEquals(0.72, config.inertiaWeight[11], 1.0e-12);
    assertEquals(40.0, config.vMaxLimit, 1.0e-12);
    assertEquals(-35.0, config.vMinLimit, 1.0e-12);
    assertEquals(common.BoundaryHandler.BoundaryMode.SPECULAR_REFLECTION, config.boundaryMode);
    assertEquals(0.10, config.boundaryRepairRatio, 1.0e-12);
    assertTrue(config.enableTVAC);
    assertEquals(2.2, config.tvacC1Start, 1.0e-12);
    assertEquals(0.8, config.tvacC1End, 1.0e-12);
    assertEquals(0.8, config.tvacC2Start, 1.0e-12);
    assertEquals(2.2, config.tvacC2End, 1.0e-12);
    assertEquals(0.12, config.vMaxRatio, 1.0e-12);
  }

  @Test
  void deConfigKeepsCurrentSigmoidDefaultWhenActivated() {
    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.DE_RAND_1.getIndex()] = 1;
    manager.initialize(flagPar, 100, 10);

    DEConfig config = manager.getDeConfig();
    assertEquals(100, config.differentialWeight1.length);
    assertEquals(0.1, config.getF1(0), 1.0e-3);
    assertEquals(0.01, config.getF1(99), 1.0e-3);
    assertEquals(DEConfig.DE_DEFAULT_CROSSOVER_RATE, config.crossoverRate, 1.0e-12);
    assertEquals(DEConfig.BoundaryPolicy.RANDOM_REINIT, config.boundaryPolicy);
  }

  @Test
  void deConfigUsesStaticTuningPropertiesAndSurvivesAdjustment() {
    System.setProperty("de.fSchedule", "STATIC");
    System.setProperty("de.fStatic", "0.05");
    System.setProperty("de.crossoverRate", "0.5");
    System.setProperty("de.boundaryPolicy", "REFLECT");
    TuningConfig.reload();

    HybCecConfigManager manager = new HybCecConfigManager();
    int[] flagPar = new int[AlgorithmIndex.getRequiredFlagParLength()];
    flagPar[AlgorithmIndex.DE_RAND_1.getIndex()] = 1;
    manager.initialize(flagPar, 20, 5);

    DEConfig initialConfig = manager.getDeConfig();
    assertEquals(1, initialConfig.differentialWeight1.length);
    assertEquals(0.05, initialConfig.getF1(19), 1.0e-12);
    assertEquals(0.5, initialConfig.crossoverRate, 1.0e-12);
    assertEquals(DEConfig.BoundaryPolicy.REFLECT, initialConfig.boundaryPolicy);

    manager.adjustDEConfig(30);

    DEConfig adjustedConfig = manager.getDeConfig();
    assertEquals(1, adjustedConfig.differentialWeight1.length);
    assertEquals(0.05, adjustedConfig.getF1(29), 1.0e-12);
    assertEquals(0.5, adjustedConfig.crossoverRate, 1.0e-12);
    assertEquals(DEConfig.BoundaryPolicy.REFLECT, adjustedConfig.boundaryPolicy);
  }
}
