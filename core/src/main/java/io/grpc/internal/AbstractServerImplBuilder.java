/*
 * Copyright 2014, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.instrumentation.stats.Stats;
import com.google.instrumentation.stats.StatsContextFactory;
import com.google.instrumentation.trace.Tracing;
import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.Internal;
import io.grpc.InternalNotifyOnServerBuild;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * The base class for server builders.
 *
 * @param <T> The concrete type for this builder.
 */
public abstract class AbstractServerImplBuilder<T extends AbstractServerImplBuilder<T>>
        extends ServerBuilder<T> {

  private static final HandlerRegistry EMPTY_FALLBACK_REGISTRY = new HandlerRegistry() {
      @Override
      public List<ServerServiceDefinition> getServices() {
        return Collections.emptyList();
      }

      @Override
      public ServerMethodDefinition<?, ?> lookupMethod(String methodName,
          @Nullable String authority) {
        return null;
      }
    };

  private final InternalHandlerRegistry.Builder registryBuilder =
      new InternalHandlerRegistry.Builder();

  private final ArrayList<ServerTransportFilter> transportFilters =
      new ArrayList<ServerTransportFilter>();

  private final List<InternalNotifyOnServerBuild> notifyOnBuildList =
      new ArrayList<InternalNotifyOnServerBuild>();

  private final List<ServerStreamTracer.Factory> streamTracerFactories =
      new ArrayList<ServerStreamTracer.Factory>();

  @Nullable
  private HandlerRegistry fallbackRegistry;

  @Nullable
  private Executor executor;

  @Nullable
  private DecompressorRegistry decompressorRegistry;

  @Nullable
  private CompressorRegistry compressorRegistry;

  @Nullable
  private StatsContextFactory statsFactory;

  @Override
  public final T directExecutor() {
    return executor(MoreExecutors.directExecutor());
  }

  @Override
  public final T executor(@Nullable Executor executor) {
    this.executor = executor;
    return thisT();
  }

  @Override
  public final T addService(ServerServiceDefinition service) {
    registryBuilder.addService(service);
    return thisT();
  }

  @Override
  public final T addService(BindableService bindableService) {
    if (bindableService instanceof InternalNotifyOnServerBuild) {
      notifyOnBuildList.add((InternalNotifyOnServerBuild) bindableService);
    }
    return addService(bindableService.bindService());
  }

  @Override
  public final T addTransportFilter(ServerTransportFilter filter) {
    transportFilters.add(checkNotNull(filter, "filter"));
    return thisT();
  }

  @Override
  public final T addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    streamTracerFactories.add(checkNotNull(factory, "factory"));
    return thisT();
  }

  @Override
  public final T fallbackHandlerRegistry(HandlerRegistry registry) {
    this.fallbackRegistry = registry;
    return thisT();
  }

  @Override
  public final T decompressorRegistry(DecompressorRegistry registry) {
    decompressorRegistry = registry;
    return thisT();
  }

  @Override
  public final T compressorRegistry(CompressorRegistry registry) {
    compressorRegistry = registry;
    return thisT();
  }

  /**
   * Override the default stats implementation.
   */
  @VisibleForTesting
  protected T statsContextFactory(StatsContextFactory statsFactory) {
    this.statsFactory = statsFactory;
    return thisT();
  }

  @Override
  public Server build() {
    ArrayList<ServerStreamTracer.Factory> tracerFactories =
        new ArrayList<ServerStreamTracer.Factory>();
    StatsContextFactory statsFactory =
        this.statsFactory != null ? this.statsFactory : Stats.getStatsContextFactory();
    if (statsFactory != null) {
      CensusStatsModule censusStats =
          new CensusStatsModule(
              statsFactory, GrpcUtil.STOPWATCH_SUPPLIER, true /** only matters on client-side **/);
      tracerFactories.add(censusStats.getServerTracerFactory());
    }
    CensusTracingModule censusTracing =
        new CensusTracingModule(Tracing.getTracer(), Tracing.getBinaryPropagationHandler());
    tracerFactories.add(censusTracing.getServerTracerFactory());
    tracerFactories.addAll(streamTracerFactories);

    io.grpc.internal.InternalServer transportServer =
        buildTransportServer(Collections.unmodifiableList(tracerFactories));
    ServerImpl server = new ServerImpl(getExecutorPool(),
        SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE), registryBuilder.build(),
        firstNonNull(fallbackRegistry, EMPTY_FALLBACK_REGISTRY), transportServer,
        Context.ROOT, firstNonNull(decompressorRegistry, DecompressorRegistry.getDefaultInstance()),
        firstNonNull(compressorRegistry, CompressorRegistry.getDefaultInstance()),
        transportFilters);
    for (InternalNotifyOnServerBuild notifyTarget : notifyOnBuildList) {
      notifyTarget.notifyOnBuild(server);
    }
    return server;
  }

  private ObjectPool<? extends Executor> getExecutorPool() {
    final Executor savedExecutor = executor;
    if (savedExecutor == null) {
      return SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
    }
    return new ObjectPool<Executor>() {
      @Override
      public Executor getObject() {
        return savedExecutor;
      }

      @Override
      public Executor returnObject(Object object) {
        return null;
      }
    };
  }

  /**
   * Children of AbstractServerBuilder should override this method to provide transport specific
   * information for the server.  This method is mean for Transport implementors and should not be
   * used by normal users.
   *
   * @param streamTracerFactories an immutable list of stream tracer factories
   */
  @Internal
  protected abstract io.grpc.internal.InternalServer buildTransportServer(
      List<ServerStreamTracer.Factory> streamTracerFactories);

  private T thisT() {
    @SuppressWarnings("unchecked")
    T thisT = (T) this;
    return thisT;
  }
}
