package com.piiscan.scanner.parse;

/**
 * 파서가 파일에서 뽑아낸 문자열 셀 하나를 전달받는 싱크.
 *
 * <p>파서는 값과 그 위치만 넘기고, dedup·집계·직렬화는 하류(InputWriter)가 담당한다.
 */
@FunctionalInterface
public interface CellConsumer {

    /**
     * @param value    스캔 대상 문자열 값(빈 문자열/공백은 파서가 걸러도 됨)
     * @param location 값이 발견된 위치
     */
    void accept(String value, Location location);
}
