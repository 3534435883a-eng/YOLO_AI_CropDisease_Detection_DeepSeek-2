-- Apple Scab is explicitly named by the UC IPM apple guideline. Preserve all
-- other unresolved labels and only promote the previously unmapped apple class.
UPDATE agent_vision_class_map
SET kb_disease_name = '苹果黑星病', match_rule = 'EXTERNAL',
    evidence = 'UC IPM Apple Scab 指南明确以苹果为作物、黑星病为病害；仅将 Scab(黑星病) 映射为知识检索入口，图像结果不能单独确诊病原',
    source_url = 'https://ipm.ucanr.edu/agriculture/apple/apple-scab/', verified_at = NOW(3)
WHERE model_code = 'apple' AND class_index = 1 AND match_rule = 'NONE' AND kb_disease_name IS NULL;
