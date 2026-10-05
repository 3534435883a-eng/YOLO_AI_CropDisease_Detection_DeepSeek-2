package com.example.Ece.agent.m3;

/** One-dimensional EKF over effective thermal age; parameters remain frozen. */
public final class OnlineInternodeFilter {
    private final double p, length;
    private double age, variance;
    public static final double MEASUREMENT_VARIANCE_CM2 = 25;
    public OnlineInternodeFilter(double initialHeight, double p, double length) {
        this.p=p; this.length=length;
        age=ThermalInternodeHeightModel.initialThermalAge(initialHeight,p,length);
        double d=derivative(age,p,length);
        if(!Double.isFinite(d)||d<1e-6)throw new IllegalArgumentException("初态导数过小");
        variance=MEASUREMENT_VARIANCE_CM2/(d*d);
    }
    public static double derivative(double age,double p,double length) {
        double k=ThermalInternodeHeightModel.ELONGATION_K, m=ThermalInternodeHeightModel.MIDPOINT_GDD;
        double birth=1/(1+Math.exp(k*m)), sum=0;
        for(int j=0;j<=Math.floor(age/p);j++) {
            double sigmoid=1/(1+Math.exp(-k*(age-j*p-m)));
            sum+=length*k*sigmoid*(1-sigmoid)/(1-birth);
        }
        return sum;
    }
    public void predict(double gdd,double days,boolean estimated) {
        double d=derivative(age,p,length);
        if(!Double.isFinite(d)||d<1e-6)throw new IllegalStateException("预测导数无效");
        age+=gdd;
        variance+=4*days*(estimated?4:1)/(d*d);
        if(!Double.isFinite(age)||!Double.isFinite(variance))throw new IllegalStateException("在线状态无效");
    }
    public Update update(double observed) {
        double before=height(), d=derivative(age,p,length);
        if(!Double.isFinite(d)||d<1e-6)throw new IllegalStateException("观测导数过小，停止更新");
        double s=d*d*variance+MEASUREMENT_VARIANCE_CM2, gain=variance*d/s;
        double innovation=observed-before;
        age=Math.max(0,age+gain*innovation);
        variance=(1-gain*d)*(1-gain*d)*variance+gain*gain*MEASUREMENT_VARIANCE_CM2;
        if(!Double.isFinite(variance)||variance<0)throw new IllegalStateException("更新方差无效");
        return new Update(before,observed,height(),gain,gain*d,innovation,Math.sqrt(s));
    }
    public double height(){return ThermalInternodeHeightModel.heightAtAge(age,p,length);}
    public double age(){return age;}
    public double variance(){return variance;}
    public static final class Update {
        public final double before,observed,after,gain,measurementWeight,innovation,innovationStd;
        Update(double before,double observed,double after,double gain,double weight,double innovation,double std) {
            this.before=before;this.observed=observed;this.after=after;this.gain=gain;
            measurementWeight=weight;this.innovation=innovation;innovationStd=std;
        }
    }
}
