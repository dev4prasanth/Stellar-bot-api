package com.stellartrack.staking.application;

import com.stellartrack.staking.domain.StakingPlanStep;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

/**
 * Parses staking plans in the approved declarative schema (version 1). Files are treated strictly as data:
 * formulas are rejected, macro-enabled workbooks are refused, and no cell content is ever evaluated.
 *
 * <pre>
 * step,amount,amount_type,on_win,on_loss
 * 1,2.00,STAKE,RESET,2
 * 2,4.00,STAKE,RESET,3
 * 3,8.00,STAKE,RESET,RESET
 * </pre>
 */
public final class StakingPlanParser {

    public static final int SCHEMA_VERSION = 1;
    public static final List<String> HEADERS = List.of("step", "amount", "amount_type", "on_win", "on_loss");
    static final int MAX_STEPS = 500;
    static final int MAX_BYTES = 1_048_576;

    public record ParseResult(List<StakingPlanStep> steps, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private StakingPlanParser() {
    }

    public static ParseResult parse(String filename, byte[] content) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (content.length == 0) {
            return failure("File is empty");
        }
        if (content.length > MAX_BYTES) {
            return failure("File exceeds " + MAX_BYTES + " bytes");
        }
        try {
            if (lower.endsWith(".csv")) {
                return validate(readCsv(content));
            }
            if (lower.endsWith(".xlsx")) {
                return validate(readXlsx(content));
            }
            return failure("Only .csv and .xlsx files are accepted (macro-enabled workbooks are refused)");
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return failure("File could not be read as " + (lower.endsWith(".csv") ? "CSV" : "XLSX"));
        }
    }

    private static List<List<String>> readCsv(byte[] content) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (String raw : line.split(",", -1)) {
                    String cell = raw.trim();
                    if (cell.length() >= 2 && cell.startsWith("\"") && cell.endsWith("\"")) {
                        cell = cell.substring(1, cell.length() - 1).trim();
                    }
                    cells.add(cell.startsWith("﻿") ? cell.substring(1) : cell);
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private static List<List<String>> readXlsx(byte[] content) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getFirstSheet();
            try (Stream<Row> stream = sheet.openStream()) {
                for (Row row : (Iterable<Row>) stream::iterator) {
                    List<String> cells = new ArrayList<>();
                    for (int i = 0; i < row.getCellCount(); i++) {
                        Cell cell = row.getCell(i);
                        if (cell != null && cell.getType() == CellType.FORMULA) {
                            throw new IllegalArgumentException("Row " + (row.getRowNum()) + ": formulas are not allowed");
                        }
                        cells.add(cell == null ? "" : cell.getRawValue() == null ? "" : cell.getRawValue().trim());
                    }
                    if (cells.stream().anyMatch(c -> !c.isBlank())) {
                        rows.add(cells);
                    }
                }
            }
        }
        return rows;
    }

    private static ParseResult validate(List<List<String>> rows) {
        List<String> errors = new ArrayList<>();
        if (rows.isEmpty()) {
            return failure("No header row found");
        }
        List<String> header = rows.getFirst().stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        if (!header.equals(HEADERS)) {
            return failure("Header must be exactly: " + String.join(",", HEADERS));
        }
        List<List<String>> data = rows.subList(1, rows.size());
        if (data.isEmpty()) {
            return failure("Plan has no steps");
        }
        if (data.size() > MAX_STEPS) {
            return failure("Plan has more than " + MAX_STEPS + " steps");
        }
        List<StakingPlanStep> steps = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            int line = i + 2;
            List<String> cells = data.get(i);
            if (cells.size() != HEADERS.size()) {
                errors.add("Row " + line + ": expected " + HEADERS.size() + " columns");
                continue;
            }
            try {
                int step = parseInt(cells.get(0), "step", line);
                if (step != i + 1) {
                    errors.add("Row " + line + ": steps must be consecutive starting at 1");
                }
                BigDecimal amount = parseAmount(cells.get(1), line);
                StakingPlanStep.AmountType type = StakingPlanStep.AmountType.valueOf(cells.get(2).toUpperCase(Locale.ROOT));
                steps.add(new StakingPlanStep(step, amount, type, parseNext(cells.get(3), "on_win", line),
                        parseNext(cells.get(4), "on_loss", line)));
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage().startsWith("Row") ? e.getMessage() : "Row " + line + ": invalid amount_type (STAKE or LIABILITY)");
            }
        }
        Set<Integer> valid = new HashSet<>();
        steps.forEach(s -> valid.add(s.step()));
        for (StakingPlanStep s : steps) {
            if (s.onWin() != null && !valid.contains(s.onWin()) || s.onLoss() != null && !valid.contains(s.onLoss())) {
                errors.add("Step " + s.step() + ": on_win/on_loss refers to a step that does not exist");
            }
        }
        return new ParseResult(errors.isEmpty() ? List.copyOf(steps) : List.of(), List.copyOf(errors));
    }

    private static int parseInt(String raw, String column, int line) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Row " + line + ": " + column + " must be a whole number");
        }
    }

    private static BigDecimal parseAmount(String raw, int line) {
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() <= 0 || value.scale() > 2) {
                throw new IllegalArgumentException("Row " + line + ": amount must be positive with at most 2 decimals");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Row " + line + ": amount must be a number");
        }
    }

    private static Integer parseNext(String raw, String column, int line) {
        if ("RESET".equalsIgnoreCase(raw)) {
            return null;
        }
        return parseInt(raw, column, line);
    }

    private static ParseResult failure(String error) {
        return new ParseResult(List.of(), List.of(error));
    }
}
