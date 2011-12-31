/*
 * Copyright 2011 LMAX Ltd.
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
package com.lmax.disruptor;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.integration.junit4.JMock;
import org.jmock.lib.legacy.ClassImposteriser;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.lmax.disruptor.ClaimStrategy;
import com.lmax.disruptor.MultiThreadedClaimStrategy;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.Sequencer;

@RunWith(JMock.class)
public final class MultiThreadedClaimStrategyTest
{
    private Mockery context = new Mockery()
    {
        {
            setImposteriser(ClassImposteriser.INSTANCE);
        }
    };

    private static final int BUFFER_SIZE = 8;
    private final ClaimStrategy claimStrategy = new MultiThreadedClaimStrategy(BUFFER_SIZE);
    
    @Test(expected = IllegalArgumentException.class)
    public void shouldNotCreateBufferWithNonPowerOf2() throws Exception
    {
        new MultiThreadedClaimStrategy(1024, 129);
    }

    @Test
    public void shouldGetCorrectBufferSize()
    {
        assertEquals(BUFFER_SIZE, claimStrategy.getBufferSize());
    }

    @Test
    public void shouldGetInitialSequence()
    {
        assertEquals(Sequencer.INITIAL_CURSOR_VALUE, claimStrategy.getSequence());
    }

    @Test
    public void shouldClaimInitialSequence()
    {
        final Sequence dependentSequence = context.mock(Sequence.class);

        context.checking(new Expectations()
        {
            {
                never(dependentSequence);
            }
        });

        Sequence[] dependentSequences = { dependentSequence };
        final long expectedSequence = Sequencer.INITIAL_CURSOR_VALUE + 1L;

        assertEquals(expectedSequence, claimStrategy.incrementAndGet(dependentSequences));
        assertEquals(expectedSequence, claimStrategy.getSequence());
    }

    @Test
    public void shouldClaimInitialBatchOfSequences()
    {
        final Sequence dependentSequence = context.mock(Sequence.class);

        context.checking(new Expectations()
        {
            {
                never(dependentSequence);
            }
        });

        Sequence[] dependentSequences = { dependentSequence };
        final int batchSize = 5;
        final long expectedSequence = Sequencer.INITIAL_CURSOR_VALUE + batchSize;

        assertEquals(expectedSequence, claimStrategy.incrementAndGet(batchSize, dependentSequences));
        assertEquals(expectedSequence, claimStrategy.getSequence());
    }

    @Test
    public void shouldSetSequenceToValue()
    {
        final Sequence dependentSequence = context.mock(Sequence.class);

        context.checking(new Expectations()
        {
            {
                never(dependentSequence);
            }
        });

        Sequence[] dependentSequences = { dependentSequence };
        final int expectedSequence = 5;
        claimStrategy.setSequence(expectedSequence, dependentSequences);

        assertEquals(expectedSequence, claimStrategy.getSequence());
    }

    @Test
    public void shouldHaveInitialAvailableCapacity()
    {
        final Sequence dependentSequence = context.mock(Sequence.class);

        context.checking(new Expectations()
        {
            {
                never(dependentSequence);
            }
        });

        Sequence[] dependentSequences = { dependentSequence };

        assertTrue(claimStrategy.hasAvailableCapacity(1, dependentSequences));
    }

    @Test
    public void shouldNotHaveAvailableCapacityWhenBufferIsFull()
    {
        final Sequence dependentSequence = context.mock(Sequence.class);

        context.checking(new Expectations()
        {
            {
                oneOf(dependentSequence).get();
                will(returnValue(Long.valueOf(Sequencer.INITIAL_CURSOR_VALUE)));
            }
        });

        Sequence[] dependentSequences = { dependentSequence };
        claimStrategy.setSequence(claimStrategy.getBufferSize() - 1L, dependentSequences);

        assertFalse(claimStrategy.hasAvailableCapacity(1, dependentSequences));
    }

    @Test
    public void shouldNotReturnNextClaimSequenceUntilBufferHasReserve() throws InterruptedException
    {
        final Sequence dependentSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
        final Sequence[] dependentSequences = { dependentSequence };
        claimStrategy.setSequence(claimStrategy.getBufferSize() - 1L, dependentSequences);

        final AtomicBoolean done = new AtomicBoolean(false);
        final CountDownLatch beforeLatch = new CountDownLatch(1);
        final CountDownLatch afterLatch = new CountDownLatch(1);

        final Runnable publisher = new Runnable()
        {
            @Override
            public void run()
            {
                beforeLatch.countDown();

                assertEquals(claimStrategy.getBufferSize(), claimStrategy.incrementAndGet(dependentSequences));

                done.set(true);
                afterLatch.countDown();
            }
        };
        new Thread(publisher).start();

        beforeLatch.await();

        Thread.sleep(1000L);
        assertFalse(done.get());

        dependentSequence.set(dependentSequence.get() + 1L);

        afterLatch.await();
        assertEquals(claimStrategy.getBufferSize(), claimStrategy.getSequence());
    }

    @Test
    public void shouldSerialisePublishingOnTheCursor()
    {
        final Sequence dependentSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
        final Sequence[] dependentSequences = { dependentSequence };

        final long sequence = claimStrategy.incrementAndGet(dependentSequences);

        final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
        claimStrategy.serialisePublishing(sequence, cursor, 1);

        assertEquals(sequence, cursor.get());
    }

    @Test
    public void shouldSerialisePublishingOnTheCursorWhenTwoThreadsArePublishing() throws InterruptedException
    {
        final Sequence dependentSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
        final Sequence[] dependentSequences = { dependentSequence };

        final AtomicReferenceArray<String> threadSequences = new AtomicReferenceArray<String>(2);

        final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE)
        {
            @Override
            public boolean compareAndSet(long expectedSequence, long nextSequence)
            {
                final String threadName = Thread.currentThread().getName();
                if ("tOne".equals(threadName) || "tTwo".equals(threadName))
                {
                    threadSequences.set((int)nextSequence, threadName);
                }
                
                return super.compareAndSet(expectedSequence, nextSequence);
            }
        };

        final CountDownLatch orderingLatch = new CountDownLatch(1);

        final Runnable publisherOne = new Runnable()
        {
            @Override
            public void run()
            {
                final long sequence = claimStrategy.incrementAndGet(dependentSequences);
                orderingLatch.countDown();

                try
                {
                    Thread.sleep(1000L);
                }
                catch (InterruptedException e)
                {
                    // don't care
                }

                claimStrategy.serialisePublishing(sequence, cursor, 1);
            }
        };

        final Runnable publisherTwo = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    orderingLatch.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }

                final long sequence = claimStrategy.incrementAndGet(dependentSequences);

                claimStrategy.serialisePublishing(sequence, cursor, 1);
            }
        };

        Thread tOne = new Thread(publisherOne);
        Thread tTwo = new Thread(publisherTwo);
        tOne.setName("tOne");
        tTwo.setName("tTwo");
        tOne.start();
        tTwo.start();
        tOne.join();
        tTwo.join();
        
        // One thread can end up setting both sequences.
        assertThat(threadSequences.get(0), is(notNullValue()));
        assertThat(threadSequences.get(1), is(notNullValue()));
    }
}
