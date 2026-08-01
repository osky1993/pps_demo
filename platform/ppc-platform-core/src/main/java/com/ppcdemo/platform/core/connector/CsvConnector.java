package com.ppcdemo.platform.core.connector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 接入：csv:/abs/path.csv?idCol=0&valueCol=1
 * 首行若非数字视为表头自动跳过；金额列按「元」解析 ×100 转「分」（平台编码规范）。
 */
public final class CsvConnector implements DatasetConnector {

    @Override
    public boolean supports(String ref) {
        return ref.startsWith("csv:");
    }

    @Override
    public Dataset load(String ref) {
        URI uri = URI.create(ref);
        Path file = Path.of(uri.getPath());
        Map<String, String> params = parseQuery(uri.getQuery());
        int idCol = Integer.parseInt(params.getOrDefault("idCol", "0"));
        int valueCol = params.containsKey("valueCol") ? Integer.parseInt(params.get("valueCol")) : -1;

        List<Long> ids = new ArrayList<>();
        Map<Long, Long> amounts = valueCol >= 0 ? new HashMap<>() : null;
        try (var lines = Files.lines(file)) {
            lines.filter(l -> !l.isBlank()).forEach(line -> {
                String[] cells = line.split(",");
                long id;
                try {
                    id = Long.parseLong(cells[idCol].trim());
                } catch (NumberFormatException headerLine) {
                    return;   // 表头行跳过
                }
                ids.add(id);
                if (amounts != null) {
                    amounts.put(id, new BigDecimal(cells[valueCol].trim())
                            .movePointRight(2).longValueExact());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("CSV 读取失败：" + file, e);
        }
        long[] idArray = ids.stream().mapToLong(Long::longValue).toArray();
        return new Dataset(idArray, amounts, Fingerprints.of(idArray, amounts));
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        return params;
    }
}
