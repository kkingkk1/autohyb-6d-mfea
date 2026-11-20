package core.config;

import algorithm.eda.EDAGaussSam;
import algorithm.alns.ALNS;
import utils.ListUtils;
import algorithm.de.DEConfig;
import algorithm.pso.PSO;
import algorithm.shade.UpdateArchive;
import algorithm.abc.ABC;
import algorithm.aco.ACOGaussGenerate;
import algorithm.sa.SACont;
import algorithm.ga.GeneticAlgorithmNoSelect;
import algorithm.shade.SHADELoopBrief;
import common.AlgorithmConstants;
import common.BoundaryHandler;
import common.ConfigStringUtils;
import core.HybCec;
import java.io.*;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * HybCec Configuration Serialization Class
 *
 * This class is used to save and load all configuration parameters of HybCec, including:
 * - Basic parameters (parent1, parent2, D, nPop, etc.)
 * - Configuration of each algorithm (PSO, GA, EDA, ACO, DE, ABC, SA, SHADE)
 *
 * Configuration is saved in Properties format to a file, making it easy to view and edit manually.
 */
public class HybCecConfig {

  private static final Logger LOGGER = Logger.getLogger(HybCecConfig.class.getName());

  // ==========================================================
  // Configuration Keys Constants
  // ==========================================================
  private static final class Keys {
    // Basic Parameters
    static final String PARENT1 = "parent1";
    static final String PARENT2 = "parent2";
    static final String D = "D";
    static final String N_POP = "nPop";
    static final String OL = "oL";
    static final String OL_RAND = "oLRand";
    static final String OO = "oO";
    static final String STR_SURVIVE = "strSurvive";
    static final String MAX_ITERA = "maxItera";
    static final String UPDATE_RATIOS = "updateRatios";
    static final String FLAG_OO = "flagOO";

    // PSO Configuration
    static final String PSO_COGNITIVE_COEFFICIENT = "pso.cognitiveCoefficient";
    static final String PSO_INERTIA_WEIGHT = "pso.inertiaWeight";
    static final String PSO_V_MAX_LIMIT = "pso.vMaxLimit";
    static final String PSO_V_MIN_LIMIT = "pso.vMinLimit";
    static final String PSO_UPPER_BOUND = "pso.upperBound";
    static final String PSO_LOWER_BOUND = "pso.lowerBound";
    static final String PSO_BOUNDARY_MODE = "pso.boundaryMode";
    static final String PSO_BOUNDARY_REPAIR_RATIO = "pso.boundaryRepairRatio";
    static final String PSO_ENABLE_TVAC = "pso.enableTVAC";
    static final String PSO_TVAC_C1_START = "pso.tvacC1Start";
    static final String PSO_TVAC_C1_END = "pso.tvacC1End";
    static final String PSO_TVAC_C2_START = "pso.tvacC2Start";
    static final String PSO_TVAC_C2_END = "pso.tvacC2End";
    static final String PSO_V_MAX_RATIO = "pso.vMaxRatio";
    static final String PSO_TOPOLOGY = "pso.topology";

    // GA Configuration
    static final String GA_PROB_CROSSOVER = "ga.probCrossover";
    static final String GA_PROB_MUTATION = "ga.probMutation";
    static final String GA_K_TOURNAMENT = "ga.kTournament";
    static final String GA_UPPER_BOUNDS = "ga.upperBounds";
    static final String GA_LOWER_BOUNDS = "ga.lowerBounds";
    static final String GA_SIGMA_SCALE = "ga.sigmaScale";
    static final String GA_N_ELITE = "ga.nElite";
    static final String GA_ELITISM_SCOPE = "ga.elitismScope";
    static final String GA_MAX_MUTATION_DIM_RATIO = "ga.maxMutationDimRatio";
    static final String GA_ADAPTIVE_MUTATION = "ga.adaptiveMutation";
    static final String GA_PROB_MUTATION_LATE = "ga.probMutationLate";
    static final String GA_SIGMA_SCALE_LATE = "ga.sigmaScaleLate";
    static final String GA_MAX_MUTATION_DIM_RATIO_LATE = "ga.maxMutationDimRatioLate";
    static final String GA_MUTATION_DECAY_START_RATIO = "ga.mutationDecayStartRatio";
    static final String GA_MAX_GENERATION = "ga.maxGeneration";

    // EDA Configuration
    static final String EDA_K_ELITE = "eda.kElite";
    static final String EDA_UPPER_BOUND = "eda.upperBound";
    static final String EDA_LOWER_BOUND = "eda.lowerBound";
    static final String EDA_SIGMA_SCALE = "eda.sigmaScale";
    static final String EDA_MIN_SIGMA_RATIO = "eda.minSigmaRatio";
    static final String EDA_STAGNATION_BOOST_RATIO = "eda.stagnationBoostRatio";
    static final String EDA_STAGNATION_GENERATIONS = "eda.stagnationGenerations";

    // ACO Configuration
    static final String ACO_N_SAMPLE = "aco.nSample";
    static final String ACO_INTENSE_FACTOR = "aco.intenseFactor";
    static final String ACO_DEVIATE_DISTANCE_RATIO = "aco.deviateDistanceRatio";
    static final String ACO_UPPER_BOUND = "aco.upperBound";
    static final String ACO_LOWER_BOUND = "aco.lowerBound";
    static final String ACO_MIN_SIGMA_RATIO = "aco.minSigmaRatio";
    static final String ACO_LEGACY_SIGMA = "aco.legacySigma";

    // DE Configuration
    static final String DE_DIFFERENTIAL_WEIGHT1 = "de.differentialWeight1";
    static final String DE_DIFFERENTIAL_WEIGHT2 = "de.differentialWeight2";
    static final String DE_CROSSOVER_RATE = "de.crossoverRate";
    static final String DE_UPPER_BOUND = "de.upperBound";
    static final String DE_LOWER_BOUND = "de.lowerBound";
    static final String DE_BOUNDARY_POLICY = "de.boundaryPolicy";

    // ABC Configuration
    static final String ABC_NUM_NO_IMP = "abc.numNoImp";
    static final String ABC_UPPER_BOUND = "abc.upperBound";
    static final String ABC_LOWER_BOUND = "abc.lowerBound";
    static final String ABC_PHI_SCALE = "abc.phiScale";
    static final String ABC_PHI_OFFSET = "abc.phiOffset";

    // SA Configuration
    static final String SA_GEN_SA = "sa.genSA";
    static final String SA_FREEZE_RATE = "sa.freezeRate";
    static final String SA_TEMPERATURE = "sa.temperature";
    static final String SA_TEMPERATURE_SCHEDULE = "sa.temperatureSchedule";
    static final String SA_TEMPERATURE_ALPHA = "sa.temperatureAlpha";
    static final String SA_TEMPERATURE_LOG_OFFSET = "sa.temperatureLogOffset";
    static final String SA_UPPER_BOUND = "sa.upperBound";
    static final String SA_LOWER_BOUND = "sa.lowerBound";
    static final String SA_ADAPTIVE_SIGMA = "sa.adaptiveSigma";

