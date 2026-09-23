package com.hackalem.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
// Tests drive workers explicitly; background jobs must not race fixture cleanup.
@Configuration @EnableScheduling @Profile("!test")
public class WorkerConfig {}
