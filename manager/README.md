# piiscan-manager

[piiscan](../README.md)의 관리 단계로, 상시 떠 있는 Spring Boot 웹 앱(dev :5050 / prod :8080)입니다.
스캔을 **스케줄링/수동 트리거**하고, run마다 [scanner](../scanner/README.md)를 자식 프로세스로
실행하며, scanner의 stdout 마커를 파싱해 **실시간 대시보드**로 스트리밍하고, 과거 결과를 **이력
탭**에서 리포트로 조회하게 합니다. 배포용 zip 패키징도 이 모듈이 담당합니다.

manager와 scanner는 **코드를 공유하지 않습니다** — 명령행 인자 입력과 `PROGRESS=`/`FILE=`/
`SUMMARY=` 마커 출력이라는 프로세스 경계로만 통신합니다.

## 스케줄링 · 중복 실행 방지

- `@Scheduled(fixedDelay)`로 주기 실행 + **수동 트리거** `POST /api/v1/scan/run`.
- `RunState`(AtomicBoolean CAS) 가드로 **이미 실행 중이면 스킵/409** → 스캔이 주기보다 오래 걸려도
  프로세스가 겹치지 않는다. 스케줄·수동 모두 같은 진입점(`ScannerLauncher.launchIfIdle()`)을 탄다.
- `ScannerLauncher`가 활성 프로필을 scanner에 전달해, 배포 레이아웃에서 scanner가 엔진을
  `anlys/`에서 찾도록 한다.

## 대시보드 (동일 페이지 2개 탭)

- **실시간**: 진행 바(완료/총), 단계, in-flight, 확정 수, 파일별 처리 로그, 패턴별 확정 차트
  (Chart.js) — SSE로 갱신.
- **결과 이력**: 완료 run 목록(최신순) → 클릭 시 요약·패턴 차트·**파일별 탐지표**(파일·패턴·이름·
  확정·마스킹 샘플·위치). **HTML 내보내기**(차트+그리드 포함 자체 완결 HTML, 오프라인에서도 열림;
  PDF는 브라우저 인쇄로 대체). 실시간 run 종료 시 목록 자동 새로고침.

## API

| 메서드   | 경로                                | 설명                                        |
|----------|-------------------------------------|---------------------------------------------|
| `POST`   | `/api/v1/scan/run`                  | 수동 스캔 시작(202 / 이미 실행 중이면 409)  |
| `GET`    | `/api/v1/scan/status`               | 현재 실행 상태 + 최신 진행                  |
| `GET`    | `/api/v1/scan/summary`              | 직전 run 요약                               |
| `GET`    | `/api/v1/scan/stream`               | SSE(progress/file/summary/end)              |
| `GET`    | `/api/v1/results`                   | 완료 run 목록(요약, 최신순)                 |
| `GET`    | `/api/v1/results/{runId}`           | run 상세(요약 + 파일 리포트)                |
| `GET`    | `/api/v1/results/{runId}/files/{name}` | 특정 파일 리포트                         |

결과 조회는 `results-dir`의 파일을 읽어 **원시 JSON을 조립**해 반환합니다(별도 JSON 라이브러리
비의존). `{runId}`/`{name}`은 정규식 검증으로 디렉토리 탈출을 차단하고, **완료(summary.json 존재)
run만 노출**합니다.

## 빌드 / 실행

```bash
mvn -B package                       # 또는  mvn -B -pl manager -am package
```

**저장소 루트에서** 실행하세요(scanner·엔진·`piiScanner/` 경로를 상대 경로로 찾습니다).

```bash
java -jar manager/target/piiscan-manager.jar
# 대시보드 http://localhost:5050 (dev)
```

## 배포 패키징 (`-Pdist`)

```bash
mvn clean package -Pdist            # → manager/target/piiscan-manager.zip
```

`dist` 프로필: scanner가 fat jar를 `manager/jars/`로 복사 → manager의 antrun이 `target/`에
bin·엔진(anlys)·jars·config·piiScanner 스캐폴드·logs를 모으고 bin 스크립트의 `@PROFILE` 토큰을
치환 → maven-assembly-plugin이 zip으로 묶습니다. zip 루트에서 `startup.sh`/`.bat`로 기동,
`shutdown.sh`/`.bat`로 종료. (meerkat mkat-manager 패키징 방식을 참고, 회사 특화 요소는 제외.)

## 설정 (application.yml)

| 키                          | 의미                          | 기본값                                 |
|-----------------------------|-------------------------------|----------------------------------------|
| `server.port`               | 웹 포트                       | 5050(dev) / `${PORT:8080}`(prod)       |
| `piiscan.scanner-jar`       | 실행할 scanner jar            | `scanner/target/…`(dev) / `jars/…`(prod)|
| `piiscan.java-bin`          | scanner 실행 java             | `java`                                 |
| `piiscan.results-dir`       | 이력 조회 디렉토리            | `piiScanner/results`                   |
| `piiscan.log-path`          | 로그 경로                     | `piiScanner/logs`                      |
| `piiscan.schedule.fixed-delay` | 스케줄 간격(ms)            | `60000`                                |

## 로깅

콘솔 + 파일(`piiScanner/logs/manager.log`, 롤링), `logback-spring.xml`. 레벨은 dev/prod 프로필.

## 디렉토리 구조

```
manager/
├── pom.xml , assembly.xml , bin/{startup,shutdown}.{sh,bat}
└── src/main/
    ├── java/com/piiscan/manager/
    │   ├── ManagerApplication.java            # @SpringBootApplication @EnableScheduling
    │   ├── controller/  ScanController, ResultsController, DashboardController
    │   ├── service/     ScannerLauncher, RunState, ScanScheduler, SseBroadcaster, ResultsService
    │   └── dto/         StatusDto
    └── resources/
        ├── application.yml , logback-spring.xml
        ├── templates/dashboard.html           # 실시간/이력 탭
        └── static/app.js                      # SSE 구독 + 이력/차트 + HTML 내보내기
```
