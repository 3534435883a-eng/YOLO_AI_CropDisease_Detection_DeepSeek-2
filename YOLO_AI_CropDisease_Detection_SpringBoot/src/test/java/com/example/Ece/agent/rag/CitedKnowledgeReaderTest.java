package com.example.Ece.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标准摘要清单的读取测试。
 *
 * <p>锁住三件事，都是"不测就会悄悄坏掉"的：</p>
 * <ol>
 *   <li>清单能被解析出来，且字段类型合法、来源登记为层级 A；</li>
 *   <li>{@code fieldType} 的名字**不超过 {@code field_type} 列的 16 字符**——
 *       本项目已经在 {@code source_type} 上被同类问题咬过一次，那个错误直到入库才报 Data too long；</li>
 *   <li>**关键数值真的在清单里**。清单是手抄的，抄漏一个数字不会让任何东西报错，
 *       只会让智能体答"依据不足"或答错，因此在测试里逐条盯住几个要害值。</li>
 * </ol>
 */
class CitedKnowledgeReaderTest {

    private final List<KnowledgeSourceEntry> entries = new CitedKnowledgeReader().readAll();

    @Test
    void readsEveryRegisteredStandard() {
        Set<String> codes = new HashSet<String>();
        for (KnowledgeSourceEntry entry : entries) {
            KnowledgeSource source = entry.getSource();
            codes.add(source.getSourceCode());
            assertTrue(Arrays.asList("A", "B").contains(source.getSourceType()),
                    "层级只能是 A（标准）或 B（论文/机构页）：" + source.getSourceCode());
            // 标准记为 1、论文/机构页记为 2，与设计文档 §23.1 的分层一致
            assertEquals("A".equals(source.getSourceType()) ? 1 : 2, source.getAuthorityLevel(),
                    "层级与权威级不匹配：" + source.getSourceCode());
            assertNotNull(source.getUrl());
            assertTrue(source.getUrl().startsWith("http"), "必须带可核对的出处链接");
            assertTrue(source.getVersion() != null && !source.getVersion().isEmpty());
        }
        assertEquals(new HashSet<String>(Arrays.asList(
                "std-nyt5449-2026", "std-db37t1849-2026", "std-db61t1422-2021",
                "std-db41t1350-2016", "std-db12t1044-2021", "std-db21t3417-2021",
                "std-db14t1700-2025", "paper-chu2021-npk-uptake", "std-gbt-pesticide-vegetable-2025draft",
                "market-ln-input-price-2026w38", "std-db41t998-2014", "std-db41t1004-2014",
                "std-db37t4057-2020", "std-db37t1851-2026")), codes);
    }

    /**
     * 出处链接必须能定位到具体一份文件（而不是一个目录）。
     *
     * <p>标准清单的 URL 形如 {@code .../portal/download/<64位pk>}，论文清单是 DOI 链接。
     * 首版写 DB61 时漏了 pk，只剩 {@code .../portal/download/}——校验只查了"以 http 开头"，
     * 于是照样通过。一条指向目录而非文件的链接，等于没有出处。
     * 这里不按长度卡（DOI 链接天然比带 pk 的下载链接短），而是卡"不是以斜杠结尾的目录"。</p>
     */
    @Test
    void sourceUrlsPointAtAConcreteDocument() {
        for (KnowledgeSourceEntry entry : entries) {
            String url = entry.getSource().getUrl();
            assertTrue(!url.endsWith("/"), "出处链接是目录不是文件："
                    + entry.getSource().getSourceCode() + " -> " + url);
            assertTrue(url.length() > "https://example.com/".length() + 3,
                    "出处链接过短，疑似缺文件标识：" + entry.getSource().getSourceCode() + " -> " + url);
        }
    }

    /** 层级只允许 A（标准）与 B（论文/机构页）；别的层级没有对应的整理清单格式。 */
    @Test
    void onlyAuthorityClassesAAndBAreAccepted() {
        for (KnowledgeSourceEntry entry : entries) {
            String type = entry.getSource().getSourceType();
            assertTrue("A".equals(type) || "B".equals(type),
                    "非 A/B 层级不该走本读取器：" + entry.getSource().getSourceCode() + " -> " + type);
        }
    }

