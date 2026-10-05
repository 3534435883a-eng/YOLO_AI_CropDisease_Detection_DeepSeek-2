package com.example.Ece.agent.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * **有出处的整理知识清单**读取器（层级 A 标准 / 层级 B 论文与机构页）。
 *
 * <p><b>为什么需要它</b>：2026-09-26 端到端实测发现，赛题点名要求的两类交付物
 * （个性化农事管理方案、水肥调控处方）智能体做不出来——问"定植后一周该怎么管"
 * "膨果期浇多少水追多少肥"，回答都是"依据不足"。原因不是模型不会，是**知识库里 283 块全是病害**，
 * 栽培/水肥/环境调控这几类根本没有类别可以落（见 {@link KnowledgeChunk.FieldType} 的注释）。
 * 本读取器把标准条款与论文结论作为**摘要条目**灌进同一套检索通道。</p>
 *
 * <p><b>为什么标准与论文共用一个读取器</b>：二者的形状完全一样——都是"一批带出处的、
 * 按主题分条的整理知识"。区别只在 {@code sourceType}（A=标准，B=论文/机构页，见设计文档 §23.1）。
 * 首版类名只叫 {@code StandardKnowledgeReader}，接入论文时就成了"论文读出来报着标准的名"，
 * 与之前 {@code CuratedCropKnowledgeReader.SourceEntry} 那次是同一类错误，故更名。</p>
 *
 * <p><b>入库方式是"结构化摘要条目 + 出处 URL"，不是全文复制</b>：这是设计文档 §23.1 对层级 A
 * 已经定好的口径（同一节里 ICAMA 农药库因"未经允许不得复制"被明确放弃批量入库）。
 * 因此每个条目的 {@code text} 保留**条款号/表号与关键数值**，删去文档管理、包装运输
 * 等与本系统交付物无关的程序性内容；{@code licenseNote} 如实写明这一点。</p>
 *
 * <p><b>校验从严</b>：清单是手写的，写错一个字段名就要等到入库才炸，因此这里在启动时就检查
 * 编号唯一、来源代码/URL 合法、层级为 A 或 B、{@code topic} 与 {@code text} 非空。
 * 特别地，**{@code fieldType} 的名字长度必须 ≤ 16**——{@code field_type} 列是 {@code varchar(16)}，
 * 而这个项目已经在 {@code source_type} 上被同类问题咬过一次（设计文档 §23 的"真实 bug"记要）。</p>
 */
@Component
public class CitedKnowledgeReader {

    /** {@code agent_knowledge_chunk.field_type} 的列宽。超长会在入库时抛 Data too long。 */
    static final int FIELD_TYPE_MAX_LENGTH = 16;

