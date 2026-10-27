// Worker.java — 로컬 Worker (N개 프로세스로 실행)
//   java -cp build Worker --host <Assigner IP> --port 9000 [--logdir <dir>]
// Thread 구성: 수신 Thread (ROUND 읽기 → Job Queue) + 계산 Thread (Job 꺼내 계산 → RESULT 전송)
// 메모리: 작업판 w·nx [h+2][160] (이중 버퍼), 덧칠용 c1·c2 [h+2][160]
import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;

public class Worker {
    static class Job {
        final int r;                 // -1 = BYE
        final char[] top, bottom;
        final long recvNs;           // ROUND 수신 완료 시각
        Job(int r, char[] top, char[] bottom, long recvNs) { this.r = r; this.top = top; this.bottom = bottom; this.recvNs = recvNs; }
    }

    static final Common.Log log = new Common.Log("WORKER?", true);
    static int k, N, y0, y1, team, h;
    static Path logdir;

    // 수신·계산 Thread가 동시에 실패해도 한 번만 종료한다. ASSIGN 전에 실패하면 k를 모르므로
    // Worker_unassigned_<pid>.txt에 그때까지의 로그와 FAIL을 남긴다.
    static synchronized void fail(String event, Exception e) {
        if (!log.isOpen()) {
            try {
                Files.createDirectories(logdir);
                log.open("WORKER?", logdir.resolve("Worker_unassigned_" + ProcessHandle.current().pid() + ".txt"));
            } catch (IOException io) { /* 콘솔에는 남는다 */ }
        }
        log.log(event, "FAIL", e.getClass().getSimpleName() + ": " + e.getMessage());
        System.exit(1);
    }

    public static void main(String[] a) throws Exception {
        String usage = "Worker --host <ip> --port <port> [--logdir <dir>]";
        Map<String, String> m = Common.args(a);
        String host = Common.need(m, "host", usage);
        int port = Common.intArg("port", Common.need(m, "port", usage), 1, 65535, usage);
        logdir = Paths.get(m.getOrDefault("logdir", "."));

        Socket sock = null;
        Common.LineReader in = null;
        OutputStream out = null;
        char[][] w, c1, c2;
        try {
            sock = new Socket(host, port);
            Common.tune(sock);
            in = new Common.LineReader(new BufferedInputStream(sock.getInputStream()));
            out = new BufferedOutputStream(sock.getOutputStream());
            log.log("CONNECT", "SUCCESS", "connected to " + host + ":" + port + " local port " + sock.getLocalPort());
            Common.sendLines(out, List.of("HELLO"));
            log.log("CONNECT", "INFO", "HELLO sent, waiting for ASSIGN");

            String[] t = in.readLine().split(" ");
            if (t.length != 6 || !t[0].equals("ASSIGN")) throw new IOException("expected ASSIGN, got: " + String.join(" ", t));
            k = Integer.parseInt(t[1]); N = Integer.parseInt(t[2]);
            y0 = Integer.parseInt(t[3]); y1 = Integer.parseInt(t[4]); team = Integer.parseInt(t[5]);
            h = y1 - y0;
            w = new char[h + 2][];
            w[0] = new char[Common.W]; w[h + 1] = new char[Common.W];
            Arrays.fill(w[0], ' '); Arrays.fill(w[h + 1], ' ');
            for (int i = 1; i <= h; i++) w[i] = in.readRow();
            c1 = new char[h + 2][];
            for (int i = 0; i < h + 2; i++) c1[i] = in.readRow();
            c2 = new char[h + 2][];
            for (int i = 0; i < h + 2; i++) c2[i] = in.readRow();

            Files.createDirectories(logdir);
            log.open("WORKER" + k, logdir.resolve("Worker" + k + ".txt"));   // ASSIGN 전 로그는 파일 앞부분에
            log.log("ASSIGN", "SUCCESS", String.format("k=%d N=%d rows %d..%d h=%d team=%d (received %d lines = %d + %d + %d)",
                    k, N, y0, y1 - 1, h, team, 3 * h + 4, h, h + 2, h + 2));
        } catch (Exception e) { fail("CONNECT", e); return; }

        final Common.LineReader fin = in;
        final OutputStream fout = out;
        final char[][] fw = w, fc1 = c1, fc2 = c2;
        JobQueue<Job> q = new JobQueue<>();

        Thread recv = new Thread(() -> receive(fin, q), "recv");
        Thread comp = new Thread(() -> compute(fout, q, fw, fc1, fc2), "compute");
        recv.start();
        comp.start();
        recv.join();
        comp.join();
        sock.close();
        log.close();
    }

