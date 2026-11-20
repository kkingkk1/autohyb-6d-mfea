package core;

import java.util.List;

/**
 * Fitness Evaluator Interface
 *
 * Functional interface for evaluating the fitness of a population.
 * This interface allows for custom fitness evaluation strategies to be injected
 * into the hybrid evolutionary computation framework.
 *
 * @author HybCec Framework
 */
@FunctionalInterface
public interface FitnessEvaluator {
  /**
   * Evaluate the fitness of a population.
   *
   * @param pop Population to evaluate (list of individuals, each represented as double[])
   * @return List of fitness values corresponding to each individual in the population
   */
  List<Double> evaluate(List<double[]> pop);
}
