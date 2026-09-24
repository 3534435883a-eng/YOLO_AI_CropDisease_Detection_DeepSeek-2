<template>
	<div ref="shellRef" class="twin-shell" :style="{ '--twin-h': shellHeight + 'px' }">
		<canvas ref="canvasRef" class="twin-canvas" tabindex="0"></canvas>

		<!-- 病害悬浮标签 -->
		<div v-show="showHud && twinDataMode === 'daily'" class="disease-layer">
			<div
				v-for="m in diseaseMarkers"
				:key="m.code"
				class="disease-tag"
				:class="['d-' + m.code.toLowerCase(), m.visible && m.pct > 0.5 ? 'on' : 'off']"
				:style="{ transform: `translate3d(${m.x}px, ${m.y}px, 0) translate(-50%, -50%)` }"
			>
				<i class="dot"></i>
				<span class="nm">{{ m.name }}</span>
				<span class="pc mono">{{ m.pct.toFixed(1) }}%</span>
			</div>
		</div>

		<!-- ─────────────── 顶栏 ─────────────── -->
		<header class="topbar">
			<div class="brand">
				<span class="mark">TW</span>
				<div class="brand-txt">
					<h1>番茄温室数字孪生</h1>
					<p>TOMATO GREENHOUSE · DIGITAL TWIN</p>
				</div>
			</div>

			<div class="sim-badge">
				<i class="pulse"></i>
				{{ twinDataMode === 'agent' ? 'AGENT RUN · 15分钟规则推演（非实测）' : 'SIMULATED · 日级评测（非实测）' }}
			</div>

			<div class="top-right">
				<span v-if="twinDataMode === 'agent'" class="chip ok">{{ agentRun?.status || '未创建运行' }}</span>
				<span v-else class="chip" :class="source === 'demo' ? 'warn' : 'ok'">
					{{ source === 'demo' ? '示例数据' : '接口数据' }}
				</span>
				<span v-if="twinDataMode === 'daily'" class="chip mono" :title="batchId">{{ batchId }}</span>
				<span class="chip mono">{{ stats.fps > 0 ? stats.fps.toFixed(0) + ' FPS' : '— FPS' }} · {{ stats.drawCalls }} DC</span>
				<span v-if="stats.degraded" class="chip warn">已自动降级</span>
				<el-button size="small" :loading="twinDataMode === 'agent' ? agentLoading : loading" @click="twinDataMode === 'agent' ? loadAgentRun() : reload()">重新加载</el-button>
			</div>
		</header>

		<div v-if="twinDataMode === 'daily' && source === 'demo' && showHud" class="demo-alert">
			<span>示例数据 · 接口不可用（{{ apiError || '未知原因' }}）</span>
			<button type="button" @click="showDemoDetails = !showDemoDetails">{{ showDemoDetails ? '收起' : '数据说明' }}</button>
			<p v-if="showDemoDetails">/api/eval/{{ batchId }}/series 不可用。当前指标由离线作物模型生成，非实测、非后端推演结果；三维模型与此数据集联动，额外设备开关仅本地演示。</p>
		</div>

		<!-- ─────────────── 左栏 ─────────────── -->
		<aside v-show="showHud && twinDataMode === 'daily'" class="hud hud-left">
			<section class="panel">
				<div class="panel-hd">
					<span class="ttl">环境</span>
					<span class="sub">ENVIRONMENT</span>
					<span class="risk" :class="'risk-' + riskKey">{{ riskText }}</span>
				</div>
				<div class="grid-2">
					<div v-for="m in envMetrics" :key="m.k" class="metric">
						<span class="lb">{{ m.k }}</span>
						<span class="vl mono">{{ m.v }}<em>{{ m.u }}</em></span>
					</div>
				</div>
			</section>

			<section class="panel">
				<div class="panel-hd">
					<span class="ttl">作物</span>
					<span class="sub">CROP</span>
					<span class="stage">{{ stageText }}</span>
				</div>
				<div class="grid-2">
					<div v-for="m in cropMetrics" :key="m.k" class="metric">
						<span class="lb">{{ m.k }}</span>
						<span class="vl mono">{{ m.v }}<em>{{ m.u }}</em></span>
					</div>
				</div>
				<div class="organ-bars">
					<div v-for="o in organBars" :key="o.k" class="obar">
						<span class="lb">{{ o.k }}</span>
						<div class="track"><i :style="{ width: o.pct + '%', background: o.c }"></i></div>
						<span class="vl mono">{{ o.v }}<em>g</em></span>
					</div>
				</div>
			</section>

			<section class="panel panel-chart">
				<div class="panel-hd">
					<span class="ttl">全季趋势</span>
					<span class="sub">LAI &amp; 果实干重</span>
					<span class="cur mono">第 {{ currentIndex }} 天</span>
				</div>
				<div ref="chartRef" class="chart"></div>
			</section>
		</aside>

		<!-- ─────────────── 右栏 ─────────────── -->
		<aside v-show="showHud && twinDataMode === 'daily'" class="hud hud-right">
			<section class="panel">
				<div class="panel-hd">
					<span class="ttl">土壤与病虫害</span>
					<span class="sub">SOIL &amp; PEST</span>
				</div>
				<div class="grid-2">
					<div class="metric">
						<span class="lb">养分因子</span>
						<span class="vl mono">{{ fmt(currentDay?.nutrientFactor, 2) }}</span>
					</div>
					<div class="metric">
						<span class="lb">虫口密度</span>
						<span class="vl mono">{{ fmt(currentDay?.pestPopulation, 1) }}<em>头</em></span>
					</div>
				</div>
				<div class="sev-list">
					<div v-for="d in diseaseBars" :key="d.code" class="sev-row">
						<span class="lb">{{ d.name }}</span>
						<div class="track sev"><i :style="{ width: d.width + '%', background: d.c }"></i></div>
						<span class="vl mono" :class="{ hot: d.pct >= 50 }">{{ d.pct.toFixed(2) }}%</span>
					</div>
				</div>
			</section>

			<section class="panel">
				<div class="panel-hd">
					<span class="ttl">设备</span>
					<span class="sub">ACTUATORS</span>
					<span class="cur mono">{{ onCount }}/{{ devices.length }} 运行</span>
				</div>
				<div class="dev-grid">
					<div v-for="d in devices" :key="d.code" class="dev" :class="{ on: d.on }">
						<i class="led"></i>
						<span class="nm">{{ d.name }}</span>
						<span class="st">{{ d.on ? 'ON' : 'OFF' }}</span>
					</div>
				</div>
			</section>

			<section v-if="twinDataMode === 'daily'" class="panel">
				<div class="panel-hd"><span class="ttl">模型设备演示</span><span class="sub">LOCAL · SIMULATED</span></div>
				<p class="eco-note">以下开关仅驱动三维模型，不代表日级评测或真实设备命令。</p>
				<div class="model-switch"><span>HAF 环流风机</span><el-switch v-model="demoActuators.circulationFan" size="small" /></div>
				<div class="model-switch"><span>端墙强制排风</span><el-switch v-model="demoActuators.exhaustFan" size="small" @change="onExhaustToggle" /></div>
				<div class="model-switch"><span>湿帘循环水</span><el-switch v-model="demoActuators.coolingPad" size="small" @change="onCoolingToggle" /></div>
				<div class="model-switch"><span>屋面通风窗</span><el-switch v-model="demoActuators.roofVent" size="small" /></div>
			</section>

			<section class="panel">
				<div class="panel-hd">
					<span class="ttl">经济</span>
					<span class="sub">ECONOMICS</span>
					<span class="cur mono">元</span>
				</div>
				<div class="eco">
					<div class="eco-row"><span>本日成本<em>差值</em></span><b class="mono">{{ fmt(todayCost, 2) }}</b></div>
					<div class="eco-row"><span>本日产值<em>差值</em></span><b class="mono">{{ fmt(todayRevenue, 2) }}</b></div>
					<div class="eco-row"><span>累计成本</span><b class="mono">{{ fmt(cumCost, 1) }}</b></div>
					<div class="eco-row"><span>累计产值</span><b class="mono">{{ fmt(cumRevenue, 1) }}</b></div>
					<div class="eco-row big" :class="{ neg: (currentDay?.profitYuan ?? 0) < 0 }">
						<span>累计利润</span><b class="mono">{{ fmt(currentDay?.profitYuan, 1) }}</b>
					</div>
					<div class="eco-row"><span>耗水 / 耗电</span><b class="mono">{{ fmt(cumWater, 2) }} m³ / {{ fmt(cumEnergy, 1) }} kWh</b></div>
					<p class="eco-note">成本 / 产值 / 利润均为后端返回的累计值；本日行由相邻两天累计值相减得到。</p>
				</div>
			</section>

			<section class="panel">
				<div class="panel-hd">
					<span class="ttl">性能</span>
					<span class="sub">RENDER</span>
					<span class="perf-tag" :class="stats.degraded ? 'bad' : 'good'">{{ stats.degraded ? '已自动降级' : '完整画质' }}</span>
				</div>
				<div class="grid-2">
					<div class="metric">
						<span class="lb">实测帧率</span>
						<span class="vl mono" :class="{ hot: stats.fps > 0 && stats.fps < 45 }">
							{{ stats.fps > 0 ? stats.fps.toFixed(1) : '—' }}<em>fps</em>
						</span>
					</div>
					<div class="metric">
						<span class="lb">渲染画质</span>
						<span class="vl mono">{{ { high: '高', medium: '中', low: '低' }[stats.quality] }}</span>
					</div>
					<div class="metric">
						<span class="lb">Draw calls</span>
						<span class="vl mono">{{ stats.drawCalls }}<em>/帧</em></span>
					</div>
					<div class="metric">
						<span class="lb">三角面</span>
						<span class="vl mono">{{ (stats.triangles / 1000).toFixed(0) }}<em>k/帧</em></span>
					</div>
					<div class="metric">
						<span class="lb">渲染分辨率</span>
						<span class="vl mono">{{ stats.width }}×{{ stats.height }}</span>
					</div>
					<div class="metric">
						<span class="lb">像素比</span>
						<span class="vl mono">×{{ stats.pixelRatio.toFixed(2) }}</span>
					</div>
				</div>
				<div class="quality-choice">
					<span>画质档位</span>
					<el-select v-model="qualityPreference" size="small" @change="updateQuality">
						<el-option label="自动" value="auto" /><el-option label="高" value="high" />
						<el-option label="中" value="medium" /><el-option label="低" value="low" />
					</el-select>
				</div>
				<p class="eco-note">
					自动档持续 2.5s 低于 38fps 时降为中档；手动档保留所选画质。设备不随档位隐藏。
				</p>
			</section>

			<section class="panel">
				<div class="panel-hd">
					<span class="ttl">策略终局对比</span>
					<span class="sub">POST /api/eval/runs</span>
				</div>
				<div v-if="!outcomes" class="runs-hint">
					<p>{{ source === 'demo' ? '示例数据已内置 4 套策略终局指标。' : '接口指标矩阵尚未加载。' }}</p>
					<el-button size="small" :loading="runsLoading" @click="loadRuns">加载终局指标矩阵</el-button>
				</div>
				<div v-else class="runs">
					<div class="runs-hd">
						<span>策略</span><span>利润元</span><span>耗水m³</span><span>高温min</span><span>违规</span>
					</div>
					<div v-for="r in outcomeRows" :key="r.code" class="runs-row" :class="{ best: r.best }">
						<span>{{ r.label }}</span>
						<span class="mono" :class="{ neg: r.profit < 0 }">{{ fmt(r.profit, 0) }}</span>
						<span class="mono">{{ fmt(r.water, 2) }}</span>
						<span class="mono">{{ r.hot }}</span>
						<span class="mono">{{ r.violations }}</span>
					</div>
					<p class="runs-note">{{ outcomes.batchId }} · seed {{ seed }} · {{ days }}d · {{ outcomesSource === 'demo' ? '示例数据' : '接口数据' }}</p>
				</div>
			</section>
		</aside>

		<aside v-if="showHud && twinDataMode === 'agent'" class="hud hud-left agent-hud">
			<section class="panel">
				<div class="panel-hd"><span class="ttl">运行快照</span><span class="sub">15 MIN · SAVED</span></div>
				<p class="eco-note">{{ agentRun?.runCode || '尚无运行' }} · {{ agentFrame?.environment.simulatedAt || '—' }}</p>
				<p v-if="agentFrame?.modelVersion" class="eco-note">模型 {{ agentFrame.modelVersion }} · 规则推演，未经过现场标定</p>
				<p class="eco-note">{{ agentFrame?.recorded ? '设备和库存取自该步保存的快照；番茄植株形态仅作固定示意' : '无完整设备快照：执行器统一待机，作物仅作固定示意；环境数值仅在有历史快照时显示' }}。所有指标为模拟值。</p>
				<p v-if="agentError" class="agent-error">{{ agentError }}</p>
				<div class="agent-actions">
					<el-button v-if="!agentRun" size="small" type="primary" :loading="agentLoading" @click="createTwinRun">创建仿真运行</el-button>
					<template v-else>
						<el-button v-if="agentRun.status !== 'RUNNING'" size="small" type="primary" :disabled="agentRun.status === 'COMPLETED'" :loading="agentLoading" @click="operateTwinRun('start')">自动运行</el-button>
						<el-button v-else size="small" type="warning" :loading="agentLoading" @click="operateTwinRun('pause')">暂停</el-button>
						<el-button size="small" :loading="agentLoading" :disabled="agentRun.status === 'COMPLETED' || agentRun.status === 'RUNNING'" @click="advanceTwinRun">推进15分钟</el-button>
						<el-button size="small" :loading="agentLoading" @click="operateTwinRun('reset')">重置</el-button>
						<el-button size="small" :loading="agentLoading" @click="loadAgentRun">同步快照</el-button>
						<el-button v-if="agentFrames.some((item) => !item.recorded)" size="small" :loading="agentLoading" @click="createTwinRun">新建兼容运行</el-button>
					</template>
				</div>
			</section>
			<section v-if="agentFrame" class="panel">
				<div class="panel-hd"><span class="ttl">环境测点</span><span class="sub">SIMULATED</span><span class="risk" :class="'risk-' + agentFrame.environment.riskLevel.toLowerCase()">{{ agentFrame.environment.riskLevel }}</span></div>
				<div class="agent-row"><span>室内温度</span><b>{{ fmt(agentFrame.environment.temperatureC, 1) }} ℃</b></div>
				<div class="agent-row"><span>空气湿度</span><b>{{ fmt(agentFrame.environment.airHumidityPct, 1) }} %</b></div>
				<div class="agent-row"><span>根区含水率</span><b>{{ fmt(agentFrame.environment.soilMoisturePct, 1) }} %</b></div>
				<div class="agent-row"><span>冠层 CO₂</span><b>{{ fmt(agentFrame.environment.co2Ppm, 0) }} ppm</b></div>
				<div class="agent-row"><span>PPFD</span><b>{{ fmt(agentFrame.environment.lightPpfd, 0) }} μmol/m²/s</b></div>
				<div class="agent-row"><span>病害环境压力</span><b>{{ fmt(agentFrame.environment.diseasePressure, 1) }} %</b></div>
				<template v-if="agentFrame.recorded">
					<div class="agent-row"><span>室外温度（推导）</span><b>{{ fmt(agentFrame.sensorReadings?.SENSOR_OUTDOOR, 1) }} ℃</b></div>
					<div class="agent-row"><span>滴灌流量（推导）</span><b>{{ fmt(agentFrame.sensorReadings?.SENSOR_FLOW, 1) }} L/min</b></div>
					<p class="eco-note">测点由保存状态和场景假设计算，不是传感器采集。</p>
				</template>
			</section>
			<section v-if="agentFrame?.recorded" class="panel">
				<div class="panel-hd"><span class="ttl">虚拟库存</span><span class="sub">PER RUN</span></div>
				<div v-for="item in agentFrame.resources" :key="item.code" class="agent-row"><span>{{ item.name }}</span><b>{{ fmt(item.value, 3) }} {{ item.unit }}<small> / 已用 {{ fmt((item.openingQuantity ?? item.value) - item.value, 3) }}</small></b></div>
			</section>
			<section v-if="agentFrame?.consumption?.length" class="panel">
				<div class="panel-hd"><span class="ttl">本步资源用量</span><span class="sub">15 MIN · LEDGER</span></div>
				<div v-for="(item, index) in agentFrame.consumption" :key="`${item.deviceCode}-${item.resourceCode}-${index}`" class="agent-row"><span>{{ agentFrame.devices.find((device) => device.code === item.deviceCode)?.name || item.deviceCode }} · {{ item.resourceCode }}</span><b>{{ fmt(item.quantity, 3) }} {{ item.unit }}</b></div>
			</section>
		</aside>
		<aside v-if="showHud && twinDataMode === 'agent' && agentFrame?.recorded" class="hud hud-right agent-hud">
			<section class="panel">
				<div class="panel-hd"><span class="ttl">执行设备</span><span class="sub">ACTUAL STATE</span></div>
				<div v-for="item in agentFrame.devices" :key="item.code" class="agent-row"><span>{{ item.name }} <small>{{ item.controlMode }}</small></span><b :class="item.actualState === 'ON' ? 'agent-on' : ''">{{ item.actualState }}</b></div>
				<p class="eco-note">湿帘需要排风；对外换气期间禁止 CO₂ 补气。设备状态仅是保存的仿真动作。</p>
			</section>
		</aside>

		<!-- ─────────────── 相机预设 ─────────────── -->
		<div class="cam-bar" :class="{ 'is-immersive': !showHud }">
			<el-button-group>
				<el-button size="small" :type="twinDataMode === 'agent' ? 'primary' : ''" @click="selectTwinMode('agent')">15分钟仿真</el-button>
				<el-button size="small" :type="twinDataMode === 'daily' ? 'primary' : ''" @click="selectTwinMode('daily')">日级评测</el-button>
			</el-button-group>
			<el-button size="small" @click="toggleHud">{{ showHud ? '隐藏数据面板' : '显示数据面板' }}</el-button>
			<el-button size="small" :type="showEquipmentList ? 'success' : ''" @click="showEquipmentList = !showEquipmentList">设备目录</el-button>
			<el-button size="small" :type="navigationMode === 'fly' ? 'success' : 'primary'" @click="toggleNavigationMode">
				{{ navigationMode === 'fly' ? '退出自由漫游' : '进入自由漫游' }}
			</el-button>
			<span v-if="navigationMode === 'fly'" class="flight-guide">WASD 移动 · Q/E 升降 · Shift 加速 · 鼠标转向 · 未锁定时按住拖动 · Esc 退出锁定</span>
			<el-select v-model="shellMode" size="small" class="shell-select" @change="updateShellMode">
				<el-option label="完整薄膜" value="solid" /><el-option label="透视薄膜" value="translucent" /><el-option label="结构剖切" value="cutaway" />
			</el-select>
			<el-button-group>
				<el-button v-for="p in presets" :key="p.v" size="small" :type="preset === p.v ? 'primary' : ''" @click="setPreset(p.v)">
					{{ p.l }}
				</el-button>
			</el-button-group>
			<el-button size="small" :type="autoRotate ? 'primary' : ''" :disabled="navigationMode === 'fly'" @click="toggleAutoRotate">自动旋转</el-button>
			<el-button size="small" :type="diurnal ? 'primary' : ''" :disabled="twinDataMode === 'agent'" @click="diurnal = !diurnal">昼夜循环</el-button>
			<span class="clock mono">{{ clockText }}</span>
		</div>
		<div v-if="showEquipmentList" class="equipment-directory">
			<div class="directory-heading"><strong>设备与测点</strong><span>点击定位 · 可继续自由漫游</span></div>
			<button v-for="item in equipmentOptions" :key="item.code" type="button" @click="inspectEquipment(item.code)">
				<span>{{ item.kind === 'sensor' ? '◇' : '●' }} {{ item.name }}</span><small>{{ item.zone }}</small>
			</button>
		</div>
		<div v-if="navigationMode === 'fly'" class="flight-reticle"></div>
		<div v-if="inspected" class="inspection-panel">
			<div class="inspection-top"><span>{{ inspected.kind === 'sensor' ? '感知节点' : '执行设备' }} · SIMULATED</span><button type="button" @click="inspected = null">×</button></div>
			<strong>{{ inspected.name }}</strong>
			<span class="inspection-zone">{{ inspected.zone }} · {{ inspected.code }}</span>
			<p>{{ inspected.description }}</p>
			<b v-if="inspected.kind === 'sensor'">{{ fmt(inspected.value, 1) }} {{ inspected.unit }}</b>
			<b v-else :class="{ active: inspected.active }">{{ inspected.active ? '模型运行中' : '模型待机' }}</b>
			<small>{{ twinDataMode === 'agent' ? '保存快照驱动的仿真状态 · 非实物遥测' : '本地模型状态 · 非实物遥测/控制命令' }}</small>
		</div>

		<!-- ─────────────── 播放条 ─────────────── -->
		<footer v-show="showHud && twinDataMode === 'daily'" class="playbar">
			<el-button class="pp" :type="playing ? 'warning' : 'primary'" circle @click="togglePlay">
				{{ playing ? '❚❚' : '▶' }}
			</el-button>

			<el-select v-model="strategy" class="strategy" size="default" @change="onStrategyChange">
				<el-option v-for="s in strategyOptions" :key="s.v" :label="s.l" :value="s.v" />
			</el-select>

			<div class="scrub">
				<div class="scrub-top">
					<span class="mono day">第 {{ currentIndex }} / {{ maxDay }} 天</span>
					<span class="mono date">{{ currentDay?.simulatedAt || '—' }}</span>
					<span class="mono stage-chip">{{ stageText }}</span>
				</div>
				<el-slider
					v-model="sliderPos"
					:min="1"
					:max="Math.max(2, maxDay)"
					:step="1"
					:show-tooltip="false"
					:disabled="maxDay < 2"
					@input="onScrub"
				/>
			</div>

			<div class="speeds">
				<el-button v-for="s in speedOptions" :key="s" size="small" :type="speed === s ? 'primary' : ''" @click="speed = s">
					{{ s }}×
				</el-button>
			</div>
		</footer>
		<footer v-if="showHud && twinDataMode === 'agent'" class="playbar agent-playbar">
			<el-button class="pp" :type="agentPlaying ? 'warning' : 'primary'" circle :disabled="agentFrames.length < 2" @click="toggleAgentPlayback">{{ agentPlaying ? '❚❚' : '▶' }}</el-button>
			<span class="chip ok">历史快照回放</span>
			<div class="scrub">
				<div class="scrub-top"><span class="mono day">第 {{ agentFrame?.stepNo ?? 0 }} / {{ Math.max(0, agentFrames.length - 1) }} 步</span><span class="mono date">{{ agentFrame?.environment.simulatedAt || '—' }}</span></div>
				<el-slider v-model="agentIndex" :min="0" :max="Math.max(1, agentFrames.length - 1)" :step="1" :disabled="agentFrames.length < 2" :show-tooltip="false" @input="agentPlaying = false" />
			</div>
		</footer>

		<div v-if="loading" class="boot">
			<div class="boot-inner">
				<i class="spin"></i>
				<span>正在加载推演序列…</span>
			</div>
		</div>

		<div v-if="webglError" class="gl-error">
			<div class="gl-error-inner">
				<h3>3D 场景初始化失败</h3>
				<p>{{ webglError }}</p>
				<p>HUD 数据不受影响，仍可正常查看推演指标；请确认浏览器已启用 WebGL2。</p>
			</div>
		</div>
	</div>
