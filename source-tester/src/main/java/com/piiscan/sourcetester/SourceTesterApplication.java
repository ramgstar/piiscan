package com.piiscan.sourcetester;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Source tester: a Spring Boot CLI that checks whether a data source is usable
 * before a scan is launched, and reports the verdict on stdout as a single
 * marker line the manager can parse:
 *
 * <pre>{@code SOURCE_TEST_RESULT={"ok":true,"rows":5,"cells":20,"message":"..."}}</pre>
 *
 * <p>This mirrors the connection-test step of a data-quality platform, adapted
 * to file/synthetic sources instead of live database connections.
 */
@SpringBootApplication
public class SourceTesterApplication implements CommandLineRunner {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(SourceTesterApplication.class, args)));
    }

    @Override
    public void run(String... args) {
        String input = null;
        boolean synthetic = false;
        for (int i = 0; i < args.length; i++) {
            if ("--input".equals(args[i]) && i + 1 < args.length) {
                input = args[++i];
            } else if ("--synthetic".equals(args[i])) {
                synthetic = true;
            }
        }

        if (input != null) {
            testCsv(Path.of(input));
        } else if (synthetic) {
            emit(true, 0, 0, "synthetic source is always available");
        } else {
            emit(false, 0, 0, "no source specified (use --input <csv> or --synthetic)");
        }
    }

    private void testCsv(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                emit(false, 0, 0, "file not found: " + path.toAbsolutePath());
                return;
            }
            if (!Files.isReadable(path)) {
                emit(false, 0, 0, "file not readable: " + path.toAbsolutePath());
                return;
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            long rows = 0;
            long cells = 0;
            for (int i = 1; i < lines.size(); i++) { // skip header
                if (lines.get(i).isBlank()) {
                    continue;
                }
                rows++;
                for (String cell : lines.get(i).split(",", -1)) {
                    if (!cell.isBlank()) {
                        cells++;
                    }
                }
            }
            if (rows == 0) {
                emit(false, 0, 0, "no data rows found (only a header?)");
            } else {
                emit(true, rows, cells, "readable CSV with " + rows + " data rows");
            }
        } catch (Exception e) {
            emit(false, 0, 0, "error reading source: " + e.getMessage());
        }
    }

    private void emit(boolean ok, long rows, long cells, String message) {
        System.out.println("SOURCE_TEST_RESULT={"
                + "\"ok\":" + ok
                + ",\"rows\":" + rows
                + ",\"cells\":" + cells
                + ",\"message\":" + jsonString(message)
                + "}");
        System.out.flush();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
