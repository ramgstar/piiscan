# piiscan-analyzer

[piiscan](../README.md)의 분석 단계로, Spring Boot CLI 애플리케이션입니다. manager가 스캔
작업마다 이 프로그램을 **자식 프로세스로 실행**합니다. 데이터 소스의 컬럼 값을 중복 제거해
배치로 묶고, 각 배치를 네이티브 [engine](../engine/README.md)에 통과시켜 정규식 후보를 얻은
뒤, 후보를 **체크섬**으로 확정하고 결과를 집계합니다.

스캔 코어(`com.piiscan.*`)에는 **Spring 의존성이 전혀 없습니다.** Spring 계층은 manager와
통신하기 위한 얇은 진입점일 뿐이며, 코어는 `com.piiscan.Main` 으로 프레임워크 없이 단독
실행·테스트할 수 있습니다.

## 동작 방식

```
데이터 소스(CSV 또는 합성)
      │  생산자: 동일 값을 빈도 테이블로 접음 (value, count)
      ▼
  유계 큐  ── 백프레셔: 매칭이 밀리면 생산자가 블록
      │
      ▼  소비자(가상 스레드) × N
  ┌─────────────────────────────────────────────┐
  │ 배치 → engine 프로세스 실행(JSONL 입출력)    │
  │ → 후보 finding 검증(체크섬) → 집계기에 접음  │
  └─────────────────────────────────────────────┘
      │
      ▼
  집계 리포트 → 사람이 읽는 표(단독 실행) 또는
                RESULT= 마커(manager가 실행한 경우)
```

- **생산자(producer)** 는 동일한 값을 하나로 접어, 1만 번 등장하는 값도 한 번만 매칭하고
  `count: 10000` 을 함께 실어 보냅니다.
- **유계 큐(bounded queue)** 가 백프레셔를 제공해, 매칭이 따라가지 못하면 생산자는 소스
  전체를 읽어들이는 대신 블록됩니다.
- 각 **소비자(consumer)** 는 가상 스레드로, 배치 하나를 별도 프로세스로 엔진에 통과시키고
  findings를 검증한 뒤 스레드 안전 집계기에 접어 넣습니다.

## manager와의 통신 (프로세스 경계)

manager가 실행할 때, analyzer는 **stdout 마커**로만 진행 상황을 되돌려줍니다. 공유 런타임도
RPC도 없습니다:

- `PROGRESS={"batches":N,"values":N,"confirmed":N}` — 배치가 하나 끝날 때마다 출력
- `RESULT={ ...집계 리포트... }` — 마지막에 한 번 출력

종료 코드는 성공 시 `0`, 실패 시 `1` 이라 manager가 정상 종료와 비정상 종료를 구분할 수
있습니다. 로그는 stdout 마커와 섞이지 않도록 억제되어 있습니다.

## 빌드

저장소 루트에서 전체를 빌드하거나(권장), 이 모듈만 빌드합니다:

```bash
# 저장소 루트에서 (모든 모듈)
mvn -B package

# …또는 이 모듈만
mvn -B -pl analyzer -am package
```

생성물: `analyzer/target/piiscan-analyzer.jar` (실행 가능한 fat jar).

> analyzer의 엔드투엔드 테스트는 실제 엔진 바이너리를 구동하므로, 테스트를 실행하려면 먼저
> `cargo build --release --manifest-path engine/Cargo.toml` 로 엔진을 빌드해 두세요. 엔진이
> 없으면 해당 테스트는 자동으로 건너뜁니다.

## 실행 (CLI)

```bash
java -jar analyzer/target/piiscan-analyzer.jar \
  --engine engine/target/release/piiscan-engine \
  --patterns samples/patterns.json \
  --synthetic 20000 --workers 8 --batch-size 2000
```

| 플래그          | 의미                                                        | 기본값            |
|-----------------|-------------------------------------------------------------|-------------------|
| `--engine`      | `piiscan-engine` 바이너리 경로                              | OS별 기본 경로    |
| `--patterns`    | 패턴 JSON                                                   | `samples/patterns.json` |
| `--input`       | 스캔할 CSV 파일(모든 컬럼)                                  | (없음)            |
| `--synthetic`   | CSV 대신 생성할 합성 행 수                                  | `5000`            |
| `--workers`     | 소비자 가상 스레드 수                                       | CPU 코어 수       |
| `--batch-size`  | 배치당 고유 값 수                                           | `1000`            |
| `--seed`        | 합성 RNG 시드                                               | `42`              |
| `-h`, `--help`  | 사용법 출력                                                 |                   |

> Windows에서는 `--engine` 에 확장자 없이 경로를 넘겨도 `piiscan-engine.exe` 를 자동으로
> 찾습니다(`RegexEngine` 의 `.exe` 폴백).

출력 예시:

```
piiscan report
----------------------------------------------
source        : synthetic.SAMPLE
workers       : 8 (virtual threads)
values scanned: 15,083 distinct (20,000 rows)
batches       : 8 (0 failed)
elapsed       : 551 ms
----------------------------------------------
PATTERN  NAME                                CONFIRMED   REJECTED
CARD     Credit card number                       2496       2518
...
----------------------------------------------
confirmed PII rows: 9,993
```

`CONFIRMED` 는 정규식과 체크섬을 모두 통과한 값, `REJECTED` 는 정규식엔 걸렸지만 체크섬에서
탈락한 유사값입니다.

## 체크섬 검증기

패턴의 `validator` 값에 따라 후보를 확정합니다(`com.piiscan.validate`):

| validator | 대상            | 알고리즘                          |
|-----------|-----------------|-----------------------------------|
| `luhn`    | 카드번호         | Luhn(mod-10)                      |
| `kr_rrn`  | 주민등록번호     | 가중치 검사 숫자                  |
| `kr_brn`  | 사업자등록번호   | 검사 숫자                         |
| `none`    | 이메일 등        | 정규식 매칭을 그대로 통과         |

새로운 종류의 검증기 추가는 `ValidatorRegistry` 에 한 줄 등록하는 일입니다.

## 디렉토리 구조

```
analyzer/
├── pom.xml
└── src/
    ├── main/java/com/piiscan/
    │   ├── Main.java          # 프레임워크 없는 CLI 진입점
    │   ├── app/               # Spring Boot 진입점(얇음, PROGRESS/RESULT 마커 출력)
    │   ├── cli/               # ScanConfig, ScanRunner (공용 코어)
    │   ├── engine/            # RegexEngine: 엔진 프로세스 래퍼(.exe 폴백 포함)
    │   ├── pipeline/          # ScanPipeline, ProgressListener (유계 생산자/소비자)
    │   ├── validate/          # Checksums, ValidatorRegistry
    │   ├── source/            # CsvDataSource, SyntheticDataSource
    │   ├── io/                # Json, Jsonl, ReportJson (최소 코덱)
    │   └── model/             # 레코드: PatternDef, Finding, ScanReport
    ├── main/resources/        # application.yml (웹 비활성, stdout 정리, dev/prod 프로필)
    └── test/java/             # 검증기·코덱·엔드투엔드 파이프라인 테스트
```
