package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.scenario.clients.ConsumerControlClient;
import com.orderfulfillment.scenario.clients.OrderServiceClient;
import com.orderfulfillment.scenario.clients.PaymentServiceClient;
import com.orderfulfillment.scenario.domain.EventRecordRepository;
import com.orderfulfillment.scenario.runtime.OrderStatusWatcher;
import com.orderfulfillment.scenario.runtime.TimelineRecorder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Bundles the dependencies every {@link AbstractScenarioRunner} needs, so each concrete scenario's
 * constructor stays a one-liner instead of eight repeated parameters. */
@Component
public record ScenarioToolkit(
        OrderServiceClient orderServiceClient,
        ConsumerControlClient consumerControlClient,
        PaymentServiceClient paymentServiceClient,
        TimelineRecorder timelineRecorder,
        OrderStatusWatcher orderStatusWatcher,
        EventRecordRepository eventRecordRepository,
        KafkaTemplate<String, String> kafkaTemplate,
        ObjectMapper objectMapper) {
}
