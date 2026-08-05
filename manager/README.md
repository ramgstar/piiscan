# piiscan-manager

[piiscan](../README.md)의 관리 단계로, 작은 Spring Boot 웹 애플리케이션(기본 포트 5050)입니다.
스캔 작업을 큐잉하고, 작업마다 [analyzer](../analyzer/README.md)를 자식 프로세스로 실행하며,
analyzer의 stdout 마커에서 진행 상황을 읽어 **실시간 대시보드**로 스트리밍합니다.

manager와 analyzer는 **코드를 공유하지 않습니다.** 오직 프로세스 경계로만 통신합니다 —
명령행 인자를 입력으로 주고, `PROGRESS=`/`RESULT=` 마커를 출력으로 받습니다. 덕분에 둘을
독립적으로 배포할 수 있습니다.

## 동작 방식

```
브라우저 ──POST /api/v1/scan/start──▶ JobManager
                                        │  ProcessBuilder 로 analyzer 실행
                                        ▼
                                   analyzer 프로세스 (작업당 1개)
                                        │  stdout: PROGRESS= / RESULT=
                                        ▼
                             stdout 파싱 → 작업 상태 갱신
                                        │
브라우저 ◀──SSE /stream── SseBroadcaster ◀┘  (progress / result / done / error 이벤트)
```

- **JobManager** 가 작업 생명주기를 소유합니다. analyzer를 실행하고, stdout을 한 줄씩 읽어
  `PROGRESS=`/`RESULT=` 마커를 파싱하고, 인메모리 작업 상태를 갱신하며, 프로세스를 정지시킬
  수 있습니다. 프로세스 실행/읽기는 가상 스레드에서 이뤄집니다.
- **SseBroadcaster** 가 각 작업을 구독한 브라우저들에게 server-sent event를 팬아웃합니다.
- 작업 상태는 **인메모리로만** 보관됩니다 — 이건 데모용 manager이지 영속 스케줄러가 아닙니다.

## 빌드

```bash
# 저장소 루트에서 (모든 모듈)
mvn -B package

# …또는 이 모듈만
mvn -B -pl manager -am package
```

생성물: `manager/target/piiscan-manager.jar` (실행 가능한 fat jar).

## 실행

엔진과 analyzer jar를 기본 경로로 찾을 수 있도록 **저장소 루트에서** 실행하세요(또는 아래
`piiscan.*` 속성을 절대 경로로 덮어쓰기):

```bash
java -jar manager/target/piiscan-manager.jar
# http://localhost:5050 접속
```

대시보드에서 합성 데이터 또는 CSV를 선택하고 **Start scan** 을 누르면, 배치·값·확정 행 수가
실시간으로 갱신되고 패턴별 확정 대 기각 막대 차트(Chart.js)가 그려집니다.

## 설정

`manager/src/main/resources/application.yml` (공통 설정 + `dev`/`prod` 프로필):

| 속성                      | 의미                                   | 기본값                                   |
|---------------------------|----------------------------------------|------------------------------------------|
| `server.port`             | 웹 서버 포트                           | `5050`                                   |
| `piiscan.java-bin`        | analyzer 실행에 쓸 `java`              | `java`                                   |
| `piiscan.engine-path`     | 엔진 바이너리 경로                     | `engine/target/release/piiscan-engine`   |
| `piiscan.analyzer-jar`    | analyzer jar 경로                      | `analyzer/target/piiscan-analyzer.jar`   |
| `piiscan.patterns-path`   | 패턴 JSON 경로                         | `samples/patterns.json`                  |

> 상대 경로는 manager의 작업 디렉토리를 기준으로 해석됩니다. 그래서 저장소 루트에서
> 실행하는 것을 권장합니다. 다른 위치에서 실행한다면 위 경로들을 절대 경로로 지정하세요.
> Windows에서는 확장자 없는 엔진 경로여도 analyzer가 `.exe` 를 자동으로 찾습니다.

### 프로필 (dev / prod)

`application.yml` 은 하나의 파일에 공통 설정과 `dev`/`prod` 프로필을 멀티도큐먼트(`---`)로
담고 있습니다. 기본 활성 프로필은 `dev` 입니다.

| 프로필 | 포트                | Thymeleaf 캐시 | 로그 레벨(root / com.piiscan) |
|--------|---------------------|----------------|-------------------------------|
| `dev`  | 5050                | 끔             | INFO / DEBUG                  |
| `prod` | `${PORT:8080}`      | 켬             | WARN / INFO                   |

```bash
# 기본(dev)
java -jar manager/target/piiscan-manager.jar

# prod 프로필로 실행
java -jar manager/target/piiscan-manager.jar --spring.profiles.active=prod
# 또는 환경변수: SPRING_PROFILES_ACTIVE=prod
```

## REST + SSE API

기본 경로: `/api/v1/scan`

| 메서드   | 경로                    | 설명                                                   |
|----------|-------------------------|--------------------------------------------------------|
| `POST`   | `/start`                | 스캔 시작. 본문은 `ScanRequest`(아래), 응답은 작업 정보 |
| `GET`    | `/`                     | 모든 작업 목록                                         |
| `GET`    | `/{id}`                 | 특정 작업 상태                                         |
| `DELETE` | `/{id}`                 | 실행 중인 작업 정지                                    |
| `GET`    | `/{id}/stream`          | SSE 스트림(`progress`/`result`/`done`/`error` 이벤트)  |

`ScanRequest` (JSON):

```json
{
  "mode": "synthetic",        // "synthetic" 또는 "csv"
  "syntheticRows": 20000,      // synthetic 모드 행 수
  "inputCsv": "samples/sample_customers.csv",  // csv 모드 경로
  "workers": 8,
  "batchSize": 2000
}
```

명령행 예시:

```bash
# 스캔 시작
curl -s -X POST http://localhost:5050/api/v1/scan/start \
  -H 'Content-Type: application/json' \
  -d '{"mode":"synthetic","syntheticRows":20000,"workers":8,"batchSize":2000}'

# 진행 상황 스트림 구독(작업 id를 넣어)
curl -N http://localhost:5050/api/v1/scan/<id>/stream
```

## 디렉토리 구조

```
manager/
├── pom.xml
└── src/main/
    ├── java/com/piiscan/manager/
    │   ├── ManagerApplication.java       # Spring Boot 진입점
    │   ├── controller/
    │   │   ├── ScanController.java       # REST + SSE 엔드포인트
    │   │   └── DashboardController.java  # 대시보드 페이지 서빙
    │   ├── service/
    │   │   ├── JobManager.java           # 작업 실행/생명주기, stdout 마커 파싱
    │   │   └── SseBroadcaster.java       # 작업별 SSE 팬아웃
    │   ├── dto/
    │   │   ├── ScanRequest.java          # 스캔 시작 파라미터
    │   │   └── JobDto.java               # 작업 JSON 뷰
    │   └── model/
    │       └── ScanJob.java              # 인메모리 작업 상태
    └── resources/
        ├── application.yml
        ├── templates/dashboard.html  # 대시보드(Thymeleaf)
        └── static/app.js             # 대시보드 클라이언트(SSE 구독 + Chart.js)
```
