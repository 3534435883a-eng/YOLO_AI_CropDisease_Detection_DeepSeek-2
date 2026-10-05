package com.example.Ece.agent.m3;

/** Simplified FSP-inspired thermal internode model, not a reproduction of the full GroIMP model. */
public final class ThermalInternodeHeightModel {
    public static final double ELONGATION_K = 0.02;
    public static final double MIDPOINT_GDD = 150;
    private ThermalInternodeHeightModel() {}
    private static double sigmoid(double x) { return 1.0/(1.0+Math.exp(-x)); }
    public static double heightAtAge(double age,double phyllochron,double maxLengthCm) {
        if(age<=0)return 0;
        double atBirth=sigmoid(-ELONGATION_K*MIDPOINT_GDD), sum=0;
        for(int j=0;j<=Math.floor(age/phyllochron);j++) {
            double nodeAge=age-j*phyllochron;
            sum+=maxLengthCm*(sigmoid(ELONGATION_K*(nodeAge-MIDPOINT_GDD))-atBirth)/(1-atBirth);
        }
        return sum;
    }
    public static double initialThermalAge(double height,double phyllochron,double maxLengthCm) {
        double low=0, high=1000;
        while(heightAtAge(high,phyllochron,maxLengthCm)<height && high<32000)high*=2;
        for(int i=0;i<50;i++) {
            double mid=(low+high)/2;
            if(heightAtAge(mid,phyllochron,maxLengthCm)<height)low=mid;else high=mid;
        }
        return (low+high)/2;
    }
    public static double predict(double initialHeight,double gdd,double phyllochron,double maxLengthCm) {
        return heightAtAge(initialThermalAge(initialHeight,phyllochron,maxLengthCm)+gdd,phyllochron,maxLengthCm);
    }
}

