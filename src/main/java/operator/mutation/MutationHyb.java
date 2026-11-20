package operator.mutation;
import common.RandomProvider;
import java.util.Random;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MutationHyb {

  /**
   * Mutate the current hybrid algorithm encoding:
   * [parent1, parent2, oL1, oL2, operationOrder, survivor].
   */
  public static int[] mutationHyb(
    int[] hybScheme0,
    int[] pos,
    int nPar,
    int nOL,
    int nOO,
    int nSS
  ) {
    return mutationHyb(
      hybScheme0,
      pos,
      nPar,
      nOL,
      nOO,
      nSS,
      RandomProvider.get()
    );
  }

  public static int[] mutationHyb(
    int[] hybScheme0,
    int[] pos,
    int nPar,
    int nOL,
    int nOO,
    int nSS,
    Random rand
  ) {
    int[] hybScheme1 = hybScheme0.clone();
    for (int p : pos) {
      switch (p) {
        case 0:
          hybScheme1[0] = generateUniqueGene(nPar, hybScheme1[1], rand);
          break;
        case 1:
          hybScheme1[1] = generateUniqueGene(nPar, hybScheme1[0], rand);
          break;
        case 2:
          hybScheme1[2] = rand.nextInt(nOL);
          break;
        case 3:
          hybScheme1[3] = rand.nextInt(nOL);
          break;
        case 4:
          hybScheme1[4] = rand.nextInt(nOO);
          break;
        case 5:
          hybScheme1[5] = rand.nextInt(nSS);
          break;
      }
    }
    return hybScheme1;
  }

  private static int generateUniqueGene(int nPar, int forbiddenValue, Random rand) {
    List<Integer> possibleValues = IntStream.range(0, nPar)
    .boxed()
    .collect(Collectors.toList());

    possibleValues.remove(Integer.valueOf(forbiddenValue));

    if (!possibleValues.isEmpty()) {
      int randomIndex = rand.nextInt(possibleValues.size());
      return possibleValues.get(randomIndex);
    } else {
      return forbiddenValue;
    }
  }
}
