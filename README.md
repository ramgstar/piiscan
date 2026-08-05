# piiscan

표 형식 데이터에서 개인식별정보(PII)를 찾아내는 멀티모듈 스캐너입니다. **manager**
웹 서비스가 스캔 작업을 큐잉하고 작업마다 **analyzer** 프로세스를 실행하면, analyzer는
컬럼 값을 네이티브 **Rust 엔진**으로 흘려보내 고속 정규식 매칭을 수행한 뒤, 각 후보를
체크섬으로 확정합니다. 진행 상황은 server-sent events(SSE)로 실시간 대시보드에 전달됩니다.

> **한 줄 요약:** 대용량 컬럼 값에서 민감정보(주민등록번호·사업자등록번호·카드번호·이메일)를
> 탐지하는 멀티모듈 토이 프로젝트입니다. **manager**(Spring Boot 웹 + 실시간 대시보드)가
> 작업을 큐잉하고 **analyzer**(Spring Boot CLI)를 프로세스로 실행하면, analyzer가 **Rust
> 엔진**으로 정규식 후보를 빠르게 걸러내고 **Java 체크섬**(주민/사업자 검증, Luhn)으로
> 오탐을 제거합니다. 모든 모듈은 프로세스 경계(명령행 인자 + stdout 마커 + JSONL 파일)로만
> 통신합니다. *모든 패턴은 공개된 형식 정의에 기반하며, 샘플 데이터는 전부 합성입니다 —
> 실제 개인정보는 어디에도 포함돼 있지 않습니다.*

---

## 왜 2단계인가?

정규식은 어떤 값이 카드번호처럼 *보이는지* — 올바른 형태의 16자리 숫자인지 — 는 알려주지만,
그게 실제 카드번호인지까지는 말해주지 못합니다. 패턴에 걸린 16자리 문자열 대부분은 Luhn
검사를 통과하지 못하고, 주민등록번호처럼 보이는 13자리 문자열 대부분도 검사 숫자에서
탈락합니다. 체크섬 계산 자체는 저렴하지만, 수백만 개의 값에 정규식을 돌리는 일은 그렇지
않습니다.

그래서 piiscan은 각 단계가 잘하는 일로 작업을 나눕니다:

- **Rust 엔진** — 패턴 집합을 한 번 컴파일해 두고 값들을 흘려보내며 정규식 *후보*를 모두
  방출합니다. 여기가 핫 루프이며 네이티브 코드로 유지됩니다.
- **Java analyzer** — 작업을 조율하고, 각 패턴에 대응하는 도메인별 **체크섬**을 적용해 실제
  탐지와 유사값을 분리합니다.

그 효과는 출력에서 바로 드러납니다. 카드번호·주민등록번호 패턴의 경우, 정규식 후보의 약
절반이 체크섬 단계에서 걸러집니다.

## 아키텍처

**코드를 전혀 공유하지 않는** 네 개의 컴포넌트로 구성됩니다. 오직 프로세스 경계로만
통신하므로, 각각을 독립적으로 빌드하고 배포할 수 있습니다:

```
   browser ──HTTP/SSE──▶ ┌───────────────────────────────────────────────┐
                         │            manager  (Spring Boot :5050)        │
                         │  REST + SSE 대시보드, 작업 큐/생명주기          │
                         └───────────────┬───────────────────────────────┘
                                         │ ProcessBuilder (작업당 1개)
                          인자 입력 ──────▼────── PROGRESS= / RESULT= 마커 출력 (stdout)
                         ┌───────────────────────────────────────────────┐
                         │            analyzer  (Spring Boot CLI)         │
                         │  중복제거 + 배치 ▶ 유계 큐 ▶ 소비자            │  (가상 스레드)
                         │  체크섬 검증, 집계                             │
                         └───────────────┬───────────────────────────────┘
                                         │ ProcessBuilder (배치당 1개)
                          JSONL 입력 ─────▼───── JSONL 출력 (후보 findings)
                         ┌───────────────────────────────────────────────┐
                         │            engine  (네이티브, Rust)            │
                         │  패턴 컴파일 → 스트리밍 매칭 → 방출            │
                         └───────────────────────────────────────────────┘

   source-tester (Spring Boot CLI): 스캔 전에 데이터 소스를 검증하고
   SOURCE_TEST_RESULT={...} 를 출력 — 파일/합성 소스에 대한 DB 연결
   테스트의 대응물.
```

