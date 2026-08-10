# piiscan

착륙 폴더(`piiScanner/scanFiles`)에 놓인 **정형/반정형 파일**(CSV·JSON)을 감지해 개인식별정보
(PII)를 탐지하는 백엔드 파이프라인입니다. **manager**(Spring Boot 웹)가 스캔을 스케줄링/트리거하면
작업마다 **scanner**(Spring Boot 배치)가 뜨고, scanner는 파일 값을 네이티브 **Rust 엔진**으로
흘려보내 고속 정규식 매칭을 한 뒤 **체크섬**으로 확정합니다. 진행 상황은 SSE로 실시간
대시보드에 스트리밍되고, 과거 결과는 이력 탭에서 리포트로 조회·내보내기 할 수 있습니다.

> **한 줄 요약:** 폴더에 파일을 두면 자동으로 민감정보(주민등록번호·사업자등록번호·카드번호·
> 이메일)를 탐지하는 멀티모듈 토이. **Rust 엔진**으로 정규식 후보를 빠르게 걸러내고 **Java
> 체크섬**(주민/사업자/Luhn)으로 오탐을 제거합니다. 모듈은 프로세스 경계(명령행 인자 + stdout
> 마커 + JSONL 파일)로만 통신합니다. *모든 패턴은 공개 형식 정의 기반, 샘플 데이터는 전부
> 합성이며 실제 개인정보는 어디에도 없습니다. 탐지 결과도 원본 대신 마스킹 샘플·위치·건수만
> 기록합니다.*

---

## 왜 2단계인가?

정규식은 어떤 값이 카드번호처럼 *보이는지*는 알려주지만 실제 카드번호인지까지는 말해주지
못합니다. 패턴에 걸린 16자리 대부분은 Luhn을, 주민번호처럼 보이는 13자리 대부분은 검사 숫자를
통과하지 못합니다. 체크섬 계산은 저렴하지만 수많은 값에 정규식을 돌리는 일은 그렇지 않으므로,
piiscan은 각 단계가 잘하는 일로 나눕니다.

- **Rust 엔진** — 패턴을 한 번 컴파일해 값을 흘려보내며 정규식 *후보*를 방출(핫 루프, 네이티브).
- **Java scanner** — 작업을 조율하고, 패턴별 **체크섬**으로 실제 탐지와 유사값을 분리.

효과는 결과에 바로 드러납니다 — 카드·주민 패턴은 정규식 후보의 약 절반이 체크섬에서 걸러집니다.

## 아키텍처

**코드를 공유하지 않는** 세 컴포넌트가 오직 프로세스 경계로만 통신합니다.

```
   브라우저 ──HTTP/SSE──▶ ┌───────────────────────────────────────────────┐
                         │        manager  (상시 웹, dev :5050 / prod :8080) │
                         │  스케줄(@Scheduled)·수동 트리거·실시간/이력 대시보드 │
                         └───────────────┬───────────────────────────────┘
                                         │ ProcessBuilder (run 당 1개)
                          인자 ───────────▼─── PROGRESS= / FILE= / SUMMARY= (stdout 마커)
                         ┌───────────────────────────────────────────────┐
                         │        scanner  (run 당 배치 프로세스)          │
                         │  ingest(클레임) → producer → broker → consumer  │  (가상 스레드)
                         │  → 2차 체크섬 → 마스킹 리포트 → 파일 이동        │
                         └───────────────┬───────────────────────────────┘
                                         │ ProcessBuilder (파일당 1개)
                          input.jsonl ───▼── output.jsonl
                         ┌───────────────────────────────────────────────┐
                         │        engine  (네이티브, Rust)                 │
                         │  패턴 컴파일 → 스트리밍 매칭 → 방출              │
                         └───────────────────────────────────────────────┘
```

프로세스 트리는 **manager(상시) → scanner(run 당) → engine(파일당)** 3단입니다. manager↔scanner는
stdout 마커, scanner↔engine은 JSONL 파일로 통신합니다.

