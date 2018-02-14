/*
 * Copyright 2018 The QueueBacca Authors
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

package io.axonif.queuebacca.sqs;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.ChangeMessageVisibilityRequest;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import io.axonif.queuebacca.Client;
import io.axonif.queuebacca.IncomingEnvelope;
import io.axonif.queuebacca.Message;
import io.axonif.queuebacca.MessageBin;
import io.axonif.queuebacca.OutgoingEnvelope;
import io.axonif.queuebacca.exceptions.QueueBaccaException;
import io.axonif.queuebacca.util.MessageSerializer;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import static java.util.Objects.requireNonNull;

/**
 * An SQS implementation of {@link Client}.
 */
public final class SqsClient implements Client {

    private static final String APPROXIMATE_RECEIVE_COUNT_ATTRIBUTE = "ApproximateReceiveCount";
    private static final String APPROXIMATE_FIRST_RECEIVE_TIMESTAMP_ATTRIBUTE = "ApproximateFirstReceiveTimestamp";

    public static final int DEFAULT_SQS_CONNECTIONS = 200;
    public static final String DEFAULT_SQS_CONNECTIONS_PROPERTY = "maxConnections";

    public static final long MAX_MESSAGE_SIZE_KB = 1024 * 256; // 256KB
    public static final int MAX_READ_COUNT = 10;

    private final AmazonSQS client;
    private final MessageSerializer serializer;
    private final SqsCourierMessageBinRegistry messageBinRegistry;

    /**
     * Creates a new instance of a {@link SqsClient} with the given {@link AmazonSQS client}, {@link MessageSerializer},
     * and {@link SqsCourierMessageBinRegistry}.
     *
     * @param client the AWS SQS client
     * @param serializer a serializer for turning {@link Message ViaMessages} into strings
     * @param messageBinRegistry a registry containing {@link MessageBin} mappings to SQS queue urls
     */
    public SqsClient(AmazonSQS client, MessageSerializer serializer, SqsCourierMessageBinRegistry messageBinRegistry) {
        this.client = requireNonNull(client);
        this.serializer = requireNonNull(serializer);
        this.messageBinRegistry = requireNonNull(messageBinRegistry);
    }

    /**
     * {@inheritDoc}
     * @throws QueueBaccaException if the message exceeds the max SQS size of 256KBs
     */
    @Override
    public <M extends Message> OutgoingEnvelope<M> sendMessage(MessageBin messageBin, M message, int delay) {
        requireNonNull(messageBin);
        requireNonNull(message);

        String messageBody = serializer.toString(message);
        int messageSize = messageBody.getBytes().length;
        if (messageSize > MAX_MESSAGE_SIZE_KB) {
            throw new QueueBaccaException("Message exceeds max size of {0}B ({1}B): {2}", MAX_MESSAGE_SIZE_KB, messageSize, messageBody);
        }

        SendMessageRequest sqsRequest = new SendMessageRequest()
                .withQueueUrl(messageBinRegistry.getQueueUrl(messageBin))
                .withMessageBody(messageBody)
                .withDelaySeconds(delay);
        String messageId = client.sendMessage(sqsRequest).getMessageId();

        LoggerFactory.getLogger(messageBin.getName()).info("Sent SQS message '{}'", messageId);

        return new OutgoingEnvelope<>(messageId, message);
    }

    /**
     * {@inheritDoc}
     * <p>
     *     <b>NOTE:</b> Messages will be sent off in batches of 10 due SQS restriction.
     * </p>
     * @throws QueueBaccaException if a single message exceeds the max size of 25KBs for a batch (256KB total max size)
     */
    @Override
    public <M extends Message> Collection<OutgoingEnvelope<M>> sendMessages(MessageBin messageBin, Collection<M> messages, int delay) {
        requireNonNull(messageBin);
        requireNonNull(messages);

        if (messages.isEmpty()) {
            return Collections.emptyList();
        }

        SqsMessageBatchSender<M> batchSender = new SqsMessageBatchSender<>(client, serializer, LoggerFactory.getLogger(messageBin.getName()));
        messages.forEach(batchSender::add);
        return batchSender.send(messageBinRegistry.getQueueUrl(messageBin), delay);
    }

    /**
     * {@inheritDoc}
     * <p>
     *     <b>NOTE:</b> At most 10 messages will be retrieved, despite what max messages is set to, due to SQS restrictions.
     * </p>
     */
    @Override
    public <M extends Message> Collection<IncomingEnvelope<M>> retrieveMessages(MessageBin messageBin, int maxMessages) {
        ReceiveMessageRequest sqsRequest = new ReceiveMessageRequest()
                .withQueueUrl(messageBinRegistry.getQueueUrl(messageBin))
                .withMaxNumberOfMessages(Math.min(MAX_READ_COUNT, maxMessages))
                .withWaitTimeSeconds(20)
                .withAttributeNames(APPROXIMATE_RECEIVE_COUNT_ATTRIBUTE, APPROXIMATE_FIRST_RECEIVE_TIMESTAMP_ATTRIBUTE);
        Collection<IncomingEnvelope<M>> messages = new ArrayList<>();
        for (com.amazonaws.services.sqs.model.Message sqsMessage : client.receiveMessage(sqsRequest).getMessages()) {
            LoggerFactory.getLogger(messageBin.getName()).info("Received SQS message '{}'", sqsMessage.getMessageId());
            IncomingEnvelope<M> message = mapSqsMessage(sqsMessage);
            messages.add(message);
        }
        return messages;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void returnMessage(MessageBin messageBin, IncomingEnvelope<?> incomingEnvelope, int delay) {
        requireNonNull(messageBin);
        requireNonNull(incomingEnvelope);

        ChangeMessageVisibilityRequest sqsRequest = new ChangeMessageVisibilityRequest()
                .withQueueUrl(messageBinRegistry.getQueueUrl(messageBin))
                .withReceiptHandle(requireNonNull(incomingEnvelope.getReceipt()))
                .withVisibilityTimeout(delay);
        client.changeMessageVisibility(sqsRequest);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void disposeMessage(MessageBin messageBin, IncomingEnvelope<?> incomingEnvelope) {
        DeleteMessageRequest sqsRequest = new DeleteMessageRequest()
                .withQueueUrl(messageBinRegistry.getQueueUrl(messageBin))
                .withReceiptHandle(requireNonNull(incomingEnvelope.getReceipt()));
        client.deleteMessage(sqsRequest);
    }

    @SuppressWarnings("unchecked")
    private <M extends Message> IncomingEnvelope<M> mapSqsMessage(com.amazonaws.services.sqs.model.Message sqsMessage) {
        return new IncomingEnvelope(
                sqsMessage.getMessageId(),
                sqsMessage.getReceiptHandle(),
                Integer.valueOf(sqsMessage.getAttributes().get(APPROXIMATE_RECEIVE_COUNT_ATTRIBUTE)),
                Instant.ofEpochMilli(Long.valueOf(sqsMessage.getAttributes().get(APPROXIMATE_FIRST_RECEIVE_TIMESTAMP_ATTRIBUTE))),
                serializer.fromString(sqsMessage.getBody(), Message.class)
        );
    }
}

