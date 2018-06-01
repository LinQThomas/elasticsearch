/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ml.action;

import org.elasticsearch.Version;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.tasks.BaseTasksRequest;
import org.elasticsearch.action.support.tasks.BaseTasksResponse;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.xpack.core.ml.MLMetadataField;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedConfig;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Objects;

public class StopDatafeedAction extends Action<StopDatafeedAction.Request, StopDatafeedAction.Response> {

    public static final StopDatafeedAction INSTANCE = new StopDatafeedAction();
    public static final String NAME = "cluster:admin/xpack/ml/datafeed/stop";
    public static final TimeValue DEFAULT_TIMEOUT = TimeValue.timeValueMinutes(5);

    private StopDatafeedAction() {
        super(NAME);
    }

    @Override
    public Response newResponse() {
        return new Response();
    }

    public static class Request extends BaseTasksRequest<Request> implements ToXContentObject {

        public static final ParseField TIMEOUT = new ParseField("timeout");
        public static final ParseField FORCE = new ParseField("force");
        public static final ParseField ALLOW_NO_DATAFEEDS = new ParseField("allow_no_datafeeds");

        public static ObjectParser<Request, Void> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString((request, datafeedId) -> request.datafeedId = datafeedId, DatafeedConfig.ID);
            PARSER.declareString((request, val) ->
                    request.setStopTimeout(TimeValue.parseTimeValue(val, TIMEOUT.getPreferredName())), TIMEOUT);
            PARSER.declareBoolean(Request::setForce, FORCE);
            PARSER.declareBoolean(Request::setAllowNoDatafeeds, ALLOW_NO_DATAFEEDS);
        }

        public static Request fromXContent(XContentParser parser) {
            return parseRequest(null, parser);
        }

        public static Request parseRequest(String datafeedId, XContentParser parser) {
            Request request = PARSER.apply(parser, null);
            if (datafeedId != null) {
                request.datafeedId = datafeedId;
            }
            return request;
        }

        private String datafeedId;
        private String[] resolvedStartedDatafeedIds;
        private TimeValue stopTimeout = DEFAULT_TIMEOUT;
        private boolean force = false;
        private boolean allowNoDatafeeds = true;

        public Request(String datafeedId) {
            this.datafeedId = ExceptionsHelper.requireNonNull(datafeedId, DatafeedConfig.ID.getPreferredName());
            this.resolvedStartedDatafeedIds = new String[] { datafeedId };
        }

        public Request() {
        }

        public String getDatafeedId() {
            return datafeedId;
        }

        public String[] getResolvedStartedDatafeedIds() {
            return resolvedStartedDatafeedIds;
        }

        public void setResolvedStartedDatafeedIds(String[] resolvedStartedDatafeedIds) {
            this.resolvedStartedDatafeedIds = resolvedStartedDatafeedIds;
        }

        public TimeValue getStopTimeout() {
            return stopTimeout;
        }

        public void setStopTimeout(TimeValue stopTimeout) {
            this.stopTimeout = ExceptionsHelper.requireNonNull(stopTimeout, TIMEOUT.getPreferredName());
        }

        public boolean isForce() {
            return force;
        }

        public void setForce(boolean force) {
            this.force = force;
        }

        public boolean allowNoDatafeeds() {
            return allowNoDatafeeds;
        }

        public void setAllowNoDatafeeds(boolean allowNoDatafeeds) {
            this.allowNoDatafeeds = allowNoDatafeeds;
        }

        @Override
        public boolean match(Task task) {
            for (String id : resolvedStartedDatafeedIds) {
                String expectedDescription = MLMetadataField.datafeedTaskId(id);
                if (task instanceof StartDatafeedAction.DatafeedTaskMatcher && expectedDescription.equals(task.getDescription())){
                    return true;
                }
            }
            return false;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            datafeedId = in.readString();
            resolvedStartedDatafeedIds = in.readStringArray();
            stopTimeout = in.readTimeValue();
            force = in.readBoolean();
            if (in.getVersion().onOrAfter(Version.V_6_1_0)) {
                allowNoDatafeeds = in.readBoolean();
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(datafeedId);
            out.writeStringArray(resolvedStartedDatafeedIds);
            out.writeTimeValue(stopTimeout);
            out.writeBoolean(force);
            if (out.getVersion().onOrAfter(Version.V_6_1_0)) {
                out.writeBoolean(allowNoDatafeeds);
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(datafeedId, stopTimeout, force, allowNoDatafeeds);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(DatafeedConfig.ID.getPreferredName(), datafeedId);
            builder.field(TIMEOUT.getPreferredName(), stopTimeout.getStringRep());
            builder.field(FORCE.getPreferredName(), force);
            builder.field(ALLOW_NO_DATAFEEDS.getPreferredName(), allowNoDatafeeds);
            builder.endObject();
            return builder;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Request other = (Request) obj;
            return Objects.equals(datafeedId, other.datafeedId) &&
                    Objects.equals(stopTimeout, other.stopTimeout) &&
                    Objects.equals(force, other.force) &&
                    Objects.equals(allowNoDatafeeds, other.allowNoDatafeeds);
        }
    }

    public static class Response extends BaseTasksResponse implements Writeable {

        private boolean stopped;

        public Response(boolean stopped) {
            super(null, null);
            this.stopped = stopped;
        }

        public Response(StreamInput in) throws IOException {
            super(null, null);
            readFrom(in);
        }

        public Response() {
            super(null, null);
        }

        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            stopped = in.readBoolean();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeBoolean(stopped);
        }
    }

    static class RequestBuilder extends ActionRequestBuilder<Request, Response> {

        RequestBuilder(ElasticsearchClient client, StopDatafeedAction action) {
            super(client, action, new Request());
        }
    }

}
