package com.example.Ece.agent.plan;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 解析的边界测试。
 *
 * <p>盯的都是**错了也不报错**的那几处：编码探测顺序反了会把 UTF-8 解成乱码、
 * 按列位置猜字段会把温度读成湿度、表头认不出却硬映射会造出一份看似正常的假农情。</p>
 */
class AgriSituationCsvReaderTest {

    private final AgriSituationCsvReader reader = new AgriSituationCsvReader();

    @Test
    void mapsRecognizedHeadersAndReportsUnknownOnesInsteadOfGuessing() throws IOException {
        AgriSituationCsvReader.CsvParseResult result = read(
                "日期,棚温,空气湿度,土壤含水率,回液EC\n"
                        + "2026-09-01,24,70,60,2.1\n"
                        + "2026-09-02,26,68,58,2.3\n");

        assertTrue(result.isOk(), result.getError());
        assertEquals(26.0, result.getInput().number("temperatureC").doubleValue(), 1e-9);
        assertEquals(68.0, result.getInput().number("humidityPct").doubleValue(), 1e-9);
        // 认不出的列必须**被报出来**——用户得知道自己传的哪些数据被忽略了，
        // 否则他会以为那些列已经进了推演。
        assertEquals(2, result.getUnrecognizedColumns().size());
        assertTrue(result.getUnrecognizedColumns().toString().contains("日期"));
        assertTrue(result.getUnrecognizedColumns().toString().contains("回液EC"));
        assertNull(result.getInput().number("co2Ppm"), "没认出的列不能被凑成别的字段");
    }

    @Test
    void takesTheLastDataRowAsCurrent() throws IOException {
        AgriSituationCsvReader.CsvParseResult result = read(
                "温度,湿度\n21,60\n23,65\n27,72\n");

        // 传感器日志是一段历史，当前棚况对应最后一行。
        assertEquals(27.0, result.getInput().number("temperatureC").doubleValue(), 1e-9);
        assertEquals(3, result.getDataRowCount());
        assertTrue(result.getNotes().toString().contains("最后一行"),
                "取最后一行这件事要说出来，否则用户不知道中间的行被忽略了");
    }

    @Test
    void stripsBomSoTheFirstHeaderStillMatches() throws IOException {
        byte[] csv = "温度,湿度\n25,70\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[csv.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(csv, 0, withBom, 3, csv.length);

        // Excel 另存为 CSV 时会写 BOM。不剥掉的话第一个表头永远匹配不上。
        AgriSituationCsvReader.CsvParseResult result = reader.read(new ByteArrayInputStream(withBom));

        assertTrue(result.isOk(), result.getError());
        assertEquals(25.0, result.getInput().number("temperatureC").doubleValue(), 1e-9);
        assertFalse(result.getUnrecognizedColumns().toString().contains("温度"));
    }

    @Test
    void readsGbkEncodedFile() throws IOException {
        // 中文 Excel 导出的 CSV 默认是 GBK。解错的表现是表头全变乱码、
        // 于是"没有任何一列能识别"，而用户以为自己传对了。
        byte[] gbk = "温度,湿度\n25,70\n".getBytes(Charset.forName("GB18030"));

        AgriSituationCsvReader.CsvParseResult result = reader.read(new ByteArrayInputStream(gbk));

        assertTrue(result.isOk(), result.getError());
        assertEquals(25.0, result.getInput().number("temperatureC").doubleValue(), 1e-9);
    }

    @Test
    void detectsSemicolonDelimiter() throws IOException {
        AgriSituationCsvReader.CsvParseResult result = read("温度;湿度\n25;70\n");

        assertTrue(result.isOk(), result.getError());
        assertEquals(25.0, result.getInput().number("temperatureC").doubleValue(), 1e-9);
        assertEquals(70.0, result.getInput().number("humidityPct").doubleValue(), 1e-9);
    }

    @Test
    void quotedFieldWithCommaStaysOneCell() throws IOException {
        AgriSituationCsvReader.CsvParseResult result =
                read("温度,症状\n25,\"下部叶片有黄斑,边缘干枯\"\n");

        assertEquals("下部叶片有黄斑,边缘干枯", result.getInput().text("symptoms"));
    }

    @Test
    void refusesInsteadOfMappingByPositionWhenNoHeaderIsRecognized() throws IOException {
        AgriSituationCsvReader.CsvParseResult result = read("a,b,c\n1,2,3\n");

        // 按位置猜的表现是"温度读到了湿度的值"，看起来一切正常。
        assertFalse(result.isOk());
        assertTrue(result.getError().contains("没有任何一列"), result.getError());
    }

    @Test
    void reportsUnparseableValueInsteadOfSilentlyDroppingIt() throws IOException {
        AgriSituationCsvReader.CsvParseResult result = read("温度,湿度\n二三十度,70\n");

        assertTrue(result.isOk(), result.getError());
        assertNull(result.getInput().number("temperatureC"));
        assertTrue(result.getNotes().toString().contains("无法从"),
                "解析不出的值要说出来，不能静默当没提供");
        // 同一行的其他列不受影响。
        assertEquals(70.0, result.getInput().number("humidityPct").doubleValue(), 1e-9);
    }

    @Test
    void rejectsEmptyFileWithReadableMessage() throws IOException {
        AgriSituationCsvReader.CsvParseResult result = read("");

        assertFalse(result.isOk());
        assertNotNull(result.getError());
    }

    private AgriSituationCsvReader.CsvParseResult read(String content) throws IOException {
        InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        return reader.read(stream);
    }
}
