package com.piiscan.scanner.pipeline;

/**
 * scanner가 stdout에 찍고 manager가 파싱하는 진행 마커 접두어.
 *
 * <p>각 마커 뒤에는 한 줄짜리 JSON이 붙는다.
 * <ul>
 *   <li>{@code PROGRESS={"total":N,"completed":M,"inFlight":k,"stage":"…","confirmed":c}}</li>
 *   <li>{@code FILE={"name":"…","status":"processed|failed","confirmed":c,"reason":"…"}}</li>
 *   <li>{@code SUMMARY={…run 요약…}}</li>
 * </ul>
 * manager와 scanner가 이 규약을 공유하므로 상수를 한 곳에 둔다.
 */
public final class Markers {

    public static final String PROGRESS = "PROGRESS=";
    public static final String FILE = "FILE=";
    public static final String SUMMARY = "SUMMARY=";

    private Markers() {
    }
}
