package com.enricoros.nreal.driver.data;

import java.util.Arrays;

public class MagnetometerPreprocessor {
  private final float cutoffFrequency;
  private final int minIntRange;

  private final int[] minValues;
  private final int[] maxValues;

  private final float[] prevFilteredData;
  private final float[] normalizedData;
  private final float[] filteredData;
  private boolean firstSample;

  public MagnetometerPreprocessor(float cutoffFrequency, int minIntRange) {
    this.cutoffFrequency = cutoffFrequency;
    this.minIntRange = minIntRange;
    this.minValues = new int[3];
    this.maxValues = new int[3];
    this.prevFilteredData = new float[3];
    this.normalizedData = new float[3];
    this.filteredData = new float[3];
    this.firstSample = true;
  }

  public float[] process(int[] rawData, float dT) {
    float alpha = 1 / (1 + (dT * cutoffFrequency));

    updateMinMax(rawData);

    boolean minIntRangeConditionMet = true;
    for (int i = 0; i < 3; i++) {
      if (maxValues[i] - minValues[i] < minIntRange) {
        minIntRangeConditionMet = false;
        break;
      }
    }

    if (minIntRangeConditionMet) {
      for (int i = 0; i < 3; i++) {
        int center = (maxValues[i] + minValues[i]) / 2;
        int halfRange = (maxValues[i] - minValues[i]) / 2;
        normalizedData[i] = (float) (rawData[i] - center) / halfRange;
      }
    } else {
      Arrays.fill(normalizedData, 0);
    }

    if (firstSample) {
      System.arraycopy(normalizedData, 0, prevFilteredData, 0, 3);
      firstSample = false;
      return normalizedData;
    }

    for (int i = 0; i < 3; i++) {
      filteredData[i] = alpha * prevFilteredData[i] + (1 - alpha) * normalizedData[i];
    }

    System.arraycopy(filteredData, 0, prevFilteredData, 0, 3);
    return filteredData;
  }

  private void updateMinMax(int[] rawData) {
    for (int i = 0; i < 3; i++) {
      if (firstSample) {
        minValues[i] = rawData[i];
        maxValues[i] = rawData[i];
      } else {
        minValues[i] = Math.min(minValues[i], rawData[i]);
        maxValues[i] = Math.max(maxValues[i], rawData[i]);
      }
    }
  }

  public void resetCalibration() {
    firstSample = true;
  }

  public int[] saveCalibration() {
    if (firstSample)
      return null;
    int[] calibrationData = new int[6];
    System.arraycopy(minValues, 0, calibrationData, 0, 3);
    System.arraycopy(maxValues, 0, calibrationData, 3, 3);
    return calibrationData;
  }

  public void restoreCalibration(int[] calibrationData) {
    if (calibrationData.length == 6) {
      System.arraycopy(calibrationData, 0, minValues, 0, 3);
      System.arraycopy(calibrationData, 3, maxValues, 0, 3);
      Arrays.fill(prevFilteredData, 0);
      firstSample = false;
    } else {
      throw new IllegalArgumentException("Calibration data must have exactly 6 elements");
    }
  }
}
