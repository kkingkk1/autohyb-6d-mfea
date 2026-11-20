package operator.crossover;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.ml.distance.EuclideanDistance;
import org.apache.commons.math3.stat.correlation.Covariance;

/**
 * Eigen-based (coordinate-rotated) crossover operator for Differential Evolution.
 *
 * <p>This operator implements an advanced crossover mechanism that rotates the search space
 * into the principal component coordinate system before performing crossover. This approach
 * can improve search efficiency when the problem has correlated variables or elongated basins.
 *
 * <p><strong>Algorithm overview:</strong>
 * <ol>
 *   <li>Select nearest neighbours to the best individual</li>
 *   <li>Compute covariance matrix from selected neighbours</li>
 *   <li>Perform eigen decomposition to obtain principal components</li>
 *   <li>Rotate population and mutant vectors to principal component space</li>
 *   <li>Perform binomial crossover in rotated space</li>
 *   <li>Rotate trial vectors back to original space</li>
 * </ol>
 *
 * <p><strong>Robustness features:</strong>
 * <ul>
 *   <li>Pre-validation of preconditions (sample size, rank, etc.)</li>
 *   <li>Covariance matrix regularization for numerical stability</li>
 *   <li>Condition number checking to detect near-singularity</li>
 *   <li>Automatic fallback to binomial crossover on failure</li>
 *   <li>Thread-safe statistics tracking for monitoring</li>
 * </ul>
 *
 * <p><strong>Performance characteristics:</strong>
 * <ul>
 *   <li><strong>Time complexity:</strong> O(N*D + D³) where N = population size, D = dimensions
 *       (dominated by eigen decomposition O(D³))</li>
 *   <li><strong>Space complexity:</strong> O(N*D + D²) for matrices</li>
 *   <li><strong>Overhead:</strong> Significantly more expensive than binomial crossover;
 *       use sparingly (e.g., with probability 0.1-0.3)</li>
 * </ul>
 *
 * <p><strong>Thread safety:</strong> This class is stateless and thread-safe. Statistics counters
 * use atomic operations for concurrent updates. Input/output lists are not synchronized.
 *
 * <p>This is a utility class and cannot be instantiated.
 *
 * @see CrossoverDE for standard binomial crossover
 */
public final class EigenCrossover {

  // =====================================================
  // Constants
  // =====================================================

  /** Minimum number of neighbours for eigen crossover */
  private static final int MIN_NEIGHBOURS_FOR_EIGEN = 2;

  /** Regularization parameter for covariance matrix */
  private static final double COVARIANCE_REGULARIZATION = 1e-8;

  /** Variance threshold for rank deficiency check */
  private static final double VARIANCE_THRESHOLD = 1e-12;

  /** Condition number threshold for singularity check */
  private static final double CONDITION_NUMBER_THRESHOLD = 1e10;

  /** Minimum eigenvalue threshold */
  private static final double MIN_EIGENVALUE_THRESHOLD = 1e-15;

  /** Warning threshold for fallback ratio */
  private static final double FALLBACK_WARNING_THRESHOLD = 0.3;

  // =====================================================
  // Thread-safe statistics tracking
  // =====================================================

  /** Fallback counter for monitoring Eigen crossover failures */
  private static final AtomicInteger eigenCrossoverFallbackCount = new AtomicInteger(0);

  /** Total attempts counter for Eigen crossover */
  private static final AtomicInteger eigenCrossoverTotalAttempts = new AtomicInteger(0);

  // =====================================================
  // Constructor
  // =====================================================

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws AssertionError always
   */
  private EigenCrossover() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  // =====================================================
  // Main crossover methods
  // =====================================================

