/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.Objects;
import java.util.function.BiConsumer;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Fuseable;

/**
 * Maps the values of the source publisher one-on-one via a handler function as long as the handler function result is
 * not null. If the result is a {@code null} value then the source value is filtered rather than mapped.
 *
 * @param <T> the source value type
 * @param <R> the result value type
 */
final class FluxHandle<T, R> extends FluxSource<T, R> {

	final BiConsumer<? super T, SynchronousSink<R>> handler;

	FluxHandle(Flux<? extends T> source, BiConsumer<? super T, SynchronousSink<R>> handler) {
		super(source);
		this.handler = Objects.requireNonNull(handler, "handler");
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(Subscriber<? super R> s) {
		if (s instanceof Fuseable.ConditionalSubscriber) {
			Fuseable.ConditionalSubscriber<? super R> cs = (Fuseable.ConditionalSubscriber<? super R>) s;
			source.subscribe(new HandleConditionalSubscriber<>(cs, handler));
			return;
		}
		source.subscribe(new HandleSubscriber<>(s, handler));
	}

	static final class HandleSubscriber<T, R>
			implements InnerOperator<T, R>,
			           Fuseable.ConditionalSubscriber<T>,
			           SynchronousSink<R> {
		final Subscriber<? super R>			actual;
		final BiConsumer<? super T, SynchronousSink<R>> handler;

		boolean done;
		boolean stop;
		Throwable error;
		R data;

		Subscription s;

		HandleSubscriber(Subscriber<? super R> actual, BiConsumer<? super T, SynchronousSink<R>> handler) {
			this.actual = actual;
			this.handler = handler;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}

			try {
				handler.accept(t, this);
			}
			catch (Throwable e) {
				onError(Operators.onOperatorError(s, e, t));
				return;
			}
			R v = data;
			data = null;
			if (v != null) {
				actual.onNext(v);
			}
			if(stop){
				s.cancel();
				if(error != null){
					onError(Operators.onOperatorError(null, error, t));
					return;
				}
				onComplete();
			}
			else if(v == null){
				s.request(1L);
			}
		}

		@Override
		public boolean tryOnNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return false;
			}

			try {
				handler.accept(t, this);
			}
			catch (Throwable e) {
				onError(Operators.onOperatorError(s, e, t));
				return false;
			}
			R v = data;
			data = null;
			if (v != null) {
				actual.onNext(v);
			}
			if(stop){
				s.cancel();
				if(error != null){
					onError(Operators.onOperatorError(null, error, t));
				}
				else {
					onComplete();
				}
				return true;
			}
			return v != null;
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}

			done = true;

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;

			actual.onComplete();
		}

		@Override
		public void complete() {
			stop = true;
		}

		@Override
		public void error(Throwable e) {
			error = Objects.requireNonNull(e, "error");
			stop = true;
		}

		@Override
		public void next(R o) {
			if(data != null){
				throw new IllegalStateException("Cannot emit more than one data");
			}
			data = Objects.requireNonNull(o, "data");
		}

		@Override
		public Object scan(Attr key) {
			switch (key) {
				case PARENT:
					return s;
				case TERMINATED:
					return done;
				case ERROR:
					return error;
			}
			return InnerOperator.super.scan(key);
		}

		@Override
		public Subscriber<? super R> actual() {
			return actual;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}
		
		@Override
		public void cancel() {
			s.cancel();
		}
	}

	static final class HandleConditionalSubscriber<T, R>
			implements Fuseable.ConditionalSubscriber<T>, InnerOperator<T, R>,
			           SynchronousSink<R> {
		final Fuseable.ConditionalSubscriber<? super R> actual;
		final BiConsumer<? super T, SynchronousSink<R>> handler;

		boolean done;
		Throwable error;
		R data;

		Subscription s;

		HandleConditionalSubscriber(Fuseable.ConditionalSubscriber<? super R> actual, BiConsumer<? super T, SynchronousSink<R>> handler) {
			this.actual = actual;
			this.handler = handler;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}

			try {
				handler.accept(t, this);
			}
			catch (Throwable e) {
				onError(Operators.onOperatorError(s, e, t));
				return;
			}
			R v = data;
			data = null;
			if (v != null) {
				actual.onNext(v);
			}
			if(done){
				s.cancel();
				if(error != null){
					actual.onError(Operators.onOperatorError(null, error, t));
					return;
				}
				actual.onComplete();
			}
			else if(v == null){
				s.request(1L);
			}
		}

		@Override
		public boolean tryOnNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return false;
			}

			try {
				handler.accept(t, this);
			}
			catch (Throwable e) {
				onError(Operators.onOperatorError(s, e, t));
				return false;
			}
			R v = data;
			boolean emit = false;
			data = null;
			if (v != null) {
				emit = actual.tryOnNext(v);
			}
			if(done){
				s.cancel();
				if(error != null){
					actual.onError(Operators.onOperatorError(null, error, t));
				}
				else {
					actual.onComplete();
				}
				return true;
			}
			return emit;
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}

			done = true;

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;

			actual.onComplete();
		}

		@Override
		public Subscriber<? super R> actual() {
			return actual;
		}

		@Override
		public void complete() {
			done = true;
		}

		@Override
		public void error(Throwable e) {
			error = Objects.requireNonNull(e, "error");
			done = true;
		}

		@Override
		public void next(R o) {
			if(data != null){
				throw new IllegalStateException("Cannot emit more than one data");
			}
			data = Objects.requireNonNull(o, "data");
		}
		
		@Override
		public void request(long n) {
			s.request(n);
		}
		
		@Override
		public void cancel() {
			s.cancel();
		}

		@Override
		public Object scan(Attr key) {
			switch (key) {
				case PARENT:
					return s;
				case TERMINATED:
					return done;
				case ERROR:
					return error;
			}
			return InnerOperator.super.scan(key);
		}
	}

}
