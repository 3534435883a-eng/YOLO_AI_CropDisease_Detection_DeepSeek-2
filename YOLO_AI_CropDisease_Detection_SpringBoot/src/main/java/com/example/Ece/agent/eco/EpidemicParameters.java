package com.example.Ece.agent.eco;

/**
 * 番茄病虫害流行模型参数表（模型 2）。
 *
 * <p>下列常数取自三类文献族：</p>
 * <ul>
 *   <li><b>植物病害流行学（epidemiology）文献族</b>：以温度适宜度、湿度/叶面结露阈值、
 *       潜伏期与侵染速率描述气传病害流行的模型族。</li>
 *   <li><b>国内番茄植保文献族</b>：日光温室/塑料大棚番茄灰霉病、晚疫病、白粉病、叶霉病的
 *       发生条件与温湿度阈值相关文献族。</li>
 *   <li><b>害虫种群生态学文献族</b>：以 logistic 内禀增长率与环境容纳量描述害虫种群增长的模型族。</li>
 * </ul>
 *
 * <p><b>数值说明：</b>本文件中的取值均为“典型文献区间，待核对出处”。
 * 这里刻意不写出具体论文标题、年份或页码，以避免引用不确切的出处。</p>
 *
 * <p>所有数值均为模拟用参数，<b>不代表任何实测数据</b>。</p>
 */
public final class EpidemicParameters {

    private EpidemicParameters() {
        // 常量类，禁止实例化
    }

    /** 各常数的文献族来源说明（供上层文档/接口展示，避免在代码中臆造具体出处）。 */
    public static final String[] SOURCE_NOTES = new String[] {
            "灰霉病温度区间、湿度阈值与潜伏期：植物病害流行学文献族 + 国内番茄植保文献；典型文献区间，待核对出处。",
            "晚疫病温度区间、湿度阈值与潜伏期：植物病害流行学文献族 + 国内番茄植保文献；典型文献区间，待核对出处。",
            "白粉病“中等湿度偏好、高湿受抑”湿度带：植物病害流行学文献族 + 国内番茄植保文献；典型文献区间，待核对出处。",
            "叶霉病温度区间、湿度阈值与潜伏期：植物病害流行学文献族 + 国内番茄植保文献；典型文献区间，待核对出处。",
            "病害严重度增益与严重度—损失换算：植物病害流行学文献族；典型文献区间，待核对出处。",
            "害虫 logistic 内禀增长率与环境容纳量：害虫种群生态学文献族；典型文献区间，待核对出处。"
    };

    // ------------------------------------------------------------------
    // 通用换算与限幅
    // ------------------------------------------------------------------

    /** 温度适宜度衰减到 0 的半宽（摄氏度），以最适区间中点为基准。 */
    public static final double TEMPERATURE_HALF_WIDTH_C = 8.0;

    /** 潜伏期进程的温度适宜度下限（避免潜伏期无限延长）。 */
    public static final double MINIMUM_TEMPERATURE_FACTOR = 0.3;

    /** 严重度上限。 */
    public static final double MAX_SEVERITY = 100.0;

    /** 病原基数（接种体）上限。 */
    public static final double MAX_INOCULUM = 1.0;

    /** 潜伏进度上限。 */
    public static final double MAX_LATENT = 1.0;

    /** 初始病原基数（各病害相同）。 */
    public static final double INITIAL_INOCULUM = 0.05;

    /** 严重度总和换算为产量损失因子的分母。 */
    public static final double DAMAGE_SEVERITY_DIVISOR = 200.0;

    /** 病原基数随侵染速率增长的日增长系数（以 minutes/1440 为步长积分）。 */
    public static final double INOCULUM_BUILDUP_PER_DAY = 1.0;

    /** 冠层（叶面积指数）对叶面湿润时长的最低加成系数。 */
    public static final double CANOPY_FACTOR_FLOOR = 0.6;

    /** 冠层系数的可调幅度（冠层郁闭时叠加到 1.0）。 */
    public static final double CANOPY_FACTOR_RANGE = 0.4;

    // ------------------------------------------------------------------
    // 灰霉病 Botrytis cinerea
    // ------------------------------------------------------------------

