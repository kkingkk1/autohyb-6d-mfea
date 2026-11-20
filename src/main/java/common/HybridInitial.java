package common;
import java.util.Random;
import common.RandomProvider;

public class HybridInitial {

  // Gene length (encoding dimension).
  private static final int geneLength = AlgorithmConstants.HYBRID_CONFIG_DIMENSION;

  // Private constructor to prevent instantiation.
  private HybridInitial() {
    throw new UnsupportedOperationException("Utility class should not be instantiated");
  }

  /**
   * Generates hybrid initial population with random gene values.
   * Uses shared Random from RandomProvider for reproducibility.
   *
   * @param nPar Number of parent operators (must be >= 2)
   * @param nOL Number of offspring learning operators (must be > 0)
   * @param nOO Number of offspring optimization operators (must be > 0)
   * @param nSS Number of search space operators (must be > 0)
   * @param nPop Population size (must be > 0)
   * @return 2D array [nPop][6] where each row contains:
   *         G₀: First parent operator index [0, nPar)
   *         G₁: Second parent operator index [0, nPar), different from G₀
   *         G₂: First offspring learning operator index [0, nOL)
   *         G₃: Second offspring learning operator index [0, nOL)
   *         G₄: Offspring optimization operator index [0, nOO)
   *         G₅: Search space operator index [0, nSS)
   * @throws IllegalArgumentException if any parameter is invalid
   */
  public static int[][] hybridInitial(int nPar, int nOL, int nOO, int nSS, int nPop) {
    return hybridInitial(nPar, nOL, nOO, nSS, nPop, false);
  }

  /**
   * Generates hybrid initial population with optional seeding of elite configurations.
   * Uses shared Random from RandomProvider for reproducibility.
   *
   * @param nPar Number of parent operators (must be >= 2)
   * @param nOL Number of offspring learning operators (must be > 0)
   * @param nOO Number of offspring optimization operators (must be > 0)
   * @param nSS Number of search space operators (must be > 0)
   * @param nPop Population size (must be > 0)
   * @param useSeedConfigs If true, first 3-4 individuals are seeded with known good configurations
   * @return 2D array [nPop][6] where each row contains:
   *         G₀: First parent operator index [0, nPar)
   *         G₁: Second parent operator index [0, nPar), different from G₀
   *         G₂: First offspring learning operator index [0, nOL)
   *         G₃: Second offspring learning operator index [0, nOL)
   *         G₄: Offspring optimization operator index [0, nOO)
   *         G₅: Search space operator index [0, nSS)
   * @throws IllegalArgumentException if any parameter is invalid
   */
  public static int[][] hybridInitial(int nPar, int nOL, int nOO, int nSS, int nPop, boolean useSeedConfigs) {
    return hybridInitial(nPar, nOL, nOO, nSS, nPop, useSeedConfigs, (int[][]) null);
  }

  /**
   * Generates hybrid initial population with optional archive-backed seed configs.
   *
   * @param nPar Number of parent operators (must be >= 2)
   * @param nOL Number of offspring learning operators (must be > 0)
   * @param nOO Number of offspring optimization operators (must be > 0)
   * @param nSS Number of search space operators (must be > 0)
   * @param nPop Population size (must be > 0)
   * @param useSeedConfigs Whether to seed elite configurations
   * @param archiveContext Instance-scoped archive context, may be null
   * @return 2D array [nPop][6] initial population
   */
  public static int[][] hybridInitial(
    int nPar,
    int nOL,
    int nOO,
    int nSS,
    int nPop,
    boolean useSeedConfigs,
    EliteConfigArchive.ArchiveContext archiveContext
  ) {
    int[][] archivedSeedConfigs = archiveContext == null
      ? new int[0][]
      : EliteConfigArchive.loadEliteConfigs(archiveContext);
    return hybridInitial(nPar, nOL, nOO, nSS, nPop, useSeedConfigs, archivedSeedConfigs);
  }

