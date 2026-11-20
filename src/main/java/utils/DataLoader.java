package utils;

import model.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data loading utilities
 *
 * THREAD-SAFETY: This class is now thread-safe as it no longer uses static shared state.
 * All data is passed through method parameters and return values.
 */
public class DataLoader {

  // File format constants
  private static final int TASK_DATA_COLUMN_COUNT = 5;
  private static final int TIME_WINDOW_COLUMN_COUNT = 12;
  private static final int ATTITUDE_RECORD_COLUMN_COUNT = 8;
  private static final boolean READ_ODD_LINES_ONLY = true;
  private static final String SCENE_CACHE_PROPERTY = "autohyb.scene.cache";
  private static final ConcurrentHashMap<SceneCacheKey, DataSet> SCENE_CACHE = new ConcurrentHashMap<>();

  // Statistics tracking
  private static class LoadStatistics {
    int validLines = 0;
    int skippedLines = 0;
    int parseErrors = 0;
    int validationErrors = 0;

    @Override
    public String toString() {
      return String.format("Valid: %d, Skipped: %d, Parse errors: %d, Validation errors: %d",
        validLines, skippedLines, parseErrors, validationErrors);
    }
  }

  /**
   * Validate task data
   *
   * @param task Task to validate
   * @throws IllegalArgumentException If validation fails
   */
  private static void validateTask(Task task) throws IllegalArgumentException {
    if (task.getImagingDuration() <= 0) {
      throw new IllegalArgumentException(
        String.format("Invalid duration for task %d: %d (must be positive)",
        task.getId(), task.getImagingDuration()));
    }
    if (task.getPriority() < 0) {
      throw new IllegalArgumentException(
        String.format("Invalid priority for task %d: %d (must be non-negative)",
        task.getId(), task.getPriority()));
    }
  }

  /**
   * Load taskList from tasklist.txt
   *
   * @param filePath Task list file path
   * @return List of loaded tasks
   * @throws IOException Exception when reading file
   */
  public static List<Task> loadTasks(String filePath) throws IOException {
    List<Task> taskList = new ArrayList<>();
    LoadStatistics stats = new LoadStatistics();

    try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
      String line;
      int lineNumber = 0;

      while ((line = reader.readLine()) != null) {
        lineNumber++;

        // Only read odd lines (based on configuration)
        if (READ_ODD_LINES_ONLY && lineNumber % 2 == 0) {
          stats.skippedLines++;
          continue;
        }

        String[] parts = line.trim().split("\\s+");

        if (parts.length < TASK_DATA_COLUMN_COUNT) {
          System.err.println("Warning: Invalid line format at line " + lineNumber +
            " (expected " + TASK_DATA_COLUMN_COUNT + " columns, got " + parts.length + "): " + line);
          stats.parseErrors++;
          continue;
        }

        try {
          int id = Integer.parseInt(parts[0]);
          double lon = Double.parseDouble(parts[1]);
          double lat = Double.parseDouble(parts[2]);
          int priority = Integer.parseInt(parts[3]);
          int duration = Integer.parseInt(parts[4]);

          Task task = new Task(id, lon, lat, priority, duration);

          // Validate task data
          try {
            validateTask(task);
            taskList.add(task);
            stats.validLines++;
          } catch (IllegalArgumentException e) {
            System.err.println("Warning: Validation failed at line " + lineNumber + ": " + e.getMessage());
            stats.validationErrors++;
          }
        } catch (NumberFormatException e) {
          System.err.println("Warning: Failed to parse line " + lineNumber + ": " + line);
          stats.parseErrors++;
        }
      }
    }

    // System.out.println("Task loading completed: " + stats);