  /**
   * Perform eigen-based (coordinate-rotated) crossover with robust fallback - index-based.
   *
   * <p>This method attempts to perform crossover in the principal component space by:
   * <ol>
   *   <li>Selecting nearest neighbours to the best individual</li>
   *   <li>Computing covariance matrix and eigen decomposition</li>
   *   <li>Rotating vectors to principal component space</li>
   *   <li>Performing binomial crossover in rotated space</li>
   *   <li>Rotating back to original space</li>
   * </ol>
   *
   * <p>If any step fails (insufficient samples, rank deficiency, near-singularity, etc.),
   * the method automatically falls back to standard binomial crossover.
   *
   * <p><strong>Performance note:</strong> This operation is expensive (O(D³) due to eigen
   * decomposition). Use with moderate probability (e.g., 0.1-0.3) and sufficient
   * neighbour selection size.
   *
   * @param pop Full population (must not be null or empty, each element is a double[] of length D)
   * @param selectedIndices Indices of individuals to evolve (must be valid indices into pop)
   * @param vi Mutant vectors for selected individuals (must have same size as selectedIndices)
   * @param shadeCR Crossover rates for each individual (must have same size as selectedIndices,
   *                values in [0, 1])
   * @param sortIndex Sorted indices by fitness (descending, must contain valid indices into pop)
   * @param neighbourSelectSize Number of neighbours to use for covariance estimation
   *                           (should be >= D and >= MIN_NEIGHBOURS_FOR_EIGEN)
   * @param random Random number generator (must not be null)
   * @return Trial vectors for selected individuals, same size as selectedIndices
   */
  public static List<double[]> performEigenCrossover(
    List<double[]> pop,
    int[] selectedIndices,
    List<double[]> vi,
    double[] shadeCR,
    List<Integer> sortIndex,
    int neighbourSelectSize,
    Random random) {

    eigenCrossoverTotalAttempts.incrementAndGet();

    int selectSize = selectedIndices.length;
    int D = pop.get(0).length;

    // Pre-validation
    String failureReason = validateEigenCrossoverPreconditions(
      neighbourSelectSize, D, pop.size()
    );

    if (failureReason == null) {
      try {
        // Attempt eigen crossover
        return attemptEigenCrossover(
          pop, selectedIndices, vi, shadeCR, sortIndex,
          neighbourSelectSize, D, selectSize, random
        );
      } catch (Exception e) {
        failureReason = e.getClass().getSimpleName() + ": " + e.getMessage();
      }
    }

    // Fallback to binomial crossover
    return handleEigenCrossoverFallback(
      pop, selectedIndices, vi, shadeCR, failureReason, random
    );
  }

  // =====================================================
  // Internal implementation methods
  // =====================================================

  /**
   * Validate preconditions for eigen crossover.
   *
   * @param neighbourSelectSize Number of neighbours to select
   * @param D Problem dimension
   * @param popSize Population size
   * @return null if valid, error message otherwise
   */
  private static String validateEigenCrossoverPreconditions(
    int neighbourSelectSize, int D, int popSize) {

    if (neighbourSelectSize < D) {
      return String.format(
        "Insufficient samples for covariance: neighbourSelectSize=%d < D=%d",
        neighbourSelectSize, D
      );
    }
    if (popSize < D) {
      return String.format(
        "Population size (%d) smaller than dimension (%d)",
        popSize, D
      );
    }
    return null;
  }

  /**
   * Attempt eigen crossover (may throw exceptions) - index-based.
   *
   * <p>This method performs the actual eigen crossover computation:
   * <ol>
   *   <li>Select nearest neighbours to best individual</li>
   *   <li>Validate matrix rank</li>
   *   <li>Compute regularized covariance matrix</li>
   *   <li>Check condition number</li>
   *   <li>Perform eigen decomposition</li>
   *   <li>Rotate to principal component space</li>
   *   <li>Perform crossover in rotated space</li>
   *   <li>Rotate back to original space</li>
   * </ol>
   *
   * @param pop Full population
   * @param selectedIndices Indices of individuals to evolve
   * @param vi Mutant vectors for selected individuals
   * @param shadeCR Crossover rates
   * @param sortIndex Sorted indices by fitness
   * @param neighbourSelectSize Number of neighbours to select
   * @param D Problem dimension
   * @param selectSize Number of individuals to evolve
   * @param random Random number generator
   * @return Trial vectors for selected individuals
   * @throws Exception If any computation step fails
   */
  private static List<double[]> attemptEigenCrossover(
    List<double[]> pop,
    int[] selectedIndices,
    List<double[]> vi,
    double[] shadeCR,
    List<Integer> sortIndex,
    int neighbourSelectSize,
    int D,
    int selectSize,
    Random random) throws Exception {

    // Select nearest neighbours to best individual
    double[] bestIndividual = pop.get(sortIndex.get(0));
    EuclideanDistance dist = new EuclideanDistance();

    List<Integer> allIndices = IntStream.range(0, pop.size())
    .boxed()
    .sorted(Comparator.comparingDouble(k -> dist.compute(pop.get(k), bestIndividual)))
    .collect(Collectors.toList());

    double[][] XSelect = new double[neighbourSelectSize][D];
    for (int i = 0; i < neighbourSelectSize; i++) {
      XSelect[i] = pop.get(allIndices.get(i));
    }

    // Validate rank
    if (!isMatrixFullRank(XSelect, D)) {
      throw new IllegalArgumentException("Covariance matrix is rank-deficient");
    }

    // Compute covariance matrix with regularization
    Covariance cov = new Covariance(XSelect, false);
    RealMatrix C = cov.getCovarianceMatrix();

    // Add regularization to improve numerical stability
    for (int i = 0; i < D; i++) {
      C.setEntry(i, i, C.getEntry(i, i) + COVARIANCE_REGULARIZATION);
    }

    // Check condition number
    double conditionNumber = estimateConditionNumber(C);
    if (conditionNumber > CONDITION_NUMBER_THRESHOLD) {
      throw new IllegalArgumentException(
        String.format("Covariance matrix near-singular (condition: %.2e)", conditionNumber)
      );
    }

    // Eigen decomposition
    EigenDecomposition eig = new EigenDecomposition(C);
    RealMatrix TM = eig.getV();
    RealMatrix TMT = TM.transpose();

    // Build matrices for selected individuals
    double[][] popSelectArray = new double[selectSize][D];
    double[][] viArray = new double[selectSize][D];

    for (int idx = 0; idx < selectSize; idx++) {
      int i = selectedIndices[idx];
      popSelectArray[idx] = pop.get(i);
      viArray[idx] = vi.get(idx);
    }

    RealMatrix popSelectMatrix = new Array2DRowRealMatrix(popSelectArray);
    RealMatrix viMatrix = new Array2DRowRealMatrix(viArray);

    // Rotate to principal component space
    RealMatrix XRotate = popSelectMatrix.multiply(TM);
    RealMatrix viRotate = viMatrix.multiply(TM);

    // Crossover in rotated space
    double[][] UR = XRotate.getData();
    for (int idx = 0; idx < selectSize; idx++) {
      int jRand = random.nextInt(D);
      for (int j = 0; j < D; j++) {
        if (random.nextDouble() < shadeCR[idx] || j == jRand) {
          UR[idx][j] = viRotate.getEntry(idx, j);
        }
      }
    }

    // Rotate back
    double[][] uiArray = new Array2DRowRealMatrix(UR).multiply(TMT).getData();
    List<double[]> ui = new ArrayList<>(selectSize);
    for (double[] row : uiArray) {
      ui.add(row);
    }

    return ui;
  }