    // SHADE Configuration
    static final String SHADE_MEMORY_SIZE = "shade.memorySize";
    static final String SHADE_FREQ_INI_FOR_SF = "shade.freqIniForSF";
    static final String SHADE_PROB_EIGEN_CROSSOVER = "shade.probEigenCrossover";
    static final String SHADE_PROPORTION_SELECT_NEIGHBOUR = "shade.proportionSelectNeighbour";
    static final String SHADE_PBEST_RATE = "shade.pbestRate";
    static final String SHADE_LEARN_PERIOD = "shade.learnPeriod";
    static final String SHADE_ARCHIVE_RATE = "shade.archiveRate";
    static final String SHADE_ARCHIVE_MAX_VOLUME = "shade.archiveMaxVolume";
    static final String SHADE_CURRENT_POP_SIZE = "shade.currentPopSize";
    static final String SHADE_CR_GAUSSIAN_STD = "shade.crGaussianStd";
    static final String SHADE_CAUCHY_SCALE = "shade.cauchyScale";

    // ALNS Configuration
    static final String ALNS_INNER_ITERATIONS = "alns.innerIterations";
    static final String ALNS_WEIGHT_UPDATE_INTERVAL = "alns.weightUpdateInterval";
    static final String ALNS_MIN_DESTROY_COUNT = "alns.minDestroyCount";
    static final String ALNS_MAX_DESTROY_RATIO = "alns.maxDestroyRatio";
    static final String ALNS_SIGMA_GLOBAL = "alns.sigmaGlobal";
    static final String ALNS_SIGMA_LOCAL = "alns.sigmaLocal";
    static final String ALNS_SIGMA_BAD = "alns.sigmaBad";
    static final String ALNS_REACTION_FACTOR = "alns.reactionFactor";
    static final String ALNS_INITIAL_TEMPERATURE_RATIO = "alns.initialTemperatureRatio";
    static final String ALNS_MAX_INITIAL_TEMPERATURE = "alns.maxInitialTemperature";
    static final String ALNS_COOLING_RATE = "alns.coolingRate";
    static final String ALNS_LOWER_BOUND = "alns.lowerBound";
    static final String ALNS_UPPER_BOUND = "alns.upperBound";
    static final String ALNS_INSERTION_MODE = "alns.insertionMode";
    static final String ALNS_ENABLE_REGRET2_REPAIR = "alns.enableRegret2Repair";
    static final String ALNS_DESTROY_WEIGHTS = "alns.destroyWeights";
    static final String ALNS_REPAIR_WEIGHTS = "alns.repairWeights";

    // Elite Evaluation Configuration
    static final String ENABLE_ELITE_EVAL = "eliteEval.enable";
    static final String ELITE_RATIO = "eliteEval.ratio";

    // Phase-based Optimal Insertion Configuration
    static final String ENABLE_PHASE_OPTIMAL = "phaseOptimal.enable";
    static final String OPTIMAL_SWITCH_RATIO = "phaseOptimal.switchRatio";

    // Always Use Optimal Insertion Configuration
    static final String ALWAYS_USE_OPTIMAL = "alwaysUseOptimal.enable";

    // Max Slack Insertion Configuration
    static final String USE_MAX_SLACK = "maxSlackInsertion.enable";
  }


  // ==========================================================
  // Basic Parameters (private with getters/setters)
  // ==========================================================
  private int parent1;
  private int parent2;
  private int D;
  private int nPop;
  private int[] oL;
  private int oLRand;
  private int oO;
  private int strSurvive;
  private int maxItera;
  private int[] flagOO;

  // Elite Evaluation Configuration
  private boolean enableEliteEval;
  private double eliteRatio;

  // Phase-based Optimal Insertion Configuration
  private boolean enablePhaseOptimal;
  private double optimalSwitchRatio;

  // Always Use Optimal Insertion Configuration
  private boolean alwaysUseOptimal;

  // Max Slack Insertion Configuration
  private boolean useMaxSlack;

  // ==========================================================
  // Algorithm Configuration (private with getters/setters)
  // ==========================================================
  private PSO.PSOConfig psoConfig;
  private GeneticAlgorithmNoSelect.GAConfig gaConfig;
  private EDAGaussSam.EDAConfig edaConfig;
  private ACOGaussGenerate.ACOConfig acoConfig;
  private DEConfig deConfig;
  private ABC.ABCConfig abcConfig;
  private SACont.SAConfig saConfig;
  private SHADELoopBrief.SHADEConfig shadeConfig;
  private ALNS.ALNSConfig alnsConfig;

  // ==========================================================
  // Constructors
  // ==========================================================

  /**
   * Default constructor
   */
  public HybCecConfig() {
  }

  /**
   * Extract Configuration from HybCec Instance
   */
  public HybCecConfig(HybCec hybCec) {
    // Basic parameters
    this.parent1 = hybCec.getParent1();
    this.parent2 = hybCec.getParent2();
    this.D = hybCec.getD();
    this.nPop = hybCec.getNPop();
    this.oL = hybCec.getOL();
    this.oLRand = hybCec.getOLRand();
    this.oO = hybCec.getOO();
    this.strSurvive = hybCec.getStrSurvive();
    this.maxItera = hybCec.getMaxItera();
    this.flagOO = AlgorithmConstants.createOperationOrderFlags();

    // Algorithm configuration
    this.psoConfig = hybCec.getPsoConfig();
    this.gaConfig = hybCec.getGaConfig();
    this.edaConfig = hybCec.getEdaConfig();
    this.acoConfig = hybCec.getAcoConfig();
    this.deConfig = hybCec.getDeConfig();
    this.abcConfig = hybCec.getAbcConfig();
    this.saConfig = hybCec.getSaConfig();
    this.shadeConfig = hybCec.getShadeConfig();
    this.alnsConfig = hybCec.getAlnsConfig();
  }

  // ==========================================================
  // Getters and Setters
  // ==========================================================

  public int getParent1() { return parent1; }
  public void setParent1(int parent1) { this.parent1 = parent1; }

  public int getParent2() { return parent2; }
  public void setParent2(int parent2) { this.parent2 = parent2; }

  public int getD() { return D; }
  public void setD(int d) { this.D = d; }

  public int getNPop() { return nPop; }
  public void setNPop(int nPop) { this.nPop = nPop; }

  public int[] getOL() { return oL; }
  public void setOL(int[] oL) { this.oL = oL; }

  public int getOLRand() { return oLRand; }
  public void setOLRand(int oLRand) { this.oLRand = oLRand; }

  public int getOO() { return oO; }
  public void setOO(int oO) { this.oO = oO; }

  public int getStrSurvive() { return strSurvive; }
  public void setStrSurvive(int strSurvive) { this.strSurvive = strSurvive; }

  public int getMaxItera() { return maxItera; }
  public void setMaxItera(int maxItera) { this.maxItera = maxItera; }

