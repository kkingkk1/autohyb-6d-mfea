package model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimeWindowTest {

  @Test
  void cachedTimesPreserveExactIrregularValuesAndRecordIdentity() {
    TimeWindow window = window();
    List<AttitudeRecord> records = List.of(record(1.4), record(4.2), record(4.2), record(12.5));
    window.setAttitudeRecords(records);
    double[] times = window.getAttitudeRecordTimesUnsafe();
    assertArrayEquals(new double[]{1.4, 4.2, 4.2, 12.5}, times);
    assertSame(times, window.getAttitudeRecordTimesUnsafe());
    for (int i = 0; i < records.size(); i++) {
      assertSame(records.get(i), window.getAttitudeRecord(i));
      assertEquals(Double.doubleToRawLongBits(records.get(i).getTotalSeconds()),
        Double.doubleToRawLongBits(times[i]));
    }
  }

  @Test
  void everyRecordMutationInvalidatesCacheWithoutChangingOldSnapshot() {
    TimeWindow window = window();
    window.addAttitudeRecord(record(1));
    double[] first = window.getAttitudeRecordTimesUnsafe();
    window.addAttitudeRecord(record(2));
    double[] added = window.getAttitudeRecordTimesUnsafe();
    assertNotSame(first, added);
    assertArrayEquals(new double[]{1}, first);
    assertArrayEquals(new double[]{1, 2}, added);
    window.addAttitudeRecords(List.of(record(3), record(4)));
    assertArrayEquals(new double[]{1, 2, 3, 4}, window.getAttitudeRecordTimesUnsafe());
    List<AttitudeRecord> replacement = new ArrayList<>(List.of(record(9)));
    window.setAttitudeRecords(replacement);
    replacement.clear();
    assertArrayEquals(new double[]{9}, window.getAttitudeRecordTimesUnsafe());
    window.clearAttitudeRecords();
    assertArrayEquals(new double[0], window.getAttitudeRecordTimesUnsafe());
    window.setAttitudeRecords(null);
    assertArrayEquals(new double[0], window.getAttitudeRecordTimesUnsafe());
    window.addAttitudeRecord(record(11));
    assertArrayEquals(new double[]{11}, window.getAttitudeRecordTimesUnsafe());
  }

  @Test
  void nullRecordKeepsOriginalIndexedReadAndExceptionBehavior() {
    TimeWindow window = window();
    window.setAttitudeRecords(Arrays.asList(record(3), null));
    assertNull(window.getAttitudeRecordTimesUnsafe());
    assertEquals(3, window.getAttitudeRecordTimeSeconds(0));
    assertThrows(NullPointerException.class, () -> window.getAttitudeRecordTimeSeconds(1));
    assertThrows(IndexOutOfBoundsException.class, () -> window.getAttitudeRecordTimeSeconds(2));
  }

  @Test
  void recordSubclassKeepsLiveTimeGetter() {
    TimeWindow window = window();
    double[] liveTime = {1};
    AttitudeRecord record = new AttitudeRecord(2026, 1, 1, 0, 0, 1, 0, 0) {
      @Override public double getTotalSeconds() { return liveTime[0]; }
    };
    window.addAttitudeRecord(record);
    assertNull(window.getAttitudeRecordTimesUnsafe());
    assertEquals(1, window.getAttitudeRecordTimeSeconds(0));
    liveTime[0] = 9;
    assertEquals(9, window.getAttitudeRecordTimeSeconds(0));
  }

  @Test
  void windowSubclassKeepsOriginalAccessors() {
    TimeWindow window = new TimeWindow(0, 0, 2026, 1, 1, 0, 0, 0, 2026, 1, 1, 0, 0, 20) {};
    window.addAttitudeRecord(record(5));
    assertNull(window.getAttitudeRecordTimesUnsafe());
    assertEquals(5, window.getAttitudeRecordTimeSeconds(0));
  }

  @Test
  void concurrentFirstReadsPublishOneCompleteArray() throws Exception {
    TimeWindow window = window();
    for (int i = 0; i < 1000; i++) window.addAttitudeRecord(record(i * 1.5));
    CyclicBarrier barrier = new CyclicBarrier(8);
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<java.util.concurrent.Callable<double[]>> reads = new ArrayList<>();
      for (int i = 0; i < 8; i++) reads.add(() -> {
        barrier.await(5, TimeUnit.SECONDS);
        double[] times = window.getAttitudeRecordTimesUnsafe();
        assertEquals(1000, times.length);
        for (int j = 0; j < times.length; j++) assertEquals(j * 1.5, times[j]);
        return times;
      });
      var results = executor.invokeAll(reads);
      double[] times = results.get(0).get();
      for (var result : results) assertSame(times, result.get());
    }
  }

  private static TimeWindow window() {
    return new TimeWindow(0, 0, 2026, 1, 1, 0, 0, 0, 2026, 1, 1, 0, 0, 20);
  }

  private static AttitudeRecord record(double second) {
    return new AttitudeRecord(2026, 1, 1, 0, 0, second, 0, 0);
  }

  @Test
  void getAttitudeRecordTimeSecondsMatchesRecordTime() {
    TimeWindow window = new TimeWindow(
      1,
      2,
      2026,
      1,
      1,
      0,
      0,
      0.0,
      2026,
      1,
      1,
      0,
      0,
      3.0
    );
    window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, 0.0, 0.0, 0.0));
    window.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, 1.5, 0.0, 0.0));

    assertEquals(
      window.getAttitudeRecord(1).getTotalSeconds(),
      window.getAttitudeRecordTimeSeconds(1)
    );
    assertThrows(IndexOutOfBoundsException.class, () -> window.getAttitudeRecordTimeSeconds(2));
  }
}
