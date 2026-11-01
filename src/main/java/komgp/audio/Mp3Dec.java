package komgp.audio;

import java.io.ByteArrayInputStream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/** MP3 解码（JLayer）。 */
final class Mp3Dec {

    private static final long MAX_FRAMES = 40L * 60 * 48000;

    Pcm decode(byte[] bytes) {
        short[] out = new short[1 << 18];
        int written = 0;
        int rate = 44100;
        int channels = 2;

        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(bytes));
        Decoder decoder = new Decoder();
        try {
            while (true) {
                Header header = bitstream.readFrame();
                if (header == null) break;
                rate = header.frequency();
                Object frame = decoder.decodeFrame(header, bitstream);
                channels = Math.max(1, Math.min(2, decoder.getOutputChannels()));
                if (frame instanceof SampleBuffer sb) {
                    short[] buf = sb.getBuffer();
                    int len = sb.getBufferLength();
                    if (written + len > out.length) {
                        out = java.util.Arrays.copyOf(out, Math.max(written + len, out.length * 2));
                    }
                    System.arraycopy(buf, 0, out, written, len);
                    written += len;
                }
                bitstream.closeFrame();
                if (written > MAX_FRAMES * 2) break;
            }
        } catch (Exception e) {
            throw new komgp.audio.Decoder.DecodeException("MP3 解码失败: " + e.getMessage(), e);
        } finally {
            try {
                bitstream.close();
            } catch (Exception ignored) {
                // 关闭失败无关紧要
            }
        }
        if (written == 0) throw new komgp.audio.Decoder.DecodeException("MP3 解码结果为空");
        short[] samples = new short[written];
        System.arraycopy(out, 0, samples, 0, written);
        return new Pcm(samples, rate, channels);
    }
}
