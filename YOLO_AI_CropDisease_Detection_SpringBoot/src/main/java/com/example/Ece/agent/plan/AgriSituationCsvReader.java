package com.example.Ece.agent.plan;

import cn.hutool.core.text.csv.CsvData;
import cn.hutool.core.text.csv.CsvReadConfig;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 农情 CSV → 结构化输入。
 *
 * <p><b>三条纪律</b>，与仓库既有的"取不到就留空、不按天气现象反推 PPFD"一脉相承：</p>
 * <ol>
 *   <li><b>表头认不出就不映射</b>，绝不按列位置猜——位置猜错的表现是"温度读到了湿度的值"，
 *       而它看起来一切正常。认不出的列进 {@link CsvParseResult#getUnrecognizedColumns()} 如实报出。</li>
 *   <li><b>多行数据取最后一行为"当前"</b>，并把行数报出来。传感器日志是一段历史，
 *       当前棚况对应的是最后一行；悄悄取第一行或求平均都是另一种误导。</li>
 *   <li><b>不新增依赖</b>：用已在依赖里的 Hutool CSV 解析引号字段，不引 POI/EasyExcel。</li>
 * </ol>
 */
@Component
public class AgriSituationCsvReader {

    /** 分隔符候选。中文 Excel 环境的"CSV"常是分号或 Tab。 */
    private static final char[] DELIMITERS = {',', ';', '\t'};

    public CsvParseResult read(InputStream rawStream) throws IOException {
        byte[] bytes = readAll(rawStream);
        String text = decode(bytes);
        if (text.trim().isEmpty()) {
            return CsvParseResult.error("文件是空的。");
        }

        char delimiter = detectDelimiter(text);
        CsvData data;
        try {
            // containsHeader 显式关掉：是否为首行表头由本类自己判定，
            // 让 Hutool 也判一次会出现"表头被消费掉、数据少一行"这种难以察觉的偏差。
            CsvReadConfig config = CsvReadConfig.defaultConfig();
            config.setContainsHeader(false);
            config.setFieldSeparator(delimiter);
            // 编码已经在 decode 里定过了，这里直接读字符串，不再绕一次字节流。
            data = CsvUtil.getReader(config).read(new StringReader(text));
        } catch (RuntimeException error) {
            return CsvParseResult.error("CSV 解析失败：" + error.getMessage());
        }

        List<CsvRow> rows = new ArrayList<CsvRow>();
        for (CsvRow row : data.getRows()) {
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return CsvParseResult.error("文件里没有可读的行。");
        }

        List<String> headers = new ArrayList<String>(rows.get(0));
        AgriSituationInput input = new AgriSituationInput();
        List<String> mapped = new ArrayList<String>();
        List<String> unrecognized = new ArrayList<String>();

        // 表头 → 字段。同一字段被多列命中时以**先出现的列**为准，并把重复报出来，
        // 而不是让后面的列悄悄覆盖前面的。
        List<SituationField> columnFields = new ArrayList<SituationField>();
        for (String header : headers) {
            SituationField field = SituationFields.byHeader(header);
            if (field == null) {
                unrecognized.add(header);
                columnFields.add(null);
                continue;
            }
            if (mapped.contains(field.getLabel())) {
                unrecognized.add(header + "（与前面的列重复，已忽略本列）");
                columnFields.add(null);
                continue;
            }
            mapped.add(field.getLabel());
            columnFields.add(field);
        }

        if (mapped.isEmpty()) {
            return CsvParseResult.error("表头里没有任何一列能识别为农情字段。"
                    + "请下载模板对照列名，或改用手填表单。未识别的列：" + String.join("、", unrecognized));
        }

        // 取最后一行非空记录作为"当前棚况"。
        CsvRow last = null;
        int dataRowCount = 0;
        for (int index = 1; index < rows.size(); index++) {
            CsvRow row = rows.get(index);
            if (!row.isEmpty()) {
                last = row;
                dataRowCount++;
            }
        }
        if (last == null) {
            return CsvParseResult.error("文件只有表头，没有数据行。");
        }

        List<String> notes = new ArrayList<String>();
        for (int index = 0; index < columnFields.size() && index < last.size(); index++) {
            SituationField field = columnFields.get(index);
            if (field == null) {
                continue;
            }
            String cell = last.get(index);
            if (cell == null || cell.trim().isEmpty()) {
                notes.add(field.getLabel() + "：该行为空，按未提供处理");
                continue;
            }
            Object before = input.get(field.getKey());
            input.put(field.getKey(), cell);
            if (before == null && input.get(field.getKey()) == null) {
                // 写进去了却没生效，说明归一失败（如"二三十度"这种非数值表达）
                notes.add(field.getLabel() + "：无法从「" + cell.trim() + "」解析出可用数值，按未提供处理");
            }
        }

        if (dataRowCount > 1) {
            notes.add("文件含 " + dataRowCount + " 行数据，已取**最后一行**作为当前棚况");
        }
        return new CsvParseResult(input, mapped, unrecognized, dataRowCount, notes, null);
    }

    /**
     * 编码探测。
     *
     * <p><b>顺序不能反</b>：先严格 UTF-8 试解，抛异常才退 GB18030。反过来的话 GB18030
     * 几乎能解码任意字节序列、永远不会失败，于是永远不回落 UTF-8，UTF-8 文件会被解成乱码。</p>
     */
    private String decode(byte[] bytes) {
        // 剥 UTF-8 BOM——Excel 另存为 CSV 时会写，留着会让第一个表头永远匹配不上。
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strictUtf8.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, offset, bytes.length - offset, Charset.forName("GB18030"));
        }
    }

    /** 按第一个非空行里的分隔符出现次数选，避免把正文里的逗号当成列分隔符。 */
    private char detectDelimiter(String text) {
        String firstLine = "";
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                firstLine = line;
                break;
            }
        }
        char best = ',';
        int bestCount = -1;
        for (char candidate : DELIMITERS) {
            int count = 0;
            for (int index = 0; index < firstLine.length(); index++) {
                if (firstLine.charAt(index) == candidate) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    private byte[] readAll(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            // 农情表不该有几十兆。设上限是为了不让一个错误上传把内存吃掉。
            if (buffer.size() > 8 * 1024 * 1024) {
                throw new IOException("文件超过 8MB，农情数据表不应有这么大");
            }
        }
        return buffer.toByteArray();
    }

    /** 解析结果：结构化的农情 + 一份"哪些列认出来了、哪些没有、为什么"的报告。 */
    public static class CsvParseResult {

        private final AgriSituationInput input;
        private final List<String> mappedColumns;
        private final List<String> unrecognizedColumns;
        private final int dataRowCount;
        private final List<String> notes;
        private final String error;

        CsvParseResult(AgriSituationInput input, List<String> mappedColumns,
                       List<String> unrecognizedColumns, int dataRowCount, List<String> notes,
                       String error) {
            this.input = input;
            this.mappedColumns = mappedColumns == null ? new ArrayList<String>() : mappedColumns;
            this.unrecognizedColumns = unrecognizedColumns == null
                    ? new ArrayList<String>() : unrecognizedColumns;
            this.dataRowCount = dataRowCount;
            this.notes = notes == null ? new ArrayList<String>() : notes;
            this.error = error;
        }

        static CsvParseResult error(String message) {
            return new CsvParseResult(null, null, null, 0, null, message);
        }

        public boolean isOk() { return error == null; }

        public String getError() { return error; }

        public AgriSituationInput getInput() { return input; }

        public List<String> getMappedColumns() { return Collections.unmodifiableList(mappedColumns); }

        /** 没能映射到任何农情字段的列。**必须展示给用户**，否则他不知道自己传的东西被忽略了。 */
        public List<String> getUnrecognizedColumns() {
            return Collections.unmodifiableList(unrecognizedColumns);
        }

        public int getDataRowCount() { return dataRowCount; }

        public List<String> getNotes() { return Collections.unmodifiableList(notes); }
    }
}