</template>

<script setup lang="ts" name="digitalTwin">
/**
 * 番茄温室数字孪生控制台
 *
 * 数据来源：GET /api/eval/{batchId}/series。后端返回项目的 Result 信封
 * （{code:"0", msg:"成功", data:{...}}），因此必须取 resp.data.data；
 * 取不到时回退到内置「示例数据」，并把**具体失败原因**显示在顶部告警里。
 *
 * 数值口径（与后端保持一致，这三条弄错界面会静默失真）：
 *   · costYuan / revenueYuan / profitYuan 都是【累计值】；"本日"发生额由相邻两天求差得到，并标注为差值
 *   · severity.* 是 0~100 的百分比量纲（不是 0~1）
 *   · stage 只有 SEEDLING / FLOWERING / FRUIT_SET / FRUIT_GROWTH / MATURITY 五个值
 *   · HUD 上的所有指标都直接取「当前整天」的推演值，不做估算
 *   · 3D 场景使用相邻两天之间的线性插值，保证植株不会跳变
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import * as echarts from 'echarts';
import { ElMessageBox } from 'element-plus';
import { GreenhouseTwin, type CameraPreset, type InspectionInfo, type TwinStats } from './scene';
import {
	createAgentRun,
	getActiveAgentRun,
	getAgentTwinFrames,
	pauseAgentRun,
	resetAgentRun,
	startAgentRun,
	stepAgentRun,
	type AgentRun,
	type AgentTwinFrame,
} from '/@/api/agent';
import {
	DEFAULT_BATCH_ID,
	DEFAULT_DAYS,
	DEFAULT_SEED,
	DEVICE_LABELS,
	DEVICE_ORDER,
	DISEASE_LABELS,
	DISEASE_ORDER,
	RISK_LABELS,
	STAGE_LABELS,
	STRATEGY_LABELS,
	STRATEGY_ORDER,
	buildDemoOutcomes,
	dayOfYearFromSimulatedAt,
	fetchEvalSeries,
	hourFromSimulatedAt,
	interpolateDayPoint,
	isFlatDailyTimestamp,
	runEval,
	type EvalDayPoint,
	type EvalRunsResponse,
} from '/@/api/eval';

/* ------------------------------ 常量 ------------------------------ */

