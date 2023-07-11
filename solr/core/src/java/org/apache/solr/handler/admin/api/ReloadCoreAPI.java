/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.handler.admin.api;

import static org.apache.solr.client.solrj.impl.BinaryResponseParser.BINARY_CONTENT_TYPE_V2;
import static org.apache.solr.security.PermissionNameProvider.Name.CORE_EDIT_PERM;

import io.swagger.v3.oas.annotations.Parameter;
import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import org.apache.solr.api.JerseyResource;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.jersey.SolrJerseyResponse;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

/**
 * V2 API for reloading collections.
 *
 * <p>The new API (POST /v2/core/coreName/reload {...}) is analogous to the v1
 * /admin/core?action=RELOAD command.
 */
@Path("/cores/{coreName}/reload")
public class ReloadCoreAPI extends JerseyResource {

  private final SolrQueryRequest solrQueryRequest;
  private final SolrQueryResponse solrQueryResponse;
  private final CoreContainer coreContainer;

  @Inject
  public ReloadCoreAPI(
      SolrQueryRequest solrQueryRequest,
      SolrQueryResponse solrQueryResponse,
      CoreContainer coreContainer) {
    this.solrQueryRequest = solrQueryRequest;
    this.solrQueryResponse = solrQueryResponse;
    this.coreContainer = coreContainer;
  }

  @POST
  @Produces({"application/json", "application/xml", BINARY_CONTENT_TYPE_V2})
  @PermissionName(CORE_EDIT_PERM)
  public SolrJerseyResponse reloadCore(
      @Parameter(description = "The name of the core to snapshot.", required = true)
          @PathParam("coreName")
          String coreName) {
    SolrJerseyResponse solrJerseyResponse = instantiateJerseyResponse(ReloadCoreResponse.class);
    coreContainer.reload(coreName);
    return solrJerseyResponse;
  }

  public static class ReloadCoreResponse extends SolrJerseyResponse {}
}