  public int[] getFlagOO() { return flagOO; }
  public void setFlagOO(int[] flagOO) { this.flagOO = flagOO; }

  public PSO.PSOConfig getPsoConfig() { return psoConfig; }
  public void setPsoConfig(PSO.PSOConfig psoConfig) { this.psoConfig = psoConfig; }

  public GeneticAlgorithmNoSelect.GAConfig getGaConfig() { return gaConfig; }
  public void setGaConfig(GeneticAlgorithmNoSelect.GAConfig gaConfig) { this.gaConfig = gaConfig; }

  public EDAGaussSam.EDAConfig getEdaConfig() { return edaConfig; }
  public void setEdaConfig(EDAGaussSam.EDAConfig edaConfig) { this.edaConfig = edaConfig; }

  public ACOGaussGenerate.ACOConfig getAcoConfig() { return acoConfig; }
  public void setAcoConfig(ACOGaussGenerate.ACOConfig acoConfig) { this.acoConfig = acoConfig; }

  public DEConfig getDeConfig() { return deConfig; }
  public void setDeConfig(DEConfig deConfig) { this.deConfig = deConfig; }

  public ABC.ABCConfig getAbcConfig() { return abcConfig; }
  public void setAbcConfig(ABC.ABCConfig abcConfig) { this.abcConfig = abcConfig; }

  public SACont.SAConfig getSaConfig() { return saConfig; }
  public void setSaConfig(SACont.SAConfig saConfig) { this.saConfig = saConfig; }

  public SHADELoopBrief.SHADEConfig getShadeConfig() { return shadeConfig; }
  public void setShadeConfig(SHADELoopBrief.SHADEConfig shadeConfig) { this.shadeConfig = shadeConfig; }

  public ALNS.ALNSConfig getAlnsConfig() { return alnsConfig; }
  public void setAlnsConfig(ALNS.ALNSConfig alnsConfig) { this.alnsConfig = alnsConfig; }

  public boolean isEnableEliteEval() { return enableEliteEval; }
  public void setEnableEliteEval(boolean enableEliteEval) { this.enableEliteEval = enableEliteEval; }

  public double getEliteRatio() { return eliteRatio; }
  public void setEliteRatio(double eliteRatio) { this.eliteRatio = eliteRatio; }

  public boolean isEnablePhaseOptimal() { return enablePhaseOptimal; }
  public void setEnablePhaseOptimal(boolean enablePhaseOptimal) { this.enablePhaseOptimal = enablePhaseOptimal; }

  public double getOptimalSwitchRatio() { return optimalSwitchRatio; }
  public void setOptimalSwitchRatio(double optimalSwitchRatio) { this.optimalSwitchRatio = optimalSwitchRatio; }

  public boolean isAlwaysUseOptimal() { return alwaysUseOptimal; }
  public void setAlwaysUseOptimal(boolean alwaysUseOptimal) { this.alwaysUseOptimal = alwaysUseOptimal; }

  public boolean isUseMaxSlack() { return useMaxSlack; }
  public void setUseMaxSlack(boolean useMaxSlack) { this.useMaxSlack = useMaxSlack; }

  // ==========================================================
  // Validation
  // ==========================================================

  /**
   * Validate the configuration
   * @throws ConfigValidationException if validation fails
   */
  public void validate() throws ConfigValidationException {
    validateBasicParameters();
    validateAlgorithmConfigs();
  }

  private void validateBasicParameters() throws ConfigValidationException {
    if (parent1 < 0) {
      throw new ConfigValidationException("parent1 cannot be negative: " + parent1);
    }
    if (parent2 < 0) {
      throw new ConfigValidationException("parent2 cannot be negative: " + parent2);
    }
    if (D <= 0) {
      throw new ConfigValidationException("D must be positive: " + D);
    }
    if (nPop <= 0) {
      throw new ConfigValidationException("nPop must be positive: " + nPop);
    }
    if (maxItera <= 0) {
      throw new ConfigValidationException("maxItera must be positive: " + maxItera);
    }
  }

  private void validateAlgorithmConfigs() throws ConfigValidationException {
    // Add specific algorithm config validations as needed
    if (psoConfig != null) {
      if (psoConfig.upperBound <= psoConfig.lowerBound) {
        throw new ConfigValidationException("PSO: upperBound must be greater than lowerBound");
      }
    }
    if (gaConfig != null) {
      if (gaConfig.probCrossover < 0 || gaConfig.probCrossover > 1) {
        throw new ConfigValidationException("GA: probCrossover must be in [0, 1]");
      }
      if (gaConfig.probMutation < 0 || gaConfig.probMutation > 1) {
        throw new ConfigValidationException("GA: probMutation must be in [0, 1]");
      }
    }
  }

  // ==========================================================
  // Save Methods
  // ==========================================================

  /**
   * Save configuration to file (Properties format)
   */
  public void saveToPropertiesFile(String filename) throws IOException {
    Properties props = new Properties();

    saveBasicParameters(props);
    saveEliteEvalConfig(props);
    savePhaseOptimalConfig(props);
    saveAlwaysOptimalConfig(props);
    saveMaxSlackConfig(props);
    savePSOConfig(props);
    saveGAConfig(props);
    saveEDAConfig(props);
    saveACOConfig(props);
    saveDEConfig(props);
    saveABCConfig(props);
    saveSAConfig(props);
    saveSHADEConfig(props);
    saveALNSConfig(props);

    try (FileOutputStream fos = new FileOutputStream(filename)) {
      props.store(fos, "HybCec Configuration");
    }

    LOGGER.log(Level.INFO, "Configuration saved to: {0}", filename);
  }

  private void saveBasicParameters(Properties props) {
    props.setProperty(Keys.PARENT1, String.valueOf(parent1));
    props.setProperty(Keys.PARENT2, String.valueOf(parent2));
    props.setProperty(Keys.D, String.valueOf(D));
    props.setProperty(Keys.N_POP, String.valueOf(nPop));
    props.setProperty(Keys.OL, ConfigStringUtils.arrayToString(oL));
    props.setProperty(Keys.OL_RAND, String.valueOf(oLRand));
    props.setProperty(Keys.OO, String.valueOf(oO));
    props.setProperty(Keys.STR_SURVIVE, String.valueOf(strSurvive));
    props.setProperty(Keys.MAX_ITERA, String.valueOf(maxItera));
    props.setProperty(Keys.FLAG_OO, ConfigStringUtils.arrayToString(
      flagOO != null ? flagOO : AlgorithmConstants.createOperationOrderFlags()));
  }

  private void saveEliteEvalConfig(Properties props) {
    props.setProperty(Keys.ENABLE_ELITE_EVAL, String.valueOf(enableEliteEval));
    props.setProperty(Keys.ELITE_RATIO, String.valueOf(eliteRatio));
  }

  private void savePhaseOptimalConfig(Properties props) {
    props.setProperty(Keys.ENABLE_PHASE_OPTIMAL, String.valueOf(enablePhaseOptimal));
    props.setProperty(Keys.OPTIMAL_SWITCH_RATIO, String.valueOf(optimalSwitchRatio));
  }

