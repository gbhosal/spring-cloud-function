/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.web.flux;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;

import org.springframework.cloud.function.context.catalog.FunctionInspector;
import org.springframework.cloud.function.context.message.MessageUtils;
import org.springframework.cloud.function.web.flux.constants.WebRequestConstants;
import org.springframework.cloud.function.web.flux.request.FluxFormRequest;
import org.springframework.cloud.function.web.flux.request.FluxRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.WebRequest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author Dave Syer
 * @author Mark Fisher
 */
@Component
public class FunctionController {

	private static Log logger = LogFactory.getLog(FunctionController.class);

	private FunctionInspector inspector;

	private boolean debug = false;

	private StringConverter converter;

	public FunctionController(FunctionInspector inspector, StringConverter converter) {
		this.inspector = inspector;
		this.converter = converter;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	@PostMapping(path = "/**")
	@ResponseBody
	public ResponseEntity<Publisher<?>> post(WebRequest request,
			@RequestBody FluxRequest<?> body) {

		@SuppressWarnings("unchecked")
		Function<Publisher<?>, Publisher<?>> function = (Function<Publisher<?>, Publisher<?>>) request
				.getAttribute(WebRequestConstants.FUNCTION, WebRequest.SCOPE_REQUEST);
		@SuppressWarnings("unchecked")
		Consumer<Publisher<?>> consumer = (Consumer<Publisher<?>>) request
				.getAttribute(WebRequestConstants.CONSUMER, WebRequest.SCOPE_REQUEST);
		Boolean single = (Boolean) request.getAttribute(WebRequestConstants.INPUT_SINGLE,
				WebRequest.SCOPE_REQUEST);

		FluxFormRequest<?, ?> form = FluxFormRequest.from(request.getParameterMap());

		if (function != null) {
			Flux<?> flux = body.body() == null ? form.flux() : body.flux();
			if (debug) {
				flux = flux.log();
			}
			Flux<?> result = Flux.from(function.apply(flux));
			if (inspector.isMessage(function)) {
				result = result.map(message -> MessageUtils.unpack(function, message));
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Handled POST with function");
			}
			return ResponseEntity.ok().body(response(request, function, single, result));
		}

		if (consumer != null) {
			Flux<?> flux = body.body() == null ? form.flux().cache()
					: body.flux().cache(); // send a copy back to the caller
			if (debug) {
				flux = flux.log();
			}
			consumer.accept(flux);
			if (logger.isDebugEnabled()) {
				logger.debug("Handled POST with consumer");
			}
			return ResponseEntity.status(HttpStatus.ACCEPTED).body(flux);
		}

		throw new IllegalArgumentException("no such function");
	}

	private Publisher<?> response(WebRequest request, Object handler, Boolean single,
			Publisher<?> result) {

		if (single != null && single && isOutputSingle(handler)) {
			request.setAttribute(WebRequestConstants.OUTPUT_SINGLE, true,
					WebRequest.SCOPE_REQUEST);
			return Mono.from(result);
		}

		if (isInputMultiple(handler) && isOutputSingle(handler)) {
			request.setAttribute(WebRequestConstants.OUTPUT_SINGLE, true,
					WebRequest.SCOPE_REQUEST);
			return Mono.from(result);
		}

		request.setAttribute(WebRequestConstants.OUTPUT_SINGLE, false,
				WebRequest.SCOPE_REQUEST);

		return result;
	}

	private boolean isInputMultiple(Object handler) {
		Class<?> type = inspector.getInputType(handler);
		Class<?> wrapper = inspector.getInputWrapper(handler);
		return Collection.class.isAssignableFrom(type) || Flux.class.equals(wrapper);
	}

	private boolean isOutputSingle(Object handler) {
		Class<?> type = inspector.getOutputType(handler);
		Class<?> wrapper = inspector.getOutputWrapper(handler);
		if (Stream.class.isAssignableFrom(type)) {
			return false;
		}
		if (wrapper == type) {
			return true;
		}
		return Mono.class.equals(wrapper) || Optional.class.equals(wrapper);
	}

	@GetMapping(path = "/**")
	@ResponseBody
	public ResponseEntity<Publisher<?>> get(WebRequest request) {
		@SuppressWarnings("unchecked")
		Function<Publisher<?>, Publisher<?>> function = (Function<Publisher<?>, Publisher<?>>) request
				.getAttribute(WebRequestConstants.FUNCTION, WebRequest.SCOPE_REQUEST);
		@SuppressWarnings("unchecked")
		Supplier<Publisher<?>> supplier = (Supplier<Publisher<?>>) request
				.getAttribute(WebRequestConstants.SUPPLIER, WebRequest.SCOPE_REQUEST);
		String argument = (String) request.getAttribute(WebRequestConstants.ARGUMENT,
				WebRequest.SCOPE_REQUEST);

		Publisher<?> result;
		if (function != null) {
			result = value(function, argument);
		}
		else {
			result = response(request, supplier, true, supplier(supplier));
		}
		if (inspector.isMessage(function)) {
			result = Flux.from(result)
					.map(message -> MessageUtils.unpack(function, message));
		}
		return ResponseEntity.ok().body(result);
	}

	private Publisher<?> supplier(Supplier<Publisher<?>> supplier) {
		Publisher<?> result = supplier.get();
		if (logger.isDebugEnabled()) {
			logger.debug("Handled GET with supplier");
		}
		return debug ? Flux.from(result).log() : result;
	}

	private Mono<?> value(Function<Publisher<?>, Publisher<?>> function, String value) {
		Object input = converter.convert(function, value);
		Mono<?> result = Mono.from(function.apply(Flux.just(input)));
		if (logger.isDebugEnabled()) {
			logger.debug("Handled GET with function");
		}
		return debug ? result.log() : result;
	}
}