    @Test
    void everyEntryIsChunkableAndWithinColumnWidths() {
        int fieldTypeMax = 0;
        for (KnowledgeSourceEntry entry : entries) {
            IngestRecord record = entry.getRecord();
            assertTrue(record.getCropType() != null && !record.getCropType().isEmpty());
            assertTrue(record.getDiseaseName() != null && !record.getDiseaseName().isEmpty(),
                    "非病害条目的这一列存的是**主题**，不得为空");
            assertTrue(record.getSourceId() > 0);
            Map<KnowledgeChunk.FieldType, String> fields = record.getFields();
            assertEquals(1, fields.size(), "标准条目一条只承载一个类别");
            for (Map.Entry<KnowledgeChunk.FieldType, String> field : fields.entrySet()) {
                fieldTypeMax = Math.max(fieldTypeMax, field.getKey().name().length());
                assertTrue(field.getValue() != null && field.getValue().trim().length() > 20,
                        "条目正文过短，多半是抄漏了：" + record.getDiseaseName());
            }
        }
        assertTrue(fieldTypeMax <= CitedKnowledgeReader.FIELD_TYPE_MAX_LENGTH,
                "field_type 列宽 " + CitedKnowledgeReader.FIELD_TYPE_MAX_LENGTH
                        + "，实测最长 " + fieldTypeMax + "——超长会在入库时抛 Data too long");
    }

    /** 非病害知识必须真的落在**新加的类别**上，否则等于没解决"没有类别可以落"的问题。 */
    @Test
    void nonDiseaseEntriesUseTheNewFieldTypes() {
        Set<KnowledgeChunk.FieldType> used = new HashSet<KnowledgeChunk.FieldType>();
        for (KnowledgeSourceEntry entry : entries) {
            used.addAll(entry.getRecord().getFields().keySet());
        }
        for (KnowledgeChunk.FieldType expected : Arrays.asList(
                KnowledgeChunk.FieldType.CULTIVATION, KnowledgeChunk.FieldType.WATER_FERT,
                KnowledgeChunk.FieldType.ENVIRONMENT, KnowledgeChunk.FieldType.CTRL_AGRI,
                KnowledgeChunk.FieldType.CTRL_PHYS, KnowledgeChunk.FieldType.CTRL_BIO,
                KnowledgeChunk.FieldType.CTRL_CHEM, KnowledgeChunk.FieldType.INPUT_COST)) {
            assertTrue(used.contains(expected), "标准清单里缺类别 " + expected);
        }
    }

    /** 只把病害类当病害；其余类别走「主题：」。 */
    @Test
    void fieldTypeClassifiesDiseaseSemantics() {
        assertTrue(KnowledgeChunk.FieldType.SYMPTOM.isDiseaseField());
        assertTrue(KnowledgeChunk.FieldType.CAUSE.isDiseaseField());
        assertTrue(KnowledgeChunk.FieldType.CONTROL.isDiseaseField());
        for (KnowledgeChunk.FieldType type : Arrays.asList(
                KnowledgeChunk.FieldType.CULTIVATION, KnowledgeChunk.FieldType.WATER_FERT,
                KnowledgeChunk.FieldType.ENVIRONMENT, KnowledgeChunk.FieldType.CTRL_AGRI,
                KnowledgeChunk.FieldType.CTRL_PHYS, KnowledgeChunk.FieldType.CTRL_BIO,
                KnowledgeChunk.FieldType.CTRL_CHEM, KnowledgeChunk.FieldType.OTHER,
                KnowledgeChunk.FieldType.INPUT_COST)) {
            assertTrue(!type.isDiseaseField(), type + " 不应被判为病害类");
        }
    }

