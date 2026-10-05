package com.example.Ece.agent.m3;
import com.example.Ece.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/m3")
public class M3Controller {
    private final M3ObservationService observations;
    private final M3MultiYearCalibrationService calibration;
    public M3Controller(M3ObservationService observations,M3MultiYearCalibrationService calibration) {
        this.observations=observations;this.calibration=calibration;
    }
    @GetMapping("/observations") public Result<?> observations() {
        try{return Result.success(observations.summary());}catch(IOException e){return Result.error("M3_DATA_UNAVAILABLE",e.getMessage());}
    }
    @GetMapping("/result") public Result<?> result() {
        try{return Result.success(calibration.latest());}catch(IOException e){return Result.error("M3_RESULT_UNAVAILABLE",e.getMessage());}
    }
    @PostMapping("/calibrate") public Result<?> calibrate() {
        try{return Result.success(calibration.calibrate());}catch(IOException e){return Result.error("M3_CALIBRATION_BLOCKED",e.getMessage());}
    }
}
