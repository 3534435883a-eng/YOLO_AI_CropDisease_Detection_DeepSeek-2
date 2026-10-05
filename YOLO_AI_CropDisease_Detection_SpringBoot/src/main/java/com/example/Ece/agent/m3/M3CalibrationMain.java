package com.example.Ece.agent.m3;
import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.fasterxml.jackson.databind.*;
/** Headless production calibration entry point; does not start DB or web services. */
public class M3CalibrationMain {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: M3CalibrationMain <dataset-directory>");
        ObjectMapper mapper=new ObjectMapper();
        M3MultiYearCalibrationService service=new M3MultiYearCalibrationService(new M3ObservationService(mapper,args[0]),new TomatoCropGrowthModel(),mapper);
        JsonNode result=service.calibrate();
        System.out.println("version="+result.path("version").asText());
        System.out.println("parameters="+result.path("parameters"));
        for(JsonNode s:result.path("seasons")) {
            System.out.println("year="+s.path("year")+" original="+s.path("scores").path("original").path("rmse")
                +" corrected="+s.path("scores").path("corrected").path("rmse")+" linear="+s.path("scores").path("linear").path("rmse"));
        }
        System.out.println("status="+result.path("status").asText());
    }
}

