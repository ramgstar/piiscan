# piiscan-source-tester

[piiscan](../README.md)의 데이터 소스 사전 점검 도구로, 작은 Spring Boot CLI입니다. 스캔을
시작하기 전에 소스가 사용 가능한지 확인하고, 그 판정을 manager가 파싱할 수 있는 **stdout
마커 한 줄**로 출력합니다:

```
SOURCE_TEST_RESULT={"ok":true,"rows":5,"cells":20,"message":"readable CSV with 5 data rows"}
```

이는 데이터 품질 플랫폼의 **연결 테스트(connection test)** 단계를 파일/합성 소스에 맞게 옮긴
대응물입니다. 실제 DB 커넥션 대신 파일 접근성과 행 수를 확인합니다.

## 빌드

```bash
# 저장소 루트에서 (모든 모듈)
mvn -B package

# …또는 이 모듈만
mvn -B -pl source-tester -am package
```

생성물: `source-tester/target/piiscan-source-tester.jar` (실행 가능한 fat jar).

## 실행

```bash
# CSV 소스 점검
java -jar source-tester/target/piiscan-source-tester.jar --input samples/sample_customers.csv

# 합성 소스 점검(항상 사용 가능)
java -jar source-tester/target/piiscan-source-tester.jar --synthetic
```

| 플래그        | 의미                                    |
|--------------|-----------------------------------------|
| `--input`    | 점검할 CSV 파일 경로                     |
| `--synthetic`| 합성 소스 점검(항상 `ok:true`)          |

## 출력 마커

`SOURCE_TEST_RESULT={...}` JSON 필드:

| 필드      | 의미                                              |
|-----------|---------------------------------------------------|
| `ok`      | 소스 사용 가능 여부(`true`/`false`)               |
| `rows`    | 데이터 행 수(헤더 제외)                           |
| `cells`   | 비어 있지 않은 셀 수                              |
| `message` | 사람이 읽는 설명 또는 실패 사유                    |

판정 예시:

- 파일 없음 → `{"ok":false,...,"message":"file not found: ..."}`
- 헤더만 있고 데이터 행 없음 → `{"ok":false,...,"message":"no data rows found (only a header?)"}`
- 정상 → `{"ok":true,"rows":5,"cells":20,"message":"readable CSV with 5 data rows"}`

## 디렉토리 구조

```
source-tester/
├── pom.xml
└── src/main/
    ├── java/com/piiscan/sourcetester/
    │   └── SourceTesterApplication.java   # Spring Boot CLI + SOURCE_TEST_RESULT 마커
    └── resources/
        └── application.yml                # 웹 비활성, stdout 정리, dev/prod 프로필
```
