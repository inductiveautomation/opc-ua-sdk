/*
 * Copyright 2014
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

package com.digitalpetri.opcua.sdk.server.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;

public class FutureUtils {


    public static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        CompletableFuture[] fa = futures.toArray(new CompletableFuture[futures.size()]);

        return CompletableFuture.allOf(fa).thenApply(
                v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    public static <T> CompletableFuture<List<T>> sequence(CompletableFuture<T>[] futures) {
        if (futures.length == 0) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return CompletableFuture.allOf(futures).thenApply(
                v -> Arrays.stream(futures).map(CompletableFuture::join).collect(Collectors.toList()));
    }

}