const BASE_DAYS_PER_SEC = 1.15; // 1× 播放速度：天/秒
const route = useRoute();
const DIURNAL_SECONDS_PER_DAY = 240;
/** 昼夜时钟累积的模拟小时数（非响应式，避免每帧触发重渲染） */
let diurnalHours = 6;
const DISEASE_COLORS: Record<string, string> = {
	BOTRYTIS: '#a7b4a4',
	LATE_BLIGHT: '#7f9149',
	POWDERY_MILDEW: '#e9ede0',
	LEAF_MOLD: '#d0b24e',
};

/* ------------------------------ 状态 ------------------------------ */

const shellRef = ref<HTMLElement>();
const canvasRef = ref<HTMLCanvasElement>();
const chartRef = ref<HTMLElement>();

const shellHeight = ref(720);
const loading = ref(false);
const webglError = ref('');
const source = ref<'api' | 'demo'>('demo');
const twinDataMode = ref<'agent' | 'daily'>('daily');
const agentRun = ref<AgentRun | null>(null);
const agentFrames = ref<AgentTwinFrame[]>([]);
const agentIndex = ref(0);
const agentLoading = ref(false);
const agentError = ref('');
const agentPlaying = ref(false);
let agentPlaybackTime = 0;
let agentSyncTimer: ReturnType<typeof setInterval> | null = null;
const agentFrame = computed(() => agentFrames.value[agentIndex.value] ?? null);
const apiError = ref('');
const batchId = ref(DEFAULT_BATCH_ID);
const seed = DEFAULT_SEED;
const days = DEFAULT_DAYS;

const seriesMap = ref<Record<string, EvalDayPoint[]>>({});
const availableStrategies = ref<string[]>([...STRATEGY_ORDER]);
const strategy = ref<string>('P3_AGENT');

/**
 * 播放位置分三层，避免 60fps 触发整页重渲染：
 *   dayAnim    —— 非响应式浮点，直接驱动 3D 插值（每帧更新）
 *   dayIndex   —— 响应式整数天，驱动 HUD / 图表标线（仅在天数变化时更新）
 *   sliderPos  —— 响应式滑块位置，节流刷新（约 8Hz）
 */
let dayAnim = 1;
const dayIndex = ref(1);
const sliderPos = ref(1);

const playing = ref(false);
const speed = ref(1);
const diurnal = ref(false);
const preset = ref<CameraPreset>('overview');
const autoRotate = ref(false);
const navigationMode = ref<'orbit' | 'fly'>('orbit');
const shellMode = ref<'solid' | 'translucent' | 'cutaway'>('translucent');
const qualityPreference = ref<'auto' | TwinStats['quality']>('high');
const showHud = ref(false);
const showDemoDetails = ref(false);
const showEquipmentList = ref(false);
const equipmentOptions = ref<Array<{ code: string; name: string; zone: string; kind: 'sensor' | 'actuator' }>>([]);
const inspected = ref<InspectionInfo | null>(null);
const demoActuators = reactive({ circulationFan: true, exhaustFan: false, coolingPad: false, roofVent: false });
const clockHour = ref(12);

const outcomes = ref<EvalRunsResponse | null>(null);
const outcomesSource = ref<'api' | 'demo'>('demo');
const runsLoading = ref(false);

const diseaseMarkers = ref<Array<{ code: string; name: string; pct: number; x: number; y: number; visible: boolean }>>([]);
const stats = ref<TwinStats>({ fps: 0, drawCalls: 0, triangles: 0, quality: 'high', degraded: false, pixelRatio: 1, width: 0, height: 0 });

const presets: Array<{ v: CameraPreset; l: string }> = [
	{ v: 'overview', l: '全景' },
	{ v: 'closeup', l: '近景' },
	{ v: 'top', l: '俯视' },
	{ v: 'fruit', l: '跟随果实' },
];
const speedOptions = [1, 4, 12];

/* ---------------------------- 派生数据 ---------------------------- */

const points = computed<EvalDayPoint[]>(() => seriesMap.value[strategy.value] ?? []);
const maxDay = computed(() => points.value.length);
const currentIndex = computed(() => {
	if (!maxDay.value) return 0;
	return Math.min(maxDay.value, Math.max(1, Math.round(dayIndex.value)));
});
const currentDay = computed<EvalDayPoint | null>(() => points.value[currentIndex.value - 1] ?? null);

const peakFruitWeight = computed(() => points.value.reduce((m, p) => Math.max(m, p.singleFruitWeightG), 1));

/**
 * 经济口径：costYuan / revenueYuan / profitYuan 在后端都是**累计值**，直接使用，
 * 绝不再做前缀和（否则会二次累加）。"本日"发生额 = 当日累计 − 前一日累计。
 */
const todayCost = computed(() => {
	const pts = points.value;
	const i = currentIndex.value - 1;
	if (i < 0 || !pts[i]) return 0;
	return i === 0 ? pts[0].costYuan : pts[i].costYuan - pts[i - 1].costYuan;
});
const todayRevenue = computed(() => {
	const pts = points.value;
	const i = currentIndex.value - 1;
	if (i < 0 || !pts[i]) return 0;
	return i === 0 ? pts[0].revenueYuan : pts[i].revenueYuan - pts[i - 1].revenueYuan;
});
const cumCost = computed(() => currentDay.value?.costYuan ?? 0);
const cumRevenue = computed(() => currentDay.value?.revenueYuan ?? 0);
const cumWater = computed(() => {
	const pts = points.value;
	const n = Math.min(pts.length, currentIndex.value);
	let w = 0;
	for (let i = 0; i < n; i++) w += pts[i].waterUsedM3;
	return w;
});
const cumEnergy = computed(() => {
	const pts = points.value;
	const n = Math.min(pts.length, currentIndex.value);
	let e = 0;
	for (let i = 0; i < n; i++) e += pts[i].energyKWh;
	return e;
});

