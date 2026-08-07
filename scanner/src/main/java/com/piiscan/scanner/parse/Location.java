package com.piiscan.scanner.parse;

/**
 * 탐지 값이 원본 파일에서 발견된 위치.
 *
 * <p>CSV는 {@code row}/{@code col}, JSON은 {@code path}만 채워지고 나머지는 {@code null}이다.
 * 리포트에는 마스킹된 값과 함께 이 위치가 기록된다.
 */
public record Location(Integer row, String col, String path) {

    /** CSV 위치: 데이터 행 번호(1부터) + 열(헤더명 또는 인덱스). */
    public static Location csv(int row, String col) {
        return new Location(row, col, null);
    }

    /** JSON 위치: JSON 경로(예: {@code $.customers[3].email}). */
    public static Location json(String path) {
        return new Location(null, null, path);
    }
}
