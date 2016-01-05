/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.ha.lock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.stubbing.OngoingStubbing;

import org.neo4j.com.ComException;
import org.neo4j.com.RequestContext;
import org.neo4j.com.ResourceReleaser;
import org.neo4j.com.Response;
import org.neo4j.com.TransactionStream;
import org.neo4j.com.TransactionStreamResponse;
import org.neo4j.graphdb.TransientFailureException;
import org.neo4j.helpers.FakeClock;
import org.neo4j.kernel.AvailabilityGuard;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.ha.com.RequestContextFactory;
import org.neo4j.kernel.ha.com.master.Master;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.ResourceTypes;
import org.neo4j.kernel.impl.locking.community.CommunityLockManger;
import org.neo4j.logging.NullLog;
import org.neo4j.storageengine.api.lock.ResourceType;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.kernel.impl.locking.ResourceTypes.NODE;

public class SlaveLocksClientTest
{
    private Master master;
    private Locks.Client local;
    private SlaveLocksClient client;
    private AvailabilityGuard availabilityGuard;

    @Before
    public void setUp() throws Exception
    {
        master = mock( Master.class );
        RequestContextFactory requestContextFactory = mock( RequestContextFactory.class );
        availabilityGuard = new AvailabilityGuard( new FakeClock(), NullLog.getInstance() );

        Locks lockManager = new CommunityLockManger();
        local = spy( lockManager.newClient() );

        LockResult lockResultOk = new LockResult( LockStatus.OK_LOCKED );
        TransactionStreamResponse<LockResult> responseOk =
                new TransactionStreamResponse<>( lockResultOk, null, TransactionStream.EMPTY, ResourceReleaser.NO_OP );

        whenMasterAcquireShared().thenReturn( responseOk );

        whenMasterAcquireExclusive().thenReturn( responseOk );

        client = new SlaveLocksClient( master, local, lockManager, requestContextFactory, availabilityGuard );
    }

    private OngoingStubbing<Response<LockResult>> whenMasterAcquireShared()
    {
        return when( master.acquireSharedLock(
                any( RequestContext.class ),
                any( ResourceType.class ),
                Matchers.<long[]>anyVararg() ) );
    }

    private OngoingStubbing<Response<LockResult>> whenMasterAcquireExclusive()
    {
        return when( master.acquireExclusiveLock(
                any( RequestContext.class ),
                any( ResourceType.class ),
                Matchers.<long[]>anyVararg() ) );
    }

    @After
    public void tearDown()
    {
        local.close();
    }

    @Test
    public void shouldNotTakeSharedLockOnMasterIfWeAreAlreadyHoldingSaidLock()
    {
        // When taking a lock twice
        client.acquireShared( NODE, 1 );
        client.acquireShared( NODE, 1 );

        // Then only a single network round-trip should be observed
        verify( master ).acquireSharedLock( null, NODE, 1 );
    }

    @Test
    public void shouldNotTakeExclusiveLockOnMasterIfWeAreAlreadyHoldingSaidLock()
    {
        // When taking a lock twice
        client.acquireExclusive( NODE, 1 );
        client.acquireExclusive( NODE, 1 );

        // Then only a single network roundtrip should be observed
        verify( master ).acquireExclusiveLock( null, NODE, 1 );
    }

    @Test
    public void shouldAllowAcquiringReleasingAndReacquiringExclusive() throws Exception
    {
        // Given we have grabbed and released a lock
        client.acquireExclusive( NODE, 1L );
        client.releaseExclusive( NODE, 1L );

        // When we grab and release that lock again
        client.acquireExclusive( NODE, 1L );
        client.releaseExclusive( NODE, 1L );

        // Then this should cause the local lock manager to hold the lock
        verify( local, times( 2 ) ).tryExclusiveLock( NODE, 1L );
        verify( local, times( 2 ) ).releaseExclusive( NODE, 1L );
    }

    @Test
    public void shouldAllowAcquiringReleasingAndReacquiringShared() throws Exception
    {
        // Given we have grabbed and released a lock
        client.acquireShared( NODE, 1L );
        client.releaseShared( NODE, 1L );

        // When we grab and release that lock again
        client.acquireShared( NODE, 1L );
        client.releaseShared( NODE, 1L );

        // Then this should cause the local lock manager to hold the lock
        verify( local, times( 2 ) ).trySharedLock( NODE, 1L );
        verify( local, times( 2 ) ).releaseShared( NODE, 1L );
    }

    @Test
    public void shouldNotTalkToLocalLocksOnReentrancyExclusive() throws Exception
    {
        // Given we have grabbed and released a lock
        client.acquireExclusive( NODE, 1L );

        // When we grab and release that lock again
        client.acquireExclusive( NODE, 1L );
        client.releaseExclusive( NODE, 1L );

        // Then this should cause the local lock manager to hold the lock
        verify( local, times( 1 ) ).tryExclusiveLock( NODE, 1L );
        verify( local, times( 0 ) ).releaseExclusive( NODE, 1L );
    }