const fmt = (v: number | undefined | null, digits = 1): string => {
	if (v === undefined || v === null || !Number.isFinite(v)) return '—';
	return v.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
};

const envMetrics = computed(() => {
	const d = currentDay.value;
	return [
		{ k: '温度', v: fmt(d?.temperatureC, 1), u: '℃' },
		{ k: '空气湿度', v: fmt(d?.airHumidityPct, 1), u: '%' },
		{ k: 'CO₂', v: fmt(d?.co2Ppm, 0), u: 'ppm' },
		{ k: '光照 PPFD', v: fmt(d?.lightPpfd, 0), u: 'μmol' },
		{ k: '土壤水分', v: fmt(d?.soilMoisturePct, 1), u: '%' },
	];
});

const cropMetrics = computed(() => {
	const d = currentDay.value;
	return [
		{ k: '积温 GDD', v: fmt(d?.gdd, 1), u: '℃·d' },
		{ k: '叶面积指数', v: fmt(d?.lai, 3), u: '' },
		{ k: '株高', v: fmt(d?.plantHeightCm, 1), u: 'cm' },
		{ k: '坐果率', v: fmt((d?.fruitSetRate ?? 0) * 100, 1), u: '%' },
		{ k: '果数 / 株', v: fmt(d?.fruitCount, 0), u: '个' },
		{ k: '单果重', v: fmt(d?.singleFruitWeightG, 1), u: 'g' },
	];
});

const organBars = computed(() => {
	const d = currentDay.value;
	const items = [
		{ k: '叶', v: d?.wLeaf ?? 0, c: 'linear-gradient(90deg,#3f8f4a,#79d17f)' },
		{ k: '茎', v: d?.wStem ?? 0, c: 'linear-gradient(90deg,#8a6a4f,#c3a68c)' },
		{ k: '根', v: d?.wRoot ?? 0, c: 'linear-gradient(90deg,#7a6250,#a99280)' },
		{ k: '果', v: d?.wFruit ?? 0, c: 'linear-gradient(90deg,#d0342c,#ff9060)' },
	];
	const max = Math.max(0.001, ...items.map((i) => i.v));
	return items.map((i) => ({ ...i, pct: Math.round((i.v / max) * 100), v: fmt(i.v, 1) }));
});

const diseaseBars = computed(() => {
	const d = currentDay.value;
	return DISEASE_ORDER.map((code) => {
		// 后端 severity 已是 0~100 的百分比，直接用；条形宽度做 0~100 截断保护
		const raw = Math.max(0, d?.severity?.[code] ?? 0);
		return { code, name: DISEASE_LABELS[code] || code, pct: raw, width: Math.min(100, raw), c: DISEASE_COLORS[code] };
	});
});

const devices = computed(() => {
	if (twinDataMode.value === 'agent' && agentFrame.value?.recorded) {
		return agentFrame.value.devices.map((item) => ({
			code: item.code,
			name: item.name,
			on: item.actualState === 'ON',
		}));
	}
	const d = currentDay.value;
	return DEVICE_ORDER.map((code) => ({ code, name: DEVICE_LABELS[code] || code, on: Boolean(d?.devices?.[code]) }));
});
const onCount = computed(() => devices.value.filter((d) => d.on).length);

const stageText = computed(() => STAGE_LABELS[currentDay.value?.stage || ''] || currentDay.value?.stage || '—');
const riskKey = computed(() => String(currentDay.value?.riskLevel || 'LOW').toLowerCase());
const riskText = computed(() => RISK_LABELS[currentDay.value?.riskLevel || ''] || currentDay.value?.riskLevel || '—');

const strategyOptions = computed(() => availableStrategies.value.map((v) => ({ v, l: STRATEGY_LABELS[v] || v })));

const outcomeRows = computed(() => {
	const o = outcomes.value?.outcomes;
	if (!o) return [];
	const codes = availableStrategies.value.filter((c) => o[c]);
	if (!codes.length) return [];
	let best = codes[0];
	for (const c of codes) if (o[c].profitYuan > o[best].profitYuan) best = c;
	return codes.map((c) => ({
		code: c,
		label: STRATEGY_LABELS[c] || c,
		profit: o[c].profitYuan,
		water: o[c].waterUsedM3,
		hot: Math.round(o[c].highTemperatureMinutes),
		violations: o[c].constraintViolations,
		best: c === best,
	}));
});

// 序列时间戳的小时是否恒定（恒定说明是「日快照」，此时 3D 采用日内 24h 循环演示）
const flatHours = computed(() => isFlatDailyTimestamp(points.value));

/** 3D 场景的日内时刻。
 *  序列时间戳有意义（逐时）时直接跟随；
 *  时间戳是「日快照」时（本数据集就是这种情况），用一条按真实时间推进的昼夜时钟做演示，
 *  起点锚定在 simulatedAt 的时刻——这样即使暂停在某一天，太阳也仍在移动，不会永远停在清晨。 */
const sceneHourAt = (dayValue: number): number => {
	const pts = points.value;
	if (!pts.length) return 6;
	const idx = Math.min(pts.length, Math.max(1, Math.round(dayValue)));
	const stampHour = hourFromSimulatedAt(pts[idx - 1]?.simulatedAt || '', 6);
	if (!flatHours.value) return stampHour;
	return (stampHour + diurnalHours) % 24;
};

const clockText = computed(() => {
	if (twinDataMode.value === 'agent') {
		const stamp = agentFrame.value?.environment.simulatedAt;
		return stamp ? `${stamp.slice(11, 16)} · 15分钟快照` : '等待仿真运行';
	}
	const h = clockHour.value;
	const hh = Math.floor(h) % 24;
	const mm = Math.floor((h - Math.floor(h)) * 60);
	return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')} ${diurnal.value || !flatHours.value ? '昼夜推演' : '定格'}`;
});

/* ------------------------- 3D 场景 / 播放循环 ------------------------- */

let twin: GreenhouseTwin | null = null;
let shellResizeObserver: ResizeObserver | null = null;
let raf = 0;
let lastT = 0;
let markerTick = 0;
let statsTick = 0;

const pushScene = () => {
	if (!twin) return;
	if (twinDataMode.value === 'agent') {
		const recorded = agentFrame.value?.recorded ? agentFrame.value : null;
		const environment = agentFrame.value?.environment;
		const enabled = (code: string) => recorded?.devices.some((device) => device.code === code && device.actualState === 'ON') ?? false;
		const at = environment?.simulatedAt || '';
		twin.applyState({
			lai: 3, plantHeightCm: 180, fruitCount: 4, singleFruitWeightG: 70,
			fruitSetRate: 0.7, mature: false, ripeness: 0.45,
			lightPpfd: environment?.lightPpfd ?? 650,
			irrigation: enabled('IRRIGATION'), ventilation: enabled('VENTILATION'),
			supplementalLight: enabled('SUPPLEMENTAL_LIGHT'), shade: enabled('SHADE'),
			co2: enabled('CO2_SUPPLY'), circulationFan: enabled('CIRCULATION_FAN'),
			exhaustFan: enabled('EXHAUST_FAN'), coolingPad: enabled('COOLING_PAD'),
			roofVent: enabled('ROOF_VENT'),
			temperatureC: environment?.temperatureC ?? 24, airHumidityPct: environment?.airHumidityPct ?? 70,
			co2Ppm: environment?.co2Ppm ?? 720, soilMoisturePct: environment?.soilMoisturePct ?? 55,
			sensorReadings: recorded?.sensorReadings,
			severity: {}, hour: hourFromSimulatedAt(at, 12), dayOfYear: dayOfYearFromSimulatedAt(at, 264),
		});
		return;
	}
	const pts = points.value;
	if (!pts.length) return;
	const snap = interpolateDayPoint(pts, dayAnim);
	if (!snap) return;
	const peak = peakFruitWeight.value || 1;
	const idx = Math.min(pts.length, Math.max(1, Math.round(dayAnim)));
	twin.applyState({
		lai: snap.lai,
		plantHeightCm: snap.plantHeightCm,
		fruitCount: snap.fruitCount,
		singleFruitWeightG: snap.singleFruitWeightG,
		fruitSetRate: snap.fruitSetRate,
		mature: snap.mature,
		ripeness: Math.max(0, Math.min(1, snap.singleFruitWeightG / peak)),
		lightPpfd: snap.lightPpfd,
		irrigation: snap.devices.IRRIGATION,
		ventilation: snap.devices.VENTILATION,
		supplementalLight: snap.devices.SUPPLEMENTAL_LIGHT,
		shade: snap.devices.SHADE,
		co2: snap.devices.CO2_SUPPLY,
		circulationFan: demoActuators.circulationFan,
		exhaustFan: demoActuators.exhaustFan,
		coolingPad: demoActuators.coolingPad,
		roofVent: demoActuators.roofVent,
		temperatureC: snap.temperatureC,
		airHumidityPct: snap.airHumidityPct,
		co2Ppm: snap.co2Ppm,
		soilMoisturePct: snap.soilMoisturePct,
		severity: { ...snap.severity },
		hour: sceneHourAt(dayAnim),
		dayOfYear: dayOfYearFromSimulatedAt(pts[idx - 1]?.simulatedAt || '', 264),
	});
};

const refreshMarkers = () => {
	if (!twin) return;
	if (twinDataMode.value === 'agent') {
		diseaseMarkers.value = [];
		return;
	}
	const d = currentDay.value;
	diseaseMarkers.value = twin.getDiseaseMarkers().map((m) => ({
		code: m.code,
		name: DISEASE_LABELS[m.code] || m.code,
		// severity 已是 0~100 的百分比，直接显示
		pct: Math.max(0, d?.severity?.[m.code] ?? 0),
		x: m.x,
		y: m.y,
		visible: m.visible,
	}));
};

