package main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SceneSeedTableTest {

  private String previousSource;
  private String previousPath;

  @BeforeEach
  void useDefaultSeedSource() {
    previousSource = System.getProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY);
    previousPath = System.getProperty(SceneSeedTable.TABLE_PATH_PROPERTY);
    System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, SceneSeedTable.NATIVE_290002_SOURCE);
    System.clearProperty(SceneSeedTable.TABLE_PATH_PROPERTY);
  }

  @AfterEach
  void restoreSeedProperties() {
    restoreProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, previousSource);
    restoreProperty(SceneSeedTable.TABLE_PATH_PROPERTY, previousPath);
  }

  @Test
  void perRunZeroMapsToTheFirstNativeSeed() {
    assertEquals(1146972547739808L,
        SceneSeedTable.resolve("E:\\data\\Scene\\500-5-0-0-24\\", 0));
    assertEquals(10, SceneSeedTable.seedIdForPerRun(0));
  }

  @Test
  void perRunOneThroughFourAdvanceWithinTheSceneRow() {
    assertEquals(1146972547739809L,
        SceneSeedTable.resolve("500-5-0-0-24", 1));
    assertEquals(1146972547739812L,
        SceneSeedTable.resolve("500-5-0-0-24", 4));
    assertEquals(11, SceneSeedTable.seedIdForPerRun(1));
    assertEquals(14, SceneSeedTable.seedIdForPerRun(4));
  }

  @Test
  void perRunNineMapsToSeedIdNineteen() {
    assertEquals(1146972547739817L,
        SceneSeedTable.resolve("500-5-0-0-24", 9));
    assertEquals(19, SceneSeedTable.seedIdForPerRun(9));
  }

  @Test
  void sceneRowsAreIndependent() {
    assertEquals(1138252824073983L,
        SceneSeedTable.resolve("1000-10-0-0-12", 0));
    assertEquals(1333574634188465L,
        SceneSeedTable.resolve("10000-100-0-6-12", 2));
  }

  @Test
  void selectedTrainRowsUseTheRecordedNative290002Seeds() {
    assertEquals(1146972547739808L,
        SceneSeedTable.resolve("500-5-0-0-24-train", 0));
    assertEquals(1138252824073987L,
        SceneSeedTable.resolve("1000-10-0-0-12-train", 4));
    assertEquals(1132439674963433L,
        SceneSeedTable.resolve("1000-20-0-0-12-train", 0));
    assertEquals(1162391149189825L,
        SceneSeedTable.resolve("4000-40-0-0-6-train", 4));
  }

  @Test
  void native410000SourceSelectsTheNative410000Table() {
    System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, SceneSeedTable.NATIVE_410000_SOURCE);
    assertEquals(1103879922877097658L,
        SceneSeedTable.resolve("500-5-0-0-24", 0));
    assertEquals(1103879922877097660L,
        SceneSeedTable.resolve("500-5-0-0-24", 4));
  }

  @Test
    void native270342SourceUsesTheRequestedNative290002SeedOrder() {
        System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, SceneSeedTable.NATIVE_270342_SOURCE);
        assertEquals(1146972547739810L,
        SceneSeedTable.resolve("500-5-0-0-24", 0));
    assertEquals(1146972547739815L,
        SceneSeedTable.resolve("500-5-0-0-24", 1));
        assertEquals(1351318495282704L,
        SceneSeedTable.resolve("8000-80-0-0-12", 2));
    }

    @Test
    void native200042SourceUsesTheRequestedNative290002SeedOrder() {
        System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, SceneSeedTable.NATIVE_200042_SOURCE);
        assertEquals(1146114331680284L,
            SceneSeedTable.resolve("500-5-1-0-24", 0));
        assertEquals(1146114331680288L,
            SceneSeedTable.resolve("500-5-1-0-24", 1));
        assertEquals(1146114331680292L,
            SceneSeedTable.resolve("500-5-1-0-24", 2));
        assertEquals(1146114331680295L,
            SceneSeedTable.resolve("500-5-1-0-24", 3));
        assertEquals(1146114331680296L,
            SceneSeedTable.resolve("500-5-1-0-24", 4));
        assertEquals(1146114331680284L,
            SceneSeedTable.resolve("500-5-1-0-24", 5));
        assertEquals(1333574634188478L,
            SceneSeedTable.resolve("10000-100-0-6-12", 4));
    }

  @Test
    void reportsTheSelectedBuiltInSourceAndTableName() {
    assertEquals(SceneSeedTable.NATIVE_290002_SOURCE, SceneSeedTable.selectedSource());
    assertEquals("scene-seeds-native290002.properties", SceneSeedTable.selectedTableName());

    System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, SceneSeedTable.NATIVE_410000_SOURCE);
    assertEquals(SceneSeedTable.NATIVE_410000_SOURCE, SceneSeedTable.selectedSource());
    assertEquals("scene-seeds-native410000.properties", SceneSeedTable.selectedTableName());

        System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, SceneSeedTable.NATIVE_270342_SOURCE);
        assertEquals(SceneSeedTable.NATIVE_270342_SOURCE, SceneSeedTable.selectedSource());
        assertEquals("scene-seeds-native270342.properties", SceneSeedTable.selectedTableName());

        System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, SceneSeedTable.NATIVE_200042_SOURCE);
        assertEquals(SceneSeedTable.NATIVE_200042_SOURCE, SceneSeedTable.selectedSource());
        assertEquals("scene-seeds-native200042.properties", SceneSeedTable.selectedTableName());
    }

  @Test
  void rejectsUnknownSeedSource() {
    System.setProperty(SceneSeedTable.TABLE_SOURCE_PROPERTY, "native999999");
    assertThrows(IllegalArgumentException.class,
        () -> SceneSeedTable.resolve("500-5-0-0-24", 0));
  }

  @Test
  void rejectsUnknownScenesAndUnsupportedPerRuns() {
    assertThrows(IllegalArgumentException.class,
        () -> SceneSeedTable.resolve("unknown-scene", 0));
    assertThrows(IllegalArgumentException.class,
        () -> SceneSeedTable.resolve("500-5-0-0-24", -1));
    assertThrows(IllegalArgumentException.class,
        () -> SceneSeedTable.resolve("500-5-0-0-24", 20));
  }

  private static void restoreProperty(String property, String value) {
    if (value == null) {
      System.clearProperty(property);
    } else {
      System.setProperty(property, value);
    }
  }
}
