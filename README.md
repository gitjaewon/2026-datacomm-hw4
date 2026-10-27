# HW#4 ASCII Evolution (G4)

데이터통신 HW#4. 원격 Work Assigner 1개와 로컬 Worker N개(4~8)가 160×80 ASCII 그림을 띠로 나눠 3,000 Round 진화시킨다. 제출용 설명은 [Readme.txt](Readme.txt), 로그 명세는 [AllDefinedLogs.txt](AllDefinedLogs.txt).

## 폴더

| 경로 | 내용 |
|---|---|
| `src/` | Assigner, Worker, JobQueue, myrules(규칙 함수), Common, Single(통신 없는 기준 실행) |
| `canvas/` | 우리 조 canvas0~2.txt |
| `scripts/` | 빌드, Worker N개 실행, 로컬 검증 |

`student_pack/`(배포 자료)과 `참고자료/`는 저장소에 없다. 각자 `HW4_student_pack.zip`을 `student_pack/`에 풀어서 쓴다.

## 빌드

```
./scripts/build.sh          # Linux · Git Bash
.\scripts\build.ps1         # Windows PowerShell
```
JDK 17 이상(개발은 21). 결과는 `build/classes`.

## 확인해 볼 것

**1. 규칙 함수 (10×10 키트)**
```
cd student_pack/1_rule_test_kit
javac -encoding UTF-8 -d ../../build/kit ../../src/myrules.java java/rule_test.java
java -cp ../../build/kit rule_test          # → ALL PASS
```

**2. 샘플 G11로 hash 확인 (로컬)**
```
./scripts/test_local.sh student_pack/3_G11_sample "1 4 6 8"
```
단일 실행과 N = 1, 4, 6, 8 분산 실행이 모두 `PASS`(샘플 정답 hash 31개와 같음)여야 한다.

**3. 우리 canvas로 직접 실행**
```
java -cp build/classes Assigner --port 9000 --team 4 --canvas canvas --out run_test --n 5
.\scripts\run_workers.ps1 -AssignerHost 127.0.0.1 -N 5 -LogDir run_test_workers
```

## 제출용 실행 순서

1. 기준값: 로컬에서 `--n 1` + Worker 1개 → `n1/RoundHash.txt`
2. 분산 실행 1: EC2에서 `--n` 없이 Assigner 실행 → INIT의 N만큼 로컬 Worker 실행 → `run1/`
3. 분산 실행 2: 같은 방법, N이 실행 1과 다를 때까지 → `run2/`
4. `verify_run`(세 실행), `make_hash_result 4 ...`, `make_gif run1/frames G4HW4.gif <N>` (배포 도구 2_tools/java)

## 남은 일

- [ ] 조원 이름 · 학번 · 역할 (Readme 1절)
- [ ] EC2 인스턴스 유형 (Readme 2절)
- [ ] 제출용 실행 3회 → hash_result.txt, round 파일, 로그, GIF
- [ ] Readme 7·8절 지표 실측표와 정합성 결과
- [ ] 시연 영상 → download.txt
- [ ] G4HW4.zip 압축 (student_pack, build 제외)
