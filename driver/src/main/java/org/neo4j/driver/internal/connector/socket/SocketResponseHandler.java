/**
 * Copyright (c) 2002-2015 "Neo Technology,"
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
package org.neo4j.driver.internal.connector.socket;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.DatabaseException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.TransientException;
import org.neo4j.driver.internal.messaging.FailureMessage;
import org.neo4j.driver.internal.messaging.InitializeMessage;
import org.neo4j.driver.internal.messaging.MessageHandler;
import org.neo4j.driver.internal.messaging.RecordMessage;
import org.neo4j.driver.internal.messaging.RunMessage;
import org.neo4j.driver.internal.messaging.SuccessMessage;
import org.neo4j.driver.internal.spi.Logger;
import org.neo4j.driver.internal.spi.StreamCollector;

import static org.neo4j.driver.internal.messaging.AckFailureMessage.ACK_FAILURE;
import static org.neo4j.driver.internal.messaging.DiscardAllMessage.DISCARD_ALL;
import static org.neo4j.driver.internal.messaging.IgnoredMessage.IGNORED;
import static org.neo4j.driver.internal.messaging.PullAllMessage.PULL_ALL;

public class SocketResponseHandler implements MessageHandler
{
    public static final String[] NO_FIELDS = new String[0];
    private final Map<Integer,StreamCollector> collectors = new HashMap<>();

    /** If a failure occurs, the error gets stored here */
    private Neo4jException error;

    /** Counts number of responses, used to correlate response data with stream collectors */
    private int responseId = 0;

    private final Logger logger;

    public SocketResponseHandler( Logger logger )
    {
        this.logger = logger;
    }

    public int receivedResponses()
    {
        return responseId;
    }

    @Override
    public void handleRecordMessage( Value[] fields )
    {
        // This is not very efficient, using something like a singly-linked queue with a current collector as head
        // would take advantage of the ordered nature of exchanges and avoid all these objects allocated from boxing
        // below.
        StreamCollector collector = collectors.get( responseId );
        if ( collector != null )
        {
            collector.record( fields );
        }

        logger.debug( new RecordMessage( fields ).toString() );
    }

    @Override
    public void handleFailureMessage( String code, String message )
    {
        try
        {
            String[] parts = code.split( "\\." );
            String classification = parts[1];
            if ( classification.equals( "ClientError" ) )
            {
                error = new ClientException( code, message );
            }
            else if ( classification.equals( "TransientError" ) )
            {
                error = new TransientException( code, message );
            }
            else
            {
                error = new DatabaseException( code, message );
            }
        }
        finally
        {
            responseId++;
            // TODO: Allocating a new object to use the toString is a major GC issue
            // Add a #debug() call that can do formatting, so we can change this to
            // debug( "FAILURE %s %s", code, message ).
            logger.debug( new FailureMessage( code, message ).toString() );
        }
    }

    @Override
    public void handleSuccessMessage( Map<String,Value> meta )
    {
        StreamCollector collector = collectors.get( responseId );
        if ( collector != null )
        {
            collector.fieldNames( fieldNamesFromMeta( meta ) );
        }
        responseId++;
        logger.debug( new SuccessMessage( meta ).toString() );
    }

    @Override
    public void handleIgnoredMessage()
    {
        responseId++;
        logger.debug( IGNORED.toString() );
    }

    @Override
    public void handleDiscardAllMessage()
    {
        logger.debug( DISCARD_ALL.toString() );
    }

    @Override
    public void handleAckFailureMessage()
    {
        logger.debug( ACK_FAILURE.toString() );
    }

    @Override
    public void handlePullAllMessage()
    {
        logger.debug( PULL_ALL.toString() );
    }

    @Override
    public void handleInitializeMessage( String clientNameAndVersion ) throws IOException
    {
        logger.debug( new InitializeMessage( clientNameAndVersion ).toString() );
    }

    @Override
    public void handleRunMessage( String statement, Map<String,Value> parameters )
    {
        logger.debug( new RunMessage( statement, parameters ).toString() );
    }

    public void registerResultCollector( int correlationId, StreamCollector collector )
    {
        collectors.put( correlationId, collector );
    }

    public boolean serverFailureOccurred()
    {
        return error != null;
    }

    public Neo4jException serverFailure()
    {
        return error;
    }

    public void clear()
    {
        responseId = 0;
        error = null;
        collectors.clear();
    }

    private String[] fieldNamesFromMeta( Map<String,Value> meta )
    {
        String[] fields;
        Value fieldValue = meta.get( "fields" );
        if ( fieldValue == null || fieldValue.size() == 0 )
        {
            fields = NO_FIELDS;
        }
        else
        {
            fields = new String[(int) fieldValue.size()];
            int idx = 0;
            for ( Value value : fieldValue )
            {
                fields[idx++] = value.javaString();
            }

        }
        return fields;
    }

}
