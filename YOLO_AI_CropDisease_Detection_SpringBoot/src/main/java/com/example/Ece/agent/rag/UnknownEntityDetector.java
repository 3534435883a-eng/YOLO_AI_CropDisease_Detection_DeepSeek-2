package com.example.Ece.agent.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「问了一个语料里根本没有的病/虫」检测。
 *
 * <p><b>为什么需要它</b>：实测难负样本（农业外表、库里确实没有依据）拒答率只有 16.7%，
 * 而完全离题的样本是 100%。落差的原因是现有两个判据（共现词数、IDF 覆盖率）
 * 都只看"词有没有撞上"：{@code 玉米黄秆虫怎么防治} 能靠「玉米」+「防治」两个**通用词**
 * 凑够共现词数而被放行，可「黄秆虫」在语料里根本不存在。</p>
 *
 * <p><b>与三个失败信号的区别</b>：先前试过、并已撤除的三个信号（查询词 IDF 前 1/3 覆盖率、
 * 共现词数阈值、原始向量余弦）都是**统计信号**——它们问的是"像不像",
 * 因而在口语化的长问句上与负样本重叠，或方向相反。本检测问的是**存在问题**：
 * 问题里点名的那个病/虫，语料里**有没有**。这是个二值事实，不是相似度。</p>
 *
 * <p><b>为什么必须按作物限定</b>：语料里有「棉花黑根腐病」，而问题是
 * {@code 苹果黑根腐病}——同一个病名换一个作物就是没有依据。按作物限定后，
 * 「黑根腐病」在苹果下不存在，判为未知；在棉花下存在，判为已知。</p>
 *
 * <p><b>判定用"子串"而非"相等"</b>：病名在句中常带修饰或前缀
 *（{@code 与早疫病相似}、{@code 比早疫病轻}），截出来的候选串会含多余的字。
 * 因此只要**任一条目是候选串的子串，或候选串是任一条目的子串**，就算已知——
 * 这大幅降低了对正常提问的误杀。</p>
 */
public final class UnknownEntityDetector {

    private UnknownEntityDetector() {
    }

    /** 病/虫名的结尾字。名只有可能以这两个字结尾。 */
    private static final char DISEASE_END = '病';
    private static final char PEST_END = '虫';

    /**
     * 允许紧跟在病/虫名之后的字（白名单）。
     *
     * <p>用白名单而不是黑名单：黑名单必须穷举"哪些字说明病后面还在接词"
     *（斑、害、情、菌、原、株、叶、果、部、变、症…），漏一个就会把
     * {@code 褐色轮纹病斑} 截成「轮纹病」并误判为未知。白名单只需列出
     * "病名到此为止"的信号：疑问词、常见谓语、标点，以及虫态字
     *（{@code 秋军虫幼虫} 里「幼虫」跟在虫名后，名到「虫」为止）。</p>
     */
    private static final String TERMINATOR_AFTER =
            "怎如该用打防治识是吗呢幼若成卵蛹期后前时和与或及对在有为" +
            "。，、；：？！,.;:?!";

    /**
     * 不能作为病名开头字的字。
     *
     * <p>向前截取候选串时遇到这些字就停：它们说明前面是动词、量词或虚词，
     * 不属于病名。例如 {@code 发病的}、{@code 有虫}、{@code 看是不是病}。</p>
     */
    private static final String NOT_NAME_HEAD =
            "有无没见长大生发用打防治得了的是出到被咬吃看检查要该会可能" +
            "此本上下这一那些什么严轻小多少几也很较更都还又再" +
            // 虫态字：语料正文里大量出现「若虫」「成虫」「幼虫」「虫卵」，
            // 若不在此截断，它们会被当成病/虫名（实测使水稻、小麦各误标一条）。
            // 注意这不影响真的虫名——「秋军虫幼虫」在「秋军虫」的虫处就已截断。
            "若成幼卵蛹雌雄";

    /** 候选串最大长度。病名一般 2~6 字，取 6 已足够覆盖最长条目。 */
    private static final int MAX_TOKEN_LENGTH = 6;

    /**
     * 从问句里抽出"疑似病/虫名"的候选串。
     *
     * <p>按 CJK 连续段切分（标点、数字、字母都算分隔符），在每段内找以
     * 病/虫 结尾、且后面紧跟白名单字或段尾的位置，再向前截取。</p>
     *
     * @return 候选串（保序、去重）
     */
    public static List<String> candidateTokens(String query) {
        List<String> tokens = new ArrayList<String>();
        if (query == null || query.isEmpty()) {
            return tokens;
        }
        Set<String> seen = new LinkedHashSet<String>();
        int index = 0;
        while (index < query.length()) {
            if (!isCjk(query.charAt(index))) {
                index++;
                continue;
            }
            int runStart = index;
            while (index < query.length() && isCjk(query.charAt(index))) {
                index++;
            }
            collectFromRun(query, runStart, index, tokens, seen);
        }
        return tokens;
    }

    private static void collectFromRun(String query, int from, int to, List<String> tokens, Set<String> seen) {
        for (int position = from; position < to; position++) {
            char current = query.charAt(position);
            if (current != DISEASE_END && current != PEST_END) {
                continue;
            }
            if (position + 1 < to && TERMINATOR_AFTER.indexOf(query.charAt(position + 1)) < 0) {
                // 病/虫 后面还紧跟着构词的字（如「病斑」「害虫」），说明名不在此处结束
                continue;
            }
            int begin = position;
            while (begin > from
                    && position - begin + 1 < MAX_TOKEN_LENGTH
                    && NOT_NAME_HEAD.indexOf(query.charAt(begin - 1)) < 0) {
                begin--;
            }
            if (position - begin + 1 < 2) {
                // 只剩单个「病」「虫」，不构成名字
                continue;
            }
            String token = query.substring(begin, position + 1);
            if (seen.add(token)) {
                tokens.add(token);
            }
        }
    }

    /**
     * 候选串是否在给定的病名清单里"存在"。
     *
     * <p>双向子串判定：{@code 苹果黑根腐病} 与条目 {@code 苹果轮纹病} 互不为子串 → 未知；
     * 而 {@code 与早疫病} 含条目 {@code 早疫病} → 已知。</p>
     *
     * @param inventory 该作物下的病名清单（含去掉作物前缀的短名），可为 null
     */
    public static boolean isKnown(String token, Set<String> inventory) {
        if (token == null || token.isEmpty()) {
            return true;
        }
        if (inventory == null || inventory.isEmpty()) {
            // 清单为空只可能是语料为空；此时不因缺数据而判未知
            return true;
        }
        for (String entry : inventory) {
            if (entry == null || entry.length() < 2) {
                continue;
            }
            if (token.contains(entry) || entry.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回第一个"语料里没有"的候选串；全部存在时返回 null。
     *
     * @param inventory 该作物下的病名清单
     */
    public static String firstUnknownToken(String query, Set<String> inventory) {
        for (String token : candidateTokens(query)) {
            if (!isKnown(token, inventory)) {
                return token;
            }
        }
        return null;
    }

    /** 只认 CJK 统一表意文字（0x4E00~0x9FFF）：全角标点、数字、字母都算分隔符。 */
    private static boolean isCjk(char value) {
        return value >= 0x4E00 && value <= 0x9FFF;
    }
}