| 모듈       | 역할                                                                   | 런타임                       |
|------------|------------------------------------------------------------------------|------------------------------|
| `manager`  | 스케줄·수동 트리거, scanner 프로세스 실행, 마커 파싱, 실시간/이력 대시보드, 결과 조회 API | Spring Boot 웹(:5050/:8080) |
| `scanner`  | 폴더 인제스트·클레임, 파싱(dedup+위치), 엔진 실행, 체크섬 확정, 마스킹 리포트, 보존 정리 | Spring Boot 배치(run당)     |
| `engine`   | 정규식 패턴 매칭 전담                                                   | 네이티브 바이너리(Rust)      |

## 처리 흐름

1. 사용자가 `piiScanner/scanFiles/`에 CSV/JSON을 떨군다.
2. manager가 **스케줄(fixedDelay) 또는 수동 트리거(`POST /api/v1/scan/run`)** 로 scanner를 실행
   (중복 실행은 `RunState` 가드로 방지).
3. scanner의 **FileScanner**가 대상 파일을 `.processing/`으로 **원자적 이동(클레임)** — 이 이동이
   중복 처리 방지와 "쓰는 중" 파일 가드를 동시에 해결한다.
4. **파일 1건당 producer**(가상 스레드)가 확장자별 파서(Strategy)로 파싱하며 **dedup**하고 값의
   **위치**를 모아 통일 `input.jsonl`을 쓴 뒤, **broker**(유계 큐)에 claim-check을 publish.
5. **consumer 풀**(yml)이 파일별로 **엔진(정규식) → 체크섬(2차)** 을 돌려 확정하고, 마스킹된
   리포트를 `results/<runId>/`에 쓴 뒤 원본을 `processed/`(성공)·`failed/`(실패)로 이동.
6. 진행 상황은 `PROGRESS=`/`FILE=`/`SUMMARY=` 마커로 나가고 manager가 SSE로 대시보드에 중계.
   완료된 run은 **결과 이력** 탭에서 리포트로 조회하고 HTML로 내보낼 수 있다.

## 패턴

엔진(`regex`)과 scanner(`validator`)가 함께 읽는 공유 JSON(`patterns.json`)에 정의됩니다.

| id       | 매칭 대상        | 검증기   |
|----------|------------------|----------|
| `KR_RRN` | 주민등록번호     | `kr_rrn` |
| `CARD`   | 신용카드번호     | `luhn`   |
| `KR_BRN` | 사업자등록번호   | `kr_brn` |
| `EMAIL`  | 이메일 주소      | `none`   |

`none`은 정규식 매칭을 그대로 통과(이메일은 검사 숫자 없음). 패턴 추가는 데이터 변경이고, 새
*종류*의 검증기 추가는 `ValidatorRegistry`에 한 줄입니다. 결과 리포트에는 patterns.json의
버전/해시를 남겨 재현성을 확보합니다.

## 보안 원칙

PII 스캐너의 결과물이 새 유출원이 되면 안 됩니다. **탐지된 원본 값은 결과에 평문으로 남기지
않고**, 위치(파일·행·열/JSON 경로), 매칭 패턴, **마스킹 샘플**(예 `901231-1******`), 건수만
기록합니다.

## 빌드

사전 요구사항: Rust 툴체인(1.70+)과 JDK 21+.

```bash
# 1. 네이티브 엔진 빌드
cargo build --release --manifest-path engine/Cargo.toml
# 2. Java 모듈 빌드(scanner, manager)
mvn -B package
```

> **Spring Boot 4.0.1**(Spring Framework 7, Jakarta EE 11), **Java 21**. CSV 파서는 Apache
> Commons CSV, JSON은 Jackson 스트리밍(jackson-core 2.x 핀 고정).

## 실행

저장소 루트에서 manager를 실행하세요(scanner·엔진·`piiScanner/` 경로를 상대 경로로 찾습니다).

```bash
java -jar manager/target/piiscan-manager.jar
# 대시보드: http://localhost:5050  (dev)
```

