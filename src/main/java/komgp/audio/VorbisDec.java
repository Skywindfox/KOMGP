package komgp.audio;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

/**
 * OGG Vorbis 解码（JOrbis 低层 API）。
 *
 * <p>用 SyncState/StreamState/DspState 手工驱动，而不是 VorbisFile，
 * 这样可以自己决定输出缓冲、正确处理声道数与流结束。
 */
final class VorbisDec {

    private static final int CHUNK = 4096;
    /** 安全上限：40 分钟，防止坏文件把内存吃光。 */
    private static final long MAX_FRAMES = 40L * 60 * 48000;

    Pcm decode(byte[] bytes) {
        short[] buf = decodeToShort(bytes);
        if (bufInfo.rate <= 0 || bufInfo.channels <= 0) {
            throw new Decoder.DecodeException("OGG 头信息缺失");
        }
        short[] samples = new short[bufInfo.written];
        System.arraycopy(buf, 0, samples, 0, bufInfo.written);
        return new Pcm(samples, bufInfo.rate, bufInfo.channels);
    }

    private final OutInfo bufInfo = new OutInfo();

    private static final class OutInfo {
        int rate;
        int channels;
        int written;
    }

    private short[] decodeToShort(byte[] bytes) {
        SyncState oy = new SyncState();
        StreamState os = new StreamState();
        Page og = new Page();
        Packet op = new Packet();
        Info vi = new Info();
        Comment vc = new Comment();
        DspState vd = new DspState();
        Block vb = new Block(vd);

        oy.init();
        vi.init();
        vc.init();

        short[] out = new short[1 << 18];
        int written = 0;
        int headers = 0;
        int serial = -1;
        boolean dspReady = false;
        // JOrbis 的约定：pcm[0] 会被填成 float[channels][frames]，
        // pcmIndex[c] 是每个声道的起始下标，所以长度必须 >= 声道数
        float[][][] pcm = new float[1][][];
        int[] pcmIndex = new int[8];

        try (InputStream in = new ByteArrayInputStream(bytes)) {
            outer:
            while (true) {
                int index = oy.buffer(CHUNK);
                int read = in.read(oy.data, index, CHUNK);
                if (read <= 0) break;
                oy.wrote(read);

                while (oy.pageout(og) == 1) {
                    if (serial == -1) {
                        serial = og.serialno();
                        os.init(serial);
                    }
                    if (og.serialno() != serial) continue;
                    os.pagein(og);

                    int result;
                    while ((result = os.packetout(op)) != 0) {
                        if (result == -1) continue; // 丢包
                        if (!dspReady) {
                            if (vi.synthesis_headerin(vc, op) < 0) {
                                throw new Decoder.DecodeException("OGG 头解析失败");
                            }
                            headers++;
                            if (headers == 3) {
                                vd.synthesis_init(vi);
                                vb.init(vd);
                                dspReady = true;
                                bufInfo.rate = vi.rate;
                                bufInfo.channels = vi.channels;
                            }
                            continue;
                        }
                        if (vb.synthesis(op) == 0) {
                            vd.synthesis_blockin(vb);
                        }
                        int n;
                        while ((n = vd.synthesis_pcmout(pcm, pcmIndex)) > 0) {
                            float[][] chans = pcm[0];
                            int off = pcmIndex[0];
                            int need = written + n * vi.channels;
                            if (need > out.length) {
                                out = java.util.Arrays.copyOf(out, Math.max(need, out.length * 2));
                            }
                            for (int i = 0; i < n; i++) {
                                for (int c = 0; c < vi.channels; c++) {
                                    float v = chans[c][off + i];
                                    int iv = (int) (v * 32767f);
                                    out[written++] = (short) (iv > 32767 ? 32767 : Math.max(iv, -32768));
                                }
                            }
                            vd.synthesis_read(n);
                            if (written > MAX_FRAMES * 2) break outer;
                        }
                    }
                    if (og.eos() != 0) break outer;
                }
                if (written > MAX_FRAMES * 2) break;
            }
        } catch (java.io.IOException e) {
            throw new Decoder.DecodeException("OGG 读取失败: " + e.getMessage(), e);
        } finally {
            os.clear();
            oy.clear();
            vd.clear();
            vi.clear();
        }

        bufInfo.written = written;
        if (written == 0) throw new Decoder.DecodeException("OGG 解码结果为空");
        return out;
    }
}