    /**
     * 标准摘要清单。**选材按气候相近与内容互补，不按"省份越多越好"**。
     *
     * <p>资料库里现存的设施番茄标准有二十余份（见 {@code 农业论文/标准/README.md}），
     * 其中大半是各省「番茄生产技术规程」的**近重复**。全部入库会稀释检索、
     * 并把"同一件事几个省给不同数"的冲突面从一两处放大到十几处。因此入库的是：</p>
     * <ul>
     *   <li>国家/主产区各一份做基线（NY/T 5449、DB37/T 1849）；</li>
     *   <li>**越冬茬**两份（陕西 DB61、河南 DB41）——成都越冬栽培最接近这两个气候区；</li>
     *   <li>**分档施肥**一份（天津 DB12）——给出按目标产量换算的施肥法，是"个性化"的落地形式；</li>
     *   <li>**育苗与营养液**各一份（辽宁 DB21、山西 DB14）——补齐育苗期粒度与 EC/pH 目标区间。</li>
     * </ul>
     *
     * <p>2026-09-27 追加一份**非标准**来源：辽宁省农业农村厅的《主要农资产品价格简讯》
     *（{@code market-input-price-liaoning-2026w38.json}）。加它的原因是赛题写的领域是
     * "生产、**营销、管理和服务**"，而库里的非病害知识此前只有农事与水肥，
     * **成本与行情一个类别都没有**（{@code field_type} 里没有可以落的地方）。
     * 该来源满足本读取器的全部要求：官方发布、有可核对 URL、有发布日期与数值口径。</p>
     */
    private static final String[] RESOURCES = {
            "/knowledge/guidance-tomato-weather.json",
            "/knowledge/guidance-tomato-wind-rain.json",
            "/knowledge/guidance-tomato-humidity.json",
            "/knowledge/papers-tomato-feedback.json",
            "/knowledge/papers-tomato-assimilation.json",
            "/knowledge/papers-tomato-digital-twin.json",
            "/knowledge/standards-nyt5449.json",
            "/knowledge/standards-db37t1849.json",
            "/knowledge/standards-db61t1422.json",
            "/knowledge/standards-db41t1350.json",
            "/knowledge/standards-db12t1044.json",
            "/knowledge/standards-db21t3417.json",
            "/knowledge/standards-db14t1700.json",
            "/knowledge/papers-tomato-npk-uptake.json",
            // 用户补充论文只录经核对摘要；条件、表号与适用边界随条目保留。
            "/knowledge/papers-tomato-environment-control.json",
            "/knowledge/papers-tomato-supplemental-light.json",
            "/knowledge/papers-tomato-mulch-co2.json",
            "/knowledge/papers-tomato-soil-intercrop.json",
            "/knowledge/papers-tomato-maize-whitefly.json",
            "/knowledge/papers-tomato-foliar-calcium.json",
            "/knowledge/standards-pesticide-tomato.json",
            "/knowledge/market-input-price-liaoning-2026w38.json",
            // 2026-09-27：其余 8 种作物此前**只有病害知识、农事类为 0**，问"这一周该怎么管"
            // 必然落到"依据不足"。先入"水肥一体化"这一批——它与番茄 DB37/T 1849 同型，
            // 是唯一按生育期给水量与折纯养分量的形态，能直接把水肥处方从"给不出"变成"给得出"。
            "/knowledge/standards-db41t998-corn.json",
            "/knowledge/standards-db41t1004-wheat.json",
            "/knowledge/standards-db37t4057-potato.json",
            "/knowledge/standards-db37t1851-strawberry.json",
            "/knowledge/standards-db1306t176-apple.json",
            "/knowledge/standards-db12t1357-grape.json"
    };

    public List<KnowledgeSourceEntry> readAll() {
        List<KnowledgeSourceEntry> entries = new ArrayList<KnowledgeSourceEntry>();
        Set<String> codes = new HashSet<String>();
        for (String path : RESOURCES) {
            readResource(path, codes, entries);
        }
        return entries;
    }