    return taskList;
  }

  /**
   * Parse a time window data line
   *
   * @param parts Split line parts
   * @param satId Satellite ID
   * @param currentTaskId Current task ID being processed
   * @return Parsed TimeWindow object, or null if parsing fails
   */
  private static TimeWindow parseTimeWindowLine(String[] parts, int satId, int currentTaskId) {
    if (parts.length < TIME_WINDOW_COLUMN_COUNT) {
      return null;
    }

    try {
      return new TimeWindow(
        satId,
        currentTaskId,
        Integer.parseInt(parts[0]),  Integer.parseInt(parts[1]),
        Integer.parseInt(parts[2]),  Integer.parseInt(parts[3]),
        Integer.parseInt(parts[4]),  Double.parseDouble(parts[5]),
        Integer.parseInt(parts[6]),  Integer.parseInt(parts[7]),
        Integer.parseInt(parts[8]),  Integer.parseInt(parts[9]),
        Integer.parseInt(parts[10]), Double.parseDouble(parts[11])
      );
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Load time windows for a single satellite
   *
   * @param filePath Path to time window file
   * @param satId Satellite ID
   * @return Satellite object with loaded time windows
   * @throws IOException Exception when reading file
   */
  private static Satellite loadSatelliteTimeWindows(String filePath, int satId) throws IOException {
    Satellite satellite = new Satellite(satId);
    List<TimeWindow> timeWindows = new ArrayList<>();
    Map<Integer, List<TimeWindow>> taskWindowMap = new HashMap<>();
    int currentTaskId = 0;
    LoadStatistics stats = new LoadStatistics();

    try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
      String line;

      while ((line = reader.readLine()) != null) {
        String[] parts = line.trim().split("\\s+");

        if (parts.length == 0) {
          continue;
        }

        // Skip header lines
        if (parts[0].startsWith("s") || parts[0].equals("0")) {
          stats.skippedLines++;
          continue;
        }

        // Task ID line: single number
        if (parts.length < 2) {
          try {
            currentTaskId = Integer.parseInt(parts[0]);
          } catch (NumberFormatException e) {
            System.err.println("Warning: Failed to parse task ID in " + filePath + ": " + line);
            stats.parseErrors++;
          }
        } else {
          // TimeWindow data line: 12 columns
          TimeWindow window = parseTimeWindowLine(parts, satId, currentTaskId);
          if (window != null) {
            timeWindows.add(window);
            taskWindowMap.computeIfAbsent(currentTaskId, k -> new ArrayList<>()).add(window);
            stats.validLines++;
          } else {
            System.err.println("Warning: Failed to parse time window data in " + filePath + ": " + line);
            stats.parseErrors++;
          }
        }
      }
    }

    satellite.setTimeWindows(timeWindows);
    // System.out.println("Satellite " + satId + " time windows loaded: " + stats);

    return satellite;
  }

  /**
   * Assign time windows to tasks
   *
   * @param taskList List of tasks
   * @param satelliteList List of satellites
   */
  private static void assignTimeWindowsToTasks(
    List<Task> taskList,
    List<Satellite> satelliteList
  ) {
    // Create maps for each satellite to speed up task lookup
    List<Map<Integer, List<TimeWindow>>> satelliteTimeWindowMaps = new ArrayList<>();

    for (Satellite satellite : satelliteList) {
      Map<Integer, List<TimeWindow>> taskWindowMap = new HashMap<>();
      for (TimeWindow window : satellite.getTimeWindows()) {
        taskWindowMap.computeIfAbsent(window.getTaskId(), k -> new ArrayList<>()).add(window);
      }
      satelliteTimeWindowMaps.add(taskWindowMap);
    }

    // Allocate time windows to the corresponding tasks
    for (Task task : taskList) {
      for (int satIndex = 0; satIndex < satelliteTimeWindowMaps.size(); satIndex++) {
        Map<Integer, List<TimeWindow>> taskWindowMap = satelliteTimeWindowMaps.get(satIndex);
        List<TimeWindow> windows = taskWindowMap.get(task.getId());
        if (windows != null) {
          task.addTimeWindowsForSatellite(satIndex, windows);
        }
      }
    }
  }

  /**
   * Load time windows from outputtimewindow_X.txt
   *
   * @param dataDir Data directory
   * @param taskList List of tasks to initialize time windows for
   * @param numSatellites Number of satellites
   * @return List of loaded satellites
   * @throws IOException Exception when reading file
   */
  public static List<Satellite> loadTimeWindows(
    String dataDir,
    List<Task> taskList,
    int numSatellites
  ) throws IOException {

    List<Satellite> satelliteList = new ArrayList<>();

    // Initialize the time window list for each task
    for (Task task : taskList) {
      task.initializeTimeWindows(numSatellites);
    }

    // Load time windows for each satellite
    for (int satId = 0; satId < numSatellites; satId++) {
      String filePath = dataDir + File.separator + "outputtimewindow_" + (satId + 1) + ".txt";
      Satellite satellite = loadSatelliteTimeWindows(filePath, satId);
      satelliteList.add(satellite);
    }

    // Assign time windows to tasks
    assignTimeWindowsToTasks(taskList, satelliteList);

    return satelliteList;
  }

  /**
   * Parse an attitude record data line
   *
   * @param parts Split line parts
   * @return Parsed AttitudeRecord object, or null if parsing fails
   */
  private static AttitudeRecord parseAttitudeRecordLine(String[] parts) {
    if (parts.length < ATTITUDE_RECORD_COLUMN_COUNT) {
      return null;
    }

    try {
      return new AttitudeRecord(
        Integer.parseInt(parts[0]),   // year
        Integer.parseInt(parts[1]),   // month
        Integer.parseInt(parts[2]),   // day
        Integer.parseInt(parts[3]),   // hour
        Integer.parseInt(parts[4]),   // minute
        Double.parseDouble(parts[5]), // second
        Double.parseDouble(parts[6]), // roll
        Double.parseDouble(parts[7])  // pitch
      );
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Load attitude records for a single satellite
   *
   * @param filePath Path to attitude file
   * @return List of attitude record groups (one group per time window)
   * @throws IOException Exception when reading file
   */
  private static List<List<AttitudeRecord>> loadSatelliteAttitudeRecords(String filePath) throws IOException {
    List<List<AttitudeRecord>> allWindowRecords = new ArrayList<>();
    List<AttitudeRecord> currentWindowRecords = new ArrayList<>();
    LoadStatistics stats = new LoadStatistics();

    try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
      String line;

      while ((line = reader.readLine()) != null) {
        String[] parts = line.trim().split("\\s+");

        // Separator line (single number or empty line): start new time window records
        if (parts.length == 1) {
          if (!currentWindowRecords.isEmpty()) {
            allWindowRecords.add(currentWindowRecords);
            currentWindowRecords = new ArrayList<>();
          }
          stats.skippedLines++;
        } else if (parts.length >= ATTITUDE_RECORD_COLUMN_COUNT) {
          // Data line: at least 8 columns
          AttitudeRecord record = parseAttitudeRecordLine(parts);
          if (record != null) {
            currentWindowRecords.add(record);
            stats.validLines++;
          } else {
            System.err.println("Warning: Failed to parse attitude record in " + filePath + ": " + line);
            stats.parseErrors++;
          }
        }
      }

      // Add a record for the last time window.
      if (!currentWindowRecords.isEmpty()) {
        allWindowRecords.add(currentWindowRecords);
      }
    }

    // System.out.println("Attitude records loaded from " + new File(filePath).getName() + ": " + stats);
    return allWindowRecords;
  }

  /**
   * Load attitude records from outputattitude_X.txt
   *
   * @param dataDir Data directory
   * @param satelliteList List of satellites to load attitude records for
   * @throws IOException Exception when reading file
   */
  public static void loadAttitudeRecords(
    String dataDir,
    List<Satellite> satelliteList
  ) throws IOException {

    // Process each satellite
    for (Satellite sat : satelliteList) {
      String filePath = dataDir + File.separator + "outputattitude_" + (sat.getId() + 1) + ".txt";
      List<List<AttitudeRecord>> allWindowRecords = loadSatelliteAttitudeRecords(filePath);

      // Assign records to time windows
      int timeWindowCount = sat.getTimeWindows().size();
      int recordGroupCount = allWindowRecords.size();

      if (timeWindowCount != recordGroupCount) {
        throw new IllegalStateException(
          String.format("Time windows and attitude records count mismatch for satellite %d: " +
          "%d time windows but %d attitude record groups.",
          sat.getId(), timeWindowCount, recordGroupCount)
        );
      }

      for (int k = 0; k < timeWindowCount; k++) {
        sat.getTimeWindows().get(k).setAttitudeRecords(allWindowRecords.get(k));
      }
    }
  }

  /**
   * Load all data at once
   *
   * @param dataDir Data directory
   * @param numTasks Expected number of tasks
   * @param numSatellites Number of satellites
   * @return DataSet containing loaded tasks and satellites
   * @throws IOException Exception when reading file
   */
  public static DataSet loadAllData(String dataDir, int numTasks, int numSatellites) throws IOException {
    if (Boolean.getBoolean(SCENE_CACHE_PROPERTY)) {
      SceneCacheKey cacheKey = SceneCacheKey.from(dataDir, numTasks, numSatellites);
      DataSet cached = SCENE_CACHE.get(cacheKey);
      if (cached != null) {
        return copyDataSet(cached);
      }
      DataSet loaded = loadAllDataUncached(dataDir, numTasks, numSatellites);
      DataSet previous = SCENE_CACHE.putIfAbsent(cacheKey, copyDataSet(loaded));
      return previous == null ? loaded : copyDataSet(previous);
    }
    return loadAllDataUncached(dataDir, numTasks, numSatellites);
  }

  private static DataSet loadAllDataUncached(String dataDir, int numTasks, int numSatellites) throws IOException {
    long startTime = System.currentTimeMillis();

    // Load taskList
    String taskFilePath = dataDir + File.separator + "tasklist.txt";
    List<Task> taskList = loadTasks(taskFilePath);

    // Verify the actual number of loaded tasks.
    if (taskList.size() != numTasks) {
      System.err.println("Warning: Expected " + numTasks + " tasks, but loaded " + taskList.size());
    }

    // Load time windows (needed for attitude distribution)
    List<Satellite> satelliteList = loadTimeWindows(dataDir, taskList, numSatellites);

    // Load attitude records (needs timeWindows to distribute correctly)
    loadAttitudeRecords(dataDir, satelliteList);

    long endTime = System.currentTimeMillis();
    double elapsedSeconds = (endTime - startTime) / 1000.0;
    // System.out.println("Data loaded: " + taskList.size() + " tasks, " + satelliteList.size() + " satellites (" + String.format("%.2f", elapsedSeconds) + "s)");

    return new DataSet(taskList, satelliteList);
  }

  private static DataSet copyDataSet(DataSet source) {
    Map<Integer, Task> taskCopiesById = new HashMap<>();
    List<Task> taskCopies = new ArrayList<>(source.taskList.size());
    for (Task task : source.taskList) {
      Task copiedTask = new Task(
        task.getId(),
        task.getLongitude(),
        task.getLatitude(),
        task.getPriority(),
        task.getImagingDuration()
      );
      copiedTask.initializeTimeWindows(source.satelliteList.size());
      taskCopies.add(copiedTask);
      taskCopiesById.put(copiedTask.getId(), copiedTask);
    }

    List<Satellite> satelliteCopies = new ArrayList<>(source.satelliteList.size());
    for (Satellite satellite : source.satelliteList) {
      Satellite copiedSatellite = new Satellite(satellite.getId());
      List<TimeWindow> copiedWindows = new ArrayList<>(satellite.getTimeWindows().size());
      for (TimeWindow window : satellite.getTimeWindows()) {
        TimeWindow copiedWindow = copyTimeWindow(window);
        copiedWindows.add(copiedWindow);
        Task copiedTask = taskCopiesById.get(copiedWindow.getTaskId());
        if (copiedTask != null) {
          copiedTask.addTimeWindowsForSatellite(copiedWindow.getSatelliteId(), Collections.singletonList(copiedWindow));
        }
      }
      copiedSatellite.setTimeWindows(copiedWindows);
      satelliteCopies.add(copiedSatellite);
    }
    return new DataSet(taskCopies, satelliteCopies);
  }

  private static TimeWindow copyTimeWindow(TimeWindow source) {
    TimeWindow copied = new TimeWindow(
      source.getSatelliteId(),
      source.getTaskId(),
      0, 0, 0, 0, 0, source.getStartTime(),
      0, 0, 0, 0, 0, source.getEndTime()
    );
    List<AttitudeRecord> attitudeCopies = new ArrayList<>(source.getAttitudeRecords().size());
    for (AttitudeRecord record : source.getAttitudeRecords()) {
      attitudeCopies.add(new AttitudeRecord(
        0, 0, 0,
        0, 0, record.getTotalSeconds(),
        record.getRollAngle(),
        record.getPitchAngle()
      ));
    }
    copied.setAttitudeRecords(attitudeCopies);
    return copied;
  }

  private static final class SceneCacheKey {
    private final String absolutePath;
    private final long modifiedTime;
    private final int numTasks;
    private final int numSatellites;

    private SceneCacheKey(String absolutePath, long modifiedTime, int numTasks, int numSatellites) {
      this.absolutePath = absolutePath;
      this.modifiedTime = modifiedTime;
      this.numTasks = numTasks;
      this.numSatellites = numSatellites;
    }

    static SceneCacheKey from(String dataDir, int numTasks, int numSatellites) throws IOException {
      Path path = Paths.get(dataDir).toAbsolutePath().normalize();
      return new SceneCacheKey(
        path.toString(),
        Files.getLastModifiedTime(path).toMillis(),
        numTasks,
        numSatellites
      );
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof SceneCacheKey)) {
        return false;
      }
      SceneCacheKey that = (SceneCacheKey) o;
      return modifiedTime == that.modifiedTime
        && numTasks == that.numTasks
        && numSatellites == that.numSatellites
        && Objects.equals(absolutePath, that.absolutePath);
    }

    @Override
    public int hashCode() {
      return Objects.hash(absolutePath, modifiedTime, numTasks, numSatellites);
    }
  }

  /**
   * DataSet container for loaded data
   */
  public static class DataSet {
    // Keep public fields for backward compatibility
    public final List<Task> taskList;
    public final List<Satellite> satelliteList;

    public DataSet(List<Task> taskList, List<Satellite> satelliteList) {
      if (taskList == null || satelliteList == null) {
        throw new IllegalArgumentException("Task list and satellite list cannot be null");
      }
      // Store as ArrayList for better performance
      this.taskList = new ArrayList<>(taskList);
      this.satelliteList = new ArrayList<>(satelliteList);
    }

    /**
     * Get unmodifiable view of task list (recommended over direct field access)
     *
     * @return Unmodifiable list of tasks
     */
    public List<Task> getTaskList() {
      return Collections.unmodifiableList(taskList);
    }

    /**
     * Get unmodifiable view of satellite list (recommended over direct field access)
     *
     * @return Unmodifiable list of satellites
     */
    public List<Satellite> getSatelliteList() {
      return Collections.unmodifiableList(satelliteList);
    }

    /**
     * Get number of tasks
     *
     * @return Number of tasks
     */
    public int getTaskCount() {
      return taskList.size();
    }

    /**
     * Get number of satellites
     *
     * @return Number of satellites
     */
    public int getSatelliteCount() {
      return satelliteList.size();
    }

    @Override
    public String toString() {
      return String.format("DataSet{tasks=%d, satellites=%d}",
        taskList.size(), satelliteList.size());
    }
  }
}
