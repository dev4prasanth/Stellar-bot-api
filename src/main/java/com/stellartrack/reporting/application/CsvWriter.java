package com.stellartrack.reporting.application;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RFC 4180 CSV writer with spreadsheet formula-injection protection: text cells that begin with
 * {@code = + - @} or a tab are prefixed with an apostrophe. Numeric cells are written as-is.
 */
public final class CsvWriter {

    private final StringBuilder out = new StringBuilder();

    public CsvWriter row(List<?> cells) {
        out.append(cells.stream().map(CsvWriter::cell).collect(Collectors.joining(","))).append("\r\n");
        return this;
    }

    public String build() {
        return out.toString();
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
