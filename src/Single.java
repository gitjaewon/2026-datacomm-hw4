// Single.java — 통신 없는 단일 프로그램 3,000 Round (개발용 자가 점검, 키트 Step 11)
//   java -cp build Single --team 4 --canvas <canvas 폴더> --out <출력 폴더>
//   → <out>/round0000~3000.txt, <out>/RoundHash.txt
import java.nio.file.*;
import java.util.*;

public class Single {
    public static void main(String[] a) throws Exception {
        String usage = "Single --team <n> --canvas <dir> --out <dir>";
        Map<String, String> m = Common.args(a);
        int team = Integer.parseInt(Common.need(m, "team", usage));
        Path cdir = Paths.get(Common.need(m, "canvas", usage)), out = Paths.get(Common.need(m, "out", usage));
        Files.createDirectories(out);
        char[][] g = Common.readCanvas(cdir.resolve("canvas0.txt"));
        char[][] c1 = Common.readCanvas(cdir.resolve("canvas1.txt"));
        char[][] c2 = Common.readCanvas(cdir.resolve("canvas2.txt"));

        StringBuilder rh = new StringBuilder();
        long t0 = System.nanoTime();
        for (int r = 0; r <= Common.ROUNDS; r++) {
            if (r % 100 == 0) {                               // S(r) 저장 (round1000·2000 = 덧칠 전)
                String name = String.format("round%04d.txt", r);
                String h = Common.save(out.resolve(name), g);
                rh.append(name).append(' ').append(h).append('\n');
                System.out.println(name + " " + h);
            }
            if (r == Common.ROUNDS) break;
            if (r == 1000) { myrules.repaint(g, c1); myrules.to_brian(g); }
            if (r == 2000) { myrules.repaint(g, c2); myrules.to_cyclic(g, 0, team); }
            g = myrules.whole(g, r / 1000 + 1);
        }
        Files.write(out.resolve("RoundHash.txt"), rh.toString().getBytes("US-ASCII"));
        System.out.printf("done in %.1fs%n", (System.nanoTime() - t0) / 1e9);
    }
}
