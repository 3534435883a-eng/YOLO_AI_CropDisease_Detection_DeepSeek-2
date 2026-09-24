# 视觉类别 → 知识库条目：证据化核验记录

> 只读分析，2026-09-23。**本文件记录"每条映射凭什么成立"**，以及哪些类别**不能**映射。
> 起因：首版核验把"子串出现在原文里"当作映射依据，7 条候选里有 5 条是错的，
> 因此改为"只接受名称层面证据 + 逐条人工裁定 + 外部权威来源引用"。

## 一、核验方法（判定规则）

| 规则 | 含义 | 是否可直接采信 |
|---|---|---|
| **V1 名称完全一致** | 知识库条目名 == 类别中文标签 | ✅ 可直接采信（字符串相等可复核） |
| **V2 去作物前缀后一致** | `草莓白粉病果` ↔ `白粉病果` | ✅ 可直接采信 |
| **V3 名称包含** | `番茄早疫病` ⊃ `早疫病`（且长度差 ≤ 2） | ✅ 可直接采信（名称层面） |
| **KB 原文枚举** | 知识库原文把该类别名列为该病害的一种 | ✅ 采信（引用原文） |
| **外部权威来源** | 由可引用的公开权威记录确认病原/病名对应 | ✅ 采信（附 URL） |
| ~~原文任意出现~~ | 子串出现在症状/诱因/防治文本中 | ❌ **不可采信**（会产生反向与巧合同串证据） |

**数据来源**：项目自训练 9 个检测模型的类别标签（`Flask/weights/*_best.pt`，共 56 类，剔除 Healthy 后 50 类）
× 知识库 100 条病害记录（`cropdisease.disease`）。类别标签形如 `CedarRust(苹果锈病)`，部分标签含**零宽空格**（已清洗）。

## 二、已验证映射（21 条，每条附依据）

### 名称层面（V1/V2/V3，18 条）

| 模型 | 类别标签 | → 知识库条目 | 规则 |
|---|---|---|---|
| apple | CedarRust(苹果锈病) | 苹果锈病 | V1 |
| corn | Rust(玉米锈病) | 玉米锈病 | V1 |
| potato | Early_Blight(早疫病) | 马铃薯早疫病 | V3 |
| potato | Late_Blight(晚疫病) | 马铃薯晚疫病 | V3 |
| rice | Narrow_Br_Spot(窄条斑病) | 水稻窄条斑病 | V3 |
| strawberry | Angular_LS(角斑病) | 草莓角斑病 | V3 |
| strawberry | Anthracnose_FR(炭疽果腐) | 草莓炭疽果腐病 | V3 |
| strawberry | Blossom_BT(花枯病) | 草莓花枯病 | V3 |
| strawberry | Gray_Mold(灰霉病) | 草莓灰霉病 | V3 |
| strawberry | Leaf_Spot(叶斑病) | 草莓叶斑病 | V3 |
| strawberry | Powdery_Fruit(白粉病果) | 草莓白粉病果 | V2 |
| strawberry | Powdery_Leaf(白粉病叶) | 草莓白粉病叶 | V2 |
| tomato | Early_Blight(早疫病) | 番茄早疫病 | V3 |
| tomato | Late_Blight(晚疫病) | 番茄晚疫病 | V3 |
| tomato | Leaf_Mold(叶霉病) | 番茄叶霉病 | V3 |
| wheat | Leaf_Rust(小麦叶锈病) | 小麦叶锈病 | V1 |
| wheat | Powdery_Mildew(小麦白粉病) | 小麦白粉病 | V1 |
| wheat | Stripe_Rust(小麦条锈病) | 小麦条锈病 | V1 |

