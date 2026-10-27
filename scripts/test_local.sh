#!/bin/sh
# 로컬 검증: 샘플 G11(team 11)로 단일 실행과 분산 실행(N=1 + 여러 N)의 hash 31개가 샘플 정답과 같은지 확인
#   ./scripts/test_local.sh <샘플 폴더(3_G11_sample)> [N 목록=1 4 6 8] [port=9100]
# 예) ./scripts/test_local.sh student_pack/3_G11_sample
#     ./scripts/test_local.sh student_pack/3_G11_sample "1 5 7"
[ $# -lt 1 ] && { echo "usage: $0 <3_G11_sample dir> [\"N list\"] [port]"; exit 2; }
cd "$(dirname "$0")/.." || exit 1
SAMPLE=$1; NS=${2:-"1 4 6 8"}; PORT=${3:-9100}
CP=build/classes; OUT=build/test_local
./scripts/build.sh > /dev/null || exit 1
rm -rf "$OUT"; mkdir -p "$OUT"

# 샘플 정답 (주석·빈 줄 제외, 파일이름 hash8만)
grep -v '^#' "$SAMPLE/RoundHash_N1.txt" | tr -d '\r' | awk 'NF>=2{print $1, $2}' > "$OUT/answer.txt"

same() { tr -d '\r' < "$1" | cmp -s - "$OUT/answer.txt" && echo "PASS" || echo "FAIL"; }

java -cp $CP Single --team 11 --canvas "$SAMPLE" --out "$OUT/single" > /dev/null
echo "single          : $(same "$OUT/single/RoundHash.txt")"

for n in $NS; do
  o="$OUT/n$n"
  java -cp $CP Assigner --port "$PORT" --team 11 --canvas "$SAMPLE" --out "$o" --n "$n" > "$OUT/assigner_n$n.console" 2>&1 &
  ap=$!
  sleep 1
  i=0
  while [ $i -lt "$n" ]; do
    java -cp $CP Worker --host 127.0.0.1 --port "$PORT" --logdir "$o/workers" > /dev/null 2>&1 &
    i=$((i + 1))
  done
  wait $ap
  wait
  echo "distributed N=$n: $(same "$o/RoundHash.txt")  frames=$(ls "$o/frames" | wc -l)  $(tail -1 "$o/Assigner.txt" | cut -d'|' -f4 | cut -c2-40)"
done
