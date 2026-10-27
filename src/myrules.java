// myrules.java — 규칙 함수 (키트 rule_test.java, Single, Worker가 같이 쓴다)
// 작업판 w = char[][] (행마다 글자 배열). 칸 함수는 w의 (i, x) 칸의 "다음 글자"만 돌려준다.
//   - 이웃 행은 w[i-1], w[i+1]을 그대로 읽는다 (세로로 감지 않음 → 위·아래 줄은 Halo가 채운다)
//   - 이웃 열은 (x ± 1 + W) % W 로 감는다 (가로 Torus, W = w[0].length)
//   - 결과는 새 판에 쓴다 (이중 버퍼, 제자리 갱신 금지)
public class myrules {
    static final String RAMP = " .:-=+*#%@";   // Rule3 단계 0~9
    static final int[] LEVEL = new int[128];    // 문자 → 단계 (RAMP에 없으면 -1)
    static {
        java.util.Arrays.fill(LEVEL, -1);
        for (int k = 0; k < RAMP.length(); k++) LEVEL[RAMP.charAt(k)] = k;
    }

    // Rule1 Text Life: B3/S23 + 글자 물려받기(부모 3개 중 최빈, 동률이면 ASCII 최소)
    static char rule1_cell(char[][] w, int i, int x) {
        int W = w[0].length, cnt = 0;
        char a = 0, b = 0, c = 0;                  // 산 이웃의 앞 3글자 (탄생일 때 부모)
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dy == 0 && dx == 0) continue;
                char n = w[i + dy][(x + dx + W) % W];
                if (n == ' ') continue;
                if (cnt == 0) a = n; else if (cnt == 1) b = n; else if (cnt == 2) c = n;
                cnt++;
            }
        char me = w[i][x];
        if (me != ' ') return (cnt == 2 || cnt == 3) ? me : ' ';
        if (cnt != 3) return ' ';
        if (a == b || a == c) return a;           // 2개 이상 같은 글자가 최빈
        if (b == c) return b;
        return (char) Math.min(a, Math.min(b, c)); // 모두 다르면 ASCII 최소
    }

    // Rule2 Brian's Brain: '@' -> '+' -> ' ', 공백은 주변 8칸 중 '@'가 정확히 2개면 '@'
    static char rule2_cell(char[][] w, int i, int x) {
        char me = w[i][x];
        if (me == '@') return '+';
        if (me == '+') return ' ';
        int W = w[0].length, fire = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dy == 0 && dx == 0) continue;
                if (w[i + dy][(x + dx + W) % W] == '@') fire++;
            }
        return fire == 2 ? '@' : ' ';
    }

    // Rule3 Cyclic Ripple: 상하좌우 중 하나라도 다음 단계(RAMP)면 전진
    static char rule3_cell(char[][] w, int i, int x) {
        int W = w[0].length;
        char me = w[i][x];
        if (me >= 128 || LEVEL[me] < 0) return me;  // RAMP에 없는 글자 (to_cyclic 뒤에는 생기지 않음)
        char nxt = RAMP.charAt((LEVEL[me] + 1) % 10);
        if (w[i - 1][x] == nxt || w[i + 1][x] == nxt
                || w[i][(x + W - 1) % W] == nxt || w[i][(x + 1) % W] == nxt) return nxt;
        return me;
    }

    static char cell(int rule, char[][] w, int i, int x) {
        if (rule == 1) return rule1_cell(w, i, x);
        if (rule == 2) return rule2_cell(w, i, x);
        return rule3_cell(w, i, x);
    }

    // (h+2)줄 작업판 → 가운데 h줄의 다음 상태. Worker의 계산 = 이 함수 (키트 Step 9)
    static char[][] band(char[][] w, int rule) {
        int h = w.length - 2, W = w[0].length;
        char[][] o = new char[h][W];
        for (int i = 1; i <= h; i++)
            for (int x = 0; x < W; x++)
                o[i - 1][x] = cell(rule, w, i, x);
        return o;
    }

    // 작업판 w의 from~to행을 계산해 nx의 같은 행에 쓴다 (Worker가 이중 버퍼로 사용)
    static void bandInto(char[][] w, char[][] nx, int rule, int from, int to) {
        int W = w[0].length;
        for (int i = from; i <= to; i++)
            for (int x = 0; x < W; x++)
                nx[i][x] = cell(rule, w, i, x);
    }

    // 판 전체(위아래도 Torus): 마지막 줄·첫 줄을 Halo로 붙여 band로 계산 (N = 1과 같은 방식)
    static char[][] whole(char[][] b, int rule) {
        int H = b.length;
        char[][] w = new char[H + 2][];
        w[0] = b[H - 1];
        for (int i = 0; i < H; i++) w[i + 1] = b[i];
        w[H + 1] = b[0];
        return band(w, rule);
    }

    static char[][] rule1(char[][] b) { return whole(b, 1); }   // 키트 Step 1 ~ 4, tests.txt
    static char[][] rule2(char[][] b) { return whole(b, 2); }   // Step 5
    static char[][] rule3(char[][] b) { return whole(b, 3); }   // Step 6

    // 덧칠: c의 글자가 공백도 '.'도 아니면 w에 덮어쓴다 (작업판 전체, 제자리 수정) — Step 7
    static void repaint(char[][] w, char[][] c) {
        for (int i = 0; i < w.length; i++)
            for (int x = 0; x < w[i].length; x++)
                if (c[i][x] != ' ' && c[i][x] != '.') w[i][x] = c[i][x];
    }

    // Round 1000 변환: 공백이 아닌 모든 칸 → '@' (제자리 수정) — Step 7
    static void to_brian(char[][] w) {
        for (char[] row : w)
            for (int x = 0; x < row.length; x++)
                if (row[x] != ' ') row[x] = '@';
    }

    // Round 2000 변환: RAMP에 없는 칸(공백 포함)은 level = (7x + 3y + (x*y mod 11) + team) mod 10,
    // y = (ytop + i) % 80 = 원래 행 번호 (w의 0행이 원래 ytop행). RAMP 문자(공백 제외)는 그대로 — Step 8
    static void to_cyclic(char[][] w, int ytop, int team) {
        for (int i = 0; i < w.length; i++) {
            int y = (ytop + i) % 80;
            for (int x = 0; x < w[i].length; x++) {
                char c = w[i][x];
                if (c != ' ' && c < 128 && LEVEL[c] >= 0) continue;
                w[i][x] = RAMP.charAt((7 * x + 3 * y + (x * y) % 11 + team) % 10);
            }
        }
    }
}