    @Test
    public void shouldNotTalkToLocalLocksOnReentrancyShared() throws Exception
    {
        // Given we have grabbed and released a lock
        client.acquireShared( NODE, 1L );

        // When we grab and release that lock again
        client.acquireShared( NODE, 1L );
        client.releaseShared( NODE, 1L );

        // Then this should cause the local lock manager to hold the lock
        verify( local, times( 1 ) ).trySharedLock( NODE, 1L );
        verify( local, times( 0 ) ).releaseShared( NODE, 1L );
    }

    @Test
    public void shouldReturnNoLockSessionIfNotInitialized() throws Exception
    {
        // When
        int lockSessionId = client.getLockSessionId();

        // Then
        assertThat( lockSessionId, equalTo( -1 ) );
    }

    @Test
    public void shouldReturnDelegateIdIfInitialized() throws Exception
    {
        // Given
        client.acquireExclusive( ResourceTypes.NODE, 1L );

        // When
        int lockSessionId = client.getLockSessionId();

        // Then
        assertThat( lockSessionId, equalTo( local.getLockSessionId() ) );
    }

    @Test( expected = DistributedLockFailureException.class )
    public void mustThrowIfStartingNewLockSessionOnMasterThrowsComException() throws Exception
    {
        when( master.newLockSession( any( RequestContext.class ) ) ).thenThrow( new ComException() );

        client.acquireShared( NODE, 1 );
    }

    @Test( expected = DistributedLockFailureException.class )
    public void mustThrowIfStartingNewLockSessionOnMasterThrowsTransactionFailureException() throws Exception
    {
        when( master.newLockSession( any( RequestContext.class ) ) ).thenThrow(
                new TransactionFailureException( Status.General.DatabaseUnavailable, "Not now" ) );

        client.acquireShared( NODE, 1 );
    }

    @Test( expected = DistributedLockFailureException.class )
    public void acquireSharedMustThrowIfMasterThrows() throws Exception
    {
        whenMasterAcquireShared().thenThrow( new ComException() );

        client.acquireShared( NODE, 1 );
    }

    @Test( expected = DistributedLockFailureException.class )
    public void acquireExclusiveMustThrowIfMasterThrows() throws Exception
    {
        whenMasterAcquireExclusive().thenThrow( new ComException() );

        client.acquireExclusive( NODE, 1 );
    }

    @Test( expected = UnsupportedOperationException.class )
    public void tryExclusiveMustBeUnsupported() throws Exception
    {
        client.tryExclusiveLock( NODE, 1 );
    }

    @Test( expected = UnsupportedOperationException.class )
    public void trySharedMustBeUnsupported() throws Exception
    {
        client.trySharedLock( NODE, 1 );
    }

    @Test( expected = DistributedLockFailureException.class )
    public void releaseAllMustThrowIfMasterThrows() throws Exception
    {
        when( master.endLockSession( any( RequestContext.class ), anyBoolean() ) ).thenThrow( new ComException() );

        client.acquireExclusive( NODE, 1 ); // initialise
        client.releaseAll();
    }

    @Test( expected = DistributedLockFailureException.class )
    public void closeMustThrowIfMasterThrows() throws Exception
    {
        when( master.endLockSession( any( RequestContext.class ), anyBoolean() ) ).thenThrow( new ComException() );

        client.acquireExclusive( NODE, 1 ); // initialise
        client.close();
    }

    @Test
    public void mustCloseLocalClientEvenIfMasterThrows() throws Exception
    {
        when( master.endLockSession( any( RequestContext.class ), anyBoolean() ) ).thenThrow( new ComException() );

        try
        {
            client.acquireExclusive( NODE, 1 ); // initialise
            client.close();
            fail( "Expected client.close to throw" );
        }
        catch ( Exception ignore )
        {
        }
        verify( local ).close();
    }

    @Test( expected = org.neo4j.graphdb.TransientDatabaseFailureException.class )
    public void mustThrowTransientTransactionFailureIfDatabaseUnavailable() throws Exception
    {
        availabilityGuard.shutdown();

        client.acquireExclusive( NODE, 1 );
    }

    @Test
    public void shouldFailWithTransientErrorOnDbUnavailable() throws Exception
    {
        // GIVEN
        availabilityGuard.shutdown();

        // WHEN
        try
        {
            client.acquireExclusive( NODE, 0 );
            fail( "Should fail" );
        }
        catch ( TransientFailureException e )
        {
            // THEN Good
        }
    }
}
