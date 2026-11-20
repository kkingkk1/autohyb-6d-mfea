package core.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import core.state.HybCecState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FitnessManagerLandscapeRegressionTest {

  @Test
  void afterIterationUpdateRecordsNeutralRatioFromCurrentPopulation() {
    HybCecState state = new HybCecState();
    state.setGbestFit(100.0);
    state.setGen(0);
    state.setPopFit(List.of(100.0, 99.8, 99.7, 80.0));
    state.setBestFitRecord(new ArrayList<>(List.of(0.0, 0.0)));

    FitnessManager manager = new FitnessManager(state, 2);
    manager.afterIterationUpdate(false);

    assertEquals(0.75, state.getNeutralRatio(), 1.0e-9);
    assertEquals(1, state.getFlagImprove());
    assertEquals(100.0, state.getBestFitRecord().get(0), 1.0e-9);
  }
}
