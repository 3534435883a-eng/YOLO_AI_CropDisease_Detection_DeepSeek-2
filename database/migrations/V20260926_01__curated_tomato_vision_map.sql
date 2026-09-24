-- Only promote reviewed tomato labels. Generic Mosaic_V remains unmapped because
-- several distinct viruses produce mosaic symptoms and the model label does not identify one.
UPDATE agent_vision_class_map
SET kb_disease_name = '番茄潜叶蝇（潜叶虫）', match_rule = 'EXTERNAL',
    evidence = 'UC IPM 番茄潜叶蝇指南记载叶内弯曲潜道及 Liriomyza spp.；仅映射到害虫类群，不判定具体种',
    source_url = 'https://ipm.ucanr.edu/agriculture/tomato/leafminers/', verified_at = NOW(3)
WHERE model_code = 'tomato' AND class_index = 3 AND match_rule = 'NONE' AND kb_disease_name IS NULL;

UPDATE agent_vision_class_map
SET kb_disease_name = '番茄黄化曲叶病', match_rule = 'EXTERNAL',
    evidence = 'UC IPM Tomato Yellow Leaf Curl 记载 TYLCV 病害及典型症状；YLCV 模型标签仅作为待复核检索入口，不能凭图像确诊病毒',
    source_url = 'https://ipm.ucanr.edu/agriculture/tomato/tomato-yellow-leaf-curl/', verified_at = NOW(3)
WHERE model_code = 'tomato' AND class_index = 8 AND match_rule = 'NONE' AND kb_disease_name IS NULL;

UPDATE agent_vision_class_map
SET kb_disease_name = '番茄叶螨（红蜘蛛类）', match_rule = 'EXTERNAL',
    evidence = 'UC IPM Spider Mites 指南记载蔬菜叶螨的失绿斑点、结网等征象；仅映射到类群，不判定番茄上的具体螨种',
    source_url = 'https://ipm.ucanr.edu/home-and-landscape/spider-mites/', verified_at = NOW(3)
WHERE model_code = 'tomato' AND class_index = 7 AND match_rule = 'NONE' AND kb_disease_name IS NULL;