  /**
   * Handle fallback when eigen crossover fails - index-based.
   *
   * <p>This method is called when eigen crossover fails for any reason. It:
   * <ol>
   *   <li>Increments the fallback counter</li>
   *   <li>Logs a warning with the failure reason</li>
   *   <li>Checks if fallback ratio exceeds threshold and warns if so</li>
   *   <li>Falls back to standard binomial crossover</li>
   * </ol>
   *
   * @param pop Full population
   * @param selectedIndices Indices of individuals to evolve
   * @param vi Mutant vectors for selected individuals
   * @param shadeCR Crossover rates
   * @param reason Failure reason description
   * @param random Random number generator
   * @return Trial vectors from binomial crossover
   */
  private static List<double[]> handleEigenCrossoverFallback(
    List<double[]> pop,
    int[] selectedIndices,
    List<double[]> vi,
    double[] shadeCR,
    String reason,
    Random random) {

    int fallbackCount = eigenCrossoverFallbackCount.incrementAndGet();
    int totalAttempts = eigenCrossoverTotalAttempts.get();

    // Log warning
    System.err.println("[SHADE WARNING] Eigen crossover fallback (" +
      fallbackCount + "/" + totalAttempts + ")");
    System.err.println("[SHADE WARNING] Reason: " + reason);

    // Check threshold
    double fallbackRatio = (double) fallbackCount / totalAttempts;
    if (fallbackRatio > FALLBACK_WARNING_THRESHOLD) {
      System.err.println(
        String.format("[SHADE ALERT] Fallback ratio (%.2f%%) exceeds threshold (%.2f%%). " +
        "Consider adjusting probEigenCrossover or neighbourSelectSize.",
        fallbackRatio * 100, FALLBACK_WARNING_THRESHOLD * 100)
      );
    }

    // Fallback to binomial crossover
    return performBinomialCrossover(pop, selectedIndices, vi, shadeCR, random);
  }

  /**
   * Standard binomial crossover - index-based.
   *
   * <p>This method is used as a fallback when eigen crossover fails or is not selected.
   * It performs standard DE binomial crossover:
   * <pre>
   * u[j] = v[j]  if (rand() <= CR) or (j == jrand)
   *        x[j]  otherwise
   * </pre>
   *
   * @param pop Full population
   * @param selectedIndices Indices of individuals to evolve
   * @param vi Mutant vectors for selected individuals
   * @param shadeCR Crossover rates
   * @param random Random number generator
   * @return Trial vectors for selected individuals
   */
  private static List<double[]> performBinomialCrossover(
    List<double[]> pop,
    int[] selectedIndices,
    List<double[]> vi,
    double[] shadeCR,
    Random random) {

    int selectSize = selectedIndices.length;
    int D = pop.get(0).length;
    List<double[]> ui = new ArrayList<>(selectSize);

    for (int idx = 0; idx < selectSize; idx++) {
      int i = selectedIndices[idx];
      double[] u = new double[D];
      int jRand = random.nextInt(D);

      for (int j = 0; j < D; j++) {
        if (random.nextDouble() < shadeCR[idx] || j == jRand) {
          u[j] = vi.get(idx)[j];
        } else {
          u[j] = pop.get(i)[j];
        }
      }
      ui.add(u);
    }

    return ui;
  }

