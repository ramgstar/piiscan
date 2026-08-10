# piiscan-scanner

[piiscan](../README.md)의 스캔 단계로, **run 당 한 번 실행되는 Spring Boot 배치**입니다. manager가
자식 프로세스로 실행하며, 착륙 폴더의 파일을 감지·클레임하여 파싱하고, 네이티브 엔진으로 정규식
매칭한 뒤 체크섬으로 확정해 **마스킹된 리포트**를 `results/<runId>/`에 적재합니다.

스캔 코어(`com.piiscan.engine`·`validate`·`io`·`model`)에는 **Spring 의존성이 없습니다.** 파서
라이브러리 의존성도 `parse` 계층에만 둡니다.

## 파이프라인

```
scanFiles/ ──claim──▶ .processing/
     │  FileScanner: 원자적 이동(중복 방지 + "쓰는 중" 가드) + 크래시 복구
     ▼
파일 1건당 producer(가상 스레드)              [ParserRegistry: 확장자→파서 Strategy]
     │  CsvFileParser(Apache Commons CSV) / JsonFileParser(Jackson 스트리밍)
     │  → dedup(value,count) + 위치 수집 → input.jsonl  (claim-check publish)
     ▼
[ MessageBroker (ArrayBlockingQueue, 유계=백프레셔; Kafka 교체 가능) ]
     ▼
consumer 풀(yml)  ── ProcessBuilder ──▶ engine (정규식 후보)
     │  → 2차 체크섬 확정 → 값→위치 조인 → 마스킹
     ▼
results/<runId>/{summary.json, <source>.report.json}  +  processed//failed/ 이동
```

- **producer**: 파일 1건을 통일 `input.jsonl`로 가공. 확장자는 파서 선택(Strategy)일 뿐 producer
  수를 확장자에 묶지 않으므로, 어느 확장자가 몰려도 가상 스레드 풀이 자동 로드밸런싱한다. 파일 내
  **dedup**으로 같은 값을 (value, count)로 접고 위치 샘플(상한)을 함께 담는다.
- **broker**: claim-check(디스크의 input 파일 참조)만 큐로 전달. 유계 큐가 백프레셔를 제공한다.
- **consumer**: 파일당 엔진 프로세스를 띄우므로 **동시 consumer 수(yml)가 실질 병목이자 튜닝
  노브**다. 엔진 output(후보)을 체크섬으로 확정하고, 엔진이 값을 그대로 echo하는 점을 이용해
  `input.jsonl`의 value→locations를 조인한 뒤 마스킹해 리포트를 쓴다.

## 결과 저장

run별 폴더로 이력을 보존합니다(같은 파일명을 재스캔해도 덮어쓰지 않음).

```
piiScanner/results/<runId>/
├── summary.json                 # run 요약(파일 수, 확정 총계, 패턴별 집계, 실패)
├── <source1>.report.json        # 파일별: 위치·패턴·마스킹 샘플·건수(원본 값 없음)
└── <source2>.report.json
```

- **보안**: 리포트에 원본 PII를 남기지 않는다 — 위치·패턴·마스킹 샘플·건수만.
- **재현성**: 리포트에 patterns.json 버전/해시 + 엔진 버전을 함께 기록.
- **완료 표식**: `summary.json`은 run 종료 시 마지막에 기록되므로 그 존재가 곧 "완료" 판정 기준
  (manager가 완료 run만 노출).
- **보존 상한**: run 종료 후 최신 `results-max-runs`(기본 20)개 run 폴더만 유지하고 초과분 삭제.
  파일이 없는 빈 실행은 폴더를 만들지 않는다.

## 빌드 / 실행

```bash
# 저장소 루트에서(모든 모듈)  또는  이 모듈만
mvn -B package                 #   mvn -B -pl scanner -am package
```

생성물: `scanner/target/piiscan-scanner.jar`(실행 가능 fat jar). manager가 아래처럼 실행합니다.

```bash
java -jar scanner/target/piiscan-scanner.jar --run-id <id> [--spring.profiles.active=prod]
```

엔진 바이너리가 필요합니다(`cargo build --release --manifest-path engine/Cargo.toml`).

## 설정 (application.yml, `piiscan.*`)

| 키                     | 의미                                            | 기본값                                   |
|------------------------|-------------------------------------------------|------------------------------------------|
| `scan-dir`             | 착륙 폴더                                        | `piiScanner/scanFiles`                   |
| `processing-dir`       | 클레임 스테이징                                  | `piiScanner/scanFiles/.processing`       |
| `processed-dir`/`failed-dir` | 처리/실패 이동 대상                        | `piiScanner/processed` / `.../failed`    |
| `output-dir`           | 결과 루트(run 하위 폴더 생성)                    | `piiScanner/results`                     |
| `patterns-path`        | 패턴 JSON                                        | `piiScanner/patterns.json`               |
| `engine-path`          | 엔진 바이너리(Windows는 `.exe` 자동 폴백)        | `engine/target/release/piiscan-engine`   |
| `consumers`            | 동시 consumer 수(=동시 엔진 프로세스)            | CPU 코어 수                              |
| `broker-capacity`      | 큐 용량(백프레셔)                                | 64                                       |
| `sample-locations`     | 값당 위치 샘플 상한                              | 20                                       |
| `quiet-period-seconds` | 이 시간 내 수정된 파일은 "쓰는 중"으로 보고 스킵 | 5                                        |
| `ignore-extensions`    | 무시 확장자                                      | `[tmp, part]`                            |
| `masking`              | 마스킹 정책 `full`/`partial`/`hash`              | `partial`                                |
| `results-max-runs`     | 보존할 run 수(0=무제한)                          | 20                                       |

`prod` 프로필은 `engine-path`를 `anlys/piiscan-engine`(배포 zip 레이아웃)으로 오버라이드합니다.

## 로깅

**로그는 파일에만** 기록합니다(`piiScanner/logs/scanner.log`, 롤링). scanner의 stdout은 manager가
파싱하는 `PROGRESS=`/`FILE=`/`SUMMARY=` **마커 채널**이라 콘솔 어펜더를 두지 않습니다
(`logback-spring.xml`).

## "쓰는 중"·복구·정리

- **2겹 가드**: (a) `.processing/`으로 원자적 이동(복사 중인 파일은 Windows에서 잠겨 이동 실패 →
  다음 run에 처리) + (b) mtime quiet-period. `*.tmp`/`*.part`는 무시.
- **크래시 복구**: run 시작 시 `.processing/`에 남은 파일을 재클레임.
- **정리**: 임시 input/output는 성공·실패 양쪽 경로에서 삭제, 원본은 processed/failed로 이동.

## 디렉토리 구조

```
scanner/src/main/java/com/piiscan/
├── scanner/
│   ├── app/         ScannerApplication            # Spring Boot 진입점(CommandLineRunner)
│   ├── config/      ScannerProperties             # @ConfigurationProperties("piiscan")
│   ├── ingest/      FileScanner, FileMover        # 클레임/복구, processed·failed 이동
│   ├── parse/       Parser, CsvFileParser, JsonFileParser, ParserRegistry,
│   │                InputWriter, UnifiedValue, Location   # 파싱·dedup·위치·통일 input
│   ├── broker/      MessageBroker, ArrayBlockingQueueBroker, ScanTask
│   ├── pipeline/    ScanCoordinator, ProducerTask, ConsumerWorker, ProgressReporter, Markers
│   └── report/      ReportWriter, Masker, PatternSetInfo, FileReport, RunSummary, PatternFinding
└── {engine,validate,io,model}/                    # 재사용 코어(RegexEngine, Checksums, Json/Jsonl, records)
```