⚠️ **以下两条曾按中文标签成立，但英文标签与中文标签疑似不一致，现已撤回映射，需人工确认后才能恢复**（不得据此断言病原）：
- `rice / Scald(纹枯病)`：Scald 在英文文献中通常指水稻云形病/褐色叶枯病，与"纹枯病"不是同一病害。
- `grape / Downey_Mildew(白粉病)`：Downy Mildew 为**霜霉病**（Plasmopara viticola），而知识库另有"葡萄霜霉病"条目；
  两者的区分可参考 [科普中国：葡萄霜霉病和白粉病 防治前要分清](https://www.kepuchina.cn/article/articleinfo?ar_id=150183&business_type=100&classify=0)。

### 知识库原文枚举（2 条）

| 模型 | 类别标签 | → 知识库条目 | 依据（原文） |
|---|---|---|---|
| rice | Leaf_Blast(叶瘟病) | 稻瘟病 | 「稻瘟病可分为苗瘟、**叶瘟**、节瘟、**穗颈瘟**和谷粒瘟几种」 |
| rice | Neck_Blast(穗颈瘟) | 稻瘟病 | 同上 |

### 外部权威来源（1 条）

| 模型 | 类别标签 | → 知识库条目 | 依据 |
|---|---|---|---|
| tomato | Septoria(壳针孢病) | 番茄斑枯病 | [北京农业数字信息资源中心·蔬菜病害数据库](http://www.agridata.ac.cn:8888/Web/DataBaseVisitDetail.aspx?DataBase=%e8%94%ac%e8%8f%9c%e7%97%85%e5%ae%b3%e6%95%b0%e6%8d%ae%e5%ba%93&SysId=70&order=insert_date)：病害名称"番茄斑枯病"，病原"**_Septoria lycopersici_ Spegazzini 称番茄壳针孢**" |

## 三、被否决的候选（5 条，附否决理由）

| 候选 | 首版误判依据 | 否决理由 |
|---|---|---|
| 棉花 Blight(枯萎病) → 棉花黑根腐病 | 原文出现"枯萎病" | 原文是「**不同于**棉花黄萎病与枯萎病」——**反向证据** |
| 棉花 Curl(卷叶病) → 棉大卷叶螟 | 原文出现"卷叶" | 只是越冬描述里的"地面**枯卷叶**"巧合同串；不能据此断言"卷叶病=卷叶螟为害" |
| 棉花 Wilt(萎蔫病) → 棉花黑根腐病 | 原文出现"萎蔫" | "叶片…**萎蔫**"是症状词，不是病名；且该类别在模型中**重复出现两次** |
| 水稻 Brn_Spot(褐斑病) → 水稻紫鞘病 | 原文出现"褐斑" | 原文是"紫**褐斑**"巧合同串 |

## 四、无任何线索（22 条，知识库现状无法对应）

`apple: RootRot(黑根腐病)、Scab(黑星病)`；
`corn: Blight(枯萎病)、Gray_Spot(玉米灰叶斑病)、FAW_Lv(秋军虫幼虫病)、Streak(玉米条斑病)、Stem_Borer(黄秆虫病)、StemBorer_Lv(黄秆虫幼虫病)`；
`grape: Black_Rot(黑腐病)、Esca(木材腐烂病)、Leaf_Blight(叶枯病)`；
`rice: Bact_L_Blight(细菌枯病)、Hispa(稻铁甲虫)`；
`tomato: Leaf_Miner(潜叶虫)、Mosaic_V(花叶病毒)、Spider_M(红蜘蛛)、YLCV(黄化卷叶病毒)`；
`wheat: Bacterial_Streak(小麦黑秆病)、Head_Scab(小麦穗霉病)、Loose_Smut(小麦松秕病)、Septoria_Blotch(小麦赤霉病)、Stem_Rust(小麦茎锈病)`

**其中部分存在"疑似对应"但未获证据，一律不写入映射**，例如
`小麦 Loose_Smut(松秕病)` 与知识库"小麦散黑穗病"（英文 loose smut 通常对应散黑穗病）——需中文权威来源确认后再采信。

## 五、由本次核验发现的项目自身问题（建议自查，与知识库无关）

1. **类别重复**：棉花模型 `Wilt(萎蔫病)` 出现两次。
2. **标签疑似错译**：`grape/Downey_Mildew` 标为"白粉病"（应为霜霉病）；`rice/Scald` 标为"纹枯病"（通常指云形病/褐色叶枯病）；
   `wheat/Septoria_Blotch` 标为"赤霉病"（Septoria 引起斑枯病，且知识库中就有"小麦斑枯病"）。
3. **标签含零宽空格**：`Gray_Spot`、`Rust`、`FAW_Lv`、`Streak` 等标签前含 `U+200B`，会影响字符串比较与前端显示。
4. **模型与知识库覆盖面不匹配**：模型可检出的类别中，28/50 在知识库中找不到依据（含 5 条被否决的候选）。

## 六、2026-09-24 番茄权威资料补充

本节是对上方 2026-09-23 快照的增量记录。接口实测旧版覆盖为 **21/50**，未映射 **29/50**；上方“28/50”是旧文档统计误差。本批次完成后目标为 **24/50**，未映射 **26/50**。原文均已于 2026-09-24 打开核对，入库内容为人工改写的短摘要，存放在 `src/main/resources/knowledge/curated-tomato.json`，未复制网页全文或历史药剂剂量。

| 模型类别 | 新知识条目 | 核验依据与边界 |
|---|---|---|
| `tomato/Leaf_Miner(潜叶虫)` | 番茄潜叶蝇（潜叶虫） | [UC IPM 番茄潜叶蝇](https://ipm.ucanr.edu/agriculture/tomato/leafminers/)记载潜道和两种常见斑潜蝇；只映射害虫类群，不判定具体种。 |
| `tomato/YLCV(黄化卷叶病毒)` | 番茄黄化曲叶病 | [UC IPM Tomato Yellow Leaf Curl](https://ipm.ucanr.edu/agriculture/tomato/tomato-yellow-leaf-curl/)记载病原、症状和粉虱传播；模型标签作为检索入口，图像不能确诊病毒。 |
| `tomato/Spider_M(红蜘蛛)` | 番茄叶螨（红蜘蛛类） | [UC IPM Spider Mites](https://ipm.ucanr.edu/home-and-landscape/spider-mites/)记载蔬菜叶螨的失绿斑点和结网；页面是多作物通用资料，不能确定番茄上的具体螨种。 |
| `tomato/Mosaic_V(花叶病毒)` | **仍未映射** | [烟草花叶类](https://ipm.ucanr.edu/agriculture/tomato/tobacco-mosaic/)与[黄瓜花叶病毒类](https://ipm.ucanr.edu/agriculture/tomato/mosaic-virus-diseases-caused-by-cucumoviruses/)都可能出现花叶；模型标签不足以区分。两类资料分别入库，供鉴别检索，不将模型结果指向任一种。 |
| `tomato/Septoria(壳针孢病)` | 番茄斑枯病（原映射保留） | [北京农业数字信息资源中心](http://www.agridata.ac.cn:8888/Web/DataBaseVisitDetail.aspx?DataBase=%e8%94%ac%e8%8f%9c%e7%97%85%e5%ae%b3%e6%95%b0%e6%8d%ae%e5%ba%93&SysId=70&order=insert_date)明确记载 `Septoria lycopersici` 为番茄壳针孢；本次补录可检索摘要和出处。 |

来源登记和每个知识块的对应关系保存在 `agent_knowledge_source` 与 `agent_knowledge_chunk_origin`。后端生成给模型的证据文本时附来源名称、资料层级和原文 URL；旧 `disease` 表的片段继续标为 **E 类项目历史数据**，没有原文 URL 时明示“原文链接未登记”。UC IPM 页面包含加州区域信息，不能直接转作中国温室的药剂处方或用量。

## 七、2026-09-24 病害知识扩充

本批次补充 5 条番茄病害与 1 条苹果病害的 **B 类人工改写短摘要**。番茄资料保存于 `src/main/resources/knowledge/curated-tomato.json`，苹果资料保存于 `src/main/resources/knowledge/curated-apple.json`。每条都独立登记来源名称、原文链接、核验日期和版权说明，加载给模型的证据继续附出处。下列原文于 2026-09-24 直接打开核对；加州发生范围、药剂和剂量不作为中国地区建议。

| 知识条目 | 一手来源 | 用途和边界 |
|---|---|---|
| 番茄早疫病 | [UC IPM Early Blight](https://ipm.ucanr.edu/agriculture/tomato/early-blight/) | 补充老叶同心轮纹、病残体和溅水传播证据；已有名称映射保持不变。 |
| 番茄晚疫病 | [UC IPM Late Blight](https://ipm.ucanr.edu/agriculture/tomato/late-blight/) | 补充水渍状叶斑、叶背霉层及持续高湿风险；已有名称映射保持不变。 |
| 番茄细菌性斑点病 | [UC IPM Bacterial Spot](https://ipm.ucanr.edu/agriculture/tomato/bacterial-spot/) | 补充带菌种苗和溅水传播信息；不将它与旧库“番茄细菌性褐斑病”自动合并或映射。 |
| 番茄灰霉病 | [UC IPM Gray Mold](https://ipm.ucanr.edu/agriculture/tomato/gray-mold/) | 补充花果受侵、茎部伤口和叶面持湿信息；不新增识别类别映射。 |
| 番茄枯萎病 | [UC IPM Fusarium Wilt](https://ipm.ucanr.edu/agriculture/tomato/fusarium-wilt/) | 补充单侧萎蔫和维管束褐变；与黄萎病相似时需专业鉴别。 |
| 苹果黑星病 | [UC IPM Apple Scab](https://ipm.ucanr.edu/agriculture/apple/apple-scab/) | 补充橄榄黑色绒斑、落叶越冬和湿润风险；据明确病名将 `apple/Scab(黑星病)` 映射到此条，图像不能单独确诊。 |

该增量将识别类别的可解释覆盖目标从 **24/50** 提高到 **25/50**；`tomato/Mosaic_V`、中英文冲突标签及其他未核实类别继续保持未映射。实际计数以运行中的 `/ai/knowledge/status` 和 `/ai/knowledge/vision-map` 为准。
