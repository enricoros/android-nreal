package com.enricoros.nreal.driver;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;
import android.util.Pair;

import com.enricoros.nreal.driver.data.MagnetometerPreprocessor;

import java.util.Arrays;

/**
 * Implements communication with the device and decoding of the data.
 * Using insights from:
 * - https://github.com/edwatt/real-air/blob/sensor_fusion/src/tracking.c
 * - https://github.com/abls/imu-inspector/blob/master/inspector.c
 *
 * @noinspection SameParameterValue, JavadocLinkAsPlainText
 */
class NrealDeviceThread extends Thread {

  private static final String TAG = "NrealDeviceThread";
  private static final boolean DEBUG_10HZ = false;
  private static final boolean DEBUG_OTHER_COMMANDS = false;

  // constants from the datasheets
  private static final float TICK_SCALE_S = 1f / 1E9f;
  private static final float GYRO_SCALE_DPS = 2000f / 8388608f; // based on 24bit signed int w/ FSR = +/-2000 dps, datasheet option
  private static final float ACCEL_SCALE_G = 16f / 8388608f;    // based on 24bit signed int w/ FSR = +/-16 g, datasheet option

  private final UsbDeviceConnection connection;
  private final UsbEndpoint imuIn;
  private final UsbEndpoint imuOut;
  private final UsbEndpoint otherIn;
  private final ThreadCallbacks threadCallbacks;
  private final byte[] imuData = new byte[64];
  private final byte[] otherData = new byte[64];
  private final ImuDataRaw imuDataRaw = new ImuDataRaw();
  private final MagnetometerPreprocessor magnetometerPreprocessor = new MagnetometerPreprocessor(100.f, 200);

  private boolean mQuit = false;

  private long lastUptimeNs;

  public static final int BUTTON_POWER = 1;
  public static final int BUTTON_BRIGHTNESS_UP = 2;
  public static final int BUTTON_BRIGHTNESS_DOWN = 3;

  public interface ThreadCallbacks {
    void onConnectionError(String s);

    void onNewData(ImuDataRaw data);

    void onButtonPressedTemp(int button, int value);
  }


  public NrealDeviceThread(UsbDeviceConnection deviceConnection, Pair<UsbEndpoint, UsbEndpoint> imuEndpoints, Pair<UsbEndpoint, UsbEndpoint> otherEndpoints, ThreadCallbacks callbacks) {
    connection = deviceConnection;
    imuIn = imuEndpoints.first;
    imuOut = imuEndpoints.second;
    otherIn = otherEndpoints.first;
    threadCallbacks = callbacks;
  }

  public void quit() {
    mQuit = true;
    try {
      join(2000);
    } catch (InterruptedException e) {
      threadCallbacks.onConnectionError("Could not stop reading the IMU");
    }
  }


  public void saveState(SharedPreferences preferences) {
    int[] calibration = magnetometerPreprocessor.saveCalibration();
    if (calibration != null)
      preferences.edit().putString("magnetometer_calibration", Arrays.toString(calibration)).apply();
  }

  public boolean restoreState(SharedPreferences preferences) {
    String calibration = preferences.getString("magnetometer_calibration", null);
    if (calibration != null) {
      String[] split = calibration.substring(1, calibration.length() - 1).split(", ");
      int[] ints = new int[split.length];
      for (int i = 0; i < split.length; i++)
        ints[i] = Integer.parseInt(split[i]);
      magnetometerPreprocessor.restoreCalibration(ints);
      return true;
    }
    return false;
  }


  @Override
  public void run() {
    if (!t_startImu()) {
      threadCallbacks.onConnectionError("Could not start reading the IMU");
      return;
    }
    if (!t_startOther()) {
      threadCallbacks.onConnectionError("Could not start reading the Others");
      return;
    }

    lastUptimeNs = 0;

    // Infinite read until we request to quit or the device is disconnected (mDeviceConnection can be nullified, not the local copy)
    while (!mQuit /*&& mDeviceConnection != null*/) {

      // read the IMU data - must be coming within 200ms (as it's periodic)
      int res = connection.bulkTransfer(imuIn, imuData, 64, 200);
      if (res < 0) {
        threadCallbacks.onConnectionError("Could not read the IMU");
        break;
      }

      // process the IMU data as soon as it comes
      processIMUData();

      // read the other data - if it's there (timeout of 1 second, non blocking)
      res = connection.bulkTransfer(otherIn, otherData, 64, DEBUG_10HZ ? 100 : 1);
      if (res > 0)
        processOtherData();
    }
    Log.e(TAG, "Reader thread finished");
  }


