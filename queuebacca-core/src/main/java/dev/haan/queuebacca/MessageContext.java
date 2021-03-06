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

package dev.haan.queuebacca;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

/**
 * Contains contextual information for the consumption of a {@link Message}.
 */
public final class MessageContext {

	private final String messageId;
	private final int readCount;
	private final Instant firstReceived;
	private final String rawMessage;

	/**
	 * Creates a new instance of a {@link MessageContext} with the provided message id.
	 *
	 * @param messageId the unique id of the message
	 * @param readCount the number of times the message has been read
	 * @param firstReceived the first time the message was received as an {@link Instant}
	 * @param rawMessage the serialized representing of the message
	 */
	public MessageContext(String messageId, int readCount, Instant firstReceived, String rawMessage) {
		this.messageId = requireNonNull(messageId);
		this.readCount = readCount;
		this.firstReceived = requireNonNull(firstReceived);
		this.rawMessage = requireNonNull(rawMessage);
	}

	/**
	 * The unique message id.
	 *
	 * @return the message id
	 */
	public String getMessageId() {
		return messageId;
	}

	/**
	 * The number of times a message has been read.
	 *
	 * @return the message read count
	 */
	public int getReadCount() {
		return readCount;
	}

	/**
	 * The timestamp the message was first received.
	 *
	 * @return an {@link Instant} of when the message was first received
	 */
	public Instant getFirstReceived() {
		return firstReceived;
	}

	/**
	 * The serialized representation of the message.
	 *
	 * @return the raw message
	 */
	public String getRawMessage() {
		return rawMessage;
	}
}
