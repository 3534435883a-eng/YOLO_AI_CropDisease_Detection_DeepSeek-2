# 模拟大棚交互与动画 Implementation Plan

**Goal:** 实施用户已选择的交互和展示动画，保留模拟数据边界及手动相机控制。

**Architecture:** 选择反馈、设备流向、巡览各自独立模块；scene.ts 统一对接设备和帧循环，index.vue 管理控制与标签。复用 Three.js，无新增运行依赖。

**Tech Stack:** Vue 3、TypeScript、Three.js、Element Plus。

**Spec:** docs/superpowers/specs/2026-10-01-greenhouse-interaction-animation-design.md。

## Global Constraints

- 用户已批准交互和展示动画；保留已有未提交修改，不改模型数值、步长、数据库或快照。
- 本地演示只在示例数据下生效；历史快照保留其时刻。
- 不增加或运行实现测试，采用构建、静态审阅及浏览器展示核对。
- 独立执行此计划，不要求新增插件安装或子代理。

## Task 1: 设备选择反馈

- [x] 新建 equipmentInteraction.ts：输入 canvas、camera、scene、equipment 和选择回调，提供 select(code)、update(dt,enabled)、getHoverLabel(width,height)、dispose()。Box3Helper 独立边框，Raycaster 节流，拖动超过5px取消点击选择。
- [x] scene.ts 接入设备选择，保留 onInspect/getInspectionInfo；index.vue 增加悬停标签、关闭选择与靠近查看，目录同步。

## Task 2: 运行动画流向

- [x] 新建 operatingFlows.ts：复用设备 entry.active，按稳定 code 建立灌溉、HAF、排风、加热路线，用三组 LineSegments 复用缓冲区。
- [x] 提供 setEnabled(boolean)、setQuality(quality)、update(dt)、dispose()；scene.ts 在设备状态刷新后调用。低画质减少箭头数，停机后淡出，UI显示示意图例。

## Task 3: 巡览与展示控制

- [x] 新建 sceneTour.ts：定点路线、平滑位置和目标补间、巡览/暂停/继续/结束状态；用户输入立即结束，页面隐藏暂停。scene.ts 接入并向UI提供状态。
- [x] index.vue 在紧凑的场景交互面板放巡览、流向开关、日级回放及展示时钟滑杆。时钟只改变日级场景光照，不改数据；半小时模式仅显示保存时刻。

## Task 4: 构建与记录

- [x] Vite 生产构建输出独立 dist-interaction-20261001；静态检查监听、状态映射与释放。
- [x] 浏览器核对新控制与场景呈现，保存页面截图，记录插件/skill搜索结论、已见效果与验证限制。

## 用户追加的界面范围

- [x] 顶部模式与数据开关、左侧探索双标签、右侧统一暖色详情、底部视角条、设备搜索与跨号、全屏入口。复用当前CSS与组件库，不迁移框架或引入新依赖。

## 实施记录（2026-10-01）

- 完成三个独立场景模块及scene接入：悬停/选中边框、拖动识别、设备选中详情、三组批量流向线、五站巡览与暂停/恢复/结束。运行流向是示意，不是流体或速度实测。
- 完成日级生长回放、灯光时钟滑杆、昼夜循环、场景设备演示与复位；API状态与半小时历史快照不被本地开关覆盖。
- 用户追加全界面完善已落实：探索与回放双标签、暖色详情和目录、归组工具条、目录搜索、跨号、全屏、焦点/悬停反馈；探索与数据面板互斥。
- 用户授权搜索插件与skill。已使用find-skills及plugin-management检索；社区候选为CloudAI-X/threejs-skills的threejs-interaction、threejs-animation，参考Three.js官方Raycaster资料。插件目录返回视频生成/设计类工具，未发现本次直接适用的工具；未安装插件或skill，不引入额外运行依赖。
- Vite生产构建成功（2299模块），输出dist-interaction-20261001；已有字体及注册背景引用警告仍在。
- 仓库TypeScript4.9无法解析现有@types/three的新语法。使用电脑已有npm缓存TypeScript7.0.2对scene及新增模块做独立noEmit检查，修正巡览坐标的元组展开，检查通过；项目package.json与依赖不变。不宣称全仓库或Vue模板类型检查通过。
- 浏览器已显示新布局、双标签、生长/光照控制；实际看到巡览进入供水站并暂停、设备目录定位后详情显示运行状态、光照循环显示时钟递增。读取页面捕获错误列表为空。
- 静态检查监听清理、独立标记材质、低画质箭头数量与仅场景演示的状态边界；未新增或执行实现测试，未运行性能基准或模拟精度验证。M3原始导入和校准仍未完成。


- 最终页面截图保存于 E:/agent 农业/.runtime/greenhouse-interaction-20261001.jpg；保留浏览器数字孪生温室标签供用户查看。