    /**
     * 要害数值逐条盯住。
     *
     * <p>这些是让"水肥调控处方"与"农事管理方案"从"答不出"变成"答得出"的那几个数，
     * 抄错/抄漏不会报任何错，只会静默地把错误答案喂给农户。</p>
     */
    @Test
    void keyNumbersSurviveTranscription() {
        String all = joinAllText();
        // NY/T 5449—2026 环境调控：这组阈值是规则层 75/80/88 的无出处阈值目前**唯一**的对照
        assertTrue(all.contains("白天20℃~28℃"), "缺昼温阈值");
        assertTrue(all.contains("夜间13℃~17℃"), "缺夜温阈值");
        assertTrue(all.contains("土壤湿度60%~80%"), "缺土壤湿度阈值");
        assertTrue(all.contains("空气湿度45%~50%"), "缺空气湿度阈值");
        // 四类防治必须分开成条（赛题要的"病虫害防治建议"要能按类给）
        assertTrue(all.contains("5.3.8.2 农业防治"));
        assertTrue(all.contains("5.3.8.3 物理防治"));
        assertTrue(all.contains("5.3.8.4 生物防治"));
        assertTrue(all.contains("5.3.8.5 化学防治"));
        // DB37/T 1849—2026 水肥：灌溉下限与逐生育期制度表
        assertTrue(all.contains("土壤含水量低于60%~70%"), "缺灌溉下限");
        assertTrue(all.contains("129 m³/667m²"), "缺秋冬茬灌溉定额");
        assertTrue(all.contains("N 35.5 kg"), "缺全季施氮量");
        assertTrue(all.contains("折纯N 2.6"), "缺开花期单次施氮量（制度表首行）");
        assertTrue(all.contains("铵态氮与硝态氮比例为5:5或3:7"), "缺氮源形态配比");

        // DB12/T 1044—2021：单次滴灌水量（三处标准互相印证的那一个数）
        assertTrue(all.contains("每次滴灌水量为 8 m³/667m²~12 m³/667m²"), "缺单次滴灌水量");
        assertTrue(all.contains("22-12-16"), "缺高氮型滴灌追肥配方");
        assertTrue(all.contains("19-6-25"), "缺高钾型滴灌追肥配方");

        // DB61/T 1422—2021：按生育期的昼夜温度与「第几水」逐次追肥
        assertTrue(all.contains("缓苗期") && all.contains("25~28℃"), "缺缓苗期昼温");
        assertTrue(all.contains("田间持水量的 70%~80%"), "缺缓苗期土壤含水量");
        assertTrue(all.contains("第二水（缓苗水）"), "缺缓苗水追肥量");
        assertTrue(all.contains("(15-10-37)"), "缺盛果期水溶肥配方");

        // DB41/T 1350—2016：按出苗/齐苗/真叶分段的苗床温度
        assertTrue(all.contains("出苗期") && all.contains("28~30℃"), "缺出苗期温度");
        assertTrue(all.contains("齐苗后"), "缺齐苗后温度段");

        // DB21/T 3417—2021：定植后按生育期的单次水量
        assertTrue(all.contains("每次每亩浇水 10~12 m³"), "缺第一穗果膨大期单次水量");
        assertTrue(all.contains("每次每亩浇水 6 m³"), "缺着色成熟期单次水量");

        // DB14/T 1700—2025：EC / pH 目标区间与营养液配方
        assertTrue(all.contains("pH 值为 5.5~6.5"), "缺 pH 目标区间");
        assertTrue(all.contains("坐果后 2.5~3.5"), "缺坐果后 EC 区间");
        assertTrue(all.contains("EDTA-二钠铁"), "缺微量元素配方");

        // 褚屿等 2021：逐生育期养分吸收比例——模型土壤氮收支缺的就是这几条
        assertTrue(all.contains("开花期") && all.contains("18.68%"), "缺开花期吸氮占比");
        assertTrue(all.contains("68.08%"), "缺坐果后期吸氮占比");
        assertTrue(all.contains("3.0∶1.0∶4.7"), "缺氮磷钾吸收比例");
        assertTrue(all.contains("8.1∶1"), "缺钙镁吸收比例");
        assertTrue(all.contains("44.89%"), "缺成熟期果实氮分配率");

        // 《农药合理使用准则 蔬菜》征求意见稿：番茄三病的登记用药与安全间隔期
        assertTrue(all.contains("苯醚甲环唑 10% 水分散粒剂 **100 g**"), "缺早疫病药剂用量");
        assertTrue(all.contains("腐霉利 50% 可湿性粉剂 **100 g**"), "缺灰霉病药剂用量");
        assertTrue(all.contains("丙森锌 70% 可湿性粉剂 **214 g**"), "缺晚疫病药剂用量");
        assertTrue(all.contains("吡噻菌胺 20% 悬浮剂 **65 mL**"), "缺灰霉病药剂用量");
        // 「征求意见稿」这个身份必须逐条带出——它是本清单最大的使用边界
        assertTrue(all.contains("征求意见稿"), "缺征求意见稿的身份说明");

        // 辽宁省农业农村厅农资价格简讯：成本侧唯一的官方数值来源
        assertTrue(all.contains("1747.50 元/吨"), "缺尿素全国出厂均价");
        assertTrue(all.contains("3425.00 元/吨"), "缺 15-15-15 复合肥全国出厂均价");
        assertTrue(all.contains("4775.00 元/吨"), "缺磷酸二铵全国出厂均价");
        assertTrue(all.contains("2026-09-14 至 09-18"), "缺监测期——价格离开日期就没有意义");
        assertTrue(all.contains("低于农户实际到手价"), "缺「出厂价不等于到手价」的口径提示");

        // 其余作物的水肥制度（2026-09-27）：这几条是把"另 8 种作物农事类为 0"补起来的依据
        assertTrue(all.contains("90 m³/667m²"), "缺马铃薯全生育期总灌水量");
        assertTrue(all.contains("194 m³/667m²"), "缺日光温室草莓灌溉定额");
        assertTrue(all.contains("20~25 m³/667m²"), "缺小麦单次灌水量");
        assertTrue(all.contains("氮肥基追比例 5:5"), "缺夏玉米氮肥基追比");
        assertTrue(all.contains("氮肥基追比例 4:6"), "缺小麦氮肥基追比");
    }

