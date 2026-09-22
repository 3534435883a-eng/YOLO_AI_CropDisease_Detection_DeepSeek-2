import request from '/@/utils/request';

/** 视觉检测类别 → 知识库条目 的映射与覆盖缺口（后端 /ai/knowledge/vision-map）。 */

export interface VisionClassMapItem {
	modelCode?: string;
	cropType?: string;
	classIndex?: number;
	/** 模型原始类别标签（可能含零宽空格，后端已清洗后再入库）。 */
	classLabel?: string;
	labelEn?: string;
	labelZh?: string;
	/** 映射到的知识库病害条目；为 null 表示**没有可核对的对应条目**。 */
	kbDiseaseName?: string | null;
	/** V1/V2/V3 名称层面、KB_TEXT 原文枚举、EXTERNAL 外部权威来源、NONE 未映射、HEALTHY 健康类。 */
	matchRule?: string;
	evidence?: string;
	sourceUrl?: string | null;
	healthy?: boolean;
	explainable?: boolean;
}

export interface VisionMapSummary {
	detectableCrops?: Record<string, number>;
	detectableCropsTotal?: number;
	explainable?: Record<string, number>;
	explainableTotal?: number;
	unexplained?: Record<string, number>;
	unexplainedTotal?: number;
}

export interface VisionMap {
	summary?: VisionMapSummary;
	items?: VisionClassMapItem[];
}

/** 后端所有控制器统一返回 {code,msg,data} 信封，这里只取 data。 */
function unwrap<T>(response: unknown): T {
	if (response && typeof response === 'object' && ('code' in response || 'data' in response)) {
		return (response as { data: T }).data;
	}
	return response as T;
}

export const getVisionClassMap = async (): Promise<VisionMap> => {
	return unwrap<VisionMap>(await request.get('/api/ai/knowledge/vision-map'));
};

export const MATCH_RULE_LABELS: Record<string, string> = {
	V1: '名称完全一致',
	V2: '去作物前缀一致',
	V3: '名称包含',
	KB_TEXT: '知识库原文枚举',
	EXTERNAL: '外部权威来源',
	HEALTHY: '健康类（无需映射）',
	NONE: '暂无对应条目',
};