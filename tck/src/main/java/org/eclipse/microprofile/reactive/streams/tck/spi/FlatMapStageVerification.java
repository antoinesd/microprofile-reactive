/*******************************************************************************
 * Copyright (c) 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.eclipse.microprofile.reactive.streams.tck.spi;

import org.eclipse.microprofile.reactive.streams.ProcessorBuilder;
import org.eclipse.microprofile.reactive.streams.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.ReactiveStreams;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.testng.Assert.assertEquals;

public class FlatMapStageVerification extends AbstractStageVerification {
    FlatMapStageVerification(ReactiveStreamsSpiVerification.VerificationDeps deps) {
        super(deps);
    }

    @Test
    public void flatMapStageShouldMapElements() {
        assertEquals(await(ReactiveStreams.of(1, 2, 3)
            .flatMap(n -> ReactiveStreams.of(n, n, n))
            .toList()
            .run(getEngine())), Arrays.asList(1, 1, 1, 2, 2, 2, 3, 3, 3));
    }

    @Test
    public void flatMapStageShouldAllowEmptySubStreams() {
        assertEquals(await(ReactiveStreams.of(ReactiveStreams.empty(), ReactiveStreams.of(1, 2))
            .flatMap(Function.identity())
            .toList()
            .run(getEngine())), Arrays.asList(1, 2));
    }

    @Test(expectedExceptions = QuietRuntimeException.class, expectedExceptionsMessageRegExp = "failed")
    public void flatMapStageShouldHandleExceptions() {
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        CompletionStage<List<Object>> result = infiniteStream()
            .onTerminate(() -> cancelled.complete(null))
            .flatMap(foo -> {
                throw new QuietRuntimeException("failed");
            })
            .toList()
            .run(getEngine());
        await(cancelled);
        await(result);
    }

    @Test(expectedExceptions = QuietRuntimeException.class, expectedExceptionsMessageRegExp = "failed")
    public void flatMapStageShouldPropagateUpstreamExceptions() {
        await(ReactiveStreams.failed(new QuietRuntimeException("failed"))
            .flatMap(ReactiveStreams::of)
            .toList()
            .run(getEngine()));
    }

    @Test(expectedExceptions = QuietRuntimeException.class, expectedExceptionsMessageRegExp = "failed")
    public void flatMapStageShouldPropagateSubstreamExceptions() {
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        CompletionStage<List<Object>> result = infiniteStream()
            .onTerminate(() -> cancelled.complete(null))
            .flatMap(f -> ReactiveStreams.failed(new QuietRuntimeException("failed")))
            .toList()
            .run(getEngine());
        await(cancelled);
        await(result);
    }

    @Test
    public void flatMapStageShouldOnlySubscribeToOnePublisherAtATime() throws Exception {
        AtomicInteger activePublishers = new AtomicInteger();

        CompletionStage<List<Integer>> result = ReactiveStreams.of(1, 2, 3, 4, 5)
            .flatMap(id -> ReactiveStreams.fromPublisher(new ScheduledPublisher(id, activePublishers, this::getExecutorService)))
            .toList()
            .run(getEngine());

        assertEquals(result.toCompletableFuture().get(2, TimeUnit.SECONDS),
            Arrays.asList(1, 2, 3, 4, 5));
    }

    @Test
    public void flatMapStageShouldPropgateCancelToSubstreams() {
        CompletableFuture<Void> outerCancelled = new CompletableFuture<>();
        CompletableFuture<Void> innerCancelled = new CompletableFuture<>();
        await(infiniteStream()
            .onTerminate(() -> outerCancelled.complete(null))
            .flatMap(i -> infiniteStream().onTerminate(() -> innerCancelled.complete(null)))
            .limit(5)
            .toList()
            .run(getEngine()));

        await(outerCancelled);
        await(innerCancelled);
    }

    @Test
    public void flatMapStageBuilderShouldBeReusable() {
        ProcessorBuilder<PublisherBuilder<Integer>, Integer> flatMap =
            ReactiveStreams.<PublisherBuilder<Integer>>builder().flatMap(Function.identity());

        assertEquals(await(ReactiveStreams.of(ReactiveStreams.of(1, 2)).via(flatMap).toList().run(getEngine())), Arrays.asList(1, 2));
        assertEquals(await(ReactiveStreams.of(ReactiveStreams.of(3, 4)).via(flatMap).toList().run(getEngine())), Arrays.asList(3, 4));
    }

    @Override
    List<Object> reactiveStreamsTckVerifiers() {
        return Arrays.asList(new OuterProcessorVerification(), new InnerSubscriberVerification());
    }

    /**
     * Verifies the outer processor.
     */
    public class OuterProcessorVerification extends StageProcessorVerification<Integer> {

        @Override
        public Processor<Integer, Integer> createIdentityProcessor(int bufferSize) {
            return ReactiveStreams.<Integer>builder().flatMap(ReactiveStreams::of).buildRs(getEngine());
        }

        @Override
        public Publisher<Integer> createFailedPublisher() {
            return ReactiveStreams.<Integer>failed(new RuntimeException("failed"))
                .flatMap(ReactiveStreams::of).buildRs(getEngine());
        }

        @Override
        public Integer createElement(int element) {
            return element;
        }
    }

    /**
     * Verifies the inner subscriber passed to publishers produced by the mapper function.
     */
    public class InnerSubscriberVerification extends StageSubscriberWhiteboxVerification<Integer> {

        @Override
        public Subscriber<Integer> createSubscriber(WhiteboxSubscriberProbe<Integer> probe) {
            CompletableFuture<Subscriber<? super Integer>> subscriber = new CompletableFuture<>();
            ReactiveStreams.of(ReactiveStreams.<Integer>fromPublisher(subscriber::complete))
                .flatMap(Function.identity())
                .to(new Subscriber<Integer>() {
                    @Override
                    public void onSubscribe(Subscription subscription) {
                        // We need to initially request an element to ensure that we get the publisher.
                        subscription.request(1);
                        probe.registerOnSubscribe(new SubscriberPuppet() {
                            @Override
                            public void triggerRequest(long elements) {
                                subscription.request(elements);
                            }

                            @Override
                            public void signalCancel() {
                                subscription.cancel();
                            }
                        });
                    }

                    @Override
                    public void onNext(Integer item) {
                        probe.registerOnNext(item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        probe.registerOnError(throwable);
                    }

                    @Override
                    public void onComplete() {
                        probe.registerOnComplete();
                    }
                })
                .run(getEngine());

            return (Subscriber) await(subscriber);
        }

        @Override
        public Integer createElement(int element) {
            return element;
        }
    }
}
