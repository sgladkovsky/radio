package au.id.jms.usbaudio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.hyinfo.util.USBMonitor;

import java.io.File;
import java.io.FileOutputStream;

/**
 * JNI wrapper for libUSBAudio.so from dab2_V3.12.
 * Layout must match the original class exactly for native setField_long("mNativePtr").
 */
public class USBAudio {
    private static final String DEFAULT_USBFS = "/dev/bus/usb";
    private static AudioTrack track;

    static {
        System.loadLibrary("USBAudio");
    }

    private String TAG = "USBAudio";
    private boolean receivedData;
    private boolean bStart;
    private boolean bStop = true;
    private boolean bInitUSBAudio;
    private USBMonitor.UsbControlBlock mCtrlBlock;
    public File pcmFile;
    public FileOutputStream fileOutputStream;
    protected long mNativePtr;
    private int SAMPLE_RATE_HZ = 48000;
    private int channel = 2;

    public USBAudio() {
        pcmFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/hy.pcm");
        mNativePtr = nativeCreate();
        Log.d(TAG, "USBAudio: nativeCreate");
    }

    public void initAudio(USBMonitor.UsbControlBlock controlBlock) {
        int result;
        try {
            mCtrlBlock = controlBlock.clone();
            result = nativeInit(
                mNativePtr,
                mCtrlBlock.getVenderId(),
                mCtrlBlock.getProductId(),
                mCtrlBlock.getBusNum(),
                mCtrlBlock.getDevNum(),
                mCtrlBlock.getFileDescriptor(),
                getUSBFSName(mCtrlBlock)
            );
        } catch (Exception error) {
            Log.e(TAG, String.valueOf(error));
            result = -1;
        }

        Log.d(TAG, "initAudio: " + result);
        if (result < 0) {
            return;
        }

        bInitUSBAudio = true;
        setAudioConfig();
    }

    public boolean isInitUSBAudio() {
        return bInitUSBAudio;
    }

    public void setAudioConfig() {
        channel = nativeGetChannelCount(mNativePtr);
        SAMPLE_RATE_HZ = nativeGetSampleRate(mNativePtr);
        int bitResolution = nativeGetBitResolution(mNativePtr);
        Log.d(TAG, "setAudioConfig: Channel " + channel);
        Log.d(TAG, "setAudioConfig: SampleRate " + SAMPLE_RATE_HZ);
        Log.d(TAG, "setAudioConfig: BitResolution " + bitResolution);
        initPlay();
    }

    public void startCapture() {
        Log.d(TAG, "startCapture");
        bStop = false;
        if (bStart) {
            return;
        }
        receivedData = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                nativeStartCapture(mNativePtr);
            }
        }).start();
        bStart = true;
    }

    public void stopCapture() {
        Log.d(TAG, "stopCapture");
        bStop = true;
    }

    public void play() {
        Log.d(TAG, "play");
        bStop = false;
    }

    public void pause() {
        Log.d(TAG, "pause");
        bStop = true;
    }

    public void closeAudio() {
        Log.d(TAG, "closeAudio");
        nativeClose(mNativePtr);
        bInitUSBAudio = false;
        bStart = false;
        if (track != null) {
            track.stop();
            track.release();
            track = null;
        }
    }

    @SuppressWarnings("unused")
    public void pcmData(byte[] data) {
        if (!receivedData) {
            Log.d(TAG, "pcmData: " + (data != null ? data.length : 0));
            receivedData = true;
        }
        if (!bStop && track != null && data != null) {
            track.write(data, 0, data.length);
        }
    }

    private String getUSBFSName(USBMonitor.UsbControlBlock controlBlock) {
        String deviceName = controlBlock.getDeviceName();
        String[] parts = !TextUtils.isEmpty(deviceName) ? deviceName.split("/") : null;
        String usbfs = null;
        if (parts != null && parts.length > 2) {
            StringBuilder builder = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length - 1; i++) {
                builder.append('/').append(parts[i]);
            }
            usbfs = builder.toString();
        }
        if (TextUtils.isEmpty(usbfs)) {
            Log.d(TAG, "failed to get USBFS path, try to use default path:" + deviceName);
            usbfs = DEFAULT_USBFS;
        }
        Log.d(TAG, "getUSBFSName:" + usbfs);
        return usbfs;
    }

    private void initPlay() {
        int channelConfig = channel == 1
            ? AudioFormat.CHANNEL_OUT_MONO
            : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        );
        Log.d(TAG, "Buf size: " + minBuffer);

        if (track != null) {
            track.stop();
            track.release();
        }

        track = new AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE_HZ,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
            AudioTrack.MODE_STREAM
        );
        track.play();
    }

    private native long nativeCreate();
    private native void nativeClose(long ptr);
    private native int nativeInit(long ptr, int vid, int pid, int bus, int dev, int fd, String usbfs);
    private native int nativeGetChannelCount(long ptr);
    private native int nativeGetSampleRate(long ptr);
    private native int nativeGetBitResolution(long ptr);
    private native int nativeStartCapture(long ptr);
    private native int nativeStopCapture(long ptr);
    private native boolean nativeIsRunning(long ptr);
}
