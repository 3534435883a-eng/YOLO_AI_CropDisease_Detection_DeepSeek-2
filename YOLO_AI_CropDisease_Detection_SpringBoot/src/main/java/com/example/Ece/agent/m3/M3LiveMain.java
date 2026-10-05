package com.example.Ece.agent.m3;

import com.example.Ece.agent.crop.TomatoCropGrowthModel;
import com.fasterxml.jackson.databind.*;

/** Executes an authorized full business replay without web or database startup. */
public final class M3LiveMain {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("数据目录参数缺失");
        ObjectMapper mapper=new ObjectMapper();TomatoCropGrowthModel crop=new TomatoCropGrowthModel();
        M3ObservationService data=new M3ObservationService(mapper,args[0]);
        M3MultiYearCalibrationService calibration=new M3MultiYearCalibrationService(data,crop,mapper);
        M3LiveService live=new M3LiveService(data,calibration,crop,mapper);
        JsonNode run=live.start(2025);String id=run.path("runId").asText();int events=0;
        while(!run.path("finished").asBoolean()) {
            run=live.step(id,run.path("cursor").asInt(),48);events+=run.path("events").size();
        }
        com.fasterxml.jackson.databind.node.ObjectNode summary=mapper.createObjectNode();
        for(String key:new String[]{"year","cursor","updateCount","scores"})summary.set(key,run.path(key));
        summary.put("eventCount",events);summary.set("lastRisk",run.path("current").path("risk"));
        java.nio.file.Path output=data.root().resolve("runs/online-replay-20261002.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),live.current(id));
        summary.put("savedResult",output.toString());
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        live.delete(id);
    }
}