  @SuppressLint("DefaultLocale")
  private void processIMUData() {
    // validity checks
    if (imuData[0] != 1 || imuData[1] != 2 || imuData[12] != (byte) 0xA0 || imuData[13] != 0x0F || imuData[27] != 0x20 || imuData[42] != 0x00) {
      printHex(imuData, 0, 64, "Unexpected IMU data (1): ");
      return;
    }

    // Packet decode
    // [0  ...  1] = 01 02
    // int counter1 = (imuData[2] & 0xFF) | ((imuData[3] & 0xFF) << 8); // seems like some sort of delta / resource usage, averaging ~500
    long uptimeNs = ((long) imuData[4] & 0xFF) | (((long) imuData[5] & 0xFF) << 8) | (((long) imuData[6] & 0xFF) << 16) | (((long) imuData[7] & 0xFF) << 24) |
        (((long) imuData[8] & 0xFF) << 32) | (((long) imuData[9] & 0xFF) << 40) | (((long) imuData[10] & 0xFF) << 48) | (((long) imuData[11] & 0xFF) << 56);
    // [12 ... 17] = A0 0F 00 00 00 01
    int angVelX = (imuData[18] & 0xFF) | ((imuData[19] & 0xFF) << 8) | ((imuData[20] & 0xFF) << 16) | ((imuData[20] & 0x80) != 0 ? (0xFF << 24) : 0);
    int angVelY = (imuData[21] & 0xFF) | ((imuData[22] & 0xFF) << 8) | ((imuData[23] & 0xFF) << 16) | ((imuData[23] & 0x80) != 0 ? (0xFF << 24) : 0);
    int angVelZ = (imuData[24] & 0xFF) | ((imuData[25] & 0xFF) << 8) | ((imuData[26] & 0xFF) << 16) | ((imuData[26] & 0x80) != 0 ? (0xFF << 24) : 0);
    // [27 ... 32] = 20 00 00 00 00 01
    int accelX = (imuData[33] & 0xFF) | ((imuData[34] & 0xFF) << 8) | ((imuData[35] & 0xFF) << 16) | ((imuData[35] & 0x80) != 0 ? (0xFF << 24) : 0);
    int accelY = (imuData[36] & 0xFF) | ((imuData[37] & 0xFF) << 8) | ((imuData[38] & 0xFF) << 16) | ((imuData[38] & 0x80) != 0 ? (0xFF << 24) : 0);
    int accelZ = (imuData[39] & 0xFF) | ((imuData[40] & 0xFF) << 8) | ((imuData[41] & 0xFF) << 16) | ((imuData[41] & 0x80) != 0 ? (0xFF << 24) : 0);
    // [42 ... 47] = 00 80 00 04 00 00
    int magX = (imuData[48] & 0xFF) | ((imuData[49] & 0xFF) << 8);
    int magY = (imuData[50] & 0xFF) | ((imuData[51] & 0xFF) << 8);
    int magZ = (imuData[52] & 0xFF) | ((imuData[53] & 0xFF) << 8);
    //int counter2 = (imuData[54] & 0xFF) | ((imuData[55] & 0xFF) << 8) | ((imuData[56] & 0xFF) << 16) | ((imuData[57] & 0xFF) << 24);
    // [58 ... 63] = 00 00 00 00 (00 | 01) 00
    if (imuData[58] != 0 || imuData[59] != 0 || imuData[60] != 0 || imuData[61] != 0 || (imuData[62] != 0 && imuData[62] != 1) || imuData[63] != 0)
      printHex(imuData, 58, 6, "Unexpected IMU data (2): ");

    // call the callback
    imuDataRaw.update(accelX, accelY, accelZ, angVelX, angVelY, angVelZ, magX, magY, magZ, uptimeNs);

    // DATA PROCESSING

    // Integrate information, if we have a previous time
    if (lastUptimeNs < 1) {
      lastUptimeNs = uptimeNs;
      return;
    }
    float dT = (uptimeNs - lastUptimeNs) * TICK_SCALE_S;
    lastUptimeNs = uptimeNs;

    // Normalize the data for the 3DoF
    float dRoll = (float) (angVelX) * GYRO_SCALE_DPS;
    float dPitch = (float) (angVelY) * GYRO_SCALE_DPS;
    float dYaw = (float) (angVelZ) * GYRO_SCALE_DPS;
    float aX = (float) (accelX) * ACCEL_SCALE_G;
    float aY = (float) (accelY) * ACCEL_SCALE_G;
    float aZ = (float) (accelZ) * ACCEL_SCALE_G;
    float[] mag = magnetometerPreprocessor.process(new int[]{magX, magY, magZ}, dT);

    // convert dRoll to string with 2 decimal places
    imuDataRaw.update(String.format("\n\nGyro (dps):  %+,.1f  %+,.1f  %+,.1f\n\nAcc    (G):  %+,.1f  %+,.1f  %+,.1f\n\nMag (norm):  %.3f  %.3f  %.3f\n\ndT (ms):  %3.0f",
        dRoll, dPitch, dYaw, aX, aY, aZ, mag[0], mag[1], mag[2], dT * 1000));
    threadCallbacks.onNewData(imuDataRaw);
  }

