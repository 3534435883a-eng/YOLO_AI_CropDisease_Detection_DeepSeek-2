-- 视觉检测类别 → 知识库条目 映射表（create-only + 幂等 upsert）
--
-- 目的：把"项目自训练模型的检测类别"与"知识库病害条目"显式对应起来，
--       使摄像头/图片检出结果能被智能体解释（采集 → 分析 闭环）。
--
-- 纪律：**只写入经过证据化核验的映射**。核验规则与逐条依据见 docs/vision-class-kb-mapping.md：
--   V1 名称完全一致 / V2 去作物前缀一致 / V3 名称包含  → 名称层面可直接复核
--   KB_TEXT  知识库原文把该类别列为该病害的一种（如稻瘟病包含叶瘟、穗颈瘟）
--   EXTERNAL 外部权威来源确认（如番茄壳针孢 Septoria lycopersici ↔ 番茄斑枯病）
--   NONE     无任何可核对依据 → **不映射**，宁可留缺口也不推测
--   HEALTHY  模型健康类别，无需映射
-- 首版曾用"子串出现在原文里"作依据，7 条候选错 5 条（如"不同于…枯萎病"被当成映射），已废弃该规则。
--
-- 本迁移每次启动都会执行，因此用 INSERT ... ON DUPLICATE KEY UPDATE 保证幂等。

