package services.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import helpers.JsonLdConstants;
import models.Record;
import models.Resource;
import models.ResourceList;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.GeoBoundingBoxQueryBuilder;
import org.elasticsearch.index.query.GeoPolygonQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.index.query.functionscore.FieldValueFactorFunctionBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import play.Logger;
import services.ElasticsearchConfig;
import services.QueryContext;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.elasticsearch.index.query.QueryBuilders.termQuery;

public class ElasticsearchRepository extends Repository implements Readable, Writable, Queryable {

  private static ElasticsearchConfig mConfig;
  private Fuzziness mFuzziness;
  private static JsonNodeFactory mJsonNodeFactory = new JsonNodeFactory(false);
  private static Pattern patternTrailingSpecialChar = Pattern.compile("^(.*)([:!]){1,}$");

  public ElasticsearchRepository(Config aConfiguration) {
    super(aConfiguration);
    mConfig = new ElasticsearchConfig(aConfiguration);

    final Settings.Builder builder = Settings.builder();
    mConfig.getClusterSettings().forEach(builder::put);

    mFuzziness = mConfig.getFuzziness();
  }

  @Override
  public void addResource(@Nonnull final Resource aResource, Map<String, String> aMetadata) {
    Record record = new Record(aResource);
    for (String key : aMetadata.keySet()) {
      record.put(key, aMetadata.get(key));
    }
    addJson(record.toString(), record.getId());
  }

  @Override
  public void addResources(@Nonnull List<Resource> aResources, Map<String, String> aMetadata) {
    Map<String, String> records = new HashMap<>();
    for (Resource resource : aResources) {
      Record record = new Record(resource);
      for (String key : aMetadata.keySet()) {
        record.put(key, aMetadata.get(key));
      }
      records.put(record.getId(), record.toString());
    }
    addJsonBulk(records);
  }

  @Override
  public Resource getResource(@Nonnull String aId) {
    try {
      Resource record = Resource.fromMap(getDocument(URLEncoder.encode(aId, Charset.defaultCharset().name())
        .concat(".").concat(Record.RESOURCE_KEY)));
      return record != null ? record.getAsResource(Record.RESOURCE_KEY) : null;
    } catch (IOException e) {
      Logger.error("Failed getting document.", e);
    }
    return null;
  }

  public List<Resource> getResources(@Nonnull String aField, @Nonnull Object aValue) {
    List<Resource> resources = new ArrayList<>();
    try {
      for (Map<String, Object> doc : getDocuments(aField, aValue)) {
        resources.add(Resource.fromMap(doc));
      }
    } catch (IOException e) {
      Logger.error("Failed getting multiple documents.", e);
    }
    return resources;
  }

  @Override
  public List<Resource> getAll(@Nonnull String aType) throws IOException {
    List<Resource> resources = new ArrayList<>();
    for (Map<String, Object> doc : getDocuments(Record.RESOURCE_KEY.concat(".")
      .concat(JsonLdConstants.TYPE), aType)) {
      resources.add(Resource.fromMap(doc));
    }
    return resources;
  }

  @Override
  public Resource deleteResource(@Nonnull String aId, Map<String, String> aMetadata) {
    Resource resource = getResource(aId);
    if (null == resource) {
      return null;
    }
    boolean found = false;
    try {
      found = deleteDocument(resource.getId().concat(".").concat(Record.RESOURCE_KEY));
    } catch (IOException e) {
      Logger.error("Failed deleting document.", e);
    }
    Logger.trace("Deleted " + aId + " from Elasticsearch");
    if (found) {
      return resource;
    } else {
      return null;
    }
  }

  /**
   * This search method is designed to be able to make use of the complete Elasticsearch query
   * syntax, as described in http://www.elasticsearch.org/guide /en/elasticsearch/reference/current/search-uri-request.html
   * .
   *
   * @param aQueryString A string describing the query
   * @return A resource resembling the result set of resources matching the criteria given in the
   * query string
   */
  @Override
  public ResourceList query(@Nonnull String aQueryString, int aFrom, int aSize, String aSortOrder,
    Map<String, List<String>> aFilters) throws IOException {
    return query(aQueryString, aFrom, aSize, aSortOrder, aFilters, null);
  }

  public ResourceList query(@Nonnull String aQueryString, int aFrom, int aSize, String aSortOrder,
    Map<String, List<String>> aFilters, QueryContext aQueryContext) throws IOException {

    return esQuery(aQueryString, aFrom, aSize, aSortOrder, aFilters, aQueryContext);
  }

