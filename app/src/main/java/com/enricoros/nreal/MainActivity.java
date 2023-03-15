package com.enricoros.nreal;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.enricoros.nreal.databinding.ActivityMainBinding;
import com.enricoros.nreal.driver.ImuDataRaw;
import com.enricoros.nreal.driver.NrealManager;

public class MainActivity extends AppCompatActivity {

  private NrealManager nrealManager;
  private ActivityMainBinding binding;
  private ImuDataRaw mImuDataRaw;


  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    appendLog("Welcome. Logs will appear below.\n");

    nrealManager = new NrealManager(getApplicationContext(), mNrealListener);
  }


  @Override
  protected void onStart() {
    super.onStart();
    appendLog("onStart -> connectToNrealUsbDevice()");
    nrealManager.connectToNrealUsbDevice();
  }

  @Override
  protected void onStop() {
    super.onStop();
    appendLog("onStop -> closeNrealUsbDevice()");
    nrealManager.closeNrealUsbDevice();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    appendLog("onNewIntent -> connectToNrealUsbDevice()");
    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    if (device != null)
      nrealManager.connectToNrealUsbDevice();
  }


  private final NrealManager.Listener mNrealListener = new NrealManager.Listener() {
    @Override
    public void onDeviceConnected() {
      appendLog("onDeviceConnected: great");
    }

    @Override
    public void onDeviceDisconnected() {
      appendLog("onDeviceDisconnected: bye");
    }

    @Override
    public void onPermissionDenied() {
      appendLog("onPermissionDenied: please grant permission");
    }

    @Override
    public void onConnectionError(String error) {
      appendLog("onConnectionError: " + error);
    }

    @Override
    public void onNewDataTemp(ImuDataRaw imuDataRawCopy) {
      mImuDataRaw = imuDataRawCopy;
      updateStatus();
    }

    @Override
    public void onButtonPressedTemp(int buttonId, int relatedValue) {
      appendLog("onButtonPressedTemp: btn=" + buttonId + ", relatedValue=" + relatedValue);
    }
  };


  private void appendLog(String message) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (!isUiContext()) {
        runOnUiThread(() -> appendLog("Error: this did not come from the UI thread -- " + message));
        return;
      }
    }
    binding.logTextView.append(message + "\n");
    binding.scrollView.post(() -> binding.scrollView.fullScroll(View.FOCUS_DOWN));
    updateStatus();
  }

  @SuppressLint("SetTextI18n")
  private void updateStatus() {
    if (nrealManager != null)
      binding.statusTextContent.setText("" +
          (nrealManager.isDeviceConnected() ? "Connected" : "Disconnected") + ", " +
          (nrealManager.isDeviceStreaming() ? "and Streaming" : "NOT streaming") + "\n" +
          (mImuDataRaw == null ? "No IMU data" : mImuDataRaw.toString())
      );
  }

}