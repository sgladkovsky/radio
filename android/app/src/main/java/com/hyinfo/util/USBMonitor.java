package com.hyinfo.util;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.text.TextUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal USB control block compatible with native libUSBAudio.so from dab2.
 */
public class USBMonitor {

    public static class UsbControlBlock implements Cloneable {
        private static final Pattern DEVICE_NAME_PATTERN = Pattern.compile("/dev/bus/usb/(\\d+)/(\\d+)");

        private final UsbDevice device;
        private final UsbDeviceConnection connection;
        private final int busNum;
        private final int devNum;

        public UsbControlBlock(UsbManager usbManager, UsbDevice device) {
            this.device = device;
            this.connection = usbManager.openDevice(device);
            if (connection == null) {
                throw new IllegalStateException("Cannot open USB device");
            }

            int bus = 0;
            int dev = 0;
            Matcher matcher = DEVICE_NAME_PATTERN.matcher(device.getDeviceName());
            if (matcher.matches()) {
                bus = Integer.parseInt(matcher.group(1));
                dev = Integer.parseInt(matcher.group(2));
            }
            this.busNum = bus;
            this.devNum = dev;
        }

        private UsbControlBlock(UsbControlBlock other) {
            this.device = other.device;
            this.connection = other.connection;
            this.busNum = other.busNum;
            this.devNum = other.devNum;
        }

        @Override
        public UsbControlBlock clone() {
            return new UsbControlBlock(this);
        }

        public UsbDevice getDevice() {
            return device;
        }

        public int getVenderId() {
            return device.getVendorId();
        }

        public int getProductId() {
            return device.getProductId();
        }

        public int getBusNum() {
            return busNum;
        }

        public int getDevNum() {
            return devNum;
        }

        public synchronized int getFileDescriptor() {
            return connection.getFileDescriptor();
        }

        public String getDeviceName() {
            return device.getDeviceName();
        }

        public String getUSBFSName() {
            String deviceName = getDeviceName();
            if (!TextUtils.isEmpty(deviceName)) {
                String[] parts = deviceName.split("/");
                if (parts.length > 2) {
                    StringBuilder builder = new StringBuilder(parts[0]);
                    for (int i = 1; i < parts.length - 1; i++) {
                        builder.append('/').append(parts[i]);
                    }
                    return builder.toString();
                }
            }
            return "/dev/bus/usb";
        }
    }
}
