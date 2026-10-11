package com.selene.onestepflight.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.cfg.DateTimeFeature;

@Configuration(proxyBeanMethods=false)
public class FlightJsonConfiguration {
    @Bean JsonMapperBuilderCustomizer preserveAirportOffsets() {
        return builder->builder.disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE,
                DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE);
    }
}