  public JsonNode reconcile(@Nonnull String aQuery, int aFrom, int aSize, String aSortOrder,
    Map<String, List<String>> aFilters, QueryContext aQueryContext,
    final Locale aPreferredLocale) throws IOException {
    // remove "words" consisting only of characters that have to be escaped
    aQuery = aQuery.replaceAll("(?<=[ \t\n\r])[\\\\+\\-&|!(){}\\[\\]^/\"~*?:]+(?=[ \t\n\r])", "");
    aQuery = QueryParser.escape(aQuery);
    aQuery = aQuery.replaceAll("([^ ]+)", "$1~");
    aQueryContext.setFetchSource(new String[]{"about.@id", "about.@type", "about.name"});

    ResourceList response = esQuery(aQuery, aFrom, aSize, aSortOrder, aFilters, aQueryContext);
    Iterator<Resource> searchHits = response.getItems().iterator();
    ArrayNode resultItems = new ArrayNode(mJsonNodeFactory);

    while (searchHits.hasNext()) {
      final Resource hit = searchHits.next();
      Map<String, String> match = hit.getAsResource(Record.RESOURCE_KEY).toPointerDict();
      String name = match.get("/name/".concat(aPreferredLocale.getLanguage()));
      ObjectNode item = new ObjectNode(mJsonNodeFactory);
      item.put("id", match.get("/@id"));
      item.put("match", !StringUtils.isEmpty(hit.getAsString("_score"))
        && Double.parseDouble(hit.getAsString("_score")) == 1.0);
      item.put("name", name);
      item.put("score", hit.getAsString("_score"));
      ArrayNode typeArray = new ArrayNode(mJsonNodeFactory);
      typeArray.add(match.get("/@type"));
      item.set("type", typeArray);
      resultItems.add(item);
    }

    ObjectNode result = new ObjectNode(mJsonNodeFactory);
    result.set("result", resultItems);
    return result;
  }

  /**
   * Add a document consisting of a JSON String specified by a given UUID and a given type.
   */
  private void addJson(final String aJsonString, final String aUuid) {
    String uuid = getUrlUuidEncoded(aUuid);
    IndexRequest request = new IndexRequest(mConfig.getIndex(), Record.TYPE,
      (uuid == null ? aUuid : uuid));
    request.source(aJsonString, XContentType.JSON);
    request.setRefreshPolicy(mConfig.getRefreshPolicy());
    // see https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-refresh.html,
    try {
      mConfig.getClient().index(request);
    } catch (IOException | ElasticsearchStatusException e) {
      Logger.error("Failed indexing data to Elasticsearch.", e);
    }
  }

  private String getUrlUuidEncoded(String aUuid) {
    if (isValidUri(aUuid)) {
      try {
        return URLEncoder.encode(aUuid, Charset.defaultCharset().name());
      } catch (UnsupportedEncodingException e) {
        return null;
      }
    } else {
      return aUuid;
    }
  }

  private static boolean isValidUri(String aUri) {
    try {
      new URL(aUri);
    } catch (MalformedURLException mue) {
      return false;
    }
    return true;
  }

