// Common.java — Assigner · Worker · Single이 같이 쓰는 도구
//   canvas/round 파일 읽기·쓰기(LF 고정), SHA-256 앞 8자리, 줄 단위 소켓 읽기, 실행 인자, 로그
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Common {
    static final int TEAM = 4;                   // 우리 조 번호 (샘플 G11로 시험할 때만 --team 11)
    static final int H = 80, W = 160, ROUNDS = 3000;
    static final int FILE_BYTES = (W + 1) * H;   // 12,880

    // ---------- 그림 파일 ----------

    // canvas 읽기: 80줄 × 160글자, 줄 끝 LF, ASCII 32~126만 (아니면 예외)
    static char[][] readCanvas(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        if (b.length != FILE_BYTES)
            throw new IOException(p + ": " + b.length + " bytes (expected " + FILE_BYTES + ", LF only)");
        char[][] g = new char[H][W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int c = b[y * (W + 1) + x] & 0xff;
                if (c < 32 || c > 126) throw new IOException(p + ": row " + y + " col " + x + " is not ASCII 32~126 (" + c + ")");
                g[y][x] = (char) c;
            }
            if (b[y * (W + 1) + W] != '\n') throw new IOException(p + ": row " + y + " does not end with LF");
        }
        return g;
    }

    // 그림 → 파일 바이트 (줄마다 160글자 + LF, 12,880 바이트)
    static byte[] toBytes(char[][] g) {
        byte[] b = new byte[FILE_BYTES];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) b[y * (W + 1) + x] = (byte) g[y][x];
            b[y * (W + 1) + W] = '\n';
        }
        return b;
    }

    static String hash8(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // 파일 저장 후 hash8 반환 (저장한 바이트 그대로의 hash)
    static String save(Path p, char[][] g) throws IOException {
        byte[] b = toBytes(g);
        Files.write(p, b);
        return hash8(b);
    }

    static char[][] copy(char[][] g) {
        char[][] c = new char[g.length][];
        for (int i = 0; i < g.length; i++) c[i] = g[i].clone();
        return c;
    }

    static String str(char[] row) { return new String(row); }

    // ---------- 줄 단위 소켓 입출력 ----------
    // TCP는 바이트 스트림이므로 수신 버퍼에서 \n 단위로 자른다. strip/trim 하지 않는다.
    static class LineReader {
        private final InputStream in;
        private final byte[] buf = new byte[1 << 16];
        private int pos = 0, len = 0;
        long bytes = 0;                       // 받은 바이트 수 (줄바꿈 포함)

        LineReader(InputStream in) { this.in = in; }

        String readLine() throws IOException {
            StringBuilder sb = new StringBuilder(W + 8);
            while (true) {
                if (pos == len) {
                    len = in.read(buf);
                    pos = 0;
                    if (len <= 0) { len = 0; throw new EOFException("connection closed"); }
                }
                byte c = buf[pos++];
                bytes++;
                if (c == '\n') return sb.toString();
                sb.append((char) (c & 0xff));
            }
        }

        // 그림 줄: 정확히 160글자인지 확인
        char[] readRow() throws IOException {
            String s = readLine();
            if (s.length() != W) throw new IOException("picture line length " + s.length() + " (expected " + W + ")");
            return s.toCharArray();
        }
    }

    // 여러 줄을 한 번의 write로 보낸다. 보낸 바이트 수를 돌려준다.
    static int sendLines(OutputStream out, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        byte[] b = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        out.write(b);
        out.flush();
        return b.length;
    }

    static void tune(Socket s) throws IOException {
        s.setTcpNoDelay(true);                // Nagle 지연 끄기 (작은 메시지가 많음)
    }

    // ---------- 실행 인자 (--key value) ----------
    static Map<String, String> args(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < a.length; i++) {
            if (!a[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + a[i]);
            String k = a[i].substring(2);
            if (i + 1 < a.length && !a[i + 1].startsWith("--")) m.put(k, a[++i]);
            else m.put(k, "true");
        }
        return m;
    }

    static String need(Map<String, String> m, String k, String usage) {
        String v = m.get(k);
        if (v == null) { System.err.println("missing --" + k + "\nusage: " + usage); System.exit(2); }
        return v;
    }

    // ---------- 로그 ----------
    // 형식: [HH:MM:SS.mmm] NODE | EVENT | STATUS | message   (시각 = KST, Asia/Seoul)
    static class Log {
        static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
        static final DateTimeFormatter F = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        private String node;
        private PrintWriter file;                       // null이면 pending에 모아 둔다
        private final List<String[]> pending = new ArrayList<>();
        private final boolean echo;

        Log(String node, boolean echo) { this.node = node; this.echo = echo; }

        private String fmt(String time, String event, String status, String msg) {
            return String.format("[%s] %s | %-9s | %-7s | %s", time, node, event, status, msg);
        }

        // 파일을 연다. 그 전에 남긴 로그는 NODE 이름을 확정해 파일 앞부분에 쓴다 (Worker: ASSIGN 전 로그)
        synchronized void open(String node, Path p) throws IOException {
            this.node = node;
            file = new PrintWriter(new OutputStreamWriter(new FileOutputStream(p.toFile()), StandardCharsets.UTF_8));
            for (String[] e : pending) file.print(fmt(e[0], e[1], e[2], e[3]) + "\n");
            pending.clear();
            file.flush();
        }

        synchronized void log(String event, String status, String msg) {
            String time = LocalTime.now(ZONE).format(F);
            if (file != null) { file.print(fmt(time, event, status, msg) + "\n"); file.flush(); }
            else pending.add(new String[]{time, event, status, msg});
            if (echo) System.out.println(fmt(time, event, status, msg));
        }

        synchronized void close() { if (file != null) file.close(); }
    }

    static String ms(long nanos) { return String.format("%.1fms", nanos / 1e6); }
}
