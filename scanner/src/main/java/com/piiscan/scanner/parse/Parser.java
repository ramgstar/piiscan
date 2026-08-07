package com.piiscan.scanner.parse;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 확장자별 파일 파서 전략. 파일을 스트리밍으로 읽어 문자열 셀을 {@link CellConsumer}로 흘려보낸다.
 *
 * <p>구현체는 {@code CsvFileParser}(Apache Commons CSV), {@code JsonFileParser}(Jackson 스트리밍).
 * 클래스명은 Jackson의 {@code JsonParser}와 충돌을 피하려 {@code *FileParser}로 둔다.
 */
public interface Parser {

    /** 이 파서가 처리하는 확장자(소문자, 점 없음). 예: {@code "csv"}, {@code "json"}. */
    String extension();

    /**
     * 파일을 파싱하며 각 문자열 셀을 {@code sink}로 방출한다. 대용량을 대비해 스트리밍으로 처리한다.
     *
     * @throws IOException 읽기/파싱 실패
     */
    void parse(Path file, CellConsumer sink) throws IOException;
}
