# make_canvas.py — G4조 canvas0~2.txt 생성 (pip install pyfiglet)
#   python tools/make_canvas.py [출력 폴더=canvas]
# 빈 판 → FIGlet 글자 배치(그림의 공백은 덮어쓰지 않음) → 남은 빈칸에 5% '.' → LF로 저장
import os, random, sys
import pyfiglet

W, H = 160, 80

# canvas마다 (문구, 글꼴, x, y, ink). ink가 있으면 획을 모두 그 문자로 칠한다
LAYOUT = {
    "canvas0.txt": (400, [
        ("DATA COMM", "colossal", 8,   8,  None),
        ("KJW",       "banner3",  104, 34, "#"),    # 김재원
        ("G4",        "doom",     26,  58, None),
    ]),
    "canvas1.txt": (401, [
        ("YDH",       "colossal", 14,  16, None),   # 윤도훈
        ("BARRIER",   "univers",  62,  54, None),
        ("G4",        "banner3",  120, 12, "#"),
    ]),
    "canvas2.txt": (402, [
        ("KCH",       "univers",  44,  26, None),   # 김치헌
        ("HALO SYNC", "big",      92,  64, None),
        ("G4",        "colossal", 10,  62, None),
    ]),
}


def art(text, font):
    lines = pyfiglet.figlet_format(text, font=font, width=1000).rstrip("\n").split("\n")
    while lines and not lines[0].strip():
        lines.pop(0)
    return lines


def make(seed, items):
    g = [[" "] * W for _ in range(H)]
    owner = [[None] * W for _ in range(H)]
    for text, font, x, y, ink in items:
        a = art(text, font)
        if y + len(a) > H or x + max(map(len, a)) > W:
            sys.exit(f"{text}: out of board at ({x},{y})")
        for dy, line in enumerate(a):
            for dx, ch in enumerate(line):
                if ch == " ":
                    continue
                if owner[y + dy][x + dx] not in (None, text):
                    sys.exit(f"{text} overlaps {owner[y + dy][x + dx]}")
                g[y + dy][x + dx] = ink or ch
                owner[y + dy][x + dx] = text
    rnd = random.Random(seed)                     # seed 고정 → 같은 파일 재현
    for r in range(H):
        for c in range(W):
            if g[r][c] == " " and rnd.random() < 0.05:
                g[r][c] = "."
    return g


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "canvas"
    os.makedirs(out, exist_ok=True)
    for name, (seed, items) in LAYOUT.items():
        g = make(seed, items)
        with open(os.path.join(out, name), "w", newline="\n") as f:
            f.write("".join("".join(row) + "\n" for row in g))
        print(name, "seed", seed, "->", ", ".join(t for t, *_ in items))


if __name__ == "__main__":
    main()
