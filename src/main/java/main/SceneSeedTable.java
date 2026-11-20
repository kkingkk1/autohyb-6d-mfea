package main;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** 读取所有 Hybrid 配置共用的固定 scene/perRun seed 合同。 */
final class SceneSeedTable {

    static final String TABLE_PATH_PROPERTY = "evaluation.seedTable.path";
    static final String TABLE_SOURCE_PROPERTY = "evaluation.seedTable.source";
    static final String NATIVE_290002_SOURCE = "native290002";
    static final String NATIVE_270342_SOURCE = "native270342";
    static final String NATIVE_200042_SOURCE = "native200042";
    static final String NATIVE_410000_SOURCE = "native410000";
    static final String DEFAULT_TABLE_SOURCE = NATIVE_290002_SOURCE;
    static final int FIRST_SEED_ID = 10;
    static final int SEED_COUNT = 20;
    private static final Map<String, String> DEFAULT_TABLE_PATHS = Map.of(
        NATIVE_290002_SOURCE, "scene-seeds-native290002.properties",
        NATIVE_270342_SOURCE, "scene-seeds-native270342.properties",
        NATIVE_200042_SOURCE, "scene-seeds-native200042.properties",
        NATIVE_410000_SOURCE, "scene-seeds-native410000.properties"
    );
    private static volatile LoadedTable cachedTable;

    private SceneSeedTable() {
    }

    static long resolve(String sceneDir, int perRun) {
        validatePerRun(perRun);
        String sceneKey = normalizeSceneKey(sceneDir);
        LoadedTable table = load();
        long[] seeds = table.seedsByScene.get(sceneKey);
        if (seeds == null) {
            throw new IllegalArgumentException(
                "No fixed seed row for scene '" + sceneKey + "' in " + table.path
            );
        }
        return seeds[perRun];
    }

    static int seedIdForPerRun(int perRun) {
        validatePerRun(perRun);
        return FIRST_SEED_ID + perRun;
    }

    static String selectedSource() {
        return System.getProperty(TABLE_PATH_PROPERTY) == null
            ? configuredSource()
            : "custom";
    }

    static String selectedTableName() {
        Path path = load().path;
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    static String normalizeSceneKey(String sceneDir) {
        if (sceneDir == null || sceneDir.isBlank()) {
            throw new IllegalArgumentException("sceneDir must not be blank");
        }
        String normalized = sceneDir.trim().replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static void validatePerRun(int perRun) {
        if (perRun < 0 || perRun >= SEED_COUNT) {
            throw new IllegalArgumentException(
                "perRun must be in [0," + (SEED_COUNT - 1) + "], got: " + perRun
            );
        }
    }

    private static LoadedTable load() {
        Path path = configuredPath();
        LoadedTable current = cachedTable;
        if (current != null && current.path.equals(path)) {
            return current;
        }
        synchronized (SceneSeedTable.class) {
            current = cachedTable;
            if (current != null && current.path.equals(path)) {
                return current;
            }
            LoadedTable loaded = read(path);
            cachedTable = loaded;
            return loaded;
        }
    }

    private static Path configuredPath() {
        String configured = System.getProperty(TABLE_PATH_PROPERTY);
        String tablePath;
        if (configured != null) {
            tablePath = configured.trim();
            if (tablePath.isEmpty()) {
                throw new IllegalArgumentException(TABLE_PATH_PROPERTY + " must not be blank");
            }
        } else {
            tablePath = DEFAULT_TABLE_PATHS.get(configuredSource());
        }
        Path path = Paths.get(tablePath);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    private static String configuredSource() {
        String raw = System.getProperty(TABLE_SOURCE_PROPERTY, DEFAULT_TABLE_SOURCE);
        String source = raw.trim().toLowerCase(Locale.ROOT);
        if (!DEFAULT_TABLE_PATHS.containsKey(source)) {
            throw new IllegalArgumentException(
                TABLE_SOURCE_PROPERTY + " must be one of " + DEFAULT_TABLE_PATHS.keySet()
                    + ", got: " + raw
            );
        }
        return source;
    }

    private static LoadedTable read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Fixed scene seed table not found: " + path);
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixed scene seed table: " + path, e);
        }

        Map<String, long[]> seedsByScene = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("seed.")) {
                throw new IllegalStateException("Unexpected seed table key: " + key);
            }
            int separator = key.lastIndexOf('.');
            if (separator <= "seed.".length() || separator == key.length() - 1) {
                throw new IllegalStateException("Malformed seed table key: " + key);
            }
            String sceneKey = key.substring("seed.".length(), separator);
            int perRun;
            try {
                perRun = Integer.parseInt(key.substring(separator + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Malformed perRun in seed table key: " + key, e);
            }
            validatePerRun(perRun);
            long[] seeds = seedsByScene.computeIfAbsent(sceneKey, ignored -> new long[SEED_COUNT]);
            String value = properties.getProperty(key);
            try {
                seeds[perRun] = Long.parseLong(value.trim());
            } catch (RuntimeException e) {
                throw new IllegalStateException("Malformed seed value for key: " + key, e);
            }
        }

        for (Map.Entry<String, long[]> entry : seedsByScene.entrySet()) {
            for (int perRun = 0; perRun < SEED_COUNT; perRun++) {
                String key = "seed." + entry.getKey() + "." + perRun;
                if (!properties.containsKey(key)) {
                    throw new IllegalStateException("Missing seed table key: " + key);
                }
            }
        }
        return new LoadedTable(path, Map.copyOf(seedsByScene));
    }

    private record LoadedTable(Path path, Map<String, long[]> seedsByScene) {
    }
}