CREATE TABLE IF NOT EXISTS `agent_vision_class_map` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `model_code` VARCHAR(32) NOT NULL,
  `crop_type` VARCHAR(64) NOT NULL,
  `class_index` INT NOT NULL,
  `class_label` VARCHAR(128) NOT NULL,
  `label_en` VARCHAR(64) NULL,
  `label_zh` VARCHAR(64) NULL,
  `kb_disease_name` VARCHAR(128) NULL,
  `match_rule` VARCHAR(16) NOT NULL,
  `evidence` VARCHAR(768) NOT NULL,
  `source_url` VARCHAR(1024) NULL,
  `is_healthy` TINYINT NOT NULL DEFAULT 0,
  `verified_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vision_class` (`model_code`, `class_index`),
  KEY `idx_vision_class_kb` (`kb_disease_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `agent_vision_class_map`
  (`model_code`, `crop_type`, `class_index`, `class_label`, `label_en`, `label_zh`,
   `kb_disease_name`, `match_rule`, `evidence`, `source_url`, `is_healthy`, `verified_at`)
VALUES('apple','苹果',0,'RootRot(黑根腐病)','RootRot','黑根腐病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('apple','苹果',1,'Scab(黑星病)','Scab','黑星病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('apple','苹果',2,'CedarRust(苹果锈病)','CedarRust','苹果锈病','苹果锈病','V1','知识库条目名与类别中文名完全相同',NULL, 0, NOW(3)),
('apple','苹果',3,'Healthy(健康)','Healthy','健康',NULL,'HEALTHY','模型健康类别，无需映射知识条目',NULL, 1, NOW(3)),
('corn','玉米',0,'Blight(枯萎病)','Blight','枯萎病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('corn','玉米',1,'​Gray_Spot(玉米灰叶斑病)','Gray_Spot','玉米灰叶斑病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('corn','玉米',2,'​Rust(玉米锈病)','Rust','玉米锈病','玉米锈病','V1','知识库条目名与类别中文名完全相同',NULL, 0, NOW(3)),
('corn','玉米',3,'​FAW_Lv(秋军虫幼虫病)','FAW_Lv','秋军虫幼虫病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('corn','玉米',4,'​Streak(玉米条斑病)','Streak','玉米条斑病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('corn','玉米',5,'Stem_Borer(黄秆虫病)','Stem_Borer','黄秆虫病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('corn','玉米',6,'StemBorer_Lv(黄秆虫幼虫病)','StemBorer_Lv','黄秆虫幼虫病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('cotton','棉花',0,'Blight(枯萎病)','Blight','枯萎病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('cotton','棉花',1,'Curl(卷叶病)','Curl','卷叶病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('cotton','棉花',2,'Healthy(健康)','Healthy','健康',NULL,'HEALTHY','模型健康类别，无需映射知识条目',NULL, 1, NOW(3)),
('cotton','棉花',3,'Wilt(萎蔫病)','Wilt','萎蔫病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('cotton','棉花',4,'Wilt(萎蔫病)','Wilt','萎蔫病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('grape','葡萄',0,'Black_Rot(黑腐病)','Black_Rot','黑腐病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('grape','葡萄',1,'Downey_Mildew(白粉病)','Downey_Mildew','白粉病','葡萄白粉病','V3','名称包含：葡萄白粉病 ⊃ 白粉病（英文标签 Downy_Mildew 与中文标签疑似不一致，待人工确认）',NULL, 0, NOW(3)),
('grape','葡萄',2,'Esca(木材腐烂病)','Esca','木材腐烂病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('grape','葡萄',3,'Healthy(健康)','Healthy','健康',NULL,'HEALTHY','模型健康类别，无需映射知识条目',NULL, 1, NOW(3)),
('grape','葡萄',4,'Leaf_Blight(叶枯病)','Leaf_Blight','叶枯病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('potato','马铃薯',0,'Early_Blight(早疫病)','Early_Blight','早疫病','马铃薯早疫病','V3','名称包含：马铃薯早疫病 ⊃ 早疫病',NULL, 0, NOW(3)),
('potato','马铃薯',1,'Healthy(健康)','Healthy','健康',NULL,'HEALTHY','模型健康类别，无需映射知识条目',NULL, 1, NOW(3)),
('potato','马铃薯',2,'Late_Blight(晚疫病)','Late_Blight','晚疫病','马铃薯晚疫病','V3','名称包含：马铃薯晚疫病 ⊃ 晚疫病',NULL, 0, NOW(3)),
('rice','水稻',0,'Bact_L_Blight(细菌枯病)','Bact_L_Blight','细菌枯病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('rice','水稻',1,'Brn_Spot(褐斑病)','Brn_Spot','褐斑病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('rice','水稻',2,'Healthy(健康)','Healthy','健康',NULL,'HEALTHY','模型健康类别，无需映射知识条目',NULL, 1, NOW(3)),
('rice','水稻',3,'Leaf_Blast(叶瘟病)','Leaf_Blast','叶瘟病','稻瘟病','KB_TEXT','知识库原文枚举：稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟几种',NULL, 0, NOW(3)),
('rice','水稻',4,'Scald(纹枯病)','Scald','纹枯病','水稻纹枯病','V3','名称包含：水稻纹枯病 ⊃ 纹枯病（英文标签 Scald 与中文标签疑似不一致，待人工确认）',NULL, 0, NOW(3)),
('rice','水稻',5,'Narrow_Br_Spot(窄条斑病)','Narrow_Br_Spot','窄条斑病','水稻窄条斑病','V3','名称包含：水稻窄条斑病 ⊃ 窄条斑病',NULL, 0, NOW(3)),
('rice','水稻',6,'Neck_Blast(穗颈瘟)','Neck_Blast','穗颈瘟','稻瘟病','KB_TEXT','知识库原文枚举：稻瘟病可分为苗瘟、叶瘟、节瘟、穗颈瘟和谷粒瘟几种',NULL, 0, NOW(3)),
('rice','水稻',7,'Hispa(稻铁甲虫)','Hispa','稻铁甲虫',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('strawberry','草莓',0,'Angular_LS(角斑病)','Angular_LS','角斑病','草莓角斑病','V3','名称包含：草莓角斑病 ⊃ 角斑病',NULL, 0, NOW(3)),
('strawberry','草莓',1,'Anthracnose_FR(炭疽果腐)','Anthracnose_FR','炭疽果腐','草莓炭疽果腐病','V3','名称包含：草莓炭疽果腐病 ⊃ 炭疽果腐',NULL, 0, NOW(3)),
('strawberry','草莓',2,'Blossom_BT(花枯病)','Blossom_BT','花枯病','草莓花枯病','V3','名称包含：草莓花枯病 ⊃ 花枯病',NULL, 0, NOW(3)),
('strawberry','草莓',3,'Gray_Mold(灰霉病)','Gray_Mold','灰霉病','草莓灰霉病','V3','名称包含：草莓灰霉病 ⊃ 灰霉病',NULL, 0, NOW(3)),
('strawberry','草莓',4,'Leaf_Spot(叶斑病)','Leaf_Spot','叶斑病','草莓叶斑病','V3','名称包含：草莓叶斑病 ⊃ 叶斑病',NULL, 0, NOW(3)),
('strawberry','草莓',5,'Powdery_Fruit(白粉病果)','Powdery_Fruit','白粉病果','草莓白粉病果','V2','去作物前缀后一致：草莓白粉病果 ↔ 白粉病果',NULL, 0, NOW(3)),
('strawberry','草莓',6,'Powdery_Leaf(白粉病叶)','Powdery_Leaf','白粉病叶','草莓白粉病叶','V2','去作物前缀后一致：草莓白粉病叶 ↔ 白粉病叶',NULL, 0, NOW(3)),
('tomato','番茄',0,'Early_Blight(早疫病)','Early_Blight','早疫病','番茄早疫病','V3','名称包含：番茄早疫病 ⊃ 早疫病',NULL, 0, NOW(3)),
('tomato','番茄',1,'Healthy(健康)','Healthy','健康',NULL,'HEALTHY','模型健康类别，无需映射知识条目',NULL, 1, NOW(3)),
('tomato','番茄',2,'Late_Blight(晚疫病)','Late_Blight','晚疫病','番茄晚疫病','V3','名称包含：番茄晚疫病 ⊃ 晚疫病',NULL, 0, NOW(3)),
('tomato','番茄',3,'Leaf_Miner(潜叶虫)','Leaf_Miner','潜叶虫',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('tomato','番茄',4,'Leaf_Mold(叶霉病)','Leaf_Mold','叶霉病','番茄叶霉病','V3','名称包含：番茄叶霉病 ⊃ 叶霉病',NULL, 0, NOW(3)),
('tomato','番茄',5,'Mosaic_V(花叶病毒)','Mosaic_V','花叶病毒',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('tomato','番茄',6,'Septoria(壳针孢病)','Septoria','壳针孢病','番茄斑枯病','EXTERNAL','外部权威来源：北京农业数字信息资源中心·蔬菜病害数据库记载病原为 Septoria lycopersici Spegazzini 称番茄壳针孢，对应病害名称“番茄斑枯病”','http://www.agridata.ac.cn:8888/Web/DataBaseVisitDetail.aspx?DataBase=%e8%94%ac%e8%8f%9c%e7%97%85%e5%ae%b3%e6%95%b0%e6%8d%ae%e5%ba%93&SysId=70&order=insert_date', 0, NOW(3)),
('tomato','番茄',7,'Spider_M(红蜘蛛)','Spider_M','红蜘蛛',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('tomato','番茄',8,'YLCV(黄化卷叶病毒)','YLCV','黄化卷叶病毒',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('wheat','小麦',0,'Bacterial_Streak(小麦黑秆病)','Bacterial_Streak','小麦黑秆病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('wheat','小麦',1,'Head_Scab(小麦穗霉病)','Head_Scab','小麦穗霉病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('wheat','小麦',2,'Leaf_Rust(小麦叶锈病)','Leaf_Rust','小麦叶锈病','小麦叶锈病','V1','知识库条目名与类别中文名完全相同',NULL, 0, NOW(3)),
('wheat','小麦',3,'Loose_Smut(小麦松秕病)','Loose_Smut','小麦松秕病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('wheat','小麦',4,'Powdery_Mildew(小麦白粉病)','Powdery_Mildew','小麦白粉病','小麦白粉病','V1','知识库条目名与类别中文名完全相同',NULL, 0, NOW(3)),
('wheat','小麦',5,'Septoria_Blotch(小麦赤霉病)','Septoria_Blotch','小麦赤霉病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('wheat','小麦',6,'Stem_Rust(小麦茎锈病)','Stem_Rust','小麦茎锈病',NULL,'NONE','未找到可核对的依据，暂不映射（见 docs/vision-class-kb-mapping.md）',NULL, 0, NOW(3)),
('wheat','小麦',7,'Stripe_Rust(小麦条锈病)','Stripe_Rust','小麦条锈病','小麦条锈病','V1','知识库条目名与类别中文名完全相同',NULL, 0, NOW(3))
ON DUPLICATE KEY UPDATE
  `crop_type` = VALUES(`crop_type`),
  `class_label` = VALUES(`class_label`),
  `label_en` = VALUES(`label_en`),
  `label_zh` = VALUES(`label_zh`),
  `kb_disease_name` = VALUES(`kb_disease_name`),
  `match_rule` = VALUES(`match_rule`),
  `evidence` = VALUES(`evidence`),
  `source_url` = VALUES(`source_url`),
  `is_healthy` = VALUES(`is_healthy`);