  static int[][] hybridInitial(
    int nPar,
    int nOL,
    int nOO,
    int nSS,
    int nPop,
    boolean useSeedConfigs,
    int[][] archivedSeedConfigs
  ) {
    // Validate parameters
    if (nPar < 2) {
      throw new IllegalArgumentException("nPar must be at least 2 (got: " + nPar + ")");
    }
    if (nPop <= 0 || nOL <= 0 || nOO <= 0 || nSS <= 0) {
      throw new IllegalArgumentException("All parameters must be positive (nPop=" + nPop +
        ", nOL=" + nOL + ", nOO=" + nOO + ", nSS=" + nSS + ")");
    }

    // Use shared Random from RandomProvider
    Random rand = RandomProvider.get();
    int[][] x = new int[nPop][geneLength];

    int startIdx = 0;

    // Seed with elite configurations if requested
    if (useSeedConfigs) {
      int[][] seedConfigs = sanitizeSeedConfigurations(archivedSeedConfigs, nPar, nOL, nOO, nSS);
      String seedSource = "archived";
      if (seedConfigs.length == 0) {
        seedConfigs = sanitizeSeedConfigurations(getEliteConfigurations(nPar, nOL, nOO, nSS), nPar, nOL, nOO, nSS);
        seedSource = "default";
      }
      int numSeeds = Math.min(seedConfigs.length, nPop);

      for (int i = 0; i < numSeeds; i++) {
        x[i] = seedConfigs[i].clone();
      }
      startIdx = numSeeds;

      if (numSeeds > 0) {
        System.out.println("[HybridInitial] Seeded " + numSeeds + " " + seedSource
          + " elite configurations into initial population");
      }
    }

    // Fill remaining population with random individuals
    for (int i = startIdx; i < nPop; i++) {
      // Select two different parent indices efficiently
      // Algorithm: pick first parent randomly, then pick second from remaining indices
      int p1 = rand.nextInt(nPar);
      int p2 = rand.nextInt(nPar - 1);
      if (p2 >= p1) {
        p2++;  // Ensure p2 != p1
      }

      x[i][0] = p1;  // G₀: First parent operator
      x[i][1] = p2;  // G₁: Second parent operator (different from p1)

      x[i][2] = rand.nextInt(nOL);  // G₂: First offspring learning operator
      x[i][3] = rand.nextInt(nOL);  // G₃: Second offspring learning operator
      x[i][4] = rand.nextInt(nOO);  // G₄: Offspring optimization operator
      x[i][5] = rand.nextInt(nSS);  // G₅: Search space operator
    }

    return x;
  }