    /** 灰霉病最适温度下限（摄氏度）。来源：植物病害流行学 + 国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double BOTRYTIS_OPTIMAL_LOW_C = 15.0;

    /** 灰霉病最适温度上限（摄氏度）。来源：植物病害流行学 + 国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double BOTRYTIS_OPTIMAL_HIGH_C = 22.0;

    /**
     * 灰霉病叶面湿润代理阈值（%）：本简化模型以空气相对湿度代理叶面湿润时长，
     * 低于该值视为叶面未湿润、不侵染。来源：国内番茄植保文献；典型文献区间，待核对出处。
     */
    public static final double BOTRYTIS_LEAF_WETNESS_PROXY_THRESHOLD_PCT = 90.0;

    /** 灰霉病侵染所需空气相对湿度阈值（%），与叶面湿润代理阈值同值。来源：国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double BOTRYTIS_HUMIDITY_THRESHOLD_PCT = BOTRYTIS_LEAF_WETNESS_PROXY_THRESHOLD_PCT;

    /** 灰霉病潜伏期（分钟）。来源：植物病害流行学文献族；典型文献区间，待核对出处。 */
    public static final double BOTRYTIS_LATENT_PERIOD_MINUTES = 720.0;

    /** 灰霉病单次侵染事件的严重度增益（%）。来源：植物病害流行学文献族；典型文献区间，待核对出处。 */
    public static final double BOTRYTIS_SEVERITY_GAIN = 6.0;

    // ------------------------------------------------------------------
    // 晚疫病 Phytophthora infestans
    // ------------------------------------------------------------------

    /** 晚疫病最适温度下限（摄氏度）。来源：植物病害流行学 + 国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double LATE_BLIGHT_OPTIMAL_LOW_C = 18.0;

    /** 晚疫病最适温度上限（摄氏度）。来源：植物病害流行学 + 国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double LATE_BLIGHT_OPTIMAL_HIGH_C = 22.0;

    /**
     * 晚疫病叶面湿润代理阈值（%）：本简化模型以空气相对湿度代理叶面湿润时长，
     * 低于该值视为叶面未湿润、不侵染。来源：国内番茄植保文献；典型文献区间，待核对出处。
     */
    public static final double LATE_BLIGHT_LEAF_WETNESS_PROXY_THRESHOLD_PCT = 90.0;

    /** 晚疫病侵染所需空气相对湿度阈值（%），与叶面湿润代理阈值同值。来源：国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double LATE_BLIGHT_HUMIDITY_THRESHOLD_PCT = LATE_BLIGHT_LEAF_WETNESS_PROXY_THRESHOLD_PCT;

    /** 晚疫病潜伏期（分钟）。来源：植物病害流行学文献族；典型文献区间，待核对出处。 */
    public static final double LATE_BLIGHT_LATENT_PERIOD_MINUTES = 480.0;

    /** 晚疫病单次侵染事件的严重度增益（%）。来源：植物病害流行学文献族；典型文献区间，待核对出处。 */
    public static final double LATE_BLIGHT_SEVERITY_GAIN = 9.0;

    // ------------------------------------------------------------------
    // 白粉病（中等湿度偏好型，与上述三种“高湿促发型”相反）
    // ------------------------------------------------------------------

    /** 白粉病最适温度下限（摄氏度）。来源：植物病害流行学 + 国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_OPTIMAL_LOW_C = 20.0;

    /** 白粉病最适温度上限（摄氏度）。来源：植物病害流行学 + 国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_OPTIMAL_HIGH_C = 25.0;

    /** 白粉病湿度适宜带下限（%）。来源：国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_BAND_LOW_PCT = 50.0;

    /** 白粉病湿度适宜带上限（%）。来源：国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_BAND_HIGH_PCT = 75.0;

    /** 白粉病湿度响应峰值（%）。来源：国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_PEAK_HUMIDITY_PCT = 65.0;

    /**
     * 白粉病叶面湿润代理阈值（%）：本简化模型以空气相对湿度代理叶面湿润时长。
     * 与其余三种病害相反，白粉病在叶面长时间湿润（高湿）时受抑，
     * 因此该阈值是“高湿抑制”的起点而非“侵染所需”的下限。
     * 来源：国内番茄植保文献；典型文献区间，待核对出处。
     */
    public static final double POWDERY_MILDEW_LEAF_WETNESS_PROXY_THRESHOLD_PCT = 85.0;

