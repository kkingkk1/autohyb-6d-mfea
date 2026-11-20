package model;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Satellite
 * 
 * Represents a satellite with its time windows and assigned tasks.
 */
public class Satellite {

  private static final int DEFAULT_ID = -1;

  private int id;
  private List<TimeWindow> timeWindows;
  private List<Task> assignedTasks;

  /**
   * Default constructor
   * Creates a satellite with default ID (-1) and empty lists.
   */
  public Satellite() {
    this.id = DEFAULT_ID;
    this.timeWindows = new ArrayList<>();
    this.assignedTasks = new ArrayList<>();
  }

  /**
   * Constructor with satellite ID
   * 
   * @param id Satellite identifier
   */
  public Satellite(int id) {
    this.id = id;
    this.timeWindows = new ArrayList<>();
    this.assignedTasks = new ArrayList<>();
  }

  // Getters
  public int getId() {
    return id;
  }

  public List<TimeWindow> getTimeWindows() {
    return timeWindows;
  }

  public List<Task> getAssignedTasks() {
    return assignedTasks;
  }

  // Setters
  public void setId(int id) {
    this.id = id;
  }

  public void setTimeWindows(List<TimeWindow> timeWindows) {
    this.timeWindows = timeWindows != null ? new ArrayList<>(timeWindows) : new ArrayList<>();
  }

  public void setAssignedTasks(List<Task> assignedTasks) {
    this.assignedTasks = assignedTasks != null ? new ArrayList<>(assignedTasks) : new ArrayList<>();
  }

  /**
   * Add a task to the assigned tasks list
   * 
   * @param task Task to add (must not be null)
   * @throws IllegalArgumentException if task is null
   */
  public void addTask(Task task) {
    if (task == null) {
      throw new IllegalArgumentException("Task cannot be null");
    }
    assignedTasks.add(task);
  }

  /**
   * Remove a task from the assigned tasks list
   * 
   * @param task Task to remove
   * @return true if the task was removed, false otherwise
   */
  public boolean removeTask(Task task) {
    return assignedTasks.remove(task);
  }

  /**
   * Clear all assigned tasks
   */
  public void clearTasks() {
    assignedTasks.clear();
  }

  /**
   * Get the number of assigned tasks
   * 
   * @return Number of assigned tasks
   */
  public int getTaskCount() {
    return assignedTasks.size();
  }

  /**
   * Check if the satellite has any assigned tasks
   * 
   * @return true if there are assigned tasks, false otherwise
   */
  public boolean hasTasks() {
    return !assignedTasks.isEmpty();
  }

  /**
   * Get the number of time windows
   * 
   * @return Number of time windows
   */
  public int getTimeWindowCount() {
    return timeWindows.size();
  }

  @Override
  public String toString() {
    return String.format("Satellite{id=%d, timeWindows=%d, assignedTasks=%d}",
      id, timeWindows.size(), assignedTasks.size());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Satellite satellite = (Satellite) o;
    return id == satellite.id;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