    // 수신 Thread: ROUND r + top + bottom → Job, BYE → 종료 Job
    static void receive(Common.LineReader in, JobQueue<Job> q) {
        try {
            while (true) {
                String cmd = in.readLine();
                if (cmd.equals("BYE")) { q.put(new Job(-1, null, null, System.nanoTime())); return; }
                if (!cmd.startsWith("ROUND ")) throw new IOException("unexpected command: " + cmd);
                int r = Integer.parseInt(cmd.substring(6));
                char[] top = in.readRow(), bottom = in.readRow();
                q.put(new Job(r, top, bottom, System.nanoTime()));
            }
        } catch (Exception e) { fail("ROUND", e); }
    }

    // 계산 Thread: 작업판 0행·h+1행 교체 → (덧칠·변환) → 1~h행 계산 → 교체 → RESULT 전송
    static void compute(OutputStream out, JobQueue<Job> q, char[][] w, char[][] c1, char[][] c2) {
        char[][] nx = new char[h + 2][Common.W];
        int expect = 0, done = 0;
        long sumAll = 0, sum100 = 0, max100 = 0, calc100 = 0, bytes100 = 0;
        try {
            while (true) {
                Job j = q.take();
                if (j.r < 0) break;
                if (j.r != expect) throw new IOException("ROUND " + j.r + " received, expected " + expect);
                long c0 = System.nanoTime();
                w[0] = j.top;
                w[h + 1] = j.bottom;
                if (j.r == 1000) {
                    myrules.repaint(w, c1);
                    myrules.to_brian(w);
                    log.log("REPAINT", "INFO", String.format("r=1000 repaint canvas1 rows %d..%d (h+2=%d) -> brian '@'",
                            (y0 + 79) % 80, y1 % 80, h + 2));
                }
                if (j.r == 2000) {
                    myrules.repaint(w, c2);
                    myrules.to_cyclic(w, (y0 + 79) % 80, team);
                    log.log("REPAINT", "INFO", String.format("r=2000 repaint canvas2 rows %d..%d -> cyclic levels (ytop=%d team=%d)",
                            (y0 + 79) % 80, y1 % 80, (y0 + 79) % 80, team));
                }
                myrules.bandInto(w, nx, j.r / 1000 + 1, 1, h);
                char[][] tmp = w; w = nx; nx = tmp;                 // 이중 버퍼 교체
                long c1ns = System.nanoTime();

                List<String> lines = new ArrayList<>(h + 3);
                lines.add("RESULT " + j.r + " " + k);
                lines.add(Common.str(w[1]));
                lines.add(Common.str(w[h]));
                if ((j.r + 1) % 10 == 0)                            // 10 Round마다 띠 전체
                    for (int i = 1; i <= h; i++) lines.add(Common.str(w[i]));
                bytes100 += Common.sendLines(out, lines);
                long sent = System.nanoTime();

                long el = sent - j.recvNs;                          // ROUND 수신 ~ RESULT 송신
                sumAll += el; sum100 += el; max100 = Math.max(max100, el); calc100 += c1ns - c0;
                done++;
                expect++;
                if (done % 100 == 0) {
                    log.log("ROUND", "INFO", String.format("r=%d..%d avg_compute=%s max=%s (calc only %s)",
                            j.r - 99, j.r, Common.ms(sum100 / 100), Common.ms(max100), Common.ms(calc100 / 100)));
                    log.log("RESULT", "INFO", String.format("r=%d..%d sent %d B", j.r - 99, j.r, bytes100));
                    sum100 = max100 = calc100 = bytes100 = 0;
                }
            }
            String st = done == Common.ROUNDS ? "SUCCESS" : "WARN";
            log.log("TERMINATE", st, String.format("BYE. rounds=%d avg_compute=%s (h=%d)",
                    done, Common.ms(done == 0 ? 0 : sumAll / done), h));
        } catch (Exception e) { fail("RESULT", e); }
    }
}
