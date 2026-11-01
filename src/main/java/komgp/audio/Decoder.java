package komgp.audio;

import java.util.Locale;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import komgp.util.Log;

/**
 * 音频解码入口：按文件头魔数判断格式，再交给对应解码器。
 *
 * <ul>
 *   <li>WAV / AIFF / AU：JDK 自带 {@link AudioSystem}；</li>
 *   <li>OGG Vorbis：JOrbis（lib/jorbis-0.0.17.jar）；</li>
 *   <li>MP3：JLayer（lib/jlayer-1.0.1.4.jar）。</li>
 * </ul>
 */
public final class Decoder {

    private Decoder() {}

    public static final class DecodeException extends RuntimeException {
        public DecodeException(String m) { super(m); }
        public DecodeException(String m, Throwable t) { super(m, t); }
    }

    public static Pcm decode(byte[] data, String name) {
        if (data == null || data.length < 16) throw new DecodeException("音频数据为空");
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);

        if (isOgg(data)) {
            try {
                return new VorbisDec().decode(data);
            } catch (NoClassDefFoundError e) {
                throw new DecodeException("缺少 lib/jorbis-*.jar，无法解码 OGG");
            }
        }
        if (isMp3(data)) {
            try {
                return new Mp3Dec().decode(data);
            } catch (NoClassDefFoundError e) {
                throw new DecodeException("缺少 lib/jlayer-*.jar，无法解码 MP3");
            }
        }
        if (isWav(data) || lower.endsWith(".wav") || lower.endsWith(".aiff") || lower.endsWith(".au")) {
            return decodeJdk(data, name);
        }
        // 再兜底试一次 JDK 解码（可能是无扩展名的 wav）
        try {
            return decodeJdk(data, name);
        } catch (RuntimeException e) {
            Log.warn("无法识别的音频格式: " + name + " (" + e.getMessage() + ")");
            throw new DecodeException("不支持的音频格式：" + name);
        }
    }

    private static boolean isOgg(byte[] d) {
        return d.length > 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
    }

    private static boolean isMp3(byte[] d) {
        if (d.length > 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') return true;
        // MPEG audio frame sync: 0xFF Ex/Fx
        return (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0;
    }

    private static boolean isWav(byte[] d) {
        return d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F';
    }

    private static Pcm decodeJdk(byte[] data, String name) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(data))) {
            javax.sound.sampled.AudioFormat src = in.getFormat();
            int channels = Math.max(1, Math.min(2, src.getChannels()));
            int rate = Math.round(src.getSampleRate());
            javax.sound.sampled.AudioFormat target = new javax.sound.sampled.AudioFormat(
                    rate, 16, channels, true, false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in)) {
                int frames = (int) Math.min(Integer.MAX_VALUE / 8, pcm.getFrameLength());
                if (frames <= 0) frames = 1 << 20;
                byte[] buf = new byte[frames * channels * 2];
                // pcm.getFrameLength() 在部分容器里不可靠，这里直接读到结束
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(Math.max(1024, buf.length / 4));
                byte[] tmp = new byte[64 * 1024];
                int r;
                while ((r = pcm.read(tmp)) > 0) bos.write(tmp, 0, r);
                byte[] raw = bos.toByteArray();
                short[] s = new short[raw.length / 2];
                for (int i = 0; i < s.length; i++) {
                    s[i] = (short) (((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8)));
                }
                return new Pcm(s, rate, channels);
            }
        } catch (Exception e) {
            throw new DecodeException("JDK 解码失败: " + e.getMessage(), e);
        }
    }
}
