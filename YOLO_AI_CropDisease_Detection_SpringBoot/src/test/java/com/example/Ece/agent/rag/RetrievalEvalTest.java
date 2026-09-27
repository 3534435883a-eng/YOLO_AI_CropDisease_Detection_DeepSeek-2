package com.example.Ece.agent.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索评测（E-A）：在**真实病害知识库**（cropdisease.sql 的 disease 表，100 条）上测量检索质量。
 *
 * <p>三类任务：</p>
 * <ol>
 *   <li>症状前缀检索（40 题）：取每条症状前 60 字作为查询，期望命中该病名——考察基本召回；</li>
 *   <li>症状中段检索（20 题）：取症状中段 40 字，查询与原文不连续——考察鲁棒性；</li>
 *   <li>负样本拒答（10 题）：与病害无关的问题，应被 lowScore 判为不可靠——考察精确性。</li>
 *   <li>覆盖率分布：正/负样本的 IDF 加权查询词覆盖率均值，用于解释拒答判据的边界。</li>
 * </ol>
 *
 * <p>向量服务可达时使用"BM25 + 向量 + RRF"完整链路，不可达时自动降级为 BM25-only 并在报告中标注，
 * 绝不假装跑过向量检索。</p>
 */
class RetrievalEvalTest {

    private static final int PREFIX_QUESTIONS = 40;
    private static final int MIDDLE_QUESTIONS = 20;
    private static final String[] NEGATIVE_QUERIES = {
            "如何给番茄施肥", "今天天气怎么样", "帮我写一首诗", "拖拉机怎么保养",
            "玉米价格今天多少", "怎么做番茄炒蛋", "量子计算机的原理", "手机电池不耐用",
            "怎么申请农业补贴", "足球比赛结果"
    };

    /**
     * **难负样本**：看起来像农业问题、但知识库确实没有依据，因此应当被拒答。
     *
     * <p>为什么另立一组：上面那 10 条里有 9 条是完全离题的（写诗、足球、量子计算、手机电池），
     * 拒答它们几乎不费力气，于是"拒答准确率 100%"这个数字会让人**严重高估**系统的拒答能力。
     * 而 `docs/eval/developer_holdout.jsonl` 里两条应拒答问题实测 **2/2 被误接纳**——
     * 那才更接近真实误用场景。本组就是为度量这个落差而设。</p>
     *
     * <p><b>标签怎么来的（逐条可核验，不凭印象）</b>：全部取自知识库**实际不存在**的主题。
     * 前 16 条对应 `docs/vision-class-kb-mapping.md` §四列出的"无任何线索"类别，
     * 每一条都用脚本核验过知识库（`cropdisease.sql` + `curated-*.json`，共 108 条）中
     * **不存在同作物同名条目**。刻意**排除**了 §四 的 `小麦 Loose_Smut(松秕病)`：
     * 文档记载它与知识库"小麦散黑穗病"属"疑似对应但未获证据"，
     * 正确答案是"不能断言是同一种"（对冲），而非"无依据"（拒答），标签会模糊。
     * 后 2 条直接取自 `developer_holdout.jsonl` 中已标注 `should_refuse=true` 的题目。</p>
     *
     * <p>每行 {作物, 查询, 依据}。作物会一并传入检索器（真实调用会带作物上下文）。</p>
     */
    private static final String[][] HARD_NEGATIVE_QUERIES = {
            {"苹果", "苹果黑根腐病怎么防治？", "§四 apple/RootRot 无依据（库中仅有棉花黑根腐病）"},
            {"玉米", "玉米枯萎病用什么药？", "§四 corn/Blight 无依据"},
            {"玉米", "玉米灰叶斑病怎么治？", "§四 corn/Gray_Spot 无依据（库中仅有玉米灰斑病、番茄灰叶斑病）"},
            {"玉米", "玉米秋军虫幼虫怎么防治？", "§四 corn/FAW_Lv 无依据"},
            {"玉米", "玉米条斑病怎么识别？", "§四 corn/Streak 无依据"},
            {"玉米", "玉米黄秆虫怎么防治？", "§四 corn/Stem_Borer 无依据"},
            {"玉米", "玉米黄秆虫幼虫打什么药？", "§四 corn/StemBorer_Lv 无依据"},
            {"葡萄", "葡萄黑腐病怎么防治？", "§四 grape/Black_Rot 无依据"},
            {"葡萄", "葡萄木材腐烂病怎么治？", "§四 grape/Esca 无依据"},
            {"葡萄", "葡萄叶枯病怎么识别？", "§四 grape/Leaf_Blight 无依据"},
            {"水稻", "水稻细菌枯病怎么防治？", "§四 rice/Bact_L_Blight 无依据"},
            {"水稻", "水稻稻铁甲虫怎么防治？", "§四 rice/Hispa 无依据"},
            {"小麦", "小麦黑秆病怎么治？", "§四 wheat/Bacterial_Streak 无依据"},
            {"小麦", "小麦穗霉病怎么防治？", "§四 wheat/Head_Scab 无依据"},
            {"小麦", "小麦赤霉病用什么药？", "§四 wheat/Septoria_Blotch 无依据（库中为小麦斑枯病）"},
            {"小麦", "小麦茎锈病怎么防治？", "§四 wheat/Stem_Rust 无依据"},
            {"番茄", "番茄检出潜叶虫后应该使用什么药剂和剂量？", "developer_holdout.jsonl 标注 should_refuse=true"},
            {"番茄", "棚内湿度高但没有病害症状，是否需要改变灌溉策略？", "developer_holdout.jsonl 标注 should_refuse=true"},
    };

