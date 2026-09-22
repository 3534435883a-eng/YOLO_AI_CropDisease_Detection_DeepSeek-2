package com.example.Ece.agent.eco;

/**
 * 番茄设施栽培主要气传病害种类（模型 2）。
 *
 * <p>四种病害在温度与湿度响应上刻意保持差异：灰霉病、晚疫病、叶霉病属于“高湿促发”型，
 * 白粉病属于“中等湿度偏好、高湿受抑”型。模型实现必须体现这一差异，
 * 而不是所有病害共用一个单调的湿度响应。</p>
 */
public enum DiseaseKind {

    /** 灰霉病（Botrytis cinerea）：低温高湿型。 */
    BOTRYTIS,

    /** 晚疫病（Phytophthora infestans）：中低温高湿型。 */
    LATE_BLIGHT,

    /** 白粉病（Leveillula taurica / Oidium 类）：中等湿度偏好，高湿受抑。 */
    POWDERY_MILDEW,

    /** 叶霉病（Passalora fulva）：中温高湿型。 */
    LEAF_MOLD
}
