package com.enricoros.nreal.driver;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.Contract;

/**
 * Companion class for pretty printing USB device info
 *
 * @noinspection SameParameterValue
 */
class UsbUtils {

  private static final String TAG = "NrealManager";


  /**
   * Looks at the connected devices, returns the first one that matches given USB Vendor/Product IDs
   */
  @Nullable
  public static UsbDevice usbFindConnectedDevice(@NonNull UsbManager usbManager, int vendorId, int productId) {
    for (UsbDevice device : usbManager.getDeviceList().values())
      if (device.getVendorId() == vendorId && device.getProductId() == productId)
        return device;
    return null;
  }

  /**
   * Heuristic to find the interfaces
   */
  @Nullable
  public static UsbInterface usbFindHIDInterface(@NonNull UsbDevice device, int interfaceId, int interfaceSubclass) {
    for (int i = 0; i < device.getInterfaceCount(); i++) {
      UsbInterface usbInterface = device.getInterface(i);
      if (usbInterface.getInterfaceClass() != UsbConstants.USB_CLASS_HID)
        continue;
      if (usbInterface.getId() != interfaceId || usbInterface.getInterfaceSubclass() != interfaceSubclass)
        continue;
      return usbInterface;
    }
    return null;
  }

  /**
   * We verify the interface has only 1 input (4:3) and 1 output (5:3) endpoints, and we use those
   */
  @Nullable
  public static Pair<UsbEndpoint, UsbEndpoint> usbFindInterfaceEndpoints(@NonNull UsbInterface usbInterface, int expectedType, int expectedAddressIn, int expectedAddressOut) {
    UsbEndpoint endpointIn = null, endpointOut = null;
    for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
      UsbEndpoint endpoint = usbInterface.getEndpoint(i);
      if (endpoint.getType() != expectedType) {
        Log.w(TAG, "Skipping endpoint " + endpoint.getAddress() + " because it is not of expected type " + expectedType + ", but " + endpoint.getType());
        return null;
      }

      if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
        if (endpoint.getAddress() != expectedAddressIn)
          Log.w(TAG, "Instead of using USB Input Endpoint " + expectedAddressIn + ", we are using endpoint " + endpoint.getAddress());
        endpointIn = endpoint;
      } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
        if (endpoint.getAddress() != expectedAddressOut)
          Log.w(TAG, "Instead of using USB Output Endpoint " + expectedAddressOut + ", we are using endpoint " + endpoint.getAddress());
        endpointOut = endpoint;
      }
    }
    return endpointIn != null && endpointOut != null ? new Pair<>(endpointIn, endpointOut) : null;
  }


  public static void logDevice(String prettyName, @NonNull UsbDevice device) {
    Log.i(TAG, "USB Device information [" + prettyName + "]:");
    Log.i(TAG, " - ID: " + device.getDeviceId() + " (VID: " + device.getVendorId() + ", PID: " + device.getProductId() + "), Name: " + device.getDeviceName() + ", Class: " + prettyUsbInterfaceClass(device.getDeviceClass(), device.getDeviceSubclass()) + ", Protocol: " + device.getDeviceProtocol());
    Log.i(TAG, " - Configurations: " + device.getConfigurationCount());
    for (int i = 0; i < device.getConfigurationCount(); i++) {
      UsbConfiguration usbConfiguration = device.getConfiguration(i);
      logConfiguration(usbConfiguration, "  ");
    }
    Log.i(TAG, " - Interfaces: " + device.getInterfaceCount());
    for (int i = 0; i < device.getInterfaceCount(); i++) {
      UsbInterface usbInterface = device.getInterface(i);
      logInterface(usbInterface, "  ");
    }
  }

  private static void logConfiguration(@NonNull UsbConfiguration usbConfiguration, String prefix) {
    Log.i(TAG, prefix + " - cid: " + usbConfiguration.getId() + valueIfNotNull("name", usbConfiguration.getName()) + ", max power: " + usbConfiguration.getMaxPower() + ", Interfaces: " + usbConfiguration.getInterfaceCount());
  }

  private static void logInterface(@NonNull UsbInterface i, String prefix) {
    Log.i(TAG, prefix + " - " + i.getId() + ":" + i.getAlternateSetting() + valueIfNotNull("name", i.getName()) + ", class: " + prettyUsbInterfaceClass(i.getInterfaceClass(), i.getInterfaceSubclass()) + (i.getInterfaceProtocol() != 0 ? ", protocol: " + i.getInterfaceProtocol() : ""));
    for (int j = 0; j < i.getEndpointCount(); j++) {
      UsbEndpoint usbEndpoint = i.getEndpoint(j);
      logEndpoint(usbEndpoint, prefix + "  ");
    }
  }

  private static void logEndpoint(@NonNull UsbEndpoint e, String prefix) {
    Log.i(TAG, prefix + "   - eid: " + e.getEndpointNumber() + ":" + e.getAttributes() + ", address: " + e.getAddress() + (e.getDirection() == UsbConstants.USB_DIR_IN ? ", Input" : ", Output") + ", type: " + e.getType() + ", size: " + e.getMaxPacketSize() + (e.getInterval() != 1 ? ", interval: " + e.getInterval() : ""));
  }

  @NonNull
  @Contract(pure = true)
  private static String prettyUsbInterfaceClass(int interfaceClass, int interfaceSubclass) {
    String subclassOrUndefined = interfaceSubclass == 0 ? " (undefined)" : " (subclass: " + interfaceSubclass + ")";
    switch (interfaceClass) {
      case UsbConstants.USB_CLASS_AUDIO:
        if (interfaceSubclass == 1) return "Audio Control";
        else if (interfaceSubclass == 2) return "Audio Streaming";
        else return "Audio" + subclassOrUndefined;
      case UsbConstants.USB_CLASS_COMM:
        if (interfaceSubclass == 2) return "Abstract Control Model";
        else if (interfaceSubclass == 6) return "Ethernet Networking Control Model";
        else if (interfaceSubclass == 8) return "ATM Networking";
        else if (interfaceSubclass == 10) return "Wireless Handset Control Model";
        else if (interfaceSubclass == 12) return "Device Management";
        else return "Comm" + subclassOrUndefined;
      case UsbConstants.USB_CLASS_HID:
        if (interfaceSubclass == 1) return "Boot Interface Subclass";
        else if (interfaceSubclass == 2) return "Keyboard";
        else if (interfaceSubclass == 3) return "Mouse";
        else if (interfaceSubclass == 4) return "Digitizer";
        else if (interfaceSubclass == 5) return "Physical Interface Device";
        else if (interfaceSubclass == 6) return "Imaging";
        return "HID" + subclassOrUndefined;
      default:
        return "Unknown" + subclassOrUndefined;
    }
  }

  @NonNull
  @Contract(pure = true)
  private static String valueIfNotNull(String name, String value) {
    if (value != null) return ", " + name + ": " + value;
    return "";
  }

}