const frame = (now: number) => {
	raf = requestAnimationFrame(frame);
	const dt = lastT ? Math.min(0.05, (now - lastT) / 1000) : 0.016;
	lastT = now;

	// 昼夜演示时钟：与播放状态无关地持续走时
	if (twinDataMode.value === 'daily' && diurnal.value && flatHours.value) diurnalHours = (diurnalHours + dt * (24 / DIURNAL_SECONDS_PER_DAY)) % 24;

	if (twinDataMode.value === 'agent' && agentPlaying.value && agentFrames.value.length > 1) {
		agentPlaybackTime += dt;
		if (agentPlaybackTime >= 0.65) {
			agentPlaybackTime = 0;
			agentIndex.value = Math.min(agentFrames.value.length - 1, agentIndex.value + 1);
			if (agentIndex.value === agentFrames.value.length - 1) agentPlaying.value = false;
		}
	}

	if (twinDataMode.value === 'daily' && playing.value && maxDay.value > 1) {
		const next = dayAnim + dt * BASE_DAYS_PER_SEC * speed.value;
		if (next >= maxDay.value) {
			dayAnim = maxDay.value;
			playing.value = false;
		} else {
			dayAnim = next;
		}
		const idx = Math.round(dayAnim);
		if (idx !== dayIndex.value) dayIndex.value = idx;
	}

	// 3D 场景每帧接收插值后的状态（丝滑过渡，不跳变）
	pushScene();

	// HUD 侧节流刷新，避免整页 60fps 重渲染
	markerTick += dt;
	if (markerTick >= 0.1) {
		markerTick = 0;
		refreshMarkers();
		sliderPos.value = dayAnim;
		clockHour.value = twinDataMode.value === 'agent'
			? hourFromSimulatedAt(agentFrame.value?.environment.simulatedAt || '', 12) : sceneHourAt(dayAnim);
	}
	statsTick += dt;
	if (statsTick >= 0.5) {
		statsTick = 0;
		if (twin) {
			stats.value = twin.getStats();
			navigationMode.value = twin.getNavigationMode();
			if (inspected.value) inspected.value = twin.getInspectionInfo(inspected.value.code);
		}
	}
};

/* ------------------------------ 图表 ------------------------------ */

let chart: echarts.ECharts | null = null;

/** 当前天标记线（单独抽出，便于按天做轻量增量更新） */
const buildMarkLine = () => ({
	silent: true,
	symbol: 'none',
	data: [{ xAxis: Math.max(0, currentIndex.value - 1) }],
	lineStyle: { color: '#ffd166', width: 1.4, type: 'solid' as const },
	label: {
		show: true,
		formatter: `第${currentIndex.value}天`,
		color: '#ffd166',
		fontSize: 9,
		position: 'insideEndTop' as const,
	},
});

const buildChartOption = (): echarts.EChartsOption => {
	const pts = points.value;
	const lai = pts.map((p) => Number(p.lai.toFixed(3)));
	const wFruit = pts.map((p) => Number(p.wFruit.toFixed(2)));
	return {
		backgroundColor: 'transparent',
		animationDuration: 320,
		grid: { left: 40, right: 44, top: 24, bottom: 20 },
		tooltip: {
			trigger: 'axis',
			backgroundColor: 'rgba(8,14,21,0.94)',
			borderColor: 'rgba(120,200,255,0.32)',
			borderWidth: 1,
			textStyle: { color: '#dce9f4', fontSize: 11 },
			axisPointer: { type: 'line', lineStyle: { color: 'rgba(255,209,102,0.7)' } },
		},
		legend: {
			data: ['LAI', '果实干重'],
			top: 0,
			right: 0,
			itemWidth: 10,
			itemHeight: 6,
			textStyle: { color: '#93a9bb', fontSize: 10 },
		},
		xAxis: {
			type: 'category',
			data: pts.map((p) => p.day),
			boundaryGap: false,
			axisLine: { lineStyle: { color: 'rgba(140,170,190,0.28)' } },
			axisTick: { show: false },
			axisLabel: { color: '#7f95a7', fontSize: 9, interval: Math.max(1, Math.floor(pts.length / 6)) },
		},
		yAxis: [
			{
				type: 'value',
				name: 'LAI',
				nameTextStyle: { color: '#5fe0b0', fontSize: 9, padding: [0, 0, 0, -24] },
				axisLabel: { color: '#7f95a7', fontSize: 9 },
				splitLine: { lineStyle: { color: 'rgba(120,150,170,0.1)' } },
			},
			{
				type: 'value',
				name: 'g',
				nameTextStyle: { color: '#ff9f45', fontSize: 9, padding: [0, -18, 0, 0] },
				axisLabel: { color: '#7f95a7', fontSize: 9 },
				splitLine: { show: false },
			},
		],
		series: [
			{
				name: 'LAI',
				type: 'line',
				smooth: true,
				showSymbol: false,
				data: lai,
				lineStyle: { width: 2, color: '#5fe0b0' },
				areaStyle: {
					color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
						{ offset: 0, color: 'rgba(95,224,176,0.34)' },
						{ offset: 1, color: 'rgba(95,224,176,0)' },
					]),
				},
				markLine: buildMarkLine(),
			},
			{
				name: '果实干重',
				type: 'line',
				smooth: true,
				showSymbol: false,
				yAxisIndex: 1,
				data: wFruit,
				lineStyle: { width: 2, color: '#ff9f45' },
			},
		],
	};
};

const updateChart = () => {
	if (!chart) return;
	chart.setOption(buildChartOption(), true);
};

const toggleHud = async () => {
	showHud.value = !showHud.value;
	if (!showHud.value) return;
	await nextTick();
	if (twinDataMode.value === 'daily') {
		if (chartRef.value && !chart) chart = echarts.init(chartRef.value, undefined, { renderer: 'canvas' });
		chart?.resize();
		updateChart();
	}
};

/* ------------------------------ 数据 ------------------------------ */

const loadData = async () => {
	loading.value = true;
	try {
		const res = await fetchEvalSeries(batchId.value, days);
		source.value = res.source;
		apiError.value = res.error || '';
		seriesMap.value = res.data.series || {};
		const list = res.data.strategies?.length ? res.data.strategies : [...STRATEGY_ORDER];
		availableStrategies.value = list;
		if (!list.includes(strategy.value)) strategy.value = list[0] || 'P3_AGENT';
		if (res.source === 'demo') {
			const demo = buildDemoOutcomes(batchId.value, days);
			outcomes.value = demo;
			outcomesSource.value = 'demo';
		} else {
			outcomes.value = null;
		}
		setDay(Math.max(1, Math.round(maxDay.value * 0.72)));
		playing.value = false;
	} catch (e) {
		// fetchEvalSeries / buildDemoDataset 本身不会 reject，这里只是最后兜底
		source.value = 'demo';
		apiError.value = String((e as Error)?.message || e);
		const demo = buildDemoOutcomes(batchId.value, days);
		seriesMap.value = Object.fromEntries(availableStrategies.value.map((c) => [c, []])) as Record<string, EvalDayPoint[]>;
		outcomes.value = demo;
		outcomesSource.value = 'demo';
		loading.value = false;
		return;
	} finally {
		loading.value = false;
	}
	await nextTick();
	updateChart();
};

const loadRuns = async () => {
	runsLoading.value = true;
	try {
		const res = await runEval({ batchId: batchId.value, seed, days });
		outcomes.value = res.data;
		outcomesSource.value = res.source;
	} finally {
		runsLoading.value = false;
	}
};

const reload = async () => {
	setDay(1);
	playing.value = false;
	await loadData();
};

const loadAgentRun = async () => {
	if (agentLoading.value) return;
	agentLoading.value = true;
	agentError.value = '';
	try {
		const previousIndex = agentIndex.value;
		const previousLength = agentFrames.value.length;
		const run = await getActiveAgentRun();
		agentRun.value = run;
		agentFrames.value = run ? await getAgentTwinFrames(run.id) : [];
		agentIndex.value = agentPlaying.value || previousLength > 0 && previousIndex < previousLength - 1
			? Math.min(previousIndex, Math.max(0, agentFrames.value.length - 1))
			: Math.max(0, agentFrames.value.length - 1);
		if (agentFrames.value.some((item) => !item.recorded)) {
			agentError.value = '旧运行包含不完整快照；建议创建新运行以回放设备与库存。';
		}
	} catch (error) {
		agentRun.value = null;
		agentError.value = `仿真服务不可用：${String((error as Error)?.message || error)}`;
		agentFrames.value = [];
	} finally {
		agentLoading.value = false;
	}
};

const createTwinRun = async () => {
	if (agentLoading.value) return;
	agentLoading.value = true;
	try {
		await createAgentRun();
		agentFrames.value = [];
		agentIndex.value = 0;
		agentPlaying.value = false;
		agentLoading.value = false;
		await loadAgentRun();
	} catch (error) {
		agentError.value = String((error as Error)?.message || error);
	} finally {
		agentLoading.value = false;
	}
};

const advanceTwinRun = async () => {
	if (!agentRun.value || agentLoading.value) return;
	agentLoading.value = true;
	try {
		await stepAgentRun(agentRun.value.id);
		agentLoading.value = false;
		await loadAgentRun();
	} catch (error) {
		agentError.value = String((error as Error)?.message || error);
	} finally {
		agentLoading.value = false;
	}
};

const operateTwinRun = async (action: 'start' | 'pause' | 'reset') => {
	if (!agentRun.value || agentLoading.value) return;
	if (action === 'reset') {
		try {
			await ElMessageBox.confirm('重置会清空本次仿真的历史快照、动作与资源流水。确定继续？', '重置虚拟运行', { type: 'warning' });
		} catch {
			return;
		}
	}
	agentLoading.value = true;
	try {
		if (action === 'start') await startAgentRun(agentRun.value.id);
		else if (action === 'pause') await pauseAgentRun(agentRun.value.id);
		else {
			await resetAgentRun(agentRun.value.id);
			agentFrames.value = [];
			agentIndex.value = 0;
			agentPlaying.value = false;
		}
		agentLoading.value = false;
		await loadAgentRun();
	} catch (error) {
		agentError.value = String((error as Error)?.message || error);
	} finally {
		agentLoading.value = false;
	}
};

const selectTwinMode = async (mode: 'agent' | 'daily') => {
	if (mode === twinDataMode.value) return;
	twinDataMode.value = mode;
	playing.value = false;
	agentPlaying.value = false;
	showHud.value = true;
	await nextTick();
	if (mode === 'daily') {
		if (chartRef.value && !chart) chart = echarts.init(chartRef.value, undefined, { renderer: 'canvas' });
		chart?.resize();
		if (!points.value.length) await loadData();
		updateChart();
	} else {
		await loadAgentRun();
	}
};

const toggleAgentPlayback = () => {
	if (agentFrames.value.length < 2) return;
	if (!agentPlaying.value && agentIndex.value >= agentFrames.value.length - 1) agentIndex.value = 0;
	agentPlaybackTime = 0;
	agentPlaying.value = !agentPlaying.value;
};

/* ------------------------------ 交互 ------------------------------ */

const togglePlay = () => {
	if (maxDay.value < 2) return;
	if (!playing.value && dayAnim >= maxDay.value) setDay(1);
	playing.value = !playing.value;
};

/** 拖动进度条：直接跳到该天 */
const onScrub = (v: number | number[]) => {
	const val = Array.isArray(v) ? v[0] : v;
	playing.value = false;
	setDay(val);
};

