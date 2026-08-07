package com.piiscan.scanner.broker;

import java.nio.file.Path;
import java.time.Instant;

/**
 * broker를 통해 producer→consumer로 전달되는 claim-check 메시지.
 *
 * <p>실제 페이로드(파싱된 값들)는 디스크의 {@code inputFile}에 있고, 메시지에는 그 참조와
 * 원본 파일 메타만 싣는다. consumer는 {@code inputFile}을 엔진에 넘기고, 처리 후
 * {@code sourceOriginal}(=.processing/으로 옮겨둔 원본)을 processed/·failed/로 이동한다.
 *
 * @param scanId         이 파일 처리의 식별자
 * @param fileName       원본 파일명
 * @param fileSize       원본 파일 크기(bytes)
 * @param ext            확장자(소문자)
 * @param inputFile      통일 input.jsonl 경로(실제 페이로드)
 * @param sourceOriginal .processing/으로 옮겨둔 원본 파일 경로
 * @param enqueuedAt     발행 시각
 */
public record ScanTask(
        String scanId,
        String fileName,
        long fileSize,
        String ext,
        Path inputFile,
        Path sourceOriginal,
        Instant enqueuedAt) {
}