  /**
   * Returns heuristic seed configurations for MFEA initialization.
   *
   * Active parent algorithm indices used by automatic MFEA selection:
   * 0=GA, 1=PSO, 2=EDA, 3=ACO, 4=DE/rand/1, 5=DE/best/1,
   * 6=DE/current-to-rand, 7=ABC, 8=SA, 9=L-SHADE, 10=ALNS
   *
   * @param nPar Number of parent operators
   * @param nOL Number of operation levels
   * @param nOO Number of operation orders
   * @param nSS Number of survival strategies
   * @return Array of elite configurations [numSeeds][6]
   */
  private static int[][] getEliteConfigurations(int nPar, int nOL, int nOO, int nSS) {
    // Define 4 elite configurations
    int[][] eliteConfigs = new int[4][geneLength];

    // Configuration 1: L-SHADE + PSO
    eliteConfigs[0][0] = Math.min(AlgorithmConstants.ALGORITHM_INDEX_LSHADE, nPar - 1);
    eliteConfigs[0][1] = Math.min(AlgorithmConstants.ALGORITHM_INDEX_PSO, nPar - 1);
    eliteConfigs[0][2] = AlgorithmConstants.UPDATE_RATIO_CODE_MIXED_SUBSET;
    eliteConfigs[0][3] = AlgorithmConstants.UPDATE_RATIO_CODE_MIXED_SUBSET;
    eliteConfigs[0][4] = AlgorithmConstants.OPERATION_ORDER_PARALLEL;
    eliteConfigs[0][5] = 2;  // Survival strategy: serial-number replacement

    // Configuration 2: L-SHADE + DE/rand/1
    eliteConfigs[1][0] = Math.min(AlgorithmConstants.ALGORITHM_INDEX_LSHADE, nPar - 1);
    eliteConfigs[1][1] = Math.min(AlgorithmConstants.ALGORITHM_INDEX_DE_RAND_1, nPar - 1);
    eliteConfigs[1][2] = AlgorithmConstants.UPDATE_RATIO_CODE_RANDOM_DECAY;
    eliteConfigs[1][3] = AlgorithmConstants.UPDATE_RATIO_CODE_LOCAL_SEARCH;
    eliteConfigs[1][4] = AlgorithmConstants.OPERATION_ORDER_BEST_NO_IMPROVEMENT_SWITCH;
    eliteConfigs[1][5] = 1;  // Survival strategy: champion/tournament

    // Configuration 3: L-SHADE + ABC
    eliteConfigs[2][0] = Math.min(AlgorithmConstants.ALGORITHM_INDEX_LSHADE, nPar - 1);
    eliteConfigs[2][1] = Math.min(AlgorithmConstants.ALGORITHM_INDEX_ABC, nPar - 1);
    eliteConfigs[2][2] = AlgorithmConstants.UPDATE_RATIO_CODE_ABOVE_MEAN;
    eliteConfigs[2][3] = AlgorithmConstants.UPDATE_RATIO_CODE_LOCAL_SEARCH;
    eliteConfigs[2][4] = AlgorithmConstants.OPERATION_ORDER_ADAPTIVE_CREDIT;
    eliteConfigs[2][5] = 3;  // Survival strategy: rank-based selection

    // Configuration 4: DE/best/1 + DE/current-to-rand
    eliteConfigs[3][0] = Math.min(AlgorithmConstants.ALGORITHM_INDEX_DE_BEST_1, nPar - 1);
    eliteConfigs[3][1] = Math.min(AlgorithmConstants.ALGORITHM_INDEX_DE_CURRENT_TO_RAND, nPar - 1);
    eliteConfigs[3][2] = AlgorithmConstants.UPDATE_RATIO_CODE_MIXED_SUBSET;
    eliteConfigs[3][3] = AlgorithmConstants.UPDATE_RATIO_CODE_WORST_REPAIR;
    eliteConfigs[3][4] = AlgorithmConstants.OPERATION_ORDER_PARALLEL;
    eliteConfigs[3][5] = 2;  // Survival strategy: serial-number replacement

    // Ensure all values are within valid ranges
    for (int i = 0; i < eliteConfigs.length; i++) {
      eliteConfigs[i][2] = Math.min(eliteConfigs[i][2], nOL - 1);
      eliteConfigs[i][3] = Math.min(eliteConfigs[i][3], nOL - 1);
      eliteConfigs[i][4] = Math.min(eliteConfigs[i][4], nOO - 1);
      eliteConfigs[i][5] = Math.min(eliteConfigs[i][5], nSS - 1);
    }

    return eliteConfigs;
  }

  private static int[][] sanitizeSeedConfigurations(
    int[][] seedConfigs,
    int nPar,
    int nOL,
    int nOO,
    int nSS
  ) {
    if (seedConfigs == null || seedConfigs.length == 0) {
      return new int[0][];
    }

    int[][] sanitized = new int[seedConfigs.length][geneLength];
    int validCount = 0;
    for (int[] seedConfig : seedConfigs) {
      if (seedConfig == null) {
        continue;
      }

      int[] config;
      try {
        config = AlgorithmConstants.normalizeHybridConfig(seedConfig);
      } catch (IllegalArgumentException e) {
        continue;
      }
      config[0] = clamp(config[0], 0, nPar - 1);
      config[1] = clamp(config[1], 0, nPar - 1);
      if (config[0] == config[1]) {
        config[1] = (config[0] + 1) % nPar;
      }
      config[2] = clamp(AlgorithmConstants.normalizeUpdatePolicyCode(config[2]), 0, nOL - 1);
      config[3] = clamp(AlgorithmConstants.normalizeUpdatePolicyCode(config[3]), 0, nOL - 1);
      config[4] = clamp(config[4], 0, nOO - 1);
      config[5] = clamp(config[5], 0, nSS - 1);
      sanitized[validCount++] = config;
    }

    int[][] trimmed = new int[validCount][geneLength];
    for (int i = 0; i < validCount; i++) {
      trimmed[i] = sanitized[i];
    }
    return trimmed;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