| 모듈            | 역할                                                        | 런타임                |
|-----------------|-------------------------------------------------------------|-----------------------|
| `manager`       | 작업 큐, 프로세스 생명주기, 실시간 SSE 대시보드(Chart.js)    | Spring Boot 웹 :5050  |
| `analyzer`      | 중복제거/배치, 엔진 실행, 체크섬 검증, 집계                  | Spring Boot CLI       |
| `engine`        | 정규식 패턴 매칭 전담                                        | 네이티브 바이너리(Rust)|
| `source-tester` | 스캔 전 데이터 소스 사용 가능 여부 검증                      | Spring Boot CLI       |

analyzer 내부:

- **생산자(producer)** 는 동일한 값을 빈도 테이블로 접어, 1만 번 등장하는 값도 한 번만
  매칭하고 `count: 10000` 을 함께 실어 보냅니다.
- **유계 큐(bounded queue)** 가 백프레셔를 제공합니다. 매칭이 따라가지 못하면 생산자는 소스
  전체를 메모리에 읽어들이는 대신 블록됩니다.
- 각 **소비자(consumer)** (가상 스레드)는 배치 하나를 별도 프로세스로 엔진에 통과시키고,
  findings를 검증한 뒤, 결과를 스레드 안전 집계기에 접어 넣습니다.

analyzer의 스캔 코어(`com.piiscan.*` — 파이프라인, 검증기, 소스, JSON)는 **Spring 의존성이
전혀 없으며**, `com.piiscan.Main` 을 통해 프레임워크 없이 단독 실행·테스트할 수 있습니다.

## 패턴

패턴은 엔진(`regex` 사용)과 analyzer(`validator` 사용)가 함께 읽는 하나의 공유 JSON 파일
(`samples/patterns.json`)에 정의됩니다:

| id       | 매칭 대상                              | 검증기     |
|----------|----------------------------------------|-----------|
| `KR_RRN` | 주민등록번호                            | `kr_rrn`  |
| `CARD`   | 신용카드번호                            | `luhn`    |
| `KR_BRN` | 사업자등록번호                          | `kr_brn`  |
| `EMAIL`  | 이메일 주소                             | `none`    |

`none` 은 정규식 매칭을 그대로 통과시킵니다(이메일은 검사 숫자가 없음). 패턴을 추가하는
것은 데이터 변경이며, 새로운 *종류*의 검증기를 추가하는 것은 `ValidatorRegistry` 에 한 줄
추가하는 일입니다.

## 빌드

사전 요구사항: Rust 툴체인(1.70+)과 JDK 21+.

```bash
# 1. 네이티브 엔진 빌드
cargo build --release --manifest-path engine/Cargo.toml

# 2. 모든 Java 모듈 빌드(analyzer, manager, source-tester)
mvn -B package
```

생성물:

- `engine/target/release/piiscan-engine`
- `analyzer/target/piiscan-analyzer.jar`
- `manager/target/piiscan-manager.jar`
- `source-tester/target/piiscan-source-tester.jar`

> **Spring Boot 버전:** 4.0.1(Spring Framework 7, Jakarta EE 11)을 사용합니다. JDK 17
> 이상이 필요하며, 이 프로젝트는 JDK 21을 대상으로 합니다.

## 실행

### 대시보드 (manager)

엔진과 analyzer jar를 찾을 수 있도록 저장소 루트에서 manager를 실행하세요(또는
`manager/src/main/resources/application.yml` 의 `piiscan.*` 경로를 덮어쓰기):

```bash
java -jar manager/target/piiscan-manager.jar
# http://localhost:5050 접속
```

합성 데이터 또는 CSV를 선택하고 **Start scan** 을 누르면, 배치·값·확정 행 수가 실시간으로
갱신되고 패턴별 확정 대 기각 차트가 그려집니다.

### analyzer 단독 실행 (CLI)