    /**
     * 口语化检索（非照抄）：种植户的真实问法，用词与语料原文不同。
     *
     * <p>前缀/中段题是原文切片，覆盖率必然接近 1.0，测不出拒答判据的上界；
     * 这组题用来回答"MIN_COVERAGE 会不会把正常提问也拒掉"。每行为 {作物, 期望病名, 查询}，
     * 期望病名为空表示只要求"不被拒答"。</p>
     */
    private static final String[][] COLLOQUIAL = {
            {"番茄", "番茄晚疫病", "棚里番茄下部叶片从叶尖开始出现暗绿色水浸状斑，湿度大的时候叶背有白霉"},
            {"番茄", "番茄叶霉病", "番茄叶子正面有淡黄色褪绿斑，翻过来叶背长褐色绒毛一样的霉"},
            {"番茄", "番茄早疫病", "番茄下面老叶上有一圈一圈同心轮纹的褐色病斑"},
            {"番茄", "番茄灰叶斑病", "番茄叶片上灰白色小圆斑，中间穿孔，果实倒没事"},
            {"番茄", "番茄根结线虫病", "番茄苗不长个，拔出来根上有一串小疙瘩"},
            {"番茄", "番茄蕨叶病毒病", "番茄顶上的叶子变细像蕨叶，卷着不长"},
            {"玉米", "玉米大斑病", "玉米叶子从底下开始长梭形大斑，灰褐色，一片片枯死"},
            {"玉米", "玉米螟", "玉米心叶被咬出一排排孔，穗子上有虫"},
            {"水稻", "稻瘟病", "水稻叶片有梭形病斑中间灰白边缘褐色，穗脖子发黑"},
            {"小麦", "小麦条锈病", "麦叶上有鲜黄色小点排成一条条线，摸一下手上有黄粉"},
            {"马铃薯", "马铃薯晚疫病", "土豆叶尖叶缘先烂，边上有一圈白霉"},
            {"葡萄", "葡萄霜霉病", "葡萄叶子背面长一层白霜，新梢弯着长"},
            {"苹果", "苹果褐斑病", "苹果树叶子上有褐色斑点，慢慢扩大成针芒状"},
            {"棉花", "棉花立枯病", "棉花苗茎基部褐色凹陷，绕一圈以后苗就倒了"},
            {"草莓", "草莓灰霉病", "草莓果子软腐，上面长灰毛"},
            {"番茄", null, "叶子上有褐色轮纹斑怎么办"}
    };
    @Test
    void reportRetrievalMetrics() throws Exception {
        List<JSONObject> corpus = loadCorpus();
        assertTrue(corpus.size() >= 50, "语料过少，评测没有意义：" + corpus.size());

        KnowledgeChunker chunker = new KnowledgeChunker();
        List<KnowledgeChunk> chunks = new ArrayList<KnowledgeChunk>();
        List<JSONObject> usable = new ArrayList<JSONObject>();
        for (JSONObject record : corpus) {
            Map<KnowledgeChunk.FieldType, String> fields = new LinkedHashMap<KnowledgeChunk.FieldType, String>();
            put(fields, KnowledgeChunk.FieldType.SYMPTOM, record.getString("symptom"));
            put(fields, KnowledgeChunk.FieldType.CAUSE, record.getString("cause"));
            put(fields, KnowledgeChunk.FieldType.CONTROL, record.getString("control"));
            List<KnowledgeChunk> produced = chunker.chunk(record.getLongValue("id"), record.getString("crop"),
                    record.getString("name"), fields);
            if (!produced.isEmpty()) {
                chunks.addAll(produced);
                usable.add(record);
            }
        }

        EmbeddingProbe probe = probeEmbedding();
        KnowledgeRetriever retriever = new KnowledgeRetriever(probe.client, new KnowledgeEntityLexicon());
        retriever.rebuild(chunks);
        // 诊断用：同一批切块建独立 BM25 索引，逐题打印命中词，用于解释拒答判据。
        Bm25Index diagIndex = new Bm25Index();
        diagIndex.rebuild(chunks);
        RetrievalResult degradedProbe = retriever.retrieve("番茄叶片褐色轮斑", "番茄", 3);
        String mode = degradedProbe.isDegraded() ? "BM25-only（向量服务不可达，已降级）" : "BM25 + 向量 + RRF";

        System.out.println("=== 检索评测 E-A（真实语料 " + corpus.size() + " 条 / 切块 " + chunks.size()
                + " 块 / 模式：" + mode + "）===");
        if (probe.note != null) {
            System.out.println("    向量探测：" + probe.note);
        }

        int prefixTop1 = 0;
        int prefixTop3 = 0;
        List<String> positiveMisses = new ArrayList<String>();
        double positiveCoverageSum = 0.0;
        int positiveCoverageCount = 0;
        for (int i = 0; i < Math.min(PREFIX_QUESTIONS, usable.size()); i++) {
            JSONObject record = usable.get(i);
            String query = cut(record.getString("symptom"), 60);
            RetrievalResult outcome = retriever.retrieve(query, record.getString("crop"), 3);
            List<ScoredChunk> hits = outcome.getItems();
            positiveCoverageSum += outcome.getQueryCoverage();
            positiveCoverageCount++;
            boolean top1 = isHit(hits, 1, record.getString("name"));
            if (top1) {
                prefixTop1++;
            } else {
                positiveMisses.add(record.getString("crop") + "/" + record.getString("name")
                        + " → 首条:" + (hits.isEmpty() ? "空" : hits.get(0).getChunk().getDiseaseName())
                        + "（覆盖率 " + String.format("%.2f", outcome.getQueryCoverage()) + "）");
            }
            if (isHit(hits, 3, record.getString("name"))) {
                prefixTop3++;
            }
        }

        int middleTop3 = 0;
        int middleCount = 0;
        for (int i = 0; i < Math.min(MIDDLE_QUESTIONS, usable.size()); i++) {
            JSONObject record = usable.get(i);
            String symptom = record.getString("symptom");
            if (symptom.length() < 120) {
                continue;
            }
            middleCount++;
            String query = symptom.substring(60, Math.min(100, symptom.length()));
            List<ScoredChunk> hits = retriever.retrieve(query, record.getString("crop"), 3).getItems();
            if (isHit(hits, 3, record.getString("name"))) {
                middleTop3++;
            }
        }

        // 病名检索：直接用"作物+病名"提问（真实用户最常见的问法）。
        // 若知识块内容里不含作物名与病名，这一步会整体失配——这是设计问题，不是调参问题。
        int nameTop1 = 0;
        int nameTop3 = 0;
        int nameCount = 0;
        int nameNoHit = 0;
        int nameAnswered = 0;
        List<String> nameMisses = new ArrayList<String>();
        for (int i = 0; i < Math.min(PREFIX_QUESTIONS, usable.size()); i++) {
            JSONObject record = usable.get(i);
            String query = record.getString("crop") + record.getString("name");
            nameCount++;
            RetrievalResult outcome = retriever.retrieve(query, record.getString("crop"), 3);
            if (outcome.getBm25HitCount() == 0) {
                nameNoHit++;
            }
            if (!retriever.isLowScore(outcome)) {
                nameAnswered++;
            }
            if (isHit(outcome.getItems(), 1, record.getString("name"))) {
                nameTop1++;
            } else {
                nameMisses.add(query + " → " + describe(outcome.getItems()));
            }
            if (isHit(outcome.getItems(), 3, record.getString("name"))) {
                nameTop3++;
            }
        }

        int colloquialTop1 = 0;
        int colloquialTop3 = 0;
        List<String> aliasMisses = new ArrayList<String>();
        List<String> aliasTop1Misses = new ArrayList<String>();
        int aliasTop1 = 0;
        int aliasTop3 = 0;
        int aliasCount = 0;
        int colloquialAnswered = 0;
        double colloquialMinCoverage = 1.0;
        String colloquialMinQuery = "";
        List<String> colloquialMisses = new ArrayList<String>();
        for (String[] row : COLLOQUIAL) {
            RetrievalResult outcome = retriever.retrieve(row[2], row[0], 3);
            if (outcome.getQueryCoverage() < colloquialMinCoverage) {
                colloquialMinCoverage = outcome.getQueryCoverage();
                colloquialMinQuery = row[2];
            }
            if (!retriever.isLowScore(outcome)) {
                colloquialAnswered++;
            }
            if (row[1] == null) {
                continue;
            }
            if (isHit(outcome.getItems(), 1, row[1])) {
                colloquialTop1++;
            }
            if (isHit(outcome.getItems(), 3, row[1])) {
                colloquialTop3++;
            } else {
                colloquialMisses.add(row[0] + "/" + row[1] + "「" + row[2] + "」→ "
                        + describe(outcome.getItems()) + "（覆盖率 "
                        + String.format("%.2f", outcome.getQueryCoverage()) + "）");
            }
        }
        int colloquialExpected = 0;
        for (String[] row : COLLOQUIAL) {
            if (row[1] != null) {
                colloquialExpected++;
            }
        }

        int refused = 0;
        List<String> negativeMisses = new ArrayList<String>();
        double negativeCoverageSum = 0.0;
        for (String query : NEGATIVE_QUERIES) {
            RetrievalResult outcome = retriever.retrieve(query, null, 3);
            negativeCoverageSum += outcome.getQueryCoverage();
            if (retriever.isLowScore(outcome)) {
                refused++;
            } else {
                negativeMisses.add("「" + query + "」覆盖率 " + String.format("%.2f", outcome.getQueryCoverage())
                        + " / 首条 " + (outcome.getItems().isEmpty() ? "空"
                        : outcome.getItems().get(0).getChunk().getDiseaseName()));
            }
        }

        double prefixTop1Rate = rate(prefixTop1, Math.min(PREFIX_QUESTIONS, usable.size()));
        double prefixTop3Rate = rate(prefixTop3, Math.min(PREFIX_QUESTIONS, usable.size()));
        double middleTop3Rate = rate(middleTop3, Math.max(1, middleCount));
        double refuseRate = rate(refused, NEGATIVE_QUERIES.length);

        System.out.printf("  症状前缀检索（%d 题）  Top-1 命中率 %.1f%%   Top-3 命中率 %.1f%%%n",
                Math.min(PREFIX_QUESTIONS, usable.size()), prefixTop1Rate * 100, prefixTop3Rate * 100);
        System.out.printf("  症状中段检索（%d 题）  Top-3 命中率 %.1f%%%n", middleCount, middleTop3Rate * 100);
        System.out.printf("  负样本拒答（%d 题）    拒答准确率 %.1f%%%n", NEGATIVE_QUERIES.length, refuseRate * 100);
        System.out.printf("  查询词覆盖率（IDF 加权）  正样本均值 %.2f   负样本均值 %.2f   E2 阈值 MIN_COVERAGE=%.2f%n",
                positiveCoverageCount == 0 ? 0.0 : positiveCoverageSum / positiveCoverageCount,
                negativeCoverageSum / NEGATIVE_QUERIES.length, KnowledgeRetriever.MIN_COVERAGE);
        for (String miss : positiveMisses) {
            System.out.println("  [Top-1 未命中] " + miss);
        }
        for (String miss : negativeMisses) {
            System.out.println("  [漏判负样本] " + miss);
        }
        System.out.println();

        // 难负样本诊断：**刻意不设断言**。
        // 断言失败会弄脏构建，断言通过则是说谎——该组当前的实测表现正是需要被看见的缺口
        // （与 developer_holdout 的 2/2 误接纳同型）。实测值记录在 docs/eval/README.md 的「当前发现」，
        // 与项目既有的记录方式保持一致：先如实记数，再由人决定怎么改判据。
        int hardRefused = 0;
        List<String> hardMisses = new ArrayList<String>();
        for (String[] row : HARD_NEGATIVE_QUERIES) {
            RetrievalResult outcome = retriever.retrieve(row[1], row[0], 3);
            if (retriever.isLowScore(outcome)) {
                hardRefused++;
            } else {
                hardMisses.add(String.format("「%s」(%s) 覆盖率 %.2f / 首条 %s / 依据：%s",
                        row[1], row[0], outcome.getQueryCoverage(),
                        outcome.getItems().isEmpty() ? "空"
                                : outcome.getItems().get(0).getChunk().getDiseaseName(),
                        row[2]));
            }
        }
        double hardRefuseRate = rate(hardRefused, HARD_NEGATIVE_QUERIES.length);
        System.out.printf("  难负样本拒答（%d 题，农业外表但库中无依据）  拒答准确率 %.1f%%  ← 无断言，见代码注释%n",
                HARD_NEGATIVE_QUERIES.length, hardRefuseRate * 100);
        System.out.printf("    对照：离题负样本 %.1f%%（%d 题）—— 两者落差即「拒答能力被高估」的幅度%n",
                refuseRate * 100, NEGATIVE_QUERIES.length);
        for (String miss : hardMisses) {
            System.out.println("  [难负样本未拒答] " + miss);
        }
        // ---- 判据信号分布（**纯测量，不改判据**）----
        // 目的：看清**已有信号**能不能把"有依据"与"农业外表但无依据"分开，再谈阈值。
        // 记录一次失败的设计以免重犯：曾试图新增"主题词覆盖率"（查询按 IDF 取前 1/3 的词），
        // 实测正样本与负样本**都**出现 0.00——因为长口语问句里 IDF 最高的正是口语词
        //（"一圈一圈""小疙瘩""不长个"），它们在库里同样不存在。两类 0.00 的含义不同，
        // 词频信号分不开，遂放弃该设计。真正要量的是**共现词数**：正样本靠症状词成串命中，
        // 负样本只能靠"作物名 + 防治"这类通用词凑到 2。
        String[] groupNames = {"症状前缀", "口语化", "别名"};
        List<int[]> groupMatched = new ArrayList<int[]>();
        for (int g = 0; g < 3; g++) {
            List<Integer> matched = new ArrayList<Integer>();
            if (g == 0) {
                for (int i = 0; i < Math.min(PREFIX_QUESTIONS, usable.size()); i++) {
                    JSONObject record = usable.get(i);
                    matched.add(retriever.retrieve(cut(record.getString("symptom"), 60),
                            record.getString("crop"), 3).getMaxChunkMatchedTerms());
                }
            } else if (g == 1) {
                for (String[] row : COLLOQUIAL) {
                    if (row[1] != null) {
                        matched.add(retriever.retrieve(row[2], row[0], 3).getMaxChunkMatchedTerms());
                    }
                }
            } else {
                String[][] aliasProbe = {
                        {"土豆叶尖叶缘先烂，边上有一圈白霉", null},
                        {"西红柿叶片有同心轮纹的褐色病斑", null},
                        {"苞米叶子上长梭形大斑，边缘褐色中间灰色", null},
                        {"叶尖叶缘先烂，边上有一圈白霉", "土豆"},
                        {"叶片有同心轮纹的褐色病斑", "西红柿"}
                };
                for (String[] row : aliasProbe) {
                    matched.add(retriever.retrieve(row[0], row[1], 3).getMaxChunkMatchedTerms());
                }
            }
            int min = Integer.MAX_VALUE;
            for (int v : matched) {
                min = Math.min(min, v);
            }
            groupMatched.add(new int[]{min, matched.size()});
        }
        List<Integer> negativeMatched = new ArrayList<Integer>();
        for (String[] row : HARD_NEGATIVE_QUERIES) {
            negativeMatched.add(retriever.retrieve(row[1], row[0], 3).getMaxChunkMatchedTerms());
        }
        for (String query : NEGATIVE_QUERIES) {
            negativeMatched.add(retriever.retrieve(query, null, 3).getMaxChunkMatchedTerms());
        }
        int negativeMax = 0;
        for (int v : negativeMatched) {
            negativeMax = Math.max(negativeMax, v);
        }
        StringBuilder line = new StringBuilder();
        int positiveFloor = Integer.MAX_VALUE;
        for (int g = 0; g < 3; g++) {
            line.append(String.format("%s %d 题 最低 %d；", groupNames[g], groupMatched.get(g)[1],
                    groupMatched.get(g)[0]));
            positiveFloor = Math.min(positiveFloor, groupMatched.get(g)[0]);
        }
        System.out.println("  共现词数（Top 块内命中的查询词个数，未归一）：");
        System.out.println("    正样本：" + line);
        System.out.printf("    负样本：难负 %d + 离题 %d 题，**最高 %d**%n",
                HARD_NEGATIVE_QUERIES.length, NEGATIVE_QUERIES.length, negativeMax);
        // 原始向量余弦（未过 RRF）：测"语义相近度能否区分有依据与无依据"
        double[] posSimMin = {1.0, 1.0, 1.0};
        for (int g = 0; g < 3; g++) {
            if (g == 0) {
                for (int i = 0; i < Math.min(PREFIX_QUESTIONS, usable.size()); i++) {
                    JSONObject record = usable.get(i);
                    double sim = retriever.retrieve(cut(record.getString("symptom"), 60),
                            record.getString("crop"), 3).getTopVectorSimilarity();
                    if (sim > 0.0) { posSimMin[g] = Math.min(posSimMin[g], sim); }
                }
            } else if (g == 1) {
                for (String[] row : COLLOQUIAL) {
                    if (row[1] != null) {
                        double sim = retriever.retrieve(row[2], row[0], 3).getTopVectorSimilarity();
                        if (sim > 0.0) { posSimMin[g] = Math.min(posSimMin[g], sim); }
                    }
                }
            } else {
                String[][] aliasProbe = {
                        {"土豆叶尖叶缘先烂，边上有一圈白霉", null},
                        {"西红柿叶片有同心轮纹的褐色病斑", null},
                        {"苞米叶子上长梭形大斑，边缘褐色中间灰色", null},
                        {"叶尖叶缘先烂，边上有一圈白霉", "土豆"},
                        {"叶片有同心轮纹的褐色病斑", "西红柿"}
                };
                for (String[] row : aliasProbe) {
                    double sim = retriever.retrieve(row[0], row[1], 3).getTopVectorSimilarity();
                    if (sim > 0.0) { posSimMin[g] = Math.min(posSimMin[g], sim); }
                }
            }
        }
        double negSimMax = 0.0;
        for (String[] row : HARD_NEGATIVE_QUERIES) {
            negSimMax = Math.max(negSimMax, retriever.retrieve(row[1], row[0], 3).getTopVectorSimilarity());
        }
        for (String query : NEGATIVE_QUERIES) {
            negSimMax = Math.max(negSimMax, retriever.retrieve(query, null, 3).getTopVectorSimilarity());
        }
        double posSimFloor = Math.min(Math.min(posSimMin[0], posSimMin[1]), posSimMin[2]);
        System.out.printf("  原始向量余弦（Top-1 块）：症状前缀 最低 %.3f；口语化 最低 %.3f；别名 最低 %.3f%n",
                posSimMin[0], posSimMin[1], posSimMin[2]);
        System.out.printf("  原始向量余弦（Top-1 块）：负样本 最高 %.3f%n", negSimMax);
        System.out.printf("    判读：正样本最低 %.3f 与负样本最高 %.3f %s%n", posSimFloor, negSimMax,
                posSimFloor > negSimMax ? "**不重叠**，存在可行阈值区间"
                        : "**重叠**，单靠语义相似度也不够");
        System.out.println();

        System.out.printf("    判读：正样本最低 %d 与负样本最高 %d %s；当前 MIN_CHUNK_TERMS=%d%n",
                positiveFloor, negativeMax,
                positiveFloor > negativeMax ? "**不重叠**，存在可行阈值区间 ("
                        + negativeMax + ", " + positiveFloor + "]" : "**重叠**，单靠本信号不够",
                KnowledgeRetriever.MIN_CHUNK_TERMS);
        System.out.println();

        // ---- 「问题点名的病/虫不在语料中」信号（**纯测量，先不接判据**）----
        // 前三个被撤除的信号都是统计量（词频、覆盖率、余弦），它们问"像不像"，
        // 因而在口语化长问句上与负样本重叠。本信号问的是**存在问题**：
        // 问题里那个病/虫，本作物语料里到底有没有。先量误标率与命中率，再决定是否接判据。
        int unknownPositiveHitsCount = 0;
        int unknownPositiveTotal = 0;
        List<String> unknownPositiveSamples = new ArrayList<String>();
        for (int i = 0; i < Math.min(PREFIX_QUESTIONS, usable.size()); i++) {
            JSONObject record = usable.get(i);
            RetrievalResult o = retriever.retrieve(cut(record.getString("symptom"), 60),
                    record.getString("crop"), 3);
            unknownPositiveTotal++;
            if (o.getUnknownEntityToken() != null) {
                unknownPositiveHitsCount++;
                unknownPositiveSamples.add("症状前缀/" + record.getString("crop")
                        + "「" + o.getUnknownEntityToken() + "」");
            }
        }
        for (String[] row : COLLOQUIAL) {
            if (row[1] == null) {
                continue;
            }
            RetrievalResult o = retriever.retrieve(row[2], row[0], 3);
            unknownPositiveTotal++;
            if (o.getUnknownEntityToken() != null) {
                unknownPositiveHitsCount++;
                unknownPositiveSamples.add("口语/" + row[0] + "「" + o.getUnknownEntityToken() + "」");
            }
        }
        int unknownHardHit = 0;
        List<String> unknownHardMisses = new ArrayList<String>();
        for (int rowIndex = 0; rowIndex < HARD_NEGATIVE_QUERIES.length; rowIndex++) {
            String[] row = HARD_NEGATIVE_QUERIES[rowIndex];
            RetrievalResult o = retriever.retrieve(row[1], row[0], 3);
            if (o.getUnknownEntityToken() != null) {
                unknownHardHit++;
            } else {
                // 只报序号与作物：问句是中文，经 GBK 控制台会乱码，序号足以定位
                unknownHardMisses.add("#" + (rowIndex + 1) + "(" + row[0] + ")");
            }
        }
        // 输出刻意全用 ASCII：本机控制台是 GBK，中文诊断行会被转码毁掉，
        // 而这几个数字正是要读的东西。病名本身另起一行原样打印，读不到也不影响判定。
        System.out.printf("[UNKNOWN-ENTITY] positives_mislabeled=%d/%d%n",
                unknownPositiveHitsCount, unknownPositiveTotal);
        for (String sample : unknownPositiveSamples) {
            // 把非 ASCII 转成反斜杠-u 加四位十六进制：控制台是 GBK，中文直出会乱码，
            // 转义后换任何编码都能读回。（注释里也不能出现反斜杠-u 序列——
            // Java 的 Unicode 转义预处理会连注释一起扫，编译期直接报"非法的 Unicode 转义"。）
            StringBuilder escaped = new StringBuilder();
            for (int ci = 0; ci < sample.length(); ci++) {
                char ch = sample.charAt(ci);
                if (ch < 0x80) {
                    escaped.append(ch);
                } else {
                    escaped.append(String.format("\\u%04x", (int) ch));
                }
            }
            System.out.println("[UNKNOWN-ENTITY]   false-positive: " + escaped);
        }
        System.out.printf("[UNKNOWN-ENTITY] hard_negatives_flagged=%d/%d%n",
                unknownHardHit, HARD_NEGATIVE_QUERIES.length);
        for (String miss : unknownHardMisses) {
            System.out.println("[UNKNOWN-ENTITY]   missed: " + miss);
        }
        System.out.println();

        // ---- 成本/行情类知识是否真的"可检索" ----
        // 赛题写的领域是"生产、营销、管理和服务"，而此前库里的非病害知识只有农事与水肥，
        // 成本与行情一个类别都没有。2026-09-27 加了 INPUT_COST 类别与一份官方农资价格来源后，
        // 必须验证**它真的能被问到**——入库了却检索不到或仍被判低分，等于没加。
        // 实测这几句的口语化程度不同，结果也不同，故逐句打印（输出保持 ASCII，见上文编码说明）。
        //
        // **必须在"组合语料"上量**：本评测原本只索引 {@code /corpus/disease-corpus.json}
        // （legacy disease 表 100 条 → 338 块），而生产库是 445 块——**标准/论文/药剂/农资价格
        // 这些非病害知识从来不在本评测的索引里**。也就是说，本轮之前所有"加了标准但指标没变"
        // 的观察，都不能说明那些知识可检索或不可检索：它们根本不在被测集合内。
        // 这里临时把 CitedKnowledgeReader 的条目一并切块，才能量到真实可达性。
        List<KnowledgeChunk> augmented = new ArrayList<KnowledgeChunk>(chunks);
        for (KnowledgeSourceEntry entry : new CitedKnowledgeReader().readAll()) {
            augmented.addAll(chunker.chunk(entry.getRecord().getSourceTable(), entry.getRecord().getSourceId(),
                    entry.getRecord().getCropType(), entry.getRecord().getDiseaseName(),
                    entry.getRecord().getFields()));
        }
        KnowledgeRetriever wide = new KnowledgeRetriever(probe.client, new KnowledgeEntityLexicon());
        wide.rebuild(augmented);
        System.out.println("[COST-RETRIEVAL] legacyChunks=" + chunks.size()
                + " augmentedChunks=" + augmented.size());
        String[] costQueries = {
                "复合肥什么价格", "现在化肥什么价", "尿素多少钱一吨", "化肥价格走势"
        };
        int costTop5 = 0;
        int costAnswerable = 0;
        for (int i = 0; i < costQueries.length; i++) {
            RetrievalResult r = wide.retrieve(costQueries[i], "番茄", 5);
            String top = r.getItems().isEmpty()
                    ? "none" : r.getItems().get(0).getChunk().getFieldType().name();
            boolean inputCostInTop5 = false;
            for (ScoredChunk item : r.getItems()) {
                if (item.getChunk().getFieldType() == KnowledgeChunk.FieldType.INPUT_COST) {
                    inputCostInTop5 = true;
                    break;
                }
            }
            System.out.printf("[COST-RETRIEVAL] q#%d lowScore=%s matched=%d coverage=%.2f top=%s inputCostInTop5=%s%n",
                    i, wide.isLowScore(r), r.getMaxChunkMatchedTerms(), r.getQueryCoverage(),
                    top, inputCostInTop5);
            if (inputCostInTop5) {
                costTop5++;
            }
            if (!wide.isLowScore(r)) {
                costAnswerable++;
            }
        }
        System.out.println();
        assertTrue(costTop5 == costQueries.length,
                "成本/行情条目应稳定进入 Top-5，实测 " + costTop5 + "/" + costQueries.length);
        // 「尿素多少钱一吨」实测 matched=1 → 判低分。这是**已知边界**而非回归：
        // 共现词数阈值 MIN_CHUNK_TERMS=2 对 6 字以内的短问句偏严，与口语化组 93.8% 未误拒同源。
        assertTrue(costAnswerable >= 3,
                "成本类问句可作答数过低，实测 " + costAnswerable + "/" + costQueries.length);

        // 别名专项：用"农户口语别名"代替规范作物名提问（如把 马铃薯 说成 土豆），
        // 考察查询归一化是否能让检索不受别名影响。
        // 别名专项（只测**真正会踩到别名问题**的两种场景）：
        //   A. 不给作物上下文、问题里用别名 → 只能靠查询归一化（块头部"作物：马铃薯"才有机会被匹配）
        //   B. 作物字段直接传别名 → 考察作物过滤是否被归一化
        //       过滤器是 chunkCrop.contains(cropType)，"马铃薯".contains("土豆") 为假，别名未归一化会一条都召不回。
        // 注意：若给别名问题**同时传规范作物名**，过滤会替我们完成匹配，测出来是假象（首版就犯了这个错）。
        String[][] aliasCases = {
                {"土豆叶尖叶缘先烂，边上有一圈白霉", null, "马铃薯晚疫病", "A 无作物上下文+别名"},
                {"西红柿叶片有同心轮纹的褐色病斑", null, "番茄早疫病", "A 无作物上下文+别名"},
                {"苞米叶子上长梭形大斑，边缘褐色中间灰色", null, "玉米大斑病", "A 无作物上下文+别名"},
                {"叶尖叶缘先烂，边上有一圈白霉", "土豆", "马铃薯晚疫病", "B 作物字段传别名"},
                {"叶片有同心轮纹的褐色病斑", "西红柿", "番茄早疫病", "B 作物字段传别名"}
        };
        for (String[] aliasCase : aliasCases) {
            aliasCount++;
            RetrievalResult outcome = retriever.retrieve(aliasCase[0], aliasCase[1], 3);
            if (outcome.getItems().isEmpty()) {
                aliasMisses.add(aliasCase[3] + "「" + aliasCase[0] + "」(crop=" + aliasCase[1] + ") → **零召回**");
                continue;
            }
            if (isHit(outcome.getItems(), 1, aliasCase[2])) {
                aliasTop1++;
            } else {
                aliasTop1Misses.add(aliasCase[3] + "「" + aliasCase[0] + "」首条 "
                        + outcome.getItems().get(0).getChunk().getDiseaseName());
            }
            if (isHit(outcome.getItems(), 3, aliasCase[2])) {
                aliasTop3++;
            } else {
                aliasMisses.add(aliasCase[3] + "「" + aliasCase[0] + "」(crop=" + aliasCase[1] + ") → "
                        + describe(outcome.getItems()) + "（覆盖率 "
                        + String.format("%.2f", outcome.getQueryCoverage()) + "）");
            }
        }

        double nameTop1Rate = rate(nameTop1, Math.max(1, nameCount));
        double nameAnsweredRate = rate(nameAnswered, Math.max(1, nameCount));
        double nameTop3Rate = rate(nameTop3, Math.max(1, nameCount));
        System.out.printf("  病名直检（%d 题）      Top-1 %.1f%%   Top-3 %.1f%%   未被误拒 %.1f%%   零命中 %d 题%n",
                nameCount, nameTop1Rate * 100, nameTop3Rate * 100, nameAnsweredRate * 100, nameNoHit);
        for (String miss : nameMisses.subList(0, Math.min(5, nameMisses.size()))) {
            System.out.println("  [病名未命中] " + miss);
        }
        double colloquialTop1Rate = rate(colloquialTop1, Math.max(1, colloquialExpected));
        double aliasTop1Rate = rate(aliasTop1, Math.max(1, aliasCount));
        double aliasTop3Rate = rate(aliasTop3, Math.max(1, aliasCount));
        double colloquialTop3Rate = rate(colloquialTop3, Math.max(1, colloquialExpected));
        double colloquialAnsweredRate = rate(colloquialAnswered, COLLOQUIAL.length);
        System.out.printf("  口语化检索（%d 题）    Top-1 %.1f%%   Top-3 %.1f%%   未误拒 %.1f%%（%d/%d）%n",
                COLLOQUIAL.length, colloquialTop1Rate * 100, colloquialTop3Rate * 100,
                colloquialAnsweredRate * 100, colloquialAnswered, COLLOQUIAL.length);
        System.out.printf("  别名提问（%d 题）      Top-1 %.1f%%   Top-3 %.1f%%%n",
                aliasCount, aliasTop1Rate * 100, aliasTop3Rate * 100);
        for (String miss : aliasMisses) {
            System.out.println("  [别名未命中 Top-3] " + miss);
        }
        for (String miss : aliasTop1Misses) {
            System.out.println("  [别名未命中 Top-1] " + miss);
        }
        System.out.printf("  口语题最低覆盖率 %.2f（%s）%n", colloquialMinCoverage, colloquialMinQuery);
        System.out.println("  --- 拒答判据逐题诊断（覆盖率 / 融合分 / 命中词 / 查询词数）---");
        for (String query : NEGATIVE_QUERIES) {
            RetrievalResult outcome = retriever.retrieve(query, null, 3);
            System.out.printf("    [负] %s 语料%.2f 块%.2f 共现%d/%d 分%.4f %s%n",
                    retriever.isLowScore(outcome) ? "拒" : "答", outcome.getQueryCoverage(),
                    outcome.getMaxChunkCoverage(), outcome.getMaxChunkMatchedTerms(),
                    diagIndex.queryTermCount(query), outcome.getTopScore(), diagIndex.matchedTerms(query));
        }
        for (String[] row : COLLOQUIAL) {
            RetrievalResult outcome = retriever.retrieve(row[2], row[0], 3);
            System.out.printf("    [口] %s 语料%.2f 块%.2f 共现%d/%d 分%.4f %s%n",
                    retriever.isLowScore(outcome) ? "误拒" : "答", outcome.getQueryCoverage(),
                    outcome.getMaxChunkCoverage(), outcome.getMaxChunkMatchedTerms(),
                    diagIndex.queryTermCount(row[2]), outcome.getTopScore(), diagIndex.matchedTerms(row[2]));
        }
        for (String miss : colloquialMisses) {
            System.out.println("  [口语题未命中] " + miss);
        }
        System.out.println();
        // 阈值取实测值的下沿作为回归护栏（实测 97.5/100/100/90，2026-09-23 全链路）：
        // 命中率明显下降即说明检索链路被破坏，而不是"跑通就算过"。
        assertTrue(prefixTop3Rate >= 0.90, "症状前缀 Top-3 命中率过低：" + prefixTop3Rate);
        assertTrue(prefixTop1Rate >= 0.85, "症状前缀 Top-1 命中率过低：" + prefixTop1Rate);
        assertTrue(middleTop3Rate >= 0.80, "症状中段 Top-3 命中率过低：" + middleTop3Rate);
        assertTrue(refuseRate >= 0.90, "负样本拒答率过低：" + refuseRate);
        // 病名提问是最自然的问法，必须既检得到、也不被拒答判据误杀。
        assertTrue(nameTop1Rate >= 0.90, "病名直检 Top-1 命中率过低：" + nameTop1Rate);
        assertTrue(nameAnsweredRate >= 0.90, "病名提问被拒答判据误杀：" + nameAnsweredRate);
        // 口语题护栏：既要检得到，也不能被拒答判据误杀。误拒率上升说明判据过激，
        // 必须先改判据而不是放宽断言（MIN_COVERAGE=0.30 的单一比值判据就曾误拒 18.8%）。
        assertTrue(colloquialTop3Rate >= 0.80, "口语化提问 Top-3 命中率过低：" + colloquialTop3Rate);
        assertTrue(colloquialAnsweredRate >= 0.90,
                "正常提问被拒答判据误杀（未误拒率 " + colloquialAnsweredRate + "，最低覆盖率 "
                        + colloquialMinCoverage + "：" + colloquialMinQuery + "）");
    }

