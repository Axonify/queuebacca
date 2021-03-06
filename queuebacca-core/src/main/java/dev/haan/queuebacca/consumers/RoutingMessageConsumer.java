/*
 * Copyright 2020 The Queuebacca Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.haan.queuebacca.consumers;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import dev.haan.queuebacca.exceptions.QueuebaccaConfigurationException;
import dev.haan.queuebacca.Message;
import dev.haan.queuebacca.MessageBin;
import dev.haan.queuebacca.MessageConsumer;
import dev.haan.queuebacca.MessageContext;
import dev.haan.queuebacca.MessageResponse;

/**
 * A {@link MessageConsumer} used to route a {@link Message} to a another, registered {@link MessageConsumer}.
 * This allows multiple message types to be published and subscribed for a single {@link MessageBin}.
 *
 * @param <M> the message type
 */
public final class RoutingMessageConsumer<M extends Message> implements MessageConsumer<M> {

    private final Map<Class<?>, Class<?>> routingMap = new ConcurrentHashMap<>();
    private final Map<Class<?>, MessageConsumer<? extends M>> consumers;

    private RoutingMessageConsumer(Map<Class<?>, MessageConsumer<? extends M>> consumers) {
        this.consumers = new HashMap<>(requireNonNull(consumers));
    }

    /**
     * Creates a new {@link RoutingMessageConsumer.Builder}.
     */
    public static <M extends Message> Builder<M> builder() {
        return new Builder<>();
    }

    /**
     * {@inheritDoc}
     * <p>
     *    This consumer will route the {@link Message} to the appropriately registered {@link MessageConsumer}.
     * </p>
     * @throws QueuebaccaConfigurationException if no consumer is available for the provided message type
     * @return a {@link MessageResponse} indicating how to handle the {@link Message} after it's been consumed
     */
    @Override
    public MessageResponse consume(M message, MessageContext messageContext) {
        requireNonNull(message);
        requireNonNull(messageContext);

        MessageConsumer<M> consumer = findConsumer(message.getClass())
                .orElseThrow(() -> new QueuebaccaConfigurationException("No consumer available for message '" +  message.getClass().getName() + "'"));
        return consumer.consume(message, messageContext);
    }

    @SuppressWarnings("unchecked")
    private Optional<MessageConsumer<M>> findConsumer(Class<?> messageType) {
        Class<?> mappedMessageType = routingMap.computeIfAbsent(messageType, this::mapMessageType);
        return Optional.ofNullable(mappedMessageType)
                .map(mt -> (MessageConsumer<M>) consumers.get(mt));
    }

    private Class<?> mapMessageType(Class<?> messageType) {
        if (messageType == null) {
            return null;
        } else if (consumers.containsKey(messageType)) {
            return messageType;
        } else {
            return Stream.of(messageType.getInterfaces())
                    .filter(consumers::containsKey)
                    .findFirst()
                    .orElseGet(() -> mapMessageType(messageType.getSuperclass()));
        }
    }

    /**
     * A builder for {@link RoutingMessageConsumer RoutingMessageConsumers}.
     *
     * @param <M> the message type
     */
    public static class Builder<M extends Message> {

        private final Map<Class<?>, MessageConsumer<? extends M>> consumers = new HashMap<>();

        private Builder() {}

        /**
         * Registers a {@link Message} type to be routed to a given {@link MessageConsumer}.
         *
         * @param messageType the message type to route
         * @param consumer the consumer to route to
         * @param <T> the message type
         * @return this object for method chaining
         * @throws QueuebaccaConfigurationException if a consumer has already been registered for a given message type
         */
        public <T extends M> Builder<M> registerMessageRoute(Class<T> messageType, MessageConsumer<T> consumer) {
            requireNonNull(messageType);
            requireNonNull(consumer);

            String messageTypeName = messageType.getName();
            if (consumers.containsKey(messageType)) {
                throw new QueuebaccaConfigurationException("A message consumer for type '" + messageTypeName + "' has already been registered...");
            }

            consumers.put(messageType, consumer);
            return this;
        }

        /**
         * Creates an instance of {@link RoutingMessageConsumer}.
         *
         * @return the subscription configuration
         */
        public RoutingMessageConsumer<M> build() {
            return new RoutingMessageConsumer<>(consumers);
        }
    }
}
