package main;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenePathResolverTest {

  @TempDir
  Path tempDir;

  @Test
  void systemPropertyOverridesDefaultCandidates() {
    String previous = System.getProperty(ScenePathResolver.SCENE_ROOT_PROPERTY);
    try {
      System.setProperty(ScenePathResolver.SCENE_ROOT_PROPERTY, tempDir.toString());

      assertEquals(
          tempDir.toAbsolutePath().normalize().toString(),
          ScenePathResolver.defaultSceneRoot());
      assertEquals(
          tempDir.resolve("500-5-0-0-24").toAbsolutePath().normalize().toString(),
          ScenePathResolver.defaultSceneDir("500-5-0-0-24"));
    } finally {
      restoreProperty(previous);
    }
  }

  private static void restoreProperty(String previous) {
    if (previous == null) {
      System.clearProperty(ScenePathResolver.SCENE_ROOT_PROPERTY);
    } else {
      System.setProperty(ScenePathResolver.SCENE_ROOT_PROPERTY, previous);
    }
  }
}