    /** Curated summaries and file fingerprints, independent of the live retrieval index. */
    public Map<String, Map<String, Object>> readLocalEvidence() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (String path : RESOURCES) {
            InputStream stream = getClass().getResourceAsStream(path);
            if (stream == null) throw new IllegalStateException("未找到知识摘要清单 " + path);
            try (InputStream input = stream; Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name())) {
                String text = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                JSONObject root = JSON.parseObject(text);
                JSONObject local = root == null ? null : root.getJSONObject("localDocument");
                if (local == null) continue;
                JSONObject source = root.getJSONObject("source");
                JSONArray entries = root.getJSONArray("entries");
                if (source == null || entries == null || entries.isEmpty()) {
                    throw new IllegalStateException("本地论文摘要不完整 " + path);
                }
                String code = required(source, "sourceCode", path);
                String id = required(local, "documentId", path);
                String hash = required(local, "sha256", path);
                String relative = required(local, "relativePath", path).replace('\\', '/');
                long size = local.getLongValue("sizeBytes");
                if (!id.matches("[a-f0-9]{64}") || !hash.matches("[a-f0-9]{64}") || size <= 0
                        || result.containsKey(code)) {
                    throw new IllegalStateException("本地论文标识或文件指纹无效 " + path);
                }
                Map<String, Object> document = new LinkedHashMap<String, Object>();
                document.put("id", id);
                document.put("fileName", relative.substring(relative.lastIndexOf('/') + 1));
                document.put("sha256", hash);
                document.put("sizeBytes", Long.valueOf(size));
                document.put("reviewedAt", required(local, "reviewedAt", path));
                List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < entries.size(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    Map<String, Object> summary = new LinkedHashMap<String, Object>();
                    summary.put("id", Long.valueOf(entry.getLongValue("id")));
                    summary.put("topic", required(entry, "topic", path));
                    summary.put("fieldType", required(entry, "fieldType", path));
                    summary.put("text", required(entry, "text", path));
                    summaries.add(summary);
                }
                Map<String, Object> evidence = new LinkedHashMap<String, Object>();
                evidence.put("localDocument", document);
                evidence.put("reviewedSummaries", summaries);
                evidence.put("summaryCount", Integer.valueOf(summaries.size()));
                result.put(code, evidence);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("读取本地论文摘要失败 " + path, error);
            }
        }
        return result;
    }

    private void readResource(String path, Set<String> codes, List<KnowledgeSourceEntry> entries) {
        InputStream stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("未找到标准摘要清单 " + path);
        }
        try (InputStream input = stream; Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name())) {
            String text = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
            JSONObject root = JSON.parseObject(text);
            JSONObject sourceRow = root == null ? null : root.getJSONObject("source");
            JSONArray rows = root == null ? null : root.getJSONArray("entries");
            if (sourceRow == null || rows == null || rows.isEmpty()) {
                throw new IllegalStateException("标准摘要清单为空或不完整 " + path);
            }
            String sourceCode = required(sourceRow, "sourceCode", path);
            String sourceUrl = required(sourceRow, "sourceUrl", path);
            if (!codes.add(sourceCode)) {
                throw new IllegalStateException("来源代码重复：" + sourceCode);
            }
            if (!(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://"))) {
                throw new IllegalStateException("标准摘要清单的来源 URL 无效：" + sourceCode);
            }
            String sourceType = required(sourceRow, "sourceType", path);
            if (!"A".equals(sourceType) && !"B".equals(sourceType)) {
                throw new IllegalStateException("层级只能是 A（标准）或 B（论文/机构页）：" + sourceCode);
            }
            String crop = required(sourceRow, "crop", path);
            String table = required(sourceRow, "sourceTable", path);
            KnowledgeSource source = new KnowledgeSource(sourceCode, required(sourceRow, "sourceName", path),
                    sourceType, sourceRow.getIntValue("authorityLevel"), sourceUrl,
                    required(sourceRow, "licenseNote", path), required(sourceRow, "sourceVersion", path));

            Set<Long> ids = new HashSet<Long>();
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                long id = row.getLongValue("id");
                if (id <= 0 || !ids.add(Long.valueOf(id))) {
                    throw new IllegalStateException("标准摘要清单的条目编号无效或重复：" + sourceCode + " #" + id);
                }
                String topic = required(row, "topic", path);
                String fieldTypeName = required(row, "fieldType", path);
                KnowledgeChunk.FieldType fieldType = parseFieldType(fieldTypeName, sourceCode, id);
                Map<KnowledgeChunk.FieldType, String> fields =
                        new LinkedHashMap<KnowledgeChunk.FieldType, String>();
                fields.put(fieldType, required(row, "text", path));
                // 第四个参数在 IngestRecord 里叫 diseaseName，但对非病害条目它就是**条目主题**
                // （切块器据此写「主题：X」而不是「病害：X」，见 KnowledgeChunker.contextHeader）。
                entries.add(new KnowledgeSourceEntry(source,
                        new IngestRecord(table, id, crop, topic, fields)));
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("读取标准摘要清单失败 " + path, error);
        }
    }

    private KnowledgeChunk.FieldType parseFieldType(String name, String sourceCode, long id) {
        KnowledgeChunk.FieldType value;
        try {
            value = KnowledgeChunk.FieldType.valueOf(name);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("标准摘要清单的 fieldType 不是已知类别："
                    + sourceCode + " #" + id + " -> " + name);
        }
        if (name.length() > FIELD_TYPE_MAX_LENGTH) {
            throw new IllegalStateException("fieldType 超过列宽 " + FIELD_TYPE_MAX_LENGTH + "：" + name);
        }
        return value;
    }

    private String required(JSONObject row, String key, String path) {
        String value = row.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("标准摘要清单缺少 " + key + "：" + path);
        }
        return value.trim();
    }
}
