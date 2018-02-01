/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
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
package org.neo4j.driver.internal.async.pool;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.concurrent.Future;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.driver.internal.BoltServerAddress;
import org.neo4j.driver.internal.async.ChannelConnector;
import org.neo4j.driver.internal.async.NettyConnection;
import org.neo4j.driver.internal.metrics.DriverMetricsHandler;
import org.neo4j.driver.internal.metrics.ListenerEvent;
import org.neo4j.driver.internal.metrics.SimpleTimerListenerEvent;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.ConnectionPool;
import org.neo4j.driver.internal.util.Clock;
import org.neo4j.driver.internal.util.Futures;
import org.neo4j.driver.v1.Logger;
import org.neo4j.driver.v1.Logging;
import org.neo4j.driver.v1.exceptions.ClientException;

public class ConnectionPoolImpl implements ConnectionPool
{
    private final ChannelConnector connector;
    private final Bootstrap bootstrap;
    private final NettyChannelTracker nettyChannelTracker;
    private final NettyChannelHealthChecker channelHealthChecker;
    private final PoolSettings settings;
    private final Clock clock;
    private final Logger log;
    private DriverMetricsHandler driverMetrics;

    private final ConcurrentMap<BoltServerAddress,ChannelPool> pools = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ConnectionPoolImpl( ChannelConnector connector, Bootstrap bootstrap, PoolSettings settings,
            DriverMetricsHandler metricsHandler, Logging logging, Clock clock )
    {
        this( connector, bootstrap, new NettyChannelTracker( metricsHandler, logging ), settings, metricsHandler, logging, clock );
    }

    ConnectionPoolImpl( ChannelConnector connector, Bootstrap bootstrap, NettyChannelTracker nettyChannelTracker,
            PoolSettings settings, DriverMetricsHandler driverMetrics, Logging logging, Clock clock )
    {
        this.connector = connector;
        this.bootstrap = bootstrap;
        this.nettyChannelTracker = nettyChannelTracker;
        this.channelHealthChecker = new NettyChannelHealthChecker( settings, clock, logging );
        this.settings = settings;
        this.driverMetrics = driverMetrics;
        this.clock = clock;
        this.log = logging.getLog( ConnectionPool.class.getSimpleName() );
    }

    @Override
    public CompletionStage<Connection> acquire( BoltServerAddress address )
    {
        log.trace( "Acquiring a connection from pool towards %s", address );

        // TODO no need to init if no driver metrics
        ListenerEvent acquireEvent = new SimpleTimerListenerEvent();

        assertNotClosed();
        ChannelPool pool = getOrCreatePool( address );
        driverMetrics.beforeAcquiring( address, acquireEvent );
        Future<Channel> connectionFuture = pool.acquire();

        return Futures.asCompletionStage( connectionFuture ).handle( ( channel, error ) ->
        {
            try
            {
                processAcquisitionError( error );
                assertNotClosed( address, channel, pool );
                return new NettyConnection( channel, pool, clock );
            }
            finally
            {
                driverMetrics.afterAcquired( address, acquireEvent );
            }
        } );
    }

    @Override
    public void retainAll( Set<BoltServerAddress> addressesToRetain )
    {
        for ( BoltServerAddress address : pools.keySet() )
        {
            if ( !addressesToRetain.contains( address ) )
            {
                int activeChannels = nettyChannelTracker.inUseChannelCount( address );
                if ( activeChannels == 0 )
                {
                    // address is not present in updated routing table and has no active connections
                    // it's now safe to terminate corresponding connection pool and forget about it

                    ChannelPool pool = pools.remove( address );
                    if ( pool != null )
                    {
                        log.info( "Closing connection pool towards %s, it has no active connections " +
                                  "and is not in the routing table", address );
                        pool.close();
                    }
                }
            }
        }
    }

    @Override
    public int inUseConnections( BoltServerAddress address )
    {
        return nettyChannelTracker.inUseChannelCount( address );
    }

    @Override
    public int idleConnections( BoltServerAddress address )
    {
        return nettyChannelTracker.idleChannelCount( address );
    }

    @Override
    public CompletionStage<Void> close()
    {
        if ( closed.compareAndSet( false, true ) )
        {
            try
            {
                for ( Map.Entry<BoltServerAddress,ChannelPool> entry : pools.entrySet() )
                {
                    BoltServerAddress address = entry.getKey();
                    ChannelPool pool = entry.getValue();

                    log.info( "Closing connection pool towards %s", address );
                    pool.close();
                }

                pools.clear();
            }
            finally
            {
                eventLoopGroup().shutdownGracefully();
            }
        }
        return Futures.asCompletionStage( eventLoopGroup().terminationFuture() )
                .thenApply( ignore -> null );
    }

    @Override
    public boolean isOpen()
    {
        return !closed.get();
    }

    private ChannelPool getOrCreatePool( BoltServerAddress address )
    {
        ChannelPool pool = pools.get( address );
        if ( pool == null )
        {
            pool = newPool( address );

            if ( pools.putIfAbsent( address, pool ) != null )
            {
                // We lost a race to create the pool, dispose of the one we created, and recurse
                pool.close();
                return getOrCreatePool( address );
            }
            else
            {
                // We added a new pool as a result we add a new metrics for the pool too
                driverMetrics.addPoolMetrics( address, this );
            }
        }

        return pool;
    }

    ChannelPool newPool( BoltServerAddress address )
    {
        return new NettyChannelPool( address, connector, bootstrap, nettyChannelTracker, channelHealthChecker,
                settings.connectionAcquisitionTimeout(), settings.maxConnectionPoolSize() );
    }

    private EventLoopGroup eventLoopGroup()
    {
        return bootstrap.config().group();
    }

    private void processAcquisitionError( Throwable error )
    {
        Throwable cause = Futures.completionExceptionCause( error );
        if ( cause != null )
        {
            if ( cause instanceof TimeoutException )
            {
                // NettyChannelPool returns future failed with TimeoutException if acquire operation takes more than
                // configured time, translate this exception to a prettier one and re-throw
                throw new ClientException(
                        "Unable to acquire connection from the pool within configured maximum time of " +
                        settings.connectionAcquisitionTimeout() + "ms" );
            }
            else
            {
                // some unknown error happened during connection acquisition, propagate it
                throw new CompletionException( cause );
            }
        }
    }

    private void assertNotClosed()
    {
        if ( closed.get() )
        {
            throw new IllegalStateException( "Pool closed" );
        }
    }

    private void assertNotClosed( BoltServerAddress address, Channel channel, ChannelPool pool )
    {
        if ( closed.get() )
        {
            pool.release( channel );
            pool.close();
            pools.remove( address );
            assertNotClosed();
        }
    }

    @Override
    public String toString()
    {
        return "ConnectionPoolImpl{" + "pools=" + pools + '}';
    }
}