```bash
java -jar analyzer/target/piiscan-analyzer.jar \
  --engine engine/target/release/piiscan-engine \
  --patterns samples/patterns.json \
  --synthetic 20000 --workers 8 --batch-size 2000
```

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
EMAIL    Email address                            2470          0
KR_BRN   Korean business registration number      2441          0
KR_RRN   Korean resident registration number      2586       2521
----------------------------------------------
confirmed PII rows: 9,993
```

`CONFIRMED` 는 정규식과 체크섬을 모두 통과한 값이고, `REJECTED` 는 정규식엔 걸렸지만
체크섬에서 탈락한 값입니다 — 정규식만 쓰는 스캐너라면 오탐으로 보고했을 바로 그 유사값들입니다.

### 엔진 단독 실행

```bash
echo '{"value":"901231-1234568","count":1}' > /tmp/in.jsonl
engine/target/release/piiscan-engine \
  --patterns samples/patterns.json --input /tmp/in.jsonl --output /tmp/out.jsonl --stats
cat /tmp/out.jsonl
```

### source-tester

```bash
java -jar source-tester/target/piiscan-source-tester.jar --input samples/sample_customers.csv
# SOURCE_TEST_RESULT={"ok":true,"rows":5,"cells":20,"message":"readable CSV with 5 data rows"}
```

## 설계 노트

- **프로세스 경계 통합.** manager ↔ analyzer 는 명령행 인자 입력과 `PROGRESS=`/`RESULT=`
  stdout 마커 출력으로 통신하고, analyzer ↔ engine 은 JSONL 파일로 통신합니다. 공유 런타임도,
  RPC 프레임워크도 없이 각 모듈이 독립적으로 배포됩니다.
- **가상 스레드(Java 21).** 소비자는 대부분의 시간을 자식 프로세스 대기로 보내는데, 이는
  가상 스레드가 겨냥하는 바로 그 워크로드입니다.
- **의존성 최소 코어.** analyzer의 스캔 코어에는 서드파티 런타임 의존성이 없으며, JSON은
  저장소 내부의 작은 코덱(`com.piiscan.io.Json`)이 처리합니다.
- **체크섬** 은 공개된 알고리즘을 직접 구현했습니다 — Luhn(mod-10), 주민등록번호 가중치
  검사 숫자, 사업자등록번호 검사 숫자. `com.piiscan.validate.Checksums` 참고.
- **안정적인 엔진 종료 코드**(입력/패턴/출력 오류에 대해 1/2/3)로 analyzer가 배치별 실패를
  정확히 드러낼 수 있습니다.

## 테스트

```bash
cargo test --manifest-path engine/Cargo.toml   # 엔진 단위 + 통합 테스트
mvn -B test                                      # Java 모듈 테스트
```

analyzer 스위트에는 실제 엔진 바이너리를 구동하는 엔드투엔드 테스트가 포함돼 있으며, 엔진이
빌드되지 않았으면 자동으로 건너뜁니다. CI(`.github/workflows/ci.yml`)는 엔진을 먼저 빌드하므로
항상 이 테스트를 실행합니다.

## 디렉토리 구조

```
piiscan/
├── pom.xml                 # 부모(집계) POM
├── engine/                 # Rust 정규식 매칭 엔진 (Cargo로 빌드)
│   ├── src/{lib,main,pattern,record,scanner}.rs
│   └── tests/integration.rs
├── analyzer/               # Spring Boot CLI: 파이프라인 + 검증
│   └── src/main/java/com/piiscan/
│       ├── app/            # Spring Boot 진입점 (얇음)
│       ├── cli/            # 프레임워크 없는 ScanRunner + Main
│       ├── pipeline/       # 유계 생산자/소비자 스캔 파이프라인
│       ├── validate/       # 체크섬 검증기 + 레지스트리
│       ├── source/         # CSV·합성 데이터 소스
│       ├── io/             # JSONL + 최소 JSON 코덱
│       └── model/          # 레코드: 패턴, finding, 리포트
├── manager/                # Spring Boot 웹: 작업 큐 + SSE 대시보드
│   └── src/main/{java,resources}/
├── source-tester/          # Spring Boot CLI: 데이터 소스 사전 점검
├── samples/                # patterns.json, 샘플 CSV
└── .github/workflows/ci.yml
```

## 참고

멀티모듈 탐지 아키텍처(네이티브 매칭 + 프로세스 경계를 넘는 JVM 조율/검증)를 탐구하기 위해
만든 개인 프로젝트입니다. 모든 패턴은 공개적으로 문서화된 형식에 기반하며, 모든 샘플
데이터는 합성입니다 — 저장소 어디에도 실제 개인정보는 포함돼 있지 않습니다.

## 라이선스

MIT — [LICENSE](LICENSE) 참고.