  private void saveAlwaysOptimalConfig(Properties props) {
    props.setProperty(Keys.ALWAYS_USE_OPTIMAL, String.valueOf(alwaysUseOptimal));
  }

  private void saveMaxSlackConfig(Properties props) {
    props.setProperty(Keys.USE_MAX_SLACK, String.valueOf(useMaxSlack));
  }

  private void savePSOConfig(Properties props) {
    if (psoConfig == null) return;

    props.setProperty(Keys.PSO_COGNITIVE_COEFFICIENT,
      ConfigStringUtils.arrayToString(psoConfig.cognitiveCoefficient));
    props.setProperty(Keys.PSO_INERTIA_WEIGHT,
      ConfigStringUtils.arrayToString(psoConfig.inertiaWeight));
    props.setProperty(Keys.PSO_V_MAX_LIMIT, String.valueOf(psoConfig.vMaxLimit));
    props.setProperty(Keys.PSO_V_MIN_LIMIT, String.valueOf(psoConfig.vMinLimit));
    props.setProperty(Keys.PSO_UPPER_BOUND, String.valueOf(psoConfig.upperBound));
    props.setProperty(Keys.PSO_LOWER_BOUND, String.valueOf(psoConfig.lowerBound));
    props.setProperty(Keys.PSO_BOUNDARY_MODE, psoConfig.boundaryMode.name());
    props.setProperty(Keys.PSO_BOUNDARY_REPAIR_RATIO, String.valueOf(psoConfig.boundaryRepairRatio));
    props.setProperty(Keys.PSO_ENABLE_TVAC, String.valueOf(psoConfig.enableTVAC));
    props.setProperty(Keys.PSO_TVAC_C1_START, String.valueOf(psoConfig.tvacC1Start));
    props.setProperty(Keys.PSO_TVAC_C1_END, String.valueOf(psoConfig.tvacC1End));
    props.setProperty(Keys.PSO_TVAC_C2_START, String.valueOf(psoConfig.tvacC2Start));
    props.setProperty(Keys.PSO_TVAC_C2_END, String.valueOf(psoConfig.tvacC2End));
    props.setProperty(Keys.PSO_V_MAX_RATIO, String.valueOf(psoConfig.vMaxRatio));
    props.setProperty(Keys.PSO_TOPOLOGY, psoConfig.topology.name());
  }

  private void saveGAConfig(Properties props) {
    if (gaConfig == null) return;

    props.setProperty(Keys.GA_PROB_CROSSOVER, String.valueOf(gaConfig.probCrossover));
    props.setProperty(Keys.GA_PROB_MUTATION, String.valueOf(gaConfig.probMutation));
    props.setProperty(Keys.GA_K_TOURNAMENT, String.valueOf(gaConfig.kTournament));
    props.setProperty(Keys.GA_SIGMA_SCALE, String.valueOf(gaConfig.sigmaScale));
    props.setProperty(Keys.GA_UPPER_BOUNDS, String.valueOf(gaConfig.upperBounds));
    props.setProperty(Keys.GA_LOWER_BOUNDS, String.valueOf(gaConfig.lowerBounds));
    props.setProperty(Keys.GA_N_ELITE, String.valueOf(gaConfig.nElite));
    props.setProperty(Keys.GA_ELITISM_SCOPE, gaConfig.elitismScope.name());
    props.setProperty(Keys.GA_MAX_MUTATION_DIM_RATIO, String.valueOf(gaConfig.maxMutationDimRatio));
    props.setProperty(Keys.GA_ADAPTIVE_MUTATION, String.valueOf(gaConfig.adaptiveMutation));
    props.setProperty(Keys.GA_PROB_MUTATION_LATE, String.valueOf(gaConfig.probMutationLate));
    props.setProperty(Keys.GA_SIGMA_SCALE_LATE, String.valueOf(gaConfig.sigmaScaleLate));
    props.setProperty(Keys.GA_MAX_MUTATION_DIM_RATIO_LATE, String.valueOf(gaConfig.maxMutationDimRatioLate));
    props.setProperty(Keys.GA_MUTATION_DECAY_START_RATIO, String.valueOf(gaConfig.mutationDecayStartRatio));
    props.setProperty(Keys.GA_MAX_GENERATION, String.valueOf(gaConfig.maxGeneration));
  }

  private void saveEDAConfig(Properties props) {
    if (edaConfig == null) return;

    props.setProperty(Keys.EDA_K_ELITE, String.valueOf(edaConfig.kElite));
    props.setProperty(Keys.EDA_SIGMA_SCALE, String.valueOf(edaConfig.sigmaScale));
    props.setProperty(Keys.EDA_UPPER_BOUND, String.valueOf(edaConfig.upperBound));
    props.setProperty(Keys.EDA_LOWER_BOUND, String.valueOf(edaConfig.lowerBound));
    props.setProperty(Keys.EDA_MIN_SIGMA_RATIO, String.valueOf(edaConfig.minSigmaRatio));
    props.setProperty(Keys.EDA_STAGNATION_BOOST_RATIO, String.valueOf(edaConfig.stagnationBoostRatio));
    props.setProperty(Keys.EDA_STAGNATION_GENERATIONS, String.valueOf(edaConfig.stagnationGenerations));
  }

  private void saveACOConfig(Properties props) {
    if (acoConfig == null) return;

    props.setProperty(Keys.ACO_N_SAMPLE, String.valueOf(acoConfig.nSample));
    props.setProperty(Keys.ACO_INTENSE_FACTOR, String.valueOf(acoConfig.intenseFactor));
    props.setProperty(Keys.ACO_DEVIATE_DISTANCE_RATIO, String.valueOf(acoConfig.deviateDistanceRatio));
    props.setProperty(Keys.ACO_UPPER_BOUND, String.valueOf(acoConfig.upperBound));
    props.setProperty(Keys.ACO_LOWER_BOUND, String.valueOf(acoConfig.lowerBound));
    props.setProperty(Keys.ACO_MIN_SIGMA_RATIO, String.valueOf(acoConfig.minSigmaRatio));
    props.setProperty(Keys.ACO_LEGACY_SIGMA, String.valueOf(acoConfig.legacySigma));
  }

  private void saveDEConfig(Properties props) {
    if (deConfig == null) return;

    props.setProperty(Keys.DE_DIFFERENTIAL_WEIGHT1,
      ConfigStringUtils.arrayToString(deConfig.differentialWeight1));
    if (deConfig.differentialWeight2 != null) {
      props.setProperty(Keys.DE_DIFFERENTIAL_WEIGHT2,
        ConfigStringUtils.arrayToString(deConfig.differentialWeight2));
    }
    props.setProperty(Keys.DE_CROSSOVER_RATE, String.valueOf(deConfig.crossoverRate));
    props.setProperty(Keys.DE_UPPER_BOUND, String.valueOf(deConfig.upperBound));
    props.setProperty(Keys.DE_LOWER_BOUND, String.valueOf(deConfig.lowerBound));
    props.setProperty(Keys.DE_BOUNDARY_POLICY, deConfig.boundaryPolicy.name());
  }

