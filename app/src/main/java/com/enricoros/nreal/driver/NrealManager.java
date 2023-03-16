package com.enricoros.nreal.driver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** @noinspection SameParameterValue */
public class NrealManager {

  private static final String TAG = "NrealManager";

  private static final int NREAL_AIR_VENDOR_ID = 0x3318;
  private static final int NREAL_AIR_PRODUCT_ID = 0x0424;

  private static final String CUSTOM_BROADCAST_PERMISSION_ACTION = "ai.enrico.mindlet.NREAL_USB_PERMISSION";

  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final Context context;
  private final Listener listener;
  private final UsbManager usbManager;
  private final SharedPreferences preferences;

  private UsbDeviceConnection mDeviceConnection;
  private NrealDeviceThread mThread;


  public interface Listener {
    void onDeviceConnected();

    void onDeviceDisconnected();

    void onPermissionDenied();

    void onConnectionError(String error);

    void onMessage(String message);

    void onNewDataTemp(ImuDataRaw imuDataRawCopy);

    void onButtonPressedTemp(int buttonId, int relatedValue);
  }


  public NrealManager(Context applicationContext, Listener nrealListener) {
    context = applicationContext;
    listener = nrealListener;
    usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    preferences = PreferenceManager.getDefaultSharedPreferences(context);

    // Note: moved registration here to be sure we will not double-register this receiver
    BroadcastReceiver mUsbPermissionReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (!Objects.equals(intent.getAction(), CUSTOM_BROADCAST_PERMISSION_ACTION))
          return;
        if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
          listener.onPermissionDenied();
          return;
        }
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) {
          listener.onConnectionError("No permission granted for device");
          return;
        }
        synchronized (this) {
          onUsbDevicePermissionGranted(device);
        }
      }
    };

    // @noinspection UnspecifiedRegisterReceiverFlag
    context.registerReceiver(mUsbPermissionReceiver, new IntentFilter(CUSTOM_BROADCAST_PERMISSION_ACTION));
  }


  public void connectToNrealUsbDevice() {
    if (mDeviceConnection == null) {
      // find the device on the list of USB devices
      UsbDevice nrealDevice = UsbUtils.usbFindConnectedDevice(usbManager, NREAL_AIR_VENDOR_ID, NREAL_AIR_PRODUCT_ID);
      if (nrealDevice == null) {
        listener.onConnectionError("No attached Nreal devices found");
        return;
      }

      // ask the user for permissions; may continue right away to -> mUsbPermissionReceiver -> onUsbDevicePermissionGranted
      PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(CUSTOM_BROADCAST_PERMISSION_ACTION), PendingIntent.FLAG_MUTABLE);
      usbManager.requestPermission(nrealDevice, permissionIntent);
    }
  }

  public void closeNrealUsbDevice() {
    stopNrealCommunication();
    if (mDeviceConnection != null) {
      mDeviceConnection.close();
      mDeviceConnection = null;
      mThread = null;
      listener.onDeviceDisconnected();
    }
  }

  public boolean isDeviceConnected() {
    return mDeviceConnection != null;
  }

  public boolean isDeviceStreaming() {
    return mThread != null && mThread.isAlive();
  }


  private void onUsbDevicePermissionGranted(UsbDevice device) {
    // [DEV] sanity check
    if (mDeviceConnection != null) {
      Log.e(TAG, "Device already opened and connected");
      return;
    }
    UsbUtils.logDevice("Nreal Air", device);


    // find the interface and endpoints
    List<UsbInterface> usbInterfaces = new ArrayList<>();
    UsbInterface imuInterface = UsbUtils.usbFindHIDInterface(device, 3, 0);
    if (imuInterface == null) {
      listener.onConnectionError("Could not find IMU interface");
      return;
    }
    usbInterfaces.add(imuInterface);
    Pair<UsbEndpoint, UsbEndpoint> imuEndpoints = UsbUtils.usbFindInterfaceEndpoints(imuInterface, UsbConstants.USB_ENDPOINT_XFER_INT, 0x84, 0x05);
    if (imuEndpoints == null || imuEndpoints.first.getMaxPacketSize() != 64) {
      listener.onConnectionError("Could not find IMU endpoints");
      return;
    }
    UsbInterface otherInterface = UsbUtils.usbFindHIDInterface(device, 4, 0);
    if (otherInterface == null) {
      listener.onConnectionError("Could not find other interface");
      return;
    }
    usbInterfaces.add(otherInterface);
    Pair<UsbEndpoint, UsbEndpoint> otherEndpoints = UsbUtils.usbFindInterfaceEndpoints(otherInterface, UsbConstants.USB_ENDPOINT_XFER_INT, 0x86, 0x07);
    if (otherEndpoints == null || otherEndpoints.first.getMaxPacketSize() != 64) {
      listener.onConnectionError("Could not find other endpoints");
      return;
    }

    // connect to the device, and claim all interfaces
    mDeviceConnection = usbManager.openDevice(device);
    if (mDeviceConnection == null) {
      listener.onConnectionError("Could not open device");
      return;
    }
    for (UsbInterface i : usbInterfaces) {
      if (!mDeviceConnection.claimInterface(i, true)) {
        listener.onConnectionError("Could not claim interface " + i.getId() + ":" + i.getAlternateSetting());
        return;
      }
    }

    // start
    listener.onDeviceConnected();

    if (mThread != null) {
      Log.e(TAG, "Reader thread already running");
      return;
    }
    mThread = new NrealDeviceThread(mDeviceConnection, imuEndpoints, otherEndpoints, mReaderCallbacks);
    if (mThread.restoreState(preferences))
      listener.onMessage("Restored Calibration");
    mThread.start();
  }

  private final NrealDeviceThread.ThreadCallbacks mReaderCallbacks = new NrealDeviceThread.ThreadCallbacks() {
    @Override
    public void onConnectionError(String error) {
      uiHandler.post(() -> {
        listener.onConnectionError(error);
        closeNrealUsbDevice();
      });
    }

    @Override
    public void onNewData(ImuDataRaw data) {
      ImuDataRaw dataCopy = new ImuDataRaw(data);
      uiHandler.post(() -> listener.onNewDataTemp(dataCopy));
    }

    @Override
    public void onButtonPressedTemp(int button, int value) {
      uiHandler.post(() -> listener.onButtonPressedTemp(button, value));
    }
  };

  private void stopNrealCommunication() {
    if (mThread != null) {
      mThread.quit();
      mThread.saveState(preferences);
      mThread = null;
    }
  }

}
