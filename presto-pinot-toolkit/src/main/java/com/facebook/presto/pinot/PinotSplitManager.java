/*
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
package com.facebook.presto.pinot;

import com.facebook.presto.pinot.query.PinotQueryGenerator.GeneratedPql;
import com.facebook.presto.spi.ConnectorId;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.ConnectorSplitSource;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ErrorCode;
import com.facebook.presto.spi.ErrorCodeSupplier;
import com.facebook.presto.spi.ErrorType;
import com.facebook.presto.spi.FixedSplitSource;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.connector.ConnectorSplitManager;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;
import com.google.common.collect.Iterables;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.facebook.presto.pinot.PinotSplit.createBrokerSplit;
import static com.facebook.presto.pinot.PinotSplit.createSegmentSplit;
import static com.facebook.presto.pinot.query.PinotQueryGeneratorContext.TABLE_NAME_SUFFIX_TEMPLATE;
import static com.facebook.presto.pinot.query.PinotQueryGeneratorContext.TIME_BOUNDARY_FILTER_TEMPLATE;
import static com.facebook.presto.spi.ErrorType.USER_ERROR;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

public class PinotSplitManager
        implements ConnectorSplitManager
{
    private final String connectorId;
    private final PinotConnection pinotPrestoConnection;

    @Inject
    public PinotSplitManager(ConnectorId connectorId, PinotConnection pinotPrestoConnection)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null").toString();
        this.pinotPrestoConnection = requireNonNull(pinotPrestoConnection, "pinotPrestoConnection is null");
    }

    protected ConnectorSplitSource generateSplitForBrokerBasedScan(GeneratedPql brokerPql)
    {
        return new FixedSplitSource(singletonList(createBrokerSplit(connectorId, brokerPql)));
    }

    protected ConnectorSplitSource generateSplitsForSegmentBasedScan(
            PinotTableLayoutHandle pinotLayoutHandle,
            ConnectorSession session)
    {
        PinotTableHandle tableHandle = pinotLayoutHandle.getTable();
        String tableName = tableHandle.getTableName();
        Map<String, Map<String, List<String>>> routingTable;

        routingTable = pinotPrestoConnection.getRoutingTable(tableName);

        List<ConnectorSplit> splits = new ArrayList<>();
        if (!routingTable.isEmpty()) {
            GeneratedPql segmentPql = tableHandle.getPql().orElseThrow(() -> new PinotException(PinotErrorCode.PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(), "Expected to find realtime and offline pql in " + tableHandle));
            PinotClusterInfoFetcher.TimeBoundary timeBoundary = pinotPrestoConnection.getTimeBoundary(tableName);
            String realtime = getSegmentPql(segmentPql, "_REALTIME", timeBoundary.getOnlineTimePredicate());
            String offline = getSegmentPql(segmentPql, "_OFFLINE", timeBoundary.getOfflineTimePredicate());
            generateSegmentSplits(splits, routingTable, tableName, "_REALTIME", session, realtime);
            generateSegmentSplits(splits, routingTable, tableName, "_OFFLINE", session, offline);
        }

        Collections.shuffle(splits);
        return new FixedSplitSource(splits);
    }

    private String getSegmentPql(GeneratedPql basePql, String suffix, Optional<String> timePredicate)
    {
        String pql = basePql.getPql().replace(TABLE_NAME_SUFFIX_TEMPLATE, suffix);
        if (timePredicate.isPresent()) {
            String tp = timePredicate.get();
            pql = pql.replace(TIME_BOUNDARY_FILTER_TEMPLATE, basePql.isHaveFilter() ? tp : " WHERE " + tp);
        }
        else {
            pql = pql.replace(TIME_BOUNDARY_FILTER_TEMPLATE, "");
        }
        return pql;
    }

    protected void generateSegmentSplits(
            List<ConnectorSplit> splits,
            Map<String, Map<String, List<String>>> routingTable,
            String tableName,
            String tableNameSuffix,
            ConnectorSession session,
            String pql)
    {
        final String finalTableName = tableName + tableNameSuffix;
        int segmentsPerSplitConfigured = PinotSessionProperties.getNumSegmentsPerSplit(session);
        for (String routingTableName : routingTable.keySet()) {
            if (!routingTableName.equalsIgnoreCase(finalTableName)) {
                continue;
            }

            Map<String, List<String>> hostToSegmentsMap = routingTable.get(routingTableName);
            hostToSegmentsMap.forEach((host, segments) -> {
                int numSegmentsInThisSplit = Math.min(segments.size(), segmentsPerSplitConfigured);
                // segments is already shuffled
                Iterables.partition(segments, numSegmentsInThisSplit).forEach(
                        segmentsForThisSplit -> splits.add(
                                createSegmentSplit(connectorId, pql, segmentsForThisSplit, host)));
            });
        }
    }

    public enum QueryNotAdequatelyPushedDownErrorCode
            implements ErrorCodeSupplier
    {
        PQL_NOT_PRESENT(1, USER_ERROR, "Query uses unsupported expressions that cannot be pushed into the storage engine. Please see https://XXX for more details");

        private final ErrorCode errorCode;

        QueryNotAdequatelyPushedDownErrorCode(int code, ErrorType type, String guidance)
        {
            errorCode = new ErrorCode(code + 0x0625_0000, name() + ": " + guidance, type);
        }

        @Override
        public ErrorCode toErrorCode()
        {
            return errorCode;
        }
    }

    public static class QueryNotAdequatelyPushedDownException
            extends PrestoException
    {
        private final String connectorId;
        private final ConnectorTableHandle connectorTableHandle;

        public QueryNotAdequatelyPushedDownException(
                QueryNotAdequatelyPushedDownErrorCode errorCode,
                ConnectorTableHandle connectorTableHandle,
                String connectorId)
        {
            super(requireNonNull(errorCode, "error code is null"), (String) null);
            this.connectorId = requireNonNull(connectorId, "connector id is null");
            this.connectorTableHandle = requireNonNull(connectorTableHandle, "connector table handle is null");
        }

        @Override
        public String getMessage()
        {
            return super.getMessage() + String.format(" table: %s:%s", connectorId, connectorTableHandle);
        }
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transactionHandle,
            ConnectorSession session,
            ConnectorTableLayoutHandle layout,
            SplitSchedulingContext splitSchedulingContext)
    {
        PinotTableLayoutHandle pinotLayoutHandle = (PinotTableLayoutHandle) layout;
        PinotTableHandle pinotTableHandle = pinotLayoutHandle.getTable();
        Supplier<PrestoException> errorSupplier = () -> new QueryNotAdequatelyPushedDownException(QueryNotAdequatelyPushedDownErrorCode.PQL_NOT_PRESENT, pinotTableHandle, connectorId);
        if (!pinotTableHandle.getIsQueryShort().orElseThrow(errorSupplier)) {
            if (PinotSessionProperties.isForbidSegmentQueries(session)) {
                throw errorSupplier.get();
            }
            return generateSplitsForSegmentBasedScan(pinotLayoutHandle, session);
        }
        else {
            return generateSplitForBrokerBasedScan(pinotTableHandle.getPql().orElseThrow(errorSupplier));
        }
    }
}
