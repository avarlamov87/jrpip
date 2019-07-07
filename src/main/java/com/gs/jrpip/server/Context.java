/*
  Copyright 2017 Goldman Sachs.
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
 */

package com.gs.jrpip.server;

import java.io.*;
import java.util.ArrayList;

import com.gs.jrpip.FixedDeflaterOutputStream;
import com.gs.jrpip.RequestId;
import com.gs.jrpip.util.stream.CopyOnWriteOutputStream;
import com.gs.jrpip.util.stream.OutputStreamBuilder;
import com.gs.jrpip.util.stream.VirtualOutputStream;

public class Context
{
    public static final long MAX_LIFE_TIME_FROM_FINISHED = 120000L; // 2 minutes

    // private static final boolean CAUSE_RANDOM_ERROR = true;
    private static final double ERROR_RATE = 0.98;

    private static final int CREATED_STATE = 0;
    private static final int READING_PARAMETERS_STATE = 1;
    private static final int INVOKING_METHOD_STATE = 2;
    private static final int FINISHED_STATE = 3;

    private static final long MAX_LIFE_TIME = 600000L; // 10 minutes

    private Object returnValue;
    private boolean exceptionThrown;
    private long lastSignOfLifeTime;
    private int state;
    private boolean compressed = true;
    private ArrayList<StreamBasedInvocator> invocators = new ArrayList<>(2);
    private OutputStreamBuilder outputStreamBuilder = VirtualOutputStream.NULL_OUTPUT_STREAM_BUILDER;

    public Object getReturnValue()
    {
        return this.returnValue;
    }

    public synchronized boolean isCreatedState()
    {
        return this.state == CREATED_STATE;
    }

    public void setOutputStreamBuilder(OutputStreamBuilder outputStreamBuilder)
    {
        this.outputStreamBuilder = outputStreamBuilder;
    }

    public synchronized void setReadingParametersState(StreamBasedInvocator invocator)
    {
        this.invocators.add(invocator);
        this.state = READING_PARAMETERS_STATE;
        this.lastSignOfLifeTime = System.currentTimeMillis();
    }

    public void setCompressed(boolean compressed)
    {
        this.compressed = compressed;
    }

    public synchronized void waitForInvocationToFinish()
    {
        if (this.isInvocationFinished())
        {
            return;
        }
        this.safeWait();
    }

    public synchronized void setInvokingMethodState(StreamBasedInvocator invocator)
    {
        switch (this.state)
        {
            case READING_PARAMETERS_STATE:
                if (this.invocators.size() > 1)
                {
                    this.abortOtherInvocators(invocator);
                }
                this.state = INVOKING_METHOD_STATE;
                this.lastSignOfLifeTime = System.currentTimeMillis();
                break;
            case INVOKING_METHOD_STATE:
                this.safeWait();  // fall through!
            case FINISHED_STATE:
                invocator.setAbortInvocation();
                break;
            case CREATED_STATE:
                throw new RuntimeException("invalid state transition!");
        }
    }

    private void abortOtherInvocators(StreamBasedInvocator invocator)
    {
        for (int i = 0; i < this.invocators.size(); i++)
        {
            StreamBasedInvocator tmpIvocator = this.invocators.get(i);
            if (!tmpIvocator.equals(invocator))
            {
                tmpIvocator.setAbortInvocation();
            }
        }
    }

    private void safeWait()
    {
        boolean done = false;
        while (!done)
        {
            try
            {
                this.wait();
                done = true;
            }
            catch (InterruptedException e)
            {
                // just ignore it.
            }
        }
    }

    public synchronized void setReturnValue(Object returnValue, boolean exceptionThrown)
    {
        this.exceptionThrown = exceptionThrown;
        this.state = FINISHED_STATE;
        this.lastSignOfLifeTime = System.currentTimeMillis();
        this.returnValue = returnValue;
        this.invocators = null;
        this.notifyAll();
    }

    public synchronized boolean isReadingParameters()
    {
        return this.state == READING_PARAMETERS_STATE;
    }

    public synchronized boolean isInvokingMethod()
    {
        return this.state == INVOKING_METHOD_STATE;
    }

    public synchronized boolean isInvocationFinished()
    {
        return this.state == FINISHED_STATE;
    }

    public synchronized boolean isExpired()
    {
        long elapsedTime = System.currentTimeMillis() - this.lastSignOfLifeTime;
        return this.isInvocationFinished() && elapsedTime > MAX_LIFE_TIME_FROM_FINISHED
                || elapsedTime > MAX_LIFE_TIME;
    }

    public void writeAndLogResponse(OutputStream outputStream, RequestId requestId) throws IOException
    {
        if (this.outputStreamBuilder.equals(VirtualOutputStream.NULL_OUTPUT_STREAM_BUILDER))
        {
            this.writeResponse(outputStream);
        }
        else
        {
            ObjectOutputStream binaryLogger = null;
            try
            {
                DataOutputStream dataOutputStream = this.outputStreamBuilder.newOutputStream();
                dataOutputStream.writeByte(OutputStreamBuilder.RESPONSE_HEADER);
                binaryLogger = new ObjectOutputStream(dataOutputStream);
                binaryLogger.writeObject(requestId);
                this.writeResponse(new CopyOnWriteOutputStream(outputStream, binaryLogger));
            }
            finally
            {
                if (binaryLogger != null)
                {
                    binaryLogger.close();
                }
            }
        }
    }

    private void writeResponse(OutputStream outputStream) throws IOException
    {
        //if (CAUSE_RANDOM_ERROR) if (Math.random() > ERROR_RATE) throw new IOException("Random error, for testing only!");
        if (this.exceptionThrown)
        {
            outputStream.write(StreamBasedInvocator.FAULT_STATUS);
        }
        else
        {
            outputStream.write(StreamBasedInvocator.OK_STATUS);
        }
        FixedDeflaterOutputStream zipped = null;
        if (this.compressed)
        {
            zipped = new FixedDeflaterOutputStream(outputStream);
            outputStream = zipped;
        }
        try
        {
            ObjectOutputStream out = new ObjectOutputStream(outputStream);
            //if (CAUSE_RANDOM_ERROR) if (Math.random() > ERROR_RATE) throw new IOException("Random error, for testing only!");
            out.writeObject(this.returnValue);
            out.flush();
        }
        finally
        {
            if (zipped != null)
            {
                zipped.finish();
            }
        }
    }
}
