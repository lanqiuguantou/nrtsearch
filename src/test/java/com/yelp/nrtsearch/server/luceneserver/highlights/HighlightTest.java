/*
 * Copyright 2022 Yelp Inc.
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
package com.yelp.nrtsearch.server.luceneserver.highlights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.google.protobuf.UInt32Value;
import com.yelp.nrtsearch.server.grpc.AddDocumentRequest;
import com.yelp.nrtsearch.server.grpc.AddDocumentRequest.MultiValuedField;
import com.yelp.nrtsearch.server.grpc.FieldDefRequest;
import com.yelp.nrtsearch.server.grpc.Highlight;
import com.yelp.nrtsearch.server.grpc.Highlight.Settings;
import com.yelp.nrtsearch.server.grpc.MatchQuery;
import com.yelp.nrtsearch.server.grpc.PhraseQuery;
import com.yelp.nrtsearch.server.grpc.Query;
import com.yelp.nrtsearch.server.grpc.SearchRequest;
import com.yelp.nrtsearch.server.grpc.SearchResponse;
import com.yelp.nrtsearch.server.grpc.SearchResponse.Hit;
import com.yelp.nrtsearch.server.luceneserver.ServerTestCase;
import io.grpc.StatusRuntimeException;
import io.grpc.testing.GrpcCleanupRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.ClassRule;
import org.junit.Test;

public class HighlightTest extends ServerTestCase {

  private static final List<String> ALL_FIELDS = List.of("doc_id", "comment");

  @ClassRule public static final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  protected FieldDefRequest getIndexDef(String name) throws IOException {
    return getFieldsFromResourceFile("/highlights/register_fields_highlights.json");
  }

  protected void initIndex(String name) throws Exception {
    List<AddDocumentRequest> docs = new ArrayList<>();
    AddDocumentRequest request =
        AddDocumentRequest.newBuilder()
            .setIndexName(name)
            .putFields("doc_id", MultiValuedField.newBuilder().addValue("1").build())
            .putFields(
                "comment",
                MultiValuedField.newBuilder()
                    .addValue("the food here is amazing, service was good")
                    .build())
            .build();
    docs.add(request);
    request =
        AddDocumentRequest.newBuilder()
            .setIndexName(name)
            .putFields("doc_id", MultiValuedField.newBuilder().addValue("2").build())
            .putFields(
                "comment",
                MultiValuedField.newBuilder()
                    .addValue(
                        "This is my first time eating at this restaurant. The food here is pretty good, the service could be better. My favorite food was chilly chicken.")
                    .build())
            .build();
    docs.add(request);
    addDocuments(docs.stream());
  }

  @Test
  public void testBasicHighlight() {
    Highlight highlight = Highlight.newBuilder().addFields("comment").build();
    SearchResponse response = doHighlightQuery(highlight);

    assertFields(response);

    assertThat(response.getHits(0).getHighlightsMap().get("comment").getFragments(0))
        .isEqualTo("the <em>food</em> here is amazing, service was good");
    assertThat(response.getHits(1).getHighlightsMap().get("comment").getFragments(0))
        .isEqualTo(
            "restaurant. The <em>food</em> here is pretty good, the service could be better. My favorite <em>food</em> was chilly chicken");
    assertThat(response.getDiagnostics().getHighlightTimeMs()).isGreaterThan(0);
  }

  @Test
  public void testHighlightGlobalSettings() {
    Settings settings =
        Settings.newBuilder()
            .addPreTags("<START>")
            .addPostTags("<END>")
            .setFragmentSize(UInt32Value.newBuilder().setValue(18))
            .setMaxNumberOfFragments(UInt32Value.newBuilder().setValue(3))
            .setHighlightQuery(
                Query.newBuilder()
                    .setMatchQuery(
                        MatchQuery.newBuilder().setField("comment").setQuery("food is good")))
            .build();
    Highlight highlight = Highlight.newBuilder().setSettings(settings).addFields("comment").build();
    SearchResponse response = doHighlightQuery(highlight);

    assertThat(response.getHits(0).getHighlightsMap().get("comment").getFragmentsList())
        .containsExactly(
            "the <START>food<END> here <START>is<END> amazing", "service was <START>good<END>");
    assertThat(response.getHits(1).getHighlightsMap().get("comment").getFragmentsList())
        .containsExactly(
            "The <START>food<END> here <START>is<END> pretty",
            "This <START>is<END> my first time",
            "pretty <START>good<END>, the service");
    assertThat(response.getDiagnostics().getHighlightTimeMs()).isGreaterThan(0);
  }

  @Test
  public void testHighlightFieldSettings() {
    Settings globalSettings =
        Settings.newBuilder()
            .addPreTags("<s>")
            .addPostTags("<e>")
            .setFragmentSize(UInt32Value.newBuilder().setValue(50))
            .setMaxNumberOfFragments(UInt32Value.newBuilder().setValue(5))
            .setHighlightQuery(
                Query.newBuilder()
                    .setMatchQuery(MatchQuery.newBuilder().setField("comment").setQuery("pretty")))
            .build();
    Settings fieldSettings =
        Settings.newBuilder()
            .addPreTags("<START>")
            .addPostTags("<END>")
            .setFragmentSize(UInt32Value.newBuilder().setValue(18))
            .setMaxNumberOfFragments(UInt32Value.newBuilder().setValue(3))
            .setHighlightQuery(
                Query.newBuilder()
                    .setMatchQuery(
                        MatchQuery.newBuilder().setField("comment").setQuery("food is good")))
            .build();
    Highlight highlight =
        Highlight.newBuilder()
            .setSettings(globalSettings)
            .addFields("comment")
            .putFieldSettings("comment", fieldSettings)
            .build();
    SearchResponse response = doHighlightQuery(highlight);

    assertThat(response.getHits(0).getHighlightsMap().get("comment").getFragmentsList())
        .containsExactly(
            "the <START>food<END> here <START>is<END> amazing", "service was <START>good<END>");
    assertThat(response.getHits(1).getHighlightsMap().get("comment").getFragmentsList())
        .containsExactly(
            "The <START>food<END> here <START>is<END> pretty",
            "This <START>is<END> my first time",
            "pretty <START>good<END>, the service");
    assertThat(response.getDiagnostics().getHighlightTimeMs()).isGreaterThan(0);
  }

  @Test
  public void testHighlightsAbsentForOneHit() {
    Highlight highlight =
        Highlight.newBuilder()
            .addFields("comment")
            .setSettings(
                Settings.newBuilder()
                    .setHighlightQuery(
                        Query.newBuilder()
                            .setPhraseQuery(
                                PhraseQuery.newBuilder()
                                    .setField("comment")
                                    .setSlop(5)
                                    .addTerms("food")
                                    .addTerms("pretty")
                                    .addTerms("good"))))
            .build();
    SearchResponse response = doHighlightQuery(highlight);

    assertFields(response);

    assertThat(response.getHits(0).getHighlightsMap()).isEmpty();
    assertThat(response.getHits(1).getHighlightsMap().get("comment").getFragmentsList())
        .containsExactly(
            "first time eating at this restaurant. The <em>food</em> here is <em>pretty good</em>, the service could be better. My favorite");
    assertThat(response.getDiagnostics().getHighlightTimeMs()).isGreaterThan(0);
  }

  @Test
  public void testHighlightIncompatibleField() {
    Highlight highlight = Highlight.newBuilder().addFields("comment.no_search").build();
    try {
      doHighlightQuery(highlight);
      fail("No error for invalid field");
    } catch (StatusRuntimeException e) {
      assertThat(e.getMessage())
          .contains("Field comment.no_search is not searchable and cannot support highlights");
    }

    highlight = Highlight.newBuilder().addFields("comment.no_store").build();
    try {
      doHighlightQuery(highlight);
      fail("No error for invalid field");
    } catch (StatusRuntimeException e) {
      assertThat(e.getMessage())
          .contains("Field comment.no_store is not stored and cannot support highlights");
    }

    highlight = Highlight.newBuilder().addFields("comment.no_term_vectors_with_offsets").build();
    try {
      doHighlightQuery(highlight);
      fail("No error for invalid field");
    } catch (StatusRuntimeException e) {
      assertThat(e.getMessage())
          .contains(
              "Field comment.no_term_vectors_with_offsets does not have term vectors with positions and offsets and cannot support highlights");
    }
  }

  private String indexName() {
    return getIndices().get(0);
  }

  private SearchResponse doHighlightQuery(Highlight highlight) {
    return getGrpcServer()
        .getBlockingStub()
        .search(
            SearchRequest.newBuilder()
                .setIndexName(indexName())
                .setStartHit(0)
                .setTopHits(2)
                .addAllRetrieveFields(ALL_FIELDS)
                .setQuery(
                    Query.newBuilder()
                        .setMatchQuery(
                            MatchQuery.newBuilder().setField("comment").setQuery("food")))
                .setHighlight(highlight)
                .build());
  }

  private void assertFields(SearchResponse response) {
    Set<String> seenSet = new HashSet<>();
    for (Hit hit : response.getHitsList()) {
      String id = hit.getFieldsOrThrow("doc_id").getFieldValue(0).getTextValue();
      seenSet.add(id);
      if (id.equals("1")) {
        assertThat(hit.getFieldsOrThrow("comment").getFieldValue(0).getTextValue())
            .isEqualTo("the food here is amazing, service was good");
      } else if (id.equals("2")) {
        assertThat(hit.getFieldsOrThrow("comment").getFieldValue(0).getTextValue())
            .isEqualTo(
                "This is my first time eating at this restaurant. The food here is pretty good, the service could be better. My favorite food was chilly chicken.");
      } else {
        fail("Unknown id: " + id);
      }
    }
    assertThat(seenSet).containsExactly("1", "2");
  }
}