  /**
   * Add documents consisting of JSON Strings specified by a given UUID and a given type.
   */
  private void addJsonBulk(final Map<String, String> aJsonStringIdMap) {
    BulkRequest request = new BulkRequest();
    for (Map.Entry<String, String> entry : aJsonStringIdMap.entrySet()) {
      request.add(new IndexRequest(mConfig.getIndex(), Record.TYPE, entry.getKey())
        .source(entry.getValue(), XContentType.JSON));
    }
    request.setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL);
    try {
      BulkResponse bulkResponse = mConfig.getClient().bulk(request);
      if (bulkResponse.hasFailures()) {
        Logger.error(bulkResponse.buildFailureMessage());
      }
    } catch (IOException e) {
      Logger.error("Failed indexing bulk data to Elasticsearch.", e);
    }
  }

  /**
   * get *all* matching documents as one list
   */
  private List<Map<String, Object>> getDocuments(final String aField, final Object aValue)
    throws IOException {
    final int docsPerPage = 1024;
    int count = 0;
    SearchResponse response = null;
    final List<Map<String, Object>> docs = new ArrayList<>();

    SearchRequest searchRequest = new SearchRequest();
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    searchSourceBuilder
      .query(QueryBuilders
        .queryStringQuery(aField.concat(":").concat(QueryParser.escape(aValue.toString()))))
      .size(docsPerPage);
    searchRequest.source(searchSourceBuilder);
    while (response == null || response.getHits().getHits().length != 0) {
      searchSourceBuilder.from(count * docsPerPage);
      response = mConfig.getClient().search(searchRequest);
      for (SearchHit hit : response.getHits().getHits()) {
        docs.add(hit.getSourceAsMap());
      }
      count++;
    }
    return docs;
  }

  /**
   * Get a document of a specified type specified by an identifier.
   *
   * @return the document as Map of String/Object
   */
  private Map<String, Object> getDocument(@Nonnull final String aIdentifier) throws IOException {
    GetRequest request = new GetRequest(mConfig.getIndex(), Record.TYPE, aIdentifier);
    // optionally: request.refresh(true);
    final GetResponse response = mConfig.getClient().get(request);
    return response.getSource();
  }

  private boolean deleteDocument(@Nonnull final String aIdentifier)
    throws IOException {
    DeleteRequest request = new DeleteRequest(mConfig.getIndex(), Record.TYPE, aIdentifier);
    request.setRefreshPolicy(mConfig.getRefreshPolicy());
    // see https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-refresh.html,
    final DeleteResponse response = mConfig.getClient().delete(request);
    return response.status().equals(RestStatus.OK);
  }

  private ResourceList esQuery(@Nonnull final String aQueryString, final int aFrom, final int aSize,
    final String aSortOrder, final Map<String, List<String>> aFilters,
    final QueryContext aQueryContext) throws IOException {

    final SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().from(aFrom);
    processSortOrder(aSortOrder, aQueryString, sourceBuilder);
    final BoolQueryBuilder globalAndFilter = QueryBuilders.boolQuery();
    processFilters(aFilters, globalAndFilter);
    final String[] fieldBoosts = processQueryContext(aQueryContext, sourceBuilder, globalAndFilter);

    QueryBuilder queryBuilder = getQueryBuilder(aQueryString, fieldBoosts);
    FunctionScoreQueryBuilder fqBuilder = getFunctionScoreQueryBuilder(queryBuilder);
    final BoolQueryBuilder bqBuilder = QueryBuilders.boolQuery().filter(globalAndFilter);
    bqBuilder.must(fqBuilder);
    sourceBuilder.query(bqBuilder);

    List<SearchHit> searchHits = new ArrayList<>();
    SearchResponse response;
    float maxScore = 0.0f;
    if (aSize == -1) {
      response = mConfig.getClient().search(
        new SearchRequest(mConfig.getIndex()).source(sourceBuilder)
          .searchType(SearchType.DFS_QUERY_THEN_FETCH)
          .scroll(new TimeValue(60000)));
      maxScore = Math.max(response.getHits().getMaxScore(), maxScore);
      List<SearchHit> nextHits = Arrays.asList(response.getHits().getHits());
      while (nextHits.size() > 0) {
        searchHits.addAll(nextHits);
        SearchScrollRequest searchScrollRequest = new SearchScrollRequest()
          .scrollId(response.getScrollId()).scroll(new TimeValue(60000));
        response = mConfig.getClient().searchScroll(searchScrollRequest);
        JsonNode resultNode = new ObjectMapper().readTree(response.toString());
        Logger.debug(resultNode.toString());
        nextHits = Arrays.asList(response.getHits().getHits());
        maxScore = Math.max(response.getHits().getMaxScore(), maxScore);
      }
    } else {
      sourceBuilder.size(aSize);
      response = mConfig.getClient()
        .search(new SearchRequest(mConfig.getIndex()).source(sourceBuilder));
      searchHits.addAll(Arrays.asList(response.getHits().getHits()));
      maxScore = Math.max(response.getHits().getMaxScore(), maxScore);
    }

    Logger.debug(sourceBuilder.toString());

    List<Resource> resources = new ArrayList<>();
    for (SearchHit hit : searchHits) {
      Resource resource = Resource.fromMap(hit.getSourceAsMap());
      if (!Float.isNaN(hit.getScore())) {
        // Convert ES scoring to score between 0 an 1
        resource.put("_score", hit.getScore() / maxScore);
      }
      resources.add(resource);
    }

    return new ResourceList(resources, response.getHits().getTotalHits(), aQueryString, aFrom,
      aSize, aSortOrder,
      aFilters);
  }

  private FunctionScoreQueryBuilder getFunctionScoreQueryBuilder(QueryBuilder queryBuilder) {
    FieldValueFactorFunctionBuilder fb = ScoreFunctionBuilders.fieldValueFactorFunction(Record.LINK_COUNT);
    return new FunctionScoreQueryBuilder(queryBuilder, fb);
  }

  private QueryBuilder getQueryBuilder(@Nonnull String aQueryString, String[] fieldBoosts) {
    QueryBuilder queryBuilder;
    if (!StringUtils.isEmpty(aQueryString)) {
      Matcher matchesTSC = patternTrailingSpecialChar.matcher(aQueryString);
      if (matchesTSC.find()) {
        // aQueryString ends with ":" or "!" --> escape for Elasticsearch query
        Logger.trace("Modify query: insert escape '\\' in front of trailing special char in ".concat(aQueryString));
        aQueryString = matchesTSC.replaceFirst("$1\\\\$2");
      }
      queryBuilder = QueryBuilders.queryStringQuery(aQueryString).fuzziness(mFuzziness)
        .defaultOperator(Operator.AND);
      if (fieldBoosts != null) {
        // TODO: extract fieldBoost parsing from loop in case
        for (String fieldBoost : fieldBoosts) {
          try {
            ((QueryStringQueryBuilder) queryBuilder).field(fieldBoost.split("\\^")[0],
              Float.parseFloat(fieldBoost.split("\\^")[1]));
          } catch (ArrayIndexOutOfBoundsException e) {
            Logger.trace("Invalid field boost: " + fieldBoost);
          }
        }
      }
    } else {
      queryBuilder = QueryBuilders.matchAllQuery();
    }
    return queryBuilder;
  }

  private void processFilters(Map<String, List<String>> aFilters,
    BoolQueryBuilder globalAndFilter) {
    if (!(null == aFilters)) {
      BoolQueryBuilder aggregationAndFilter = QueryBuilders.boolQuery();
      for (Map.Entry<String, List<String>> entry : aFilters.entrySet()) {
        BoolQueryBuilder orFilterBuilder = QueryBuilders.boolQuery();
        String filterName = entry.getKey();
        for (String filterValue : entry.getValue()) {
          orFilterBuilder.should(buildFilterQuery(filterName, filterValue));
        }
        aggregationAndFilter.must(orFilterBuilder);
      }
      globalAndFilter.must(aggregationAndFilter);
    }
  }

  private QueryBuilder buildFilterQuery(final String aField, final String aValue) {
    final String filterName = aField.endsWith(".GTE")
      ? aField.substring(0, aField.length() - ".GTE".length())
      : aField;

    return aField.endsWith(".GTE")
      ? QueryBuilders.rangeQuery(filterName).gte(aValue)
      : termQuery(filterName, aValue);
  }

  private void processSortOrder(String aSortOrder, String aQueryString,
    SearchSourceBuilder searchBuilder) {
    // Sort by dateCreated if no query string given
    if (StringUtils.isEmpty(aQueryString) && StringUtils.isEmpty(aSortOrder)) {
      aSortOrder = "dateCreated:DESC";
    }
    if (!StringUtils.isEmpty(aSortOrder)) {
      String[] sort = aSortOrder.split(":");
      if (2 == sort.length) {
        searchBuilder
          .sort(sort[0], sort[1].toUpperCase().equals("ASC") ? SortOrder.ASC : SortOrder.DESC);
      } else {
        Logger.trace("Invalid sort string: " + aSortOrder);
      }
    }
  }

  private String[] processQueryContext(
    final QueryContext aQueryContext, final SearchSourceBuilder sourceBuilder,
    final BoolQueryBuilder globalAndFilter) {
    String[] fieldBoosts = null;
    if (null != aQueryContext) {
      sourceBuilder.fetchSource(aQueryContext.getFetchSource(), null);
      for (QueryBuilder contextFilter : aQueryContext.getFilters()) {
        globalAndFilter.must(contextFilter);
      }
      if (aQueryContext.hasFieldBoosts()) {
        fieldBoosts = aQueryContext.getElasticsearchFieldBoosts();
      }
      if (null != aQueryContext.getZoomTopLeft() && null != aQueryContext.getZoomBottomRight()) {
        GeoBoundingBoxQueryBuilder zoomFilter = QueryBuilders
          .geoBoundingBoxQuery("about.location.geo")
          .setCorners(aQueryContext.getZoomTopLeft(), aQueryContext.getZoomBottomRight());
        globalAndFilter.must(zoomFilter);
      }
      if (null != aQueryContext.getPolygonFilter() && !aQueryContext.getPolygonFilter().isEmpty()) {
        GeoPolygonQueryBuilder polygonFilter = QueryBuilders.geoPolygonQuery("about.location.geo",
          aQueryContext.getPolygonFilter());
        globalAndFilter.must(polygonFilter);
      }
    }
    return fieldBoosts;
  }

  public boolean hasIndex(String aIndex) {
    return mConfig.indexExists(aIndex);
  }

  public void deleteIndex(String aIndex) throws IOException {
    mConfig.deleteIndex(aIndex);
  }

  public void createIndex(String aIndex) throws IOException {
    mConfig.createIndex(aIndex);
  }

  public ElasticsearchConfig getConfig() {
    return mConfig;
  }
}
