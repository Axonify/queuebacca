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

package dev.haan.queuebacca.sqs;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AbortedException;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.ChangeMessageVisibilityRequest;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import dev.haan.queuebacca.Client;
import dev.haan.queuebacca.IncomingEnvelope;
import dev.haan.queuebacca.Message;
import dev.haan.queuebacca.MessageBin;
import dev.haan.queuebacca.OutgoingEnvelope;
import dev.haan.queuebacca.exceptions.QueuebaccaException;
import dev.haan.queuebacca.util.MessageSerializer;

/**
 * An SQS implementation of {@link Client}.
 */
public final class SqsClient implements Client {

    private static final ScheduledExecutorService REFRESH_SCHEDULER;

    static {
        ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder()
                .setNameFormat("queuebacca-sqs-messageTimeoutRefresher")
                .build());
        scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
        Runtime.getRuntime().addShutdownHook(new Thread(scheduledThreadPoolExecutor::shutdownNow));

        REFRESH_SCHEDULER = scheduledThreadPoolExecutor;
    }

    private static final String APPROXIMATE_RECEIVE_COUNT_ATTRIBUTE = "ApproximateReceiveCount";
    private static final String APPROXIMATE_FIRST_RECEIVE_TIMESTAMP_ATTRIBUTE = "ApproximateFirstReceiveTimestamp";

    private static final int MAX_READ_COUNT = 10;

    private final MessageRefresher refresher = new MessageRefresher();
    private final AmazonSQS client;
    private final MessageSerializer serializer;
    private final SqsCourierMessageBinRegistry messageBinRegistry;

    /**
     * Creates a new instance of a {@link SqsClient} with the given {@link AmazonSQS client}, {@link MessageSerializer},
     * and {@link SqsCourierMessageBinRegistry}.
     *
     * @param client the AWS SQS client
     * @param serializer a serializer for turning {@link Message Messages} into strings
     * @param messageBinRegistry a registry containing {@link MessageBin} mappings to SQS queue urls
     */
    public SqsClient(AmazonSQS client, MessageSerializer serializer, SqsCourierMessageBinRegistry messageBinRegistry) {
        this.client = requireNonNull(client);
        this.serializer = requireNonNull(serializer);
        this.messageBinRegistry = requireNonNull(messageBinRegistry);
    }

    /**
     * {@inheritDoc}
     * @throws QueuebaccaException if the message exceeds the max SQS size of 256KBs
     */
    @Override
    public <M extends Message> OutgoingEnvelope<M> sendMessage(MessageBin messageBin, M message, int delay) {
        requireNonNull(messageBin);
        requireNonNull(message);

        String messageBody = serializer.toString(message);

        SendMessageRequest sqsRequest = new SendMessageRequest()
                .withQueueUrl(messageBinRegistry.getQueueUrl(messageBin))
                .withMessageBody(messageBody)
                .withDelaySeconds(delay);
        String messageId = client.sendMessage(sqsRequest).getMessageId();

        LoggerFactory.getLogger(messageBin.getName()).info("Sent SQS message '{}'", messageId);

        return new OutgoingEnvelope<>(messageId, message, messageBody);
    }

    /**
     * {@inheritDoc}
     * <p>
     *     <b>NOTE:</b> Messages will be sent off in batches of 10 due SQS restriction.
     * </p>
     * @throws QueuebaccaException if a single message exceeds the max size of 25KBs for a batch (256KB total max size)
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
    public <M extends Message> Collection<IncomingEnvelope<M>> retrieveMessages(MessageBin messageBin, int maxMessages) throws InterruptedException {
        requireNonNull(messageBin);
        ReceiveMessageRequest sqsRequest = new ReceiveMessageRequest()
                .withQueueUrl(messageBinRegistry.getQueueUrl(messageBin))
                .withMaxNumberOfMessages(Math.min(MAX_READ_COUNT, maxMessages))
                .withWaitTimeSeconds(20)
                .withAttributeNames(APPROXIMATE_RECEIVE_COUNT_ATTRIBUTE, APPROXIMATE_FIRST_RECEIVE_TIMESTAMP_ATTRIBUTE);

        Collection<IncomingEnvelope<M>> messages = new ArrayList<>();
        try {
            for (com.amazonaws.services.sqs.model.Message sqsMessage : client.receiveMessage(sqsRequest).getMessages()) {
                LoggerFactory.getLogger(messageBin.getName()).info("Received SQS message '{}'", sqsMessage.getMessageId());
                IncomingEnvelope<M> message = mapSqsMessage(sqsMessage);
                messages.add(message);
            }
        } catch (AbortedException e) {
            LoggerFactory.getLogger(messageBin.getName()).debug("receiveMessage was aborted", e);
            throw new InterruptedException("Message retrieval was interrupted");
        }

        messages.forEach(envelope -> refresher.scheduleRefresh(
                envelope,
                messageBinRegistry.getQueueUrl(messageBin),
                messageBinRegistry.getVisibilityTimeout(messageBin),
                LoggerFactory.getLogger(messageBin.getName()))
        );
        return messages;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void returnMessage(MessageBin messageBin, IncomingEnvelope<?> incomingEnvelope, int delay) {
        requireNonNull(messageBin);
        requireNonNull(incomingEnvelope);

        refresher.cancelRefresh(incomingEnvelope);

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
        requireNonNull(messageBin);
        requireNonNull(incomingEnvelope);

        refresher.cancelRefresh(incomingEnvelope);

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
                Integer.parseInt(sqsMessage.getAttributes().get(APPROXIMATE_RECEIVE_COUNT_ATTRIBUTE)),
                Instant.ofEpochMilli(Long.parseLong(sqsMessage.getAttributes().get(APPROXIMATE_FIRST_RECEIVE_TIMESTAMP_ATTRIBUTE))),
                serializer.fromString(sqsMessage.getBody(), Message.class),
                sqsMessage.getBody()
        );
    }

    private class MessageRefresher {

        private final Map<IncomingEnvelope, Future<?>> futures = new ConcurrentHashMap<>();

        void scheduleRefresh(IncomingEnvelope<?> envelope, String queueUrl, Duration visibilityTimeout, Logger logger) {
            Duration delay = visibilityTimeout.toMinutes() < 2
                    ? visibilityTimeout.dividedBy(2)
                    : visibilityTimeout.minusMinutes(1);
            Future<?> future = REFRESH_SCHEDULER.schedule(() -> refreshMessages(envelope, queueUrl, visibilityTimeout, logger), delay.toMillis(), TimeUnit.MILLISECONDS);
            futures.put(envelope, future);
        }

        void cancelRefresh(IncomingEnvelope<?> envelope) {
            futures.remove(envelope).cancel(true);
        }

        private void refreshMessages(IncomingEnvelope<?> envelope, String queueUrl, Duration visibilityTimeout, Logger logger) {
            logger.info("Refreshing SQS message '{}' for '{}' seconds", envelope.getMessageId(), visibilityTimeout.getSeconds());
            ChangeMessageVisibilityRequest sqsRequest = new ChangeMessageVisibilityRequest()
                    .withQueueUrl(queueUrl)
                    .withReceiptHandle(envelope.getReceipt())
                    .withVisibilityTimeout((int) visibilityTimeout.getSeconds());
            client.changeMessageVisibility(sqsRequest);

            scheduleRefresh(envelope, queueUrl, visibilityTimeout, logger);
        }
    }
}