  private void saveABCConfig(Properties props) {
    if (abcConfig == null) return;

    props.setProperty(Keys.ABC_NUM_NO_IMP, String.valueOf(abcConfig.numNoImp));
    props.setProperty(Keys.ABC_UPPER_BOUND, String.valueOf(abcConfig.upperBound));
    props.setProperty(Keys.ABC_LOWER_BOUND, String.valueOf(abcConfig.lowerBound));
    props.setProperty(Keys.ABC_PHI_SCALE, String.valueOf(abcConfig.phiScale));
    props.setProperty(Keys.ABC_PHI_OFFSET, String.valueOf(abcConfig.phiOffset));
  }

  private void saveSAConfig(Properties props) {
    if (saConfig == null) return;

    props.setProperty(Keys.SA_GEN_SA, String.valueOf(saConfig.genSA));
    props.setProperty(Keys.SA_FREEZE_RATE, String.valueOf(saConfig.freezeRate));
    props.setProperty(Keys.SA_TEMPERATURE, String.valueOf(saConfig.temperature));
    props.setProperty(Keys.SA_TEMPERATURE_SCHEDULE, String.valueOf(saConfig.temperatureSchedule));
    props.setProperty(Keys.SA_TEMPERATURE_ALPHA, String.valueOf(saConfig.temperatureAlpha));
    props.setProperty(Keys.SA_TEMPERATURE_LOG_OFFSET, String.valueOf(saConfig.temperatureLogOffset));
    props.setProperty(Keys.SA_UPPER_BOUND, String.valueOf(saConfig.upperBound));
    props.setProperty(Keys.SA_LOWER_BOUND, String.valueOf(saConfig.lowerBound));
    props.setProperty(Keys.SA_ADAPTIVE_SIGMA, String.valueOf(saConfig.adaptiveSigma));
  }

  private void saveSHADEConfig(Properties props) {
    if (shadeConfig == null) return;

    props.setProperty(Keys.SHADE_MEMORY_SIZE, String.valueOf(shadeConfig.memorySize));
    props.setProperty(Keys.SHADE_FREQ_INI_FOR_SF, String.valueOf(shadeConfig.freqIniForSF));
    props.setProperty(Keys.SHADE_PROB_EIGEN_CROSSOVER, String.valueOf(shadeConfig.probEigenCrossover));
    props.setProperty(Keys.SHADE_PROPORTION_SELECT_NEIGHBOUR, String.valueOf(shadeConfig.proportionSelectNeighbour));
    props.setProperty(Keys.SHADE_PBEST_RATE, String.valueOf(shadeConfig.pbestRate));
    props.setProperty(Keys.SHADE_LEARN_PERIOD, String.valueOf(shadeConfig.learnPeriod));
    props.setProperty(Keys.SHADE_ARCHIVE_RATE, String.valueOf(shadeConfig.archiveRate));
    props.setProperty(Keys.SHADE_ARCHIVE_MAX_VOLUME, String.valueOf(shadeConfig.archiveMaxVolume));
    props.setProperty(Keys.SHADE_CURRENT_POP_SIZE, String.valueOf(shadeConfig.currentPopSize));
    props.setProperty(Keys.SHADE_CR_GAUSSIAN_STD, String.valueOf(shadeConfig.crGaussianStd));
    props.setProperty(Keys.SHADE_CAUCHY_SCALE, String.valueOf(shadeConfig.cauchyScale));
  }

  private void saveALNSConfig(Properties props) {
    if (alnsConfig == null) return;

    props.setProperty(Keys.ALNS_INNER_ITERATIONS, String.valueOf(alnsConfig.innerIterations));
    props.setProperty(Keys.ALNS_WEIGHT_UPDATE_INTERVAL, String.valueOf(alnsConfig.weightUpdateInterval));
    props.setProperty(Keys.ALNS_MIN_DESTROY_COUNT, String.valueOf(alnsConfig.minDestroyCount));
    props.setProperty(Keys.ALNS_MAX_DESTROY_RATIO, String.valueOf(alnsConfig.maxDestroyRatio));
    props.setProperty(Keys.ALNS_SIGMA_GLOBAL, String.valueOf(alnsConfig.sigmaGlobal));
    props.setProperty(Keys.ALNS_SIGMA_LOCAL, String.valueOf(alnsConfig.sigmaLocal));
    props.setProperty(Keys.ALNS_SIGMA_BAD, String.valueOf(alnsConfig.sigmaBad));
    props.setProperty(Keys.ALNS_REACTION_FACTOR, String.valueOf(alnsConfig.reactionFactor));
    props.setProperty(Keys.ALNS_INITIAL_TEMPERATURE_RATIO, String.valueOf(alnsConfig.initialTemperatureRatio));
    props.setProperty(Keys.ALNS_MAX_INITIAL_TEMPERATURE, String.valueOf(alnsConfig.maxInitialTemperature));
    props.setProperty(Keys.ALNS_COOLING_RATE, String.valueOf(alnsConfig.coolingRate));
    props.setProperty(Keys.ALNS_LOWER_BOUND, String.valueOf(alnsConfig.lowerBound));
    props.setProperty(Keys.ALNS_UPPER_BOUND, String.valueOf(alnsConfig.upperBound));
    props.setProperty(Keys.ALNS_INSERTION_MODE, alnsConfig.insertionMode.name());
    props.setProperty(Keys.ALNS_ENABLE_REGRET2_REPAIR, String.valueOf(alnsConfig.enableRegret2Repair));
    if (alnsConfig.destroyWeights != null) {
      props.setProperty(Keys.ALNS_DESTROY_WEIGHTS,
        ConfigStringUtils.arrayToString(alnsConfig.destroyWeights));
    }
    if (alnsConfig.repairWeights != null) {
      props.setProperty(Keys.ALNS_REPAIR_WEIGHTS,
        ConfigStringUtils.arrayToString(alnsConfig.repairWeights));
    }
  }

  // ==========================================================
  // Load Methods
  // ==========================================================

  /**
   * Load configuration from properties file
   */
  public static HybCecConfig loadFromPropertiesFile(String filename) throws IOException, ConfigValidationException {
    Properties props = new Properties();
    try (FileInputStream fis = new FileInputStream(filename)) {
      props.load(fis);
    }

    HybCecConfig config = new HybCecConfig();

    try {
      config.loadBasicParameters(props);
      config.loadEliteEvalConfig(props);
      config.loadPhaseOptimalConfig(props);
      config.loadAlwaysOptimalConfig(props);
      config.loadMaxSlackConfig(props);
      config.loadPSOConfig(props);
      config.loadGAConfig(props);
      config.loadEDAConfig(props);
      config.loadACOConfig(props);
      config.loadDEConfig(props);
      config.loadABCConfig(props);
      config.loadSAConfig(props);
      config.loadSHADEConfig(props);
      config.loadALNSConfig(props);
    } catch (NumberFormatException e) {
      throw new ConfigValidationException("Invalid number format in configuration: " + e.getMessage(), e);
    }

    config.validate();

    LOGGER.log(Level.INFO, "Configuration loaded from: {0}", filename);
    return config;
  }