    private String describe(List<ScoredChunk> hits) {
        if (hits.isEmpty()) {
            return "无结果";
        }
        StringBuilder builder = new StringBuilder("首条 ");
        for (int i = 0; i < Math.min(3, hits.size()); i++) {
            if (i > 0) {
                builder.append(" / ");
            }
            builder.append(hits.get(i).getChunk().getDiseaseName());
        }
        return builder.toString();
    }

    private void put(Map<KnowledgeChunk.FieldType, String> fields, KnowledgeChunk.FieldType type, String value) {
        if (value != null && !value.trim().isEmpty()) {
            fields.put(type, value);
        }
    }

    private boolean isHit(List<ScoredChunk> hits, int topN, String expectedName) {
        int limit = Math.min(topN, hits.size());
        for (int i = 0; i < limit; i++) {
            String name = hits.get(i).getChunk().getDiseaseName();
            if (expectedName != null && expectedName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private String cut(String text, int limit) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private double rate(int hit, int total) {
        return total <= 0 ? 0.0 : (double) hit / total;
    }

    private List<JSONObject> loadCorpus() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/corpus/disease-corpus.json");
        assertTrue(stream != null, "缺少评测语料资源 /corpus/disease-corpus.json");
        StringBuilder builder = new StringBuilder();
        InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) > 0) {
            builder.append(buffer, 0, read);
        }
        reader.close();
        JSONArray array = JSON.parseArray(builder.toString());
        List<JSONObject> records = new ArrayList<JSONObject>();
        for (int i = 0; i < array.size(); i++) {
            records.add(array.getJSONObject(i));
        }
        return records;
    }

    /** 探测向量服务：可达则用真实 HTTP 客户端，否则用必然降级的桩并在报告中如实标注。 */
    private EmbeddingProbe probeEmbedding() {
        HttpEmbeddingClient client = new HttpEmbeddingClient("http://127.0.0.1:5000", 8000);
        try {
            double[] probe = client.embed("番茄");
            return new EmbeddingProbe(client, "可达（维度 " + probe.length + "）");
        } catch (EmbeddingUnavailableException error) {
            EmbeddingClient fallback = new EmbeddingClient() {
                public double[] embed(String text) throws EmbeddingUnavailableException {
                    throw new EmbeddingUnavailableException("bm25-only");
                }
            };
            return new EmbeddingProbe(fallback, "不可达（" + error.getMessage() + "），本次仅评测关键词检索");
        }
    }

    private static final class EmbeddingProbe {
        private final EmbeddingClient client;
        private final String note;

        private EmbeddingProbe(EmbeddingClient client, String note) {
            this.client = client;
            this.note = note;
        }
    }
}
