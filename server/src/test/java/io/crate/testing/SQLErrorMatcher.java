/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.testing;

import io.crate.protocols.postgres.PGError;
import io.crate.protocols.postgres.PGErrorStatus;
import io.crate.rest.action.HttpError;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.hamcrest.Matcher;
import org.postgresql.util.PSQLException;

import static io.crate.testing.MoreMatchers.withFeature;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class SQLErrorMatcher {

    public static <T extends Throwable> Matcher<T> isSQLError(Matcher<String> msg,
                                                              PGErrorStatus pgErrorStatus,
                                                              HttpResponseStatus httpResponseStatus,
                                                              int errorCode) {
        return anyOf(isPGError(msg, pgErrorStatus), isHttpError(msg, httpResponseStatus, errorCode));
    }

    public static <T extends Throwable> Matcher<T> isPGError(Matcher<String> msg, PGErrorStatus pgErrorStatus) {
        return allOf(
            instanceOf(PSQLException.class),
            withFeature(e -> stripErrorMessage(fromPSQLException((PSQLException) e).message()), "error message", msg),
            withFeature(e -> fromPSQLException((PSQLException) e).status(), "error status", equalTo(pgErrorStatus))
        );
    }

    public static <T extends Throwable> Matcher<T> isHttpError(Matcher<String> msg,
                                                               HttpResponseStatus httpResponseStatus,
                                                               int errorCode) {
        return allOf(
            withFeature(e -> stripErrorMessage(HttpError.fromThrowable(e).message()), "error message", msg),
            withFeature(e -> HttpError.fromThrowable(e).errorCode(), "http error code", equalTo(errorCode)),
            withFeature(e -> HttpError.fromThrowable(e).httpResponseStatus(), "http response", equalTo(httpResponseStatus))
        );
    }

    private static String stripErrorMessage(String msg) {
        //Converts 'Error: SQLParseException: error' to 'error' or 'Exception: error' to 'error'
        return msg.replaceAll("\\p{Upper}\\p{Alpha}++:\\s{1}", "");
    }

    private static PGError fromPSQLException(PSQLException e) {
        var sqlState = e.getServerErrorMessage().getSQLState();
        PGErrorStatus errorStatus = null;
        for (var status : PGErrorStatus.values()) {
            if (status.code().equals(sqlState)) {
                errorStatus = status;
                break;
            }
        }
        assert errorStatus != null : "Unknown psql error code: " + sqlState;
        return new PGError(errorStatus, e.getMessage(), e);
    }
}