  private void loadBasicParameters(Properties props) throws ConfigValidationException {
    parent1 = getRequiredInt(props, Keys.PARENT1);
    parent2 = getRequiredInt(props, Keys.PARENT2);
    D = getRequiredInt(props, Keys.D);
    nPop = getRequiredInt(props, Keys.N_POP);
    oL = ConfigStringUtils.stringToIntArray(getRequiredProperty(props, Keys.OL));
    oLRand = getRequiredInt(props, Keys.OL_RAND);
    oO = getRequiredInt(props, Keys.OO);
    strSurvive = getRequiredInt(props, Keys.STR_SURVIVE);
    maxItera = getRequiredInt(props, Keys.MAX_ITERA);
    if (props.containsKey(Keys.UPDATE_RATIOS)) {
      getRequiredProperty(props, Keys.UPDATE_RATIOS);
    }
    flagOO = props.containsKey(Keys.FLAG_OO)
      ? ConfigStringUtils.stringToIntArray(getRequiredProperty(props, Keys.FLAG_OO))
      : AlgorithmConstants.createOperationOrderFlags();
  }

  private void loadEliteEvalConfig(Properties props) {
    enableEliteEval = getOptionalBoolean(props, Keys.ENABLE_ELITE_EVAL, false);
    eliteRatio = getOptionalDouble(props, Keys.ELITE_RATIO, 0.2);
  }

  private void loadPhaseOptimalConfig(Properties props) {
    enablePhaseOptimal = getOptionalBoolean(props, Keys.ENABLE_PHASE_OPTIMAL, false);
    optimalSwitchRatio = getOptionalDouble(props, Keys.OPTIMAL_SWITCH_RATIO, 0.8);
  }

  private void loadAlwaysOptimalConfig(Properties props) {
    alwaysUseOptimal = getOptionalBoolean(props, Keys.ALWAYS_USE_OPTIMAL, false);
  }

  private void loadMaxSlackConfig(Properties props) {
    useMaxSlack = getOptionalBoolean(props, Keys.USE_MAX_SLACK, false);
  }

  private void loadPSOConfig(Properties props) {
    if (!props.containsKey(Keys.PSO_COGNITIVE_COEFFICIENT)) return;

    double[] cognitiveCoefficient = ConfigStringUtils.stringToDoubleArray(
      props.getProperty(Keys.PSO_COGNITIVE_COEFFICIENT));
    double[] inertiaWeight = ConfigStringUtils.stringToDoubleArray(
      props.getProperty(Keys.PSO_INERTIA_WEIGHT));
    double vMaxLimit = Double.parseDouble(props.getProperty(Keys.PSO_V_MAX_LIMIT));
    double vMinLimit = Double.parseDouble(props.getProperty(Keys.PSO_V_MIN_LIMIT));
    double upperBound = Double.parseDouble(props.getProperty(Keys.PSO_UPPER_BOUND));
    double lowerBound = Double.parseDouble(props.getProperty(Keys.PSO_LOWER_BOUND));
    BoundaryHandler.BoundaryMode boundaryMode = getOptionalEnum(
      props, Keys.PSO_BOUNDARY_MODE, BoundaryHandler.BoundaryMode.class,
      PSO.PSO_BOUNDARY_MODE);
    double boundaryRepairRatio = getOptionalDouble(
      props, Keys.PSO_BOUNDARY_REPAIR_RATIO, PSO.PSO_BOUNDARY_REPAIR_RATIO);
    boolean enableTVAC = getOptionalBoolean(
      props, Keys.PSO_ENABLE_TVAC, PSO.PSO_ENABLE_TVAC_DEFAULT);
    double tvacC1Start = getOptionalDouble(props, Keys.PSO_TVAC_C1_START, PSO.PSO_TVAC_C1_START);
    double tvacC1End = getOptionalDouble(props, Keys.PSO_TVAC_C1_END, PSO.PSO_TVAC_C1_END);
    double tvacC2Start = getOptionalDouble(props, Keys.PSO_TVAC_C2_START, PSO.PSO_TVAC_C2_START);
    double tvacC2End = getOptionalDouble(props, Keys.PSO_TVAC_C2_END, PSO.PSO_TVAC_C2_END);
    double vMaxRatio = getOptionalDouble(props, Keys.PSO_V_MAX_RATIO, Double.NaN);
    PSO.PSOConfig.Topology topology = getOptionalEnum(
      props, Keys.PSO_TOPOLOGY, PSO.PSOConfig.Topology.class, PSO.PSOConfig.Topology.GLOBAL_BEST);

    psoConfig = new PSO.PSOConfig(
      cognitiveCoefficient, inertiaWeight, vMaxLimit, vMinLimit, upperBound, lowerBound,
      boundaryMode, boundaryRepairRatio, enableTVAC, vMaxRatio, topology,
      tvacC1Start, tvacC1End, tvacC2Start, tvacC2End);
  }

  private void loadGAConfig(Properties props) {
    if (!props.containsKey(Keys.GA_PROB_CROSSOVER)) return;

    double probCrossover = Double.parseDouble(props.getProperty(Keys.GA_PROB_CROSSOVER));
    double probMutation = Double.parseDouble(props.getProperty(Keys.GA_PROB_MUTATION));
    int kTournament = Integer.parseInt(props.getProperty(Keys.GA_K_TOURNAMENT));
    double sigmaScale = getOptionalDouble(props, Keys.GA_SIGMA_SCALE, GeneticAlgorithmNoSelect.GA_DEFAULT_SIGMA_SCALE);
    double upperBounds = Double.parseDouble(props.getProperty(Keys.GA_UPPER_BOUNDS));
    double lowerBounds = Double.parseDouble(props.getProperty(Keys.GA_LOWER_BOUNDS));
    int nElite = getOptionalInt(props, Keys.GA_N_ELITE, 0);
    GeneticAlgorithmNoSelect.GAConfig.ElitismScope elitismScope = getOptionalEnum(
      props, Keys.GA_ELITISM_SCOPE, GeneticAlgorithmNoSelect.GAConfig.ElitismScope.class,
      GeneticAlgorithmNoSelect.GAConfig.ElitismScope.GLOBAL);
    double maxMutationDimRatio = getOptionalDouble(props, Keys.GA_MAX_MUTATION_DIM_RATIO, 1.0);
    boolean adaptiveMutation = getOptionalBoolean(
      props, Keys.GA_ADAPTIVE_MUTATION, GeneticAlgorithmNoSelect.GA_DEFAULT_ADAPTIVE_MUTATION);
    double probMutationLate = getOptionalDouble(props, Keys.GA_PROB_MUTATION_LATE, probMutation);
    double sigmaScaleLate = getOptionalDouble(props, Keys.GA_SIGMA_SCALE_LATE, sigmaScale);
    double maxMutationDimRatioLate = getOptionalDouble(
      props, Keys.GA_MAX_MUTATION_DIM_RATIO_LATE, maxMutationDimRatio);
    double mutationDecayStartRatio = getOptionalDouble(
      props, Keys.GA_MUTATION_DECAY_START_RATIO,
      GeneticAlgorithmNoSelect.GA_DEFAULT_MUTATION_DECAY_START_RATIO);
    int maxGeneration = getOptionalInt(props, Keys.GA_MAX_GENERATION, 1);

    gaConfig = new GeneticAlgorithmNoSelect.GAConfig(
      probCrossover, probMutation, kTournament, sigmaScale, upperBounds, lowerBounds,
      nElite, elitismScope, maxMutationDimRatio, adaptiveMutation, probMutationLate,
      sigmaScaleLate, maxMutationDimRatioLate, mutationDecayStartRatio, maxGeneration);
  }