const setDay = (v: number) => {
	const max = Math.max(1, maxDay.value);
	dayAnim = Math.min(max, Math.max(1, Number.isFinite(v) ? v : 1));
	dayIndex.value = Math.round(dayAnim);
	sliderPos.value = dayAnim;
	clockHour.value = sceneHourAt(dayAnim);
};

const onStrategyChange = () => {
	setDay(Math.min(dayAnim, Math.max(1, maxDay.value)));
	updateChart();
};
const setPreset = (p: CameraPreset) => {
	preset.value = p;
	autoRotate.value = false;
	twin?.setCameraPreset(p);
};
const toggleNavigationMode = () => {
	navigationMode.value = navigationMode.value === 'fly' ? 'orbit' : 'fly';
	autoRotate.value = false;
	twin?.setNavigationMode(navigationMode.value);
};
const updateShellMode = () => twin?.setShellMode(shellMode.value);
const updateQuality = () => twin?.setQuality(qualityPreference.value);
const inspectEquipment = (code: string) => {
	inspected.value = twin?.focusEquipment(code) || null;
	showEquipmentList.value = false;
};
const onCoolingToggle = (on: string | number | boolean) => {
	if (on) demoActuators.exhaustFan = true;
};
const onExhaustToggle = (on: string | number | boolean) => {
	if (!on) demoActuators.coolingPad = false;
};
const toggleAutoRotate = () => {
	autoRotate.value = !autoRotate.value;
	twin?.setAutoRotate(autoRotate.value);
};

/* ---------------------------- 生命周期 ---------------------------- */

const measureHeight = () => {
	const el = shellRef.value;
	if (!el) return;
	const rect = el.getBoundingClientRect();
	const avail = Math.max(window.innerHeight - Math.max(0, rect.top), el.parentElement?.clientHeight || 0) - 14;
	shellHeight.value = Math.max(560, Math.floor(avail));
};

const onResize = () => {
	measureHeight();
	twin?.resize();
	chart?.resize();
};

/** 路由参数可覆盖 batchId：/digitalTwin/:batchId */
const resolveBatchId = () => {
	try {
		const fromParams = (route.params as Record<string, unknown>)?.batchId;
		const fromQuery = (route.query as Record<string, unknown>)?.batchId;
		const v = String(fromParams || fromQuery || '').trim();
		if (v) batchId.value = v;
	} catch {
		/* 无路由上下文时使用默认批次号 */
	}
};

watch(points, () => updateChart());
// 天数变化时只增量刷新标记线，避免高频全量重绘
watch(currentIndex, () => {
	if (!chart) return;
	chart.setOption({ series: [{ markLine: buildMarkLine() }] });
});

onMounted(async () => {
	resolveBatchId();
	if (route.query.mode === 'agent') {
		twinDataMode.value = 'agent';
		showHud.value = true;
	}
	measureHeight();
	await nextTick();

	if (chartRef.value && showHud.value && twinDataMode.value === 'daily') {
		chart = echarts.init(chartRef.value, undefined, { renderer: 'canvas' });
		updateChart();
	}

	if (canvasRef.value && shellRef.value) {
		try {
			twin = new GreenhouseTwin(canvasRef.value, shellRef.value);
			twin.setQuality(qualityPreference.value);
			twin.setShellMode(shellMode.value);
			twin.onInspect((entry) => { inspected.value = entry; });
			equipmentOptions.value = twin.getEquipmentList();
			twin.setCameraPreset('overview');
		} catch (e) {
			webglError.value = String((e as Error)?.message || e);
		}
	}

	window.addEventListener('resize', onResize);
	agentSyncTimer = setInterval(() => {
		if (twinDataMode.value === 'agent' && agentRun.value?.status === 'RUNNING' && !agentLoading.value) {
			void loadAgentRun();
		}
	}, 4000);
	if (shellRef.value?.parentElement && typeof ResizeObserver !== 'undefined') {
		shellResizeObserver = new ResizeObserver(onResize);
		shellResizeObserver.observe(shellRef.value.parentElement);
	}
	raf = requestAnimationFrame(frame);

	if (twinDataMode.value === 'agent') await loadAgentRun();
	else await loadData();
});

onUnmounted(() => {
	if (agentSyncTimer) clearInterval(agentSyncTimer);
	agentSyncTimer = null;
	shellResizeObserver?.disconnect();
	shellResizeObserver = null;
	window.removeEventListener('resize', onResize);
	if (raf) cancelAnimationFrame(raf);
	raf = 0;
	if (chart) {
		chart.dispose();
		chart = null;
	}
	if (twin) {
		twin.dispose();
		twin = null;
	}
});
</script>

