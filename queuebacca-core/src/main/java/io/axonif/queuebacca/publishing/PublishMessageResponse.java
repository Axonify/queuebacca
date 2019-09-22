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

package io.axonif.queuebacca.publishing;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PublishMessageResponse {

    private final List<PublishedMessage> publishedMessages;

    public PublishMessageResponse(List<PublishedMessage> publishedMessages) {
        this.publishedMessages = new ArrayList<>(publishedMessages);
    }

    public List<PublishedMessage> getPublishedMessages() {
        return Collections.unmodifiableList(publishedMessages);
    }

    public static class PublishedMessage {

        private final String messageId;
        private final String messageBody;

        public PublishedMessage(String messageId, String messageBody) {
            this.messageId = requireNonNull(messageId);
            this.messageBody = requireNonNull(messageBody);
        }

        public String getMessageId() {
            return messageId;
        }

        public String getMessageBody() {
            return messageBody;
        }
    }
}