  private void loadEDAConfig(Properties props) {
    if (!props.containsKey(Keys.EDA_K_ELITE)) return;

    double kElite = Double.parseDouble(props.getProperty(Keys.EDA_K_ELITE));
    double sigmaScale = getOptionalDouble(props, Keys.EDA_SIGMA_SCALE, 1.0);
    double upperBound = Double.parseDouble(props.getProperty(Keys.EDA_UPPER_BOUND));
    double lowerBound = Double.parseDouble(props.getProperty(Keys.EDA_LOWER_BOUND));
    double minSigmaRatio = getOptionalDouble(props, Keys.EDA_MIN_SIGMA_RATIO, 0.001);
    double stagnationBoostRatio = getOptionalDouble(props, Keys.EDA_STAGNATION_BOOST_RATIO, 1.0);
    int stagnationGenerations = getOptionalInt(props, Keys.EDA_STAGNATION_GENERATIONS, 0);

    edaConfig = new EDAGaussSam.EDAConfig(
      kElite, sigmaScale, upperBound, lowerBound, minSigmaRatio,
      stagnationBoostRatio, stagnationGenerations);
  }

  private void loadACOConfig(Properties props) {
    if (!props.containsKey(Keys.ACO_N_SAMPLE)) return;

    double nSample = Double.parseDouble(props.getProperty(Keys.ACO_N_SAMPLE));
    double intenseFactor = Double.parseDouble(props.getProperty(Keys.ACO_INTENSE_FACTOR));
    double deviateDistanceRatio = Double.parseDouble(props.getProperty(Keys.ACO_DEVIATE_DISTANCE_RATIO));
    double upperBound = Double.parseDouble(props.getProperty(Keys.ACO_UPPER_BOUND));
    double lowerBound = Double.parseDouble(props.getProperty(Keys.ACO_LOWER_BOUND));
    double minSigmaRatio = getOptionalDouble(
      props, Keys.ACO_MIN_SIGMA_RATIO, ACOGaussGenerate.ACO_DEFAULT_MIN_SIGMA_RATIO);
    boolean legacySigma = getOptionalBoolean(props, Keys.ACO_LEGACY_SIGMA, false);

    acoConfig = new ACOGaussGenerate.ACOConfig(
      nSample, intenseFactor, deviateDistanceRatio, upperBound, lowerBound, minSigmaRatio, legacySigma);
  }

  private void loadDEConfig(Properties props) {
    if (!props.containsKey(Keys.DE_DIFFERENTIAL_WEIGHT1)) return;

    double[] differentialWeight1 = ConfigStringUtils.stringToDoubleArray(
      props.getProperty(Keys.DE_DIFFERENTIAL_WEIGHT1));
    double[] differentialWeight2 = null;
    if (props.containsKey(Keys.DE_DIFFERENTIAL_WEIGHT2)) {
      differentialWeight2 = ConfigStringUtils.stringToDoubleArray(
        props.getProperty(Keys.DE_DIFFERENTIAL_WEIGHT2));
    }
    double crossoverRate = Double.parseDouble(props.getProperty(Keys.DE_CROSSOVER_RATE));
    double upperBound = Double.parseDouble(props.getProperty(Keys.DE_UPPER_BOUND));
    double lowerBound = Double.parseDouble(props.getProperty(Keys.DE_LOWER_BOUND));
    DEConfig.BoundaryPolicy boundaryPolicy = getOptionalEnum(
      props, Keys.DE_BOUNDARY_POLICY, DEConfig.BoundaryPolicy.class,
      DEConfig.BoundaryPolicy.RANDOM_REINIT);

    deConfig = new DEConfig(
      differentialWeight1, differentialWeight2, crossoverRate, upperBound, lowerBound, boundaryPolicy);
  }

  private void loadABCConfig(Properties props) {
    if (!props.containsKey(Keys.ABC_NUM_NO_IMP)) return;

    int numNoImp = Integer.parseInt(props.getProperty(Keys.ABC_NUM_NO_IMP));
    double upperBound = Double.parseDouble(props.getProperty(Keys.ABC_UPPER_BOUND));
    double lowerBound = Double.parseDouble(props.getProperty(Keys.ABC_LOWER_BOUND));
    double phiScale = getOptionalDouble(props, Keys.ABC_PHI_SCALE, ABC.ABC_PHI_SCALE);
    double phiOffset = getOptionalDouble(props, Keys.ABC_PHI_OFFSET, ABC.ABC_PHI_OFFSET);

    abcConfig = new ABC.ABCConfig(numNoImp, upperBound, lowerBound, phiScale, phiOffset);
  }

  private void loadSAConfig(Properties props) {
    if (!props.containsKey(Keys.SA_GEN_SA)) return;

    int genSA = Integer.parseInt(props.getProperty(Keys.SA_GEN_SA));
    double freezeRate = Double.parseDouble(props.getProperty(Keys.SA_FREEZE_RATE));
    double upperBound = Double.parseDouble(props.getProperty(Keys.SA_UPPER_BOUND));
    double lowerBound = Double.parseDouble(props.getProperty(Keys.SA_LOWER_BOUND));
    double temperature = getOptionalDouble(props, Keys.SA_TEMPERATURE, SACont.SA_DEFAULT_TEMPERATURE);
    int temperatureSchedule = getOptionalInt(
      props, Keys.SA_TEMPERATURE_SCHEDULE, SACont.SA_DEFAULT_TEMPERATURE_SCHEDULE);
    double temperatureAlpha = getOptionalDouble(
      props, Keys.SA_TEMPERATURE_ALPHA, SACont.SA_DEFAULT_TEMPERATURE_ALPHA);
    double temperatureLogOffset = getOptionalDouble(
      props, Keys.SA_TEMPERATURE_LOG_OFFSET, SACont.SA_DEFAULT_TEMPERATURE_LOG_OFFSET);
    boolean adaptiveSigma = getOptionalBoolean(props, Keys.SA_ADAPTIVE_SIGMA, false);

    saConfig = new SACont.SAConfig(
      genSA,
      freezeRate,
      temperature,
      temperatureSchedule,
      temperatureAlpha,
      temperatureLogOffset,
      upperBound,
      lowerBound,
      adaptiveSigma);
  }