<style scoped lang="scss">
/* ============================ 基础 ============================ */
.twin-shell {
	position: relative;
	width: 100%;
	height: var(--twin-h, 720px);
	min-height: 560px;
	overflow: hidden;
	background: radial-gradient(120% 90% at 50% 0%, #0d1723 0%, #060a11 60%, #04070c 100%);
	color: #dbe7f2;
	font-family: 'Inter', 'HarmonyOS Sans SC', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
	font-size: 12px;
	user-select: none;
	isolation: isolate;
}

.twin-canvas {
	position: absolute;
	inset: 0;
	width: 100%;
	height: 100%;
	display: block;
	z-index: 1;
	outline: none;
}

.mono {
	font-variant-numeric: tabular-nums;
	font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
}

/* ============================ 顶栏 ============================ */
.topbar {
	position: absolute;
	top: 0;
	left: 0;
	right: 0;
	z-index: 6;
	display: flex;
	align-items: center;
	gap: 14px;
	padding: 10px 16px 22px;
	background: linear-gradient(180deg, rgba(3, 7, 12, 0.92) 0%, rgba(3, 7, 12, 0.55) 55%, rgba(3, 7, 12, 0) 100%);
	pointer-events: none;

	> * {
		pointer-events: auto;
	}
}

.brand {
	display: flex;
	align-items: center;
	gap: 10px;

	.mark {
		width: 34px;
		height: 34px;
		display: grid;
		place-items: center;
		border-radius: 10px;
		background: linear-gradient(145deg, #1c7a5a, #0d3d55);
		border: 1px solid rgba(120, 240, 200, 0.35);
		box-shadow: 0 0 18px rgba(60, 220, 170, 0.25), inset 0 1px 0 rgba(255, 255, 255, 0.16);
		font-weight: 800;
		letter-spacing: 0.5px;
		font-size: 13px;
		color: #b8ffe6;
	}

	.brand-txt {
		h1 {
			margin: 0;
			font-size: 15px;
			font-weight: 700;
			letter-spacing: 1.6px;
			color: #eaf5ff;
			text-shadow: 0 0 22px rgba(90, 190, 255, 0.35);
		}

		p {
			margin: 1px 0 0;
			font-size: 9px;
			letter-spacing: 2.4px;
			color: #5d7d95;
		}
	}
}

.sim-badge {
	display: flex;
	align-items: center;
	gap: 8px;
	margin-left: 8px;
	padding: 6px 14px;
	border-radius: 999px;
	font-size: 11px;
	font-weight: 700;
	letter-spacing: 1.4px;
	color: #ffd9a0;
	background: linear-gradient(90deg, rgba(255, 150, 40, 0.16), rgba(255, 90, 40, 0.08));
	border: 1px solid rgba(255, 170, 70, 0.42);
	box-shadow: 0 0 22px rgba(255, 150, 40, 0.16), inset 0 1px 0 rgba(255, 255, 255, 0.08);

	.pulse {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: #ffb343;
		box-shadow: 0 0 10px #ffb343;
		animation: simPulse 1.8s ease-in-out infinite;
	}
}

@keyframes simPulse {
	0%,
	100% {
		opacity: 1;
		transform: scale(1);
	}
	50% {
		opacity: 0.35;
		transform: scale(0.72);
	}
}

.top-right {
	margin-left: auto;
	display: flex;
	align-items: center;
	gap: 8px;
}

.chip {
	padding: 4px 10px;
	border-radius: 7px;
	font-size: 10.5px;
	letter-spacing: 0.6px;
	color: #9fb8cc;
	background: rgba(12, 22, 33, 0.72);
	border: 1px solid rgba(120, 170, 210, 0.2);
	white-space: nowrap;

	&.ok {
		color: #7ef0c0;
		border-color: rgba(80, 230, 180, 0.42);
		background: rgba(20, 70, 56, 0.45);
	}

	&.warn {
		color: #ffc266;
		border-color: rgba(255, 180, 70, 0.45);
		background: rgba(80, 50, 12, 0.45);
	}
}

.demo-alert {
	position: absolute;
	top: 54px;
	left: 50%;
	transform: translateX(-50%);
	z-index: 7;
	width: max-content;
	max-width: min(640px, calc(100% - 40px));
	padding: 5px 10px;
	border-radius: 8px;
	background: rgba(45, 31, 15, 0.9);
	border: 1px solid rgba(255, 180, 70, 0.32);
	backdrop-filter: blur(10px);
	color: #f5cf92;
	font-size: 10px;
	text-align: center;

	button {
		margin-left: 12px;
		padding: 1px 4px;
		border: 0;
		background: none;
		color: #9ee4d1;
		cursor: pointer;
	}

	p {
		margin: 7px 2px 3px;
		line-height: 1.6;
		text-align: left;
	}
}

/* ============================ HUD 面板 ============================ */
.hud {
	position: absolute;
	top: 64px;
	bottom: 116px;
	z-index: 5;
	width: 330px;
	display: flex;
	flex-direction: column;
	gap: 10px;
	overflow-y: auto;
	overflow-x: hidden;
	padding-right: 4px;
	scrollbar-width: thin;
	scrollbar-color: rgba(110, 160, 200, 0.35) transparent;

	&::-webkit-scrollbar {
		width: 5px;
	}

	&::-webkit-scrollbar-thumb {
		background: rgba(110, 160, 200, 0.32);
		border-radius: 4px;
	}

	&::-webkit-scrollbar-track {
		background: transparent;
	}
}

.hud-left {
	left: 16px;
}

.hud-right {
	right: 16px;
	width: 344px;
}

.agent-hud {
	.agent-row {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 14px;
		padding: 8px 1px;
		border-bottom: 1px solid rgba(130, 170, 185, 0.12);
		color: #b9cdd5;
		font-size: 11px;

		b { color: #e1f3ed; font-variant-numeric: tabular-nums; white-space: nowrap; }
		b.agent-on { color: #79efb0; }
		small { color: #799b9e; font-size: 9px; }
	}

	.agent-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		margin-top: 12px;
	}

	.agent-error {
		color: #f2bd81;
		font-size: 11px;
		line-height: 1.5;
		word-break: break-word;
	}
}

.panel {
	position: relative;
	padding: 11px 13px 13px;
	border-radius: 13px;
	background: linear-gradient(158deg, rgba(11, 20, 30, 0.86) 0%, rgba(6, 12, 19, 0.76) 100%);
	border: 1px solid rgba(110, 175, 225, 0.16);
	box-shadow: 0 16px 40px rgba(0, 0, 0, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.055);
	backdrop-filter: blur(15px) saturate(140%);

	&::before {
		content: '';
		position: absolute;
		left: 13px;
		right: 13px;
		top: 0;
		height: 1px;
		background: linear-gradient(90deg, transparent, rgba(110, 235, 200, 0.5), transparent);
		opacity: 0.7;
	}
}

.panel-hd {
	display: flex;
	align-items: baseline;
	gap: 8px;
	margin-bottom: 9px;

	.ttl {
		font-size: 12.5px;
		font-weight: 700;
		letter-spacing: 2px;
		color: #dff0ff;
	}

	.sub {
		font-size: 8.5px;
		letter-spacing: 1.5px;
		color: #4f6d84;
		text-transform: uppercase;
	}

	.risk,
	.stage,
	.cur {
		margin-left: auto;
		font-size: 10.5px;
		padding: 2px 8px;
		border-radius: 6px;
		letter-spacing: 0.6px;
	}

	.cur {
		color: #8fb3c9;
		background: rgba(20, 36, 50, 0.7);
		border: 1px solid rgba(110, 170, 210, 0.2);
	}

	.stage {
		color: #9fe8c8;
		background: rgba(20, 62, 50, 0.6);
		border: 1px solid rgba(80, 220, 170, 0.3);
	}

	.risk-low {
		color: #7ef0c0;
		background: rgba(20, 70, 56, 0.6);
		border: 1px solid rgba(80, 230, 180, 0.4);
	}

	.risk-medium {
		color: #ffd166;
		background: rgba(74, 60, 12, 0.6);
		border: 1px solid rgba(255, 209, 102, 0.4);
	}

	.risk-high,
	.risk-critical {
		color: #ff8a7a;
		background: rgba(78, 24, 20, 0.66);
		border: 1px solid rgba(255, 120, 100, 0.45);
		box-shadow: 0 0 14px rgba(255, 90, 70, 0.25);
	}
}

.grid-2 {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 6px 12px;
}

.metric {
	display: flex;
	align-items: baseline;
	justify-content: space-between;
	gap: 6px;
	padding: 4px 0;
	border-bottom: 1px dashed rgba(110, 160, 200, 0.12);

	.lb {
		font-size: 10.5px;
		color: #7e97aa;
		white-space: nowrap;
	}

	.vl {
		font-size: 13.5px;
		font-weight: 600;
		color: #eaf6ff;
		letter-spacing: 0.3px;

		em {
			font-style: normal;
			font-size: 9.5px;
			color: #6d8798;
			margin-left: 2px;
		}

		&.hot {
			color: #ffb066;
		}
	}
}

.perf-tag {
	margin-left: auto;
	font-size: 10.5px;
	padding: 2px 8px;
	border-radius: 6px;
	letter-spacing: 0.6px;

	&.good {
		color: #7ef0c0;
		background: rgba(20, 70, 56, 0.6);
		border: 1px solid rgba(80, 230, 180, 0.4);
	}

	&.bad {
		color: #ffc266;
		background: rgba(80, 50, 12, 0.6);
		border: 1px solid rgba(255, 180, 70, 0.45);
		box-shadow: 0 0 14px rgba(255, 170, 60, 0.25);
	}
}

.grid-2 .metric:last-child:nth-child(odd) {
	grid-column: span 2;
}

/* 器官干重条 */
.organ-bars {
	margin-top: 9px;
	display: flex;
	flex-direction: column;
	gap: 5px;
}

.obar,
.sev-row {
	display: grid;
	grid-template-columns: 30px 1fr 52px;
	align-items: center;
	gap: 8px;

	.lb {
		font-size: 10.5px;
		color: #7e97aa;
	}

	.vl {
		font-size: 11.5px;
		text-align: right;
		color: #e2f0fa;
		font-weight: 600;

		em {
			font-style: normal;
			font-size: 9px;
			color: #6d8798;
			margin-left: 1px;
		}

		&.hot {
			color: #ff9a86;
		}
	}
}

.sev-list {
	margin-top: 9px;
	display: flex;
	flex-direction: column;
	gap: 5px;
}

.sev-row {
	grid-template-columns: 44px 1fr 52px;
}

.track {
	position: relative;
	height: 6px;
	border-radius: 4px;
	background: rgba(120, 160, 190, 0.14);
	overflow: hidden;
	box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.45);

	i {
		position: absolute;
		inset: 0 auto 0 0;
		border-radius: 4px;
		transition: width 0.25s ease;
		box-shadow: 0 0 10px rgba(255, 255, 255, 0.12);
	}

	&.sev i {
		box-shadow: 0 0 12px currentColor;
	}
}

.panel-chart {
	display: flex;
	flex-direction: column;
}

.chart {
	width: 100%;
	height: 132px;
}

/* 设备 */
.dev-grid {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 7px;
}

.dev {
	display: flex;
	align-items: center;
	gap: 7px;
	padding: 7px 9px;
	border-radius: 9px;
	background: rgba(14, 24, 34, 0.7);
	border: 1px solid rgba(110, 160, 200, 0.16);
	transition: all 0.22s ease;

	.led {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: #33424f;
		box-shadow: none;
		flex: none;
	}

	.nm {
		font-size: 11px;
		color: #8ea6b8;
	}

	.st {
		margin-left: auto;
		font-size: 9px;
		letter-spacing: 1px;
		color: #4e6373;
	}

	&.on {
		background: linear-gradient(120deg, rgba(20, 74, 60, 0.72), rgba(12, 40, 44, 0.7));
		border-color: rgba(80, 235, 180, 0.45);

		.led {
			background: #4dfab4;
			box-shadow: 0 0 10px #4dfab4, 0 0 20px rgba(77, 250, 180, 0.5);
		}

		.nm {
			color: #d6fff0;
		}

		.st {
			color: #4dfab4;
		}
	}
}

.model-switch,
.quality-choice {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	min-height: 32px;
	padding: 5px 2px;
	border-bottom: 1px solid rgba(110, 160, 200, 0.12);
	color: #acc6d7;
	font-size: 11px;
}

.quality-choice {
	margin-top: 8px;

	.el-select {
		width: 112px;
	}
}

/* 经济 */
.eco {
	display: flex;
	flex-direction: column;
	gap: 4px;
}

.eco-row {
	display: flex;
	align-items: baseline;
	justify-content: space-between;
	font-size: 11px;
	color: #7e97aa;
	padding: 3px 0;
	border-bottom: 1px dashed rgba(110, 160, 200, 0.1);

	em {
		font-style: normal;
		font-size: 9px;
		margin-left: 4px;
		padding: 1px 4px;
		border-radius: 4px;
		color: #8fb3c9;
		background: rgba(30, 52, 70, 0.7);
		border: 1px solid rgba(110, 170, 210, 0.22);
	}

	b {
		font-size: 12.5px;
		color: #e6f3fc;
		font-weight: 600;
	}

	&.big {
		border-bottom: none;
		margin-top: 2px;
		padding-top: 6px;
		border-top: 1px solid rgba(110, 175, 225, 0.2);

		b {
			font-size: 16px;
			color: #6ef0b8;
			text-shadow: 0 0 16px rgba(110, 240, 184, 0.35);
		}
	}

	&.neg b {
		color: #ff8f7d;
		text-shadow: 0 0 16px rgba(255, 120, 100, 0.3);
	}
}

.eco-note {
	margin: 7px 0 0;
	font-size: 9.5px;
	line-height: 1.55;
	color: #5a7385;
	border-top: 1px solid rgba(110, 160, 200, 0.12);
	padding-top: 6px;
}

/* 终局矩阵 */
.runs-hint {
	display: flex;
	flex-direction: column;
	gap: 8px;

	p {
		margin: 0;
		font-size: 10.5px;
		line-height: 1.6;
		color: #7e97aa;
	}
}

.runs {
	display: flex;
	flex-direction: column;
	gap: 3px;
}

.runs-hd,
.runs-row {
	display: grid;
	grid-template-columns: 1.5fr 0.9fr 0.8fr 0.8fr 0.6fr;
	gap: 4px;
	align-items: center;
	font-size: 10px;
}

.runs-hd {
	color: #56707f;
	letter-spacing: 0.4px;
	padding-bottom: 4px;
	border-bottom: 1px solid rgba(110, 160, 200, 0.14);
}

.runs-row {
	padding: 4px 5px;
	border-radius: 6px;
	color: #b9cddd;

	span:not(:first-child) {
		text-align: right;
	}

	.neg {
		color: #ff8f7d;
	}

	&.best {
		background: linear-gradient(90deg, rgba(24, 84, 66, 0.6), rgba(10, 30, 32, 0.3));
		border: 1px solid rgba(80, 235, 180, 0.28);
		color: #d9fff1;
	}
}

.runs-note {
	margin: 6px 0 0;
	font-size: 9.5px;
	color: #5a7385;
	letter-spacing: 0.3px;
}

/* ============================ 相机条 ============================ */
.cam-bar {
	position: absolute;
	right: 16px;
	bottom: 124px;
	z-index: 6;
	display: flex;
	align-items: center;
	gap: 8px;
	padding: 7px 10px;
	border-radius: 11px;
	background: rgba(8, 15, 23, 0.8);
	border: 1px solid rgba(110, 175, 225, 0.18);
	backdrop-filter: blur(14px);
	box-shadow: 0 14px 34px rgba(0, 0, 0, 0.45);
	max-width: calc(100% - 32px);
	flex-wrap: wrap;
	justify-content: flex-end;

	&.is-immersive {
		bottom: 20px;
	}

	.flight-guide {
		color: #b8e9c7;
		font-size: 11px;
	}

	.clock {
		font-size: 11px;
		color: #9fd8ff;
		letter-spacing: 1px;
		padding-left: 4px;
		min-width: 92px;
		text-align: right;
	}
}

.shell-select {
	width: 112px;
}

.equipment-directory {
	position: absolute;
	right: 16px;
	top: 146px;
	z-index: 7;
	width: 280px;
	max-height: min(380px, calc(100% - 260px));
	overflow-y: auto;
	padding: 10px;
	border: 1px solid rgba(107, 224, 189, 0.3);
	border-radius: 12px;
	background: rgba(7, 22, 30, 0.95);
	box-shadow: 0 18px 48px rgba(0, 0, 0, 0.44);
	backdrop-filter: blur(18px);

	.directory-heading {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		gap: 6px;
		padding: 4px 5px 10px;
		color: #e3f6ec;

		span { font-size: 10px; color: #7daca6; }
	}

	button {
		display: flex;
		width: 100%;
		justify-content: space-between;
		align-items: center;
		gap: 8px;
		padding: 8px 6px;
		border: 0;
		border-top: 1px solid rgba(110, 160, 200, 0.13);
		background: none;
		color: #c2dfdf;
		font-size: 11px;
		text-align: left;
		cursor: pointer;

		&:hover { background: rgba(40, 110, 88, 0.25); }
		small { flex: none; color: #779ca5; font-size: 9px; }
	}
}

.flight-reticle {
	position: absolute;
	left: 50%;
	top: 50%;
	z-index: 5;
	width: 16px;
	height: 16px;
	transform: translate(-50%, -50%);
	pointer-events: none;
	background: radial-gradient(circle, rgba(255, 255, 255, 0.95) 0 1px, transparent 2px);
	filter: drop-shadow(0 1px 2px #031016);

	&::before,
	&::after {
		content: '';
		position: absolute;
		background: rgba(220, 255, 240, 0.82);
	}

	&::before {
		width: 16px;
		height: 1px;
		top: 7px;
	}

	&::after {
		width: 1px;
		height: 16px;
		left: 7px;
	}
}

.inspection-panel {
	position: absolute;
	left: max(326px, 22%);
	top: 135px;
	z-index: 7;
	width: min(290px, calc(100% - 344px));
	padding: 15px 16px;
	border: 1px solid rgba(107, 224, 189, 0.38);
	border-radius: 12px;
	background: rgba(7, 22, 30, 0.94);
	box-shadow: 0 18px 48px rgba(0, 0, 0, 0.44);
	backdrop-filter: blur(18px);
	line-height: 1.5;

	.inspection-top {
		display: flex;
		justify-content: space-between;
		align-items: center;
		color: #68d5aa;
		font-size: 10px;
		letter-spacing: 1px;

		button {
			border: 0;
			background: none;
			color: #afcbd5;
			font-size: 20px;
			cursor: pointer;
		}
	}

	strong,
	.inspection-zone,
	b,
	small {
		display: block;
	}

	strong { margin: 4px 0; font-size: 15px; color: #ecf7f4; }
	.inspection-zone { color: #9fb9c5; font-size: 10px; }
	p { margin: 12px 0; color: #c6d8dd; }
	b { color: #a5c7d1; font-size: 14px; }
	b.active { color: #69e4a5; }
	small { margin-top: 10px; color: #7999a3; font-size: 10px; }
}

/* ============================ 播放条 ============================ */
.playbar {
	position: absolute;
	left: 0;
	right: 0;
	bottom: 0;
	z-index: 6;
	display: flex;
	align-items: center;
	gap: 16px;
	padding: 12px 20px 16px;
	background: linear-gradient(0deg, rgba(3, 7, 12, 0.96) 0%, rgba(3, 7, 12, 0.78) 60%, rgba(3, 7, 12, 0) 100%);
	border-top: 1px solid rgba(110, 175, 225, 0.12);

	.pp {
		width: 42px;
		height: 42px;
		font-size: 13px;
		flex: none;
	}

	.strategy {
		width: 150px;
		flex: none;
	}
}

.scrub {
	flex: 1;
	min-width: 0;

	.scrub-top {
		display: flex;
		align-items: center;
		gap: 14px;
		margin-bottom: -2px;

		.day {
			font-size: 12px;
			font-weight: 700;
			color: #9ff0d0;
			letter-spacing: 0.6px;
		}

		.date {
			font-size: 11px;
			color: #7d95a8;
		}

		.stage-chip {
			font-size: 10px;
			color: #b7d4e6;
			padding: 1px 8px;
			border-radius: 5px;
			background: rgba(20, 40, 56, 0.7);
			border: 1px solid rgba(110, 175, 225, 0.2);
		}
	}
}

.speeds {
	display: flex;
	gap: 6px;
	flex: none;
}

/* ============================ 病害标签 ============================ */
.disease-layer {
	position: absolute;
	inset: 0;
	z-index: 4;
	pointer-events: none;
	overflow: hidden;
}

.disease-tag {
	position: absolute;
	left: 0;
	top: 0;
	display: flex;
	align-items: center;
	gap: 6px;
	padding: 3px 9px;
	border-radius: 8px;
	font-size: 10.5px;
	white-space: nowrap;
	background: rgba(8, 14, 21, 0.78);
	border: 1px solid rgba(255, 255, 255, 0.16);
	backdrop-filter: blur(6px);
	transition: opacity 0.3s ease;

	.dot {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		flex: none;
	}

	.pc {
		font-weight: 700;
		color: #ffd8a0;
	}

	&.off {
		opacity: 0;
	}

	&.on {
		opacity: 1;
	}

	&.d-botrytis .dot {
		background: #a7b4a4;
		box-shadow: 0 0 8px #a7b4a4;
	}

	&.d-late_blight .dot {
		background: #7f9149;
		box-shadow: 0 0 8px #7f9149;
	}

	&.d-powdery_mildew .dot {
		background: #e9ede0;
		box-shadow: 0 0 8px #e9ede0;
	}

	&.d-leaf_mold .dot {
		background: #d0b24e;
		box-shadow: 0 0 8px #d0b24e;
	}
}

/* ============================ 加载 / 错误 ============================ */
.boot,
.gl-error {
	position: absolute;
	inset: 0;
	z-index: 9;
	display: grid;
	place-items: center;
	background: rgba(4, 8, 13, 0.78);
	backdrop-filter: blur(3px);
}

.boot-inner {
	display: flex;
	align-items: center;
	gap: 12px;
	padding: 16px 26px;
	border-radius: 14px;
	background: rgba(10, 18, 27, 0.9);
	border: 1px solid rgba(110, 175, 225, 0.24);
	color: #b6cfe0;
	font-size: 12.5px;
	letter-spacing: 1px;

	.spin {
		width: 18px;
		height: 18px;
		border-radius: 50%;
		border: 2px solid rgba(110, 220, 190, 0.25);
		border-top-color: #5fe0b0;
		animation: spin 0.85s linear infinite;
	}
}

@keyframes spin {
	to {
		transform: rotate(360deg);
	}
}

.gl-error-inner {
	max-width: 460px;
	text-align: center;
	padding: 20px;
	border-radius: 14px;
	background: rgba(30, 12, 12, 0.9);
	border: 1px solid rgba(255, 130, 110, 0.4);
	color: #ffc9be;
	font-size: 12.5px;
	line-height: 1.7;

	h3 {
		margin: 0 0 8px;
		font-size: 14px;
		color: #ff9d8a;
	}
}

/* ============================ Element Plus 暗色适配 ============================ */
.twin-shell {
	:deep(.el-button) {
		background: rgba(16, 28, 40, 0.86);
		border-color: rgba(110, 175, 225, 0.24);
		color: #b9d3e4;
		font-size: 11.5px;
		letter-spacing: 0.5px;

		&:hover:not(.is-disabled) {
			background: rgba(24, 44, 62, 0.92);
			border-color: rgba(120, 220, 190, 0.55);
			color: #e6fff6;
		}

		&.el-button--primary {
			background: linear-gradient(120deg, #1c8f6a, #14657e);
			border-color: rgba(110, 240, 200, 0.45);
			color: #eafff8;
			box-shadow: 0 0 16px rgba(40, 200, 160, 0.22);
		}

		&.el-button--warning {
			background: linear-gradient(120deg, #a8761c, #8a5216);
			border-color: rgba(255, 200, 110, 0.5);
			color: #fff5e0;
		}

		&.is-circle {
			display: grid;
			place-items: center;
		}
	}

	:deep(.el-button-group .el-button) {
		border-radius: 0;
	}

	:deep(.el-select) {
		.el-input__wrapper,
		.el-select__wrapper {
			background: rgba(12, 22, 32, 0.9);
			box-shadow: 0 0 0 1px rgba(110, 175, 225, 0.24) inset;
			border-radius: 8px;
		}

		.el-input__inner,
		.el-select__placeholder,
		.el-select__selected-item {
			color: #cfe6f6;
			font-size: 12px;
		}
	}

	:deep(.el-slider) {
		--el-slider-main-bg-color: linear-gradient(90deg, #23c98d, #59d8ff);
		height: 26px;

		.el-slider__runway {
			background: rgba(120, 165, 200, 0.2);
			height: 5px;
			margin: 10px 0;
		}

		.el-slider__bar {
			background: linear-gradient(90deg, #23c98d, #59d8ff);
			height: 5px;
			box-shadow: 0 0 12px rgba(60, 220, 190, 0.45);
		}

		.el-slider__button {
			width: 13px;
			height: 13px;
			border: 2px solid #7ff0cf;
			background: #06121a;
			box-shadow: 0 0 12px rgba(90, 240, 200, 0.6);
		}
	}

	:deep(.el-alert--warning.is-light) {
		background: rgba(48, 32, 8, 0.86);
	}
}

/* ============================ 自适应 ============================ */
@media (max-width: 1560px) {
	.hud {
		width: 296px;
	}

	.hud-right {
		width: 306px;
	}

	.twin-shell {
		font-size: 11.5px;
	}

	.demo-alert {
		width: min(600px, calc(100% - 660px));
	}
}

@media (max-width: 1280px) {
	.hud-right {
		display: none;
	}

	.demo-alert {
		width: calc(100% - 380px);
	}

	.inspection-panel {
		left: 326px;
	}
}
@media (max-width: 640px) {
	.topbar { flex-wrap: wrap; align-items: center; gap: 6px 8px; padding: 8px 12px 12px; background: rgba(3, 7, 12, .86); }
	.brand { flex: 0 0 100%; }
	.brand .brand-txt h1 { font-size: 13px; letter-spacing: 0; }
	.brand .brand-txt p { display: none; }
	.sim-badge { order: 2; margin: 0; padding: 5px 8px; font-size: 9px; letter-spacing: 0; box-shadow: none; }
	.top-right { order: 3; margin-left: auto; }
	.top-right .chip.mono, .top-right > .el-button { display: none; }
	.hud { top: 102px; bottom: 315px; left: 12px; width: calc(100% - 24px); max-width: 366px; }
	.hud-right { display: none; }
	.cam-bar { left: 12px; right: 12px; bottom: 94px; max-width: none; max-height: 150px; overflow-y: auto; justify-content: center; }
	.cam-bar.is-immersive { bottom: 12px; }
	.playbar { gap: 10px; padding: 10px 12px 14px; }
}
</style>