  // =====================================================
  // Utility methods
  // =====================================================

  /**
   * Check if matrix has full rank by examining variance in each dimension.
   *
   * <p>This method checks if the matrix is rank-deficient by examining the variance
   * in each dimension. If any dimension has variance below the threshold, the matrix
   * is considered rank-deficient.
   *
   * @param matrix Input matrix (samples x dimensions)
   * @param expectedRank Expected rank (typically equal to number of dimensions)
   * @return true if matrix has full rank, false otherwise
   */
  private static boolean isMatrixFullRank(double[][] matrix, int expectedRank) {
    if (matrix.length < expectedRank) {
      return false;
    }

    for (int j = 0; j < expectedRank; j++) {
      double mean = 0;
      for (int i = 0; i < matrix.length; i++) {
        mean += matrix[i][j];
      }
      mean /= matrix.length;

      double variance = 0;
      for (int i = 0; i < matrix.length; i++) {
        double diff = matrix[i][j] - mean;
        variance += diff * diff;
      }
      variance /= matrix.length;

      if (variance < VARIANCE_THRESHOLD) {
        return false;
      }
    }

    return true;
  }

  /**
   * Estimate condition number of a matrix.
   *
   * <p>The condition number is the ratio of the largest to smallest eigenvalue
   * (in absolute value). A large condition number indicates near-singularity.
   *
   * @param matrix Input matrix
   * @return Estimated condition number, or Double.POSITIVE_INFINITY if computation fails
   *         or smallest eigenvalue is below threshold
   */
  private static double estimateConditionNumber(RealMatrix matrix) {
    try {
      EigenDecomposition eig = new EigenDecomposition(matrix);
      double[] eigenvalues = eig.getRealEigenvalues();

      double maxEigen = Double.NEGATIVE_INFINITY;
      double minEigen = Double.POSITIVE_INFINITY;

      for (double ev : eigenvalues) {
        double absEv = Math.abs(ev);
        if (absEv > maxEigen) maxEigen = absEv;
        if (absEv < minEigen) minEigen = absEv;
      }

      if (minEigen < MIN_EIGENVALUE_THRESHOLD) {
        return Double.POSITIVE_INFINITY;
      }

      return maxEigen / minEigen;
    } catch (Exception e) {
      return Double.POSITIVE_INFINITY;
    }
  }

  // =====================================================
  // Statistics methods
  // =====================================================

  /**
   * Get statistics about Eigen crossover fallback usage.
   *
   * <p>This method returns a string describing the current fallback statistics,
   * including the number of fallbacks, total attempts, and fallback ratio.
   *
   * @return Statistics string, or message if no attempts yet
   */
  public static String getEigenCrossoverStats() {
    int totalAttempts = eigenCrossoverTotalAttempts.get();
    if (totalAttempts == 0) {
      return "No Eigen crossover attempts yet.";
    }

    int fallbackCount = eigenCrossoverFallbackCount.get();
    double fallbackRatio = (double) fallbackCount / totalAttempts;

    return String.format(
      "Eigen Crossover Stats: %d fallbacks / %d attempts (%.2f%%)",
      fallbackCount, totalAttempts, fallbackRatio * 100
    );
  }

  /**
   * Reset Eigen crossover statistics.
   *
   * <p>This method resets both the fallback counter and total attempts counter to zero.
   * Useful for starting fresh statistics tracking or for testing purposes.
   */
  public static void resetEigenCrossoverStats() {
    eigenCrossoverFallbackCount.set(0);
    eigenCrossoverTotalAttempts.set(0);
  }

  /**
   * Get the current fallback count.
   *
   * @return Number of times eigen crossover has fallen back to binomial crossover
   */
  public static int getFallbackCount() {
    return eigenCrossoverFallbackCount.get();
  }

  /**
   * Get the total number of eigen crossover attempts.
   *
   * @return Total number of eigen crossover attempts
   */
  public static int getTotalAttempts() {
    return eigenCrossoverTotalAttempts.get();
  }

  /**
   * Get the current fallback ratio.
   *
   * @return Fallback ratio (fallbacks / total attempts), or 0.0 if no attempts yet
   */
  public static double getFallbackRatio() {
    int totalAttempts = eigenCrossoverTotalAttempts.get();
    if (totalAttempts == 0) {
      return 0.0;
    }
    return (double) eigenCrossoverFallbackCount.get() / totalAttempts;
  }
}
