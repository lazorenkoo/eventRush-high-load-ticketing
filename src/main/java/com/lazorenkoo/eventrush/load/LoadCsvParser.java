package com.lazorenkoo.eventrush.load;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class LoadCsvParser {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    public Parsed parse(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            String[] lines = text.split("\r?\n", 50003);
            int end = lines.length;
            if (lines[end - 1].isEmpty()) end--;
            if (end < 2 || end > 50001 || !fields(lines[0]).equals(List.of("firstName", "lastName", "seatNumber"))) {
                throw invalid();
            }
            var rows = new ArrayList<Row>(end - 1);
            for (int index = 1; index < end; index++) {
                var values = fields(lines[index]);
                if (values.size() != 3 || !values.get(2).matches("[1-9][0-9]{0,9}")) throw invalid();
                rows.add(new Row(name(values.get(0)), name(values.get(1)), Integer.parseInt(values.get(2))));
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Parsed(List.copyOf(rows), hash);
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private List<String> fields(String line) {
        var result = new ArrayList<String>(3);
        var value = new StringBuilder();
        boolean quoted = false;
        boolean closed = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                        value.append('"');
                        index++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else value.append(character);
            } else if (character == ',') {
                result.add(value.toString());
                if (result.size() > 2) throw invalid();
                value.setLength(0);
                closed = false;
            } else if (character == '"' && value.isEmpty() && !closed) {
                quoted = true;
            } else {
                if (closed || character == '"') throw invalid();
                value.append(character);
            }
            if (value.length() > 200) throw invalid();
        }
        if (quoted) throw invalid();
        result.add(value.toString());
        return result;
    }

    private String name(String value) {
        if (value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        String cleaned = value.strip();
        if (cleaned.isEmpty() || cleaned.codePointCount(0, cleaned.length()) > 100) throw invalid();
        return cleaned;
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOAD_CSV");
    }

    public record Row(String firstName, String lastName, int seatNumber) {
        @Override
        public String toString() {
            return "Row[redacted]";
        }
    }

    public record Parsed(List<Row> rows, String sha256) {
    }
}