package main;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves the shared Scene dataset without binding the checkout to its source path. */
public final class ScenePathResolver {

  public static final String SCENE_ROOT_PROPERTY = "autohyb.sceneRoot";
  public static final String SCENE_ROOT_ENV = "AUTOHYB_SCENE_ROOT";

  private ScenePathResolver() {
  }

  public static String defaultSceneRoot() {
    return sceneRoot().toString();
  }

  public static String defaultSceneDir(String sceneName) {
    return sceneRoot().resolve(sceneName).toString();
  }

  private static Path sceneRoot() {
    Path configured = configuredRoot(System.getProperty(SCENE_ROOT_PROPERTY));
    if (configured != null) {
      return configured;
    }
    configured = configuredRoot(System.getenv(SCENE_ROOT_ENV));
    if (configured != null) {
      return configured;
    }

    Path[] candidates = {
        Paths.get("Scene"),
        Paths.get("..", "Scene")
    };
    for (Path candidate : candidates) {
      if (Files.isDirectory(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    throw new IllegalStateException(
        "Scene root not found. Set -D" + SCENE_ROOT_PROPERTY + "=<path> or "
            + SCENE_ROOT_ENV + ", or place Scene beside the project directory.");
  }

  private static Path configuredRoot(String rawPath) {
    if (rawPath == null || rawPath.trim().isEmpty()) {
      return null;
    }
    return Paths.get(rawPath.trim()).toAbsolutePath().normalize();
  }
}
