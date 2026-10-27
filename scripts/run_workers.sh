#!/bin/sh
# Worker N개를 한 번에 실행 (로컬 PC, Linux · macOS · Git Bash)
#   ./scripts/run_workers.sh <Assigner IP> <N> [port=9000] [logdir=run_workers]
# N은 Assigner의 INIT 로그(N=...)를 보고 똑같이 넣는다.
[ $# -lt 2 ] && { echo "usage: $0 <host> <N> [port] [logdir]"; exit 2; }
cd "$(dirname "$0")/.." || exit 1
HOST=$1; N=$2; PORT=${3:-9000}; LOGDIR=${4:-run_workers}
mkdir -p "$LOGDIR"
i=0
while [ $i -lt "$N" ]; do
  java -cp build/classes Worker --host "$HOST" --port "$PORT" --logdir "$LOGDIR" > "$LOGDIR/console_$i.txt" 2>&1 &
  i=$((i + 1))
done
echo "started $N workers -> $HOST:$PORT (logs: $LOGDIR/Worker{k}.txt)"
wait
echo "all workers finished"
