package common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EliteConfigArchiveTest {

  @TempDir
  Path tempDir;

  @Test
  void saveAndLoadRoundTripUsesInstanceScopedArchive() throws Exception {
    EliteConfigArchive.ArchiveContext context = new EliteConfigArchive.ArchiveContext(
      "sceneA",
      500,
      5,
      3.0,
      750,
      "GREEDY"
    );
    int[] bestConfig = {4, 1, 2, 3, 4, 1};

    EliteConfigArchive.saveBestConfig(tempDir, context, bestConfig);

    int[][] loaded = EliteConfigArchive.loadEliteConfigs(tempDir, context);

    assertEquals(1, loaded.length);
    assertArrayEquals(AlgorithmConstants.normalizeHybridConfig(bestConfig), loaded[0]);
  }

  @Test
  void corruptedArchiveFallsBackToEmptySeedList() throws Exception {
    EliteConfigArchive.ArchiveContext context = new EliteConfigArchive.ArchiveContext(
      "sceneB",
      200,
      3,
      2.5,
      300,
      "GREEDY"
    );
    Path archivePath = EliteConfigArchive.getArchivePath(tempDir, context);
    Files.createDirectories(archivePath.getParent());
    Files.writeString(archivePath, "{not-json", StandardCharsets.UTF_8);

    int[][] loaded = EliteConfigArchive.loadEliteConfigs(tempDir, context);

    assertEquals(0, loaded.length);
  }

  @Test
  void invalidDimensionArchiveFallsBackToEmptySeedList() throws Exception {
    EliteConfigArchive.ArchiveContext context = new EliteConfigArchive.ArchiveContext(
      "sceneInvalidDimension",
      200,
      3,
      2.5,
      300,
      "GREEDY"
    );
    Path archivePath = EliteConfigArchive.getArchivePath(tempDir, context);
    Files.createDirectories(archivePath.getParent());
    Files.writeString(
      archivePath,
      "{\n"
        + "  \"instanceKey\": \"" + EliteConfigArchive.buildInstanceKey(context) + "\",\n"
        + "  \"eliteConfigs\": [[4, 1, 0, 0, 0, 0, 1]]\n"
        + "}\n",
      StandardCharsets.UTF_8
    );

    int[][] loaded = EliteConfigArchive.loadEliteConfigs(tempDir, context);

    assertEquals(0, loaded.length);
  }

  @Test
  void instanceKeyChangesWhenContextChanges() {
    EliteConfigArchive.ArchiveContext baseContext = new EliteConfigArchive.ArchiveContext(
      "sceneA",
      500,
      5,
      3.0,
      750,
      "GREEDY"
    );
    EliteConfigArchive.ArchiveContext changedContext = new EliteConfigArchive.ArchiveContext(
      "sceneB",
      500,
      5,
      3.0,
      750,
      "GREEDY"
    );

    assertNotEquals(
      EliteConfigArchive.buildInstanceKey(baseContext),
      EliteConfigArchive.buildInstanceKey(changedContext)
    );
  }
}
