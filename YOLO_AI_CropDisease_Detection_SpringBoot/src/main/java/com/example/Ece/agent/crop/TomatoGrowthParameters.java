package com.example.Ece.agent.crop;

/**
 * 番茄半机理生长模型参数表。
 *
 * <p>下列常数取自三类文献族：</p>
 * <ul>
 *   <li><b>TOMGROM</b>：以有效积温驱动物候、以辐射截获驱动干物质积累的温室番茄模型族。</li>
 *   <li><b>TOMSIM</b>：以冠层光截获（Beer-Lambert 消光）与同化物分配为核心的番茄作物模拟模型族。</li>
 *   <li><b>国内番茄栽培文献</b>：国内日光温室/塑料大棚番茄栽培与温度、水分管理相关的文献族。</li>
 * </ul>
 *
 * <p><b>数值说明：</b>本文件中的取值均为“典型文献区间，实施时逐条核对出处”。
 * 这里刻意不写出具体论文标题、页码或年份，以避免引用不确切的出处。</p>
 */
public final class TomatoGrowthParameters {

    private TomatoGrowthParameters() {
        // 常量类，禁止实例化
    }

    /** 各常数的文献族来源说明（供上层文档/接口展示，避免在代码中臆造具体出处）。 */
    public static final String[] SOURCE_NOTES = new String[] {
            "温度三基点（BASE/OPTIMAL/MAX）与有效积温阈值：TOMGROM 模型族 + 国内番茄栽培文献；典型文献区间，实施时逐条核对出处。",
            "冠层消光系数 K 与光能利用效率 RUE：TOMSIM 模型族；典型文献区间，实施时逐条核对出处。",
            "比叶面积 SLA 与叶片日衰老率：TOMSIM 模型族 + 国内番茄栽培文献；典型文献区间，实施时逐条核对出处。",
            "果实分配系数：TOMSIM 模型族；典型文献区间，实施时逐条核对出处。",
            "坐果对温度与水汽压亏缺（VPD）的敏感性：国内番茄栽培文献；典型文献区间，实施时逐条核对出处。",
            "最大叶面积指数（MAX_LAI）：国内日光温室番茄栽培文献；典型文献区间，实施时逐条核对出处。"
    };

    // ------------------------------------------------------------------
    // 温度三基点（摄氏度）
    // ------------------------------------------------------------------

    /** 生长基点温度：低于此温度有效积温不再累积。来源：TOMGROM 模型族。 */
    public static final double BASE_TEMPERATURE_C = 10.0;

    /** 最适温度下限。来源：TOMGROM 模型族 + 国内番茄栽培文献。 */
    public static final double OPTIMAL_LOW_C = 18.0;

    /** 最适温度上限。来源：TOMGROM 模型族 + 国内番茄栽培文献。 */
    public static final double OPTIMAL_HIGH_C = 28.0;

    /** 温度因子降至 0 的上限温度。来源：TOMGROM 模型族。 */
    public static final double MAX_TEMPERATURE_C = 35.0;

    // ------------------------------------------------------------------
    // 冠层光截获与干物质积累
    // ------------------------------------------------------------------

    /** 冠层消光系数 K（Beer-Lambert）。来源：TOMSIM 模型族。 */
    public static final double EXTINCTION_K = 0.65;

    /** 光能利用效率 RUE（g 干物质 / MJ 截获光合有效辐射）。来源：TOMSIM 模型族。 */
    public static final double RUE_G_PER_MJ = 3.0;

    /** 比叶面积 SLA（m^2 叶面积 / g 叶干重）。来源：TOMSIM 模型族。 */
    public static final double SLA_M2_PER_G = 0.02;

    /** 叶片日衰老率（占现有 LAI 的比例 / 天）。来源：TOMSIM 模型族 + 国内番茄栽培文献。 */
    public static final double SENESCENCE_PER_DAY = 0.02;

    // ------------------------------------------------------------------
    // 同化物分配
    // ------------------------------------------------------------------

    /** 进入坐果期后分配给果实的同化物比例。来源：TOMSIM 模型族。 */
    public static final double FRUIT_ALLOCATION_RATIO = 0.55;

    // ------------------------------------------------------------------
    // 物候有效积温阈值（摄氏度·天，GDD）
    // ------------------------------------------------------------------

    /** 进入开花期的累积有效积温阈值。来源：TOMGROM 模型族。 */
    public static final double GDD_FLOWERING = 600.0;

    /** 进入坐果期的累积有效积温阈值。来源：TOMGROM 模型族。 */
    public static final double GDD_FRUIT_SET = 800.0;

    /** 进入果实膨大期的累积有效积温阈值。来源：TOMGROM 模型族。 */
    public static final double GDD_FRUIT_GROWTH = 1200.0;

    /** 进入成熟期的累积有效积温阈值。来源：TOMGROM 模型族。 */
    public static final double GDD_MATURITY = 1500.0;

    // ------------------------------------------------------------------
    // 冠层与坐果限制
    // ------------------------------------------------------------------

    /** 最大叶面积指数上限。来源：国内番茄栽培文献。 */
    public static final double MAX_LAI = 6.0;

    /** 坐果不受 VPD 抑制的水汽压亏缺上限（kPa）。来源：国内番茄栽培文献。 */
    public static final double VPD_FRUIT_SET_LIMIT_KPA = 2.0;
}
