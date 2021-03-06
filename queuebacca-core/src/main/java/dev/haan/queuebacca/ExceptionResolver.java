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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExceptionResolver {

	private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionResolver.class);

	private final Map<Class<?>, ExceptionHandler<?>> exceptionHandlers;

	private ExceptionResolver(Map<Class<?>, ExceptionHandler<?>> exceptionHandlers) {
		this.exceptionHandlers = exceptionHandlers;
	}

	public static Builder builder() {
		return new Builder();
	}

	public MessageResponse resolve(Exception exception, MessageContext messageContext) {
		Optional<ExceptionHandler<Exception>> handler = findExceptionHandler(exception.getClass());
		if (handler.isPresent()) {
			return handler.get().handle(exception, messageContext);
		} else {
			LOGGER.error("Error occurred '{}'", messageContext.getMessageId(), exception);
			return MessageResponse.RETRY;
		}
	}

	private <E extends Exception> Optional<ExceptionHandler<E>> findExceptionHandler(Class<? extends E> exceptionType) {
		Class<?> mappedExceptionType = mapExceptionType(exceptionType);
		@SuppressWarnings("unchecked") ExceptionHandler<E> exceptionHandler = (ExceptionHandler<E>) exceptionHandlers.get(mappedExceptionType);
		return Optional.ofNullable(exceptionHandler);
	}

	private Class<?> mapExceptionType(Class<?> exceptionType) {
		if (exceptionType == null) {
			return null;
		} else if (exceptionHandlers.containsKey(exceptionType)) {
			return exceptionType;
		} else {
			return mapExceptionType(exceptionType.getSuperclass());
		}
	}

	public interface ExceptionHandler<E extends Exception> {

		MessageResponse handle(E exception, MessageContext messageContext);
	}

	public static class Builder {

		private final Map<Class<?>, ExceptionHandler<?>> exceptionHandlers = new HashMap<>();

		private Builder() {}

		public <E extends Exception> Builder addHandler(Class<E> exceptionType, ExceptionHandler<E> exceptionHandler) {
			exceptionHandlers.put(exceptionType, exceptionHandler);
			return this;
		}

		public ExceptionResolver build() {
			return new ExceptionResolver(exceptionHandlers);
		}
	}
}
