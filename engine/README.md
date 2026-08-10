# piiscan-engine

[piiscan](../README.md)의 정규식 매칭 단계로, Rust로 작성됐습니다. 값들을 JSONL로 읽어들여
컴파일된 패턴 집합을 적용하고, 정규식 **후보**를 모두 JSONL로 다시 써냅니다. 정규식 매칭만
담당하며 — 체크섬 검증은 Java **scanner**에서 이뤄집니다. scanner의 consumer가 이 바이너리를
**파일당 한 번씩** 자식 프로세스로 실행합니다.

## 빌드

Rust 툴체인(1.70+)이 필요합니다. 이 디렉토리에서 빌드하거나, 저장소 루트에서 매니페스트를
지정해 빌드하세요:

```bash
# 저장소 루트에서
cargo build --release --manifest-path engine/Cargo.toml

# …또는 engine/ 안에서
cargo build --release
```

최적화된 바이너리 출력 위치:

| 플랫폼        | 경로                                          |
|--------------|-----------------------------------------------|
| Linux / WSL  | `engine/target/release/piiscan-engine`        |
| Windows      | `engine/target/release/piiscan-engine.exe`    |

릴리스 프로파일은 처리량을 위해 LTO와 `codegen-units = 1` 을 켜므로 릴리스 빌드는 디버그
빌드보다 조금 더 걸립니다. 개발 중 반복할 때는 `cargo build`(디버그)를 쓰세요.

### Windows 참고

Rust의 기본 Windows 툴체인은 **MSVC** 이며, Visual C++ 링커(`link.exe`)가 필요합니다.
`error: linker 'link.exe' not found` 가 보이면 빌드 도구를 한 번만 설치하세요:

```powershell
winget install Microsoft.VisualStudio.2022.BuildTools --override "--add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
```

그다음 터미널을 새로 열고 다시 빌드합니다. 또는 자체 링커를 포함해 Visual Studio가 필요 없는
GNU 툴체인을 쓸 수도 있습니다:

```powershell
rustup default stable-x86_64-pc-windows-gnu
cargo build --release --manifest-path engine/Cargo.toml
```

### Linux / WSL 참고

표준 빌드에는 별도의 시스템 패키지가 필요 없습니다. Rust가 아직 없다면:

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"
cargo build --release --manifest-path engine/Cargo.toml
```

한 Linux에서 빌드한 바이너리는 다른 Linux가 가진 것보다 최신 glibc를 요구할 수 있습니다
(`ldd --version` 으로 확인). 애매하면 실제로 실행할 머신에서 빌드하세요.

## 실행

```bash
piiscan-engine \
  --patterns ../samples/patterns.json \
  --input batch.jsonl \
  --output findings.jsonl \
  --stats
```

| 플래그        | 의미                                                |
|--------------|-----------------------------------------------------|
| `--patterns` | 패턴 정의(공유 JSON 파일) — 필수                     |
| `--input`    | `{ "value", "count" }` 레코드의 JSONL — 필수         |
| `--output`   | 써낼 후보 findings JSONL — 필수                      |
| `--stats`    | 완료 시 stderr에 `read=<n> findings=<n>` 출력        |
| `--version`  | 버전 출력                                            |
| `--help`     | 사용법 출력                                          |

### 종료 코드

scanner가 파일별 실패 유형을 구분할 수 있도록 안정적으로 유지됩니다:

| 코드 | 의미                          |
|------|-------------------------------|
| `0`  | 성공                          |
| `1`  | 입력 읽기 실패                |
| `2`  | 패턴 로드 / 컴파일 실패       |
| `3`  | 출력 쓰기 실패                |

## 데이터 형식

**입력** — 한 줄에 JSON 객체 하나. `count` 는 이 (중복 제거된) 값을 담고 있던 원본 행의
개수입니다. scanner는 여기에 위치(`locations`) 등 필드를 더 실어 보낼 수 있는데, **엔진은
`value`/`count` 외의 필드를 무시**합니다(위치는 이후 JVM 리포트 단계에서 값으로 조인):

```json
{"value":"901231-1234568","count":3}
{"value":"user@example.com","count":1}
```

**패턴** — `{ id, name, regex, validator }` 배열. `regex` 는 여기서 사용되고, `validator` 는
이후 scanner가 사용합니다:

```json
[
  {"id":"KR_RRN","name":"Korean resident registration number","regex":"\\d{6}-?[1-4]\\d{6}","validator":"kr_rrn"}
]
```

**출력** — 패턴에 매칭된 값마다 한 줄씩 finding을 씁니다. `count` 가 그대로 전달되어
scanner가 행 합계를 가중할 수 있습니다:

```json
{"patternId":"KR_RRN","validator":"kr_rrn","value":"901231-1234568","matched":"901231-1234568","count":3}
```

## 빠른 확인

```bash
printf '{"value":"901231-1234568","count":1}\n' > /tmp/in.jsonl
cargo run --release -- \
  --patterns ../samples/patterns.json --input /tmp/in.jsonl --output /tmp/out.jsonl --stats
cat /tmp/out.jsonl
```

## 테스트

```bash
cargo test --manifest-path engine/Cargo.toml
```

패턴 로드/컴파일, 레코드 코덱, 스캐너를 다루며, 빌드된 바이너리를 엔드투엔드로 실행하는 통합
테스트(`engine/tests/integration.rs`)도 포함합니다.

## 디렉토리 구조

```
engine/
├── Cargo.toml
└── src/
    ├── main.rs      # CLI 진입점 + 안정적 종료 코드
    ├── lib.rs       # 크레이트 루트
    ├── pattern.rs   # 패턴 집합 로드 + 컴파일
    ├── record.rs    # JSONL 입력/finding 타입 (serde)
    └── scanner.rs   # 스트리밍 매칭 루프
```
