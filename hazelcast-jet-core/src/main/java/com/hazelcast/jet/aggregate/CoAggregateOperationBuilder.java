/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.aggregate;

import com.hazelcast.jet.datamodel.ItemsByTag;
import com.hazelcast.jet.datamodel.Tag;
import com.hazelcast.jet.function.DistributedBiConsumer;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.pipeline.StageWithWindow;
import com.hazelcast.util.Preconditions;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Stream;

import static com.hazelcast.jet.function.DistributedFunction.identity;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;

/**
 * Offers a step-by-step API to create an aggregate operation that
 * accepts multiple inputs. You must supply this kind of operation to a
 * co-aggregating pipeline stage. Most typically you'll need this builder
 * if you're using the {@link StageWithWindow#aggregateBuilder()}. For
 * two-way or three-way co-aggregation you can use {@link
 * AggregateOperations#aggregateOperation2} and {@link AggregateOperations#aggregateOperation3}.
 * <p>
 * To obtain this builder, call {@link AggregateOperations#coAggregateOperationBuilder()}.
 * This builder is suitable only if you'll use independent aggregate
 * operations on each input and expect to receive a separate result for
 * each of them. You can combine the individual results in the final
 * step by providing a {@code finishFn}.
 */
public class CoAggregateOperationBuilder {

    private final Map<Tag, AggregateOperation1> opsByTag = new HashMap<>();

    CoAggregateOperationBuilder() { }

    /**
     * Registers the given aggregate operation with the tag corresponding to an
     * input to the co-aggregating operation being built. If you are preparing
     * an operation to pass to an {@linkplain
     * StageWithWindow#aggregateBuilder() aggregate builder}, you must use the
     * tags you obtained from it.
     * <p>
     * Returns the tag you'll use to retrieve the results of aggregating this
     * input.
     *
     * @param <T> type of this operation's input
     * @param <R> the result type of this operation
     * @return the result tag
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public <T, R> Tag<R> add(
            @Nonnull Tag<T> tag,
            @Nonnull AggregateOperation1<? super T, ?, ? extends R> operation
    ) {
        opsByTag.put(tag, operation);
        return (Tag<R>) tag;
    }

    /**
     * Builds and returns the {@link AggregateOperation}. Its result type is
     * {@link ItemsByTag} containing all the tags you got from the
     * {@link #add} method.
     */
    @Nonnull
    public AggregateOperation<Object[], ItemsByTag> build() {
        return build(identity());
    }

    /**
     * Builds and returns the multi-input {@link AggregateOperation}. It will
     * call the supplied {@code finishFn} to transform the {@link ItemsByTag}
     * it creates to the result type it emits as the actual result.
     *
     * @param finishFn function to convert {@link ItemsByTag} to the target result type
     */
    @Nonnull
    @SuppressWarnings({"unchecked", "ConstantConditions"})
    public <R> AggregateOperation<Object[], R> build(
            @Nonnull DistributedFunction<? super ItemsByTag, ? extends R> finishFn
    ) {
        Tag[] tags = opsByTag.keySet().stream().sorted().toArray(Tag[]::new);
        for (int i = 0; i < tags.length; i++) {
            Preconditions.checkTrue(tags[i].index() == i, "Registered tags' indices are "
                    + stream(tags).map(Tag::index).collect(toList())
                    + ", but should be " + range(0, tags.length).boxed().collect(toList()));
        }
        // Variable `sorted` extracted due to type inference failure
        Stream<Entry<Tag, AggregateOperation1>> sorted = opsByTag.entrySet().stream()
                                                                 .sorted(comparing(Entry::getKey));
        List<AggregateOperation1> ops = sorted.map(Entry::getValue).collect(toList());
        DistributedBiConsumer[] combineFns =
                ops.stream().map(AggregateOperation::combineFn).toArray(DistributedBiConsumer[]::new);
        DistributedBiConsumer[] deductFns =
                ops.stream().map(AggregateOperation::deductFn).toArray(DistributedBiConsumer[]::new);
        DistributedFunction[] finishFns =
                ops.stream().map(AggregateOperation::finishFn).toArray(DistributedFunction[]::new);

        AggregateOperationBuilder.VarArity<Object[]> b = AggregateOperation
                .withCreate(() -> ops.stream().map(op -> op.createFn().get()).toArray())
                .varArity();
        opsByTag.forEach((tag, op) -> {
            int index = tag.index();
            b.andAccumulate(tag, (acc, item) -> op.accumulateFn().accept(acc[index], item));
        });
        return b.andCombine(stream(combineFns).anyMatch(Objects::isNull) ? null :
                        (acc1, acc2) -> {
                            for (int i = 0; i < combineFns.length; i++) {
                                combineFns[i].accept(acc1[i], acc2[i]);
                            }
                        })
                .andDeduct(stream(deductFns).anyMatch(Objects::isNull) ? null :
                        (acc1, acc2) -> {
                            for (int i = 0; i < deductFns.length; i++) {
                                deductFns[i].accept(acc1[i], acc2[i]);
                            }
                        })
                .andFinish(acc -> {
                    ItemsByTag result = new ItemsByTag();
                    for (int i = 0; i < finishFns.length; i++) {
                        result.put(tags[i], finishFns[i].apply(acc[i]));
                    }
                    return finishFn.apply(result);
                });
    }
}