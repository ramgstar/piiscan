package com.piiscan.scanner.parse;

import java.util.List;

/**
 * dedup으로 접힌 하나의 distinct 값과 그 등장 횟수, 위치 샘플.
 *
 * <p>{@code count}는 원본 등장 총계, {@code locations}는 {@code sample-locations} 상한 내 샘플이다.
 * InputWriter가 이를 통일 input JSONL 한 줄로 직렬화한다:
 * <pre>{@code {"value":"…","count":3,"locations":[{"row":12,"col":"memo"}]}}</pre>
 */
public record UnifiedValue(String value, long count, List<Location> locations) {
}
