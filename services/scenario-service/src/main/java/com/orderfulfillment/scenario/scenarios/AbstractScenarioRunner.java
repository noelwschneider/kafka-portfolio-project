package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.scenario.clients.ConsumerControlClient;
import com.orderfulfillment.scenario.clients.OrderServiceClient;
import com.orderfulfillment.scenario.clients.PaymentServiceClient;
import com.orderfulfillment.scenario.domain.EventRecordRepository;
import com.orderfulfillment.scenario.domain.TimelineKind;
import com.orderfulfillment.scenario.runtime.OrderStatusWatcher;
import com.orderfulfillment.scenario.runtime.TimelineRecorder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/** Shared plumbing every {@link ScenarioRunner} needs — thin wrappers around HTTP-with-timeline-recording. */
abstract class AbstractScenarioRunner implements ScenarioRunner {

    protected final OrderServiceClient orderServiceClient;
    protected final ConsumerControlClient consumerControlClient;
    protected final PaymentServiceClient paymentServiceClient;
    protected final TimelineRecorder timelineRecorder;
    protected final OrderStatusWatcher orderStatusWatcher;
    protected final EventRecordRepository eventRecordRepository;
    protected final KafkaTemplate<String, String> kafkaTemplate;
    protected final ObjectMapper objectMapper;

    protected AbstractScenarioRunner(ScenarioToolkit toolkit) {
        this.orderServiceClient = toolkit.orderServiceClient();
        this.consumerControlClient = toolkit.consumerControlClient();
        this.paymentServiceClient = toolkit.paymentServiceClient();
        this.timelineRecorder = toolkit.timelineRecorder();
        this.orderStatusWatcher = toolkit.orderStatusWatcher();
        this.eventRecordRepository = toolkit.eventRecordRepository();
        this.kafkaTemplate = toolkit.kafkaTemplate();
        this.objectMapper = toolkit.objectMapper();
    }

    /** Creates an order and records the HTTP timeline entry with the real status code, per the OpenAPI
     * doc's "the run timeline shows the HTTP 201 returning before the downstream events". */
    protected OrderServiceClient.OrderCreationResult createOrder(
            String runId, String sku, int quantity, String customerId) {
        List<Map<String, Object>> items = List.of(Map.of("sku", sku, "quantity", quantity));
        OrderServiceClient.OrderCreationResult result = orderServiceClient.createOrder(customerId, items);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("statusCode", result.statusCode());
        if (result.orderId() != null) {
            detail.put("orderId", result.orderId());
        }
        timelineRecorder.append(runId, TimelineKind.HTTP, "POST /api/orders", detail);
        return result;
    }

    /** Records a control-plane HTTP call (pause/resume/payment-behavior) with its real status code. */
    protected void recordHttp(String runId, String label, int statusCode) {
        timelineRecorder.append(runId, TimelineKind.HTTP, label, Map.of("statusCode", statusCode));
    }
}
