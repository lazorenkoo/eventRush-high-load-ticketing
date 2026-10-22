package com.lazorenkoo.eventrush.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadCsvParserTest {
    private final LoadCsvParser parser = new LoadCsvParser();
    private static final String HEADER = "firstName,lastName,seatNumber\n";

    private LoadCsvParser.Parsed parse(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private void invalid(byte[] bytes) {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(bytes)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(400);
                    assertThat(exception.getReason()).isEqualTo("INVALID_LOAD_CSV");
                });
    }

    @Test
    void parsesBomCrLfUnicodeEscapedQuotesAndRawInputHash() throws Exception {
        String csv = "\uFEFFfirstName,lastName,seatNumber\r\n\"Zoë, A\",\"O\"\"Neil\",300\r\n";
        var result = parse(csv);
        assertThat(result.rows()).containsExactly(new LoadCsvParser.Row("Zoë, A", "O\"Neil", 300));
        assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(csv.getBytes(StandardCharsets.UTF_8))));
        assertThat(result.toString()).doesNotContain("Zoë", "Neil");
        assertThatThrownBy(() -> result.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "firstName,lastName,seatNumber", "lastName,firstName,seatNumber\nA,B,1",
            "firstName,lastName,seatNumber,extra\nA,B,1", "firstName,lastName,seatNumber\n\n"})
    void rejectsMissingRowsAndWrongHeaders(String csv) {
        invalid(csv.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A,B,0", "A,B,-1", "A,B,01", "A,B,2147483648", "A,B,1.5", "A,B, 1",
            "A,B", "A,B,1,extra", "\"A,B,1", "\"A\"x,B,1", "A\"x,B,1", "A,B,1\n\n",
            ",B,1", "A, ,1", "A\t,B,1", "A\r,B,1", "\"A\nZ\",B,1"})
    void rejectsMalformedRowsWithoutPartialResults(String row) {
        invalid((HEADER + row).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsMalformedUtf8() {
        invalid(new byte[] {(byte) 0xC3, (byte) 0x28});
    }

    @Test
    void countsUnicodeCodePointsAndRejectsOversizedFields() {
        String name = "\uD83D\uDE00".repeat(100);
        assertThat(parse(HEADER + name + ", B ,1").rows().getFirst().firstName()).isEqualTo(name);
        invalid((HEADER + name + "x,B,1").getBytes(StandardCharsets.UTF_8));
        invalid((HEADER + "A".repeat(201) + ",B,1").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsExactlyFiftyThousandRowsAndRejectsOneMore() {
        String csv = HEADER + "A,B,1\n".repeat(50000);
        assertThat(parse(csv).rows()).hasSize(50000);
        invalid((csv + "A,B,1\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void boundsBytesReadFromOversizedStream() {
        var input = new InputStream() {
            int read;
            @Override public int read() {
                read++;
                return 'A';
            }
            @Override public int read(byte[] bytes, int offset, int length) {
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 'A');
                read += length;
                return length;
            }
        };
        assertThatThrownBy(() -> parser.parse(input)).isInstanceOf(ResponseStatusException.class);
        assertThat(input.read).isEqualTo(16 * 1024 * 1024 + 1);
    }

    @Test
    void mapsInputFailureToSafeValidationError() {
        var input = new InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("private storage details");
            }
        };
        assertThatThrownBy(() -> parser.parse(input)).isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining("private storage details");
    }
}