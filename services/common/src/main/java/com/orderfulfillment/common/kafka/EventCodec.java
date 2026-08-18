package com.orderfulfillment.common.kafka;

import com.orderfulfillment.common.events.EventEnvelope;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Consumer-side counterpart to {@link EventPublisher}. Decodes the envelope with {@code payload}
 * left as a raw {@link JsonNode} (a topic can carry more than one eventType, so the concrete
 * payload type isn't known until {@code eventType} has been read), then converts the payload node
 * to a concrete type once the caller's {@code switch} on eventType has picked one.
 *
 * <p>Jackson 3 (Spring Boot 4.1's baseline — {@code tools.jackson.*}, not the older
 * {@code com.fasterxml.jackson.databind}/{@code .core} packages) makes {@code ObjectMapper}'s
 * read/write methods throw the unchecked {@code tools.jackson.core.JacksonException} instead of a
 * checked exception, so no try/catch is needed here.
 */
@Component
public class EventCodec {

    private final ObjectMapper objectMapper;

    public EventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EventEnvelope<JsonNode> decode(String json) {
        EventEnvelope<JsonNode> envelope = objectMapper.readValue(json, new TypeReference<>() {
        });
        if (envelope.eventVersion() != EventTypes.CURRENT_VERSION) {
            throw new UnsupportedEventVersionException(envelope.eventType(), envelope.eventVersion());
        }
        return envelope;
    }

    public <T> T payloadAs(EventEnvelope<JsonNode> envelope, Class<T> payloadType) {
        return objectMapper.treeToValue(envelope.payload(), payloadType);
    }
}