  private void loadSHADEConfig(Properties props) {
    if (!props.containsKey(Keys.SHADE_MEMORY_SIZE)) return;

    SHADELoopBrief.SHADEConfig shadeConf = new SHADELoopBrief.SHADEConfig();
    shadeConf.memorySize = Integer.parseInt(props.getProperty(Keys.SHADE_MEMORY_SIZE));
    shadeConf.freqIniForSF = Double.parseDouble(props.getProperty(Keys.SHADE_FREQ_INI_FOR_SF));
    shadeConf.probEigenCrossover = Double.parseDouble(props.getProperty(Keys.SHADE_PROB_EIGEN_CROSSOVER));
    shadeConf.proportionSelectNeighbour = Double.parseDouble(props.getProperty(Keys.SHADE_PROPORTION_SELECT_NEIGHBOUR));
    shadeConf.pbestRate = Double.parseDouble(props.getProperty(Keys.SHADE_PBEST_RATE));
    shadeConf.learnPeriod = Integer.parseInt(props.getProperty(Keys.SHADE_LEARN_PERIOD));
    shadeConf.archiveRate = Double.parseDouble(props.getProperty(Keys.SHADE_ARCHIVE_RATE));
    shadeConf.archiveMaxVolume = Integer.parseInt(props.getProperty(Keys.SHADE_ARCHIVE_MAX_VOLUME));
    shadeConf.currentPopSize = Integer.parseInt(props.getProperty(Keys.SHADE_CURRENT_POP_SIZE));
    shadeConf.crGaussianStd = getOptionalDouble(props, Keys.SHADE_CR_GAUSSIAN_STD, 0.1);
    shadeConf.cauchyScale = getOptionalDouble(props, Keys.SHADE_CAUCHY_SCALE, 0.1);

    // Initialize memory and archive
    shadeConf.memorySF = ListUtils.zeros1DList(shadeConf.memorySize);
    shadeConf.memoryCR = ListUtils.zeros1DList(shadeConf.memorySize);
    shadeConf.memoryFreq = ListUtils.zeros1DList(shadeConf.memorySize);
    shadeConf.archive = new UpdateArchive.ListArchive(shadeConf.archiveMaxVolume);

    shadeConfig = shadeConf;
  }

  private void loadALNSConfig(Properties props) {
    if (!props.containsKey(Keys.ALNS_INNER_ITERATIONS)) return;

    ALNS.ALNSConfig defaults = ALNS.ALNSConfig.defaultConfig();
    int innerIterations = Integer.parseInt(props.getProperty(Keys.ALNS_INNER_ITERATIONS));
    int weightUpdateInterval = getOptionalInt(
      props, Keys.ALNS_WEIGHT_UPDATE_INTERVAL, defaults.weightUpdateInterval);
    int minDestroyCount = getOptionalInt(props, Keys.ALNS_MIN_DESTROY_COUNT, defaults.minDestroyCount);
    double maxDestroyRatio = getOptionalDouble(props, Keys.ALNS_MAX_DESTROY_RATIO, defaults.maxDestroyRatio);
    double sigmaGlobal = getOptionalDouble(props, Keys.ALNS_SIGMA_GLOBAL, defaults.sigmaGlobal);
    double sigmaLocal = getOptionalDouble(props, Keys.ALNS_SIGMA_LOCAL, defaults.sigmaLocal);
    double sigmaBad = getOptionalDouble(props, Keys.ALNS_SIGMA_BAD, defaults.sigmaBad);
    double reactionFactor = getOptionalDouble(props, Keys.ALNS_REACTION_FACTOR, defaults.reactionFactor);
    double initialTemperatureRatio = getOptionalDouble(
      props, Keys.ALNS_INITIAL_TEMPERATURE_RATIO, defaults.initialTemperatureRatio);
    double maxInitialTemperature = getOptionalDouble(
      props, Keys.ALNS_MAX_INITIAL_TEMPERATURE, defaults.maxInitialTemperature);
    double coolingRate = getOptionalDouble(props, Keys.ALNS_COOLING_RATE, defaults.coolingRate);
    double lowerBound = getOptionalDouble(props, Keys.ALNS_LOWER_BOUND, defaults.lowerBound);
    double upperBound = getOptionalDouble(props, Keys.ALNS_UPPER_BOUND, defaults.upperBound);
    operator.insertion.InsertionMode insertionMode = getOptionalEnum(
      props, Keys.ALNS_INSERTION_MODE, operator.insertion.InsertionMode.class, defaults.insertionMode);
    boolean enableRegret2Repair = getOptionalBoolean(
      props, Keys.ALNS_ENABLE_REGRET2_REPAIR, defaults.enableRegret2Repair);
    double[] destroyWeights = getOptionalDoubleArray(
      props, Keys.ALNS_DESTROY_WEIGHTS, defaults.destroyWeights);
    double[] repairWeights = getOptionalDoubleArray(
      props, Keys.ALNS_REPAIR_WEIGHTS, defaults.repairWeights);

    alnsConfig = new ALNS.ALNSConfig(
      innerIterations,
      weightUpdateInterval,
      minDestroyCount,
      maxDestroyRatio,
      sigmaGlobal,
      sigmaLocal,
      sigmaBad,
      reactionFactor,
      initialTemperatureRatio,
      maxInitialTemperature,
      coolingRate,
      lowerBound,
      upperBound,
      insertionMode,
      enableRegret2Repair,
      destroyWeights,
      repairWeights);
  }

  // ==========================================================
  // Helper Methods
  // ==========================================================

  private String getRequiredProperty(Properties props, String key) throws ConfigValidationException {
    String value = props.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      throw new ConfigValidationException("Missing required configuration: " + key);
    }
    return value;
  }

  private int getRequiredInt(Properties props, String key) throws ConfigValidationException {
    return Integer.parseInt(getRequiredProperty(props, key));
  }

  private double getOptionalDouble(Properties props, String key, double defaultValue) {
    String value = props.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return Double.parseDouble(value);
  }

  private int getOptionalInt(Properties props, String key, int defaultValue) {
    String value = props.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return Integer.parseInt(value);
  }

  private boolean getOptionalBoolean(Properties props, String key, boolean defaultValue) {
    String value = props.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
  }

  private double[] getOptionalDoubleArray(Properties props, String key, double[] defaultValue) {
    String value = props.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return ConfigStringUtils.stringToDoubleArray(value);
  }

  private <E extends Enum<E>> E getOptionalEnum(
    Properties props,
    String key,
    Class<E> enumClass,
    E defaultValue) {
    String value = props.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return Enum.valueOf(enumClass, value.trim());
  }

  // ==========================================================
  // Custom Exception
  // ==========================================================

  /**
   * Exception thrown when configuration validation fails
   */
  public static class ConfigValidationException extends Exception {
    public ConfigValidationException(String message) {
      super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
