package com.example.Ece.agent.crop;

/**
 * 番茄生育阶段（半机理作物模型的离散状态）。
 *
 * <p>顺序即物候先后：SEEDLING -&gt; FLOWERING -&gt; FRUIT_SET -&gt; FRUIT_GROWTH -&gt; MATURITY。
 * 模型中阶段由累积有效积温（GDD）阈值推导，且<b>只能向前推进，不允许回退</b>。</p>
 */
public enum CropStage {

    /** 苗期：以营养生长为主，干物质优先分配给叶片与根系。 */
    SEEDLING,

    /** 开花期：花穗分化至开花，同化物仍以营养器官为主。 */
    FLOWERING,

    /** 坐果期：开始坐果，同化物开始向果实分配。 */
    FRUIT_SET,

    /** 果实膨大期：果实成为主要库器官。 */
    FRUIT_GROWTH,

    /** 成熟期：果实成熟，模型标记 mature = true。 */
    MATURITY
}
