package com.orderfulfillment.common.kafka;

/**
 * One Kafka listener's state, as returned by every service's {@code GET /demo/consumers} — the
 * frozen {@code ConsumerState} schema in each service's OpenAPI document.
 *
 * <p>An API response shape, not a persistence entity: it is shared across the four services because
 * their OpenAPI documents define it identically, and four hand-written copies would be four chances
 * to spell {@code groupId} differently.
 *
 * @param name    the logical listener name — the {@code id} of the {@code @KafkaListener}, and the
 *                {@code consumerName} path variable of the pause/resume endpoints
 * @param topic   the topic it consumes
 * @param groupId its consumer group
 * @param paused  whether the container is <em>actually</em> paused (not merely asked to be)
 */
public record ConsumerState(String name, String topic, String groupId, boolean paused) {
}
