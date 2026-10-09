package com.example.Ece.agent.m3;

/** One-dimensional EKF over effective thermal age; parameters remain frozen. */
public final class OnlineInternodeFilter {
    private final double p, length;
    private double age, variance;
    // Matches the initial-age solver's numerical range; it is not an agronomic threshold.
    private static final double MAX_THERMAL_AGE = 32000;
    public static final double MEASUREMENT_VARIANCE_CM2 = 25;
    public OnlineInternodeFilter(double initialHeight, double p, double length) {
        validateParameters(p,length);
        if(!Double.isFinite(initialHeight)||initialHeight<0)throw new IllegalArgumentException("初始株高须为非负有限数");
        this.p=p; this.length=length;
        age=ThermalInternodeHeightModel.initialThermalAge(initialHeight,p,length);
        if(Math.abs(ThermalInternodeHeightModel.heightAtAge(age,p,length)-initialHeight)>1e-6)
            throw new IllegalArgumentException("初始株高超出热龄求解范围");
        double d=derivative(age,p,length);
        if(!Double.isFinite(d)||d<1e-6)throw new IllegalArgumentException("初态导数过小");
        variance=MEASUREMENT_VARIANCE_CM2/(d*d);
    }
    public static double derivative(double age,double p,double length) {
        validateParameters(p,length);
        validateState(age,0);
        double k=ThermalInternodeHeightModel.ELONGATION_K, m=ThermalInternodeHeightModel.MIDPOINT_GDD;
        double birth=1/(1+Math.exp(k*m)), sum=0;
        for(int j=0;j<=Math.floor(age/p);j++) {
            double sigmoid=1/(1+Math.exp(-k*(age-j*p-m)));
            sum+=length*k*sigmoid*(1-sigmoid)/(1-birth);
        }
        return sum;
    }
    public void predict(double gdd,double days,boolean estimated) {
        if(!Double.isFinite(gdd)||gdd<0||!Double.isFinite(days)||days<0)
            throw new IllegalArgumentException("积温及预测天数须为非负有限数");
        double d=derivative(age,p,length);
        if(!Double.isFinite(d)||d<1e-6)throw new IllegalStateException("预测导数无效");
        double nextAge=age+gdd, nextVariance=variance+4*days*(estimated?4:1)/(d*d);
        validateState(nextAge,nextVariance);
        age=nextAge;variance=nextVariance;
    }
    public Update update(double observed) {
        if(!Double.isFinite(observed)||observed<0)throw new IllegalArgumentException("观测株高须为非负有限数");
        double before=height(), d=derivative(age,p,length);
        if(!Double.isFinite(d)||d<1e-6)throw new IllegalStateException("观测导数过小，停止更新");
        double s=d*d*variance+MEASUREMENT_VARIANCE_CM2, gain=variance*d/s;
        double innovation=observed-before;
        double nextAge=Math.max(0,age+gain*innovation);
        double nextVariance=(1-gain*d)*(1-gain*d)*variance+gain*gain*MEASUREMENT_VARIANCE_CM2;
        validateState(nextAge,nextVariance);
        age=nextAge;variance=nextVariance;
        return new Update(before,observed,height(),gain,gain*d,innovation,Math.sqrt(s));
    }
    public double height(){return ThermalInternodeHeightModel.heightAtAge(age,p,length);}
    public double age(){return age;}
    public double variance(){return variance;}
    /** Resume the exact consumed filter state without inventing another measurement. */
    public void restore(double savedAge,double savedVariance) {
        validateState(savedAge,savedVariance);
        age=savedAge;variance=savedVariance;
    }
    private static void validateParameters(double p,double length) {
        if(!Double.isFinite(p)||p<1||!Double.isFinite(length)||length<=0)
            throw new IllegalArgumentException("节位间隔须为至少1°C·d的有限数，最大节间长度须为正有限数");
    }
    private static void validateState(double age,double variance) {
        if(!Double.isFinite(age)||age<0||age>MAX_THERMAL_AGE||!Double.isFinite(variance)||variance<0)
            throw new IllegalArgumentException("滤波状态超出有效数值范围");
    }
    public static final class Update {
        public final double before,observed,after,gain,measurementWeight,innovation,innovationStd;
        Update(double before,double observed,double after,double gain,double weight,double innovation,double std) {
            this.before=before;this.observed=observed;this.after=after;this.gain=gain;
            measurementWeight=weight;this.innovation=innovation;innovationStd=std;
        }
    }
}
