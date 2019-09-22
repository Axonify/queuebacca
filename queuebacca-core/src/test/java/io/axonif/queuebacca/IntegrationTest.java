/*
 * Copyright 2019 The Queuebacca Authors
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

package io.axonif.queuebacca;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.axonif.queuebacca.publishing.Publisher;
import io.axonif.queuebacca.publishing.Publishing;
import io.axonif.queuebacca.retries.ConstantRetryDelay;

public class IntegrationTest {

	private ExceptionResolver exceptionResolver = ExceptionResolver.builder().build();

	@Test
	public void subscribe() throws InterruptedException {
		TestMessageSerializer testMessageSerializer = new TestMessageSerializer();

		MessageBin messageBin = new MessageBin("test");
		TestClient client = new TestClient();
		Subscriber subscriber = Subscribing.subscriber(client)
				.withMessageSerializer(testMessageSerializer)
				.withExceptionResolver(exceptionResolver)
				.build();

		MessageConsumer<TestMessage> consumer = MessageConsumer.basic(TestMessage::markComplete);
		SubscriptionConfiguration<TestMessage> configuration = SubscriptionConfiguration.builder(messageBin, TestMessage.class, consumer)
				// Note: This test runs very fast and can cause ThreadPoolExecutor to hit a race condition and throw
				// RejectedExecutionException if the threadpool size is too small. This has not been an issue in prod
				// because there are natural delays (SQS, database access). To stabilize the test, we set the capacity
				// here to a number that will result in a max threadpool size > the number of messages we're queuing.
				// See ThreadPoolWorkExecutor.newPooledWorkExecutor() for how the max is computed and configured.
				.withMessageCapacity(10)
				.build();
		subscriber.subscribe(configuration);

		Publisher publisher = Publishing.publisher(client)
				.withMessageSerializer(testMessageSerializer)
				.build();

		CountDownLatch countDownLatch = new CountDownLatch(10);
		Collection<TestMessage> messages = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			messages.add(new TestMessage(countDownLatch::countDown));
		}
		publisher.publish(messageBin, messages);

		// If a message doesn't finish, this will timeout
		countDownLatch.await(10, TimeUnit.SECONDS);

		subscriber.cancelAll();
	}

	@Test
	public void subscribe_FailedMessage() throws InterruptedException {
		TestMessageSerializer testMessageSerializer = new TestMessageSerializer();

		MessageBin messageBin = new MessageBin("test");
		TestClient client = new TestClient();
		Subscriber subscriber = Subscribing.subscriber(client)
				.withExceptionResolver(exceptionResolver)
				.withMessageSerializer(testMessageSerializer)
				.build();

		MessageConsumer<TestMessage> consumer = MessageConsumer.basic(TestMessage::markComplete);
		SubscriptionConfiguration<TestMessage> configuration = SubscriptionConfiguration.builder(messageBin, TestMessage.class, consumer)
				// Note: This test runs very fast and can cause ThreadPoolExecutor to hit a race condition and throw
				// RejectedExecutionException if the threadpool size is too small. This has not been an issue in prod
				// because there are natural delays (SQS, database access). To stabilize the test, we set the capacity
				// here to a number that will result in a max threadpool size > the number of messages we're queuing.
				// See ThreadPoolWorkExecutor.newPooledWorkExecutor() for how the max is computed and configured.
				.withMessageCapacity(15)
				.withRetryDelayGenerator(new ConstantRetryDelay(0))
				.build();
		subscriber.subscribe(configuration);

		Publisher publisher = Publishing.publisher(client)
				.withMessageSerializer(testMessageSerializer)
				.build();

		CountDownLatch countDownLatch = new CountDownLatch(10);
		Map<TestMessage, AtomicInteger> messageCounters = new ConcurrentHashMap<>();
		for (int i = 0; i < 10; i++) {
			AtomicInteger counter = new AtomicInteger(0);
			Runnable runnable = () -> {
				if (counter.getAndIncrement() == 0) {
					throw new RuntimeException("Counter is 0, oh no!");
				}
				countDownLatch.countDown();
			};
			messageCounters.put(new TestMessage(runnable), counter);
		}
		publisher.publish(messageBin, messageCounters.keySet());

		// If a message doesn't finish, this will timeout
		countDownLatch.await(10, TimeUnit.SECONDS);

		subscriber.cancelAll();

		for(AtomicInteger counter : messageCounters.values()) {
			assertEquals(2, counter.get());
		}
	}

	private static class TestMessage {

		private final Runnable onComplete;

		private TestMessage(Runnable onComplete) {
			this.onComplete = onComplete;
		}

		void markComplete() {
			onComplete.run();
		}
	}

	private static class TestMessageSerializer implements MessageSerializer {

		private Map<String, Object> serializationMap = new HashMap<>();

		@Override
		public <Message> String toString(Message message, Class<? extends Message> messageType) {
			String uuid = UUID.randomUUID().toString();
			serializationMap.put(uuid, message);
			return uuid;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <Message> Message fromString(String body, Class<? extends Message> messageType) {
			return (Message) serializationMap.get(body);
		}
	}
}