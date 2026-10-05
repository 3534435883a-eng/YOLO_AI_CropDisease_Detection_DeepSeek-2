package com.example.Ece.agent.engine;

import com.example.Ece.agent.model.AgentDeviceCodes;
import com.example.Ece.agent.model.DecisionPlan;
import com.example.Ece.agent.model.DeviceCommand;
import com.example.Ece.agent.model.SimulationState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连续 PID 气候控制器：把 {@link TomatoDecisionPolicy} 的"越线才动作"阈值逻辑
 * 换成对设定值的连续反馈调节。
 *
 * <p><b>为什么要换。</b>规则层是"硬阈值 + 固定出力 + 二值开关"，在控制论意义上等价于
 * 一个带死区的比例（P）控制器：误差必须先越过阈值才会产生任何控制量，因此稳态偏差被
 * 结构性保留（例如室外扰动把棚温稳在 28.9℃ 时，29.0 的阈值永不触发，偏差就一直存在）。
 * 引入积分项后，只要误差非零，控制量就持续增大直到偏差被消除。</p>
 *
 * <p><b>控制律。</b>误差先按量程归一化，使比例增益恒为 1——即"误差达到量程时输出满量程"，
 * 这样增益是可读的而不是拟合出来的：
 * <pre>
 *   e = (测量值 − 设定值) / 量程        // 降温类；CO₂/补光为反向
 *   u = clamp(Kp·e + Ki·∫e dt + Kd·de/dt, 0, 1)      Kp = 1
 * </pre>
 * 积分采用条件积分抗饱和：输出已饱和且误差仍指向饱和方向时停止累积。</p>
 *
 * <p><b>出力映射。</b>控制量 u 是连续的，但设备模型只有"开/关 + 出力比例(duty)"两个自由度，
 * 因此按固定分档把 u 映射到设备出力：屋窗先开（换气代价最低）、主通风跟进、强制排风最后介入。
 * 这套分档阈值与增益**都是未校准的演示参数**，不是整定结果，不得作为工程设定值。</p>
 *
 * <p><b>约束保持。</b>CO₂ 补给与任何换气设备互斥，补光与遮阳互斥——这两条与规则层一致，
 * 由控制器直接保证，因此不会产生设备互斥冲突。</p>
 *
 * <p><b>确定性。</b>控制器只持有积分与上次误差，无随机数、无墙钟依赖；
 * 同一初值序列必然产生同一出力序列。每次运行前必须调用 {@link #reset()}。</p>
 */
public class PidControlPolicy {

    /** 温度设定值（℃）：取番茄适宜区间 20–26℃ 的上沿，与评价阈值 28℃ 留出 2℃ 裕量。 */
    public static final double TEMPERATURE_SET_C = 26.0;
    /** 温度量程（℃）：误差达到 4℃ 时比例项输出满量程。 */
    public static final double TEMPERATURE_SPAN_C = 4.0;
    /** 空气湿度设定值（%RH）。 */
    public static final double HUMIDITY_SET_PCT = 75.0;
    /** 空气湿度量程（%RH）。 */
    public static final double HUMIDITY_SPAN_PCT = 10.0;
    /** CO₂ 设定值（ppm）。 */
    public static final double CO2_SET_PPM = 800.0;
    /** CO₂ 量程（ppm）：当前浓度比设定值低 300 ppm 时补气满量程。 */
    public static final double CO2_SPAN_PPM = 300.0;
    /** 补光目标光强（PPFD，µmol/m²/s）。 */
    public static final double PPFD_SET = 500.0;
    /** 补光量程（PPFD）。 */
    public static final double PPFD_SPAN = 500.0;
    /** 遮阳起始温度（℃）与量程（℃）。 */
    public static final double SHADE_START_C = 28.0;
    public static final double SHADE_SPAN_C = 4.0;

    /** 湿帘只在空气湿度低于该值时启用：高湿时蒸发降温会进一步推高湿度。 */
    public static final double COOLING_PAD_MAX_HUMIDITY_PCT = 80.0;

    private final Loop temperatureLoop;
    private final Loop humidityLoop;
    private final Loop co2Loop;
    private final Loop lightLoop;

    /**
     * 仅比例（P）：用于对照"连续但无积分"是否仍留稳态偏差。
     */
    public static PidControlPolicy proportionalOnly() {
        return new PidControlPolicy(1.0, 0.0, 1.0, 0.0, KP_SECONDARY, 0.0, KP_SECONDARY, 0.0);
    }

    /** 比例 + 积分（PI）：预期消除稳态偏差。 */
    public static PidControlPolicy proportionalIntegral() {
        return new PidControlPolicy(1.0, KI_TEMPERATURE, 1.0, KI_HUMIDITY,
                KP_SECONDARY, KI_SECONDARY, KP_SECONDARY, KI_SECONDARY);
    }

    /** 完整 PID：预期在消偏之外进一步抑制超调。 */
    public static PidControlPolicy proportionalIntegralDerivative() {
        return new PidControlPolicy(1.0, KI_TEMPERATURE, 1.0, KI_HUMIDITY,
                KP_SECONDARY, KI_SECONDARY, KP_SECONDARY, KI_SECONDARY,
                KD_TEMPERATURE, KD_HUMIDITY, KD_SECONDARY, KD_SECONDARY);
    }

    /** 温度/湿度回路的比例增益恒为 1（误差满量程即输出满量程）；CO₂/补光回路同理，仅积分增益不同。 */
    private static final double KP_SECONDARY = 1.0;
    /** 温度/湿度回路的积分增益：持续 0.1 的归一化误差在 10 步（2.5 小时）内累积到同量级校正量。 */
    private static final double KI_TEMPERATURE = 0.1;
    private static final double KI_HUMIDITY = 0.1;
    /** 温度/湿度回路的微分增益：按每步误差变化量折算，快速逼近设定值时显著压低出力。 */
    private static final double KD_TEMPERATURE = 0.5;
    private static final double KD_HUMIDITY = 0.5;
    /** CO₂ 与补光回路响应较慢，积分与微分增益取较小值。 */
    private static final double KI_SECONDARY = 0.05;
    private static final double KD_SECONDARY = 0.25;

    public PidControlPolicy(double kpTemperature, double kiTemperature, double kpHumidity, double kiHumidity,
                            double kpCo2, double kiCo2, double kpLight, double kiLight) {
        this(kpTemperature, kiTemperature, kpHumidity, kiHumidity, kpCo2, kiCo2, kpLight, kiLight,
                0.0, 0.0, 0.0, 0.0);
    }

    public PidControlPolicy(double kpTemperature, double kiTemperature, double kpHumidity, double kiHumidity,
                            double kpCo2, double kiCo2, double kpLight, double kiLight,
                            double kdTemperature, double kdHumidity, double kdCo2, double kdLight) {
        // 归一化误差的量程为 1，积分项上限 10 意味着积分最多贡献 10·Ki 的控制量（Ki=0.1 时可达满量程）。
        this.temperatureLoop = new Loop(kpTemperature, kiTemperature, kdTemperature, 10.0);
        this.humidityLoop = new Loop(kpHumidity, kiHumidity, kdHumidity, 10.0);
        this.co2Loop = new Loop(kpCo2, kiCo2, kdCo2, 10.0);
        this.lightLoop = new Loop(kpLight, kiLight, kdLight, 10.0);
    }

    /** 清空积分与微分记忆。同一批次内每档策略各持有一个实例，运行前必须调用。 */
    public void reset() {
        temperatureLoop.reset();
        humidityLoop.reset();
        co2Loop.reset();
        lightLoop.reset();
    }

    /** 控制器的完整输出：既给出结构化决策计划，也给出各设备的连续出力。 */
    public static final class Control {
        private final DecisionPlan plan;
        private final Map<String, Double> duties;

        Control(DecisionPlan plan, Map<String, Double> duties) {
            this.plan = plan;
            this.duties = duties;
        }

        public DecisionPlan getPlan() {
            return plan;
        }

        /** 设备代码 → 出力比例 ∈ [0,1]。未列出的设备视为 0（不开）。 */
        public Map<String, Double> getDuties() {
            return duties;
        }
    }

    public Control decide(SimulationState state) {
        return decide(state, 15);
    }

    /** 积分/微分按实际步长折算，15 分钟为旧增益的参考时间单位。 */
    public Control decide(SimulationState state, int minutes) {
        double dtSteps = Math.max(1, minutes) / 15.0;
        double temperatureError = (state.getTemperatureC() - TEMPERATURE_SET_C) / TEMPERATURE_SPAN_C;
        double humidityError = (state.getAirHumidityPct() - HUMIDITY_SET_PCT) / HUMIDITY_SPAN_PCT;
        double co2Error = (CO2_SET_PPM - state.getCo2Ppm()) / CO2_SPAN_PPM;
        double lightError = (PPFD_SET - state.getLightPpfd()) / PPFD_SPAN;

        double cooling = clamp(temperatureLoop.output(temperatureError, dtSteps), 0.0, 1.0);
        double dehumidify = clamp(humidityLoop.output(humidityError, dtSteps), 0.0, 1.0);
        double co2 = clamp(co2Loop.output(co2Error, dtSteps), 0.0, 1.0);
        double light = clamp(lightLoop.output(lightError, dtSteps), 0.0, 1.0);

        // 降温与排湿共用同一组换气设备：取两者需求的大者，保持"通风同时降温排湿"的语义。
        double exchangeDemand = Math.max(cooling, dehumidify);
        double roofVent = stage(exchangeDemand, 0.0, 0.5);
        double ventilation = stage(exchangeDemand, 0.25, 0.75);
        double exhaustFan = stage(exchangeDemand, 0.75, 0.25);
        double shade = clamp((state.getTemperatureC() - SHADE_START_C) / SHADE_SPAN_C, 0.0, 1.0);

        boolean airExchanging = roofVent > 0.0 || ventilation > 0.0 || exhaustFan > 0.0;
        double coolingPad = exhaustFan > 0.0
                && state.getAirHumidityPct() < COOLING_PAD_MAX_HUMIDITY_PCT
                ? stage(exchangeDemand, 0.85, 0.15) : 0.0;
        // 互斥（与规则层同一约束，构造上保证不产生冲突）：
        double effectiveShade = shade;
        double growLight = effectiveShade > 0.0 ? 0.0 : light;
        double co2Supply = airExchanging ? 0.0 : co2;

        Map<String, Double> duties = new LinkedHashMap<String, Double>();
        for (String code : AgentDeviceCodes.all()) {
            duties.put(code, Double.valueOf(0.0));
        }
        duties.put(AgentDeviceCodes.VENTILATION, Double.valueOf(ventilation));
        duties.put(AgentDeviceCodes.ROOF_VENT, Double.valueOf(roofVent));
        duties.put(AgentDeviceCodes.EXHAUST_FAN, Double.valueOf(exhaustFan));
        duties.put(AgentDeviceCodes.COOLING_PAD, Double.valueOf(coolingPad));
        duties.put(AgentDeviceCodes.SHADE, Double.valueOf(effectiveShade));
        duties.put(AgentDeviceCodes.GROW_LIGHT, Double.valueOf(growLight));
        duties.put(AgentDeviceCodes.CO2_SUPPLY, Double.valueOf(co2Supply));

        List<DeviceCommand> commands = new ArrayList<DeviceCommand>();
        for (String code : AgentDeviceCodes.all()) {
            double duty = duties.get(code).doubleValue();
            commands.add(new DeviceCommand(code, duty > 0.0, ruleCodeOf(code, duty), priorityOf(code),
                    summaryOf(code, duty), null, null, duty >= 0.75));
        }
        String summary = String.format(
                "PID 连续调节：温 %.1f℃(u=%.2f) 湿 %.1f%%(u=%.2f) CO₂ %.0fppm(u=%.2f)；换气需求 %.2f",
                state.getTemperatureC(), cooling, state.getAirHumidityPct(), dehumidify,
                state.getCo2Ppm(), co2, exchangeDemand);
        return new Control(new DecisionPlan(commands, summary, state.getRiskLevel()), duties);
    }

    /** 分档映射：误差需求达到 start 时出力从 0 起，达到 start+span 时满量程。 */
    private double stage(double demand, double start, double span) {
        if (demand <= start) {
            return 0.0;
        }
        return clamp((demand - start) / span, 0.0, 1.0);
    }

    private String ruleCodeOf(String code, double duty) {
        if (duty <= 0.0) {
            return "PID_IDLE";
        }
        return "PID_" + code + (duty >= 1.0 ? "_FULL" : "_PARTIAL");
    }

    private int priorityOf(String code) {
        if (AgentDeviceCodes.EXHAUST_FAN.equals(code) || AgentDeviceCodes.COOLING_PAD.equals(code)) {
            return 1;
        }
        if (AgentDeviceCodes.VENTILATION.equals(code) || AgentDeviceCodes.ROOF_VENT.equals(code)) {
            return 2;
        }
        return 3;
    }

    private String summaryOf(String code, double duty) {
        if (duty <= 0.0) {
            return "PID 出力为零，设备待机";
        }
        return String.format("PID 出力 %.0f%%", Double.valueOf(duty * 100.0));
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    /** 一个控制回路：归一化误差 → 连续出力，含积分限幅与条件积分抗饱和。 */
    private static final class Loop {
        private final double kp;
        private final double ki;
        private final double kd;
        private final double integralCap;
        private double integral;
        private double previousError;
        private boolean initialized;

        Loop(double kp, double ki, double kd, double integralCap) {
            this.kp = kp;
            this.ki = ki;
            this.kd = kd;
            this.integralCap = integralCap;
        }

        void reset() {
            integral = 0.0;
            previousError = 0.0;
            initialized = false;
        }

        double output(double error, double dtSteps) {
            double derivative = initialized ? (error - previousError) / dtSteps : 0.0;
            double proportional = kp * error;
            double derivativeTerm = kd * derivative;

            // 条件积分：输出已顶到上/下限且误差仍指向饱和方向时不再累积，避免积分饱和后长时间退不下来。
            double tentative = proportional + ki * integral + derivativeTerm;
            boolean saturatingHigh = tentative >= 1.0 && error > 0.0;
            boolean saturatingLow = tentative <= 0.0 && error < 0.0;
            if (!saturatingHigh && !saturatingLow) {
                integral = clamp(integral + error * dtSteps, -integralCap, integralCap);
            }
            previousError = error;
            initialized = true;
            return clamp(proportional + ki * integral + derivativeTerm, 0.0, 1.0);
        }
    }
}