  private void processOtherData() {
    byte btnIndex = otherData[22];
    byte btnValue = otherData[30];

    // we have a partial understanding of the data
    if (btnIndex == 1) {
      // Power button press
      if (btnValue == 1) {
        // Clicked power - screen is ON
        threadCallbacks.onButtonPressedTemp(BUTTON_POWER, 1);
      } else if (btnValue == 0) {
        // Clicked power - screen is OFF
        threadCallbacks.onButtonPressedTemp(BUTTON_POWER, 0);
      } else
        Log.e(TAG, "Unknown screen state: " + btnValue);
    } else if (btnIndex == 2) {
      // Brightness up press
      threadCallbacks.onButtonPressedTemp(BUTTON_BRIGHTNESS_UP, btnValue);
      //mBrightness = btnValue;
    } else if (btnIndex == 3) {
      // Brightness down press
      threadCallbacks.onButtonPressedTemp(BUTTON_BRIGHTNESS_DOWN, btnValue);
      //mBrightness = btnValue;
    } else if (DEBUG_OTHER_COMMANDS)
      Log.e(TAG, "Read Other bytes: 22: " + btnIndex + ", 15: " + otherData[15] + ", 30: " + otherData[30] + ", 23: " + otherData[23] + " - " + Arrays.toString(otherData));
  }

  private boolean t_startImu() {
    // Issues the start reading magic command to the IMU
    // NOTE: compared to the hid_write implementations, this is missing the first byte as it's an internal command for the hid library
    byte[] magicPayload = {(byte) 0xaa, (byte) 0xc5, (byte) 0xd1, 0x21, 0x42, 0x04, 0x00, 0x19, 0x01};
    return connection.bulkTransfer(imuOut, magicPayload, magicPayload.length, 200) >= 0;
  }

  private boolean t_startOther() {
    // The magic command is to read brightness
    // NOTE: doesn't seem to work now - commented out
    // magicPayload to retrieve brightness = {(byte) 0xfd, 0x1e, (byte) 0xb9, (byte) 0xf0, 0x68, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03};
    return true;
  }

  private void printHex(byte[] data, int from, int count, String prefix) {
    StringBuilder sb = new StringBuilder().append(prefix).append(from).append(": ");
    for (int i = from; i < from + count; i++)
      sb.append(String.format("%02X ", data[i] & 0xFF));
    Log.e(TAG, sb.toString());
  }

}
