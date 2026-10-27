HW#4 ASCII Evolution - Work Assigner가 중재하는 N-Worker 협업 진화
====================================================================

1. 조원
--------------------------------------------------------------------
조 이름 : G4 (team = 4)
이름 / 학번 / 역할
  -
  -
  -


2. 배포 정보
--------------------------------------------------------------------
원격 호스트 : AWS EC2 (리전: ap-northeast-2 서울 / 인스턴스 유형:            / OS: Ubuntu 26.04 LTS, OpenJDK 21)
             공인 IP는 가림. 보안 그룹에서 TCP 9000 허용, 실험 후 인스턴스 중지
로컬 PC     : Windows, Worker N개 (scripts/run_workers.ps1로 한 번에 실행)
언어        : Java 21 (표준 라이브러리만 사용)
로그 시간대 : KST (Asia/Seoul), 형식 [HH:MM:SS.mmm]
포트        : 9000

실행 명령
  [원격 호스트] Assigner
    ./scripts/build.sh            (또는 javac -encoding UTF-8 -d build/classes src/*.java)
    java -cp build/classes Assigner --port 9000 --team 4 --canvas ./canvas --out ./run1
    → INIT 로그에 N과 띠 경계가 찍힌다

  [로컬 PC] Worker N개
    .\scripts\build.ps1
    .\scripts\run_workers.ps1 -AssignerHost <공인IP> -N <INIT에 나온 N> -LogDir run1_workers

  기준값 (N = 1, 로컬)
    java -cp build/classes Assigner --port 9000 --team 4 --canvas ./canvas --out ./n1 --n 1
    java -cp build/classes Worker --host 127.0.0.1 --port 9000 --logdir n1_workers

  실행이 끝난 뒤 (검증 도구는 배포 자료 2_tools/java를 그대로 사용)
    scp -r -i key.pem ubuntu@<공인IP>:~/hw4/run1 .
    javac -encoding UTF-8 -d build/tools <2_tools/java 경로>/*.java
    java -cp build/tools verify_run run1 run1/RoundHash.txt         (세 실행 각각)
    java -cp build/tools make_hash_result 4 n1/RoundHash.txt:1 run1/RoundHash.txt:a run2/RoundHash.txt:b
    java -cp build/tools make_gif run1/frames G4HW4.gif a


3. 프로그램 구성
--------------------------------------------------------------------
src/Assigner.java   원격 Work Assigner
src/Worker.java     Worker (로컬, N개 프로세스)
src/JobQueue.java   Worker의 수신 → 계산 작업 큐 (Mutex + CV)
src/myrules.java    규칙 함수 rule1~3_cell, repaint, to_brian, to_cyclic (키트 rule_test와 같은 이름)
src/Common.java     canvas/round 파일 입출력(LF 고정), SHA-256 앞 8자리, 줄 단위 소켓 읽기, 로그 형식
src/Single.java     통신 없는 단일 프로그램 3,000 Round (개발 중 자가 점검용)
scripts/            build.sh / build.ps1 / run_workers.ps1 / run_workers.sh / test_local.sh
canvas/             canvas0.txt / canvas1.txt / canvas2.txt

3-1. 실행 인자
  Assigner  --port 9000 --team 4 --canvas <dir> --out <dir> [--n N] [--bind 0.0.0.0]
            --n 이 없으면 N은 4~8 중 무작위. 샘플 G11로 시험할 때는 --team 11
  Worker    --host <Assigner IP> --port 9000 [--logdir <dir>]

3-2. 메시지 형식 (명세 그대로, 줄바꿈 \n 단위)
  W→A  HELLO
  A→W  ASSIGN k N y0 y1 team  + canvas0 [y0,y1) h줄 + canvas1 [y0-1,y1+1) h+2줄 + canvas2 h+2줄
  A→W  ROUND r  + top 1줄 + bottom 1줄
  W→A  RESULT r k  + 새 첫 줄 + 새 끝 줄 (+ (r+1) mod 10 = 0이면 띠 전체 h줄)
  A→W  BYE
  TCP는 바이트 스트림이므로 Socket마다 버퍼를 두고 \n 단위로 자른다. 그림 줄은 strip하지 않고 길이 160만 확인한다.
  한 메시지의 여러 줄은 한 번의 write로 보내고 TCP_NODELAY를 켰다.


4. Assigner 설계
--------------------------------------------------------------------
4-1. Thread 구성 (실행 중 Thread를 새로 만들지 않음)
  Main (1)         canvas 읽기·검사, N 결정, frame0000/round0000 저장, 접속 수락, ASSIGN, Handler 생성,
                   Round 진행 관리, 파일 저장, 지표 계산, 종료 요약
  Handler (N)      Worker k 전담. 접속 단계에서 한 번만 생성. ROUND 송신 → RESULT 수신 → 공유 상태 기록 → 대기

4-2. 공유 상태와 Round Barrier
  Mutex 1개(ReentrantLock m)로 round, arrived, edge[k], halo[k], frame[80], 도착 시각, 송수신 바이트를 보호한다.
  Condition 2개
    done_cv   Handler → Main  "N개 다 왔다"  (arrived == N이 된 Handler가 signal)
    go_cv     Main → Handler  "다음 Round"   (Main이 round = r+1 후 signalAll)
  Handler k
    Lock 안에서 halo[k] 복사 → Lock 밖에서 ROUND 송신·RESULT 수신 → Lock 안에서 edge[k] 갱신,
    (10 Round마다) frame에 띠 복사, arrived + 1, N이면 done_cv signal, round가 바뀔 때까지 go_cv 대기
  Main
    Lock 안에서 arrived < N이면 done_cv 대기 → halo 준비 → (10 Round마다) frame 복사본 → arrived = 0, round = r+1
    → go_cv signalAll → Lock 밖에서 frame / round 파일 저장, hash 기록, 로그
  모든 wait는 while로 조건을 다시 확인한다. send, recv, 파일 저장은 Lock 밖에서 한다.
  Lock이 하나뿐이라 Lock 순서 문제가 없다.

4-3. 예외
  RESULT의 r이나 k가 기대와 다르거나 Worker 연결이 끊기면 FAIL 로그를 남기고 종료한다(장애 처리는 범위 밖).


5. Worker 설계
--------------------------------------------------------------------
5-1. Thread 구성 (가산점: 수신 Thread + 계산 Thread + Job Queue)
  main      접속, HELLO, ASSIGN 수신 후 두 Thread를 시작하고 join
  recv      ROUND r + top + bottom을 읽어 Job으로 Queue에 넣음. BYE면 종료 Job
  compute   Queue에서 Job을 꺼내 계산하고 RESULT 송신
  Job Queue  ReentrantLock + Condition(notEmpty), take는 while로 대기

5-2. 메모리
  작업판 w, nx [h+2][160] (이중 버퍼, 계산 후 참조만 교체), 덧칠용 c1, c2 [h+2][160]

5-3. 한 Round 처리
  ① 작업판 0행 ← top, h+1행 ← bottom
  ② r = 1000이면 작업판 전체(0 ~ h+1행)에 c1 덧칠 → '@' 변환
     r = 2000이면 c2 덧칠 → to_cyclic(w, (y0+79) % 80, team)
  ③ 규칙 r/1000+1로 1~h행을 nx에 계산 ④ 교체 ⑤ RESULT 송신
  Halo 줄에도 덧칠하는 이유: ROUND 1000에서 받은 top/bottom은 이웃의 덧칠 전 줄이다. 덧칠과 변환은 칸마다 독립이라
  내가 가진 canvas1 조각으로 직접 바꾸면 이웃이 바꾼 것과 같아진다.

5-4. 로그
  로그 파일 이름은 ASSIGN으로 받은 k로 정한다(Worker{k}.txt). ASSIGN 전 로그(CONNECT)는 메모리에 두었다가 파일 앞부분에 쓴다.


6. canvas 제작
--------------------------------------------------------------------
한 장마다 조원 한 명의 영문 이니셜과 과제 관련 단어를 하나씩 넣었다.

  파일      조 이름              문구 1 (조원)                 문구 2
  canvas0   G4 (doom)            KJW 김재원 (banner3, '#')     DATA COMM (colossal)
  canvas1   G4 (banner3, '#')    YDH 윤도훈 (colossal)         BARRIER (univers)
  canvas2   G4 (colossal)        KCH 김치헌 (univers)          HALO SYNC (big)

만든 방법
  Python pyfiglet으로 문구를 FIGlet 글자로 바꾼 뒤 지정한 (x, y)에 복사했다(그림의 공백은 덮어쓰지 않음).
  KJW와 G4(canvas1)는 획을 모두 '#'로 칠해서 Rule1에서 오래 남게 하고, 나머지는 FIGlet 글자를 그대로 썼다.
  글자를 놓은 뒤 남은 빈칸마다 5% 확률로 '.'을 뿌렸다(seed 400 / 401 / 402 고정). 저장은 newline='\n'.
  문구와 위치는 세 장이 모두 다르게 잡았다. 생성 스크립트는 제출하지 않는다.

check_canvas 결과
  canvas0.txt: 형식 OK, 문자 OK, 점 4.87% OK, 글자 획 600칸 OK
  canvas1.txt: 형식 OK, 문자 OK, 점 5.21% OK, 글자 획 546칸 OK
  canvas2.txt: 형식 OK, 문자 OK, 점 4.65% OK, 글자 획 431칸 OK


7. 지표 실측과 분석
--------------------------------------------------------------------
(정식 실행 후 Assigner.txt TERMINATE 요약과 Worker 로그를 정리해서 붙인다)

측정 위치
  모든 시각은 Assigner Main의 System.nanoTime()으로 잰다.
  t_start[r] = go_cv broadcast 직전 (Round 0은 Handler 시작 직전), t_end[r] = done_cv에서 깨어난 시각
  Barrier 대기시간 = 가장 늦은 RESULT 도착 - 가장 빠른 도착 (Handler가 RESULT를 받은 뒤 Lock 안에서 기록)
  Worker 계산시간 = Worker 쪽 ROUND 수신 ~ RESULT 송신
  전송량 = 한 Round에 모든 Worker와 주고받은 바이트(줄바꿈 포함), 보통 Round와 10 Round마다를 나눠 기록

                     분산 실행 1 (N=  )     분산 실행 2 (N=  )
  총 시간 / 처리량 :
  Round 시간       :
  Barrier 대기     :
  가장 느린 Worker :
  Worker 계산시간  :
  전송량           :

분석 :


8. 정합성 확인
--------------------------------------------------------------------
  ① round 파일 31개 모두 12,880 바이트, 80줄 × 160글자     verify_run
  ② hash_result.txt 31줄 SAME, N=1 = N=a = N=b             make_hash_result
  ③ 기록 hash = 파일에서 다시 계산한 hash                   verify_run
  ④ Assigner 로그 Round 수 = 3,000, 각 Worker 처리 Round 수 = 3,000   TERMINATE 로그
  ⑤ GIF 301장, 첫 장 = canvas0, 마지막 장 = round3000.txt

개발 중 확인
  10×10 키트 Step 1~9 전부 PASS, 샘플 G11(--team 11)로 단일 실행 / N = 1, 4~8 모두 RoundHash_N1과 31개 일치

결과 :


9. 추가 사항 / 한계
--------------------------------------------------------------------
- Worker를 수신 Thread + 계산 Thread + Job Queue로 나눴다. Assigner가 Barrier로 Round를 막고 있어서 Queue에는
  Job이 한 번에 하나만 들어가므로 속도 이득은 거의 없다.
-
