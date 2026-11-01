package komgp.audio;

/** 解码后的 PCM：16 bit 有符号、交错声道、保留原始采样率。 */
public final class Pcm {

    public final short[] data;
    public final int sampleRate;
    public final int channels;

    public Pcm(short[] data, int sampleRate, int channels) {
        this.data = data;
        this.sampleRate = Math.max(8000, sampleRate);
        this.channels = Math.max(1, Math.min(2, channels));
    }

    public int frames() { return data.length / channels; }

    public double durationMs() { return frames() * 1000.0 / sampleRate; }

    public static Pcm silence(double ms, int sampleRate, int channels) {
        int frames = (int) Math.max(1, Math.round(ms * sampleRate / 1000.0));
        return new Pcm(new short[frames * channels], sampleRate, channels);
    }

    /** 峰值 RMS，用于自检（判断解码是否真的出了声音而不是静音）。 */
    public double rms() {
        if (data.length == 0) return 0;
        double s = 0;
        int step = Math.max(1, data.length / 200_000);
        int c = 0;
        for (int i = 0; i < data.length; i += step) {
            double v = data[i] / 32768.0;
            s += v * v;
            c++;
        }
        return Math.sqrt(s / Math.max(1, c));
    }

    @Override
    public String toString() {
        return String.format("Pcm[%d Hz, %d ch, %.1fs, rms=%.3f]",
                sampleRate, channels, durationMs() / 1000.0, rms());
    }
}