    /**
     * 药剂清单的**使用边界**必须随数据一起入库。
     *
     * <p>这批数据的出处是 GB/T 的**征求意见稿**，不是已发布标准；且同剂型常对应多家厂商的
     * 不同登记产品，用量与安全间隔期各不相同。若只入库数字而不带边界，模型会以
     * "有国家标准出处"的口吻给出可能过时或张冠李戴的用药方案——这比不答更危险。</p>
     */
    @Test
    void pesticideEntriesCarryTheirUsageBoundary() {
        String all = joinAllText();
        assertTrue(all.contains("不得据本清单直接配药"), "缺「不得直接配药」的硬边界");
        assertTrue(all.contains("按所购产品的标签执行") || all.contains("以**所购产品的现行标签**为准"),
                "缺「以产品标签为准」的指引");
        assertTrue(all.contains("现行农药登记"), "缺「核对现行登记」的要求");
    }

    /**
     * 化学防治药方**刻意不入库**。
     *
     * <p>项目原设计立场是"药剂信息未入库、不出具药方"（见设计文档 §23），
     * 理由是 ICAMA 登记库不能批量复制。DB41/T 1350—2016 的附录 A 有一张完整的
     * 「农药名称 / 使用浓度 / 安全间隔期」表，抄起来很方便——但它是 **2016 年**的，
     * 农药登记状态会变，照抄会让系统以"有出处的标准"为名给出可能已撤销的用药方案。
     * 这条断言锁住"不要顺手把它加进来"。</p>
     */
    @Test
    void chemicalControlDosagesAreDeliberatelyExcluded() {
        String all = joinAllText();
        assertTrue(!all.contains("倍液喷雾"), "化学防治药方不得入库：实测出现「倍液喷雾」");
        assertTrue(!all.contains("安全间隔期（天）"), "化学防治药方不得入库：实测出现药方表头");
    }

    /**
     * 每条摘要**必须能整条进到模型的证据块里**，不能被片段上限截断。
     *
     * <p>这是 2026-09-26 实测暴露的：{@code CitationFormatter} 的片段上限原为 160 字，
     * 而标准条款普遍长于它，于是模型看到的是"每次每亩浇水 1…"，只能如实回答"原文截断，给不出"。
     * 知识入库了却没进上下文，等于白灌。本断言把"摘要长度"与"片段上限"绑在一起，
     * 将来有人把上限调回去、或写一条超长的摘要，都会在这里失败。</p>
     */
    @Test
    void everyEntryFitsInOneEvidenceSnippet() {
        int limit = com.example.Ece.agent.rag.CitationFormatter.SNIPPET_LIMIT;
        for (KnowledgeSourceEntry entry : entries) {
            // 证据块给模型的是整块内容（含上下文头），故按块内容校验而非仅正文。
            for (KnowledgeChunk chunk : new KnowledgeChunker().chunk(
                    entry.getRecord().getSourceTable(), entry.getRecord().getSourceId(),
                    entry.getRecord().getCropType(), entry.getRecord().getDiseaseName(),
                    entry.getRecord().getFields())) {
                assertTrue(chunk.getContent().length() <= limit,
                        "摘要被截断，模型看不到后半段：" + entry.getRecord().getDiseaseName()
                                + " 共 " + chunk.getContent().length() + " 字，上限 " + limit);
            }
        }
    }

    private String joinAllText() {
        StringBuilder builder = new StringBuilder();
        for (KnowledgeSourceEntry entry : entries) {
            for (String text : entry.getRecord().getFields().values()) {
                builder.append(text).append('\n');
            }
        }
        return builder.toString();
    }

    private List<String> topics() {
        List<String> result = new ArrayList<String>();
        for (KnowledgeSourceEntry entry : entries) {
            result.add(entry.getRecord().getDiseaseName());
        }
        return result;
    }

    @Test
    void topicsAreUniqueWithinASource() {
        Set<String> codes = new HashSet<String>();
        for (KnowledgeSourceEntry entry : entries) {
            codes.add(entry.getSource().getSourceCode());
        }
        for (String code : codes) {
            Set<Long> ids = new HashSet<Long>();
            List<String> seen = new ArrayList<String>();
            for (KnowledgeSourceEntry entry : entries) {
                if (!code.equals(entry.getSource().getSourceCode())) {
                    continue;
                }
                assertTrue(ids.add(Long.valueOf(entry.getRecord().getSourceId())), "编号重复：" + code);
                assertTrue(seen.add(entry.getRecord().getDiseaseName()),
                        "同一来源内主题重复会让两个条目撞上切块器的唯一键：" + code);
            }
        }
        assertTrue(topics().size() > 10);
    }
}
