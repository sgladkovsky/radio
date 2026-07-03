package au.id.jms.usbaudio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.hyinfo.util.USBMonitor;

/**
 * JNI wrapper for libUSBAudio.so from dab2_V3.12.
 * Package and method names must match native symbols.
 */
public class USBAudio {
    private static final String TAG = "USBAudio";
    private static AudioTrack track;

    static {
        System.loadLibrary("usb100");
        System.loadLibrary("USBAudio");
    }

    private final long nativePtr;
    private boolean initialized;
    private boolean stopped = true;
    private int sampleRateHz = 48000;
    private int channelCount = 2;

    public USBAudio() {
        nativePtr = nativeCreate();
    }

    public void initAudio(USBMonitor.UsbControlBlock controlBlock) {
        int result = nativeInit(
            nativePtr,
            controlBlock.getVenderId(),
            controlBlock.getProductId(),
            controlBlock.getBusNum(),
            controlBlock.getDevNum(),
            controlBlock.getFileDescriptor(),
            controlBlock.getUSBFSName()
        );

        if (result < 0) {
            Log.e(TAG, "initAudio failed: " + result);
            return;
        }

        initialized = true;
        setAudioConfig();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setAudioConfig() {
        channelCount = nativeGetChannelCount(nativePtr);
        sampleRateHz = nativeGetSampleRate(nativePtr);
        int bitResolution = nativeGetBitResolution(nativePtr);
        Log.d(TAG, "Audio config: rate=" + sampleRateHz + " channels=" + channelCount + " bits=" + bitResolution);
        initPlay();
    }

    public void startCapture() {
        stopped = false;
        if (!initialized) {
            return;
        }
        new Thread(() -> nativeStartCapture(nativePtr), "USBAudioCapture").start();
    }

    public void stopCapture() {
        if (!initialized) {
            return;
        }
        nativeStopCapture(nativePtr);
    }

    public void play() {
        stopped = false;
    }

    public void pause() {
        stopped = true;
    }

    public void closeAudio() {
        if (initialized) {
            nativeClose(nativePtr);
            initialized = false;
        }
        if (track != null) {
            track.stop();
            track.release();
            track = null;
        }
    }

    @SuppressWarnings("unused")
    public void pcmData(byte[] data) {
        if (stopped || track == null || data == null) {
            return;
        }
        track.write(data, 0, data.length);
    }

    private void initPlay() {
        int channelConfig = channelCount == 1
            ? AudioFormat.CHANNEL_OUT_MONO
            : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

        if (track != null) {
            track.stop();
            track.release();
        }

        track = new AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRateHz,
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
}
