package services;

import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.RDFNode;
import models.Resource;
import models.TripleCommit;
import play.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by fo on 23.03.16.
 */
public class ResourceIndexer {

  Model mDb;
  String mNamespace;

  private final static String SCOPE_QUERY_TEMPLATE =
    "SELECT DISTINCT ?s1 ?s2 WHERE {" +
    "    ?s1 ?p1 ?s ." +
    "    FILTER (%%1$s) ." +
    "    FILTER(STRSTARTS(STR(?s1), \"%1$s\"))" +
    "    OPTIONAL { ?s2 ?p2 ?s1 . FILTER(STRSTARTS(STR(?s2), \"%1$s\"))} ." +
    "}";

  private final String SCOPE_QUERY;

  public ResourceIndexer(Model aDb, String aNamespace) {

    this.mDb = aDb;
    this.mNamespace = aNamespace;
    SCOPE_QUERY = String.format(SCOPE_QUERY_TEMPLATE, aNamespace);

  }

  public Set<String> getScope(TripleCommit.Diff aDiff) {

    Set<String> scope = new HashSet<>();
    for (TripleCommit.Diff.Line line : aDiff.getLines()) {
      RDFNode subject = line.stmt.getSubject();
      RDFNode object = line.stmt.getObject();
      if (subject.isURIResource() && subject.toString().startsWith(mNamespace)) {
        scope.add(subject.toString());
      }
      if (object.isURIResource() && object.toString().startsWith(mNamespace)) {
        scope.add(object.toString());
      }
    }

    String filter = String.join(" || ", scope.stream().map(id -> "?s = <".concat(id).concat(">"))
      .collect(Collectors.toSet()));

    Logger.debug(String.format(SCOPE_QUERY, filter));

    ResultSet rs = QueryExecutionFactory.create(QueryFactory.create(String.format(SCOPE_QUERY, filter)), mDb)
      .execSelect();
    while (rs.hasNext()) {
      QuerySolution qs = rs.next();
      if (qs.contains("s1")) {
        scope.add(qs.get("s1").toString());
      }
      if (qs.contains("s2")) {
        scope.add(qs.get("s2").toString());
      }
    }

    return scope;

  }

  public Set<Resource> getResources(TripleCommit.Diff aDiff) {

    Set<Resource> resourceToIndex = new HashSet<>();
    Set<String> idsToIndex = this.getScope(aDiff);
    for (String id : idsToIndex) {
      try {
        resourceToIndex.add(ResourceFramer.resourceFromModel(mDb, id));
      } catch (IOException e) {
        Logger.error("Could not read resource from model", e);
      }
    }

    return resourceToIndex;

  }

}
