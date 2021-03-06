// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.remote.Digests.ActionKey;
import com.google.devtools.remoteexecution.v1test.RequestMetadata;
import com.google.devtools.remoteexecution.v1test.ToolDetails;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.MetadataUtils;

/** Utility functions to handle Metadata for remote Grpc calls. */
public class TracingMetadataUtils {

  private TracingMetadataUtils() {}

  private static final Context.Key<RequestMetadata> CONTEXT_KEY =
      Context.key("remote-grpc-metadata");

  @VisibleForTesting
  public static final Metadata.Key<RequestMetadata> METADATA_KEY =
      ProtoUtils.keyForProto(RequestMetadata.getDefaultInstance());

  /**
   * Returns a new gRPC context derived from the current context, with
   * {@link RequestMetadata} accessible by the {@link fromCurrentContext()} method.
   *
   * <p>The {@link RequestMetadata} is constructed using the provided arguments
   * and the current tool version.
   */
  public static Context contextWithMetadata(
      String buildRequestId, String commandId, ActionKey actionKey) {
    RequestMetadata metadata =
        RequestMetadata.newBuilder()
            .setCorrelatedInvocationsId(buildRequestId)
            .setToolInvocationId(commandId)
            .setActionId(actionKey.getDigest().getHash())
            .setToolDetails(
                ToolDetails.newBuilder()
                    .setToolName("bazel")
                    .setToolVersion(BlazeVersionInfo.instance().getVersion()))
            .build();
    return Context.current().withValue(CONTEXT_KEY, metadata);
  }

  /**
   * Fetches a {@link RequestMetadata} defined on the current context.
   *
   * @throws {@link IllegalStateException} when the metadata is not defined in the current context.
   */
  public static RequestMetadata fromCurrentContext() {
    RequestMetadata metadata = CONTEXT_KEY.get();
    if (metadata == null) {
      throw new IllegalStateException("RequestMetadata not set in current context.");
    }
    return metadata;
  }

  /**
   * Creates a {@link Metadata} containing the {@link RequestMetadata} defined on the current
   * context.
   *
   * @throws {@link IllegalStateException} when the metadata is not defined in the current context.
   */
  public static Metadata headersFromCurrentContext() {
    Metadata headers = new Metadata();
    headers.put(METADATA_KEY, fromCurrentContext());
    return headers;
  }

  public static ClientInterceptor attachMetadataFromContextInterceptor() {
    return MetadataUtils.newAttachHeadersInterceptor(headersFromCurrentContext());
  }

  /** GRPC interceptor to add logging metadata to the GRPC context. */
  public static class ServerHeadersInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      RequestMetadata meta = headers.get(METADATA_KEY);
      if (meta == null) {
        throw new IllegalStateException("RequestMetadata not received from the client.");
      }
      Context ctx = Context.current().withValue(CONTEXT_KEY, meta);
      return Contexts.interceptCall(ctx, call, headers, next);
    }
  }

}
