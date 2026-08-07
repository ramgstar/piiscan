package com.piiscan.scanner.parse;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Streaming JSON parser backed by Jackson. Walks the token stream, tracking a
 * JSON path (e.g. {@code $.customers[3].email}), and emits every string leaf with
 * a {@link Location#json} position. Numbers, booleans and nulls are ignored.
 *
 * <p>Supports a top-level object or array. The document is streamed token by token,
 * never materialized into a tree.
 */
public final class JsonFileParser implements Parser {

    private static final JsonFactory FACTORY = new JsonFactory();

    @Override
    public String extension() {
        return "json";
    }

    @Override
    public void parse(Path file, CellConsumer sink) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             JsonParser jp = FACTORY.createParser(in)) {

            // Stack of container frames. Each frame is either an object (waiting for a
            // pending field name) or an array (tracking the next element index).
            Deque<Frame> stack = new ArrayDeque<>();

            JsonToken token;
            while ((token = jp.nextToken()) != null) {
                switch (token) {
                    case FIELD_NAME -> {
                        Frame top = stack.peek();
                        if (top != null) {
                            top.field = jp.currentName();
                        }
                    }
                    case START_OBJECT -> {
                        advance(stack);
                        stack.push(Frame.object());
                    }
                    case START_ARRAY -> {
                        advance(stack);
                        stack.push(Frame.array());
                    }
                    case END_OBJECT, END_ARRAY -> stack.pop();
                    case VALUE_STRING -> {
                        advance(stack);
                        sink.accept(jp.getText(), Location.json(path(stack)));
                    }
                    default -> advance(stack);
                }
            }
        }
    }

    /**
     * Consume the position a scalar or container occupies within its parent: for an
     * array parent bump the element index, for an object parent clear the pending
     * field once used.
     */
    private static void advance(Deque<Frame> stack) {
        Frame top = stack.peek();
        if (top != null && top.array) {
            top.index++;
        }
    }

    /** Build the JSON path for the value currently occupying the top frame's slot. */
    private static String path(Deque<Frame> stack) {
        // Iterate from the root frame down to the leaf's parent.
        StringBuilder sb = new StringBuilder("$");
        // ArrayDeque iterator goes from head (top) to tail (root); reverse it.
        Frame[] frames = stack.toArray(new Frame[0]);
        for (int i = frames.length - 1; i >= 0; i--) {
            Frame f = frames[i];
            if (f.array) {
                sb.append('[').append(f.index - 1).append(']');
            } else if (f.field != null) {
                sb.append('.').append(f.field);
            }
        }
        return sb.toString();
    }

    /** One container level on the path stack. */
    private static final class Frame {
        private final boolean array;
        private String field;   // last field name seen (object frames)
        private int index;      // next element index (array frames)

        private Frame(boolean array) {
            this.array = array;
        }

        static Frame object() {
            return new Frame(false);
        }

        static Frame array() {
            return new Frame(true);
        }
    }
}
