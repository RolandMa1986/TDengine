package com.taosdata.jdbc.ws;

import com.taosdata.jdbc.*;
import com.taosdata.jdbc.rs.RestfulResultSet;
import com.taosdata.jdbc.rs.RestfulResultSetMetaData;
import com.taosdata.jdbc.ws.entity.*;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public abstract class AbstractWSResultSet extends AbstractResultSet {
    public static DateTimeFormatter rfc3339Parser = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 2, 9, true)
            .optionalEnd()
            .appendOffset("+HH:MM", "Z").toFormatter()
            .withResolverStyle(ResolverStyle.STRICT)
            .withChronology(IsoChronology.INSTANCE);


    protected final Statement statement;
    protected final Transport transport;
    protected final RequestFactory factory;
    protected final long queryId;

    protected boolean isClosed;
    // meta
    protected final ResultSetMetaData metaData;
    protected final List<RestfulResultSet.Field> fields = new ArrayList<>();
    protected final List<String> columnNames;
    // data
    protected List<List<Object>> result = new ArrayList<>();

    private int numOfRows = 0;
    protected int rowIndex = 0;

    public AbstractWSResultSet(Statement statement, Transport transport, RequestFactory factory,
                               QueryResp response, String database) throws SQLException {
        this.statement = statement;
        this.transport = transport;
        this.factory = factory;
        this.queryId = response.getId();
        columnNames = Arrays.asList(response.getFieldsNames());
        for (int i = 0; i < response.getFieldsCount(); i++) {
            String colName = response.getFieldsNames()[i];
            int taosType = response.getFieldsTypes()[i];
            int jdbcType = TSDBConstants.taosType2JdbcType(taosType);
            int length = response.getFieldsLengths()[i];
            fields.add(new RestfulResultSet.Field(colName, jdbcType, length, "", taosType));
        }
        this.metaData = new RestfulResultSetMetaData(database, fields, null);

    }

    private boolean forward() {
        if (this.rowIndex > this.numOfRows) {
            return false;
        }

        return ((++this.rowIndex) < this.numOfRows);
    }

    public void reset() {
        this.rowIndex = 0;
    }

    @Override
    public boolean next() throws SQLException {
        if (isClosed()) {
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_RESULTSET_CLOSED);
        }

        if (this.forward()) {
            return true;
        }

        Request request = factory.generateFetch(queryId);
        CompletableFuture<Response> send = transport.send(request);
        try {
            Response response = send.get();
            FetchResp fetchResp = (FetchResp) response;
            if (Code.SUCCESS.getCode() != fetchResp.getCode()) {
//                TODO reWrite error type
                throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_UNKNOWN, fetchResp.getMessage());
            }
            this.reset();
            if (fetchResp.isCompleted()) {
                return false;
            }
            this.numOfRows = fetchResp.getRows();
            this.result = fetchJsonData();
            return true;
        } catch (InterruptedException | ExecutionException e) {
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_RESTFul_Client_IOException, e.getMessage());
        }
    }

    public abstract List<List<Object>> fetchJsonData() throws SQLException, ExecutionException, InterruptedException;

    @Override
    public void close() throws SQLException {
        this.isClosed = true;
        // TODO call taosadapter release resource
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        if (isClosed())
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_RESULTSET_CLOSED);
        return this.metaData;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }
}
