// Assigner.java — Work Assigner (외부 서버에서 1개 실행)
//   java -cp build Assigner --port 9000 --team 4 --canvas <dir> --out <dir> [--n N] [--bind 0.0.0.0]
// Thread 구성: Main 1 + Handler N (접속 단계에서 한 번만 생성)
// 공유 상태(round, arrived, edge, halo, frame, 측정값)는 Mutex m 하나로 보호하고,
// done_cv(Handler → Main "다 왔다")와 go_cv(Main → Handler "다음 Round")로 Round Barrier를 만든다.
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Assigner {
    static final int R = Common.ROUNDS;
    static final Common.Log log = new Common.Log("ASSIGNER", true);

    // ---------- 공유 상태 (Mutex m으로 보호) ----------
    static final ReentrantLock m = new ReentrantLock();
    static final Condition doneCv = m.newCondition();
    static final Condition goCv = m.newCondition();
    static int round = 0, arrived = 0;
    static char[][] edgeFirst, edgeLast;     // edge[k] = Worker k의 (첫 줄, 끝 줄)
    static char[][] haloTop, haloBottom;     // halo[k] = 다음 ROUND의 (top, bottom)
    static char[][] frame;                   // 10 Round마다 조립할 그림 80줄
    static long[] arrT;                      // 이번 Round RESULT 도착 시각 (Handler가 Lock 안에서 기록)
    static long roundBytes;                  // 이번 Round 송수신 바이트 (줄바꿈 포함)

    // ---------- 읽기 전용 (시작 후 바뀌지 않음) ----------
    static int N, team;
    static int[] y;                          // 띠 경계 y[0..N]
    static Path outDir;

    // 여러 Handler가 동시에 실패해도 FAIL 로그 한 줄을 남기고 한 번만 종료한다 (로그는 줄마다 flush됨)
    static synchronized void fail(String event, String msg) {
        log.log(event, "FAIL", msg);
        System.exit(1);
    }

    public static void main(String[] a) throws Exception {
        String usage = "Assigner --port <port> --team <n> --canvas <dir> --out <dir> [--n N] [--bind addr]";
        Map<String, String> arg = Common.args(a);
        int port = Common.intArg("port", Common.need(arg, "port", usage), 1, 65535, usage);
        team = Common.intArg("team", Common.need(arg, "team", usage), 0, 999, usage);
        Path cdir = Paths.get(Common.need(arg, "canvas", usage));
        outDir = Paths.get(Common.need(arg, "out", usage));
        String bind = arg.getOrDefault("bind", "0.0.0.0");

        Files.createDirectories(outDir.resolve("frames"));
        log.open("ASSIGNER", outDir.resolve("Assigner.txt"));

        // ① canvas 3장 읽기·검사
        char[][][] cv = new char[3][][];
        for (int i = 0; i < 3; i++) {
            try { cv[i] = Common.readCanvas(cdir.resolve("canvas" + i + ".txt")); }
            catch (IOException e) { fail("INIT", e.getMessage()); }
        }

        // ② N 결정 (--n 없으면 4~8 무작위) → 띠 경계
        if (arg.containsKey("n")) N = Common.intArg("n", arg.get("n"), 1, 8, usage);
        else N = 4 + new Random().nextInt(5);
        y = new int[N + 1];
        for (int k = 0; k <= N; k++) y[k] = Common.H * k / N;

        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(bind, port));
        log.log("INIT", "SUCCESS", String.format("N=%d%s bands=%s team=%d listen %s:%d canvas=%s out=%s",
                N, arg.containsKey("n") ? " (--n)" : " (random 4~8)", Arrays.toString(y).replace(" ", ""),
                team, bind, port, cdir, outDir));

        // ③ S(0) = canvas0 → frame0000 · round0000 저장
        frame = Common.copy(cv[0]);
        saveFrame(0, frame);

        // ④ N개 접속 수락 (접속 순서 = k), HELLO 확인
        Socket[] socks = new Socket[N];
        Common.LineReader[] ins = new Common.LineReader[N];
        OutputStream[] outs = new OutputStream[N];
        for (int k = 0; k < N; k++) {
            Socket s = ss.accept();
            Common.tune(s);
            socks[k] = s;
            ins[k] = new Common.LineReader(new BufferedInputStream(s.getInputStream()));
            outs[k] = new BufferedOutputStream(s.getOutputStream());
            String hello;
            try { hello = ins[k].readLine(); } catch (IOException e) { fail("CONNECT", "worker k=" + k + ": " + e.getMessage()); return; }
            if (!hello.equals("HELLO")) fail("CONNECT", "worker k=" + k + " sent '" + hello + "' instead of HELLO");
            log.log("CONNECT", "SUCCESS", String.format("worker k=%d from %s:%d (%d/%d) HELLO",
                    k, s.getInetAddress().getHostAddress(), s.getPort(), k + 1, N));
        }
        ss.close();

        // ⑤ ASSIGN k N y0 y1 team + canvas0 [y0,y1) h줄 + canvas1·2 [y0-1, y1+1) h+2줄씩
        for (int k = 0; k < N; k++) {
            int y0 = y[k], y1 = y[k + 1];
            List<String> lines = new ArrayList<>();
            lines.add(String.format("ASSIGN %d %d %d %d %d", k, N, y0, y1, team));
            for (int r = y0; r < y1; r++) lines.add(Common.str(cv[0][r]));
            for (int c = 1; c <= 2; c++)
                for (int r = y0 - 1; r <= y1; r++) lines.add(Common.str(cv[c][(r + 80) % 80]));
            try { Common.sendLines(outs[k], lines); } catch (IOException e) { fail("ASSIGN", "worker k=" + k + ": " + e.getMessage()); }
            log.log("ASSIGN", "SUCCESS", String.format("k=%d rows %d..%d (h=%d) sent %d lines (canvas0 %d + canvas1 %d + canvas2 %d)",
                    k, y0, y1 - 1, y1 - y0, lines.size() - 1, y1 - y0, y1 - y0 + 2, y1 - y0 + 2));
        }

        // ⑥ Round 0 준비: edge = canvas0의 띠 첫·끝 줄 → halo, round = 0, arrived = 0
        edgeFirst = new char[N][]; edgeLast = new char[N][];
        haloTop = new char[N][]; haloBottom = new char[N][];
        arrT = new long[N];
        m.lock();
        try {
            for (int k = 0; k < N; k++) { edgeFirst[k] = cv[0][y[k]].clone(); edgeLast[k] = cv[0][y[k + 1] - 1].clone(); }
            makeHalo();
            round = 0; arrived = 0; roundBytes = 0;
        } finally { m.unlock(); }

        // ⑦ Handler N개 생성 (이후 Thread 생성 없음) → Round 진행
        Thread[] hs = new Thread[N];
        for (int k = 0; k < N; k++) {
            final int kk = k;
            final Common.LineReader in = ins[k];
            final OutputStream out = outs[k];
            hs[k] = new Thread(() -> handler(kk, in, out), "handler-" + k);
        }
        mainLoop(hs);                                                    // Handler join까지 mainLoop가 한다

        for (Socket s : socks) s.close();
        log.close();
    }

    // top[k] = edge[(k-1+N)%N].last, bottom[k] = edge[(k+1)%N].first  (Lock 안에서 호출)
    static void makeHalo() {
        for (int k = 0; k < N; k++) {
            haloTop[k] = edgeLast[(k - 1 + N) % N];
            haloBottom[k] = edgeFirst[(k + 1) % N];
        }
    }

    // ---------- Handler k (Worker k 전담) ----------
    static void handler(int k, Common.LineReader in, OutputStream out) {
        int y0 = y[k], h = y[k + 1] - y[k];
        try {
            for (int r = 0; r < R; r++) {
                char[] t, b;
                m.lock();
                try { t = haloTop[k].clone(); b = haloBottom[k].clone(); }   // Lock 안에서 복사
                finally { m.unlock(); }

                long sent = Common.sendLines(out, List.of("ROUND " + r, Common.str(t), Common.str(b)));   // Lock 밖
                long before = in.bytes;
                String cmd = in.readLine();
                String expect = "RESULT " + r + " " + k;
                if (!cmd.equals(expect)) fail("RESULT", String.format("worker k=%d sent '%s', expected '%s'", k, cmd, expect));
                char[] first = in.readRow(), last = in.readRow();
                char[][] band = null;
                if ((r + 1) % 10 == 0) {
                    band = new char[h][];
                    for (int i = 0; i < h; i++) band[i] = in.readRow();
                }
                long recvd = in.bytes - before;

                m.lock();
                try {
                    edgeFirst[k] = first;
                    edgeLast[k] = last;
                    arrT[k] = System.nanoTime();
                    if (band != null) for (int i = 0; i < h; i++) frame[y0 + i] = band[i];
                    roundBytes += sent + recvd;
                    arrived++;
                    if (arrived == N) doneCv.signal();                  // Main 깨우기
                    while (round == r) goCv.await();                     // 다음 Round까지
                } finally { m.unlock(); }
            }
            Common.sendLines(out, List.of("BYE"));
        } catch (IOException | InterruptedException e) {
            fail("RESULT", "worker k=" + k + " connection error: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ---------- Main: Round 진행 관리 · 저장 · 측정 ----------
    static void mainLoop(Thread[] hs) throws Exception {
        long[] tStart = new long[R + 1], tEnd = new long[R];
        int[] slowAll = new int[N], slow100 = new int[N];
        long sumRound = 0, maxRound = 0, sumWait = 0, maxWait = 0;
        long s100Round = 0, m100Round = 0, s100Wait = 0, m100Wait = 0;
        long bytesNormal = 0, bytesFull = 0, totalBytes = 0;
        int nNormal = 0, nFull = 0;
        long bNormal100 = 0, bFull100 = 0; int nNormal100 = 0, nFull100 = 0;

        tStart[0] = System.nanoTime();                                   // Handler 시작 직전 = Round 0 시작
        for (Thread t : hs) t.start();
        log.log("ROUND", "INFO", "r=0 start, handlers=" + N);

        for (int r = 0; r < R; r++) {
            char[][] snapshot = null;
            long wait, bytes;
            int slowest;
            boolean full = (r + 1) % 10 == 0;
            m.lock();
            try {
                while (arrived < N) doneCv.await();
                tEnd[r] = System.nanoTime();                             // Round r 종료
                long mx = Long.MIN_VALUE, mn = Long.MAX_VALUE; slowest = 0;
                for (int k = 0; k < N; k++) {
                    if (arrT[k] > mx) { mx = arrT[k]; slowest = k; }
                    mn = Math.min(mn, arrT[k]);
                }
                wait = mx - mn;                                          // Barrier 대기시간
                bytes = roundBytes;
                makeHalo();                                              // 다음 ROUND의 halo 준비
                if (full) snapshot = Common.copy(frame);
                arrived = 0; roundBytes = 0;
                round = r + 1;
                tStart[r + 1] = System.nanoTime();                       // broadcast 직전 = Round r+1 시작
                goCv.signalAll();                                        // 모두 깨우기
            } finally { m.unlock(); }

            // ---- 여기부터 Lock 밖 (측정 정리 · 파일 저장 · 로그) ----
            long rt = tEnd[r] - tStart[r];
            sumRound += rt; maxRound = Math.max(maxRound, rt); s100Round += rt; m100Round = Math.max(m100Round, rt);
            sumWait += wait; maxWait = Math.max(maxWait, wait); s100Wait += wait; m100Wait = Math.max(m100Wait, wait);
            slowAll[slowest]++; slow100[slowest]++;
            totalBytes += bytes;
            if (full) { bytesFull += bytes; nFull++; bFull100 += bytes; nFull100++; }
            else { bytesNormal += bytes; nNormal++; bNormal100 += bytes; nNormal100++; }

            if (full) saveFrame(r + 1, snapshot);

            if ((r + 1) % 100 == 0) {
                int sk = argmax(slow100);
                log.log("RESULT", "INFO", String.format("r=%d..%d all %d results x100 received, traffic normal=%dB full=%dB per round",
                        r - 99, r, N, nNormal100 == 0 ? 0 : bNormal100 / nNormal100, nFull100 == 0 ? 0 : bFull100 / nFull100));
                log.log("BARRIER", "INFO", String.format("r=%d..%d avg_round=%s max_round=%s avg_wait=%s max_wait=%s slowest=W%d(%d/100,h=%d)",
                        r - 99, r, Common.ms(s100Round / 100), Common.ms(m100Round), Common.ms(s100Wait / 100), Common.ms(m100Wait),
                        sk, slow100[sk], y[sk + 1] - y[sk]));
                s100Round = m100Round = s100Wait = m100Wait = 0;
                bNormal100 = bFull100 = 0; nNormal100 = nFull100 = 0;
                Arrays.fill(slow100, 0);
                if (r + 1 < R) log.log("ROUND", "INFO", "r=" + (r + 1) + " start");
            }
            if (r + 1 == 1000) log.log("REPAINT", "INFO", "r=1000 canvas1 repaint + brian (applied by workers on ROUND 1000)");
            if (r + 1 == 2000) log.log("REPAINT", "INFO", "r=2000 canvas2 repaint + cyclic levels team=" + team + " (applied by workers on ROUND 2000)");
        }

        // ---- 종료 요약 (Handler들은 round = 3000을 보고 BYE를 보낸 뒤 끝난다) ----
        double total = (tEnd[R - 1] - tStart[0]) / 1e9;
        int sk = argmax(slowAll);
        log.log("TERMINATE", "INFO", String.format("round time avg=%s max=%s | barrier wait avg=%s max=%s | slowest=W%d (%d/%d, h=%d)",
                Common.ms(sumRound / R), Common.ms(maxRound), Common.ms(sumWait / R), Common.ms(maxWait), sk, slowAll[sk], R, y[sk + 1] - y[sk]));
        StringBuilder per = new StringBuilder();
        for (int k = 0; k < N; k++) per.append(String.format("W%d(h=%d)=%d ", k, y[k + 1] - y[k], slowAll[k]));
        log.log("TERMINATE", "INFO", "slowest count per worker: " + per.toString().trim());
        log.log("TERMINATE", "INFO", String.format("traffic normal round avg=%dB, every 10 rounds avg=%dB, total=%.1fKB",
                nNormal == 0 ? 0 : bytesNormal / nNormal, nFull == 0 ? 0 : bytesFull / nFull, totalBytes / 1024.0));
        log.log("TERMINATE", "INFO", String.format("files: round x31, frames x301 in %s", outDir));
        for (Thread t : hs) t.join();
        log.log("TERMINATE", "SUCCESS", String.format("%d rounds %.1fs (%.1f round/s). BYE sent to %d. joined.", R, total, R / total, N));
    }

    static int argmax(int[] a) {
        int b = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[b]) b = i;
        return b;
    }

    // frame%04d.txt 저장, 100의 배수면 round%04d.txt도 저장하고 hash 기록 (Main이 Lock 밖에서 호출)
    static void saveFrame(int r, char[][] g) throws IOException {
        Common.save(outDir.resolve("frames").resolve(String.format("frame%04d.txt", r)), g);
        if (r % 100 == 0) {
            String name = String.format("round%04d.txt", r);
            String h = Common.save(outDir.resolve(name), g);
            Files.write(outDir.resolve("RoundHash.txt"), (name + " " + h + "\n").getBytes(StandardCharsets.US_ASCII),
                    r == 0 ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE}
                           : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE});
            log.log("SAVE", "SUCCESS", String.format("%s %dB hash=%s", name, Common.FILE_BYTES, h));
        }
    }
}