    /** 白粉病高湿受抑的起始相对湿度（%），高于该值湿度适宜度被压到下限期；与叶面湿润代理阈值同值。来源：国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_HIGH_HUMIDITY_PCT = POWDERY_MILDEW_LEAF_WETNESS_PROXY_THRESHOLD_PCT;

    /** 白粉病偏干端的参考相对湿度（%），低于该值湿度适宜度取下限期。来源：国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_DRY_REFERENCE_PCT = 35.0;

    /** 白粉病湿度适宜度下限（高湿受抑时的取值，显著低于适宜带）。 */
    public static final double POWDERY_MILDEW_HUMIDITY_FLOOR = 0.1;

    /** 白粉病潜伏期（分钟）。来源：植物病害流行学文献族；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_LATENT_PERIOD_MINUTES = 1440.0;

    /** 白粉病单次侵染事件的严重度增益（%）。来源：植物病害流行学文献族；典型文献区间，待核对出处。 */
    public static final double POWDERY_MILDEW_SEVERITY_GAIN = 4.0;

    // ------------------------------------------------------------------
    // 叶霉病 Passalora fulva
    // ------------------------------------------------------------------

    /** 叶霉病最适温度下限（摄氏度）。来源：植物病害流行学 + 国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double LEAF_MOLD_OPTIMAL_LOW_C = 20.0;

    /** 叶霉病最适温度上限（摄氏度）。来源：植物病害流行学 + 国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double LEAF_MOLD_OPTIMAL_HIGH_C = 25.0;

    /**
     * 叶霉病叶面湿润代理阈值（%）：本简化模型以空气相对湿度代理叶面湿润时长，
     * 低于该值视为叶面未湿润、不侵染。来源：国内番茄植保文献；典型文献区间，待核对出处。
     */
    public static final double LEAF_MOLD_LEAF_WETNESS_PROXY_THRESHOLD_PCT = 85.0;

    /** 叶霉病侵染所需空气相对湿度阈值（%），与叶面湿润代理阈值同值。来源：国内番茄植保文献；典型文献区间，待核对出处。 */
    public static final double LEAF_MOLD_HUMIDITY_THRESHOLD_PCT = LEAF_MOLD_LEAF_WETNESS_PROXY_THRESHOLD_PCT;

    /** 叶霉病潜伏期（分钟）。来源：植物病害流行学文献族；典型文献区间，待核对出处。 */
    public static final double LEAF_MOLD_LATENT_PERIOD_MINUTES = 720.0;

    /** 叶霉病单次侵染事件的严重度增益（%）。来源：植物病害流行学文献族；典型文献区间，待核对出处。 */
    public static final double LEAF_MOLD_SEVERITY_GAIN = 5.0;

    // ------------------------------------------------------------------
    // 害虫种群（logistic 增长）
    // ------------------------------------------------------------------

    /** 害虫内禀增长率（每天）。来源：害虫种群生态学文献族；典型文献区间，待核对出处。 */
    public static final double R_MAX_PER_DAY = 0.08;

    /** 害虫环境容纳量（相对虫口单位）。来源：害虫种群生态学文献族；典型文献区间，待核对出处。 */
    public static final double CARRYING_CAPACITY = 500.0;

    /** 害虫繁殖最适温度下限（摄氏度）。来源：害虫种群生态学文献族；典型文献区间，待核对出处。 */
    public static final double PEST_OPTIMAL_LOW_C = 20.0;

    /** 害虫繁殖最适温度上限（摄氏度）。来源：害虫种群生态学文献族；典型文献区间，待核对出处。 */
    public static final double PEST_OPTIMAL_HIGH_C = 30.0;

    /** 害虫温度适宜度衰减到 0 的半宽（摄氏度）。来源：害虫种群生态学文献族；典型文献区间，待核对出处。 */
    public static final double PEST_TEMPERATURE_HALF_WIDTH_C = 8.0;

    /** 初始害虫种群数量。 */
    public static final double INITIAL_PEST_POPULATION = 5.0;
}