`piiScanner/scanFiles/`에 파일을 넣고 대시보드에서 **지금 스캔**(또는 스케줄 대기) → 실시간
진행이 보이고, **결과 이력** 탭에서 run별 리포트(패턴 차트 + 파일별 탐지표)를 열람·HTML
내보내기 할 수 있습니다.

### scanner 단독 실행

```bash
java -jar scanner/target/piiscan-scanner.jar --run-id manual-1
```

### 엔진 단독 실행

```bash
printf '{"value":"901231-1234568","count":1}\n' > /tmp/in.jsonl
engine/target/release/piiscan-engine \
  --patterns piiScanner/patterns.json --input /tmp/in.jsonl --output /tmp/out.jsonl --stats
cat /tmp/out.jsonl
```

## 배포 패키징

`dist` 프로필로 실행 스크립트·엔진·jar·설정·스캐폴드를 한 zip으로 묶습니다.

```bash
mvn clean package -Pdist
# → manager/target/piiscan-manager.zip
```

압축 해제 후 루트에서 `startup.sh`(Linux) / `startup.bat`(Windows) 실행 → prod 프로필로 manager가
뜨고, `jars/piiscan-scanner.jar` · `anlys/piiscan-engine[.exe]` · `piiScanner/` 스캐폴드가 함께
포함됩니다. (`shutdown.sh`/`.bat`로 종료.)

## 로깅 · 설정

- **로깅**: logback으로 파일 기록. **scanner는 파일 전용**(`piiScanner/logs/scanner.log`) — stdout은
  마커 채널이라 오염되면 안 됩니다. manager는 콘솔 + `piiScanner/logs/manager.log`.
- **설정**: 각 모듈 `application.yml`의 `piiscan.*`(경로·consumer 수·broker 용량·마스킹 정책·보존
  상한 등)와 dev/prod 프로필. 경로 기본값은 `piiScanner/` 하위.

## 테스트

```bash
cargo test --manifest-path engine/Cargo.toml   # 엔진 단위 + 통합
mvn -B test                                      # Java 모듈 테스트
```

## 디렉토리 구조

```
piiscan/
├── pom.xml                    # 부모(집계) POM
├── engine/                    # Rust 정규식 매칭 엔진 (Cargo)
├── scanner/                   # Spring Boot 배치: 인제스트→파싱→엔진→체크섬→리포트
│   └── src/main/java/com/piiscan/
│       ├── scanner/{app,config,ingest,parse,broker,pipeline,report}/
│       └── {engine,validate,io,model}/   # 재사용 코어(프레임워크 비의존)
├── manager/                   # Spring Boot 웹: 스케줄·대시보드(실시간/이력)·결과 API·패키징
│   ├── src/main/{java,resources}/ , bin/ , assembly.xml
├── piiScanner/                # 런타임 작업 트리(scanFiles/patterns.json/results/…)
├── samples/                   # patterns.json, 샘플 데이터
├── docs/                      # 컨셉/구현 계획 문서
└── .github/workflows/ci.yml
```

## 문서

- [`docs/00-컨셉_v1.1.md`](docs/00-컨셉_v1.1.md) — 비즈니스 개념·아키텍처·운영 원칙
- [`docs/01-최초_구현_계획_v1.0.md`](docs/01-최초_구현_계획_v1.0.md) — 초기 구현 계획
- [`docs/02-결과_이력_추가_계획_v1.1.md`](docs/02-결과_이력_추가_계획_v1.1.md) — 결과 이력 기능 계획
- 모듈별 상세: [`engine`](engine/README.md) · [`scanner`](scanner/README.md) · [`manager`](manager/README.md)

## 참고

폴더 인제스트 기반 PII 탐지 아키텍처(네이티브 매칭 + 프로세스 경계를 넘는 JVM 조율/검증)를
탐구한 개인 프로젝트입니다. 모든 패턴은 공개 형식 정의 기반, 모든 샘플 데이터는 합성이며,
저장소 어디에도 실제 개인정보는 없습니다.

## 라이선스

MIT — [LICENSE](LICENSE) 참고.
